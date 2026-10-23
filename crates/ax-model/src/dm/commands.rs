//! Protocole de commande de `SimIn` (IF-03, ADR-114).
//!
//! Java écrit dans `SimIn` un flux ordonné : un [`CommandStreamHeader`], puis
//! `command_count` commandes (chacune un [`SimCommandHeader`] suivi d'un payload
//! propre à l'opcode), puis les impacts. Les payloads sont `repr(C)`, lus en
//! place ; leur disposition est figée et testée. Un opcode inconnu est ignoré
//! (le lecteur saute son payload) — compatibilité avant.
//!
//! `CREATE_ASSEMBLY` porte l'en-tête [`CreateAssembly`], ses corps, puis les sections
//! `NODE` et `PHYS` de l'asset (ADR-115, ADR-130, ADR-151) ; les champs runtime de
//! `AssemblyDesc` (DM-09) viendront avec leur consommateur.

use super::geometry::WorldTransform;
use super::handle::Handle;
use super::joints::JointDesc;
use super::physics::BodyDesc;

/// Valeurs d'opcode (ADR-114), champ `opcode` de [`SimCommandHeader`].
pub mod opcode {
    /// Créer une assembly depuis un asset (payload `AssemblyDesc`, DM-09). C-32.
    pub const CREATE_ASSEMBLY: u32 = 0;
    /// Retirer une assembly (payload [`super::RemoveAssembly`]).
    pub const REMOVE_ASSEMBLY: u32 = 1;
    /// Imposer la pose d'un corps cinématique (payload [`super::SetKinematic`]).
    pub const SET_KINEMATIC: u32 = 2;
    /// Appliquer une force (payload [`super::ApplyForce`]).
    pub const APPLY_FORCE: u32 = 3;
    /// Appliquer une impulsion (même payload [`super::ApplyForce`]).
    pub const APPLY_IMPULSE: u32 = 4;
    /// Régler l'environnement d'une dimension (payload [`super::SetDimensionEnv`]).
    pub const SET_DIMENSION_ENV: u32 = 5;
    /// Poser une tuile de collision solide du monde (C-38, R-640 ; en-tête
    /// [`super::SetWorldCollision`] + `box_count × [f32;6]`). ADR-117.
    pub const SET_WORLD_COLLISION: u32 = 6;
    /// Poser une tuile de collision en champ de hauteurs (C-38, R-641 ; en-tête
    /// [`super::SetWorldHeightfield`] + `rows × cols × f32`). ADR-117.
    pub const SET_WORLD_HEIGHTFIELD: u32 = 7;
    /// Poser des volumes de fluide du monde (C-38, R-642 ; en-tête
    /// [`super::SetWorldFluid`] + `box_count × [f32;6]`). ADR-117.
    pub const SET_WORLD_FLUID: u32 = 8;
    /// Retirer la tuile de collision d'une section (payload [`super::RemoveWorldTile`]).
    /// ADR-117.
    pub const REMOVE_WORLD_COLLISION: u32 = 9;
    /// Retirer les volumes de fluide d'une section (payload [`super::RemoveWorldTile`]).
    /// ADR-117.
    pub const REMOVE_WORLD_FLUID: u32 = 10;
    /// Déclarer les observateurs d'une dimension pour le tick — les positions de ses
    /// joueurs (en-tête [`super::SetObservers`] + `count × [f64; 3]`). ADR-123 §2.
    pub const SET_OBSERVERS: u32 = 11;
    /// Déclarer les proxies des entités vanilla d'une dimension pour le tick (en-tête
    /// [`super::SetEntityProxies`] + `count ×` [`super::EntityProxyDesc`]). ADR-123 §5.
    pub const SET_ENTITY_PROXIES: u32 = 12;
    /// Remplacer la table des matériaux physiques de la session (en-tête
    /// [`super::SetPhysicsMaterials`] + `count ×` [`super::super::physics::PhysicsMaterial`]).
    /// ADR-149.
    pub const SET_PHYSICS_MATERIALS: u32 = 13;
}

/// Forme d'un proxy d'entité vanilla, champ `shape` de [`EntityProxyDesc`] (R-614).
pub mod entity_proxy_shape {
    /// Boîte : l'AABB de l'entité.
    pub const BOX: u8 = 0;
    /// Capsule verticale inscrite dans l'AABB : un être vivant.
    pub const CAPSULE: u8 = 1;
}

