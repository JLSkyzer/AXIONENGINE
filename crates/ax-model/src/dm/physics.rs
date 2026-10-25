//! DM-06 — colliders. DM-07 — matériaux physiques. §10.7 — événements physiques.

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
    /// Index du matériau physique dans la table DM-07 de la session (ADR-149), ou
    /// [`NO_MATERIAL`] : écrit par Java d'après la part du collider.
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

/// Index de matériau absent (ADR-149) : `ColliderDesc::material`, matériau d'une tuile ou d'un
/// événement. Un collider sans matériau garde le contact neutre du solveur.
pub const NO_MATERIAL: u16 = 0xFFFF;

/// Modes de combinaison d'un coefficient de contact entre deux matériaux (DM-07, ADR-149 D3),
/// valeurs des champs `friction_combine` et `restitution_combine`.
///
/// Ce sont les valeurs du solveur. À modes différents, le plus fort l'emporte :
/// [`MAX`](combine::MAX) > [`MULTIPLY`](combine::MULTIPLY) > [`MIN`](combine::MIN) >
/// [`AVERAGE`](combine::AVERAGE) — l'ordre des valeurs.
pub mod combine {
    /// Moyenne des deux coefficients.
    pub const AVERAGE: u8 = 0;
    /// Le plus petit des deux.
    pub const MIN: u8 = 1;
    /// Leur produit.
    pub const MULTIPLY: u8 = 2;
    /// Le plus grand des deux.
    pub const MAX: u8 = 3;
}

/// Drapeaux d'un [`PhysicsMaterial`] (DM-07, R-170), champ `flags`, dans l'ordre du §3.7.
pub mod material_flags {
    /// Fragile : pas de plasticité, tout dépassement rompt.
    pub const BRITTLE: u32 = 1 << 0;
    /// Ductile : la plasticité prime.
    pub const DUCTILE: u32 = 1 << 1;
    /// Souple : grandes déformations élastiques.
    pub const SOFT: u32 = 1 << 2;
    /// Rigide.
    pub const RIGID: u32 = 1 << 3;
    /// Inflammable.
    pub const FLAMMABLE: u32 = 1 << 4;
    /// Exclu du système de déformation.
    pub const NON_DEFORMABLE: u32 = 1 << 5;
}

/// Matériau physique (DM-07, §3.7) : une entrée de la table que Java pousse au natif par
/// `SET_PHYSICS_MATERIALS` (ADR-149). Son index dans la table est celui que portent les
/// colliders, les tuiles et les événements.
///
/// `name_hash` est l'empreinte FNV-1a 64 de l'identifiant du matériau ; `sound_group` et
/// `particle_group`, les 32 bits de poids faible de celle de leur groupe, `0` sans groupe.
///
/// **Disposition figée** : 116 octets de champs, 120 avec l'alignement sur 8 de `name_hash` ;
/// sur la frontière, les 4 derniers octets sont à zéro. Le test de disposition la verrouille.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct PhysicsMaterial {
    /// Empreinte FNV-1a 64 de l'identifiant.
    pub name_hash: u64,
    /// Coefficient de frottement, `[0, 2]`.
    pub friction: f32,
    /// Coefficient de restitution, `[0, 1]`.
    pub restitution: f32,
    /// Coefficient de frottement de roulement, positif, sans dimension (ADR-149 D6).
    pub rolling_friction: f32,
    /// Mode de combinaison du frottement, voir [`combine`].
    pub friction_combine: u8,
    /// Mode de combinaison de la restitution, voir [`combine`].
    pub restitution_combine: u8,
    /// Réservé, à zéro.
    pub _pad0: [u8; 2],
    /// Masse volumique, kg/m³.
    pub density: f32,
    /// Résistance à la rayure, `[0, 1]`.
    pub hardness: f32,
    /// Module apparent, Pa.
    pub stiffness: f32,
    /// Part du déplacement qui revient, `[0, 1]`.
    pub elasticity: f32,
    /// Part du dépassement qui devient permanente, `[0, 1]`.
    pub plasticity: f32,
    /// Limite d'élasticité, Pa.
    pub yield_strength: f32,
    /// Seuil de rupture locale, Pa.
    pub fracture_strength: f32,
    /// Multiplicateur global de résistance, `[0,01, 100]`.
    pub deformation_resistance: f32,
    /// Déformation relative maximale avant rupture, `[0, 1]`.
    pub max_strain: f32,
    /// Amortissement du retour élastique.
    pub damping: f32,
    /// Énergie spécifique de rupture, J/m².
    pub fracture_energy: f32,
    /// Dureté dynamique d'indentation, Pa.
    pub hardness_pressure: f32,
    /// Rayon d'indenteur de référence, m.
    pub indenter_radius_ref: f32,
    /// Facteur d'étalement, `[0,5, 8]`.
    pub spread_factor: f32,
    /// Étalement selon l'épaisseur, `[0, 20]`.
    pub thickness_spread: f32,
    /// Multiplicateur du dommage reçu.
    pub damage_multiplier: f32,
    /// Part de l'énergie convertie en dommage, `[0, 1]`.
    pub energy_absorption: f32,
    /// Résistance à l'éraflure, `[0, 1]`.
    pub scratch_resistance: f32,
    /// Énergie thermique avant marquage, J.
    pub burn_threshold: f32,
    /// Vitesse d'encrassement.
    pub soil_rate: f32,
    /// Groupe de sons.
    pub sound_group: u32,
    /// Groupe de particules.
    pub particle_group: u32,
    /// Drapeaux, voir [`material_flags`].
    pub flags: u32,
}

