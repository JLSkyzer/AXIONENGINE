//! La direction (12.6, ADR-159 D7).
//!
//! Repère du véhicule (§6.5) : -Z avant, +X droite. Un braquage positif tourne à droite.

use ax_model::dm::vehicle::SteeringDesc;

/// Vitesse de référence de `speed_sensitivity`, en m/s : à partir d'elle, la réduction de l'angle est
/// entière. Choix d'ADR-159 D7.
pub const SPEED_REFERENCE: f32 = 30.0;

/// La position du volant, en fraction du braquage maximal, dans [-1, 1].
#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct Steering {
    position: f32,
}

impl Steering {
    /// La position, dans [-1, 1], positive à droite.
    #[must_use]
    pub fn position(&self) -> f32 {
        self.position
    }

    /// Rapproche la position de `target` : à `speed` fraction par seconde en s'en éloignant du centre,
    /// à `return_speed` en y revenant (D7).
    pub fn update(&mut self, desc: &SteeringDesc, target: f32, dt: f32) {
        let target = if target.is_finite() {
            target.clamp(-1.0, 1.0)
        } else {
            0.0
        };
        let returning = target.abs() < self.position.abs() || target * self.position < 0.0;
        let rate = if returning {
            desc.return_speed
        } else {
            desc.speed
        };
        let step = (rate * dt).max(0.0);
        self.position += (target - self.position).clamp(-step, step);
    }
}

/// L'angle maximal à la vitesse `speed` (m/s) : `max_angle · (1 − s · min(1, |v| / 30))` (D7).
#[must_use]
pub fn max_angle(desc: &SteeringDesc, speed: f32) -> f32 {
    let reduction = desc.speed_sensitivity * (speed.abs() / SPEED_REFERENCE).min(1.0);
    desc.max_angle * (1.0 - reduction).max(0.0)
}

/// La géométrie de direction, tirée des positions déclarées des roues (D7).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Geometry {
    /// La ligne de pivot, en Z : la moyenne des roues qui ne braquent pas, ou de toutes si toutes
    /// braquent.
    pub pivot_z: f32,
    /// Le milieu de la voie, en X.
    pub center_x: f32,
    /// L'empattement : la distance moyenne des roues braquées à la ligne de pivot.
    pub wheelbase: f32,
}

impl Geometry {
    /// La géométrie de roues placées en `(x, z)`, dans le repère du véhicule ; `steered` dit
    /// lesquelles braquent.
    #[must_use]
    pub fn new(positions: &[[f32; 2]], steered: &[bool]) -> Self {
        let mean = |pick: &dyn Fn(usize) -> bool, axis: usize| {
            let (sum, count) = positions
                .iter()
                .enumerate()
                .filter(|(i, _)| pick(*i))
                .fold((0.0, 0.0), |(s, n), (_, p)| (s + p[axis], n + 1.0));
            (count > 0.0).then(|| sum / count)
        };
        let fixed = |i: usize| !steered[i];
        let all = |_: usize| true;
        let pivot_z = mean(&fixed, 1).or_else(|| mean(&all, 1)).unwrap_or(0.0);
        let center_x = mean(&all, 0).unwrap_or(0.0);
        let turned = |i: usize| steered[i];
        let (sum, count) = positions
            .iter()
            .enumerate()
            .filter(|(i, _)| turned(*i))
            .fold((0.0, 0.0), |(s, n), (_, p)| {
                (s + (pivot_z - p[1]).abs(), n + 1.0)
            });
        Self {
            pivot_z,
            center_x,
            wheelbase: if count > 0.0 { sum / count } else { 0.0 },
        }
    }

