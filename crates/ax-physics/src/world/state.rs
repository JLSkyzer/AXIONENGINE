//! L'état complet d'une assembly vivante (ADR-168 D1) : ce que le serveur exporte à chaque tick pour corriger le client
//! qui prédit, et ce que ce client restaure avant de rejouer ses entrées (R-1682).
//!
//! Un bloc **opaque pour Java** : le serveur l'écrit, le canal le porte tel quel, le client le rend au natif. Il est
//! versionné et petit-boutiste, ses longueurs fixes, et toute lecture le valide : comptes égaux à ceux de l'assembly
//! vivante, valeurs finies, quaternions normés (R-1650, R-1693).
//!
//! ```text
//! en-tête    16 o : version u16 | drapeaux u16 (bit 0 : véhicule) | corps u16 | joints u16 | roues u16 | 6 o à 0
//! corps      64 o chacun, dans l'ordre de l'assembly : position monde f64×3 | rotation f32×4 | vitesse f32×3 |
//!            vitesse angulaire f32×3
//! joints      8 o chacun, dans l'ordre de la definition : rompu u8 | 7 o à 0
//! véhicule   56 o : entrée (accélérateur, frein, direction, embrayage, frein à main f32 | rapport i8 | drapeaux u8) |
//!            conducteur u8 | 1 o à 0 | régime f32 | boîte (rang i8, cible i8, 2 o à 0, minuteur f32, maintien f32) |
//!            direction f32 | patinage f32 | charge f32 | 4 o à 0
//! roues      32 o chacune : rotation, compression, braquage, charge, glissement long, glissement latéral, couple
//!            posé f32 | contact u8 | 3 o à 0
//! ```
//!
//! Le bloc ne porte ni les paramètres réglés (ADR-167 D6), que Java renvoie, ni l'état de contact que rapier garde d'un
//! pas à l'autre : un rejeu depuis un état restauré reste proche de la course d'origine, sans lui être identique au bit
//! près (précisions d'ADR-168).

use ax_math::{DVec3, Quat, Vec3};
use ax_model::dm::vehicle::VehicleInput;

use super::PhysicsWorld;
use crate::body::BodyId;
use ax_model::dm::handle::Handle;

/// Version du format.
pub const STATE_VERSION: u16 = 1;
/// Octets de l'en-tête.
pub const HEADER_BYTES: usize = 16;
/// Octets d'un corps.
pub const BODY_BYTES: usize = 64;
/// Octets d'un joint.
pub const JOINT_BYTES: usize = 8;
/// Octets de l'état du véhicule, roues à part.
pub const VEHICLE_BYTES: usize = 56;
/// Octets d'une roue.
pub const WHEEL_BYTES: usize = 32;
/// Drapeau d'en-tête : l'assembly est un véhicule.
const FLAG_VEHICLE: u16 = 1;
/// Écart toléré du carré de la norme d'un quaternion à 1.
const UNIT_TOLERANCE: f32 = 1e-3;
/// Les plus grands comptes que le format porte (R-190 : 64 corps, 128 joints ; R-1052 : 32 roues).
const MAX_BODIES: usize = 64;
const MAX_JOINTS: usize = 128;
const MAX_WHEELS: usize = 32;

/// Le plus grand bloc possible, en octets : de quoi borner toute lecture.
pub const MAX_STATE_BYTES: usize = HEADER_BYTES
    + MAX_BODIES * BODY_BYTES
    + MAX_JOINTS * JOINT_BYTES
    + VEHICLE_BYTES
    + MAX_WHEELS * WHEEL_BYTES;

/// Pourquoi un état ne se lit pas, ou ne se pose pas.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StateError {
    /// Plus court que ses comptes ne le disent, ou plus long.
    Length,
    /// Une version que ce natif ne connaît pas.
    Version(u16),
    /// Un compte au-delà de ce que le format porte.
    TooLarge,
    /// Une valeur non finie.
    NotFinite,
    /// Un quaternion qui n'est pas unitaire.
    Rotation,
    /// L'assembly n'existe pas dans ce pilote.
    UnknownAssembly,
    /// Les comptes ne sont pas ceux de l'assembly vivante.
    Mismatch,
    /// Un joint intact dans l'état est rompu ici : il ne se recoud pas, l'assembly se recrée.
    Irreconcilable,
}

/// Un corps vivant : sa pose dans le monde, ses vitesses.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct LiveBody {
    /// Position monde, en blocs.
    pub position: DVec3,
    /// Orientation, unitaire.
    pub rotation: Quat,
    /// Vitesse, en m/s.
    pub linvel: Vec3,
    /// Vitesse angulaire, en rad/s.
    pub angvel: Vec3,
}