/// Drapeaux de [`SetDimensionEnv`], champ `flags`.
pub mod dimension_env_flags {
    /// Un fluide est présent : `fluid_surface`/`fluid_density` sont significatifs.
    pub const FLUID_PRESENT: u32 = 1 << 0;
}

/// En-tête du flux de commandes (ADR-114).
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CommandStreamHeader {
    /// Version du protocole ; incrémentée à tout changement de payload.
    pub schema_version: u32,
    /// Réservé, à zéro.
    pub _pad: u32,
}

impl CommandStreamHeader {
    /// Taille sur la frontière, en octets.
    pub const BYTES: usize = 8;
    /// Version courante du protocole : 3 depuis les assemblies à plusieurs corps (ADR-151
    /// D7), après l'index de matériau des tuiles (2, ADR-149).
    pub const CURRENT_SCHEMA: u32 = 3;
}

/// En-tête d'une commande : opcode et longueur de son payload (ADR-114).
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SimCommandHeader {
    /// Opcode, voir [`opcode`].
    pub opcode: u32,
    /// Nombre d'octets de payload qui suivent.
    pub payload_len: u32,
}

impl SimCommandHeader {
    /// Taille sur la frontière, en octets.
    pub const BYTES: usize = 8;
}

/// Payload de `REMOVE_ASSEMBLY`.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RemoveAssembly {
    /// Assembly à retirer.
    pub handle: Handle,
}

impl RemoveAssembly {
    /// Taille sur la frontière, en octets.
    pub const BYTES: usize = 8;
}

/// Payload de `SET_KINEMATIC`.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct SetKinematic {
    /// Corps cinématique visé.
    pub handle: Handle,
    /// Pose monde imposée pour le prochain pas.
    pub transform: WorldTransform,
}

impl SetKinematic {
    /// Taille sur la frontière, en octets.
    pub const BYTES: usize = 48;
}

/// Payload de `APPLY_FORCE` et `APPLY_IMPULSE`.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ApplyForce {
    /// Corps visé.
    pub handle: Handle,
    /// Vecteur force ou impulsion, en repère monde.
    pub vector: [f32; 3],
    /// Point d'application, en coordonnées locales du corps (si `at_point`).
    pub point: [f32; 3],
    /// Non nul : appliquer au `point` ; nul : force centrale.
    pub at_point: u32,
}

impl ApplyForce {
    /// Taille sur la frontière, en octets.
    pub const BYTES: usize = 36;
}

/// Payload de `SET_DIMENSION_ENV` (§10.6).
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct SetDimensionEnv {
    /// Dimension visée.
    pub dimension: u64,
    /// Gravité, en m/s².
    pub gravity: [f32; 3],
    /// Vent, en m/s.
    pub wind: [f32; 3],
    /// Altitude de la surface du fluide (si `FLUID_PRESENT`).
    pub fluid_surface: f32,
    /// Masse volumique du fluide (si `FLUID_PRESENT`).
    pub fluid_density: f32,
    /// Drapeaux, voir [`dimension_env_flags`].
    pub flags: u32,
}

impl SetDimensionEnv {
    /// Taille sur la frontière, en octets (remplissage compris).
    pub const BYTES: usize = 48;
}

/// En-tête de `CREATE_ASSEMBLY` (IF-03, ADR-114 ; colliders en ligne, ADR-115 ;
/// description des corps, ADR-130 ; plusieurs corps, ADR-151 D7).
///
/// Suivi **dans le même payload** de `body_count` entrées [`AssemblyBodyEntry`], le corps
/// racine d'abord ; puis de `joint_count` [`JointDesc`] et des `jam_points` points de leurs
/// courbes de grippage ([`JointDesc::JAM_POINT_BYTES`] chacun) ; puis des `node_bytes`
/// octets de la section `NODE` de l'asset, complétés à 8 ; puis des octets de sa section
/// `PHYS` jusqu'à la fin du payload. Java les extrait de l'A3D et les envoie, le natif les
/// décode (Option A d'ADR-115) : chaque collider va au corps de son node, posé dans le
/// repère de ce corps (ADR-151 D2). Sans section `NODE` (`node_bytes` nul), un seul corps
/// est admis, et ses colliders sont pris tels quels. [`create_assembly_payload`] assemble ce
/// payload. Les champs runtime de `AssemblyDesc` (DM-09) — asset, definition, propriétaire,
/// qualité — s'ajouteront avec leur consommateur.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct CreateAssembly {
    /// Handle d'assembly attribué par Java, pour le routage de ses corps.
    pub handle: Handle,
    /// Dimension d'accueil (R-610).
    pub dimension: u64,
    /// Pose monde de spawn : celle du repère de l'assembly, que suit le corps racine.
    pub spawn: WorldTransform,
    /// Type du corps racine : `0` statique, `1` cinématique, `2` dynamique (miroir de
    /// `BodyKind`). Les autres corps sont dynamiques.
    pub body_kind: u8,
    /// Drapeaux, voir [`create_assembly_flags`].
    pub flags: u8,
    /// Nombre de corps, de 1 à [`MAX_ASSEMBLY_BODIES`] (R-190).
    pub body_count: u16,
    /// Nombre de joints (C-34, ADR-151 D7).
    pub joint_count: u16,
    /// Nombre de points des courbes de grippage, en annexe après les joints.
    pub jam_points: u16,
    /// Masse de l'assembly, en kg, répartie entre ses corps sans masse déclarée ; `0` :
    /// aucune (ADR-151 D2).
    pub assembly_mass: f32,
    /// Taille de la section `NODE` qui suit les corps, en octets, sans son complément.
    pub node_bytes: u32,
}

