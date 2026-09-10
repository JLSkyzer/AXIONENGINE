//! Types de travaux et budgets associés (R-474).

use ax_model::budgets::Budget;
use core::fmt;

/// Nature d'un travail soumis au système de jobs (R-474).
///
/// La liste est celle du cahier des charges, ni plus ni moins : un type de plus
/// serait un sous-système sans budget déclaré, ce qu'INV-19 interdit.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
#[repr(u8)]
pub enum JobKind {
    /// Simulation physique.
    Physics = 0,
    /// Chaîne de dommage.
    Damage = 1,
    /// Déformation continue.
    Deform = 2,
    /// Solveur de particules.
    Particles = 3,
    /// Animation et skinning.
    Anim = 4,
    /// Culling et niveaux de détail.
    Cull = 5,
    /// Occlusion culling logicielle.
    Occlusion = 6,
    /// Chargement et compilation d'assets.
    Asset = 7,
}

impl JobKind {
    /// Tous les types, dans l'ordre de leur discriminant.
    pub const ALL: [JobKind; 8] = [
        JobKind::Physics,
        JobKind::Damage,
        JobKind::Deform,
        JobKind::Particles,
        JobKind::Anim,
        JobKind::Cull,
        JobKind::Occlusion,
        JobKind::Asset,
    ];

    /// Nom du type tel que le cahier des charges l'écrit.
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            JobKind::Physics => "PHYSICS",
            JobKind::Damage => "DAMAGE",
            JobKind::Deform => "DEFORM",
            JobKind::Particles => "PARTICLES",
            JobKind::Anim => "ANIM",
            JobKind::Cull => "CULL",
            JobKind::Occlusion => "OCCLUSION",
            JobKind::Asset => "ASSET",
        }
    }

    /// Position du type dans les tables indexées par type.
    #[must_use]
    pub const fn index(self) -> usize {
        self as usize
    }

    /// Budget du registre auquel le temps de ce type s'impute.
    ///
    /// `ANIM` et `CULL` s'imputent tous deux sur `budgets.render_prep_ns` : le
    /// registre ne déclare pas de budget d'animation, et en inventer un
    /// reviendrait à ajouter un budget que le cahier des charges ne connaît
    /// pas. Les deux types gardent en revanche leurs métriques propres, si bien
    /// que leur consommation reste distinguable dans le budget qu'ils partagent.
    #[must_use]
    pub const fn budget(self) -> Budget {
        match self {
            JobKind::Physics => Budget::SimNsPerTick,
            JobKind::Damage => Budget::DamageNsPerTick,
            JobKind::Deform => Budget::DeformationNsPerTick,
            JobKind::Particles => Budget::ParticlesNsPerTick,
            JobKind::Anim | JobKind::Cull => Budget::RenderPrepNs,
            JobKind::Occlusion => Budget::OcclusionNs,
            JobKind::Asset => Budget::AssetNsPerTick,
        }
    }
}

impl fmt::Display for JobKind {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.name())
    }
}

/// Budgets temporels en vigueur, en nanosecondes.
///
/// Il n'y a **pas** de `Default` porteur de valeurs : les défauts vivent dans le
/// registre de configuration (`ax-model`), source unique documentée par
/// `CONFIGURATION.md` (R-430). Les recopier ici en créerait une seconde, qui
/// divergerait à la première modification. Un budget non renseigné vaut zéro,
/// c'est-à-dire **aucune deadline** : le travail n'est jamais marqué en
/// dépassement, faute de quoi le comparer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct JobBudgets {
    nanos: [u64; Budget::ALL.len()],
}

impl Default for JobBudgets {
    fn default() -> Self {
        Self::new()
    }
}

impl JobBudgets {
    /// Crée des budgets tous nuls, donc sans deadline.
    #[must_use]
    pub const fn new() -> Self {
        Self {
            nanos: [0; Budget::ALL.len()],
        }
    }

    /// Renseigne un budget, en nanosecondes.
    ///
    /// # Panics
    ///
    /// Si le budget ne borne pas une durée : un plafond de mémoire ou de
    /// dénombrement ne fixe pas de deadline, et l'employer comme tel
    /// marquerait des dépassements qui n'ont pas de sens.
    pub fn set(&mut self, budget: Budget, nanos: u64) {
        assert!(
            budget.is_duration(),
            "{budget} ne borne pas une durée : il ne peut pas fixer de deadline"
        );
        self.nanos[budget.index()] = nanos;
    }

    /// Renseigne un budget et rend la valeur modifiée, pour l'écriture en
    /// chaîne.
    #[must_use]
    pub fn with(mut self, budget: Budget, nanos: u64) -> Self {
        self.set(budget, nanos);
        self
    }

    /// Budget d'un travail de ce type, en nanosecondes.
    #[must_use]
    pub fn for_kind(&self, kind: JobKind) -> u64 {
        self.nanos[kind.budget().index()]
    }

    /// Budget désigné, en nanosecondes.
    #[must_use]
    pub fn get(&self, budget: Budget) -> u64 {
        self.nanos[budget.index()]
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tout_type_porte_un_budget_de_duree() {
        // R-474 : aucun type ne peut exister sans budget, sous peine d'être un
        // sous-système sans budget déclaré (INV-19). Et ce budget doit borner
        // une durée : un travail se mesure en temps.
        for kind in JobKind::ALL {
            assert!(!kind.name().is_empty());
            assert!(kind.budget().is_duration(), "{kind} : budget hors durée");
        }
    }

    #[test]
    fn les_index_suivent_les_discriminants() {
        for (position, kind) in JobKind::ALL.iter().enumerate() {
            assert_eq!(position, kind.index(), "{kind} mal indexé");
        }
    }

    #[test]
    fn les_budgets_se_lisent_par_type() {
        let budgets = JobBudgets::new()
            .with(Budget::SimNsPerTick, 3_000_000)
            .with(Budget::OcclusionNs, 800_000)
            .with(Budget::RenderPrepNs, 2_000_000);

        assert_eq!(budgets.for_kind(JobKind::Physics), 3_000_000);
        assert_eq!(budgets.for_kind(JobKind::Occlusion), 800_000);
        // ANIM et CULL partagent render_prep_ns, faute de budget d'animation
        // au registre.
        assert_eq!(budgets.for_kind(JobKind::Anim), 2_000_000);
        assert_eq!(budgets.for_kind(JobKind::Cull), 2_000_000);
        // Un budget non renseigné vaut zéro : aucune deadline.
        assert_eq!(budgets.for_kind(JobKind::Asset), 0);
    }

    #[test]
    #[should_panic(expected = "ne borne pas une durée")]
    fn un_plafond_de_memoire_ne_fixe_pas_de_deadline() {
        let _ = JobBudgets::new().with(Budget::NativeMemBytes, 1_000);
    }
}