/// L'état interne d'une roue, d'un pas au suivant.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct WheelSnapshot {
    /// Vitesse de rotation, en rad/s.
    pub spin_speed: f32,
    /// Compression, de 0 à 1.
    pub compression: f32,
    /// Braquage, en radians.
    pub steer_angle: f32,
    /// Force de suspension, en newtons.
    pub load: f32,
    /// Glissement longitudinal.
    pub slip_long: f32,
    /// Glissement latéral, en radians.
    pub slip_lat: f32,
    /// Couple posé par les contrôleurs (R-1082), en N·m.
    pub torque: f32,
    /// Le rayon touchait le sol.
    pub on_ground: bool,
}

/// L'état interne d'un véhicule, d'un pas au suivant.
#[derive(Debug, Clone, PartialEq)]
pub struct VehicleSnapshot {
    /// L'entrée tenue.
    pub input: VehicleInput,
    /// Le siège `driver` est occupé.
    pub driver: bool,
    /// Vitesse du moteur, en rad/s.
    pub engine_speed: f32,
    /// La boîte : rang engagé, rang visé, temps restant au changement, maintien.
    pub gearbox: (i8, i8, f32, f32),
    /// Position de la direction, dans [-1, 1].
    pub steering: f32,
    /// Pire patinage des roues motrices au sous-pas précédent.
    pub traction_slip: f32,
    /// Charge du moteur au dernier sous-pas.
    pub load: f32,
    /// Chaque roue, dans l'ordre de la definition.
    pub wheels: Vec<WheelSnapshot>,
}

/// L'état complet d'une assembly.
#[derive(Debug, Clone, PartialEq)]
pub struct AssemblyState {
    /// Ses corps, dans l'ordre de l'assembly (racine d'abord).
    pub bodies: Vec<LiveBody>,
    /// Chacun de ses joints est-il rompu, dans l'ordre de la definition.
    pub joints: Vec<bool>,
    /// Son véhicule, s'il en est un.
    pub vehicle: Option<VehicleSnapshot>,
}

