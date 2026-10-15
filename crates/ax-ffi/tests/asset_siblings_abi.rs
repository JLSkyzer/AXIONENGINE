//! ADR-139 par l'ABI, comme Java le fera : la compilation demande les fichiers voisins que la
//! source référence (`NEEDS_FILES`), l'appelant la relance avec eux, et l'asset compilé est **au
//! bit près** celui que `ax-asset` compile pour `axion-cli` des mêmes fichiers (R-830).
//!
//! Un seul test, son propre binaire : la session native est globale au processus.

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

use ax_asset::compile::{compile, source_hash, CompileOptions};
use ax_asset::import::{normalize_reference, ImportLimits, SourceFormat};
use ax_asset::options::AssetOptions;
use ax_model::buffer::{BufferKind, HEADER_BYTES};
use ax_model::config::{find, ConfigScope, ConfigValue};
use axion_native::abi::{
    axion_asset_compile, axion_asset_poll, axion_buffer_acquire, axion_buffer_release, axion_init,
    axion_shutdown, AxionBufferInfo, AXION_ASSET_COMPILED, AXION_ASSET_FAILED,
    AXION_ASSET_NEEDS_FILES, AXION_ASSET_PENDING, AXION_E_INVALID_BUFFER, AXION_FILE_ABSENT,
    AXION_FILE_PRESENT, AXION_OK, AXION_SIDE_SERVER, MAX_SIBLING_FILES,
};
use std::collections::BTreeMap;

const ASSET_ID: u64 = 0x0A51_0C0B_0000_0139;
/// Formats d'`axion_asset_compile` : glTF texte et OBJ.
const GLTF: u32 = 1;
const OBJ: u32 = 2;

/// Ce que le sondage d'un travail a rendu.
#[derive(Debug, PartialEq)]
enum Outcome {
    Compiled(Vec<u8>),
    Failed(i32),
    NeedsFiles(Vec<String>),
}

fn empty_info() -> AxionBufferInfo {
    AxionBufferInfo {
        ptr: std::ptr::null_mut(),
        capacity: 0,
        kind: 0,
        generation: 0,
    }
}

/// Une table de fichiers voisins (ADR-139) : `None`, une entrée marquée absente.
fn table(entries: &[(&str, Option<&[u8]>)]) -> Vec<u8> {
    let mut out = (entries.len() as u32).to_le_bytes().to_vec();
    for (name, data) in entries {
        out.extend_from_slice(&(name.len() as u32).to_le_bytes());
        let (flags, bytes) = match data {
            Some(bytes) => (AXION_FILE_PRESENT, *bytes),
            None => (AXION_FILE_ABSENT, &[][..]),
        };
        out.extend_from_slice(&flags.to_le_bytes());
        out.extend_from_slice(&(bytes.len() as u64).to_le_bytes());
        out.extend_from_slice(name.as_bytes());
        out.extend_from_slice(bytes);
    }
    out
}

/// Écrit la source et sa table dans `ASSET_IN`, et lance la compilation.
fn submit(ctx: u64, format: u32, source: &[u8], files: &[u8]) -> Result<u32, i32> {
    let mut asset_in = empty_info();
    let total = source.len() + files.len();
    // SAFETY: `asset_in` est accessible en écriture.
    let code = unsafe {
        axion_buffer_acquire(
            ctx,
            BufferKind::AssetIn.as_u32(),
            total as u64,
            &raw mut asset_in,
        )
    };
    assert_eq!(code, AXION_OK);
    // SAFETY: le tampon appartient au contexte vivant et fait au moins `capacity` octets.
    let view = unsafe {
        std::slice::from_raw_parts_mut(asset_in.ptr, usize::try_from(asset_in.capacity).unwrap())
    };
    view[HEADER_BYTES..HEADER_BYTES + source.len()].copy_from_slice(source);
    view[HEADER_BYTES + source.len()..HEADER_BYTES + total].copy_from_slice(files);

    let mut job: u32 = 0;
    // SAFETY: aucun paramètre d'options ; `job` est une variable locale.
    let code = unsafe {
        axion_asset_compile(
            ctx,
            ASSET_ID,
            format,
            source.len() as u64,
            files.len() as u64,
            std::ptr::null(),
            0,
            &raw mut job,
        )
    };
    // SAFETY: aucun pointeur déréférencé.
    let released =
        unsafe { axion_buffer_release(ctx, BufferKind::AssetIn.as_u32(), asset_in.generation) };
    assert_eq!(released, AXION_OK);
    if code == AXION_OK {
        Ok(job)
    } else {
        Err(code)
    }
}

