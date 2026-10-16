//! R-501 par le calcul (ADR-133 A1) : le coût de la télémétrie reste sous 1 % du composant
//! mesuré.
//!
//! B-08, jouant l'une après l'autre la charge physique sous l'allocateur du système et sous
//! l'allocateur compteur, laissait la dérive de la machine dépasser l'effet cherché. Le calcul
//! sépare les deux grandeurs : le **surcoût d'une allocation**, que B-08 mesure sur sa charge
//! d'allocations seules (`small_allocations`), où il domine ; et le **nombre d'allocations** de
//! la charge physique, un fait du code compté par [`crate::alloc_events`]. Leur produit est le
//! coût du compteur sur cette charge, rapporté à sa durée sous l'allocateur du système.
//!
//! Numérateur et dénominateur portent sur le même travail : la charge `physics_ticks` entière,
//! construction du monde comprise, telle que B-08 la chronomètre. Les durées viennent d'une même
//! campagne B-08 — même commit, même date, même machine.
//!
//! Les chiffres d'un poste de développement ne se publient pas (R-2252) : ce module rend un
//! verdict, la mesure publiable vient en M12 sur matériel de référence (ADR-131).

use std::collections::BTreeMap;
use std::path::Path;

use crate::BenchmarkResult;

/// Seuil de R-501 : la télémétrie coûte moins de 1 % du composant mesuré.
pub const THRESHOLD: f64 = 0.01;

/// Variantes de B-08 dont le calcul a besoin (`<charge>-<allocateur>`).
const SMALL_SYSTEM: &str = "small_allocations-system";
const SMALL_COUNTED: &str = "small_allocations-counted";
const PHYSICS_SYSTEM: &str = "physics_ticks-system";

/// Ce que le calcul consomme.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Inputs {
    /// Allocations de la charge `small_allocations`, comptées.
    pub small_allocations: u64,
    /// p50 de `small_allocations` sous l'allocateur du système, en ns.
    pub small_system_ns: u64,
    /// p50 de `small_allocations` sous l'allocateur compteur, en ns.
    pub small_counted_ns: u64,
    /// Allocations de la charge `physics_ticks`, comptées.
    pub physics_allocations: u64,
    /// p50 de `physics_ticks` sous l'allocateur du système, en ns.
    pub physics_system_ns: u64,
}

/// Le coût calculé du compteur sur la charge physique.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Verdict {
    /// Surcoût d'une allocation sous l'allocateur compteur, libération comprise, en ns.
    pub overhead_per_allocation_ns: f64,
    /// Surcoût du compteur sur la charge physique, en ns.
    pub telemetry_ns: f64,
    /// Part de la durée de la charge physique que ce surcoût représente.
    pub fraction: f64,
}

impl Verdict {
    /// Vrai si R-501 tient : la part reste sous [`THRESHOLD`].
    #[must_use]
    pub fn holds(&self) -> bool {
        self.fraction < THRESHOLD
    }
}

/// Calcule le coût du compteur sur la charge physique.
///
/// Un écart négatif sur `small_allocations` — le compteur plus rapide que le système — est du
/// bruit de mesure : il compte pour zéro, pas pour un gain.
///
/// # Errors
///
/// Rien à diviser : aucune allocation comptée sur `small_allocations` (le compteur
/// d'allocations n'était pas installé), ou une durée physique nulle.
#[allow(clippy::cast_precision_loss)] // des comptes et des nanosecondes, loin de 2^52
pub fn judge(inputs: &Inputs) -> Result<Verdict, String> {
    if inputs.small_allocations == 0 {
        return Err(
            "aucune allocation comptee sur small_allocations : le compteur \
                    d'allocations n'est pas l'allocateur global"
                .to_string(),
        );
    }
    if inputs.physics_system_ns == 0 {
        return Err("duree nulle pour physics_ticks-system".to_string());
    }
    let delta = inputs
        .small_counted_ns
        .saturating_sub(inputs.small_system_ns) as f64;
    let overhead_per_allocation_ns = delta / inputs.small_allocations as f64;
    let telemetry_ns = overhead_per_allocation_ns * inputs.physics_allocations as f64;
    Ok(Verdict {
        overhead_per_allocation_ns,
        telemetry_ns,
        fraction: telemetry_ns / inputs.physics_system_ns as f64,
    })
}

