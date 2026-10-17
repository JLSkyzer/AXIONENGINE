//! ADR-149 par l'ABI : `CREATE_ASSEMBLY` porte l'index de matériau de chaque collider dans sa
//! section `PHYS` ; `SET_PHYSICS_MATERIALS` le résout, même arrivé dans le même flux — la
//! frontière crée les assemblies d'abord, puis applique le reste, et une table reçue se propage
//! aux corps déjà posés.
//!
//! Son propre binaire : la session native est globale au processus.

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

use ax_asset::a3d::encode_colliders;
use ax_model::buffer::{BufferHeader, BufferKind, HEADER_BYTES};
use ax_model::dm::commands::{opcode, CommandStreamHeader, CreateAssembly};
use ax_model::dm::geometry::Transform;
use ax_model::dm::handle::Handle;
use ax_model::dm::physics::{combine, BodyDesc, ColliderDesc, ColliderShape, PhysicsMaterial};
use ax_physics::ContactMaterial;
use axion_native::abi::{
    axion_buffer_acquire, axion_buffer_release, axion_init, axion_shutdown, axion_sim_cancel,
    axion_sim_collect, axion_sim_submit, AxionBufferInfo, AxionCollectResult, AXION_OK,
    AXION_SIDE_SERVER,
};
use axion_native::context;

const ASSEMBLY: Handle = Handle {
    index: 7,
    generation: 1,
};

fn empty_info() -> AxionBufferInfo {
    AxionBufferInfo {
        ptr: std::ptr::null_mut(),
        capacity: 0,
        kind: 0,
        generation: 0,
    }
}

fn release_buffer(ctx: u64, kind: BufferKind) {
    let mut info = empty_info();
    unsafe {
        axion_buffer_acquire(ctx, kind.as_u32(), 0, &raw mut info);
        axion_buffer_release(ctx, kind.as_u32(), info.generation);
    }
}

/// Un matériau DM-07 au frottement et au mode donnés, le reste à zéro.
fn materiau(friction: f32, mode: u8) -> PhysicsMaterial {
    PhysicsMaterial {
        friction,
        friction_combine: mode,
        ..PhysicsMaterial::read_le(&[0; PhysicsMaterial::BYTES]).expect("120 octets")
    }
}

/// Ajoute une commande au flux, payload rembourré à 8 octets.
fn push(stream: &mut Vec<u8>, op: u32, payload: &[u8]) {
    stream.extend_from_slice(&op.to_le_bytes());
    stream.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    stream.extend_from_slice(payload);
    while !stream.len().is_multiple_of(8) {
        stream.push(0);
    }
}

/// `CREATE_ASSEMBLY` d'une boîte dont le collider porte l'index de matériau `material`.
fn create_assembly(material: u16) -> Vec<u8> {
    let collider = ColliderDesc {
        shape: ColliderShape::Box {
            half_extents: [0.5, 0.5, 0.5],
        },
        local: Transform::identity(),
        material,
        _pad: 0,
        group: u32::MAX,
        mask: u32::MAX,
        flags: 0,
        density: 1000.0,
        damage_zone: u16::MAX,
        part: u16::MAX,
        region: u16::MAX,
        _pad2: 0,
        hull_points_offset: 0,
        hull_points_count: 0,
    };
    let phys = encode_colliders(&[collider], &[], &[]).expect("encodage PHYS");
    let mut payload = Vec::new();
    payload.extend_from_slice(&ASSEMBLY.index.to_le_bytes());
    payload.extend_from_slice(&ASSEMBLY.generation.to_le_bytes());
    payload.extend_from_slice(&0u64.to_le_bytes()); // dimension
    for value in [5.0f64, 10.0, -2.0] {
        payload.extend_from_slice(&value.to_le_bytes());
    }
    for value in [0.0f32, 0.0, 0.0, 1.0] {
        payload.extend_from_slice(&value.to_le_bytes());
    }
    payload.push(2); // dynamique
    payload.resize(CreateAssembly::BYTES, 0);
    BodyDesc::undeclared(2).write_le(&mut payload);
    payload.extend_from_slice(&phys);
    payload
}

