//! ADR-155, tranche 3b : l'animation côté serveur (R-1430, T-360).
//!
//! Une assembly dont un collider est porté par un node animé : le serveur joue son clip, et
//! l'emprise du corps suit le collider. Une autre, dont le node animé ne porte aucun collider : le
//! serveur ne l'anime pas, et ne l'échantillonne jamais.
//!
//! Un seul test, son propre binaire : la session native est globale au processus.

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

use ax_asset::a3d::{encode_animations, encode_colliders, encode_nodes, AnimationTable};
use ax_model::buffer::{BufferHeader, BufferKind, HEADER_BYTES};
use ax_model::dm::animation::{anim_action, channel, interp, AnimationDesc, TrackDesc};
use ax_model::dm::commands::{
    create_assembly_payload_full, opcode, AssemblyBodyEntry, CommandStreamHeader, CreateAssembly,
};
use ax_model::dm::geometry::{Transform, WorldTransform};
use ax_model::dm::handle::Handle;
use ax_model::dm::physics::{BodyDesc, ColliderDesc, ColliderShape};
use ax_model::dm::scene::{name_hash, NodeDesc, NONE_U16, NONE_U32, NO_PARENT};
use axion_native::abi::{
    axion_buffer_acquire, axion_buffer_release, axion_init, axion_shutdown, axion_sim_collect,
    axion_sim_submit, AxionBufferInfo, AxionCollectResult, AXION_OK, AXION_SIDE_SERVER,
};

const DEMI_RACINE: f32 = core::f32::consts::FRAC_1_SQRT_2;

/// Type de corps cinématique : rapporté par la simulation, et immobile sans ordre.
const CINEMATIQUE: u8 = 1;

fn node(name: &str, parent: u32, collider: u32) -> NodeDesc {
    NodeDesc {
        name_hash: name_hash(name),
        parent,
        local: Transform::identity(),
        flags: 0,
        mesh: NONE_U32,
        collider,
        bone: NONE_U32,
        part: NONE_U16,
        region: NONE_U16,
        lod_mask: 0xFF,
        state: 0,
        mesh_count: 0,
    }
}

