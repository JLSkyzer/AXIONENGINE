//! Les attaches entre assemblies (C-48, ADR-171 D6) : un joint de la table de C-34 dont les deux
//! corps appartiennent à deux assemblies, ou dont le second est un point fixe du monde.
//!
//! Le joint vit dans la table des joints : sa résolution, ses efforts, sa rupture et son overlay
//! sont ceux de tout joint (étape 5). Cette table-ci garde ce qui est propre à l'attache : son
//! identifiant, venu de Java, ses deux extrémités, la longueur d'un treuil et sa cible.

use super::joints::ATTACHMENT_INDEX;
use super::PhysicsWorld;
use crate::body::BodyId;
use crate::joint::{AxisMode, BreakThresholds, JointKind, JointSpec};
use ax_math::{Quat, Vec3};
use ax_model::dm::attachments::{attachment_kind, attachment_report_flags, AttachmentReport};
use ax_model::dm::handle::Handle;
use ax_model::dm::physics::{event_kind, PhysicsEvent, NO_MATERIAL};
use rapier3d::prelude::{RigidBodyBuilder, RopeJointBuilder, SpringCoefficients};

/// La fréquence propre d'une corde (`ROPE`) tendue, en hertz (ADR-171, précision 8d) : elle s'étire comme une sangle de
/// remorquage au lieu de se tendre d'un coup. Rigide (le défaut d'un joint, 10⁶ Hz), une corde qui se retend après
/// avoir molli est un choc inélastique : au film du remorquage, conduit au clavier, de 9 à 17 kN entre deux véhicules
/// de 400 kg, assez pour faire partir la voiture en tête-à-queue au slalom ; à 3 Hz, 3,5 kN au plus. Une sangle de
/// nylon de 4 m, 20 % d'allongement à la rupture, tourne autour de 2,5 Hz pour cette masse réduite (estimation). Un
/// câble de treuil reste rigide : souple, il traîne derrière sa longueur cible.
pub(super) const ROPE_FREQUENCY_HZ: f32 = 3.0;

/// La souplesse que reçoit une corde : amortie critique, elle ne rebondit pas.
pub(super) fn rope_softness() -> SpringCoefficients<f32> {
    SpringCoefficients::new(ROPE_FREQUENCY_HZ, 1.0)
}

/// L'autre extrémité d'une attache.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum AttachEnd {
    /// Un corps d'une autre assembly, et le repère du socket dans ce corps.
    Body {
        /// L'assembly.
        assembly: Handle,
        /// Le corps qui porte le socket.
        body: BodyId,
        /// Le repère du socket dans le corps : ancrage et rotation.
        frame: (Vec3, Quat),
    },
    /// Un point du monde, en coordonnées locales du monde physique.
    World(Vec3),
}

/// Ce qu'une attache règle (ADR-171 D1, D2), une fois les paramètres des deux sockets réunis par
/// Java.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct AttachmentSpec {
    /// Voir [`attachment_kind`].
    pub kind: u8,
    /// Le repère du socket `a` dans son corps.
    pub frame_a: (Vec3, Quat),
    /// Force au-delà de laquelle elle rompt, en newtons.
    pub max_force: f32,
    /// Couple au-delà duquel elle rompt, en newtons-mètres.
    pub max_torque: f32,
    /// `HITCH` : le balancement permis sur chaque axe, en radians.
    pub cone: f32,
    /// `ROPE`, `WINCH` : la longueur au départ, en blocs.
    pub length: f32,
    /// `WINCH` : les longueurs extrêmes.
    pub min_length: f32,
    /// `WINCH` : la plus longue.
    pub max_length: f32,
    /// `WINCH` : la vitesse d'enroulement, en blocs par seconde.
    pub speed: f32,
}

/// Pourquoi une attache n'a pas été créée.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AttachError {
    /// L'identifiant est déjà celui d'une attache vivante.
    Duplicate,
    /// Un type inconnu, ou un réglage non fini, négatif ou croisé.
    InvalidSetting,
    /// Un corps absent, ou les deux extrémités sur un même corps.
    MissingBody,
}

