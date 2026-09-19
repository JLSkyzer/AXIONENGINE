//! Corps rigides et formes de collision (§10.2, §10.3).

use rapier3d::prelude::RigidBodyHandle;

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

/// Forme de collision d'un corps.
///
/// Tranche 1 : les deux primitives suffisantes pour éprouver le monde. Le jeu
/// complet de §10.3 (Capsule, Cylinder, Cone, ConvexHull, Compound, TriMesh,
/// Heightfield) arrive en tranche 2 avec le Collider Builder (C-32), sous
/// l'interdiction R-970 — ni `TriMesh` ni `Heightfield` sur un corps dynamique.
#[derive(Debug, Clone, Copy, PartialEq)]
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
}

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
