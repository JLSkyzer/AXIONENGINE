//! R-612 à travers l'ABI (ADR-123 §3) : `axion_sim_collect` gère l'activité avant le pas.
//! Un corps sans joueur à portée s'endort, et se réveille au retour d'un joueur ; les métriques
//! le comptent (R-613 « journalisé »).
//!
//! Un seul test : la session native est globale au processus.

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

use ax_asset::a3d::encode_colliders;
use ax_model::buffer::{BufferHeader, BufferKind, HEADER_BYTES};
use ax_model::dm::commands::{
    opcode, ApplyForce, CommandStreamHeader, CreateAssembly, SetDimensionEnv,
};
use ax_model::dm::geometry::Transform;
use ax_model::dm::physics::{body_state_flags, BodyState, ColliderDesc, ColliderShape};
use axion_native::abi::{
    axion_buffer_acquire, axion_buffer_release, axion_init, axion_metrics_export, axion_shutdown,
    axion_sim_collect, axion_sim_submit, AxionBufferInfo, AxionCollectResult, AXION_OK,
    AXION_SIDE_SERVER,
};

/// Position monde du corps d'essai.
const CORPS: [f64; 3] = [5.0, 10.0, -2.0];

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

/// Dimension 0 sans gravité : le corps ne bouge que s'il est éveillé et lancé.
fn sans_gravite() -> Vec<u8> {
    let mut payload = 0u64.to_le_bytes().to_vec();
    payload.resize(SetDimensionEnv::BYTES, 0);
    payload
}

/// Un cube dynamique d'une tonne au handle (7, 1), en `CORPS`.
fn cube() -> Vec<u8> {
    let collider = ColliderDesc {
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
    };
    let phys = encode_colliders(&[collider], &[], &[]).expect("encodage PHYS");
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
    payload.extend_from_slice(&phys);
    payload
}

/// Un joueur, à quelques blocs du corps.
fn joueur_proche() -> Vec<u8> {
    let mut payload = 0u64.to_le_bytes().to_vec();
    payload.extend_from_slice(&1u32.to_le_bytes());
    payload.extend_from_slice(&0u32.to_le_bytes());
    for value in [CORPS[0] + 3.0, CORPS[1], CORPS[2]] {
        payload.extend_from_slice(&value.to_le_bytes());
    }
    payload
}

/// Payload `APPLY_IMPULSE` (`ApplyForce`) au centre de masse du corps (7, 1).
fn impulsion(vector: [f32; 3]) -> Vec<u8> {
    let mut payload = 7u32.to_le_bytes().to_vec();
    payload.extend_from_slice(&1u32.to_le_bytes());
    for value in vector.into_iter().chain([0.0f32; 3]) {
        payload.extend_from_slice(&value.to_le_bytes());
    }
    payload.extend_from_slice(&0u32.to_le_bytes()); // at_point : faux
    assert_eq!(payload.len(), ApplyForce::BYTES);
    payload
}

/// Soumet et collecte un tick ; rend les drapeaux du premier `BodyState` de `SimOut`.
fn tick(ctx: u64, tick: u64, commands: &[(u32, Vec<u8>)]) -> u32 {
    if !commands.is_empty() {
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
    }
    let count = u32::try_from(commands.len()).unwrap();
    assert_eq!(unsafe { axion_sim_submit(ctx, tick, count, 0) }, AXION_OK);
    let mut result = AxionCollectResult::default();
    assert_eq!(
        unsafe { axion_sim_collect(ctx, u64::MAX, &raw mut result) },
        AXION_OK
    );
    assert_eq!(result.state_count, 1, "un corps rapporté");

    let mut info = empty_info();
    assert_eq!(
        unsafe { axion_buffer_acquire(ctx, BufferKind::SimOut.as_u32(), 0, &raw mut info) },
        AXION_OK
    );
    let view =
        unsafe { std::slice::from_raw_parts(info.ptr, usize::try_from(info.capacity).unwrap()) };
    let at = HEADER_BYTES + 72; // BodyState.flags
    let flags = u32::from_le_bytes(view[at..at + 4].try_into().unwrap());
    assert!(HEADER_BYTES + BodyState::BYTES <= view.len());
    assert_eq!(
        unsafe { axion_buffer_release(ctx, BufferKind::SimOut.as_u32(), info.generation) },
        AXION_OK
    );
    flags
}

