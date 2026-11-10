//! Les véhicules dans le pas (C-33, étape 3 de R-660, ADR-159 D2, D3, D4, D6, D7, D8).
//!
//! Le modèle vit dans `ax-vehicle`, en fonctions pures ; ce module lance les rayons, lit les corps,
//! tient l'état du conducteur, de la boîte, de la direction et du moteur, et applique le résultat.
//! Chaque roue agit par impulsion : `F · dt` au sous-pas, ce que la force aurait donné, sans rien
//! laisser dans l'accumulateur de forces de Rapier d'un sous-pas à l'autre.

use super::{PhysicsWorld, AIR_DENSITY};
use crate::body::BodyId;
use crate::debug::{DebugDraft, ROUND_SUBDIVISIONS};
use crate::forces::LiftSurface;
use crate::groups::CollisionGroups;
use crate::sim::handle_key;
use ax_math::{DVec3, FloatingOrigin, Mat3, Quat, Vec3};
use ax_model::dm::debug::{debug_frame, overlay, DebugSegment};
use ax_model::dm::handle::Handle;
use ax_model::dm::scene::NONE_U16;
use ax_model::dm::vehicle::{
    transmission_kind, vehicle_flags, wheel_flags, wheel_report_flags, VehicleDesc, VehicleInput,
    WheelReport,
};
use ax_vehicle::powertrain::{self, DriveCommand, WheelDrive};
use ax_vehicle::steering::{self, Geometry, Steering};
use ax_vehicle::transmission::Gearbox;
use ax_vehicle::{brakes, differential, engine, suspension, tire};
use rapier3d::prelude::{
    Collider, ColliderHandle, QueryFilter as RapierQueryFilter, QueryFilterFlags, Ray,
    RigidBodyHandle,
};

/// Où une roue est montée (ADR-159 D3).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct WheelMount {
    /// Le rang, parmi les corps de l'assembly, du corps qui la porte.
    pub body: usize,
    /// Le centre de la roue au repos, dans le repère de ce corps, en blocs.
    pub center: Vec3,
}

/// Où une surface portante est montée (12.7), dans l'ordre des `surfaces` de la definition.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct SurfaceMount {
    /// Le rang, parmi les corps de l'assembly, du corps qui la porte.
    pub body: usize,
    /// Son point d'application, dans le repère de ce corps, en blocs.
    pub point: Vec3,
    /// Sa normale, dans le repère de ce corps : le +Y de son node.
    pub normal: Vec3,
}

/// L'état d'une roue, à la fin du dernier sous-pas.
#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct WheelState {
    /// Vitesse de rotation, en rad/s, positive en marche avant.
    pub spin_speed: f32,
    /// Compression, `0` au repos et `1` en butée.
    pub compression: f32,
    /// Angle de braquage, en radians, positif à droite.
    pub steer_angle: f32,
    /// Le rayon touche le sol.
    pub on_ground: bool,
    /// Force de suspension, en newtons.
    pub load: f32,
    /// Glissement longitudinal.
    pub slip_long: f32,
    /// Glissement latéral, en radians.
    pub slip_lat: f32,
    /// Origine du dernier rayon, en repère du monde physique.
    pub anchor: Vec3,
    /// Bout du dernier rayon : le contact, ou sa portée.
    pub reach: Vec3,
}

/// Ce que le pas sait du groupe motopropulseur et de la direction d'un véhicule.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct VehicleTelemetry {
    /// Régime du moteur, en tr/min.
    pub engine_rpm: f32,
    /// Rang du rapport engagé, relatif au rapport nul.
    pub gear: i8,
    /// Un changement de rapport est en cours.
    pub shifting: bool,
    /// Position de la direction, dans [-1, 1], positive à droite.
    pub steer: f32,
    /// Le siège `driver` est occupé.
    pub driver: bool,
}

/// Le contact d'une roue au sous-pas : ce que la passe des impulsions relit après le moteur.
#[derive(Debug, Clone, Copy, Default)]
struct Contact {
    /// Le point de contact.
    point: Vec3,
    /// La normale du sol au contact : la direction de la force de suspension.
    normal: Vec3,
    /// L'avant de la roue dans le plan de contact.
    tangent: Vec3,
    /// Sa droite dans le plan de contact.
    right: Vec3,
    /// La vitesse longitudinale du contact au début du sous-pas, gravité comprise.
    ground_speed: f32,
    /// Le corps dynamique touché, qui reçoit la réaction.
    ground: Option<RigidBodyHandle>,
}

