//! Cycle de simulation d'IF-03, exercé par l'ABI comme Java le fera.
//!
//! Un seul test, comme `cycle_abi.rs` : la session native est globale au
//! processus. On soumet une commande `SET_DIMENSION_ENV` dans `SimIn`, on
//! `submit` puis on `collect`, et l'on vérifie que le cycle aboutit — sans corps,
//! donc zéro `BodyState`, mais toute la plomberie de la frontière est traversée.
//! Les métriques disent le reste : le monde ouvert par la commande est détruit en
//! fin de tick (R-610), un `submit` sans `collect` est compté (R-282).

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

use ax_model::buffer::{BufferHeader, BufferKind, HEADER_BYTES};
use ax_model::dm::commands::{opcode, CommandStreamHeader, SetDimensionEnv};
use axion_native::abi::{
    axion_buffer_acquire, axion_buffer_release, axion_init, axion_metrics_export, axion_shutdown,
    axion_sim_cancel, axion_sim_collect, axion_sim_submit, AxionBufferInfo, AxionCollectResult,
    AXION_OK, AXION_SIDE_SERVER,
};

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
    let valeur = ligne.split("\"value\": ").nth(1).expect("champ value");
    valeur
        .chars()
        .take_while(char::is_ascii_digit)
        .collect::<String>()
        .parse()
        .expect("valeur entière")
}

static CONTEXTE: std::sync::Mutex<()> = std::sync::Mutex::new(());

/// Libère un tampon comme Java le fera après lecture : l'acquérir (pour sa
/// génération courante, l'acquisition étant idempotente) puis le relâcher, afin
/// que le bilan d'allocations soit équilibré à l'arrêt (R-322).
fn release_buffer(ctx: u64, kind: BufferKind) {
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

/// Encode le flux `SimIn` : en-tête + une commande `SET_DIMENSION_ENV`.
fn command_stream() -> Vec<u8> {
    let mut stream = CommandStreamHeader::CURRENT_SCHEMA.to_le_bytes().to_vec();
    stream.extend_from_slice(&0u32.to_le_bytes()); // _pad

    let mut payload = 0u64.to_le_bytes().to_vec(); // dimension 0
    payload.extend([0.0f32, -9.81, 0.0].iter().flat_map(|v| v.to_le_bytes())); // gravity
    payload.extend([0.0f32, 0.0, 0.0].iter().flat_map(|v| v.to_le_bytes())); // wind
    payload.extend(0.0f32.to_le_bytes()); // fluid_surface
    payload.extend(0.0f32.to_le_bytes()); // fluid_density
    payload.extend(0u32.to_le_bytes()); // flags : pas de fluide
    payload.resize(SetDimensionEnv::BYTES, 0);

    stream.extend_from_slice(&opcode::SET_DIMENSION_ENV.to_le_bytes());
    stream.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    stream.extend_from_slice(&payload);
    stream
}

#[test]
fn cycle_de_simulation_aboutit() {
    let _contexte = CONTEXTE
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);

    let mut ctx = 0u64;
    // Configuration vide : les défauts s'appliquent.
    let code = unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut ctx) };
    assert_eq!(code, AXION_OK, "init");

    // Écrit le flux de commandes dans SimIn (en-tête de tampon + payload).
    let stream = command_stream();
    let mut info = AxionBufferInfo {
        ptr: std::ptr::null_mut(),
        capacity: 0,
        kind: 0,
        generation: 0,
    };
    let code = unsafe {
        axion_buffer_acquire(
            ctx,
            BufferKind::SimIn.as_u32(),
            stream.len() as u64,
            &raw mut info,
        )
    };
    assert_eq!(code, AXION_OK, "acquire SimIn");
    assert!(!info.ptr.is_null());
    let view = unsafe {
        std::slice::from_raw_parts_mut(info.ptr, usize::try_from(info.capacity).unwrap())
    };
    let header = BufferHeader {
        kind: BufferKind::SimIn,
        generation: info.generation,
        schema_version: 0,
        payload_len: stream.len() as u64,
        element_count: 1,
        crc32c: 0,
    };
    header.write(view);
    view[HEADER_BYTES..HEADER_BYTES + stream.len()].copy_from_slice(&stream);

    // Soumet une commande, puis collecte.
    assert_eq!(
        unsafe { axion_sim_submit(ctx, 1, 1, 0) },
        AXION_OK,
        "submit"
    );
    assert_eq!(
        metrique(ctx, "axion.sim.worlds"),
        1,
        "SET_DIMENSION_ENV ouvre le monde de sa dimension"
    );

    let mut result = AxionCollectResult::default();
    assert_eq!(
        unsafe { axion_sim_collect(ctx, u64::MAX, &raw mut result) },
        AXION_OK,
        "collect"
    );
    // Aucun corps : zéro état, zéro événement, mais le cycle a bien traversé la
    // frontière.
    assert_eq!(result.state_count, 0);
    assert_eq!(result.event_count, 0);
    // R-610 : sans corps, fluide ni joueur, le monde est détruit en fin de tick.
    assert_eq!(metrique(ctx, "axion.sim.worlds"), 0);

    // Annuler un cycle déjà clos est inoffensif.
    assert_eq!(unsafe { axion_sim_cancel(ctx) }, AXION_OK, "cancel");

    // R-282 : un `submit` que nul `collect` n'a suivi est clos par le suivant, et compté.
    assert_eq!(metrique(ctx, "axion.sim.unbalanced"), 0);
    assert_eq!(unsafe { axion_sim_submit(ctx, 2, 0, 0) }, AXION_OK);
    assert_eq!(unsafe { axion_sim_submit(ctx, 3, 0, 0) }, AXION_OK);
    assert_eq!(
        unsafe { axion_sim_collect(ctx, u64::MAX, &raw mut result) },
        AXION_OK
    );
    assert_eq!(metrique(ctx, "axion.sim.unbalanced"), 1);

    // Java relâche les tampons après lecture ; le bilan d'allocations doit être
    // équilibré à l'arrêt (R-322).
    release_buffer(ctx, BufferKind::SimIn);
    release_buffer(ctx, BufferKind::SimOut);
    release_buffer(ctx, BufferKind::Events);

    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK, "shutdown");
}
