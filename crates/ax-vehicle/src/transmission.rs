//! La boîte de vitesses (12.5, R-1080, ADR-159 D6).
//!
//! Le rang d'un rapport se compte depuis le rapport nul de `gears` : 0 est le point mort, `n > 0`
//! le n-ième rapport avant, `n < 0` la marche arrière. Une boîte `direct` n'a que deux rangs, 1 et
//! -1, de rapport `±final_drive`.

use ax_model::dm::vehicle::{transmission_kind, VehicleDesc, VehicleInput};

/// Vitesse sous laquelle le véhicule est « proche de l'arrêt » pour changer de sens, en m/s. Choix
/// d'ADR-159 (tranche 3) : le CDC n'en donne pas.
pub const NEAR_STOP: f32 = 0.5;

/// Durée pendant laquelle la touche de l'autre sens reste enfoncée, près de l'arrêt, avant que la
/// boîte n'en change, en secondes. Choix d'ADR-159 (tranche 3).
pub const REVERSE_HOLD: f32 = 0.3;

/// Le rapport total d'un rang, pont compris et signé ; `None` hors de la table.
#[must_use]
pub fn ratio_of(desc: &VehicleDesc, rank: i8) -> Option<f32> {
    let t = &desc.transmission;
    if t.kind == transmission_kind::DIRECT {
        return match rank {
            1 => Some(t.final_drive),
            -1 => Some(-t.final_drive),
            _ => None,
        };
    }
    let neutral = desc.gears.iter().position(|gear| *gear == 0.0)?;
    let index = usize::try_from(i64::try_from(neutral).ok()? + i64::from(rank)).ok()?;
    desc.gears.get(index).map(|gear| gear * t.final_drive)
}

/// Ce que le moteur et les freins reçoivent du conducteur, la boîte jugée.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Demand {
    /// Accélérateur, dans [0, 1].
    pub throttle: f32,
    /// Frein, dans [0, 1].
    pub brake: f32,
}

/// L'état de la boîte d'un véhicule.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Gearbox {
    /// Le rang engagé.
    gear: i8,
    /// Le rang en cours d'engagement.
    target: i8,
    /// Le temps qui reste au changement en cours, en secondes.
    shift_left: f32,
    /// Depuis combien de temps la touche de l'autre sens est tenue près de l'arrêt, en secondes.
    hold: f32,
}

impl Gearbox {
    /// Une boîte neuve : au point mort en `manual`, en première sinon.
    #[must_use]
    pub fn new(desc: &VehicleDesc) -> Self {
        let gear = i8::from(desc.transmission.kind != transmission_kind::MANUAL);
        Self {
            gear,
            target: gear,
            shift_left: 0.0,
            hold: 0.0,
        }
    }

    /// Le rang engagé.
    #[must_use]
    pub fn gear(&self) -> i8 {
        self.gear
    }

    /// Son état entier : rang engagé, rang visé, temps restant au changement, maintien (ADR-168 D1).
    #[must_use]
    pub fn parts(&self) -> (i8, i8, f32, f32) {
        (self.gear, self.target, self.shift_left, self.hold)
    }

    /// Une boîte dans cet état : celui qu'un état restauré lui rend (ADR-168 D1). Un rang hors de la table de `desc`
    /// revient au point mort ; une durée négative ou non finie compte pour zéro.
    #[must_use]
    pub fn from_parts(
        desc: &VehicleDesc,
        gear: i8,
        target: i8,
        shift_left: f32,
        hold: f32,
    ) -> Self {
        let rank = |rank: i8| {
            if rank == 0 || ratio_of(desc, rank).is_some() {
                rank
            } else {
                0
            }
        };
        let duration = |value: f32| {
            if value.is_finite() {
                value.max(0.0)
            } else {
                0.0
            }
        };
        Self {
            gear: rank(gear),
            target: rank(target),
            shift_left: duration(shift_left),
            hold: duration(hold),
        }
    }

    /// Vrai pendant un changement de rapport : le couple ne passe pas.
    #[must_use]
    pub fn shifting(&self) -> bool {
        self.shift_left > 0.0
    }

    /// Le rapport total qui transmet le couple, signé : nul au point mort et pendant un changement.
    #[must_use]
    pub fn ratio(&self, desc: &VehicleDesc) -> f32 {
        if self.shifting() {
            0.0
        } else {
            ratio_of(desc, self.gear).unwrap_or(0.0)
        }
    }

    /// Ce que le conducteur demande au moteur et aux freins. Hors boîte `manual`, en marche arrière,
    /// accélérateur et frein s'échangent (ADR-159 D6) ; pendant un changement, le moteur est coupé.
    #[must_use]
    pub fn demand(&self, desc: &VehicleDesc, input: &VehicleInput) -> Demand {
        let swapped = desc.transmission.kind != transmission_kind::MANUAL && self.gear < 0;
        let (throttle, brake) = if swapped {
            (input.brake, input.throttle)
        } else {
            (input.throttle, input.brake)
        };
        Demand {
            throttle: if self.shifting() { 0.0 } else { throttle },
            brake,
        }
    }

