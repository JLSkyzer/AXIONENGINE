//! IF-06 / ADR-122 §6 exercé par l'ABI comme Java le fera : un panneau au
//! matériau texturé — albedo d'un PNG embarqué, émissive d'une texture de
//! ressource — se charge avec `MATL | TEXR` ; sa table revient par
//! `axion_asset_materials`, son PNG par `axion_asset_texture`, l'en-tête
//! d'`ASSET_OUT` annonçant le schéma 1. Les handles rendus, l'arrêt est
//! équilibré, octets de l'arène `PERSISTENT` compris.
//!
//! Un seul test, son propre binaire : la session native est globale au processus.

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

use ax_asset::a3d::{A3dFile, A3dLimits};
use ax_asset::collider::ColliderMode;
use ax_asset::compile::{compile, CompileOptions};
use ax_asset::import::{ImportLimits, SourceFormat};
use ax_asset::optimize::LodOptions;
use ax_asset::png;
use ax_model::buffer::{BufferHeader, BufferKind, HEADER_BYTES};
use ax_model::dm::handle::Handle;
use ax_model::dm::material::{texture_sampler, texture_source, MaterialDesc, TextureDesc};
use ax_model::dm::render::{material_transfer_len, MATERIAL_TRANSFER_HEADER_BYTES};
use axion_native::abi::{
    axion_asset_load, axion_asset_materials, axion_asset_texture, axion_asset_unload,
    axion_buffer_acquire, axion_buffer_release, axion_init, axion_shutdown, AxionBufferInfo,
    AXION_E_INVALID_BUFFER, AXION_E_INVALID_HANDLE, AXION_OK, AXION_SECTION_GEOM,
    AXION_SECTION_MATL, AXION_SECTION_NODE, AXION_SECTION_TEXR, AXION_SIDE_CLIENT,
};

static CONTEXTE: std::sync::Mutex<()> = std::sync::Mutex::new(());

const ASSET_ID: u64 = 0x0A51_0C0B_E000_0122;
const NODE_GEOM: u32 = AXION_SECTION_NODE | AXION_SECTION_GEOM;
const RENDU: u32 = NODE_GEOM | AXION_SECTION_MATL | AXION_SECTION_TEXR;

/// Chemin de la texture émissive, que Java résout contre le répertoire du
/// modèle (ADR-122 §2).
const LUEUR: &[u8] = b"tex/lueur.png";

/// Produit par le compilateur 6, avant ADR-122 : `MATL` à la disposition
/// provisoire, pas de `TEXR`.
const MATL_PROVISOIRE: &[u8] =
    include_bytes!("../../ax-asset/tests/fixtures/a3d/v1.1-matl-provisoire.a3d");

/// Un triangle à UV, son matériau « peint » : albedo du PNG que porte la
/// `bufferView` 3, filtré au plus proche ; émissive d'une ressource. `PNG_LEN`
/// et `BIN_LEN` sont remplacés à la construction.
const PANNEAU_GLTF: &str = r#"{
  "asset": { "version": "2.0" },
  "scene": 0,
  "scenes": [{ "nodes": [0] }],
  "nodes": [{ "name": "panneau", "mesh": 0 }],
  "meshes": [{ "name": "panneau", "primitives": [{
    "attributes": { "POSITION": 0, "TEXCOORD_0": 1 }, "indices": 2, "material": 0 }] }],
  "materials": [{
    "name": "peint",
    "pbrMetallicRoughness": { "baseColorTexture": { "index": 0 } },
    "emissiveFactor": [1.0, 1.0, 1.0],
    "emissiveTexture": { "index": 1 } }],
  "textures": [{ "source": 0, "sampler": 0 }, { "source": 1 }],
  "samplers": [{ "magFilter": 9728 }],
  "images": [{ "bufferView": 3, "mimeType": "image/png" }, { "uri": "tex/lueur.png" }],
  "accessors": [
    { "bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3",
      "min": [0.0, 0.0, 0.0], "max": [1.0, 1.0, 0.0] },
    { "bufferView": 1, "componentType": 5126, "count": 3, "type": "VEC2" },
    { "bufferView": 2, "componentType": 5123, "count": 3, "type": "SCALAR" }],
  "bufferViews": [
    { "buffer": 0, "byteOffset": 0, "byteLength": 36 },
    { "buffer": 0, "byteOffset": 36, "byteLength": 24 },
    { "buffer": 0, "byteOffset": 60, "byteLength": 6 },
    { "buffer": 0, "byteOffset": 68, "byteLength": PNG_LEN }],
  "buffers": [{ "byteLength": BIN_LEN }]
}"#;

