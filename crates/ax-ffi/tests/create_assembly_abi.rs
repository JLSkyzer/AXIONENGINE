//! CREATE_ASSEMBLY d'IF-03, exercé par l'ABI comme Java le fera (C-32, Option A,
//! ADR-115) : un flux `SimIn` porte une commande CREATE_ASSEMBLY dont le payload
//! est l'en-tête `CreateAssembly` suivi des octets de la section `PHYS`. Le natif
//! décode les colliders, crée un corps, et le corps apparaît dans le `collect`.
//! Depuis ADR-130, la description DM-08 du corps suit l'en-tête ; invalide, ou d'un
//! autre type de corps que l'en-tête, l'assembly est refusée (T-312). Qu'elle soit
//! appliquée se voit dans `body_desc_abi`.
//!
//! Son propre binaire : la session native est globale au processus, et les tests
//! s'y succèdent sous un verrou.

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

use ax_asset::a3d::encode_colliders;
use ax_model::buffer::{BufferHeader, BufferKind, HEADER_BYTES};
use ax_model::dm::commands::{opcode, CommandStreamHeader, CreateAssembly};
use ax_model::dm::geometry::Transform;
use ax_model::dm::physics::{BodyDesc, ColliderDesc, ColliderShape};
use axion_native::abi::{
    axion_buffer_acquire, axion_buffer_release, axion_init, axion_shutdown, axion_sim_cancel,
    axion_sim_collect, axion_sim_submit, AxionBufferInfo, AxionCollectResult, AXION_OK,
    AXION_SIDE_SERVER,
};

static CONTEXTE: std::sync::Mutex<()> = std::sync::Mutex::new(());

fn release_buffer(ctx: u64, kind: BufferKind) {
    let mut info = AxionBufferInfo {
        ptr: std::ptr::null_mut(),
        capacity: 0,
        kind: 0,
        generation: 0,
    };
    unsafe {
        axion_buffer_acquire(ctx, kind.as_u32(), 0, &raw mut info);
        axion_buffer_release(ctx, kind.as_u32(), info.generation);
    }
}

/// Une boîte de collider, densité de l'eau, sans référence.
fn box_collider() -> ColliderDesc {
    ColliderDesc {
        shape: ColliderShape::Box {
            half_extents: [0.5, 0.5, 0.5],
        },
        local: Transform::identity(),
        material: u16::MAX,
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
    }
}

/// Construit le flux `SimIn` : en-tête + une commande CREATE_ASSEMBLY (en-tête
/// `CreateAssembly`, description DM-08 du corps, octets `PHYS`).
fn command_stream(desc: BodyDesc) -> Vec<u8> {
    let phys = encode_colliders(&[box_collider()], &[], &[]).expect("encodage PHYS");

    // En-tête CreateAssembly (64 octets).
    let mut payload = Vec::new();
    payload.extend_from_slice(&7u32.to_le_bytes()); // handle.index
    payload.extend_from_slice(&1u32.to_le_bytes()); // handle.generation
    payload.extend_from_slice(&0u64.to_le_bytes()); // dimension
    for value in [5.0f64, 10.0, -2.0] {
        payload.extend_from_slice(&value.to_le_bytes()); // spawn.position
    }
    for value in [0.0f32, 0.0, 0.0, 1.0] {
        payload.extend_from_slice(&value.to_le_bytes()); // spawn.rotation
    }
    payload.push(2); // body_kind : dynamique
    payload.resize(CreateAssembly::BYTES, 0); // remplissage

    // ADR-130 : la description DM-08 du corps, puis les octets PHYS.
    desc.write_le(&mut payload);
    assert_eq!(payload.len(), CreateAssembly::PHYS_OFFSET);
    payload.extend_from_slice(&phys);

    let mut stream = CommandStreamHeader::CURRENT_SCHEMA.to_le_bytes().to_vec();
    stream.extend_from_slice(&0u32.to_le_bytes()); // _pad
    stream.extend_from_slice(&opcode::CREATE_ASSEMBLY.to_le_bytes());
    stream.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    stream.extend_from_slice(&payload);
    stream
}

