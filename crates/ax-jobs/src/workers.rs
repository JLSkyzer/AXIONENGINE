//! Dimensionnement du pool de workers (R-471, PARTIE 27.4).

use core::fmt;

/// Nombre de cœurs qu'AXION laisse à l'hôte.
///
/// R-471 pose `available_parallelism() - 2` : un pour le thread de jeu, un pour
/// le rendu ou les entrées-sorties. AXION ne prend jamais toute la machine.
pub const RESERVED_CORES: usize = 2;

/// Part de parallélisme qu'AXION s'autorise (PARTIE 27.4).
///
/// L'option de configuration qui la porte n'est pas nommée ici : elle appartient
/// au pont d'interopérabilité optionnel, et INV-06 veut qu'aucun système
/// d'AXION ne référence ce mod hors de C-76. Le pool ne sait donc rien de plus
/// que « un autre gros consommateur de CPU est présent », ce qui est tout ce
/// dont il a besoin.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CpuShare {
    /// Tout ce que R-471 autorise, quelle que soit la présence d'un tiers.
    Full,
    /// La moitié, demandée explicitement.
    Half,
    /// La moitié en présence d'un autre consommateur de CPU, tout sinon.
    Auto,
    /// Un nombre de threads imposé par l'utilisateur.
    Fixed(u32),
}

impl CpuShare {
    /// Lit la valeur d'une option `cpu_share`.
    ///
    /// R-2060 la veut surchargeable en `full | half | auto | <n>`. Une valeur
    /// hors de ces formes n'est pas devinée : elle est refusée, et l'appelant
    /// retombe sur le défaut en le signalant.
    #[must_use]
    pub fn parse(value: &str) -> Option<Self> {
        match value.trim() {
            "full" => Some(CpuShare::Full),
            "half" => Some(CpuShare::Half),
            "auto" => Some(CpuShare::Auto),
            other => other.parse::<u32>().ok().map(CpuShare::Fixed),
        }
    }
}

impl fmt::Display for CpuShare {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            CpuShare::Full => formatter.write_str("full"),
            CpuShare::Half => formatter.write_str("half"),
            CpuShare::Auto => formatter.write_str("auto"),
            CpuShare::Fixed(threads) => write!(formatter, "{threads}"),
        }
    }
}

/// Côté sur lequel tourne le runtime.
///
/// Le côté ne change pas la nature des travaux, seulement leur plafond par
/// défaut : un client partage sa machine avec le rendu, un serveur dédié non.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Side {
    /// Client, intégré ou non.
    Client,
    /// Serveur dédié.
    Server,
}

impl Side {
    /// Plafond de workers lorsque `jobs.max_workers` vaut 0 (R-471).
    #[must_use]
    pub const fn default_max_workers(self) -> u32 {
        match self {
            Side::Client => 4,
            Side::Server => 8,
        }
    }
}

/// Éléments dont dépend le nombre de workers.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct WorkerPolicy {
    /// Parallélisme disponible, tel que `std::thread::available_parallelism`
    /// le rapporte.
    pub cores: usize,
    /// Côté du runtime.
    pub side: Side,
    /// `jobs.max_workers` ; `0` signifie « déduire », et le plafond devient
    /// celui du côté.
    pub max_workers: u32,
    /// Part de parallélisme autorisée.
    pub cpu_share: CpuShare,
    /// Un autre gros consommateur de CPU est présent.
    pub third_party_present: bool,
}

impl WorkerPolicy {
    /// Lit le parallélisme de la machine, ou `1` si le système ne le dit pas.
    ///
    /// Un système qui ne sait pas répondre n'autorise pas à supposer : une
    /// valeur inventée dimensionnerait le pool au hasard, alors qu'un seul
    /// worker fonctionne partout.
    #[must_use]
    pub fn available_cores() -> usize {
        std::thread::available_parallelism().map_or(1, std::num::NonZeroUsize::get)
    }

