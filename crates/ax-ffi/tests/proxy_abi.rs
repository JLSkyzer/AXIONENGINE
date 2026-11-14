//! Les proxies du monde prédit (ADR-168, précision 6c-C2), exercés par l'ABI comme Java le fera : une assembly créée
//! avec le drapeau `KINEMATIC` ne tombe pas, va où `SET_ASSEMBLY_POSE` la pose ; la même definition créée sans le
//! drapeau reste simulée, et la pose ne la déplace pas.
//!
//! Un seul test, son propre binaire : la session native est globale au processus.

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

mod common;

use ax_model::buffer::{BufferKind, HEADER_BYTES};
use ax_model::dm::commands::{create_assembly_flags, opcode, CommandStreamHeader};
use ax_model::dm::geometry::{Transform, WorldTransform};
use ax_model::dm::handle::Handle;
use ax_model::dm::physics::{BodyDesc, BodyState, ColliderDesc, ColliderShape};
use axion_native::abi::{
    axion_buffer_acquire, axion_buffer_release, axion_init, axion_shutdown, axion_sim_collect,
    axion_sim_submit, AxionBufferInfo, AxionCollectResult, AXION_OK, AXION_SIDE_SERVER,
};
use common::{creation, Modele};

const DEFINITION: u64 = 0x0DEF_0000_0000_0C02;
const PROXY: Handle = Handle::new(7, 1);
const SIMULEE: Handle = Handle::new(8, 1);

fn acquire(ctx: u64, kind: BufferKind, min_capacity: u64) -> AxionBufferInfo {
    let mut info = AxionBufferInfo {
        ptr: std::ptr::null_mut(),
        capacity: 0,
        kind: 0,
        generation: 0,
    };
    let code = unsafe { axion_buffer_acquire(ctx, kind.as_u32(), min_capacity, &raw mut info) };
    assert_eq!(code, AXION_OK, "acquire {kind}");
    info
}

fn release(ctx: u64, kind: BufferKind) {
    let info = acquire(ctx, kind, 0);
    assert_eq!(
        unsafe { axion_buffer_release(ctx, kind.as_u32(), info.generation) },
        AXION_OK
    );
}

