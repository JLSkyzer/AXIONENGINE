//! C-72 : harnais de benchmarks d'AXION ENGINE.
//!
//! Ce crate ne mesure pas encore : il fournit la **fondation** commune que toute
//! mesure partage — le format de resultat schema 2 (PARTIE 30.4), la capture de
//! la plateforme, le calcul des statistiques normatives (PARTIE 30.3) et
//! l'archivage sous `benchmarks/results/`. Les micro-benchmarks Rust
//! (`criterion`, R-2240) et le harnais en jeu s'appuient dessus pour produire
//! des fichiers conformes a R-2230 et R-2231.
//!
//! Position de principe (R-2230) : aucun chiffre de performance ne se publie
//! sans un fichier genere par ce harnais. Ce crate rend ce fichier possible et
//! refuse d'en ecrire un incomplet ; il n'en fabrique jamais le contenu.

pub mod archive;
pub mod cases;
pub mod measure;
pub mod regress;
pub mod result;
pub mod stats;

pub use archive::{archive, platform_slug, to_json, validate, ArchiveError};
pub use measure::{measure, measure_with_clock, MeasureConfig};
pub use regress::{compare_p95, latest_baseline, Regression, REGRESSION_TOLERANCE};
pub use result::{
    BenchmarkResult, Platform, Run, HARNESS_VERSION, HARNESS_VERSION_KEY, SCHEMA_VERSION,
};
pub use stats::Stats;
