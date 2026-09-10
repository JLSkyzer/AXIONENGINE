//! Registre des budgets (PARTIE 25.2, INV-19).
//!
//! R-1850 le dit en une phrase : « tout sous-système possède un budget déclaré,
//! une métrique de consommation, une métrique de dépassement, un comportement
//! en surcharge et un fallback ». Ce module est le registre en question, et
//! T-007 le compare au registre de configuration.
//!
//! # Ce que le registre n'est pas
//!
//! Ce n'est **pas** la structure `Budgets` de DM-18. Celle-ci en porte dix-sept
//! sur vingt : `budgets.idle_hook_ns` et `budgets.submit_ns` sont déclarés par
//! les fiches de composants, et `net.max_bytes_per_second_per_player` par les
//! budgets réseau. Prendre `Budgets` pour le registre laissait trois budgets
//! hors audit — c'est ce qu'a montré la première exécution de T-007.
//!
//! Ce module ne déclare que les **identifiants** : chemin de configuration,
//! unité, noms de métriques. Ni valeurs — elles vivent dans [`crate::config`],
//! source unique — ni disposition mémoire : figer un `repr(C)` avant que la
//! première fonction ne l'emploie reviendrait à décider trop tôt de ce qu'on ne
//! pourra plus changer.

use core::fmt;

/// Ce que compte un budget.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum BudgetUnit {
    /// Un temps, en nanosecondes.
    Nanoseconds,
    /// Une quantité de mémoire, en octets.
    Bytes,
    /// Un débit, en octets par seconde.
    BytesPerSecond,
    /// Un dénombrement.
    Count,
}

impl BudgetUnit {
    /// Nom de l'unité, tel que l'export de télémétrie l'écrit.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            BudgetUnit::Nanoseconds => "ns",
            BudgetUnit::Bytes => "bytes",
            BudgetUnit::BytesPerSecond => "bytes_per_second",
            BudgetUnit::Count => "count",
        }
    }

    /// Indique si le budget borne une durée.
    ///
    /// Ce sont les seuls qui bornent un **travail**, donc les seuls qui se
    /// prêtent à une deadline ; les autres bornent un état.
    #[must_use]
    pub const fn is_duration(self) -> bool {
        matches!(self, BudgetUnit::Nanoseconds)
    }
}

/// Budget déclaré par le cahier des charges.
///
/// L'ordre est celui de la table 25.2, pour que la comparaison avec elle reste
/// immédiate.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum Budget {
    /// Simulation physique, par tick.
    SimNsPerTick,
    /// Chaîne de dommage, par tick.
    DamageNsPerTick,
    /// Déformation, par tick.
    DeformationNsPerTick,
    /// Solveur de particules, par tick.
    ParticlesNsPerTick,
    /// Préparation du rendu, par frame.
    RenderPrepNs,
    /// Occlusion culling logicielle, par frame.
    OcclusionNs,
    /// Chargement et compilation d'assets, par tick.
    AssetNsPerTick,
    /// Surcoût des hooks Forge à vide, par tick (C-01).
    IdleHookNs,
    /// Soumission des commandes à la frontière (R-280).
    SubmitNs,
    /// Mémoire native.
    NativeMemBytes,
    /// Mémoire des champs de déformation.
    DeformMemBytes,
    /// Mémoire GPU gérée par AXION.
    GpuMemBytes,
    /// Corps physiques actifs.
    MaxActiveBodies,
    /// Assemblies portant un champ de déformation.
    MaxDeformedAssemblies,
    /// Réajustements de collider par tick.
    MaxColliderRefitsPerTick,
    /// Instances rendues par frame.
    MaxVisibleInstances,
    /// Triangles rendus par frame.
    MaxTrianglesFrame,
    /// Décalques rendus par frame.
    MaxDecalsFrame,
    /// Instances projetant une ombre AXION.
    MaxShadowInstances,
    /// Bande passante AXION par joueur.
    NetBytesPerSecondPerPlayer,
}