fn boite() -> ColliderDesc {
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

fn commande(stream: &mut Vec<u8>, op: u32, payload: &[u8]) {
    stream.extend_from_slice(&op.to_le_bytes());
    stream.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    stream.extend_from_slice(payload);
    stream.extend_from_slice(&vec![0; (8 - payload.len() % 8) % 8]);
}

/// Soumet un flux de `count` commandes, collecte, et rend le handle et la position de chaque état rapporté.
fn pas(ctx: u64, tick: u64, count: u32, commands: &[u8]) -> Vec<(Handle, [f64; 3])> {
    let mut stream = CommandStreamHeader::CURRENT_SCHEMA.to_le_bytes().to_vec();
    stream.extend_from_slice(&0u32.to_le_bytes());
    stream.extend_from_slice(commands);
    let info = acquire(ctx, BufferKind::SimIn, stream.len() as u64);
    let view = unsafe {
        std::slice::from_raw_parts_mut(info.ptr, usize::try_from(info.capacity).unwrap())
    };
    view[HEADER_BYTES..HEADER_BYTES + stream.len()].copy_from_slice(&stream);
    assert_eq!(
        unsafe { axion_sim_submit(ctx, tick, count, 0) },
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
    let out = acquire(ctx, BufferKind::SimOut, 0);
    let view =
        unsafe { std::slice::from_raw_parts(out.ptr, usize::try_from(out.capacity).unwrap()) };
    let states = (0..result.state_count as usize)
        .map(|i| {
            let at = HEADER_BYTES + i * BodyState::BYTES;
            let u32_at =
                |o: usize| u32::from_le_bytes(view[at + o..at + o + 4].try_into().unwrap());
            let f64_at =
                |o: usize| f64::from_le_bytes(view[at + o..at + o + 8].try_into().unwrap());
            (
                Handle::new(u32_at(0), u32_at(4)),
                [f64_at(8), f64_at(16), f64_at(24)],
            )
        })
        .collect();
    release(ctx, BufferKind::SimOut);
    release(ctx, BufferKind::Events);
    states
}

fn y_de(states: &[(Handle, [f64; 3])], handle: Handle) -> [f64; 3] {
    states
        .iter()
        .find(|s| s.0 == handle)
        .expect("corps rapporté")
        .1
}

#[test]
fn adr168_un_proxy_cinematique_va_ou_on_le_pose_et_seulement_lui() {
    let mut ctx = 0u64;
    assert_eq!(
        unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut ctx) },
        AXION_OK
    );
    let modele = Modele::seul(vec![boite()], BodyDesc::undeclared(2));
    assert_eq!(modele.inscrire(ctx, DEFINITION), AXION_OK);
    let spawn = |x: f64| WorldTransform {
        position: [x, 100.0, 0.0],
        rotation: [0.0, 0.0, 0.0, 1.0],
    };
    let mut flux = Vec::new();
    // La gravité de la dimension : sans elle, rien ne tombe.
    let mut env = Vec::new();
    env.extend_from_slice(&0u64.to_le_bytes());
    for component in [0.0f32, -9.81, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0] {
        env.extend_from_slice(&component.to_le_bytes());
    }
    env.extend_from_slice(&0u32.to_le_bytes());
    env.resize(ax_model::dm::commands::SetDimensionEnv::BYTES, 0);
    commande(&mut flux, opcode::SET_DIMENSION_ENV, &env);
    commande(
        &mut flux,
        opcode::CREATE_ASSEMBLY,
        &creation(
            PROXY,
            0,
            spawn(0.0),
            DEFINITION,
            create_assembly_flags::KINEMATIC,
            None,
        ),
    );
    commande(
        &mut flux,
        opcode::CREATE_ASSEMBLY,
        &creation(SIMULEE, 0, spawn(20.0), DEFINITION, 0, None),
    );
    // Un joueur entre les deux : sans observateur proche, la gestion d'activité endort les corps.
    let mut observateur = 0u64.to_le_bytes().to_vec();
    observateur.extend_from_slice(&1u32.to_le_bytes());
    observateur.extend_from_slice(&0u32.to_le_bytes());
    for value in [10.0f64, 100.0, 0.0] {
        observateur.extend_from_slice(&value.to_le_bytes());
    }
    commande(&mut flux, opcode::SET_OBSERVERS, &observateur);
    let mut seul_observateur = Vec::new();
    commande(&mut seul_observateur, opcode::SET_OBSERVERS, &observateur);
    for tick in 1..=10 {
        let states = if tick == 1 {
            pas(ctx, tick, 4, &flux)
        } else {
            pas(ctx, tick, 1, &seul_observateur)
        };
        if tick == 10 {
            assert!(
                (y_de(&states, PROXY)[1] - 100.0).abs() < 1.0e-6,
                "un proxy ne tombe pas"
            );
            assert!(
                y_de(&states, SIMULEE)[1] < 99.9,
                "la même definition, simulée, tombe : {states:?}"
            );
        }
    }

    // La pose de chaque corps, par son node : le proxy y va ; la même pose adressée à l'assembly simulée ne la
    // déplace pas.
    let pose = |handle: Handle| {
        let mut payload = Vec::new();
        payload.extend_from_slice(&handle.index.to_le_bytes());
        payload.extend_from_slice(&handle.generation.to_le_bytes());
        payload.extend_from_slice(&1u32.to_le_bytes());
        payload.extend_from_slice(&0u32.to_le_bytes());
        payload.extend_from_slice(&0u32.to_le_bytes());
        payload.extend_from_slice(&0u32.to_le_bytes());
        for coordinate in [3.0f64, 50.0, -4.0] {
            payload.extend_from_slice(&coordinate.to_le_bytes());
        }
        for component in [0.0f32, 0.0, 0.0, 1.0] {
            payload.extend_from_slice(&component.to_le_bytes());
        }
        payload
    };
    let mut flux = Vec::new();
    commande(&mut flux, opcode::SET_OBSERVERS, &observateur);
    commande(&mut flux, opcode::SET_ASSEMBLY_POSE, &pose(PROXY));
    commande(&mut flux, opcode::SET_ASSEMBLY_POSE, &pose(SIMULEE));
    let states = pas(ctx, 11, 3, &flux);
    let proxy = y_de(&states, PROXY);
    assert!(
        (proxy[0] - 3.0).abs() < 1.0e-4
            && (proxy[1] - 50.0).abs() < 1.0e-4
            && (proxy[2] + 4.0).abs() < 1.0e-4,
        "le proxy à sa pose : {proxy:?}"
    );
    assert!(
        y_de(&states, SIMULEE)[0] > 19.0,
        "l'assembly simulée ne se téléporte pas"
    );
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK, "arrêt équilibré");
}
