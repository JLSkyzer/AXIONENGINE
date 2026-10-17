//! Pic de mémoire d'une charge (ADR-146 §4) : **combien d'octets au plus** elle tient à la fois,
//! là où [`crate::alloc_events`] compte des allocations et [`ax_mem::CountingAllocator`] des
//! octets vivants.
//!
//! La décomposition convexe de C-32 voxelise sa géométrie : sa mémoire croît comme le cube de la
//! résolution, et c'est elle qui borne `collision.resolution` (ADR-108). Elle se mesure ici, sous
//! un allocateur qui délègue à celui du système et ne fait que tenir le compte.
//!
//! Le compte est **par thread** : la décomposition tourne sur le thread appelant (`parry3d` sans
//! sa fonction `parallel`), et un compte global mêlerait les allocations d'un autre thread.

use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;

thread_local! {
    /// Octets alloués moins octets libérés par le thread. Signé : un bloc libéré ici et alloué
    /// ailleurs le fait descendre sous son départ sans déborder. Initialisés sans allocation ni
    /// destructeur : les atteindre depuis l'allocateur ne réentre pas dans l'allocateur.
    static LIVE: Cell<i64> = const { Cell::new(0) };
    /// Le plus haut que `LIVE` ait atteint depuis la dernière remise à niveau.
    static PEAK: Cell<i64> = const { Cell::new(0) };
}

/// Note une variation des octets du thread courant.
fn note(delta: i64) {
    // Pendant la destruction des locales du thread, le compte n'existe plus : rien à noter.
    let _ = LIVE.try_with(|live| {
        let now = live.get().saturating_add(delta);
        live.set(now);
        let _ = PEAK.try_with(|peak| {
            if now > peak.get() {
                peak.set(now);
            }
        });
    });
}

/// Taille d'un bloc, en octets signés.
fn size(bytes: usize) -> i64 {
    i64::try_from(bytes).unwrap_or(i64::MAX)
}

/// L'allocateur du système, qui tient par thread les octets vivants et leur pic.
///
/// Une réallocation compte comme le passage de l'ancienne taille à la nouvelle, sans l'instant
/// où l'allocateur du système tient les deux blocs : le pic mesuré est celui des blocs que la
/// charge détient, pas celui des copies internes du système.
///
/// À installer comme allocateur global d'un binaire ou d'un test :
///
/// ```no_run
/// #[global_allocator]
/// static ALLOCATOR: ax_bench::peak_bytes::PeakBytes = ax_bench::peak_bytes::PeakBytes::new();
///
/// fn main() {
///     let ((), octets) = ax_bench::peak_bytes::peak(|| drop(vec![0u8; 4096]));
///     assert!(octets >= 4096);
/// }
/// ```
#[derive(Debug, Default)]
pub struct PeakBytes;

impl PeakBytes {
    /// L'allocateur, à poser en `static`.
    #[must_use]
    pub const fn new() -> Self {
        Self
    }
}

// SAFETY : chaque méthode délègue à `System` avec les arguments reçus, dont l'appelant garantit
// les préconditions de `GlobalAlloc` ; le compte n'alloue pas et ne touche pas aux blocs.
#[allow(unsafe_code)]
unsafe impl GlobalAlloc for PeakBytes {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        // SAFETY : préconditions de `GlobalAlloc::alloc`, transmises telles quelles.
        let block = unsafe { System.alloc(layout) };
        if !block.is_null() {
            note(size(layout.size()));
        }
        block
    }

    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
        // SAFETY : préconditions de `GlobalAlloc::alloc_zeroed`, transmises telles quelles.
        let block = unsafe { System.alloc_zeroed(layout) };
        if !block.is_null() {
            note(size(layout.size()));
        }
        block
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        // SAFETY : `ptr` vient de cet allocateur, donc de `System`, avec ce `layout`.
        unsafe { System.dealloc(ptr, layout) };
        note(-size(layout.size()));
    }

    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        // SAFETY : préconditions de `GlobalAlloc::realloc`, transmises telles quelles.
        let block = unsafe { System.realloc(ptr, layout, new_size) };
        if !block.is_null() {
            note(size(new_size) - size(layout.size()));
        }
        block
    }
}

/// Exécute `work` et rend, avec son résultat, le pic d'octets que le thread appelant a tenus
/// pendant ce temps, au-dessus de ce qu'il tenait au départ.
///
/// Zéro si [`PeakBytes`] n'est pas l'allocateur global : un appelant qui en dépend le vérifie sur
/// une charge connue.
pub fn peak<R>(work: impl FnOnce() -> R) -> (R, u64) {
    let start = LIVE.with(Cell::get);
    PEAK.with(|peak| peak.set(start));
    let result = work();
    let high = PEAK.with(Cell::get);
    (
        result,
        u64::try_from(high.saturating_sub(start)).unwrap_or(0),
    )
}