/// Soumet le flux dans une session neuve, collecte un tick, et rend le nombre de
/// corps collectés et la position monde du premier, s'il y en a un.
fn soumettre(stream: &[u8]) -> (u32, Option<[f64; 3]>) {
    let _contexte = CONTEXTE
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);

    let mut ctx = 0u64;
    let code = unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut ctx) };
    assert_eq!(code, AXION_OK, "init");

    // Écrit le flux dans SimIn (en-tête de tampon + payload).
    let mut info = AxionBufferInfo {
        ptr: std::ptr::null_mut(),
        capacity: 0,
        kind: 0,
        generation: 0,
    };
    let code = unsafe {
        axion_buffer_acquire(
            ctx,
            BufferKind::SimIn.as_u32(),
            stream.len() as u64,
            &raw mut info,
        )
    };
    assert_eq!(code, AXION_OK, "acquire SimIn");
    assert!(!info.ptr.is_null());
    let view = unsafe {
        std::slice::from_raw_parts_mut(info.ptr, usize::try_from(info.capacity).unwrap())
    };
    let header = BufferHeader {
        kind: BufferKind::SimIn,
        generation: info.generation,
        schema_version: 0,
        payload_len: stream.len() as u64,
        element_count: 1,
        crc32c: 0,
    };
    header.write(view);
    view[HEADER_BYTES..HEADER_BYTES + stream.len()].copy_from_slice(stream);

    // Soumet la commande, puis collecte.
    assert_eq!(
        unsafe { axion_sim_submit(ctx, 1, 1, 0) },
        AXION_OK,
        "submit"
    );
    let mut result = AxionCollectResult::default();
    assert_eq!(
        unsafe { axion_sim_collect(ctx, u64::MAX, &raw mut result) },
        AXION_OK,
        "collect"
    );

    // Position monde du premier corps, relue dans SIM_OUT (BodyState de 80
    // octets, position f64 à l'offset 8).
    let mut position = None;
    if result.state_count > 0 {
        let mut out = AxionBufferInfo {
            ptr: std::ptr::null_mut(),
            capacity: 0,
            kind: 0,
            generation: 0,
        };
        let code =
            unsafe { axion_buffer_acquire(ctx, BufferKind::SimOut.as_u32(), 0, &raw mut out) };
        assert_eq!(code, AXION_OK, "acquire SimOut");
        let out_view =
            unsafe { std::slice::from_raw_parts(out.ptr, usize::try_from(out.capacity).unwrap()) };
        let f = |offset: usize| {
            f64::from_le_bytes(
                out_view[HEADER_BYTES + offset..HEADER_BYTES + offset + 8]
                    .try_into()
                    .unwrap(),
            )
        };
        position = Some([f(8), f(16), f(24)]);
    }

    assert_eq!(unsafe { axion_sim_cancel(ctx) }, AXION_OK, "cancel");
    release_buffer(ctx, BufferKind::SimIn);
    release_buffer(ctx, BufferKind::SimOut);
    release_buffer(ctx, BufferKind::Events);
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK, "shutdown");
    (result.state_count, position)
}

#[test]
fn create_assembly_cree_un_corps_visible_au_collect() {
    let (count, position) = soumettre(&command_stream(BodyDesc::undeclared(2)));
    // Le corps a été créé depuis les colliders : il apparaît dans l'état collecté,
    // à sa pose de spawn (origine à zéro).
    assert_eq!(count, 1, "un corps créé depuis CREATE_ASSEMBLY");
    let [x, _, _] = position.expect("position du corps");
    assert!((x - 5.0).abs() < 1.0e-3, "corps à sa pose de spawn, x={x}");
}

#[test]
fn t312_une_description_invalide_ou_d_un_autre_type_est_refusee() {
    // Corps statique décrit, en-tête dynamique : l'assembly est abandonnée.
    let (count, _) = soumettre(&command_stream(BodyDesc::undeclared(0)));
    assert_eq!(count, 0, "type de corps différent de l'en-tête : refusé");
    // Masse négative : refusée, sans corps à moitié formé.
    let negative = BodyDesc {
        mass: -1.0,
        ..BodyDesc::undeclared(2)
    };
    let (count, _) = soumettre(&command_stream(negative));
    assert_eq!(count, 0, "description invalide : refusée");
}
