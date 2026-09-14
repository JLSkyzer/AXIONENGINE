//! Resultat de benchmark au format schema 2 (PARTIE 30.4).
//!
//! R-2230 interdit tout chiffre de performance publie sans fichier genere par
//! le harnais ; R-2231 fixe ce que ce fichier porte. Ce module modelise ce
//! fichier et rien d'autre : il ne mesure pas, il decrit une mesure faite.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use crate::stats::Stats;

/// Numero de schema du format de resultat (PARTIE 30.4). Un changement de forme
/// l'incremente.
pub const SCHEMA_VERSION: u32 = 2;

/// Version du harnais qui a produit le resultat (R-2231, « version du
/// harnais »). Le schema 2 n'a pas de champ dedie : elle voyage dans `config`
/// sous la cle [`HARNESS_VERSION_KEY`], ce que documente
/// `docs/decisions/ADR-111.md`. Une hausse la signale a une comparaison de
/// non-regression, qui ne doit pas comparer les mesures de deux harnais.
pub const HARNESS_VERSION: u32 = 1;

/// Cle sous laquelle la version du harnais figure dans `config`.
pub const HARNESS_VERSION_KEY: &str = "harness_version";

/// Machine sur laquelle un benchmark a tourne (R-2231).
///
/// La plateforme decrit la **machine**, non le benchmark : une mesure sans GPU
/// tourne sur une machine qui en a peut-etre un, mais que la mesure ne sollicite
/// pas. `gpu`, `driver` et `jvm` restent donc vides quand le benchmark ne les
/// met pas en jeu ; `os`, `cpu` et `cores` identifient la machine et sont
/// toujours requis a l'archivage.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Platform {
    /// Systeme d'exploitation, p. ex. `windows`, `linux`, `macos`.
    pub os: String,
    /// Modele de processeur. La bibliotheque standard ne le connait pas seule ;
    /// le fournisseur (CI, harnais en jeu) le renseigne.
    pub cpu: String,
    /// Nombre de coeurs logiques disponibles.
    pub cores: u32,
    /// Carte graphique, quand un benchmark GPU la sollicite ; vide sinon.
    #[serde(default)]
    pub gpu: String,
    /// Version du pilote graphique, quand elle s'applique ; vide sinon.
    #[serde(default)]
    pub driver: String,
    /// Version de la JVM, pour un benchmark en jeu ; vide pour un micro-
    /// benchmark Rust sans JVM.
    #[serde(default)]
    pub jvm: String,
}

impl Platform {
    /// Renseigne ce que la bibliotheque standard sait avec certitude : le
    /// systeme et le nombre de coeurs.
    ///
    /// Le reste — processeur, GPU, pilote, JVM — n'est pas connaissable en Rust
    /// pur sans supposer, et reste a la charge de l'appelant (R-2231, et la
    /// lecon « une absence se marque, elle ne se remplace pas »).
    ///
    /// Effet : lecture d'environnement seule. Thread : quelconque. Cout :
    /// negligeable. Echec : aucun ; `cores` vaut 1 si le compte est indisponible.
    pub fn detect() -> Self {
        let cores = std::thread::available_parallelism()
            .map(|n| n.get() as u32)
            .unwrap_or(1);
        Self {
            os: std::env::consts::OS.to_string(),
            cpu: String::new(),
            cores,
            gpu: String::new(),
            driver: String::new(),
            jvm: String::new(),
        }
    }
}

/// Une execution independante : ses echantillons bruts, en nanosecondes
/// (R-2231, « donnees brutes par iteration »).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Run {
    /// Duree de chaque iteration mesuree, en nanosecondes.
    pub samples_ns: Vec<u64>,
}

/// Resultat complet d'un benchmark, tel qu'archive (PARTIE 30.4).
///
/// L'ordre des champs suit l'exemple normatif ; `serde_json` le preserve, et la
/// sortie reste ainsi comparable a la specification.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct BenchmarkResult {
    /// Numero de schema ; toujours [`SCHEMA_VERSION`].
    pub schema: u32,
    /// Identifiant du benchmark mesure, p. ex. `B-02` (PARTIE 30.2).
    pub benchmark: String,
    /// Empreinte du commit mesure.
    pub commit: String,
    /// Date de la mesure, en ISO 8601.
    pub date: String,
    /// Machine de mesure.
    pub platform: Platform,
    /// Configuration figee de la mesure (R-2231). Porte la version du harnais
    /// sous [`HARNESS_VERSION_KEY`] et, le cas echeant, les niveaux de qualite.
    pub config: BTreeMap<String, serde_json::Value>,
    /// Parametres du cas mesure (taille, nombre d'objets...).
    pub parameters: BTreeMap<String, serde_json::Value>,
    /// Executions independantes ; au moins une, chacune non vide (R-2231).
    pub runs: Vec<Run>,
    /// Statistiques agregees sur l'ensemble des echantillons.
    pub stats: Stats,
}