/// Un véhicule dans le monde.
#[derive(Debug)]
pub(super) struct Vehicle {
    assembly: Handle,
    desc: VehicleDesc,
    /// Tous les corps de l'assembly : les rayons les ignorent (R-1051).
    bodies: Vec<RigidBodyHandle>,
    /// Le corps de chaque roue, et son rang de montage.
    carriers: Vec<RigidBodyHandle>,
    /// Le centre au repos de chaque roue, dans le repère de son corps.
    centers: Vec<Vec3>,
    /// La hauteur de l'ancrage de chaque roue au-dessus de son centre au repos : le modèle montre
    /// le véhicule **posé** (§6.5), ressort déjà comprimé de `g / raideur`.
    anchor_heights: Vec<f32>,
    /// La compression de chaque roue quand le véhicule est posé, `g / raideur` bornée à 1 : la position
    /// de la roue dans le modèle (§6.5).
    settled: Vec<f32>,
    /// Le nombre de roues de chaque roue sur son corps : le diviseur de `mass_ref`.
    sharing: Vec<f32>,
    groups: CollisionGroups,
    /// Distance du corps racine à laquelle une tuile peut toucher un rayon.
    reach: f32,
    wheels: Vec<WheelState>,
    /// Tampon réutilisé : corps, impulsion, point.
    impulses: Vec<(RigidBodyHandle, Vec3, Vec3)>,
    /// La dernière entrée du siège `driver`, bornée (R-1110).
    input: VehicleInput,
    /// Le siège `driver` est occupé (D8) ; sans lui, le frein de stationnement est serré (Q3).
    driver: bool,
    /// Vitesse du moteur, en rad/s.
    engine_speed: f32,
    gearbox: Gearbox,
    steering: Steering,
    geometry: Geometry,
    /// Chaque roue est devant le centre de masse de son corps (D7).
    front: Vec<bool>,
    /// La position de chaque roue en X, relative au milieu de la voie.
    lateral: Vec<f32>,
    /// Le nombre de roues avant et arrière : l'appui de chaque essieu s'y partage.
    axle_counts: [f32; 2],
    /// Tampons réutilisés au sous-pas, une entrée par roue.
    drives: Vec<WheelDrive>,
    contacts: Vec<Contact>,
    grounded: Vec<bool>,
    /// Le pire patinage des roues motrices au sous-pas précédent, que l'antipatinage lit.
    traction_slip: f32,
}

