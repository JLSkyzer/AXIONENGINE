//! Les véhicules dans le pas (C-33, étape 3 de R-660, ADR-159 D2, D3, D4).
//!
//! Le modèle vit dans `ax-vehicle`, en fonctions pures ; ce module lance les rayons, lit les corps
//! et applique le résultat. Chaque roue agit par impulsion : `F · dt` au sous-pas, ce que la force
//! aurait donné, sans rien laisser dans l'accumulateur de forces de Rapier d'un sous-pas à l'autre.

use super::PhysicsWorld;
use crate::body::BodyId;
use crate::debug::{DebugDraft, ROUND_SUBDIVISIONS};
use crate::groups::CollisionGroups;
use crate::sim::handle_key;
use ax_math::{DVec3, FloatingOrigin, Quat, Vec3};
use ax_model::dm::debug::{debug_frame, overlay, DebugSegment};
use ax_model::dm::handle::Handle;
use ax_model::dm::vehicle::VehicleDesc;
use ax_vehicle::{suspension, tire};
use rapier3d::prelude::{
    Collider, ColliderHandle, QueryFilter as RapierQueryFilter, QueryFilterFlags, Ray,
    RigidBodyHandle,
};

/// Ralentissement d'une roue en l'air, en 1/s : la « traînée de rotation » de 12.2. Choix d'ADR-159.
const AIR_SPIN_DRAG: f32 = 0.5;

/// Où une roue est montée (ADR-159 D3).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct WheelMount {
    /// Le rang, parmi les corps de l'assembly, du corps qui la porte.
    pub body: usize,
    /// Le centre de la roue au repos, dans le repère de ce corps, en blocs.
    pub center: Vec3,
}

/// L'état d'une roue, à la fin du dernier sous-pas.
#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct WheelState {
    /// Vitesse de rotation, en rad/s, positive en marche avant.
    pub spin_speed: f32,
    /// Compression, `0` au repos et `1` en butée.
    pub compression: f32,
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
    /// Le nombre de roues de chaque roue sur son corps : le diviseur de `mass_ref`.
    sharing: Vec<f32>,
    groups: CollisionGroups,
    /// Distance du corps racine à laquelle une tuile peut toucher un rayon.
    reach: f32,
    wheels: Vec<WheelState>,
    /// Tampon réutilisé : corps, impulsion, point.
    impulses: Vec<(RigidBodyHandle, Vec3, Vec3)>,
}

