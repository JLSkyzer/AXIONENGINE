//! Ce que reçoivent les bibliothèques C — meshoptimizer, zstd — est **compté** par l'allocateur
//! compteur installé comme allocateur global (INV-08, R-480).
//!
//! Son propre binaire, un seul test : il mesure l'arène `SCRATCH` et les compteurs globaux de
//! `foreign`, que rien d'autre n'emploie ici. Sous Miri (R-2122), il vérifie aussi l'en-tête
//! et sa provenance à travers l'allocateur installé.

// Le test écrit dans le bloc et le rend : deux opérations sur pointeur brut.
#![allow(unsafe_code)]

use ax_mem::{
    foreign_alloc, foreign_allocations, foreign_free, foreign_live_bytes, scope, ArenaClass,
    CountingAllocator, FOREIGN_ALIGN,
};

#[global_allocator]
static ALLOCATOR: CountingAllocator = CountingAllocator::new();

#[test]
fn inv08_un_bloc_etranger_est_compte_dans_l_arene_du_thread_puis_rendu() {
    let _scratch = scope(ArenaClass::Scratch);
    let before = ALLOCATOR.live_bytes_in(ArenaClass::Scratch);
    let allocations = foreign_allocations();

    let ptr = foreign_alloc(100);
    assert!(!ptr.is_null());
    assert_eq!(ptr as usize % FOREIGN_ALIGN, 0);
    // Les 100 octets demandés et l'en-tête qui garde la taille : tout est compté.
    assert_eq!(
        ALLOCATOR.live_bytes_in(ArenaClass::Scratch),
        before + 100 + FOREIGN_ALIGN
    );
    assert_eq!(foreign_allocations(), allocations + 1);
    assert_eq!(foreign_live_bytes(), 100);

    // SAFETY: le bloc couvre 100 octets.
    unsafe { ptr.cast::<u8>().write_bytes(7, 100) };
    // SAFETY: `ptr` vient de `foreign_alloc` et n'est libéré qu'ici.
    unsafe { foreign_free(ptr) };
    assert_eq!(ALLOCATOR.live_bytes_in(ArenaClass::Scratch), before);
    assert_eq!(foreign_live_bytes(), 0);
}
