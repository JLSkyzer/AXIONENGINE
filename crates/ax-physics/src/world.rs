//! Le monde physique d'une dimension (C-31, fiche 5.23).

use ax_math::{FloatingOrigin, Quat, Vec3};
use rapier3d::parry::query::ShapeCastOptions;
use rapier3d::parry::utils::Array2;
use rapier3d::prelude::{
    ActiveEvents, Collider, ColliderBuilder, ColliderHandle, ColliderSet, CollisionEvent,
    CollisionEventFlags, ContactPair, EventHandler, PhysicsWorld as RapierWorld,
    Pose as RapierPose, QueryFilter as RapierQueryFilter, QueryFilterFlags, Ray, Real, RigidBody,
    RigidBodyBuilder, RigidBodyHandle, RigidBodySet, RigidBodyType, SharedShape, Vector,
};

use crate::activity::{Candidate, Observers};
use crate::proxies::{EntityProxy, ProxyShape};
use crate::query::{RayHit, SensorMode, SpatialFilter, SweepHit};
use crate::scheduler::{SimMode, Stage, StageDurations};
use std::time::Instant;

use crate::body::{
    BodyCollider, BodyError, BodyId, BodyKind, CompoundPart, ContactMaterial, Shape,
    MAX_COMPOUND_PARTS, MAX_CONVEX_HULL_POINTS, MIN_CONVEX_HULL_POINTS,
};
use crate::config::PhysicsConfig;
use crate::debug::{append_outline, ColliderOutline};
use crate::forces::{FluidEnvironment, FluidVolume, LiftSurface};
use crate::groups::{CollisionGroups, ReservedGroup};
use ax_model::dm::debug::debug_body_flags;
use ax_model::dm::handle::Handle;
use ax_model::dm::physics::{
    body_state_flags, event_data, event_kind, BodyBounds, BodyState, PhysicsEvent,
};
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::sync::atomic::{AtomicU64, Ordering};

/// Plafond par défaut d'événements par tick (§10.7, `physics.max_events_per_tick`).
const DEFAULT_MAX_EVENTS_PER_TICK: usize = 4096;

/// Seuil par défaut sous lequel un contact ne remonte pas à Java (§10.7,
/// `physics.contact_event_threshold`), en N·s.
const DEFAULT_CONTACT_EVENT_THRESHOLD: f32 = 0.5;

/// Masse volumique de l'air au niveau de la mer, en kg/m³ (§10.6, terme ρ de la
/// traînée). Valeur physique standard, non un chiffre de performance.
const AIR_DENSITY: f32 = 1.225;

/// Borne linéaire par défaut, en m/s (R-180). Au-delà, la vitesse est clampée.
const DEFAULT_MAX_LINEAR_VEL: f32 = 300.0;

/// Part d'un sous-pas en deçà de laquelle un reliquat d'accumulateur compte pour un sous-pas
/// entier (R-990) : de quoi absorber l'arrondi f32 d'un tick divisé en sous-pas, jamais un
/// sous-pas réellement manquant.
const SUBSTEP_TOLERANCE: f32 = 1e-3;

/// Borne angulaire par défaut, en rad/s (R-180). Au-delà, la vitesse est clampée.
const DEFAULT_MAX_ANGULAR_VEL: f32 = 100.0;

/// Période minimale entre deux journalisations de clamp d'un même corps, en s
/// (R-180 : « au plus une fois par body et par minute »). Comptée en temps
/// simulé, donc déterministe.
const CLAMP_JOURNAL_PERIOD: f64 = 60.0;

/// Borne de coordonnée locale au-delà de laquelle une position est jugée hors du
/// monde (R-181). En repère d'origine flottante les coordonnées restent proches
/// de zéro ; une valeur au-delà de dix millions de blocs — bien en deçà du
/// débordement `f32`, bien au-delà de toute position légitime — signale une
/// explosion du solveur, à traiter comme FM-20.
const WORLD_COORD_LIMIT: f32 = 1.0e7;

/// Paramètres de force environnementale attachés à un corps (§10.6).
///
/// La flottabilité (fluide) enrichira ce profil dans une tranche suivante.
#[derive(Debug, Clone, Default)]
struct AeroProfile {
    /// Produit `Cd · A` (coefficient de traînée × aire de référence), en m².
    drag_cd_a: f32,
    /// Surfaces portantes déclarées (§10.6, R-1000).
    lift_surfaces: Vec<LiftSurface>,
}

/// Le plan de forces d'un corps pour un sous-pas : une force centrale (traînée)
/// et des forces appliquées à des points (portances, qui créent un moment).
struct BodyForcePlan {
    handle: RigidBodyHandle,
    central: Vec3,
    at_points: Vec<(Vec3, Vec3)>,
}

/// Identité d'un corps pour les événements (§10.7) : à quelle assembly, quel
/// node et quel matériau il correspond. Un corps sans identité déclarée émet des
/// événements à [`Handle::ABSENT`].
#[derive(Debug, Clone, Copy, Default)]
struct BodyIdentity {
    assembly: Handle,
    node: u32,
    material: u16,
}

/// Dernière pose finie et dans le monde d'un corps, mémorisée pour la
/// restauration de R-181 (retour au dernier état valide sur NaN ou débordement).
#[derive(Debug, Clone, Copy)]
struct ValidPose {
    translation: Vec3,
    rotation: Quat,
}

/// Vitesse d'un corps au moment où la gestion d'activité l'a endormi (R-612, R-613) ;
/// `rapier` l'annule en l'endormant, son réveil la lui rend.
#[derive(Debug, Clone, Copy)]
struct ForcedSleep {
    linvel: Vec3,
    angvel: Vec3,
}

/// Mouvement d'un corps juste avant un sous-pas : de quoi connaître la vitesse d'un de ses
/// points à l'approche d'un contact, avant que la résolution ne l'absorbe (R-615).
#[derive(Debug, Clone, Copy)]
struct PreStepMotion {
    linvel: Vec3,
    angvel: Vec3,
    center_of_mass: Vec3,
}

impl PreStepMotion {
    /// Vitesse du point `point` (repère monde local) du corps : `v + ω × (p − c)`.
    fn velocity_at(&self, point: Vec3) -> Vec3 {
        self.linvel + self.angvel.cross(point - self.center_of_mass)
    }
}

/// Collecteur d'événements de collision d'un sous-pas.
///
/// `rapier` appelle son gestionnaire pendant `step_with_events` ; on n'y retient
/// que les paires de colliders et le drapeau capteur, puis on reconstruit les
/// événements après le pas, où l'on a accès aux identités et aux paires de
/// contact. Le `Mutex` satisfait le `Send + Sync` du trait ; le monde étant
/// mono-thread, il n'est jamais contendu.
#[derive(Default)]
struct ContactCollector {
    started: std::sync::Mutex<Vec<(ColliderHandle, ColliderHandle, bool)>>,
    stopped: std::sync::Mutex<Vec<(ColliderHandle, ColliderHandle, bool)>>,
}

impl ContactCollector {
    fn take_started(&self) -> Vec<(ColliderHandle, ColliderHandle, bool)> {
        std::mem::take(&mut self.started.lock().expect("collecteur non empoisonné"))
    }

    fn take_stopped(&self) -> Vec<(ColliderHandle, ColliderHandle, bool)> {
        std::mem::take(&mut self.stopped.lock().expect("collecteur non empoisonné"))
    }
}

impl EventHandler for ContactCollector {
    fn handle_collision_event(
        &self,
        _bodies: &RigidBodySet,
        _colliders: &ColliderSet,
        event: CollisionEvent,
        _contact_pair: Option<&ContactPair>,
    ) {
        match event {
            CollisionEvent::Started(a, b, flags) => self
                .started
                .lock()
                .expect("collecteur non empoisonné")
                .push((a, b, flags.contains(CollisionEventFlags::SENSOR))),
            CollisionEvent::Stopped(a, b, flags) => self
                .stopped
                .lock()
                .expect("collecteur non empoisonné")
                .push((a, b, flags.contains(CollisionEventFlags::SENSOR))),
        }
    }

    fn handle_contact_force_event(
        &self,
        _dt: Real,
        _bodies: &RigidBodySet,
        _colliders: &ColliderSet,
        _contact_pair: &ContactPair,
        _total_force_magnitude: Real,
    ) {
        // Les forces de contact ne sont pas employées ici : les impulsions se
        // lisent sur la paire de contact après le pas.
    }
}

/// Pose rigide d'un corps : translation et rotation, sans échelle.
///
/// Exprimée en `f32` **relativement à l'origine flottante** de la dimension
/// (R-462) ; la composition avec la position monde en `f64` se fait à la
/// frontière (tranche 4). Les types viennent de `glam` via `ax_math` (R-460).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Pose {
    /// Translation locale, en blocs.
    pub translation: Vec3,
    /// Rotation, quaternion `(x, y, z, w)` (R-461).
    pub rotation: Quat,
}

/// Un monde physique — un par dimension (R-610).
///
/// Enveloppe le monde `rapier` (ADR-002) ; aucun de ses types ne franchit cette
/// frontière (R-1753). Le pas est **fixe** (R-990) : [`advance`](Self::advance)
/// accumule le temps réel et exécute des sous-pas de `fixed_dt`, sans spirale de
/// rattrapage. Mono-thread, ce qui suffit à la reproductibilité même-machine de
/// R-1020 (ADR-005) : la parallélisation à fusion ordonnée viendra plus tard.
pub struct PhysicsWorld {
    inner: RapierWorld,
    config: PhysicsConfig,
    /// Temps écoulé non encore simulé, en s. Toujours dans `[0, fixed_dt)` après
    /// [`advance`](Self::advance).
    accumulator: f32,
    /// Vent de la dimension (§10.6, `physics.wind`), défaut nul.
    wind: Vec3,
    /// Fluide de la dimension pour la flottabilité (§10.6) : une surface plate,
    /// absente par défaut. Les volumes d'eau **localisés** du monde vivent à part,
    /// dans `fluid_volumes`.
    fluid: Option<FluidEnvironment>,
    /// Volumes de fluide localisés du fournisseur de collision du monde (C-38, R-642),
    /// en coordonnées locales, par section 16³, dans l'ordre des sections (R-1020). Chaque
    /// section porte ses boîtes d'eau et la boîte qui les englobe.
    fluid_volumes: BTreeMap<[i32; 3], FluidSection>,
    /// Rôle de la simulation (C-40, R-662) : serveur autoritaire par défaut.
    mode: SimMode,
    /// Durées par étape du dernier tick (C-40, R-661), observationnelles.
    stage_durations: StageDurations,
    /// Profils de force par corps. **Consultée par clé** pendant l'itération
    /// déterministe des corps `rapier`, jamais itérée elle-même : son ordre de
    /// parcours n'entre donc dans aucun résultat de simulation (R-1020).
    aero: HashMap<BodyId, AeroProfile>,
    /// Identités des corps, pour peupler les événements (§10.7).
    identity: HashMap<BodyId, BodyIdentity>,
    /// Dernier état de sommeil connu de chaque corps dynamique, pour détecter les
    /// transitions SLEEP/WAKE. Consultée par clé, jamais itérée (R-1020).
    sleep_state: HashMap<BodyId, bool>,
    /// Lot d'événements du tick courant (§10.7), vidé par `drain_events`.
    events: Vec<PhysicsEvent>,
    /// Plafond d'événements par tick (R-1011).
    max_events_per_tick: usize,
    /// Seuil d'impulsion sous lequel un CONTACT_IMPULSE ne remonte pas (R-1012).
    contact_event_threshold: f32,
    /// Compteur cumulé d'événements perdus faute de place (R-1011, aucune perte
    /// silencieuse).
    dropped_events: u64,
    /// Bornes de vitesse propres à un corps (R-180). À défaut, les valeurs par
    /// défaut de la fiche (300 m/s, 100 rad/s) s'appliquent. Consultée par clé,
    /// jamais itérée (R-1020).
    velocity_limits: HashMap<BodyId, (f32, f32)>,
    /// Dernière pose valide connue de chaque corps, pour la restauration R-181.
    /// Semée à la création du corps. Consultée par clé, jamais itérée (R-1020).
    last_valid: HashMap<BodyId, ValidPose>,
    /// Drapeaux de garde-fou du tick courant (R-180 CLAMPED), peuplés pendant
    /// `advance` et lus par `body_states`. Reconstruits à chaque `advance` :
    /// un corps clampé à un tick antérieur ne le reste pas. Consultée par clé,
    /// jamais itérée (R-1020).
    guard_flags: HashMap<BodyId, u32>,
    /// Instant simulé de la dernière journalisation de clamp par corps, pour le
    /// débit d'au plus une par minute (R-180). Consultée par clé (R-1020).
    clamp_journal_at: HashMap<BodyId, f64>,
    /// Horloge simulée cumulée, en s, qui cadence le débit de journalisation.
    sim_clock: f64,
    /// Nombre cumulé de clamps journalisés (R-180), après filtrage par le débit.
    clamp_journal_count: u64,
    /// Nombre cumulé d'états invalides restaurés (R-181, `E-2030`).
    invalid_state_count: u64,
    /// Nombre cumulé d'emprises incalculables (ADR-120). Atomique parce que le
    /// rapport se produit en lecture (`&self`) ; aucun accès concurrent n'existe
    /// aujourd'hui, l'ordre relâché suffit.
    bounds_unavailable: AtomicU64,
    /// Corps endormis par la gestion d'activité (R-612, R-613), avec la vitesse qu'ils
    /// avaient. L'entrée disparaît dès que le corps se réveille — de notre fait, ou de
    /// celui du solveur (un contact qui commence) : il repart alors de son état courant.
    /// Consultée par clé, jamais itérée (R-1020).
    forced_sleep: HashMap<BodyId, ForcedSleep>,
    /// Rang de création de chaque corps, l'ancienneté de R-613 : l'ordre d'itération de
    /// `rapier` réutilise les emplacements libérés et n'en donne qu'une approximation.
    /// Consultée par clé, jamais itérée (R-1020).
    births: HashMap<BodyId, u64>,
    /// Rang du prochain corps créé.
    next_birth: u64,
    /// Mouvement des corps éveillés et non fixes juste avant le sous-pas en cours : la
    /// vitesse relative d'un contact se lit là, à l'approche — lue après la résolution,
    /// elle vaudrait zéro au moment même d'un choc (R-615). Relevé à chaque sous-pas ;
    /// consulté par clé, jamais itéré (R-1020).
    pre_step: HashMap<RigidBodyHandle, PreStepMotion>,
    /// Palier 1 de la dégradation appliqué (§25.6, FM-21) : une itération de solveur et un
    /// sous-pas de moins que la configuration.
    solver_degraded: bool,
    /// Surveillance des empilements (FM-22) : départ et chemin de chaque corps éveillé sur
    /// la fenêtre d'une seconde simulée en cours. Consultée par clé, jamais itérée (R-1020).
    stack_watch: HashMap<BodyId, StackWatch>,
    /// Sous-pas écoulés dans la fenêtre FM-22 en cours.
    stack_window_substeps: u32,
    /// Corps amortis au palier 1 à la fin de la fenêtre précédente : encore instables, ils
    /// passent au palier 2. Consultée par clé (R-1020).
    stack_damped: HashSet<BodyId>,
    /// Amortissements linéaire et angulaire d'origine des corps portés à
    /// [`STACK_DAMPING`] par FM-22, rendus quand l'empilement se calme ou s'endort.
    /// Consultée par clé (R-1020).
    damping_backup: HashMap<BodyId, (f32, f32)>,
    /// Proxies des entités vanilla du tick (R-614) : entité → corps cinématique. Ordonnée :
    /// parcours déterministe (R-1020).
    proxies: BTreeMap<u32, ProxyBody>,
    /// Entité de chaque collider de proxy, pour l'identité des événements (ADR-123 §6).
    /// Consultée par clé, jamais itérée (R-1020).
    proxy_colliders: HashMap<ColliderHandle, u32>,
    /// Colliders des proxies retirés à la dernière synchronisation, gardés jusqu'à la
    /// suivante : la fin de leurs contacts, émise au pas qui suit, porte encore leur entité.
    /// Consultée par clé (R-1020).
    departed_proxy_colliders: HashMap<ColliderHandle, u32>,
}