impl PhysicsWorld {
    /// Fait d'une assembly un véhicule. `mounts` en donne une par roue de `desc`, dans son ordre ;
    /// `surfaces`, une par surface portante. Sa traînée et ses surfaces portantes entrent dans le
    /// profil aérodynamique de ses corps (12.7).
    ///
    /// # Panics
    ///
    /// Si une monture désigne un corps hors de `bodies`, ou si `mounts` n'a pas une entrée par roue.
    pub fn attach_vehicle(
        &mut self,
        assembly: Handle,
        bodies: &[BodyId],
        desc: VehicleDesc,
        mounts: &[WheelMount],
        surfaces: &[SurfaceMount],
        groups: CollisionGroups,
    ) {
        assert_eq!(desc.wheels.len(), mounts.len(), "une monture par roue");
        let count = desc.wheels.len();
        let carriers: Vec<RigidBodyHandle> = mounts
            .iter()
            .map(|mount| bodies[mount.body].handle())
            .collect();
        let sharing = carriers
            .iter()
            .map(|carrier| carriers.iter().filter(|other| *other == carrier).count() as f32)
            .collect();
        let reach = mounts
            .iter()
            .zip(&desc.wheels)
            .map(|(mount, wheel)| {
                mount.center.length()
                    + wheel.suspension.rest_length
                    + suspension::ray_length(&wheel.suspension, wheel.radius)
            })
            .fold(0.0, f32::max);
        let gravity = self.config.gravity.length();
        let settled: Vec<f32> = desc
            .wheels
            .iter()
            .map(|wheel| {
                if wheel.suspension.stiffness > 0.0 {
                    (gravity / wheel.suspension.stiffness).min(1.0)
                } else {
                    0.0
                }
            })
            .collect();
        let anchor_heights = desc
            .wheels
            .iter()
            .zip(&settled)
            .map(|(wheel, settled)| {
                wheel.suspension.rest_length - settled * wheel.suspension.max_travel
            })
            .collect();
        // D7 : une roue est à l'avant si elle est devant (-Z) le centre de masse de son corps.
        let front: Vec<bool> = mounts
            .iter()
            .map(|mount| {
                let com = self
                    .inner
                    .bodies
                    .get(bodies[mount.body].handle())
                    .map_or(Vec3::ZERO, |body| {
                        body.mass_properties().local_mprops.local_com
                    });
                mount.center.z < com.z
            })
            .collect();
        let positions: Vec<[f32; 2]> = mounts
            .iter()
            .map(|mount| [mount.center.x, mount.center.z])
            .collect();
        let steered: Vec<bool> = desc
            .wheels
            .iter()
            .map(|wheel| wheel.flags & wheel_flags::STEERED != 0)
            .collect();
        let geometry = Geometry::new(&positions, &steered);
        let lateral = positions.iter().map(|p| p[0] - geometry.center_x).collect();
        let powered = desc.flags & vehicle_flags::POWERTRAIN != 0;
        let driven: Vec<bool> = desc
            .wheels
            .iter()
            .map(|wheel| powered && wheel.flags & wheel_flags::DRIVEN != 0)
            .collect();
        let mut shares = vec![0.0; count];
        differential::shares(&desc.differential, &driven, &front, &mut shares);
        let mut couplings = vec![NONE_U16; count];
        differential::groups(&desc.wheels, &driven, &front, &mut couplings);
        // Les roues d'un même corps partagent son mouvement au micro-pas : chacune y est rangée sous
        // la première d'entre elles.
        let drives = shares
            .iter()
            .zip(&couplings)
            .zip(&carriers)
            .map(|((share, group), carrier)| WheelDrive {
                share: *share,
                group: *group,
                carrier: carriers
                    .iter()
                    .position(|first| first == carrier)
                    .and_then(|rank| u16::try_from(rank).ok())
                    .unwrap_or(0),
                ..WheelDrive::default()
            })
            .collect();
        let fronts = front.iter().filter(|f| **f).count() as f32;
        let engine_speed = if desc.transmission.kind == transmission_kind::DIRECT {
            0.0
        } else {
            desc.engine.idle_rpm / engine::RPM_PER_RAD_S
        };
        self.attach_aero(bodies, &desc, surfaces);
        let wheels = vec![WheelState::default(); count];
        self.vehicles.push(Vehicle {
            assembly,
            bodies: bodies.iter().map(|id| id.handle()).collect(),
            carriers,
            centers: mounts.iter().map(|mount| mount.center).collect(),
            anchor_heights,
            settled,
            sharing,
            groups,
            reach,
            impulses: Vec::with_capacity(count * 4),
            wheels,
            input: VehicleInput::NONE,
            driver: false,
            engine_speed,
            gearbox: Gearbox::new(&desc),
            steering: Steering::default(),
            geometry,
            front,
            lateral,
            axle_counts: [fronts, count as f32 - fronts],
            drives,
            contacts: vec![Contact::default(); count],
            grounded: vec![false; count],
            traction_slip: 0.0,
            desc,
        });
    }

    /// 12.7 : la traînée du véhicule sur son corps racine, ses surfaces portantes sur leurs corps ; la
    /// traînée propre de chaque surface s'ajoute à celle du véhicule.
    fn attach_aero(&mut self, bodies: &[BodyId], desc: &VehicleDesc, surfaces: &[SurfaceMount]) {
        let drag = desc.aero.drag_coefficient * desc.aero.frontal_area
            + desc
                .surfaces
                .iter()
                .map(|surface| surface.cd * surface.area)
                .sum::<f32>();
        if let Some(&root) = bodies.first() {
            if drag > 0.0 {
                self.set_drag(root, drag, 1.0);
            }
        }
        for (rank, body) in bodies.iter().enumerate() {
            let lifts: Vec<LiftSurface> = surfaces
                .iter()
                .zip(&desc.surfaces)
                .filter(|(mount, _)| mount.body == rank)
                .map(|(mount, surface)| LiftSurface {
                    local_point: mount.point,
                    local_normal: mount.normal,
                    area: surface.area,
                    lift_coefficient: surface.cl,
                })
                .collect();
            if !lifts.is_empty() {
                self.set_lift_surfaces(*body, lifts);
            }
        }
    }

