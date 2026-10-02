//! Géométrie de debug par l'ABI (C-67, ADR-121), comme Java la demandera : un corps
//! créé par CREATE_ASSEMBLY est tracé dans `DEBUG` en repère du corps ; refus nets,
//! budget, dimension inconnue, arrêt équilibré.
//!
//! Un seul test, son propre binaire : la session native est globale au processus.

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

use ax_asset::a3d::encode_colliders;
use ax_model::buffer::{BufferHeader, BufferKind, HEADER_BYTES};
use ax_model::dm::commands::{opcode, CommandStreamHeader, CreateAssembly};
use ax_model::dm::debug::{debug_flags, DebugBody, DebugSegment, DEBUG_HEADER_BYTES};
use ax_model::dm::geometry::Transform;
use ax_model::dm::handle::Handle;
use ax_model::dm::physics::{ColliderDesc, ColliderShape};
use axion_native::abi::{
    axion_buffer_acquire, axion_buffer_release, axion_debug_fill, axion_init, axion_shutdown,
    axion_sim_collect, axion_sim_submit, AxionBufferInfo, AxionCollectResult,
    AXION_E_INVALID_BUFFER, AXION_OK, AXION_OVERLAY_COLLIDERS, AXION_SIDE_SERVER,
};
use std::collections::BTreeSet;

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

/// Boîte (1, 0.25, 0.5) posée sur l'origine du corps : centre local (0, 0.25, 0).
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

/// Flux `SimIn` : une commande CREATE_ASSEMBLY, corps tourné en dimension 0.
fn command_stream() -> Vec<u8> {
    let phys = encode_colliders(&[boite_posee()], &[], &[]).expect("encodage PHYS");
    let mut header = Vec::new();
    header.extend_from_slice(&7u32.to_le_bytes()); // handle.index
    header.extend_from_slice(&1u32.to_le_bytes()); // handle.generation
    header.extend_from_slice(&0u64.to_le_bytes()); // dimension
    for value in [5.0f64, 10.0, -2.0] {
        header.extend_from_slice(&value.to_le_bytes()); // spawn.position
    }
    // Spawn tourné de 90° autour de Z : le tracé, en repère du corps, n'en dépend pas.
    for value in [
        0.0f32,
        0.0,
        core::f32::consts::FRAC_1_SQRT_2,
        core::f32::consts::FRAC_1_SQRT_2,
    ] {
        header.extend_from_slice(&value.to_le_bytes()); // spawn.rotation
    }
    header.push(2); // body_kind : dynamique
    header.resize(CreateAssembly::BYTES, 0);
    let mut payload = header;
    payload.extend_from_slice(&phys);

    let mut stream = CommandStreamHeader::CURRENT_SCHEMA.to_le_bytes().to_vec();
    stream.extend_from_slice(&0u32.to_le_bytes());
    stream.extend_from_slice(&opcode::CREATE_ASSEMBLY.to_le_bytes());
    stream.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    stream.extend_from_slice(&payload);
    stream
}

/// Ce qu'un appel a déposé dans `DEBUG`.
struct Depot {
    flags: u32,
    omitted: u32,
    bodies: Vec<DebugBody>,
    segments: Vec<DebugSegment>,
}

/// Appelle `axion_debug_fill` et relit `DEBUG` d'après la disposition d'ADR-121.
fn fill(ctx: u64, mask: u64, dimension: u64, max_segments: u32) -> Result<Depot, i32> {
    let mut size = 0u64;
    let code = unsafe {
        axion_debug_fill(
            ctx,
            mask,
            dimension,
            6.0,
            11.0,
            -2.0,
            max_segments,
            &raw mut size,
        )
    };
    if code != AXION_OK {
        return Err(code);
    }
    let info = acquire(ctx, BufferKind::Debug, 0);
    let view =
        unsafe { std::slice::from_raw_parts(info.ptr, usize::try_from(info.capacity).unwrap()) };
    let header = BufferHeader::read(view).expect("en-tête DEBUG lisible");
    assert_eq!(header.kind, BufferKind::Debug);
    assert_eq!(header.schema_version, 1, "schéma de DEBUG (ADR-121)");
    let payload = &view[HEADER_BYTES..HEADER_BYTES + usize::try_from(size).unwrap()];
    let u32_at = |at: usize| u32::from_le_bytes(payload[at..at + 4].try_into().unwrap());
    let (body_count, segment_count) = (u32_at(0) as usize, u32_at(4) as usize);
    assert_eq!(
        payload.len(),
        DEBUG_HEADER_BYTES + body_count * DebugBody::BYTES + segment_count * DebugSegment::BYTES,
        "taille annoncée = dénombrements"
    );
    let bodies = (0..body_count)
        .map(|i| {
            let at = DEBUG_HEADER_BYTES + i * DebugBody::BYTES;
            DebugBody::read_le(payload[at..at + DebugBody::BYTES].try_into().unwrap())
        })
        .collect();
    let segments_at = DEBUG_HEADER_BYTES + body_count * DebugBody::BYTES;
    let segments = (0..segment_count)
        .map(|i| {
            let at = segments_at + i * DebugSegment::BYTES;
            DebugSegment::read_le(payload[at..at + DebugSegment::BYTES].try_into().unwrap())
        })
        .collect();
    let depot = Depot {
        flags: u32_at(8),
        omitted: u32_at(12),
        bodies,
        segments,
    };
    assert_eq!(
        unsafe { axion_buffer_release(ctx, BufferKind::Debug.as_u32(), info.generation) },
        AXION_OK
    );
    Ok(depot)
}

