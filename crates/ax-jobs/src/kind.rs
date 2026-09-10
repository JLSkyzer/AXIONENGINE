//! Types de travaux et budgets associés (R-474).

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

    /// Budget de DM-18 auquel le temps de ce type s'impute.
    ///
    /// `ANIM` et `CULL` s'imputent tous deux sur `render_prep_ns` : DM-18 ne
    /// déclare pas de budget d'animation, et la structure `Budgets` traverse la
    /// frontière en `repr(C)` — lui ajouter un champ serait modifier un modèle
    /// de données, ce qui ne se décide pas ici. Les deux types gardent en
    /// revanche leurs métriques propres, si bien que leur consommation reste
    /// distinguable dans le budget qu'ils partagent.
    #[must_use]
    pub const fn budget(self) -> BudgetKey {
        match self {
            JobKind::Physics => BudgetKey::SimNsPerTick,
            JobKind::Damage => BudgetKey::DamageNsPerTick,
            JobKind::Deform => BudgetKey::DeformationNsPerTick,
            JobKind::Particles => BudgetKey::ParticlesNsPerTick,
            JobKind::Anim | JobKind::Cull => BudgetKey::RenderPrepNs,
            JobKind::Occlusion => BudgetKey::OcclusionNs,
            JobKind::Asset => BudgetKey::AssetNsPerTick,
        }
    }
}

impl fmt::Display for JobKind {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.name())
    }
}

/// Budget temporel de DM-18 sur lequel un travail s'impute.
///
/// Seuls les budgets de durée y figurent : les plafonds de mémoire et de
/// dénombrement de DM-18 ne bornent pas un travail, ils bornent un état.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
#[repr(u8)]
pub enum BudgetKey {
    /// `budgets.sim_ns_per_tick`.
    SimNsPerTick = 0,
    /// `budgets.damage_ns_per_tick`.
    DamageNsPerTick = 1,
    /// `budgets.deformation_ns_per_tick`.
    DeformationNsPerTick = 2,
    /// `budgets.particles_ns_per_tick`.
    ParticlesNsPerTick = 3,
    /// `budgets.render_prep_ns`.
    RenderPrepNs = 4,
    /// `budgets.occlusion_ns`.
    OcclusionNs = 5,
    /// `budgets.asset_ns_per_tick`.
    AssetNsPerTick = 6,
}

impl BudgetKey {
    /// Tous les budgets temporels, dans l'ordre de leur discriminant.
    pub const ALL: [BudgetKey; 7] = [
        BudgetKey::SimNsPerTick,
        BudgetKey::DamageNsPerTick,
        BudgetKey::DeformationNsPerTick,
        BudgetKey::ParticlesNsPerTick,
        BudgetKey::RenderPrepNs,
        BudgetKey::OcclusionNs,
        BudgetKey::AssetNsPerTick,
    ];

    /// Chemin de l'option de configuration qui porte ce budget.
    #[must_use]
    pub const fn config_path(self) -> &'static str {
        match self {
            BudgetKey::SimNsPerTick => "budgets.sim_ns_per_tick",
            BudgetKey::DamageNsPerTick => "budgets.damage_ns_per_tick",
            BudgetKey::DeformationNsPerTick => "budgets.deformation_ns_per_tick",
            BudgetKey::ParticlesNsPerTick => "budgets.particles_ns_per_tick",
            BudgetKey::RenderPrepNs => "budgets.render_prep_ns",
            BudgetKey::OcclusionNs => "budgets.occlusion_ns",
            BudgetKey::AssetNsPerTick => "budgets.asset_ns_per_tick",
        }
    }

    /// Position du budget dans les tables indexées par budget.
    #[must_use]
    pub const fn index(self) -> usize {
        self as usize
    }
}

/// Budgets temporels en vigueur, en nanosecondes.
///
/// Il n'y a **pas** de `Default` : les valeurs par défaut vivent dans le
/// registre de configuration (`ax-model`), source unique documentée par
/// `CONFIGURATION.md` (R-430). Les recopier ici en créerait une seconde, qui
/// divergerait à la première modification.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct JobBudgets {
    nanos: [u64; 7],
}

impl JobBudgets {
    /// Construit les budgets depuis les valeurs de configuration résolues.
    ///
    /// L'ordre des paramètres est celui de la structure `Budgets` de DM-18.
    #[must_use]
    pub const fn new(
        sim_ns_per_tick: u64,
        damage_ns_per_tick: u64,
        deformation_ns_per_tick: u64,
        particles_ns_per_tick: u64,
        render_prep_ns: u64,
        occlusion_ns: u64,
        asset_ns_per_tick: u64,
    ) -> Self {
        Self {
            nanos: [
                sim_ns_per_tick,
                damage_ns_per_tick,
                deformation_ns_per_tick,
                particles_ns_per_tick,
                render_prep_ns,
                occlusion_ns,
                asset_ns_per_tick,
            ],
        }
    }

    /// Budget d'un travail de ce type, en nanosecondes.
    #[must_use]
    pub const fn for_kind(&self, kind: JobKind) -> u64 {
        self.nanos[kind.budget().index()]
    }

    /// Budget désigné, en nanosecondes.
    #[must_use]
    pub const fn get(&self, key: BudgetKey) -> u64 {
        self.nanos[key.index()]
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tout_type_porte_un_budget_et_un_nom() {
        // R-474 : aucun type ne peut exister sans budget, sous peine d'être un
        // sous-système sans budget déclaré (INV-19).
        for kind in JobKind::ALL {
            assert!(!kind.name().is_empty());
            assert!(BudgetKey::ALL.contains(&kind.budget()));
        }
    }

    #[test]
    fn les_index_suivent_les_discriminants() {
        for (position, kind) in JobKind::ALL.iter().enumerate() {
            assert_eq!(position, kind.index(), "{kind} mal indexé");
        }
        for (position, key) in BudgetKey::ALL.iter().enumerate() {
            assert_eq!(position, key.index(), "{key:?} mal indexé");
        }
    }

    #[test]
    fn chaque_budget_designe_une_option_de_configuration() {
        // Le chemin doit être celui du registre : une faute de frappe ferait
        // lire un budget qui n'existe pas.
        for key in BudgetKey::ALL {
            assert!(key.config_path().starts_with("budgets."), "{key:?}");
        }
    }

    #[test]
    fn les_budgets_se_lisent_par_type() {
        let budgets = JobBudgets::new(
            3_000_000, 1_000_000, 1_500_000, 1_000_000, 2_000_000, 800_000, 1_000_000,
        );

        assert_eq!(budgets.for_kind(JobKind::Physics), 3_000_000);
        assert_eq!(budgets.for_kind(JobKind::Occlusion), 800_000);
        // ANIM et CULL partagent render_prep_ns, faute de budget d'animation
        // dans DM-18.
        assert_eq!(budgets.for_kind(JobKind::Anim), 2_000_000);
        assert_eq!(budgets.for_kind(JobKind::Cull), 2_000_000);
    }
}
