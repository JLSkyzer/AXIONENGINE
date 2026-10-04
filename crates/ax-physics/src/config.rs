//! Configuration d'un monde physique (C-31, PARTIE 10).
//!
//! Ces valeurs pilotent le pas fixe (R-990), la gravité (R-611), le solveur
//! (§10.5) et le sommeil (fiche 5.23). En tranche 1 elles sont construites en
//! Rust ; leur lecture depuis la configuration Java arrive avec la frontière
//! (tranche 4). La correspondance avec le solveur de `rapier` 0.35 est consignée
//! dans `docs/decisions/ADR-112.md`.

use ax_math::Vec3;
use core::fmt;

/// Pas de temps fixes autorisés (R-990) : `sim.fixed_dt` ∈ {1/30, 1/60, 1/120}.
///
/// Comparés à l'identique : la valeur vient d'une configuration, et un pas hors
/// de cet ensemble déstabiliserait le solveur ou dériverait de l'horloge du jeu.
const ALLOWED_FIXED_DT: [f32; 3] = [1.0 / 30.0, 1.0 / 60.0, 1.0 / 120.0];

/// Écart admis entre un pas configuré, écrit en décimal, et le pas autorisé qu'il désigne,
/// en secondes : l'annexe arrondit au dix-millionième, l'écart réel est de l'ordre de 3·10⁻⁸.
const CONFIGURED_DT_TOLERANCE: f64 = 1.0e-6;

/// Ce qui rend une configuration invalide.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConfigError {
    /// `fixed_dt` hors de {1/30, 1/60, 1/120} (R-990).
    FixedDtNotAllowed,
    /// `max_substeps` nul : un tick doit pouvoir avancer d'au moins un sous-pas.
    ZeroMaxSubsteps,
}

impl fmt::Display for ConfigError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let message = match self {
            Self::FixedDtNotAllowed => "sim.fixed_dt hors de {1/30, 1/60, 1/120} (R-990)",
            Self::ZeroMaxSubsteps => "sim.max_substeps doit valoir au moins 1",
        };
        f.write_str(message)
    }
}

impl std::error::Error for ConfigError {}

/// Configuration d'un monde physique.
///
/// `fixed_dt` et `max_substeps` sont privés parce qu'ils portent l'invariant
/// R-990 ; les autres champs sont des réglages bornés à l'usage par le monde
/// (une itération de solveur nulle est ramenée à 1, un seuil négatif signifie
/// « ne jamais dormir », comme chez `rapier`).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct PhysicsConfig {
    fixed_dt: f32,
    max_substeps: u32,
    /// Gravité de la dimension (R-611), défaut `(0, −9.81, 0)`.
    pub gravity: Vec3,
    /// Itérations du solveur de vitesse (§10.5), défaut 4.
    pub velocity_iterations: u32,
    /// Itérations internes de position (§10.5), défaut 1.
    pub position_iterations: u32,
    /// Seuil linéaire de sommeil, en m/s (fiche 5.23), défaut 0.05.
    pub sleep_linear_threshold: f32,
    /// Seuil angulaire de sommeil, en rad/s (fiche 5.23), défaut 0.05.
    ///
    /// Le défaut de `rapier` est 0.5 rad/s ; la fiche impose 0.05, qu'on écrit
    /// donc explicitement sur chaque corps (ADR-112).
    pub sleep_angular_threshold: f32,
    /// Délai d'immobilité avant sommeil, en s (fiche 5.23), défaut 0.5.
    pub sleep_time: f32,
}

impl PhysicsConfig {
    /// Construit une configuration en validant l'invariant R-990.
    ///
    /// Les autres champs prennent les valeurs par défaut de la fiche 5.23.
    ///
    /// # Errors
    /// [`ConfigError::FixedDtNotAllowed`] si `fixed_dt` n'appartient pas à
    /// {1/30, 1/60, 1/120} ; [`ConfigError::ZeroMaxSubsteps`] si `max_substeps`
    /// est nul.
    pub fn new(fixed_dt: f32, max_substeps: u32) -> Result<Self, ConfigError> {
        if !ALLOWED_FIXED_DT.contains(&fixed_dt) {
            return Err(ConfigError::FixedDtNotAllowed);
        }
        if max_substeps == 0 {
            return Err(ConfigError::ZeroMaxSubsteps);
        }
        Ok(Self {
            fixed_dt,
            max_substeps,
            ..Self::defaults()
        })
    }

    /// Construit une configuration depuis les valeurs **configurées** (IF-01, ADR-123 §1).
    ///
    /// L'annexe A.3 écrit le pas en décimal (`0.0166667`), et `f32` ne le confond pas avec
    /// `1/60` : la valeur est rapportée au pas autorisé le plus proche, s'il en est à moins
    /// d'un millionième de seconde. Le registre de configuration a déjà restreint `sim.fixed_dt`
    /// à ces trois écritures ; une autre valeur reste refusée.
    ///
    /// # Errors
    /// Celles de [`new`](Self::new) : aucun pas autorisé assez proche, ou `max_substeps` nul.
    pub fn from_configured(fixed_dt: f64, max_substeps: u32) -> Result<Self, ConfigError> {
        let nearest = ALLOWED_FIXED_DT
            .iter()
            .copied()
            .find(|allowed| (f64::from(*allowed) - fixed_dt).abs() < CONFIGURED_DT_TOLERANCE)
            .ok_or(ConfigError::FixedDtNotAllowed)?;
        Self::new(nearest, max_substeps)
    }

    /// Valeurs par défaut de la fiche 5.23, toutes valides.
    fn defaults() -> Self {
        Self {
            fixed_dt: 1.0 / 60.0,
            max_substeps: 4,
            gravity: Vec3::new(0.0, -9.81, 0.0),
            velocity_iterations: 4,
            position_iterations: 1,
            sleep_linear_threshold: 0.05,
            sleep_angular_threshold: 0.05,
            sleep_time: 0.5,
        }
    }

    /// Pas de temps fixe, garanti dans {1/30, 1/60, 1/120} (R-990).
    #[must_use]
    pub fn fixed_dt(&self) -> f32 {
        self.fixed_dt
    }

    /// Nombre maximal de sous-pas par tick, garanti non nul (R-990).
    #[must_use]
    pub fn max_substeps(&self) -> u32 {
        self.max_substeps
    }
}

impl Default for PhysicsConfig {
    fn default() -> Self {
        Self::defaults()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn un_pas_configure_se_rapporte_au_pas_exact_de_r990() {
        // L'annexe A.3 écrit les pas au dix-millionième ; `f32` ne les confond pas avec
        // les pas exacts, que `new` exige à l'identique.
        for (configure, exact) in [
            (0.0333333, 1.0 / 30.0),
            (0.0166667, 1.0 / 60.0),
            (0.0083333, 1.0 / 120.0),
        ] {
            let config = PhysicsConfig::from_configured(configure, 4).expect("pas de l'annexe");
            assert_eq!(config.fixed_dt(), exact, "{configure} désigne {exact}");
        }
    }

    #[test]
    fn un_pas_configure_hors_de_r990_reste_refuse() {
        assert_eq!(
            PhysicsConfig::from_configured(0.02, 4),
            Err(ConfigError::FixedDtNotAllowed)
        );
        assert_eq!(
            PhysicsConfig::from_configured(0.0166667, 0),
            Err(ConfigError::ZeroMaxSubsteps)
        );
    }
}
