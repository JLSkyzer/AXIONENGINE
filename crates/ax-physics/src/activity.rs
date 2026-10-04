//! Gestion d'activité autour des observateurs (R-612, R-613, ADR-123 §3).
//!
//! Le monde relève ses corps sujets à la gestion — éveillés, ou endormis par elle —
//! avec leur distance au plus proche observateur ; [`plan`] décide, sur l'ensemble des
//! dimensions, qui s'endort et qui se réveille ; le monde applique. La décision est une
//! fonction pure des relevés : même scène, mêmes décisions (R-1020).
//!
//! - **R-612** : un corps éveillé au-delà du rayon de tout observateur s'endort ; sans
//!   observateur, tous. Jamais de suppression.
//! - **R-613** : le plafond `budgets.max_active_bodies` est un budget **serveur**, compté
//!   sur toutes les dimensions. Parmi les corps à portée — éveillés, ou endormis par la
//!   gestion —, restent ou deviennent actifs les plus proches d'un observateur, puis les
//!   plus jeunes : au-delà du plafond s'endorment les plus éloignés, puis les plus anciens.
//! - Un corps endormi par la gestion se réveille quand il revient à portée et qu'il a sa
//!   place sous le plafond. Il retrouve alors la vitesse qu'il avait : le sommeil forcé
//!   suspend la simulation d'un corps, il ne le freine pas (R-1890, « elle diffère »).

use crate::body::BodyId;
use ax_math::Vec3;
use core::cmp::Ordering;

/// Avance qu'un corps éveillé garde sur un corps endormi par la gestion, en blocs : pour
/// prendre sa place sous le plafond, le second doit être plus proche d'un observateur d'au
/// moins une section 16³. Sans cette marge, deux corps à distances voisines échangeraient
/// leur place à chaque tick au gré d'un frémissement.
const CAP_SWAP_MARGIN: f32 = 16.0;

/// Rayon et plafond appliqués à un tick, avec leurs valeurs nominales (FM-21 les réduit).
#[derive(Debug, Clone, Copy, PartialEq)]
pub(crate) struct ActivityLimits {
    /// Rayon de simulation appliqué, en blocs (R-612).
    pub radius: f32,
    /// Rayon configuré, `sim.simulation_radius` ; au moins égal au rayon appliqué.
    pub nominal_radius: f32,
    /// Plafond de corps actifs appliqué (R-613).
    pub cap: usize,
    /// Plafond configuré, `budgets.max_active_bodies` ; au moins égal au plafond appliqué.
    pub nominal_cap: usize,
}

/// Ce que la gestion d'activité a fait à un tick, en comptes : la journalisation de R-613.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct ActivityReport {
    /// Corps endormis hors du rayon de tout observateur (R-612).
    pub slept_by_radius: usize,
    /// Corps endormis au-delà du plafond de corps actifs (R-613).
    pub slept_by_cap: usize,
    /// Parmi les corps endormis, ceux que seule la dégradation a endormis (FM-21) : ils
    /// ne l'auraient pas été aux rayon et plafond configurés. Chacun émet un `CLAMPED`.
    pub slept_by_budget: usize,
    /// Corps endormis par la gestion et réveillés : revenus à portée, avec une place.
    pub woken: usize,
}

/// Observateurs d'une dimension en coordonnées locales, triés sur x : la recherche du plus
/// proche ne mesure que la tranche `[x − portée, x + portée]` autour d'un corps.
pub(crate) struct Observers {
    points: Vec<Vec3>,
    reach: f32,
}

impl Observers {
    /// Indexe des observateurs ; `reach` borne la recherche (le rayon configuré, le plus
    /// grand des rayons appliqués). Les positions non finies sont écartées.
    pub(crate) fn new(points: impl IntoIterator<Item = Vec3>, reach: f32) -> Self {
        let mut points: Vec<Vec3> = points.into_iter().filter(|p| p.is_finite()).collect();
        points.sort_by(|left, right| left.x.total_cmp(&right.x));
        Self { points, reach }
    }

    /// Distance au plus proche observateur s'il en est un à portée, sinon `None`.
    pub(crate) fn nearest(&self, at: Vec3) -> Option<f32> {
        let reach_squared = self.reach * self.reach;
        let start = self.points.partition_point(|p| p.x < at.x - self.reach);
        let mut nearest: Option<f32> = None;
        for point in &self.points[start..] {
            if point.x > at.x + self.reach {
                break;
            }
            let squared = (*point - at).length_squared();
            if squared <= reach_squared && nearest.is_none_or(|best| squared < best) {
                nearest = Some(squared);
            }
        }
        nearest.map(f32::sqrt)
    }
}

