//! Allocations des bibliothèques C et C++ liées à la bibliothèque native (INV-08, R-480).
//!
//! meshoptimizer (C-23) et zstd (C-24) allouent leurs tampons de travail par `operator new` et
//! `malloc` : hors de l'allocateur global, donc hors du comptage que R-480 exige. Tous deux
//! acceptent un allocateur à eux — `meshopt_setAllocator`, `ZSTD_customMem` —, mais leur
//! libération ne transmet que l'adresse du bloc, jamais sa taille, que Rust demande.
//!
//! [`foreign_alloc`] place donc la taille du bloc dans un en-tête devant la zone rendue, et
//! [`foreign_free`] l'y relit. Les deux passent par [`std::alloc`] : sous l'allocateur compteur
//! de la bibliothèque native ([`crate::CountingAllocator`]), ces octets sont comptés comme les
//! autres, dans l'arène du thread qui les demande.
//!
//! # Alignement
//!
//! Seize octets, ce que `malloc` et `operator new` garantissent sur les plateformes supportées
//! (`max_align_t`, `__STDCPP_DEFAULT_NEW_ALIGNMENT__`) : une bibliothèque qui y range des `double`
//! ou des vecteurs SSE n'y voit aucune différence.
//!
//! # Provenance
//!
//! Le pointeur rendu est dérivé de celui du bloc entier, et la bibliothèque le rend tel quel :
//! aucune référence ne le restreint à sa zone, si bien qu'il atteint encore l'en-tête à la
//! libération (Stacked Borrows, R-2122) — à la différence de ce qu'un `Box` rend à
//! l'allocateur global.
//!
//! # Échec
//!
//! Une bibliothèque C++ ne s'attend pas à un pointeur nul : `operator new` lèverait. Un refus du
//! système, ou une taille qui déborde, **arrête le processus** — ce que Rust fait de tout échec
//! d'allocation —, plutôt que de rendre un pointeur nul qu'elle écrirait.

// R-2120 : `unsafe` est admis ici comme dans `counting` — l'en-tête se lit et s'écrit par
// pointeurs bruts, et la libération reçoit un pointeur venu d'une bibliothèque C.
#![allow(unsafe_code)]
#![deny(clippy::undocumented_unsafe_blocks)]

use core::ffi::c_void;
use core::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::alloc::{alloc, dealloc, handle_alloc_error, Layout};

/// Alignement des blocs rendus, en octets.
pub const FOREIGN_ALIGN: usize = 16;

/// Taille de l'en-tête : la taille du bloc y tient, et la zone rendue reste alignée.
const HEADER: usize = FOREIGN_ALIGN;

/// Nombre d'allocations servies depuis le démarrage du processus.
static ALLOCATIONS: AtomicU64 = AtomicU64::new(0);

/// Octets demandés et pas encore rendus, en-tête exclu, **modulo** 2^usize.
static LIVE: AtomicUsize = AtomicUsize::new(0);

/// Alloue un bloc d'au moins `size` octets, aligné sur [`FOREIGN_ALIGN`].
///
/// La signature est celle d'un allocateur C : `void* (*)(size_t)`. Une taille nulle rend un
/// pointeur valide et unique, comme `operator new(0)`.
///
/// Arrête le processus si le système refuse ou si `size` déborde (voir « Échec »).
pub extern "C" fn foreign_alloc(size: usize) -> *mut c_void {
    let Some(layout) = size
        .checked_add(HEADER)
        .and_then(|total| Layout::from_size_align(total, FOREIGN_ALIGN).ok())
    else {
        // meshoptimizer passe `SIZE_MAX` pour qu'un calcul de taille qui déborde échoue :
        // aucune disposition ne le représente, et l'échec est celui d'une allocation.
        std::process::abort();
    };
    // SAFETY: `layout` a une taille non nulle — l'en-tête au moins — et vient d'être validée.
    let base = unsafe { alloc(layout) };
    if base.is_null() {
        handle_alloc_error(layout);
    }
    // SAFETY: le bloc couvre `layout.size()` ≥ `HEADER` octets, aligné sur `FOREIGN_ALIGN` :
    // un `usize`, d'alignement au plus 8, tient au début.
    unsafe { base.cast::<usize>().write(layout.size()) };
    ALLOCATIONS.fetch_add(1, Ordering::Relaxed);
    LIVE.fetch_add(size, Ordering::Relaxed);
    // SAFETY: `HEADER` < `layout.size()` : la zone rendue est dans les bornes du bloc.
    unsafe { base.add(HEADER) }.cast()
}