/// Sonde un travail jusqu'à son issue, en relisant `ASSET_OUT` quand il porte quelque chose.
fn settle(ctx: u64, job: u32) -> Outcome {
    let echeance = std::time::Instant::now() + std::time::Duration::from_secs(30);
    let mut status = AXION_ASSET_PENDING;
    let mut size: u64 = 0;
    let mut error: i32 = 0;
    while status == AXION_ASSET_PENDING && std::time::Instant::now() < echeance {
        // SAFETY: les trois pointeurs de sortie sont des variables locales.
        let code =
            unsafe { axion_asset_poll(ctx, job, &raw mut status, &raw mut size, &raw mut error) };
        assert_eq!(code, AXION_OK, "sondage refusé");
        std::thread::yield_now();
    }
    let payload = |size: u64| {
        let mut out = empty_info();
        // SAFETY: `out` est accessible en écriture.
        let code =
            unsafe { axion_buffer_acquire(ctx, BufferKind::AssetOut.as_u32(), 0, &raw mut out) };
        assert_eq!(code, AXION_OK);
        // SAFETY: le tampon appartient au contexte vivant et fait au moins `capacity` octets.
        let view =
            unsafe { std::slice::from_raw_parts(out.ptr, usize::try_from(out.capacity).unwrap()) };
        let bytes = view[HEADER_BYTES..HEADER_BYTES + usize::try_from(size).unwrap()].to_vec();
        // SAFETY: aucun pointeur déréférencé.
        assert_eq!(
            unsafe { axion_buffer_release(ctx, out.kind, out.generation) },
            AXION_OK
        );
        bytes
    };
    match status {
        AXION_ASSET_COMPILED => Outcome::Compiled(payload(size)),
        AXION_ASSET_FAILED => Outcome::Failed(error),
        AXION_ASSET_NEEDS_FILES => Outcome::NeedsFiles(decode_names(&payload(size))),
        _ => panic!("compilation toujours en cours après l'échéance"),
    }
}

/// La liste de `NEEDS_FILES` : `u32` du nombre, puis `u32` de longueur et octets par nom.
fn decode_names(bytes: &[u8]) -> Vec<String> {
    let count = u32::from_le_bytes(bytes[..4].try_into().unwrap()) as usize;
    let mut at = 4;
    let mut names = Vec::with_capacity(count);
    for _ in 0..count {
        let len = u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap()) as usize;
        names.push(String::from_utf8(bytes[at + 4..at + 4 + len].to_vec()).unwrap());
        at += 4 + len;
    }
    assert_eq!(at, bytes.len(), "reste après la liste");
    names
}

fn default_max_source_bytes() -> u64 {
    match find(ConfigScope::Common, "assets.max_source_bytes").map(|option| option.default) {
        Some(ConfigValue::Int(value)) => u64::try_from(value).unwrap(),
        other => panic!("assets.max_source_bytes : {other:?}"),
    }
}

/// Ce qu'`axion-cli` compile de la même source, ses voisins sur le disque : le résolveur joint
/// la forme normale de la référence au répertoire de la source, et une lecture qui échoue rend
/// « rien ».
fn cli(format: SourceFormat, source: &[u8], disk: &BTreeMap<&str, &[u8]>) -> Result<Vec<u8>, i32> {
    let options = CompileOptions {
        asset_id: ASSET_ID,
        source_hash: source_hash(source),
        limits: ImportLimits::new(default_max_source_bytes()),
        dynamic_body: true,
        authored: AssetOptions::DEFAULT,
    };
    compile(source, format, &options, |reference| {
        let name = normalize_reference(reference)?;
        disk.get(name.as_str()).map(|bytes| bytes.to_vec())
    })
    .map(|asset| asset.bytes)
    .map_err(|error| error.code())
}

const OBJ_SOURCE: &[u8] = b"mtllib cube.mtl
usemtl rouge
v 0.0 0.0 0.0
v 1.0 0.0 0.0
v 0.0 1.0 0.0
f 1 2 3
";

const OBJ_SOUS_DOSSIER: &[u8] = b"mtllib ./sub//cube.mtl
usemtl rouge
v 0.0 0.0 0.0
v 1.0 0.0 0.0
v 0.0 1.0 0.0
f 1 2 3
";

const MTL: &[u8] = b"newmtl rouge
Kd 0.9 0.1 0.1
";

/// Un triangle glTF dont les positions et les indices vivent dans deux buffers externes.
fn gltf_two_buffers() -> (Vec<u8>, Vec<u8>, Vec<u8>) {
    let positions: Vec<u8> = [0.0f32, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0]
        .iter()
        .flat_map(|v| v.to_le_bytes())
        .collect();
    let indices: Vec<u8> = [0u16, 1, 2, 0]
        .iter()
        .flat_map(|v| v.to_le_bytes())
        .collect();
    let json = String::from(
        r#"{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
        "nodes":[{"mesh":0}],
        "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}],
        "accessors":[
          {"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},
          {"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"}],
        "bufferViews":[{"buffer":0,"byteLength":36},{"buffer":1,"byteLength":6}],
        "buffers":[{"uri":"a.bin","byteLength":36},{"uri":"b.bin","byteLength":8}]}"#,
    );
    (json.into_bytes(), positions, indices)
}