/// Un corps dynamique sujet à la gestion d'activité, tel que son monde l'a relevé.
#[derive(Debug, Clone, Copy, PartialEq)]
pub(crate) struct Candidate {
    /// Dimension de son monde.
    pub dimension: u64,
    /// Le corps.
    pub body: BodyId,
    /// Distance au plus proche observateur, en blocs ; `None` au-delà du rayon configuré.
    pub distance: Option<f32>,
    /// Rang de création dans son monde : le plus petit est le plus ancien.
    pub birth: u64,
    /// Éveillé (vrai), ou endormi par la gestion d'activité (faux).
    pub awake: bool,
}

/// Pourquoi un corps s'endort.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum SleepCause {
    /// Hors du rayon de tout observateur (R-612).
    Radius,
    /// Au-delà du plafond de corps actifs (R-613).
    Cap,
}

/// Décision prise pour un corps.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Decision {
    /// L'endormir ; `budget` s'il ne l'aurait pas été aux valeurs configurées (FM-21).
    Sleep { cause: SleepCause, budget: bool },
    /// Le réveiller, en lui rendant sa vitesse.
    Wake,
}

/// Décide, pour l'ensemble des corps relevés, qui s'endort et qui se réveille.
///
/// Rend des paires `(indice dans candidates, décision)` dans l'ordre des indices — celui
/// des relevés : dimensions croissantes, puis ordre d'itération du monde. Un corps reçoit
/// au plus une décision ; un corps absent du résultat garde son état.
pub(crate) fn plan(candidates: &[Candidate], limits: ActivityLimits) -> Vec<(usize, Decision)> {
    let mut decisions = Vec::new();
    // R-612 : hors du rayon, un corps éveillé s'endort ; un corps endormi y reste.
    let mut contenders = Vec::new();
    for (index, candidate) in candidates.iter().enumerate() {
        if candidate.distance.is_some_and(|d| d <= limits.radius) {
            contenders.push(index);
        } else if candidate.awake {
            let budget = candidate
                .distance
                .is_some_and(|d| d <= limits.nominal_radius);
            let cause = SleepCause::Radius;
            decisions.push((index, Decision::Sleep { cause, budget }));
        }
    }
    // R-613 : à portée, les `cap` premiers dans l'ordre de maintien sont actifs.
    contenders.sort_by(|&left, &right| keep_order(&candidates[left], &candidates[right]));
    for (rank, &index) in contenders.iter().enumerate() {
        let active = rank < limits.cap;
        match (candidates[index].awake, active) {
            (true, false) => {
                let cause = SleepCause::Cap;
                let budget = rank < limits.nominal_cap;
                decisions.push((index, Decision::Sleep { cause, budget }));
            }
            (false, true) => decisions.push((index, Decision::Wake)),
            _ => {}
        }
    }
    decisions.sort_by_key(|&(index, _)| index);
    decisions
}

/// Ordre de maintien sous le plafond : le plus proche d'un observateur d'abord — un corps
/// éveillé comptant [`CAP_SWAP_MARGIN`] de moins —, puis le plus jeune ; entre dimensions,
/// à égalité parfaite, la plus petite. Total et déterministe (R-1020).
fn keep_order(left: &Candidate, right: &Candidate) -> Ordering {
    keep_distance(left)
        .total_cmp(&keep_distance(right))
        .then(right.birth.cmp(&left.birth))
        .then(left.dimension.cmp(&right.dimension))
}