/// Le corps cinématique d'un proxy d'entité et la forme qu'il porte.
#[derive(Debug, Clone, Copy)]
struct ProxyBody {
    body: BodyId,
    collider: ColliderHandle,
    shape: ProxyShape,
    half_extents: [f32; 3],
}

/// Départ et chemin d'un corps sur la fenêtre FM-22 en cours.
#[derive(Debug, Clone, Copy)]
struct StackWatch {
    start: Vec3,
    last: Vec3,
    path: f32,
}

/// Amortissement linéaire et angulaire du palier 1 de FM-22 : à 1/60 s par sous-pas, la
/// vitesse se divise par deux en environ 0,15 s (ADR-123 §10).
const STACK_DAMPING: f32 = 5.0;

/// Durée d'une fenêtre de surveillance FM-22, en secondes simulées (ADR-123 §10).
const STACK_WINDOW_SECONDS: f32 = 1.0;

/// Un corps instable parcourt plus de ce multiple du seuil de sommeil sur une fenêtre,
/// sans s'éloigner de plus du seuil lui-même : il s'agite sur place (ADR-123 §10).
const STACK_PATH_FACTOR: f32 = 10.0;

/// États et emprises des corps rapportés par un tick (DM-08, ADR-120).
///
/// Deux tableaux parallèles, produits par un même parcours : `bounds[i]` est
/// l'emprise du corps de `states[i]`. C'est la forme dans laquelle ils
/// traversent la frontière, l'un après l'autre dans `SIM_OUT`.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct BodyReports {
    /// États des corps.
    pub states: Vec<BodyState>,
    /// Emprises des mêmes corps, dans le même ordre.
    pub bounds: Vec<BodyBounds>,
}

impl BodyReports {
    /// Ajoute les rapports d'un autre lot à la suite de celui-ci, en gardant
    /// l'appariement.
    pub fn append(&mut self, mut other: BodyReports) {
        self.states.append(&mut other.states);
        self.bounds.append(&mut other.bounds);
    }
}

/// Compteurs cumulés d'un monde : ce qu'il n'a pas pu faire ou a dû corriger, sans jamais
/// le taire. Le pilote les additionne sur toutes les dimensions, détruites comprises
/// (R-610) : un tel compteur ne décroît jamais.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct WorldCounters {
    /// Événements perdus faute de place dans un lot (R-1011).
    pub dropped_events: u64,
    /// Clamps de vitesse journalisés, au débit d'un par corps et par minute (R-180).
    pub clamp_journal: u64,
    /// États non finis ou hors du monde restaurés (R-181, `E-2030`).
    pub invalid_states: u64,
    /// Emprises incalculables, rapportées comme la boîte nulle (ADR-120).
    pub bounds_unavailable: u64,
}

impl core::ops::Add for WorldCounters {
    type Output = Self;

    fn add(self, other: Self) -> Self {
        Self {
            dropped_events: self.dropped_events + other.dropped_events,
            clamp_journal: self.clamp_journal + other.clamp_journal,
            invalid_states: self.invalid_states + other.invalid_states,
            bounds_unavailable: self.bounds_unavailable + other.bounds_unavailable,
        }
    }
}

impl core::ops::AddAssign for WorldCounters {
    fn add_assign(&mut self, other: Self) {
        *self = *self + other;
    }
}

impl PhysicsWorld {
    /// Crée un monde vide à partir d'une configuration.
    #[must_use]
    pub fn new(config: PhysicsConfig) -> Self {
        let mut inner = RapierWorld::new();
        // `rapier` 0.35 emploie `glam` (par `glamx`), unifié à la version
        // d'AXION : le vecteur de gravité se pose sans conversion.
        inner.gravity = config.gravity;
        let params = &mut inner.integration_parameters;
        params.dt = config.fixed_dt();
        // rapier 0.35 emploie un solveur TGS-soft, distinct du couple
        // vélocité/position du CDC ; la correspondance est consignée dans
        // ADR-112. Au moins une itération, sans quoi rapier ne progresse pas.
        params.num_solver_iterations = config.velocity_iterations.max(1) as usize;
        params.num_internal_pgs_iterations = config.position_iterations.max(1) as usize;
        // Frottement de Coulomb, une contrainte par point de contact : le modèle
        // « simplifié », défaut de rapier, n'en résout qu'une par groupe de quatre et
        // ne rend aucune impulsion tangentielle, que R-615 exige (ADR-112).
        params.friction_model = rapier3d::prelude::FrictionModel::Coulomb;
        Self {
            inner,
            config,
            accumulator: 0.0,
            wind: Vec3::ZERO,
            fluid: None,
            fluid_volumes: BTreeMap::new(),
            mode: SimMode::default(),
            stage_durations: StageDurations::default(),
            aero: HashMap::new(),
            identity: HashMap::new(),
            sleep_state: HashMap::new(),
            events: Vec::new(),
            max_events_per_tick: DEFAULT_MAX_EVENTS_PER_TICK,
            contact_event_threshold: DEFAULT_CONTACT_EVENT_THRESHOLD,
            dropped_events: 0,
            velocity_limits: HashMap::new(),
            last_valid: HashMap::new(),
            guard_flags: HashMap::new(),
            clamp_journal_at: HashMap::new(),
            sim_clock: 0.0,
            clamp_journal_count: 0,
            invalid_state_count: 0,
            bounds_unavailable: AtomicU64::new(0),
            forced_sleep: HashMap::new(),
            births: HashMap::new(),
            next_birth: 0,
            pre_step: HashMap::new(),
            solver_degraded: false,
            stack_watch: HashMap::new(),
            stack_window_substeps: 0,
            stack_damped: HashSet::new(),
            damping_backup: HashMap::new(),
            proxies: BTreeMap::new(),
            proxy_colliders: HashMap::new(),
            departed_proxy_colliders: HashMap::new(),
        }
    }

    /// Ajoute un corps et son collider ; renvoie sa référence.
    ///
    /// La forme est validée **avant** la création du corps : si elle est refusée
    /// (§10.3), rien n'est inséré dans le monde.
    ///
    /// # Errors
    /// [`BodyError`] si la forme est invalide — enveloppe convexe hors de 4..256
    /// points ou dégénérée, composé vide ou de plus de 64 formes filles.
    pub fn add_body(
        &mut self,
        kind: BodyKind,
        position: Vec3,
        rotation: Quat,
        shape: Shape,
    ) -> Result<BodyId, BodyError> {
        // Un seul collider, densité neutre 1.0 (défaut rapier) : ce chemin sert la
        // collision et le mouvement, pas la masse déclarée — voir `add_assembly`.
        self.add_assembly(
            kind,
            position,
            rotation,
            &[BodyCollider {
                shape,
                density: 1.0,
                material: ContactMaterial::default(),
                translation: Vec3::ZERO,
                rotation: Quat::IDENTITY,
            }],
        )
    }

    /// Ajoute un corps composé d'un ou plusieurs colliders, chacun avec sa densité
    /// et sa pose relative ; renvoie sa référence.
    ///
    /// rapier somme les propriétés de masse des colliders : la masse, le centre de
    /// masse et l'inertie du corps découlent des **densités déclarées** (R-622,
    /// moitié « calculée depuis les densités »), un collider par matériau. Les
    /// propriétés sont recalculées dès l'insertion, sans attendre un pas.
    ///
    /// Toutes les formes sont validées **avant** toute insertion : si l'une est
    /// refusée (§10.3), rien n'entre dans le monde.
    ///
    /// # Errors
    /// [`BodyError`] à la première forme invalide — enveloppe convexe hors de
    /// 4..256 points ou dégénérée, composé vide ou de plus de 64 formes filles.
    pub fn add_assembly(
        &mut self,
        kind: BodyKind,
        position: Vec3,
        rotation: Quat,
        colliders: &[BodyCollider],
    ) -> Result<BodyId, BodyError> {
        // INV-13 (R-970) : les formes concaves du monde — ici `Heightfield` — ne
        // vivent que sur du décor non dynamique. Refus avant toute construction.
        if matches!(kind, BodyKind::Dynamic)
            && colliders
                .iter()
                .any(|collider| shape_forbids_dynamic(&collider.shape))
        {
            return Err(BodyError::HeightfieldOnDynamicBody);
        }
        // Valider toutes les formes d'abord : un corps à moitié inséré serait pire
        // qu'un refus propre.
        let mut shapes = Vec::with_capacity(colliders.len());
        for collider in colliders {
            shapes.push(shared_shape_of(&collider.shape)?);
        }

        let body_type = match kind {
            BodyKind::Static => RigidBodyType::Fixed,
            BodyKind::Kinematic => RigidBodyType::KinematicPositionBased,
            BodyKind::Dynamic => RigidBodyType::Dynamic,
        };
        let mut body = RigidBodyBuilder::new(body_type)
            .pose(RapierPose::from_parts(position, rotation))
            .build();
        // Seuils de sommeil de la fiche 5.23 ; l'angulaire diffère du défaut
        // rapier (ADR-112). Inerte sur un corps non dynamique.
        let activation = body.activation_mut();
        activation.normalized_linear_threshold = self.config.sleep_linear_threshold;
        activation.angular_threshold = self.config.sleep_angular_threshold;
        activation.time_until_sleep = self.config.sleep_time;

        let handle = self.inner.bodies.insert(body);
        for (shape, collider) in shapes.into_iter().zip(colliders) {
            let built = ColliderBuilder::new(shape)
                .density(collider.density)
                // Friction et restitution du matériau de contact (R-643) : ce que
                // rapier combine au contact — friction des roues, énergie des impacts.
                .friction(collider.material.friction)
                .restitution(collider.material.restitution)
                .position(RapierPose::from_parts(
                    collider.translation,
                    collider.rotation,
                ))
                .active_events(ActiveEvents::COLLISION_EVENTS)
                .build();
            self.inner
                .colliders
                .insert_with_parent(built, handle, &mut self.inner.bodies);
        }
        // Masse à jour dès la création : recalcul depuis les colliders, sans
        // attendre un pas de simulation (utile aux lectures immédiates de masse).
        if let Some(body) = self.inner.bodies.get_mut(handle) {
            body.recompute_mass_properties_from_colliders(&self.inner.colliders);
        }

        let id = BodyId::from_handle(handle);
        self.births.insert(id, self.next_birth);
        self.next_birth += 1;
        // Pose de départ comme premier état valide, si elle l'est : une
        // restauration R-181 dès le premier sous-pas a alors un repli fini vers
        // lequel revenir. Un corps placé hors du monde n'est pas semé — on ne
        // reviendrait pas à une pose invalide ; à défaut de repli, la
        // restauration le parque à l'origine, endormi.
        if state_is_valid(position, rotation, Vec3::ZERO, Vec3::ZERO) {
            self.last_valid.insert(
                id,
                ValidPose {
                    translation: position,
                    rotation,
                },
            );
        }
        Ok(id)
    }

    /// {@return la masse du corps, en kg, ou `None` s'il n'existe pas}
    ///
    /// Somme des masses de ses colliders (densité × volume, R-622). Un corps
    /// statique ou cinématique a une masse effective nulle du point de vue du
    /// solveur (masse « infinie ») : rapier rapporte alors `0.0`.
    #[must_use]
    pub fn body_mass(&self, id: BodyId) -> Option<f32> {
        self.inner.bodies.get(id.handle()).map(RigidBody::mass)
    }

