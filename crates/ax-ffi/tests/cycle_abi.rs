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
use axion_native::abi::{
    axion_abi_version, axion_buffer_acquire, axion_buffer_release, axion_init, axion_last_error,
    axion_shutdown, AxionBufferInfo, AXION_ABI_VERSION, AXION_E_INVALID_BUFFER,
    AXION_E_INVALID_HANDLE, AXION_OK,
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
    let code = unsafe { axion_init(cbor.as_ptr(), cbor.len(), &raw mut ctx) };
    assert_eq!(code, AXION_OK, "initialisation refusée");
    assert_ne!(ctx, 0, "jeton de contexte nul");

    // Une seconde initialisation est refusée : un contexte par processus.
    let mut second: u64 = 0;
    // SAFETY: mêmes garanties.
    let code = unsafe { axion_init(cbor.as_ptr(), cbor.len(), &raw mut second) };
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

    // Un arrêt propre autorise un redémarrage : le cas d'un serveur qui
    // recharge le mod.
    let mut reprise: u64 = 0;
    // SAFETY: mêmes garanties qu'à la première initialisation.
    let code = unsafe { axion_init(std::ptr::null(), 0, &raw mut reprise) };
    assert_eq!(code, AXION_OK, "réinitialisation refusée");
    assert_ne!(reprise, ctx, "jeton réutilisé d'une session à l'autre");
    // SAFETY: aucun pointeur déréférencé.
    unsafe {
        assert_eq!(axion_shutdown(reprise), AXION_OK);
    }
}
