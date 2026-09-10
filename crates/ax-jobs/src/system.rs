//! Pool dédié, soumission, annulation et deadlines (R-470, R-472).

use crate::kind::{JobBudgets, JobKind};
use crate::metrics::JobMetrics;
use crate::workers::WorkerPolicy;
use core::fmt;
use core::sync::atomic::{AtomicBool, Ordering};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

/// Échec de création du système de jobs.
#[derive(Debug)]
pub enum JobError {
    /// Le pool de threads n'a pas pu être créé.
    PoolUnavailable(String),
}

impl fmt::Display for JobError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            JobError::PoolUnavailable(detail) => {
                write!(formatter, "pool de jobs indisponible : {detail}")
            }
        }
    }
}

impl std::error::Error for JobError {}

/// Jeton d'annulation partagé entre le demandeur et le travail.
///
/// L'annulation est **coopérative** : un travail déjà commencé n'est jamais
/// interrompu de force. R-472 l'exige — un travail en dépassement est marqué,
/// jamais tué — et tuer un thread au milieu d'un calcul laisserait des états
/// partiels que rien ne saurait réparer.
#[derive(Debug, Clone, Default)]
pub struct CancelToken {
    flag: Arc<AtomicBool>,
}

impl CancelToken {
    /// Crée un jeton non annulé.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Demande l'annulation.
    pub fn cancel(&self) {
        self.flag.store(true, Ordering::Relaxed);
    }

    /// Indique si l'annulation a été demandée.
    #[must_use]
    pub fn is_cancelled(&self) -> bool {
        self.flag.load(Ordering::Relaxed)
    }
}

/// Ce qu'un travail voit de son propre ordonnancement.
///
/// Un travail long consulte ce contexte entre deux étapes : c'est ainsi qu'il
/// devient annulable et qu'il sait rendre un résultat partiel plutôt que de
/// dépasser son budget.
#[derive(Debug)]
pub struct JobContext {
    kind: JobKind,
    cancel: CancelToken,
    deadline: Option<Instant>,
}

impl JobContext {
    /// Type du travail en cours.
    #[must_use]
    pub const fn kind(&self) -> JobKind {
        self.kind
    }

    /// Indique si l'annulation a été demandée.
    #[must_use]
    pub fn is_cancelled(&self) -> bool {
        self.cancel.is_cancelled()
    }

    /// Indique si le budget du travail est déjà consommé.
    #[must_use]
    pub fn deadline_exceeded(&self) -> bool {
        self.deadline
            .is_some_and(|deadline| Instant::now() >= deadline)
    }

    /// Temps restant avant la deadline, s'il y en a une.
    #[must_use]
    pub fn remaining(&self) -> Option<Duration> {
        self.deadline
            .map(|deadline| deadline.saturating_duration_since(Instant::now()))
    }

    /// Indique si le travail devrait s'arrêter là.
    ///
    /// Un travail qui interroge cette seule méthode se comporte correctement
    /// dans les deux cas : annulation demandée, ou budget épuisé.
    #[must_use]
    pub fn should_yield(&self) -> bool {
        self.is_cancelled() || self.deadline_exceeded()
    }
}

/// Issue d'un travail.
#[derive(Debug, PartialEq, Eq)]
pub enum JobOutcome<T> {
    /// Le travail est allé au bout et rend sa valeur.
    Done(T),
    /// Le travail a été annulé avant d'avoir commencé.
    Cancelled,
    /// Le travail a paniqué ; la panic a été capturée sans traverser le pool.
    Panicked,
}

/// Résultat complet d'un travail : son issue et ce qu'elle a coûté.
#[derive(Debug, PartialEq, Eq)]
pub struct JobResult<T> {
    /// Issue du travail.
    pub outcome: JobOutcome<T>,
    /// Durée réellement mesurée, en nanosecondes.
    pub elapsed_nanos: u64,
    /// Le budget du type a été dépassé (R-472) : marqué, jamais tué.
    pub overran: bool,
}

