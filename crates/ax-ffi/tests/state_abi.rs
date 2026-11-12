//! L'état complet d'une assembly exportée dans `SIM_OUT` (ADR-168 D1), exercé par l'ABI comme Java le
//! fera : un corps créé par CREATE_ASSEMBLY, son export demandé par SET_STATE_EXPORT, est collecté ;
//! la section des états suit celles des motopropulseurs, et son bloc se relit.
//!
//! Un seul test, son propre binaire : la session native est globale au processus.

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

mod common;

use ax_model::buffer::{BufferKind, HEADER_BYTES};
use ax_model::dm::commands::{opcode, CommandStreamHeader};
use ax_model::dm::geometry::{Transform, WorldTransform};
use ax_model::dm::handle::Handle;
use ax_model::dm::physics::{BodyBounds, BodyDesc, BodyState, ColliderDesc, ColliderShape};
use axion_native::abi::{
    axion_buffer_acquire, axion_buffer_release, axion_init, axion_shutdown, axion_sim_collect,
    axion_sim_submit, AxionBufferInfo, AxionCollectResult, AXION_OK, AXION_SIDE_SERVER,
};
use common::{creation, Modele};

/// L'empreinte de la definition de la boîte.
const DEFINITION: u64 = 0x0DEF_0000_0000_0120;

/// Rotation de spawn : 90° autour de Z, quaternion `(x, y, z, w)`.
const QUART_DE_TOUR_Z: [f32; 4] = [
    0.0,
    0.0,
    core::f32::consts::FRAC_1_SQRT_2,
    core::f32::consts::FRAC_1_SQRT_2,
];

fn acquire(ctx: u64, kind: BufferKind, min_capacity: u64) -> AxionBufferInfo {
    let mut info = AxionBufferInfo {
        ptr: std::ptr::null_mut(),
        capacity: 0,
        kind: 0,
        generation: 0,
    };
    let code = unsafe { axion_buffer_acquire(ctx, kind.as_u32(), min_capacity, &raw mut info) };
    assert_eq!(code, AXION_OK, "acquire {kind}");
    assert!(!info.ptr.is_null());
    info
}

fn release(ctx: u64, kind: BufferKind) {
    let info = acquire(ctx, kind, 0);
    assert_eq!(
        unsafe { axion_buffer_release(ctx, kind.as_u32(), info.generation) },
        AXION_OK,
        "release {kind}"
    );
}

/// Une boîte non cubique, posée sur l'origine du corps : demi-dimensions
/// (1, 0.25, 0.5), centre local (0, 0.25, 0).
fn boite_posee() -> ColliderDesc {
    let mut local = Transform::identity();
    local.translation = [0.0, 0.25, 0.0];
    ColliderDesc {
        shape: ColliderShape::Box {
            half_extents: [1.0, 0.25, 0.5],
        },
        local,
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

/// Flux `SimIn` : en-tête + une commande CREATE_ASSEMBLY de la boîte au handle (7, 1), spawn
/// tourné.
fn command_stream() -> Vec<u8> {
    let spawn = WorldTransform {
        position: [5.0, 10.0, -2.0],
        rotation: QUART_DE_TOUR_Z,
    };
    let payload = creation(Handle::new(7, 1), 0, spawn, DEFINITION, 0, None);

    let mut stream = CommandStreamHeader::CURRENT_SCHEMA.to_le_bytes().to_vec();
    stream.extend_from_slice(&0u32.to_le_bytes()); // _pad
    stream.extend_from_slice(&opcode::CREATE_ASSEMBLY.to_le_bytes());
    stream.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    stream.extend_from_slice(&payload);
    stream
}

#[test]
fn adr168_sim_out_porte_l_etat_complet_d_une_assembly_exportee() {
    // ADR-168 D1 : SET_STATE_EXPORT demande l'état complet de la boîte ; la section des états suit celles des joints,
    // des roues et des motopropulseurs, et son bloc se relit.
    let mut ctx = 0u64;
    assert_eq!(
        unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut ctx) },
        AXION_OK
    );
    let boite = Modele::seul(vec![boite_posee()], BodyDesc::undeclared(2));
    assert_eq!(boite.inscrire(ctx, DEFINITION), AXION_OK);
    let mut stream = command_stream();
    let mut export = Vec::new();
    export.extend_from_slice(&7u32.to_le_bytes());
    export.extend_from_slice(&1u32.to_le_bytes());
    export.extend_from_slice(&1u32.to_le_bytes());
    stream.extend_from_slice(&opcode::SET_STATE_EXPORT.to_le_bytes());
    stream.extend_from_slice(&(export.len() as u32).to_le_bytes());
    stream.extend_from_slice(&export);
    stream.extend_from_slice(&[0; 4]);
    let info = acquire(ctx, BufferKind::SimIn, stream.len() as u64);
    let view = unsafe {
        std::slice::from_raw_parts_mut(info.ptr, usize::try_from(info.capacity).unwrap())
    };
    view[HEADER_BYTES..HEADER_BYTES + stream.len()].copy_from_slice(&stream);
    assert_eq!(
        unsafe { axion_sim_submit(ctx, 1, 2, 0) },
        AXION_OK,
        "submit"
    );
    assert_eq!(
        unsafe { axion_buffer_release(ctx, BufferKind::SimIn.as_u32(), info.generation) },
        AXION_OK
    );
    let mut result = AxionCollectResult::default();
    assert_eq!(
        unsafe { axion_sim_collect(ctx, u64::MAX, &raw mut result) },
        AXION_OK
    );
    let count = result.state_count as usize;

    let out = acquire(ctx, BufferKind::SimOut, 0);
    let out_view =
        unsafe { std::slice::from_raw_parts(out.ptr, usize::try_from(out.capacity).unwrap()) };
    // Après les états et les emprises : les joints, les roues et les motopropulseurs, vides ici (8 octets chacun).
    let at = HEADER_BYTES + count * (BodyState::BYTES + BodyBounds::BYTES) + 3 * 8;
    let etats = ax_model::dm::state::AssemblyStateReport::read_section(&out_view[at..])
        .expect("section lisible");
    assert_eq!(etats.len(), 1, "la boîte exportée");
    assert_eq!(etats[0].handle, Handle::new(7, 1));
    assert_eq!(etats[0].status, ax_model::dm::state::state_status::OK);
    let bloc = ax_physics::state::AssemblyState::decode(&etats[0].bytes).expect("bloc lisible");
    assert_eq!(bloc.bodies.len(), 1);
    assert!(
        (bloc.bodies[0].position.y - 10.0).abs() < 0.5,
        "près de son apparition : {:?}",
        bloc.bodies[0]
    );

    release(ctx, BufferKind::SimOut);
    acquire(ctx, BufferKind::Events, 0);
    release(ctx, BufferKind::Events);
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK, "arrêt équilibré");
}
