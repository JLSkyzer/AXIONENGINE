//! Métriques par type de travail (R-474, R-500, INV-19).

use crate::kind::{BudgetKey, JobKind};
use core::sync::atomic::{AtomicU64, Ordering};

/// Compteurs d'un type de travail.
///
/// Les workers les incrémentent depuis plusieurs threads : ils sont atomiques,
/// et lus en `Relaxed` — une métrique n'ordonne rien, elle compte.
#[derive(Debug, Default)]
struct KindCounters {
    submitted: AtomicU64,
    completed: AtomicU64,
    cancelled: AtomicU64,
    panicked: AtomicU64,
    overran: AtomicU64,
    elapsed_nanos: AtomicU64,
}

/// Instantané des compteurs d'un type de travail.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct KindMetrics {
    /// Travaux soumis.
    pub submitted: u64,
    /// Travaux menés à terme.
    pub completed: u64,
    /// Travaux annulés avant ou pendant leur exécution.
    pub cancelled: u64,
    /// Travaux interrompus par une panic, capturée sans traverser le pool.
    pub panicked: u64,
    /// Travaux ayant dépassé leur budget (R-472) : **marqués, jamais tués**.
    pub overran: u64,
    /// Temps cumulé passé dans ce type, en nanosecondes.
    ///
    /// C'est la métrique de consommation du budget qu'exige R-500.
    pub elapsed_nanos: u64,
}

impl KindMetrics {
    /// Travaux terminés d'une façon ou d'une autre.
    #[must_use]
    pub const fn finished(&self) -> u64 {
        self.completed + self.cancelled + self.panicked
    }

    /// Travaux encore en vol.
    #[must_use]
    pub const fn in_flight(&self) -> u64 {
        self.submitted.saturating_sub(self.finished())
    }
}

/// Métriques de tous les types de travaux.
///
/// INV-19 veut qu'aucun sous-système n'existe sans budget déclaré **et mesuré**.
/// Chaque type porte donc ses compteurs, y compris ceux qui partagent un budget
/// de DM-18 : leur consommation reste distinguable.
#[derive(Debug, Default)]
pub struct JobMetrics {
    per_kind: [KindCounters; 8],
}

impl JobMetrics {
    /// Crée des métriques remises à zéro.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    pub(crate) fn record_submitted(&self, kind: JobKind) {
        self.per_kind[kind.index()]
            .submitted
            .fetch_add(1, Ordering::Relaxed);
    }

    pub(crate) fn record_cancelled(&self, kind: JobKind) {
        self.per_kind[kind.index()]
            .cancelled
            .fetch_add(1, Ordering::Relaxed);
    }

    pub(crate) fn record_panicked(&self, kind: JobKind, elapsed_nanos: u64) {
        let counters = &self.per_kind[kind.index()];
        counters.panicked.fetch_add(1, Ordering::Relaxed);
        counters
            .elapsed_nanos
            .fetch_add(elapsed_nanos, Ordering::Relaxed);
    }

    pub(crate) fn record_completed(&self, kind: JobKind, elapsed_nanos: u64, overran: bool) {
        let counters = &self.per_kind[kind.index()];
        counters.completed.fetch_add(1, Ordering::Relaxed);
        counters
            .elapsed_nanos
            .fetch_add(elapsed_nanos, Ordering::Relaxed);
        if overran {
            counters.overran.fetch_add(1, Ordering::Relaxed);
        }
    }

    /// Instantané des compteurs d'un type.
    #[must_use]
    pub fn kind(&self, kind: JobKind) -> KindMetrics {
        let counters = &self.per_kind[kind.index()];
        KindMetrics {
            submitted: counters.submitted.load(Ordering::Relaxed),
            completed: counters.completed.load(Ordering::Relaxed),
            cancelled: counters.cancelled.load(Ordering::Relaxed),
            panicked: counters.panicked.load(Ordering::Relaxed),
            overran: counters.overran.load(Ordering::Relaxed),
            elapsed_nanos: counters.elapsed_nanos.load(Ordering::Relaxed),
        }
    }

    /// Temps cumulé imputé à un budget de DM-18, en nanosecondes.
    ///
    /// Les types qui partagent un budget s'y additionnent : c'est le budget qui
    /// est consommé, pas le type.
    #[must_use]
    pub fn budget_elapsed_nanos(&self, key: BudgetKey) -> u64 {
        JobKind::ALL
            .iter()
            .filter(|kind| kind.budget() == key)
            .map(|kind| self.kind(*kind).elapsed_nanos)
            .sum()
    }

    /// Dépassements imputés à un budget de DM-18.
    ///
    /// C'est la métrique de dépassement qu'exigent R-241 et R-500.
    #[must_use]
    pub fn budget_overruns(&self, key: BudgetKey) -> u64 {
        JobKind::ALL
            .iter()
            .filter(|kind| kind.budget() == key)
            .map(|kind| self.kind(*kind).overran)
            .sum()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn t173_chaque_type_compte_separement() {
        let metrics = JobMetrics::new();

        metrics.record_submitted(JobKind::Physics);
        metrics.record_completed(JobKind::Physics, 500, false);
        metrics.record_submitted(JobKind::Asset);
        metrics.record_cancelled(JobKind::Asset);

        let physics = metrics.kind(JobKind::Physics);
        assert_eq!(physics.completed, 1);
        assert_eq!(physics.elapsed_nanos, 500);
        assert_eq!(physics.cancelled, 0);

        let asset = metrics.kind(JobKind::Asset);
        assert_eq!(asset.cancelled, 1);
        assert_eq!(asset.completed, 0);
        assert_eq!(asset.elapsed_nanos, 0);
    }

    #[test]
    fn t173_les_types_partageant_un_budget_s_y_additionnent() {
        let metrics = JobMetrics::new();

        metrics.record_submitted(JobKind::Anim);
        metrics.record_completed(JobKind::Anim, 1_000, true);
        metrics.record_submitted(JobKind::Cull);
        metrics.record_completed(JobKind::Cull, 2_000, false);

        assert_eq!(metrics.budget_elapsed_nanos(BudgetKey::RenderPrepNs), 3_000);
        assert_eq!(metrics.budget_overruns(BudgetKey::RenderPrepNs), 1);
        // Et restent distinguables l'un de l'autre.
        assert_eq!(metrics.kind(JobKind::Anim).elapsed_nanos, 1_000);
        assert_eq!(metrics.kind(JobKind::Cull).elapsed_nanos, 2_000);
    }

    #[test]
    fn t173_les_travaux_en_vol_se_deduisent_des_compteurs() {
        let metrics = JobMetrics::new();

        metrics.record_submitted(JobKind::Deform);
        metrics.record_submitted(JobKind::Deform);
        assert_eq!(metrics.kind(JobKind::Deform).in_flight(), 2);

        metrics.record_completed(JobKind::Deform, 10, false);
        assert_eq!(metrics.kind(JobKind::Deform).in_flight(), 1);

        metrics.record_panicked(JobKind::Deform, 10);
        assert_eq!(metrics.kind(JobKind::Deform).in_flight(), 0);
        assert_eq!(metrics.kind(JobKind::Deform).panicked, 1);
    }

    #[test]
    fn t173_tous_les_types_sont_instrumentes() {
        // INV-19 : pas de sous-système sans métrique. Un type ajouté sans
        // compteur ferait échouer ce test au premier travail soumis.
        let metrics = JobMetrics::new();
        for kind in JobKind::ALL {
            metrics.record_submitted(kind);
            assert_eq!(metrics.kind(kind).submitted, 1, "{kind} non instrumenté");
        }
    }
}
