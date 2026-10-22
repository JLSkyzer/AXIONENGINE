//! Les joints du monde (C-34, ADR-151 D4, D5 ; ADR-152) : leur création dans l'`ImpulseJointSet`
//! de rapier, et l'étape 5, qui lit ce qu'ils ont transmis après la résolution et les rompt au-delà
//! de leurs seuils.

use super::PhysicsWorld;
use crate::body::BodyId;
use crate::joint::{
    AxisMode, BreakThresholds, JointCounts, JointError, JointId, JointKind, JointMotor,
    JointReport, JointSpec,
};
use ax_math::Vec3;
use ax_model::dm::handle::Handle;
use ax_model::dm::physics::{event_kind, PhysicsEvent, NO_MATERIAL};
use rapier3d::prelude::{
    GenericJoint, GenericJointBuilder, ImpulseJointHandle, JointAxesMask, JointAxis, MotorModel,
    Pose as RapierPose, RopeJointBuilder, SpringJointBuilder,
};

/// Les six axes d'un joint, dans l'ordre de `JointSpec::axes`.
const AXES: [JointAxis; 6] = [
    JointAxis::LinX,
    JointAxis::LinY,
    JointAxis::LinZ,
    JointAxis::AngX,
    JointAxis::AngY,
    JointAxis::AngZ,
];

/// Un joint du monde : son joint rapier, ses seuils et ce que l'étape 5 en a lu.
#[derive(Debug, Clone)]
pub(super) struct JointEntry {
    /// Son assembly et son rang dans la definition, pour ses événements.
    pub(super) assembly: Handle,
    pub(super) index: u16,
    /// Le joint, absent une fois rompu.
    pub(super) joint: Option<ImpulseJointHandle>,
    pub(super) breaking: BreakThresholds,
    pub(super) report: JointReport,
}

impl PhysicsWorld {
    /// Crée un joint entre deux corps (C-34, ADR-151 D4, ADR-152) : un joint impulsionnel de
    /// rapier, résolu avec les contacts à chaque sous-pas ; son moteur et sa friction interne sont
    /// résolus avec ses axes bloqués.
    ///
    /// # Errors
    ///
    /// [`JointError`] : un corps absent, les deux bouts sur un même corps, un réglage non fini ou une
    /// plage croisée.
    pub fn add_joint(
        &mut self,
        assembly: Handle,
        index: u16,
        body_a: BodyId,
        body_b: BodyId,
        spec: &JointSpec,
    ) -> Result<JointId, JointError> {
        if body_a == body_b {
            return Err(JointError::SameBody);
        }
        if self.inner.bodies.get(body_a.handle()).is_none()
            || self.inner.bodies.get(body_b.handle()).is_none()
        {
            return Err(JointError::MissingBody);
        }
        if !settings_are_valid(spec) {
            return Err(JointError::InvalidSetting);
        }
        let joint = self.inner.impulse_joints.insert(
            body_a.handle(),
            body_b.handle(),
            rapier_joint(spec),
            true,
        );
        let id = JointId(self.next_joint);
        self.next_joint += 1;
        self.joints.insert(
            id.0,
            JointEntry {
                assembly,
                index,
                joint: Some(joint),
                breaking: spec.breaking,
                report: JointReport {
                    force: 0.0,
                    torque: 0.0,
                    energy: 0.0,
                    broken: false,
                },
            },
        );
        Ok(id)
    }

    /// Oublie les joints d'une assembly et retire de rapier ceux qui y sont encore.
    pub fn remove_assembly_joints(&mut self, assembly: Handle) {
        let gone: Vec<u64> = self
            .joints
            .iter()
            .filter(|(_, entry)| entry.assembly == assembly)
            .map(|(key, _)| *key)
            .collect();
        for key in gone {
            if let Some(handle) = self.joints.remove(&key).and_then(|entry| entry.joint) {
                self.inner.impulse_joints.remove(handle, true);
            }
        }
    }