    /// Nombre de workers à créer.
    ///
    /// ```text
    /// base    = cores - 2                       (R-471)
    /// demande = base | base/2 | n               selon cpu_share et 27.4
    /// workers = clamp(demande, 1, plafond)
    /// ```
    ///
    /// Le résultat est toujours au moins `1` : un pool vide n'exécuterait rien,
    /// et R-2062 interdit qu'une réduction de parallélisme retire une
    /// fonctionnalité — elle allonge le calcul, elle ne l'annule pas.
    #[must_use]
    pub fn workers(&self) -> usize {
        let base = self.cores.saturating_sub(RESERVED_CORES);

        let requested = match self.cpu_share {
            CpuShare::Fixed(threads) => threads as usize,
            CpuShare::Full => base,
            CpuShare::Half => base / 2,
            // PARTIE 27.4 : l'heuristique est documentée, ce n'est pas une
            // négociation. En l'absence de tiers, `auto` ne retire rien.
            CpuShare::Auto if self.third_party_present => base / 2,
            CpuShare::Auto => base,
        };

        let ceiling = if self.max_workers == 0 {
            self.side.default_max_workers()
        } else {
            self.max_workers
        } as usize;

        requested.clamp(1, ceiling.max(1))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn policy(cores: usize, side: Side, max_workers: u32, cpu_share: CpuShare) -> WorkerPolicy {
        WorkerPolicy {
            cores,
            side,
            max_workers,
            cpu_share,
            third_party_present: false,
        }
    }

    #[test]
    fn t170_deux_coeurs_restent_a_l_hote() {
        // R-471 : n = clamp(cores - 2, 1, plafond).
        assert_eq!(policy(16, Side::Server, 0, CpuShare::Auto).workers(), 8);
        assert_eq!(policy(6, Side::Server, 0, CpuShare::Auto).workers(), 4);
        assert_eq!(policy(16, Side::Client, 0, CpuShare::Auto).workers(), 4);
        assert_eq!(policy(6, Side::Client, 0, CpuShare::Auto).workers(), 4);
    }

    #[test]
    fn t170_une_petite_machine_garde_un_worker() {
        // Un pool vide n'exécuterait rien ; R-2062 veut que le calcul soit plus
        // long, pas absent.
        for cores in 0..=2 {
            assert_eq!(
                policy(cores, Side::Client, 0, CpuShare::Auto).workers(),
                1,
                "{cores} cœurs"
            );
        }
        assert_eq!(policy(3, Side::Client, 0, CpuShare::Auto).workers(), 1);
    }

    #[test]
    fn t170_le_plafond_configure_prime() {
        assert_eq!(policy(32, Side::Server, 2, CpuShare::Auto).workers(), 2);
        // Un plafond nul serait un pool vide : il est ramené à un worker plutôt
        // que refusé, la configuration ayant déjà été validée en amont.
        assert_eq!(policy(32, Side::Server, 0, CpuShare::Fixed(0)).workers(), 1);
    }

    #[test]
    fn t170_partage_du_cpu_avec_un_tiers() {
        // PARTIE 27.4 : auto + tiers détecté -> floor((cores - 2) * 0.5).
        let mut avec_tiers = policy(18, Side::Server, 16, CpuShare::Auto);
        avec_tiers.third_party_present = true;
        assert_eq!(avec_tiers.workers(), 8);

        let sans_tiers = policy(18, Side::Server, 16, CpuShare::Auto);
        assert_eq!(sans_tiers.workers(), 16);

        // `full` ignore la présence du tiers, `half` l'ignore aussi dans
        // l'autre sens : ce sont des ordres, pas des indices.
        let mut plein = policy(18, Side::Server, 16, CpuShare::Full);
        plein.third_party_present = true;
        assert_eq!(plein.workers(), 16);

        let moitie = policy(18, Side::Server, 16, CpuShare::Half);
        assert_eq!(moitie.workers(), 8);
    }

    #[test]
    fn t170_un_nombre_impair_de_coeurs_arrondit_vers_le_bas() {
        let mut policy = policy(19, Side::Server, 16, CpuShare::Auto);
        policy.third_party_present = true;
        // (19 - 2) / 2 = 8.5 -> 8.
        assert_eq!(policy.workers(), 8);
    }

    #[test]
    fn t170_les_formes_de_cpu_share_sont_celles_de_r2060() {
        assert_eq!(CpuShare::parse("full"), Some(CpuShare::Full));
        assert_eq!(CpuShare::parse("half"), Some(CpuShare::Half));
        assert_eq!(CpuShare::parse(" auto "), Some(CpuShare::Auto));
        assert_eq!(CpuShare::parse("6"), Some(CpuShare::Fixed(6)));
        // Une valeur inconnue n'est pas devinée.
        assert_eq!(CpuShare::parse("beaucoup"), None);
        assert_eq!(CpuShare::parse("-1"), None);
    }

    #[test]
    fn t170_le_parallelisme_lu_est_au_moins_un() {
        assert!(WorkerPolicy::available_cores() >= 1);
    }
}