    /// {@return le centre de masse du corps, en repère monde, ou `None`}
    ///
    /// Décalé vers les colliders les plus denses (R-622). Pour un corps à l'origine
    /// sans rotation, le repère monde coïncide avec le repère local.
    #[must_use]
    pub fn body_center_of_mass(&self, id: BodyId) -> Option<Vec3> {
        self.inner
            .bodies
            .get(id.handle())
            .map(|body| body.mass_properties().world_com)
    }

    /// Avance la simulation d'au plus `max_substeps` sous-pas de `fixed_dt`,
    /// selon le temps réel écoulé. Renvoie le nombre de sous-pas exécutés.
    ///
    /// L'accumulateur est **clampé** à `max_substeps · fixed_dt` avant la
    /// boucle : au-delà, le retard est abandonné plutôt que rattrapé, ce qui
    /// évite la spirale où chaque tick prend plus de retard qu'il n'en comble
    /// (R-990).
    pub fn advance(&mut self, frame_dt: f32) -> u32 {
        self.stage_durations.reset();
        // R-662 (INV-17) : le client n'exécute pas l'intégration physique autoritaire — il
        // reçoit les états du serveur et interpole. Aucun sous-pas de ce côté.
        if self.mode == SimMode::Client {
            return 0;
        }
        let dt = self.config.fixed_dt();
        let ceiling = dt * self.max_substeps() as f32;
        self.accumulator = (self.accumulator + frame_dt.max(0.0)).min(ceiling);
        // Un reliquat à un millième de sous-pas près vaut un sous-pas entier : en f32, un tick de
        // 1/20 s ne se divise pas exactement en trois sous-pas de 1/60 s, et le troisième se
        // perdait au premier tick — la simulation restait ensuite d'un sous-pas en retard.
        let full_step = dt * (1.0 - SUBSTEP_TOLERANCE);
        // Les drapeaux de garde-fou ne valent que pour le tick courant : un corps
        // clampé à un tick antérieur ne l'est plus tant qu'il ne dépasse pas de
        // nouveau. On repart donc de zéro à chaque `advance`, même à 0 sous-pas.
        self.guard_flags.clear();
        let mut substeps = 0;
        while self.accumulator >= full_step {
            self.sim_clock += f64::from(dt);

            // Étape 4 (C-40, R-660) : intégration physique — forces, intégration Rapier,
            // puis garde-fous R-180/R-181 avant la récolte, pour lire un état sain.
            let integration_start = Instant::now();
            self.apply_aero_forces();
            self.record_pre_step_motion();
            let collector = ContactCollector::default();
            self.inner.step_with_events(&(), &collector);
            self.enforce_body_guardrails();
            self.stage_durations
                .add(Stage::Integration, elapsed_nanos(integration_start));

            // Étape 6 (C-40, R-660) : collecte des contacts et des événements.
            let contacts_start = Instant::now();
            self.collect_contact_events(&collector);
            self.collect_contact_impulses();
            self.collect_sleep_events();
            self.watch_stacking();
            self.stage_durations
                .add(Stage::Contacts, elapsed_nanos(contacts_start));

            self.accumulator = (self.accumulator - dt).max(0.0);
            substeps += 1;
        }

        // Étapes 7 à 12 (C-40, R-660) : la **chaîne de dommage** s'exécute une seule fois
        // par tick, après le dernier sous-pas, pour rendre son coût indépendant du nombre
        // de sous-pas. Réservée aux composants C-41..C-45 (M6) : aucun travail à ce jalon,
        // mais son créneau est ici, à sa place normative.

        substeps
    }

    /// Fait respecter les garde-fous par corps après un sous-pas (R-180, R-181).
    ///
    /// Deux sources d'état invalide (R-181, `E-2030`), traitées dans cet ordre :
    ///
    /// 1. **Non fini** (NaN, infini) — `rapier` le détecte à ses points de
    ///    contrôle, ramène la pose au dernier état valide, annule vitesses et
    ///    forces, et **désactive** le corps (sa quarantaine, qui contient aussi
    ///    la propagation par les contacts et la CCD). On lit ce rapport, on
    ///    convertit la désactivation en **sommeil forcé** — fidèle à R-181 et au
    ///    principe « endort, jamais ne supprime » (R-612) — et on compte l'E-2030.
    /// 2. **Fini mais hors du monde** (au-delà de [`WORLD_COORD_LIMIT`]) — que la
    ///    quarantaine de `rapier` ne couvre pas, une telle valeur étant finie. On
    ///    le détecte soi-même, on restaure le dernier état valide connu et on
    ///    endort.
    ///
    /// Puis R-180 : les vitesses d'un corps sain dépassant leurs bornes sont
    /// clampées, le drapeau `CLAMPED` posé et le fait journalisé au plus une fois
    /// par corps et par minute ; sa pose devient le dernier état valide.
    ///
    /// Comme [`apply_aero_forces`](Self::apply_aero_forces), on relève d'abord
    /// l'état dans l'ordre déterministe de `rapier`, puis on agit : l'emprunt en
    /// lecture des corps est clos avant les mutations.
    fn enforce_body_guardrails(&mut self) {
        // (1) Quarantaine de `rapier` : le non-fini, déjà ramené au dernier état
        // valide et désactivé. On réveille pour endormir — un corps endormi
        // reste dans la simulation et pourra se réveiller, un corps désactivé en
        // sort. Le rapport est propre à ce monde et vidé à chaque pas.
        let quarantined: Vec<RigidBodyHandle> = self.inner.quarantine().bodies().to_vec();
        for handle in quarantined {
            if let Some(body) = self.inner.bodies.get_mut(handle) {
                body.set_enabled(true);
                body.sleep();
            }
            self.signal_recovered(BodyId::from_handle(handle));
        }

        // (2) Bornes de vitesse (R-180) et hors-monde fini (R-181).
        let snapshot: Vec<(BodyId, RigidBodyHandle, Vec3, Quat, Vec3, Vec3)> = self
            .inner
            .bodies
            .iter()
            .filter(|(_, body)| body.is_dynamic())
            .map(|(handle, body)| {
                let pose = body.position();
                (
                    BodyId::from_handle(handle),
                    handle,
                    pose.translation,
                    pose.rotation,
                    body.linvel(),
                    body.angvel(),
                )
            })
            .collect();

        for (id, handle, translation, rotation, linvel, angvel) in snapshot {
            if !state_is_valid(translation, rotation, linvel, angvel) {
                self.restore_last_valid(id, handle);
                continue;
            }
            self.clamp_velocities(id, handle, linvel, angvel);
            // Cette pose finie devient le repli d'une éventuelle restauration.
            self.last_valid.insert(
                id,
                ValidPose {
                    translation,
                    rotation,
                },
            );
        }
    }

    /// Restaure un corps à son dernier état valide connu et l'endort (R-181,
    /// `E-2030`), pour une position finie mais hors du monde.
    fn restore_last_valid(&mut self, id: BodyId, handle: RigidBodyHandle) {
        // Un corps passé par `add_body` a toujours un repli semé ; à défaut,
        // l'origine et l'identité, finies par construction.
        let valid = self.last_valid.get(&id).copied().unwrap_or(ValidPose {
            translation: Vec3::ZERO,
            rotation: Quat::IDENTITY,
        });
        if let Some(body) = self.inner.bodies.get_mut(handle) {
            body.set_position(
                RapierPose::from_parts(valid.translation, valid.rotation),
                false,
            );
            body.set_linvel(Vec3::ZERO, false);
            body.set_angvel(Vec3::ZERO, false);
            // Sommeil forcé : un corps parti hors du monde ne poursuit pas sa
            // course ce tick.
            body.sleep();
        }
        self.signal_recovered(id);
    }

    /// Compte une restauration (R-181, `E-2030`) et la rend visible : un `RECOVERED`
    /// portant le corps et le code 2030 (ADR-123 §8), que Java journalise pour
    /// « signaler le scénario ».
    fn signal_recovered(&mut self, id: BodyId) {
        self.invalid_state_count += 1;
        self.push_single_body_event(
            id,
            event_kind::RECOVERED,
            event_data::RECOVERED_INVALID_STATE,
        );
    }

    /// Clampe les vitesses d'un corps à leurs bornes (R-180) ; pose le drapeau
    /// `CLAMPED` et journalise (débit d'une par minute) si une borne a mordu.
    fn clamp_velocities(
        &mut self,
        id: BodyId,
        handle: RigidBodyHandle,
        linvel: Vec3,
        angvel: Vec3,
    ) {
        let (max_linear, max_angular) = self
            .velocity_limits
            .get(&id)
            .copied()
            .unwrap_or((DEFAULT_MAX_LINEAR_VEL, DEFAULT_MAX_ANGULAR_VEL));
        let mut clamped = false;
        if let Some(body) = self.inner.bodies.get_mut(handle) {
            let linear_speed = linvel.length();
            if linear_speed > max_linear && linear_speed > 0.0 {
                body.set_linvel(linvel * (max_linear / linear_speed), false);
                clamped = true;
            }
            let angular_speed = angvel.length();
            if angular_speed > max_angular && angular_speed > 0.0 {
                body.set_angvel(angvel * (max_angular / angular_speed), false);
                clamped = true;
            }
        }
        if clamped {
            *self.guard_flags.entry(id).or_default() |= body_state_flags::CLAMPED;
            self.journalise_clamp(id);
        }
    }

    /// Journalise un clamp au plus une fois par corps et par minute simulée
    /// (R-180). Sans framework de log dans ce crate pur, la journalisation prend
    /// la forme d'un compteur, à l'image de `dropped_events`, et d'un événement
    /// `CLAMPED` que Java consigne. Le débit reste testable.
    fn journalise_clamp(&mut self, id: BodyId) {
        let now = self.sim_clock;
        let due = match self.clamp_journal_at.get(&id) {
            Some(&last) => now - last >= CLAMP_JOURNAL_PERIOD,
            None => true,
        };
        if due {
            self.clamp_journal_at.insert(id, now);
            self.clamp_journal_count += 1;
            // Au débit du journal, un `CLAMPED` de code 1 porte le fait jusqu'à Java
            // (ADR-123 §11) ; le drapeau `CLAMPED` de l'état, lui, vaut à chaque tick.
            self.push_single_body_event(id, event_kind::CLAMPED, event_data::CLAMPED_VELOCITY);
        }
    }

    /// Construit les événements de contact (§10.7, R-615) du dernier sous-pas.
    ///
    /// CONTACT_START à l'apparition d'un contact (données complètes de la paire),
    /// CONTACT_END à sa disparition (identités seules, plus de contact). Les
    /// paires de capteurs sont ignorées ici — leurs événements SENSOR arrivent
    /// avec les capteurs (tranche suivante). L'ordre suit l'émission déterministe
    /// de `rapier` (R-1020).
    fn collect_contact_events(&mut self, collector: &ContactCollector) {
        for (a, b, sensor) in collector.take_started() {
            if sensor {
                let event = self.build_identity_event(a, b, event_kind::SENSOR_ENTER);
                self.push_event(event);
            } else if let Some(event) = self.build_contact_event(a, b, event_kind::CONTACT_START) {
                self.push_event(event);
            }
        }
        for (a, b, sensor) in collector.take_stopped() {
            let kind = if sensor {
                event_kind::SENSOR_EXIT
            } else {
                event_kind::CONTACT_END
            };
            let event = self.build_identity_event(a, b, kind);
            self.push_event(event);
        }
    }

    /// Construit un événement de contact peuplé des données R-615 de la paire,
    /// ou `None` si la paire n'a plus de contact profond exploitable. Sert pour
    /// CONTACT_START comme pour CONTACT_IMPULSE.
    fn build_contact_event(
        &self,
        a: ColliderHandle,
        b: ColliderHandle,
        kind: u32,
    ) -> Option<PhysicsEvent> {
        let pair = self.inner.contact_pair(a, b)?;
        let (manifold, contact) = pair.find_deepest_contact()?;
        let normal = manifold.data.normal;
        let world_point = self
            .inner
            .colliders
            .get(a)?
            .position()
            .transform_point(contact.local_p1);

        let (identity_a, body_a) = self.contact_body(a);
        let (identity_b, body_b) = self.contact_body(b);

        // Vitesses d'avant la résolution du sous-pas : celles de l'approche (R-615).
        let velocity_a = self.pre_step_velocity(a, world_point);
        let velocity_b = self.pre_step_velocity(b, world_point);
        // Vitesse relative projetée sur la normale : la vitesse de rapprochement.
        let relative_velocity = (velocity_a - velocity_b).dot(normal);

        let mut event = PhysicsEvent {
            kind,
            assembly_a: identity_a.assembly,
            assembly_b: identity_b.assembly,
            node_a: identity_a.node,
            node_b: identity_b.node,
            point: world_point.to_array(),
            normal: normal.to_array(),
            impulse: contact.data.impulse,
            tangent_impulse: contact.data.tangent_impulse.norm(),
            relative_velocity,
            effective_mass: effective_mass_at(world_point, normal, body_a, body_b),
            material_a: identity_a.material,
            material_b: identity_b.material,
            data: 0,
        };
        self.attribute_entity(&mut event, a, b);
        Some(event)
    }

    /// Émet un CONTACT_IMPULSE (§10.7) par paire de contact active dont
    /// l'impulsion dépasse le seuil (R-1012), une fois par sous-pas.
    ///
    /// Un seul événement par paire (le contact le plus profond) : c'est
    /// l'agrégation par paire de R-1011, impulsion maximale conservée. Le seuil
    /// écarte les contacts légers de la remontée vers Java — et, au passage, le
    /// bruit des contacts au repos, dont l'impulsion par pas (`mg·dt`) reste
    /// faible. La voie « sous seuil mais vers C-41 » (usure) attend C-41.
    fn collect_contact_impulses(&mut self) {
        let pairs: Vec<(ColliderHandle, ColliderHandle)> = self
            .inner
            .contact_pairs()
            .map(|pair| (pair.collider1, pair.collider2))
            .collect();
        let threshold = self.contact_event_threshold;
        for (a, b) in pairs {
            if let Some(event) = self.build_contact_event(a, b, event_kind::CONTACT_IMPULSE) {
                if event.impulse >= threshold {
                    self.push_event(event);
                }
            }
        }
    }