/// `SET_PHYSICS_MATERIALS` d'une table.
fn set_materials(materials: &[PhysicsMaterial]) -> Vec<u8> {
    let mut payload = (materials.len() as u32).to_le_bytes().to_vec();
    payload.extend_from_slice(&0u32.to_le_bytes());
    for material in materials {
        material.write_le(&mut payload);
    }
    payload
}

/// Écrit `commands` dans `SIM_IN`, soumet et collecte un tick.
fn submit(ctx: u64, commands: &[(u32, Vec<u8>)]) {
    let mut stream = CommandStreamHeader::CURRENT_SCHEMA.to_le_bytes().to_vec();
    stream.extend_from_slice(&0u32.to_le_bytes());
    for (op, payload) in commands {
        push(&mut stream, *op, payload);
    }
    let mut info = empty_info();
    let code = unsafe {
        axion_buffer_acquire(
            ctx,
            BufferKind::SimIn.as_u32(),
            stream.len() as u64,
            &raw mut info,
        )
    };
    assert_eq!(code, AXION_OK, "acquire SimIn");
    let view = unsafe {
        std::slice::from_raw_parts_mut(info.ptr, usize::try_from(info.capacity).unwrap())
    };
    BufferHeader {
        kind: BufferKind::SimIn,
        generation: info.generation,
        schema_version: 0,
        payload_len: stream.len() as u64,
        element_count: commands.len() as u32,
        crc32c: 0,
    }
    .write(view);
    view[HEADER_BYTES..HEADER_BYTES + stream.len()].copy_from_slice(&stream);
    let count = u32::try_from(commands.len()).unwrap();
    assert_eq!(
        unsafe { axion_sim_submit(ctx, 1, count, 0) },
        AXION_OK,
        "submit"
    );
    let mut result = AxionCollectResult::default();
    assert_eq!(
        unsafe { axion_sim_collect(ctx, u64::MAX, &raw mut result) },
        AXION_OK,
        "collect"
    );
}

/// Les matériaux de contact des colliders de l'assembly de test.
fn materiaux(ctx: u64) -> Vec<ContactMaterial> {
    context::with(ctx, false, |session| {
        let physics = session.physics();
        let (dimension, body) = physics.assembly_body(ASSEMBLY).expect("assembly créée");
        physics
            .world_mut(dimension)
            .expect("dimension")
            .body_materials(body)
    })
    .unwrap()
}

#[test]
fn adr149_une_table_du_meme_flux_resout_les_colliders_crees() {
    let mut ctx = 0u64;
    let code = unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut ctx) };
    assert_eq!(code, AXION_OK, "init");

    let table = [materiau(0.6, combine::AVERAGE), materiau(0.2, combine::MIN)];
    // La table suit la création dans le flux : la frontière crée d'abord, puis applique la table,
    // qui résout le collider déjà posé.
    submit(
        ctx,
        &[
            (opcode::CREATE_ASSEMBLY, create_assembly(1)),
            (opcode::SET_PHYSICS_MATERIALS, set_materials(&table)),
        ],
    );
    assert_eq!(
        materiaux(ctx),
        vec![ContactMaterial::from_table(&table[1], 1)]
    );

    // Un rechargement : l'index 1 change de frottement, le corps posé suit.
    let rechargee = [table[0], materiau(0.9, combine::MAX)];
    submit(
        ctx,
        &[(opcode::SET_PHYSICS_MATERIALS, set_materials(&rechargee))],
    );
    assert_eq!(
        materiaux(ctx),
        vec![ContactMaterial::from_table(&rechargee[1], 1)]
    );

    assert_eq!(unsafe { axion_sim_cancel(ctx) }, AXION_OK, "cancel");
    release_buffer(ctx, BufferKind::SimIn);
    release_buffer(ctx, BufferKind::SimOut);
    release_buffer(ctx, BufferKind::Events);
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK, "shutdown");
}
