//! Requêtes spatiales en lecture seule (C-39, fiche 5.31).
//!
//! `raycast`, `sweep`, `overlap` et leurs versions par lot, avec filtres par groupe/masque,
//! exclusion d'assembly et traitement des capteurs. Les requêtes **ne mutent jamais** le
//! monde (R-650) et lisent la géométrie de collision courante telle que le dernier pas l'a
//! laissée — l'état du **début du tick** du point de vue de Java (R-651).
//!
//! Les résultats sont des types AXION (glam) : aucun type `rapier`/`parry` ne fuit (R-460,
//! R-1753).

use crate::body::BodyId;
use crate::groups::CollisionGroups;
use ax_math::Vec3;

/// Traitement des capteurs (colliders « trigger ») par une requête spatiale (C-39).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum SensorMode {
    /// Ne heurte que la géométrie **solide** (défaut) ; les capteurs sont ignorés.
    #[default]
    SolidsOnly,
    /// Ne heurte que les **capteurs**.
    SensorsOnly,
    /// Heurte les deux.
    Both,
}

/// Filtre d'une requête spatiale (C-39, fiche 5.31).
///
/// Le défaut ne filtre rien (tous les groupes, aucune exclusion) et ne heurte que le solide.
#[derive(Debug, Clone, Copy, Default)]
pub struct SpatialFilter {
    /// Groupes de collision (membership/masque, §10.4) ; `None` = tous les groupes.
    pub groups: Option<CollisionGroups>,
    /// Corps (assembly) dont **tous** les colliders sont exclus ; `None` = aucune exclusion.
    pub exclude_body: Option<BodyId>,
    /// Traitement des capteurs.
    pub sensors: SensorMode,
}

impl SpatialFilter {
    /// Filtre neutre : tous groupes, aucune exclusion, solide seul.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Exclut tous les colliders d'un corps (l'assembly qui lance la requête, typiquement).
    #[must_use]
    pub fn excluding(mut self, body: BodyId) -> Self {
        self.exclude_body = Some(body);
        self
    }

    /// Restreint aux groupes de collision donnés.
    #[must_use]
    pub fn with_groups(mut self, groups: CollisionGroups) -> Self {
        self.groups = Some(groups);
        self
    }

    /// Fixe le traitement des capteurs.
    #[must_use]
    pub fn with_sensors(mut self, sensors: SensorMode) -> Self {
        self.sensors = sensors;
        self
    }
}

/// Impact d'un rayon (C-39).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct RayHit {
    /// Corps heurté.
    pub body: BodyId,
    /// Distance le long du rayon jusqu'à l'impact, en blocs.
    pub distance: f32,
    /// Point d'impact, en repère local du monde (blocs).
    pub point: Vec3,
    /// Normale de surface à l'impact.
    pub normal: Vec3,
}

/// Impact d'un balayage de forme (sweep, C-39).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct SweepHit {
    /// Corps heurté.
    pub body: BodyId,
    /// Distance parcourue avant impact, le long du déplacement, en blocs.
    pub time_of_impact: f32,
    /// Point d'impact sur le collider heurté, en repère monde.
    pub point: Vec3,
    /// Normale de surface à l'impact.
    pub normal: Vec3,
}
