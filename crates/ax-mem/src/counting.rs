//! Allocateur compteur (C-13, R-480, INV-08, ADR-133 D2).
//!
//! [`CountingAllocator`] enveloppe l'allocateur du système et compte, par arène
//! ([`ArenaClass`]), les octets vivants que le code natif d'AXION a demandés.
//! Installé comme `#[global_allocator]` de la bibliothèque native, il ne laisse
//! **aucune** allocation échapper au comptage — celles de `rapier`, du pool de
//! jobs ou de la sérialisation comprises —, ce que R-480 exige et qu'un comptage
//! à la main ne peut pas garantir.
//!
//! # Arène d'une allocation
//!
//! Une allocation est imputée à l'arène du **contexte qui l'a faite** : celle
//! que [`scope`] a posée sur le thread, [`ArenaClass::Persistent`] à défaut. Elle
//! le reste jusqu'à sa libération, où qu'elle ait lieu : l'arène est écrite
//! dans un en-tête placé devant le bloc rendu, et relue à la libération. Un
//! `realloc` garde l'arène d'origine. Ce qu'un job alloue est ainsi compté en
//! [`ArenaClass::Scratch`] — son résultat compris, tant qu'il vit.
//!
//! # Coût
//!
//! Par allocation et par libération : une lecture de deux cellules locales au
//! thread et une addition atomique relâchée sur un compteur propre à une part
//! des threads (huit parts, chacune sur sa ligne de cache) — pas de
//! contention entre workers. En mémoire : un en-tête de 16 octets, ou de
//! l'alignement demandé s'il est plus grand ; les octets comptés sont ceux
//! demandés, hors en-tête. Le surcoût se mesure par B-08 (R-501).

// R-2120 : `unsafe` est admis dans ax-mem pour l'allocateur — `GlobalAlloc` est
// un trait `unsafe`, et ses méthodes manipulent des pointeurs bruts.
#![allow(unsafe_code)]
#![deny(clippy::undocumented_unsafe_blocks)]

use core::alloc::{GlobalAlloc, Layout};
use core::cell::Cell;
use core::sync::atomic::{AtomicU8, AtomicUsize, Ordering};
use std::alloc::System;

use crate::ArenaClass;

/// Taille minimale de l'en-tête, en octets : l'alignement que l'allocateur du
/// système garantit déjà sur les plateformes supportées, si bien qu'un bloc
/// ordinaire reste aussi aligné qu'avant.
const MIN_HEADER: usize = 16;

/// Nombre de parts de compteurs. Un thread écrit toujours dans la sienne.
const SHARDS: usize = 8;

/// Part non encore attribuée au thread.
const NO_SHARD: u8 = u8::MAX;

/// Nombre d'arènes.
const ARENAS: usize = ArenaClass::ALL.len();

thread_local! {
    // Initialisations constantes, types sans destructeur : l'accès est celui
    // d'une variable locale au thread, sans allocation ni enregistrement de
    // destructeur — condition pour être lu depuis l'allocateur lui-même.
    static ARENA: Cell<u8> = const { Cell::new(0) };
    static SHARD: Cell<u8> = const { Cell::new(NO_SHARD) };
}

/// Prochaine part à attribuer à un thread, en tourniquet.
static NEXT_SHARD: AtomicU8 = AtomicU8::new(0);

/// Rang d'une arène dans les compteurs et dans l'en-tête.
const fn arena_index(class: ArenaClass) -> u8 {
    match class {
        ArenaClass::Persistent => 0,
        ArenaClass::Deform => 1,
        ArenaClass::Frame => 2,
        ArenaClass::Scratch => 3,
    }
}

/// Arène courante du thread.
fn current_arena() -> u8 {
    ARENA.try_with(Cell::get).unwrap_or(0)
}

/// Part du thread, attribuée à son premier appel.
fn current_shard() -> usize {
    SHARD
        .try_with(|cell| {
            let mut shard = cell.get();
            if shard == NO_SHARD {
                // `SHARDS` divise 256 : le tourniquet sur un octet reste uniforme.
                shard = NEXT_SHARD.fetch_add(1, Ordering::Relaxed) % SHARDS as u8;
                cell.set(shard);
            }
            usize::from(shard)
        })
        .unwrap_or(0)
}

/// Pose l'arène des allocations du thread jusqu'à la fin de la portée, et rend
/// la précédente ensuite — y compris si la portée se termine par une panique.
///
/// ```
/// use ax_mem::{scope, ArenaClass};
/// let _scratch = scope(ArenaClass::Scratch);
/// // Ce que ce thread alloue ici est compté en SCRATCH.
/// ```
#[must_use = "l'arène n'est posée que tant que la garde vit"]
pub fn scope(class: ArenaClass) -> ArenaScope {
    let previous = current_arena();
    let _ = ARENA.try_with(|cell| cell.set(arena_index(class)));
    ArenaScope { previous }
}

