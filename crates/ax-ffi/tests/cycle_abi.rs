//! Cycle complet de l'ABI, exercé comme Java l'exercera.
//!
//! Ce fichier ne contient **qu'un seul** test, délibérément : la session native
//! est un état global au processus, et deux tests qui l'ouvriraient en
//! parallèle mesureraient les effets l'un de l'autre. L'échec ressemblerait
//! alors à un défaut de l'ABI. Chaque fichier de `tests/` étant un binaire
//! séparé, un test unique ici garantit l'isolation.
//!
//! La séquence suit le protocole imposé : version d'ABI d'abord (R-260),
//! initialisation, acquisition d'un tampon, écriture d'une charge utile avec
//! son en-tête, libération, arrêt.

// Le test exerce des points d'entree `unsafe extern "C"` : c'est tout son
// objet. Le lint reste `deny` pour le crate, et se leve ici — c'est
// precisement ce que `forbid` aurait rendu impossible.
#![allow(unsafe_code)]

// Le crate `ax-ffi` produit une bibliotheque nommee `axion_native` ([lib] name),
// imposee par R-420 : c'est ce nom-la qu'on importe, pas celui du paquet.
use ax_model::buffer::{BufferHeader, BufferKind, HEADER_BYTES};

/// R-450 impose **un contexte par processus**. Les tests d'intégration
/// partagent ce processus : sans ce verrou, deux d'entre eux s'ouvriraient en
/// même temps et le second recevrait `E-1004` — un échec qui ne dirait rien du
/// code testé.
static CONTEXTE: std::sync::Mutex<()> = std::sync::Mutex::new(());

/// Prend le contexte du processus pour la durée d'un test.
fn contexte() -> std::sync::MutexGuard<'static, ()> {
    CONTEXTE
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}
use axion_native::abi::{
    axion_abi_version, axion_asset_compile, axion_asset_poll, axion_buffer_acquire,
    axion_buffer_release, axion_init, axion_last_error, axion_metrics_export, axion_shutdown,
    AxionBufferInfo, AXION_ABI_VERSION, AXION_ASSET_COMPILED, AXION_ASSET_PENDING,
    AXION_E_INVALID_BUFFER, AXION_E_INVALID_HANDLE, AXION_E_LEAK, AXION_OK, AXION_SIDE_SERVER,
};

