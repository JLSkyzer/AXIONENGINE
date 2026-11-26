//! Le prolongateur d'autonomie (12.5, ADR-182 D4) : le moteur à combustion, découplé des roues, charge une réserve en
//! kWh entre deux seuils.
//!
//! Il travaille à son régime de plus grande puissance, avec la charge que demande sa puissance électrique ; la
//! consommation suit la courbe du moteur, comme quand il roule. L'état est une hystérésis : démarrer sous le seuil bas,
//! s'arrêter au haut.

use crate::engine::RPM_PER_RAD_S;
use ax_model::dm::vehicle::{GeneratorDesc, VehicleDesc};

/// Ce que le prolongateur fait quand son moteur tourne.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Plan {
    /// Le régime visé, en rad/s : celui où la courbe donne le plus de puissance.
    pub speed: f32,
    /// La charge du moteur, dans [0, 1] : la part de cette puissance que la génération demande.
    pub load: f32,
    /// La puissance électrique rendue à la réserve, en kW.
    pub electric_kw: f32,
}

/// Le plan du prolongateur d'un moteur : au point de la courbe de couple qui donne le plus de puissance, sous le régime
/// maximal. Un moteur sans puissance ne charge rien.
#[must_use]
pub fn plan(desc: &VehicleDesc, generator: &GeneratorDesc) -> Plan {
    let (speed, power_kw) = desc
        .torque_curve
        .iter()
        .filter(|point| point[0] <= desc.engine.max_rpm)
        .map(|point| {
            let speed = point[0] / RPM_PER_RAD_S;
            (speed, point[1] * speed / 1000.0)
        })
        .fold(
            (0.0f32, 0.0f32),
            |best, next| {
                if next.1 > best.1 {
                    next
                } else {
                    best
                }
            },
        );
    let electric_kw = generator.power_kw.min(power_kw * generator.efficiency);
    let load = if power_kw > 0.0 {
        (electric_kw / generator.efficiency / power_kw).clamp(0.0, 1.0)
    } else {
        0.0
    };
    Plan {
        speed,
        load,
        electric_kw,
    }
}

/// L'hystérésis : vrai si le prolongateur doit charger, la réserve à `content` sur `capacity`, sachant qu'il chargeait
/// (`active`) ou non.
#[must_use]
pub fn wants(generator: &GeneratorDesc, content: f64, capacity: f32, active: bool) -> bool {
    if capacity <= 0.0 {
        return false;
    }
    // En f32, comme les seuils de la definition : un contenu posé au seuil le franchit.
    let ratio = (content / f64::from(capacity)) as f32;
    if ratio < generator.start_below {
        true
    } else if ratio >= generator.stop_above {
        false
    } else {
        active
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tests_support::vehicule;

    fn prolongateur(power_kw: f32) -> GeneratorDesc {
        GeneratorDesc {
            store: 0,
            power_kw,
            efficiency: 0.9,
            start_below: 0.3,
            stop_above: 0.8,
        }
    }

    #[test]
    fn adr182_d4_le_regime_de_puissance_et_la_charge() {
        let mut v = vehicule();
        v.torque_curve = vec![[0.0, 0.0], [3000.0, 200.0], [6000.0, 120.0], [7000.0, 20.0]];
        v.engine.max_rpm = 6500.0;
        // 200 N·m à 3000 : 62,8 kW ; 120 N·m à 6000 : 75,4 kW ; 7000 est au-delà du régime maximal.
        let p = plan(&v, &prolongateur(30.0));
        assert!((p.speed * RPM_PER_RAD_S - 6000.0).abs() < 0.5);
        assert_eq!(p.electric_kw, 30.0);
        assert!((p.load - 30.0 / 0.9 / 75.398).abs() < 1e-3, "{}", p.load);
        // Il ne rend pas plus que le moteur ne donne.
        let plein = plan(&v, &prolongateur(500.0));
        assert!((plein.electric_kw - 75.398 * 0.9).abs() < 1e-2);
        assert!((plein.load - 1.0).abs() < 1e-6);
        v.torque_curve = vec![[0.0, 0.0], [3000.0, 0.0]];
        assert_eq!(
            plan(&v, &prolongateur(30.0)).electric_kw,
            0.0,
            "sans couple"
        );
    }

    #[test]
    fn adr182_d4_l_hysteresis_entre_les_seuils() {
        let g = prolongateur(10.0);
        assert!(wants(&g, 0.2, 1.0, false), "sous le bas : démarre");
        assert!(wants(&g, 0.5, 1.0, true), "entre : continue");
        assert!(!wants(&g, 0.5, 1.0, false), "entre : reste arrêté");
        assert!(!wants(&g, 0.8, 1.0, true), "au haut : s'arrête");
        assert!(!wants(&g, 0.0, 0.0, true), "sans capacité");
    }
}
