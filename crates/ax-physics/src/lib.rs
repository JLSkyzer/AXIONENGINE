//! C-31 : monde physique d'AXION ENGINE.
//!
//! Voir la fiche 5.23 et la PARTIE 10 du cahier des charges. Le moteur est
//! `rapier3d` (ADR-002) ; ses types ne fuient jamais hors de ce crate
//! (R-1753), qui les convertit vers les types AXION à sa frontière.
//!
//! # Déterminisme
//!
//! La physique n'est pas bit-exacte entre machines (ADR-005) : elle est
//! **reproductible sur une même machine et un même binaire** (R-1020). Chaque
//! monde est mono-thread, parcouru dans l'ordre de `rapier` ; ses tables de
//! hachage ne sont consultées que par clé, et celles qu'il parcourt sont ordonnées.
//!
//! # Contenu
//!
//! - [`PhysicsWorld`] (`world`) — un monde : corps `STATIC` / `KINEMATIC` /
//!   `DYNAMIC` (§10.2), pas fixe à accumulateur clampé (R-990), formes des corps
//!   dynamiques (§10.3, INV-13), groupes de collision (§10.4, R-980), activité
//!   autour des observateurs (ADR-123 §3, R-612, R-613), CCD déclarée ou
//!   automatique au-delà de `|v|·dt > 0.5·min_dim` (fiche 5.23), forces
//!   environnementales (§10.6, R-1000), événements de sommeil, de contact,
//!   d'impulsion et de capteur (§10.7, R-615, R-1011), garde-fous R-180/R-181,
//!   description DM-08 d'un corps (ADR-130).
//! - [`SimDriver`] (`sim`) — un monde par dimension (R-610), origine flottante
//!   (R-462), routage des handles d'assembly, tuiles de collision du monde (C-38,
//!   R-640, R-641, groupe `world`), volumes de fluide et proxies d'entités
//!   vanilla (R-614, `proxies`).
//! - `commands` — lecture du flux de commandes IF-03 ; `scheduler` — étapes du
//!   tick (C-40, R-660) ; `governor` — paliers de dégradation (R-1880) ; `query`
//!   — requêtes spatiales (C-39) ; `debug` — géométrie des overlays du debug
//!   renderer (C-67, ADR-121 et son amendement A1).
//!
//! Hors de ce crate pour l'instant : liaisons et leurs défaillances (C-34),
//! matériaux de contact des assemblies (DM-07 ; contact neutre en attendant),
//! chaîne de dommage (C-41..C-45, M6).
//!
//! Exigences : R-460, R-462, R-610, R-611, R-990, R-1020, R-1753 ; PARTIE 10.
//! Correspondance avec `rapier` 0.35 : `docs/decisions/ADR-112.md`.

mod activity;
mod body;
mod commands;
mod config;
mod debug;
mod forces;
mod governor;
mod groups;
mod materials;
mod proxies;
mod query;
mod scheduler;
mod sim;
mod world;

pub use activity::ActivityReport;
pub use body::{
    BodyCollider, BodyError, BodyId, BodyKind, CombineRule, CompoundPart, ContactMaterial, Shape,
    ShapeConversionError,
};
pub use commands::{apply_command_stream, create_assembly_payloads, CommandError, CommandOutcome};
pub use config::{ConfigError, PhysicsConfig};
pub use debug::{
    select_drafts, DebugDraft, DebugSelection, AXIS_LENGTH, CONTACT_CROSS, MASS_CROSS,
    NORMAL_LENGTH, ROUND_SUBDIVISIONS,
};
pub use forces::{FluidEnvironment, FluidVolume, LiftSurface};
pub use governor::{DegradationLevel, DegradationTransition, Governor, WINDOW_TICKS};
pub use groups::{CollisionGroups, GroupError, GroupRegistry, ReservedGroup, GROUP_COUNT};
pub use materials::MaterialTable;
pub use proxies::{EntityProxy, ProxyShape};
pub use query::{RayHit, SensorMode, SpatialFilter, SweepHit};
pub use scheduler::{check_order, SimMode, Stage, StageDurations, StageLog};
pub use sim::{SimDriver, SimSettings};
pub use world::{BodyReports, PhysicsWorld, Pose, WorldCounters};

// §10.7 : les événements sont une structure DM figée dans `ax-model` ; on les
// réexporte pour que l'API du monde physique soit autonome.
pub use ax_model::dm::handle::Handle;
pub use ax_model::dm::physics::{
    body_state_flags, combine, event_data, event_kind, BodyBounds, BodyDesc, BodyState,
    PhysicsEvent, PhysicsMaterial, NO_MATERIAL,
};