/// Drapeaux de [`CreateAssembly`], champ `flags`.
pub mod create_assembly_flags {
    /// Les corps de l'assembly se touchent entre eux (`physics.self_collision`, §10.4).
    pub const SELF_COLLISION: u8 = 1 << 0;
}

/// Plafond de corps par assembly (R-190, `E-3050`).
pub const MAX_ASSEMBLY_BODIES: usize = 64;

impl CreateAssembly {
    /// Taille de l'en-tête sur la frontière, en octets.
    pub const BYTES: usize = 72;

    /// Décalage de la première entrée de corps dans le payload.
    pub const BODIES_OFFSET: usize = Self::BYTES;

    /// Écrit l'en-tête en little-endian, dans sa disposition figée, à la fin de `out`.
    pub fn write_le(&self, out: &mut Vec<u8>) {
        out.extend_from_slice(&self.handle.index.to_le_bytes());
        out.extend_from_slice(&self.handle.generation.to_le_bytes());
        out.extend_from_slice(&self.dimension.to_le_bytes());
        for value in self.spawn.position {
            out.extend_from_slice(&value.to_le_bytes());
        }
        for value in self.spawn.rotation {
            out.extend_from_slice(&value.to_le_bytes());
        }
        out.push(self.body_kind);
        out.push(self.flags);
        out.extend_from_slice(&self.body_count.to_le_bytes());
        out.extend_from_slice(&self.joint_count.to_le_bytes());
        out.extend_from_slice(&self.jam_points.to_le_bytes());
        out.extend_from_slice(&self.assembly_mass.to_le_bytes());
        out.extend_from_slice(&self.node_bytes.to_le_bytes());
    }

    /// Lit un en-tête au début de `bytes` ; `None` s'il est tronqué.
    #[must_use]
    pub fn read_le(bytes: &[u8]) -> Option<Self> {
        let bytes = bytes.get(..Self::BYTES)?;
        let u16_at = |at: usize| u16::from_le_bytes([bytes[at], bytes[at + 1]]);
        let u32_at = |at: usize| u32::from_le_bytes(bytes[at..at + 4].try_into().expect("borne"));
        let f32_at = |at: usize| f32::from_le_bytes(bytes[at..at + 4].try_into().expect("borne"));
        let f64_at = |at: usize| f64::from_le_bytes(bytes[at..at + 8].try_into().expect("borne"));
        Some(Self {
            handle: Handle::new(u32_at(0), u32_at(4)),
            dimension: u64::from_le_bytes(bytes[8..16].try_into().expect("borne")),
            spawn: WorldTransform {
                position: [f64_at(16), f64_at(24), f64_at(32)],
                rotation: [f32_at(40), f32_at(44), f32_at(48), f32_at(52)],
            },
            body_kind: bytes[56],
            flags: bytes[57],
            body_count: u16_at(58),
            joint_count: u16_at(60),
            jam_points: u16_at(62),
            assembly_mass: f32_at(64),
            node_bytes: u32_at(68),
        })
    }
}

