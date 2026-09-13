//! C-30 — graphe de scène d'une assembly.
//!
//! Chaque assembly runtime possède un graphe : ses nodes en ordre topologique,
//! leurs transforms locales et monde, leur état (SM-03) et leur visibilité
//! (PARTIE 9). Il se construit depuis les `NodeDesc` d'un asset validé, et
//! c'est tout ce qu'il en garde : la géométrie reste partagée entre les
//! instances (R-960).
//!
//! # Propagation
//!
//! Les colonnes sont des tableaux parallèles, indexés par node. L'ordre
//! topologique — un parent précède ses enfants — rend la propagation
//! **linéaire, sans récursion** : un seul parcours, dans l'ordre des index,
//! trouve toujours la transform monde du parent déjà à jour.
//!
//! Seuls les nodes **sales** et leurs descendants sont recalculés. Un parcours
//! qui ne change rien ne fait que lire des bits.
//!
//! # Parallélisme
//!
//! Parallélisable **entre** assemblies, jamais à l'intérieur : un graphe ne
//! partage rien avec un autre, et [`SceneGraph`] est `Send`. C'est
//! l'ordonnanceur du tick qui répartit les graphes sur le pool de jobs ; la
//! propagation d'un graphe reste séquentielle, puisque chaque node dépend de
//! son parent.
//!
//! # Ce que le graphe ne fait pas
//!
//! - **La déformation ne touche jamais une transform** (R-601, R-933). Elle agit
//!   sur les sommets et les points d'enveloppe ; une porte tordue se modélise
//!   par le décalage du repère de joint (PARTIE 18.5). Aucune méthode de ce
//!   crate ne prend de champ de déformation, et c'est voulu.
//! - Il ne choisit pas les LOD (C-64) ni ne dessine : il dit quels nodes sont
//!   visibles, où ils sont, et lesquels ont bougé.
//!
//! Exigences : R-600, R-601, R-930..R-933, R-950, R-952. Tests : T-290..T-292,
//! T-612.

mod bitset;
mod graph;
mod metrics;

pub use bitset::BitSet;
pub use graph::{SceneError, SceneGraph};
pub use metrics::SceneMetrics;
