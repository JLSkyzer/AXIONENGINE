//! Le monde physique d'une dimension (C-31, fiche 5.23).

use ax_math::{Quat, Vec3};
use rapier3d::prelude::{
    ColliderBuilder, Pose as RapierPose, PhysicsWorld as RapierWorld, RigidBodyBuilder,
    RigidBodyHandle, RigidBodyType, SharedShape,
};

use crate::body::{
    BodyError, BodyId, BodyKind, CompoundPart, Shape, MAX_COMPOUND_PARTS, MAX_CONVEX_HULL_POINTS,
    MIN_CONVEX_HULL_POINTS,
};
use crate::config::PhysicsConfig;
use crate::groups::CollisionGroups;
use std::collections::HashMap;

/// Masse volumique de l'air au niveau de la mer, en kg/m³ (§10.6, terme ρ de la
/// traînée). Valeur physique standard, non un chiffre de performance.
const AIR_DENSITY: f32 = 1.225;

/// Paramètres de force environnementale attachés à un corps (§10.6).
///
/// En tranche 2d, seule la traînée est portée. La portance (surfaces déclarées)
/// et la flottabilité (fluide) enrichiront ce profil dans les tranches
/// suivantes.
#[derive(Debug, Clone, Copy, Default)]
struct AeroProfile {
    /// Produit `Cd · A` (coefficient de traînée × aire de référence), en m².
    drag_cd_a: f32,
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
    /// Profils de force par corps. **Consultée par clé** pendant l'itération
    /// déterministe des corps `rapier`, jamais itérée elle-même : son ordre de
    /// parcours n'entre donc dans aucun résultat de simulation (R-1020).
    aero: HashMap<BodyId, AeroProfile>,
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
            aero: HashMap::new(),
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
        let collider = ColliderBuilder::new(shared_shape_of(&shape)?).build();

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
            self.inner.step();
            self.accumulator -= dt;
            substeps += 1;
        }
        substeps
    }

    /// Applique les forces environnementales de chaque corps avant un sous-pas
    /// (§10.6). En tranche 2d : la traînée, relative au vent.
    ///
    /// Les forces `rapier` persistent d'un pas à l'autre ; on les remet à zéro
    /// puis on les repose à chaque sous-pas, à partir de la vitesse courante. La
    /// gravité, appliquée par `rapier`, n'est pas touchée. L'ordre de parcours
    /// est celui, déterministe, des corps `rapier` (R-1020).
    fn apply_aero_forces(&mut self) {
        if self.aero.is_empty() {
            return;
        }
        let wind = self.wind;
        let updates: Vec<(RigidBodyHandle, Vec3)> = self
            .inner
            .bodies
            .iter()
            .filter(|(_, body)| body.is_dynamic() && !body.is_sleeping())
            .filter_map(|(handle, body)| {
                let profile = self.aero.get(&BodyId::from_handle(handle))?;
                let relative = body.linvel() - wind;
                let speed = relative.length();
                let drag = if profile.drag_cd_a > 0.0 && speed > f32::EPSILON {
                    // F = −0.5·ρ·Cd·A·|v|·v : opposée à la vitesse relative.
                    relative * (-0.5 * AIR_DENSITY * profile.drag_cd_a * speed)
                } else {
                    Vec3::ZERO
                };
                Some((handle, drag))
            })
            .collect();
        for (handle, drag) in updates {
            if let Some(body) = self.inner.bodies.get_mut(handle) {
                body.reset_forces(false);
                body.add_force(drag, false);
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
        self.aero.insert(
            id,
            AeroProfile {
                drag_cd_a: drag_coefficient * area,
            },
        );
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

    /// Retire un corps ; renvoie vrai s'il existait.
    pub fn remove_body(&mut self, id: BodyId) -> bool {
        self.aero.remove(&id);
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