/// En-tête PNG d'une image de 2×2 — signature et `IHDR` : ce que le natif
/// contrôle, sans rien décoder (R-532).
fn png_2x2() -> Vec<u8> {
    let mut png = png::SIGNATURE.to_vec();
    png.extend_from_slice(&13u32.to_be_bytes());
    png.extend_from_slice(b"IHDR");
    png.extend_from_slice(&2u32.to_be_bytes());
    png.extend_from_slice(&2u32.to_be_bytes());
    // Profondeur, couleur, compression, filtre, entrelacement, puis un CRC que
    // personne ne vérifie ici.
    png.extend_from_slice(&[8, 6, 0, 0, 0, 0, 0, 0, 0]);
    png
}

/// Le panneau en glTF binaire : en-tête, morceau JSON, morceau binaire, chacun
/// complété à un multiple de quatre octets.
fn panneau_glb(png: &[u8]) -> Vec<u8> {
    let mut bin = Vec::new();
    let positions = [0.0f32, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0];
    let uvs = [0.0f32, 0.0, 1.0, 0.0, 0.0, 1.0];
    for value in positions.into_iter().chain(uvs) {
        bin.extend_from_slice(&value.to_le_bytes());
    }
    for index in [0u16, 1, 2] {
        bin.extend_from_slice(&index.to_le_bytes());
    }
    bin.resize(68, 0);
    bin.extend_from_slice(png);

    let json = PANNEAU_GLTF
        .replace("PNG_LEN", &png.len().to_string())
        .replace("BIN_LEN", &bin.len().to_string());
    let padded = |mut chunk: Vec<u8>, filler: u8| {
        chunk.resize(chunk.len().next_multiple_of(4), filler);
        chunk
    };
    let json = padded(json.into_bytes(), b' ');
    let bin = padded(bin, 0);

    let total = 12 + 8 + json.len() + 8 + bin.len();
    let mut glb = Vec::with_capacity(total);
    glb.extend_from_slice(b"glTF");
    glb.extend_from_slice(&2u32.to_le_bytes());
    glb.extend_from_slice(&u32::try_from(total).unwrap().to_le_bytes());
    for (kind, chunk) in [(b"JSON", &json), (b"BIN\0", &bin)] {
        glb.extend_from_slice(&u32::try_from(chunk.len()).unwrap().to_le_bytes());
        glb.extend_from_slice(kind);
        glb.extend_from_slice(chunk);
    }
    glb
}

/// Le panneau compilé de bout en bout, comme le registre d'assets le reçoit.
fn panneau(png: &[u8]) -> Vec<u8> {
    let options = CompileOptions {
        asset_id: ASSET_ID,
        source_hash: 0,
        limits: ImportLimits::new(1 << 20),
        dynamic_body: true,
        lod: LodOptions::DEFAULT,
        collider_mode: ColliderMode::None,
    };
    compile(&panneau_glb(png), SourceFormat::Glb, &options, |_| None)
        .expect("compilation refusée")
        .bytes
}

/// Acquiert un tampon et rend sa description.
fn acquire(ctx: u64, kind: BufferKind, min: u64) -> AxionBufferInfo {
    let mut out = AxionBufferInfo {
        ptr: std::ptr::null_mut(),
        capacity: 0,
        kind: 0,
        generation: 0,
    };
    let code = unsafe { axion_buffer_acquire(ctx, kind.as_u32(), min, &raw mut out) };
    assert_eq!(code, AXION_OK, "acquisition de {kind:?}");
    assert!(!out.ptr.is_null());
    out
}

