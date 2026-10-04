//! FM-21 à travers l'ABI (ADR-123 §9) : un budget de simulation intenable fait descendre
//! le gouverneur d'un palier après trois fenêtres de 100 ticks, et `axion_sim_collect` le
//! dit — drapeau `AXION_SIM_DEGRADED`, jauges `axion.sim.degradation_level` et
//! `axion.sim.p95_ns`.
//!
//! Un seul test : la session native est globale au processus.

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

use ax_model::buffer::{BufferHeader, BufferKind, HEADER_BYTES};
use ax_model::dm::commands::{opcode, CommandStreamHeader, SetDimensionEnv};
use axion_native::abi::{
    axion_buffer_acquire, axion_buffer_release, axion_init, axion_metrics_export, axion_shutdown,
    axion_sim_collect, axion_sim_submit, AxionBufferInfo, AxionCollectResult, AXION_OK,
    AXION_SIDE_SERVER, AXION_SIM_DEGRADED,
};

/// Configuration IF-01 : un budget de simulation nul, que tout tick dépasse.
fn config_cbor() -> Vec<u8> {
    let map = ciborium::Value::Map(vec![(
        ciborium::Value::Text("budgets.sim_ns_per_tick".to_owned()),
        ciborium::Value::Integer(0.into()),
    )]);
    let mut out = Vec::new();
    ciborium::into_writer(&map, &mut out).unwrap();
    out
}

fn empty_info() -> AxionBufferInfo {
    AxionBufferInfo {
        ptr: std::ptr::null_mut(),
        capacity: 0,
        kind: 0,
        generation: 0,
    }
}

/// Libère un tampon comme Java après lecture : acquérir sa génération courante, relâcher.
fn release_buffer(ctx: u64, kind: BufferKind) {
    let mut info = empty_info();
    unsafe {
        axion_buffer_acquire(ctx, kind.as_u32(), 0, &raw mut info);
        axion_buffer_release(ctx, kind.as_u32(), info.generation);
    }
}

/// Écrit dans `SimIn` un flux d'une commande `SET_DIMENSION_ENV` (dimension 0) : chaque
/// tick ouvre un monde à simuler, que la fin du tick détruit (R-610).
fn write_command(ctx: u64) {
    let mut stream = CommandStreamHeader::CURRENT_SCHEMA.to_le_bytes().to_vec();
    stream.extend_from_slice(&0u32.to_le_bytes());
    let mut payload = 0u64.to_le_bytes().to_vec();
    payload.extend([0.0f32, -9.81, 0.0].iter().flat_map(|v| v.to_le_bytes()));
    payload.resize(SetDimensionEnv::BYTES, 0);
    stream.extend_from_slice(&opcode::SET_DIMENSION_ENV.to_le_bytes());
    stream.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    stream.extend_from_slice(&payload);

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
        element_count: 1,
        crc32c: 0,
    }
    .write(view);
    view[HEADER_BYTES..HEADER_BYTES + stream.len()].copy_from_slice(&stream);
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
fn un_budget_intenable_degrade_la_simulation_apres_trois_fenetres() {
    let config = config_cbor();
    let mut ctx = 0u64;
    let code = unsafe {
        axion_init(
            config.as_ptr(),
            config.len(),
            AXION_SIDE_SERVER,
            &raw mut ctx,
        )
    };
    assert_eq!(code, AXION_OK, "init");
    assert_eq!(metrique(ctx, "axion.sim.degradation_level"), 0);

    // 299 ticks : trois fenêtres ne sont pas encore closes, la qualité reste nominale.
    let mut result = AxionCollectResult::default();
    for tick in 1..=300u64 {
        write_command(ctx);
        assert_eq!(unsafe { axion_sim_submit(ctx, tick, 1, 0) }, AXION_OK);
        assert_eq!(
            unsafe { axion_sim_collect(ctx, u64::MAX, &raw mut result) },
            AXION_OK
        );
        if tick < 300 {
            assert_eq!(result.flags & AXION_SIM_DEGRADED, 0, "tick {tick}");
        }
    }

    // La troisième fenêtre close en dépassement : un palier de moins, et c'est visible.
    assert_ne!(result.flags & AXION_SIM_DEGRADED, 0, "drapeau DEGRADED");
    assert_eq!(metrique(ctx, "axion.sim.degradation_level"), 1);
    assert!(metrique(ctx, "axion.sim.p95_ns") > 0, "p95 mesuré");

    release_buffer(ctx, BufferKind::SimIn);
    release_buffer(ctx, BufferKind::SimOut);
    release_buffer(ctx, BufferKind::Events);
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK, "shutdown");
}