impl AssemblyState {
    /// Le bloc, dans le format du module.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let wheels = self
            .vehicle
            .as_ref()
            .map_or(0, |vehicle| vehicle.wheels.len());
        let mut out = Vec::with_capacity(
            HEADER_BYTES
                + self.bodies.len() * BODY_BYTES
                + self.joints.len() * JOINT_BYTES
                + if self.vehicle.is_some() {
                    VEHICLE_BYTES
                } else {
                    0
                }
                + wheels * WHEEL_BYTES,
        );
        out.extend_from_slice(&STATE_VERSION.to_le_bytes());
        let flags = if self.vehicle.is_some() {
            FLAG_VEHICLE
        } else {
            0
        };
        out.extend_from_slice(&flags.to_le_bytes());
        for count in [self.bodies.len(), self.joints.len(), wheels] {
            out.extend_from_slice(&u16::try_from(count).unwrap_or(u16::MAX).to_le_bytes());
        }
        out.extend_from_slice(&[0; 6]);
        for body in &self.bodies {
            for value in body.position.to_array() {
                out.extend_from_slice(&value.to_le_bytes());
            }
            for value in body.rotation.to_array() {
                out.extend_from_slice(&value.to_le_bytes());
            }
            for value in body
                .linvel
                .to_array()
                .into_iter()
                .chain(body.angvel.to_array())
            {
                out.extend_from_slice(&value.to_le_bytes());
            }
        }
        for broken in &self.joints {
            out.push(u8::from(*broken));
            out.extend_from_slice(&[0; 7]);
        }
        if let Some(vehicle) = &self.vehicle {
            let input = &vehicle.input;
            for value in [
                input.throttle,
                input.brake,
                input.steer,
                input.clutch,
                input.handbrake,
            ] {
                out.extend_from_slice(&value.to_le_bytes());
            }
            out.push(input.gear.to_le_bytes()[0]);
            out.push(input.flags);
            out.push(u8::from(vehicle.driver));
            out.push(0);
            out.extend_from_slice(&vehicle.engine_speed.to_le_bytes());
            let (gear, target, shift_left, hold) = vehicle.gearbox;
            out.push(gear.to_le_bytes()[0]);
            out.push(target.to_le_bytes()[0]);
            out.extend_from_slice(&[0; 2]);
            for value in [
                shift_left,
                hold,
                vehicle.steering,
                vehicle.traction_slip,
                vehicle.load,
            ] {
                out.extend_from_slice(&value.to_le_bytes());
            }
            out.extend_from_slice(&[0; 4]);
            for wheel in &vehicle.wheels {
                for value in [
                    wheel.spin_speed,
                    wheel.compression,
                    wheel.steer_angle,
                    wheel.load,
                    wheel.slip_long,
                    wheel.slip_lat,
                    wheel.torque,
                ] {
                    out.extend_from_slice(&value.to_le_bytes());
                }
                out.push(u8::from(wheel.on_ground));
                out.extend_from_slice(&[0; 3]);
            }
        }
        out
    }

    /// Lit un bloc et le valide : longueur exacte, version connue, comptes bornés, valeurs finies, quaternions normés.
    ///
    /// # Errors
    ///
    /// [`StateError`] : ce qui ne va pas, le premier trouvé.
    pub fn decode(bytes: &[u8]) -> Result<Self, StateError> {
        let mut reader = Reader { bytes, at: 0 };
        let version = reader.u16()?;
        if version != STATE_VERSION {
            return Err(StateError::Version(version));
        }
        let flags = reader.u16()?;
        let bodies = usize::from(reader.u16()?);
        let joints = usize::from(reader.u16()?);
        let wheels = usize::from(reader.u16()?);
        reader.skip(6)?;
        if bodies > MAX_BODIES || joints > MAX_JOINTS || wheels > MAX_WHEELS {
            return Err(StateError::TooLarge);
        }
        let vehicle = flags & FLAG_VEHICLE != 0;
        let expected = HEADER_BYTES
            + bodies * BODY_BYTES
            + joints * JOINT_BYTES
            + if vehicle {
                VEHICLE_BYTES + wheels * WHEEL_BYTES
            } else {
                0
            };
        if bytes.len() != expected || (!vehicle && wheels != 0) {
            return Err(StateError::Length);
        }
        let mut state = AssemblyState {
            bodies: Vec::with_capacity(bodies),
            joints: Vec::with_capacity(joints),
            vehicle: None,
        };
        for _ in 0..bodies {
            let position = DVec3::new(reader.f64()?, reader.f64()?, reader.f64()?);
            let rotation =
                Quat::from_xyzw(reader.f32()?, reader.f32()?, reader.f32()?, reader.f32()?);
            let linvel = Vec3::new(reader.f32()?, reader.f32()?, reader.f32()?);
            let angvel = Vec3::new(reader.f32()?, reader.f32()?, reader.f32()?);
            if !(position.is_finite()
                && rotation.is_finite()
                && linvel.is_finite()
                && angvel.is_finite())
            {
                return Err(StateError::NotFinite);
            }
            if (rotation.length_squared() - 1.0).abs() > UNIT_TOLERANCE {
                return Err(StateError::Rotation);
            }
            // Gardé au bit près : rapier tient ses rotations presque unitaires, et un état relu doit se réécrire
            // à l'identique.
            state.bodies.push(LiveBody {
                position,
                rotation,
                linvel,
                angvel,
            });
        }
        for _ in 0..joints {
            state.joints.push(reader.u8()? != 0);
            reader.skip(7)?;
        }
        if vehicle {
            let input = VehicleInput {
                throttle: reader.f32()?,
                brake: reader.f32()?,
                steer: reader.f32()?,
                clutch: reader.f32()?,
                handbrake: reader.f32()?,
                gear: i8::from_le_bytes([reader.u8()?]),
                flags: reader.u8()?,
                _pad: [0; 2],
            };
            let driver = reader.u8()? != 0;
            reader.skip(1)?;
            let engine_speed = reader.f32()?;
            let gear = i8::from_le_bytes([reader.u8()?]);
            let target = i8::from_le_bytes([reader.u8()?]);
            reader.skip(2)?;
            let shift_left = reader.f32()?;
            let hold = reader.f32()?;
            let steering = reader.f32()?;
            let traction_slip = reader.f32()?;
            let load = reader.f32()?;
            reader.skip(4)?;
            let scalars = [
                input.throttle,
                input.brake,
                input.steer,
                input.clutch,
                input.handbrake,
                engine_speed,
                shift_left,
                hold,
                steering,
                traction_slip,
                load,
            ];
            if !scalars.iter().all(|value| value.is_finite()) {
                return Err(StateError::NotFinite);
            }
            let mut snapshot = VehicleSnapshot {
                input,
                driver,
                engine_speed,
                gearbox: (gear, target, shift_left, hold),
                steering,
                traction_slip,
                load,
                wheels: Vec::with_capacity(wheels),
            };
            for _ in 0..wheels {
                let wheel = WheelSnapshot {
                    spin_speed: reader.f32()?,
                    compression: reader.f32()?,
                    steer_angle: reader.f32()?,
                    load: reader.f32()?,
                    slip_long: reader.f32()?,
                    slip_lat: reader.f32()?,
                    torque: reader.f32()?,
                    on_ground: reader.u8()? != 0,
                };
                reader.skip(3)?;
                let values = [
                    wheel.spin_speed,
                    wheel.compression,
                    wheel.steer_angle,
                    wheel.load,
                    wheel.slip_long,
                    wheel.slip_lat,
                    wheel.torque,
                ];
                if !values.iter().all(|value| value.is_finite()) {
                    return Err(StateError::NotFinite);
                }
                snapshot.wheels.push(wheel);
            }
            state.vehicle = Some(snapshot);
        }
        Ok(state)
    }
}