    /// Pose l'entrée du siège `driver` d'un véhicule (ADR-159 D8, étape 1). Elle est bornée de nouveau
    /// (R-1110) ; une entrée qui fait bouger le véhicule le réveille, la seule présence du conducteur
    /// non. Rend `None` si l'assembly n'est pas un véhicule, sinon vrai si une valeur n'était pas
    /// finie.
    pub fn set_vehicle_input(
        &mut self,
        assembly: Handle,
        input: &VehicleInput,
        driver: bool,
    ) -> Option<bool> {
        let (clean, non_finite) = input.sanitized();
        let bodies = &mut self.inner.bodies;
        let vehicle = self
            .vehicles
            .iter_mut()
            .find(|vehicle| handle_key(vehicle.assembly) == handle_key(assembly))?;
        vehicle.input = clean;
        vehicle.driver = driver;
        if driver && clean.moves() {
            for handle in &vehicle.bodies {
                if let Some(body) = bodies.get_mut(*handle) {
                    body.wake_up(true);
                }
            }
        }
        Some(non_finite)
    }

    /// L'état des roues d'un véhicule, ou `None` si l'assembly n'en est pas un.
    #[must_use]
    pub fn vehicle_wheels(&self, assembly: Handle) -> Option<&[WheelState]> {
        self.vehicle(assembly)
            .map(|vehicle| vehicle.wheels.as_slice())
    }

    /// Le moteur, la boîte et la direction d'un véhicule, ou `None` si l'assembly n'en est pas un.
    #[must_use]
    pub fn vehicle_telemetry(&self, assembly: Handle) -> Option<VehicleTelemetry> {
        self.vehicle(assembly).map(|vehicle| VehicleTelemetry {
            engine_rpm: vehicle.engine_speed * engine::RPM_PER_RAD_S,
            gear: vehicle.gearbox.gear(),
            shifting: vehicle.gearbox.shifting(),
            steer: vehicle.steering.position(),
            driver: vehicle.driver,
        })
    }

    fn vehicle(&self, assembly: Handle) -> Option<&Vehicle> {
        self.vehicles
            .iter()
            .find(|vehicle| handle_key(vehicle.assembly) == handle_key(assembly))
    }

    /// L'état des roues de chaque véhicule éveillé, pour Java (ADR-159 D9). La compression y est
    /// relative à la pose du modèle : `0` quand la roue est où le modèle la dessine, positive quand elle
    /// monte vers la caisse, en fraction du débattement.
    #[must_use]
    pub fn wheel_reports(&self) -> Vec<WheelReport> {
        let mut reports = Vec::new();
        for vehicle in self.vehicles.iter().filter(|v| !self.vehicle_asleep(v)) {
            for (index, state) in vehicle.wheels.iter().enumerate() {
                reports.push(WheelReport {
                    handle: vehicle.assembly,
                    index: u16::try_from(index).unwrap_or(u16::MAX),
                    flags: if state.on_ground {
                        wheel_report_flags::ON_GROUND
                    } else {
                        0
                    },
                    spin_speed: state.spin_speed,
                    compression: state.compression.clamp(-1.0, 1.0) - vehicle.settled[index],
                    steer_angle: state.steer_angle,
                });
            }
        }
        reports
    }

    /// Tous les corps du véhicule dorment : ses roues ne sont ni simulées ni rapportées.
    fn vehicle_asleep(&self, vehicle: &Vehicle) -> bool {
        vehicle.bodies.iter().all(|handle| {
            self.inner
                .bodies
                .get(*handle)
                .is_none_or(rapier3d::prelude::RigidBody::is_sleeping)
        })
    }

    /// Oublie les véhicules qu'un corps retiré portait.
    pub(super) fn forget_vehicle_body(&mut self, id: BodyId) {
        self.vehicles
            .retain(|vehicle| !vehicle.bodies.contains(&id.handle()));
    }

    /// ADR-159 D3 : réveille les véhicules à portée d'un volume de tuile qui change. Leur châssis ne
    /// touche pas le sol : Rapier ne les réveillerait jamais.
    pub fn wake_vehicles_near(&mut self, min: Vec3, max: Vec3) {
        for vehicle in &self.vehicles {
            let Some(&root) = vehicle.bodies.first() else {
                continue;
            };
            let Some(center) = self
                .inner
                .bodies
                .get(root)
                .map(|b| b.position().translation)
            else {
                continue;
            };
            let gap = (min - center).max(center - max).max(Vec3::ZERO);
            if gap.length() <= vehicle.reach {
                for handle in &vehicle.bodies {
                    if let Some(body) = self.inner.bodies.get_mut(*handle) {
                        body.wake_up(true);
                    }
                }
            }
        }
    }

