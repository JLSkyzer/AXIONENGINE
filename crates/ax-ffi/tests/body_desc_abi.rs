//! T-312 (ADR-130), par l'ABI comme Java l'exerce : la description DM-08 d'un corps,
//! jointe à `CREATE_ASSEMBLY`, lui est appliquée. Un joueur proche le garde éveillé
//! (R-612) ; une seconde simulée sépare le témoin, qui tombe, du corps à gravité
//! nulle déclarée, qui reste à sa hauteur.
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
    axion_buffer_acquire, axion_buffer_release, axion_init, axion_shutdown, axion_sim_collect,
    axion_sim_submit, AxionBufferInfo, AxionCollectResult, AXION_OK, AXION_SIDE_SERVER,
};

static CONTEXTE: std::sync::Mutex<()> = std::sync::Mutex::new(());

/// Position monde du corps d'essai.
const CORPS: [f64; 3] = [5.0, 10.0, -2.0];

/// Une seconde à 20 ticks par seconde.
const TICKS: u64 = 20;

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

fn push(stream: &mut Vec<u8>, op: u32, payload: &[u8]) {
    stream.extend_from_slice(&op.to_le_bytes());
    stream.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    stream.extend_from_slice(payload);
    while !stream.len().is_multiple_of(8) {
        stream.push(0);
    }
}

/// Une boîte de collider, densité de l'eau.
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

/// Payload `CREATE_ASSEMBLY` du corps (7, 1), dynamique, décrit par `desc`.
fn assembly(desc: BodyDesc) -> Vec<u8> {
    let phys = encode_colliders(&[box_collider()], &[], &[]).expect("encodage PHYS");
    let mut payload = Vec::new();
    payload.extend_from_slice(&7u32.to_le_bytes());
    payload.extend_from_slice(&1u32.to_le_bytes());
    payload.extend_from_slice(&0u64.to_le_bytes());
    for value in CORPS {
        payload.extend_from_slice(&value.to_le_bytes());
    }
    for value in [0.0f32, 0.0, 0.0, 1.0] {
        payload.extend_from_slice(&value.to_le_bytes());
    }
    payload.push(2); // dynamique
    payload.resize(CreateAssembly::BYTES, 0);
    desc.write_le(&mut payload);
    payload.extend_from_slice(&phys);
    payload
}

/// Un joueur, à quelques blocs du corps : il le garde éveillé (R-612).
fn joueur_proche() -> Vec<u8> {
    let mut payload = 0u64.to_le_bytes().to_vec();
    payload.extend_from_slice(&1u32.to_le_bytes());
    payload.extend_from_slice(&0u32.to_le_bytes());
    for value in [CORPS[0] + 3.0, CORPS[1], CORPS[2]] {
        payload.extend_from_slice(&value.to_le_bytes());
    }
    payload
}

/// Soumet et collecte un tick ; rend la position monde du premier `BodyState`.
fn tick(ctx: u64, tick: u64, commands: &[(u32, Vec<u8>)]) -> [f64; 3] {
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
        element_count: u32::try_from(commands.len()).unwrap(),
        crc32c: 0,
    }
    .write(view);
    view[HEADER_BYTES..HEADER_BYTES + stream.len()].copy_from_slice(&stream);
    let count = u32::try_from(commands.len()).unwrap();
    assert_eq!(unsafe { axion_sim_submit(ctx, tick, count, 0) }, AXION_OK);
    let mut result = AxionCollectResult::default();
    assert_eq!(
        unsafe { axion_sim_collect(ctx, u64::MAX, &raw mut result) },
        AXION_OK
    );
    assert_eq!(result.state_count, 1, "un corps rapporté");

    let mut out = empty_info();
    assert_eq!(
        unsafe { axion_buffer_acquire(ctx, BufferKind::SimOut.as_u32(), 0, &raw mut out) },
        AXION_OK
    );
    let out_view =
        unsafe { std::slice::from_raw_parts(out.ptr, usize::try_from(out.capacity).unwrap()) };
    // BodyState : position f64 à l'offset 8.
    let f = |offset: usize| {
        f64::from_le_bytes(
            out_view[HEADER_BYTES + offset..HEADER_BYTES + offset + 8]
                .try_into()
                .unwrap(),
        )
    };
    let position = [f(8), f(16), f(24)];
    assert_eq!(
        unsafe { axion_buffer_release(ctx, BufferKind::SimOut.as_u32(), out.generation) },
        AXION_OK
    );
    position
}

/// Crée le corps décrit par `desc` près d'un joueur, simule une seconde, et rend sa hauteur.
fn hauteur_apres_une_seconde(desc: BodyDesc) -> f64 {
    let _contexte = CONTEXTE
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let mut ctx = 0u64;
    let code = unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut ctx) };
    assert_eq!(code, AXION_OK, "init");

    tick(
        ctx,
        1,
        &[
            (opcode::CREATE_ASSEMBLY, assembly(desc)),
            (opcode::SET_OBSERVERS, joueur_proche()),
        ],
    );
    let mut position = CORPS;
    for t in 2..=TICKS {
        position = tick(ctx, t, &[(opcode::SET_OBSERVERS, joueur_proche())]);
    }

    release_buffer(ctx, BufferKind::SimIn);
    release_buffer(ctx, BufferKind::SimOut);
    release_buffer(ctx, BufferKind::Events);
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK, "shutdown");
    position[1]
}

#[test]
fn t312_la_description_du_corps_traverse_la_frontiere() {
    // Témoin : rien de déclaré, le corps tombe.
    let temoin = hauteur_apres_une_seconde(BodyDesc::undeclared(2));
    assert!(temoin < CORPS[1] - 1.0, "le témoin tombe, y={temoin}");
    // Gravité déclarée nulle : le même corps reste à sa hauteur.
    let flottant = hauteur_apres_une_seconde(BodyDesc {
        gravity_scale: 0.0,
        ..BodyDesc::undeclared(2)
    });
    assert!(
        (flottant - CORPS[1]).abs() < 1.0e-4,
        "gravité nulle transmise par la frontière, y={flottant}"
    );
}