#[test]
fn adr139_les_fichiers_voisins_se_demandent_puis_se_fournissent_comme_dans_la_cli() {
    let mut ctx: u64 = 0;
    // SAFETY: `ctx` est une variable locale accessible en écriture.
    let code = unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut ctx) };
    assert_eq!(code, AXION_OK, "initialisation refusée");

    // OBJ : le `.mtl` est demandé, puis fourni ; l'asset est celui de la CLI.
    assert_eq!(
        settle(ctx, submit(ctx, OBJ, OBJ_SOURCE, &[]).unwrap()),
        Outcome::NeedsFiles(vec!["cube.mtl".to_owned()])
    );
    let avec = settle(
        ctx,
        submit(ctx, OBJ, OBJ_SOURCE, &table(&[("cube.mtl", Some(MTL))])).unwrap(),
    );
    let disque = BTreeMap::from([("cube.mtl", MTL)]);
    let reference = cli(SourceFormat::Obj, OBJ_SOURCE, &disque).expect("CLI");
    assert_eq!(avec, Outcome::Compiled(reference.clone()));

    // Absent : comme une lecture qui échoue dans la CLI — matériau par défaut, autre asset.
    let sans = settle(
        ctx,
        submit(ctx, OBJ, OBJ_SOURCE, &table(&[("cube.mtl", None)])).unwrap(),
    );
    let reference_sans = cli(SourceFormat::Obj, OBJ_SOURCE, &BTreeMap::new()).expect("CLI");
    assert_eq!(sans, Outcome::Compiled(reference_sans.clone()));
    assert_ne!(reference, reference_sans, "le .mtl doit changer l'asset");

    // Une référence qui n'est pas sous sa forme normale est demandée sous sa forme normale.
    assert_eq!(
        settle(ctx, submit(ctx, OBJ, OBJ_SOUS_DOSSIER, &[]).unwrap()),
        Outcome::NeedsFiles(vec!["sub/cube.mtl".to_owned()])
    );
    let disque_sous = BTreeMap::from([("sub/cube.mtl", MTL)]);
    assert_eq!(
        settle(
            ctx,
            submit(
                ctx,
                OBJ,
                OBJ_SOUS_DOSSIER,
                &table(&[("sub/cube.mtl", Some(MTL))])
            )
            .unwrap()
        ),
        Outcome::Compiled(cli(SourceFormat::Obj, OBJ_SOUS_DOSSIER, &disque_sous).expect("CLI"))
    );

    // glTF à deux buffers externes : les deux sont demandés en un seul passage.
    let (gltf, a, b) = gltf_two_buffers();
    assert_eq!(
        settle(ctx, submit(ctx, GLTF, &gltf, &[]).unwrap()),
        Outcome::NeedsFiles(vec!["a.bin".to_owned(), "b.bin".to_owned()])
    );
    let fichiers = table(&[("a.bin", Some(&a)), ("b.bin", Some(&b))]);
    let disque_gltf = BTreeMap::from([("a.bin", a.as_slice()), ("b.bin", b.as_slice())]);
    assert_eq!(
        settle(ctx, submit(ctx, GLTF, &gltf, &fichiers).unwrap()),
        Outcome::Compiled(cli(SourceFormat::Gltf, &gltf, &disque_gltf).expect("CLI"))
    );
    // Un buffer absent : l'échec de la CLI, avec son code.
    let absent = table(&[("a.bin", Some(&a)), ("b.bin", None)]);
    let disque_partiel = BTreeMap::from([("a.bin", a.as_slice())]);
    let code_cli =
        cli(SourceFormat::Gltf, &gltf, &disque_partiel).expect_err("CLI : buffer absent");
    assert_eq!(
        settle(ctx, submit(ctx, GLTF, &gltf, &absent).unwrap()),
        Outcome::Failed(code_cli)
    );

    // Une table mal formée est refusée à la soumission, sans travail lancé.
    let mut reste = table(&[("cube.mtl", Some(MTL))]);
    reste.push(0);
    let mut tronquee = table(&[("cube.mtl", Some(MTL))]);
    tronquee.pop();
    let mut drapeau = table(&[("cube.mtl", None)]);
    drapeau[8..12].copy_from_slice(&2u32.to_le_bytes());
    let mut absent_avec_octets = table(&[("cube.mtl", Some(MTL))]);
    absent_avec_octets[8..12].copy_from_slice(&AXION_FILE_ABSENT.to_le_bytes());
    for (fichiers, cas) in [
        (table(&[("./cube.mtl", Some(MTL))]), "nom non normalisé"),
        (table(&[("../cube.mtl", Some(MTL))]), "nom qui remonte"),
        (
            table(&[("cube.mtl", Some(MTL)), ("cube.mtl", None)]),
            "doublon",
        ),
        (table(&[("", Some(MTL))]), "nom vide"),
        (reste, "octet après la table"),
        (tronquee, "table tronquée"),
        (drapeau, "drapeau inconnu"),
        (absent_avec_octets, "absent avec des octets"),
        (
            (MAX_SIBLING_FILES + 1).to_le_bytes().to_vec(),
            "trop d'entrées",
        ),
    ] {
        assert_eq!(
            submit(ctx, OBJ, OBJ_SOURCE, &fichiers),
            Err(AXION_E_INVALID_BUFFER),
            "{cas}"
        );
    }

    // SAFETY: aucun pointeur déréférencé.
    unsafe {
        assert_eq!(axion_shutdown(ctx), AXION_OK, "bilan d'arrêt");
    }
}
