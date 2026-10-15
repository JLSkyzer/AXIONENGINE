//! §31.1, R-500 par l'ABI : la chaîne d'assets se mesure par étape. Une compilation aboutie compte
//! une compilation, ses sommets, ses octets, et un passage dans chaque étape ; une compilation
//! refusée compte un échec ; une demande de fichiers voisins (ADR-139) n'est pas une fin.
//!
//! Un seul test, son propre binaire : la session native est globale au processus.

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

use ax_model::buffer::{BufferKind, HEADER_BYTES};
use axion_native::abi::{
    axion_asset_compile, axion_asset_poll, axion_buffer_acquire, axion_buffer_release, axion_init,
    axion_metrics_export, axion_shutdown, AxionBufferInfo, AXION_ASSET_COMPILED,
    AXION_ASSET_FAILED, AXION_ASSET_NEEDS_FILES, AXION_ASSET_PENDING, AXION_OK, AXION_SIDE_SERVER,
};

/// Format OBJ d'`axion_asset_compile`.
const OBJ: u32 = 2;
const TRIANGLE: &[u8] = b"v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n";
/// Une face qui désigne des sommets absents : l'import la refuse.
const FAUTIVE: &[u8] = b"v 0 0 0\nf 1 2 3\n";
/// Un `.mtl` que la table ne porte pas : le premier passage le demande.
const AVEC_MTL: &[u8] = b"mtllib absent.mtl\nv 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n";

fn empty_info() -> AxionBufferInfo {
    AxionBufferInfo {
        ptr: std::ptr::null_mut(),
        capacity: 0,
        kind: 0,
        generation: 0,
    }
}

/// Écrit la source dans `ASSET_IN`, compile, et sonde jusqu'à l'issue ; rend l'état final.
fn compile(ctx: u64, source: &[u8]) -> u32 {
    let mut asset_in = empty_info();
    // SAFETY: `asset_in` est accessible en écriture.
    let code = unsafe {
        axion_buffer_acquire(
            ctx,
            BufferKind::AssetIn.as_u32(),
            source.len() as u64,
            &raw mut asset_in,
        )
    };
    assert_eq!(code, AXION_OK);
    // SAFETY: le tampon appartient au contexte vivant et fait au moins `capacity` octets.
    let view = unsafe {
        std::slice::from_raw_parts_mut(asset_in.ptr, usize::try_from(asset_in.capacity).unwrap())
    };
    view[HEADER_BYTES..HEADER_BYTES + source.len()].copy_from_slice(source);
    let mut job: u32 = 0;
    // SAFETY: aucun paramètre d'options ; `job` est une variable locale.
    let code = unsafe {
        axion_asset_compile(
            ctx,
            0x0A51_0C0B_0000_0500,
            OBJ,
            source.len() as u64,
            0,
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

    let echeance = std::time::Instant::now() + std::time::Duration::from_secs(30);
    let mut status = AXION_ASSET_PENDING;
    let mut size: u64 = 0;
    let mut error: i32 = 0;
    while status == AXION_ASSET_PENDING {
        assert!(
            std::time::Instant::now() < echeance,
            "compilation toujours en cours"
        );
        // SAFETY: les trois pointeurs de sortie sont des variables locales.
        let code =
            unsafe { axion_asset_poll(ctx, job, &raw mut status, &raw mut size, &raw mut error) };
        assert_eq!(code, AXION_OK, "sondage refusé");
        std::thread::yield_now();
    }
    if status == AXION_ASSET_COMPILED || status == AXION_ASSET_NEEDS_FILES {
        let mut out = empty_info();
        // SAFETY: `out` est accessible en écriture ; le tampon est rendu aussitôt.
        unsafe {
            assert_eq!(
                axion_buffer_acquire(ctx, BufferKind::AssetOut.as_u32(), 0, &raw mut out),
                AXION_OK
            );
            assert_eq!(
                axion_buffer_release(ctx, out.kind, out.generation),
                AXION_OK
            );
        }
    }
    status
}

/// L'export des métriques, en texte.
fn export(ctx: u64) -> String {
    let mut needed = 0usize;
    // SAFETY: demande de taille, aucun octet écrit.
    let code = unsafe { axion_metrics_export(ctx, std::ptr::null_mut(), 0, &raw mut needed) };
    assert_eq!(code, AXION_OK);
    let mut buffer = vec![0u8; needed];
    let mut written = 0usize;
    // SAFETY: le tampon fait `needed` octets.
    let code =
        unsafe { axion_metrics_export(ctx, buffer.as_mut_ptr(), buffer.len(), &raw mut written) };
    assert_eq!(code, AXION_OK);
    buffer.truncate(written);
    String::from_utf8(buffer).expect("export UTF-8")
}

/// Un champ numérique de la ligne d'une métrique.
fn field(json: &str, name: &str, key: &str) -> u64 {
    let line = json
        .lines()
        .find(|line| line.contains(&format!("\"name\": \"{name}\"")))
        .unwrap_or_else(|| panic!("{name} absente de l'export"));
    line.split(&format!("\"{key}\": "))
        .nth(1)
        .unwrap_or_else(|| panic!("{name} sans champ {key}"))
        .chars()
        .take_while(char::is_ascii_digit)
        .collect::<String>()
        .parse()
        .expect("valeur entière")
}

#[test]
fn r500_la_chaine_d_assets_se_mesure_par_etape() {
    let mut ctx: u64 = 0;
    // SAFETY: `ctx` est une variable locale accessible en écriture.
    let code = unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut ctx) };
    assert_eq!(code, AXION_OK, "initialisation refusée");

    assert_eq!(compile(ctx, TRIANGLE), AXION_ASSET_COMPILED);
    assert_eq!(compile(ctx, FAUTIVE), AXION_ASSET_FAILED);
    assert_eq!(compile(ctx, AVEC_MTL), AXION_ASSET_NEEDS_FILES);

    let json = export(ctx);
    assert_eq!(field(&json, "axion.asset.compiled", "value"), 1, "{json}");
    assert_eq!(field(&json, "axion.asset.failed", "value"), 1, "{json}");
    assert_eq!(field(&json, "axion.asset.vertices", "value"), 3);
    assert!(field(&json, "axion.asset.output_bytes", "value") > 0);
    // Chaque étape a été traversée une fois, par la seule compilation aboutie.
    for stage in [
        "axion.asset.import_ns",
        "axion.asset.validate_ns",
        "axion.asset.optimize_ns",
        "axion.asset.colliders_ns",
        "axion.asset.write_ns",
    ] {
        assert_eq!(field(&json, stage, "count"), 1, "{stage}");
    }

    // SAFETY: aucun pointeur déréférencé.
    unsafe {
        assert_eq!(axion_shutdown(ctx), AXION_OK, "bilan d'arrêt");
    }
}
