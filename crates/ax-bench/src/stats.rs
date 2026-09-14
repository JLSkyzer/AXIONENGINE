//! Statistiques d'un ensemble d'echantillons de duree (PARTIE 30.3).
//!
//! La methodologie normative interdit de rapporter la moyenne seule : un
//! resultat porte p50, p95, p99, min, max et ecart-type. Ce module ne fait que
//! ce calcul, sur des echantillons deja mesures.

use serde::{Deserialize, Serialize};

/// Agregats exiges par la methodologie (PARTIE 30.3).
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct Stats {
    /// Mediane (centile 50), en nanosecondes.
    pub p50_ns: u64,
    /// Centile 95, en nanosecondes.
    pub p95_ns: u64,
    /// Centile 99, en nanosecondes.
    pub p99_ns: u64,
    /// Minimum observe, en nanosecondes.
    pub min_ns: u64,
    /// Maximum observe, en nanosecondes.
    pub max_ns: u64,
    /// Ecart-type de population des echantillons, en nanosecondes.
    pub stddev_ns: f64,
}

impl Stats {
    /// Calcule les agregats d'un ensemble d'echantillons.
    ///
    /// Les centiles suivent la methode du rang le plus proche
    /// (« nearest-rank ») sur les echantillons tries : `rang = ceil(p/100 * n)`,
    /// et la valeur est le `rang`-ieme echantillon trie. Elle rend toujours une
    /// valeur reellement observee — ce qui convient a des durees — et son calcul
    /// est entier, donc reproductible d'une machine a l'autre. L'ecart-type est
    /// celui de la population (division par `n`).
    ///
    /// La somme se fait dans l'ordre trie, fixe, pour que le resultat ne depende
    /// pas de l'ordre d'arrivee des echantillons.
    ///
    /// Effet : trie une copie des echantillons. Thread : quelconque. Cout :
    /// O(n log n). Echec : `None` si `samples` est vide — une statistique sans
    /// echantillon n'a pas de sens, et en inventer une violerait R-2230.
    pub fn from_samples(samples: &[u64]) -> Option<Self> {
        if samples.is_empty() {
            return None;
        }
        let mut sorted = samples.to_vec();
        sorted.sort_unstable();
        let n = sorted.len();

        // rang dans [1, n], index dans [0, n-1]. `p * n` tient dans un u64 pour
        // tout n plausible (p <= 99).
        let percentile = |p: u64| -> u64 {
            let rank = (p * n as u64).div_ceil(100).clamp(1, n as u64);
            sorted[(rank - 1) as usize]
        };

        let min_ns = sorted[0];
        let max_ns = sorted[n - 1];

        let sum: f64 = sorted.iter().map(|&x| x as f64).sum();
        let mean = sum / n as f64;
        let variance: f64 = sorted
            .iter()
            .map(|&x| {
                let d = x as f64 - mean;
                d * d
            })
            .sum::<f64>()
            / n as f64;
        let stddev_ns = variance.sqrt();

        Some(Self {
            p50_ns: percentile(50),
            p95_ns: percentile(95),
            p99_ns: percentile(99),
            min_ns,
            max_ns,
            stddev_ns,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn t_empty_gives_none() {
        assert_eq!(Stats::from_samples(&[]), None);
    }

    #[test]
    fn t_single_sample_collapses_all_stats() {
        let s = Stats::from_samples(&[42]).unwrap();
        assert_eq!(s.p50_ns, 42);
        assert_eq!(s.p95_ns, 42);
        assert_eq!(s.p99_ns, 42);
        assert_eq!(s.min_ns, 42);
        assert_eq!(s.max_ns, 42);
        assert_eq!(s.stddev_ns, 0.0);
    }

    #[test]
    fn t_nearest_rank_on_hundred_samples() {
        // 1..=100 : le n-ieme centile par rang le plus proche vaut n.
        let samples: Vec<u64> = (1..=100).collect();
        let s = Stats::from_samples(&samples).unwrap();
        assert_eq!(s.p50_ns, 50);
        assert_eq!(s.p95_ns, 95);
        assert_eq!(s.p99_ns, 99);
        assert_eq!(s.min_ns, 1);
        assert_eq!(s.max_ns, 100);
    }

    #[test]
    fn t_median_of_five() {
        let s = Stats::from_samples(&[9, 1, 5, 3, 7]).unwrap();
        assert_eq!(s.min_ns, 1);
        assert_eq!(s.max_ns, 9);
        assert_eq!(s.p50_ns, 5);
        // p95 et p99 de 5 echantillons pointent le dernier (le max).
        assert_eq!(s.p95_ns, 9);
        assert_eq!(s.p99_ns, 9);
    }

    #[test]
    fn t_order_independent() {
        let a = Stats::from_samples(&[1, 2, 3, 4, 5]).unwrap();
        let b = Stats::from_samples(&[5, 4, 3, 2, 1]).unwrap();
        assert_eq!(a, b);
    }

    #[test]
    fn t_population_stddev_known_value() {
        // Population {2,4,4,4,5,5,7,9} : moyenne 5, ecart-type 2 (cas classique).
        let s = Stats::from_samples(&[2, 4, 4, 4, 5, 5, 7, 9]).unwrap();
        assert!((s.stddev_ns - 2.0).abs() < 1e-9);
    }
}
