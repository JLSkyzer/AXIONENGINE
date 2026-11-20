//! L'énergie du véhicule : un réservoir et une batterie (12.5, ADR-176 D3).
//!
//! L'état est en `f64` : au ralenti, un sous-pas brûle de l'ordre de 2·10⁻⁷ litre, sous l'ulp d'un `f32` à 40 litres, et
//! la consommation s'y perdrait. Les additions sont simples, sans `mul_add` (INV-14, le binaire est compilé sans FMA) : le
//! pas est fixe, la consommation suit le temps simulé.

use crate::engine::RPM_PER_RAD_S;
use ax_model::dm::vehicle::EnergyDesc;

/// Ce que le moteur peut attendre de l'énergie à un pas : de quoi brûler, de quoi lancer le démarreur.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Supply {
    /// Le réservoir n'est pas vide (ou il n'y en a pas).
    pub fuel: bool,
    /// La batterie n'est pas vide (ou il n'y en a pas).
    pub battery: bool,
}

impl Supply {
    /// Rien ne manque : ce que voit un véhicule sans énergie déclarée.
    pub const FULL: Self = Self {
        fuel: true,
        battery: true,
    };
}

/// Le carburant et la batterie d'un véhicule.
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct EnergyState {
    /// Carburant restant, en litres.
    pub fuel: f64,
    /// Charge restante de la batterie.
    pub battery: f64,
}

impl EnergyState {
    /// Un véhicule neuf : réservoir plein, batterie pleine.
    #[must_use]
    pub fn full(desc: &EnergyDesc) -> Self {
        Self {
            fuel: f64::from(desc.fuel_capacity),
            battery: f64::from(desc.battery_capacity),
        }
    }

    /// Ramène l'état dans les capacités de la definition : un état relu ne les dépasse jamais.
    pub fn clamp(&mut self, desc: &EnergyDesc) {
        self.fuel = clamp_to(self.fuel, f64::from(desc.fuel_capacity));
        self.battery = clamp_to(self.battery, f64::from(desc.battery_capacity));
    }

    /// Ce que le moteur peut attendre de l'énergie : sans réservoir, le carburant ne manque jamais, de même sans batterie.
    #[must_use]
    pub fn supply(&self, desc: &EnergyDesc) -> Supply {
        Supply {
            fuel: desc.fuel_capacity <= 0.0 || self.fuel > 0.0,
            battery: desc.battery_capacity <= 0.0 || self.battery > 0.0,
        }
    }

    /// Avance l'énergie d'un pas de `dt` secondes.
    ///
    /// Le moteur qui tourne brûle `courbe(charge) × régime / régime_max` litres par heure et recharge la batterie ; le
    /// démarreur qui tourne la décharge. Un moteur arrêté ne brûle rien.
    pub fn step(&mut self, desc: &EnergyDesc, burn: Burn, dt: f32) {
        let dt = f64::from(dt);
        if desc.fuel_capacity > 0.0 && burn.running {
            let per_hour = f64::from(consumption(&desc.consumption, burn.load))
                * f64::from(burn.rpm / burn.max_rpm.max(f32::EPSILON)).clamp(0.0, 1.0);
            self.fuel -= per_hour / 3600.0 * dt;
        }
        if desc.battery_capacity > 0.0 {
            if burn.cranking {
                self.battery -= f64::from(desc.starter_draw) * dt;
            }
            if burn.running {
                self.battery += f64::from(desc.charge_rate) * dt;
            }
        }
        self.clamp(desc);
    }
}

/// Ce que le moteur fait à l'énergie pendant un pas.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Burn {
    /// Le moteur tourne.
    pub running: bool,
    /// Le démarreur tourne.
    pub cranking: bool,
    /// La charge du moteur, dans [0, 1].
    pub load: f32,
    /// Le régime du moteur, en tr/min.
    pub rpm: f32,
    /// Le régime maximal, en tr/min.
    pub max_rpm: f32,
}

impl Burn {
    /// Le régime en tr/min d'une vitesse en rad/s.
    #[must_use]
    pub fn rpm_of(speed: f32) -> f32 {
        speed * RPM_PER_RAD_S
    }
}

fn clamp_to(value: f64, max: f64) -> f64 {
    if value.is_finite() {
        value.clamp(0.0, max.max(0.0))
    } else {
        0.0
    }
}

/// Les litres par heure de la courbe à une charge : affine entre deux points, constant au-delà des extrémités.
fn consumption(curve: &[[f32; 2]], load: f32) -> f32 {
    let load = load.clamp(0.0, 1.0);
    let Some(first) = curve.first() else {
        return 0.0;
    };
    if load <= first[0] {
        return first[1];
    }
    for pair in curve.windows(2) {
        let (a, b) = (pair[0], pair[1]);
        if load <= b[0] {
            return a[1] + (b[1] - a[1]) * (load - a[0]) / (b[0] - a[0]);
        }
    }
    curve.last().map_or(0.0, |last| last[1])
}

#[cfg(test)]
mod tests {
    use super::*;

