//! Les paramètres réglés d'une instance (ADR-167 D1, D6).
//!
//! Java compose les causes et envoie la valeur effective de chaque paramètre ; ce module en dérive la description
//! que le pas lit, depuis la description de **base** de la definition, sans jamais la toucher. NaN garde la base :
//! c'est ainsi que se dit un paramètre que l'instance n'a pas, ou qu'elle ne règle pas.

use ax_model::dm::vehicle::VehicleDesc;

use crate::engine;

/// Les valeurs effectives des paramètres natifs d'un véhicule, NaN pour garder la base. Les facteurs valent 1 à la
/// base ; les rapports, s'il y en a, sont la table entière de la definition, point mort compris.
#[derive(Debug, Clone, PartialEq)]
pub struct Tuning {
    /// Le pic de la courbe de couple, en N·m.
    pub torque: f32,
    /// Le régime du limiteur, en tr/min.
    pub max_rpm: f32,
    /// Le rapport de pont.
    pub final_drive: f32,
    /// La durée d'un passage, en secondes.
    pub shift_time: f32,
    /// Le couple de freinage avant, en N·m.
    pub brake_front: f32,
    /// Le couple de freinage arrière, en N·m.
    pub brake_rear: f32,
    /// Le couple du frein à main, en N·m.
    pub handbrake: f32,
    /// Facteur de `mu_long` et `mu_lat` de chaque roue.
    pub grip: f32,
    /// Facteur de la raideur de chaque suspension.
    pub stiffness: f32,
    /// Facteur des amortissements de chaque suspension.
    pub damping: f32,
    /// Le braquage maximal, en radians.
    pub max_angle: f32,
    /// Le coefficient de traînée.
    pub drag: f32,
    /// L'appui avant.
    pub downforce_front: f32,
    /// L'appui arrière.
    pub downforce_rear: f32,
    /// La table des rapports, vide pour garder celle de la base.
    pub gears: Vec<f32>,
}

impl Tuning {
    /// Aucun réglage : la base en tout.
    #[must_use]
    pub fn base() -> Self {
        Self {
            torque: f32::NAN,
            max_rpm: f32::NAN,
            final_drive: f32::NAN,
            shift_time: f32::NAN,
            brake_front: f32::NAN,
            brake_rear: f32::NAN,
            handbrake: f32::NAN,
            grip: f32::NAN,
            stiffness: f32::NAN,
            damping: f32::NAN,
            max_angle: f32::NAN,
            drag: f32::NAN,
            downforce_front: f32::NAN,
            downforce_rear: f32::NAN,
            gears: Vec::new(),
        }
    }

    /// La description effective, dérivée de la base.
    ///
    /// - le couple met la courbe à l'échelle de son pic, sa forme gardée ; un moteur sans couple n'a rien à mettre à
    ///   l'échelle ;
    /// - le limiteur emmène les seuils de la boîte automatique dans le même rapport ; la courbe n'est pas étirée ;
    /// - les facteurs multiplient les valeurs de chaque roue ;
    /// - une table de rapports d'une autre longueur que celle de la base est ignorée, comme une valeur non finie
    ///   autre que NaN : Java les a déjà bornées, le natif ne devine rien.
    #[must_use]
    pub fn apply(&self, base: &VehicleDesc) -> VehicleDesc {
        let mut desc = base.clone();
        if let Some(torque) = finite(self.torque) {
            let peak = engine::peak_torque(&base.torque_curve);
            if peak > 0.0 {
                let scale = torque / peak;
                for point in &mut desc.torque_curve {
                    point[1] *= scale;
                }
            }
        }
        if let Some(max_rpm) = finite(self.max_rpm) {
            let ratio = max_rpm / base.engine.max_rpm;
            desc.engine.max_rpm = max_rpm;
            desc.transmission.auto_shift_up_rpm = base.transmission.auto_shift_up_rpm * ratio;
            desc.transmission.auto_shift_down_rpm = base.transmission.auto_shift_down_rpm * ratio;
        }
        set(&mut desc.transmission.final_drive, self.final_drive);
        set(&mut desc.transmission.shift_time, self.shift_time);
        set(&mut desc.brakes.max_torque_front, self.brake_front);
        set(&mut desc.brakes.max_torque_rear, self.brake_rear);
        set(&mut desc.brakes.handbrake_torque, self.handbrake);
        set(&mut desc.steering.max_angle, self.max_angle);
        set(&mut desc.aero.drag_coefficient, self.drag);
        set(&mut desc.aero.downforce_front, self.downforce_front);
        set(&mut desc.aero.downforce_rear, self.downforce_rear);
        for (wheel, of) in desc.wheels.iter_mut().zip(&base.wheels) {
            if let Some(grip) = finite(self.grip) {
                wheel.mu_long = of.mu_long * grip;
                wheel.mu_lat = of.mu_lat * grip;
            }
            if let Some(stiffness) = finite(self.stiffness) {
                wheel.suspension.stiffness = of.suspension.stiffness * stiffness;
            }
            if let Some(damping) = finite(self.damping) {
                wheel.suspension.damping_compression = of.suspension.damping_compression * damping;
                wheel.suspension.damping_rebound = of.suspension.damping_rebound * damping;
            }
        }
        if self.gears.len() == base.gears.len() {
            for (gear, value) in desc.gears.iter_mut().zip(&self.gears) {
                set(gear, *value);
            }
        }
        desc
    }
}