fn keep_distance(candidate: &Candidate) -> f32 {
    let distance = candidate.distance.unwrap_or(f32::INFINITY);
    if candidate.awake {
        distance - CAP_SWAP_MARGIN
    } else {
        distance
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rapier3d::prelude::RigidBodyHandle;

    fn body(index: u32) -> BodyId {
        BodyId::from_handle(RigidBodyHandle::from_raw_parts(index, 0))
    }

    fn candidate(index: u32, distance: Option<f32>, awake: bool) -> Candidate {
        Candidate {
            dimension: 0,
            body: body(index),
            distance,
            birth: u64::from(index),
            awake,
        }
    }

    fn limits(radius: f32, nominal_radius: f32, cap: usize, nominal_cap: usize) -> ActivityLimits {
        ActivityLimits {
            radius,
            nominal_radius,
            cap,
            nominal_cap,
        }
    }

    fn sleep(cause: SleepCause, budget: bool) -> Decision {
        Decision::Sleep { cause, budget }
    }

    #[test]
    fn le_plus_proche_observateur_est_trouve_dans_la_tranche() {
        let observers = Observers::new(
            [
                Vec3::new(-50.0, 0.0, 0.0),
                Vec3::new(10.0, 0.0, 30.0),
                Vec3::new(12.0, 0.0, 3.0),
                Vec3::new(f32::NAN, 0.0, 0.0),
            ],
            40.0,
        );
        let nearest = observers.nearest(Vec3::new(12.0, 0.0, 0.0)).unwrap();
        assert!((nearest - 3.0).abs() < 1e-6, "{nearest}");
        assert_eq!(observers.nearest(Vec3::new(200.0, 0.0, 0.0)), None);
        // Dans la tranche en x mais hors de portée en z : aucun.
        assert_eq!(observers.nearest(Vec3::new(10.0, 0.0, 90.0)), None);
        assert_eq!(Observers::new([], 40.0).nearest(Vec3::ZERO), None);
    }

    #[test]
    fn le_rayon_endort_les_eveilles_hors_de_portee_et_rien_d_autre() {
        let candidates = [
            candidate(0, Some(10.0), true),
            candidate(1, None, true),
            candidate(2, None, false),
            candidate(3, Some(10.0), false),
        ];
        let decisions = plan(&candidates, limits(128.0, 128.0, 100, 100));
        assert_eq!(
            decisions,
            vec![(1, sleep(SleepCause::Radius, false)), (3, Decision::Wake)]
        );
    }

    #[test]
    fn un_sommeil_que_seule_la_degradation_impose_est_de_budget() {
        // Rayon réduit à 96 (palier 2) : entre 96 et 128, le sommeil est de budget.
        let candidates = [candidate(0, Some(100.0), true), candidate(1, None, true)];
        assert_eq!(
            plan(&candidates, limits(96.0, 128.0, 100, 100)),
            vec![
                (0, sleep(SleepCause::Radius, true)),
                (1, sleep(SleepCause::Radius, false)),
            ]
        );
        // Plafond réduit à 1 (palier 3), nominal 2 : le deuxième rang est de budget, le
        // troisième ne l'est pas.
        let candidates = [
            candidate(0, Some(1.0), true),
            candidate(1, Some(2.0), true),
            candidate(2, Some(3.0), true),
        ];
        assert_eq!(
            plan(&candidates, limits(128.0, 128.0, 1, 2)),
            vec![
                (1, sleep(SleepCause::Cap, true)),
                (2, sleep(SleepCause::Cap, false)),
            ]
        );
    }

    #[test]
    fn a_distance_egale_le_plus_ancien_s_endort_d_abord() {
        let candidates = [
            candidate(0, Some(5.0), true),
            candidate(1, Some(5.0), true),
            candidate(2, Some(5.0), true),
        ];
        assert_eq!(
            plan(&candidates, limits(128.0, 128.0, 1, 1)),
            vec![
                (0, sleep(SleepCause::Cap, false)),
                (1, sleep(SleepCause::Cap, false)),
            ]
        );
    }

    #[test]
    fn un_corps_endormi_ne_prend_une_place_que_s_il_est_nettement_plus_proche() {
        // Plafond plein : l'endormi n'a l'avance que de 10 blocs, il attend.
        let near_tie = [
            candidate(0, Some(50.0), true),
            candidate(1, Some(40.0), false),
        ];
        assert!(plan(&near_tie, limits(128.0, 128.0, 1, 1)).is_empty());
        // Plus proche d'au moins une section : il prend la place.
        let clear = [
            candidate(0, Some(50.0), true),
            candidate(1, Some(20.0), false),
        ];
        assert_eq!(
            plan(&clear, limits(128.0, 128.0, 1, 1)),
            vec![(0, sleep(SleepCause::Cap, false)), (1, Decision::Wake)]
        );
    }

    #[test]
    fn une_place_libre_reveille_les_plus_proches_d_abord() {
        let candidates = [
            candidate(0, Some(90.0), false),
            candidate(1, Some(30.0), false),
            candidate(2, Some(60.0), false),
        ];
        assert_eq!(
            plan(&candidates, limits(128.0, 128.0, 2, 2)),
            vec![(1, Decision::Wake), (2, Decision::Wake)]
        );
    }
}
