//! Le modèle de pneu (12.4, ADR-159 D4).

use ax_model::dm::vehicle::{TireCurve, WheelDesc};

/// Vitesse sous laquelle `slip_long` cesse de diviser par la vitesse du sol, en m/s (`eps` de 12.4).
pub const SLIP_EPSILON: f32 = 0.1;

/// L'adhérence normalisée à un glissement, de signe quelconque : affine jusqu'au pic, affine
/// jusqu'à la queue, constante au-delà.
#[must_use]
pub fn curve(curve: &TireCurve, slip: f32) -> f32 {
    let s = slip.abs();
    if s <= curve.peak_slip {
        curve.peak_value * s / curve.peak_slip
    } else if s < curve.tail_slip {
        let t = (s - curve.peak_slip) / (curve.tail_slip - curve.peak_slip);
        curve.peak_value + (curve.tail_value - curve.peak_value) * t
    } else {
        curve.tail_value
    }
}

/// Le glissement longitudinal : positif quand la roue tourne plus vite que le sol ne défile.
#[must_use]
pub fn slip_long(wheel_speed: f32, ground_speed: f32) -> f32 {
    (wheel_speed - ground_speed) / ground_speed.abs().max(SLIP_EPSILON)
}

/// Le glissement latéral, en radians.
#[must_use]
pub fn slip_lat(lateral_speed: f32, longitudinal_speed: f32) -> f32 {
    lateral_speed.atan2(longitudinal_speed.abs())
}

/// Les forces d'un pneu dans le plan de contact, en newtons.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct TireForce {
    /// Force longitudinale, positive vers l'avant de la roue.
    pub long: f32,
    /// Force latérale, de signe opposé au glissement latéral.
    pub lat: f32,
}

/// Les forces du pneu (12.4), cercle de friction compris. `surface` est le coefficient du matériau
/// touché, qui multiplie celui du pneu (R-1070).
#[must_use]
pub fn forces(wheel: &WheelDesc, surface: f32, normal: f32, long: f32, lat: f32) -> TireForce {
    let load = normal.max(0.0) * surface;
    let f_long = load * wheel.mu_long * curve(&wheel.long, long) * long.signum();
    let f_lat = -load * wheel.mu_lat * curve(&wheel.lat, lat) * lat.signum();
    let limit = load * wheel.mu_long.max(wheel.mu_lat);
    let norm = (f_long * f_long + f_lat * f_lat).sqrt();
    let scale = if norm > limit && norm > 0.0 {
        limit / norm
    } else {
        1.0
    };
    TireForce {
        long: f_long * scale,
        lat: f_lat * scale,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ax_model::dm::scene::NONE_U16;
    use ax_model::dm::vehicle::SuspensionDesc;

    const COURBE: TireCurve = TireCurve {
        peak_slip: 0.1,
        peak_value: 1.0,
        tail_slip: 0.5,
        tail_value: 0.6,
    };

    fn roue() -> WheelDesc {
        WheelDesc {
            node: 0,
            flags: 0,
            axle: NONE_U16,
            radius: 0.36,
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
            long: COURBE,
            lat: COURBE,
        }
    }

    fn proche(a: f32, b: f32) -> bool {
        (a - b).abs() < 1e-3
    }

    #[test]
    fn la_courbe_passe_par_ses_quatre_points_et_reste_a_sa_queue() {
        assert!(proche(curve(&COURBE, 0.0), 0.0));
        assert!(proche(curve(&COURBE, 0.05), 0.5));
        assert!(proche(curve(&COURBE, 0.1), 1.0));
        assert!(proche(curve(&COURBE, 0.3), 0.8));
        assert!(proche(curve(&COURBE, 0.5), 0.6));
        assert!(proche(curve(&COURBE, 9.0), 0.6));
        assert!(proche(curve(&COURBE, -0.1), 1.0), "symétrique");
    }

    #[test]
    fn les_glissements_ont_le_signe_de_la_cause() {
        assert!(slip_long(12.0, 10.0) > 0.0, "roue plus rapide");
        assert!(slip_long(8.0, 10.0) < 0.0, "roue freinée");
        assert!(
            proche(slip_long(0.0, -10.0), 1.0),
            "marche arrière, roue bloquée"
        );
        assert!(
            proche(slip_long(1.0, 0.0), 10.0),
            "à l'arrêt, eps évite la division par zéro"
        );
        assert!(proche(slip_lat(1.0, 1.0), std::f32::consts::FRAC_PI_4));
        assert!(
            proche(slip_lat(1.0, -1.0), std::f32::consts::FRAC_PI_4),
            "|v_long|"
        );
    }

    #[test]
    fn la_roue_motrice_pousse_et_la_force_laterale_s_oppose() {
        let f = forces(&roue(), 1.0, 1000.0, 0.05, 0.0);
        assert!(proche(f.long, 500.0));
        let g = forces(&roue(), 1.0, 1000.0, 0.0, 0.05);
        assert!(proche(g.lat, -500.0));
        assert!(proche(
            forces(&roue(), 1.0, 1000.0, -0.05, 0.0).long,
            -500.0
        ));
    }

    #[test]
    fn r1070_le_materiau_touche_multiplie_l_adherence() {
        let route = forces(&roue(), 1.0, 1000.0, 0.1, 0.0).long;
        let glace = forces(&roue(), 0.1, 1000.0, 0.1, 0.0).long;
        assert!(proche(glace, route * 0.1));
    }

    #[test]
    fn le_cercle_de_friction_met_les_deux_composantes_a_l_echelle() {
        let f = forces(&roue(), 1.0, 1000.0, 0.1, 0.1);
        assert!(proche((f.long * f.long + f.lat * f.lat).sqrt(), 1000.0));
        assert!(proche(f.long, -f.lat), "à la même échelle");
    }

    #[test]
    fn sans_charge_le_pneu_n_a_aucune_force() {
        let f = forces(&roue(), 1.0, 0.0, 0.1, 0.1);
        assert!(proche(f.long, 0.0) && proche(f.lat, 0.0));
    }
}
