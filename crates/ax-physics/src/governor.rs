//! FM-21 : la part physique de la machine de dégradation SM-02 (§25.5, §25.6, ADR-123 §9).
//!
//! Une machine d'état pure, nourrie de la durée mesurée de chaque tick de simulation :
//! testable avec des durées injectées. Elle décide sur le **p95** de fenêtres de
//! 100 ticks, jamais sur un pic (R-1870) :
//!
//! - **descente** d'un palier quand le p95 dépasse `budgets.sim_ns_per_tick` trois
//!   fenêtres de suite ;
//! - **remontée** d'un palier après 30 s (six fenêtres) de p95 sous 60 % du budget, au
//!   plus un palier toutes les 10 s (200 ticks).
//!
//! Les paliers sont cumulatifs ; le pilote en tire ses actionneurs (§25.6) — `D1` une
//! itération de solveur et un sous-pas de moins, `D2` le rayon de simulation réduit d'un
//! quart, `D3` le plafond de corps actifs réduit de moitié. Le palier `SAFE` (« seules les
//! assemblies pilotées par un joueur ») suppose les sièges (M5) et C-77 : hors de portée.
//!
//! Sous surcharge, les décisions dépendent du temps mesuré et la reproductibilité de
//! R-1020 cède : c'est la définition même d'une dégradation (R-1890). Hors surcharge,
//! rien ne change.

/// Ticks d'une fenêtre de mesure (§25.5).
pub const WINDOW_TICKS: usize = 100;
/// Fenêtres consécutives au-dessus du budget avant une descente (§25.5, R-1870).
const OVER_WINDOWS: u32 = 3;
/// Fenêtres consécutives sous le seuil de calme avant une remontée : 30 s (§25.6).
const CALM_WINDOWS: u32 = 6;
/// Ticks minimaux entre deux changements avant une remontée : 10 s (§25.6).
const CLIMB_SPACING_TICKS: u64 = 200;
/// Seuil de calme, en pourcentage du budget : celui du gouverneur de C-77 (§25).
const CALM_PERCENT: u128 = 60;

/// Palier de dégradation de la simulation (SM-02), du nominal au plus dégradé.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum DegradationLevel {
    /// Qualité nominale.
    #[default]
    Normal,
    /// Une itération de solveur et un sous-pas de moins.
    Degraded1,
    /// En plus, rayon de simulation réduit d'un quart : les corps lointains dorment.
    Degraded2,
    /// En plus, plafond de corps actifs réduit de moitié.
    Degraded3,
}

impl DegradationLevel {
    /// Rang du palier, de 0 (nominal) à 3 — la valeur de `axion.sim.degradation_level`.
    #[must_use]
    pub const fn rank(self) -> u8 {
        match self {
            Self::Normal => 0,
            Self::Degraded1 => 1,
            Self::Degraded2 => 2,
            Self::Degraded3 => 3,
        }
    }

    const fn lower(self) -> Self {
        match self {
            Self::Normal => Self::Degraded1,
            Self::Degraded1 => Self::Degraded2,
            Self::Degraded2 | Self::Degraded3 => Self::Degraded3,
        }
    }

    const fn raise(self) -> Self {
        match self {
            Self::Normal | Self::Degraded1 => Self::Normal,
            Self::Degraded2 => Self::Degraded1,
            Self::Degraded3 => Self::Degraded2,
        }
    }
}

/// Un changement de palier et sa cause (R-1880) : la mesure, sa valeur et le budget.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DegradationTransition {
    /// Palier quitté.
    pub from: DegradationLevel,
    /// Palier atteint.
    pub to: DegradationLevel,
    /// p95 de la fenêtre qui a décidé, en nanosecondes (`axion.sim.p95_ns`).
    pub p95_ns: u64,
    /// `budgets.sim_ns_per_tick`, en nanosecondes.
    pub budget_ns: u64,
}