/// Garde d'une portée d'arène : rend l'arène précédente à sa destruction.
#[derive(Debug)]
pub struct ArenaScope {
    previous: u8,
}

impl Drop for ArenaScope {
    fn drop(&mut self) {
        let previous = self.previous;
        let _ = ARENA.try_with(|cell| cell.set(previous));
    }
}

/// Un compteur sur sa propre ligne de cache.
#[repr(align(64))]
struct Padded(AtomicUsize);

/// Allocateur du système, comptant les octets vivants par arène (R-480).
pub struct CountingAllocator {
    /// `live[part][arène]` : octets alloués moins octets libérés par les threads
    /// de la part, **modulo** 2^usize — une part peut libérer ce qu'une autre a
    /// alloué ; seule la somme des parts a un sens.
    live: [[Padded; ARENAS]; SHARDS],
}

impl CountingAllocator {
    /// Un allocateur dont tous les compteurs sont à zéro.
    #[must_use]
    pub const fn new() -> Self {
        // Blocs `const` répétés : un `Padded` n'est pas `Copy`.
        Self {
            live: [const { [const { Padded(AtomicUsize::new(0)) }; ARENAS] }; SHARDS],
        }
    }

    /// Octets vivants d'une arène : ceux qu'on a demandés et pas encore rendus.
    #[must_use]
    pub fn live_bytes_in(&self, class: ArenaClass) -> usize {
        let arena = usize::from(arena_index(class));
        self.live.iter().fold(0usize, |sum, part| {
            sum.wrapping_add(part[arena].0.load(Ordering::Relaxed))
        })
    }

    /// Octets vivants, toutes arènes confondues.
    #[must_use]
    pub fn live_bytes(&self) -> usize {
        ArenaClass::ALL.iter().fold(0usize, |sum, class| {
            sum.wrapping_add(self.live_bytes_in(*class))
        })
    }

    fn charge(&self, arena: u8, bytes: usize) {
        // Une arène hors bornes ne peut venir que d'un en-tête corrompu : rien
        // n'est compté plutôt que de paniquer dans l'allocateur.
        if let Some(counter) = self.live[current_shard()].get(usize::from(arena)) {
            counter.0.fetch_add(bytes, Ordering::Relaxed);
        }
    }

    fn discharge(&self, arena: u8, bytes: usize) {
        if let Some(counter) = self.live[current_shard()].get(usize::from(arena)) {
            counter.0.fetch_sub(bytes, Ordering::Relaxed);
        }
    }
}

impl Default for CountingAllocator {
    fn default() -> Self {
        Self::new()
    }
}

impl core::fmt::Debug for CountingAllocator {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.debug_struct("CountingAllocator")
            .field("live_bytes", &self.live_bytes())
            .finish()
    }
}

/// Taille de l'en-tête pour un alignement : au moins [`MIN_HEADER`], et un
/// multiple de l'alignement, pour que le bloc rendu le respecte.
const fn header(align: usize) -> usize {
    if align > MIN_HEADER {
        align
    } else {
        MIN_HEADER
    }
}

/// Disposition demandée au système : en-tête compris. `None` si elle déborde.
fn outer(layout: Layout) -> Option<Layout> {
    let size = layout.size().checked_add(header(layout.align()))?;
    Layout::from_size_align(size, layout.align()).ok()
}

