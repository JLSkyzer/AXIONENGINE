//! ADR-148 par l'ABI : le sondage d'une compilation finie retient ses lignes — ses
//! avertissements, ou le message de son échec —, qu'`axion_asset_diagnostics` dépose une fois
//! dans `ASSET_OUT`, puis oublie.
//!
//! Un seul test, son propre binaire : la session native est globale au processus.

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

use ax_model::buffer::{BufferKind, HEADER_BYTES};
use axion_native::abi::{
    axion_asset_cancel, axion_asset_compile, axion_asset_diagnostics, axion_asset_poll,
    axion_buffer_acquire, axion_buffer_release, axion_init, axion_shutdown, AxionBufferInfo,
    AXION_ASSET_COMPILED, AXION_ASSET_FAILED, AXION_ASSET_NEEDS_FILES, AXION_ASSET_PENDING,
    AXION_E_INVALID_BUFFER, AXION_E_INVALID_HANDLE, AXION_FILE_PRESENT, AXION_OK,
    AXION_SIDE_SERVER,
};
use axion_native::context;

const ASSET_ID: u64 = 0x0A51_0C0B_0000_0148;
/// Formats d'`axion_asset_compile` : glTF texte et OBJ.
const GLTF: u32 = 1;
const OBJ: u32 = 2;
/// Un triangle sans rien à redire.
const TRIANGLE: &[u8] = b"v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n";
/// Un triangle qui cite son `.mtl` : sans lui, la compilation le demande (ADR-139).
const AVEC_MTL: &[u8] = b"mtllib cube.mtl\nusemtl rouge\nv 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n";
/// L'avertissement de R-530 qu'attend le triangle glTF.
const AVERTISSEMENT: &str = "extension ignorée : KHR_materials_variants";

fn empty_info() -> AxionBufferInfo {
    AxionBufferInfo {
        ptr: std::ptr::null_mut(),
        capacity: 0,
        kind: 0,
        generation: 0,
    }
}