    /// Construit un événement aux identités seules, les champs de contact étant
    /// nuls : CONTACT_END (plus de contact) ou SENSOR_ENTER/EXIT (un capteur ne
    /// résout aucun contact, il ne rapporte qu'un chevauchement).
    fn build_identity_event(
        &self,
        a: ColliderHandle,
        b: ColliderHandle,
        kind: u32,
    ) -> PhysicsEvent {
        let (identity_a, _) = self.contact_body(a);
        let (identity_b, _) = self.contact_body(b);
        let mut event = PhysicsEvent {
            kind,
            assembly_a: identity_a.assembly,
            assembly_b: identity_b.assembly,
            node_a: identity_a.node,
            node_b: identity_b.node,
            point: [0.0; 3],
            normal: [0.0; 3],
            impulse: 0.0,
            tangent_impulse: 0.0,
            relative_velocity: 0.0,
            effective_mass: 0.0,
            material_a: identity_a.material,
            material_b: identity_b.material,
            data: 0,
        };
        self.attribute_entity(&mut event, a, b);
        event
    }

    /// Identité d'une entité vanilla dans un événement (ADR-123 §6). Si l'un des corps est
    /// le proxy d'une entité, l'autre — l'assembly — passe en `a`, la normale suivant ; en
    /// `b`, `{ index: entité, generation: 0 }`, node et matériau nuls ; et `data` porte le
    /// bit `CONTACT_OTHER_ENTITY`. La génération 0 dit « pas une assembly » (R-111) : un
    /// lecteur qui ne teste que `is_absent()` n'y voit toujours pas d'assembly.
    fn attribute_entity(&self, event: &mut PhysicsEvent, a: ColliderHandle, b: ColliderHandle) {
        let entity = match (self.proxy_entity(a), self.proxy_entity(b)) {
            (None, Some(entity)) => entity,
            (Some(entity), None) => {
                core::mem::swap(&mut event.assembly_a, &mut event.assembly_b);
                core::mem::swap(&mut event.node_a, &mut event.node_b);
                core::mem::swap(&mut event.material_a, &mut event.material_b);
                if event.normal != [0.0; 3] {
                    event.normal = event.normal.map(|component| -component);
                }
                entity
            }
            _ => return,
        };
        event.assembly_b = Handle::new(entity, 0);
        event.node_b = 0;
        event.material_b = 0;
        event.data |= event_data::CONTACT_OTHER_ENTITY;
    }

    /// Entité dont `collider` est le proxy, présent ou retiré à la dernière synchronisation.
    fn proxy_entity(&self, collider: ColliderHandle) -> Option<u32> {
        self.proxy_colliders
            .get(&collider)
            .or_else(|| self.departed_proxy_colliders.get(&collider))
            .copied()
    }

    /// Remplace les proxies d'entités du monde par ceux du tick (R-614, ADR-123 §5) : une
    /// entité déclarée a son corps cinématique, posé en son centre et lancé à sa vitesse ;
    /// une entité qui ne l'est plus perd le sien. Un proxy inutilisable est ignoré ; une
    /// entité déclarée deux fois ne garde que sa première déclaration. Un proxy qui change
    /// de forme la change en place : ses contacts en cours ne s'interrompent pas.
    pub(crate) fn sync_entity_proxies(&mut self, proxies: &[EntityProxy], origin: &FloatingOrigin) {
        self.departed_proxy_colliders.clear();
        let mut declared = BTreeSet::new();
        for proxy in proxies {
            let center = origin.to_local(proxy.center);
            if !proxy.is_valid() || !center.is_finite() || !declared.insert(proxy.entity) {
                continue;
            }
            match self.proxies.get(&proxy.entity).copied() {
                Some(existing) => self.update_proxy(existing, proxy, center),
                None => self.create_proxy(proxy, center),
            }
        }
        let stale: Vec<u32> = self
            .proxies
            .keys()
            .copied()
            .filter(|entity| !declared.contains(entity))
            .collect();
        for entity in stale {
            if let Some(proxy) = self.proxies.remove(&entity) {
                self.proxy_colliders.remove(&proxy.collider);
                self.departed_proxy_colliders.insert(proxy.collider, entity);
                self.inner.remove_body(proxy.body.handle());
            }
        }
    }

    /// Crée le corps cinématique d'un proxy : à vitesse imposée, membre du groupe
    /// `entity_proxy`, ne heurtant que le groupe `assembly`.
    fn create_proxy(&mut self, proxy: &EntityProxy, center: Vec3) {
        let Ok(shape) = shared_shape_of(&proxy.collision_shape()) else {
            return;
        };
        let body = RigidBodyBuilder::kinematic_velocity_based()
            .translation(center)
            .linvel(proxy.velocity)
            .build();
        let handle = self.inner.bodies.insert(body);
        let groups = CollisionGroups::from_indices(
            &[ReservedGroup::EntityProxy.bit()],
            &[ReservedGroup::Assembly.bit()],
        );
        let collider = ColliderBuilder::new(shape)
            .collision_groups(groups.to_rapier())
            .active_events(ActiveEvents::COLLISION_EVENTS)
            .build();
        let collider =
            self.inner
                .colliders
                .insert_with_parent(collider, handle, &mut self.inner.bodies);
        self.proxy_colliders.insert(collider, proxy.entity);
        self.proxies.insert(
            proxy.entity,
            ProxyBody {
                body: BodyId::from_handle(handle),
                collider,
                shape: proxy.shape,
                half_extents: proxy.half_extents,
            },
        );
    }

    /// Repose un proxy existant pour le tick : centre, vitesse, et forme si elle a changé.
    fn update_proxy(&mut self, existing: ProxyBody, proxy: &EntityProxy, center: Vec3) {
        if existing.shape != proxy.shape || existing.half_extents != proxy.half_extents {
            let Ok(shape) = shared_shape_of(&proxy.collision_shape()) else {
                return;
            };
            if let Some(collider) = self.inner.colliders.get_mut(existing.collider) {
                collider.set_shape(shape);
            }
            if let Some(entry) = self.proxies.get_mut(&proxy.entity) {
                entry.shape = proxy.shape;
                entry.half_extents = proxy.half_extents;
            }
        }
        if let Some(body) = self.inner.bodies.get_mut(existing.body.handle()) {
            body.set_position(RapierPose::from_translation(center), true);
            body.set_linvel(proxy.velocity, true);
        }
    }

    /// Nombre de proxies d'entités du monde (R-614).
    #[must_use]
    pub fn entity_proxy_count(&self) -> usize {
        self.proxies.len()
    }

    /// Relève le mouvement des corps éveillés et non fixes avant le sous-pas (R-615). Un
    /// corps fixe ou endormi n'y figure pas : sa vitesse d'approche est nulle.
    fn record_pre_step_motion(&mut self) {
        self.pre_step.clear();
        for (handle, body) in self.inner.bodies.iter() {
            if body.is_fixed() || body.is_sleeping() {
                continue;
            }
            self.pre_step.insert(
                handle,
                PreStepMotion {
                    linvel: body.linvel(),
                    angvel: body.angvel(),
                    center_of_mass: body.mass_properties().world_com,
                },
            );
        }
    }

    /// Vitesse, avant le sous-pas, du point `point` du corps qui porte `collider` ; nulle
    /// pour un corps fixe, endormi, ou un collider sans corps.
    fn pre_step_velocity(&self, collider: ColliderHandle, point: Vec3) -> Vec3 {
        self.inner
            .colliders
            .get(collider)
            .and_then(Collider::parent)
            .and_then(|parent| self.pre_step.get(&parent))
            .map_or(Vec3::ZERO, |motion| motion.velocity_at(point))
    }

    /// Rend l'identité et le corps parent d'un collider.
    fn contact_body(&self, collider: ColliderHandle) -> (BodyIdentity, Option<&RigidBody>) {
        let parent = self
            .inner
            .colliders
            .get(collider)
            .and_then(|collider| collider.parent());
        let body = parent.and_then(|handle| self.inner.bodies.get(handle));
        let identity = parent
            .map(|handle| {
                self.identity
                    .get(&BodyId::from_handle(handle))
                    .copied()
                    .unwrap_or_default()
            })
            .unwrap_or_default();
        (identity, body)
    }

    /// Émet les événements SLEEP/WAKE (§10.7) des corps qui ont changé d'état de
    /// sommeil au dernier sous-pas, dans l'ordre déterministe de `rapier`.
    fn collect_sleep_events(&mut self) {
        let current: Vec<(BodyId, bool)> = self
            .inner
            .bodies
            .iter()
            .filter(|(_, body)| body.is_dynamic())
            .map(|(handle, body)| (BodyId::from_handle(handle), body.is_sleeping()))
            .collect();
        for (id, sleeping) in current {
            // `insert` rend l'état précédent : une transition n'existe que s'il
            // était connu et différent. Première apparition d'un corps = pas
            // d'événement.
            if let Some(previous) = self.sleep_state.insert(id, sleeping) {
                if previous != sleeping {
                    let kind = if sleeping {
                        event_kind::SLEEP
                    } else {
                        // Réveillé par le solveur, un corps endormi par la gestion
                        // d'activité repart de son état courant : la vitesse retenue
                        // ne vaut plus.
                        self.forced_sleep.remove(&id);
                        event_kind::WAKE
                    };
                    self.push_single_body_event(id, kind, 0);
                }
            }
        }
    }

    /// Construit et met en file un événement portant sur un seul corps (§10.7) ; `data`
    /// suit le genre (ADR-123 §11).
    fn push_single_body_event(&mut self, id: BodyId, kind: u32, data: u32) {
        let identity = self.identity.get(&id).copied().unwrap_or_default();
        let event = PhysicsEvent {
            kind,
            assembly_a: identity.assembly,
            assembly_b: Handle::ABSENT,
            node_a: identity.node,
            node_b: 0,
            point: [0.0; 3],
            normal: [0.0; 3],
            impulse: 0.0,
            tangent_impulse: 0.0,
            relative_velocity: 0.0,
            effective_mass: 0.0,
            material_a: identity.material,
            material_b: 0,
            data,
        };
        self.push_event(event);
    }

    /// Met un événement en file, ou compte une perte si le lot est plein
    /// (R-1011, aucune perte silencieuse).
    fn push_event(&mut self, event: PhysicsEvent) {
        if self.events.len() >= self.max_events_per_tick {
            self.dropped_events += 1;
        } else {
            self.events.push(event);
        }
    }

    /// Surveille les empilements après un sous-pas (FM-22, ADR-123 §10) : cumule le chemin
    /// de chaque corps dynamique éveillé et décide à la fin de chaque fenêtre d'une seconde
    /// simulée. Ne lit que l'état simulé et compte les sous-pas : même scène, mêmes
    /// décisions (R-1020).
    fn watch_stacking(&mut self) {
        for (handle, body) in self.inner.bodies.iter() {
            if !body.is_dynamic() || body.is_sleeping() {
                continue;
            }
            let position = body.translation();
            let watch = self
                .stack_watch
                .entry(BodyId::from_handle(handle))
                .or_insert(StackWatch {
                    start: position,
                    last: position,
                    path: 0.0,
                });
            watch.path += (position - watch.last).length();
            watch.last = position;
        }
        self.stack_window_substeps += 1;
        let window = (STACK_WINDOW_SECONDS / self.config.fixed_dt()).round() as u32;
        if self.stack_window_substeps >= window.max(1) {
            self.stack_window_substeps = 0;
            self.settle_stacking();
        }
    }

    /// Fin d'une fenêtre FM-22. Un corps éveillé en contact avec un autre corps dynamique,
    /// qui a parcouru plus de dix fois le seuil de sommeil sans s'éloigner de plus du seuil
    /// lui-même, s'agite sur place :
    ///
    /// - **palier 1** : lui et ses voisins dynamiques sont amortis pour la fenêtre suivante ;
    /// - **palier 2** : encore instable après une fenêtre amortie, lui et ses voisins sont
    ///   endormis, leurs amortissements d'origine rendus ;
    /// - un corps amorti dont l'empilement s'est calmé retrouve son amortissement.
    ///
    /// Chaque palier émet un `CLAMPED` de code 3 pour le corps instable (ADR-123 §11).
    fn settle_stacking(&mut self) {
        let threshold = self.config.sleep_linear_threshold * STACK_WINDOW_SECONDS;
        let neighbors = self.dynamic_neighbors();
        let watches = std::mem::take(&mut self.stack_watch);
        let previously_damped = std::mem::take(&mut self.stack_damped);

        let mut unstable = Vec::new();
        let mut to_damp = Vec::new();
        let mut to_sleep = Vec::new();
        for (handle, body) in self.inner.bodies.iter() {
            if !body.is_dynamic() || body.is_sleeping() {
                continue;
            }
            let id = BodyId::from_handle(handle);
            let (Some(watch), Some(around)) = (watches.get(&id), neighbors.get(&id)) else {
                continue;
            };
            let wandered = (watch.last - watch.start).length();
            if wandered >= threshold || watch.path <= STACK_PATH_FACTOR * threshold {
                continue;
            }
            unstable.push(id);
            let group = std::iter::once(id).chain(around.iter().copied());
            if previously_damped.contains(&id) {
                to_sleep.extend(group);
            } else {
                self.stack_damped.insert(id);
                to_damp.extend(group);
            }
        }

        // Palier 2 d'abord : un corps qui s'endort ne reste ni amorti ni surveillé.
        let sleeping: HashSet<BodyId> = to_sleep.iter().copied().collect();
        for &id in &to_sleep {
            self.stack_damped.remove(&id);
            if let Some(body) = self.inner.bodies.get_mut(id.handle()) {
                if let Some((linear, angular)) = self.damping_backup.remove(&id) {
                    body.set_linear_damping(linear);
                    body.set_angular_damping(angular);
                }
                body.sleep();
            }
        }
        let damped: HashSet<BodyId> = to_damp
            .into_iter()
            .filter(|id| !sleeping.contains(id))
            .collect();
        // Un parcours dans l'ordre de `rapier` amortit le groupe et rend son amortissement
        // à qui n'en fait plus partie.
        for (handle, body) in self.inner.bodies.iter_mut() {
            let id = BodyId::from_handle(handle);
            if damped.contains(&id) {
                self.damping_backup
                    .entry(id)
                    .or_insert((body.linear_damping(), body.angular_damping()));
                body.set_linear_damping(STACK_DAMPING);
                body.set_angular_damping(STACK_DAMPING);
            } else if let Some((linear, angular)) = self.damping_backup.remove(&id) {
                body.set_linear_damping(linear);
                body.set_angular_damping(angular);
            }
        }
        for id in unstable {
            self.push_single_body_event(id, event_kind::CLAMPED, event_data::CLAMPED_STACKING);
        }
    }