fn config_cbor() -> Vec<u8> {
    let map = ciborium::Value::Map(vec![
        (
            ciborium::Value::Text("sim.max_substeps".to_owned()),
            ciborium::Value::Integer(6.into()),
        ),
        (
            ciborium::Value::Text("deformation.quality".to_owned()),
            ciborium::Value::Text("high".to_owned()),
        ),
    ]);
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

#[test]
fn cycle_complet_de_l_abi() {
    let _contexte = contexte();
    // R-260 : la version d'ABI se demande avant toute autre chose, et sans
    // contexte — c'est ce qui permet de la comparer avant d'initialiser.
    assert_eq!(axion_abi_version(), AXION_ABI_VERSION as i32);

    // Aucun appel n'est accepté tant qu'aucun contexte n'existe.
    // SAFETY: pointeurs valides, jeton volontairement invalide.
    unsafe {
        let mut info = empty_info();
        assert_eq!(
            axion_buffer_acquire(0, BufferKind::SimIn.as_u32(), 16, &raw mut info),
            AXION_E_INVALID_HANDLE
        );
    }

    let cbor = config_cbor();
    let mut ctx: u64 = 0;
    // SAFETY: `cbor` est vivant pour la durée de l'appel, `ctx` est accessible
    // en écriture.
    let code = unsafe { axion_init(cbor.as_ptr(), cbor.len(), AXION_SIDE_SERVER, &raw mut ctx) };
    assert_eq!(code, AXION_OK, "initialisation refusée");
    assert_ne!(ctx, 0, "jeton de contexte nul");

    // Une seconde initialisation est refusée : un contexte par processus.
    let mut second: u64 = 0;
    // SAFETY: mêmes garanties.
    let code = unsafe {
        axion_init(
            cbor.as_ptr(),
            cbor.len(),
            AXION_SIDE_SERVER,
            &raw mut second,
        )
    };
    assert_eq!(code, -1004, "double initialisation acceptée");

    // Acquisition d'un tampon, puis écriture d'une charge utile complète.
    let mut info = empty_info();
    // SAFETY: `info` est accessible en écriture.
    let code = unsafe { axion_buffer_acquire(ctx, BufferKind::SimOut.as_u32(), 64, &raw mut info) };
    assert_eq!(code, AXION_OK);
    assert!(!info.ptr.is_null());
    assert!(info.capacity >= 64 + HEADER_BYTES as u64);
    assert_eq!(info.kind, BufferKind::SimOut.as_u32());
    assert_eq!(info.ptr as usize % 16, 0, "tampon mal aligné");

    // C'est exactement ce que fera Java à travers son DirectByteBuffer :
    // écrire l'en-tête, puis la charge utile.
    // SAFETY: le tampon appartient au contexte vivant, et fait au moins
    // `info.capacity` octets.
    let view = unsafe {
        std::slice::from_raw_parts_mut(info.ptr, usize::try_from(info.capacity).unwrap())
    };
    let header = BufferHeader {
        kind: BufferKind::SimOut,
        generation: info.generation,
        schema_version: 1,
        payload_len: 8,
        element_count: 2,
        crc32c: 0,
    };
    header.write(view);
    view[HEADER_BYTES..HEADER_BYTES + 8].copy_from_slice(&[1, 2, 3, 4, 5, 6, 7, 8]);

    // Le natif relit ce que Java vient d'écrire, en validant l'en-tête.
    let relu = BufferHeader::read(view).expect("en-tête refusé");
    assert_eq!(relu, header);
    assert_eq!(relu.payload(view), &[1, 2, 3, 4, 5, 6, 7, 8]);

    // Un kind inconnu est refusé, jamais utilisé comme indice.
    // SAFETY: pointeur valide.
    unsafe {
        let mut rejet = empty_info();
        assert_eq!(
            axion_buffer_acquire(ctx, 999, 16, &raw mut rejet),
            AXION_E_INVALID_BUFFER
        );
    }

    // Libération : la génération doit correspondre (R-270).
    // SAFETY: aucun pointeur déréférencé.
    unsafe {
        assert_eq!(
            axion_buffer_release(ctx, BufferKind::SimOut.as_u32(), info.generation ^ 0xFF),
            AXION_E_INVALID_BUFFER
        );
        assert_eq!(
            axion_buffer_release(ctx, BufferKind::SimOut.as_u32(), info.generation),
            AXION_OK
        );
    }

    // Le message d'erreur se lit sans zéro terminal, avec sa longueur.
    let mut buffer = [0u8; 256];
    let mut len: usize = 0;
    // SAFETY: `buffer` et `len` sont accessibles en écriture.
    let code = unsafe { axion_last_error(ctx, buffer.as_mut_ptr(), buffer.len(), &raw mut len) };
    assert_eq!(code, AXION_OK);
    assert!(len <= buffer.len());
    assert!(
        std::str::from_utf8(&buffer[..len]).is_ok(),
        "message d'erreur non UTF-8"
    );

    // Arrêt, puis le jeton ne désigne plus rien.
    // SAFETY: aucun pointeur déréférencé.
    unsafe {
        assert_eq!(axion_shutdown(ctx), AXION_OK);
        assert_eq!(axion_shutdown(ctx), AXION_E_INVALID_HANDLE);

        let mut apres = empty_info();
        assert_eq!(
            axion_buffer_acquire(ctx, BufferKind::SimIn.as_u32(), 16, &raw mut apres),
            AXION_E_INVALID_HANDLE
        );
    }

    // T-016, R-322 : un tampon jamais relâché fait apparaître un bilan non nul
    // à l'arrêt. La session se ferme quand même — un déséquilibre se signale,
    // il n'empêche pas de s'arrêter.
    let mut fuite: u64 = 0;
    // SAFETY: pointeurs locaux valides.
    unsafe {
        assert_eq!(
            axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut fuite),
            AXION_OK
        );
        let mut oublie = empty_info();
        assert_eq!(
            axion_buffer_acquire(fuite, BufferKind::Events.as_u32(), 32, &raw mut oublie),
            AXION_OK
        );
        // Pas de axion_buffer_release : c'est tout l'objet du test.
        assert_eq!(
            axion_shutdown(fuite),
            AXION_E_LEAK,
            "un tampon non relâché devrait être signalé"
        );
        // Et la session est bien fermée malgré le signalement.
        assert_eq!(axion_shutdown(fuite), AXION_E_INVALID_HANDLE);
    }

    // T-201 : l'export des métriques traverse la frontière (R-502). Il est
    // demandé ici, sur une session qui a vécu — tampons acquis, relâchés, et
    // un déséquilibre signalé — plutôt que sur une session neuve.
    let mut reprise: u64 = 0;
    // SAFETY: mêmes garanties qu'à la première initialisation.
    let code = unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut reprise) };
    assert_eq!(code, AXION_OK, "réinitialisation refusée");
    exporte_les_metriques(reprise);
    // SAFETY: aucun pointeur déréférencé.
    unsafe {
        assert_eq!(axion_shutdown(reprise), AXION_OK);
    }

    // Un arrêt propre autorise un redémarrage : le cas d'un serveur qui
    // recharge le mod.
    let mut reprise: u64 = 0;
    // SAFETY: mêmes garanties qu'à la première initialisation.
    let code = unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut reprise) };
    assert_eq!(code, AXION_OK, "réinitialisation refusée");
    assert_ne!(reprise, ctx, "jeton réutilisé d'une session à l'autre");
    // SAFETY: aucun pointeur déréférencé.
    unsafe {
        assert_eq!(axion_shutdown(reprise), AXION_OK);
    }
}

