//! DM-06 — colliders. §10.7 — événements physiques.

use super::geometry::Transform;
use super::handle::Handle;

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

/// Genres d'un [`PhysicsEvent`] (§10.7), valeurs du champ `kind`.
///
/// Les valeurs ne sont pas fixées par le cahier des charges ; elles sont
/// attribuées ici dans l'ordre d'énumération du §10.7 et gelées avec la
/// structure (voir `docs/decisions/ADR-113.md`). Un lecteur qui rencontre un
/// genre inconnu l'ignore sans erreur — c'est pourquoi le champ est un `u32`
/// ouvert plutôt qu'une énumération fermée.
pub mod event_kind {
    /// Début d'un contact.
    pub const CONTACT_START: u32 = 0;
    /// Fin d'un contact.
    pub const CONTACT_END: u32 = 1;
    /// Impulsion d'un contact persistant.
    pub const CONTACT_IMPULSE: u32 = 2;
    /// Entrée dans un capteur.
    pub const SENSOR_ENTER: u32 = 3;
    /// Sortie d'un capteur.
    pub const SENSOR_EXIT: u32 = 4;
    /// Rupture d'une liaison.
    pub const JOINT_BROKEN: u32 = 5;
    /// Blocage d'une liaison.
    pub const JOINT_JAMMED: u32 = 6;
    /// Endormissement d'un corps.
    pub const SLEEP: u32 = 7;
    /// Réveil d'un corps.
    pub const WAKE: u32 = 8;
    /// Grandeur clampée (budget dépassé).
    pub const CLAMPED: u32 = 9;
    /// Retour à un état valide.
    pub const RECOVERED: u32 = 10;
    /// Attache d'un élément.
    pub const ATTACH: u32 = 11;
    /// Détachement d'un attachement.
    pub const DETACH_ATTACHMENT: u32 = 12;
}

/// Événement physique produit en natif (§10.7, DM).
///
/// Transmis par lot et appliqué côté Java sur le thread autoritatif (R-1010).
/// C'est le contrat de données vers C-41 (R-615) : chaque contact publie point,
/// normale, impulsions normale et tangentielle, vitesse relative, masse
/// effective et matériaux. `assembly_b` vaut [`Handle::ABSENT`] pour un
/// événement à un seul corps (sommeil, réveil) ou contre le monde.
///
/// **Disposition figée en V1.0** : 76 octets, alignement 4, champs dans l'ordre
/// du §10.7. Le test de disposition la verrouille — un champ déplacé rendrait
/// illisible tout lot déjà échangé, sans qu'aucune erreur ne le dise.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct PhysicsEvent {
    /// Genre, voir [`event_kind`].
    pub kind: u32,
    /// Premier corps concerné.
    pub assembly_a: Handle,
    /// Second corps, ou [`Handle::ABSENT`] si aucun (monde, entité vanilla,
    /// événement à un corps).
    pub assembly_b: Handle,
    /// Node du premier corps.
    pub node_a: u32,
    /// Node du second corps.
    pub node_b: u32,
    /// Point de contact, en coordonnées monde locales.
    pub point: [f32; 3],
    /// Normale de contact.
    pub normal: [f32; 3],
    /// Impulsion normale, en N·s.
    pub impulse: f32,
    /// Impulsion tangentielle (frottement), en N·s.
    pub tangent_impulse: f32,
    /// Vitesse relative au point de contact, en m/s.
    pub relative_velocity: f32,
    /// Masse effective au contact, en kg.
    pub effective_mass: f32,
    /// Matériau du premier corps.
    pub material_a: u16,
    /// Matériau du second corps.
    pub material_b: u16,
    /// Charge utile propre au genre.
    pub data: u32,
}

impl PhysicsEvent {
    /// Taille de la structure sur la frontière, en octets.
    pub const BYTES: usize = 76;
}

