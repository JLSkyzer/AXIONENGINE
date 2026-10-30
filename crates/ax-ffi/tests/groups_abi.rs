//! R-980 par l'ABI comme Java l'exerce : les groupes de collision que Java résout pour une
//! definition (`DEFN`, ADR-156) s'écrivent sur chaque collider à la compilation, et s'appliquent au
//! corps créé par `CREATE_ASSEMBLY` — quels que soient ceux que portait la section `PHYS`.
//!
//! Son propre binaire : la session native est globale au processus, et les tests
//! s'y succèdent sous un verrou.

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

mod common;

use ax_model::buffer::{BufferHeader, BufferKind, HEADER_BYTES};
use ax_model::dm::commands::{opcode, CommandStreamHeader, SetWorldCollision};
use ax_model::dm::geometry::{Transform, WorldTransform};
use ax_model::dm::handle::Handle;
use ax_model::dm::physics::{BodyDesc, ColliderDesc, ColliderShape};
use axion_native::abi::{
    axion_buffer_acquire, axion_buffer_release, axion_init, axion_shutdown, axion_sim_collect,
    axion_sim_submit, AxionBufferInfo, AxionCollectResult, AXION_OK, AXION_SIDE_SERVER,
};
use common::{creation, Modele};

/// L'empreinte de la definition du corps d'essai.
const DEFINITION: u64 = 0x0DEF_0000_0000_0980;

static CONTEXTE: std::sync::Mutex<()> = std::sync::Mutex::new(());

/// Bits des groupes réservés `world` et `assembly` (R-980, `ReservedGroup`).
const WORLD: u32 = 1 << 0;
const ASSEMBLY: u32 = 1 << 1;

/// Position monde du corps d'essai : au-dessus du plancher de la section (0, 0, 0).
const CORPS: [f64; 3] = [8.0, 3.0, 8.0];

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

/// Une boîte de collider sous les groupes donnés.
fn box_collider(group: u32, mask: u32) -> ColliderDesc {
    ColliderDesc {
        shape: ColliderShape::Box {
            half_extents: [0.5, 0.5, 0.5],
        },
        local: Transform::identity(),
        material: u16::MAX,
        _pad: 0,
        group,
        mask,
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

/// Payload `CREATE_ASSEMBLY` du corps (7, 1), de la definition compilée sous [`DEFINITION`].
fn assembly() -> Vec<u8> {
    creation(
        Handle::new(7, 1),
        0,
        WorldTransform {
            position: CORPS,
            rotation: [0.0, 0.0, 0.0, 1.0],
        },
        DEFINITION,
        0,
        None,
    )
}

/// `SET_WORLD_COLLISION` : un plancher de y = 0 à y = 1 sur la section (0, 0, 0).
fn plancher() -> Vec<u8> {
    let mut payload = 0u64.to_le_bytes().to_vec();
    for value in [0i32, 0, 0] {
        payload.extend_from_slice(&value.to_le_bytes());
    }
    payload.extend_from_slice(&1u32.to_le_bytes());
    // Sans matériau (ADR-149) : index, puis 6 octets réservés.
    payload.extend_from_slice(&0xFFFFu16.to_le_bytes());
    payload.extend_from_slice(&[0u8; 6]);
    assert_eq!(payload.len(), SetWorldCollision::BYTES);
    for value in [0.0f32, 0.0, 0.0, 16.0, 1.0, 16.0] {
        payload.extend_from_slice(&value.to_le_bytes());
    }
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

/// Soumet et collecte un tick ; rend la position monde du premier `BodyState`, s'il y en a un.
fn tick(ctx: u64, tick: u64, commands: &[(u32, Vec<u8>)]) -> Option<[f64; 3]> {
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
    if result.state_count == 0 {
        return None;
    }

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
    Some(position)
}

/// Pose le plancher, crée près d'un joueur le corps fait de `colliders`, membre de `group` et qui
/// heurte `mask` (la definition, R-980), simule deux secondes, et rend sa hauteur — `None` si aucun
/// corps n'a été créé.
fn hauteur_apres_deux_secondes(group: u32, mask: u32, colliders: &[ColliderDesc]) -> Option<f64> {
    let _contexte = CONTEXTE
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let mut ctx = 0u64;
    let code = unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut ctx) };
    assert_eq!(code, AXION_OK, "init");
    let modele = Modele {
        group,
        mask,
        ..Modele::seul(colliders.to_vec(), BodyDesc::undeclared(2))
    };
    assert_eq!(modele.inscrire(ctx, DEFINITION), AXION_OK);

    let mut position = tick(
        ctx,
        1,
        &[
            (opcode::SET_WORLD_COLLISION, plancher()),
            (opcode::CREATE_ASSEMBLY, assembly()),
            (opcode::SET_OBSERVERS, joueur_proche()),
        ],
    );
    for t in 2..=40 {
        position = tick(ctx, t, &[(opcode::SET_OBSERVERS, joueur_proche())]);
    }

    release_buffer(ctx, BufferKind::SimIn);
    release_buffer(ctx, BufferKind::SimOut);
    release_buffer(ctx, BufferKind::Events);
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK, "shutdown");
    position.map(|p| p[1])
}

#[test]
fn les_groupes_de_la_definition_s_appliquent_au_corps() {
    // Témoin : membre d'`assembly`, heurte tout — il se pose sur le plancher.
    let pose = hauteur_apres_deux_secondes(ASSEMBLY, u32::MAX, &[box_collider(0, 0)])
        .expect("le témoin est créé");
    assert!(
        (pose - 1.5).abs() < 0.05,
        "le témoin repose sur le plancher, y={pose}"
    );
    // Le même corps, qui ne filtre pas `world`, traverse le plancher.
    let traverse = hauteur_apres_deux_secondes(ASSEMBLY, !WORLD, &[box_collider(0, 0)])
        .expect("le corps est créé");
    assert!(
        traverse < 0.0,
        "le filtre de la definition le fait traverser, y={traverse}"
    );
}

#[test]
fn les_groupes_de_la_definition_l_emportent_sur_ceux_de_phys() {
    // Des colliders aux groupes discordants dans PHYS : la compilation les met tous sous ceux de la
    // definition, et le corps se forme, filtré comme elle le dit.
    let traverse = hauteur_apres_deux_secondes(
        ASSEMBLY,
        !WORLD,
        &[box_collider(ASSEMBLY, u32::MAX), box_collider(ASSEMBLY, WORLD)],
    )
    .expect("le corps est créé");
    assert!(traverse < 0.0, "les groupes de la definition, y={traverse}");
}