/// Un corps de `CREATE_ASSEMBLY` (ADR-151 D2, D7) : sa description DM-08 et son node racine.
///
/// Sur la frontière : la description ([`BodyDesc::WIRE_BYTES`] octets), le node racine
/// (`u32`), puis 4 octets réservés, à zéro — 64 octets, alignés sur 8.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct AssemblyBodyEntry {
    /// Description DM-08 du corps (ADR-130).
    pub desc: BodyDesc,
    /// Node racine du corps dans l'asset. Ses descendants lui appartiennent, sauf ceux d'un
    /// autre corps plus proche ; le corps racine prend aussi les nodes qu'aucun ne réclame.
    pub root_node: u32,
}

impl AssemblyBodyEntry {
    /// Taille d'une entrée sur la frontière, en octets.
    pub const WIRE_BYTES: usize = 64;

    /// Écrit l'entrée en little-endian, réservé compris, à la fin de `out`.
    pub fn write_le(&self, out: &mut Vec<u8>) {
        self.desc.write_le(out);
        out.extend_from_slice(&self.root_node.to_le_bytes());
        out.extend_from_slice(&[0u8; 4]);
    }

    /// Lit une entrée au début de `bytes` ; `None` si elle est tronquée ou si sa description
    /// ne se lit pas.
    #[must_use]
    pub fn read_le(bytes: &[u8]) -> Option<Self> {
        let bytes = bytes.get(..Self::WIRE_BYTES)?;
        let desc = BodyDesc::read_le(bytes)?;
        let at = BodyDesc::WIRE_BYTES;
        let root_node = u32::from_le_bytes(bytes[at..at + 4].try_into().expect("borne"));
        Some(Self { desc, root_node })
    }
}

/// Le payload complet de `CREATE_ASSEMBLY` : l'en-tête — ses `body_count`, `joint_count`,
/// `jam_points` et `node_bytes` pris de `bodies`, `joints`, `jam_points` et `nodes` —, les
/// corps, les joints, les points de grippage, la section `NODE` complétée à 8, puis les
/// octets `PHYS`.
///
/// # Panics
///
/// Si `bodies`, `joints`, `jam_points` ou `nodes` dépassent ce que leur champ d'en-tête peut
/// dire.
#[must_use]
pub fn create_assembly_payload(
    header: &CreateAssembly,
    bodies: &[AssemblyBodyEntry],
    joints: &[JointDesc],
    jam_points: &[[f32; 2]],
    nodes: &[u8],
    phys: &[u8],
) -> Vec<u8> {
    let header = CreateAssembly {
        body_count: u16::try_from(bodies.len()).expect("au plus 65 535 corps"),
        joint_count: u16::try_from(joints.len()).expect("au plus 65 535 joints"),
        jam_points: u16::try_from(jam_points.len()).expect("au plus 65 535 points"),
        node_bytes: u32::try_from(nodes.len()).expect("section NODE sous 4 Gio"),
        ..*header
    };
    let mut out = Vec::new();
    header.write_le(&mut out);
    for body in bodies {
        body.write_le(&mut out);
    }
    for joint in joints {
        joint.write_le(&mut out);
    }
    for point in jam_points {
        out.extend_from_slice(&point[0].to_le_bytes());
        out.extend_from_slice(&point[1].to_le_bytes());
    }
    out.extend_from_slice(nodes);
    out.resize(out.len().next_multiple_of(8), 0);
    out.extend_from_slice(phys);
    out
}

/// En-tête de `SET_WORLD_COLLISION` (C-38, R-640 ; ADR-117).
///
/// Suivi **dans le même payload** de `box_count` boîtes `[f32; 6]`
/// (`[minx, miny, minz, maxx, maxy, maxz]`), **relatives à l'origine de la section**
/// (`section × 16`), en blocs quantifiés en 1/16. Toutes portent le matériau dominant
/// de la section (R-643). `box_count == 0` retire la tuile de collision.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct SetWorldCollision {
    /// Dimension visée (R-610).
    pub dimension: u64,
    /// Index de la section 16³ (`coord_bloc >> 4`).
    pub section: [i32; 3],
    /// Nombre de boîtes qui suivent.
    pub box_count: u32,
    /// Index du matériau dominant (R-643) dans la table de la session (ADR-149), ou
    /// [`super::physics::NO_MATERIAL`].
    pub material: u16,
    /// Réservé, à zéro.
    pub _pad: u16,
    /// Réservé, à zéro.
    pub _reserved: u32,
}

impl SetWorldCollision {
    /// Taille de l'en-tête sur la frontière, en octets (hors boîtes).
    pub const BYTES: usize = 32;
}