impl BenchmarkResult {
    /// Assemble un resultat a partir de ses executions.
    ///
    /// Les statistiques sont **calculees** ici, sur l'ensemble des echantillons
    /// de toutes les executions : elles ne peuvent donc pas contredire les
    /// donnees brutes, contrairement a des stats fournies a cote. La version du
    /// harnais est posee dans `config` par le harnais lui-meme (ADR-111) ; une
    /// valeur que l'appelant aurait mise sous cette cle est ecrasee.
    ///
    /// Effet : agrege et trie les echantillons. Thread : quelconque. Cout :
    /// O(n log n) sur le total des echantillons. Echec : `None` si aucune
    /// execution ne porte le moindre echantillon — un resultat sans mesure
    /// n'aurait rien a archiver (R-2230).
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        benchmark: impl Into<String>,
        commit: impl Into<String>,
        date: impl Into<String>,
        platform: Platform,
        mut config: BTreeMap<String, serde_json::Value>,
        parameters: BTreeMap<String, serde_json::Value>,
        runs: Vec<Run>,
    ) -> Option<Self> {
        let pooled: Vec<u64> = runs
            .iter()
            .flat_map(|r| r.samples_ns.iter().copied())
            .collect();
        let stats = Stats::from_samples(&pooled)?;
        config.insert(
            HARNESS_VERSION_KEY.to_string(),
            serde_json::Value::from(HARNESS_VERSION),
        );
        Some(Self {
            schema: SCHEMA_VERSION,
            benchmark: benchmark.into(),
            commit: commit.into(),
            date: date.into(),
            platform,
            config,
            parameters,
            runs,
            stats,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn one_run(samples: &[u64]) -> Vec<Run> {
        vec![Run {
            samples_ns: samples.to_vec(),
        }]
    }

    #[test]
    fn t_new_computes_stats_from_pooled_samples() {
        let runs = vec![
            Run {
                samples_ns: vec![1, 2, 3],
            },
            Run {
                samples_ns: vec![4, 5],
            },
        ];
        let r = BenchmarkResult::new(
            "B-02",
            "abc",
            "2026-09-14T00:00:00Z",
            Platform::detect(),
            BTreeMap::new(),
            BTreeMap::new(),
            runs,
        )
        .unwrap();
        assert_eq!(r.schema, SCHEMA_VERSION);
        assert_eq!(r.stats.min_ns, 1);
        assert_eq!(r.stats.max_ns, 5);
    }

    #[test]
    fn t_new_injects_harness_version() {
        let r = BenchmarkResult::new(
            "B-02",
            "abc",
            "2026-09-14T00:00:00Z",
            Platform::detect(),
            BTreeMap::new(),
            BTreeMap::new(),
            one_run(&[10]),
        )
        .unwrap();
        assert_eq!(
            r.config.get(HARNESS_VERSION_KEY),
            Some(&serde_json::Value::from(HARNESS_VERSION))
        );
    }

    #[test]
    fn t_new_overwrites_caller_harness_version() {
        let mut config = BTreeMap::new();
        config.insert(
            HARNESS_VERSION_KEY.to_string(),
            serde_json::Value::from(999u32),
        );
        let r = BenchmarkResult::new(
            "B-02",
            "abc",
            "2026-09-14T00:00:00Z",
            Platform::detect(),
            config,
            BTreeMap::new(),
            one_run(&[10]),
        )
        .unwrap();
        assert_eq!(
            r.config.get(HARNESS_VERSION_KEY),
            Some(&serde_json::Value::from(HARNESS_VERSION))
        );
    }

    #[test]
    fn t_new_without_samples_is_none() {
        let empty: Vec<Run> = vec![Run { samples_ns: vec![] }];
        assert!(BenchmarkResult::new(
            "B-02",
            "abc",
            "2026-09-14T00:00:00Z",
            Platform::detect(),
            BTreeMap::new(),
            BTreeMap::new(),
            empty,
        )
        .is_none());
    }

    #[test]
    fn t_detect_reports_at_least_one_core() {
        assert!(Platform::detect().cores >= 1);
    }
}
