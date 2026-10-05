//! La mesure du pas de simulation à travers l'ABI, quand le budget tient (R-500, R-501,
//! R-661) : chaque pas est compté et décomposé, aucun dépassement n'est compté, et le temps
//! CPU du thread n'est pas mesuré — deux appels système par pas coûteraient plus que la
//! télémétrie n'en a le droit, et seul un dépassement ouvre sa surveillance.
//!
//! Un seul test : la session native est globale au processus.

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

use ax_model::buffer::{BufferHeader, BufferKind, HEADER_BYTES};
use ax_model::dm::commands::{opcode, CommandStreamHeader, SetDimensionEnv};
use axion_native::abi::{
    axion_buffer_acquire, axion_buffer_release, axion_init, axion_metrics_export, axion_shutdown,
    axion_sim_collect, axion_sim_submit, AxionBufferInfo, AxionCollectResult, AXION_OK,
    AXION_SIDE_SERVER,
};

/// Ticks joués : moins d'une fenêtre du gouverneur suffit à ce qui est vérifié.
const TICKS: u64 = 50;

/// Configuration IF-01 : un budget de simulation de dix secondes, qu'aucun pas ne dépasse.
fn config_cbor() -> Vec<u8> {
    let map = ciborium::Value::Map(vec![(
        ciborium::Value::Text("budgets.sim_ns_per_tick".to_owned()),
        ciborium::Value::Integer(10_000_000_000u64.into()),
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

/// Un champ entier d'une métrique, lu dans l'export JSON de la session (R-502) : `value`,
/// ou `count` et `max` d'une durée.
fn champ(ctx: u64, nom: &str, champ: &str) -> u64 {
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
        .split(&format!("\"{champ}\": "))
        .nth(1)
        .unwrap_or_else(|| panic!("champ {champ} absent : {ligne}"))
        .chars()
        .take_while(char::is_ascii_digit)
        .collect::<String>()
        .parse()
        .expect("valeur entière")
}

fn metrique(ctx: u64, nom: &str) -> u64 {
    champ(ctx, nom, "value")
}

#[test]
fn un_pas_dans_son_budget_est_mesure_sans_son_temps_cpu() {
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

    let mut result = AxionCollectResult::default();
    for tick in 1..=TICKS {
        write_command(ctx);
        assert_eq!(unsafe { axion_sim_submit(ctx, tick, 1, 0) }, AXION_OK);
        assert_eq!(
            unsafe { axion_sim_collect(ctx, u64::MAX, &raw mut result) },
            AXION_OK
        );
    }

    // Chaque pas est mesuré et décomposé…
    assert_eq!(champ(ctx, "axion.sim.step_ns", "count"), TICKS);
    assert!(metrique(ctx, "axion.budget.sim_ns_per_tick.consumed") > 0);
    assert!(
        metrique(ctx, "axion.sim.last_step_integration_ns") > 0,
        "étape 4 mesurée"
    );
    metrique(ctx, "axion.sim.last_step_contacts_ns");
    // … aucun ne dépasse, et aucun n'est mesuré en temps CPU.
    assert_eq!(metrique(ctx, "axion.budget.sim_ns_per_tick.overruns"), 0);
    assert_eq!(champ(ctx, "axion.sim.step_cpu_ns", "count"), 0);
    assert_eq!(metrique(ctx, "axion.sim.last_step_cpu_ns"), 0);

    release_buffer(ctx, BufferKind::SimIn);
    release_buffer(ctx, BufferKind::SimOut);
    release_buffer(ctx, BufferKind::Events);
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK, "shutdown");
}