    /// Voisins dynamiques de chaque corps dynamique, d'après les paires en contact actif,
    /// dans l'ordre déterministe des paires de `rapier` (R-1020).
    fn dynamic_neighbors(&self) -> HashMap<BodyId, Vec<BodyId>> {
        let parent = |collider: ColliderHandle| {
            self.inner
                .colliders
                .get(collider)
                .and_then(Collider::parent)
        };
        let dynamic = |handle: RigidBodyHandle| {
            self.inner
                .bodies
                .get(handle)
                .is_some_and(RigidBody::is_dynamic)
        };
        let mut neighbors: HashMap<BodyId, Vec<BodyId>> = HashMap::new();
        for pair in self.inner.contact_pairs() {
            if !pair.has_any_active_contact() {
                continue;
            }
            let (Some(a), Some(b)) = (parent(pair.collider1), parent(pair.collider2)) else {
                continue;
            };
            if a != b && dynamic(a) && dynamic(b) {
                let (a, b) = (BodyId::from_handle(a), BodyId::from_handle(b));
                neighbors.entry(a).or_default().push(b);
                neighbors.entry(b).or_default().push(a);
            }
        }
        neighbors
    }

    /// Applique les forces environnementales de chaque corps avant un sous-pas
    /// (§10.6). En tranche 2d : la traînée, relative au vent.
    ///
    /// Les forces `rapier` persistent d'un pas à l'autre ; on les remet à zéro
    /// puis on les repose à chaque sous-pas, à partir de la vitesse courante. La
    /// gravité, appliquée par `rapier`, n'est pas touchée. L'ordre de parcours
    /// est celui, déterministe, des corps `rapier` (R-1020).
    fn apply_aero_forces(&mut self) {
        let has_fluid = self.fluid.is_some() || !self.fluid_volumes.is_empty();
        if self.aero.is_empty() && !has_fluid {
            return;
        }
        let wind = self.wind;
        let flat_fluid = self.fluid;
        let gravity = self.inner.gravity;
        // Références partagées prises avant l'itération : `bodies.iter()` n'emprunte
        // alors que `inner.bodies`, laissant lire `colliders`, `aero`, les volumes.
        let colliders = &self.inner.colliders;
        let aero = &self.aero;
        let fluid_volumes = &self.fluid_volumes;
        // Les solides fixes — les tuiles du monde —, pour les coins d'AABB qui y débordent.
        let solids = self.query_pipeline(RapierQueryFilter::only_fixed().exclude_sensors());
        // Passe 1, en lecture : calcule le plan de chaque corps dans l'ordre
        // déterministe de `rapier`, en consultant son profil par clé. Avec un
        // fluide, **tout** corps dynamique est planifié — même hors de l'eau —
        // pour que sa poussée précédente soit remise à zéro à la sortie.
        let plans: Vec<BodyForcePlan> = self
            .inner
            .bodies
            .iter()
            .filter(|(_, body)| body.is_dynamic() && !body.is_sleeping())
            .filter_map(|(handle, body)| {
                let profile = aero.get(&BodyId::from_handle(handle));
                if profile.is_none() && !has_fluid {
                    return None;
                }
                let mut central = Vec3::ZERO;
                let mut at_points = Vec::new();
                if let Some(profile) = profile {
                    central = drag_force(body, wind, profile.drag_cd_a, AIR_DENSITY);
                    let pose = body.position();
                    at_points.extend(
                        profile
                            .lift_surfaces
                            .iter()
                            .filter_map(|surface| lift_force(body, pose, wind, surface)),
                    );
                }
                if has_fluid {
                    if let Some(im) = sample_immersion(
                        body,
                        colliders,
                        |p| fluid_density_at(fluid_volumes, &flat_fluid, p),
                        |p| solids.intersect_point(p).next().is_some(),
                    ) {
                        // Poussée d'Archimède `−g·ρ·V_immergé` au centre de poussée.
                        let buoyancy = -gravity * (im.density * im.fraction * im.volume);
                        at_points.push((buoyancy, im.centroid));
                        // Traînée du fluide sur la part immergée, qui s'ajoute à celle
                        // de l'air : ρ_fluide ≫ ρ_air, la part d'air en trop y est
                        // négligeable (modèle approximatif assumé, §10.6).
                        if let Some(profile) = profile {
                            central +=
                                drag_force(body, wind, profile.drag_cd_a * im.fraction, im.density);
                        }
                    }
                }
                Some(BodyForcePlan {
                    handle,
                    central,
                    at_points,
                })
            })
            .collect();
        // Passe 2, en écriture : remise à zéro une seule fois par corps, puis
        // repose de toutes ses forces (les forces `rapier` s'accumulent).
        for plan in plans {
            if let Some(body) = self.inner.bodies.get_mut(plan.handle) {
                body.reset_forces(false);
                body.add_force(plan.central, false);
                for (force, point) in plan.at_points {
                    body.add_force_at_point(force, point, false);
                }
            }
        }
    }

    /// Pose courante d'un corps, ou `None` s'il n'existe plus.
    #[must_use]
    pub fn pose(&self, id: BodyId) -> Option<Pose> {
        self.inner.bodies.get(id.handle()).map(|body| {
            // `rapier` rend une pose `glam` : translation et rotation se lisent
            // directement dans les types d'AXION (mêmes types, R-460).
            let pose = body.position();
            Pose {
                translation: pose.translation,
                rotation: pose.rotation,
            }
        })
    }

    /// Impose la pose d'un corps cinématique pour le prochain pas (§10.2).
    ///
    /// Sans effet si le corps n'existe pas. Sur un corps non cinématique,
    /// `rapier` ignore l'ordre.
    pub fn set_kinematic_pose(&mut self, id: BodyId, position: Vec3, rotation: Quat) {
        if let Some(body) = self.inner.bodies.get_mut(id.handle()) {
            body.set_next_kinematic_position(RapierPose::from_parts(position, rotation));
        }
    }

    /// Applique ou lève le palier 1 de la dégradation (§25.6, FM-21) : une itération de
    /// solveur et un sous-pas de moins que la configuration. Le reste de la configuration
    /// du monde est inchangé ; lever le palier rend exactement les valeurs configurées.
    pub fn set_degraded_solver(&mut self, degraded: bool) {
        self.solver_degraded = degraded;
        self.inner.integration_parameters.num_solver_iterations = self.solver_iterations() as usize;
    }

    /// Itérations du solveur appliquées : `physics.velocity_iterations` (au moins 1), une
    /// de moins au palier 1 de la dégradation sans descendre sous 2 (§25.6).
    #[must_use]
    pub fn solver_iterations(&self) -> u32 {
        let configured = self.config.velocity_iterations.max(1);
        if self.solver_degraded {
            configured.saturating_sub(1).max(configured.min(2))
        } else {
            configured
        }
    }

    /// Sous-pas maximaux par tick appliqués : `sim.max_substeps`, un de moins au palier 1
    /// de la dégradation sans descendre sous 1 (§25.6). Le temps que l'accumulateur ne peut
    /// plus rattraper est abandonné, jamais rejoué (R-990).
    #[must_use]
    pub fn max_substeps(&self) -> u32 {
        let configured = self.config.max_substeps();
        if self.solver_degraded {
            configured.saturating_sub(1).max(1)
        } else {
            configured
        }
    }

    /// Fixe la gravité de la dimension (§10.6, R-611). Prend effet au prochain
    /// pas.
    pub fn set_gravity(&mut self, gravity: Vec3) {
        self.inner.gravity = gravity;
    }

    /// Applique une impulsion à un corps (§10.5, API), instantanée.
    ///
    /// Sans effet si le corps n'existe pas ou n'est pas dynamique. Au point
    /// `local_point` (coordonnées locales du corps) si `at_point`, sinon au
    /// centre de masse ; réveille le corps.
    pub fn apply_impulse(&mut self, id: BodyId, impulse: Vec3, local_point: Vec3, at_point: bool) {
        if let Some(body) = self.inner.bodies.get_mut(id.handle()) {
            if at_point {
                let world_point = body.position().transform_point(local_point);
                body.apply_impulse_at_point(impulse, world_point, true);
            } else {
                body.apply_impulse(impulse, true);
            }
        }
    }

    /// Attribue ses groupes de collision à un corps (§10.4, R-980).
    ///
    /// Sans effet si le corps n'existe pas. Le corps porte un seul collider ; le
    /// filtre s'y applique. À défaut d'appel, un corps est membre de tous les
    /// groupes et entre en collision avec tous ([`CollisionGroups::ALL`]).
    pub fn set_collision_groups(&mut self, id: BodyId, groups: CollisionGroups) {
        // On copie les handles avant de muter : `bodies` et `colliders` sont deux
        // ensembles distincts, mais l'emprunt du corps doit finir avant l'accès
        // mutable aux colliders.
        let handles: Vec<_> = match self.inner.bodies.get(id.handle()) {
            Some(body) => body.colliders().to_vec(),
            None => return,
        };
        let interaction = groups.to_rapier();
        for handle in handles {
            if let Some(collider) = self.inner.colliders.get_mut(handle) {
                collider.set_collision_groups(interaction);
            }
        }
    }

    /// Active ou désactive la détection continue de collision (CCD) d'un corps.
    ///
    /// Sans effet si le corps n'existe pas. Une fois activée, `rapier` déclenche
    /// de lui-même les sous-pas CCD quand le corps se déplace assez vite pour
    /// traverser une forme fine en un pas — l'« automatique » de la fiche (le
    /// seuil interne de `rapier` joue le rôle de `|v|·dt > 0.5·min_dim`).
    pub fn set_ccd_enabled(&mut self, id: BodyId, enabled: bool) {
        if let Some(body) = self.inner.bodies.get_mut(id.handle()) {
            body.enable_ccd(enabled);
        }
    }

    /// Amortissements linéaire et angulaire d'un corps, ou `None` s'il n'existe pas.
    #[must_use]
    pub fn damping(&self, id: BodyId) -> Option<(f32, f32)> {
        self.inner
            .bodies
            .get(id.handle())
            .map(|body| (body.linear_damping(), body.angular_damping()))
    }

    /// Indique si un corps dort, ou `None` s'il n'existe pas.
    #[must_use]
    pub fn is_sleeping(&self, id: BodyId) -> Option<bool> {
        self.inner
            .bodies
            .get(id.handle())
            .map(rapier3d::prelude::RigidBody::is_sleeping)
    }

    /// Indique si la CCD est activée sur un corps, ou `None` s'il n'existe pas.
    #[must_use]
    pub fn is_ccd_enabled(&self, id: BodyId) -> Option<bool> {
        self.inner
            .bodies
            .get(id.handle())
            .map(rapier3d::prelude::RigidBody::is_ccd_enabled)
    }

    /// Vitesse linéaire d'un corps, en m/s, ou `None` s'il n'existe pas.
    #[must_use]
    pub fn velocity(&self, id: BodyId) -> Option<Vec3> {
        self.inner
            .bodies
            .get(id.handle())
            .map(rapier3d::prelude::RigidBody::linvel)
    }

    /// Vitesse angulaire d'un corps, en rad/s, ou `None` s'il n'existe pas.
    #[must_use]
    pub fn angular_velocity(&self, id: BodyId) -> Option<Vec3> {
        self.inner
            .bodies
            .get(id.handle())
            .map(rapier3d::prelude::RigidBody::angvel)
    }

    /// Fixe le vent de la dimension (§10.6, `physics.wind`).
    ///
    /// Le vent n'agit qu'à travers la traînée : un corps sans traînée l'ignore.
    pub fn set_wind(&mut self, wind: Vec3) {
        self.wind = wind;
    }

    /// Vent courant de la dimension.
    #[must_use]
    pub fn wind(&self) -> Vec3 {
        self.wind
    }

    /// Fixe (ou retire, avec `None`) le fluide de la dimension (§10.6).
    ///
    /// Tant qu'un fluide est présent, tout corps dynamique dont l'AABB plonge
    /// sous la surface reçoit une poussée d'Archimède.
    pub fn set_fluid(&mut self, fluid: Option<FluidEnvironment>) {
        self.fluid = fluid;
    }

    /// Fluide courant de la dimension, ou `None`.
    #[must_use]
    pub fn fluid(&self) -> Option<FluidEnvironment> {
        self.fluid
    }

