//! R-491 exercé sur IF-03 : `axion_sim_submit` valide l'en-tête du tampon
//! `SIM_IN` avant d'en lire les commandes. Un en-tête corrompu (magic faux) est
//! refusé avec `E-2002`, sans qu'aucune commande soit lue.
//!
//! Un seul test, dans son propre binaire : la session native est globale au
//! processus, et chaque binaire d'intégration l'isole (cf. `sim_abi.rs`).

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

use ax_model::buffer::BufferKind;
use axion_native::abi::{
    axion_buffer_acquire, axion_buffer_release, axion_init, axion_shutdown, axion_sim_cancel,
    axion_sim_submit, AxionBufferInfo, AXION_E_INVALID_BUFFER, AXION_OK, AXION_SIDE_SERVER,
};

static CONTEXTE: std::sync::Mutex<()> = std::sync::Mutex::new(());

/// Libère un tampon comme Java le fera, pour équilibrer le bilan à l'arrêt
/// (R-322) : l'acquérir (idempotent) pour sa génération courante puis le relâcher.
fn release_buffer(ctx: u64, kind: BufferKind) {
    let mut info = AxionBufferInfo {
        ptr: std::ptr::null_mut(),
        capacity: 0,
        kind: 0,
        generation: 0,
    };
    unsafe {
        axion_buffer_acquire(ctx, kind.as_u32(), 0, &raw mut info);
        axion_buffer_release(ctx, kind.as_u32(), info.generation);
    }
}

#[test]
fn submit_refuse_un_entete_corrompu() {
    let _contexte = CONTEXTE
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);

    let mut ctx = 0u64;
    let code = unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut ctx) };
    assert_eq!(code, AXION_OK, "init");

    // Acquérir SIM_IN : le natif y pose un en-tête valide (magic, kind, génération).
    let mut info = AxionBufferInfo {
        ptr: std::ptr::null_mut(),
        capacity: 0,
        kind: 0,
        generation: 0,
    };
    let code = unsafe { axion_buffer_acquire(ctx, BufferKind::SimIn.as_u32(), 64, &raw mut info) };
    assert_eq!(code, AXION_OK, "acquire SimIn");
    assert!(!info.ptr.is_null());

    // Corrompre le magic (octets 0..4) : l'en-tête n'est plus un tampon AXION.
    let view = unsafe {
        std::slice::from_raw_parts_mut(info.ptr, usize::try_from(info.capacity).unwrap())
    };
    view[0] ^= 0xFF;

    // Une commande annoncée par le paramètre, mais l'en-tête est invalide :
    // `submit` refuse avec `E-2002` (R-491), sans lire les commandes.
    assert_eq!(
        unsafe { axion_sim_submit(ctx, 1, 1, 0) },
        AXION_E_INVALID_BUFFER,
        "submit doit refuser un en-tête corrompu"
    );

    // Le cycle a été ouvert avant le refus : on le referme.
    assert_eq!(unsafe { axion_sim_cancel(ctx) }, AXION_OK, "cancel");

    // Seul SIM_IN a été acquis (le collect n'a jamais tourné) : le relâcher suffit.
    release_buffer(ctx, BufferKind::SimIn);
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK, "shutdown");
}