    fn energie() -> EnergyDesc {
        EnergyDesc {
            fuel_capacity: 40.0,
            unit_mass: 0.75,
            battery_capacity: 1.0,
            starter_draw: 0.02,
            charge_rate: 0.002,
            consumption: vec![[0.0, 0.4], [1.0, 9.0]],
        }
    }

    fn tourne(charge: f32) -> Burn {
        Burn {
            running: true,
            cranking: false,
            load: charge,
            rpm: 7000.0,
            max_rpm: 7000.0,
        }
    }

    #[test]
    fn adr176_d3_un_vehicule_neuf_a_ses_reservoirs_pleins() {
        let e = EnergyState::full(&energie());
        assert_eq!(e.fuel, 40.0);
        assert_eq!(e.battery, 1.0);
        assert_eq!(EnergyState::full(&EnergyDesc::NONE), EnergyState::default());
    }

    #[test]
    fn adr176_d3_la_consommation_suit_la_courbe_et_le_regime() {
        let mut e = EnergyState::full(&energie());
        // Plein régime, pleine charge : 9 l/h. Une heure simulée en 3 600 pas d'une seconde.
        for _ in 0..3600 {
            e.step(&energie(), tourne(1.0), 1.0);
        }
        assert!((e.fuel - 31.0).abs() < 1e-6, "9 litres brûlés : {}", e.fuel);
        // Au demi-régime, la moitié.
        let mut moitie = EnergyState::full(&energie());
        let burn = Burn {
            rpm: 3500.0,
            ..tourne(1.0)
        };
        for _ in 0..3600 {
            moitie.step(&energie(), burn, 1.0);
        }
        assert!(
            (moitie.fuel - 35.5).abs() < 1e-6,
            "4,5 litres : {}",
            moitie.fuel
        );
        // À mi-charge, la courbe donne 4,7 l/h.
        let mut milieu = EnergyState::full(&energie());
        for _ in 0..3600 {
            milieu.step(&energie(), tourne(0.5), 1.0);
        }
        assert!((milieu.fuel - (40.0 - 4.7)).abs() < 1e-6, "{}", milieu.fuel);
    }

    #[test]
    fn adr176_d3_le_ralenti_ne_s_arrondit_pas_a_zero() {
        // Un sous-pas de 1/240 s au ralenti : de l'ordre de 2·10⁻⁷ litre, sous l'ulp d'un f32 à 40 litres.
        let mut e = EnergyState::full(&energie());
        let ralenti = Burn {
            rpm: 800.0,
            ..tourne(0.0)
        };
        for _ in 0..240 * 60 {
            e.step(&energie(), ralenti, 1.0 / 240.0);
        }
        let brule = 40.0 - e.fuel;
        let attendu = 0.4 * (800.0 / 7000.0) / 60.0;
        assert!(
            (brule - attendu).abs() < attendu * 1e-4,
            "une minute au ralenti : {brule} litre, attendu {attendu}"
        );
    }

    #[test]
    fn adr176_d3_un_moteur_arrete_ne_brule_rien_et_le_demarreur_decharge_la_batterie() {
        let mut e = EnergyState::full(&energie());
        let arrete = Burn {
            running: false,
            ..tourne(1.0)
        };
        for _ in 0..600 {
            e.step(&energie(), arrete, 0.1);
        }
        assert_eq!(e.fuel, 40.0);
        let demarreur = Burn {
            running: false,
            cranking: true,
            ..tourne(0.0)
        };
        for _ in 0..10 {
            e.step(&energie(), demarreur, 0.5);
        }
        assert!((e.battery - 0.9).abs() < 1e-6, "5 s à 0,02 : {}", e.battery);
        // Le moteur qui tourne la recharge, jusqu'à la capacité.
        for _ in 0..2000 {
            e.step(&energie(), tourne(0.0), 1.0);
        }
        assert_eq!(e.battery, 1.0, "bornée à la capacité");
    }

    #[test]
    fn adr176_d3_le_reservoir_vide_manque_et_ne_descend_pas_sous_zero() {
        let mut e = EnergyState {
            fuel: 1e-9,
            battery: 0.0,
        };
        assert!(!e.supply(&energie()).battery, "batterie vide");
        e.step(&energie(), tourne(1.0), 1.0);
        assert_eq!(e.fuel, 0.0);
        assert!(!e.supply(&energie()).fuel, "réservoir vide");
    }

    #[test]
    fn adr176_d3_sans_energie_declaree_rien_ne_manque_jamais() {
        let nulle = EnergyDesc::NONE;
        let mut e = EnergyState::default();
        e.step(&nulle, tourne(1.0), 1.0);
        assert_eq!(e, EnergyState::default());
        assert_eq!(e.supply(&nulle), Supply::FULL);
    }

    #[test]
    fn adr176_d3_un_etat_relu_est_borne_aux_capacites() {
        let mut e = EnergyState {
            fuel: 1e9,
            battery: f64::NAN,
        };
        e.clamp(&energie());
        assert_eq!(e.fuel, 40.0);
        assert_eq!(e.battery, 0.0);
    }
}
