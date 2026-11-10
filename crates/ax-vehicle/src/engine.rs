//! Le moteur (12.5, ADR-159 D6).

use ax_model::dm::vehicle::EngineDesc;

/// Tours par minute d'une rotation de 1 rad/s.
pub const RPM_PER_RAD_S: f32 = 60.0 / std::f32::consts::TAU;

/// Le couple de la courbe à un régime, en N·m : affine entre deux points, constant au-delà des
/// extrémités. Une courbe vide ne donne rien.
#[must_use]
pub fn curve_torque(curve: &[[f32; 2]], rpm: f32) -> f32 {
    let Some(first) = curve.first() else {
        return 0.0;
    };
    if rpm <= first[0] {
        return first[1];
    }
    for pair in curve.windows(2) {
        let (a, b) = (pair[0], pair[1]);
        if rpm <= b[0] {
            return a[1] + (b[1] - a[1]) * (rpm - a[0]) / (b[0] - a[0]);
        }
    }
    curve.last().map_or(0.0, |last| last[1])
}

/// Le couple le plus haut de la courbe, en N·m.
#[must_use]
pub fn peak_torque(curve: &[[f32; 2]]) -> f32 {
    curve.iter().fold(0.0, |peak, point| peak.max(point[1]))
}

/// Le couple du moteur qui tourne à `speed` rad/s, en N·m : la courbe multipliée par
/// l'accélérateur, et, hors accélérateur, `braking_torque` qui s'oppose à la rotation. La courbe ne
/// donne plus rien à partir de `max_rpm`.
#[must_use]
pub fn torque(engine: &EngineDesc, curve: &[[f32; 2]], speed: f32, throttle: f32) -> f32 {
    let throttle = throttle.clamp(0.0, 1.0);
    let rpm = speed * RPM_PER_RAD_S;
    let drive = if rpm < engine.max_rpm {
        throttle * curve_torque(curve, rpm)
    } else {
        0.0
    };
    drive - (1.0 - throttle) * engine.braking_torque * crate::sign(speed)
}

#[cfg(test)]
mod tests {
    use super::*;

    const COURBE: [[f32; 2]; 5] = [
        [0.0, 0.0],
        [1000.0, 180.0],
        [3000.0, 260.0],
        [5500.0, 240.0],
        [7000.0, 180.0],
    ];

    fn moteur() -> EngineDesc {
        EngineDesc {
            idle_rpm: 800.0,
            max_rpm: 7000.0,
            inertia: 0.3,
            braking_torque: 25.0,
        }
    }

    fn proche(a: f32, b: f32) -> bool {
        (a - b).abs() < 1e-3
    }

    fn vitesse(rpm: f32) -> f32 {
        rpm / RPM_PER_RAD_S
    }

    #[test]
    fn la_courbe_s_interpole_entre_ses_points_et_reste_constante_au_dela() {
        assert!(proche(curve_torque(&COURBE, 1000.0), 180.0));
        assert!(proche(curve_torque(&COURBE, 2000.0), 220.0));
        assert!(proche(curve_torque(&COURBE, 6250.0), 210.0));
        assert!(proche(curve_torque(&COURBE, -10.0), 0.0));
        assert!(proche(curve_torque(&COURBE, 9000.0), 180.0));
        assert!(proche(curve_torque(&[], 3000.0), 0.0));
        assert!(proche(peak_torque(&COURBE), 260.0));
    }

    #[test]
    fn l_accelerateur_dose_la_courbe_et_le_frein_moteur_s_y_substitue() {
        let m = moteur();
        assert!(proche(torque(&m, &COURBE, vitesse(3000.0), 1.0), 260.0));
        assert!(proche(
            torque(&m, &COURBE, vitesse(3000.0), 0.5),
            130.0 - 12.5
        ));
        assert!(proche(torque(&m, &COURBE, vitesse(3000.0), 0.0), -25.0));
        assert!(
            proche(torque(&m, &COURBE, 0.0, 0.0), 0.0),
            "à l'arrêt, rien à freiner"
        );
        assert!(
            proche(torque(&m, &COURBE, -vitesse(500.0), 0.0), 25.0),
            "le frein moteur s'oppose à la rotation"
        );
    }

    #[test]
    fn la_courbe_est_coupee_au_regime_maximal() {
        let m = moteur();
        assert!(torque(&m, &COURBE, vitesse(6990.0), 1.0) > 150.0);
        assert!(proche(torque(&m, &COURBE, vitesse(7000.0), 1.0), 0.0));
        assert!(proche(torque(&m, &COURBE, vitesse(7500.0), 1.0), 0.0));
    }
}
