//! Proxies des entités vanilla (R-614, ADR-123 §5).
//!
//! Une entité que vanilla tient pour solide ou poussable est vue par la physique comme un
//! corps **cinématique temporaire** (§10.2, masse infinie) : posé chaque tick là où est
//! l'entité, lancé à sa vitesse — le contact voit l'entité en mouvement. Il appartient au
//! groupe réservé `entity_proxy` et ne heurte que les assemblies. L'ensemble des proxies
//! d'une dimension est remplacé à chaque tick ; l'effet d'un contact sur l'entité, lui, est
//! appliqué côté Java (R-614).

use crate::body::Shape;
use ax_math::{DVec3, Vec3};

/// Forme d'un proxy (R-614).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProxyShape {
    /// L'AABB de l'entité.
    Box,
    /// Capsule verticale inscrite dans l'AABB : un être vivant.
    Capsule,
}

/// Une entité vanilla vue par la physique pour un tick, en coordonnées monde.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct EntityProxy {
    /// Identifiant réseau de l'entité ; il identifie aussi l'entité dans les événements de
    /// contact (ADR-123 §6).
    pub entity: u32,
    /// Centre de l'AABB de l'entité, monde, en blocs.
    pub center: DVec3,
    /// Demi-dimensions de l'AABB, en blocs.
    pub half_extents: [f32; 3],
    /// Vitesse de l'entité, en m/s.
    pub velocity: Vec3,
    /// Forme.
    pub shape: ProxyShape,
}

impl EntityProxy {
    /// Vrai si le proxy est utilisable : centre et vitesse finis, demi-dimensions finies et
    /// strictement positives. Un proxy inutilisable est ignoré (ADR-123 §5) — donnée venue
    /// du jeu, jamais crue sur parole.
    #[must_use]
    pub fn is_valid(&self) -> bool {
        self.center.is_finite()
            && self.velocity.is_finite()
            && self
                .half_extents
                .iter()
                .all(|half| half.is_finite() && *half > 0.0)
    }

    /// Forme de collision : la boîte de l'AABB, ou la capsule verticale de rayon
    /// `min(hx, hz)` et de demi-segment `max(0, hy − rayon)`, centrée sur l'AABB.
    #[must_use]
    pub fn collision_shape(&self) -> Shape {
        let [hx, hy, hz] = self.half_extents;
        match self.shape {
            ProxyShape::Box => Shape::Cuboid {
                half_extents: self.half_extents,
            },
            ProxyShape::Capsule => {
                let radius = hx.min(hz);
                Shape::Capsule {
                    half_height: (hy - radius).max(0.0),
                    radius,
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn proxy(half_extents: [f32; 3], shape: ProxyShape) -> EntityProxy {
        EntityProxy {
            entity: 1,
            center: DVec3::ZERO,
            half_extents,
            velocity: Vec3::ZERO,
            shape,
        }
    }

    #[test]
    fn la_capsule_s_inscrit_dans_l_aabb_d_un_etre_vivant() {
        // Un joueur : AABB 0,6 × 1,8 × 0,6 — demi-segment 0,9 − 0,3, calculé en f32.
        assert_eq!(
            proxy([0.3, 0.9, 0.3], ProxyShape::Capsule).collision_shape(),
            Shape::Capsule {
                half_height: 0.9f32 - 0.3f32,
                radius: 0.3
            }
        );
        // Plus large que haut : la capsule se réduit à une sphère.
        assert_eq!(
            proxy([0.5, 0.25, 0.7], ProxyShape::Capsule).collision_shape(),
            Shape::Capsule {
                half_height: 0.0,
                radius: 0.5
            }
        );
        assert_eq!(
            proxy([0.5, 0.25, 0.7], ProxyShape::Box).collision_shape(),
            Shape::Cuboid {
                half_extents: [0.5, 0.25, 0.7]
            }
        );
    }

    #[test]
    fn un_proxy_non_fini_ou_plat_est_inutilisable() {
        assert!(proxy([0.3, 0.9, 0.3], ProxyShape::Box).is_valid());
        assert!(!proxy([0.3, 0.0, 0.3], ProxyShape::Box).is_valid());
        assert!(!proxy([0.3, -0.9, 0.3], ProxyShape::Box).is_valid());
        assert!(!proxy([f32::NAN, 0.9, 0.3], ProxyShape::Box).is_valid());
        let mut lointain = proxy([0.3, 0.9, 0.3], ProxyShape::Box);
        lointain.center = DVec3::new(f64::INFINITY, 0.0, 0.0);
        assert!(!lointain.is_valid());
        let mut fou = proxy([0.3, 0.9, 0.3], ProxyShape::Box);
        fou.velocity = Vec3::new(0.0, f32::NAN, 0.0);
        assert!(!fou.is_valid());
    }
}
