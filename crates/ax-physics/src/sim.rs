//! Pilote du cycle de simulation (IF-03), côté natif.
//!
//! Un [`SimDriver`] détient un [`PhysicsWorld`] par dimension (R-610) avec son
//! origine flottante, avance tous les mondes d'un tick, et récolte l'état
//! ([`BodyState`]) et les événements ([`PhysicsEvent`]) — la moitié « collect »
//! d'IF-03. L'enveloppe FFI (`ax-ffi`) n'y ajoute que la lecture des tampons et
//! les points d'entrée `axion_sim_*`.
//!
//! Les dimensions vivent dans une [`BTreeMap`] ordonnée par identifiant : leur
//! parcours est déterministe, donc l'ordre de `BodyState[]` et du lot
//! d'événements l'est aussi (R-1020).

use crate::activity::{self, ActivityLimits, ActivityReport, Decision, Observers, SleepCause};
use crate::body::{BodyCollider, BodyId, BodyKind, ContactMaterial, Shape};
use crate::config::PhysicsConfig;
use crate::debug::{select_outlines, DebugColliders};
use crate::forces::FluidEnvironment;
use crate::forces::FluidVolume;
use crate::governor::{DegradationLevel, DegradationTransition, Governor};
use crate::proxies::EntityProxy;
use crate::world::{BodyReports, PhysicsWorld, WorldCounters};
use ax_math::{DVec3, FloatingOrigin, Quat, Vec3};
use ax_model::dm::geometry::WorldTransform;
use ax_model::dm::handle::Handle;
use ax_model::dm::physics::{BodyState, PhysicsEvent};
use std::collections::BTreeMap;

/// La simulation d'une dimension : son monde et son origine flottante.
struct DimensionSim {
    world: PhysicsWorld,
    origin: FloatingOrigin,
}

/// Réglages du pilote, lus de la configuration reçue par IF-01 (ADR-123 §1).
///
/// Tout monde naît de ces réglages — et non des défauts de la fiche — si bien qu'un
/// administrateur qui change `sim.fixed_dt` ou `physics.gravity` les voit appliqués.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct SimSettings {
    /// Pas fixe, sous-pas, gravité par défaut (R-611), solveur, sommeil.
    pub physics: PhysicsConfig,
    /// `physics.contact_event_threshold`, en N·s (R-1012).
    pub contact_event_threshold: f32,
    /// `physics.max_events_per_tick` (R-1011).
    pub max_events_per_tick: usize,
    /// `sim.simulation_radius`, en blocs (R-612).
    pub simulation_radius: f32,
    /// `budgets.max_active_bodies` (R-613).
    pub max_active_bodies: usize,
    /// `budgets.sim_ns_per_tick`, en nanosecondes : le budget de FM-21.
    pub sim_budget_ns: u64,
}

impl Default for SimSettings {
    /// Les défauts de l'ANNEXE A.3.
    fn default() -> Self {
        Self {
            physics: PhysicsConfig::default(),
            contact_event_threshold: 0.5,
            max_events_per_tick: 4096,
            simulation_radius: 128.0,
            max_active_bodies: 2048,
            sim_budget_ns: 3_000_000,
        }
    }
}

/// Environnement d'une dimension (§10.6, R-611) : gravité, vent, fluide plat.
///
/// Gardé par le pilote, hors du monde : il survit à la destruction d'un monde vide
/// (R-610) et s'applique à sa recréation (ADR-123 §1).
#[derive(Debug, Clone, Copy, PartialEq)]
struct DimensionEnv {
    gravity: Vec3,
    wind: Vec3,
    fluid: Option<FluidEnvironment>,
}

/// Clé de routage d'un handle : génération en poids fort, index en poids faible.
pub(crate) fn handle_key(handle: Handle) -> u64 {
    (u64::from(handle.generation) << 32) | u64::from(handle.index)
}

/// Pilote de simulation : un monde physique par dimension (R-610).
///
/// Tient aussi le routage d'un handle d'assembly vers son corps et sa dimension,
/// pour appliquer les commandes de `SimIn` (ADR-114) qui ciblent un handle.
pub struct SimDriver {
    dimensions: BTreeMap<u64, DimensionSim>,
    /// handle → (dimension, corps). Consultée par clé, jamais itérée (R-1020).
    routes: BTreeMap<u64, (u64, BodyId)>,
    /// (dimension, section 16³) → corps statique de la tuile de collision monde
    /// (C-38). Ordonnée : parcours déterministe (R-1020).
    tiles: BTreeMap<(u64, [i32; 3]), BodyId>,
    /// Réglages dont naît tout monde (IF-01, ADR-123 §1).
    settings: SimSettings,
    /// Environnement de chaque dimension réglée par `SET_DIMENSION_ENV`, gardé hors
    /// du monde pour survivre à sa destruction (R-610).
    envs: BTreeMap<u64, DimensionEnv>,
    /// Observateurs du tick : positions monde finies des joueurs de chaque dimension
    /// (ADR-123 §2). État par tick, remis à zéro par [`begin_tick`](Self::begin_tick).
    observers: BTreeMap<u64, Vec<DVec3>>,
    /// Proxies d'entités vanilla déclarés pour le tick, par dimension (ADR-123 §5). État par
    /// tick, appliqué aux mondes par [`sync_entity_proxies`](Self::sync_entity_proxies).
    proxy_declarations: BTreeMap<u64, Vec<EntityProxy>>,
    /// Compteurs cumulés des mondes détruits (R-610), gardés dans les totaux : ils ne se
    /// perdent pas avec leur monde.
    retired: WorldCounters,
    /// Gouverneur FM-21 : la dégradation de la simulation sous surcharge (§25.5, §25.6).
    governor: Governor,
}

impl Default for SimDriver {
    /// Un pilote aux réglages par défaut de l'annexe A.3.
    fn default() -> Self {
        Self::with_settings(SimSettings::default())
    }
}

impl std::fmt::Debug for SimDriver {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Les mondes `rapier` ne sont pas `Debug` ; on n'expose que leur nombre.
        f.debug_struct("SimDriver")
            .field("dimensions", &self.dimensions.len())
            .field("routes", &self.routes.len())
            .field("tiles", &self.tiles.len())
            .finish()
    }
}

impl SimDriver {
    /// Crée un pilote sans aucune dimension, aux réglages par défaut de l'annexe A.3.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Crée un pilote sans aucune dimension, dont tout monde naîtra de `settings`
    /// (configuration reçue par IF-01, ADR-123 §1).
    #[must_use]
    pub fn with_settings(settings: SimSettings) -> Self {
        Self {
            dimensions: BTreeMap::new(),
            routes: BTreeMap::new(),
            tiles: BTreeMap::new(),
            settings,
            envs: BTreeMap::new(),
            observers: BTreeMap::new(),
            proxy_declarations: BTreeMap::new(),
            retired: WorldCounters::default(),
            governor: Governor::new(settings.sim_budget_ns),
        }
    }

    /// Réglages dont naît tout monde.
    #[must_use]
    pub fn settings(&self) -> &SimSettings {
        &self.settings
    }

    /// Construit le monde d'une dimension : réglages du pilote, puis environnement de
    /// la dimension s'il a été réglé — gravité de `physics.gravity` sinon (R-611).
    fn build_world(&self, dimension: u64) -> PhysicsWorld {
        let mut world = PhysicsWorld::new(self.settings.physics);
        world.set_contact_event_threshold(self.settings.contact_event_threshold);
        world.set_max_events_per_tick(self.settings.max_events_per_tick);
        world.set_degraded_solver(self.governor.level() >= DegradationLevel::Degraded1);
        if let Some(env) = self.envs.get(&dimension) {
            world.set_gravity(env.gravity);
            world.set_wind(env.wind);
            world.set_fluid(env.fluid);
        }
        world
    }

    /// Rend la simulation d'une dimension, en la créant au premier besoin (R-610) depuis
    /// les réglages du pilote, origine flottante à zéro.
    fn dimension_mut(&mut self, dimension: u64) -> &mut DimensionSim {
        if !self.dimensions.contains_key(&dimension) {
            let sim = DimensionSim {
                world: self.build_world(dimension),
                origin: FloatingOrigin::new(DVec3::ZERO),
            };
            self.dimensions.insert(dimension, sim);
        }
        self.dimensions
            .get_mut(&dimension)
            .expect("la dimension vient d'être insérée")
    }

    /// Rend le monde d'une dimension, en le créant au premier besoin (R-610).
    ///
    /// `config` et `origin` ne servent qu'à la création : sur une dimension déjà
    /// ouverte, le monde existant est rendu tel quel.
    pub fn world_or_create(
        &mut self,
        dimension: u64,
        config: PhysicsConfig,
        origin: FloatingOrigin,
    ) -> &mut PhysicsWorld {
        &mut self
            .dimensions
            .entry(dimension)
            .or_insert_with(|| DimensionSim {
                world: PhysicsWorld::new(config),
                origin,
            })
            .world
    }

