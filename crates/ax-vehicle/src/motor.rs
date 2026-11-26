//! Les moteurs électriques, au micro-pas du groupe motopropulseur (12.5, ADR-182 D3).
//!
//! Un moteur tire sa puissance mécanique `couple × vitesse`, divisée par son rendement, de sa réserve en kWh ; au
//! lever de pied et au freinage, il retient les roues et leur rend une part de ce qu'il absorbe. Retenir passe par le
//! même chemin que les freins : il arrête une roue, jamais ne la fait repartir. L'énergie est tenue en `f64`, par
//! additions simples, sans `mul_add` (INV-14).

use crate::engine::{curve_torque, RPM_PER_RAD_S};
use crate::powertrain::{DriveCommand, WheelDrive};
use crate::sign;
use ax_model::dm::vehicle::{
    motor_coupling, MotorDesc, VehicleDesc, MAX_MOTORS, MAX_STORES, MAX_WHEELS,
};

/// Joules d'un kilowattheure.
const JOULES_PER_KWH: f64 = 3.6e6;

/// Les moteurs électriques d'un véhicule pour un sous-pas : ce qu'ils peuvent tirer, ce qu'ils ont fait.
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct Motors {
    /// Un bit par moteur autorisé à donner et à récupérer.
    pub enabled: u8,
    /// Le contenu des réserves, que les moteurs tirent et remplissent au fil des micro-pas.
    pub stores: [f64; MAX_STORES],
    /// Le régime de chaque moteur au dernier micro-pas, en tr/min, positif dans le sens engagé.
    pub rpm: [f32; MAX_MOTORS],
    /// La puissance électrique de chaque moteur, moyenne du sous-pas, en kW : positive tirée, négative rendue.
    pub power: [f32; MAX_MOTORS],
    /// L'énergie nette tirée par chaque moteur pendant le sous-pas, en kWh.
    drawn: [f64; MAX_MOTORS],
}

/// Les couples des moteurs pour un micro-pas.
#[derive(Debug, Clone, Copy)]
pub(crate) struct MicroStep {
    /// Couple ajouté à la sortie de la boîte, rendement compris, que le différentiel partage.
    pub shaft: f32,
    /// Couple moteur posé sur chaque roue, pour un moteur couplé à ses roues.
    pub drive: [f32; MAX_WHEELS],
    /// Couple de récupération qui retient chaque roue.
    pub regen: [f32; MAX_WHEELS],
    /// La part de chaque moteur dans le couple de récupération de chaque roue.
    parts: [[f32; MAX_WHEELS]; MAX_MOTORS],
}

impl Motors {
    /// Les moteurs autorisés, sur ces réserves.
    #[must_use]
    pub fn new(enabled: u8, stores: [f64; MAX_STORES]) -> Self {
        Self {
            enabled,
            stores,
            ..Self::default()
        }
    }