fn release(ctx: u64, buffer: AxionBufferInfo) {
    assert_eq!(
        unsafe { axion_buffer_release(ctx, buffer.kind, buffer.generation) },
        AXION_OK,
        "relâchement du tampon {}",
        buffer.kind
    );
}

/// Écrit le conteneur dans `ASSET_IN`, le charge sous chaque masque donné, puis
/// relâche le tampon ; rend le résultat de chaque chargement.
fn load_each(ctx: u64, container: &[u8], masks: &[u32]) -> Vec<Result<Handle, i32>> {
    let asset_id = A3dFile::open(container, A3dLimits::new(1 << 20))
        .expect("conteneur")
        .header()
        .asset_id;
    let buffer = acquire(ctx, BufferKind::AssetIn, container.len() as u64);
    let view = unsafe {
        std::slice::from_raw_parts_mut(buffer.ptr, usize::try_from(buffer.capacity).unwrap())
    };
    view[HEADER_BYTES..HEADER_BYTES + container.len()].copy_from_slice(container);
    let results = masks
        .iter()
        .map(|&mask| {
            let mut handle = Handle::ABSENT;
            match unsafe { axion_asset_load(ctx, asset_id, mask, &raw mut handle) } {
                AXION_OK => Ok(handle),
                code => Err(code),
            }
        })
        .collect();
    release(ctx, buffer);
    results
}

/// Lit la charge que le dernier dépôt a laissée dans `ASSET_OUT`, après avoir
/// vérifié que l'en-tête annonce le schéma 1 (ADR-122 §6) — ce que Java vérifie
/// avant toute lecture.
fn read_asset_out(ctx: u64, size: u64) -> Vec<u8> {
    let buffer = acquire(ctx, BufferKind::AssetOut, 0);
    let view = unsafe {
        std::slice::from_raw_parts(buffer.ptr, usize::try_from(buffer.capacity).unwrap())
    };
    let header = BufferHeader::read(view).expect("en-tête d'ASSET_OUT");
    assert_eq!(header.kind, BufferKind::AssetOut);
    assert_eq!(header.schema_version, 1, "ASSET_OUT au schéma 1");
    let payload = view[HEADER_BYTES..HEADER_BYTES + usize::try_from(size).unwrap()].to_vec();
    release(ctx, buffer);
    payload
}

fn materials(ctx: u64, handle: Handle) -> Result<Vec<u8>, i32> {
    let mut size = 0u64;
    match unsafe { axion_asset_materials(ctx, handle, &raw mut size) } {
        AXION_OK => Ok(read_asset_out(ctx, size)),
        code => Err(code),
    }
}

fn texture(ctx: u64, handle: Handle, rank: u32) -> Result<Vec<u8>, i32> {
    let mut size = 0u64;
    match unsafe { axion_asset_texture(ctx, handle, rank, &raw mut size) } {
        AXION_OK => Ok(read_asset_out(ctx, size)),
        code => Err(code),
    }
}

fn u32_at(bytes: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap())
}

fn texture_entry(table: &[u8], materials: usize, rank: u16) -> TextureDesc {
    let at = MATERIAL_TRANSFER_HEADER_BYTES
        + materials * MaterialDesc::BYTES
        + usize::from(rank) * TextureDesc::BYTES;
    TextureDesc::read_le(table[at..at + TextureDesc::BYTES].try_into().unwrap())
}