    /// Ce que l'étape 5 a lu d'un joint au dernier sous-pas ; `None` pour un joint inconnu.
    #[must_use]
    pub fn joint_report(&self, id: JointId) -> Option<JointReport> {
        self.joints.get(&id.0).map(|entry| entry.report)
    }

    /// Les comptes des joints du monde.
    #[must_use]
    pub fn joint_counts(&self) -> JointCounts {
        JointCounts {
            active: self
                .joints
                .values()
                .filter(|entry| !entry.report.broken)
                .count(),
            solved: self.joints_solved,
            broken: self.joints_broken,
        }
    }

    /// Rompt un joint : il quitte rapier, reste connu, rompu, et émet un seul `JOINT_BROKEN`. Rend
    /// faux s'il est inconnu ou déjà rompu.
    pub fn break_joint(&mut self, id: JointId) -> bool {
        self.break_entry(id.0, 0.0)
    }

    /// Remet à zéro l'énergie absorbée par chaque joint : elle se compte sur un tick (D5).
    pub(super) fn start_joint_tick(&mut self) {
        for entry in self.joints.values_mut() {
            entry.report.energy = 0.0;
        }
    }

    /// Étape 5 (C-40, R-660 ; ADR-151 D5, ADR-152), après la résolution d'un sous-pas : ce que
    /// chaque joint résolu a transmis, l'énergie qu'il a absorbée, et sa rupture au-delà d'un seuil,
    /// dans l'ordre de création des joints (R-1020). Un joint dont les deux corps dorment n'est pas
    /// résolu (R-631) : rapier ne l'a pas touché, rien ne se lit.
    pub(super) fn resolve_joints(&mut self, dt: f32) {
        self.joints_solved = 0;
        let keys: Vec<u64> = self.joints.keys().copied().collect();
        for key in keys {
            let Some(handle) = self.joints.get(&key).and_then(|entry| entry.joint) else {
                continue;
            };
            let Some(joint) = self.inner.impulse_joints.get(handle) else {
                continue;
            };
            let (Some(body1), Some(body2)) = (
                self.inner.bodies.get(joint.body1()),
                self.inner.bodies.get(joint.body2()),
            ) else {
                continue;
            };
            if body1.is_sleeping() && body2.is_sleeping() {
                continue;
            }
            self.joints_solved += 1;
            // Ce que rapier publie (ADR-152) : l'impulsion que le joint a appliquée à son second
            // corps sur le sous-pas, par ses axes tenus — bloqués et butées — et par ses moteur et
            // friction ; linéaire, puis angulaire autour de l'ancrage.
            let (held_linear, held_angular) = spatial(joint.applied_held);
            let (driven_linear, driven_angular) = spatial(joint.applied_driven);
            let linear = held_linear + driven_linear;
            let force = linear.length() / dt;
            let torque = (held_angular + driven_angular).length() / dt;
            // L'énergie qu'il absorbe : ½·|λ·c|, λ l'impulsion de ses axes tenus et c la vitesse du
            // second corps relativement au premier, à l'ancrage, avant le sous-pas.
            let anchor = (body2.position() * joint.data.local_frame2).translation;
            let before = |body| {
                self.pre_step
                    .get(&body)
                    .map_or((Vec3::ZERO, Vec3::ZERO), |motion| {
                        (motion.velocity_at(anchor), motion.angvel)
                    })
            };
            let (linvel1, angvel1) = before(joint.body1());
            let (linvel2, angvel2) = before(joint.body2());
            let absorbed = 0.5
                * (held_linear.dot(linvel2 - linvel1) + held_angular.dot(angvel2 - angvel1)).abs();

            let Some(entry) = self.joints.get_mut(&key) else {
                continue;
            };
            entry.report.force = force;
            entry.report.torque = torque;
            entry.report.energy += absorbed;
            let breaking = entry.breaking;
            if force > breaking.force
                || torque > breaking.torque
                || entry.report.energy > breaking.energy
            {
                self.break_entry(key, linear.length());
            }
        }
    }