// SAFETY: chaque méthode délègue à `System` avec une disposition valide — celle
// que `outer` a construite et vérifiée à l'allocation, reconstruite à
// l'identique à la libération —, et rend un pointeur décalé de l'en-tête à
// l'intérieur du bloc obtenu, aligné comme demandé (l'en-tête est un multiple de
// l'alignement). Aucune méthode ne panique.
unsafe impl GlobalAlloc for CountingAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        let Some(outer) = outer(layout) else {
            return core::ptr::null_mut();
        };
        // SAFETY: `outer` a une taille non nulle (l'en-tête au moins) et a été
        // validée par `Layout::from_size_align`.
        let base = unsafe { System.alloc(outer) };
        // SAFETY: `base` vient d'être obtenu pour `outer`, ou est nul.
        unsafe { finish(self, base, layout) }
    }

    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
        let Some(outer) = outer(layout) else {
            return core::ptr::null_mut();
        };
        // SAFETY: comme dans `alloc`.
        let base = unsafe { System.alloc_zeroed(outer) };
        // SAFETY: `base` vient d'être obtenu pour `outer`, ou est nul.
        unsafe { finish(self, base, layout) }
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        let offset = header(layout.align());
        // SAFETY: le contrat de `dealloc` garantit que `ptr` a été rendu par cet
        // allocateur pour `layout` : il est à `offset` octets du début de son bloc,
        // et l'octet qui le précède, dans l'en-tête, porte son arène.
        let (base, arena) = unsafe { (ptr.sub(offset), ptr.sub(1).read()) };
        self.discharge(arena, layout.size());
        // SAFETY: la disposition extérieure a été validée à l'allocation pour les
        // mêmes taille et alignement : la reconstruire ne peut pas échouer.
        let outer =
            unsafe { Layout::from_size_align_unchecked(layout.size() + offset, layout.align()) };
        // SAFETY: `base` a été obtenu de `System` pour `outer`.
        unsafe { System.dealloc(base, outer) };
    }

    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        let offset = header(layout.align());
        let Some(new_outer_size) = new_size.checked_add(offset) else {
            return core::ptr::null_mut();
        };
        if Layout::from_size_align(new_outer_size, layout.align()).is_err() {
            return core::ptr::null_mut();
        }
        // SAFETY: comme dans `dealloc`, `ptr` vient de cet allocateur pour `layout`.
        let (base, arena) = unsafe { (ptr.sub(offset), ptr.sub(1).read()) };
        // SAFETY: validée à l'allocation, comme dans `dealloc`.
        let outer =
            unsafe { Layout::from_size_align_unchecked(layout.size() + offset, layout.align()) };
        // SAFETY: `base` vient de `System` pour `outer` ; la nouvelle taille, en-tête
        // compris, vient d'être vérifiée. `System.realloc` recopie le début du bloc,
        // en-tête et arène compris.
        let moved = unsafe { System.realloc(base, outer, new_outer_size) };
        if moved.is_null() {
            // Échec : l'ancien bloc reste valide, et compté.
            return core::ptr::null_mut();
        }
        if new_size >= layout.size() {
            self.charge(arena, new_size - layout.size());
        } else {
            self.discharge(arena, layout.size() - new_size);
        }
        // SAFETY: `moved` couvre `new_outer_size` octets, dont `offset` d'en-tête.
        unsafe { moved.add(offset) }
    }
}

/// Écrit l'arène dans l'en-tête, compte, et rend le pointeur utilisateur ; nul
/// si le système a refusé.
///
/// # Safety
///
/// `base` est nul, ou le début d'un bloc obtenu de `System` pour `outer(layout)`.
unsafe fn finish(counter: &CountingAllocator, base: *mut u8, layout: Layout) -> *mut u8 {
    if base.is_null() {
        return base;
    }
    let offset = header(layout.align());
    let arena = current_arena();
    // SAFETY: le bloc couvre `offset + layout.size()` octets ; `ptr` est à `offset`
    // de son début.
    let ptr = unsafe { base.add(offset) };
    // SAFETY: l'octet qui précède `ptr` appartient à l'en-tête, que ce bloc possède.
    unsafe { ptr.sub(1).write(arena) };
    counter.charge(arena, layout.size());
    ptr
}

#[cfg(test)]
mod tests {
    use super::*;

    fn layout(size: usize, align: usize) -> Layout {
        Layout::from_size_align(size, align).expect("disposition valide")
    }

    #[test]
    fn t016_une_allocation_est_comptee_puis_decomptee() {
        let allocator = CountingAllocator::new();
        let l = layout(100, 8);
        // SAFETY: disposition non nulle ; le bloc est rendu avec la même.
        let ptr = unsafe { allocator.alloc(l) };
        assert!(!ptr.is_null());
        assert_eq!(allocator.live_bytes(), 100);
        assert_eq!(allocator.live_bytes_in(ArenaClass::Persistent), 100);
        // SAFETY: `ptr` vient de `alloc(l)`.
        unsafe { allocator.dealloc(ptr, l) };
        assert_eq!(allocator.live_bytes(), 0);
    }

    #[test]
    fn t016_la_portee_designe_l_arene_et_se_rend() {
        let allocator = CountingAllocator::new();
        let l = layout(64, 8);
        let ptr = {
            let _scratch = scope(ArenaClass::Scratch);
            // SAFETY: disposition non nulle.
            let ptr = unsafe { allocator.alloc(l) };
            {
                let _deform = scope(ArenaClass::Deform);
                assert_eq!(current_arena(), arena_index(ArenaClass::Deform));
            }
            assert_eq!(
                current_arena(),
                arena_index(ArenaClass::Scratch),
                "portée imbriquée rendue"
            );
            ptr
        };
        assert_eq!(current_arena(), arena_index(ArenaClass::Persistent));
        assert_eq!(allocator.live_bytes_in(ArenaClass::Scratch), 64);
        // Libéré hors de la portée : l'en-tête dit l'arène d'origine.
        // SAFETY: `ptr` vient de `alloc(l)`.
        unsafe { allocator.dealloc(ptr, l) };
        assert_eq!(allocator.live_bytes_in(ArenaClass::Scratch), 0);
        assert_eq!(allocator.live_bytes_in(ArenaClass::Persistent), 0);
    }