impl PhysicsMaterial {
    /// Taille sur la frontière, en octets : celle de la structure, alignement compris.
    pub const BYTES: usize = 120;

    /// Les 20 grandeurs `f32` qui suivent les modes, dans l'ordre de la disposition.
    fn quantities(&self) -> [f32; 20] {
        [
            self.density,
            self.hardness,
            self.stiffness,
            self.elasticity,
            self.plasticity,
            self.yield_strength,
            self.fracture_strength,
            self.deformation_resistance,
            self.max_strain,
            self.damping,
            self.fracture_energy,
            self.hardness_pressure,
            self.indenter_radius_ref,
            self.spread_factor,
            self.thickness_spread,
            self.damage_multiplier,
            self.energy_absorption,
            self.scratch_resistance,
            self.burn_threshold,
            self.soil_rate,
        ]
    }

    /// Sérialise le matériau en little-endian, dans la disposition figée ([`Self::BYTES`]
    /// octets, les 4 derniers à zéro), à la fin de `out`.
    pub fn write_le(&self, out: &mut Vec<u8>) {
        out.extend_from_slice(&self.name_hash.to_le_bytes());
        for value in [self.friction, self.restitution, self.rolling_friction] {
            out.extend_from_slice(&value.to_le_bytes());
        }
        out.extend_from_slice(&[
            self.friction_combine,
            self.restitution_combine,
            self._pad0[0],
            self._pad0[1],
        ]);
        for value in self.quantities() {
            out.extend_from_slice(&value.to_le_bytes());
        }
        for value in [self.sound_group, self.particle_group, self.flags] {
            out.extend_from_slice(&value.to_le_bytes());
        }
        out.extend_from_slice(&[0u8; 4]); // alignement
    }