    /// Avance la boîte d'un sous-pas. `forward_speed` est la vitesse du véhicule vers son avant (-Z),
    /// en m/s ; `input_rpm` le régime de l'arbre d'entrée, celui que les roues imposent au rapport
    /// engagé, en tr/min.
    pub fn update(
        &mut self,
        desc: &VehicleDesc,
        input: &VehicleInput,
        forward_speed: f32,
        input_rpm: f32,
        dt: f32,
    ) {
        if self.shifting() {
            self.shift_left -= dt;
            if self.shift_left <= 0.0 {
                self.shift_left = 0.0;
                self.gear = self.target;
            }
            return;
        }
        let t = &desc.transmission;
        if t.kind == transmission_kind::MANUAL {
            // Un rang hors de la table est ignoré : la boîte garde le sien.
            if input.gear != self.gear && ratio_of(desc, input.gear).is_some() {
                self.begin(input.gear, t.shift_time);
            }
            return;
        }
        // ADR-159 D6 : la touche de l'autre sens, tenue près de l'arrêt, change de sens.
        let other = if self.gear < 0 {
            input.throttle
        } else {
            input.brake
        };
        if other > 0.0 && forward_speed.abs() < NEAR_STOP {
            self.hold += dt;
            if self.hold >= REVERSE_HOLD {
                self.hold = 0.0;
                let to = if self.gear < 0 { 1 } else { -1 };
                if ratio_of(desc, to).is_some() {
                    let time = if t.kind == transmission_kind::DIRECT {
                        0.0
                    } else {
                        t.shift_time
                    };
                    self.begin(to, time);
                }
                return;
            }
        } else {
            self.hold = 0.0;
        }
        if t.kind == transmission_kind::AUTOMATIC && self.gear > 0 {
            self.shift_automatically(desc, input_rpm);
        }
    }

    /// `auto_shift_up_rpm` et `auto_shift_down_rpm`, sans engager un rapport qui repasserait aussitôt
    /// l'autre seuil.
    fn shift_automatically(&mut self, desc: &VehicleDesc, input_rpm: f32) {
        let t = &desc.transmission;
        let Some(current) = ratio_of(desc, self.gear).filter(|r| *r > 0.0) else {
            return;
        };
        let after = |rank: i8| {
            ratio_of(desc, rank)
                .filter(|r| *r > 0.0)
                .map(|r| input_rpm * r / current)
        };
        if input_rpm >= t.auto_shift_up_rpm {
            if let Some(rpm) = after(self.gear + 1) {
                if rpm > t.auto_shift_down_rpm {
                    self.begin(self.gear + 1, t.shift_time);
                }
            }
        } else if input_rpm <= t.auto_shift_down_rpm && self.gear > 1 {
            if let Some(rpm) = after(self.gear - 1) {
                if rpm < t.auto_shift_up_rpm {
                    self.begin(self.gear - 1, t.shift_time);
                }
            }
        }
    }

