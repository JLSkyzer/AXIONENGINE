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
//! # Périmètre de la tranche 1
//!
//! Fondation du monde : configuration validée (R-990), corps `STATIC` /
//! `KINEMATIC` / `DYNAMIC` (§10.2) avec colliders primitifs, pas fixe à
//! accumulateur clampé, gravité par dimension (R-611). Le jeu complet des formes
//! (§10.3), les forces (§10.6), les événements (§10.7, DM → C-41) et la
//! frontière FFI arrivent dans les tranches suivantes.
//!
//! Exigences : R-460, R-462, R-610, R-611, R-990, R-1020, R-1753 ; PARTIE 10.
//! Correspondance avec `rapier` 0.35 : `docs/decisions/ADR-112.md`.

mod body;
mod config;
mod world;

pub use body::{BodyId, BodyKind, Shape};
pub use config::{ConfigError, PhysicsConfig};
pub use world::{PhysicsWorld, Pose};