    /// Rend le monde d'une dimension existante, ou `None`.
    #[must_use]
    pub fn world_mut(&mut self, dimension: u64) -> Option<&mut PhysicsWorld> {
        self.dimensions
            .get_mut(&dimension)
            .map(|sim| &mut sim.world)
    }

    /// Fixe l'origine flottante d'une dimension existante.
    pub fn set_origin(&mut self, dimension: u64, origin: FloatingOrigin) {
        if let Some(sim) = self.dimensions.get_mut(&dimension) {
            sim.origin = origin;
        }
    }

    /// Détruit la simulation d'une dimension (R-610) ; rend vrai si elle existait. Ses
    /// compteurs restent dans les totaux de [`counters`](Self::counters), son
    /// environnement dans le pilote.
    pub fn remove_dimension(&mut self, dimension: u64) -> bool {
        match self.dimensions.remove(&dimension) {
            Some(sim) => {
                self.retired += sim.world.counters();
                true
            }
            None => false,
        }
    }

    /// Détruit les mondes devenus sans objet (R-610, ADR-123 §4) : ceux qui ne portent
    /// aucun corps — assembly ni tuile de collision —, aucun volume de fluide, et dont la
    /// dimension n'a aucun observateur ce tick. À appeler en fin de tick, après la récolte.
    ///
    /// L'environnement de la dimension est gardé (ADR-123 §1) : son monde renaîtra au
    /// premier besoin tel qu'il était réglé. Le planificateur Java libère de lui-même les
    /// tuiles d'une dimension sans assembly ; le natif ne détruit donc jamais ce que Java
    /// croit encore posé. Rend le nombre de mondes détruits.
    pub fn release_idle_dimensions(&mut self) -> usize {
        let idle: Vec<u64> = self
            .dimensions
            .iter()
            .filter(|(dimension, sim)| {
                !self.observers.contains_key(dimension)
                    && sim.world.body_count() == 0
                    && sim.world.fluid_volume_count() == 0
            })
            .map(|(&dimension, _)| dimension)
            .collect();
        for &dimension in &idle {
            self.remove_dimension(dimension);
        }
        idle.len()
    }

    /// Nombre de dimensions actives.
    #[must_use]
    pub fn dimension_count(&self) -> usize {
        self.dimensions.len()
    }

    /// Compteurs cumulés de toutes les dimensions, détruites comprises : aucun ne décroît
    /// quand un monde disparaît (R-610).
    #[must_use]
    pub fn counters(&self) -> WorldCounters {
        self.dimensions
            .values()
            .fold(self.retired, |total, sim| total + sim.world.counters())
    }

    /// Pose (ou remplace) la tuile de collision du monde d'une section 16³ (C-38).
    ///
    /// Une tuile est un corps **statique** portant une boîte `Cuboid` par entrée de
    /// `boxes` — un corps statique n'a pas de plafond de formes filles, contrairement
    /// au `Compound` d'une assembly (§10.3). Les boîtes sont en blocs, **relatives à
    /// l'origine de la section** (`section × 16`) ; leur pose monde en découle, ramenée
    /// au repère local par l'origine flottante de la dimension (créée au besoin, R-610).
    ///
    /// Toutes les boîtes portent le **matériau physique dominant** de la section
    /// (`material`, R-643) : sa friction et sa restitution alimentent la friction des
    /// roues et l'énergie des impacts contre le monde.
    ///
    /// Rend vrai si une tuile a été posée. Une liste vide — ou dont aucune boîte n'est
    /// finie et non dégénérée — **retire** la tuile existante et rend faux : une section
    /// sans collision n'a pas de corps.
    pub fn set_world_tile(
        &mut self,
        dimension: u64,
        section: [i32; 3],
        boxes: &[[f32; 6]],
        material: ContactMaterial,
    ) -> bool {
        // Une tuile est remplacée en bloc : l'ancienne part d'abord.
        self.remove_world_tile(dimension, section);

        let mut colliders = Vec::with_capacity(boxes.len());
        for b in boxes {
            let half = [
                (b[3] - b[0]) * 0.5,
                (b[4] - b[1]) * 0.5,
                (b[5] - b[2]) * 0.5,
            ];
            // Une boîte dégénérée ou non finie est ignorée, plutôt que de faire
            // refuser toute la tuile (donnée venue du monde, jamais crue sur parole).
            if half.iter().any(|h| !h.is_finite() || *h <= 0.0) {
                continue;
            }
            let center = Vec3::new(
                (b[0] + b[3]) * 0.5,
                (b[1] + b[4]) * 0.5,
                (b[2] + b[5]) * 0.5,
            );
            colliders.push(BodyCollider {
                shape: Shape::Cuboid { half_extents: half },
                // Sans effet : un corps statique a une masse infinie.
                density: 1.0,
                material,
                translation: center,
                rotation: Quat::IDENTITY,
            });
        }
        if colliders.is_empty() {
            return false;
        }

        let sim = self.dimension_mut(dimension);
        let world_origin = DVec3::new(
            f64::from(section[0]) * 16.0,
            f64::from(section[1]) * 16.0,
            f64::from(section[2]) * 16.0,
        );
        let local = sim.origin.to_local(world_origin);
        let Ok(body) = sim
            .world
            .add_assembly(BodyKind::Static, local, Quat::IDENTITY, &colliders)
        else {
            return false;
        };
        self.tiles.insert((dimension, section), body);
        true
    }

    /// Pose (ou remplace) une tuile de collision du monde sous forme de **champ de
    /// hauteurs** (C-38, R-641) : le repli d'une section trop découpée pour tenir en
    /// boîtes. Une tuile champ de hauteurs est, comme une tuile en boîtes, un corps
    /// **statique** — INV-13 (R-970) réserve les formes concaves au décor non dynamique.
    ///
    /// Le champ est **centré** sur son collider ; on le décale d'une demi-étendue
    /// (`scale.x/2`, `scale.z/2`) pour que son empreinte parte du coin `[0, 0]` de la
    /// section, comme les boîtes de [`set_world_tile`](Self::set_world_tile). La hauteur
    /// monde d'un sommet vaut `origine_y_section + height × scale.y`. Les hauteurs sont
    /// en disposition ligne-major (`heights[row * cols + col]`, `row` sur z, `col` sur x).
    ///
    /// Le champ porte le **matériau physique dominant** de la section (`material`,
    /// R-643), comme une tuile en boîtes.
    ///
    /// Rend vrai si la tuile a été posée. Un champ invalide — moins de 2 lignes/colonnes,
    /// taille incohérente, échelle x/z non positive, ou hauteur non finie — **retire** la
    /// tuile existante et rend faux, sans panique (donnée venue du monde, jamais crue sur
    /// parole).
    // Sept paramètres tous distincts et irréductibles (où, géométrie du champ, matériau) :
    // les envelopper dans une struct pour un unique setter serait une abstraction sans gain.
    #[allow(clippy::too_many_arguments)]
    pub fn set_world_tile_heightfield(
        &mut self,
        dimension: u64,
        section: [i32; 3],
        rows: u32,
        cols: u32,
        heights: Vec<f32>,
        scale: [f32; 3],
        material: ContactMaterial,
    ) -> bool {
        // Une tuile est remplacée en bloc : l'ancienne part d'abord.
        self.remove_world_tile(dimension, section);

        let collider = BodyCollider {
            shape: Shape::Heightfield {
                rows,
                cols,
                heights,
                scale,
            },
            // Sans effet : un corps statique a une masse infinie.
            density: 1.0,
            material,
            translation: Vec3::new(scale[0] * 0.5, 0.0, scale[2] * 0.5),
            rotation: Quat::IDENTITY,
        };

        let sim = self.dimension_mut(dimension);
        let world_origin = DVec3::new(
            f64::from(section[0]) * 16.0,
            f64::from(section[1]) * 16.0,
            f64::from(section[2]) * 16.0,
        );
        let local = sim.origin.to_local(world_origin);
        // Un champ invalide fait échouer `add_assembly` (validé par `shared_shape_of`) :
        // la tuile reste retirée, aucun corps n'entre.
        let Ok(body) = sim
            .world
            .add_assembly(BodyKind::Static, local, Quat::IDENTITY, &[collider])
        else {
            return false;
        };
        self.tiles.insert((dimension, section), body);
        true
    }

    /// Retire la tuile de collision d'une section ; rend vrai si elle existait.
    pub fn remove_world_tile(&mut self, dimension: u64, section: [i32; 3]) -> bool {
        let Some(body) = self.tiles.remove(&(dimension, section)) else {
            return false;
        };
        if let Some(sim) = self.dimensions.get_mut(&dimension) {
            sim.world.remove_body(body);
        }
        true
    }

