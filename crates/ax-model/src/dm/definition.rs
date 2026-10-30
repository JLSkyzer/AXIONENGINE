//! `DEFN` : une definition validée, telle que Java la remet au natif pour qu'il la compile
//! (étape 4 de C-27, ADR-156 D2).
//!
//! Java connaît le JSON et les registres de données ; le natif connaît l'asset. `DEFN` porte donc
//! ce que Java a déjà tranché — descriptions DM-08 des corps, réglages des joints, matériau de
//! chaque part, groupes de collision résolus en bits — et laisse au natif tout ce qui désigne
//! l'asset : chaque nom de node, de clip ou d'os y est une [`DefinitionRef`], l'empreinte du nom
//! (`name_hash` de DM-03) et sa catégorie. Le texte des noms ne passe pas : un refus rend le rang
//! de la référence fautive, et Java en tire le chemin JSON et le nom de son message.
//!
//! Les noms de mesh, de collider et de région ne s'y trouvent pas. Ni `MeshDesc` (DM-04) ni
//! `ColliderDesc` (DM-06) ne portent de nom dans l'A3D, et une région peut être générée par C-28
//! (M6) : leur résolution vient avec leurs systèmes (ADR-156, tranche 1).
//!
//! # Disposition
//!
//! Little-endian, sans remplissage entre les blocs :
//!
//! ```text
//! DefinitionHeader                         144 o
//! DefinitionRef[ref_count]                  16 o chacune
//! AssemblyBodyEntry[body_count]             64 o — root_node : rang d'une référence NODE,
//!                                                  NONE_U32 pour la racine de l'asset (corps 0)
//! JointDesc[joint_count]                   160 o — b_node : rang d'une référence NODE ;
//!                                                  body_b : NONE_U16, tranché par le natif
//! [f32; 2][jam_points]                       8 o chacun
//! DefinitionPart[part_count]                 8 o chacune
//! ```

use super::commands::{AssemblyBodyEntry, MAX_ASSEMBLY_BODIES};
use super::joints::JointDesc;
use super::physics::BodyDesc;
use super::scene::{NONE_U16, NONE_U32};

/// Signature d'un `DEFN`.
pub const DEFN_MAGIC: [u8; 4] = *b"DEFN";

/// Version du format.
pub const DEFN_VERSION: u16 = 1;

/// Drapeaux de [`DefinitionHeader`], champ `flags`.
pub mod definition_flags {
    /// Les corps de l'assembly se touchent entre eux (`physics.self_collision`, §10.4).
    pub const SELF_COLLISION: u16 = 1 << 0;
    /// Tous les drapeaux connus.
    pub const ALL: u16 = SELF_COLLISION;
}

/// Catégorie d'une [`DefinitionRef`] : la table de l'asset où son nom se résout.
pub mod reference_category {
    /// Un node (DM-03), par `NodeDesc::name_hash`.
    pub const NODE: u8 = 0;
    /// Un clip d'animation (DM-10), par `AnimationDesc::name_hash`.
    pub const CLIP: u8 = 1;
    /// Un os du squelette (DM-10), par `BoneDesc::name_hash`.
    pub const BONE: u8 = 2;
    /// La dernière catégorie connue.
    pub const LAST: u8 = BONE;
}

/// Drapeaux de [`DefinitionPart`], champ `flags`.
pub mod part_flags {
    /// La part porte au moins une région de déformation : ses colliders sont `REFITTABLE` sur un
    /// corps dynamique (R-623).
    pub const DEFORMABLE: u16 = 1 << 0;
    /// Tous les drapeaux connus.
    pub const ALL: u16 = DEFORMABLE;
}