    /// Relit un matériau écrit par [`Self::write_le`], ou `None` si `bytes` est trop court.
    /// Les valeurs ne sont pas vérifiées : c'est au consommateur de les borner.
    #[must_use]
    pub fn read_le(bytes: &[u8]) -> Option<Self> {
        let bytes = bytes.get(..Self::BYTES)?;
        let word = |offset: usize| {
            [
                bytes[offset],
                bytes[offset + 1],
                bytes[offset + 2],
                bytes[offset + 3],
            ]
        };
        let f = |offset: usize| f32::from_le_bytes(word(offset));
        let u = |offset: usize| u32::from_le_bytes(word(offset));
        let mut hash = [0u8; 8];
        hash.copy_from_slice(&bytes[..8]);
        Some(Self {
            name_hash: u64::from_le_bytes(hash),
            friction: f(8),
            restitution: f(12),
            rolling_friction: f(16),
            friction_combine: bytes[20],
            restitution_combine: bytes[21],
            _pad0: [bytes[22], bytes[23]],
            density: f(24),
            hardness: f(28),
            stiffness: f(32),
            elasticity: f(36),
            plasticity: f(40),
            yield_strength: f(44),
            fracture_strength: f(48),
            deformation_resistance: f(52),
            max_strain: f(56),
            damping: f(60),
            fracture_energy: f(64),
            hardness_pressure: f(68),
            indenter_radius_ref: f(72),
            spread_factor: f(76),
            thickness_spread: f(80),
            damage_multiplier: f(84),
            energy_absorption: f(88),
            scratch_resistance: f(92),
            burn_threshold: f(96),
            soil_rate: f(100),
            sound_group: u(104),
            particle_group: u(108),
            flags: u(112),
        })
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
    /// Index du matériau du collider du premier corps qui touche (ADR-149 D5), ou
    /// [`NO_MATERIAL`] — sans contact, ou sans matériau.
    pub material_a: u16,
    /// Index du matériau du collider du second côté — tuile comprise —, ou [`NO_MATERIAL`].
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
    /// Le corps dort parce que la gestion d'activité l'a endormi (R-612, R-613) : ses vitesses
    /// sont gelées et lui seront rendues au réveil ; celles de l'état sont celles d'un corps
    /// endormi, nulles. Toujours avec [`SLEEPING`]. ADR-153 D4.
    pub const SUSPENDED: u32 = 1 << 6;
}

/// État d'un corps rapporté par le cycle de simulation (DM-08, IF-03).
///
/// La position est en coordonnées **monde** (`f64`, R-462) ; la simulation
/// travaille en `f32` autour d'une origine flottante, recomposée ici. Vitesses
/// linéaire et angulaire en repère monde.
///
/// **Disposition figée en V1.0** : 80 octets, alignement 8 (imposé par la
/// position `f64`). Les 4 derniers octets, remplissage jusqu'à ADR-151, portent
/// le node racine du corps : une assembly en a plusieurs (D2, D7). Le test de
/// disposition la verrouille.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct BodyState {
    /// Assembly du corps.
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
    /// Node racine du corps dans l'asset (ADR-151 D2) : celui du premier corps de
    /// l'assembly, le corps racine, que l'entité suit ; celui de chaque autre corps.
    pub node: u32,
}

impl BodyState {
    /// Taille de la structure sur la frontière, en octets.
    pub const BYTES: usize = 80;

    /// Sérialise l'état en little-endian, dans la disposition figée (80 octets),
    /// à la fin de `out`.
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
        out.extend_from_slice(&self.node.to_le_bytes());
    }
}

/// Description d'un corps (DM-08, §3.8), portée par `CREATE_ASSEMBLY` (ADR-130).
///
/// Masse, centre de masse et inertie **déclarés** priment sur ceux que les
/// densités des colliders donnent (R-622) ; non déclarés, ils en viennent :
/// `mass = 0` pour une masse calculée, inertie `[0; 3]` pour une inertie
/// calculée, centre de masse aux trois composantes NaN pour un centre calculé.
///
/// **Disposition figée en V1.0** : 52 octets, alignement 4, telle que le §3.8
/// la donne ; sur la frontière, suivie de 4 octets réservés, à zéro
/// ([`BodyDesc::WIRE_BYTES`]), pour que les octets `PHYS` qui la suivent restent
/// alignés sur 8. Le test de disposition la verrouille.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct BodyDesc {
    /// Type de corps : `0` statique, `1` cinématique, `2` dynamique (comme
    /// `CreateAssembly::body_kind`, qu'il doit égaler).
    pub body_type: u8,
    /// `1` : détection continue (CCD) ; `0` sinon.
    pub ccd: u8,
    /// `1` : le corps peut s'endormir ; `0` : jamais de lui-même.
    pub can_sleep: u8,
    /// Réservé, à zéro.
    pub _pad: u8,
    /// Masse déclarée, en kg ; `0` : calculée depuis les densités.
    pub mass: f32,
    /// Centre de masse déclaré, repère local ; NaN aux trois composantes : calculé.
    pub center_of_mass: [f32; 3],
    /// Inertie principale déclarée, repère local, en kg·m² ; `[0; 3]` : calculée.
    pub inertia: [f32; 3],
    /// Amortissement linéaire, en 1/s, positif ou nul.
    pub linear_damping: f32,
    /// Amortissement angulaire, en 1/s, positif ou nul.
    pub angular_damping: f32,
    /// Échelle de la gravité de la dimension.
    pub gravity_scale: f32,
    /// Vitesse linéaire maximale, en m/s (R-180, défaut 300).
    pub max_linear_vel: f32,
    /// Vitesse angulaire maximale, en rad/s (R-180, défaut 100).
    pub max_angular_vel: f32,
}

impl BodyDesc {
    /// Taille de la structure, en octets.
    pub const BYTES: usize = 52;