/// Une attache du monde.
#[derive(Debug, Clone)]
pub(super) struct AttachmentEntry {
    /// Son joint, dans la table des joints.
    pub(super) joint: u64,
    /// Voir [`attachment_kind`].
    pub(super) kind: u8,
    /// L'assembly de l'extrémité `a`, et celle de `b` ; `None` pour un ancrage au monde.
    pub(super) a: Handle,
    pub(super) b: Option<Handle>,
    /// Le corps fixe qui porte un ancrage au monde, retiré avec l'attache.
    pub(super) anchor: Option<BodyId>,
    /// La longueur courante d'une corde, sa cible, ses bornes et sa vitesse (D5).
    pub(super) length: f32,
    pub(super) target: f32,
    pub(super) bounds: [f32; 2],
    pub(super) speed: f32,
}

impl PhysicsWorld {
    /// Crée l'attache `id` entre le socket `a` — un corps de l'assembly `assembly` — et `end`
    /// (ADR-171 D6). Le joint naît de la table des joints, entre les deux corps, sans contact entre
    /// eux ; il rompt au-delà des seuils de `spec`. Un `ATTACH` part, `data` portant `id`.
    ///
    /// # Errors
    ///
    /// [`AttachError`] : un identifiant vivant, un réglage invalide, un corps absent.
    pub fn attach(
        &mut self,
        id: u32,
        assembly: Handle,
        body: BodyId,
        end: AttachEnd,
        spec: &AttachmentSpec,
    ) -> Result<(), AttachError> {
        if self.attachments.contains_key(&id) {
            return Err(AttachError::Duplicate);
        }
        if !spec_is_valid(spec) {
            return Err(AttachError::InvalidSetting);
        }
        if self.inner.bodies.get(body.handle()).is_none() {
            return Err(AttachError::MissingBody);
        }
        let (other, frame_b, anchor) = match end {
            AttachEnd::Body {
                assembly: other,
                body: other_body,
                frame,
            } => {
                if other_body == body || self.inner.bodies.get(other_body.handle()).is_none() {
                    return Err(AttachError::MissingBody);
                }
                if !(frame.0.is_finite() && frame.1.is_finite()) {
                    return Err(AttachError::InvalidSetting);
                }
                (Some(other), (other_body, frame), None)
            }
            AttachEnd::World(point) => {
                if !point.is_finite() {
                    return Err(AttachError::InvalidSetting);
                }
                // Un corps fixe sans collider, au point d'ancrage : rapier ne joint que des corps.
                // Sans identité, il n'entre dans aucun rapport de corps.
                let handle = self
                    .inner
                    .bodies
                    .insert(RigidBodyBuilder::fixed().translation(point).build());
                let fixed = BodyId::from_handle(handle);
                (None, (fixed, (Vec3::ZERO, Quat::IDENTITY)), Some(fixed))
            }
        };
        let joint_spec = joint_spec(spec, frame_b.1);
        let created = self.add_joint(assembly, ATTACHMENT_INDEX, body, frame_b.0, &joint_spec);
        let joint = match created {
            Ok(joint) => joint,
            Err(_) => {
                if let Some(fixed) = anchor {
                    self.inner.bodies.remove(
                        fixed.handle(),
                        &mut self.inner.islands,
                        &mut self.inner.colliders,
                        &mut self.inner.impulse_joints,
                        &mut self.inner.multibody_joints,
                        true,
                    );
                }
                return Err(AttachError::InvalidSetting);
            }
        };
        self.mark_attachment_joint(joint.0, id, other);
        if spec.kind == attachment_kind::ROPE {
            self.soften_rope(joint.0);
        }
        self.attachments.insert(
            id,
            AttachmentEntry {
                joint: joint.0,
                kind: spec.kind,
                a: assembly,
                b: other,
                anchor,
                length: spec.length,
                target: spec.length,
                bounds: [spec.min_length, spec.max_length],
                speed: spec.speed,
            },
        );
        self.push_event(attachment_event(event_kind::ATTACH, assembly, other, id, 0));
        Ok(())
    }

    /// Émet un événement d'attache que le pilote compose : le refus d'une attache qu'il n'a pas pu
    /// poser.
    pub(crate) fn push_attachment_event(&mut self, event: PhysicsEvent) {
        self.push_event(event);
    }

    /// Défait l'attache `id`, sans événement : c'est Java qui la défait. Faux si elle est inconnue.
    pub fn detach(&mut self, id: u32) -> bool {
        let Some(entry) = self.attachments.remove(&id) else {
            return false;
        };
        self.forget_attachment(&entry);
        true
    }

