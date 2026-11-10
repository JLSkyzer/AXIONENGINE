//! Les freins et les correcteurs déclaratifs (12.6, R-1090, ADR-159 D7).
//!
//! L'ABS, l'antipatinage et le contrôle de stabilité corrigent des couples : ils relâchent un frein,
//! réduisent celui du moteur ou freinent une roue. Aucun ne connaît le véhicule qu'il corrige.

use ax_model::dm::vehicle::BrakesDesc;

/// Vitesse sous laquelle aucun correcteur n'agit, en m/s : près de l'arrêt, un glissement rapporté à
/// la vitesse du sol n'a plus de sens, et l'ABS empêcherait l'arrêt. Choix d'ADR-159 (tranche 3).
pub const ASSIST_MIN_SPEED: f32 = 1.0;

/// Le couple de la pédale de frein sur une roue, en N·m : `max_torque_front` devant le centre de
/// masse, `max_torque_rear` derrière.
#[must_use]
pub fn pedal_torque(brakes: &BrakesDesc, front: bool, brake: f32) -> f32 {
    let max = if front {
        brakes.max_torque_front
    } else {
        brakes.max_torque_rear
    };
    max * brake.clamp(0.0, 1.0)
}

/// Le couple du frein à main sur une roue qu'il serre, en N·m.
#[must_use]
pub fn handbrake_torque(brakes: &BrakesDesc, handbrake: f32) -> f32 {
    brakes.handbrake_torque * handbrake.clamp(0.0, 1.0)
}

/// Le couple de résistance au roulement, en N·m : le coefficient du matériau touché, la charge et le
/// rayon.
#[must_use]
pub fn rolling_torque(rolling_friction: f32, load: f32, radius: f32) -> f32 {
    rolling_friction.max(0.0) * load.max(0.0) * radius
}

/// ABS : vrai si le frein d'une roue doit être relâché, parce qu'elle tourne moins vite que le sol ne
/// défile au-delà de `threshold`.
#[must_use]
pub fn abs_releases(threshold: f32, wheel_speed: f32, ground_speed: f32) -> bool {
    ground_speed.abs() > ASSIST_MIN_SPEED
        && crate::tire::slip_long(wheel_speed, ground_speed) * crate::sign(ground_speed)
            < -threshold
}

/// Le patinage d'une roue motrice, positif quand elle tourne plus vite que le sol dans son sens de
/// rotation. Rapporté à la vitesse du sol, sans descendre sous [`ASSIST_MIN_SPEED`] : au démarrage,
/// une roue qui patine se mesure encore.
#[must_use]
pub fn traction_slip(wheel_speed: f32, ground_speed: f32) -> f32 {
    (wheel_speed - ground_speed) * crate::sign(wheel_speed)
        / ground_speed.abs().max(ASSIST_MIN_SPEED)
}

/// Antipatinage : le facteur du couple moteur, 1 jusqu'au seuil, nul au double du seuil.
#[must_use]
pub fn traction_scale(threshold: f32, worst_slip: f32) -> f32 {
    (1.0 - (worst_slip - threshold) / threshold).clamp(0.0, 1.0)
}

/// Le lacet que vise le modèle bicyclette, en rad/s autour de +Y : `v · tan δ / L`, de signe opposé
/// au braquage en marche avant, borné à ce que l'adhérence permet, `µ · g / |v|`.
#[must_use]
pub fn target_yaw_rate(
    forward_speed: f32,
    angle: f32,
    wheelbase: f32,
    grip: f32,
    gravity: f32,
) -> f32 {
    if wheelbase <= 0.0 {
        return 0.0;
    }
    let geometric = -forward_speed * angle.tan() / wheelbase;
    let limit = grip.max(0.0) * gravity / forward_speed.abs().max(ASSIST_MIN_SPEED);
    geometric.clamp(-limit, limit)
}

/// Ce que le contrôle de stabilité demande.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct StabilityCorrection {
    /// Le côté de la roue à freiner : `-1` à gauche (-X), `1` à droite.
    pub side: f32,
    /// Vrai pour freiner à l'avant (survirage), faux à l'arrière (sous-virage).
    pub front: bool,
    /// La part du couple de frein maximal de la roue, dans ]0, 1].
    pub strength: f32,
}

/// Contrôle de stabilité : la roue à freiner quand le lacet `yaw_rate` s'écarte de `target` de plus de
/// `tolerance`, en marche avant. Un frein à droite fait tourner à droite (lacet négatif) : l'écart
/// `target − yaw_rate` choisit le côté. Survirage, lacet plus fort que visé : l'avant ; sous-virage :
/// l'arrière. La correction croît de 0 au seuil à tout le couple au double du seuil.
#[must_use]
pub fn stability_correction(
    tolerance: f32,
    forward_speed: f32,
    yaw_rate: f32,
    target: f32,
) -> Option<StabilityCorrection> {
    let error = target - yaw_rate;
    if forward_speed <= ASSIST_MIN_SPEED || error.abs() <= tolerance {
        return None;
    }
    Some(StabilityCorrection {
        side: -crate::sign(error),
        front: yaw_rate.abs() > target.abs(),
        strength: ((error.abs() - tolerance) / tolerance).min(1.0),
    })
}

