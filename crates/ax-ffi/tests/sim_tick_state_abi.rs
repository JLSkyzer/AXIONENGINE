//! L'état par tick du cycle IF-03, à travers l'ABI (ADR-123 §2 et §5) : les joueurs
//! (`SET_OBSERVERS`) et les proxies d'entités (`SET_ENTITY_PROXIES`) ne valent que pour le
//! tick qui les déclare. Un joueur garde le monde de sa dimension, un proxy aussi tant
//! qu'il existe ; R-610 détruit le monde dès le premier tick qui ne les répète pas.
//!
//! Un seul test : la session native est globale au processus.

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

use ax_model::buffer::{BufferHeader, BufferKind, HEADER_BYTES};
use ax_model::dm::commands::{
    entity_proxy_shape, opcode, CommandStreamHeader, EntityProxyDesc, SetDimensionEnv, SetObservers,
};
use axion_native::abi::{
    axion_buffer_acquire, axion_buffer_release, axion_init, axion_metrics_export, axion_shutdown,
    axion_sim_collect, axion_sim_submit, AxionBufferInfo, AxionCollectResult,
    AXION_E_INVALID_BUFFER, AXION_OK, AXION_SIDE_SERVER,
};

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

/// Ajoute une commande au flux, payload rembourré à 8 octets.
fn push(stream: &mut Vec<u8>, op: u32, payload: &[u8]) {
    stream.extend_from_slice(&op.to_le_bytes());
    stream.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    stream.extend_from_slice(payload);
    while !stream.len().is_multiple_of(8) {
        stream.push(0);
    }
}

fn set_dimension_env(dimension: u64) -> Vec<u8> {
    let mut payload = dimension.to_le_bytes().to_vec();
    payload.extend([0.0f32, -9.81, 0.0].iter().flat_map(|v| v.to_le_bytes()));
    payload.resize(SetDimensionEnv::BYTES, 0);
    payload
}

fn set_observers(dimension: u64, positions: &[[f64; 3]]) -> Vec<u8> {
    let mut payload = dimension.to_le_bytes().to_vec();
    payload.extend_from_slice(&(positions.len() as u32).to_le_bytes());
    payload.extend_from_slice(&0u32.to_le_bytes());
    for position in positions {
        payload.extend(position.iter().flat_map(|v| v.to_le_bytes()));
    }
    assert_eq!(
        payload.len(),
        SetObservers::BYTES + positions.len() * SetObservers::POSITION_BYTES
    );
    payload
}

/// Payload `SET_ENTITY_PROXIES` d'un proxy : une entité-boîte de 1 × 2 × 1 blocs, de la
/// forme donnée.
fn set_entity_proxies(dimension: u64, entity: u32, shape: u8) -> Vec<u8> {
    let mut payload = dimension.to_le_bytes().to_vec();
    payload.extend_from_slice(&1u32.to_le_bytes());
    payload.extend_from_slice(&0u32.to_le_bytes());
    payload.extend([0.5f64, 70.0, 0.5].iter().flat_map(|v| v.to_le_bytes()));
    payload.extend([0.5f32, 1.0, 0.5].iter().flat_map(|v| v.to_le_bytes()));
    payload.extend([0.0f32; 3].iter().flat_map(|v| v.to_le_bytes()));
    payload.extend_from_slice(&entity.to_le_bytes());
    payload.push(shape);
    payload.extend_from_slice(&[0u8; 3]);
    assert_eq!(payload.len(), 16 + EntityProxyDesc::BYTES);
    payload
}

/// Soumet un tick portant `commands` (opcode, payload), puis le collecte.
fn tick(ctx: u64, tick: u64, commands: &[(u32, Vec<u8>)]) {
    assert_eq!(submit(ctx, tick, commands), AXION_OK, "submit");
    let mut result = AxionCollectResult::default();
    assert_eq!(
        unsafe { axion_sim_collect(ctx, u64::MAX, &raw mut result) },
        AXION_OK,
        "collect"
    );
}

/// Écrit `commands` dans `SimIn` et soumet le tick ; rend le code de l'ABI.
fn submit(ctx: u64, tick: u64, commands: &[(u32, Vec<u8>)]) -> i32 {
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
    unsafe { axion_sim_submit(ctx, tick, count, 0) }
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
fn l_etat_d_un_tick_ne_garde_le_monde_que_le_temps_de_son_tick() {
    let mut ctx = 0u64;
    let code = unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut ctx) };
    assert_eq!(code, AXION_OK, "init");

    // Un joueur seul n'ouvre aucun monde : rien à simuler.
    tick(
        ctx,
        1,
        &[(opcode::SET_OBSERVERS, set_observers(0, &[[0.5, 70.0, 0.5]]))],
    );
    assert_eq!(metrique(ctx, "axion.sim.worlds"), 0);

    // Le monde ouvert par SET_DIMENSION_ENV survit à la fin du tick : son joueur le garde.
    tick(
        ctx,
        2,
        &[
            (opcode::SET_DIMENSION_ENV, set_dimension_env(0)),
            (opcode::SET_OBSERVERS, set_observers(0, &[[0.5, 70.0, 0.5]])),
        ],
    );
    assert_eq!(metrique(ctx, "axion.sim.worlds"), 1);

    // Un joueur d'une autre dimension ne le garde pas : la déclaration vaut par dimension.
    tick(
        ctx,
        3,
        &[(opcode::SET_OBSERVERS, set_observers(1, &[[0.5, 70.0, 0.5]]))],
    );
    assert_eq!(metrique(ctx, "axion.sim.worlds"), 0);

    // Ni d'un tick à l'autre : un tick sans commande n'a aucun observateur.
    tick(
        ctx,
        4,
        &[
            (opcode::SET_DIMENSION_ENV, set_dimension_env(0)),
            (opcode::SET_OBSERVERS, set_observers(0, &[[0.5, 70.0, 0.5]])),
        ],
    );
    assert_eq!(metrique(ctx, "axion.sim.worlds"), 1);
    tick(ctx, 5, &[]);
    assert_eq!(metrique(ctx, "axion.sim.worlds"), 0);

    // Le proxy d'une entité prend corps au tick qui le déclare et garde le monde vivant ;
    // non redéclaré, il disparaît, et le monde avec lui.
    tick(
        ctx,
        6,
        &[
            (opcode::SET_DIMENSION_ENV, set_dimension_env(0)),
            (
                opcode::SET_ENTITY_PROXIES,
                set_entity_proxies(0, 42, entity_proxy_shape::CAPSULE),
            ),
        ],
    );
    assert_eq!(metrique(ctx, "axion.sim.worlds"), 1);
    tick(ctx, 7, &[]);
    assert_eq!(metrique(ctx, "axion.sim.worlds"), 0);

    // Une forme inconnue est une donnée fautive : le flux est refusé.
    assert_eq!(
        submit(
            ctx,
            8,
            &[(opcode::SET_ENTITY_PROXIES, set_entity_proxies(0, 42, 2))]
        ),
        AXION_E_INVALID_BUFFER
    );
    let mut result = AxionCollectResult::default();
    assert_eq!(
        unsafe { axion_sim_collect(ctx, u64::MAX, &raw mut result) },
        AXION_OK
    );

    release_buffer(ctx, BufferKind::SimIn);
    release_buffer(ctx, BufferKind::SimOut);
    release_buffer(ctx, BufferKind::Events);
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK, "shutdown");
}