/// En-tête d'un `DEFN`.
///
/// **Disposition figée** : signature (4 o), version `u16`, drapeaux `u16`, appartenance `u32`,
/// filtre `u32`, masse `f32`, `ref_count` `u32`, `body_count`, `joint_count`, `jam_points` et
/// `part_count` en `u16` — 32 octets —, puis les deux [`BodyDesc`] de 56 octets.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct DefinitionHeader {
    /// Drapeaux, voir [`definition_flags`].
    pub flags: u16,
    /// Groupes dont l'assembly est membre, en masque (R-980, `physics.collision_group`).
    pub group: u32,
    /// Groupes qu'elle heurte (`physics.collides_with`).
    pub mask: u32,
    /// Masse de l'assembly à répartir entre ses corps quand elle en a plusieurs, en kg
    /// (`physics.mass`, ADR-151 D2) ; `0` sans masse déclarée.
    pub assembly_mass: f32,
    /// Nombre de références à l'asset.
    pub ref_count: u32,
    /// Nombre de corps déclarés (`bodies[]`) ; `0` : un seul corps, à la racine de l'asset.
    pub body_count: u16,
    /// Nombre de joints ; `0` si `modules.joints` est désactivé.
    pub joint_count: u16,
    /// Nombre de points des courbes de grippage.
    pub jam_points: u16,
    /// Nombre de parts.
    pub part_count: u16,
    /// Description de l'assembly entière, quand elle n'a qu'un corps : le bloc `physics`.
    pub physics: BodyDesc,
    /// Description d'un corps que le `b_node` d'un joint crée sans qu'il soit déclaré : dynamique,
    /// sans masse propre.
    pub implicit: BodyDesc,
}

impl DefinitionHeader {
    /// Taille de l'en-tête, en octets.
    pub const BYTES: usize = 32 + 2 * BodyDesc::WIRE_BYTES;

    /// Écrit l'en-tête en little-endian à la fin de `out`.
    pub fn write_le(&self, out: &mut Vec<u8>) {
        out.extend_from_slice(&DEFN_MAGIC);
        out.extend_from_slice(&DEFN_VERSION.to_le_bytes());
        out.extend_from_slice(&self.flags.to_le_bytes());
        out.extend_from_slice(&self.group.to_le_bytes());
        out.extend_from_slice(&self.mask.to_le_bytes());
        out.extend_from_slice(&self.assembly_mass.to_le_bytes());
        out.extend_from_slice(&self.ref_count.to_le_bytes());
        for count in [
            self.body_count,
            self.joint_count,
            self.jam_points,
            self.part_count,
        ] {
            out.extend_from_slice(&count.to_le_bytes());
        }
        self.physics.write_le(out);
        self.implicit.write_le(out);
    }

    fn read_le(bytes: &[u8]) -> Result<Self, DefnError> {
        let bytes = bytes.get(..Self::BYTES).ok_or(DefnError::Truncated)?;
        if bytes[..4] != DEFN_MAGIC {
            return Err(DefnError::Magic);
        }
        let u16_at = |at: usize| u16::from_le_bytes([bytes[at], bytes[at + 1]]);
        let u32_at = |at: usize| u32::from_le_bytes(bytes[at..at + 4].try_into().expect("borne"));
        if u16_at(4) != DEFN_VERSION {
            return Err(DefnError::Version(u16_at(4)));
        }
        let physics = BodyDesc::read_le(&bytes[32..]).ok_or(DefnError::Truncated)?;
        let implicit =
            BodyDesc::read_le(&bytes[32 + BodyDesc::WIRE_BYTES..]).ok_or(DefnError::Truncated)?;
        Ok(Self {
            flags: u16_at(6),
            group: u32_at(8),
            mask: u32_at(12),
            assembly_mass: f32::from_bits(u32_at(16)),
            ref_count: u32_at(20),
            body_count: u16_at(24),
            joint_count: u16_at(26),
            jam_points: u16_at(28),
            part_count: u16_at(30),
            physics,
            implicit,
        })
    }
}

/// Un nom de la definition qui désigne l'asset.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DefinitionRef {
    /// Empreinte FNV-1a 64 du nom (`name_hash` de DM-03).
    pub name_hash: u64,
    /// Catégorie, voir [`reference_category`].
    pub category: u8,
}

impl DefinitionRef {
    /// Taille d'une référence, en octets : l'empreinte, la catégorie, 7 octets réservés à zéro.
    pub const BYTES: usize = 16;

