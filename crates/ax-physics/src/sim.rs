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

use crate::body::BodyId;
use crate::config::PhysicsConfig;
use crate::forces::FluidEnvironment;
use crate::world::PhysicsWorld;
use ax_math::{DVec3, FloatingOrigin, Quat, Vec3};
use ax_model::dm::handle::Handle;
use ax_model::dm::physics::{BodyState, PhysicsEvent};
use std::collections::BTreeMap;

/// La simulation d'une dimension : son monde et son origine flottante.
struct DimensionSim {
    world: PhysicsWorld,
    origin: FloatingOrigin,
}

/// Clé de routage d'un handle : génération en poids fort, index en poids faible.
fn handle_key(handle: Handle) -> u64 {
    (u64::from(handle.generation) << 32) | u64::from(handle.index)
}

/// Pilote de simulation : un monde physique par dimension (R-610).
///
/// Tient aussi le routage d'un handle d'assembly vers son corps et sa dimension,
/// pour appliquer les commandes de `SimIn` (ADR-114) qui ciblent un handle.
#[derive(Default)]
pub struct SimDriver {
    dimensions: BTreeMap<u64, DimensionSim>,
    /// handle → (dimension, corps). Consultée par clé, jamais itérée (R-1020).
    routes: BTreeMap<u64, (u64, BodyId)>,
}

impl std::fmt::Debug for SimDriver {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Les mondes `rapier` ne sont pas `Debug` ; on n'expose que leur nombre.
        f.debug_struct("SimDriver")
            .field("dimensions", &self.dimensions.len())
            .field("routes", &self.routes.len())
            .finish()
    }
}

impl SimDriver {
    /// Crée un pilote sans aucune dimension.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
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
        self.dimensions.get_mut(&dimension).map(|sim| &mut sim.world)
    }

    /// Fixe l'origine flottante d'une dimension existante.
    pub fn set_origin(&mut self, dimension: u64, origin: FloatingOrigin) {
        if let Some(sim) = self.dimensions.get_mut(&dimension) {
            sim.origin = origin;
        }
    }

    /// Détruit la simulation d'une dimension (R-610) ; rend vrai si elle
    /// existait.
    pub fn remove_dimension(&mut self, dimension: u64) -> bool {
        self.dimensions.remove(&dimension).is_some()
    }

    /// Nombre de dimensions actives.
    #[must_use]
    pub fn dimension_count(&self) -> usize {
        self.dimensions.len()
    }

    /// Avance toutes les dimensions du temps réel écoulé.
    pub fn advance_all(&mut self, frame_dt: f32) {
        for sim in self.dimensions.values_mut() {
            sim.world.advance(frame_dt);
        }
    }

    /// Récolte l'état des corps mobiles de toutes les dimensions (DM-08).
    ///
    /// Concaténés dans l'ordre déterministe des dimensions puis des corps
    /// (R-1020).
    #[must_use]
    pub fn collect_states(&self) -> Vec<BodyState> {
        let mut states = Vec::new();
        for sim in self.dimensions.values() {
            states.extend(sim.world.body_states(&sim.origin));
        }
        states
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
    pub fn apply_dimension_env(
        &mut self,
        dimension: u64,
        gravity: Vec3,
        wind: Vec3,
        fluid: Option<FluidEnvironment>,
    ) {
        let sim = self.dimensions.entry(dimension).or_insert_with(|| DimensionSim {
            world: PhysicsWorld::new(PhysicsConfig::default()),
            origin: FloatingOrigin::new(DVec3::ZERO),
        });
        sim.world.set_gravity(gravity);
        sim.world.set_wind(wind);
        sim.world.set_fluid(fluid);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::body::{BodyKind, Shape};
    use crate::Handle;
    use ax_math::{DVec3, Quat, Vec3};

    fn config() -> PhysicsConfig {
        PhysicsConfig::new(1.0 / 60.0, 4).unwrap()
    }

    #[test]
    fn les_dimensions_se_creent_au_premier_besoin() {
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
    fn collect_couvre_les_dimensions_dans_l_ordre() {
        let mut driver = SimDriver::new();
        // Dimension 0 : origine décalée, une bille identifiée.
        let ball0 = driver
            .world_or_create(0, config(), FloatingOrigin::new(DVec3::new(1000.0, 0.0, 0.0)))
            .add_body(BodyKind::Dynamic, Vec3::new(0.0, 5.0, 0.0), Quat::IDENTITY, Shape::Ball { radius: 0.5 })
            .unwrap();
        driver.world_mut(0).unwrap().set_body_identity(ball0, Handle::new(10, 1), 0, 0);
        // Dimension 1 : origine à zéro, une bille identifiée.
        let ball1 = driver
            .world_or_create(1, config(), FloatingOrigin::new(DVec3::ZERO))
            .add_body(BodyKind::Dynamic, Vec3::new(0.0, 2.0, 0.0), Quat::IDENTITY, Shape::Ball { radius: 0.5 })
            .unwrap();
        driver.world_mut(1).unwrap().set_body_identity(ball1, Handle::new(20, 1), 0, 0);

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
    fn collect_est_deterministe() {
        let run = || {
            let mut driver = SimDriver::new();
            let ball = driver
                .world_or_create(3, config(), FloatingOrigin::new(DVec3::ZERO))
                .add_body(BodyKind::Dynamic, Vec3::new(0.1, 5.0, 0.0), Quat::IDENTITY, Shape::Ball { radius: 0.5 })
                .unwrap();
            driver.world_mut(3).unwrap().set_body_identity(ball, Handle::new(1, 1), 0, 0);
            for _ in 0..120 {
                driver.advance_all(1.0 / 60.0);
            }
            driver.collect_states()
        };
        assert_eq!(run(), run());
    }
}
