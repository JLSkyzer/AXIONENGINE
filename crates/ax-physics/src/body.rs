//! Corps rigides et formes de collision (§10.2, §10.3).

use ax_math::{Quat, Vec3};
use ax_model::dm::physics::{ColliderDesc, ColliderShape};
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

/// Un collider d'un corps : sa forme, sa densité et sa pose relative au corps.
///
/// C'est l'unité d'assemblage d'un corps multi-colliders
/// ([`add_assembly`](crate::PhysicsWorld::add_assembly)) : chaque collider porte
/// **sa** densité, si bien que la masse, le centre de masse et l'inertie du corps
/// sont ceux des matériaux déclarés (R-622, moitié « calculée depuis les densités »),
/// et non d'une densité unique. Une carrosserie d'acier et des pneus de caoutchouc
/// donnent ainsi la bonne masse et le bon centre de masse.
#[derive(Debug, Clone, PartialEq)]
pub struct BodyCollider {
    /// Forme de collision.
    pub shape: Shape,
    /// Masse volumique, en kg/m³ (DM-06, R-622). Strictement positive.
    pub density: f32,
    /// Translation relative au corps, en blocs.
    pub translation: Vec3,
    /// Rotation relative au corps, quaternion `(x, y, z, w)` (R-461).
    pub rotation: Quat,
}

impl Shape {
    /// Convertit une forme de collider compilée ([`ColliderShape`], DM-06) en
    /// forme runtime (C-32, pont d'ADR-115).
    ///
    /// Les primitives (`Sphere`, `Box`, `Capsule`, `Cylinder`, `Cone`) passent
    /// directement. `ConvexHull` lit ses points dans `points` (l'annexe de la
    /// section `PHYS`) via `points_offset`/`points_count`, en indices de points :
    /// le nombre de points admissible (4..256, R-161) et la non-dégénérescence sont
    /// ensuite vérifiés par [`PhysicsWorld::add_assembly`](crate::PhysicsWorld::add_assembly).
    /// `Compound` lit ses formes filles dans `children` (l'annexe des enfants) via
    /// `children_offset`/`children_count`, chacune placée par son `local` ; une fille
    /// ne peut pas être elle-même un `Compound` (pas d'imbrication). `TriMesh` et
    /// `Heightfield` restent **interdits sur un corps dynamique** (R-160, INV-13)
    /// ([`ShapeConversionError::ForbiddenOnDynamicBody`]).
    ///
    /// # Errors
    ///
    /// [`ShapeConversionError`] selon la forme refusée, ou lorsqu'une forme indexée
    /// (`ConvexHull`, `Compound`) déborde de son annexe, ou qu'un `Compound` en
    /// imbrique un autre.
    pub fn from_collider_shape(
        shape: &ColliderShape,
        points: &[[f32; 3]],
        children: &[ColliderDesc],
    ) -> Result<Self, ShapeConversionError> {
        Ok(match *shape {
            ColliderShape::Sphere { radius } => Shape::Ball { radius },
            ColliderShape::Box { half_extents } => Shape::Cuboid { half_extents },
            ColliderShape::Capsule {
                half_height,
                radius,
            } => Shape::Capsule {
                half_height,
                radius,
            },
            ColliderShape::Cylinder {
                half_height,
                radius,
            } => Shape::Cylinder {
                half_height,
                radius,
            },
            ColliderShape::Cone {
                half_height,
                radius,
            } => Shape::Cone {
                half_height,
                radius,
            },
            ColliderShape::ConvexHull {
                points_offset,
                points_count,
            } => {
                let start = points_offset as usize;
                let end = start
                    .checked_add(points_count as usize)
                    .filter(|end| *end <= points.len())
                    .ok_or(ShapeConversionError::HullPointsOutOfRange)?;
                Shape::ConvexHull {
                    points: points[start..end].to_vec(),
                }
            }
            ColliderShape::Compound {
                children_offset,
                children_count,
            } => {
                let start = children_offset as usize;
                let end = start
                    .checked_add(children_count as usize)
                    .filter(|end| *end <= children.len())
                    .ok_or(ShapeConversionError::CompoundChildrenOutOfRange)?;
                let mut parts = Vec::with_capacity(children_count as usize);
                for child in &children[start..end] {
                    if matches!(child.shape, ColliderShape::Compound { .. }) {
                        return Err(ShapeConversionError::NestedCompound);
                    }
                    // Une fille ne référence pas d'autres filles : `&[]`.
                    let shape = Shape::from_collider_shape(&child.shape, points, &[])?;
                    parts.push(CompoundPart {
                        translation: Vec3::from_array(child.local.translation),
                        rotation: Quat::from_array(child.local.rotation),
                        shape,
                    });
                }
                Shape::Compound { parts }
            }
            ColliderShape::TriMesh { .. } | ColliderShape::Heightfield { .. } => {
                return Err(ShapeConversionError::ForbiddenOnDynamicBody);
            }
        })
    }
}