    /// La longueur cible d'un treuil (ADR-171 D5), ramenée dans ses bornes ; sa corde la rejoint à
    /// sa vitesse, sous-pas par sous-pas. Faux pour une attache inconnue ou qui n'est pas un treuil.
    pub fn set_attachment_target(&mut self, id: u32, target: f32) -> bool {
        match self.attachments.get_mut(&id) {
            Some(entry) if entry.kind == attachment_kind::WINCH && target.is_finite() => {
                entry.target = target.clamp(entry.bounds[0], entry.bounds[1]);
                true
            }
            _ => false,
        }
    }

    /// Les attaches vivantes, dans l'ordre de leurs identifiants.
    #[must_use]
    pub fn attachment_ids(&self) -> Vec<u32> {
        self.attachments.keys().copied().collect()
    }

    /// Avance chaque treuil vers sa cible d'un sous-pas `dt` (ADR-171 D5), avant l'intégration :
    /// la corde change de longueur dans rapier, au plus de `speed × dt`. Ordre des identifiants
    /// (R-1020).
    pub(super) fn advance_winches(&mut self, dt: f32) {
        let mut changed = Vec::new();
        for entry in self.attachments.values_mut() {
            if entry.kind != attachment_kind::WINCH || entry.length == entry.target {
                continue;
            }
            let step = entry.speed * dt;
            entry.length = if entry.target > entry.length {
                (entry.length + step).min(entry.target)
            } else {
                (entry.length - step).max(entry.target)
            };
            changed.push((entry.joint, entry.length));
        }
        for (joint, length) in changed {
            self.set_rope_length(joint, length);
        }
    }

    /// Le rapport de chaque attache à la fin du tick, dans l'ordre de leurs identifiants ; celles qui
    /// ont rompu y paraissent une dernière fois, marquées : le tick suivant les oublie
    /// ([`Self::purge_broken_attachments`]).
    #[must_use]
    pub fn attachment_reports(&self) -> Vec<AttachmentReport> {
        let mut reports = Vec::with_capacity(self.attachments.len());
        for (id, entry) in &self.attachments {
            let Some((report, distance)) = self.attachment_joint_report(entry.joint) else {
                continue;
            };
            let mut flags = 0;
            if report.broken {
                flags |= attachment_report_flags::BROKEN;
            }
            let rope = entry.kind == attachment_kind::ROPE || entry.kind == attachment_kind::WINCH;
            reports.push(AttachmentReport {
                id: *id,
                flags,
                length: if rope { entry.length } else { 0.0 },
                distance,
                force: report.force,
                torque: report.torque,
            });
        }
        reports
    }

    /// Oublie les attaches rompues, une fois rapportées : au début d'un tick, avant ses sous-pas.
    pub(super) fn purge_broken_attachments(&mut self) {
        let broken: Vec<u32> = self
            .attachments
            .iter()
            .filter(|(_, entry)| {
                self.attachment_joint_report(entry.joint)
                    .is_none_or(|(report, _)| report.broken)
            })
            .map(|(id, _)| *id)
            .collect();
        for id in broken {
            if let Some(entry) = self.attachments.remove(&id) {
                self.forget_attachment(&entry);
            }
        }
    }

    /// Oublie les attaches dont `assembly` est une extrémité, sans événement : l'assembly part.
    pub(super) fn remove_assembly_attachments(&mut self, assembly: Handle) {
        let gone: Vec<u32> = self
            .attachments
            .iter()
            .filter(|(_, entry)| entry.a == assembly || entry.b == Some(assembly))
            .map(|(id, _)| *id)
            .collect();
        for id in gone {
            if let Some(entry) = self.attachments.remove(&id) {
                self.forget_attachment(&entry);
            }
        }
    }

    /// Retire de rapier le joint d'une attache et le corps fixe de son ancrage.
    fn forget_attachment(&mut self, entry: &AttachmentEntry) {
        self.remove_joint_entry(entry.joint);
        if let Some(fixed) = entry.anchor {
            self.inner.bodies.remove(
                fixed.handle(),
                &mut self.inner.islands,
                &mut self.inner.colliders,
                &mut self.inner.impulse_joints,
                &mut self.inner.multibody_joints,
                true,
            );
        }
    }
}

