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

/// Sens du champ `data` d'un [`PhysicsEvent`] selon son genre (ADR-123).
///
/// La disposition de l'événement ne change pas : seul le sens d'un champ que le §10.7
/// laisse « propre au genre » est fixé ici, ratifié avec ADR-123.
pub mod event_data {
    /// Contact (`CONTACT_*`) : bit 0 à 1 quand l'autre corps est le proxy d'une entité
    /// vanilla — `assembly_b` vaut alors `{ index: identifiant réseau de l'entité,
    /// generation: 0 }`, `node_b` et `material_b` valent 0.
    pub const CONTACT_OTHER_ENTITY: u32 = 1 << 0;
    /// `RECOVERED` : code de l'erreur dont le corps a été restauré — `E-2030`, état non fini
    /// ou hors du monde (FM-20).
    pub const RECOVERED_INVALID_STATE: u32 = 2030;
    /// `CLAMPED` : une vitesse a été bornée (R-180), au débit du journal — une fois par corps
    /// et par minute.
    pub const CLAMPED_VELOCITY: u32 = 1;
    /// `CLAMPED` : le corps a été endormi par la dégradation de budget (FM-21) — il ne
    /// l'aurait pas été aux rayon et plafond nominaux.
    pub const CLAMPED_BUDGET: u32 = 2;
    /// `CLAMPED` : le corps s'agitait sur place dans un empilement (FM-22) — amorti, puis
    /// endormi s'il ne se calme pas.
    pub const CLAMPED_STACKING: u32 = 3;
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

    /// Sérialise l'événement en little-endian, dans la disposition figée (76
    /// octets), à la fin de `out`.
    ///
    /// Champ à champ plutôt que par transtypage : le résultat est identique à la
    /// mémoire `repr(C)` sur une plateforme little-endian (ce que le test de
    /// disposition garantit par les offsets), sans exiger de `unsafe` ni la
    /// définition d'octets de remplissage.
    pub fn write_le(&self, out: &mut Vec<u8>) {
        out.extend_from_slice(&self.kind.to_le_bytes());
        out.extend_from_slice(&self.assembly_a.index.to_le_bytes());
        out.extend_from_slice(&self.assembly_a.generation.to_le_bytes());
        out.extend_from_slice(&self.assembly_b.index.to_le_bytes());
        out.extend_from_slice(&self.assembly_b.generation.to_le_bytes());
        out.extend_from_slice(&self.node_a.to_le_bytes());
        out.extend_from_slice(&self.node_b.to_le_bytes());
        for value in self.point {
            out.extend_from_slice(&value.to_le_bytes());
        }
        for value in self.normal {
            out.extend_from_slice(&value.to_le_bytes());
        }
        out.extend_from_slice(&self.impulse.to_le_bytes());
        out.extend_from_slice(&self.tangent_impulse.to_le_bytes());
        out.extend_from_slice(&self.relative_velocity.to_le_bytes());
        out.extend_from_slice(&self.effective_mass.to_le_bytes());
        out.extend_from_slice(&self.material_a.to_le_bytes());
        out.extend_from_slice(&self.material_b.to_le_bytes());
        out.extend_from_slice(&self.data.to_le_bytes());
    }
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

    /// Sérialise l'état en little-endian, dans la disposition figée (80 octets,
    /// remplissage final compris), à la fin de `out`.
    pub fn write_le(&self, out: &mut Vec<u8>) {
        out.extend_from_slice(&self.handle.index.to_le_bytes());
        out.extend_from_slice(&self.handle.generation.to_le_bytes());
        for value in self.position {
            out.extend_from_slice(&value.to_le_bytes());
        }
        for value in self.rotation {
            out.extend_from_slice(&value.to_le_bytes());
        }
        for value in self.lin_vel {
            out.extend_from_slice(&value.to_le_bytes());
        }
        for value in self.ang_vel {
            out.extend_from_slice(&value.to_le_bytes());
        }
        out.extend_from_slice(&self.flags.to_le_bytes());
        out.extend_from_slice(&[0u8; 4]); // remplissage final
    }
}

/// Emprise d'un corps rapporté (ajout à DM-08, ADR-120) : sa boîte englobante
/// alignée sur les axes du monde, **relative à la position** de son
/// [`BodyState`], en blocs.
///
/// C'est la source de la hitbox vanilla (R-702) : Java la recompose avec la
/// position `f64` de l'état. Union des emprises de tous les colliders du corps,
/// calculée depuis la pose rapportée. Garanties : composantes finies,
/// `min <= max` sur chaque axe ; une emprise incalculable est la boîte nulle
/// [`BodyBounds::EMPTY`].
///
/// Dans `SIM_OUT`, le tableau `BodyBounds[state_count]` suit `BodyState[]`,
/// dans le même ordre (schéma 1 du tampon).
///
/// **Disposition figée** : 24 octets, alignement 4, aucun remplissage.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct BodyBounds {
    /// Coin minimal, relatif à la position du corps.
    pub min: [f32; 3],
    /// Coin maximal, relatif à la position du corps.
    pub max: [f32; 3],
}

