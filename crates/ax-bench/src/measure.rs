//! Mesure d'une routine selon la methodologie normative (PARTIE 30.3).
//!
//! La methodologie prescrit un echauffement non mesure, puis plusieurs
//! executions independantes, chacune collectant des echantillons bruts. Ce
//! module l'applique et rend des [`Run`] ; il ne calcule pas les statistiques
//! (c'est [`crate::stats`]) et n'archive rien (c'est [`crate::archive`]).
//!
//! La collecte est separee du temps reel : [`measure_with_clock`] prend une
//! horloge, ce qui la rend testable sans dependre d'une duree de paroi.

use std::time::{Duration, Instant};

use crate::result::Run;

/// Parametres d'une campagne de mesure (PARTIE 30.3).
///
/// Le plafond d'echantillons borne la memoire : une routine de l'ordre de la
/// nanoseconde produirait des millions d'echantillons en une seconde. Pour une
/// routine lente, c'est la duree qui arrete l'execution ; pour une rapide, le
/// plafond.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MeasureConfig {
    /// Duree d'echauffement non mesuree.
    pub warmup: Duration,
    /// Nombre d'executions independantes.
    pub runs: u32,
    /// Duree cible d'une execution.
    pub run_duration: Duration,
    /// Plafond d'echantillons par execution.
    pub max_samples_per_run: usize,
}

impl MeasureConfig {
    /// La configuration normative de la PARTIE 30.3 : 30 s d'echauffement,
    /// 5 executions de 60 s. Pour les chiffres publies sur materiel de
    /// reference (R-2252), pas pour la CI.
    pub const NORMATIVE: Self = Self {
        warmup: Duration::from_secs(30),
        runs: 5,
        run_duration: Duration::from_secs(60),
        max_samples_per_run: 1_000_000,
    };

    /// Configuration rapide pour la CI et les tests : de quoi detecter une
    /// regression grossiere (R-2252), pas de quoi publier.
    pub const QUICK: Self = Self {
        warmup: Duration::from_millis(200),
        runs: 5,
        run_duration: Duration::from_millis(200),
        max_samples_per_run: 20_000,
    };
}

/// Mesure une routine avec une horloge injectee, rendant des nanosecondes
/// monotones.
///
/// Deroulement (PARTIE 30.3) : appeler la routine sans mesurer jusqu'a ecouler
/// l'echauffement, puis, pour chaque execution, chronometrer chaque appel et
/// enregistrer sa duree jusqu'a ce que la duree cible soit atteinte ou le
/// plafond d'echantillons franchi. Chaque execution rend au moins un
/// echantillon.
///
/// Effet : appelle `routine` de nombreuses fois. Thread : quelconque. Cout : la
/// somme des durees. Echec : aucun ; rend toujours `config.runs` executions non
/// vides.
pub fn measure_with_clock(
    config: &MeasureConfig,
    mut clock: impl FnMut() -> u64,
    mut routine: impl FnMut(),
) -> Vec<Run> {
    let warmup_ns = duration_ns(config.warmup);
    let warmup_start = clock();
    while clock().saturating_sub(warmup_start) < warmup_ns {
        routine();
    }

    let run_ns = duration_ns(config.run_duration);
    let cap = config.max_samples_per_run.max(1);
    let mut runs = Vec::with_capacity(config.runs as usize);
    for _ in 0..config.runs {
        let mut samples = Vec::new();
        let run_start = clock();
        loop {
            let before = clock();
            routine();
            let after = clock();
            samples.push(after.saturating_sub(before));
            if samples.len() >= cap || clock().saturating_sub(run_start) >= run_ns {
                break;
            }
        }
        runs.push(Run {
            samples_ns: samples,
        });
    }
    runs
}

/// Mesure une routine sur l'horloge monotone du systeme.
///
/// Voir [`measure_with_clock`] pour le deroulement.
pub fn measure(config: &MeasureConfig, routine: impl FnMut()) -> Vec<Run> {
    let origin = Instant::now();
    measure_with_clock(config, move || elapsed_ns(&origin), routine)
}

/// Nanosecondes d'une duree, saturees a `u64::MAX` (une duree de benchmark n'en
/// approche jamais).
fn duration_ns(d: Duration) -> u64 {
    u64::try_from(d.as_nanos()).unwrap_or(u64::MAX)
}

/// Nanosecondes ecoulees depuis `origin`, saturees.
fn elapsed_ns(origin: &Instant) -> u64 {
    u64::try_from(origin.elapsed().as_nanos()).unwrap_or(u64::MAX)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Horloge factice : chaque lecture avance de `step` nanosecondes. Elle rend
    /// le deroulement deterministe, sans dependre d'une duree reelle.
    fn fake_clock(step: u64) -> impl FnMut() -> u64 {
        let mut now = 0u64;
        move || {
            let value = now;
            now += step;
            value
        }
    }

    #[test]
    fn t_produces_exactly_runs_executions() {
        let config = MeasureConfig {
            warmup: Duration::from_nanos(10),
            runs: 5,
            run_duration: Duration::from_nanos(50),
            max_samples_per_run: 1_000,
        };
        let runs = measure_with_clock(&config, fake_clock(1), || {});
        assert_eq!(runs.len(), 5);
    }

    #[test]
    fn t_each_run_has_at_least_one_sample() {
        // Duree cible nulle : la boucle enregistre tout de meme un echantillon
        // avant de verifier la sortie.
        let config = MeasureConfig {
            warmup: Duration::ZERO,
            runs: 3,
            run_duration: Duration::ZERO,
            max_samples_per_run: 1_000,
        };
        let runs = measure_with_clock(&config, fake_clock(7), || {});
        assert_eq!(runs.len(), 3);
        for run in &runs {
            assert!(!run.samples_ns.is_empty());
        }
    }

    #[test]
    fn t_sample_cap_is_respected() {
        // Duree cible enorme, plafond bas : c'est le plafond qui arrete.
        let config = MeasureConfig {
            warmup: Duration::ZERO,
            runs: 2,
            run_duration: Duration::from_secs(3600),
            max_samples_per_run: 8,
        };
        let runs = measure_with_clock(&config, fake_clock(1), || {});
        for run in &runs {
            assert_eq!(run.samples_ns.len(), 8);
        }
    }

    #[test]
    fn t_real_clock_measures_without_panicking() {
        // La routine fait un travail non trivial pour que la mesure soit
        // positive ; on verifie surtout qu'aucun echantillon n'est vide.
        let config = MeasureConfig {
            warmup: Duration::from_millis(1),
            runs: 2,
            run_duration: Duration::from_millis(1),
            max_samples_per_run: 100,
        };
        let runs = measure(&config, || {
            let _ = (0..64).sum::<u64>();
        });
        assert_eq!(runs.len(), 2);
        assert!(runs.iter().all(|r| !r.samples_ns.is_empty()));
    }
}
