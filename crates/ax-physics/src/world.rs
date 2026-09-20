//! Le monde physique d'une dimension (C-31, fiche 5.23).

use ax_math::{FloatingOrigin, Quat, Vec3};
use rapier3d::prelude::{
    ActiveEvents, ColliderBuilder, ColliderHandle, ColliderSet, CollisionEvent, CollisionEventFlags,
    ContactPair, EventHandler, Pose as RapierPose, PhysicsWorld as RapierWorld, Real, RigidBody,
    RigidBodyBuilder, RigidBodyHandle, RigidBodySet, RigidBodyType, SharedShape,
};

use crate::body::{
    BodyError, BodyId, BodyKind, CompoundPart, Shape, MAX_COMPOUND_PARTS, MAX_CONVEX_HULL_POINTS,
    MIN_CONVEX_HULL_POINTS,
};
use crate::config::PhysicsConfig;
use crate::forces::{FluidEnvironment, LiftSurface};
use crate::groups::CollisionGroups;
use ax_model::dm::handle::Handle;
use ax_model::dm::physics::{body_state_flags, event_kind, BodyState, PhysicsEvent};
use std::collections::HashMap;

/// Plafond par défaut d'événements par tick (§10.7, `physics.max_events_per_tick`).
const DEFAULT_MAX_EVENTS_PER_TICK: usize = 4096;

/// Seuil par défaut sous lequel un contact ne remonte pas à Java (§10.7,
/// `physics.contact_event_threshold`), en N·s.
const DEFAULT_CONTACT_EVENT_THRESHOLD: f32 = 0.5;