impl<T> JobResult<T> {
    /// Rend la valeur produite, s'il y en a une.
    pub fn value(self) -> Option<T> {
        match self.outcome {
            JobOutcome::Done(value) => Some(value),
            JobOutcome::Cancelled | JobOutcome::Panicked => None,
        }
    }
}

/// Emplacement partagé où le worker dépose le résultat.
#[derive(Debug)]
struct Slot<T> {
    result: Mutex<Option<JobResult<T>>>,
    ready: Condvar,
}

/// Poignée sur un travail soumis.
///
/// Elle survit au cycle qui l'a créée : R-472 veut que le résultat d'un travail
/// en dépassement soit **utilisé au cycle suivant**, pas jeté. Interroger la
/// poignée ne bloque jamais.
#[derive(Debug)]
pub struct JobHandle<T> {
    slot: Arc<Slot<T>>,
    cancel: CancelToken,
    kind: JobKind,
}

impl<T> JobHandle<T> {
    /// Type du travail.
    #[must_use]
    pub const fn kind(&self) -> JobKind {
        self.kind
    }

    /// Demande l'annulation du travail.
    pub fn cancel(&self) {
        self.cancel.cancel();
    }

    /// Reprend le résultat s'il est disponible, sans jamais attendre.
    ///
    /// Rend `None` tant que le travail n'a pas fini : l'appelant réessaiera au
    /// cycle suivant. Une fois repris, le résultat n'est plus dans la poignée.
    pub fn poll(&self) -> Option<JobResult<T>> {
        self.slot
            .result
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .take()
    }

    /// Attend le résultat, au plus `timeout`.
    ///
    /// **À n'employer ni dans un tick ni dans une frame** : bloquer le thread
    /// autoritatif sur un travail parallèle annule tout l'intérêt du pool.
    /// C'est l'outil des arrêts — vider ce qui est en vol avant de fermer — et
    /// des tests, qui ont besoin d'une fin déterministe.
    pub fn wait(&self, timeout: Duration) -> Option<JobResult<T>> {
        let deadline = Instant::now() + timeout;
        let mut result = self
            .slot
            .result
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);

        loop {
            if let Some(value) = result.take() {
                return Some(value);
            }
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return None;
            }
            let (guard, _) = self
                .slot
                .ready
                .wait_timeout(result, remaining)
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            result = guard;
        }
    }
}

/// Système de jobs d'AXION (C-12).
///
/// Le pool est **dédié** : R-470 interdit `ThreadPoolBuilder::build_global`,
/// qui installerait le pool global de `rayon`. Ce pool est partagé par tout le
/// processus, donc par les autres mods qui utilisent `rayon` ; AXION ne
/// s'autorise pas à le configurer pour eux, et ne veut pas voir ses propres
/// travaux mis en file derrière les leurs.
#[derive(Debug)]
pub struct JobSystem {
    pool: rayon::ThreadPool,
    budgets: JobBudgets,
    metrics: Arc<JobMetrics>,
}

impl JobSystem {
    /// Crée le pool dédié.
    ///
    /// # Errors
    ///
    /// Rend [`JobError::PoolUnavailable`] si le système refuse de créer les
    /// threads. L'appelant bascule alors en mode dégradé : R-2062 interdit de
    /// retirer une fonctionnalité faute de parallélisme.
    pub fn new(policy: &WorkerPolicy, budgets: JobBudgets) -> Result<Self, JobError> {
        let metrics = Arc::new(JobMetrics::new());
        let pool = rayon::ThreadPoolBuilder::new()
            .num_threads(policy.workers())
            .thread_name(|index| format!("axion-job-{index}"))
            // Une panic ne doit pas traverser le pool : chaque travail est déjà
            // enveloppé, ce gestionnaire n'est que le dernier filet.
            .panic_handler(|_| ())
            .build()
            .map_err(|error| JobError::PoolUnavailable(error.to_string()))?;

        Ok(Self {
            pool,
            budgets,
            metrics,
        })
    }