    /// Rompt le joint `key` et émet son `JOINT_BROKEN`, portant `impulse`.
    fn break_entry(&mut self, key: u64, impulse: f32) -> bool {
        let Some(entry) = self.joints.get_mut(&key) else {
            return false;
        };
        let Some(handle) = entry.joint.take() else {
            return false;
        };
        entry.report.broken = true;
        let (assembly, index) = (entry.assembly, entry.index);
        let mut event = PhysicsEvent {
            kind: event_kind::JOINT_BROKEN,
            assembly_a: assembly,
            assembly_b: assembly,
            node_a: 0,
            node_b: 0,
            point: [0.0; 3],
            normal: [0.0; 3],
            impulse,
            tangent_impulse: 0.0,
            relative_velocity: 0.0,
            effective_mass: 0.0,
            material_a: NO_MATERIAL,
            material_b: NO_MATERIAL,
            data: u32::from(index),
        };
        if let Some(joint) = self.inner.impulse_joints.get(handle) {
            if let Some(body1) = self.inner.bodies.get(joint.body1()) {
                event.point = (body1.position() * joint.data.local_frame1)
                    .translation
                    .to_array();
            }
            let identity = |body| {
                self.identity
                    .get(&BodyId::from_handle(body))
                    .map_or(0, |identity| identity.node)
            };
            event.node_a = identity(joint.body1());
            event.node_b = identity(joint.body2());
        }
        self.inner.impulse_joints.remove(handle, true);
        self.joints_broken += 1;
        self.push_event(event);
        true
    }
}

/// Une impulsion de rapier (ADR-152) : linéaire, puis angulaire.
fn spatial(impulse: [f32; 6]) -> (Vec3, Vec3) {
    (
        Vec3::new(impulse[0], impulse[1], impulse[2]),
        Vec3::new(impulse[3], impulse[4], impulse[5]),
    )
}

/// Les réglages d'un joint sont finis, et ses plages ordonnées.
fn settings_are_valid(spec: &JointSpec) -> bool {
    let (anchor_a, rotation_a) = spec.frame_a;
    let (anchor_b, rotation_b) = spec.frame_b;
    let ordered =
        |range: [f32; 2]| range[0].is_finite() && range[1].is_finite() && range[0] <= range[1];
    let non_negative = |value: f32| value.is_finite() && value >= 0.0;
    anchor_a.is_finite()
        && anchor_b.is_finite()
        && rotation_a.is_finite()
        && rotation_b.is_finite()
        && spec.limits.is_none_or(ordered)
        && spec.friction.is_none_or(non_negative)
        && spec.axes.iter().all(|mode| match mode {
            AxisMode::Limited(range) => ordered(*range),
            AxisMode::Locked | AxisMode::Free => true,
        })
        && [
            spec.max_length,
            spec.rest_length,
            spec.stiffness,
            spec.damping,
        ]
        .into_iter()
        .all(non_negative)
}

/// L'axe libre d'un joint `Revolute` ou `Prismatic` : l'axe X de ses repères.
fn free_axis(kind: JointKind) -> Option<JointAxis> {
    match kind {
        JointKind::Revolute => Some(JointAxis::AngX),
        JointKind::Prismatic => Some(JointAxis::LinX),
        _ => None,
    }
}

/// Les axes qu'un joint laisse bouger, que sa friction freine.
fn moving_axes(spec: &JointSpec) -> Vec<JointAxis> {
    match spec.kind {
        JointKind::Revolute => vec![JointAxis::AngX],
        JointKind::Prismatic => vec![JointAxis::LinX],
        JointKind::Spherical => vec![JointAxis::AngX, JointAxis::AngY, JointAxis::AngZ],
        JointKind::Generic => spec
            .axes
            .iter()
            .zip(AXES)
            .filter(|(mode, _)| !matches!(mode, AxisMode::Locked))
            .map(|(_, axis)| axis)
            .collect(),
        JointKind::Fixed | JointKind::Rope | JointKind::Spring => Vec::new(),
    }
}