    /// Étape 3 de R-660, à chaque sous-pas : rayons, suspension, pneus, moteur, direction et freins
    /// de chaque véhicule éveillé. Un conducteur qui commande tient le sien éveillé : endormi entre
    /// deux sous-pas, un corps perd sa vitesse, et sa suspension rebondirait à chaque réveil.
    pub(super) fn step_vehicles(&mut self, dt: f32) {
        if self.vehicles.is_empty() {
            return;
        }
        let mut vehicles = std::mem::take(&mut self.vehicles);
        for vehicle in &mut vehicles {
            if vehicle.driver && vehicle.input.moves() {
                for handle in &vehicle.bodies {
                    if let Some(body) = self.inner.bodies.get_mut(*handle) {
                        body.wake_up(true);
                    }
                }
            } else if self.vehicle_asleep(vehicle) {
                continue;
            }
            self.plan_vehicle(vehicle, dt);
            for (handle, impulse, point) in vehicle.impulses.drain(..) {
                if let Some(body) = self.inner.bodies.get_mut(handle) {
                    body.apply_impulse_at_point(impulse, point, false);
                }
            }
        }
        self.vehicles = vehicles;
    }

    /// Plages `raycasts`, `wheels` et `suspension` (ADR-146, ADR-159), en repère d'origine.
    pub(super) fn vehicle_drafts(
        &self,
        origin: &FloatingOrigin,
        camera: DVec3,
        mask: u64,
        drafts: &mut Vec<DebugDraft>,
    ) {
        let relative = |p: Vec3| (origin.to_world(p) - camera).as_vec3().to_array();
        for vehicle in &self.vehicles {
            let mut rays = Vec::new();
            let mut circles = Vec::new();
            let mut springs = Vec::new();
            for (index, state) in vehicle.wheels.iter().enumerate() {
                let Some(body) = self.inner.bodies.get(vehicle.carriers[index]) else {
                    continue;
                };
                let radius = vehicle.desc.wheels[index].radius;
                let up = (state.anchor - state.reach).normalize_or_zero();
                let center = state.reach + up * radius;
                rays.push(DebugSegment {
                    a: relative(state.anchor),
                    b: relative(state.reach),
                });
                springs.push(DebugSegment {
                    a: relative(state.anchor),
                    b: relative(center),
                });
                let forward = body.position().rotation
                    * (Quat::from_rotation_y(-state.steer_angle) * Vec3::NEG_Z);
                let point = |step: u32| {
                    let angle = std::f32::consts::TAU * step as f32 / ROUND_SUBDIVISIONS as f32;
                    center + (forward * angle.cos() + up * angle.sin()) * radius
                };
                for step in 0..ROUND_SUBDIVISIONS {
                    circles.push(DebugSegment {
                        a: relative(point(step)),
                        b: relative(point(step + 1)),
                    });
                }
            }
            let anchor = vehicle
                .wheels
                .first()
                .map_or(DVec3::ZERO, |state| origin.to_world(state.anchor));
            for (bit, segments) in [
                (overlay::RAYCASTS, rays),
                (overlay::WHEELS, circles),
                (overlay::SUSPENSION, springs),
            ] {
                if mask & bit != 0 && !segments.is_empty() {
                    drafts.push(DebugDraft {
                        handle: vehicle.assembly,
                        overlay: overlay::rank(bit),
                        frame: debug_frame::ORIGIN,
                        flags: 0,
                        anchor,
                        segments,
                    });
                }
            }
        }
    }

