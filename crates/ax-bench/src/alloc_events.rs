//! Compteur d'allocations (R-501, ADR-133 A1) : **combien** d'allocations une charge fait, là où
//! [`ax_mem::CountingAllocator`] compte des octets.
//!
//! R-501 se juge en M3 par le calcul : le nombre d'allocations d'une charge, multiplié par le
//! surcoût d'une allocation que B-08 mesure. Le nombre est un fait du code, reproductible
//! partout ; il se compte ici, sous un allocateur qui délègue à celui du système et ne fait
//! rien d'autre. Ce compteur n'est jamais installé dans les binaires de B-08 : il fausserait la
//! mesure qu'il sert à interpréter.
//!
//! Le compte est **par thread** : la charge physique tourne sur le thread appelant, et un
//! compte global mêlerait les allocations du harnais de test ou d'un autre thread.

use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;

thread_local! {
    /// Allocations du thread depuis son départ. Initialisée sans allocation ni destructeur :
    /// l'atteindre depuis l'allocateur ne réentre pas dans l'allocateur.
    static ALLOCATIONS: Cell<u64> = const { Cell::new(0) };
}

/// Note une allocation sur le thread courant.
fn note() {
    // Pendant la destruction des locales du thread, le compte n'existe plus : une allocation de
    // cette phase n'appartient à aucune charge comptée.
    let _ = ALLOCATIONS.try_with(|count| count.set(count.get() + 1));
}

/// L'allocateur du système, qui compte ses allocations par thread.
///
/// Une réallocation compte pour une allocation : sous l'allocateur compteur, elle coûte une
/// imputation et une restitution, comme une allocation et sa libération.
///
/// À installer comme allocateur global d'un binaire ou d'un test :
///
/// ```no_run
/// #[global_allocator]
/// static ALLOCATOR: ax_bench::alloc_events::AllocationEvents =
///     ax_bench::alloc_events::AllocationEvents::new();
///
/// fn main() {
///     let ((), allocations) = ax_bench::alloc_events::count(|| drop(vec![0u8; 64]));
///     assert_eq!(allocations, 1);
/// }
/// ```
#[derive(Debug, Default)]
pub struct AllocationEvents;

impl AllocationEvents {
    /// Le compteur, à poser en `static`.
    #[must_use]
    pub const fn new() -> Self {
        Self
    }
}

// SAFETY : chaque méthode délègue à `System` avec les arguments reçus, dont l'appelant garantit
// les préconditions de `GlobalAlloc` ; le compte n'alloue pas et ne touche pas aux blocs.
#[allow(unsafe_code)]
unsafe impl GlobalAlloc for AllocationEvents {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        note();
        // SAFETY : préconditions de `GlobalAlloc::alloc`, transmises telles quelles.
        unsafe { System.alloc(layout) }
    }

    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
        note();
        // SAFETY : préconditions de `GlobalAlloc::alloc_zeroed`, transmises telles quelles.
        unsafe { System.alloc_zeroed(layout) }
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        // SAFETY : `ptr` vient de cet allocateur, donc de `System`, avec ce `layout`.
        unsafe { System.dealloc(ptr, layout) }
    }

    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        note();
        // SAFETY : préconditions de `GlobalAlloc::realloc`, transmises telles quelles.
        unsafe { System.realloc(ptr, layout, new_size) }
    }
}

/// Exécute `work` et rend, avec son résultat, les allocations que le thread appelant a faites
/// pendant ce temps.
///
/// Zéro si [`AllocationEvents`] n'est pas l'allocateur global : un appelant qui en dépend le
/// vérifie sur une charge au compte connu.
pub fn count<R>(work: impl FnOnce() -> R) -> (R, u64) {
    let before = ALLOCATIONS.with(Cell::get);
    let result = work();
    let after = ALLOCATIONS.with(Cell::get);
    (result, after - before)
}
