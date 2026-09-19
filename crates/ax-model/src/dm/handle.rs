//! DM-01 — handle de la frontière.
//!
//! Forme `repr(C)` du handle tel qu'il **voyage dans une structure DM**, par
//! opposition au `ax_core::Handle<T>` typé qui vit dans les tables du runtime.
//! Les deux portent le même couple `(index, génération)` ; le premier est la
//! disposition figée qu'un lecteur Java lit en place, le second la référence
//! sûre côté Rust. `ax_core` fait le pont par `to_raw`/`from_raw`. Voir
//! `docs/decisions/ADR-113.md`.

/// Handle de la frontière (DM-01), tel qu'il apparaît dans une structure DM.
///
/// `{0, 0}` est réservé à « absent » (R-111) : monde, entité vanilla, ou pas de
/// second corps. Une génération nulle est invalide (R-110).
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub struct Handle {
    /// Rang du slot dans sa table.
    pub index: u32,
    /// Génération du slot ; `0` est invalide.
    pub generation: u32,
}

impl Handle {
    /// Le handle « absent » `{0, 0}` (R-111).
    pub const ABSENT: Handle = Handle {
        index: 0,
        generation: 0,
    };

    /// Construit un handle.
    #[must_use]
    pub const fn new(index: u32, generation: u32) -> Self {
        Self { index, generation }
    }

    /// Indique si le handle désigne « absent » `{0, 0}`.
    #[must_use]
    pub const fn is_absent(self) -> bool {
        self.generation == 0
    }
}