/// En-tête de `SET_WORLD_HEIGHTFIELD` (C-38, R-641 ; ADR-117).
///
/// Suivi **dans le même payload** de `rows × cols` hauteurs `f32`, en disposition
/// ligne-major (`row` sur z, `col` sur x). Le champ couvre la section, centré sur le
/// plan x-z ; la hauteur monde d'un sommet vaut `height × scale.y`.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct SetWorldHeightfield {
    /// Dimension visée (R-610).
    pub dimension: u64,
    /// Index de la section 16³.
    pub section: [i32; 3],
    /// Nombre de lignes (axe z), ≥ 2.
    pub rows: u32,
    /// Nombre de colonnes (axe x), ≥ 2.
    pub cols: u32,
    /// Index du matériau dominant (R-643) dans la table de la session (ADR-149), ou
    /// [`super::physics::NO_MATERIAL`].
    pub material: u16,
    /// Réservé, à zéro.
    pub _pad: u16,
    /// Réservé, à zéro.
    pub _reserved: u32,
    /// Échelle `[x, y, z]` : étendue en blocs sur x et z, facteur de hauteur sur y.
    pub scale: [f32; 3],
}

impl SetWorldHeightfield {
    /// Taille de l'en-tête sur la frontière, en octets (hors hauteurs).
    pub const BYTES: usize = 48;
}

/// En-tête de `SET_WORLD_FLUID` (C-38, R-642 ; ADR-117).
///
/// Suivi **dans le même payload** de `box_count` boîtes `[f32; 6]`, relatives à la
/// section, quantifiées 1/16. `density` est la masse volumique du fluide (eau douce
/// ≈ 1000). `box_count == 0` ou `density ≤ 0` retire les volumes de fluide.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct SetWorldFluid {
    /// Dimension visée (R-610).
    pub dimension: u64,
    /// Index de la section 16³.
    pub section: [i32; 3],
    /// Nombre de boîtes de fluide qui suivent.
    pub box_count: u32,
    /// Masse volumique du fluide, en kg/m³.
    pub density: f32,
    /// Réservé, à zéro.
    pub _pad: u32,
}

impl SetWorldFluid {
    /// Taille de l'en-tête sur la frontière, en octets (hors boîtes).
    pub const BYTES: usize = 32;
}

/// Payload de `REMOVE_WORLD_COLLISION` et `REMOVE_WORLD_FLUID` (C-38 ; ADR-117).
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RemoveWorldTile {
    /// Dimension visée (R-610).
    pub dimension: u64,
    /// Index de la section 16³ à retirer.
    pub section: [i32; 3],
    /// Réservé, à zéro.
    pub _pad: u32,
}

impl RemoveWorldTile {
    /// Taille sur la frontière, en octets.
    pub const BYTES: usize = 24;
}

/// En-tête de `SET_OBSERVERS` (ADR-123 §2).
///
/// Suivi **dans le même payload** de `count` positions monde `[f64; 3]`, en blocs : les
/// joueurs de la dimension, spectateurs exclus. État **par tick** : une dimension sans
/// `SET_OBSERVERS` dans le flux d'un tick n'a aucun observateur pour ce tick.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SetObservers {
    /// Dimension visée (R-610).
    pub dimension: u64,
    /// Nombre de positions qui suivent.
    pub count: u32,
    /// Réservé, à zéro.
    pub _pad: u32,
}

impl SetObservers {
    /// Taille de l'en-tête sur la frontière, en octets (hors positions).
    pub const BYTES: usize = 16;
    /// Taille d'une position qui suit l'en-tête, en octets.
    pub const POSITION_BYTES: usize = 24;
}

/// En-tête de `SET_ENTITY_PROXIES` (ADR-123 §5).
///
/// Suivi **dans le même payload** de `count` [`EntityProxyDesc`]. L'ensemble des proxies
/// d'une dimension est **remplacé à chaque tick** : une entité absente du flux perd son
/// proxy, une dimension sans `SET_ENTITY_PROXIES` n'en a plus aucun.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SetEntityProxies {
    /// Dimension visée (R-610).
    pub dimension: u64,
    /// Nombre de proxies qui suivent.
    pub count: u32,
    /// Réservé, à zéro.
    pub _pad: u32,
}

impl SetEntityProxies {
    /// Taille de l'en-tête sur la frontière, en octets (hors proxies).
    pub const BYTES: usize = 16;
}