/// Le gouverneur de la simulation.
#[derive(Debug, Clone)]
pub struct Governor {
    budget_ns: u64,
    level: DegradationLevel,
    window: Vec<u64>,
    over_windows: u32,
    calm_windows: u32,
    ticks_since_change: u64,
    last_p95_ns: Option<u64>,
}

impl Governor {
    /// Un gouverneur au palier nominal, pour le budget `budgets.sim_ns_per_tick`.
    #[must_use]
    pub fn new(budget_ns: u64) -> Self {
        Self {
            budget_ns,
            level: DegradationLevel::Normal,
            window: Vec::with_capacity(WINDOW_TICKS),
            over_windows: 0,
            calm_windows: 0,
            ticks_since_change: 0,
            last_p95_ns: None,
        }
    }

    /// Palier courant.
    #[must_use]
    pub fn level(&self) -> DegradationLevel {
        self.level
    }

    /// p95 de la dernière fenêtre complète, en nanosecondes ; `None` avant la première.
    #[must_use]
    pub fn last_p95_ns(&self) -> Option<u64> {
        self.last_p95_ns
    }

    /// Prend en compte la durée d'un tick ; rend la transition si le palier change.
    pub fn observe(&mut self, nanos: u64) -> Option<DegradationTransition> {
        self.ticks_since_change = self.ticks_since_change.saturating_add(1);
        self.window.push(nanos);
        if self.window.len() < WINDOW_TICKS {
            return None;
        }
        let p95 = p95(&mut self.window);
        self.window.clear();
        self.last_p95_ns = Some(p95);

        if p95 > self.budget_ns {
            self.calm_windows = 0;
            self.over_windows += 1;
            if self.over_windows >= OVER_WINDOWS && self.level != DegradationLevel::Degraded3 {
                self.over_windows = 0;
                return Some(self.change_to(self.level.lower(), p95));
            }
        } else if u128::from(p95) * 100 < u128::from(self.budget_ns) * CALM_PERCENT {
            self.over_windows = 0;
            self.calm_windows += 1;
            if self.calm_windows >= CALM_WINDOWS
                && self.ticks_since_change >= CLIMB_SPACING_TICKS
                && self.level != DegradationLevel::Normal
            {
                return Some(self.change_to(self.level.raise(), p95));
            }
        } else {
            // Ni en dépassement ni au calme : les deux séries s'interrompent.
            self.over_windows = 0;
            self.calm_windows = 0;
        }
        None
    }

    fn change_to(&mut self, to: DegradationLevel, p95_ns: u64) -> DegradationTransition {
        let transition = DegradationTransition {
            from: self.level,
            to,
            p95_ns,
            budget_ns: self.budget_ns,
        };
        self.level = to;
        self.ticks_since_change = 0;
        transition
    }
}

/// p95 au rang le plus proche : la plus petite valeur dont au moins 95 % des mesures ne
/// dépassent pas. Réordonne `samples`, non vide.
fn p95(samples: &mut [u64]) -> u64 {
    let rank = (samples.len() * 95).div_ceil(100);
    let (_, value, _) = samples.select_nth_unstable(rank - 1);
    *value
}

#[cfg(test)]
mod tests {
    use super::*;

    const BUDGET: u64 = 3_000_000;

    /// Une fenêtre complète : 95 ticks à `base`, 5 à `pic` — le p95 vaut `base`.
    fn window(governor: &mut Governor, base: u64, pic: u64) -> Option<DegradationTransition> {
        let mut transition = None;
        for tick in 0..WINDOW_TICKS {
            let nanos = if tick % 20 == 19 { pic } else { base };
            if let Some(change) = governor.observe(nanos) {
                assert!(transition.is_none(), "au plus un changement par fenêtre");
                transition = Some(change);
            }
        }
        transition
    }

    fn windows(governor: &mut Governor, count: usize, nanos: u64) -> Vec<DegradationTransition> {
        (0..count)
            .filter_map(|_| window(governor, nanos, nanos))
            .collect()
    }