#[test]
fn les_materiaux_et_les_textures_traversent_l_abi() {
    let _contexte = CONTEXTE
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);

    let mut ctx = 0u64;
    assert_eq!(
        unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_CLIENT, &raw mut ctx) },
        AXION_OK,
        "init"
    );

    // MATL et TEXR se demandent ensemble : l'un sans l'autre est refusé net,
    // sans rien ranger.
    let png = png_2x2();
    let container = panneau(&png);
    let masks = [
        NODE_GEOM | AXION_SECTION_MATL,
        NODE_GEOM | AXION_SECTION_TEXR,
        AXION_SECTION_MATL,
        RENDU,
        NODE_GEOM,
    ];
    let loaded = load_each(ctx, &container, &masks);
    for (mask, result) in masks.iter().zip(&loaded).take(3) {
        assert_eq!(*result, Err(AXION_E_INVALID_BUFFER), "masque {mask:#b}");
    }
    let rendu = loaded[3].expect("chargement MATL | TEXR");
    let geometrie_seule = loaded[4].expect("chargement NODE | GEOM");

    // La table : un matériau, deux textures, un chemin.
    let table = materials(ctx, rendu).expect("table des matériaux");
    assert_eq!(
        [0, 4, 8, 12].map(|at| u32_at(&table, at)),
        [1, 2, LUEUR.len() as u32, 0]
    );
    assert_eq!(Some(table.len()), material_transfer_len(1, 2, LUEUR.len()));
    let material = MaterialDesc::read_le(
        table[MATERIAL_TRANSFER_HEADER_BYTES..MATERIAL_TRANSFER_HEADER_BYTES + MaterialDesc::BYTES]
            .try_into()
            .unwrap(),
    );
    let albedo = texture_entry(&table, 1, material.albedo_tex);
    assert_eq!(albedo.source, texture_source::EMBEDDED);
    assert_eq!((albedo.width, albedo.height), (2, 2));
    assert_eq!(
        (albedo.data_offset, albedo.data_size as usize),
        (0, png.len()),
        "un PNG ne porte que sa taille"
    );
    assert_eq!(
        texture_sampler::filter(albedo.sampler),
        texture_sampler::FILTER_NEAREST
    );
    let lueur = texture_entry(&table, 1, material.emissive_tex);
    assert_eq!(lueur.source, texture_source::RESOURCE);
    let paths = MATERIAL_TRANSFER_HEADER_BYTES + MaterialDesc::BYTES + 2 * TextureDesc::BYTES;
    let start = paths + lueur.data_offset as usize;
    assert_eq!(&table[start..start + lueur.data_size as usize], LUEUR);

    // Le PNG voyage seul, par son rang, octet pour octet.
    assert_eq!(
        texture(ctx, rendu, u32::from(material.albedo_tex)),
        Ok(png.clone())
    );

    // Une ressource ne voyage pas par ici, ni un rang hors de la table.
    for rank in [u32::from(material.emissive_tex), 2, u32::MAX] {
        assert_eq!(
            texture(ctx, rendu, rank),
            Err(AXION_E_INVALID_BUFFER),
            "rang {rank}"
        );
    }
    assert_eq!(
        unsafe { axion_asset_materials(ctx, rendu, std::ptr::null_mut()) },
        AXION_E_INVALID_BUFFER,
        "sortie nulle"
    );
    assert_eq!(
        unsafe { axion_asset_texture(ctx, rendu, 0, std::ptr::null_mut()) },
        AXION_E_INVALID_BUFFER,
        "sortie nulle"
    );

    // Chargé sans MATL | TEXR : ni table, ni texture.
    assert_eq!(materials(ctx, geometrie_seule), Err(AXION_E_INVALID_BUFFER));
    assert_eq!(
        texture(ctx, geometrie_seule, 0),
        Err(AXION_E_INVALID_BUFFER)
    );

    // Rendus, puis périmés (R-110).
    for handle in [rendu, geometrie_seule] {
        assert_eq!(unsafe { axion_asset_unload(ctx, handle) }, AXION_OK);
    }
    assert_eq!(materials(ctx, rendu), Err(AXION_E_INVALID_HANDLE));
    assert_eq!(texture(ctx, rendu, 0), Err(AXION_E_INVALID_HANDLE));

    // Un asset d'avant ADR-122 se charge avec MATL | TEXR : sa table est vide,
    // le client lui applique le matériau par défaut (R-880).
    let ancien = load_each(ctx, MATL_PROVISOIRE, &[RENDU])[0].expect("chargement");
    assert_eq!(materials(ctx, ancien), Ok(vec![0; 16]));
    assert_eq!(unsafe { axion_asset_unload(ctx, ancien) }, AXION_OK);

    // Tout est rendu : l'arrêt est équilibré, tampons, handles et octets de
    // l'arène PERSISTENT compris (R-322, INV-08).
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK, "arrêt équilibré");
}