    /// Nombre de tuiles de collision monde actives, toutes dimensions confondues.
    #[must_use]
    pub fn world_tile_count(&self) -> usize {
        self.tiles.len()
    }

    /// Pose (ou remplace) les **volumes de fluide** d'une section 16³ (C-38, R-642) :
    /// des boîtes d'eau (« capteurs » de flottabilité), et non de la collision solide.
    ///
    /// Comme pour [`set_world_tile`](Self::set_world_tile), les boîtes sont en blocs,
    /// **relatives à l'origine de la section** (`section × 16`), et ramenées au repère
    /// local par l'origine flottante de la dimension (créée au besoin, R-610). `density`
    /// est la masse volumique du fluide (eau douce ≈ 1000), en kg/m³.
    ///
    /// Rend vrai si des volumes ont été posés. Une densité non positive, une liste vide,
    /// ou des boîtes toutes dégénérées/non finies **retirent** les volumes de la section
    /// et rendent faux (donnée venue du monde, jamais crue sur parole).
    pub fn set_world_fluid_tile(
        &mut self,
        dimension: u64,
        section: [i32; 3],
        boxes: &[[f32; 6]],
        density: f32,
    ) -> bool {
        let sim = self.dimension_mut(dimension);
        // Une densité non finie ou non positive n'est pas un fluide : on retire.
        if !density.is_finite() || density <= 0.0 {
            sim.world.remove_fluid_volumes(section);
            return false;
        }
        let section_origin = DVec3::new(
            f64::from(section[0]) * 16.0,
            f64::from(section[1]) * 16.0,
            f64::from(section[2]) * 16.0,
        );
        let mut volumes = Vec::with_capacity(boxes.len());
        for b in boxes {
            let min = sim.origin.to_local(
                section_origin + DVec3::new(f64::from(b[0]), f64::from(b[1]), f64::from(b[2])),
            );
            let max = sim.origin.to_local(
                section_origin + DVec3::new(f64::from(b[3]), f64::from(b[4]), f64::from(b[5])),
            );
            // Une boîte dégénérée ou non finie est ignorée, pas toute la tuile.
            if !min.is_finite() || !max.is_finite() {
                continue;
            }
            if max.x <= min.x || max.y <= min.y || max.z <= min.z {
                continue;
            }
            volumes.push(FluidVolume { min, max, density });
        }
        if volumes.is_empty() {
            sim.world.remove_fluid_volumes(section);
            return false;
        }
        sim.world.set_fluid_volumes(section, volumes);
        true
    }

    /// Retire les volumes de fluide d'une section ; rend vrai s'ils existaient.
    pub fn remove_world_fluid_tile(&mut self, dimension: u64, section: [i32; 3]) -> bool {
        self.dimensions
            .get_mut(&dimension)
            .is_some_and(|sim| sim.world.remove_fluid_volumes(section))
    }

    /// Nombre de sections portant des volumes de fluide, toutes dimensions confondues.
    #[must_use]
    pub fn world_fluid_tile_count(&self) -> usize {
        self.dimensions
            .values()
            .map(|sim| sim.world.fluid_volume_count())
            .sum()
    }

    /// Ouvre un tick : remet à zéro l'état qui ne vaut que pour un tick. Une dimension
    /// sans `SET_OBSERVERS` dans le flux du tick n'a aucun observateur (ADR-123 §2), sans
    /// `SET_ENTITY_PROXIES` aucun proxy (§5).
    pub fn begin_tick(&mut self) {
        self.observers.clear();
        self.proxy_declarations.clear();
    }

    /// Déclare les proxies des entités vanilla d'une dimension pour le tick (R-614,
    /// ADR-123 §5). Remplace une déclaration antérieure du même tick ; les mondes les
    /// reçoivent à [`sync_entity_proxies`](Self::sync_entity_proxies).
    pub fn set_entity_proxies(&mut self, dimension: u64, proxies: Vec<EntityProxy>) {
        self.proxy_declarations.insert(dimension, proxies);
    }

    /// Proxies déclarés pour une dimension ce tick ; vide sans déclaration.
    #[must_use]
    pub fn entity_proxies(&self, dimension: u64) -> &[EntityProxy] {
        self.proxy_declarations
            .get(&dimension)
            .map_or(&[], Vec::as_slice)
    }

    /// Applique à chaque monde les proxies déclarés ce tick (R-614, ADR-123 §5), à appeler
    /// après les commandes du tick et avant le pas : une entité déclarée a son corps
    /// cinématique, posé et lancé à sa vitesse ; une entité qui ne l'est plus perd le sien ;
    /// une dimension sans déclaration n'en garde aucun. N'ouvre aucun monde : sans
    /// assembly, une entité n'a rien à heurter.
    pub fn sync_entity_proxies(&mut self) {
        for (dimension, sim) in &mut self.dimensions {
            let declared = self
                .proxy_declarations
                .get(dimension)
                .map_or(&[][..], Vec::as_slice);
            sim.world.sync_entity_proxies(declared, &sim.origin);
        }
    }

    /// Déclare les observateurs d'une dimension pour le tick : les positions monde de ses
    /// joueurs (ADR-123 §2). Remplace une déclaration antérieure du même tick ; les
    /// positions non finies sont écartées (donnée venue du jeu, jamais crue sur parole).
    ///
    /// N'ouvre aucun monde : un joueur seul n'a rien à simuler (R-610).
    pub fn set_observers(&mut self, dimension: u64, positions: &[DVec3]) {
        let finite: Vec<DVec3> = positions
            .iter()
            .copied()
            .filter(|position| position.is_finite())
            .collect();
        if finite.is_empty() {
            self.observers.remove(&dimension);
        } else {
            self.observers.insert(dimension, finite);
        }
    }

    /// Observateurs déclarés pour une dimension ce tick, en positions monde ; vide sans
    /// déclaration.
    #[must_use]
    pub fn observers(&self, dimension: u64) -> &[DVec3] {
        self.observers.get(&dimension).map_or(&[], Vec::as_slice)
    }

    /// Applique R-612 et R-613 autour des observateurs du tick (ADR-123 §3), à appeler
    /// après les commandes du tick et avant [`advance_all`](Self::advance_all) : un corps
    /// hors du rayon de tout joueur ne bouge pas ce tick.
    ///
    /// Le rayon se mesure dans chaque dimension depuis ses joueurs ; le plafond de corps
    /// actifs est un budget serveur, compté sur toutes les dimensions. Un corps endormi
    /// par cette gestion se réveille à portée, s'il a sa place sous le plafond, avec la
    /// vitesse qu'il avait ; un corps endormi naturellement n'est jamais réveillé.
    /// Endort, jamais ne supprime. Rend les comptes du tick, que la frontière journalise.
    pub fn manage_activity(&mut self) -> ActivityReport {
        let limits = self.activity_limits();
        let mut candidates = Vec::new();
        for (&dimension, sim) in &mut self.dimensions {
            let positions = self
                .observers
                .get(&dimension)
                .map_or(&[][..], Vec::as_slice);
            let origin = &sim.origin;
            let observers = Observers::new(
                positions.iter().map(|position| origin.to_local(*position)),
                limits.nominal_radius,
            );
            sim.world
                .survey_activity(dimension, &observers, &mut candidates);
        }

        let mut report = ActivityReport::default();
        for (index, decision) in activity::plan(&candidates, limits) {
            let candidate = &candidates[index];
            let Some(sim) = self.dimensions.get_mut(&candidate.dimension) else {
                continue;
            };
            match decision {
                Decision::Sleep { cause, budget } => {
                    sim.world.force_sleep(candidate.body, budget);
                    match cause {
                        SleepCause::Radius => report.slept_by_radius += 1,
                        SleepCause::Cap => report.slept_by_cap += 1,
                    }
                    report.slept_by_budget += usize::from(budget);
                }
                Decision::Wake => {
                    sim.world.end_forced_sleep(candidate.body);
                    report.woken += 1;
                }
            }
        }
        report
    }

    /// Rayon et plafond du tick : ceux des réglages, que nulle dégradation ne réduit
    /// encore.
    fn activity_limits(&self) -> ActivityLimits {
        let level = self.governor.level();
        let radius = self.settings.simulation_radius;
        let cap = self.settings.max_active_bodies;
        ActivityLimits {
            // Palier 2 : rayon de simulation −25 %, les corps lointains s'endorment.
            radius: if level >= DegradationLevel::Degraded2 {
                radius * 0.75
            } else {
                radius
            },
            nominal_radius: radius,
            // Palier 3 : plafond de corps actifs −50 %, arrondi au supérieur — jamais nul
            // s'il ne l'était pas.
            cap: if level >= DegradationLevel::Degraded3 {
                cap.div_ceil(2)
            } else {
                cap
            },
            nominal_cap: cap,
        }
    }