/// Un triangle glTF qui cite dans `extensionsUsed` une extension non prise en charge : R-530
/// l'ignore avec avertissement. Son buffer, `a.bin`, voyage en fichier voisin (ADR-139).
fn gltf_avec_extension() -> (Vec<u8>, Vec<u8>) {
    let mut buffer: Vec<u8> = [0.0f32, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0]
        .iter()
        .flat_map(|v| v.to_le_bytes())
        .collect();
    buffer.extend([0u16, 1, 2, 0].iter().flat_map(|v| v.to_le_bytes()));
    let json = r#"{"asset":{"version":"2.0"},"extensionsUsed":["KHR_materials_variants"],
        "scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
        "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}],
        "accessors":[
          {"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},
          {"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"}],
        "bufferViews":[{"buffer":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":6}],
        "buffers":[{"uri":"a.bin","byteLength":44}]}"#;
    (json.as_bytes().to_vec(), buffer)
}

/// Une table de fichiers voisins présents (ADR-139).
fn table(entries: &[(&str, &[u8])]) -> Vec<u8> {
    let mut out = (entries.len() as u32).to_le_bytes().to_vec();
    for (name, bytes) in entries {
        out.extend_from_slice(&(name.len() as u32).to_le_bytes());
        out.extend_from_slice(&AXION_FILE_PRESENT.to_le_bytes());
        out.extend_from_slice(&(bytes.len() as u64).to_le_bytes());
        out.extend_from_slice(name.as_bytes());
        out.extend_from_slice(bytes);
    }
    out
}

/// Écrit la source et sa table dans `ASSET_IN`, et lance la compilation.
fn submit(ctx: u64, format: u32, source: &[u8], files: &[u8]) -> u32 {
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
    assert_eq!(code, AXION_OK, "soumission refusée");
    job
}

/// Sonde une fois : `(code de l'appel, état, taille, code d'erreur)`.
fn poll(ctx: u64, job: u32) -> (i32, u32, u64, i32) {
    let mut status = u32::MAX;
    let mut size: u64 = 0;
    let mut error: i32 = 0;
    // SAFETY: les trois pointeurs de sortie sont des variables locales.
    let code =
        unsafe { axion_asset_poll(ctx, job, &raw mut status, &raw mut size, &raw mut error) };
    (code, status, size, error)
}

/// Sonde un travail jusqu'à son issue : `(état, taille, code d'erreur)`.
fn settle(ctx: u64, job: u32) -> (u32, u64, i32) {
    let echeance = std::time::Instant::now() + std::time::Duration::from_secs(30);
    loop {
        let (code, status, size, error) = poll(ctx, job);
        assert_eq!(code, AXION_OK, "sondage refusé");
        if status != AXION_ASSET_PENDING {
            return (status, size, error);
        }
        assert!(
            std::time::Instant::now() < echeance,
            "le travail ne finit pas"
        );
        std::thread::yield_now();
    }
}

/// Une vue d'`ASSET_OUT`, sans le libérer : le libérer rend son emplacement au pool.
fn vue(ctx: u64) -> AxionBufferInfo {
    let mut out = empty_info();
    // SAFETY: `out` est accessible en écriture.
    let code = unsafe { axion_buffer_acquire(ctx, BufferKind::AssetOut.as_u32(), 0, &raw mut out) };
    assert_eq!(code, AXION_OK);
    out
}

/// Les `size` premiers octets de la charge utile d'une vue.
fn lire(out: &AxionBufferInfo, size: u64) -> Vec<u8> {
    // SAFETY: le tampon appartient au contexte vivant et fait au moins `capacity` octets.
    let view =
        unsafe { std::slice::from_raw_parts(out.ptr, usize::try_from(out.capacity).unwrap()) };
    view[HEADER_BYTES..HEADER_BYTES + usize::try_from(size).unwrap()].to_vec()
}

fn liberer(ctx: u64, out: &AxionBufferInfo) {
    // SAFETY: aucun pointeur déréférencé.
    assert_eq!(
        unsafe { axion_buffer_release(ctx, out.kind, out.generation) },
        AXION_OK
    );
}

/// Les `size` premiers octets de la charge utile d'`ASSET_OUT`, que Java libère après lecture.
fn asset_out(ctx: u64, size: u64) -> Vec<u8> {
    let out = vue(ctx);
    let bytes = lire(&out, size);
    liberer(ctx, &out);
    bytes
}

/// `axion_asset_diagnostics` : `(code, taille)`.
fn diagnostics(ctx: u64, job: u32) -> (i32, u64) {
    let mut size = u64::MAX;
    // SAFETY: `size` est une variable locale.
    let code = unsafe { axion_asset_diagnostics(ctx, job, &raw mut size) };
    (code, size)
}

/// Les lignes que la fonction vient de déposer dans `ASSET_OUT`.
fn lignes(ctx: u64, job: u32) -> Vec<String> {
    let (code, size) = diagnostics(ctx, job);
    assert_eq!(code, AXION_OK, "lignes refusées");
    let bytes = asset_out(ctx, size);
    let count = u32::from_le_bytes(bytes[..4].try_into().unwrap()) as usize;
    let mut at = 4;
    let mut out = Vec::with_capacity(count);
    for _ in 0..count {
        let len = u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap()) as usize;
        out.push(String::from_utf8(bytes[at + 4..at + 4 + len].to_vec()).unwrap());
        at += 4 + len;
    }
    assert_eq!(at, bytes.len(), "reste après la liste");
    out
}

/// Compilations que la session retient.
fn held(ctx: u64) -> usize {
    context::with(ctx, false, |session| session.asset_jobs_held()).unwrap()
}

/// Valeur d'une métrique de la session.
fn metrique(ctx: u64, nom: &str) -> u64 {
    let json = context::with(ctx, false, |session| session.metrics_json()).unwrap();
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
fn adr148_les_lignes_d_une_compilation_se_lisent_une_fois_apres_son_sondage() {
    let mut ctx: u64 = 0;
    // SAFETY: `ctx` est une variable locale accessible en écriture.
    let code = unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut ctx) };
    assert_eq!(code, AXION_OK, "initialisation refusée");
    let (gltf, bin) = gltf_avec_extension();
    let voisins = table(&[("a.bin", &bin)]);

    // Un avertissement connu (R-530) : l'asset compile, sa ligne attend après le sondage.
    let job = submit(ctx, GLTF, &gltf, &voisins);
    let (status, _, _) = settle(ctx, job);
    assert_eq!(status, AXION_ASSET_COMPILED);
    assert_eq!(held(ctx), 1, "les lignes attendent dans la table");
    // Repris, il n'est plus sondable, même avant la lecture de ses lignes.
    assert_eq!(poll(ctx, job).0, AXION_E_INVALID_HANDLE, "sondé deux fois");
    assert_eq!(lignes(ctx, job), vec![AVERTISSEMENT]);
    // Une fois : ni relire, ni sonder de nouveau.
    assert_eq!(diagnostics(ctx, job).0, AXION_E_INVALID_HANDLE, "relecture");
    assert_eq!(poll(ctx, job).0, AXION_E_INVALID_HANDLE, "second sondage");
    assert_eq!(held(ctx), 0);
    assert_eq!(metrique(ctx, "axion.asset.warnings"), 1);

    // Sans avertissement : aucune ligne, et `ASSET_OUT` garde l'asset du sondage — même
    // tampon, mêmes octets.
    let job = submit(ctx, OBJ, TRIANGLE, &[]);
    let (status, size, _) = settle(ctx, job);
    assert_eq!(status, AXION_ASSET_COMPILED);
    let avant = vue(ctx);
    let asset = lire(&avant, size);
    assert_eq!(diagnostics(ctx, job), (AXION_OK, 0));
    let apres = vue(ctx);
    assert_eq!(apres.generation, avant.generation, "ASSET_OUT réalloué");
    assert_eq!(lire(&apres, size), asset, "ASSET_OUT touché sans ligne");
    liberer(ctx, &apres);
    assert_eq!(diagnostics(ctx, job).0, AXION_E_INVALID_HANDLE);
    assert_eq!(metrique(ctx, "axion.asset.warnings"), 1);

    // Un échec : son message, en une ligne, en plus de son code (ADR-148 D3).
    let job = submit(ctx, GLTF, b"pas du json", &[]);
    let (status, _, error) = settle(ctx, job);
    assert_eq!(status, AXION_ASSET_FAILED);
    assert!(error < 0, "code d'échec : {error}");
    let message = lignes(ctx, job);
    assert_eq!(message.len(), 1, "{message:?}");
    assert!(message[0].starts_with("import : "), "{message:?}");
    assert_eq!(diagnostics(ctx, job).0, AXION_E_INVALID_HANDLE);

    // Une demande de fichiers voisins ne garde rien : le passage sera rejoué.
    let job = submit(ctx, OBJ, AVEC_MTL, &[]);
    let (status, size, _) = settle(ctx, job);
    assert_eq!(status, AXION_ASSET_NEEDS_FILES);
    let _ = asset_out(ctx, size);
    assert_eq!(diagnostics(ctx, job).0, AXION_E_INVALID_HANDLE);
    assert_eq!(held(ctx), 0);

    // Abandonner un travail sondé libère ses lignes (ADR-140).
    let job = submit(ctx, GLTF, &gltf, &voisins);
    let (status, size, _) = settle(ctx, job);
    assert_eq!(status, AXION_ASSET_COMPILED);
    let _ = asset_out(ctx, size);
    assert_eq!(held(ctx), 1);
    // SAFETY: aucun pointeur déréférencé.
    assert_eq!(unsafe { axion_asset_cancel(ctx, job) }, AXION_OK);
    assert_eq!(held(ctx), 0, "l'abandon a laissé les lignes");
    assert_eq!(diagnostics(ctx, job).0, AXION_E_INVALID_HANDLE);

    // Ce qui ne désigne rien : un travail jamais émis, un contexte forgé, un pointeur nul.
    assert_eq!(diagnostics(ctx, 4242).0, AXION_E_INVALID_HANDLE);
    assert_eq!(diagnostics(ctx ^ 1, job).0, AXION_E_INVALID_HANDLE);
    // SAFETY: le pointeur nul est l'objet de l'appel ; il n'est pas déréférencé.
    let nul = unsafe { axion_asset_diagnostics(ctx, job, std::ptr::null_mut()) };
    assert_eq!(nul, AXION_E_INVALID_BUFFER);

    // SAFETY: le contexte est vivant.
    assert_eq!(
        unsafe { axion_shutdown(ctx) },
        AXION_OK,
        "arrêt déséquilibré"
    );
}