    fn begin(&mut self, to: i8, time: f32) {
        self.target = to;
        if time > 0.0 {
            self.shift_left = time;
        } else {
            self.gear = to;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tests_support::vehicule;

    const DT: f32 = 1.0 / 60.0;

    fn boite(kind: u8) -> VehicleDesc {
        let mut v = vehicule();
        v.transmission.kind = kind;
        v
    }

    fn entree(throttle: f32, brake: f32, gear: i8) -> VehicleInput {
        VehicleInput {
            throttle,
            brake,
            gear,
            ..VehicleInput::NONE
        }
    }

    fn avancer(b: &mut Gearbox, v: &VehicleDesc, e: &VehicleInput, vitesse: f32, rpm: f32, s: f32) {
        for _ in 0..(s / DT).round() as u32 {
            b.update(v, e, vitesse, rpm, DT);
        }
    }

    #[test]
    fn le_rang_se_compte_depuis_le_rapport_nul() {
        let v = boite(transmission_kind::MANUAL);
        // gears = [-3.2, 0, 3.5, 2.1, 1.4, 1.0, 0.8], pont 3.7.
        assert_eq!(ratio_of(&v, 0), Some(0.0));
        assert!((ratio_of(&v, 1).unwrap() - 3.5 * 3.7).abs() < 1e-4);
        assert!((ratio_of(&v, 5).unwrap() - 0.8 * 3.7).abs() < 1e-4);
        assert!((ratio_of(&v, -1).unwrap() + 3.2 * 3.7).abs() < 1e-4);
        assert_eq!(ratio_of(&v, 6), None);
        assert_eq!(ratio_of(&v, -2), None);
        let d = boite(transmission_kind::DIRECT);
        assert_eq!(ratio_of(&d, 1), Some(3.7), "R-1080 : le pont seul");
        assert_eq!(ratio_of(&d, -1), Some(-3.7));
        assert_eq!(ratio_of(&d, 2), None);
    }

    #[test]
    fn en_manuelle_le_rapport_vient_de_l_entree_apres_shift_time() {
        let v = boite(transmission_kind::MANUAL);
        let mut b = Gearbox::new(&v);
        assert_eq!(b.gear(), 0, "au point mort");
        assert_eq!(b.ratio(&v), 0.0);
        b.update(&v, &entree(0.0, 0.0, 2), 0.0, 0.0, DT);
        assert!(b.shifting());
        assert_eq!(
            b.ratio(&v),
            0.0,
            "le couple ne passe pas pendant le changement"
        );
        assert_eq!(b.demand(&v, &entree(1.0, 0.0, 2)).throttle, 0.0);
        avancer(&mut b, &v, &entree(0.0, 0.0, 2), 0.0, 0.0, 0.3);
        assert_eq!(b.gear(), 2);
        assert!((b.ratio(&v) - 2.1 * 3.7).abs() < 1e-4);
        avancer(&mut b, &v, &entree(0.0, 0.0, 9), 0.0, 0.0, 0.5);
        assert_eq!(b.gear(), 2, "un rang hors de la table est ignoré");
    }

    #[test]
    fn en_automatique_la_boite_monte_et_descend_a_ses_regimes() {
        let v = boite(transmission_kind::AUTOMATIC);
        let mut b = Gearbox::new(&v);
        assert_eq!(b.gear(), 1);
        b.update(&v, &entree(1.0, 0.0, 0), 10.0, 6300.0, DT);
        assert!(b.shifting(), "au-delà de auto_shift_up_rpm");
        avancer(&mut b, &v, &entree(1.0, 0.0, 0), 10.0, 4000.0, 0.2);
        assert_eq!(b.gear(), 1, "pendant shift_time");
        avancer(&mut b, &v, &entree(1.0, 0.0, 0), 10.0, 4000.0, 0.1);
        assert_eq!(b.gear(), 2);
        avancer(&mut b, &v, &entree(0.0, 0.0, 0), 5.0, 4000.0, 1.0);
        assert_eq!(b.gear(), 2, "entre les deux seuils, elle garde son rapport");
        b.update(&v, &entree(0.0, 0.0, 0), 5.0, 2100.0, DT);
        avancer(&mut b, &v, &entree(0.0, 0.0, 0), 5.0, 4000.0, 0.3);
        assert_eq!(b.gear(), 1, "sous auto_shift_down_rpm");
        avancer(&mut b, &v, &entree(0.0, 0.0, 0), 1.0, 500.0, 1.0);
        assert_eq!(b.gear(), 1, "jamais sous la première");
    }

    #[test]
    fn adr159_d6_en_automatique_le_frein_tenu_a_l_arret_engage_la_marche_arriere() {
        let v = boite(transmission_kind::AUTOMATIC);
        let mut b = Gearbox::new(&v);
        avancer(&mut b, &v, &entree(0.0, 1.0, 0), 5.0, 1000.0, 1.0);
        assert_eq!(b.gear(), 1, "en roulant, le frein freine");
        avancer(&mut b, &v, &entree(0.0, 1.0, 0), 0.1, 0.0, 0.2);
        assert_eq!(b.gear(), 1, "pas encore assez tenu");
        avancer(&mut b, &v, &entree(0.0, 1.0, 0), 0.1, 0.0, 0.5);
        assert_eq!(b.gear(), -1);
        let demande = b.demand(&v, &entree(0.25, 0.75, 0));
        assert_eq!(demande.throttle, 0.75, "en arrière, le frein accélère");
        assert_eq!(demande.brake, 0.25, "et l'accélérateur freine");
        avancer(&mut b, &v, &entree(1.0, 0.0, 0), 0.1, 0.0, 0.7);
        assert_eq!(
            b.gear(),
            1,
            "l'accélérateur tenu à l'arrêt revient en marche avant"
        );
    }

    #[test]
    fn la_boite_directe_change_de_sens_sans_delai() {
        let v = boite(transmission_kind::DIRECT);
        let mut b = Gearbox::new(&v);
        assert_eq!(b.gear(), 1);
        avancer(
            &mut b,
            &v,
            &entree(0.0, 1.0, 0),
            0.0,
            0.0,
            REVERSE_HOLD + 2.0 * DT,
        );
        assert_eq!(b.gear(), -1);
        assert!(!b.shifting());
        assert!((b.ratio(&v) + 3.7).abs() < 1e-4);
    }
}
