//! C-12 — ordonnancement des travaux d'AXION.
//!
//! AXION calcule en parallèle sur un **pool dédié**, jamais sur le pool global
//! de `rayon` (R-470). Ce pool global est partagé par tout le processus : le
//! configurer reviendrait à décider pour les autres mods, et y soumettre son
//! travail reviendrait à attendre derrière le leur.
//!
//! Le système repose sur quatre pièces indépendantes, chacune vérifiable seule :
//!
//! - [`WorkerPolicy`] dimensionne le pool (R-471, PARTIE 27.4) ;
//! - [`JobKind`] et [`JobBudgets`] rattachent chaque travail à un budget du
//!   registre de la PARTIE 25.2, tenu par `ax-model` (R-474) ;
//! - [`Granularity`] découpe les lots en tâches d'environ 100 µs, **mesurées**
//!   et non supposées (R-473) ;
//! - [`JobSystem`] soumet, annule et marque les dépassements (R-472).
//!
//! # Ce que ce crate ne fait pas
//!
//! Il ne lit aucune configuration. Les budgets et le nombre de workers lui sont
//! **donnés** : le registre de `ax-model` est la source unique des valeurs par
//! défaut (R-430), et les recopier ici en créerait une seconde, qui divergerait.
//!
//! Il ne connaît pas non plus le côté sur lequel il tourne. R-471 fait dépendre
//! le plafond de workers du côté — 4 sur un client, 8 sur un serveur — mais
//! `axion_init` ne reçoit aujourd'hui qu'une configuration, où rien ne dit d'où
//! elle vient (IF-01). Transmettre le côté est une modification de contrat, pas
//! une déduction à improviser : [`Side`] est donc un paramètre, et le câblage
//! attend cette décision.
//!
//! # Annulation
//!
//! Elle est coopérative. Un travail consulte [`JobContext::should_yield`] entre
//! deux étapes et rend ce qu'il a. Rien n'est tué en vol : R-472 veut qu'un
//! travail en dépassement soit **marqué**, et son résultat utilisé au cycle
//! suivant — un thread interrompu au milieu d'un calcul laisserait un état
//! partiel que personne ne saurait réparer.
//!
//! Exigences : R-470, R-471, R-472, R-473, R-474, R-2060, R-2062.
//! Invariants : INV-05, INV-19. Tests : T-170..T-173.

mod granularity;
mod kind;
mod metrics;
mod system;
mod workers;

pub use granularity::{Granularity, TARGET_TASK_NANOS};
pub use kind::{JobBudgets, JobKind};
pub use metrics::{JobMetrics, KindMetrics};
pub use system::{CancelToken, JobContext, JobError, JobHandle, JobOutcome, JobResult, JobSystem};
pub use workers::{CpuShare, Side, WorkerPolicy, RESERVED_CORES};