    /// Écrit la référence en little-endian à la fin de `out`.
    pub fn write_le(&self, out: &mut Vec<u8>) {
        out.extend_from_slice(&self.name_hash.to_le_bytes());
        out.push(self.category);
        out.extend_from_slice(&[0u8; 7]);
    }

    fn read_le(bytes: &[u8]) -> Option<Self> {
        let bytes = bytes.get(..Self::BYTES)?;
        Some(Self {
            name_hash: u64::from_le_bytes(bytes[..8].try_into().expect("borne")),
            category: bytes[8],
        })
    }
}

/// Une part : son node racine, son matériau, sa déformabilité (ADR-149 D2, R-623).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DefinitionPart {
    /// Rang de la référence `NODE` de son `root_node`.
    pub root: u32,
    /// Index de son matériau dans la table de la session, [`NONE_U16`] si elle n'en nomme pas.
    pub material: u16,
    /// Drapeaux, voir [`part_flags`].
    pub flags: u16,
}

impl DefinitionPart {
    /// Taille d'une part, en octets.
    pub const BYTES: usize = 8;

    /// Écrit la part en little-endian à la fin de `out`.
    pub fn write_le(&self, out: &mut Vec<u8>) {
        out.extend_from_slice(&self.root.to_le_bytes());
        out.extend_from_slice(&self.material.to_le_bytes());
        out.extend_from_slice(&self.flags.to_le_bytes());
    }

    fn read_le(bytes: &[u8]) -> Option<Self> {
        let bytes = bytes.get(..Self::BYTES)?;
        Some(Self {
            root: u32::from_le_bytes(bytes[..4].try_into().expect("borne")),
            material: u16::from_le_bytes([bytes[4], bytes[5]]),
            flags: u16::from_le_bytes([bytes[6], bytes[7]]),
        })
    }
}

/// Ce qui empêche de lire un `DEFN`. C'est un défaut de son auteur, Java, jamais d'une definition :
/// Java ne remet que des definitions acceptées aux étapes 1 à 3.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DefnError {
    /// Les octets s'arrêtent avant la fin annoncée.
    Truncated,
    /// Des octets suivent la fin annoncée.
    Trailing,
    /// La signature n'est pas `DEFN`.
    Magic,
    /// Une version que ce code ne lit pas.
    Version(u16),
    /// Un champ hors de son domaine : son nom.
    Field(&'static str),
}

/// Un `DEFN` lu et vérifié dans sa forme.
#[derive(Debug, Clone, PartialEq)]
pub struct Definition {
    /// L'en-tête ; ses dénombrements sont ceux des tables qui suivent.
    pub header: DefinitionHeader,
    /// Les références à l'asset.
    pub refs: Vec<DefinitionRef>,
    /// Les corps déclarés, le corps racine d'abord.
    pub bodies: Vec<AssemblyBodyEntry>,
    /// Les joints, dans l'ordre de la definition.
    pub joints: Vec<JointDesc>,
    /// Les points des courbes de grippage.
    pub jam_points: Vec<[f32; 2]>,
    /// Les parts.
    pub parts: Vec<DefinitionPart>,
}

impl Definition {
    /// Écrit le `DEFN` à la fin de `out` ; les dénombrements de l'en-tête sont pris des tables.
    ///
    /// # Panics
    ///
    /// Si une table dépasse ce que son champ d'en-tête peut dire.
    pub fn write_le(&self, out: &mut Vec<u8>) {
        let count = |len: usize| u16::try_from(len).expect("au plus 65 535 entrées");
        DefinitionHeader {
            ref_count: u32::try_from(self.refs.len()).expect("au plus 2^32 références"),
            body_count: count(self.bodies.len()),
            joint_count: count(self.joints.len()),
            jam_points: count(self.jam_points.len()),
            part_count: count(self.parts.len()),
            ..self.header
        }
        .write_le(out);
        for reference in &self.refs {
            reference.write_le(out);
        }
        for body in &self.bodies {
            body.write_le(out);
        }
        for joint in &self.joints {
            joint.write_le(out);
        }
        for [x, y] in &self.jam_points {
            out.extend_from_slice(&x.to_le_bytes());
            out.extend_from_slice(&y.to_le_bytes());
        }
        for part in &self.parts {
            part.write_le(out);
        }
    }