    /// Passe en lecture : conducteur, boîte, direction, rayons, moteur et pneus, puis les impulsions.
    fn plan_vehicle(&self, vehicle: &mut Vehicle, dt: f32) {
        let Some(root) = vehicle
            .bodies
            .first()
            .and_then(|handle| self.inner.bodies.get(*handle))
        else {
            return;
        };
        let rotation: Quat = root.position().rotation;
        let up = rotation * Vec3::Y;
        let forward = rotation * Vec3::NEG_Z;
        let velocity = root.linvel();
        let forward_speed = velocity.dot(forward);
        let yaw_rate = root.angvel().dot(up);
        let air_speed = (velocity - self.wind).dot(forward);
        let gravity = self.inner.gravity;

        // D8, Q3 : sans conducteur, le frein de stationnement est serré.
        let input = if vehicle.driver {
            vehicle.input
        } else {
            VehicleInput {
                handbrake: 1.0,
                ..VehicleInput::NONE
            }
        };
        let carrier: f32 = vehicle.drives.iter().map(|d| d.share * d.spin).sum();
        let input_rpm =
            (vehicle.gearbox.ratio(&vehicle.desc) * carrier).abs() * engine::RPM_PER_RAD_S;
        vehicle
            .gearbox
            .update(&vehicle.desc, &input, forward_speed, input_rpm, dt);
        let demand = vehicle.gearbox.demand(&vehicle.desc, &input);
        vehicle
            .steering
            .update(&vehicle.desc.steering, input.steer, dt);
        let angle = vehicle.steering.position()
            * steering::max_angle(&vehicle.desc.steering, velocity.length());

        self.cast_wheels(vehicle, &input, demand.brake, angle, dt);

        let desc = &vehicle.desc;
        let mut throttle = demand.throttle;
        if desc.flags & vehicle_flags::TRACTION_CONTROL != 0 {
            throttle *=
                brakes::traction_scale(desc.assists.traction_slip_threshold, vehicle.traction_slip);
        }
        if desc.flags & vehicle_flags::STABILITY_CONTROL != 0 {
            let (sum, count) = vehicle
                .drives
                .iter()
                .zip(&desc.wheels)
                .filter(|(drive, _)| drive.grounded)
                .fold((0.0, 0.0), |(s, n), (drive, wheel)| {
                    (s + drive.surface * wheel.mu_lat, n + 1.0)
                });
            let grip = if count > 0.0 { sum / count } else { 0.0 };
            let target = brakes::target_yaw_rate(
                forward_speed,
                angle,
                vehicle.geometry.wheelbase,
                grip,
                gravity.length(),
            );
            let correction = brakes::stability_correction(
                desc.assists.stability_yaw_tolerance,
                forward_speed,
                yaw_rate,
                target,
            );
            if let Some(correction) = correction {
                if let Some(wheel) = brakes::stability_wheel(
                    &correction,
                    &vehicle.lateral,
                    &vehicle.front,
                    &vehicle.grounded,
                ) {
                    vehicle.drives[wheel].brake += correction.strength
                        * brakes::pedal_torque(&desc.brakes, correction.front, 1.0);
                }
            }
        }
        let command = DriveCommand {
            ratio: if desc.flags & vehicle_flags::POWERTRAIN != 0 {
                vehicle.gearbox.ratio(desc)
            } else {
                0.0
            },
            throttle,
            clutch: input.clutch,
            abs: (desc.flags & vehicle_flags::ABS != 0).then_some(desc.brakes.abs_slip_threshold),
        };
        powertrain::step(
            desc,
            &command,
            &mut vehicle.engine_speed,
            &mut vehicle.drives,
            dt,
        );

        // Les impulsions, et le patinage que l'antipatinage lira au sous-pas suivant.
        vehicle.traction_slip = 0.0;
        let downforce = [desc.aero.downforce_front, desc.aero.downforce_rear]
            .map(|coefficient| 0.5 * AIR_DENSITY * coefficient * air_speed * air_speed);
        for index in 0..vehicle.wheels.len() {
            let wheel = &desc.wheels[index];
            let drive = &vehicle.drives[index];
            let contact = vehicle.contacts[index];
            let state = &mut vehicle.wheels[index];
            state.spin_speed = drive.spin;
            state.slip_long = drive.slip_long;
            let axle = usize::from(!vehicle.front[index]);
            if downforce[axle] > 0.0 && vehicle.axle_counts[axle] > 0.0 {
                // 12.7 : l'appui d'un essieu, partagé entre ses roues, à leur ancrage.
                let carrier_up = self
                    .inner
                    .bodies
                    .get(vehicle.carriers[index])
                    .map_or(up, |body| body.position().rotation * Vec3::Y);
                let impulse = -carrier_up * downforce[axle] / vehicle.axle_counts[axle] * dt;
                vehicle
                    .impulses
                    .push((vehicle.carriers[index], impulse, state.anchor));
            }
            if !drive.grounded {
                continue;
            }
            if drive.share > 0.0 {
                vehicle.traction_slip = vehicle.traction_slip.max(brakes::traction_slip(
                    drive.spin * wheel.radius,
                    contact.ground_speed,
                ));
            }
            let tire_impulse =
                (contact.tangent * drive.force_long + contact.right * drive.force_lat) * dt;
            vehicle
                .impulses
                .push((vehicle.carriers[index], tire_impulse, contact.point));
            // ADR-159 D3 : un corps dynamique sous la roue reçoit la réaction.
            if let Some(ground) = contact.ground {
                vehicle
                    .impulses
                    .push((ground, -tire_impulse, contact.point));
            }
        }
    }