/// Drapeaux d'un [`BodyState`] (DM-08), champ `flags`.
pub mod body_state_flags {
    /// Le corps dort.
    pub const SLEEPING: u32 = 1 << 0;
    /// Le corps touche le sol (collision avec le groupe `world`).
    pub const TOUCHING_GROUND: u32 = 1 << 1;
    /// Le corps est immergé dans un fluide.
    pub const IN_FLUID: u32 = 1 << 2;
    /// Une vitesse a été clampée à sa borne (R-180).
    pub const CLAMPED: u32 = 1 << 3;
    /// Le corps est déformé.
    pub const DEFORMED: u32 = 1 << 4;
    /// Le corps est endommagé.
    pub const DAMAGED: u32 = 1 << 5;
}

/// État d'un corps rapporté par le cycle de simulation (DM-08, IF-03).
///
/// La position est en coordonnées **monde** (`f64`, R-462) ; la simulation
/// travaille en `f32` autour d'une origine flottante, recomposée ici. Vitesses
/// linéaire et angulaire en repère monde.
///
/// **Disposition figée en V1.0** : 80 octets, alignement 8 (imposé par la
/// position `f64`), 4 octets de remplissage final. Le test de disposition la
/// verrouille.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct BodyState {
    /// Corps concerné.
    pub handle: Handle,
    /// Position monde, en blocs.
    pub position: [f64; 3],
    /// Rotation, quaternion `(x, y, z, w)` (R-461).
    pub rotation: [f32; 4],
    /// Vitesse linéaire, en m/s.
    pub lin_vel: [f32; 3],
    /// Vitesse angulaire, en rad/s.
    pub ang_vel: [f32; 3],
    /// Drapeaux, voir [`body_state_flags`].
    pub flags: u32,
}

impl BodyState {
    /// Taille de la structure sur la frontière, en octets (remplissage compris).
    pub const BYTES: usize = 80;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dm_physics_event_disposition_figee() {
        use core::mem::{align_of, offset_of, size_of};
        assert_eq!(size_of::<PhysicsEvent>(), PhysicsEvent::BYTES);
        assert_eq!(align_of::<PhysicsEvent>(), 4);
        assert_eq!(offset_of!(PhysicsEvent, kind), 0);
        assert_eq!(offset_of!(PhysicsEvent, assembly_a), 4);
        assert_eq!(offset_of!(PhysicsEvent, assembly_b), 12);
        assert_eq!(offset_of!(PhysicsEvent, node_a), 20);
        assert_eq!(offset_of!(PhysicsEvent, node_b), 24);
        assert_eq!(offset_of!(PhysicsEvent, point), 28);
        assert_eq!(offset_of!(PhysicsEvent, normal), 40);
        assert_eq!(offset_of!(PhysicsEvent, impulse), 52);
        assert_eq!(offset_of!(PhysicsEvent, tangent_impulse), 56);
        assert_eq!(offset_of!(PhysicsEvent, relative_velocity), 60);
        assert_eq!(offset_of!(PhysicsEvent, effective_mass), 64);
        assert_eq!(offset_of!(PhysicsEvent, material_a), 68);
        assert_eq!(offset_of!(PhysicsEvent, material_b), 70);
        assert_eq!(offset_of!(PhysicsEvent, data), 72);
    }

    #[test]
    fn dm_body_state_disposition_figee() {
        use core::mem::{align_of, offset_of, size_of};
        assert_eq!(size_of::<BodyState>(), BodyState::BYTES);
        assert_eq!(align_of::<BodyState>(), 8);
        assert_eq!(offset_of!(BodyState, handle), 0);
        assert_eq!(offset_of!(BodyState, position), 8);
        assert_eq!(offset_of!(BodyState, rotation), 32);
        assert_eq!(offset_of!(BodyState, lin_vel), 48);
        assert_eq!(offset_of!(BodyState, ang_vel), 60);
        assert_eq!(offset_of!(BodyState, flags), 72);
    }

    #[test]
    fn dm_handle_disposition_figee() {
        use core::mem::{align_of, offset_of, size_of};
        assert_eq!(size_of::<Handle>(), 8);
        assert_eq!(align_of::<Handle>(), 4);
        assert_eq!(offset_of!(Handle, index), 0);
        assert_eq!(offset_of!(Handle, generation), 4);
        assert!(Handle::ABSENT.is_absent());
        assert!(!Handle::new(3, 1).is_absent());
    }

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