/// Masse volumique de l'air au niveau de la mer, en kg/m³ (§10.6, terme ρ de la
/// traînée). Valeur physique standard, non un chiffre de performance.
const AIR_DENSITY: f32 = 1.225;

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
    /// Fluide de la dimension pour la flottabilité (§10.6), absent par défaut.
    fluid: Option<FluidEnvironment>,
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
        Self {
            inner,
            config,
            accumulator: 0.0,
            wind: Vec3::ZERO,
            fluid: None,
            aero: HashMap::new(),
            identity: HashMap::new(),
            sleep_state: HashMap::new(),
            events: Vec::new(),
            max_events_per_tick: DEFAULT_MAX_EVENTS_PER_TICK,
            contact_event_threshold: DEFAULT_CONTACT_EVENT_THRESHOLD,
            dropped_events: 0,
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
        let collider = ColliderBuilder::new(shared_shape_of(&shape)?)
            .active_events(ActiveEvents::COLLISION_EVENTS)
            .build();

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

        let (handle, _collider) = self.inner.insert(body, collider);
        Ok(BodyId::from_handle(handle))
    }

    /// Avance la simulation d'au plus `max_substeps` sous-pas de `fixed_dt`,
    /// selon le temps réel écoulé. Renvoie le nombre de sous-pas exécutés.
    ///
    /// L'accumulateur est **clampé** à `max_substeps · fixed_dt` avant la
    /// boucle : au-delà, le retard est abandonné plutôt que rattrapé, ce qui
    /// évite la spirale où chaque tick prend plus de retard qu'il n'en comble
    /// (R-990).
    pub fn advance(&mut self, frame_dt: f32) -> u32 {
        let dt = self.config.fixed_dt();
        let ceiling = dt * self.config.max_substeps() as f32;
        self.accumulator = (self.accumulator + frame_dt.max(0.0)).min(ceiling);
        let mut substeps = 0;
        while self.accumulator >= dt {
            self.apply_aero_forces();
            let collector = ContactCollector::default();
            self.inner.step_with_events(&(), &collector);
            self.collect_contact_events(&collector);
            self.collect_contact_impulses();
            self.collect_sleep_events();
            self.accumulator -= dt;
            substeps += 1;
        }
        substeps
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
        let world_point = self.inner.colliders.get(a)?.position().transform_point(contact.local_p1);

        let (identity_a, body_a) = self.contact_body(a);
        let (identity_b, body_b) = self.contact_body(b);

        let velocity_a = body_a.map_or(Vec3::ZERO, |body| body.velocity_at_point(world_point));
        let velocity_b = body_b.map_or(Vec3::ZERO, |body| body.velocity_at_point(world_point));
        // Vitesse relative projetée sur la normale : la vitesse de rapprochement.
        let relative_velocity = (velocity_a - velocity_b).dot(normal);

        Some(PhysicsEvent {
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
        })
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
    fn build_identity_event(&self, a: ColliderHandle, b: ColliderHandle, kind: u32) -> PhysicsEvent {
        let (identity_a, _) = self.contact_body(a);
        let (identity_b, _) = self.contact_body(b);
        PhysicsEvent {
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
        }
    }

    /// Rend l'identité et le corps parent d'un collider.
    fn contact_body(&self, collider: ColliderHandle) -> (BodyIdentity, Option<&RigidBody>) {
        let parent = self.inner.colliders.get(collider).and_then(|collider| collider.parent());
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
                    let kind = if sleeping { event_kind::SLEEP } else { event_kind::WAKE };
                    self.push_single_body_event(id, kind);
                }
            }
        }
    }

    /// Construit et met en file un événement portant sur un seul corps (§10.7).
    fn push_single_body_event(&mut self, id: BodyId, kind: u32) {
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
            data: 0,
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

    /// Applique les forces environnementales de chaque corps avant un sous-pas
    /// (§10.6). En tranche 2d : la traînée, relative au vent.
    ///
    /// Les forces `rapier` persistent d'un pas à l'autre ; on les remet à zéro
    /// puis on les repose à chaque sous-pas, à partir de la vitesse courante. La
    /// gravité, appliquée par `rapier`, n'est pas touchée. L'ordre de parcours
    /// est celui, déterministe, des corps `rapier` (R-1020).
    fn apply_aero_forces(&mut self) {
        if self.aero.is_empty() && self.fluid.is_none() {
            return;
        }
        let wind = self.wind;
        let fluid = self.fluid;
        let gravity = self.inner.gravity;
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
                let profile = self.aero.get(&BodyId::from_handle(handle));
                if profile.is_none() && fluid.is_none() {
                    return None;
                }
                let mut central = Vec3::ZERO;
                let mut at_points = Vec::new();
                if let Some(profile) = profile {
                    central = drag_force(body, wind, profile.drag_cd_a);
                    let pose = body.position();
                    at_points.extend(
                        profile
                            .lift_surfaces
                            .iter()
                            .filter_map(|surface| lift_force(body, pose, wind, surface)),
                    );
                }
                if let Some(fluid) = fluid {
                    if let Some(app) = buoyancy_force(body, &self.inner.colliders, gravity, &fluid) {
                        at_points.push(app);
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

    /// Indique si un corps dort, ou `None` s'il n'existe pas.
    #[must_use]
    pub fn is_sleeping(&self, id: BodyId) -> Option<bool> {
        self.inner.bodies.get(id.handle()).map(rapier3d::prelude::RigidBody::is_sleeping)
    }

    /// Indique si la CCD est activée sur un corps, ou `None` s'il n'existe pas.
    #[must_use]
    pub fn is_ccd_enabled(&self, id: BodyId) -> Option<bool> {
        self.inner.bodies.get(id.handle()).map(rapier3d::prelude::RigidBody::is_ccd_enabled)
    }

    /// Vitesse linéaire d'un corps, en m/s, ou `None` s'il n'existe pas.
    #[must_use]
    pub fn velocity(&self, id: BodyId) -> Option<Vec3> {
        self.inner.bodies.get(id.handle()).map(rapier3d::prelude::RigidBody::linvel)
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

    /// Produit l'état des corps mobiles identifiés (DM-08), pour le cycle de
    /// simulation (IF-03).
    ///
    /// Un seul état par corps **non statique** portant une identité : les corps
    /// statiques ne bougent pas, et un corps sans identité n'est pas routable
    /// vers Java. La position monde (`f64`) est recomposée depuis la simulation
    /// `f32` par l'origine flottante de la dimension (R-462). L'ordre suit
    /// l'itération déterministe de `rapier` (R-1020).
    ///
    /// Flags peuplés en tranche 4a : SLEEPING et IN_FLUID. Les autres
    /// (TOUCHING_GROUND, CLAMPED, DEFORMED, DAMAGED) s'ajoutent avec leur source
    /// (garde-fous R-180/181 puis M6).
    #[must_use]
    pub fn body_states(&self, origin: &FloatingOrigin) -> Vec<BodyState> {
        let mut states = Vec::new();
        for (handle, body) in self.inner.bodies.iter() {
            if !body.is_dynamic_or_kinematic() {
                continue;
            }
            let identity = match self.identity.get(&BodyId::from_handle(handle)) {
                Some(identity) if !identity.assembly.is_absent() => identity,
                _ => continue,
            };
            let pose = body.position();
            let world = origin.to_world(pose.translation);

            let mut flags = 0;
            if body.is_sleeping() {
                flags |= body_state_flags::SLEEPING;
            }
            if self.fluid.is_some_and(|fluid| self.body_in_fluid(handle, &fluid)) {
                flags |= body_state_flags::IN_FLUID;
            }

            states.push(BodyState {
                handle: identity.assembly,
                position: world.to_array(),
                rotation: pose.rotation.to_array(),
                lin_vel: body.linvel().to_array(),
                ang_vel: body.angvel().to_array(),
                flags,
            });
        }
        states
    }

    /// Indique si l'AABB du corps plonge sous la surface du fluide.
    fn body_in_fluid(&self, handle: RigidBodyHandle, fluid: &FluidEnvironment) -> bool {
        self.inner
            .bodies
            .get(handle)
            .and_then(|body| body.colliders().first().copied())
            .and_then(|collider| self.inner.colliders.get(collider))
            .is_some_and(|collider| collider.compute_aabb().mins.y < fluid.surface_y)
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

    /// Endort les corps dynamiques éveillés au-delà de `radius` autour de
    /// `center` (R-612). Renvoie le nombre endormi.
    ///
    /// **Endort, jamais ne supprime** (R-612) : un corps hors du rayon de
    /// simulation garde son état et se réveille s'il y rentre. L'itération suit
    /// l'ordre déterministe de `rapier` (R-1020).
    pub fn enforce_simulation_radius(&mut self, center: Vec3, radius: f32) -> usize {
        let radius_squared = radius * radius;
        let beyond: Vec<RigidBodyHandle> = self
            .inner
            .bodies
            .iter()
            .filter(|(_, body)| body.is_dynamic() && !body.is_sleeping())
            .filter(|(_, body)| (body.translation() - center).length_squared() > radius_squared)
            .map(|(handle, _)| handle)
            .collect();
        for handle in &beyond {
            if let Some(body) = self.inner.bodies.get_mut(*handle) {
                body.sleep();
            }
        }
        beyond.len()
    }

    /// Fait respecter le plafond de corps actifs (R-613) autour de `center`.
    ///
    /// Si plus de `cap` corps dynamiques sont éveillés, endort le surplus en
    /// commençant par **les plus éloignés** ; à distance égale, par **les plus
    /// anciens** — un tri stable sur l'ordre d'itération déterministe de `rapier`
    /// suffit à départager (R-1020). Renvoie la liste endormie, dans l'ordre où
    /// elle a été endormie, pour que l'appelant la journalise (R-613).
    pub fn enforce_active_body_cap(&mut self, center: Vec3, cap: usize) -> Vec<BodyId> {
        let mut awake: Vec<(RigidBodyHandle, f32)> = self
            .inner
            .bodies
            .iter()
            .filter(|(_, body)| body.is_dynamic() && !body.is_sleeping())
            .map(|(handle, body)| (handle, (body.translation() - center).length_squared()))
            .collect();
        if awake.len() <= cap {
            return Vec::new();
        }
        // Tri **stable** par distance décroissante : à distance égale, l'ordre
        // d'itération (déterministe, ~ ancienneté) reste, donc les plus anciens
        // partent en premier.
        awake.sort_by(|left, right| right.1.total_cmp(&left.1));
        let excess = awake.len() - cap;
        let mut slept = Vec::with_capacity(excess);
        for (handle, _) in awake.into_iter().take(excess) {
            if let Some(body) = self.inner.bodies.get_mut(handle) {
                body.sleep();
            }
            slept.push(BodyId::from_handle(handle));
        }
        slept
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
        self.inner.remove_body(id.handle()).is_some()
    }

    /// Nombre de corps dans le monde.
    #[must_use]
    pub fn body_count(&self) -> usize {
        self.inner.bodies.len()
    }
}

/// Construit la forme `parry` d'une [`Shape`], en validant §10.3.
///
/// Un seul chemin de construction, récursif pour [`Shape::Compound`] : primitive
/// ou collider composé, la validation est la même partout.
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
            let cloud: Vec<Vec3> = points.iter().map(|point| Vec3::from_array(*point)).collect();
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
    }
}

/// Traînée d'un corps : force centrale opposée à la vitesse relative au vent.
fn drag_force(body: &RigidBody, wind: Vec3, drag_cd_a: f32) -> Vec3 {
    if drag_cd_a <= 0.0 {
        return Vec3::ZERO;
    }
    let relative = body.linvel() - wind;
    let speed = relative.length();
    if speed <= f32::EPSILON {
        return Vec3::ZERO;
    }
    // F = −0.5·ρ·Cd·A·|v|·v.
    relative * (-0.5 * AIR_DENSITY * drag_cd_a * speed)
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

/// Poussée d'Archimède d'un corps (§10.6), appliquée au centre de poussée.
///
/// Le volume immergé est approché par la fraction des **huit coins de l'AABB**
/// sous la surface (§10.6). La force `−gravity · ρ · V` s'applique au centroïde
/// des coins immergés : sous COM quand le corps émerge à moitié, elle produit
/// alors le moment de redressement d'un bateau. `None` si aucun coin n'est
/// immergé.
fn buoyancy_force(
    body: &RigidBody,
    colliders: &ColliderSet,
    gravity: Vec3,
    fluid: &FluidEnvironment,
) -> Option<(Vec3, Vec3)> {
    if fluid.density <= 0.0 {
        return None;
    }
    let handle = *body.colliders().first()?;
    let aabb = colliders.get(handle)?.compute_aabb();
    let (lo, hi) = (aabb.mins, aabb.maxs);
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
    let mut submerged = 0u32;
    let mut sum = Vec3::ZERO;
    for corner in corners {
        if corner.y < fluid.surface_y {
            submerged += 1;
            sum += corner;
        }
    }
    if submerged == 0 {
        return None;
    }
    let extents = hi - lo;
    let volume = extents.x * extents.y * extents.z;
    let immersed = (submerged as f32 / 8.0) * volume;
    if immersed <= 0.0 {
        return None;
    }
    let center_of_buoyancy = sum / submerged as f32;
    Some((-gravity * (fluid.density * immersed), center_of_buoyancy))
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
