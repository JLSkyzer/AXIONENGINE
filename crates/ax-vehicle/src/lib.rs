//! C-33 : le modèle de véhicule (PARTIE 12, ADR-159 D2).
//!
//! Des fonctions pures, sans Rapier ni monde : `ax-physics` lance les rayons et applique les
//! forces, le client les rejoue à l'identique pour la prédiction. Le moteur ne connaît aucun nom
//! de véhicule (INV-01) ; tout vient de la definition.

pub mod brakes;
pub mod differential;
pub mod engine;
pub mod powertrain;
pub mod steering;
pub mod suspension;
pub mod tire;
pub mod transmission;

/// Le signe d'un nombre, nul en zéro : une force de frottement ne s'exerce pas sur ce qui est immobile.
#[must_use]
pub fn sign(value: f32) -> f32 {
    if value > 0.0 {
        1.0
    } else if value < 0.0 {
        -1.0
    } else {
        0.0
    }
}

#[cfg(test)]
mod tests_support {
    use ax_model::dm::scene::NONE_U16;
    use ax_model::dm::vehicle::{
        differential_kind, transmission_kind, vehicle_flags, AeroDesc, AssistDesc, BrakesDesc,
        DifferentialDesc, EngineDesc, SteeringDesc, SuspensionDesc, TireCurve, TransmissionDesc,
        VehicleDesc, WheelDesc,
    };

    /// Une berline : les valeurs des exemples de 12.3 à 12.6, quatre roues, propulsion.
    pub(crate) fn vehicule() -> VehicleDesc {
        let roue = WheelDesc {
            node: 0,
            flags: 0,
            axle: NONE_U16,
            radius: 0.35,
            width: 0.24,
            mass: 20.0,
            suspension: SuspensionDesc {
                rest_length: 0.35,
                max_travel: 0.25,
                stiffness: 30.0,
                damping_compression: 2.3,
                damping_rebound: 2.8,
                max_force: 60_000.0,
                anti_roll: 0.0,
            },
            mu_long: 1.0,
            mu_lat: 1.0,
            long: TireCurve {
                peak_slip: 0.12,
                peak_value: 1.0,
                tail_slip: 0.5,
                tail_value: 0.75,
            },
            lat: TireCurve {
                peak_slip: 0.15,
                peak_value: 1.0,
                tail_slip: 0.6,
                tail_value: 0.8,
            },
        };
        VehicleDesc {
            flags: vehicle_flags::POWERTRAIN,
            engine: EngineDesc {
                idle_rpm: 800.0,
                max_rpm: 7000.0,
                inertia: 0.3,
                braking_torque: 25.0,
            },
            transmission: TransmissionDesc {
                kind: transmission_kind::MANUAL,
                final_drive: 3.7,
                shift_time: 0.25,
                auto_shift_up_rpm: 6200.0,
                auto_shift_down_rpm: 2200.0,
                efficiency: 0.92,
            },
            differential: DifferentialDesc {
                kind: differential_kind::OPEN,
                front_rear_split: 0.0,
                lock: 0.0,
            },
            steering: SteeringDesc {
                max_angle: 0.61,
                speed: 3.0,
                return_speed: 4.0,
                speed_sensitivity: 0.6,
                ackermann: 1.0,
            },
            brakes: BrakesDesc {
                max_torque_front: 3000.0,
                max_torque_rear: 1800.0,
                handbrake_torque: 4000.0,
                abs_slip_threshold: 0.15,
            },
            aero: AeroDesc {
                drag_coefficient: 0.32,
                frontal_area: 2.2,
                downforce_front: 0.0,
                downforce_rear: 0.0,
            },
            assists: AssistDesc {
                traction_slip_threshold: 0.2,
                stability_yaw_tolerance: 0.15,
            },
            wheels: vec![roue; 4],
            torque_curve: vec![
                [0.0, 0.0],
                [1000.0, 180.0],
                [3000.0, 260.0],
                [5500.0, 240.0],
                [7000.0, 180.0],
            ],
            gears: vec![-3.2, 0.0, 3.5, 2.1, 1.4, 1.0, 0.8],
            surfaces: Vec::new(),
        }
    }
}