/// Rend un bloc obtenu de [`foreign_alloc`]. Un pointeur nul est ignoré, comme par `free`.
///
/// La signature est celle d'une libération C : `void (*)(void*)`.
///
/// # Safety
///
/// `ptr` est nul, ou a été rendu par [`foreign_alloc`] et n'a pas encore été libéré.
pub unsafe extern "C" fn foreign_free(ptr: *mut c_void) {
    if ptr.is_null() {
        return;
    }
    // SAFETY: le contrat garantit que `ptr` vient de `foreign_alloc`, qui l'a placé `HEADER`
    // octets après le début de son bloc, et qu'il porte la provenance du bloc entier.
    let base = unsafe { ptr.cast::<u8>().sub(HEADER) };
    // SAFETY: `foreign_alloc` a écrit la taille du bloc au début, aligné.
    let total = unsafe { base.cast::<usize>().read() };
    LIVE.fetch_sub(total - HEADER, Ordering::Relaxed);
    // SAFETY: `base` a été obtenu de `alloc` pour cette taille et cet alignement, validés à
    // l'allocation.
    unsafe {
        dealloc(
            base,
            Layout::from_size_align_unchecked(total, FOREIGN_ALIGN),
        )
    };
}

/// Nombre d'allocations servies aux bibliothèques C depuis le démarrage du processus.
///
/// Monotone : un test y voit qu'une bibliothèque emploie bien cet allocateur.
#[must_use]
pub fn foreign_allocations() -> u64 {
    ALLOCATIONS.load(Ordering::Relaxed)
}

/// Octets que les bibliothèques C tiennent encore, en-têtes exclus : nul quand elles ont
/// tout rendu.
#[must_use]
pub fn foreign_live_bytes() -> usize {
    LIVE.load(Ordering::Relaxed)
}

#[cfg(test)]
mod tests {
    use super::*;

    const SIZES: [usize; 7] = [0, 1, 15, 16, 17, 1000, 4096];

    // Un seul test : les compteurs sont globaux au processus, et deux tests parallèles
    // brouilleraient leurs écarts.
    #[test]
    fn inv08_un_bloc_etranger_est_aligne_ecrivable_et_rendu() {
        let allocations = foreign_allocations();
        let live = foreign_live_bytes();
        let mut blocks = Vec::new();
        for size in SIZES {
            let ptr = foreign_alloc(size);
            assert!(!ptr.is_null(), "taille {size}");
            assert_eq!(ptr as usize % FOREIGN_ALIGN, 0, "alignement, taille {size}");
            // SAFETY: le bloc couvre au moins `size` octets.
            unsafe { ptr.cast::<u8>().write_bytes(0xA5, size) };
            blocks.push((ptr, size));
        }
        // Un bloc de taille nulle est unique, comme `operator new(0)` : un second, pris tant
        // que le premier vit, a une autre adresse.
        let other_zero = foreign_alloc(0);
        assert_ne!(blocks[0].0, other_zero);
        // SAFETY: `other_zero` vient de `foreign_alloc`.
        unsafe { foreign_free(other_zero) };
        assert_eq!(foreign_live_bytes(), live + SIZES.iter().sum::<usize>());
        for (ptr, size) in blocks.into_iter().rev() {
            // SAFETY: le contenu écrit est relu dans les bornes du bloc.
            let content = unsafe { core::slice::from_raw_parts(ptr.cast::<u8>(), size) };
            assert!(content.iter().all(|&b| b == 0xA5), "taille {size}");
            // SAFETY: `ptr` vient de `foreign_alloc` et n'est libéré qu'ici.
            unsafe { foreign_free(ptr) };
        }
        // SAFETY: un pointeur nul est admis.
        unsafe { foreign_free(core::ptr::null_mut()) };
        assert_eq!(foreign_live_bytes(), live, "tout est rendu");
        assert_eq!(foreign_allocations(), allocations + SIZES.len() as u64 + 1);
    }
}