    /// Nourrit le gouverneur FM-21 de la durée mesurée d'un tick de simulation (§25.5) et
    /// applique son palier s'il change (§25.6) : le solveur de chaque monde au palier 1,
    /// le rayon et le plafond de la gestion d'activité aux paliers 2 et 3, dès le
    /// prochain `manage_activity`. Rend la transition et sa cause, que la frontière
    /// journalise (R-1880).
    pub fn record_tick_duration(&mut self, nanos: u64) -> Option<DegradationTransition> {
        let transition = self.governor.observe(nanos)?;
        let degraded_solver = transition.to >= DegradationLevel::Degraded1;
        for sim in self.dimensions.values_mut() {
            sim.world.set_degraded_solver(degraded_solver);
        }
        Some(transition)
    }

    /// Palier de dégradation courant (SM-02, FM-21).
    #[must_use]
    pub fn degradation_level(&self) -> DegradationLevel {
        self.governor.level()
    }

    /// p95 de la dernière fenêtre de mesure du gouverneur, en nanosecondes ; `None` avant
    /// la première fenêtre complète.
    #[must_use]
    pub fn last_p95_ns(&self) -> Option<u64> {
        self.governor.last_p95_ns()
    }

    /// Avance toutes les dimensions du temps réel écoulé.
    pub fn advance_all(&mut self, frame_dt: f32) {
        for sim in self.dimensions.values_mut() {
            sim.world.advance(frame_dt);
        }
    }

    /// Récolte l'état des corps mobiles de toutes les dimensions (DM-08) — vue
    /// des seuls états de [`collect_reports`](Self::collect_reports).
    #[must_use]
    pub fn collect_states(&self) -> Vec<BodyState> {
        self.collect_reports().states
    }

    /// Géométrie de l'overlay `colliders` d'une dimension (C-67, ADR-121) : les
    /// corps d'assembly les plus proches de `camera` (position monde) d'abord, au
    /// plus `max_segments` segments en repère du corps ; un corps qui ne tient
    /// pas est omis et compté. Dimension inconnue : rien. Lecture seule.
    #[must_use]
    pub fn debug_colliders(
        &self,
        dimension: u64,
        camera: DVec3,
        max_segments: u32,
    ) -> DebugColliders {
        match self.dimensions.get(&dimension) {
            Some(sim) => select_outlines(
                sim.world.collider_outlines(&sim.origin),
                camera,
                max_segments,
            ),
            None => DebugColliders::default(),
        }
    }

    /// Récolte l'état et l'emprise des corps mobiles de toutes les dimensions
    /// (DM-08, ADR-120).
    ///
    /// Concaténés dans l'ordre déterministe des dimensions puis des corps
    /// (R-1020), états et emprises ensemble : l'appariement de chaque dimension
    /// est conservé.
    #[must_use]
    pub fn collect_reports(&self) -> BodyReports {
        let mut reports = BodyReports::default();
        for sim in self.dimensions.values() {
            reports.append(sim.world.body_reports(&sim.origin));
        }
        reports
    }

    /// Vide et concatène les lots d'événements de toutes les dimensions (§10.7).
    #[must_use]
    pub fn drain_events(&mut self) -> Vec<PhysicsEvent> {
        let mut events = Vec::new();
        for sim in self.dimensions.values_mut() {
            events.extend(sim.world.drain_events());
        }
        events
    }

    /// Attache une identité d'assembly à un corps et l'enregistre au routage.
    ///
    /// C'est ce que fera `CREATE_ASSEMBLY` une fois C-32 disponible ; les tests
    /// s'en servent pour poser des corps routables.
    pub fn register_body(
        &mut self,
        dimension: u64,
        handle: Handle,
        body: BodyId,
        node: u32,
        material: u16,
    ) {
        let exists = if let Some(sim) = self.dimensions.get_mut(&dimension) {
            sim.world.set_body_identity(body, handle, node, material);
            true
        } else {
            false
        };
        if exists {
            self.routes.insert(handle_key(handle), (dimension, body));
        }
    }

    /// Crée une assembly (`CREATE_ASSEMBLY`, IF-03) : un corps dans la dimension
    /// (créée au besoin, R-610), à la pose monde `spawn` ramenée au repère local
    /// par l'origine flottante, puis l'enregistre au routage.
    ///
    /// Rend le corps créé, ou `None` si une forme est refusée par le monde. Les
    /// colliders sont déjà convertis depuis la section `PHYS` compilée par la
    /// frontière (ax-ffi), qui seule dépend d'`ax-asset` ; chacun porte sa densité,
    /// d'où la masse et le centre de masse du corps (R-622).
    pub fn create_assembly(
        &mut self,
        dimension: u64,
        handle: Handle,
        spawn: WorldTransform,
        kind: BodyKind,
        colliders: &[BodyCollider],
    ) -> Option<BodyId> {
        let sim = self.dimension_mut(dimension);
        let local = sim.origin.to_local(DVec3::from_array(spawn.position));
        let rotation = Quat::from_array(spawn.rotation);
        let body = sim
            .world
            .add_assembly(kind, local, rotation, colliders)
            .ok()?;
        // L'emprunt de `sim` s'achève ici ; le routage réutilise `register_body`.
        self.register_body(dimension, handle, body, 0, 0);
        Some(body)
    }

    /// Applique `REMOVE_ASSEMBLY` : retire le corps et son routage. Rend vrai s'il
    /// existait.
    pub fn apply_remove_assembly(&mut self, handle: Handle) -> bool {
        let Some((dimension, body)) = self.routes.remove(&handle_key(handle)) else {
            return false;
        };
        self.dimensions
            .get_mut(&dimension)
            .is_some_and(|sim| sim.world.remove_body(body))
    }

    /// Applique `SET_KINEMATIC` : la pose monde imposée est ramenée au repère
    /// local de la dimension par l'origine flottante.
    pub fn apply_set_kinematic(&mut self, handle: Handle, world_position: DVec3, rotation: Quat) {
        if let Some(&(dimension, body)) = self.routes.get(&handle_key(handle)) {
            if let Some(sim) = self.dimensions.get_mut(&dimension) {
                let local = sim.origin.to_local(world_position);
                sim.world.set_kinematic_pose(body, local, rotation);
            }
        }
    }

    /// Applique `APPLY_IMPULSE`.
    pub fn apply_impulse(&mut self, handle: Handle, impulse: Vec3, point: Vec3, at_point: bool) {
        if let Some(&(dimension, body)) = self.routes.get(&handle_key(handle)) {
            if let Some(sim) = self.dimensions.get_mut(&dimension) {
                sim.world.apply_impulse(body, impulse, point, at_point);
            }
        }
    }