impl Budget {
    /// Tous les budgets de la table 25.2.
    pub const ALL: [Budget; 20] = [
        Budget::SimNsPerTick,
        Budget::DamageNsPerTick,
        Budget::DeformationNsPerTick,
        Budget::ParticlesNsPerTick,
        Budget::RenderPrepNs,
        Budget::OcclusionNs,
        Budget::AssetNsPerTick,
        Budget::IdleHookNs,
        Budget::SubmitNs,
        Budget::NativeMemBytes,
        Budget::DeformMemBytes,
        Budget::GpuMemBytes,
        Budget::MaxActiveBodies,
        Budget::MaxDeformedAssemblies,
        Budget::MaxColliderRefitsPerTick,
        Budget::MaxVisibleInstances,
        Budget::MaxTrianglesFrame,
        Budget::MaxDecalsFrame,
        Budget::MaxShadowInstances,
        Budget::NetBytesPerSecondPerPlayer,
    ];

    /// Chemin de l'option de configuration qui porte sa valeur.
    ///
    /// Presque tous vivent sous `budgets.`, mais pas tous : la bande passante
    /// par joueur est déclarée avec les budgets réseau. Le chemin est donc
    /// donné, jamais dérivé du nom.
    #[must_use]
    pub const fn config_path(self) -> &'static str {
        match self {
            Budget::SimNsPerTick => "budgets.sim_ns_per_tick",
            Budget::DamageNsPerTick => "budgets.damage_ns_per_tick",
            Budget::DeformationNsPerTick => "budgets.deformation_ns_per_tick",
            Budget::ParticlesNsPerTick => "budgets.particles_ns_per_tick",
            Budget::RenderPrepNs => "budgets.render_prep_ns",
            Budget::OcclusionNs => "budgets.occlusion_ns",
            Budget::AssetNsPerTick => "budgets.asset_ns_per_tick",
            Budget::IdleHookNs => "budgets.idle_hook_ns",
            Budget::SubmitNs => "budgets.submit_ns",
            Budget::NativeMemBytes => "budgets.native_mem_bytes",
            Budget::DeformMemBytes => "budgets.deform_mem_bytes",
            Budget::GpuMemBytes => "budgets.gpu_mem_bytes",
            Budget::MaxActiveBodies => "budgets.max_active_bodies",
            Budget::MaxDeformedAssemblies => "budgets.max_deformed_assemblies",
            Budget::MaxColliderRefitsPerTick => "budgets.max_collider_refits_per_tick",
            Budget::MaxVisibleInstances => "budgets.max_visible_instances",
            Budget::MaxTrianglesFrame => "budgets.max_triangles_frame",
            Budget::MaxDecalsFrame => "budgets.max_decals_frame",
            Budget::MaxShadowInstances => "budgets.max_shadow_instances",
            Budget::NetBytesPerSecondPerPlayer => "net.max_bytes_per_second_per_player",
        }
    }

    /// Nom du budget dans les métriques : son chemin, sans le préfixe
    /// `budgets.` quand il l'a.
    ///
    /// Le préfixe restant, lorsqu'il y en a un, est conservé : sans lui, la
    /// bande passante réseau deviendrait indiscernable d'un budget homonyme
    /// d'un autre domaine.
    #[must_use]
    pub const fn metric_name(self) -> &'static str {
        let path = self.config_path();
        match path.as_bytes() {
            [b'b', b'u', b'd', b'g', b'e', b't', b's', b'.', ..] => {
                // `split_at` en contexte const : le préfixe fait huit octets.
                path.split_at(8).1
            }
            _ => path,
        }
    }

    /// Unité de ce que le budget compte.
    #[must_use]
    pub const fn unit(self) -> BudgetUnit {
        match self {
            Budget::SimNsPerTick
            | Budget::DamageNsPerTick
            | Budget::DeformationNsPerTick
            | Budget::ParticlesNsPerTick
            | Budget::RenderPrepNs
            | Budget::OcclusionNs
            | Budget::AssetNsPerTick
            | Budget::IdleHookNs
            | Budget::SubmitNs => BudgetUnit::Nanoseconds,
            Budget::NativeMemBytes | Budget::DeformMemBytes | Budget::GpuMemBytes => {
                BudgetUnit::Bytes
            }
            Budget::NetBytesPerSecondPerPlayer => BudgetUnit::BytesPerSecond,
            _ => BudgetUnit::Count,
        }
    }

    /// Indique si le budget borne une durée, donc un travail.
    #[must_use]
    pub const fn is_duration(self) -> bool {
        self.unit().is_duration()
    }

    /// Position du budget dans une table indexée par budget.
    #[must_use]
    pub fn index(self) -> usize {
        Self::ALL
            .iter()
            .position(|budget| *budget == self)
            .expect("tout budget figure dans Budget::ALL")
    }

    /// Nom de la métrique de consommation (R-500, R-1850).
    ///
    /// La convention est mécanique — `axion.budget.<nom>.consumed` — parce que
    /// le cahier des charges ne nomme pas ces métriques : il exige seulement
    /// qu'elles existent. Une convention dérivée du registre ne peut pas
    /// diverger de lui ; une liste écrite à la main, si.
    #[must_use]
    pub fn consumed_metric(self) -> String {
        format!("axion.budget.{}.consumed", self.metric_name())
    }

    /// Nom de la métrique de dépassement (R-241, R-500, R-1850).
    #[must_use]
    pub fn overrun_metric(self) -> String {
        format!("axion.budget.{}.overruns", self.metric_name())
    }
}

