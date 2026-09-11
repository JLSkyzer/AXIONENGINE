//! C-16 — noyau déterministe.
//!
//! Ce crate fournit les opérations dont AXION garantit qu'elles produisent un
//! résultat **bit-identique** entre client et serveur. C'est ce qui permet de
//! répliquer la déformation par événements plutôt que par données (P-15,
//! INV-14) : le client rejoue les impacts au lieu de recevoir le champ.
//!
//! # La portée de la garantie
//!
//! Elle n'est **pas universelle**, et R-517 interdit de le laisser croire. Elle
//! vaut pour les configurations de la matrice de validation (5.12bis), et pour
//! elles seules. Hors matrice, la réplication bascule en `SNAPSHOT` : la
//! correction est préservée, seul le mécanisme change, et aucune fonctionnalité
//! n'est retirée (R-514).
//!
//! Le repli a deux niveaux, et ce crate porte les deux :
//!
//! - **Préventif** — [`profile::negotiate`] écarte la reconstruction dès le
//!   handshake quand on sait d'avance qu'elle ne tiendra pas. Aucune divergence
//!   n'a alors l'occasion de se produire.
//! - **Curatif** — [`divergence::DivergenceTracker`] traite celle qu'on ne
//!   savait pas : instantané autoritatif de la région, comptage, et bascule de
//!   l'assembly au-delà du seuil.
//!
//! # Ce qui rend un résultat bit-identique
//!
//! Trois choses, et il suffit qu'une manque pour que tout tombe :
//!
//! 1. **Les opérations employées sont exactement spécifiées.** IEEE-754 impose
//!    l'arrondi correct de `+ - * /` et de `sqrt` ; il n'impose rien à `sin`,
//!    `exp` ou `powf`, dont deux bibliothèques peuvent différer au dernier bit.
//!    R-510 les interdit, et le noyau n'emploie aucune fonction de libm.
//! 2. **L'ordre des opérations est figé.** `3x² - 2x³` et `x²(3 - 2x)` sont
//!    égaux en mathématiques et différents en flottant. Ce qui est écrit ici
//!    fait partie du contrat.
//! 3. **Le compilateur ne réécrit pas l'arithmétique.** Une contraction en
//!    multiplication-addition fusionnée change un résultat sans changer une
//!    ligne de code. Le test `no_fma` le vérifie sur le binaire produit, ce
//!    qu'un drapeau de compilation déclaré ne fait que promettre.
//!
//! # Les vecteurs d'or
//!
//! R-513 : dix mille cas d'entrée-sortie versionnés, rejoués sur chaque
//! configuration de la matrice. Ils sont le seul moyen de constater une
//! divergence **avant** qu'un joueur ne la voie, et une divergence sur une
//! configuration de la matrice est un défaut bloquant.
//!
//! # Ce que ce crate n'emploie pas
//!
//! Ni `glam`, ni aucun type vecteur : le noyau est scalaire, et les chemins
//! déterministes qui manipulent des vecteurs le font composante par composante
//! ([ADR-104](../../../docs/decisions/ADR-104.md)). Ni SIMD : R-511 l'autorise
//! sous conditions, et n'en écrire aucun est la façon la plus sûre de n'avoir
//! rien à vérifier.
//!
//! Exigences : R-510..R-517. Invariants : INV-14. Tests : T-820..T-822.

pub mod digest;
pub mod divergence;
pub mod kernel;
pub mod profile;
pub mod rng;

pub use digest::{digest64, digest_field, mix64};
pub use divergence::{
    DivergenceResponse, DivergenceTracker, DEFAULT_DIVERGENCE_TOLERANCE, DIVERGENCE_TOLERANCE_PATH,
};
pub use kernel::{clamp, dequantize_i8, falloff, inv_sqrt, quantize_i8, smooth01, QUANT_MAX};
pub use profile::{
    current_entry, det_profile, det_profile_string, is_in_validation_matrix, is_matrix_target,
    negotiate, MatrixEntry, ReplicationMode, VALIDATION_MATRIX,
};
pub use rng::DetRng;

/// Version du noyau déterministe.
///
/// **À incrémenter dès qu'une sortie du noyau change**, pour quelque raison que
/// ce soit : une formule, un ordre d'évaluation, une version majeure de rustc.
/// Elle entre dans l'empreinte de configuration, si bien qu'un client et un
/// serveur de versions différentes basculent en `SNAPSHOT` au lieu de diverger.
///
/// C'est la seule chose qui sépare une correction d'un champ qui se déchire :
/// sans incrément, deux extrémités qui ne calculent plus pareil continueraient
/// de se croire d'accord.
pub const DET_KERNEL_VERSION: u32 = 1;