/// Ce qui empêche de convertir un [`ColliderShape`] (DM-06) en [`Shape`] runtime.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ShapeConversionError {
    /// `TriMesh`/`Heightfield` : interdits sur un corps dynamique (R-160, INV-13).
    ForbiddenOnDynamicBody,
    /// `ConvexHull` dont `points_offset`/`points_count` sort de l'annexe des points
    /// fournie — cache altéré ou incohérent (R-901).
    HullPointsOutOfRange,
    /// `Compound` dont `children_offset`/`children_count` sort de l'annexe des
    /// enfants fournie — cache altéré ou incohérent (R-901).
    CompoundChildrenOutOfRange,
    /// Une forme fille d'un `Compound` est elle-même un `Compound` : rapier ne les
    /// imbrique pas.
    NestedCompound,
}

impl fmt::Display for ShapeConversionError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let message = match self {
            Self::ForbiddenOnDynamicBody => {
                "TriMesh et Heightfield sont interdits sur un corps dynamique"
            }
            Self::HullPointsOutOfRange => {
                "les points d'une enveloppe convexe sortent de l'annexe fournie"
            }
            Self::CompoundChildrenOutOfRange => {
                "les enfants d'un compound sortent de l'annexe fournie"
            }
            Self::NestedCompound => "un compound ne peut pas en imbriquer un autre",
        };
        f.write_str(message)
    }
}

impl std::error::Error for ShapeConversionError {}

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
            Self::DegenerateConvexHull => {
                "les points de l'enveloppe convexe ne forment aucun volume"
            }
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

#[cfg(test)]
mod tests {
    use super::*;

    /// Un `ColliderDesc` d'enfant de compound : forme + pose locale, le reste
    /// neutre (la masse et le matériau d'un compound vivent au niveau du parent).
    fn child(shape: ColliderShape, translation: [f32; 3]) -> ColliderDesc {
        ColliderDesc {
            shape,
            local: ax_model::dm::geometry::Transform {
                translation,
                ..ax_model::dm::geometry::Transform::identity()
            },
            material: u16::MAX,
            _pad: 0,
            group: u32::MAX,
            mask: u32::MAX,
            flags: 0,
            density: 1000.0,
            damage_zone: u16::MAX,
            part: u16::MAX,
            region: u16::MAX,
            _pad2: 0,
            hull_points_offset: 0,
            hull_points_count: 0,
        }
    }

    #[test]
    fn conversion_des_primitives() {
        assert_eq!(
            Shape::from_collider_shape(&ColliderShape::Sphere { radius: 2.0 }, &[], &[]).unwrap(),
            Shape::Ball { radius: 2.0 }
        );
        assert_eq!(
            Shape::from_collider_shape(
                &ColliderShape::Box {
                    half_extents: [1.0, 2.0, 3.0]
                },
                &[],
                &[]
            )
            .unwrap(),
            Shape::Cuboid {
                half_extents: [1.0, 2.0, 3.0]
            }
        );
        assert_eq!(
            Shape::from_collider_shape(
                &ColliderShape::Capsule {
                    half_height: 1.0,
                    radius: 0.5
                },
                &[],
                &[]
            )
            .unwrap(),
            Shape::Capsule {
                half_height: 1.0,
                radius: 0.5
            }
        );
    }