    /// Pose (ou remplace) les volumes de fluide d'une section 16³ (C-38, R-642).
    ///
    /// Les volumes sont en coordonnées **locales**. Une liste vide retire la section.
    /// Tout corps dynamique dont un coin d'AABB tombe dans un volume reçoit poussée et
    /// traînée du fluide.
    pub fn set_fluid_volumes(&mut self, section: [i32; 3], volumes: Vec<FluidVolume>) {
        if volumes.is_empty() {
            self.fluid_volumes.remove(&section);
        } else {
            self.fluid_volumes
                .insert(section, FluidSection::new(volumes));
        }
    }

    /// Retire les volumes de fluide d'une section ; rend vrai s'ils existaient.
    pub fn remove_fluid_volumes(&mut self, section: [i32; 3]) -> bool {
        self.fluid_volumes.remove(&section).is_some()
    }

    /// Nombre de sections portant des volumes de fluide.
    #[must_use]
    pub fn fluid_volume_count(&self) -> usize {
        self.fluid_volumes.len()
    }

    /// Déclare l'identité d'un corps pour ses événements (§10.7) : l'assembly, le
    /// node et le matériau qu'il représente. Sans effet si le corps n'existe pas.
    ///
    /// À défaut, un corps émet ses événements à [`Handle::ABSENT`]. Le monde ne
    /// fait que transporter cette identité : il n'en interprète rien (moteur
    /// générique, pas de branche sur un contenu).
    pub fn set_body_identity(&mut self, id: BodyId, assembly: Handle, node: u32, material: u16) {
        if self.inner.bodies.get(id.handle()).is_none() {
            return;
        }
        self.identity.insert(
            id,
            BodyIdentity {
                assembly,
                node,
                material,
            },
        );
    }

    /// Fixe le plafond d'événements par tick (R-1011). Au moins 1.
    pub fn set_max_events_per_tick(&mut self, max: usize) {
        self.max_events_per_tick = max.max(1);
    }

    /// Fixe le seuil d'impulsion des CONTACT_IMPULSE (§10.7, R-1012), en N·s.
    ///
    /// En dessous, un contact ne remonte pas dans le lot (destiné à Java). Une
    /// valeur négative laisse tout passer.
    pub fn set_contact_event_threshold(&mut self, threshold: f32) {
        self.contact_event_threshold = threshold;
    }

    /// Retire et renvoie le lot d'événements accumulé (§10.7, R-1010).
    ///
    /// À appeler une fois par tick, côté autoritatif. Le lot est vidé ; les
    /// événements du tick suivant repartent de zéro.
    pub fn drain_events(&mut self) -> Vec<PhysicsEvent> {
        core::mem::take(&mut self.events)
    }

    /// Nombre cumulé d'événements perdus faute de place dans un lot (R-1011).
    ///
    /// Jamais de perte silencieuse : ce compteur croît dès qu'un événement est
    /// écarté pour cause de plafond atteint.
    #[must_use]
    pub fn dropped_event_count(&self) -> u64 {
        self.dropped_events
    }

    /// Fixe les bornes de vitesse d'un corps (R-180), en m/s et rad/s.
    ///
    /// Sans effet si le corps n'existe pas. À défaut d'appel, les défauts de la
    /// fiche s'appliquent (300 m/s, 100 rad/s). Une borne négative ou nulle
    /// n'aurait pas de sens : elles sont ramenées à zéro, ce qui fige la vitesse
    /// correspondante — usage légitime pour un corps qu'on veut immobiliser.
    pub fn set_velocity_limits(&mut self, id: BodyId, max_linear: f32, max_angular: f32) {
        if self.inner.bodies.get(id.handle()).is_none() {
            return;
        }
        self.velocity_limits
            .insert(id, (max_linear.max(0.0), max_angular.max(0.0)));
    }

    /// Nombre cumulé de clamps journalisés (R-180), après le débit d'au plus un
    /// par corps et par minute. Destiné à la métrique de la frontière (C-15).
    #[must_use]
    pub fn clamp_journal_count(&self) -> u64 {
        self.clamp_journal_count
    }

    /// Nombre cumulé d'états non finis ou hors du monde restaurés (R-181,
    /// `E-2030`). Jamais de restauration silencieuse : ce compteur en fait foi.
    #[must_use]
    pub fn invalid_state_count(&self) -> u64 {
        self.invalid_state_count
    }

    /// Nombre cumulé d'emprises incalculables, rapportées comme la boîte nulle
    /// (ADR-120). Jamais de valeur inventée en silence : ce compteur en fait foi.
    #[must_use]
    pub fn bounds_unavailable_count(&self) -> u64 {
        self.bounds_unavailable.load(Ordering::Relaxed)
    }

    /// Tous les compteurs cumulés du monde, d'un coup.
    #[must_use]
    pub fn counters(&self) -> WorldCounters {
        WorldCounters {
            dropped_events: self.dropped_events,
            clamp_journal: self.clamp_journal_count,
            invalid_states: self.invalid_state_count,
            bounds_unavailable: self.bounds_unavailable_count(),
        }
    }

    /// Produit l'état des corps mobiles identifiés (DM-08) — vue des seuls
    /// états de [`body_reports`](Self::body_reports).
    #[must_use]
    pub fn body_states(&self, origin: &FloatingOrigin) -> Vec<BodyState> {
        self.body_reports(origin).states
    }

    /// Produit l'état et l'emprise des corps mobiles identifiés (DM-08,
    /// ADR-120), pour le cycle de simulation (IF-03).
    ///
    /// Un seul rapport par corps **non statique** portant une identité : les
    /// corps statiques ne bougent pas, et un corps sans identité n'est pas
    /// routable vers Java. La position monde (`f64`) est recomposée depuis la
    /// simulation `f32` par l'origine flottante de la dimension (R-462). L'ordre
    /// suit l'itération déterministe de `rapier` (R-1020).
    ///
    /// États et emprises sortent de **ce même parcours** : `bounds[i]` est
    /// l'emprise du corps de `states[i]`, par construction.
    ///
    /// Flags peuplés : SLEEPING, IN_FLUID et CLAMPED (garde-fou R-180 du tick).
    /// Les autres (TOUCHING_GROUND, DEFORMED, DAMAGED) s'ajouteront avec leur
    /// source (contacts sol puis M6).
    #[must_use]
    pub fn body_reports(&self, origin: &FloatingOrigin) -> BodyReports {
        let mut reports = BodyReports::default();
        for (handle, body) in self.inner.bodies.iter() {
            if !body.is_dynamic_or_kinematic() {
                continue;
            }
            let id = BodyId::from_handle(handle);
            let identity = match self.identity.get(&id) {
                Some(identity) if !identity.assembly.is_absent() => identity,
                _ => continue,
            };
            let pose = body.position();
            let world = origin.to_world(pose.translation);

            // Drapeaux de garde-fou du tick (CLAMPED), posés pendant `advance`.
            let mut flags = self.guard_flags.get(&id).copied().unwrap_or(0);
            if body.is_sleeping() {
                flags |= body_state_flags::SLEEPING;
            }
            if (self.fluid.is_some() || !self.fluid_volumes.is_empty())
                && self.body_in_fluid(handle)
            {
                flags |= body_state_flags::IN_FLUID;
            }

            reports.states.push(BodyState {
                handle: identity.assembly,
                position: world.to_array(),
                rotation: pose.rotation.to_array(),
                lin_vel: body.linvel().to_array(),
                ang_vel: body.angvel().to_array(),
                flags,
            });
            reports
                .bounds
                .push(self.body_bounds(body).unwrap_or_else(|| {
                    self.bounds_unavailable.fetch_add(1, Ordering::Relaxed);
                    BodyBounds::EMPTY
                }));
        }
        reports
    }

    /// Emprise d'un corps (ADR-120) : union des emprises de ses colliders,
    /// alignée sur les axes du monde, **relative à sa translation**.
    ///
    /// Calculée depuis la **pose courante du corps** — celle que rapporte
    /// [`body_reports`](Self::body_reports) — composée avec la pose locale de
    /// chaque collider, jamais depuis la position de collider en cache : une
    /// restauration R-181 (`set_position`) ne resynchronise les colliders qu'au
    /// pas suivant, et l'emprise contredirait alors la pose rapportée.
    ///
    /// Le calcul se fait sous la seule **rotation** du corps : l'emprise d'une
    /// forme translatée n'est que l'emprise translatée, si bien que le résultat
    /// sort directement relatif à l'origine du corps — sans soustraction de deux
    /// grandeurs voisines, ni dépendance à l'origine flottante.
    ///
    /// `None` si l'emprise est incalculable : aucun collider, collider sans
    /// parent, valeur non finie.
    fn body_bounds(&self, body: &RigidBody) -> Option<BodyBounds> {
        let orientation = RapierPose::from_parts(Vec3::ZERO, body.position().rotation);
        let mut union: Option<(Vec3, Vec3)> = None;
        for handle in body.colliders() {
            let collider = self.inner.colliders.get(*handle)?;
            let local = collider.position_wrt_parent()?;
            let aabb = collider.shape().compute_aabb(&(orientation * local));
            union = Some(match union {
                None => (aabb.mins, aabb.maxs),
                Some((min, max)) => (min.min(aabb.mins), max.max(aabb.maxs)),
            });
        }
        let (min, max) = union?;
        if !(min.is_finite() && max.is_finite()) {
            return None;
        }
        Some(BodyBounds {
            min: min.to_array(),
            max: max.to_array(),
        })
    }

    /// Arêtes des colliders de chaque corps d'assembly, en repère du corps, pour
    /// l'overlay `colliders` (C-67, ADR-121).
    ///
    /// Lecture seule. Corps retenus : ceux qui portent une identité d'assembly,
    /// **statiques compris** — les tuiles de collision du monde n'en ont pas et
    /// relèvent d'un autre overlay. Forme courante de chaque collider, à sa pose
    /// relative au corps ; position monde (`f64`) recomposée par l'origine
    /// flottante, pour le tri par distance. Ordre de `rapier` (R-1020).
    #[must_use]
    pub fn collider_outlines(&self, origin: &FloatingOrigin) -> Vec<ColliderOutline> {
        let mut outlines = Vec::new();
        for (handle, body) in self.inner.bodies.iter() {
            let identity = match self.identity.get(&BodyId::from_handle(handle)) {
                Some(identity) if !identity.assembly.is_absent() => identity,
                _ => continue,
            };
            let mut flags = 0;
            if body.is_fixed() {
                flags |= debug_body_flags::STATIC;
            } else if body.is_kinematic() {
                flags |= debug_body_flags::KINEMATIC;
            }
            if body.is_sleeping() {
                flags |= debug_body_flags::SLEEPING;
            }
            let mut segments = Vec::new();
            for collider_handle in body.colliders() {
                let Some(collider) = self.inner.colliders.get(*collider_handle) else {
                    continue;
                };
                // Un collider rattaché à un corps a toujours sa pose relative ;
                // sans elle, il n'est pas de ce corps.
                let Some(local) = collider.position_wrt_parent() else {
                    continue;
                };
                append_outline(collider.shape(), local, &mut segments);
            }
            outlines.push(ColliderOutline {
                handle: identity.assembly,
                flags,
                position: origin.to_world(body.position().translation),
                segments,
            });
        }
        outlines
    }

    /// Indique si un coin de l'AABB du corps est immergé — dans un volume localisé ou
    /// sous la surface plate de la dimension (§10.6, R-642). Un coin pris dans un solide
    /// n'y change rien : il ne compte que si un autre l'est déjà.
    fn body_in_fluid(&self, handle: RigidBodyHandle) -> bool {
        self.inner.bodies.get(handle).is_some_and(|body| {
            sample_immersion(
                body,
                &self.inner.colliders,
                |p| fluid_density_at(&self.fluid_volumes, &self.fluid, p),
                |_| false,
            )
            .is_some()
        })
    }

    /// Multiplie la gravité ressentie par un corps (§10.6, `gravity_scale`).
    ///
    /// Sans effet si le corps n'existe pas. `0` fait flotter le corps, `2` le
    /// rend deux fois plus lourd.
    pub fn set_gravity_scale(&mut self, id: BodyId, scale: f32) {
        if let Some(body) = self.inner.bodies.get_mut(id.handle()) {
            body.set_gravity_scale(scale, true);
        }
    }

    /// Déclare la traînée d'un corps (§10.6) par son coefficient et son aire de
    /// référence. La force vaut `−0.5·ρ·Cd·A·|v|·v`, relative au vent.
    ///
    /// Sans effet si le corps n'existe pas. Un produit `Cd·A` nul retire la
    /// traînée.
    pub fn set_drag(&mut self, id: BodyId, drag_coefficient: f32, area: f32) {
        if self.inner.bodies.get(id.handle()).is_none() {
            return;
        }
        self.aero.entry(id).or_default().drag_cd_a = drag_coefficient * area;
    }

    /// Déclare les surfaces portantes d'un corps (§10.6, R-1000).
    ///
    /// Sans effet si le corps n'existe pas. Remplace les surfaces déclarées ; une
    /// liste vide retire la portance. C'est le seul mécanisme du « vol » : il n'y
    /// a ni système avion ni système bateau, seulement des surfaces.
    pub fn set_lift_surfaces(&mut self, id: BodyId, surfaces: Vec<LiftSurface>) {
        if self.inner.bodies.get(id.handle()).is_none() {
            return;
        }
        self.aero.entry(id).or_default().lift_surfaces = surfaces;
    }

