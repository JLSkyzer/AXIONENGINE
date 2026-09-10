//! C-20..C-25 — assets d'AXION.
//!
//! Ce crate porte la chaîne d'assets : orchestration, importers, validation,
//! conteneur et cache. Aujourd'hui, il porte le **conteneur A3D** (C-24), qui
//! est le format que tout le reste produit et consomme — les importers écrivent
//! des A3D, le validateur les relit, le cache les range.
//!
//! # Ce que le conteneur garantit
//!
//! Un fichier A3D vient toujours de l'extérieur : d'un cache que quelqu'un a pu
//! altérer, d'un disque qui a pu se corrompre, ou d'un resource pack qu'on n'a
//! pas écrit. Rien n'y est cru sur parole :
//!
//! - l'en-tête porte un CRC qui couvre `total_size`, donc toutes les
//!   vérifications de bornes qui s'appuient dessus (R-900) ;
//! - chaque taille annoncée est plafonnée **avant** allocation (R-901) ;
//! - chaque charge utile est comparée à son CRC **avant** décompression
//!   (R-882), et la décompression est bornée par une taille connue d'avance
//!   (R-902) ;
//! - une section de tag inconnu est ignorée proprement (R-880), et une version
//!   majeure inconnue refusée (R-890).
//!
//! # Ce que le validateur garantit
//!
//! Un asset est validé **deux fois** : à la compilation, et de nouveau au
//! chargement (R-540). Entre les deux, il a séjourné dans un cache sur disque
//! que rien n'empêche d'altérer. La seconde passe est ce qui fait qu'un cache
//! modifié ne devient pas une lecture hors bornes.
//!
//! Chaque violation est **nommée et localisée** (R-541), et la validation ne
//! s'arrête pas à la première : un auteur qui corrige son modèle veut la liste.
//!
//! Exigences : R-540..R-542, R-880..R-883, R-890..R-893, R-900..R-903.
//! Tests : T-230..T-233, T-250..T-253.

pub mod a3d;
pub mod import;
pub mod validate;