    /// L'angle d'une roue braquée en `(x, z)` quand le braquage de référence vaut `angle` : entre le
    /// braquage parallèle (`ackermann` 0) et la géométrie d'Ackermann (1), où chaque roue vise le
    /// centre du virage, sur la ligne de pivot. Une roue derrière le pivot braque à l'opposé.
    #[must_use]
    pub fn wheel_angle(&self, ackermann: f32, angle: f32, x: f32, z: f32) -> f32 {
        let lever = self.pivot_z - z;
        if angle == 0.0 || lever == 0.0 || self.wheelbase <= 0.0 {
            return 0.0;
        }
        let parallel = angle * lever.signum();
        let side = angle.signum();
        let radius = self.wheelbase / angle.abs().tan();
        let offset = radius - side * (x - self.center_x);
        let exact = (side * lever.atan2(offset))
            .clamp(-std::f32::consts::FRAC_PI_2, std::f32::consts::FRAC_PI_2);
        let a = ackermann.clamp(0.0, 1.0);
        parallel + (exact - parallel) * a
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn direction() -> SteeringDesc {
        SteeringDesc {
            max_angle: 0.6,
            speed: 3.0,
            return_speed: 4.0,
            speed_sensitivity: 0.5,
            ackermann: 1.0,
        }
    }

    /// Quatre roues, avant braquées, empattement 2,8, voie 1,6.
    fn geometrie() -> Geometry {
        Geometry::new(
            &[[-0.8, -1.4], [0.8, -1.4], [-0.8, 1.4], [0.8, 1.4]],
            &[true, true, false, false],
        )
    }

    #[test]
    fn le_volant_va_a_speed_et_revient_a_return_speed() {
        let d = direction();
        let mut s = Steering::default();
        s.update(&d, 1.0, 0.1);
        assert!((s.position() - 0.3).abs() < 1e-5);
        s.update(&d, 1.0, 1.0);
        assert!((s.position() - 1.0).abs() < 1e-5, "borné à la cible");
        s.update(&d, 0.0, 0.1);
        assert!((s.position() - 0.6).abs() < 1e-5, "retour plus rapide");
        s.update(&d, f32::NAN, 1.0);
        assert_eq!(s.position(), 0.0);
    }

    #[test]
    fn adr159_d7_l_angle_maximal_diminue_avec_la_vitesse() {
        let d = direction();
        assert!((max_angle(&d, 0.0) - 0.6).abs() < 1e-6);
        assert!((max_angle(&d, 15.0) - 0.6 * 0.75).abs() < 1e-6);
        assert!((max_angle(&d, -30.0) - 0.3).abs() < 1e-6);
        assert!((max_angle(&d, 90.0) - 0.3).abs() < 1e-6, "borné à |v| = 30");
    }

    #[test]
    fn la_geometrie_vient_des_positions_des_roues() {
        let g = geometrie();
        assert!((g.pivot_z - 1.4).abs() < 1e-6);
        assert!(g.center_x.abs() < 1e-6);
        assert!((g.wheelbase - 2.8).abs() < 1e-6);
    }

    #[test]
    fn adr159_d7_ackermann_vise_le_centre_du_virage_et_zero_reste_parallele() {
        let g = geometrie();
        let a = 0.3;
        // À droite : la roue intérieure est la droite (+X), qui braque plus.
        let droite = g.wheel_angle(1.0, a, 0.8, -1.4);
        let gauche = g.wheel_angle(1.0, a, -0.8, -1.4);
        assert!(
            droite > a && gauche < a && gauche > 0.0,
            "{gauche} {droite}"
        );
        let rayon = 2.8 / a.tan();
        assert!((droite - (2.8f32 / (rayon - 0.8)).atan()).abs() < 1e-5);
        assert!((gauche - (2.8f32 / (rayon + 0.8)).atan()).abs() < 1e-5);
        assert!(
            (g.wheel_angle(0.0, a, 0.8, -1.4) - a).abs() < 1e-6,
            "parallèle"
        );
        let moitie = g.wheel_angle(0.5, a, 0.8, -1.4);
        assert!((moitie - (a + droite) / 2.0).abs() < 1e-6);
        // À gauche, symétrique.
        assert!((g.wheel_angle(1.0, -a, -0.8, -1.4) + droite).abs() < 1e-6);
        assert_eq!(g.wheel_angle(1.0, 0.0, 0.8, -1.4), 0.0);
    }

    #[test]
    fn une_roue_derriere_le_pivot_braque_a_l_oppose() {
        // Un chariot : les roues arrière braquent, le pivot est à l'avant.
        let g = Geometry::new(
            &[[-0.5, -1.0], [0.5, -1.0], [0.0, 1.0]],
            &[false, false, true],
        );
        assert!((g.wheelbase - 2.0).abs() < 1e-6);
        assert!(g.wheel_angle(1.0, 0.4, 0.0, 1.0) < 0.0);
        assert!(g.wheel_angle(0.0, 0.4, 0.0, 1.0) < 0.0);
    }

    #[test]
    fn sans_roue_braquee_rien_ne_braque() {
        let g = Geometry::new(&[[-0.5, -1.0], [0.5, 1.0]], &[false, false]);
        assert_eq!(g.wheelbase, 0.0);
        assert_eq!(g.wheel_angle(1.0, 0.4, 0.5, -1.0), 0.0);
    }
}