    /// Ce que les moteurs font à un micro-pas de `h` secondes : leur couple, et ce qu'ils tirent de leurs réserves.
    /// `carrier` est la vitesse moyenne des roues motrices à la sortie de la boîte, en rad/s.
    pub(crate) fn micro_step(
        &mut self,
        desc: &VehicleDesc,
        command: &DriveCommand,
        wheels: &[WheelDrive],
        carrier: f32,
        h: f32,
    ) -> MicroStep {
        let mut micro = MicroStep {
            shaft: 0.0,
            drive: [0.0; MAX_WHEELS],
            regen: [0.0; MAX_WHEELS],
            parts: [[0.0; MAX_WHEELS]; MAX_MOTORS],
        };
        // Le sens engagé : celui du rapport ; nul au point mort et pendant un changement.
        let direction = if command.ratio == 0.0 {
            0.0
        } else {
            sign(command.ratio)
        };
        let efficiency = desc.transmission.efficiency;
        for (rank, motor) in desc.motors.iter().enumerate().take(MAX_MOTORS) {
            let speed = motor_speed(motor, command.ratio, wheels, carrier);
            self.rpm[rank] = speed * RPM_PER_RAD_S;
            if self.enabled & (1 << rank) == 0 || direction == 0.0 {
                continue;
            }
            let Some(content) = self.stores.get(usize::from(motor.store)).copied() else {
                continue;
            };
            let capacity = f64::from(desc.energy.capacity(motor.store));
            let transmitted = motor.coupling == motor_coupling::TRANSMISSION;
            let driven = wheels.iter().enumerate().filter(|(index, wheel)| {
                if transmitted {
                    wheel.share > 0.0
                } else {
                    motor.wheels & (1 << index) != 0
                }
            });
            let count = driven.clone().count() as f32;
            if count == 0.0 {
                continue;
            }
            if command.throttle > 0.0 {
                let rpm = self.rpm[rank].abs();
                if content <= 0.0 || rpm >= motor.max_rpm {
                    continue;
                }
                let torque = curve_torque(&motor.torque_curve, rpm) * command.throttle.min(1.0);
                if transmitted {
                    micro.shaft += torque * command.ratio * efficiency;
                } else {
                    for (index, _) in driven {
                        micro.drive[index] += direction * torque * motor.ratio / count;
                    }
                }
                let power = (torque * speed).max(0.0);
                let energy =
                    f64::from(power) * f64::from(h) / f64::from(motor.efficiency) / JOULES_PER_KWH;
                let taken = energy.min(content);
                self.stores[usize::from(motor.store)] = content - taken;
                self.drawn[rank] += taken;
            } else {
                // Une réserve pleine ne récupère rien : il reste les freins.
                let torque =
                    motor.regen_lift_off + motor.regen_brake * command.brake.clamp(0.0, 1.0);
                if torque <= 0.0 || content >= capacity {
                    continue;
                }
                for (index, wheel) in driven {
                    let part = if transmitted {
                        torque * command.ratio.abs() / efficiency * wheel.share
                    } else {
                        torque * motor.ratio / count
                    };
                    micro.parts[rank][index] = part;
                    micro.regen[index] += part;
                }
            }
        }
        micro
    }

    /// Rend à leurs réserves la part du travail `work`, en joules, que la récupération a pris à la roue `wheel` pendant ce
    /// micro-pas.
    pub(crate) fn recover(
        &mut self,
        desc: &VehicleDesc,
        micro: &MicroStep,
        wheel: usize,
        work: f32,
    ) {
        if work <= 0.0 || micro.regen[wheel] <= 0.0 {
            return;
        }
        for (rank, motor) in desc.motors.iter().enumerate().take(MAX_MOTORS) {
            let part = micro.parts[rank][wheel];
            if part <= 0.0 {
                continue;
            }
            let gearbox = if motor.coupling == motor_coupling::TRANSMISSION {
                desc.transmission.efficiency
            } else {
                1.0
            };
            let energy = f64::from(work)
                * f64::from(part / micro.regen[wheel])
                * f64::from(gearbox)
                * f64::from(motor.regen_efficiency)
                / JOULES_PER_KWH;
            let store = usize::from(motor.store);
            let room = f64::from(desc.energy.capacity(motor.store)) - self.stores[store];
            let returned = energy.min(room).max(0.0);
            self.stores[store] += returned;
            self.drawn[rank] -= returned;
        }
    }

    /// La puissance moyenne du sous-pas de `dt` secondes, une fois ses micro-pas faits.
    pub(crate) fn finish(&mut self, dt: f32) {
        for (power, drawn) in self.power.iter_mut().zip(self.drawn) {
            *power = if dt > 0.0 {
                (drawn * 3600.0 / f64::from(dt)) as f32
            } else {
                0.0
            };
        }
        self.drawn = [0.0; MAX_MOTORS];
    }
}

/// La vitesse d'un moteur, en rad/s, positive dans le sens engagé : celle de la sortie de la boîte multipliée par le
/// rapport, ou celle de ses roues multipliée par le sien.
fn motor_speed(motor: &MotorDesc, ratio: f32, wheels: &[WheelDrive], carrier: f32) -> f32 {
    if motor.coupling == motor_coupling::TRANSMISSION {
        return ratio * carrier;
    }
    let (sum, count) = wheels
        .iter()
        .enumerate()
        .filter(|(index, _)| motor.wheels & (1 << index) != 0)
        .fold((0.0f32, 0u32), |(sum, count), (_, wheel)| {
            (sum + wheel.spin, count + 1)
        });
    if count == 0 {
        return 0.0;
    }
    let reversed = if ratio < 0.0 { -1.0 } else { 1.0 };
    reversed * motor.ratio * sum / count as f32
}
