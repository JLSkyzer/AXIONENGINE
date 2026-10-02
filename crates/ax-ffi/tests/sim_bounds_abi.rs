//! Emprise des corps dans `SIM_OUT` (ADR-120), exercée par l'ABI comme Java le
//! fera : un corps créé par CREATE_ASSEMBLY, tourné et décalé, est collecté ; son
//! emprise suit les `BodyState` dans le même lot, et l'en-tête annonce le schéma 1.
//!
//! Un seul test, son propre binaire : la session native est globale au processus.

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

use ax_asset::a3d::encode_colliders;
use ax_model::buffer::{BufferHeader, BufferKind, HEADER_BYTES};
use ax_model::dm::commands::{opcode, CommandStreamHeader, CreateAssembly};
use ax_model::dm::geometry::Transform;
use ax_model::dm::physics::{BodyBounds, BodyState, ColliderDesc, ColliderShape};
use axion_native::abi::{
    axion_buffer_acquire, axion_buffer_release, axion_init, axion_shutdown, axion_sim_collect,
    axion_sim_submit, AxionBufferInfo, AxionCollectResult, AXION_OK, AXION_SIDE_SERVER,
};

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

/// Flux `SimIn` : en-tête + une commande CREATE_ASSEMBLY, spawn tourné.
fn command_stream() -> Vec<u8> {
    let phys = encode_colliders(&[boite_posee()], &[], &[]).expect("encodage PHYS");

    let mut header = Vec::new();
    header.extend_from_slice(&7u32.to_le_bytes()); // handle.index
    header.extend_from_slice(&1u32.to_le_bytes()); // handle.generation
    header.extend_from_slice(&0u64.to_le_bytes()); // dimension
    for value in [5.0f64, 10.0, -2.0] {
        header.extend_from_slice(&value.to_le_bytes()); // spawn.position
    }
    for value in QUART_DE_TOUR_Z {
        header.extend_from_slice(&value.to_le_bytes()); // spawn.rotation
    }
    header.push(2); // body_kind : dynamique
    header.resize(CreateAssembly::BYTES, 0);

    let mut payload = header;
    payload.extend_from_slice(&phys);

    let mut stream = CommandStreamHeader::CURRENT_SCHEMA.to_le_bytes().to_vec();
    stream.extend_from_slice(&0u32.to_le_bytes()); // _pad
    stream.extend_from_slice(&opcode::CREATE_ASSEMBLY.to_le_bytes());
    stream.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    stream.extend_from_slice(&payload);
    stream
}

#[test]
fn sim_out_porte_l_emprise_apres_les_etats() {
    let mut ctx = 0u64;
    let code = unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut ctx) };
    assert_eq!(code, AXION_OK, "init");

    // CREATE_ASSEMBLY, comme NativeSimulation l'écrit : charge après l'en-tête
    // pré-rempli par le natif, puis submit, puis release.
    let stream = command_stream();
    let info = acquire(ctx, BufferKind::SimIn, stream.len() as u64);
    let view = unsafe {
        std::slice::from_raw_parts_mut(info.ptr, usize::try_from(info.capacity).unwrap())
    };
    view[HEADER_BYTES..HEADER_BYTES + stream.len()].copy_from_slice(&stream);
    assert_eq!(
        unsafe { axion_sim_submit(ctx, 1, 1, 0) },
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
        AXION_OK,
        "collect"
    );
    let count = result.state_count as usize;
    assert_eq!(count, 1, "un corps rapporté");

    // L'en-tête annonce le schéma 1 de SIM_OUT : les emprises suivent les états.
    let out = acquire(ctx, BufferKind::SimOut, 0);
    let out_view =
        unsafe { std::slice::from_raw_parts(out.ptr, usize::try_from(out.capacity).unwrap()) };
    let header = BufferHeader::read(out_view).expect("en-tête SIM_OUT lisible");
    assert_eq!(header.kind, BufferKind::SimOut);
    assert_eq!(header.schema_version, 1, "schéma de SIM_OUT (ADR-120)");
    assert!(
        out_view.len() >= HEADER_BYTES + count * (BodyState::BYTES + BodyBounds::BYTES),
        "la charge couvre états et emprises"
    );

    // État au rang 0 : le corps créé, à sa poignée.
    let index = u32::from_le_bytes(out_view[HEADER_BYTES..HEADER_BYTES + 4].try_into().unwrap());
    assert_eq!(index, 7, "poignée du corps");

    // Emprise au rang 0, après les `count` états : la boîte (1, 0.25, 0.5) centrée
    // en (0, 0.25, 0), tournée de 90° autour de Z — demi-dimensions monde
    // (0.25, 1, 0.5), centre (-0.25, 0, 0).
    let at = HEADER_BYTES + count * BodyState::BYTES;
    let bounds = BodyBounds::read_le(out_view[at..at + BodyBounds::BYTES].try_into().unwrap());
    let attendu = BodyBounds {
        min: [-0.5, -1.0, -0.5],
        max: [0.0, 1.0, 0.5],
    };
    for axis in 0..3 {
        assert!(
            (bounds.min[axis] - attendu.min[axis]).abs() < 1.0e-5
                && (bounds.max[axis] - attendu.max[axis]).abs() < 1.0e-5,
            "emprise {bounds:?}, attendue {attendu:?}"
        );
    }

    // Les autres kinds n'ont pas de schéma versionné : 0.
    let events = acquire(ctx, BufferKind::Events, 0);
    let events_view = unsafe {
        std::slice::from_raw_parts(events.ptr, usize::try_from(events.capacity).unwrap())
    };
    assert_eq!(
        BufferHeader::read(events_view)
            .expect("en-tête EVENTS")
            .schema_version,
        0
    );

    release(ctx, BufferKind::SimOut);
    release(ctx, BufferKind::Events);
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK, "arrêt équilibré");
}