/// En-tête de `SET_PHYSICS_MATERIALS` (ADR-149) : la table des matériaux de la session.
///
/// Suivi **dans le même payload** de `count` [`super::physics::PhysicsMaterial`] de
/// [`PhysicsMaterial::BYTES`](super::physics::PhysicsMaterial::BYTES) octets. L'index d'un
/// matériau est son rang ; Java le garde stable pour la session, et le natif résout de
/// nouveau les colliders et tuiles existants à chaque table reçue.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SetPhysicsMaterials {
    /// Nombre de matériaux qui suivent, au plus [`SetPhysicsMaterials::MAX_COUNT`].
    pub count: u32,
    /// Réservé, à zéro.
    pub _reserved: u32,
}

impl SetPhysicsMaterials {
    /// Taille de l'en-tête sur la frontière, en octets (hors matériaux).
    pub const BYTES: usize = 8;
    /// Matériaux au plus : un index tient dans un `u16`, et `0xFFFF` dit « aucun ».
    pub const MAX_COUNT: u32 = 0xFFFF;
}

/// Une entité vanilla vue par la physique pour un tick (R-614, ADR-123 §5) : le natif en
/// fait un corps cinématique temporaire, à la vitesse de l'entité.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct EntityProxyDesc {
    /// Centre de l'AABB de l'entité, monde, en blocs.
    pub center: [f64; 3],
    /// Demi-dimensions de l'AABB, en blocs.
    pub half_extents: [f32; 3],
    /// Vitesse de l'entité, en m/s.
    pub velocity: [f32; 3],
    /// Identifiant réseau de l'entité.
    pub entity: u32,
    /// Forme, voir [`entity_proxy_shape`].
    pub shape: u8,
    /// Réservé, à zéro.
    pub _pad: [u8; 3],
}

impl EntityProxyDesc {
    /// Taille sur la frontière, en octets.
    pub const BYTES: usize = 56;
}

#[cfg(test)]
mod tests {
    use super::*;
    use core::mem::{align_of, offset_of, size_of};

    #[test]
    fn dm_command_headers_disposition_figee() {
        assert_eq!(size_of::<CommandStreamHeader>(), CommandStreamHeader::BYTES);
        assert_eq!(size_of::<SimCommandHeader>(), SimCommandHeader::BYTES);
        assert_eq!(offset_of!(SimCommandHeader, opcode), 0);
        assert_eq!(offset_of!(SimCommandHeader, payload_len), 4);
    }

    #[test]
    fn dm_command_payloads_disposition_figee() {
        assert_eq!(size_of::<RemoveAssembly>(), RemoveAssembly::BYTES);

        assert_eq!(size_of::<SetKinematic>(), SetKinematic::BYTES);
        assert_eq!(align_of::<SetKinematic>(), 8);
        assert_eq!(offset_of!(SetKinematic, handle), 0);
        assert_eq!(offset_of!(SetKinematic, transform), 8);

        assert_eq!(size_of::<ApplyForce>(), ApplyForce::BYTES);
        assert_eq!(align_of::<ApplyForce>(), 4);
        assert_eq!(offset_of!(ApplyForce, handle), 0);
        assert_eq!(offset_of!(ApplyForce, vector), 8);
        assert_eq!(offset_of!(ApplyForce, point), 20);
        assert_eq!(offset_of!(ApplyForce, at_point), 32);

        assert_eq!(size_of::<SetDimensionEnv>(), SetDimensionEnv::BYTES);
        assert_eq!(align_of::<SetDimensionEnv>(), 8);
        assert_eq!(offset_of!(SetDimensionEnv, dimension), 0);
        assert_eq!(offset_of!(SetDimensionEnv, gravity), 8);
        assert_eq!(offset_of!(SetDimensionEnv, wind), 20);
        assert_eq!(offset_of!(SetDimensionEnv, fluid_surface), 32);
        assert_eq!(offset_of!(SetDimensionEnv, fluid_density), 36);
        assert_eq!(offset_of!(SetDimensionEnv, flags), 40);

        assert_eq!(size_of::<CreateAssembly>(), CreateAssembly::BYTES);
        assert_eq!(align_of::<CreateAssembly>(), 8);
        assert_eq!(offset_of!(CreateAssembly, handle), 0);
        assert_eq!(offset_of!(CreateAssembly, dimension), 8);
        assert_eq!(offset_of!(CreateAssembly, spawn), 16);
        assert_eq!(offset_of!(CreateAssembly, body_kind), 56);
        assert_eq!(offset_of!(CreateAssembly, flags), 57);
        assert_eq!(offset_of!(CreateAssembly, body_count), 58);
        assert_eq!(offset_of!(CreateAssembly, joint_count), 60);
        assert_eq!(offset_of!(CreateAssembly, jam_points), 62);
        assert_eq!(offset_of!(CreateAssembly, assembly_mass), 64);
        assert_eq!(offset_of!(CreateAssembly, node_bytes), 68);
        // ADR-151 D7 : les corps suivent l'en-tête, chacun sur 64 octets alignés sur 8.
        assert_eq!(CreateAssembly::BODIES_OFFSET % 8, 0);
        assert_eq!(AssemblyBodyEntry::WIRE_BYTES, BodyDesc::WIRE_BYTES + 8);
    }

