//! DM-06 — colliders.

use super::geometry::Transform;

/// Forme d'un collider (DM-06).
///
/// `repr(C, u32)` : R-262 interdit qu'une structure `repr(Rust)` traverse la
/// frontière, et cette forme-ci y voyage dans la section `PHYS` d'un A3D comme
/// dans les tampons partagés.
#[repr(C, u32)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum ColliderShape {
    /// Sphère.
    Sphere {
        /// Rayon, en blocs.
        radius: f32,
    },
    /// Boîte alignée sur les axes locaux.
    Box {
        /// Demi-dimensions.
        half_extents: [f32; 3],
    },
    /// Capsule le long de l'axe Y local.
    Capsule {
        /// Demi-hauteur du segment.
        half_height: f32,
        /// Rayon.
        radius: f32,
    },
    /// Cylindre.
    Cylinder {
        /// Demi-hauteur.
        half_height: f32,
        /// Rayon.
        radius: f32,
    },
    /// Cône.
    Cone {
        /// Demi-hauteur.
        half_height: f32,
        /// Rayon de la base.
        radius: f32,
    },
    /// Enveloppe convexe.
    ConvexHull {
        /// Premier point, dans le tableau de points de l'asset.
        points_offset: u32,
        /// Nombre de points, entre [`CONVEX_MIN_POINTS`] et
        /// [`CONVEX_MAX_POINTS`] (R-161).
        points_count: u32,
    },
    /// Maillage de triangles. **Interdit sur un body dynamique** (R-160).
    TriMesh {
        /// Premier sommet.
        vertices_offset: u32,
        /// Nombre de sommets.
        vertices_count: u32,
        /// Premier indice.
        indices_offset: u32,
        /// Nombre d'indices.
        indices_count: u32,
    },
    /// Champ de hauteurs. **Interdit sur un body dynamique** (R-160).
    Heightfield {
        /// Nombre de lignes.
        rows: u32,
        /// Nombre de colonnes.
        cols: u32,
        /// Position des hauteurs.
        data_offset: u32,
        /// Échelle par axe.
        scale: [f32; 3],
    },
    /// Assemblage de formes.
    Compound {
        /// Premier enfant.
        children_offset: u32,
        /// Nombre d'enfants.
        children_count: u32,
    },
}

/// Nombre minimal de points d'une enveloppe convexe (R-161).
pub const CONVEX_MIN_POINTS: u32 = 4;

/// Nombre maximal de points d'une enveloppe convexe (R-161).
pub const CONVEX_MAX_POINTS: u32 = 256;

/// Nombre maximal de points d'enveloppe conservés par collider (R-163).
pub const HULL_POINTS_MAX: u32 = 256;

impl ColliderShape {
    /// Indique si la forme est admise sur un body dynamique.
    ///
    /// R-160 : ni `TriMesh` ni `Heightfield`, y compris après déformation
    /// (INV-13). Un maillage de triangles n'a pas de volume défini, et un
    /// solveur ne sait pas en tirer une réponse de contact stable.
    #[must_use]
    pub const fn allowed_on_dynamic_body(&self) -> bool {
        !matches!(
            self,
            ColliderShape::TriMesh { .. } | ColliderShape::Heightfield { .. }
        )
    }

    /// Nom de la forme, pour les messages d'erreur.
    #[must_use]
    pub const fn name(&self) -> &'static str {
        match self {
            ColliderShape::Sphere { .. } => "Sphere",
            ColliderShape::Box { .. } => "Box",
            ColliderShape::Capsule { .. } => "Capsule",
            ColliderShape::Cylinder { .. } => "Cylinder",
            ColliderShape::Cone { .. } => "Cone",
            ColliderShape::ConvexHull { .. } => "ConvexHull",
            ColliderShape::TriMesh { .. } => "TriMesh",
            ColliderShape::Heightfield { .. } => "Heightfield",
            ColliderShape::Compound { .. } => "Compound",
        }
    }

    /// Indique si toutes les dimensions de la forme sont finies et positives.
    ///
    /// Un rayon nul, négatif ou non fini ne décrit aucun volume : le solveur en
    /// tirerait des contacts absurdes, et une valeur non finie contaminerait
    /// tout ce qu'elle touche.
    #[must_use]
    pub fn has_valid_dimensions(&self) -> bool {
        let positive = |value: &f32| value.is_finite() && *value > 0.0;
        match self {
            ColliderShape::Sphere { radius } => positive(radius),
            ColliderShape::Box { half_extents } => half_extents.iter().all(positive),
            ColliderShape::Capsule {
                half_height,
                radius,
            }
            | ColliderShape::Cylinder {
                half_height,
                radius,
            }
            | ColliderShape::Cone {
                half_height,
                radius,
            } => positive(half_height) && positive(radius),
            ColliderShape::Heightfield {
                rows, cols, scale, ..
            } => *rows > 0 && *cols > 0 && scale.iter().all(positive),
            // Les formes indexées ne portent pas de dimension : ce sont leurs
            // dénombrements que le validateur examine.
            ColliderShape::ConvexHull { .. }
            | ColliderShape::TriMesh { .. }
            | ColliderShape::Compound { .. } => true,
        }
    }
}

