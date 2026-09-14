//! Non-regression : comparaison du p95 au dernier resultat archive (R-2250,
//! R-2251).
//!
//! La CI mesure un sous-ensemble rapide et le compare au dernier resultat
//! archive de la meme plateforme. Une regression de plus de 20 % sur le p95
//! bloque la fusion (R-2251). Ces chiffres servent a detecter une regression
//! grossiere, pas a etre publies (R-2252).

use std::path::Path;

use crate::archive::platform_slug;
use crate::result::{BenchmarkResult, Platform, HARNESS_VERSION_KEY};

/// Seuil de regression sur le p95 (R-2251) : au-dela, la fusion est bloquee.
pub const REGRESSION_TOLERANCE: f64 = 0.20;

/// Verdict d'une comparaison de non-regression.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Regression {
    /// Aucune reference comparable : premiere mesure de cette plateforme, ou
    /// reference produite par un autre harnais (ADR-111), ou p95 de reference
    /// nul. On ne bloque pas sur une absence.
    NoBaseline,
    /// Dans la tolerance. `ratio` est la variation relative du p95 : negatif
    /// pour une amelioration, positif pour une degradation en deca du seuil.
    Ok {
        /// Variation relative du p95, `(courant - reference) / reference`.
        ratio: f64,
    },
    /// Regression : le p95 a cru de plus de [`REGRESSION_TOLERANCE`] (R-2251).
    Regressed {
        /// Variation relative du p95.
        ratio: f64,
    },
}

impl Regression {
    /// Vrai si le verdict bloque la fusion (R-2251).
    #[must_use]
    pub fn blocks_merge(&self) -> bool {
        matches!(self, Regression::Regressed { .. })
    }
}

/// Compare le p95 courant au p95 d'une reference.
///
/// La comparaison n'a lieu qu'entre mesures du **meme harnais** (ADR-111) : deux
/// versions du harnais peuvent mesurer differemment, et un changement de methode
/// n'est pas une regression. Une reference sans p95 (nul) n'est pas comparable.
///
/// Effet : aucun. Thread : quelconque. Cout : constant.
#[must_use]
pub fn compare_p95(current: &BenchmarkResult, baseline: &BenchmarkResult) -> Regression {
    if current.config.get(HARNESS_VERSION_KEY) != baseline.config.get(HARNESS_VERSION_KEY) {
        return Regression::NoBaseline;
    }
    let base = baseline.stats.p95_ns;
    if base == 0 {
        return Regression::NoBaseline;
    }
    let ratio = (current.stats.p95_ns as f64 - base as f64) / base as f64;
    if ratio > REGRESSION_TOLERANCE {
        Regression::Regressed { ratio }
    } else {
        Regression::Ok { ratio }
    }
}

/// Trouve le dernier resultat archive pour un benchmark et une plateforme.
///
/// Cherche `<root>/<benchmark>/<plateforme>/*.json` et prend le nom le plus
/// grand dans l'ordre lexicographique — que le prefixe de date rend
/// chronologique (voir [`crate::archive::archive`]). Rend `None` si le dossier
/// n'existe pas, ne contient aucun JSON, ou si le plus recent est illisible.
///
/// Effet : lit le systeme de fichiers. Thread : quelconque. Cout : O(nombre de
/// fichiers du dossier).
#[must_use]
pub fn latest_baseline(
    root: &Path,
    benchmark: &str,
    platform: &Platform,
) -> Option<BenchmarkResult> {
    let dir = root.join(benchmark).join(platform_slug(platform));
    let mut latest: Option<std::ffi::OsString> = None;
    for entry in std::fs::read_dir(&dir).ok()? {
        let Ok(entry) = entry else { continue };
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("json") {
            continue;
        }
        let Some(name) = path.file_name().map(|n| n.to_os_string()) else {
            continue;
        };
        if latest.as_ref().is_none_or(|current| name > *current) {
            latest = Some(name);
        }
    }
    let name = latest?;
    let text = std::fs::read_to_string(dir.join(name)).ok()?;
    serde_json::from_str(&text).ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::result::{BenchmarkResult, Run};
    use std::collections::BTreeMap;

    fn result(p95_samples: &[u64]) -> BenchmarkResult {
        let mut platform = Platform::detect();
        platform.cpu = "CPU de test".to_string();
        BenchmarkResult::new(
            "B-02",
            "commit",
            "2026-09-14T00:00:00Z",
            platform,
            BTreeMap::new(),
            BTreeMap::new(),
            vec![Run {
                samples_ns: p95_samples.to_vec(),
            }],
        )
        .unwrap()
    }

    #[test]
    fn t_stable_p95_is_ok() {
        let base = result(&[100; 100]);
        let now = result(&[100; 100]);
        assert!(matches!(compare_p95(&now, &base), Regression::Ok { .. }));
    }

    #[test]
    fn t_improvement_is_ok_with_negative_ratio() {
        let base = result(&[200; 100]);
        let now = result(&[100; 100]);
        match compare_p95(&now, &base) {
            Regression::Ok { ratio } => assert!(ratio < 0.0),
            other => panic!("attendu Ok, obtenu {other:?}"),
        }
    }

    #[test]
    fn t_regression_beyond_20_percent_blocks() {
        let base = result(&[100; 100]);
        // p95 passe de 100 a 130 : +30 %, au-dela du seuil.
        let now = result(&[130; 100]);
        let verdict = compare_p95(&now, &base);
        assert!(verdict.blocks_merge());
        assert!(matches!(verdict, Regression::Regressed { .. }));
    }

    #[test]
    fn t_just_under_threshold_is_ok() {
        let base = result(&[100; 100]);
        // +20 % pile : la tolerance est stricte (« superieure a 20 % »).
        let now = result(&[120; 100]);
        assert!(!compare_p95(&now, &base).blocks_merge());
    }

    #[test]
    fn t_different_harness_version_is_no_baseline() {
        let base = result(&[100; 100]);
        let mut now = result(&[1000; 100]);
        now.config.insert(
            HARNESS_VERSION_KEY.to_string(),
            serde_json::Value::from(999),
        );
        assert_eq!(compare_p95(&now, &base), Regression::NoBaseline);
    }

    #[test]
    fn t_latest_baseline_reads_the_most_recent() {
        let root = std::env::temp_dir().join(format!("ax-bench-regress-{}", std::process::id()));
        let older = {
            let mut r = result(&[100; 10]);
            r.date = "2026-01-01T00:00:00Z".to_string();
            r
        };
        let newer = {
            let mut r = result(&[200; 10]);
            r.date = "2026-06-01T00:00:00Z".to_string();
            r
        };
        crate::archive::archive(&older, &root).unwrap();
        crate::archive::archive(&newer, &root).unwrap();

        let found = latest_baseline(&root, "B-02", &newer.platform).unwrap();
        assert_eq!(found.date, "2026-06-01T00:00:00Z");
        assert_eq!(found.stats.p95_ns, 200);

        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn t_latest_baseline_absent_is_none() {
        let root = std::env::temp_dir().join("ax-bench-regress-absent");
        assert!(latest_baseline(&root, "B-99", &Platform::detect()).is_none());
    }
}