    /// Lit un `DEFN` qui occupe exactement `bytes`, et vérifie sa forme : descriptions valides,
    /// catégories connues, chaque rang de référence dans sa table et de la catégorie `NODE` là où
    /// un node est attendu, au plus [`MAX_ASSEMBLY_BODIES`] corps déclarés, seul le corps racine à
    /// la racine de l'asset. Ce qui dépend de l'asset — un nom introuvable, les corps qu'ajoutent
    /// les joints — se juge à la compilation.
    ///
    /// # Errors
    ///
    /// [`DefnError`], au premier écart.
    pub fn read_le(bytes: &[u8]) -> Result<Self, DefnError> {
        let header = DefinitionHeader::read_le(bytes)?;
        if header.flags & !definition_flags::ALL != 0 {
            return Err(DefnError::Field("flags"));
        }
        let validate =
            |desc: &BodyDesc, field| desc.validate().map_err(|_| DefnError::Field(field));
        validate(&header.physics, "physics")?;
        validate(&header.implicit, "implicit")?;
        if header.implicit.body_type != 2 {
            return Err(DefnError::Field("implicit"));
        }
        if !(header.assembly_mass.is_finite() && header.assembly_mass >= 0.0) {
            return Err(DefnError::Field("assembly_mass"));
        }
        if usize::from(header.body_count) > MAX_ASSEMBLY_BODIES {
            return Err(DefnError::Field("body_count"));
        }

        let mut at = DefinitionHeader::BYTES;
        let mut take = |len: usize| -> Result<&[u8], DefnError> {
            let end = at.checked_add(len).ok_or(DefnError::Truncated)?;
            let slice = bytes.get(at..end).ok_or(DefnError::Truncated)?;
            at = end;
            Ok(slice)
        };

        let ref_count = usize::try_from(header.ref_count).map_err(|_| DefnError::Truncated)?;
        let mut refs = Vec::with_capacity(ref_count.min(bytes.len() / DefinitionRef::BYTES));
        for _ in 0..ref_count {
            let reference =
                DefinitionRef::read_le(take(DefinitionRef::BYTES)?).ok_or(DefnError::Truncated)?;
            if reference.category > reference_category::LAST {
                return Err(DefnError::Field("category"));
            }
            refs.push(reference);
        }
        let node_ref = |index: u32| {
            refs.get(index as usize)
                .is_some_and(|reference| reference.category == reference_category::NODE)
        };

        let mut bodies = Vec::with_capacity(usize::from(header.body_count));
        for rank in 0..usize::from(header.body_count) {
            let entry = AssemblyBodyEntry::read_le(take(AssemblyBodyEntry::WIRE_BYTES)?)
                .ok_or(DefnError::Truncated)?;
            validate(&entry.desc, "bodies.desc")?;
            // Le corps racine a le type de l'assembly ; les autres sont dynamiques.
            let body_type = if rank == 0 {
                header.physics.body_type
            } else {
                2
            };
            if entry.desc.body_type != body_type {
                return Err(DefnError::Field("bodies.desc"));
            }
            let at_asset_root = rank == 0 && entry.root_node == NONE_U32;
            if !(at_asset_root || node_ref(entry.root_node)) {
                return Err(DefnError::Field("bodies.root_node"));
            }
            bodies.push(entry);
        }

        let mut joints = Vec::with_capacity(usize::from(header.joint_count));
        for _ in 0..header.joint_count {
            let joint = JointDesc::read_le(take(JointDesc::BYTES)?).ok_or(DefnError::Truncated)?;
            joint.validate().map_err(DefnError::Field)?;
            if !node_ref(joint.b_node) {
                return Err(DefnError::Field("joints.b_node"));
            }
            if usize::from(joint.body_a) >= bodies.len() || joint.body_b != NONE_U16 {
                return Err(DefnError::Field("joints.body"));
            }
            joints.push(joint);
        }

        let mut jam_points = Vec::with_capacity(usize::from(header.jam_points));
        for _ in 0..header.jam_points {
            let point = take(JointDesc::JAM_POINT_BYTES)?;
            let f32_at =
                |at: usize| f32::from_le_bytes(point[at..at + 4].try_into().expect("borne"));
            jam_points.push([f32_at(0), f32_at(4)]);
        }

        let mut parts = Vec::with_capacity(usize::from(header.part_count));
        for _ in 0..header.part_count {
            let part = DefinitionPart::read_le(take(DefinitionPart::BYTES)?)
                .ok_or(DefnError::Truncated)?;
            if !node_ref(part.root) {
                return Err(DefnError::Field("parts.root_node"));
            }
            if part.flags & !part_flags::ALL != 0 {
                return Err(DefnError::Field("parts.flags"));
            }
            parts.push(part);
        }

        if at != bytes.len() {
            return Err(DefnError::Trailing);
        }
        Ok(Self {
            header,
            refs,
            bodies,
            joints,
            jam_points,
            parts,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dm::joints::{joint_axis_mode, joint_kind};

    fn charniere(b_node: u32) -> JointDesc {
        JointDesc {
            kind: joint_kind::REVOLUTE,
            flags: 0,
            body_a: 0,
            body_b: NONE_U16,
            axes: [joint_axis_mode::LOCKED; 6],
            b_node,
            anchor: [0.0; 3],
            axis: [1.0, 0.0, 0.0],
            limits: [0.0; 2],
            motor_speed: 0.0,
            motor_target: 0.0,
            motor_stiffness: 0.0,
            motor_damping: 0.0,
            motor_max_force: f32::INFINITY,
            friction: 0.0,
            break_force: f32::INFINITY,
            break_torque: f32::INFINITY,
            break_energy: f32::INFINITY,
            jam_friction: 0.0,
            jam_first: 0,
            jam_count: 0,
            axis_limits: [[0.0; 2]; 6],
            max_length: 0.0,
            rest_length: 0.0,
            stiffness: 0.0,
            damping: 0.0,
        }
    }

    fn porte() -> Definition {
        Definition {
            header: DefinitionHeader {
                flags: definition_flags::SELF_COLLISION,
                group: 0b10,
                mask: u32::MAX,
                assembly_mass: 40.0,
                ref_count: 0,
                body_count: 0,
                joint_count: 0,
                jam_points: 0,
                part_count: 0,
                physics: BodyDesc::undeclared(2),
                implicit: BodyDesc::undeclared(2),
            },
            refs: vec![
                DefinitionRef {
                    name_hash: 11,
                    category: reference_category::NODE,
                },
                DefinitionRef {
                    name_hash: 22,
                    category: reference_category::CLIP,
                },
            ],
            bodies: vec![
                AssemblyBodyEntry {
                    desc: BodyDesc::undeclared(2),
                    root_node: NONE_U32,
                },
                AssemblyBodyEntry {
                    desc: BodyDesc::undeclared(2),
                    root_node: 0,
                },
            ],
            joints: vec![charniere(0)],
            jam_points: vec![[0.1, 0.5]],
            parts: vec![DefinitionPart {
                root: 0,
                material: 3,
                flags: part_flags::DEFORMABLE,
            }],
        }
    }

    fn octets(definition: &Definition) -> Vec<u8> {
        let mut out = Vec::new();
        definition.write_le(&mut out);
        out
    }

    #[test]
    fn la_disposition_est_figee() {
        assert_eq!(DefinitionHeader::BYTES, 144);
        assert_eq!(DefinitionRef::BYTES, 16);
        assert_eq!(DefinitionPart::BYTES, 8);
        let bytes = octets(&porte());
        assert_eq!(&bytes[..4], b"DEFN");
        assert_eq!(
            bytes.len(),
            144 + 2 * 16 + 2 * 64 + JointDesc::BYTES + 8 + 8,
            "en-tête, références, corps, joint, point, part, sans remplissage"
        );
    }

    #[test]
    fn un_defn_ecrit_se_relit_a_l_identique() {
        let definition = porte();
        let relu = Definition::read_le(&octets(&definition)).expect("lisible");
        // Un centre de masse non déclaré vaut NaN, que `==` ne reconnaît pas : l'aller-retour se
        // juge sur les octets.
        assert_eq!(octets(&relu), octets(&definition));
        assert_eq!(relu.refs, definition.refs);
        assert_eq!(relu.joints, definition.joints);
        assert_eq!(relu.jam_points, definition.jam_points);
        assert_eq!(relu.parts, definition.parts);
        assert_eq!(relu.header.group, 0b10);
        assert_eq!(relu.header.body_count, 2);
        assert_eq!(relu.header.flags, definition_flags::SELF_COLLISION);
    }

    #[test]
    fn un_defn_tronque_ou_prolonge_est_refuse() {
        let bytes = octets(&porte());
        assert_eq!(
            Definition::read_le(&bytes[..bytes.len() - 1]),
            Err(DefnError::Truncated)
        );
        let mut long = bytes.clone();
        long.push(0);
        assert_eq!(Definition::read_le(&long), Err(DefnError::Trailing));
        let mut magic = bytes.clone();
        magic[0] = b'X';
        assert_eq!(Definition::read_le(&magic), Err(DefnError::Magic));
        let mut version = bytes;
        version[4] = 2;
        assert_eq!(Definition::read_le(&version), Err(DefnError::Version(2)));
    }

    #[test]
    fn un_rang_de_reference_hors_table_ou_d_une_autre_categorie_est_refuse() {
        let mut corps = porte();
        corps.bodies[1].root_node = 2;
        assert_eq!(
            Definition::read_le(&octets(&corps)),
            Err(DefnError::Field("bodies.root_node"))
        );
        let mut clip = porte();
        clip.joints[0].b_node = 1;
        assert_eq!(
            Definition::read_le(&octets(&clip)),
            Err(DefnError::Field("joints.b_node"))
        );
        let mut part = porte();
        part.parts[0].root = 1;
        assert_eq!(
            Definition::read_le(&octets(&part)),
            Err(DefnError::Field("parts.root_node"))
        );
        let mut categorie = porte();
        categorie.refs[1].category = reference_category::LAST + 1;
        assert_eq!(
            Definition::read_le(&octets(&categorie)),
            Err(DefnError::Field("category"))
        );
    }

    #[test]
    fn seul_le_corps_racine_peut_se_poser_a_la_racine_de_l_asset() {
        let mut second = porte();
        second.bodies[1].root_node = NONE_U32;
        assert_eq!(
            Definition::read_le(&octets(&second)),
            Err(DefnError::Field("bodies.root_node"))
        );
    }

    #[test]
    fn un_corps_secondaire_non_dynamique_ou_un_joint_hors_des_corps_est_refuse() {
        let mut statique = porte();
        statique.bodies[1].desc = BodyDesc::undeclared(0);
        assert_eq!(
            Definition::read_le(&octets(&statique)),
            Err(DefnError::Field("bodies.desc"))
        );
        let mut joint = porte();
        joint.joints[0].body_a = 2;
        assert_eq!(
            Definition::read_le(&octets(&joint)),
            Err(DefnError::Field("joints.body"))
        );
        let mut tranche = porte();
        tranche.joints[0].body_b = 1;
        assert_eq!(
            Definition::read_le(&octets(&tranche)),
            Err(DefnError::Field("joints.body")),
            "body_b se tranche à la compilation, pas dans DEFN"
        );
    }

    #[test]
    fn des_drapeaux_inconnus_sont_refuses() {
        let mut entete = porte();
        entete.header.flags = 1 << 7;
        assert_eq!(
            Definition::read_le(&octets(&entete)),
            Err(DefnError::Field("flags"))
        );
        let mut part = porte();
        part.parts[0].flags = 1 << 5;
        assert_eq!(
            Definition::read_le(&octets(&part)),
            Err(DefnError::Field("parts.flags"))
        );
    }
}