/// Un lecteur petit-boutiste qui refuse de lire au-delà de son bloc.
struct Reader<'a> {
    bytes: &'a [u8],
    at: usize,
}

impl Reader<'_> {
    fn take<const N: usize>(&mut self) -> Result<[u8; N], StateError> {
        let end = self.at.checked_add(N).ok_or(StateError::Length)?;
        let slice = self.bytes.get(self.at..end).ok_or(StateError::Length)?;
        self.at = end;
        let mut out = [0; N];
        out.copy_from_slice(slice);
        Ok(out)
    }

    fn skip(&mut self, count: usize) -> Result<(), StateError> {
        let end = self.at.checked_add(count).ok_or(StateError::Length)?;
        if end > self.bytes.len() {
            return Err(StateError::Length);
        }
        self.at = end;
        Ok(())
    }

    fn u8(&mut self) -> Result<u8, StateError> {
        Ok(self.take::<1>()?[0])
    }

    fn u16(&mut self) -> Result<u16, StateError> {
        Ok(u16::from_le_bytes(self.take()?))
    }

    fn f32(&mut self) -> Result<f32, StateError> {
        Ok(f32::from_le_bytes(self.take()?))
    }

    fn f64(&mut self) -> Result<f64, StateError> {
        Ok(f64::from_le_bytes(self.take()?))
    }
}

impl PhysicsWorld {
    /// La pose locale et les vitesses d'un corps vivant ; `None` s'il n'existe pas.
    #[must_use]
    pub fn live_body(&self, id: BodyId) -> Option<(Vec3, Quat, Vec3, Vec3)> {
        let body = self.inner.bodies.get(id.handle())?;
        let pose = body.position();
        Some((
            pose.translation,
            pose.rotation,
            body.linvel(),
            body.angvel(),
        ))
    }

    /// Pose un corps vivant et le réveille, sa dernière pose valide (R-181) devenant celle-ci. Les rayons des roues
    /// partent de la pose du corps, à jour dès l'appel ; les colliders suivent au pas suivant, comme rapier le fait de
    /// toute pose posée.
    pub fn set_live_body(
        &mut self,
        id: BodyId,
        position: Vec3,
        rotation: Quat,
        linvel: Vec3,
        angvel: Vec3,
    ) {
        // Endormi par la gestion d'activité, il en sort : ses vitesses gelées ne valent plus.
        self.forced_sleep.remove(&id);
        let Some(body) = self.inner.bodies.get_mut(id.handle()) else {
            return;
        };
        body.set_position(super::RapierPose::from_parts(position, rotation), true);
        body.set_linvel(linvel, true);
        body.set_angvel(angvel, true);
        if super::state_is_valid(position, rotation, linvel, angvel) {
            self.last_valid.insert(
                id,
                super::ValidPose {
                    translation: position,
                    rotation,
                },
            );
        }
    }

    /// Le nombre de joints d'une assembly, rompus compris : les rangs de `0` au premier absent.
    #[must_use]
    pub(crate) fn assembly_joint_count(&self, assembly: Handle) -> u16 {
        let mut count = 0;
        while self.assembly_joint(assembly, count).is_some() {
            count += 1;
        }
        count
    }

    /// Le joint de ce rang est-il rompu ; `None` s'il n'existe pas.
    #[must_use]
    pub(crate) fn assembly_joint_broken(&self, assembly: Handle, index: u16) -> Option<bool> {
        let id = self.assembly_joint(assembly, index)?;
        self.joint_report(id).map(|report| report.broken)
    }
}