    #[test]
    fn t016_realloc_garde_l_arene_et_le_contenu() {
        let allocator = CountingAllocator::new();
        let l = layout(16, 8);
        let ptr = {
            let _scratch = scope(ArenaClass::Scratch);
            // SAFETY: disposition non nulle.
            unsafe { allocator.alloc(l) }
        };
        for i in 0..16u8 {
            // SAFETY: le bloc couvre 16 octets.
            unsafe { ptr.add(usize::from(i)).write(i) };
        }
        // SAFETY: `ptr` vient de `alloc(l)` ; nouvelle taille non nulle.
        let grown = unsafe { allocator.realloc(ptr, l, 4096) };
        assert!(!grown.is_null());
        assert_eq!(allocator.live_bytes_in(ArenaClass::Scratch), 4096);
        // SAFETY: les 16 premiers octets ont été recopiés.
        let kept: Vec<u8> = (0..16).map(|i| unsafe { grown.add(i).read() }).collect();
        assert_eq!(kept, (0..16).collect::<Vec<u8>>());
        // SAFETY: `grown` est un bloc de 4096 octets, alignement 8.
        let shrunk = unsafe { allocator.realloc(grown, layout(4096, 8), 8) };
        assert_eq!(allocator.live_bytes_in(ArenaClass::Scratch), 8);
        // SAFETY: `shrunk` est un bloc de 8 octets, alignement 8.
        unsafe { allocator.dealloc(shrunk, layout(8, 8)) };
        assert_eq!(allocator.live_bytes(), 0);
    }

    #[test]
    fn t016_un_grand_alignement_est_respecte() {
        let allocator = CountingAllocator::new();
        for align in [1, 2, 8, 16, 32, 64, 4096] {
            let l = layout(24, align);
            // SAFETY: disposition non nulle.
            let ptr = unsafe { allocator.alloc(l) };
            assert!(!ptr.is_null());
            assert_eq!(ptr as usize % align, 0, "alignement {align}");
            // SAFETY: `ptr` vient de `alloc(l)`.
            unsafe { allocator.dealloc(ptr, l) };
        }
        assert_eq!(allocator.live_bytes(), 0);
    }

    #[test]
    fn t016_alloc_zeroed_rend_des_zeros() {
        let allocator = CountingAllocator::new();
        let l = layout(256, 32);
        // SAFETY: disposition non nulle.
        let ptr = unsafe { allocator.alloc_zeroed(l) };
        // SAFETY: le bloc couvre 256 octets.
        assert!((0..256).all(|i| unsafe { ptr.add(i).read() } == 0));
        assert_eq!(allocator.live_bytes(), 256);
        // SAFETY: `ptr` vient de `alloc_zeroed(l)`.
        unsafe { allocator.dealloc(ptr, l) };
        assert_eq!(allocator.live_bytes(), 0);
    }

    #[test]
    fn t016_une_taille_qui_deborde_est_refusee_sans_panique() {
        let allocator = CountingAllocator::new();
        let l = layout(isize::MAX as usize - 8, 1);
        // SAFETY: disposition non nulle ; l'appel doit rendre nul.
        let ptr = unsafe { allocator.alloc(l) };
        assert!(ptr.is_null());
        assert_eq!(allocator.live_bytes(), 0);
    }

    #[test]
    fn t016_une_liberation_sur_un_autre_thread_decompte_l_arene_d_origine() {
        let allocator = std::sync::Arc::new(CountingAllocator::new());
        let l = layout(512, 8);
        let ptr = {
            let _scratch = scope(ArenaClass::Scratch);
            // SAFETY: disposition non nulle.
            unsafe { allocator.alloc(l) as usize }
        };
        let remote = std::sync::Arc::clone(&allocator);
        std::thread::spawn(move || {
            // SAFETY: `ptr` vient de `alloc(l)` et n'est libéré qu'ici.
            unsafe { remote.dealloc(ptr as *mut u8, l) };
        })
        .join()
        .expect("le thread se termine");
        assert_eq!(allocator.live_bytes_in(ArenaClass::Scratch), 0);
        assert_eq!(allocator.live_bytes(), 0);
    }
}