    /// Relève les corps sujets à la gestion d'activité (R-612, R-613, ADR-123 §3) : les
    /// corps dynamiques éveillés, et ceux qu'elle a endormis — chacun avec sa distance au
    /// plus proche des `observers` et son rang de création. Un corps endormi
    /// naturellement n'y figure pas : il ne compte pas parmi les actifs, et rien ne le
    /// réveille.
    ///
    /// Le relevé suit l'ordre d'itération de `rapier` (R-1020). Un client ne relève rien :
    /// la gestion d'activité est autoritaire (R-662).
    pub(crate) fn survey_activity(
        &mut self,
        dimension: u64,
        observers: &Observers,
        out: &mut Vec<Candidate>,
    ) {
        if self.mode == SimMode::Client {
            return;
        }
        for (handle, body) in self.inner.bodies.iter() {
            if !body.is_dynamic() {
                continue;
            }
            let id = BodyId::from_handle(handle);
            let awake = !body.is_sleeping();
            if awake {
                // Réveillé hors d'un pas (une impulsion venue de Java) : la vitesse
                // retenue ne vaut plus.
                self.forced_sleep.remove(&id);
            } else if !self.forced_sleep.contains_key(&id) {
                continue;
            }
            out.push(Candidate {
                dimension,
                body: id,
                distance: observers.nearest(body.translation()),
                birth: self.births.get(&id).copied().unwrap_or_default(),
                awake,
            });
        }
    }

    /// Endort un corps au nom de la gestion d'activité (R-612, R-613) et retient sa
    /// vitesse. **Endort, jamais ne supprime.** `budget` dit que seule la dégradation
    /// l'endort (FM-21) : un `CLAMPED` de code 2 le signale (ADR-123 §11).
    ///
    /// Sans effet sur un corps absent, non dynamique ou déjà endormi.
    pub(crate) fn force_sleep(&mut self, id: BodyId, budget: bool) {
        let Some(body) = self.inner.bodies.get_mut(id.handle()) else {
            return;
        };
        if !body.is_dynamic() || body.is_sleeping() {
            return;
        }
        let frozen = ForcedSleep {
            linvel: body.linvel(),
            angvel: body.angvel(),
        };
        body.sleep();
        self.forced_sleep.insert(id, frozen);
        if budget {
            self.push_single_body_event(id, event_kind::CLAMPED, event_data::CLAMPED_BUDGET);
        }
    }

    /// Réveille un corps endormi par la gestion d'activité et lui rend la vitesse qu'il
    /// avait : sa simulation reprend où elle s'était suspendue (R-1890).
    ///
    /// Sans effet sur un corps que la gestion n'a pas endormi.
    pub(crate) fn end_forced_sleep(&mut self, id: BodyId) {
        let Some(frozen) = self.forced_sleep.remove(&id) else {
            return;
        };
        if let Some(body) = self.inner.bodies.get_mut(id.handle()) {
            body.wake_up(true);
            body.set_linvel(frozen.linvel, false);
            body.set_angvel(frozen.angvel, false);
        }
    }

    /// Fait d'un corps un capteur, ou l'en retire (§10.4).
    ///
    /// Sans effet si le corps n'existe pas. Un capteur détecte les
    /// chevauchements — il émet SENSOR_ENTER/SENSOR_EXIT (§10.7) — mais ne
    /// résout aucun contact : rien ne rebondit dessus.
    pub fn set_sensor(&mut self, id: BodyId, is_sensor: bool) {
        let handles: Vec<_> = match self.inner.bodies.get(id.handle()) {
            Some(body) => body.colliders().to_vec(),
            None => return,
        };
        for handle in handles {
            if let Some(collider) = self.inner.colliders.get_mut(handle) {
                collider.set_sensor(is_sensor);
            }
        }
    }

    /// Retire un corps ; renvoie vrai s'il existait.
    pub fn remove_body(&mut self, id: BodyId) -> bool {
        self.aero.remove(&id);
        self.identity.remove(&id);
        self.sleep_state.remove(&id);
        self.velocity_limits.remove(&id);
        self.last_valid.remove(&id);
        self.guard_flags.remove(&id);
        self.clamp_journal_at.remove(&id);
        self.forced_sleep.remove(&id);
        self.births.remove(&id);
        self.stack_watch.remove(&id);
        self.stack_damped.remove(&id);
        self.damping_backup.remove(&id);
        self.inner.remove_body(id.handle()).is_some()
    }

    /// Nombre de corps dans le monde.
    #[must_use]
    pub fn body_count(&self) -> usize {
        self.inner.bodies.len()
    }

    /// Nombre de colliders dans le monde : les feuilles de l'arbre de la phase large.
    #[must_use]
    pub fn collider_count(&self) -> usize {
        self.inner.colliders.len()
    }

    /// Emprises monde de tous les colliders, dans l'ordre de rapier.
    #[cfg(test)]
    pub(crate) fn collider_aabbs(&self) -> Vec<(Vec3, Vec3)> {
        self.inner
            .colliders
            .iter()
            .map(|(_, collider)| {
                let aabb = collider.compute_aabb();
                (aabb.mins, aabb.maxs)
            })
            .collect()
    }

    /// Fixe le rôle de la simulation (C-40, R-662). En [`SimMode::Client`], `advance`
    /// n'exécute pas l'intégration autoritaire.
    pub fn set_mode(&mut self, mode: SimMode) {
        self.mode = mode;
    }

    /// Rôle courant de la simulation (C-40).
    #[must_use]
    pub fn mode(&self) -> SimMode {
        self.mode
    }

    /// Durée cumulée d'une étape sur le dernier tick, en nanosecondes (C-40, R-661,
    /// observationnel).
    #[must_use]
    pub fn stage_duration(&self, stage: Stage) -> u64 {
        self.stage_durations.get(stage)
    }

    // --- C-39 : requêtes spatiales (fiche 5.31) -----------------------------------------
    // Toutes en lecture seule (`&self`, R-650) ; elles lisent la géométrie de collision
    // laissée par le dernier `advance` (l'état du début du tick, R-651).

    /// Lance un rayon et rend le premier corps heurté (C-39, R-650).
    ///
    /// `direction` est normalisée ; `max_distance` borne la portée, en blocs. `None` si le
    /// rayon ne heurte rien, si la direction est nulle ou si `max_distance` est non fini/≤ 0.
    #[must_use]
    pub fn raycast(
        &self,
        origin: Vec3,
        direction: Vec3,
        max_distance: f32,
        filter: SpatialFilter,
    ) -> Option<RayHit> {
        let dir = direction.normalize_or_zero();
        if dir == Vec3::ZERO || !max_distance.is_finite() || max_distance <= 0.0 {
            return None;
        }
        let rfilter = to_rapier_filter(&filter);
        let pipeline = self.query_pipeline(rfilter);
        let ray = Ray::new(origin, dir);
        let (handle, hit) = pipeline.cast_ray_and_get_normal(&ray, max_distance, true)?;
        let body = self.body_of_collider(handle)?;
        Some(RayHit {
            body,
            distance: hit.time_of_impact,
            point: origin + dir * hit.time_of_impact,
            normal: hit.normal,
        })
    }

    /// Balaye une forme le long de `direction` et rend le premier corps heurté (C-39).
    ///
    /// `max_distance` borne la distance parcourue, en blocs. `None` si aucun impact, si la
    /// direction est nulle, ou si la forme de requête est invalide.
    #[must_use]
    pub fn sweep(
        &self,
        shape: &Shape,
        position: Vec3,
        rotation: Quat,
        direction: Vec3,
        max_distance: f32,
        filter: SpatialFilter,
    ) -> Option<SweepHit> {
        let dir = direction.normalize_or_zero();
        if dir == Vec3::ZERO || !max_distance.is_finite() || max_distance <= 0.0 {
            return None;
        }
        let shared = shared_shape_of(shape).ok()?;
        let rfilter = to_rapier_filter(&filter);
        let pipeline = self.query_pipeline(rfilter);
        let pose = RapierPose::from_parts(position, rotation);
        let options = ShapeCastOptions::with_max_time_of_impact(max_distance);
        let (handle, hit) = pipeline.cast_shape(&pose, dir, shared.as_ref(), options)?;
        let body = self.body_of_collider(handle)?;
        Some(SweepHit {
            body,
            time_of_impact: hit.time_of_impact,
            point: hit.witness1,
            normal: hit.normal1,
        })
    }

    /// Rend tous les corps dont un collider recouvre la forme donnée (C-39), sans doublon.
    ///
    /// Liste vide si la forme de requête est invalide.
    #[must_use]
    pub fn overlap(
        &self,
        shape: &Shape,
        position: Vec3,
        rotation: Quat,
        filter: SpatialFilter,
    ) -> Vec<BodyId> {
        let Ok(shared) = shared_shape_of(shape) else {
            return Vec::new();
        };
        let rfilter = to_rapier_filter(&filter);
        let pipeline = self.query_pipeline(rfilter);
        let pose = RapierPose::from_parts(position, rotation);
        let mut bodies = Vec::new();
        for (handle, _collider) in pipeline.intersect_shape(pose, shared.as_ref()) {
            if let Some(body) = self.body_of_collider(handle) {
                if !bodies.contains(&body) {
                    bodies.push(body);
                }
            }
        }
        bodies
    }

    /// Version par lot de [`raycast`](Self::raycast) : un rayon `(origine, direction,
    /// distance max)` par entrée. Chaque requête est indépendante et sans effet de bord
    /// (R-650) — parallélisable ; l'implémentation reste séquentielle et déterministe.
    #[must_use]
    pub fn raycast_batch(
        &self,
        rays: &[(Vec3, Vec3, f32)],
        filter: SpatialFilter,
    ) -> Vec<Option<RayHit>> {
        rays.iter()
            .map(|&(origin, direction, max)| self.raycast(origin, direction, max, filter))
            .collect()
    }

    /// Version par lot de [`sweep`](Self::sweep) : la **même** forme balayée depuis
    /// plusieurs poses `(position, rotation, direction, distance max)`.
    #[must_use]
    pub fn sweep_batch(
        &self,
        shape: &Shape,
        motions: &[(Vec3, Quat, Vec3, f32)],
        filter: SpatialFilter,
    ) -> Vec<Option<SweepHit>> {
        motions
            .iter()
            .map(|&(position, rotation, direction, max)| {
                self.sweep(shape, position, rotation, direction, max, filter)
            })
            .collect()
    }

    /// Version par lot d'[`overlap`](Self::overlap) : la **même** forme testée à plusieurs
    /// poses `(position, rotation)`.
    #[must_use]
    pub fn overlap_batch(
        &self,
        shape: &Shape,
        poses: &[(Vec3, Quat)],
        filter: SpatialFilter,
    ) -> Vec<Vec<BodyId>> {
        poses
            .iter()
            .map(|&(position, rotation)| self.overlap(shape, position, rotation, filter))
            .collect()
    }

    /// Construit la `QueryPipeline` de `rapier` sur l'état courant, avec le filtre donné.
    fn query_pipeline<'a>(
        &'a self,
        filter: RapierQueryFilter<'a>,
    ) -> rapier3d::prelude::QueryPipeline<'a> {
        self.inner.broad_phase.as_query_pipeline(
            self.inner.narrow_phase.query_dispatcher(),
            &self.inner.bodies,
            &self.inner.colliders,
            filter,
        )
    }

    /// Corps parent d'un collider, ou `None` (collider sans parent).
    fn body_of_collider(&self, handle: ColliderHandle) -> Option<BodyId> {
        self.inner
            .colliders
            .get(handle)
            .and_then(Collider::parent)
            .map(BodyId::from_handle)
    }
}

/// Nanosecondes écoulées depuis `start` (métrique d'étape C-40, R-661).
fn elapsed_nanos(start: Instant) -> u64 {
    start.elapsed().as_nanos() as u64
}

/// Traduit un [`SpatialFilter`] AXION en `QueryFilter` de `rapier`.
fn to_rapier_filter(filter: &SpatialFilter) -> RapierQueryFilter<'_> {
    let mut flags = QueryFilterFlags::empty();
    match filter.sensors {
        SensorMode::SolidsOnly => flags |= QueryFilterFlags::EXCLUDE_SENSORS,
        SensorMode::SensorsOnly => flags |= QueryFilterFlags::EXCLUDE_SOLIDS,
        SensorMode::Both => {}
    }
    RapierQueryFilter {
        flags,
        groups: filter.groups.map(CollisionGroups::to_rapier),
        exclude_collider: None,
        exclude_rigid_body: filter.exclude_body.map(BodyId::handle),
        predicate: None,
    }
}

/// Construit la forme `parry` d'une [`Shape`], en validant §10.3.
///
/// Un seul chemin de construction, récursif pour [`Shape::Compound`] : primitive
/// ou collider composé, la validation est la même partout.
/// Vrai si la forme — elle-même ou l'une de ses filles — est interdite sur un corps
/// dynamique (INV-13, R-970). Seule `Heightfield` l'est aujourd'hui ; la recherche
/// descend dans les `Compound` par prudence, une fille concave restant interdite.
fn shape_forbids_dynamic(shape: &Shape) -> bool {
    match shape {
        Shape::Heightfield { .. } => true,
        Shape::Compound { parts } => parts.iter().any(|part| shape_forbids_dynamic(&part.shape)),
        _ => false,
    }
}

