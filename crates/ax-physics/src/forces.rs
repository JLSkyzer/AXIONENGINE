//! Types de forces environnementales déclaratives (§10.6).

use ax_math::Vec3;

/// Le fluide d'une dimension, pour la flottabilité (§10.6).
///
/// Modèle simple : une surface **plate** au niveau `surface_y` et une densité
/// uniforme. La présence réelle de l'eau, par bloc, viendra du fournisseur de
/// collision du monde (C-38) ; ce niveau plat suffit à porter le modèle de
/// flottabilité, dont le volume immergé est de toute façon approché par les huit
/// coins de l'AABB d'un corps.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct FluidEnvironment {
    /// Altitude de la surface du fluide, en blocs. Un point d'altitude
    /// inférieure est immergé.
    pub surface_y: f32,
    /// Masse volumique du fluide, en kg/m³ (eau douce ≈ 1000).
    pub density: f32,
}

/// Un volume de fluide **localisé**, produit par le fournisseur de collision du monde
/// (C-38, R-642) : une boîte d'eau (ou autre fluide) en coordonnées **locales** du monde
/// physique, avec sa densité.
///
/// Les fluides sont des **volumes de flottabilité** — des « capteurs » : ils ne
/// collisionnent pas solidement, ils portent et freinent les corps qui les traversent.
/// Le volume immergé d'un corps y est approché par l'échantillonnage des **huit coins de
/// son AABB** (§10.6) : chaque coin dans un volume compte pour un huitième du corps. Le
/// modèle est explicitement approximatif et assumé comme tel.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct FluidVolume {
    /// Coin bas (min) de la boîte, en blocs (coords locales du monde physique).
    pub min: Vec3,
    /// Coin haut (max) de la boîte, en blocs (coords locales du monde physique).
    pub max: Vec3,
    /// Masse volumique du fluide, en kg/m³ (eau douce ≈ 1000). Strictement positive.
    pub density: f32,
}

impl FluidVolume {
    /// Vrai si le point (coords locales) est dans la boîte, bornes incluses.
    #[must_use]
    pub fn contains(&self, p: Vec3) -> bool {
        p.x >= self.min.x
            && p.x <= self.max.x
            && p.y >= self.min.y
            && p.y <= self.max.y
            && p.z >= self.min.z
            && p.z <= self.max.z
    }
}

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