/// Le joint de C-34 qui fait une attache : fixe pour `RIGID` et `MAGNET`, une rotule aux
/// balancements bornés pour `HITCH`, une corde pour `ROPE` et `WINCH`. Les deux corps ne se touchent
/// pas : l'attelage d'une remorque ne la pousse pas contre sa voiture.
fn joint_spec(spec: &AttachmentSpec, frame_b: (Vec3, Quat)) -> JointSpec {
    let (kind, axes) = match spec.kind {
        attachment_kind::HITCH => (
            JointKind::Generic,
            [
                AxisMode::Locked,
                AxisMode::Locked,
                AxisMode::Locked,
                AxisMode::Free,
                AxisMode::Limited([-spec.cone, spec.cone]),
                AxisMode::Limited([-spec.cone, spec.cone]),
            ],
        ),
        attachment_kind::ROPE | attachment_kind::WINCH => (JointKind::Rope, [AxisMode::Free; 6]),
        _ => (JointKind::Fixed, [AxisMode::Locked; 6]),
    };
    JointSpec {
        kind,
        frame_a: spec.frame_a,
        frame_b,
        limits: None,
        motor: None,
        friction: None,
        breaking: BreakThresholds {
            force: spec.max_force,
            torque: spec.max_torque,
            energy: f32::INFINITY,
        },
        axes,
        max_length: spec.length,
        rest_length: 0.0,
        stiffness: 0.0,
        damping: 0.0,
        collide: false,
        jam: None,
    }
}

/// Une attache se règle de valeurs finies, positives, ordonnées.
fn spec_is_valid(spec: &AttachmentSpec) -> bool {
    let positive = |value: f32| value > 0.0 && !value.is_nan();
    spec.kind <= attachment_kind::LAST
        && spec.frame_a.0.is_finite()
        && spec.frame_a.1.is_finite()
        && positive(spec.max_force)
        && positive(spec.max_torque)
        && match spec.kind {
            attachment_kind::HITCH => spec.cone.is_finite() && spec.cone >= 0.0,
            attachment_kind::ROPE => spec.length.is_finite() && spec.length > 0.0,
            attachment_kind::WINCH => {
                spec.speed.is_finite()
                    && spec.speed >= 0.0
                    && spec.min_length.is_finite()
                    && spec.max_length.is_finite()
                    && 0.0 < spec.min_length
                    && spec.min_length <= spec.max_length
                    && (spec.min_length..=spec.max_length).contains(&spec.length)
            }
            _ => true,
        }
}

/// Un événement d'attache : `ATTACH` ou `DETACH_ATTACHMENT`, l'identifiant dans `data`, la cause
/// d'un détachement dans `node_a`.
pub(super) fn attachment_event(
    kind: u32,
    a: Handle,
    b: Option<Handle>,
    id: u32,
    cause: u32,
) -> PhysicsEvent {
    PhysicsEvent {
        kind,
        assembly_a: a,
        assembly_b: b.unwrap_or(Handle::ABSENT),
        node_a: cause,
        node_b: 0,
        point: [0.0; 3],
        normal: [0.0; 3],
        impulse: 0.0,
        tangent_impulse: 0.0,
        relative_velocity: 0.0,
        effective_mass: 0.0,
        material_a: NO_MATERIAL,
        material_b: NO_MATERIAL,
        data: id,
    }
}

/// Le `DETACH_ATTACHMENT` d'une attache refusée (cause `REFUSED`).
pub(crate) fn attachment_refused(a: Handle, b: Option<Handle>, id: u32) -> PhysicsEvent {
    attachment_event(
        event_kind::DETACH_ATTACHMENT,
        a,
        b,
        id,
        ax_model::dm::attachments::detach_cause::REFUSED,
    )
}

/// Une corde d'une longueur donnée, pour remplacer celle d'un treuil.
pub(super) fn rope_of(spec: &JointSpec, length: f32) -> rapier3d::prelude::GenericJoint {
    RopeJointBuilder::new(length)
        .local_anchor1(spec.frame_a.0)
        .local_anchor2(spec.frame_b.0)
        .contacts_enabled(spec.collide)
        .build()
        .into()
}