    #[test]
    fn conversion_de_l_enveloppe_convexe() {
        // Les points viennent de l'annexe, tranchés par offset/count.
        let cloud = [
            [0.0, 0.0, 0.0],
            [1.0, 0.0, 0.0],
            [0.0, 1.0, 0.0],
            [0.0, 0.0, 1.0],
        ];
        assert_eq!(
            Shape::from_collider_shape(
                &ColliderShape::ConvexHull {
                    points_offset: 0,
                    points_count: 4
                },
                &cloud,
                &[]
            )
            .unwrap(),
            Shape::ConvexHull {
                points: cloud.to_vec()
            }
        );
        // Des points hors de l'annexe : refusé, jamais de panique.
        assert_eq!(
            Shape::from_collider_shape(
                &ColliderShape::ConvexHull {
                    points_offset: 2,
                    points_count: 4
                },
                &cloud,
                &[]
            ),
            Err(ShapeConversionError::HullPointsOutOfRange)
        );
    }

    #[test]
    fn conversion_du_compound() {
        // Deux filles placées par leur `local` : un compound de deux formes.
        let children = [
            child(
                ColliderShape::Box {
                    half_extents: [0.5, 0.5, 0.5],
                },
                [1.0, 0.0, 0.0],
            ),
            child(ColliderShape::Sphere { radius: 0.25 }, [-1.0, 0.0, 0.0]),
        ];
        let shape = Shape::from_collider_shape(
            &ColliderShape::Compound {
                children_offset: 0,
                children_count: 2,
            },
            &[],
            &children,
        )
        .unwrap();
        let Shape::Compound { parts } = shape else {
            panic!("attendu un compound, obtenu {shape:?}");
        };
        assert_eq!(parts.len(), 2);
        assert_eq!(parts[0].translation, Vec3::new(1.0, 0.0, 0.0));
        assert_eq!(
            parts[0].shape,
            Shape::Cuboid {
                half_extents: [0.5, 0.5, 0.5]
            }
        );
        assert_eq!(parts[1].translation, Vec3::new(-1.0, 0.0, 0.0));
        assert_eq!(parts[1].shape, Shape::Ball { radius: 0.25 });
    }

    #[test]
    fn conversion_refuse_compound_hors_annexe_et_imbrique() {
        // Enfants hors de l'annexe fournie.
        assert_eq!(
            Shape::from_collider_shape(
                &ColliderShape::Compound {
                    children_offset: 0,
                    children_count: 2
                },
                &[],
                &[child(ColliderShape::Sphere { radius: 1.0 }, [0.0; 3])]
            ),
            Err(ShapeConversionError::CompoundChildrenOutOfRange)
        );
        // Une fille qui est elle-même un compound : refusée (pas d'imbrication).
        assert_eq!(
            Shape::from_collider_shape(
                &ColliderShape::Compound {
                    children_offset: 0,
                    children_count: 1
                },
                &[],
                &[child(
                    ColliderShape::Compound {
                        children_offset: 0,
                        children_count: 0
                    },
                    [0.0; 3]
                )]
            ),
            Err(ShapeConversionError::NestedCompound)
        );
    }

    #[test]
    fn conversion_refuse_les_formes_interdites() {
        assert_eq!(
            Shape::from_collider_shape(
                &ColliderShape::TriMesh {
                    vertices_offset: 0,
                    vertices_count: 3,
                    indices_offset: 0,
                    indices_count: 3
                },
                &[],
                &[]
            ),
            Err(ShapeConversionError::ForbiddenOnDynamicBody)
        );
        assert_eq!(
            Shape::from_collider_shape(
                &ColliderShape::Heightfield {
                    rows: 2,
                    cols: 2,
                    data_offset: 0,
                    scale: [1.0, 1.0, 1.0]
                },
                &[],
                &[]
            ),
            Err(ShapeConversionError::ForbiddenOnDynamicBody)
        );
    }
}
