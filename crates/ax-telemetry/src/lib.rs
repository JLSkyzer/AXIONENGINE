//! C-15 — télémétrie native.
//!
//! R-500 demande à chaque composant d'exposer sa durée, ses opérations, ses
//! erreurs et sa mémoire, et à chaque budget du registre une métrique de
//! consommation et une métrique de dépassement. Ce crate est l'endroit où tout
//! cela se déclare et se mesure.
//!
//! Trois pièces :
//!
//! - [`Telemetry`] — le registre. Les métriques se déclarent au démarrage, puis
//!   il est figé : ensuite on mesure, on ne déclare plus.
//! - [`BudgetMetrics`] — les deux métriques de **chaque** budget, déclarées
//!   d'un bloc pour qu'aucun ne puisse être oublié (INV-19).
//! - [`to_json`] — l'export de R-502, lu par `/axion metrics export` et joint au
//!   dump de diagnostic.
//!
//! # Coût
//!
//! R-501 plafonne le coût de la télémétrie à 1 % du composant mesuré. La
//! conception y tend : une mesure est un `fetch_add` atomique en `Relaxed` sur
//! une case désignée par index, sans recherche par nom, sans verrou et sans
//! allocation. **Ce n'est pas une mesure** : le chiffre se vérifie par le
//! benchmark B-08, qui arrive avec les autres en M12.
//!
//! # Ce que ce crate ne fait pas
//!
//! Il ne nomme aucune métrique de composant. Chaque composant déclare les
//! siennes, avec les noms que le cahier des charges lui donne : les déclarer
//! ici reviendrait à tenir une seconde liste, qui divergerait. Les seules qu'il
//! nomme sont celles des budgets, et il les **dérive** du registre de
//! `ax-model` plutôt que de les écrire.
//!
//! Il ne journalise pas non plus. Le journal natif de R-441 est un autre
//! sujet, avec sa rotation et son répertoire ; le mélanger aux compteurs
//! empêcherait de garder ceux-ci gratuits.
//!
//! Exigences : R-500, R-501, R-502, R-241, R-1850. Invariants : INV-19.
//! Tests : T-200, T-201, T-007.

mod budget;
mod export;
mod registry;

pub use budget::BudgetMetrics;
pub use export::{to_json, EXPORT_SCHEMA_VERSION};
pub use registry::{
    MetricId, MetricKind, MetricSnapshot, Telemetry, TelemetryBuilder, TelemetryError,
};
