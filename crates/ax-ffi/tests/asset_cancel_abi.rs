//! ADR-140 par l'ABI : `axion_asset_cancel` retire une compilation de la table de la session, en
//! vol ou finie ; le sondage d'un travail abandonné rend `E-2001`, et l'arrêt reste équilibré
//! (R-322) même quand des travaux abandonnés tournent encore.
//!
//! Un seul test, son propre binaire : la session native est globale au processus.

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

use ax_model::buffer::{BufferKind, HEADER_BYTES};
use axion_native::abi::{
    axion_asset_cancel, axion_asset_compile, axion_asset_poll, axion_buffer_acquire,
    axion_buffer_release, axion_init, axion_shutdown, AxionBufferInfo, AXION_ASSET_COMPILED,
    AXION_ASSET_PENDING, AXION_E_INVALID_HANDLE, AXION_OK, AXION_SIDE_SERVER,
};
use axion_native::context::{self, AssetJob};

const ASSET_ID: u64 = 0x0A51_0C0B_0000_0140;
/// Format OBJ d'`axion_asset_compile`.
const OBJ: u32 = 2;
const TRIANGLE: &[u8] = b"v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n";

fn empty_info() -> AxionBufferInfo {
    AxionBufferInfo {
        ptr: std::ptr::null_mut(),
        capacity: 0,
        kind: 0,
        generation: 0,
    }
}

/// Écrit la source dans `ASSET_IN` et lance sa compilation.
fn submit(ctx: u64, source: &[u8]) -> u32 {
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
            ASSET_ID,
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
    job
}

fn cancel(ctx: u64, job: u32) -> i32 {
    // SAFETY: aucun pointeur déréférencé.
    unsafe { axion_asset_cancel(ctx, job) }
}

/// Sonde une fois : `(code de l'appel, état)`.
fn poll(ctx: u64, job: u32) -> (i32, u32) {
    let mut status = u32::MAX;
    let mut size: u64 = 0;
    let mut error: i32 = 0;
    // SAFETY: les trois pointeurs de sortie sont des variables locales.
    let code =
        unsafe { axion_asset_poll(ctx, job, &raw mut status, &raw mut size, &raw mut error) };
    (code, status)
}

/// Compilations que la session retient.
fn held(ctx: u64) -> usize {
    context::with(ctx, false, |session| session.asset_jobs_held()).unwrap()
}

/// Attend qu'un travail soit fini **sans en reprendre le résultat** : `poll_asset_job` fait
/// avancer l'entrée de la table, seul le sondage de l'ABI l'oublie.
fn wait_finished(ctx: u64, job: u32) {
    let echeance = std::time::Instant::now() + std::time::Duration::from_secs(30);
    while context::with(ctx, false, |session| {
        matches!(session.poll_asset_job(job), Some(AssetJob::Running(_)))
    })
    .unwrap()
    {
        assert!(
            std::time::Instant::now() < echeance,
            "le travail ne finit pas"
        );
        std::thread::yield_now();
    }
}

#[test]
fn adr140_un_travail_abandonne_ne_retient_plus_rien() {
    let mut ctx: u64 = 0;
    // SAFETY: `ctx` est une variable locale accessible en écriture.
    let code = unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut ctx) };
    assert_eq!(code, AXION_OK, "initialisation refusée");

    // Ce qui ne désigne rien est refusé, comme au sondage : un contexte forgé, un travail
    // jamais émis.
    assert_eq!(cancel(ctx ^ 1, 1), AXION_E_INVALID_HANDLE, "contexte forgé");
    assert_eq!(cancel(ctx, 0), AXION_E_INVALID_HANDLE, "travail nul");
    assert_eq!(
        cancel(ctx, 4242),
        AXION_E_INVALID_HANDLE,
        "travail jamais émis"
    );

    // Fini, son asset attend d'être lu : l'abandon le libère, et plus rien ne le trouve.
    let fini = submit(ctx, TRIANGLE);
    wait_finished(ctx, fini);
    assert_eq!(held(ctx), 1);
    assert_eq!(cancel(ctx, fini), AXION_OK, "travail fini");
    assert_eq!(held(ctx), 0, "l'asset compilé est resté dans la table");
    assert_eq!(
        poll(ctx, fini).0,
        AXION_E_INVALID_HANDLE,
        "sondage après abandon"
    );
    assert_eq!(cancel(ctx, fini), AXION_E_INVALID_HANDLE, "second abandon");

    // Abandonnés aussitôt soumis — en file ou en cours, selon le pool : retirés de même.
    let soumis: Vec<u32> = (0..8).map(|_| submit(ctx, TRIANGLE)).collect();
    assert_eq!(held(ctx), soumis.len());
    for job in &soumis {
        assert_eq!(cancel(ctx, *job), AXION_OK, "travail {job} en vol");
    }
    assert_eq!(held(ctx), 0);

    // Un travail sondé jusqu'au bout est oublié par le sondage : rien ne reste à abandonner.
    let lu = submit(ctx, TRIANGLE);
    let echeance = std::time::Instant::now() + std::time::Duration::from_secs(30);
    let mut etat = AXION_ASSET_PENDING;
    while etat == AXION_ASSET_PENDING {
        assert!(
            std::time::Instant::now() < echeance,
            "compilation toujours en cours"
        );
        let (code, status) = poll(ctx, lu);
        assert_eq!(code, AXION_OK, "sondage refusé");
        etat = status;
        std::thread::yield_now();
    }
    assert_eq!(etat, AXION_ASSET_COMPILED);
    let mut asset_out = empty_info();
    // SAFETY: `asset_out` est accessible en écriture ; le tampon est rendu aussitôt.
    unsafe {
        assert_eq!(
            axion_buffer_acquire(ctx, BufferKind::AssetOut.as_u32(), 0, &raw mut asset_out),
            AXION_OK
        );
        assert_eq!(
            axion_buffer_release(ctx, asset_out.kind, asset_out.generation),
            AXION_OK
        );
    }
    assert_eq!(
        cancel(ctx, lu),
        AXION_E_INVALID_HANDLE,
        "travail déjà repris"
    );

    // R-322 : des travaux abandonnés qui tournent peut-être encore ne faussent pas le bilan
    // de l'arrêt, qui joint les workers.
    for _ in 0..4 {
        let job = submit(ctx, TRIANGLE);
        assert_eq!(cancel(ctx, job), AXION_OK);
    }
    // SAFETY: aucun pointeur déréférencé.
    unsafe {
        assert_eq!(axion_shutdown(ctx), AXION_OK, "bilan d'arrêt");
    }
}