impl fmt::Display for Budget {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.config_path())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{ConfigOption, CLIENT, COMMON, SERVER};

    /// Toutes les options du registre de configuration, portées confondues.
    fn toutes_les_options() -> Vec<&'static ConfigOption> {
        COMMON.iter().chain(CLIENT).chain(SERVER).collect()
    }

    #[test]
    fn t007_tout_budget_a_une_valeur_configurable() {
        // INV-19 : un budget sans valeur configurable serait une constante
        // cachée, que personne ne pourrait voir ni ajuster.
        for budget in Budget::ALL {
            assert!(
                toutes_les_options()
                    .iter()
                    .any(|option| option.path == budget.config_path()),
                "{budget} absent du registre de configuration"
            );
        }
    }

    #[test]
    fn t007_toute_option_de_budget_figure_au_registre() {
        // Et réciproquement : une option `budgets.*` hors registre serait un
        // budget hors audit. C'est ce test qui a montré que la structure
        // `Budgets` de DM-18 n'était pas le registre.
        for option in toutes_les_options() {
            if !option.path.starts_with("budgets.") {
                continue;
            }
            assert!(
                Budget::ALL
                    .iter()
                    .any(|budget| budget.config_path() == option.path),
                "{} absent du registre des budgets",
                option.path
            );
        }
    }

    #[test]
    fn t007_tout_budget_porte_ses_deux_metriques() {
        // R-1850 : consommation et dépassement, pour chacun.
        let mut noms: Vec<String> = Budget::ALL
            .iter()
            .flat_map(|budget| [budget.consumed_metric(), budget.overrun_metric()])
            .collect();
        assert_eq!(noms.len(), Budget::ALL.len() * 2);
        for nom in &noms {
            assert!(nom.starts_with("axion.budget."), "{nom} mal préfixée");
        }

        let total = noms.len();
        noms.sort();
        noms.dedup();
        assert_eq!(total, noms.len(), "deux budgets partagent une métrique");
    }

    #[test]
    fn les_budgets_de_temps_sont_ceux_en_nanosecondes() {
        let durees = Budget::ALL.iter().filter(|b| b.is_duration()).count();
        assert_eq!(durees, 9, "le nombre de budgets de temps a changé");
        assert!(Budget::SimNsPerTick.is_duration());
        assert!(!Budget::NativeMemBytes.is_duration());
        assert!(!Budget::NetBytesPerSecondPerPlayer.is_duration());
    }

    #[test]
    fn le_nom_de_metrique_retire_le_prefixe_budgets() {
        assert_eq!(Budget::SimNsPerTick.metric_name(), "sim_ns_per_tick");
        // Celui qui ne vit pas sous `budgets.` garde son domaine.
        assert_eq!(
            Budget::NetBytesPerSecondPerPlayer.metric_name(),
            "net.max_bytes_per_second_per_player"
        );
    }

    #[test]
    fn les_index_couvrent_tous_les_budgets() {
        for (position, budget) in Budget::ALL.iter().enumerate() {
            assert_eq!(position, budget.index(), "{budget} mal indexé");
        }
    }
}