    /// Lance le rayon de chaque roue : compression, charge, plan de contact et vitesses du contact,
    /// freins ; remplit `drives` et `contacts`, et pousse les impulsions de suspension.
    fn cast_wheels(
        &self,
        vehicle: &mut Vehicle,
        input: &VehicleInput,
        brake: f32,
        angle: f32,
        dt: f32,
    ) {
        let own = &vehicle.bodies;
        // R-1051 : ni sa propre assembly, ni les capteurs ; les proxies d'entités, par son filtre.
        let predicate = |_: ColliderHandle, collider: &Collider| {
            !collider
                .parent()
                .is_some_and(|parent| own.contains(&parent))
        };
        let filter = RapierQueryFilter {
            flags: QueryFilterFlags::EXCLUDE_SENSORS,
            groups: Some(vehicle.groups.without_entity_proxies().to_rapier()),
            exclude_collider: None,
            exclude_rigid_body: None,
            predicate: Some(&predicate),
        };
        let pipeline = self.query_pipeline(filter);
        let desc = &vehicle.desc;
        // Le solveur TGS de Rapier intègre la gravité en `k` sous-itérations : la position avance à la
        // vitesse moyenne du sous-pas, `v + g·dt·(k + 1) / 2k`, pas à sa vitesse finale. C'est elle que
        // le pneu annule, sans quoi un véhicule freiné remonterait ou descendrait la pente.
        let iterations = self
            .inner
            .integration_parameters
            .num_solver_iterations
            .max(1) as f32;
        let gravity = self.inner.gravity * ((iterations + 1.0) / (2.0 * iterations));

        for index in 0..vehicle.wheels.len() {
            let wheel = &desc.wheels[index];
            let state = &mut vehicle.wheels[index];
            let drive = &mut vehicle.drives[index];
            let contact = &mut vehicle.contacts[index];
            *contact = Contact::default();
            drive.grounded = false;
            drive.load = 0.0;
            drive.ground_speed = 0.0;
            drive.slip_lat = 0.0;
            // Les freins d'abord : une roue en l'air se freine aussi.
            drive.brake = brakes::pedal_torque(&desc.brakes, vehicle.front[index], brake);
            drive.hold = if wheel.flags & wheel_flags::HANDBRAKE != 0 {
                brakes::handbrake_torque(&desc.brakes, input.handbrake)
            } else {
                0.0
            };
            vehicle.grounded[index] = false;
            let Some(body) = self.inner.bodies.get(vehicle.carriers[index]) else {
                continue;
            };
            let props = body.mass_properties();
            let inverse = props.effective_world_inv_inertia;
            drive.carrier_mass = body.mass();
            drive.carrier_inv_inertia = Mat3::from_cols(
                Vec3::new(inverse.m11, inverse.m12, inverse.m13),
                Vec3::new(inverse.m12, inverse.m22, inverse.m23),
                Vec3::new(inverse.m13, inverse.m23, inverse.m33),
            );
            let center_local = vehicle.centers[index];
            let steer = if wheel.flags & wheel_flags::STEERED != 0 {
                vehicle.geometry.wheel_angle(
                    desc.steering.ackermann,
                    angle,
                    center_local.x,
                    center_local.z,
                )
            } else {
                0.0
            };
            state.steer_angle = steer;
            let pose = body.position();
            let rotation: Quat = pose.rotation;
            let up = rotation * Vec3::Y;
            // D7 : le braquage tourne l'avant de la roue autour de son haut, positif à droite.
            let forward = rotation * (Quat::from_rotation_y(-steer) * Vec3::NEG_Z);
            let center = pose.translation + rotation * center_local;
            let anchor = center + up * vehicle.anchor_heights[index];
            let down = -up;
            let length = suspension::ray_length(&wheel.suspension, wheel.radius);
            state.anchor = anchor;

            let hit = pipeline.cast_ray_and_get_normal(&Ray::new(anchor, down), length, true);
            let Some((collider, hit)) = hit else {
                // 12.2, étape 5 : en l'air, seule la traînée de rotation s'applique.
                state.on_ground = false;
                state.compression =
                    suspension::compression(&wheel.suspension, wheel.radius, length);
                state.load = 0.0;
                state.slip_long = 0.0;
                state.slip_lat = 0.0;
                state.reach = anchor + down * length;
                continue;
            };
            let point = anchor + down * hit.time_of_impact;
            // Un rayon né dans un solide n'a pas de normale.
            let normal = if hit.normal.length_squared() > 0.5 {
                hit.normal
            } else {
                up
            };
            state.on_ground = true;
            state.reach = point;

            let ground = self
                .inner
                .colliders
                .get(collider)
                .and_then(Collider::parent)
                .and_then(|parent| self.inner.bodies.get(parent).map(|ground| (parent, ground)));
            let ground_at =
                |at: Vec3| ground.map_or(Vec3::ZERO, |(_, ground)| ground.velocity_at_point(at));

            let mass_ref = body.mass() / vehicle.sharing[index];
            let compression =
                suspension::compression(&wheel.suspension, wheel.radius, hit.time_of_impact);
            let approach = (body.velocity_at_point(anchor) - ground_at(point)).dot(down);
            let rate = suspension::compression_rate(&wheel.suspension, approach);
            let load = suspension::force(&wheel.suspension, mass_ref, compression, rate);
            state.compression = compression;
            state.load = load;

            // Le plan de contact : l'avant de la roue projeté, puis sa droite. La vitesse du contact
            // compte la gravité du sous-pas : un pneu qui tient le véhicule sur une pente la reprend
            // dans le même sous-pas, sans laisser de dérive.
            let tangent = (forward - normal * forward.dot(normal)).normalize_or_zero();
            let right = tangent.cross(normal);
            let relative = body.velocity_at_point(point) - ground_at(point)
                + gravity * body.gravity_scale() * dt;
            let v_long = relative.dot(tangent);
            let v_lat = relative.dot(right);
            let slip_lat = tire::slip_lat(v_lat, v_long);
            state.slip_lat = slip_lat;

            // R-1070 : le matériau de la surface touchée multiplie le coefficient du pneu.
            let material = self.collider_materials.get(&collider);
            drive.grounded = true;
            drive.load = load;
            drive.surface = material.map_or(1.0, |m| m.friction);
            drive.ground_speed = v_long;
            drive.lateral_speed = v_lat;
            drive.slip_lat = slip_lat;
            drive.tangent = tangent;
            drive.right = right;
            drive.offset = point - props.world_com;
            drive.hold += brakes::rolling_torque(
                material.map_or(0.0, |m| m.rolling_friction),
                load,
                wheel.radius,
            );
            vehicle.grounded[index] = true;
            *contact = Contact {
                point,
                normal,
                tangent,
                right,
                ground_speed: v_long,
                ground: ground
                    .filter(|(_, ground)| ground.is_dynamic())
                    .map(|(parent, _)| parent),
            };

            let suspension_impulse = normal * load * dt;
            vehicle
                .impulses
                .push((vehicle.carriers[index], suspension_impulse, anchor));
            if let Some(ground) = contact.ground {
                vehicle.impulses.push((ground, -suspension_impulse, point));
            }
        }
        Self::count_suspension(vehicle, dt);
    }