/// T-201 — R-502 : l'export JSON des métriques traverse la frontière.
///
/// Le protocole est celui de la fonction : `out_len` reçoit toujours la
/// longueur complète, et rien n'est écrit tant que la capacité ne suffit pas.
fn exporte_les_metriques(ctx: u64) {
    // Capacité nulle : on demande seulement la taille.
    let mut needed: usize = 0;
    // SAFETY: `out_utf8` peut être nul quand `cap` vaut zéro, `needed` est local.
    let code = unsafe { axion_metrics_export(ctx, std::ptr::null_mut(), 0, &raw mut needed) };
    assert_eq!(code, AXION_OK, "export refusé");
    assert!(needed > 0, "export vide");

    // Capacité insuffisante : rien n'est écrit, un JSON tronqué n'étant pas un
    // JSON.
    let mut trop_petit = vec![0xAA_u8; needed - 1];
    let mut encore: usize = 0;
    // SAFETY: le tampon fait bien `needed - 1` octets.
    let code = unsafe {
        axion_metrics_export(
            ctx,
            trop_petit.as_mut_ptr(),
            trop_petit.len(),
            &raw mut encore,
        )
    };
    assert_eq!(code, AXION_OK);
    assert_eq!(encore, needed, "longueur requise non rendue");
    assert!(
        trop_petit.iter().all(|byte| *byte == 0xAA),
        "un export tronqué a été écrit"
    );

    // Capacité suffisante : le document complet.
    let mut buffer = vec![0_u8; needed];
    let mut written: usize = 0;
    // SAFETY: le tampon fait `needed` octets.
    let code =
        unsafe { axion_metrics_export(ctx, buffer.as_mut_ptr(), buffer.len(), &raw mut written) };
    assert_eq!(code, AXION_OK);
    assert_eq!(written, needed);

    let json = std::str::from_utf8(&buffer).expect("export non UTF-8");
    assert!(json.starts_with('{'), "{json}");
    assert!(json.contains("\"schema_version\""), "{json}");
    // Les métriques de budget y sont toutes, INV-19 l'exigeant.
    assert!(
        json.contains("axion.budget.sim_ns_per_tick.consumed"),
        "{json}"
    );
    assert!(
        json.contains("axion.budget.sim_ns_per_tick.overruns"),
        "{json}"
    );
    assert!(json.contains("axion.jobs.workers"), "{json}");

    // Un jeton invalide ne rend pas d'export.
    let mut ignore: usize = 0;
    // SAFETY: aucun pointeur déréférencé, la capacité étant nulle.
    let code = unsafe { axion_metrics_export(0, std::ptr::null_mut(), 0, &raw mut ignore) };
    assert_eq!(code, AXION_E_INVALID_HANDLE, "export sur jeton invalide");
}