    #[test]
    fn create_assembly_payload_aligne_ses_sections() {
        // ADR-151 D7 : en-tête, corps, joints, points de grippage, NODE complétée à 8, puis
        // PHYS ; l'en-tête relu porte les nombres de corps, de joints et de points, et la
        // taille de NODE, pris des sections.
        let header = CreateAssembly {
            handle: Handle::new(7, 1),
            dimension: 3,
            spawn: WorldTransform {
                position: [1.0, 2.0, 3.0],
                rotation: [0.0, 0.0, 0.0, 1.0],
            },
            body_kind: 2,
            flags: create_assembly_flags::SELF_COLLISION,
            body_count: 0,
            joint_count: 0,
            jam_points: 0,
            assembly_mass: 120.0,
            node_bytes: 0,
        };
        let bodies = [
            AssemblyBodyEntry {
                desc: BodyDesc::undeclared(2),
                root_node: 0,
            },
            AssemblyBodyEntry {
                desc: BodyDesc::undeclared(2),
                root_node: 4,
            },
        ];
        let joint = JointDesc {
            kind: super::super::joints::joint_kind::FIXED,
            flags: 0,
            body_a: 0,
            body_b: 1,
            axes: [0; 6],
            b_node: 4,
            anchor: [0.0; 3],
            axis: [0.0; 3],
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
        };
        let jam = [[0.0, 1.0], [0.1, 0.0]];
        let payload = create_assembly_payload(&header, &bodies, &[joint], &jam, &[9; 13], &[5; 3]);

        let read = CreateAssembly::read_le(&payload).unwrap();
        assert_eq!(read.body_count, 2);
        assert_eq!(read.joint_count, 1);
        assert_eq!(read.jam_points, 2);
        assert_eq!(read.node_bytes, 13);
        assert_eq!(read.assembly_mass, 120.0);
        assert_eq!(read.flags, create_assembly_flags::SELF_COLLISION);
        assert_eq!(read.spawn, header.spawn);
        let second = AssemblyBodyEntry::read_le(&payload[CreateAssembly::BODIES_OFFSET + 64..]);
        assert_eq!(second.unwrap().root_node, 4);
        let joints = CreateAssembly::BODIES_OFFSET + 2 * AssemblyBodyEntry::WIRE_BYTES;
        assert_eq!(JointDesc::read_le(&payload[joints..]), Some(joint));
        let points = joints + JointDesc::BYTES;
        assert_eq!(&payload[points + 4..points + 8], &1.0f32.to_le_bytes());
        assert_eq!(&payload[points + 8..points + 12], &0.1f32.to_le_bytes());
        let nodes = points + 2 * JointDesc::JAM_POINT_BYTES;
        assert_eq!(nodes % 8, 0, "NODE alignée sur 8");
        assert_eq!(&payload[nodes..nodes + 13], &[9; 13]);
        assert_eq!(
            &payload[nodes + 16..],
            &[5; 3],
            "PHYS aligné sur 8 après NODE"
        );
    }