/// Les axes bloqués d'un joint à repères.
fn locked_axes(spec: &JointSpec) -> JointAxesMask {
    match spec.kind {
        JointKind::Fixed => JointAxesMask::LOCKED_FIXED_AXES,
        JointKind::Revolute => JointAxesMask::LOCKED_REVOLUTE_AXES,
        JointKind::Prismatic => JointAxesMask::LOCKED_PRISMATIC_AXES,
        JointKind::Spherical => JointAxesMask::LOCKED_SPHERICAL_AXES,
        JointKind::Generic => spec
            .axes
            .iter()
            .zip(AXES)
            .filter(|(mode, _)| matches!(mode, AxisMode::Locked))
            .fold(JointAxesMask::empty(), |mask, (_, axis)| mask | axis.into()),
        JointKind::Rope | JointKind::Spring => JointAxesMask::empty(),
    }
}

/// Un moteur en vitesse rigide : sous un amortissement sans borne, rapier ne régularise plus la
/// ligne (`MotorModel::combine_coefficients`) et vise la vitesse à chaque sous-pas interne, plafonné
/// par la force seule.
const RIGID: f32 = f32::MAX;

/// Le joint rapier d'un joint : ses repères, ses axes bloqués, ses limites, son moteur et sa
/// friction interne. Un moteur en vitesse est rigide, plafonné par sa force ; un servo en position a
/// sa raideur et son amortissement en unités absolues ; la friction freine chaque axe mobile sans le
/// bloquer (ADR-152).
fn rapier_joint(spec: &JointSpec) -> GenericJoint {
    let (anchor_a, rotation_a) = spec.frame_a;
    let (anchor_b, rotation_b) = spec.frame_b;
    match spec.kind {
        JointKind::Rope => RopeJointBuilder::new(spec.max_length)
            .local_anchor1(anchor_a)
            .local_anchor2(anchor_b)
            .contacts_enabled(spec.collide)
            .build()
            .into(),
        JointKind::Spring => {
            SpringJointBuilder::new(spec.rest_length, spec.stiffness, spec.damping)
                .local_anchor1(anchor_a)
                .local_anchor2(anchor_b)
                .contacts_enabled(spec.collide)
                .build()
                .into()
        }
        _ => {
            let mut builder = GenericJointBuilder::new(locked_axes(spec))
                .local_frame1(RapierPose::from_parts(anchor_a, rotation_a))
                .local_frame2(RapierPose::from_parts(anchor_b, rotation_b))
                .contacts_enabled(spec.collide);
            if let Some(axis) = free_axis(spec.kind) {
                if let Some(limits) = spec.limits {
                    builder = builder.limits(axis, limits);
                }
                builder = match spec.motor {
                    Some(JointMotor::Velocity { speed, max_force }) => builder
                        .motor_model(axis, MotorModel::AccelerationBased)
                        .motor_velocity(axis, speed, RIGID)
                        .motor_max_force(axis, max_force),
                    Some(JointMotor::Position {
                        target,
                        speed,
                        stiffness,
                        damping,
                        max_force,
                    }) => builder
                        .motor_model(axis, MotorModel::ForceBased)
                        .set_motor(axis, target, speed, stiffness, damping)
                        .motor_max_force(axis, max_force),
                    None => builder,
                };
            }
            if spec.kind == JointKind::Generic {
                for (mode, axis) in spec.axes.iter().zip(AXES) {
                    if let AxisMode::Limited(limits) = mode {
                        builder = builder.limits(axis, *limits);
                    }
                }
            }
            if let Some(friction) = spec.friction {
                for axis in moving_axes(spec) {
                    builder = builder.friction(axis, friction);
                }
            }
            builder.build()
        }
    }
}