    #[test]
    fn le_p95_est_au_rang_le_plus_proche() {
        let mut samples: Vec<u64> = (1..=100).rev().collect();
        assert_eq!(p95(&mut samples), 95);
        assert_eq!(p95(&mut [7]), 7);
        let mut twenty: Vec<u64> = (1..=20).collect();
        assert_eq!(p95(&mut twenty), 19);
    }

    #[test]
    fn t307_des_pics_isoles_ne_degradent_jamais() {
        // R-1870 : cinq pics énormes par fenêtre, p95 sous le budget — aucune descente.
        let mut governor = Governor::new(BUDGET);
        for _ in 0..10 {
            assert_eq!(window(&mut governor, 1_000_000, 100_000_000), None);
        }
        assert_eq!(governor.level(), DegradationLevel::Normal);
        assert_eq!(governor.last_p95_ns(), Some(1_000_000));
    }

    #[test]
    fn t307_trois_fenetres_en_depassement_descendent_d_un_palier() {
        let mut governor = Governor::new(BUDGET);
        assert!(windows(&mut governor, 2, 4_000_000).is_empty());
        let transitions = windows(&mut governor, 1, 4_000_000);
        assert_eq!(
            transitions,
            [DegradationTransition {
                from: DegradationLevel::Normal,
                to: DegradationLevel::Degraded1,
                p95_ns: 4_000_000,
                budget_ns: BUDGET,
            }]
        );
        // Un palier à la fois : trois fenêtres de plus pour chacun.
        assert_eq!(windows(&mut governor, 3, 4_000_000).len(), 1);
        assert_eq!(governor.level(), DegradationLevel::Degraded2);
        assert_eq!(windows(&mut governor, 3, 4_000_000).len(), 1);
        assert_eq!(governor.level(), DegradationLevel::Degraded3);
        // Le palier SAFE est hors de portée : D3 est le plancher.
        assert!(windows(&mut governor, 9, 4_000_000).is_empty());
        assert_eq!(governor.level(), DegradationLevel::Degraded3);
    }

    #[test]
    fn t307_une_fenetre_dans_le_budget_interrompt_la_serie() {
        let mut governor = Governor::new(BUDGET);
        windows(&mut governor, 2, 4_000_000);
        windows(&mut governor, 1, 2_500_000);
        assert!(windows(&mut governor, 2, 4_000_000).is_empty());
        assert_eq!(governor.level(), DegradationLevel::Normal);
    }

    #[test]
    fn t307_la_remontee_attend_trente_secondes_puis_un_palier_par_dix_secondes() {
        let mut governor = Governor::new(BUDGET);
        windows(&mut governor, 6, 4_000_000);
        assert_eq!(governor.level(), DegradationLevel::Degraded2);

        // Calme (p95 à 50 % du budget) : rien avant six fenêtres, soit 30 s.
        assert!(windows(&mut governor, 5, 1_500_000).is_empty());
        assert_eq!(
            windows(&mut governor, 1, 1_500_000)
                .first()
                .map(|t| (t.from, t.to)),
            Some((DegradationLevel::Degraded2, DegradationLevel::Degraded1))
        );
        // Puis au plus un palier toutes les 10 s : pas à la fenêtre suivante, à celle d'après.
        assert!(windows(&mut governor, 1, 1_500_000).is_empty());
        assert_eq!(windows(&mut governor, 1, 1_500_000).len(), 1);
        assert_eq!(governor.level(), DegradationLevel::Normal);
        assert!(windows(&mut governor, 10, 1_500_000).is_empty());
    }

    #[test]
    fn t307_entre_soixante_et_cent_pour_cent_rien_ne_bouge() {
        let mut governor = Governor::new(BUDGET);
        windows(&mut governor, 3, 4_000_000);
        assert_eq!(governor.level(), DegradationLevel::Degraded1);
        // 70 % du budget : ni dépassement, ni calme.
        assert!(windows(&mut governor, 20, 2_100_000).is_empty());
        assert_eq!(governor.level(), DegradationLevel::Degraded1);
    }
}
