//! Le monde physique d'une dimension (C-31, fiche 5.23).

use ax_math::{Quat, Vec3};
use rapier3d::prelude::{
    ColliderBuilder, Pose as RapierPose, PhysicsWorld as RapierWorld, RigidBodyBuilder,
    RigidBodyType, SharedShape,
};

use crate::body::{
    BodyError, BodyId, BodyKind, CompoundPart, Shape, MAX_COMPOUND_PARTS, MAX_CONVEX_HULL_POINTS,
    MIN_CONVEX_HULL_POINTS,
};
use crate::config::PhysicsConfig;
use crate::groups::CollisionGroups;

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
            self.inner.step();
            self.accumulator -= dt;
            substeps += 1;
        }
        substeps
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

    /// Retire un corps ; renvoie vrai s'il existait.
    pub fn remove_body(&mut self, id: BodyId) -> bool {
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
