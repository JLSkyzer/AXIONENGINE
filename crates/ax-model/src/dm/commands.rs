//! Protocole de commande de `SimIn` (IF-03, ADR-114).
//!
//! Java écrit dans `SimIn` un flux ordonné : un [`CommandStreamHeader`], puis
//! `command_count` commandes (chacune un [`SimCommandHeader`] suivi d'un payload
//! propre à l'opcode), puis les impacts. Les payloads sont `repr(C)`, lus en
//! place ; leur disposition est figée et testée. Un opcode inconnu est ignoré
//! (le lecteur saute son payload) — compatibilité avant.
//!
//! `CREATE_ASSEMBLY` (payload `AssemblyDesc`, DM-09) dépend de C-32 (colliders
//! depuis l'asset) et n'est pas encore transcrit ; son opcode est réservé.

use super::geometry::WorldTransform;
use super::handle::Handle;

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
    /// Version courante du protocole.
    pub const CURRENT_SCHEMA: u32 = 1;
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

/// En-tête de `CREATE_ASSEMBLY` (IF-03, ADR-114 ; colliders en ligne, ADR-115).
///
/// Suivi **dans le même payload de commande** des octets de la section `PHYS` de
/// l'asset : Java les extrait de l'A3D et les envoie, le natif les décode
/// (Option A). Les octets PHYS occupent `payload_len - CreateAssembly::BYTES`
/// octets après l'en-tête. Les champs runtime de `AssemblyDesc` (DM-09) — asset,
/// definition, propriétaire, qualité — s'ajouteront avec leur consommateur (cache
/// d'assets natif, suivi de propriété), pas avant.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct CreateAssembly {
    /// Handle d'assembly attribué par Java, pour le routage du corps.
    pub handle: Handle,
    /// Dimension d'accueil (R-610).
    pub dimension: u64,
    /// Pose monde de spawn.
    pub spawn: WorldTransform,
    /// Type de corps : `0` statique, `1` cinématique, `2` dynamique (miroir de
    /// `BodyKind`).
    pub body_kind: u8,
    /// Réservé, à zéro.
    pub _pad: [u8; 7],
}

impl CreateAssembly {
    /// Taille de l'en-tête sur la frontière, en octets (hors octets `PHYS`).
    pub const BYTES: usize = 64;
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
    /// Frottement du matériau dominant (R-643).
    pub friction: f32,
    /// Restitution du matériau dominant (R-643).
    pub restitution: f32,
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
    /// Frottement du matériau dominant (R-643).
    pub friction: f32,
    /// Restitution du matériau dominant (R-643).
    pub restitution: f32,
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
    }

    #[test]
    fn dm_world_tile_payloads_disposition_figee() {
        // ADR-117 : dispositions figées des opcodes de tuiles du monde (6–10).
        assert_eq!(size_of::<SetWorldCollision>(), SetWorldCollision::BYTES);
        assert_eq!(align_of::<SetWorldCollision>(), 8);
        assert_eq!(offset_of!(SetWorldCollision, dimension), 0);
        assert_eq!(offset_of!(SetWorldCollision, section), 8);
        assert_eq!(offset_of!(SetWorldCollision, box_count), 20);
        assert_eq!(offset_of!(SetWorldCollision, friction), 24);
        assert_eq!(offset_of!(SetWorldCollision, restitution), 28);

        assert_eq!(size_of::<SetWorldHeightfield>(), SetWorldHeightfield::BYTES);
        assert_eq!(align_of::<SetWorldHeightfield>(), 8);
        assert_eq!(offset_of!(SetWorldHeightfield, dimension), 0);
        assert_eq!(offset_of!(SetWorldHeightfield, section), 8);
        assert_eq!(offset_of!(SetWorldHeightfield, rows), 20);
        assert_eq!(offset_of!(SetWorldHeightfield, cols), 24);
        assert_eq!(offset_of!(SetWorldHeightfield, friction), 28);
        assert_eq!(offset_of!(SetWorldHeightfield, restitution), 32);
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
}
