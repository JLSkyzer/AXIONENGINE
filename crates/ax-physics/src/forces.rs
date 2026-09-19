//! Types de forces environnementales déclaratives (§10.6).

use ax_math::Vec3;

/// Une surface portante déclarée sur un corps (§10.6, R-1000).
///
/// C'est ce qui rend avions et bateaux possibles **sans système dédié** : une
/// aile, une voile, un empennage sont des surfaces portantes, pas des systèmes.
/// Un corps peut en porter plusieurs.
///
/// # Modèle de portance
///
/// Le §10.6 fixe la **magnitude** de la portance, `0.5·ρ·Cl·A·|v|²`, mais pas sa
/// **direction**. Le moteur la choisit ainsi : la portance est perpendiculaire à
/// la vitesse relative au point de la surface, dans la direction de la composante
/// de la normale orthogonale à cet écoulement. Une surface dont la normale est
/// alignée avec l'écoulement (de profil) ne porte pas ; une surface qui lui fait
/// face porte au maximum. `Cl` reste un coefficient déclaré constant — le modèle
/// ne le fait pas varier avec l'incidence. La force s'applique **au point** de la
/// surface, créant donc le moment qui fait tanguer, rouler et virer.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct LiftSurface {
    /// Point d'application, en coordonnées locales du corps (blocs).
    pub local_point: Vec3,
    /// Normale de la surface, en coordonnées locales (normalisée à l'usage).
    pub local_normal: Vec3,
    /// Aire de référence, en m².
    pub area: f32,
    /// Coefficient de portance `Cl`.
    pub lift_coefficient: f32,
}
