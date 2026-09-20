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

use crate::config::PhysicsConfig;
use crate::world::PhysicsWorld;
use ax_math::FloatingOrigin;
use ax_model::dm::physics::{BodyState, PhysicsEvent};
use std::collections::BTreeMap;

/// La simulation d'une dimension : son monde et son origine flottante.
struct DimensionSim {
    world: PhysicsWorld,
    origin: FloatingOrigin,
}

/// Pilote de simulation : un monde physique par dimension (R-610).
#[derive(Default)]
pub struct SimDriver {
    dimensions: BTreeMap<u64, DimensionSim>,
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