    /// Nombre de workers du pool.
    #[must_use]
    pub fn workers(&self) -> usize {
        self.pool.current_num_threads()
    }

    /// Budgets en vigueur.
    #[must_use]
    pub const fn budgets(&self) -> &JobBudgets {
        &self.budgets
    }

    /// Métriques par type de travail.
    #[must_use]
    pub fn metrics(&self) -> &JobMetrics {
        &self.metrics
    }

    /// Soumet un travail au pool.
    ///
    /// Le budget du type fixe la deadline. Un travail qui la dépasse est
    /// **marqué et laissé finir** (R-472) : son résultat reste valide et sera
    /// repris au cycle suivant.
    pub fn submit<T, F>(&self, kind: JobKind, work: F) -> JobHandle<T>
    where
        F: FnOnce(&JobContext) -> T + Send + 'static,
        T: Send + 'static,
    {
        self.submit_with(kind, CancelToken::new(), work)
    }

    /// Soumet un travail avec un jeton d'annulation existant.
    ///
    /// Un même jeton peut couvrir plusieurs travaux : annuler une passe de
    /// simulation annule alors tout ce qu'elle a semé, d'un seul geste.
    pub fn submit_with<T, F>(&self, kind: JobKind, cancel: CancelToken, work: F) -> JobHandle<T>
    where
        F: FnOnce(&JobContext) -> T + Send + 'static,
        T: Send + 'static,
    {
        self.metrics.record_submitted(kind);

        let slot = Arc::new(Slot {
            result: Mutex::new(None),
            ready: Condvar::new(),
        });

        let budget_nanos = self.budgets.for_kind(kind);
        let metrics = Arc::clone(&self.metrics);
        let worker_slot = Arc::clone(&slot);
        let worker_cancel = cancel.clone();

        self.pool.spawn(move || {
            // La deadline part du moment où le travail commence réellement :
            // le temps passé en file n'est pas du temps de calcul, et
            // l'imputer au budget du type ferait passer pour un dépassement ce
            // qui n'est qu'une attente.
            let deadline =
                (budget_nanos > 0).then(|| Instant::now() + Duration::from_nanos(budget_nanos));

            let context = JobContext {
                kind,
                cancel: worker_cancel,
                deadline,
            };

            let result = if context.is_cancelled() {
                // Annulé avant d'avoir commencé : rien n'a été calculé, rien
                // n'est imputé au budget.
                metrics.record_cancelled(kind);
                JobResult {
                    outcome: JobOutcome::Cancelled,
                    elapsed_nanos: 0,
                    overran: false,
                }
            } else {
                let started = Instant::now();
                let produced = catch_unwind(AssertUnwindSafe(|| work(&context)));
                let elapsed_nanos = u64::try_from(started.elapsed().as_nanos()).unwrap_or(u64::MAX);
                let overran = budget_nanos > 0 && elapsed_nanos > budget_nanos;

                match produced {
                    Ok(value) => {
                        metrics.record_completed(kind, elapsed_nanos, overran);
                        JobResult {
                            outcome: JobOutcome::Done(value),
                            elapsed_nanos,
                            overran,
                        }
                    }
                    Err(_) => {
                        // INV-05 : une panic ne traverse aucune frontière. Elle
                        // est comptée et le travail rendu comme raté, le pool
                        // et le jeu restant intacts.
                        metrics.record_panicked(kind, elapsed_nanos);
                        JobResult {
                            outcome: JobOutcome::Panicked,
                            elapsed_nanos,
                            overran,
                        }
                    }
                }
            };

            let mut guard = worker_slot
                .result
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            *guard = Some(result);
            drop(guard);
            worker_slot.ready.notify_all();
        });

        JobHandle { slot, cancel, kind }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::kind::BudgetKey;
    use crate::workers::{CpuShare, Side};

    const SECONDE: Duration = Duration::from_secs(5);

    fn budgets(nanos: u64) -> JobBudgets {
        JobBudgets::new(nanos, nanos, nanos, nanos, nanos, nanos, nanos)
    }

    fn system(workers: u32, budget_nanos: u64) -> JobSystem {
        let policy = WorkerPolicy {
            cores: 8,
            side: Side::Server,
            max_workers: workers,
            cpu_share: CpuShare::Fixed(workers),
            third_party_present: false,
        };
        JobSystem::new(&policy, budgets(budget_nanos)).expect("pool de test")
    }

    #[test]
    fn t171_un_travail_rend_sa_valeur() {
        let jobs = system(2, 1_000_000_000);
        let handle = jobs.submit(JobKind::Physics, |_| 6 * 7);

        let result = handle.wait(SECONDE).expect("résultat non rendu");
        assert_eq!(result.outcome, JobOutcome::Done(42));
        assert!(!result.overran);
        assert_eq!(jobs.metrics().kind(JobKind::Physics).completed, 1);
    }

    #[test]
    fn t171_un_travail_annule_avant_son_depart_ne_s_execute_pas() {
        let jobs = system(1, 1_000_000_000);
        let cancel = CancelToken::new();
        cancel.cancel();

        let handle = jobs.submit_with(JobKind::Asset, cancel, |_| {
            panic!("un travail annulé ne doit pas s'exécuter");
        });

        let result = handle.wait(SECONDE).expect("résultat non rendu");
        assert_eq!(result.outcome, JobOutcome::Cancelled);
        assert_eq!(
            result.elapsed_nanos, 0,
            "temps imputé à un travail non exécuté"
        );
        assert_eq!(jobs.metrics().kind(JobKind::Asset).cancelled, 1);
        assert_eq!(jobs.metrics().kind(JobKind::Asset).completed, 0);
    }

    #[test]
    fn t171_un_travail_en_cours_observe_son_annulation() {
        let jobs = system(2, 1_000_000_000);
        let cancel = CancelToken::new();

        // Le travail signale son depart, puis avance par etapes en consultant
        // son contexte entre chacune : c'est la seule forme d'annulation qui ne
        // laisse pas d'etat partiel. Sans ce signal, l'annulation pourrait
        // arriver avant que le worker ne demarre, et le test verifierait le
        // chemin voisin sans le dire.
        let started = Arc::new(AtomicBool::new(false));
        let started_worker = Arc::clone(&started);
        let handle = jobs.submit_with(JobKind::Deform, cancel.clone(), move |context| {
            started_worker.store(true, Ordering::Relaxed);
            let abandon = Instant::now() + SECONDE;
            while !context.should_yield() && Instant::now() < abandon {
                std::thread::yield_now();
            }
            context.is_cancelled()
        });

        while !started.load(Ordering::Relaxed) {
            std::thread::yield_now();
        }
        cancel.cancel();
        let result = handle.wait(SECONDE).expect("résultat non rendu");

        assert_eq!(
            result.outcome,
            JobOutcome::Done(true),
            "un travail déjà commencé n'a pas vu son annulation"
        );
        assert_eq!(jobs.metrics().kind(JobKind::Deform).completed, 1);
    }

    #[test]
    fn t171_un_depassement_est_marque_jamais_tue() {
        // Budget d'une microseconde : le travail le dépasse forcément.
        let jobs = system(1, 1_000);
        let handle = jobs.submit(JobKind::Particles, |context| {
            while !context.deadline_exceeded() {
                std::thread::yield_now();
            }
            // Le travail continue après sa deadline : R-472 veut qu'il finisse.
            std::thread::sleep(Duration::from_millis(2));
            "terminé"
        });

        let result = handle.wait(SECONDE).expect("résultat non rendu");
        assert_eq!(result.outcome, JobOutcome::Done("terminé"));
        assert!(result.overran, "dépassement non marqué");
        assert!(result.elapsed_nanos > 1_000);

        let metrics = jobs.metrics().kind(JobKind::Particles);
        assert_eq!(metrics.overran, 1);
        assert_eq!(
            metrics.completed, 1,
            "un travail en dépassement doit compter comme terminé"
        );
        assert_eq!(
            jobs.metrics()
                .budget_overruns(BudgetKey::ParticlesNsPerTick),
            1
        );
    }

    #[test]
    fn t171_un_budget_nul_vaut_absence_de_deadline() {
        let jobs = system(1, 0);
        let handle = jobs.submit(JobKind::Anim, |context| {
            assert!(!context.deadline_exceeded());
            assert!(context.remaining().is_none());
            0_u8
        });

        let result = handle.wait(SECONDE).expect("résultat non rendu");
        assert!(!result.overran);
    }

    #[test]
    fn t171_le_resultat_reste_disponible_au_cycle_suivant() {
        let jobs = system(1, 1_000_000_000);
        let handle = jobs.submit(JobKind::Cull, |_| 7_u32);

        // Le cycle courant interroge trop tôt : ce n'est pas une erreur.
        let mut cycles = 0;
        loop {
            if let Some(result) = handle.poll() {
                assert_eq!(result.outcome, JobOutcome::Done(7));
                break;
            }
            cycles += 1;
            assert!(cycles < 100_000, "résultat jamais rendu");
            std::thread::yield_now();
        }

        // Repris une fois, il ne l'est pas deux : la poignée ne duplique rien.
        assert!(handle.poll().is_none());
    }

    #[test]
    fn t171_une_panic_ne_traverse_pas_le_pool() {
        let jobs = system(1, 1_000_000_000);
        let fautif: JobHandle<()> = jobs.submit(JobKind::Damage, |_| {
            panic!("travail fautif");
        });

        let result = fautif.wait(SECONDE).expect("résultat non rendu");
        assert_eq!(result.outcome, JobOutcome::Panicked);
        assert_eq!(jobs.metrics().kind(JobKind::Damage).panicked, 1);

        // Le pool survit : le travail suivant s'exécute normalement.
        let suivant = jobs.submit(JobKind::Damage, |_| 1_u8);
        assert_eq!(
            suivant.wait(SECONDE).expect("résultat non rendu").outcome,
            JobOutcome::Done(1)
        );
    }

    #[test]
    fn t170_le_pool_a_la_taille_que_la_politique_donne() {
        let jobs = system(3, 1_000_000_000);
        assert_eq!(jobs.workers(), 3);
    }

    #[test]
    fn t173_chaque_type_est_soumis_avec_son_budget() {
        let budgets = JobBudgets::new(1, 2, 3, 4, 5, 6, 7);
        let policy = WorkerPolicy {
            cores: 4,
            side: Side::Server,
            max_workers: 1,
            cpu_share: CpuShare::Fixed(1),
            third_party_present: false,
        };
        let jobs = JobSystem::new(&policy, budgets).expect("pool de test");

        assert_eq!(jobs.budgets().for_kind(JobKind::Physics), 1);
        assert_eq!(jobs.budgets().for_kind(JobKind::Asset), 7);

        for kind in JobKind::ALL {
            let handle = jobs.submit(kind, |context| context.kind());
            let result = handle.wait(SECONDE).expect("résultat non rendu");
            assert_eq!(result.outcome, JobOutcome::Done(kind));
        }
    }

    #[test]
    fn t171_le_temps_en_file_n_est_pas_impute_au_budget() {
        // Un seul worker et un premier travail qui l'occupe : le second attend
        // en file bien plus longtemps que son budget, sans le dépasser.
        let jobs = system(1, 50_000_000);
        let bloquant = jobs.submit(JobKind::Physics, |_| {
            std::thread::sleep(Duration::from_millis(120));
        });
        let en_file = jobs.submit(JobKind::Physics, |_| 1_u8);

        bloquant.wait(SECONDE).expect("résultat non rendu");
        let result = en_file.wait(SECONDE).expect("résultat non rendu");

        assert_eq!(result.outcome, JobOutcome::Done(1));
        assert!(
            !result.overran,
            "l'attente en file a été imputée au budget : {} ns",
            result.elapsed_nanos
        );
    }
}