    /// Applique `SET_DIMENSION_ENV` : gravité, vent et fluide d'une dimension,
    /// créée au besoin (§10.6).
    ///
    /// L'environnement est aussi **gardé par le pilote** : un monde détruit faute
    /// d'assembly et de joueur (R-610) le retrouve à sa recréation (ADR-123 §1).
    pub fn apply_dimension_env(
        &mut self,
        dimension: u64,
        gravity: Vec3,
        wind: Vec3,
        fluid: Option<FluidEnvironment>,
    ) {
        self.envs.insert(
            dimension,
            DimensionEnv {
                gravity,
                wind,
                fluid,
            },
        );
        let sim = self.dimension_mut(dimension);
        sim.world.set_gravity(gravity);
        sim.world.set_wind(wind);
        sim.world.set_fluid(fluid);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::body::{BodyCollider, BodyKind, Shape};
    use crate::Handle;
    use ax_math::{DVec3, Quat, Vec3};

    fn config() -> PhysicsConfig {
        PhysicsConfig::new(1.0 / 60.0, 4).unwrap()
    }

    #[test]
    fn t300_les_dimensions_se_creent_au_premier_besoin() {
        let mut driver = SimDriver::new();
        assert_eq!(driver.dimension_count(), 0);
        driver.world_or_create(0, config(), FloatingOrigin::new(DVec3::ZERO));
        driver.world_or_create(0, config(), FloatingOrigin::new(DVec3::ZERO));
        assert_eq!(driver.dimension_count(), 1, "un second accès ne recrée pas");
        assert!(driver.world_mut(0).is_some());
        assert!(driver.world_mut(7).is_none());
        assert!(driver.remove_dimension(0));
        assert!(!driver.remove_dimension(0));
    }

    #[test]
    fn t300_un_monde_sans_corps_fluide_ni_joueur_est_detruit_en_fin_de_tick() {
        // R-610 : `SET_DIMENSION_ENV` ouvre le monde ; sans rien à simuler ni joueur, la
        // fin du tick le détruit. L'environnement reste, et revient avec le monde.
        let mut driver = SimDriver::new();
        driver.apply_dimension_env(3, Vec3::new(0.0, -2.0, 0.0), Vec3::ZERO, None);
        assert_eq!(driver.dimension_count(), 1);

        assert_eq!(driver.release_idle_dimensions(), 1);
        assert_eq!(driver.dimension_count(), 0);
        assert_eq!(driver.release_idle_dimensions(), 0);

        bille(&mut driver, 3);
        let vy = vitesse_apres_une_seconde(&mut driver);
        assert!(
            (vy + 2.0).abs() < 0.1,
            "le monde recréé retrouve sa gravité : {vy}"
        );
    }

    #[test]
    fn t300_un_monde_vit_tant_qu_il_porte_une_assembly_une_tuile_un_fluide_ou_un_joueur() {
        let mut driver = SimDriver::new();
        bille(&mut driver, 1);
        assert!(driver.set_world_tile(
            2,
            [0, 0, 0],
            &[[0.0, 0.0, 0.0, 1.0, 1.0, 1.0]],
            ContactMaterial::default()
        ));
        assert!(driver.set_world_fluid_tile(
            3,
            [0, 0, 0],
            &[[0.0, 0.0, 0.0, 4.0, 4.0, 4.0]],
            1000.0
        ));
        driver.apply_dimension_env(4, Vec3::new(0.0, -9.81, 0.0), Vec3::ZERO, None);
        driver.begin_tick();
        driver.set_observers(4, &[DVec3::ZERO]);

        assert_eq!(driver.release_idle_dimensions(), 0);
        assert_eq!(driver.dimension_count(), 4);

        // Chaque raison retirée, son monde part — et lui seul.
        assert!(driver.apply_remove_assembly(Handle::new(1, 1)));
        assert_eq!(driver.release_idle_dimensions(), 1);
        assert!(driver.world_mut(1).is_none());
        assert!(driver.remove_world_tile(2, [0, 0, 0]));
        assert!(driver.remove_world_fluid_tile(3, [0, 0, 0]));
        assert_eq!(driver.release_idle_dimensions(), 2);
        assert!(driver.world_mut(4).is_some(), "son joueur le garde ce tick");
        // Un nouveau tick sans déclaration : la dimension n'a plus de joueur.
        driver.begin_tick();
        assert_eq!(driver.release_idle_dimensions(), 1);
        assert_eq!(driver.dimension_count(), 0);
    }

    #[test]
    fn t300_les_compteurs_d_un_monde_detruit_restent_dans_les_totaux() {
        // Un compteur de perte ne décroît jamais (R-1011) : détruire un monde n'efface pas
        // ce qu'il a compté.
        let mut driver = SimDriver::with_settings(SimSettings {
            max_events_per_tick: 1,
            ..SimSettings::default()
        });
        bille(&mut driver, 0);
        let (_, body) = driver.routes[&handle_key(Handle::new(1, 1))];
        let world = driver.world_mut(0).unwrap();
        // Deux événements pour une seule place : le second est perdu, et compté.
        world.force_sleep(body, true);
        world.end_forced_sleep(body);
        world.force_sleep(body, true);
        assert_eq!(driver.counters().dropped_events, 1);

        assert!(driver.apply_remove_assembly(Handle::new(1, 1)));
        assert_eq!(driver.release_idle_dimensions(), 1);

        assert_eq!(driver.counters().dropped_events, 1);
    }

    /// Une bille dynamique créée en (0, 100, 0) de la dimension 0, au handle (1, 1).
    fn bille(driver: &mut SimDriver, dimension: u64) {
        driver.create_assembly(
            dimension,
            Handle::new(1, 1),
            WorldTransform {
                position: [0.0, 100.0, 0.0],
                rotation: [0.0, 0.0, 0.0, 1.0],
            },
            BodyKind::Dynamic,
            &[BodyCollider {
                shape: Shape::Ball { radius: 0.5 },
                density: 1000.0,
                material: ContactMaterial::default(),
                translation: Vec3::ZERO,
                rotation: Quat::IDENTITY,
            }],
        );
    }

    /// Vitesse verticale de la bille après une seconde simulée, en sous-pas de 1/60.
    fn vitesse_apres_une_seconde(driver: &mut SimDriver) -> f32 {
        for _ in 0..60 {
            driver.advance_all(1.0 / 60.0);
        }
        driver.collect_states()[0].lin_vel[1]
    }

    #[test]
    fn t301_la_gravite_d_un_monde_vient_des_reglages() {
        // R-611 / ADR-123 §1 : un monde naît des réglages du pilote — `physics.gravity`
        // configuré —, et non des défauts de la fiche.
        let mut physics = PhysicsConfig::default();
        physics.gravity = Vec3::new(0.0, -3.0, 0.0);
        let mut driver = SimDriver::with_settings(SimSettings {
            physics,
            ..SimSettings::default()
        });
        bille(&mut driver, 0);
        let vy = vitesse_apres_une_seconde(&mut driver);
        assert!(
            (vy + 3.0).abs() < 0.1,
            "vitesse après 1 s sous −3 m/s² : {vy}"
        );

        // Les défauts de l'annexe, eux, gardent −9,81.
        let mut defaut = SimDriver::new();
        bille(&mut defaut, 0);
        let vy = vitesse_apres_une_seconde(&mut defaut);
        assert!(
            (vy + 9.81).abs() < 0.2,
            "vitesse après 1 s sous −9,81 m/s² : {vy}"
        );
    }

    #[test]
    fn t301_l_environnement_d_une_dimension_survit_a_son_monde() {
        // ADR-123 §1 : `SET_DIMENSION_ENV` est gardé par le pilote ; un monde détruit
        // (R-610) le retrouve à sa recréation.
        let mut driver = SimDriver::new();
        driver.apply_dimension_env(4, Vec3::new(0.0, -2.0, 0.0), Vec3::ZERO, None);
        assert!(driver.remove_dimension(4));
        bille(&mut driver, 4);
        let vy = vitesse_apres_une_seconde(&mut driver);
        assert!(
            (vy + 2.0).abs() < 0.1,
            "la gravité réglée revient avec le monde : {vy}"
        );

        // Une autre dimension n'en hérite pas.
        let mut autre = SimDriver::new();
        autre.apply_dimension_env(4, Vec3::new(0.0, -2.0, 0.0), Vec3::ZERO, None);
        bille(&mut autre, 5);
        let vy = vitesse_apres_une_seconde(&mut autre);
        assert!(
            (vy + 9.81).abs() < 0.2,
            "une dimension sans environnement : {vy}"
        );
    }

    /// Une bille dynamique de la dimension, au handle et à la position monde donnés.
    fn bille_en(
        driver: &mut SimDriver,
        dimension: u64,
        handle: Handle,
        position: [f64; 3],
    ) -> BodyId {
        driver
            .create_assembly(
                dimension,
                handle,
                WorldTransform {
                    position,
                    rotation: [0.0, 0.0, 0.0, 1.0],
                },
                BodyKind::Dynamic,
                &[BodyCollider {
                    shape: Shape::Ball { radius: 0.5 },
                    density: 1000.0,
                    material: ContactMaterial::default(),
                    translation: Vec3::ZERO,
                    rotation: Quat::IDENTITY,
                }],
            )
            .expect("une bille est valide")
    }

    /// Un pilote sans gravité, au pas de 1/120 s, de budget 1 µs : 2 µs le dépassent,
    /// 0,1 µs est le calme.
    fn pilote_degradable(radius: f32, cap: usize) -> SimDriver {
        let mut physics = PhysicsConfig::new(1.0 / 120.0, 4).unwrap();
        physics.gravity = Vec3::ZERO;
        SimDriver::with_settings(SimSettings {
            physics,
            simulation_radius: radius,
            max_active_bodies: cap,
            sim_budget_ns: 1_000,
            ..SimSettings::default()
        })
    }

    /// Nourrit le gouverneur de `count` fenêtres complètes de ticks à `nanos`.
    fn fenetres(driver: &mut SimDriver, count: usize, nanos: u64) {
        for _ in 0..count * crate::governor::WINDOW_TICKS {
            driver.record_tick_duration(nanos);
        }
    }

    /// Un tick dont le seul joueur de la dimension 0 se tient à l'origine.
    fn gestion_autour_de_l_origine(driver: &mut SimDriver) -> ActivityReport {
        driver.begin_tick();
        driver.set_observers(0, &[DVec3::ZERO]);
        driver.manage_activity()
    }

    #[test]
    fn t307_le_palier_1_retire_une_iteration_et_un_sous_pas_a_chaque_monde() {
        let mut driver = pilote_degradable(128.0, 2048);
        bille(&mut driver, 0);
        let world = driver.world_mut(0).unwrap();
        assert_eq!((world.solver_iterations(), world.max_substeps()), (4, 4));
        assert_eq!(
            world.advance(1.0 / 20.0),
            4,
            "six sous-pas demandés, quatre permis"
        );

        fenetres(&mut driver, 3, 2_000);

        assert_eq!(driver.degradation_level(), DegradationLevel::Degraded1);
        let world = driver.world_mut(0).unwrap();
        assert_eq!((world.solver_iterations(), world.max_substeps()), (3, 3));
        assert_eq!(world.advance(1.0 / 20.0), 3);
        // Un monde né pendant la dégradation la porte aussi.
        driver.apply_dimension_env(7, Vec3::ZERO, Vec3::ZERO, None);
        assert_eq!(driver.world_mut(7).unwrap().max_substeps(), 3);

        // Trente secondes de calme : le palier se lève et rend la configuration.
        fenetres(&mut driver, 6, 100);
        assert_eq!(driver.degradation_level(), DegradationLevel::Normal);
        assert_eq!(driver.world_mut(0).unwrap().max_substeps(), 4);
        assert_eq!(driver.world_mut(7).unwrap().solver_iterations(), 4);
    }

    #[test]
    fn t307_au_palier_2_les_corps_lointains_s_endorment_et_se_signalent() {
        use ax_model::dm::physics::{event_data, event_kind};
        let mut driver = pilote_degradable(100.0, 2048);
        // À 50 et 90 blocs du joueur : le rayon réduit à 75 n'en garde qu'une.
        let proche = bille_en(&mut driver, 0, Handle::new(1, 1), [50.0, 0.0, 0.0]);
        let lointaine = bille_en(&mut driver, 0, Handle::new(2, 1), [90.0, 0.0, 0.0]);
        fenetres(&mut driver, 6, 2_000);
        assert_eq!(driver.degradation_level(), DegradationLevel::Degraded2);

        let report = gestion_autour_de_l_origine(&mut driver);

        assert_eq!((report.slept_by_radius, report.slept_by_budget), (1, 1));
        let world = driver.world_mut(0).unwrap();
        assert_eq!(world.is_sleeping(proche), Some(false));
        assert_eq!(world.is_sleeping(lointaine), Some(true));
        let clamped: Vec<PhysicsEvent> = world
            .drain_events()
            .into_iter()
            .filter(|event| event.kind == event_kind::CLAMPED)
            .collect();
        assert_eq!(clamped.len(), 1);
        assert_eq!(
            (clamped[0].assembly_a, clamped[0].data),
            (Handle::new(2, 1), event_data::CLAMPED_BUDGET)
        );

        // Le palier levé (30 s, puis 10 s par palier), elle revient à portée et repart.
        fenetres(&mut driver, 8, 100);
        assert_eq!(driver.degradation_level(), DegradationLevel::Normal);
        assert_eq!(gestion_autour_de_l_origine(&mut driver).woken, 1);
    }

    #[test]
    fn t307_au_palier_3_le_plafond_de_corps_actifs_est_divise_par_deux() {
        let mut driver = pilote_degradable(128.0, 4);
        let corps: Vec<BodyId> = (1..=4)
            .map(|i| {
                bille_en(
                    &mut driver,
                    0,
                    Handle::new(i, 1),
                    [f64::from(i) * 3.0, 0.0, 0.0],
                )
            })
            .collect();
        fenetres(&mut driver, 9, 2_000);
        assert_eq!(driver.degradation_level(), DegradationLevel::Degraded3);

        let report = gestion_autour_de_l_origine(&mut driver);

        // Plafond 4 → 2 : les deux plus éloignés dorment, et seule la dégradation l'impose.
        assert_eq!((report.slept_by_cap, report.slept_by_budget), (2, 2));
        let world = driver.world_mut(0).unwrap();
        let dorment: Vec<bool> = corps
            .iter()
            .map(|body| world.is_sleeping(*body).unwrap())
            .collect();
        assert_eq!(dorment, [false, false, true, true]);
    }

    #[test]
    fn t307_un_sommeil_de_budget_se_signale_par_un_clamped_de_code_2() {
        // ADR-123 §11 : un corps que seule la dégradation endort (FM-21) émet un CLAMPED
        // portant son assembly et `data` = 2 ; un sommeil nominal n'émet rien de tel.
        use ax_model::dm::physics::{event_data, event_kind};
        let mut driver = SimDriver::new();
        bille(&mut driver, 0);
        let (_, body) = driver.routes[&handle_key(Handle::new(1, 1))];
        let world = driver.world_mut(0).unwrap();
        world.force_sleep(body, false);
        assert!(world.drain_events().is_empty());
        world.end_forced_sleep(body);

        world.force_sleep(body, true);

        let events = world.drain_events();
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].kind, event_kind::CLAMPED);
        assert_eq!(events[0].data, event_data::CLAMPED_BUDGET);
        assert_eq!(events[0].assembly_a, Handle::new(1, 1));
        assert!(events[0].assembly_b.is_absent());
    }

    #[test]
    fn create_assembly_cree_un_corps_route_a_sa_pose_monde() {
        let mut driver = SimDriver::new();
        let spawn = WorldTransform {
            position: [10.0, 5.0, -3.0],
            rotation: [0.0, 0.0, 0.0, 1.0],
        };
        let created = driver.create_assembly(
            0,
            Handle::new(1, 1),
            spawn,
            BodyKind::Dynamic,
            &[BodyCollider {
                shape: Shape::Ball { radius: 0.5 },
                density: 1000.0,
                material: ContactMaterial::default(),
                translation: Vec3::ZERO,
                rotation: Quat::IDENTITY,
            }],
        );
        assert!(created.is_some(), "le corps doit être créé");
        assert_eq!(
            driver.dimension_count(),
            1,
            "la dimension est créée au besoin"
        );

        // Le corps est identifié et routé : il apparaît dans l'état collecté, à sa
        // pose monde (origine à zéro → local = monde).
        driver.advance_all(1.0 / 60.0);
        let states = driver.collect_states();
        assert_eq!(states.len(), 1);
        assert_eq!(states[0].handle, Handle::new(1, 1));
        assert!((states[0].position[0] - 10.0).abs() < 1.0e-3);
        assert!((states[0].position[2] + 3.0).abs() < 1.0e-3);

        // Routable : une impulsion adressée au handle atteint bien le corps.
        driver.apply_impulse(
            Handle::new(1, 1),
            Vec3::new(5.0, 0.0, 0.0),
            Vec3::ZERO,
            false,
        );
        driver.advance_all(1.0 / 60.0);
        let apres = driver.collect_states();
        assert!(apres[0].lin_vel[0] > 0.0, "l'impulsion a bien été routée");
    }

    #[test]
    fn collect_couvre_les_dimensions_dans_l_ordre() {
        let mut driver = SimDriver::new();
        // Dimension 0 : origine décalée, une bille identifiée.
        let ball0 = driver
            .world_or_create(
                0,
                config(),
                FloatingOrigin::new(DVec3::new(1000.0, 0.0, 0.0)),
            )
            .add_body(
                BodyKind::Dynamic,
                Vec3::new(0.0, 5.0, 0.0),
                Quat::IDENTITY,
                Shape::Ball { radius: 0.5 },
            )
            .unwrap();
        driver
            .world_mut(0)
            .unwrap()
            .set_body_identity(ball0, Handle::new(10, 1), 0, 0);
        // Dimension 1 : origine à zéro, une bille identifiée.
        let ball1 = driver
            .world_or_create(1, config(), FloatingOrigin::new(DVec3::ZERO))
            .add_body(
                BodyKind::Dynamic,
                Vec3::new(0.0, 2.0, 0.0),
                Quat::IDENTITY,
                Shape::Ball { radius: 0.5 },
            )
            .unwrap();
        driver
            .world_mut(1)
            .unwrap()
            .set_body_identity(ball1, Handle::new(20, 1), 0, 0);

        driver.advance_all(1.0 / 60.0);
        let states = driver.collect_states();
        assert_eq!(states.len(), 2);
        // Dimension 0 d'abord (clé la plus basse), avec son origine recomposée.
        assert_eq!(states[0].handle, Handle::new(10, 1));
        assert!((states[0].position[0] - 1000.0).abs() < 1e-3);
        assert_eq!(states[1].handle, Handle::new(20, 1));
        assert!(states[1].position[0].abs() < 1e-3);
    }

    #[test]
    fn collect_reports_garde_l_appariement_entre_dimensions() {
        // ADR-120 : la concaténation des dimensions garde chaque emprise au rang
        // de son état — des formes de tailles distinctes le rendent visible.
        let mut driver = SimDriver::new();
        let petite = driver
            .world_or_create(0, config(), FloatingOrigin::new(DVec3::ZERO))
            .add_body(
                BodyKind::Dynamic,
                Vec3::new(0.0, 5.0, 0.0),
                Quat::IDENTITY,
                Shape::Ball { radius: 0.5 },
            )
            .unwrap();
        driver
            .world_mut(0)
            .unwrap()
            .set_body_identity(petite, Handle::new(10, 1), 0, 0);
        let grande = driver
            .world_or_create(
                1,
                config(),
                FloatingOrigin::new(DVec3::new(5000.0, 0.0, 0.0)),
            )
            .add_body(
                BodyKind::Dynamic,
                Vec3::new(0.0, 5.0, 0.0),
                Quat::IDENTITY,
                Shape::Cuboid {
                    half_extents: [1.0, 2.0, 3.0],
                },
            )
            .unwrap();
        driver
            .world_mut(1)
            .unwrap()
            .set_body_identity(grande, Handle::new(20, 1), 0, 0);

        let reports = driver.collect_reports();
        assert_eq!(reports.states.len(), 2);
        assert_eq!(reports.bounds.len(), 2);
        assert_eq!(reports.states[0].handle, Handle::new(10, 1));
        assert_eq!(reports.bounds[0].max, [0.5, 0.5, 0.5]);
        assert_eq!(reports.states[1].handle, Handle::new(20, 1));
        assert_eq!(reports.bounds[1].max, [1.0, 2.0, 3.0]);
        assert_eq!(reports.bounds[1].min, [-1.0, -2.0, -3.0]);
    }

    #[test]
    fn collect_est_deterministe() {
        let run = || {
            let mut driver = SimDriver::new();
            let ball = driver
                .world_or_create(3, config(), FloatingOrigin::new(DVec3::ZERO))
                .add_body(
                    BodyKind::Dynamic,
                    Vec3::new(0.1, 5.0, 0.0),
                    Quat::IDENTITY,
                    Shape::Ball { radius: 0.5 },
                )
                .unwrap();
            driver
                .world_mut(3)
                .unwrap()
                .set_body_identity(ball, Handle::new(1, 1), 0, 0);
            for _ in 0..120 {
                driver.advance_all(1.0 / 60.0);
            }
            driver.collect_states()
        };
        assert_eq!(run(), run());
    }

    #[test]
    fn un_corps_repose_sur_une_tuile_de_collision_monde() {
        let mut driver = SimDriver::new();
        // Une tuile-sol : une dalle 16×1×16 au bas de la section (0, 0, 0).
        let posee = driver.set_world_tile(
            0,
            [0, 0, 0],
            &[[0.0, 0.0, 0.0, 16.0, 1.0, 16.0]],
            ContactMaterial::default(),
        );
        assert!(posee, "la tuile est posée");
        assert_eq!(driver.world_tile_count(), 1);

        // Un corps dynamique lâché au-dessus de la dalle.
        let spawn = WorldTransform {
            position: [8.0, 6.0, 8.0],
            rotation: [0.0, 0.0, 0.0, 1.0],
        };
        driver.create_assembly(
            0,
            Handle::new(1, 1),
            spawn,
            BodyKind::Dynamic,
            &[BodyCollider {
                shape: Shape::Ball { radius: 0.5 },
                density: 1000.0,
                material: ContactMaterial::default(),
                translation: Vec3::ZERO,
                rotation: Quat::IDENTITY,
            }],
        );

        // Une chute : le corps doit reposer sur la dalle, pas la traverser.
        for _ in 0..180 {
            driver.advance_all(1.0 / 60.0);
        }
        let states = driver.collect_states();
        // La tuile est statique : seule la bille dynamique est collectée.
        assert_eq!(states.len(), 1, "seul le corps dynamique est collecté");
        let y = states[0].position[1];
        // Sommet de la dalle à y=1, plus le rayon 0.5 : le centre repose vers 1.5,
        // franchement au-dessus de 1 (pas de traversée) et bien en deçà du départ (6).
        assert!(y > 1.0, "le corps a traversé la dalle (y={y})");
        assert!(
            y < 2.0,
            "le corps ne s'est pas immobilisé sur la dalle (y={y})"
        );
    }

    #[test]
    fn un_corps_repose_sur_une_tuile_champ_de_hauteurs() {
        let mut driver = SimDriver::new();
        // Une tuile-sol en champ de hauteurs : grille 2×2 plate à hauteur 1,
        // étendue 16×16, sur la section (0, 0, 0). Surface à y = 0 + 1×1 = 1.
        let posee = driver.set_world_tile_heightfield(
            0,
            [0, 0, 0],
            2,
            2,
            vec![1.0, 1.0, 1.0, 1.0],
            [16.0, 1.0, 16.0],
            ContactMaterial::default(),
        );
        assert!(posee, "la tuile champ de hauteurs est posée");
        assert_eq!(driver.world_tile_count(), 1);

        // Un corps dynamique lâché au-dessus du champ.
        let spawn = WorldTransform {
            position: [8.0, 6.0, 8.0],
            rotation: [0.0, 0.0, 0.0, 1.0],
        };
        driver.create_assembly(
            0,
            Handle::new(1, 1),
            spawn,
            BodyKind::Dynamic,
            &[BodyCollider {
                shape: Shape::Ball { radius: 0.5 },
                density: 1000.0,
                material: ContactMaterial::default(),
                translation: Vec3::ZERO,
                rotation: Quat::IDENTITY,
            }],
        );

        for _ in 0..180 {
            driver.advance_all(1.0 / 60.0);
        }
        let states = driver.collect_states();
        // La tuile est statique : seule la bille dynamique est collectée.
        assert_eq!(states.len(), 1, "seul le corps dynamique est collecté");
        let y = states[0].position[1];
        // Surface à y=1, plus le rayon 0.5 : le centre repose vers 1.5, au-dessus de
        // 1 (pas de traversée) et bien en deçà du départ (6).
        assert!(y > 1.0, "le corps a traversé le champ de hauteurs (y={y})");
        assert!(
            y < 2.0,
            "le corps ne s'est pas immobilisé sur le champ (y={y})"
        );
    }

    #[test]
    fn une_tuile_champ_de_hauteurs_invalide_ne_se_pose_pas() {
        let mut driver = SimDriver::new();
        // Nombre de hauteurs incohérent avec rows×cols : refus propre, sans panique.
        assert!(!driver.set_world_tile_heightfield(
            0,
            [0, 0, 0],
            2,
            2,
            vec![1.0, 1.0, 1.0],
            [16.0, 1.0, 16.0],
            ContactMaterial::default(),
        ));
        assert_eq!(driver.world_tile_count(), 0);
        // Un champ valide se pose, puis un champ invalide sur la même section la retire.
        assert!(driver.set_world_tile_heightfield(
            0,
            [0, 0, 0],
            2,
            2,
            vec![0.0, 0.0, 0.0, 0.0],
            [16.0, 1.0, 16.0],
            ContactMaterial::default(),
        ));
        assert_eq!(driver.world_tile_count(), 1);
        assert!(!driver.set_world_tile_heightfield(
            0,
            [0, 0, 0],
            1, // moins de 2 lignes : invalide
            2,
            vec![0.0, 0.0],
            [16.0, 1.0, 16.0],
            ContactMaterial::default(),
        ));
        assert_eq!(driver.world_tile_count(), 0);
    }

    #[test]
    fn le_materiau_de_tuile_change_la_friction() {
        // R-643 : la friction de la tuile freine un corps qui glisse dessus. Même
        // impulsion horizontale, deux frottements : le corps glisse plus loin sur la
        // tuile la moins rugueuse — c'est la friction des roues contre le monde.
        let distance_glissee = |friction: f32| {
            let mut driver = SimDriver::new();
            driver.set_world_tile(
                0,
                [0, 0, 0],
                &[[0.0, 0.0, 0.0, 16.0, 1.0, 16.0]],
                ContactMaterial::sanitized(friction, 0.0),
            );
            // Une caisse posée sur la dalle (sommet y=1, demi-hauteur 0.5 → centre 1.5).
            let spawn = WorldTransform {
                position: [8.0, 1.5, 8.0],
                rotation: [0.0, 0.0, 0.0, 1.0],
            };
            driver.create_assembly(
                0,
                Handle::new(1, 1),
                spawn,
                BodyKind::Dynamic,
                &[BodyCollider {
                    shape: Shape::Cuboid {
                        half_extents: [0.5, 0.5, 0.5],
                    },
                    density: 1000.0,
                    material: ContactMaterial::default(),
                    translation: Vec3::ZERO,
                    rotation: Quat::IDENTITY,
                }],
            );
            // Laisser la caisse se poser franchement avant de la pousser.
            for _ in 0..30 {
                driver.advance_all(1.0 / 60.0);
            }
            let depart = driver.collect_states()[0].position[0];
            // Une forte impulsion horizontale, au centre de masse (sans couple).
            driver.apply_impulse(
                Handle::new(1, 1),
                Vec3::new(4000.0, 0.0, 0.0),
                Vec3::ZERO,
                false,
            );
            for _ in 0..180 {
                driver.advance_all(1.0 / 60.0);
            }
            driver.collect_states()[0].position[0] - depart
        };
        let peu_rugueux = distance_glissee(0.0);
        let tres_rugueux = distance_glissee(2.0);
        assert!(
            peu_rugueux > 0.0,
            "l'impulsion pousse la caisse en +x (glissé {peu_rugueux})"
        );
        assert!(
            peu_rugueux > tres_rugueux * 1.5,
            "la caisse glisse plus loin sur une tuile peu rugueuse : {peu_rugueux} vs {tres_rugueux}"
        );
    }

    #[test]
    fn le_materiau_de_tuile_change_le_rebond() {
        // R-643 : la restitution de la tuile renvoie un corps qui la percute — c'est
        // l'énergie des impacts contre le monde. Un sol rebondissant relance la bille
        // plus haut qu'un sol amortissant.
        let sommet_apres_impact = |restitution: f32| {
            let mut driver = SimDriver::new();
            driver.set_world_tile(
                0,
                [0, 0, 0],
                &[[0.0, 0.0, 0.0, 16.0, 1.0, 16.0]],
                ContactMaterial::sanitized(0.5, restitution),
            );
            let spawn = WorldTransform {
                position: [8.0, 6.0, 8.0],
                rotation: [0.0, 0.0, 0.0, 1.0],
            };
            driver.create_assembly(
                0,
                Handle::new(1, 1),
                spawn,
                BodyKind::Dynamic,
                &[BodyCollider {
                    shape: Shape::Ball { radius: 0.5 },
                    density: 1000.0,
                    material: ContactMaterial::default(),
                    translation: Vec3::ZERO,
                    rotation: Quat::IDENTITY,
                }],
            );
            // Chute, impact, rebond : on suit le sommet atteint après le premier contact.
            // La position monde est recomposée en f64 par l'origine flottante.
            let mut a_touche = false;
            let mut sommet = 0.0_f64;
            for _ in 0..240 {
                driver.advance_all(1.0 / 60.0);
                let y = driver.collect_states()[0].position[1];
                if !a_touche && y < 1.7 {
                    a_touche = true;
                }
                if a_touche {
                    sommet = sommet.max(y);
                }
            }
            sommet
        };
        let rebondissant = sommet_apres_impact(1.0);
        let amortissant = sommet_apres_impact(0.0);
        assert!(
            rebondissant > amortissant + 0.2,
            "un sol rebondissant relance plus haut : {rebondissant} vs {amortissant}"
        );
    }

    /// Lâche un cube de densité donnée dans une section pourvue d'un sol de collision
    /// et, si `eau`, d'un volume d'eau (densité 1000) de y=1 à y=9. Rend l'altitude du
    /// corps après stabilisation. Le cube porte une traînée pour amortir le ballant.
    fn altitude_apres_chute(densite: f32, eau: bool, x: f64) -> f64 {
        let mut driver = SimDriver::new();
        // Sol de collision : dalle 16×1×16 au bas de la section.
        driver.set_world_tile(
            0,
            [0, 0, 0],
            &[[0.0, 0.0, 0.0, 16.0, 1.0, 16.0]],
            ContactMaterial::default(),
        );
        if eau {
            // Eau localisée en x ∈ [0, 4], toute la profondeur, de y=1 à y=9.
            driver.set_world_fluid_tile(0, [0, 0, 0], &[[0.0, 1.0, 0.0, 4.0, 9.0, 16.0]], 1000.0);
        }
        let spawn = WorldTransform {
            position: [x, 7.0, 8.0],
            rotation: [0.0, 0.0, 0.0, 1.0],
        };
        let body = driver
            .create_assembly(
                0,
                Handle::new(1, 1),
                spawn,
                BodyKind::Dynamic,
                &[BodyCollider {
                    // Un cube : volume = volume de l'AABB, flottabilité sans biais.
                    shape: Shape::Cuboid {
                        half_extents: [0.5, 0.5, 0.5],
                    },
                    density: densite,
                    material: ContactMaterial::default(),
                    translation: Vec3::ZERO,
                    rotation: Quat::IDENTITY,
                }],
            )
            .expect("le corps est créé");
        // Une traînée amortit le ballant dans l'eau : le corps se stabilise.
        driver.world_mut(0).unwrap().set_drag(body, 1.0, 1.0);
        for _ in 0..600 {
            driver.advance_all(1.0 / 60.0);
        }
        driver.collect_states()[0].position[1]
    }

    #[test]
    fn un_corps_leger_flotte_dans_un_volume_de_fluide() {
        // R-642 : un cube moins dense que l'eau (500 < 1000) flotte, porté par le
        // volume de fluide de la tuile — il se stabilise près de la surface (y=9), très
        // au-dessus du fond (sommet de dalle y=1).
        let y = altitude_apres_chute(500.0, true, 2.0);
        assert!(y > 6.0, "le cube léger flotte près de la surface (y={y})");
        assert!(y < 11.0, "le cube léger ne s'envole pas (y={y})");
    }

    #[test]
    fn le_volume_de_fluide_est_localise() {
        // R-642 : le fluide est un volume, pas un plan infini. Deux cubes légers
        // identiques : celui dans l'eau (x=2, boîte x∈[0,4]) flotte ; celui hors de
        // l'eau (x=10) tombe et repose sur le sol (y≈1.5).
        let dans_l_eau = altitude_apres_chute(500.0, true, 2.0);
        let hors_de_l_eau = altitude_apres_chute(500.0, true, 10.0);
        assert!(
            dans_l_eau > hors_de_l_eau + 3.0,
            "le cube dans l'eau flotte bien au-dessus de celui hors de l'eau : {dans_l_eau} vs {hors_de_l_eau}"
        );
        assert!(
            hors_de_l_eau < 3.0,
            "hors de l'eau, le cube repose sur le sol (y={hors_de_l_eau})"
        );
    }

    #[test]
    fn un_corps_dense_coule_dans_un_volume_de_fluide() {
        // R-642 : un cube plus dense que l'eau (2000 > 1000) coule malgré la poussée et
        // repose sur le sol, tandis que le même cube léger flotte.
        let lourd = altitude_apres_chute(2000.0, true, 2.0);
        let leger = altitude_apres_chute(500.0, true, 2.0);
        assert!(lourd < 3.0, "le cube dense coule au fond (y={lourd})");
        assert!(
            leger > lourd + 3.0,
            "le cube léger flotte bien au-dessus du dense : {leger} vs {lourd}"
        );
    }

    #[test]
    fn une_tuile_de_fluide_se_remplace_et_se_retire() {
        let mut driver = SimDriver::new();
        assert!(driver.set_world_fluid_tile(
            0,
            [1, 0, 2],
            &[[0.0, 0.0, 0.0, 4.0, 4.0, 4.0]],
            1000.0
        ));
        // Reposer la même section remplace le volume sans en cumuler un second.
        assert!(driver.set_world_fluid_tile(
            0,
            [1, 0, 2],
            &[[0.0, 0.0, 0.0, 8.0, 8.0, 8.0]],
            1000.0
        ));
        assert_eq!(driver.world_fluid_tile_count(), 1);
        // Une densité non positive retire le volume.
        assert!(!driver.set_world_fluid_tile(0, [1, 0, 2], &[[0.0, 0.0, 0.0, 4.0, 4.0, 4.0]], 0.0));
        assert_eq!(driver.world_fluid_tile_count(), 0);
        // Retirer une tuile de fluide absente est faux, sans paniquer.
        assert!(!driver.remove_world_fluid_tile(0, [9, 9, 9]));
    }

    #[test]
    fn une_tuile_se_remplace_et_se_retire() {
        let mut driver = SimDriver::new();
        assert!(driver.set_world_tile(
            0,
            [1, 0, 2],
            &[[0.0, 0.0, 0.0, 1.0, 1.0, 1.0]],
            ContactMaterial::default()
        ));
        // Reposer la même section remplace la tuile sans en cumuler une seconde.
        assert!(driver.set_world_tile(
            0,
            [1, 0, 2],
            &[[0.0, 0.0, 0.0, 2.0, 2.0, 2.0]],
            ContactMaterial::default()
        ));
        assert_eq!(driver.world_tile_count(), 1);
        // Une liste vide retire la tuile.
        assert!(!driver.set_world_tile(0, [1, 0, 2], &[], ContactMaterial::default()));
        assert_eq!(driver.world_tile_count(), 0);
        // Retirer une tuile absente est faux, sans paniquer.
        assert!(!driver.remove_world_tile(0, [9, 9, 9]));
    }
}
