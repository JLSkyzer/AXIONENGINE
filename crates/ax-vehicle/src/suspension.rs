//! La suspension d'une roue par rayon (12.2, 12.3, ADR-159 D3).

use ax_model::dm::vehicle::SuspensionDesc;

/// Longueur du rayon de la roue, depuis son ancrage (12.2, R-1050 : le rayon de la roue compris).
#[must_use]
pub fn ray_length(desc: &SuspensionDesc, radius: f32) -> f32 {
    desc.rest_length + desc.max_travel + radius
}

/// La compression à la distance `distance` de l'ancrage : `0` au repos, `1` en butée, négative
/// quand la roue descend sous sa longueur de repos.
#[must_use]
pub fn compression(desc: &SuspensionDesc, radius: f32, distance: f32) -> f32 {
    (desc.rest_length + radius - distance) / desc.max_travel
}

/// La vitesse de compression, depuis la vitesse à laquelle le point de contact se rapproche de
/// l'ancrage (ADR-159 D3).
///
/// Sur un sol plat, c'est `d(compression)/dt`. Au bord d'un bloc, la distance saute d'un sous-pas à
/// l'autre : une différence finie y vaudrait un pic, qui porterait l'amortisseur à `max_force`.
#[must_use]
pub fn compression_rate(desc: &SuspensionDesc, approach_speed: f32) -> f32 {
    approach_speed / desc.max_travel
}

/// La force de suspension, en newtons (12.2).
///
/// `mass_ref` multiplie ressort **et** amortisseur (R-1060). La compression est bornée à 1 pour
/// le ressort : au-delà, la butée est le collider du châssis.
#[must_use]
pub fn force(desc: &SuspensionDesc, mass_ref: f32, compression: f32, rate: f32) -> f32 {
    let spring = desc.stiffness * compression.min(1.0) * mass_ref;
    let damping = if rate >= 0.0 {
        desc.damping_compression
    } else {
        desc.damping_rebound
    };
    (spring + damping * rate * mass_ref).clamp(0.0, desc.max_force)
}

#[cfg(test)]
mod tests {
    use super::*;

    const GRAVITE: f32 = 9.81;
    const RAYON: f32 = 0.36;

    fn exemple() -> SuspensionDesc {
        SuspensionDesc {
            rest_length: 0.35,
            max_travel: 0.25,
            stiffness: 30.0,
            damping_compression: 2.3,
            damping_rebound: 2.8,
            max_force: 60_000.0,
            anti_roll: 0.0,
        }
    }

    #[test]
    fn r1050_le_rayon_comprend_le_rayon_de_la_roue() {
        assert!((ray_length(&exemple(), RAYON) - 0.96).abs() < 1e-5);
    }

    #[test]
    fn la_compression_vaut_zero_au_repos_et_un_en_butee() {
        let s = exemple();
        assert!(compression(&s, RAYON, 0.71).abs() < 1e-5);
        assert!((compression(&s, RAYON, 0.46) - 1.0).abs() < 1e-5);
        assert!(compression(&s, RAYON, 0.96) < 0.0);
    }

    #[test]
    fn r1060_l_equilibre_ne_depend_pas_de_la_masse() {
        let s = exemple();
        let c = GRAVITE / s.stiffness;
        for masse in [75.0, 300.0, 4000.0] {
            let ecart = force(&s, masse, c, 0.0) / (masse * GRAVITE) - 1.0;
            assert!(ecart.abs() < 1e-5, "masse {masse}");
        }
    }

    #[test]
    fn r1060_la_masse_multiplie_aussi_l_amortisseur() {
        let s = exemple();
        assert!((force(&s, 300.0, 0.0, 1.0) - 2.3 * 300.0).abs() < 1e-3);
        assert!((force(&s, 600.0, 0.0, 1.0) - 2.3 * 600.0).abs() < 1e-3);
    }

    #[test]
    fn compression_et_detente_ont_chacune_leur_amortissement() {
        let s = exemple();
        assert!((force(&s, 100.0, 0.5, -1.0) - (1500.0 - 280.0)).abs() < 1e-2);
        assert!((force(&s, 100.0, 0.5, 1.0) - (1500.0 + 230.0)).abs() < 1e-2);
    }

    #[test]
    fn le_ressort_est_en_butee_au_dela_de_un() {
        let s = exemple();
        assert!((force(&s, 100.0, 1.0, 0.0) - force(&s, 100.0, 3.0, 0.0)).abs() < 1e-3);
    }

    #[test]
    fn la_force_ne_tire_jamais_et_reste_sous_son_plafond() {
        let s = exemple();
        assert!(force(&s, 100.0, -1.0, 0.0).abs() < 1e-6, "roue pendante");
        assert!(
            force(&s, 100.0, 0.1, -50.0).abs() < 1e-6,
            "détente violente"
        );
        assert!(
            (force(&s, 1e6, 1.0, 5.0) - s.max_force).abs() < 1e-3,
            "plafond"
        );
    }

    #[test]
    fn le_bord_d_un_bloc_ne_fait_pas_de_pic() {
        // La roue passe sur une marche : la distance saute, la vitesse du châssis non.
        let s = exemple();
        let lente = compression_rate(&s, 0.1);
        let sans_pic = force(&s, 300.0, 1.0, lente);
        assert!(sans_pic < 300.0 * (30.0 + 2.3 * lente) + 1.0);
        assert!(sans_pic < s.max_force);
    }
}