    /// Taille sur la frontière, 4 octets réservés compris.
    pub const WIRE_BYTES: usize = 56;

    /// Vitesse linéaire maximale par défaut (R-180), en m/s.
    pub const DEFAULT_MAX_LINEAR_VEL: f32 = 300.0;

    /// Vitesse angulaire maximale par défaut (R-180), en rad/s.
    pub const DEFAULT_MAX_ANGULAR_VEL: f32 = 100.0;

    /// Rien de déclaré : tout vient des densités et des défauts — sans CCD,
    /// endormissable, gravité entière, sans amortissement, plafonds de R-180.
    pub fn undeclared(body_type: u8) -> Self {
        Self {
            body_type,
            ccd: 0,
            can_sleep: 1,
            _pad: 0,
            mass: 0.0,
            center_of_mass: [f32::NAN; 3],
            inertia: [0.0; 3],
            linear_damping: 0.0,
            angular_damping: 0.0,
            gravity_scale: 1.0,
            max_linear_vel: Self::DEFAULT_MAX_LINEAR_VEL,
            max_angular_vel: Self::DEFAULT_MAX_ANGULAR_VEL,
        }
    }

    /// La masse déclarée, ou `None` : calculée.
    pub fn declared_mass(&self) -> Option<f32> {
        (self.mass > 0.0).then_some(self.mass)
    }

    /// Le centre de masse déclaré, ou `None` : calculé.
    pub fn declared_center_of_mass(&self) -> Option<[f32; 3]> {
        self.center_of_mass
            .iter()
            .all(|c| c.is_finite())
            .then_some(self.center_of_mass)
    }

    /// L'inertie principale déclarée, ou `None` : calculée.
    pub fn declared_inertia(&self) -> Option<[f32; 3]> {
        (self.inertia != [0.0; 3]).then_some(self.inertia)
    }