/// La roue qui porte une correction : au sol, du côté demandé (`lateral` est la position de chaque
/// roue en X, relative au milieu de la voie), sur l'essieu demandé s'il en a une, la plus écartée.
#[must_use]
pub fn stability_wheel(
    correction: &StabilityCorrection,
    lateral: &[f32],
    front: &[bool],
    grounded: &[bool],
) -> Option<usize> {
    let pick = |same_axle: bool| {
        (0..lateral.len())
            .filter(|&i| grounded[i] && lateral[i] * correction.side > 0.0)
            .filter(|&i| !same_axle || front[i] == correction.front)
            .fold(None, |best: Option<usize>, i| match best {
                Some(b) if lateral[b].abs() >= lateral[i].abs() => Some(b),
                _ => Some(i),
            })
    };
    pick(true).or_else(|| pick(false))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn freins() -> BrakesDesc {
        BrakesDesc {
            max_torque_front: 3000.0,
            max_torque_rear: 1800.0,
            handbrake_torque: 4000.0,
            abs_slip_threshold: 0.15,
        }
    }

    #[test]
    fn la_pedale_choisit_son_couple_par_la_position_de_la_roue() {
        let f = freins();
        assert_eq!(pedal_torque(&f, true, 0.5), 1500.0);
        assert_eq!(pedal_torque(&f, false, 1.0), 1800.0);
        assert_eq!(pedal_torque(&f, false, 3.0), 1800.0, "borné");
        assert_eq!(handbrake_torque(&f, 1.0), 4000.0);
        assert!((rolling_torque(0.015, 4000.0, 0.35) - 21.0).abs() < 1e-3);
        assert_eq!(rolling_torque(-1.0, 4000.0, 0.35), 0.0);
    }

    #[test]
    fn r1090_l_abs_relache_une_roue_bloquee_et_jamais_pres_de_l_arret() {
        assert!(abs_releases(0.15, 0.0, 10.0), "roue bloquée");
        assert!(!abs_releases(0.15, 9.0, 10.0), "glissement de 10 %");
        assert!(abs_releases(0.15, 0.0, -10.0), "en marche arrière aussi");
        assert!(!abs_releases(0.15, 0.0, 0.5), "près de l'arrêt");
    }

    #[test]
    fn r1090_l_antipatinage_reduit_le_couple_au_dela_du_seuil() {
        assert!((traction_slip(12.0, 10.0) - 0.2).abs() < 1e-6);
        assert!(
            (traction_slip(-12.0, -10.0) - 0.2).abs() < 1e-6,
            "en arrière"
        );
        assert!((traction_slip(0.5, 0.0) - 0.5).abs() < 1e-6, "au démarrage");
        assert_eq!(traction_scale(0.2, 0.1), 1.0);
        assert!((traction_scale(0.2, 0.3) - 0.5).abs() < 1e-6);
        assert_eq!(traction_scale(0.2, 0.5), 0.0);
    }

    #[test]
    fn r1090_le_lacet_vise_suit_le_braquage_et_l_adherence() {
        // À droite en marche avant : rotation horaire vue de dessus, lacet négatif.
        let vise = target_yaw_rate(10.0, 0.1, 2.8, 1.0, 9.81);
        assert!((vise + 10.0 * 0.1f32.tan() / 2.8).abs() < 1e-5);
        let borne = target_yaw_rate(20.0, 0.5, 2.8, 0.1, 9.81);
        assert!((borne + 0.1 * 9.81 / 20.0).abs() < 1e-5, "sur la glace");
        assert_eq!(target_yaw_rate(10.0, 0.1, 0.0, 1.0, 9.81), 0.0);
    }

    #[test]
    fn r1090_la_stabilite_freine_l_avant_exterieur_en_survirage_et_l_arriere_interieur_en_sous_virage(
    ) {
        let x = [-0.8, 0.8, -0.8, 0.8];
        let avant = [true, true, false, false];
        let sol = [true; 4];
        // Virage à droite visé à -0,3 rad/s ; il tourne à -0,8 : survirage, freiner l'avant gauche.
        let c = stability_correction(0.1, 10.0, -0.8, -0.3).expect("correction");
        assert!(c.front && c.side < 0.0 && c.strength == 1.0, "{c:?}");
        assert_eq!(stability_wheel(&c, &x, &avant, &sol), Some(0));
        // Il ne tourne qu'à -0,1 : sous-virage, freiner l'arrière droit.
        let c = stability_correction(0.1, 10.0, -0.1, -0.3).expect("correction");
        assert!(
            !c.front && c.side > 0.0 && (c.strength - 1.0).abs() < 1e-5,
            "{c:?}"
        );
        assert_eq!(stability_wheel(&c, &x, &avant, &sol), Some(3));
        // Roue en l'air : l'autre essieu du même côté.
        assert_eq!(
            stability_wheel(&c, &x, &avant, &[true, true, true, false]),
            Some(1)
        );
        assert_eq!(stability_correction(0.1, 10.0, -0.35, -0.3), None, "toléré");
        assert_eq!(stability_correction(0.1, 0.5, -2.0, 0.0), None, "à l'arrêt");
    }
}