/// Valeur d'une métrique, lue dans l'export JSON de la session (R-502).
fn metrique(ctx: u64, nom: &str) -> u64 {
    let mut needed = 0usize;
    let code = unsafe { axion_metrics_export(ctx, std::ptr::null_mut(), 0, &raw mut needed) };
    assert_eq!(code, AXION_OK, "taille de l'export");
    let mut buffer = vec![0u8; needed];
    let mut written = 0usize;
    let code =
        unsafe { axion_metrics_export(ctx, buffer.as_mut_ptr(), buffer.len(), &raw mut written) };
    assert_eq!(code, AXION_OK, "export");
    let json = String::from_utf8(buffer).expect("export UTF-8");
    let marque = format!("\"name\": \"{nom}\"");
    let ligne = json
        .lines()
        .find(|ligne| ligne.contains(&marque))
        .unwrap_or_else(|| panic!("{nom} absente de l'export : {json}"));
    ligne
        .split("\"value\": ")
        .nth(1)
        .expect("champ value")
        .chars()
        .take_while(char::is_ascii_digit)
        .collect::<String>()
        .parse()
        .expect("valeur entière")
}

#[test]
fn un_corps_sans_joueur_a_portee_s_endort_et_se_reveille_a_son_retour() {
    let mut ctx = 0u64;
    let code = unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut ctx) };
    assert_eq!(code, AXION_OK, "init");

    // Un joueur près du corps qui naît : il reste éveillé.
    let flags = tick(
        ctx,
        1,
        &[
            (opcode::SET_DIMENSION_ENV, sans_gravite()),
            (opcode::CREATE_ASSEMBLY, cube()),
            (opcode::SET_OBSERVERS, joueur_proche()),
        ],
    );
    assert_eq!(
        flags & body_state_flags::SLEEPING,
        0,
        "éveillé près d'un joueur"
    );
    assert_eq!(metrique(ctx, "axion.sim.slept_radius"), 0);

    // Plus aucun joueur déclaré : le corps s'endort avant le pas (R-612), compté.
    let flags = tick(ctx, 2, &[]);
    assert_ne!(flags & body_state_flags::SLEEPING, 0, "endormi sans joueur");
    assert_eq!(metrique(ctx, "axion.sim.slept_radius"), 1);
    assert_eq!(metrique(ctx, "axion.sim.woken"), 0);

    // Le joueur revient : le corps se réveille.
    let flags = tick(ctx, 3, &[(opcode::SET_OBSERVERS, joueur_proche())]);
    assert_eq!(
        flags & body_state_flags::SLEEPING,
        0,
        "réveillé au retour du joueur"
    );
    assert_eq!(metrique(ctx, "axion.sim.woken"), 1);
    // Ce que la simulation corrige est compté, jamais tu : rien encore…
    assert_eq!(metrique(ctx, "axion.sim.velocity_clamps"), 0);
    assert_eq!(metrique(ctx, "axion.sim.invalid_states"), 0);
    assert_eq!(metrique(ctx, "axion.sim.events_dropped"), 0);
    // … puis une impulsion démesurée, bornée à 300 m/s (R-180) …
    tick(
        ctx,
        4,
        &[
            (opcode::SET_OBSERVERS, joueur_proche()),
            (opcode::APPLY_IMPULSE, impulsion([1.0e6, 0.0, 0.0])),
        ],
    );
    assert_eq!(metrique(ctx, "axion.sim.velocity_clamps"), 1);
    // … et une impulsion non finie, dont le corps est restauré (FM-20, E-2030).
    tick(
        ctx,
        5,
        &[
            (opcode::SET_OBSERVERS, joueur_proche()),
            (opcode::APPLY_IMPULSE, impulsion([f32::NAN, 0.0, 0.0])),
        ],
    );
    assert_eq!(metrique(ctx, "axion.sim.invalid_states"), 1);

    release_buffer(ctx, BufferKind::SimIn);
    release_buffer(ctx, BufferKind::Events);
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK, "shutdown");
}