#[test]
fn debug_porte_les_aretes_des_colliders_en_repere_du_corps() {
    let mut ctx = 0u64;
    assert_eq!(
        unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut ctx) },
        AXION_OK,
        "init"
    );

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

    // Le corps créé, tracé par ses 12 arêtes, aux coins de sa boîte en repère du corps.
    let depot = fill(ctx, AXION_OVERLAY_COLLIDERS, 0, u32::MAX).expect("tracé");
    assert_eq!(depot.flags, 0);
    assert_eq!(depot.omitted, 0);
    assert_eq!(depot.bodies.len(), 1);
    let corps = depot.bodies[0];
    assert_eq!(corps.handle, Handle::new(7, 1));
    assert_eq!((corps.first_segment, corps.segment_count), (0, 12));
    let arrondi = |p: [f32; 3]| p.map(|c| (f64::from(c) * 1000.0).round() as i64);
    let extremites: BTreeSet<[i64; 3]> = depot
        .segments
        .iter()
        .flat_map(|s| [arrondi(s.a), arrondi(s.b)])
        .collect();
    let mut coins = BTreeSet::new();
    for x in [-1000, 1000] {
        for y in [0, 500] {
            for z in [-500, 500] {
                coins.insert([x, y, z]);
            }
        }
    }
    assert_eq!(extremites, coins, "coins de la boîte, en repère du corps");

    // Budget insuffisant : rien n'est coupé, le corps est omis et l'en-tête le dit.
    let tronque = fill(ctx, AXION_OVERLAY_COLLIDERS, 0, 5).expect("tracé tronqué");
    assert!(tronque.bodies.is_empty() && tronque.segments.is_empty());
    assert_eq!(tronque.flags, debug_flags::TRUNCATED);
    assert_eq!(tronque.omitted, 1);

    // Dimension sans corps : un dépôt vide, valide.
    let vide = fill(ctx, AXION_OVERLAY_COLLIDERS, 99, u32::MAX).expect("dimension vide");
    assert!(vide.bodies.is_empty());
    assert_eq!((vide.flags, vide.omitted), (0, 0));

    // Refus nets : masque vide, overlay non pris en charge (aabb, rang 1), caméra non
    // finie, sortie nulle.
    assert_eq!(
        fill(ctx, 0, 0, u32::MAX).err(),
        Some(AXION_E_INVALID_BUFFER)
    );
    assert_eq!(
        fill(ctx, 1 << 1, 0, u32::MAX).err(),
        Some(AXION_E_INVALID_BUFFER)
    );
    let mut size = 0u64;
    assert_eq!(
        unsafe {
            axion_debug_fill(
                ctx,
                AXION_OVERLAY_COLLIDERS,
                0,
                f64::NAN,
                0.0,
                0.0,
                10,
                &raw mut size,
            )
        },
        AXION_E_INVALID_BUFFER
    );
    assert_eq!(
        unsafe {
            axion_debug_fill(
                ctx,
                AXION_OVERLAY_COLLIDERS,
                0,
                0.0,
                0.0,
                0.0,
                10,
                std::ptr::null_mut(),
            )
        },
        AXION_E_INVALID_BUFFER
    );

    release(ctx, BufferKind::SimOut);
    release(ctx, BufferKind::Events);
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK, "arrêt équilibré");
}