/// T-210 — IF-06 : une compilation d'asset traverse la frontière.
///
/// Le chemin complet, tel que Java l'empruntera : écrire la source dans
/// `ASSET_IN`, lancer, sonder jusqu'à l'aboutissement, lire l'A3D dans
/// `ASSET_OUT`.
#[test]
fn t210_une_compilation_d_asset_traverse_la_frontiere() {
    let _contexte = contexte();
    let mut ctx: u64 = 0;
    // SAFETY: `ctx` est une variable locale accessible en écriture.
    let code = unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut ctx) };
    assert_eq!(code, AXION_OK, "initialisation refusée");

    const SOURCE: &[u8] = b"v 0.0 0.0 0.0
v 1.0 0.0 0.0
v 0.0 1.0 0.0
f 1 2 3
";

    // Java écrit la source dans le tampon d'entrée.
    let mut info = empty_info();
    // SAFETY: `info` est accessible en écriture.
    let code = unsafe {
        axion_buffer_acquire(
            ctx,
            BufferKind::AssetIn.as_u32(),
            SOURCE.len() as u64,
            &raw mut info,
        )
    };
    assert_eq!(code, AXION_OK);
    // SAFETY: le tampon appartient au contexte vivant et fait au moins
    // `info.capacity` octets.
    let view = unsafe {
        std::slice::from_raw_parts_mut(info.ptr, usize::try_from(info.capacity).unwrap())
    };
    view[HEADER_BYTES..HEADER_BYTES + SOURCE.len()].copy_from_slice(SOURCE);

    // Format 2 : OBJ.
    let mut job: u32 = 0;
    // SAFETY: `options_cbor` peut être nul quand sa longueur est nulle.
    let code = unsafe {
        axion_asset_compile(
            ctx,
            0x4242,
            2,
            SOURCE.len() as u64,
            std::ptr::null(),
            0,
            &raw mut job,
        )
    };
    assert_eq!(code, AXION_OK, "compilation refusée");
    assert_ne!(job, 0);

    // Sondage, comme Java le fera à chaque tick (R-521), jusqu'à une échéance large : la
    // compilation tourne sur le pool, et un nombre fixe de sondages ne borne pas un temps — cent
    // mille `yield_now` ont tenu moins de 50 ms sur un runner macOS de la CI, moins que cette
    // compilation n'y a pris.
    let echeance = std::time::Instant::now() + std::time::Duration::from_secs(30);
    let mut status = AXION_ASSET_PENDING;
    let mut size: u64 = 0;
    let mut error: i32 = 0;
    while std::time::Instant::now() < echeance {
        // SAFETY: les trois pointeurs de sortie sont des variables locales.
        let code =
            unsafe { axion_asset_poll(ctx, job, &raw mut status, &raw mut size, &raw mut error) };
        assert_eq!(code, AXION_OK);
        if status != AXION_ASSET_PENDING {
            break;
        }
        std::thread::yield_now();
    }

    assert_eq!(
        status, AXION_ASSET_COMPILED,
        "compilation échouée, code {error}"
    );
    assert!(size > 0, "asset vide");

    // L'A3D attend dans le tampon de sortie.
    let mut out = empty_info();
    // SAFETY: `out` est accessible en écriture.
    let code = unsafe { axion_buffer_acquire(ctx, BufferKind::AssetOut.as_u32(), 0, &raw mut out) };
    assert_eq!(code, AXION_OK);
    // SAFETY: mêmes garanties que pour le tampon d'entrée.
    let compiled =
        unsafe { std::slice::from_raw_parts(out.ptr, usize::try_from(out.capacity).unwrap()) };
    assert_eq!(
        &compiled[HEADER_BYTES..HEADER_BYTES + 4],
        b"A3D ",
        "le tampon de sortie ne porte pas un A3D"
    );

    // Un travail dont le résultat a été repris est oublié : le redemander vaut
    // mieux qu'une seconde lecture d'un tampon qui a pu changer.
    // SAFETY: mêmes garanties.
    let code =
        unsafe { axion_asset_poll(ctx, job, &raw mut status, &raw mut size, &raw mut error) };
    assert_eq!(code, AXION_E_INVALID_HANDLE);

    // SAFETY: aucun pointeur déréférencé.
    unsafe {
        assert_eq!(
            axion_buffer_release(ctx, BufferKind::AssetIn.as_u32(), info.generation),
            AXION_OK
        );
        assert_eq!(
            axion_buffer_release(ctx, BufferKind::AssetOut.as_u32(), out.generation),
            AXION_OK
        );
        assert_eq!(axion_shutdown(ctx), AXION_OK);
    }
}