/// Drapeaux d'un collider (DM-06).
pub mod collider_flags {
    /// Le collider détecte sans répondre.
    pub const SENSOR: u32 = 1 << 0;
    /// La détection continue est active.
    pub const CCD_ENABLED: u32 = 1 << 1;
    /// Le collider délimite une zone de dommage.
    pub const DAMAGE_ZONE: u32 = 1 << 2;
    /// Le collider est réajusté après déformation.
    pub const REFITTABLE: u32 = 1 << 3;
    /// Le collider n'est jamais réajusté.
    pub const NO_REFIT: u32 = 1 << 4;
}

/// Description d'un collider (DM-06).
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ColliderDesc {
    /// Forme.
    pub shape: ColliderShape,
    /// Transformation locale.
    pub local: Transform,
    /// Matériau physique.
    pub material: u16,
    /// Réservé, à zéro.
    pub _pad: u16,
    /// Groupe de collision.
    pub group: u32,
    /// Masque des groupes avec lesquels il entre en collision.
    pub mask: u32,
    /// Drapeaux, voir [`collider_flags`].
    pub flags: u32,
    /// Densité, en kg/m³ ; strictement positive.
    pub density: f32,
    /// Zone de dommage associée.
    pub damage_zone: u16,
    /// Part d'appartenance.
    pub part: u16,
    /// Région de déformation appliquée au refit.
    pub region: u16,
    /// Réservé, à zéro.
    pub _pad2: u16,
    /// Premier point d'enveloppe conservé pour le refit (R-163).
    pub hull_points_offset: u32,
    /// Nombre de points d'enveloppe, plafonné par [`HULL_POINTS_MAX`].
    pub hull_points_count: u32,
}

impl ColliderDesc {
    /// Indique si le collider porte ce drapeau.
    #[must_use]
    pub const fn has(&self, flag: u32) -> bool {
        self.flags & flag != 0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn t230_les_formes_sans_volume_sont_interdites_sur_un_body_dynamique() {
        // R-160, INV-13 : un maillage de triangles n'a pas de volume défini.
        assert!(!ColliderShape::TriMesh {
            vertices_offset: 0,
            vertices_count: 3,
            indices_offset: 0,
            indices_count: 3,
        }
        .allowed_on_dynamic_body());
        assert!(!ColliderShape::Heightfield {
            rows: 2,
            cols: 2,
            data_offset: 0,
            scale: [1.0; 3],
        }
        .allowed_on_dynamic_body());

        assert!(ColliderShape::Sphere { radius: 1.0 }.allowed_on_dynamic_body());
        assert!(ColliderShape::ConvexHull {
            points_offset: 0,
            points_count: 8,
        }
        .allowed_on_dynamic_body());
    }

    #[test]
    fn t230_une_dimension_nulle_ou_non_finie_est_refusee() {
        assert!(ColliderShape::Sphere { radius: 0.5 }.has_valid_dimensions());
        assert!(!ColliderShape::Sphere { radius: 0.0 }.has_valid_dimensions());
        assert!(!ColliderShape::Sphere { radius: -1.0 }.has_valid_dimensions());
        assert!(!ColliderShape::Sphere { radius: f32::NAN }.has_valid_dimensions());

        assert!(!ColliderShape::Box {
            half_extents: [1.0, 0.0, 1.0],
        }
        .has_valid_dimensions());
        assert!(!ColliderShape::Capsule {
            half_height: 1.0,
            radius: f32::INFINITY,
        }
        .has_valid_dimensions());
        assert!(!ColliderShape::Heightfield {
            rows: 0,
            cols: 4,
            data_offset: 0,
            scale: [1.0; 3],
        }
        .has_valid_dimensions());
    }

    #[test]
    fn t230_chaque_forme_porte_un_nom() {
        let formes = [
            ColliderShape::Sphere { radius: 1.0 },
            ColliderShape::Box {
                half_extents: [1.0; 3],
            },
            ColliderShape::Capsule {
                half_height: 1.0,
                radius: 1.0,
            },
            ColliderShape::Cylinder {
                half_height: 1.0,
                radius: 1.0,
            },
            ColliderShape::Cone {
                half_height: 1.0,
                radius: 1.0,
            },
            ColliderShape::ConvexHull {
                points_offset: 0,
                points_count: 4,
            },
            ColliderShape::TriMesh {
                vertices_offset: 0,
                vertices_count: 3,
                indices_offset: 0,
                indices_count: 3,
            },
            ColliderShape::Heightfield {
                rows: 1,
                cols: 1,
                data_offset: 0,
                scale: [1.0; 3],
            },
            ColliderShape::Compound {
                children_offset: 0,
                children_count: 1,
            },
        ];
        let mut noms: Vec<&str> = formes.iter().map(|shape| shape.name()).collect();
        let total = noms.len();
        noms.sort_unstable();
        noms.dedup();
        // R-541 veut une erreur nommée : deux formes de même nom rendraient le
        // message ambigu.
        assert_eq!(noms.len(), total);
    }

    #[test]
    fn les_bornes_d_enveloppe_convexe_sont_celles_de_r161() {
        assert_eq!(CONVEX_MIN_POINTS, 4);
        assert_eq!(CONVEX_MAX_POINTS, 256);
        assert_eq!(HULL_POINTS_MAX, 256);
    }
}