fn boite(x: f32) -> ColliderDesc {
    ColliderDesc {
        shape: ColliderShape::Box {
            half_extents: [0.5, 0.5, 0.5],
        },
        local: Transform {
            translation: [x, 0.0, 0.0],
            ..Transform::identity()
        },
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

/// `tourner` : le node 1 d'un quart de tour autour de z, en une seconde, linéairement.
fn clips() -> Vec<u8> {
    encode_animations(&AnimationTable {
        animations: vec![AnimationDesc {
            name_hash: name_hash("tourner"),
            duration: 1.0,
            track_offset: 0,
            track_count: 1,
            flags: 0,
        }],
        names: vec!["tourner".to_owned()],
        tracks: vec![TrackDesc {
            target_node: 1,
            channel: channel::ROTATION,
            interp: interp::LINEAR,
            _pad: [0; 2],
            key_offset: 0,
            key_count: 2,
        }],
        keys: vec![
            0.0,
            0.0,
            0.0,
            0.0,
            1.0,
            1.0,
            0.0,
            0.0,
            DEMI_RACINE,
            DEMI_RACINE,
        ],
    })
    .expect("ANIM")
}

/// Une assembly cinématique : la base porte une boîte à l'origine ; le bras, enfant de la base,
/// porte une boîte à 2 blocs en x si `bras_porte` — sinon il ne porte rien, et seul le node est
/// animé.
fn creation(index: u32, bras_porte: bool) -> Vec<u8> {
    let nodes = [
        node("base", NO_PARENT, 0),
        node("bras", 0, if bras_porte { 1 } else { NONE_U32 }),
    ];
    let names = ["base".to_owned(), "bras".to_owned()];
    let colliders: Vec<ColliderDesc> = if bras_porte {
        vec![boite(0.0), boite(2.0)]
    } else {
        vec![boite(0.0)]
    };
    let header = CreateAssembly {
        handle: Handle::new(index, 1),
        dimension: 0,
        spawn: WorldTransform {
            position: [0.0, 64.0, 0.0],
            rotation: [0.0, 0.0, 0.0, 1.0],
        },
        body_kind: CINEMATIQUE,
        flags: 0,
        body_count: 0,
        joint_count: 0,
        jam_points: 0,
        assembly_mass: 0.0,
        node_bytes: 0,
    };
    let desc = BodyDesc::undeclared(CINEMATIQUE);
    create_assembly_payload_full(
        &header,
        &[AssemblyBodyEntry { desc, root_node: 0 }],
        &[],
        &[],
        &encode_nodes(&nodes, &names).expect("NODE"),
        None,
        Some(&clips()),
        &encode_colliders(&colliders, &[], &[]).expect("PHYS"),
    )
}

/// `ANIMATE_ASSEMBLY` : jouer le clip 0 sur la couche 0 au tick donné.
fn jouer(index: u32, tick: f64) -> Vec<u8> {
    let mut out = Vec::new();
    for value in [index, 1] {
        out.extend_from_slice(&value.to_le_bytes());
    }
    out.extend_from_slice(&tick.to_le_bytes());
    for value in [anim_action::PLAY, 0, 0] {
        out.extend_from_slice(&value.to_le_bytes());
    }
    for value in [1.0f32, 0.0, 1.0] {
        out.extend_from_slice(&value.to_le_bytes());
    }
    out.extend_from_slice(&[0; 8]);
    out
}

fn flux(commandes: &[(u32, Vec<u8>)]) -> Vec<u8> {
    let mut stream = CommandStreamHeader::CURRENT_SCHEMA.to_le_bytes().to_vec();
    stream.extend_from_slice(&0u32.to_le_bytes());
    for (op, payload) in commandes {
        stream.extend_from_slice(&op.to_le_bytes());
        stream.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        stream.extend_from_slice(payload);
        stream.resize(stream.len().next_multiple_of(8), 0);
    }
    stream
}

fn cycle(ctx: u64, tick: u64, commandes: &[(u32, Vec<u8>)]) {
    let stream = flux(commandes);
    let mut info = AxionBufferInfo {
        ptr: std::ptr::null_mut(),
        capacity: 0,
        kind: 0,
        generation: 0,
    };
    assert_eq!(
        unsafe {
            axion_buffer_acquire(
                ctx,
                BufferKind::SimIn.as_u32(),
                stream.len() as u64,
                &raw mut info,
            )
        },
        AXION_OK
    );
    let view = unsafe {
        std::slice::from_raw_parts_mut(info.ptr, usize::try_from(info.capacity).unwrap())
    };
    BufferHeader {
        kind: BufferKind::SimIn,
        generation: info.generation,
        schema_version: 0,
        payload_len: stream.len() as u64,
        element_count: 1,
        crc32c: 0,
    }
    .write(view);
    view[HEADER_BYTES..HEADER_BYTES + stream.len()].copy_from_slice(&stream);
    assert_eq!(
        unsafe { axion_sim_submit(ctx, tick, commandes.len() as u32, 0) },
        AXION_OK
    );
    assert_eq!(
        unsafe { axion_buffer_release(ctx, info.kind, info.generation) },
        AXION_OK
    );
    let mut result = AxionCollectResult::default();
    assert_eq!(
        unsafe { axion_sim_collect(ctx, u64::MAX, &raw mut result) },
        AXION_OK
    );
}

/// L'emprise du corps de l'assembly `index`, relative à sa translation.
fn emprise(ctx: u64, index: u32) -> ([f32; 3], [f32; 3]) {
    axion_native::context::with(ctx, false, |session| {
        let reports = session.physics().collect_reports();
        let rank = reports
            .states
            .iter()
            .position(|state| state.handle == Handle::new(index, 1))
            .expect("le corps de l'assembly");
        (reports.bounds[rank].min, reports.bounds[rank].max)
    })
    .unwrap()
}

fn proche(a: [f32; 3], b: [f32; 3]) -> bool {
    (0..3).all(|i| (a[i] - b[i]).abs() < 1e-4)
}

#[test]
fn adr155_le_serveur_anime_un_collider_et_seulement_lui() {
    let mut ctx = 0u64;
    assert_eq!(
        unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut ctx) },
        AXION_OK
    );

    // Au tick 0 : deux assemblies et leur clip. La seconde n'a aucun collider sur son node animé.
    cycle(
        ctx,
        0,
        &[
            (opcode::CREATE_ASSEMBLY, creation(1, true)),
            (opcode::CREATE_ASSEMBLY, creation(2, false)),
            (opcode::ANIMATE_ASSEMBLY, jouer(1, 0.0)),
            (opcode::ANIMATE_ASSEMBLY, jouer(2, 0.0)),
        ],
    );
    let stats =
        axion_native::context::with(ctx, false, |session| session.animation_stats()).unwrap();
    assert_eq!(
        stats.animated, 1,
        "seule l'assembly dont un collider dépend du node animé (R-1430)"
    );
    assert_eq!(stats.commands, 1);
    assert_eq!(stats.ignored, 1, "la commande sur l'autre ne fait rien");

    // Au tick 0, le clip commence : la boîte du bras est à 2 blocs en x.
    let (min, max) = emprise(ctx, 1);
    assert!(
        proche(min, [-0.5, -0.5, -0.5]) && proche(max, [2.5, 0.5, 0.5]),
        "{min:?} {max:?}"
    );

    // Au tick 20 (une seconde), le bras a tourné d'un quart de tour : la boîte est à 2 blocs en y.
    for tick in 1..=20 {
        cycle(ctx, tick, &[]);
    }
    let (min, max) = emprise(ctx, 1);
    assert!(
        proche(min, [-0.5, -0.5, -0.5]) && proche(max, [0.5, 2.5, 0.5]),
        "{min:?} {max:?}"
    );
    // La seconde garde son emprise de repos, une boîte à l'origine.
    let (min, max) = emprise(ctx, 2);
    assert!(
        proche(min, [-0.5; 3]) && proche(max, [0.5; 3]),
        "{min:?} {max:?}"
    );

    // T-360 : seule la première a été échantillonnée, une fois par tick, de 0 à 20.
    let stats =
        axion_native::context::with(ctx, false, |session| session.animation_stats()).unwrap();
    assert_eq!(stats.samples, 21);

    // Le clip tient sa dernière pose.
    cycle(ctx, 21, &[]);
    let (_, max) = emprise(ctx, 1);
    assert!(proche(max, [0.5, 2.5, 0.5]), "{max:?}");

    // Retirée, l'assembly emporte son animation.
    let mut retrait = Vec::new();
    for value in [1u32, 1] {
        retrait.extend_from_slice(&value.to_le_bytes());
    }
    cycle(ctx, 22, &[(opcode::REMOVE_ASSEMBLY, retrait)]);
    let stats =
        axion_native::context::with(ctx, false, |session| session.animation_stats()).unwrap();
    assert_eq!(stats.animated, 0);

    // Les tampons que le cycle a remplis, relâchés comme Java le fait : l'arrêt ne signale aucune
    // fuite.
    for kind in [BufferKind::SimIn, BufferKind::SimOut, BufferKind::Events] {
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
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK);
}
