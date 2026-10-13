//! INV-08 et R-482 par l'ABI (ADR-133 D2) : la mémoire native que la bibliothèque
//! alloue — celle des mondes physiques comprise — est comptée par l'allocateur global,
//! publiée dans l'export, et une session complète la rend tout entière.
//!
//! Son propre binaire, et un seul test : l'allocateur compte tout le processus, et
//! aucun autre test ne doit allouer pendant les mesures.

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

use ax_asset::a3d::encode_colliders;
use ax_model::buffer::{BufferHeader, BufferKind, HEADER_BYTES};
use ax_model::dm::commands::{opcode, CommandStreamHeader, CreateAssembly};
use ax_model::dm::geometry::Transform;
use ax_model::dm::physics::{BodyDesc, ColliderDesc, ColliderShape};
use axion_native::abi::{
    axion_buffer_acquire, axion_buffer_release, axion_init, axion_metrics_export, axion_shutdown,
    axion_sim_collect, axion_sim_submit, AxionBufferInfo, AxionCollectResult, AXION_OK,
    AXION_SIDE_SERVER,
};
use axion_native::ALLOCATOR;
use std::time::{Duration, Instant};

/// Corps créés pour peser sur la mémoire du monde physique.
const BODIES: u32 = 200;

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

/// Payload `CREATE_ASSEMBLY` d'une boîte dynamique au handle (`index`, 1).
fn assembly(index: u32) -> Vec<u8> {
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
    payload.extend_from_slice(&index.to_le_bytes());
    payload.extend_from_slice(&1u32.to_le_bytes());
    payload.extend_from_slice(&0u64.to_le_bytes());
    for value in [f64::from(index) * 2.0, 10.0, 0.0] {
        payload.extend_from_slice(&value.to_le_bytes());
    }
    for value in [0.0f32, 0.0, 0.0, 1.0] {
        payload.extend_from_slice(&value.to_le_bytes());
    }
    payload.push(2);
    payload.resize(CreateAssembly::BYTES, 0);
    BodyDesc::undeclared(2).write_le(&mut payload);
    payload.extend_from_slice(&phys);
    payload
}

/// Soumet et collecte un tick.
fn tick(ctx: u64, tick: u64, commands: &[(u32, Vec<u8>)]) {
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
}

/// Valeur d'une métrique dans l'export (R-502).
fn metrique(ctx: u64, nom: &str) -> u64 {
    let mut needed = 0usize;
    assert_eq!(
        unsafe { axion_metrics_export(ctx, std::ptr::null_mut(), 0, &raw mut needed) },
        AXION_OK
    );
    let mut buffer = vec![0u8; needed];
    let mut written = 0usize;
    assert_eq!(
        unsafe { axion_metrics_export(ctx, buffer.as_mut_ptr(), buffer.len(), &raw mut written) },
        AXION_OK
    );
    assert_eq!(
        written, needed,
        "le document écrit est celui dont la taille a été annoncée"
    );
    let json = String::from_utf8(buffer).expect("export UTF-8");
    let marque = format!("\"name\": \"{nom}\"");
    let ligne = json
        .lines()
        .find(|ligne| ligne.contains(&marque))
        .unwrap_or_else(|| panic!("{nom} absente de l'export"));
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

/// Une session complète : ouverture, deux cents corps, quelques ticks, fermeture.
/// Rend la mémoire comptée par l'export au plus fort de la session, et avant les corps.
fn session() -> (u64, u64) {
    let mut ctx = 0u64;
    assert_eq!(
        unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut ctx) },
        AXION_OK,
        "init"
    );
    tick(ctx, 1, &[]);
    let avant = metrique(ctx, "axion.budget.native_mem_bytes.consumed");
    let creations: Vec<(u32, Vec<u8>)> = (1..=BODIES)
        .map(|index| (opcode::CREATE_ASSEMBLY, assembly(index)))
        .collect();
    tick(ctx, 2, &creations);
    for t in 3..10 {
        tick(ctx, t, &[]);
    }
    let pendant = metrique(ctx, "axion.budget.native_mem_bytes.consumed");
    assert!(metrique(ctx, "axion.mem.persistent.bytes") > 0);
    release_buffer(ctx, BufferKind::SimIn);
    release_buffer(ctx, BufferKind::SimOut);
    release_buffer(ctx, BufferKind::Events);
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK, "shutdown");
    (avant, pendant)
}

/// Le bilan de la mémoire comptée une fois la session close tout à fait. L'arrêt attend
/// déjà les threads du pool de jobs (`ax-jobs`) : aucun ne rend sa mémoire après lui.
/// Reste `rayon`, qui libère ses files par époques (`crossbeam-epoch`), plus tard. On
/// fait avancer l'époque jusqu'à deux lectures identiques. Sans cela, un reliquat différé
/// de 35 Ko apparaissait au second cycle, puis restait stable — mesuré le 2026-10-13 :
/// vidée, l'époque ramène le total exactement au même octet après chaque cycle.
fn bilan() -> usize {
    let echeance = Instant::now() + Duration::from_secs(5);
    let mut precedent = usize::MAX;
    loop {
        for _ in 0..256 {
            crossbeam_epoch::pin().flush();
        }
        let vivant = ALLOCATOR.live_bytes();
        if vivant == precedent || Instant::now() > echeance {
            return vivant;
        }
        precedent = vivant;
        std::thread::sleep(Duration::from_millis(20));
    }
}

#[test]
fn t016_la_memoire_native_est_comptee_et_une_session_la_rend_toute() {
    let (avant, pendant) = session();
    assert!(
        pendant > avant + u64::from(BODIES) * 1024,
        "les corps du monde physique sont comptés : {avant} puis {pendant} octets"
    );
    let apres_premier = bilan();

    // Un second cycle identique : R-482, il ne laisse rien derrière lui.
    session();
    assert_eq!(
        bilan(),
        apres_premier,
        "une session complète rend toute la mémoire native qu'elle a allouée"
    );
}
