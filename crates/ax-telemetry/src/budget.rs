//! Métriques de budget (R-500, R-241, R-1850, INV-19).

use crate::registry::{MetricId, Telemetry, TelemetryBuilder, TelemetryError};
use ax_model::budgets::Budget;

/// Métriques des budgets du registre.
///
/// R-1850 exige de **chaque** budget une métrique de consommation et une
/// métrique de dépassement. Elles sont déclarées d'un bloc, pour tous les
/// budgets à la fois : les déclarer une par une laisserait la porte ouverte à
/// l'oubli, et un budget sans métrique est précisément ce qu'INV-19 interdit.
///
/// Un budget dont aucun composant ne consomme encore rien rapporte zéro. C'est
/// exact — rien ne l'a consommé — et cela vaut mieux que de taire son
/// existence : la liste des budgets mesurés doit être la liste des budgets.
#[derive(Debug)]
pub struct BudgetMetrics {
    consumed: [MetricId; Budget::ALL.len()],
    overruns: [MetricId; Budget::ALL.len()],
}

impl BudgetMetrics {
    /// Déclare les deux métriques de chaque budget.
    ///
    /// La consommation est une **jauge** : ce qu'un budget par tick a consommé
    /// au dernier tick, ou ce qu'un plafond de mémoire occupe en ce moment. Un
    /// cumul depuis le démarrage ne dirait pas si le budget est tenu.
    ///
    /// Le dépassement est un **compteur** : combien de fois le budget a été
    /// dépassé depuis le démarrage.
    ///
    /// # Errors
    ///
    /// Remonte l'erreur du registre si un nom est déjà pris — signe que les
    /// métriques de budget ont été déclarées deux fois.
    pub fn register(builder: &mut TelemetryBuilder) -> Result<Self, TelemetryError> {
        let mut consumed = Vec::with_capacity(Budget::ALL.len());
        let mut overruns = Vec::with_capacity(Budget::ALL.len());

        for budget in Budget::ALL {
            consumed.push(builder.gauge(&budget.consumed_metric(), budget.unit().as_str())?);
            overruns.push(builder.counter(&budget.overrun_metric(), "count")?);
        }

        Ok(Self {
            consumed: consumed.try_into().expect("un identifiant par budget"),
            overruns: overruns.try_into().expect("un identifiant par budget"),
        })
    }

    /// Métrique de consommation d'un budget.
    #[must_use]
    pub fn consumed_id(&self, budget: Budget) -> MetricId {
        self.consumed[budget.index()]
    }

    /// Métrique de dépassement d'un budget.
    #[must_use]
    pub fn overruns_id(&self, budget: Budget) -> MetricId {
        self.overruns[budget.index()]
    }

    /// Rapporte ce qu'un budget a consommé, dans son unité.
    pub fn report_consumed(&self, telemetry: &Telemetry, budget: Budget, amount: u64) {
        telemetry.set(self.consumed_id(budget), amount);
    }

    /// Rapporte un dépassement.
    pub fn report_overrun(&self, telemetry: &Telemetry, budget: Budget) {
        telemetry.increment(self.overruns_id(budget));
    }

    /// Rapporte une consommation et compte le dépassement si le plafond est
    /// franchi.
    ///
    /// Le plafond vient de la configuration ; `0` signifie qu'il n'y en a pas,
    /// et rien n'est alors compté comme dépassement — comparer à zéro ferait
    /// passer toute consommation pour une faute.
    ///
    /// Rend vrai si le budget est dépassé.
    pub fn report(&self, telemetry: &Telemetry, budget: Budget, amount: u64, limit: u64) -> bool {
        self.report_consumed(telemetry, budget, amount);
        let exceeded = limit > 0 && amount > limit;
        if exceeded {
            self.report_overrun(telemetry, budget);
        }
        exceeded
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn registre() -> (Telemetry, BudgetMetrics) {
        let mut builder = Telemetry::builder();
        let budgets = BudgetMetrics::register(&mut builder).expect("déclaration");
        (builder.build(), budgets)
    }

    #[test]
    fn t007_chaque_budget_a_ses_deux_metriques() {
        let (telemetry, _) = registre();

        for budget in Budget::ALL {
            assert!(
                telemetry.id(&budget.consumed_metric()).is_some(),
                "{budget} sans métrique de consommation"
            );
            assert!(
                telemetry.id(&budget.overrun_metric()).is_some(),
                "{budget} sans métrique de dépassement"
            );
        }
        assert_eq!(telemetry.len(), Budget::ALL.len() * 2);
    }

    #[test]
    fn t007_la_consommation_porte_l_unite_du_budget() {
        let (telemetry, budgets) = registre();

        let temps = telemetry.snapshot(budgets.consumed_id(Budget::SimNsPerTick));
        assert_eq!(temps.unit, "ns");

        let memoire = telemetry.snapshot(budgets.consumed_id(Budget::NativeMemBytes));
        assert_eq!(memoire.unit, "bytes");

        let compte = telemetry.snapshot(budgets.consumed_id(Budget::MaxActiveBodies));
        assert_eq!(compte.unit, "count");

        // Un dépassement se compte, quelle que soit l'unité du budget.
        let depassements = telemetry.snapshot(budgets.overruns_id(Budget::SimNsPerTick));
        assert_eq!(depassements.unit, "count");
    }

    #[test]
    fn t201_un_depassement_est_compte_et_la_consommation_rapportee() {
        let (telemetry, budgets) = registre();

        assert!(!budgets.report(&telemetry, Budget::SimNsPerTick, 2_000_000, 3_000_000));
        assert_eq!(
            telemetry
                .snapshot(budgets.consumed_id(Budget::SimNsPerTick))
                .value,
            2_000_000
        );
        assert_eq!(
            telemetry
                .snapshot(budgets.overruns_id(Budget::SimNsPerTick))
                .value,
            0
        );

        assert!(budgets.report(&telemetry, Budget::SimNsPerTick, 4_000_000, 3_000_000));
        let consomme = telemetry.snapshot(budgets.consumed_id(Budget::SimNsPerTick));
        // La jauge décrit le dernier cycle, pas la somme des cycles : c'est
        // elle qui dit si le budget est tenu maintenant.
        assert_eq!(consomme.value, 4_000_000);
        assert_eq!(
            telemetry
                .snapshot(budgets.overruns_id(Budget::SimNsPerTick))
                .value,
            1
        );
    }

    #[test]
    fn t201_un_plafond_nul_ne_compte_aucun_depassement() {
        let (telemetry, budgets) = registre();

        // Sans plafond, comparer à zéro ferait passer toute consommation pour
        // une faute.
        assert!(!budgets.report(&telemetry, Budget::OcclusionNs, 10_000_000, 0));
        assert_eq!(
            telemetry
                .snapshot(budgets.overruns_id(Budget::OcclusionNs))
                .value,
            0
        );
    }

    #[test]
    fn t201_atteindre_le_plafond_n_est_pas_le_depasser() {
        let (telemetry, budgets) = registre();
        assert!(!budgets.report(&telemetry, Budget::AssetNsPerTick, 1_000, 1_000));
        assert!(budgets.report(&telemetry, Budget::AssetNsPerTick, 1_001, 1_000));
    }

    #[test]
    fn les_metriques_de_budget_ne_se_declarent_pas_deux_fois() {
        let mut builder = Telemetry::builder();
        assert!(BudgetMetrics::register(&mut builder).is_ok());
        assert!(BudgetMetrics::register(&mut builder).is_err());
    }
}