fn shared_shape_of(shape: &Shape) -> Result<SharedShape, BodyError> {
    match shape {
        Shape::Cuboid {
            half_extents: [hx, hy, hz],
        } => Ok(SharedShape::cuboid(*hx, *hy, *hz)),
        Shape::Ball { radius } => Ok(SharedShape::ball(*radius)),
        Shape::Capsule {
            half_height,
            radius,
        } => Ok(SharedShape::capsule_y(*half_height, *radius)),
        Shape::Cylinder {
            half_height,
            radius,
        } => Ok(SharedShape::cylinder(*half_height, *radius)),
        Shape::Cone {
            half_height,
            radius,
        } => Ok(SharedShape::cone(*half_height, *radius)),
        Shape::ConvexHull { points } => {
            if points.len() < MIN_CONVEX_HULL_POINTS {
                return Err(BodyError::ConvexHullTooFewPoints);
            }
            if points.len() > MAX_CONVEX_HULL_POINTS {
                return Err(BodyError::ConvexHullTooManyPoints);
            }
            let cloud: Vec<Vec3> = points
                .iter()
                .map(|point| Vec3::from_array(*point))
                .collect();
            // `rapier` rend `None` si les points ne forment aucun volume
            // (coplanaires, colinéaires, ou confondus) : la forme n'existe pas.
            SharedShape::convex_hull(&cloud).ok_or(BodyError::DegenerateConvexHull)
        }
        Shape::Compound { parts } => {
            if parts.is_empty() {
                return Err(BodyError::EmptyCompound);
            }
            if parts.len() > MAX_COMPOUND_PARTS {
                return Err(BodyError::CompoundTooManyParts);
            }
            let mut children = Vec::with_capacity(parts.len());
            for part in parts {
                let CompoundPart {
                    translation,
                    rotation,
                    shape,
                } = part;
                children.push((
                    RapierPose::from_parts(*translation, *rotation),
                    shared_shape_of(shape)?,
                ));
            }
            Ok(SharedShape::compound(children))
        }
        Shape::Heightfield {
            rows,
            cols,
            heights,
            scale,
        } => {
            let rows = *rows as usize;
            let cols = *cols as usize;
            // parry exige au moins une cellule (≥ 2 lignes et 2 colonnes) et
            // paniquerait sinon : on refuse proprement en amont.
            if rows < 2 || cols < 2 {
                return Err(BodyError::HeightfieldTooSmall);
            }
            if heights.len() != rows * cols {
                return Err(BodyError::HeightfieldSizeMismatch);
            }
            let [sx, sy, sz] = *scale;
            if !sx.is_finite() || !sy.is_finite() || !sz.is_finite() || sx <= 0.0 || sz <= 0.0 {
                return Err(BodyError::HeightfieldInvalidScale);
            }
            if heights.iter().any(|h| !h.is_finite()) {
                return Err(BodyError::HeightfieldNonFiniteHeight);
            }
            // Nos hauteurs sont ligne-major (`row * cols + col`, `row` sur z) ;
            // l'`Array2` de parry est colonne-major et indexé `(i = ligne z, j = col x)`.
            // `from_fn` remplit dans l'ordre colonne-major : la conversion est exacte.
            let matrix = Array2::from_fn(rows, cols, |i, j| heights[i * cols + j]);
            Ok(SharedShape::heightfield(matrix, Vector::new(sx, sy, sz)))
        }
    }
}

/// Vrai si l'état d'un corps est fini et dans le monde (R-181).
///
/// Rejette toute composante non finie (NaN, infini) de la pose ou des vitesses,
/// ainsi qu'une translation dont un axe dépasse [`WORLD_COORD_LIMIT`] — les deux
/// symptômes d'une explosion du solveur (FM-20).
fn state_is_valid(translation: Vec3, rotation: Quat, linvel: Vec3, angvel: Vec3) -> bool {
    translation.is_finite()
        && rotation.is_finite()
        && linvel.is_finite()
        && angvel.is_finite()
        && translation.abs().max_element() <= WORLD_COORD_LIMIT
}

/// Traînée d'un corps : force centrale opposée à la vitesse relative au vent, dans un
/// milieu de masse volumique `rho` (air ou fluide, §10.6).
fn drag_force(body: &RigidBody, wind: Vec3, drag_cd_a: f32, rho: f32) -> Vec3 {
    if drag_cd_a <= 0.0 {
        return Vec3::ZERO;
    }
    let relative = body.linvel() - wind;
    let speed = relative.length();
    if speed <= f32::EPSILON {
        return Vec3::ZERO;
    }
    // F = −0.5·ρ·Cd·A·|v|·v.
    relative * (-0.5 * rho * drag_cd_a * speed)
}

/// Portance d'une surface, appliquée au point de la surface (§10.6, R-1000).
///
/// Perpendiculaire à l'écoulement au point, dans la direction de la composante
/// de la normale qui lui est orthogonale. `None` si la surface est de profil
/// (normale alignée à l'écoulement) ou immobile relativement au vent.
fn lift_force(
    body: &RigidBody,
    pose: &RapierPose,
    wind: Vec3,
    surface: &LiftSurface,
) -> Option<(Vec3, Vec3)> {
    if surface.lift_coefficient == 0.0 || surface.area <= 0.0 {
        return None;
    }
    let world_point = pose.translation + pose.rotation * surface.local_point;
    let world_normal = (pose.rotation * surface.local_normal).normalize_or_zero();
    if world_normal == Vec3::ZERO {
        return None;
    }
    let relative = body.velocity_at_point(world_point) - wind;
    let speed = relative.length();
    if speed <= f32::EPSILON {
        return None;
    }
    let flow = relative / speed;
    // Composante de la normale orthogonale à l'écoulement.
    let perpendicular = world_normal - flow * world_normal.dot(flow);
    let perpendicular_length = perpendicular.length();
    if perpendicular_length <= f32::EPSILON {
        return None;
    }
    let direction = perpendicular / perpendicular_length;
    let magnitude = 0.5 * AIR_DENSITY * surface.lift_coefficient * surface.area * speed * speed;
    Some((direction * magnitude, world_point))
}

/// Les volumes de fluide d'une section 16³ (C-38, R-642), avec la boîte qui les englobe.
///
/// Une section d'eau en compte jusqu'à 4 096, un par bloc, et un point s'y cherche pour
/// chacun des huit coins de chaque corps, à chaque sous-pas : un point hors de la boîte
/// englobante n'est dans aucun d'eux, et la recherche passe à la section suivante sans les
/// parcourir.
#[derive(Debug, Clone)]
struct FluidSection {
    min: Vec3,
    max: Vec3,
    volumes: Vec<FluidVolume>,
}

impl FluidSection {
    fn new(volumes: Vec<FluidVolume>) -> Self {
        let (min, max) = volumes.iter().fold(
            (Vec3::splat(f32::INFINITY), Vec3::splat(f32::NEG_INFINITY)),
            |(min, max), volume| (min.min(volume.min), max.max(volume.max)),
        );
        Self { min, max, volumes }
    }

    /// Densité du premier volume de la section qui contient `p` (bornes incluses).
    fn density_at(&self, p: Vec3) -> Option<f32> {
        if p.cmplt(self.min).any() || p.cmpgt(self.max).any() {
            return None;
        }
        self.volumes
            .iter()
            .find(|volume| volume.density > 0.0 && volume.contains(p))
            .map(|volume| volume.density)
    }
}

/// Densité du fluide au point `p` (coords locales), ou `None` s'il est dans l'air.
///
/// Les volumes localisés du monde (C-38, R-642) priment sur la surface plate de la
/// dimension : un point dans une boîte d'eau prend sa densité ; sinon, s'il est sous la
/// surface plate, celle du fluide de dimension. Densités nulles ou négatives ignorées.
fn fluid_density_at(
    sections: &BTreeMap<[i32; 3], FluidSection>,
    flat: &Option<FluidEnvironment>,
    p: Vec3,
) -> Option<f32> {
    if let Some(density) = sections.values().find_map(|section| section.density_at(p)) {
        return Some(density);
    }
    if let Some(fluid) = flat {
        if fluid.density > 0.0 && p.y < fluid.surface_y {
            return Some(fluid.density);
        }
    }
    None
}

/// L'immersion d'un corps dans un fluide (§10.6), échantillonnée aux huit coins de son
/// AABB.
struct Immersion {
    /// Fraction immergée : coins immergés / 8.
    fraction: f32,
    /// Masse volumique moyenne du fluide sur les coins immergés, en kg/m³.
    density: f32,
    /// Centre de poussée : centroïde des coins immergés (coords locales).
    centroid: Vec3,
    /// Volume du corps, somme de ceux de ses colliders, en m³.
    volume: f32,
}

/// Échantillonne l'immersion d'un corps : compte ses **huit coins d'AABB** dans le
/// fluide selon `density_at` (§10.6). `None` si aucun coin n'est immergé ou si l'AABB
/// est dégénérée.
///
/// Un coin qui déborde dans un solide (`in_solid`) n'est ni dans l'eau ni dans l'air : le
/// corps n'y est pas, seule son AABB. Il compte comme immergé si un coin l'est à sa hauteur
/// ou plus haut — la surface est horizontale —, comme sec sinon. Le compter sec décentrait la
/// poussée d'un corps posé sur un fond inégal : un couple constant, qui l'a fait tourner
/// jusqu'au plafond de `rapier` (essai du 2026-10-06). Un corps plaqué sous un plafond immergé
/// garde cette limite : ses coins pris dans le plafond, au-dessus de tout coin immergé,
/// restent secs.
///
/// Le centroïde des coins immergés sert de centre de poussée : sous le COM quand le
/// corps émerge à moitié, il produit le moment de redressement d'un bateau. La densité
/// retenue est la moyenne sur les coins immergés — un corps à cheval sur deux fluides
/// reçoit une densité intermédiaire (modèle approximatif assumé).
fn sample_immersion(
    body: &RigidBody,
    colliders: &ColliderSet,
    density_at: impl Fn(Vec3) -> Option<f32>,
    in_solid: impl Fn(Vec3) -> bool,
) -> Option<Immersion> {
    // Les huit coins de l'AABB qui englobe tous les colliders du corps.
    let mut lo = Vec3::splat(f32::INFINITY);
    let mut hi = Vec3::splat(f32::NEG_INFINITY);
    for collider in body
        .colliders()
        .iter()
        .filter_map(|handle| colliders.get(*handle))
    {
        let aabb = collider.compute_aabb();
        lo = lo.min(Vec3::new(aabb.mins.x, aabb.mins.y, aabb.mins.z));
        hi = hi.max(Vec3::new(aabb.maxs.x, aabb.maxs.y, aabb.maxs.z));
    }
    if !lo.cmple(hi).all() {
        return None;
    }
    let corners = [
        Vec3::new(lo.x, lo.y, lo.z),
        Vec3::new(hi.x, lo.y, lo.z),
        Vec3::new(lo.x, hi.y, lo.z),
        Vec3::new(hi.x, hi.y, lo.z),
        Vec3::new(lo.x, lo.y, hi.z),
        Vec3::new(hi.x, lo.y, hi.z),
        Vec3::new(lo.x, hi.y, hi.z),
        Vec3::new(hi.x, hi.y, hi.z),
    ];
    let densities = corners.map(&density_at);
    let mut submerged = 0u32;
    let mut sum = Vec3::ZERO;
    let mut density_sum = 0.0;
    // La plus haute hauteur à laquelle un coin est dans le fluide.
    let mut waterline = f32::NEG_INFINITY;
    for (corner, density) in corners.iter().zip(densities.iter()) {
        if let Some(density) = density {
            submerged += 1;
            sum += *corner;
            density_sum += density;
            waterline = waterline.max(corner.y);
        }
    }
    if submerged == 0 {
        return None;
    }
    // Les coins pris dans un solide sous cette hauteur baignent dans le fluide des autres.
    let fluid_density = density_sum / submerged as f32;
    for (corner, density) in corners.iter().zip(densities.iter()) {
        if density.is_none() && corner.y <= waterline && in_solid(*corner) {
            submerged += 1;
            sum += *corner;
            density_sum += fluid_density;
        }
    }
    // V_immergé est une part du volume du corps, celui de ses colliders — non de son AABB,
    // qui le dépasse dès qu'il tourne (jusqu'à √3³ ≈ 5,2 fois pour un cube) : la prendre
    // pour lui fait jaillir un corps de la densité du fluide. Calculé une fois un coin
    // immergé, pour qu'un corps hors de l'eau n'en paie rien.
    let volume: f32 = body
        .colliders()
        .iter()
        .filter_map(|handle| colliders.get(*handle))
        .map(Collider::volume)
        .sum();
    if !(volume > 0.0 && volume.is_finite()) {
        return None;
    }
    Some(Immersion {
        fraction: submerged as f32 / 8.0,
        density: density_sum / submerged as f32,
        centroid: sum / submerged as f32,
        volume,
    })
}

/// Masse effective au contact le long de la normale (§10.7, R-615), en kg.
///
/// `1/m_eff = Σ_corps ( n·(invMass ⊙ n) + |M√⁻¹·(r×n)|² )`, où `r` va du centre
/// de masse au point, et `M√⁻¹` est la racine de l'inverse de l'inertie monde
/// (le champ `effective_world_inv_inertia` de `rapier`). Un corps statique n'y
/// contribue pas (masse et inertie inverses nulles).
fn effective_mass_at(
    point: Vec3,
    normal: Vec3,
    body_a: Option<&RigidBody>,
    body_b: Option<&RigidBody>,
) -> f32 {
    let inverse = inverse_effective_mass_term(body_a, point, normal)
        + inverse_effective_mass_term(body_b, point, normal);
    if inverse > f32::EPSILON {
        1.0 / inverse
    } else {
        0.0
    }
}

/// Contribution d'un corps à l'inverse de la masse effective (terme linéaire +
/// angulaire). Nulle pour un corps absent, statique ou cinématique.
fn inverse_effective_mass_term(body: Option<&RigidBody>, point: Vec3, normal: Vec3) -> f32 {
    let Some(body) = body else {
        return 0.0;
    };
    let mass_properties = body.mass_properties();
    // Terme linéaire : Σ n_i² · invMass_i (anisotrope si des axes sont verrouillés).
    let linear = (normal * normal).dot(mass_properties.effective_inv_mass);
    // Terme angulaire : (r×n)·I⁻¹·(r×n) = |M√⁻¹·(r×n)|².
    let lever = point - mass_properties.world_com;
    let lever_cross_normal = lever.cross(normal);
    let angular = mass_properties
        .effective_world_inv_inertia
        .mul_vec(lever_cross_normal)
        .length_squared();
    linear + angular
}