/// Les p50 d'une campagne B-08 archivée, une par commit et par date.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Campaign {
    /// Commit mesuré.
    pub commit: String,
    /// Date de la campagne.
    pub date: String,
    /// p50 de `small_allocations` sous l'allocateur du système, en ns.
    pub small_system_ns: u64,
    /// p50 de `small_allocations` sous l'allocateur compteur, en ns.
    pub small_counted_ns: u64,
    /// p50 de `physics_ticks` sous l'allocateur du système, en ns.
    pub physics_system_ns: u64,
}

impl Campaign {
    /// Lit, dans le dossier B-08 d'une plateforme, la plus récente campagne complète d'un
    /// commit : celle dont les trois variantes du calcul sont archivées à la même date.
    ///
    /// `commit` est l'empreinte complète ou un préfixe d'au moins sept caractères.
    ///
    /// # Errors
    ///
    /// Dossier illisible, préfixe trop court, ou aucune campagne complète de ce commit.
    pub fn load(dir: &Path, commit: &str) -> Result<Self, String> {
        if commit.len() < 7 {
            return Err(format!("empreinte de commit trop courte : {commit}"));
        }
        let entries = std::fs::read_dir(dir)
            .map_err(|error| format!("{} illisible : {error}", dir.display()))?;
        // date -> (commit complet, variante -> p50)
        let mut by_date: BTreeMap<String, (String, BTreeMap<String, u64>)> = BTreeMap::new();
        for entry in entries.flatten() {
            let path = entry.path();
            if path.extension().and_then(|e| e.to_str()) != Some("json") {
                continue;
            }
            let Ok(text) = std::fs::read_to_string(&path) else {
                continue;
            };
            let Ok(result) = serde_json::from_str::<BenchmarkResult>(&text) else {
                continue;
            };
            if result.benchmark != "B-08" || !result.commit.starts_with(commit) {
                continue;
            }
            let Some(variant) = result.parameters.get("variant").and_then(|v| v.as_str()) else {
                continue;
            };
            let slot = by_date
                .entry(result.date.clone())
                .or_insert_with(|| (result.commit.clone(), BTreeMap::new()));
            slot.1.insert(variant.to_string(), result.stats.p50_ns);
        }
        by_date
            .into_iter()
            .rev()
            .find_map(|(date, (full, p50))| {
                Some(Self {
                    small_system_ns: *p50.get(SMALL_SYSTEM)?,
                    small_counted_ns: *p50.get(SMALL_COUNTED)?,
                    physics_system_ns: *p50.get(PHYSICS_SYSTEM)?,
                    commit: full,
                    date,
                })
            })
            .ok_or_else(|| {
                format!(
                    "aucune campagne B-08 complete du commit {commit} sous {} ({SMALL_SYSTEM}, \
                     {SMALL_COUNTED} et {PHYSICS_SYSTEM} a la meme date)",
                    dir.display()
                )
            })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{archive, Platform, Run};
    use std::sync::atomic::{AtomicU64, Ordering};

    fn inputs() -> Inputs {
        Inputs {
            small_allocations: 10_000,
            small_system_ns: 1_000_000,
            small_counted_ns: 1_200_000,
            physics_allocations: 5_000,
            physics_system_ns: 50_000_000,
        }
    }

    #[test]
    fn t_r501_multiplie_le_surcout_par_allocation_par_le_compte() {
        let verdict = judge(&inputs()).unwrap();
        // 200 000 ns d'écart sur 10 000 allocations : 20 ns chacune ; 5 000 allocations, 100 µs.
        assert!((verdict.overhead_per_allocation_ns - 20.0).abs() < 1e-9);
        assert!((verdict.telemetry_ns - 100_000.0).abs() < 1e-6);
        assert!((verdict.fraction - 0.002).abs() < 1e-12);
        assert!(verdict.holds());
    }

    #[test]
    fn t_r501_le_seuil_est_strict() {
        let at_threshold = Inputs {
            physics_allocations: 25_000,
            ..inputs()
        };
        // 25 000 × 20 ns = 500 µs sur 50 ms : 1 % tout juste, qui ne tient pas.
        let verdict = judge(&at_threshold).unwrap();
        assert!((verdict.fraction - 0.01).abs() < 1e-12);
        assert!(!verdict.holds());
    }

    #[test]
    fn t_r501_un_ecart_negatif_est_du_bruit() {
        let noise = Inputs {
            small_counted_ns: 900_000,
            ..inputs()
        };
        let verdict = judge(&noise).unwrap();
        assert!(verdict.overhead_per_allocation_ns.abs() < f64::EPSILON);
        assert!(verdict.holds());
    }

    #[test]
    fn t_r501_refuse_ce_qui_ne_se_divise_pas() {
        assert!(judge(&Inputs {
            small_allocations: 0,
            ..inputs()
        })
        .unwrap_err()
        .contains("allocateur global"));
        assert!(judge(&Inputs {
            physics_system_ns: 0,
            ..inputs()
        })
        .is_err());
    }

    fn result(commit: &str, date: &str, variant: &str, p50: u64) -> BenchmarkResult {
        let mut parameters = BTreeMap::new();
        parameters.insert("variant".to_string(), serde_json::Value::from(variant));
        BenchmarkResult::new(
            "B-08",
            commit,
            date,
            Platform {
                os: "test".to_string(),
                cpu: "cpu de test".to_string(),
                cores: 8,
                gpu: String::new(),
                driver: String::new(),
                jvm: String::new(),
            },
            BTreeMap::new(),
            parameters,
            vec![Run {
                samples_ns: vec![p50],
            }],
        )
        .unwrap()
    }

    #[test]
    fn t_r501_lit_la_derniere_campagne_complete_du_commit() {
        static COUNTER: AtomicU64 = AtomicU64::new(0);
        let root = std::env::temp_dir().join(format!(
            "ax-bench-r501-{}-{}",
            std::process::id(),
            COUNTER.fetch_add(1, Ordering::Relaxed)
        ));
        let a = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        let b = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        for (commit, date, variant, p50) in [
            // Campagne complète du 13.
            (a, "2026-10-13T01:00:00Z", SMALL_SYSTEM, 100),
            (a, "2026-10-13T01:00:00Z", SMALL_COUNTED, 120),
            (a, "2026-10-13T01:00:00Z", PHYSICS_SYSTEM, 9_000),
            // Celle du 14 est incomplète : la physique manque.
            (a, "2026-10-14T01:00:00Z", SMALL_SYSTEM, 101),
            (a, "2026-10-14T01:00:00Z", SMALL_COUNTED, 121),
            // Un autre commit, plus récent, complet.
            (b, "2026-10-15T01:00:00Z", SMALL_SYSTEM, 1),
            (b, "2026-10-15T01:00:00Z", SMALL_COUNTED, 2),
            (b, "2026-10-15T01:00:00Z", PHYSICS_SYSTEM, 3),
        ] {
            archive(&result(commit, date, variant, p50), &root).unwrap();
        }
        let dir = root.join("B-08").join("test-cpu-de-test");
        let campaign = Campaign::load(&dir, &a[..12]).unwrap();
        assert_eq!(campaign.date, "2026-10-13T01:00:00Z");
        assert_eq!(campaign.commit, a);
        assert_eq!(
            (
                campaign.small_system_ns,
                campaign.small_counted_ns,
                campaign.physics_system_ns
            ),
            (100, 120, 9_000)
        );
        assert!(Campaign::load(&dir, "cccccccccccc").is_err());
        assert!(Campaign::load(&dir, "aaaa").is_err(), "préfixe trop court");
        let _ = std::fs::remove_dir_all(&root);
    }
}
