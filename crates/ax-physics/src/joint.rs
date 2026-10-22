//! Les joints entre les corps d'une assembly (C-34, ADR-151 D4, D5).
//!
//! Un joint relie deux corps par un repère dans chacun : à la pose de repos de l'asset, les deux
//! repères coïncident, et l'axe libre d'un joint `Revolute` ou `Prismatic` est leur axe X. Rapier
//! le résout avec les contacts dans l'étape 4, à chaque sous-pas ; l'étape 5 lit ensuite ses
//! efforts et le rompt au-delà de ses seuils. Aucun type de rapier ne sort d'ici (ADR-002 du CDC).

use ax_math::{Quat, Vec3};

/// Le type d'un joint (fiche 5.26).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum JointKind {
    /// Aucun degré de liberté.
    Fixed,
    /// Une rotation autour de l'axe X des repères.
    Revolute,
    /// Une translation le long de l'axe X des repères.
    Prismatic,
    /// Trois rotations autour de l'ancrage.
    Spherical,
    /// Six axes réglés un à un.
    Generic,
    /// Une distance maximale entre les ancrages.
    Rope,
    /// Un ressort amorti vers une longueur de repos.
    Spring,
}

/// Le mode d'un axe d'un joint `Generic`.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum AxisMode {
    /// L'axe est bloqué.
    Locked,
    /// L'axe est libre.
    Free,
    /// L'axe est libre dans cette plage, en blocs ou en radians.
    Limited([f32; 2]),
}

/// Le moteur d'un joint, sur son axe libre (`Revolute`, `Prismatic`).
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum JointMotor {
    /// Une vitesse cible, atteinte aussi vite que `max_force` le permet.
    Velocity {
        /// Vitesse cible, en rad/s ou en blocs/s.
        speed: f32,
        /// Force ou couple maximal ; `INFINITY` : sans plafond.
        max_force: f32,
    },
    /// Un servo en position : une force `stiffness·(target − x) + damping·(speed − v)`.
    Position {
        /// Position cible, en radians ou en blocs.
        target: f32,
        /// Vitesse cible, en rad/s ou en blocs/s.
        speed: f32,
        /// Raideur, en N/bloc ou en N·m/rad.
        stiffness: f32,
        /// Amortissement.
        damping: f32,
        /// Force ou couple maximal ; `INFINITY` : sans plafond.
        max_force: f32,
    },
}

/// Les seuils de rupture d'un joint (D5) ; `INFINITY` : aucun.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct BreakThresholds {
    /// Force que le joint transmet, en newtons.
    pub force: f32,
    /// Couple qu'il transmet autour de son ancrage, en newtons-mètres.
    pub torque: f32,
    /// Énergie absorbée sur un tick, en joules : par sous-pas, ½·λ·c, c la vitesse relative de ses
    /// corps sur ses axes contraints avant le sous-pas et λ l'impulsion qui l'annule.
    pub energy: f32,
}

impl BreakThresholds {
    /// Aucun seuil.
    pub const NONE: Self = Self {
        force: f32::INFINITY,
        torque: f32::INFINITY,
        energy: f32::INFINITY,
    };
}

/// Un joint à créer.
#[derive(Debug, Clone, PartialEq)]
pub struct JointSpec {
    /// Son type.
    pub kind: JointKind,
    /// Son repère dans le corps `a` : ancrage et rotation.
    pub frame_a: (Vec3, Quat),
    /// Son repère dans le corps `b`.
    pub frame_b: (Vec3, Quat),
    /// La plage de l'axe libre (`Revolute`, `Prismatic`), en radians ou en blocs.
    pub limits: Option<[f32; 2]>,
    /// Le moteur de l'axe libre.
    pub motor: Option<JointMotor>,
    /// La friction interne, en newtons ou newtons-mètres : elle s'oppose au mouvement des axes
    /// libres sans le bloquer, et ne gêne pas le moteur.
    pub friction: Option<f32>,
    /// Les seuils de rupture.
    pub breaking: BreakThresholds,
    /// Les six axes d'un `Generic` : x, y, z linéaires, puis x, y, z angulaires.
    pub axes: [AxisMode; 6],
    /// Distance maximale d'un `Rope`, en blocs.
    pub max_length: f32,
    /// Longueur de repos d'un `Spring`, en blocs.
    pub rest_length: f32,
    /// Raideur d'un `Spring`, en N/bloc.
    pub stiffness: f32,
    /// Amortissement d'un `Spring`, en N·s/bloc.
    pub damping: f32,
    /// Vrai si ses deux corps se touchent.
    pub collide: bool,
}

/// Référence d'un joint dans un [`PhysicsWorld`](crate::PhysicsWorld).
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct JointId(pub(crate) u64);

/// Ce qu'un joint ne peut pas être.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum JointError {
    /// Un de ses corps n'existe pas.
    MissingBody,
    /// Ses deux bouts sont sur le même corps.
    SameBody,
    /// Un réglage n'est pas fini, ou une plage est croisée.
    InvalidSetting,
}

impl core::fmt::Display for JointError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.write_str(match self {
            JointError::MissingBody => "corps du joint absent",
            JointError::SameBody => "joint d'un corps à lui-même",
            JointError::InvalidSetting => "réglage de joint invalide",
        })
    }
}

impl std::error::Error for JointError {}

/// Ce que l'étape 5 a vu d'un joint au dernier sous-pas.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct JointReport {
    /// Force qu'il transmet entre ses corps, friction et moteur compris, en newtons.
    pub force: f32,
    /// Couple qu'il transmet autour de son ancrage, en newtons-mètres.
    pub torque: f32,
    /// Énergie absorbée depuis le début du tick, en joules.
    pub energy: f32,
    /// Vrai s'il est rompu.
    pub broken: bool,
}

/// Les comptes des joints d'un monde.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct JointCounts {
    /// Joints non rompus.
    pub active: usize,
    /// Joints résolus au dernier sous-pas : ceux dont un corps au moins est éveillé (R-631).
    pub solved: usize,
    /// Ruptures depuis la création du monde.
    pub broken: u64,
}