impl PhysicsWorld {
    /// Fait d'une assembly un véhicule. `mounts` en donne une par roue de `desc`, dans son ordre.
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
        groups: CollisionGroups,
    ) {
        assert_eq!(desc.wheels.len(), mounts.len(), "une monture par roue");
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
        let wheels = vec![WheelState::default(); desc.wheels.len()];
        self.vehicles.push(Vehicle {
            assembly,
            bodies: bodies.iter().map(|id| id.handle()).collect(),
            carriers,
            centers: mounts.iter().map(|mount| mount.center).collect(),
            sharing,
            groups,
            reach,
            impulses: Vec::with_capacity(desc.wheels.len() * 4),
            wheels,
            desc,
        });
    }

    /// L'état des roues d'un véhicule, ou `None` si l'assembly n'en est pas un.
    #[must_use]
    pub fn vehicle_wheels(&self, assembly: Handle) -> Option<&[WheelState]> {
        self.vehicles
            .iter()
            .find(|vehicle| handle_key(vehicle.assembly) == handle_key(assembly))
            .map(|vehicle| vehicle.wheels.as_slice())
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

    /// Étape 3 de R-660, à chaque sous-pas : rayons, suspension et pneus de chaque véhicule éveillé.
    pub(super) fn step_vehicles(&mut self, dt: f32) {
        if self.vehicles.is_empty() {
            return;
        }
        let mut vehicles = std::mem::take(&mut self.vehicles);
        for vehicle in &mut vehicles {
            let asleep = vehicle.bodies.iter().all(|handle| {
                self.inner
                    .bodies
                    .get(*handle)
                    .is_none_or(rapier3d::prelude::RigidBody::is_sleeping)
            });
            if !asleep {
                self.plan_vehicle(vehicle, dt);
                for (handle, impulse, point) in vehicle.impulses.drain(..) {
                    if let Some(body) = self.inner.bodies.get_mut(handle) {
                        body.apply_impulse_at_point(impulse, point, false);
                    }
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
                let forward = body.position().rotation * Vec3::NEG_Z;
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

    /// Passe en lecture : lance les rayons, met à jour l'état des roues, et remplit les impulsions.
    fn plan_vehicle(&self, vehicle: &mut Vehicle, dt: f32) {
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

        for index in 0..vehicle.wheels.len() {
            let Some(body) = self.inner.bodies.get(vehicle.carriers[index]) else {
                continue;
            };
            let wheel = &vehicle.desc.wheels[index];
            let state = &mut vehicle.wheels[index];
            let pose = body.position();
            let rotation: Quat = pose.rotation;
            let up = rotation * Vec3::Y;
            let forward = rotation * Vec3::NEG_Z;
            let center = pose.translation + rotation * vehicle.centers[index];
            let anchor = center + up * wheel.suspension.rest_length;
            let down = -up;
            let length = suspension::ray_length(&wheel.suspension, wheel.radius);
            state.anchor = anchor;

            let hit = pipeline.cast_ray_and_get_normal(&Ray::new(anchor, down), length, true);
            let Some((collider, hit)) = hit else {
                // 12.2, étape 5 : en l'air, seule la traînée de rotation s'applique.
                state.spin_speed *= (1.0 - AIR_SPIN_DRAG * dt).max(0.0);
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

            // Le plan de contact : l'avant de la roue projeté, puis sa droite.
            let tangent = (forward - normal * forward.dot(normal)).normalize_or_zero();
            let right = tangent.cross(normal);
            let relative = body.velocity_at_point(point) - ground_at(point);
            let v_long = relative.dot(tangent);
            let v_lat = relative.dot(right);
            let wheel_speed = state.spin_speed * wheel.radius;
            let slip_long = tire::slip_long(wheel_speed, v_long);
            let slip_lat = tire::slip_lat(v_lat, v_long);
            state.slip_long = slip_long;
            state.slip_lat = slip_lat;

            // R-1070 : le matériau de la surface touchée multiplie le coefficient du pneu.
            let surface = self
                .collider_materials
                .get(&collider)
                .map_or(1.0, |material| material.friction);
            let forces = tire::forces(wheel, surface, load, slip_long, slip_lat);

            // ADR-159 D4 : ni le pneu ne dépasse le glissement qu'il combat, ni la roue, légère,
            // ne franchit la vitesse du sol en un sous-pas.
            let wheel_mass = 0.5 * wheel.mass;
            let reduced = wheel_mass * mass_ref / (wheel_mass + mass_ref);
            let f_long = tire::no_reversal(forces.long, wheel_speed - v_long, reduced, dt);
            let f_lat = tire::no_reversal(forces.lat, v_lat, mass_ref, dt);

            // La réaction du sol sur la roue la ramène vers la vitesse du sol.
            state.spin_speed -=
                f_long * wheel.radius * dt / (wheel_mass * wheel.radius * wheel.radius);

            let suspension_impulse = normal * load * dt;
            let tire_impulse = (tangent * f_long + right * f_lat) * dt;
            vehicle
                .impulses
                .push((vehicle.carriers[index], suspension_impulse, anchor));
            vehicle
                .impulses
                .push((vehicle.carriers[index], tire_impulse, point));
            // ADR-159 D3 : un corps dynamique sous la roue reçoit la réaction.
            if let Some((parent, ground)) = ground {
                if ground.is_dynamic() {
                    vehicle
                        .impulses
                        .push((parent, -(suspension_impulse + tire_impulse), point));
                }
            }
        }
    }
}
