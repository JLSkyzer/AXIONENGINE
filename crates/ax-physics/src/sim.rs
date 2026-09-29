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

use crate::body::{BodyCollider, BodyId, BodyKind, ContactMaterial, Shape};
use crate::config::PhysicsConfig;
use crate::forces::FluidEnvironment;
use crate::forces::FluidVolume;
use crate::world::PhysicsWorld;
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
    /// (dimension, section 16³) → corps statique de la tuile de collision monde
    /// (C-38). Ordonnée : parcours déterministe (R-1020).
    tiles: BTreeMap<(u64, [i32; 3]), BodyId>,
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

        let sim = self
            .dimensions
            .entry(dimension)
            .or_insert_with(|| DimensionSim {
                world: PhysicsWorld::new(PhysicsConfig::default()),
                origin: FloatingOrigin::new(DVec3::ZERO),
            });
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

        let sim = self
            .dimensions
            .entry(dimension)
            .or_insert_with(|| DimensionSim {
                world: PhysicsWorld::new(PhysicsConfig::default()),
                origin: FloatingOrigin::new(DVec3::ZERO),
            });
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
        let sim = self
            .dimensions
            .entry(dimension)
            .or_insert_with(|| DimensionSim {
                world: PhysicsWorld::new(PhysicsConfig::default()),
                origin: FloatingOrigin::new(DVec3::ZERO),
            });
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
        let sim = self
            .dimensions
            .entry(dimension)
            .or_insert_with(|| DimensionSim {
                world: PhysicsWorld::new(PhysicsConfig::default()),
                origin: FloatingOrigin::new(DVec3::ZERO),
            });
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
    pub fn apply_dimension_env(
        &mut self,
        dimension: u64,
        gravity: Vec3,
        wind: Vec3,
        fluid: Option<FluidEnvironment>,
    ) {
        let sim = self
            .dimensions
            .entry(dimension)
            .or_insert_with(|| DimensionSim {
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
    use crate::body::{BodyCollider, BodyKind, Shape};
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