    #[test]
    fn dm_world_tile_payloads_disposition_figee() {
        // ADR-117 : dispositions figées des opcodes de tuiles du monde (6–10).
        assert_eq!(size_of::<SetWorldCollision>(), SetWorldCollision::BYTES);
        assert_eq!(align_of::<SetWorldCollision>(), 8);
        assert_eq!(offset_of!(SetWorldCollision, dimension), 0);
        assert_eq!(offset_of!(SetWorldCollision, section), 8);
        assert_eq!(offset_of!(SetWorldCollision, box_count), 20);
        assert_eq!(offset_of!(SetWorldCollision, material), 24);
        assert_eq!(offset_of!(SetWorldCollision, _pad), 26);
        assert_eq!(offset_of!(SetWorldCollision, _reserved), 28);

        assert_eq!(size_of::<SetWorldHeightfield>(), SetWorldHeightfield::BYTES);
        assert_eq!(align_of::<SetWorldHeightfield>(), 8);
        assert_eq!(offset_of!(SetWorldHeightfield, dimension), 0);
        assert_eq!(offset_of!(SetWorldHeightfield, section), 8);
        assert_eq!(offset_of!(SetWorldHeightfield, rows), 20);
        assert_eq!(offset_of!(SetWorldHeightfield, cols), 24);
        assert_eq!(offset_of!(SetWorldHeightfield, material), 28);
        assert_eq!(offset_of!(SetWorldHeightfield, _pad), 30);
        assert_eq!(offset_of!(SetWorldHeightfield, _reserved), 32);
        assert_eq!(offset_of!(SetWorldHeightfield, scale), 36);

        assert_eq!(size_of::<SetWorldFluid>(), SetWorldFluid::BYTES);
        assert_eq!(align_of::<SetWorldFluid>(), 8);
        assert_eq!(offset_of!(SetWorldFluid, dimension), 0);
        assert_eq!(offset_of!(SetWorldFluid, section), 8);
        assert_eq!(offset_of!(SetWorldFluid, box_count), 20);
        assert_eq!(offset_of!(SetWorldFluid, density), 24);

        assert_eq!(size_of::<RemoveWorldTile>(), RemoveWorldTile::BYTES);
        assert_eq!(align_of::<RemoveWorldTile>(), 8);
        assert_eq!(offset_of!(RemoveWorldTile, dimension), 0);
        assert_eq!(offset_of!(RemoveWorldTile, section), 8);
    }

    #[test]
    fn dm_table_des_materiaux_disposition_figee() {
        // ADR-149 : opcode 13, en-tête de 8 octets ; le flux est au schéma 3 depuis ADR-151.
        assert_eq!(opcode::SET_PHYSICS_MATERIALS, 13);
        assert_eq!(CommandStreamHeader::CURRENT_SCHEMA, 3);
        assert_eq!(size_of::<SetPhysicsMaterials>(), SetPhysicsMaterials::BYTES);
        assert_eq!(align_of::<SetPhysicsMaterials>(), 4);
        assert_eq!(offset_of!(SetPhysicsMaterials, count), 0);
        assert_eq!(offset_of!(SetPhysicsMaterials, _reserved), 4);
        assert_eq!(SetPhysicsMaterials::MAX_COUNT, u32::from(u16::MAX));
    }

    #[test]
    fn dm_observateurs_et_proxies_disposition_figee() {
        // ADR-123 §2 et §5 : dispositions ratifiées des opcodes 11 et 12.
        assert_eq!(opcode::SET_OBSERVERS, 11);
        assert_eq!(opcode::SET_ENTITY_PROXIES, 12);

        assert_eq!(size_of::<SetObservers>(), SetObservers::BYTES);
        assert_eq!(align_of::<SetObservers>(), 8);
        assert_eq!(offset_of!(SetObservers, dimension), 0);
        assert_eq!(offset_of!(SetObservers, count), 8);
        assert_eq!(size_of::<[f64; 3]>(), SetObservers::POSITION_BYTES);

        assert_eq!(size_of::<SetEntityProxies>(), SetEntityProxies::BYTES);
        assert_eq!(align_of::<SetEntityProxies>(), 8);
        assert_eq!(offset_of!(SetEntityProxies, dimension), 0);
        assert_eq!(offset_of!(SetEntityProxies, count), 8);

        assert_eq!(size_of::<EntityProxyDesc>(), EntityProxyDesc::BYTES);
        assert_eq!(align_of::<EntityProxyDesc>(), 8);
        assert_eq!(offset_of!(EntityProxyDesc, center), 0);
        assert_eq!(offset_of!(EntityProxyDesc, half_extents), 24);
        assert_eq!(offset_of!(EntityProxyDesc, velocity), 36);
        assert_eq!(offset_of!(EntityProxyDesc, entity), 48);
        assert_eq!(offset_of!(EntityProxyDesc, shape), 52);
        assert_eq!(offset_of!(EntityProxyDesc, _pad), 53);
    }
}
