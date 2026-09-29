//! C-31 : monde physique d'AXION ENGINE.
//!
//! Voir la fiche 5.23 et la PARTIE 10 du cahier des charges. Le moteur est
//! `rapier3d` (ADR-002) ; ses types ne fuient jamais hors de ce crate
//! (R-1753), qui les convertit vers les types AXION à sa frontière.
//!
//! # Déterminisme
//!
//! La physique n'est pas bit-exacte entre machines (ADR-005) : elle est
//! **reproductible sur une même machine et un même binaire** (R-1020). Le monde
//! est mono-thread en tranche 1, ce qui garantit cette reproductibilité sans
//! précaution supplémentaire.
//!
//! # Périmètre actuel
//!
//! Fondation du monde : configuration validée (R-990), corps `STATIC` /
//! `KINEMATIC` / `DYNAMIC` (§10.2), pas fixe à accumulateur clampé, gravité par
//! dimension (R-611). Catalogue des formes portables par un corps dynamique
//! (§10.3) : primitives, `ConvexHull` (4..256 points) et `Compound` (≤64), avec
//! validation à l'ajout. Les formes concaves du monde (`TriMesh`, `Heightfield`)
//! arrivent avec C-38 (INV-13). Groupes de collision data-driven par nom (§10.4,
//! R-980) avec les huit groupes réservés. Gestion d'activité : CCD déclarée,
//! sommeil forcé hors du rayon de simulation (R-612), plafond de corps actifs
//! déterministe (R-613). Forces environnementales complètes (§10.6) : gravité
//! par corps, vent de dimension, traînée relative au vent, portance sur surfaces
//! déclarées (R-1000) et flottabilité (volume immergé par les 8 coins de l'AABB
//! contre un fluide de dimension), appliquées par sous-pas. Événements (§10.7,
//! DM → C-41) : la structure `PhysicsEvent` figée, l'identité de corps, le lot
//! borné (R-1011) et les transitions SLEEP/WAKE ; les événements de contact
//! (données R-615) et de capteur suivent. La frontière FFI arrive ensuite.
//!
//! Exigences : R-460, R-462, R-610, R-611, R-990, R-1020, R-1753 ; PARTIE 10.
//! Correspondance avec `rapier` 0.35 : `docs/decisions/ADR-112.md`.

mod body;
mod commands;
mod config;
mod forces;
mod groups;
mod sim;
mod world;

pub use body::{
    BodyCollider, BodyError, BodyId, BodyKind, CompoundPart, ContactMaterial, Shape,
    ShapeConversionError,
};
pub use commands::{apply_command_stream, create_assembly_payloads, CommandError, CommandOutcome};
pub use config::{ConfigError, PhysicsConfig};
pub use forces::{FluidEnvironment, FluidVolume, LiftSurface};
pub use groups::{CollisionGroups, GroupError, GroupRegistry, ReservedGroup, GROUP_COUNT};
pub use sim::SimDriver;
pub use world::{PhysicsWorld, Pose};

// §10.7 : les événements sont une structure DM figée dans `ax-model` ; on les
// réexporte pour que l'API du monde physique soit autonome.
pub use ax_model::dm::handle::Handle;
pub use ax_model::dm::physics::{body_state_flags, event_kind, BodyState, PhysicsEvent};
