//! ADR-156 D4 par l'ABI : `axion_asset_warnings` rend les tables qu'un asset chargé a refusées sans
//! être refusé lui-même — Java les journalise en nommant l'asset —, rien pour un asset sain, et
//! refuse un handle périmé.
//!
//! Un seul test, son propre binaire : la session native est globale au processus.

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

mod common;

use ax_asset::a3d::{A3dFile, A3dLimits, A3dWriter, SectionTag};
use ax_asset::compile::{compile, CompileOptions};
use ax_asset::import::{ImportLimits, SourceFormat};
use ax_asset::options::AssetOptions;
use ax_model::buffer::{BufferKind, HEADER_BYTES};
use ax_model::dm::handle::Handle;
use axion_native::abi::{
    axion_asset_load, axion_asset_unload, axion_asset_warnings, axion_init, axion_shutdown,
    AXION_E_INVALID_HANDLE, AXION_OK, AXION_SECTION_GEOM, AXION_SECTION_NODE, AXION_SIDE_SERVER,
};
use common::{acquerir, ecrire, relacher};

/// Un cube unité en OBJ, compilé par le compilateur de production sous `asset_id`.
fn cube(asset_id: u64) -> Vec<u8> {
    let obj = "v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nf 1 2 3\nf 1 3 4\n";
    let options = CompileOptions {
        asset_id,
        source_hash: 0,
        limits: ImportLimits::new(1 << 20),
        dynamic_body: true,
        authored: AssetOptions::DEFAULT,
    };
    compile(obj.as_bytes(), SourceFormat::Obj, &options, |_| None)
        .expect("compilation")
        .bytes
}

/// `source` réécrit, avec une table `LODM` illisible en plus.
fn avec_lodm_illisible(source: &[u8]) -> Vec<u8> {
    let limits = A3dLimits::new(1 << 24);
    let file = A3dFile::open(source, limits).expect("conteneur");
    let header = file.header();
    let mut writer = A3dWriter::new(header.asset_id, header.source_hash, header.compiler_version);
    for entry in file.entries() {
        let bytes = file.section(entry.tag).expect("lisible").expect("présente");
        writer.section(entry.tag, &bytes).expect("écriture");
    }
    writer.section(SectionTag::LODM, &[1, 2, 3]).expect("LODM");
    writer.finish().expect("écriture")
}

/// Charge `a3d` sous `asset_id`, avec `NODE | GEOM`.
fn charger(ctx: u64, asset_id: u64, a3d: &[u8]) -> Handle {
    let entree = ecrire(ctx, a3d);
    let mut handle = Handle::ABSENT;
    let code = unsafe {
        axion_asset_load(
            ctx,
            asset_id,
            AXION_SECTION_NODE | AXION_SECTION_GEOM,
            &raw mut handle,
        )
    };
    relacher(ctx, entree);
    assert_eq!(code, AXION_OK, "chargement");
    handle
}

/// Lit un `u32` petit-boutiste en tête de `reste`, et avance.
fn mot(reste: &mut &[u8]) -> usize {
    let valeur = u32::from_le_bytes(reste[..4].try_into().unwrap());
    *reste = &reste[4..];
    valeur as usize
}

/// Les avertissements de `handle` : le code, et les lignes lues d'`ASSET_OUT`.
fn avertissements(ctx: u64, handle: Handle) -> (i32, Vec<String>) {
    let mut size = u64::MAX;
    let code = unsafe { axion_asset_warnings(ctx, handle, &raw mut size) };
    if code != AXION_OK || size == 0 {
        return (code, Vec::new());
    }
    let sortie = acquerir(ctx, BufferKind::AssetOut, 0);
    let vue = unsafe {
        std::slice::from_raw_parts(sortie.ptr, HEADER_BYTES + usize::try_from(size).unwrap())
    };
    let mut reste = &vue[HEADER_BYTES..];
    let nombre = mot(&mut reste);
    let mut lignes = Vec::new();
    for _ in 0..nombre {
        let longueur = mot(&mut reste);
        lignes.push(String::from_utf8(reste[..longueur].to_vec()).expect("UTF-8"));
        reste = &reste[longueur..];
    }
    assert!(reste.is_empty(), "rien après les lignes");
    relacher(ctx, sortie);
    (code, lignes)
}

#[test]
fn adr156_d4_les_tables_refusees_d_un_asset_se_disent_par_l_abi() {
    let mut ctx = 0u64;
    assert_eq!(
        unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut ctx) },
        AXION_OK
    );

    let sain = charger(ctx, 0xA1, &cube(0xA1));
    assert_eq!(
        avertissements(ctx, sain),
        (AXION_OK, Vec::new()),
        "un asset sain"
    );
    // Sans ligne, la taille vaut 0 et ASSET_OUT n'est pas touché.
    let mut size = u64::MAX;
    assert_eq!(
        unsafe { axion_asset_warnings(ctx, sain, &raw mut size) },
        AXION_OK
    );
    assert_eq!(size, 0);

    let fautif = charger(ctx, 0xA2, &avec_lodm_illisible(&cube(0xA2)));
    let (code, lignes) = avertissements(ctx, fautif);
    assert_eq!(code, AXION_OK);
    assert_eq!(lignes.len(), 1, "{lignes:?}");
    assert!(
        lignes[0].starts_with("table LODM refusée, rendu au LOD 0 : "),
        "{}",
        lignes[0]
    );
    // Les lignes restent à l'asset : un second appel rend les mêmes.
    assert_eq!(avertissements(ctx, fautif), (AXION_OK, lignes));

    // Rendu, le handle est périmé.
    assert_eq!(unsafe { axion_asset_unload(ctx, fautif) }, AXION_OK);
    assert_eq!(avertissements(ctx, fautif).0, AXION_E_INVALID_HANDLE);
    assert_eq!(unsafe { axion_asset_unload(ctx, sain) }, AXION_OK);
    for kind in [BufferKind::AssetIn, BufferKind::AssetOut] {
        relacher(ctx, acquerir(ctx, kind, 0));
    }
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK);
}