impl BodyBounds {
    /// Taille de la structure sur la frontière, en octets.
    pub const BYTES: usize = 24;

    /// Boîte nulle, rapportée pour un corps dont l'emprise ne peut être calculée.
    pub const EMPTY: BodyBounds = BodyBounds {
        min: [0.0; 3],
        max: [0.0; 3],
    };

    /// Sérialise l'emprise en little-endian, dans la disposition figée, à la fin
    /// de `out`.
    pub fn write_le(&self, out: &mut Vec<u8>) {
        for value in self.min.iter().chain(self.max.iter()) {
            out.extend_from_slice(&value.to_le_bytes());
        }
    }

    /// Relit une emprise écrite par [`BodyBounds::write_le`].
    #[must_use]
    pub fn read_le(bytes: &[u8; Self::BYTES]) -> Self {
        let at = |rank: usize| {
            let start = rank * 4;
            f32::from_le_bytes([
                bytes[start],
                bytes[start + 1],
                bytes[start + 2],
                bytes[start + 3],
            ])
        };
        BodyBounds {
            min: [at(0), at(1), at(2)],
            max: [at(3), at(4), at(5)],
        }
    }
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
    fn body_state_write_le_respecte_la_disposition() {
        let state = BodyState {
            handle: Handle::new(3, 7),
            position: [1.0, 2.0, 3.0],
            rotation: [0.1, 0.2, 0.3, 0.4],
            lin_vel: [4.0, 5.0, 6.0],
            ang_vel: [7.0, 8.0, 9.0],
            flags: 0b101,
        };
        let mut bytes = Vec::new();
        state.write_le(&mut bytes);
        assert_eq!(bytes.len(), BodyState::BYTES);
        assert_eq!(u32::from_le_bytes(bytes[0..4].try_into().unwrap()), 3);
        assert_eq!(u32::from_le_bytes(bytes[4..8].try_into().unwrap()), 7);
        assert_eq!(f64::from_le_bytes(bytes[8..16].try_into().unwrap()), 1.0);
        assert_eq!(f32::from_le_bytes(bytes[32..36].try_into().unwrap()), 0.1);
        assert_eq!(u32::from_le_bytes(bytes[72..76].try_into().unwrap()), 0b101);
    }

    #[test]
    fn physics_event_write_le_respecte_la_disposition() {
        let event = PhysicsEvent {
            kind: 2,
            assembly_a: Handle::new(1, 1),
            assembly_b: Handle::ABSENT,
            node_a: 5,
            node_b: 0,
            point: [1.0, 2.0, 3.0],
            normal: [0.0, 1.0, 0.0],
            impulse: 4.0,
            tangent_impulse: 0.5,
            relative_velocity: -2.0,
            effective_mass: 0.5,
            material_a: 9,
            material_b: 3,
            data: 0,
        };
        let mut bytes = Vec::new();
        event.write_le(&mut bytes);
        assert_eq!(bytes.len(), PhysicsEvent::BYTES);
        assert_eq!(u32::from_le_bytes(bytes[0..4].try_into().unwrap()), 2);
        assert_eq!(f32::from_le_bytes(bytes[52..56].try_into().unwrap()), 4.0);
        assert_eq!(u16::from_le_bytes(bytes[68..70].try_into().unwrap()), 9);
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
    fn dm_body_bounds_disposition_figee() {
        // ADR-120 : 24 octets, alignement 4, `min` puis `max`, aucun remplissage.
        use core::mem::{align_of, offset_of, size_of};
        assert_eq!(size_of::<BodyBounds>(), BodyBounds::BYTES);
        assert_eq!(align_of::<BodyBounds>(), 4);
        assert_eq!(offset_of!(BodyBounds, min), 0);
        assert_eq!(offset_of!(BodyBounds, max), 12);
    }

    #[test]
    fn body_bounds_write_le_respecte_la_disposition() {
        let bounds = BodyBounds {
            min: [-0.5, 0.0, -1.25],
            max: [0.5, 1.0, 2.5],
        };
        let mut bytes = Vec::new();
        bounds.write_le(&mut bytes);
        assert_eq!(bytes.len(), BodyBounds::BYTES);
        // Octet par octet, d'après la disposition : six f32 little-endian.
        let attendus: [f32; 6] = [-0.5, 0.0, -1.25, 0.5, 1.0, 2.5];
        for (rank, value) in attendus.iter().enumerate() {
            let at = rank * 4;
            assert_eq!(
                bytes[at..at + 4],
                value.to_le_bytes(),
                "composante {rank} mal placée"
            );
        }
        let relue = BodyBounds::read_le(bytes.as_slice().try_into().unwrap());
        assert_eq!(relue, bounds);
    }

    #[test]
    fn body_bounds_vide_est_la_boite_nulle() {
        let mut bytes = Vec::new();
        BodyBounds::EMPTY.write_le(&mut bytes);
        assert_eq!(bytes, vec![0u8; BodyBounds::BYTES]);
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
