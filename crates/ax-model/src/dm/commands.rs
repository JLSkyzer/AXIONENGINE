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
}