fn finite(value: f32) -> Option<f32> {
    value.is_finite().then_some(value)
}

fn set(field: &mut f32, value: f32) {
    if let Some(value) = finite(value) {
        *field = value;
    }
}

#[cfg(test)]
mod tests {
    use super::Tuning;
    use crate::tests_support::vehicule as description;

    #[test]
    fn sans_reglage_la_base_en_tout() {
        let base = description();
        assert_eq!(Tuning::base().apply(&base), base);
    }

    #[test]
    fn le_couple_met_la_courbe_a_l_echelle_et_le_limiteur_emmene_les_seuils() {
        let base = description();
        let peak = crate::engine::peak_torque(&base.torque_curve);
        let reglee = Tuning {
            torque: peak * 1.5,
            max_rpm: base.engine.max_rpm * 1.25,
            ..Tuning::base()
        }
        .apply(&base);
        for (a, b) in reglee.torque_curve.iter().zip(&base.torque_curve) {
            assert_eq!(a[0], b[0], "les régimes ne bougent pas");
            assert!((a[1] - b[1] * 1.5).abs() < 1e-3, "{a:?} {b:?}");
        }
        assert!((crate::engine::peak_torque(&reglee.torque_curve) - peak * 1.5).abs() < 1e-3);
        assert!(
            (reglee.transmission.auto_shift_up_rpm - base.transmission.auto_shift_up_rpm * 1.25)
                .abs()
                < 1e-3
        );
    }

    #[test]
    fn les_facteurs_multiplient_chaque_roue_et_les_rapports_se_posent_par_table() {
        let base = description();
        let mut gears = base.gears.clone();
        if let Some(first) = gears.iter_mut().find(|gear| **gear > 0.0) {
            *first *= 0.8;
        }
        let reglee = Tuning {
            grip: 1.2,
            stiffness: 0.5,
            damping: 2.0,
            gears: gears.clone(),
            ..Tuning::base()
        }
        .apply(&base);
        for (a, b) in reglee.wheels.iter().zip(&base.wheels) {
            assert!((a.mu_long - b.mu_long * 1.2).abs() < 1e-6);
            assert!((a.mu_lat - b.mu_lat * 1.2).abs() < 1e-6);
            assert!((a.suspension.stiffness - b.suspension.stiffness * 0.5).abs() < 1e-6);
            assert!(
                (a.suspension.damping_rebound - b.suspension.damping_rebound * 2.0).abs() < 1e-6
            );
        }
        assert_eq!(reglee.gears, gears);
        let mal = Tuning {
            gears: vec![1.0],
            ..Tuning::base()
        }
        .apply(&base);
        assert_eq!(
            mal.gears, base.gears,
            "une table d'une autre longueur est ignorée"
        );
    }

    #[test]
    fn une_valeur_non_finie_garde_la_base() {
        let base = description();
        let reglee = Tuning {
            brake_front: f32::INFINITY,
            drag: 0.4,
            ..Tuning::base()
        }
        .apply(&base);
        assert_eq!(reglee.brakes.max_torque_front, base.brakes.max_torque_front);
        assert_eq!(reglee.aero.drag_coefficient, 0.4);
    }
}
