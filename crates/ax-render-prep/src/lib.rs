//! C-64 — préparation du rendu : culling et LOD (ADR-154).
//!
//! Java remet à chaque frame la caméra ([`Camera`]) et les assemblies que Minecraft lui donne à
//! dessiner ([`Candidate`]), leur pose **relative à la caméra** : la caméra est à l'origine, et un
//! `f32` y garde la précision de l'écran à toute distance de l'origine du monde. Ce crate décide
//! lesquelles sont visibles ; il ne connaît ni la session native, ni la mémoire partagée, ni GL.
//!
//! # Le pipeline (fiche C-64)
//!
//! 1. culling de distance (`render.max_distance`) ;
//! 2. culling de frustum : la sphère englobante contre les six plans, conservatif ;
//! 3. occlusion : C-82, en M9 ;
//! 4. LOD : taille apparente, seuils, hystérésis et biais ([`lod`]) ;
//! 5. à 7. tri, plafond : tranches suivantes d'ADR-154.
//!
//! # Deux chemins, une décision
//!
//! [`cull_scalar`] est la référence ; [`cull_batched`] traite les candidats par lots de
//! [`LANES`], en colonnes, la forme qu'un compilateur vectorise. Les deux font les mêmes
//! opérations flottantes dans le même ordre, sans `mul_add` : leurs décisions sont identiques,
//! au bit près (T-521).
//!
//! Exigences : R-770, R-771. Tests : T-520, T-521, T-522.

mod bounds;
mod camera;
mod candidate;
mod cull;
pub mod lod;

pub use bounds::bounds_of;
pub use camera::{Camera, CameraError, Frustum};
pub use candidate::{Candidate, CandidateError, LocalBounds};
pub use cull::{cull_batched, cull_scalar, CullStats, Visible, LANES};
