//! Corps rigides et formes de collision (§10.2, §10.3).

use ax_math::{Quat, Vec3};
use core::fmt;
use rapier3d::prelude::RigidBodyHandle;

/// Nombre minimal de points d'une enveloppe convexe (§10.3).
pub(crate) const MIN_CONVEX_HULL_POINTS: usize = 4;
/// Nombre maximal de points d'une enveloppe convexe (§10.3).
pub(crate) const MAX_CONVEX_HULL_POINTS: usize = 256;
/// Nombre maximal de formes filles d'un composé (§10.3).
pub(crate) const MAX_COMPOUND_PARTS: usize = 64;

/// Type de corps (§10.2).
///
/// - [`Static`](Self::Static) : non intégré, non déplaçable — décor, collision
///   du monde ;
/// - [`Kinematic`](Self::Kinematic) : déplacé par une transform imposée —
///   plateformes, proxies d'entités vanilla, objets scriptés ;
/// - [`Dynamic`](Self::Dynamic) : intégré, mû par les forces — objets, pièces
///   détachées, débris, véhicules.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BodyKind {
    /// Masse infinie, immobile.
    Static,
    /// Masse infinie, déplacé par transform imposée.
    Kinematic,
    /// Masse finie, mû par les forces.
    Dynamic,
}

/// Forme de collision d'un corps (§10.3).
///
/// Ce sont les formes **portables par un corps dynamique** : primitives,
/// `ConvexHull` (seule forme refitable, R-970) et `Compound`. Les formes
/// concaves du monde — `TriMesh` (statique et kinematic) et `Heightfield`
/// (statique) — arrivent avec le fournisseur de collision du monde (C-38), leur
/// source de données ; c'est là que R-970 (INV-13) en restreindra l'usage aux
/// corps non dynamiques, sur du vrai terrain.
///
/// `Cylinder` et `Cone` sont supportés mais plus coûteux et moins stables
/// (R-971) : le générateur automatique ne les produit pas, et le refit les
/// convertit en `ConvexHull`.
#[derive(Debug, Clone, PartialEq)]
pub enum Shape {
    /// Boîte, demi-dimensions par axe, en blocs.
    Cuboid {
        /// Demi-dimensions `[x, y, z]`.
        half_extents: [f32; 3],
    },
    /// Sphère, rayon en blocs.
    Ball {
        /// Rayon.
        radius: f32,
    },
    /// Capsule d'axe Y.
    Capsule {
        /// Demi-hauteur du segment central.
        half_height: f32,
        /// Rayon.
        radius: f32,
    },
    /// Cylindre d'axe Y (R-971).
    Cylinder {
        /// Demi-hauteur.
        half_height: f32,
        /// Rayon.
        radius: f32,
    },
    /// Cône d'axe Y (R-971).
    Cone {
        /// Demi-hauteur.
        half_height: f32,
        /// Rayon de la base.
        radius: f32,
    },
    /// Enveloppe convexe de 4 à 256 points — la seule forme refitable (R-970).
    ConvexHull {
        /// Nuage de points `[x, y, z]`.
        points: Vec<[f32; 3]>,
    },
    /// Assemblage d'au plus 64 formes filles placées.
    Compound {
        /// Formes filles, chacune avec sa pose relative.
        parts: Vec<CompoundPart>,
    },
}

/// Une forme fille d'un [`Shape::Compound`], avec sa pose relative au corps.
#[derive(Debug, Clone, PartialEq)]
pub struct CompoundPart {
    /// Translation relative, en blocs.
    pub translation: Vec3,
    /// Rotation relative, quaternion `(x, y, z, w)` (R-461).
    pub rotation: Quat,
    /// Forme fille.
    pub shape: Shape,
}

/// Ce qui empêche d'ajouter un corps (§10.3).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BodyError {
    /// `ConvexHull` avec moins de 4 points.
    ConvexHullTooFewPoints,
    /// `ConvexHull` avec plus de 256 points.
    ConvexHullTooManyPoints,
    /// `ConvexHull` dont les points sont dégénérés (coplanaires ou colinéaires) :
    /// ils ne forment aucun volume.
    DegenerateConvexHull,
    /// `Compound` sans aucune forme fille.
    EmptyCompound,
    /// `Compound` avec plus de 64 formes filles.
    CompoundTooManyParts,
}

impl fmt::Display for BodyError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let message = match self {
            Self::ConvexHullTooFewPoints => "une enveloppe convexe exige au moins 4 points",
            Self::ConvexHullTooManyPoints => "une enveloppe convexe admet au plus 256 points",
            Self::DegenerateConvexHull => "les points de l'enveloppe convexe ne forment aucun volume",
            Self::EmptyCompound => "un composé doit avoir au moins une forme fille",
            Self::CompoundTooManyParts => "un composé admet au plus 64 formes filles",
        };
        f.write_str(message)
    }
}

impl std::error::Error for BodyError {}

/// Référence opaque d'un corps dans un [`PhysicsWorld`](crate::PhysicsWorld).
///
/// Elle enveloppe un handle `rapier` sans jamais l'exposer (R-1753) : aucun code
/// hors de ce crate ne peut lire ni fabriquer le handle sous-jacent.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct BodyId(RigidBodyHandle);

impl BodyId {
    /// Enveloppe un handle rapier.
    pub(crate) fn from_handle(handle: RigidBodyHandle) -> Self {
        Self(handle)
    }

    /// Handle rapier sous-jacent, réservé au crate.
    pub(crate) fn handle(self) -> RigidBodyHandle {
        self.0
    }
}
