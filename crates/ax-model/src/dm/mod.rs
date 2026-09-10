//! Modèle de données (DM-01..DM-19).
//!
//! Les structures traversent la frontière et sont lues **en place** dans les
//! sections A3D : elles sont toutes `repr(C)`, R-262 interdisant qu'une
//! disposition `repr(Rust)` — que rien ne garantit — traverse quoi que ce soit.
//!
//! Chaque module porte un test de disposition. Ce n'est pas une formalité : le
//! format de sommet est **figé en V1.0** (R-140), et un champ déplacé ou élargi
//! rendrait illisible tout asset déjà compilé, sans qu'aucune erreur ne le
//! dise.
//!
//! # Ce qui est transcrit
//!
//! DM-02 et DM-04 (transformations, meshes, sommets), DM-03 et DM-11 (nodes,
//! parts), DM-06 (colliders), DM-12 et DM-14 (régions de déformation, graphe
//! structurel), et les plafonds de [`limits`]. Ce sont ceux que le validateur
//! C-22 examine.
//!
//! Les autres viendront avec les composants qui les emploient. Les transcrire
//! en avance figerait des dispositions mémoire avant que la première fonction
//! ne s'en serve, ce qui est exactement la décision qu'on ne peut plus défaire.

pub mod geometry;
pub mod integrity;
pub mod limits;
pub mod physics;
pub mod scene;