    /// Les impulsions de suspension du sous-pas changent elles aussi la vitesse de chaque contact, par
    /// la translation et la rotation de leur corps : le pneu les compte. Sans elles, il annulerait la
    /// vitesse de son contact en partie par un tangage que les ressorts défont aussitôt, et un véhicule
    /// freiné glisserait le long d'une pente.
    fn count_suspension(vehicle: &mut Vehicle, dt: f32) {
        let count = vehicle.wheels.len();
        for index in 0..count {
            let drive = vehicle.drives[index];
            if !drive.grounded {
                continue;
            }
            let center = vehicle.contacts[index].point - drive.offset;
            let mut linear = Vec3::ZERO;
            let mut angular = Vec3::ZERO;
            for other in 0..count {
                if !vehicle.drives[other].grounded
                    || vehicle.carriers[other] != vehicle.carriers[index]
                {
                    continue;
                }
                let impulse = vehicle.contacts[other].normal * vehicle.drives[other].load * dt;
                linear += impulse / drive.carrier_mass.max(f32::EPSILON);
                angular += drive.carrier_inv_inertia
                    * (vehicle.wheels[other].anchor - center).cross(impulse);
            }
            let along = |direction: Vec3| {
                direction.dot(linear) + drive.offset.cross(direction).dot(angular)
            };
            let wheel = &mut vehicle.drives[index];
            wheel.ground_speed += along(drive.tangent);
            wheel.lateral_speed += along(drive.right);
            wheel.slip_lat = tire::slip_lat(wheel.lateral_speed, wheel.ground_speed);
            vehicle.wheels[index].slip_lat = wheel.slip_lat;
            vehicle.contacts[index].ground_speed = wheel.ground_speed;
        }
    }
}
