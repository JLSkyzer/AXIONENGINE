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

/// Où en est le moteur : arrêté, lancé par le démarreur, ou tournant (ADR-176 D1).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum EnginePhase {
    /// Pas de combustion : aucun couple moteur.
    #[default]
    Stopped,
    /// Le démarreur tourne ; le moteur ne donne encore rien.
    Cranking,
    /// Le moteur tourne, au moins au ralenti.
    Running,
}

/// L'état du moteur que le monde garde d'un pas à l'autre (ADR-176 D1).
///
/// Sans démarreur (`starter_time == 0`), le moteur tourne toujours, comme avant ADR-176 : rien ne l'arrête, le contact
/// n'est pas lu. Avec un démarreur, le contact **mis** (front montant) lance le démarreur pendant `starter_time`
/// secondes, puis le moteur tourne ; le contact **coupé** l'arrête aussitôt. Un moteur arrêté par autre chose que le
/// contact (réservoir vide, ADR-176 D3) ne redémarre pas tant que le contact n'a pas été coupé puis remis.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct EngineState {
    /// La phase.
    pub phase: EnginePhase,
    /// Temps restant au démarreur, en secondes ; 0 hors de la phase du démarreur.
    pub remaining: f32,
    /// Le contact était mis au pas précédent : sert à voir le front montant.
    pub ignition: bool,
}

impl EngineState {
    /// L'état d'un moteur neuf : tournant sans démarreur, arrêté contact coupé avec un démarreur.
    #[must_use]
    pub fn new(starter_time: f32) -> Self {
        Self {
            phase: if starter_time > 0.0 {
                EnginePhase::Stopped
            } else {
                EnginePhase::Running
            },
            remaining: 0.0,
            ignition: false,
        }
    }

    /// Le moteur donne du couple.
    #[must_use]
    pub fn running(&self) -> bool {
        self.phase == EnginePhase::Running
    }

    /// Le démarreur tourne.
    #[must_use]
    pub fn cranking(&self) -> bool {
        self.phase == EnginePhase::Cranking
    }

    /// Avance l'état d'un pas de `dt` secondes, avec le contact tel qu'il est tenu pendant ce pas.
    pub fn step(&mut self, starter_time: f32, ignition: bool, dt: f32) {
        let rising = ignition && !self.ignition;
        self.ignition = ignition;
        if starter_time <= 0.0 {
            self.phase = EnginePhase::Running;
            self.remaining = 0.0;
            return;
        }
        if !ignition {
            self.phase = EnginePhase::Stopped;
            self.remaining = 0.0;
            return;
        }
        match self.phase {
            EnginePhase::Stopped if rising => {
                self.phase = EnginePhase::Cranking;
                self.remaining = starter_time;
            }
            EnginePhase::Cranking => {
                self.remaining -= dt;
                if self.remaining <= 0.0 {
                    self.phase = EnginePhase::Running;
                    self.remaining = 0.0;
                }
            }
            EnginePhase::Stopped | EnginePhase::Running => {}
        }
    }
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
            starter_time: 0.0,
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

    #[test]
    fn adr176_d1_sans_demarreur_le_moteur_tourne_toujours_et_ignore_le_contact() {
        let mut etat = EngineState::new(0.0);
        assert!(etat.running());
        for contact in [false, true, false] {
            etat.step(0.0, contact, 0.05);
            assert!(etat.running(), "le contact est sans effet sans démarreur");
        }
    }

    #[test]
    fn adr176_d1_le_contact_lance_le_demarreur_puis_le_moteur_tourne() {
        // 0,125 s : exact en binaire, le temps restant tombe juste à zéro.
        let mut etat = EngineState::new(0.5);
        assert_eq!(etat.phase, EnginePhase::Stopped);
        etat.step(0.5, false, 0.125);
        assert_eq!(etat.phase, EnginePhase::Stopped, "contact coupé : rien");
        etat.step(0.5, true, 0.125);
        assert!(etat.cranking(), "front montant : le démarreur tourne");
        for _ in 0..3 {
            etat.step(0.5, true, 0.125);
            assert!(etat.cranking());
        }
        etat.step(0.5, true, 0.125);
        assert!(etat.running(), "après `starter_time` secondes");
        etat.step(0.5, true, 0.125);
        assert!(etat.running(), "il tourne tant que le contact est mis");
    }

    #[test]
    fn adr176_d1_couper_le_contact_arrete_le_moteur_et_le_demarreur() {
        let mut etat = EngineState::new(0.5);
        etat.step(0.5, true, 0.1);
        etat.step(0.5, false, 0.1);
        assert_eq!(etat.phase, EnginePhase::Stopped);
        assert_eq!(etat.remaining, 0.0);
        let mut etat = EngineState::new(0.2);
        etat.step(0.2, true, 0.1);
        etat.step(0.2, true, 0.1);
        etat.step(0.2, true, 0.1);
        assert!(etat.running());
        etat.step(0.2, false, 0.1);
        assert_eq!(etat.phase, EnginePhase::Stopped);
        etat.step(0.2, true, 0.1);
        assert!(etat.cranking(), "remis, il redémarre");
    }

    #[test]
    fn adr176_d1_un_moteur_arrete_contact_mis_ne_redemarre_pas_sans_front() {
        // Le contact est déjà mis quand l'état est relu arrêté (réservoir vide, ADR-176 D3) : pas de front, pas de démarreur.
        let mut etat = EngineState {
            phase: EnginePhase::Stopped,
            remaining: 0.0,
            ignition: true,
        };
        etat.step(0.5, true, 0.1);
        assert_eq!(etat.phase, EnginePhase::Stopped);
    }
}