    /// Vérifie la description ; l'erreur nomme le champ fautif.
    ///
    /// # Errors
    ///
    /// Un champ hors de son domaine : type de corps inconnu, drapeau autre que
    /// 0 ou 1, réservé non nul, masse, inertie ou amortissement négatifs ou non
    /// finis, inertie déclarée sur une partie des axes seulement, centre de
    /// masse ni fini ni entièrement NaN, échelle de gravité non finie, plafond
    /// de vitesse non strictement positif.
    pub fn validate(&self) -> Result<(), &'static str> {
        if self.body_type > 2 {
            return Err("body_type");
        }
        if self.ccd > 1 {
            return Err("ccd");
        }
        if self.can_sleep > 1 {
            return Err("can_sleep");
        }
        if self._pad != 0 {
            return Err("_pad");
        }
        if !(self.mass.is_finite() && self.mass >= 0.0) {
            return Err("mass");
        }
        let com_finite = self.center_of_mass.iter().all(|c| c.is_finite());
        let com_nan = self.center_of_mass.iter().all(|c| c.is_nan());
        if !(com_finite || com_nan) {
            return Err("center_of_mass");
        }
        if !self.inertia.iter().all(|i| i.is_finite() && *i >= 0.0) {
            return Err("inertia");
        }
        let declared = self.inertia.iter().filter(|i| **i > 0.0).count();
        if declared != 0 && declared != 3 {
            // Une inertie principale déclarée à moitié bloquerait des axes.
            return Err("inertia");
        }
        if !(self.linear_damping.is_finite() && self.linear_damping >= 0.0) {
            return Err("linear_damping");
        }
        if !(self.angular_damping.is_finite() && self.angular_damping >= 0.0) {
            return Err("angular_damping");
        }
        if !self.gravity_scale.is_finite() {
            return Err("gravity_scale");
        }
        if !(self.max_linear_vel.is_finite() && self.max_linear_vel > 0.0) {
            return Err("max_linear_vel");
        }
        if !(self.max_angular_vel.is_finite() && self.max_angular_vel > 0.0) {
            return Err("max_angular_vel");
        }
        Ok(())
    }

    /// Sérialise la description en little-endian, dans la disposition figée,
    /// suivie de ses 4 octets réservés ([`Self::WIRE_BYTES`]), à la fin de `out`.
    pub fn write_le(&self, out: &mut Vec<u8>) {
        out.extend_from_slice(&[self.body_type, self.ccd, self.can_sleep, self._pad]);
        out.extend_from_slice(&self.mass.to_le_bytes());
        for value in self.center_of_mass.iter().chain(&self.inertia) {
            out.extend_from_slice(&value.to_le_bytes());
        }
        for value in [
            self.linear_damping,
            self.angular_damping,
            self.gravity_scale,
            self.max_linear_vel,
            self.max_angular_vel,
        ] {
            out.extend_from_slice(&value.to_le_bytes());
        }
        out.extend_from_slice(&[0u8; 4]); // réservé
    }

    /// Relit une description écrite par [`Self::write_le`], ou `None` si `bytes`
    /// est trop court. Les octets réservés ne sont pas lus ; la description
    /// n'est pas vérifiée ([`Self::validate`]).
    pub fn read_le(bytes: &[u8]) -> Option<Self> {
        let bytes = bytes.get(..Self::WIRE_BYTES)?;
        let f = |offset: usize| {
            f32::from_le_bytes([
                bytes[offset],
                bytes[offset + 1],
                bytes[offset + 2],
                bytes[offset + 3],
            ])
        };
        Some(Self {
            body_type: bytes[0],
            ccd: bytes[1],
            can_sleep: bytes[2],
            _pad: bytes[3],
            mass: f(4),
            center_of_mass: [f(8), f(12), f(16)],
            inertia: [f(20), f(24), f(28)],
            linear_damping: f(32),
            angular_damping: f(36),
            gravity_scale: f(40),
            max_linear_vel: f(44),
            max_angular_vel: f(48),
        })
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
    fn dm_physics_material_disposition_figee() {
        use core::mem::{align_of, offset_of, size_of};
        // DM-07, §3.7 (ADR-149) : 116 octets de champs, 120 avec l'alignement de `name_hash`.
        assert_eq!(size_of::<PhysicsMaterial>(), PhysicsMaterial::BYTES);
        assert_eq!(align_of::<PhysicsMaterial>(), 8);
        assert_eq!(offset_of!(PhysicsMaterial, name_hash), 0);
        assert_eq!(offset_of!(PhysicsMaterial, friction), 8);
        assert_eq!(offset_of!(PhysicsMaterial, restitution), 12);
        assert_eq!(offset_of!(PhysicsMaterial, rolling_friction), 16);
        assert_eq!(offset_of!(PhysicsMaterial, friction_combine), 20);
        assert_eq!(offset_of!(PhysicsMaterial, restitution_combine), 21);
        assert_eq!(offset_of!(PhysicsMaterial, _pad0), 22);
        assert_eq!(offset_of!(PhysicsMaterial, density), 24);
        assert_eq!(offset_of!(PhysicsMaterial, hardness), 28);
        assert_eq!(offset_of!(PhysicsMaterial, stiffness), 32);
        assert_eq!(offset_of!(PhysicsMaterial, elasticity), 36);
        assert_eq!(offset_of!(PhysicsMaterial, plasticity), 40);
        assert_eq!(offset_of!(PhysicsMaterial, yield_strength), 44);
        assert_eq!(offset_of!(PhysicsMaterial, fracture_strength), 48);
        assert_eq!(offset_of!(PhysicsMaterial, deformation_resistance), 52);
        assert_eq!(offset_of!(PhysicsMaterial, max_strain), 56);
        assert_eq!(offset_of!(PhysicsMaterial, damping), 60);
        assert_eq!(offset_of!(PhysicsMaterial, fracture_energy), 64);
        assert_eq!(offset_of!(PhysicsMaterial, hardness_pressure), 68);
        assert_eq!(offset_of!(PhysicsMaterial, indenter_radius_ref), 72);
        assert_eq!(offset_of!(PhysicsMaterial, spread_factor), 76);
        assert_eq!(offset_of!(PhysicsMaterial, thickness_spread), 80);
        assert_eq!(offset_of!(PhysicsMaterial, damage_multiplier), 84);
        assert_eq!(offset_of!(PhysicsMaterial, energy_absorption), 88);
        assert_eq!(offset_of!(PhysicsMaterial, scratch_resistance), 92);
        assert_eq!(offset_of!(PhysicsMaterial, burn_threshold), 96);
        assert_eq!(offset_of!(PhysicsMaterial, soil_rate), 100);
        assert_eq!(offset_of!(PhysicsMaterial, sound_group), 104);
        assert_eq!(offset_of!(PhysicsMaterial, particle_group), 108);
        assert_eq!(offset_of!(PhysicsMaterial, flags), 112);
    }

    /// Un matériau dont chaque champ a une valeur distincte.
    fn materiau_distinct() -> PhysicsMaterial {
        let mut next = 0.0f32;
        let mut v = || {
            next += 1.5;
            next
        };
        PhysicsMaterial {
            name_hash: 0x0102_0304_0506_0708,
            friction: v(),
            restitution: v(),
            rolling_friction: v(),
            friction_combine: combine::MULTIPLY,
            restitution_combine: combine::MAX,
            _pad0: [0, 0],
            density: v(),
            hardness: v(),
            stiffness: v(),
            elasticity: v(),
            plasticity: v(),
            yield_strength: v(),
            fracture_strength: v(),
            deformation_resistance: v(),
            max_strain: v(),
            damping: v(),
            fracture_energy: v(),
            hardness_pressure: v(),
            indenter_radius_ref: v(),
            spread_factor: v(),
            thickness_spread: v(),
            damage_multiplier: v(),
            energy_absorption: v(),
            scratch_resistance: v(),
            burn_threshold: v(),
            soil_rate: v(),
            sound_group: 0xA1A2_A3A4,
            particle_group: 0xB1B2_B3B4,
            flags: material_flags::BRITTLE | material_flags::NON_DEFORMABLE,
        }
    }

    #[test]
    fn physics_material_write_le_respecte_la_disposition() {
        let materiau = materiau_distinct();
        let mut out = Vec::new();
        materiau.write_le(&mut out);
        assert_eq!(out.len(), PhysicsMaterial::BYTES);
        // Chaque champ à son décalage, comme la mémoire `repr(C)` le place.
        let f = |offset: usize| f32::from_le_bytes(out[offset..offset + 4].try_into().unwrap());
        let u = |offset: usize| u32::from_le_bytes(out[offset..offset + 4].try_into().unwrap());
        assert_eq!(
            u64::from_le_bytes(out[..8].try_into().unwrap()),
            materiau.name_hash
        );
        assert_eq!(f(8), materiau.friction);
        assert_eq!(f(16), materiau.rolling_friction);
        assert_eq!((out[20], out[21]), (combine::MULTIPLY, combine::MAX));
        assert_eq!(f(24), materiau.density);
        assert_eq!(f(100), materiau.soil_rate);
        assert_eq!(u(104), materiau.sound_group);
        assert_eq!(u(112), materiau.flags);
        assert_eq!(out[116..], [0; 4], "alignement à zéro");
        assert_eq!(PhysicsMaterial::read_le(&out), Some(materiau));
        assert_eq!(PhysicsMaterial::read_le(&out[..119]), None, "trop court");
    }

    #[test]
    fn les_modes_de_combinaison_sont_ceux_du_solveur() {
        // ADR-149 D3 : valeurs de rapier, ordonnées du plus faible au plus fort.
        assert_eq!(
            [
                combine::AVERAGE,
                combine::MIN,
                combine::MULTIPLY,
                combine::MAX
            ],
            [0, 1, 2, 3]
        );
    }

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
            node: 11,
        };
        let mut bytes = Vec::new();
        state.write_le(&mut bytes);
        assert_eq!(bytes.len(), BodyState::BYTES);
        assert_eq!(u32::from_le_bytes(bytes[0..4].try_into().unwrap()), 3);
        assert_eq!(u32::from_le_bytes(bytes[4..8].try_into().unwrap()), 7);
        assert_eq!(f64::from_le_bytes(bytes[8..16].try_into().unwrap()), 1.0);
        assert_eq!(f32::from_le_bytes(bytes[32..36].try_into().unwrap()), 0.1);
        assert_eq!(u32::from_le_bytes(bytes[72..76].try_into().unwrap()), 0b101);
        assert_eq!(u32::from_le_bytes(bytes[76..80].try_into().unwrap()), 11);
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
        assert_eq!(offset_of!(BodyState, node), 76);
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

    #[test]
    fn dm08_body_desc_disposition_figee() {
        use core::mem::{align_of, offset_of, size_of};
        // §3.8, champ pour champ ; sur la frontière, 4 octets réservés de plus (ADR-130).
        assert_eq!(size_of::<BodyDesc>(), BodyDesc::BYTES);
        assert_eq!(size_of::<BodyDesc>(), 52);
        assert_eq!(align_of::<BodyDesc>(), 4);
        assert_eq!(offset_of!(BodyDesc, body_type), 0);
        assert_eq!(offset_of!(BodyDesc, ccd), 1);
        assert_eq!(offset_of!(BodyDesc, can_sleep), 2);
        assert_eq!(offset_of!(BodyDesc, _pad), 3);
        assert_eq!(offset_of!(BodyDesc, mass), 4);
        assert_eq!(offset_of!(BodyDesc, center_of_mass), 8);
        assert_eq!(offset_of!(BodyDesc, inertia), 20);
        assert_eq!(offset_of!(BodyDesc, linear_damping), 32);
        assert_eq!(offset_of!(BodyDesc, angular_damping), 36);
        assert_eq!(offset_of!(BodyDesc, gravity_scale), 40);
        assert_eq!(offset_of!(BodyDesc, max_linear_vel), 44);
        assert_eq!(offset_of!(BodyDesc, max_angular_vel), 48);
        assert_eq!(BodyDesc::WIRE_BYTES, 56);
        assert_eq!(BodyDesc::WIRE_BYTES % 8, 0);
    }

    #[test]
    fn dm08_body_desc_fait_l_aller_retour_sur_la_frontiere() {
        let desc = BodyDesc {
            body_type: 2,
            ccd: 1,
            can_sleep: 0,
            _pad: 0,
            mass: 1650.0,
            center_of_mass: [0.0, -0.25, 0.5],
            inertia: [10.0, 20.0, 30.0],
            linear_damping: 0.1,
            angular_damping: 0.2,
            gravity_scale: 0.5,
            max_linear_vel: 120.0,
            max_angular_vel: 40.0,
        };
        let mut out = Vec::new();
        desc.write_le(&mut out);
        assert_eq!(out.len(), BodyDesc::WIRE_BYTES);
        assert_eq!(&out[52..], &[0u8; 4]);
        assert_eq!(&out[4..8], &1650.0f32.to_le_bytes());
        assert_eq!(BodyDesc::read_le(&out), Some(desc));
        assert_eq!(BodyDesc::read_le(&out[..55]), None);
    }

    #[test]
    fn dm08_rien_de_declare_tout_vient_des_densites_et_des_defauts() {
        let desc = BodyDesc::undeclared(2);
        assert_eq!(desc.validate(), Ok(()));
        assert_eq!(desc.declared_mass(), None);
        assert_eq!(desc.declared_center_of_mass(), None);
        assert_eq!(desc.declared_inertia(), None);
        assert_eq!(desc.can_sleep, 1);
        assert_eq!(desc.gravity_scale, 1.0);
        // R-180.
        assert_eq!(desc.max_linear_vel, 300.0);
        assert_eq!(desc.max_angular_vel, 100.0);
        // Le NaN du centre « calculé » survit à l'aller-retour.
        let mut out = Vec::new();
        desc.write_le(&mut out);
        let relu = BodyDesc::read_le(&out).expect("relue");
        assert_eq!(relu.declared_center_of_mass(), None);
        assert_eq!(relu.validate(), Ok(()));
    }

    #[test]
    fn dm08_un_champ_hors_domaine_est_nomme() {
        let base = BodyDesc::undeclared(2);
        let cas: [(BodyDesc, &str); 10] = [
            (
                BodyDesc {
                    body_type: 3,
                    ..base
                },
                "body_type",
            ),
            (BodyDesc { ccd: 2, ..base }, "ccd"),
            (
                BodyDesc {
                    can_sleep: 2,
                    ..base
                },
                "can_sleep",
            ),
            (BodyDesc { _pad: 1, ..base }, "_pad"),
            (BodyDesc { mass: -1.0, ..base }, "mass"),
            (
                BodyDesc {
                    center_of_mass: [0.0, f32::NAN, 0.0],
                    ..base
                },
                "center_of_mass",
            ),
            (
                BodyDesc {
                    inertia: [1.0, 0.0, 1.0],
                    ..base
                },
                "inertia",
            ),
            (
                BodyDesc {
                    linear_damping: f32::INFINITY,
                    ..base
                },
                "linear_damping",
            ),
            (
                BodyDesc {
                    gravity_scale: f32::NAN,
                    ..base
                },
                "gravity_scale",
            ),
            (
                BodyDesc {
                    max_angular_vel: 0.0,
                    ..base
                },
                "max_angular_vel",
            ),
        ];
        for (desc, champ) in cas {
            assert_eq!(desc.validate(), Err(champ), "{champ}");
        }
    }
}
