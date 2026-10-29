//! C-37 — animation : échantillonnage des clips, couches et blending (PARTIE 18, ADR-155).
//!
//! Un asset porte ses clips dans la section `ANIM` (DM-10) ; ce crate les joue. Il ne connaît ni
//! la session native, ni la mémoire partagée, ni le graphe de scène : il rend la pose locale de
//! chaque node, que l'appelant propage (étape 14) et dont il tire les palettes de skinning.
//!
//! - [`ClipLibrary`] : les clips d'un asset, vérifiés une fois ;
//! - [`sample()`] : l'échantillonnage d'une piste, à curseur, conforme à glTF 2.0 (§18.2) ;
//! - [`Animator`] : les quatre couches d'une instance, leur blending en TRS et leurs fondus.
//!
//! Rien ici n'appelle de fonction transcendante : la pose d'un node animé peut entrer dans la
//! physique (R-1430), qui est identique d'une plateforme à l'autre ([`quat`]).
//!
//! Exigences : R-1440, R-200. Tests : T-361..T-364.

mod animator;
mod clip;
pub mod quat;
mod sample;

pub use animator::{AnimError, Animator, LAYERS};
pub use clip::{ClipError, ClipLibrary};
pub use sample::{sample, Cursor};
