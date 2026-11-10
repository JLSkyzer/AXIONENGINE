//! Les différentiels (R-1081, ADR-159 D6).
//!
//! Le couple de l'arbre se partage entre les roues motrices : à parts égales, ou, avec un
//! différentiel central (`front_rear_split` non nul et les deux essieux entraînés), selon la part de
//! l'avant. Un différentiel `locked` ou `limited_slip` couple en plus les vitesses des roues motrices
//! d'un même essieu, en conservant leur somme : une roue en l'air ne prend plus tout le couple.

use ax_model::dm::scene::NONE_U16;
use ax_model::dm::vehicle::{differential_kind, DifferentialDesc, WheelDesc};

/// Groupe de couplage des roues motrices avant sans essieu déclaré.
pub const FRONT_GROUP: u16 = NONE_U16 - 1;
/// Groupe de couplage des roues motrices arrière sans essieu déclaré.
pub const REAR_GROUP: u16 = NONE_U16 - 2;

/// Écrit dans `out` la part du couple de l'arbre de chaque roue : nulle pour une roue non motrice, et
/// de somme 1 dès qu'une roue est motrice.
pub fn shares(desc: &DifferentialDesc, driven: &[bool], front: &[bool], out: &mut [f32]) {
    let count = |want_front: Option<bool>| {
        driven
            .iter()
            .zip(front)
            .filter(|(d, f)| **d && want_front.is_none_or(|w| **f == w))
            .count() as f32
    };
    let (all, fronts, rears) = (count(None), count(Some(true)), count(Some(false)));
    let central = desc.front_rear_split > 0.0 && fronts > 0.0 && rears > 0.0;
    let split = desc.front_rear_split.clamp(0.0, 1.0);
    for (i, share) in out.iter_mut().enumerate() {
        *share = if !driven[i] {
            0.0
        } else if central && front[i] {
            split / fronts
        } else if central {
            (1.0 - split) / rears
        } else {
            1.0 / all
        };
    }
}

/// Écrit dans `out` le groupe de couplage de chaque roue motrice : son essieu déclaré, sinon l'avant
/// ou l'arrière ; [`NONE_U16`] pour une roue non motrice.
pub fn groups(wheels: &[WheelDesc], driven: &[bool], front: &[bool], out: &mut [u16]) {
    for (i, group) in out.iter_mut().enumerate() {
        *group = if !driven[i] {
            NONE_U16
        } else if wheels[i].axle != NONE_U16 {
            wheels[i].axle
        } else if front[i] {
            FRONT_GROUP
        } else {
            REAR_GROUP
        };
    }
}

/// La part de l'écart à la moyenne de son groupe que chaque roue rattrape à chaque micro-pas : 1 pour
/// `locked`, `lock / micro_steps` pour `limited_slip` (un sous-pas rattrape alors environ la part
/// `lock` de l'écart, pour `lock` petit), 0 pour `open`.
#[must_use]
pub fn coupling(desc: &DifferentialDesc, micro_steps: u32) -> f32 {
    match desc.kind {
        differential_kind::LOCKED => 1.0,
        differential_kind::LIMITED_SLIP => desc.lock.clamp(0.0, 1.0) / micro_steps.max(1) as f32,
        _ => 0.0,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tests_support::vehicule;

    fn diff(split: f32) -> DifferentialDesc {
        DifferentialDesc {
            kind: differential_kind::OPEN,
            front_rear_split: split,
            lock: 0.0,
        }
    }

    #[test]
    fn r1081_sans_differentiel_central_les_parts_sont_egales() {
        let mut parts = [0.0; 4];
        shares(
            &diff(0.0),
            &[false, false, true, true],
            &[true, true, false, false],
            &mut parts,
        );
        assert_eq!(parts, [0.0, 0.0, 0.5, 0.5]);
        shares(
            &diff(0.0),
            &[true, true, true, true],
            &[true, true, false, false],
            &mut parts,
        );
        assert_eq!(parts, [0.25; 4], "intégrale sans différentiel central");
        shares(
            &diff(0.4),
            &[false, false, true, true],
            &[true, true, false, false],
            &mut parts,
        );
        assert_eq!(parts, [0.0, 0.0, 0.5, 0.5], "un seul essieu entraîné");
    }

    #[test]
    fn r1081_le_differentiel_central_repartit_entre_avant_et_arriere() {
        let mut parts = [0.0; 6];
        shares(
            &diff(0.4),
            &[true, true, true, true, true, true],
            &[true, true, false, false, false, false],
            &mut parts,
        );
        assert!((parts[0] - 0.2).abs() < 1e-6 && (parts[5] - 0.15).abs() < 1e-6);
        assert!((parts.iter().sum::<f32>() - 1.0).abs() < 1e-6);
    }

    #[test]
    fn le_groupe_est_l_essieu_declare_sinon_l_avant_ou_l_arriere() {
        let mut v = vehicule();
        v.wheels[0].axle = 3;
        let mut g = [0u16; 4];
        groups(
            &v.wheels,
            &[true, true, true, false],
            &[true, true, false, false],
            &mut g,
        );
        assert_eq!(g, [3, FRONT_GROUP, REAR_GROUP, NONE_U16]);
    }

    #[test]
    fn le_couplage_est_total_bloque_partiel_a_glissement_limite_nul_libre() {
        let mut d = diff(0.0);
        assert_eq!(coupling(&d, 12), 0.0);
        d.kind = differential_kind::LOCKED;
        assert_eq!(coupling(&d, 12), 1.0);
        d.kind = differential_kind::LIMITED_SLIP;
        d.lock = 0.6;
        assert!((coupling(&d, 12) - 0.05).abs() < 1e-6);
    }
}
