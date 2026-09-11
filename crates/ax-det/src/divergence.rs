//! Repli curatif : ce qu'on fait d'une divergence survenue malgré tout (5.12bis).
//!
//! Le repli **préventif** de [`crate::profile::negotiate`] écarte la
//! reconstruction quand on sait d'avance qu'elle ne tiendra pas. Celui-ci
//! traite le cas qu'on ne savait pas : deux extrémités dans la matrice, de même
//! empreinte de configuration, dont une empreinte de champ diverge quand même.
//!
//! Cela arrive. Un défaut du noyau, un état persistant corrompu, un paquet
//! reconstruit de travers — la cause importe peu sur le moment, la réponse doit
//! être immédiate et bornée :
//!
//! 1. Le serveur envoie un **instantané autoritatif** de la région concernée.
//!    Le joueur voit le champ juste, tout de suite.
//! 2. La divergence est **comptée**.
//! 3. Au-delà du seuil, l'assembly passe en `SNAPSHOT`. Continuer à
//!    reconstruire une assembly qui a divergé trois fois, c'est resynchroniser
//!    indéfiniment pour économiser une bande passante qu'on finit par dépenser
//!    quand même.
//!
//! **Le jeu continue dans tous les cas** (R-514, R-515) : le mode `SNAPSHOT` ne
//! retire aucune fonctionnalité, il change le mécanisme de réplication.

use crate::profile::ReplicationMode;

/// Chemin de l'option qui fixe le seuil de bascule (5.12bis).
///
/// La valeur, ses bornes et son défaut appartiennent au registre de
/// configuration (ANNEXE A.3) ; cette constante existe pour que le réseau lise
/// l'option sans en écrire le nom en dur.
pub const DIVERGENCE_TOLERANCE_PATH: &str = "net.divergence_tolerance";

/// Seuil par défaut, tel que l'ANNEXE A.3 le donne.
pub const DEFAULT_DIVERGENCE_TOLERANCE: u32 = 3;

/// Métrique du nombre de divergences observées (5.12bis).
pub const DIVERGENCES_METRIC: &str = "axion.det.divergences";

/// Métrique du mode de réplication courant (5.12bis).
pub const MODE_METRIC: &str = "axion.det.mode";

/// Ce que le réseau doit faire d'une divergence.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DivergenceResponse {
    /// Envoyer un instantané autoritatif de la région ; la reconstruction
    /// continue pour le reste de l'assembly.
    ResyncRegion,
    /// Envoyer l'instantané **et** basculer l'assembly en `SNAPSHOT`.
    ///
    /// Rendu **une fois au plus** dans la vie d'un suivi : c'est la transition,
    /// pas l'état. Le journal de session peut donc s'y accrocher sans
    /// mécanisme de déduplication — « journalisé une fois par session avec la
    /// cause » se lit directement ici.
    ResyncAndFallback,
}

/// Suivi des divergences d'une assembly (5.12bis, repli curatif).
///
/// Un suivi par assembly, et non un compteur global : la bascule est décidée
/// par assembly, et une assembly qui diverge n'a aucune raison d'entraîner
/// celles qui vont bien.
#[derive(Debug, Clone)]
pub struct DivergenceTracker {
    tolerance: u32,
    divergences: u32,
    mode: ReplicationMode,
}

impl DivergenceTracker {
    /// Ouvre un suivi sur le mode issu du handshake.
    ///
    /// Un seuil nul est ramené à `1`. Zéro signifierait « basculer avant toute
    /// divergence », ce qui contredit le fait même d'être en `RECONSTRUCT` ; le
    /// registre l'interdit déjà (bornes `1..32`), et l'accepter ici rendrait le
    /// suivi dépendant d'une validation faite ailleurs.
    #[must_use]
    pub fn new(mode: ReplicationMode, tolerance: u32) -> Self {
        Self {
            tolerance: tolerance.max(1),
            divergences: 0,
            mode,
        }
    }

    /// Ouvre un suivi au seuil par défaut.
    #[must_use]
    pub fn with_default_tolerance(mode: ReplicationMode) -> Self {
        Self::new(mode, DEFAULT_DIVERGENCE_TOLERANCE)
    }

    /// Enregistre une divergence d'empreinte et dit quoi en faire.
    ///
    /// Le comptage est **cumulatif sur la session**, et non par série : trois
    /// divergences espacées d'une heure disent la même chose que trois
    /// divergences d'affilée — que la reconstruction de cette assembly n'est
    /// pas fiable. Remettre le compteur à zéro à chaque accord laisserait une
    /// assembly diverger indéfiniment tant qu'elle le fait lentement.
    pub fn record_divergence(&mut self) -> DivergenceResponse {
        self.divergences = self.divergences.saturating_add(1);

        if self.mode == ReplicationMode::Reconstruct && self.divergences >= self.tolerance {
            self.mode = ReplicationMode::Snapshot;
            return DivergenceResponse::ResyncAndFallback;
        }

        DivergenceResponse::ResyncRegion
    }

    /// Mode de réplication courant de l'assembly.
    #[must_use]
    pub fn mode(&self) -> ReplicationMode {
        self.mode
    }

    /// Nombre de divergences observées — la métrique [`DIVERGENCES_METRIC`].
    #[must_use]
    pub fn divergences(&self) -> u32 {
        self.divergences
    }

    /// Seuil effectif de bascule.
    #[must_use]
    pub fn tolerance(&self) -> u32 {
        self.tolerance
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn t820d_une_divergence_resynchronise_avant_toute_bascule() {
        // Le joueur voit le champ juste tout de suite, quelle que soit la
        // suite : l'instantané ne dépend pas du seuil.
        let mut suivi = DivergenceTracker::with_default_tolerance(ReplicationMode::Reconstruct);
        assert_eq!(suivi.record_divergence(), DivergenceResponse::ResyncRegion);
        assert_eq!(suivi.mode(), ReplicationMode::Reconstruct);
        assert_eq!(suivi.divergences(), 1);
    }

    #[test]
    fn t820d_la_bascule_survient_au_seuil_et_une_seule_fois() {
        let mut suivi = DivergenceTracker::new(ReplicationMode::Reconstruct, 3);
        assert_eq!(suivi.record_divergence(), DivergenceResponse::ResyncRegion);
        assert_eq!(suivi.record_divergence(), DivergenceResponse::ResyncRegion);

        // « après 3 divergences » se lit : dès la troisième. L'autre lecture
        // — basculer à la quatrième — tolérerait un défaut de plus que le
        // cahier des charges n'en tolère.
        assert_eq!(
            suivi.record_divergence(),
            DivergenceResponse::ResyncAndFallback
        );
        assert_eq!(suivi.mode(), ReplicationMode::Snapshot);

        // La transition n'est rendue qu'une fois : le journal de session s'y
        // accroche sans avoir à dédupliquer.
        for _ in 0..10 {
            assert_eq!(suivi.record_divergence(), DivergenceResponse::ResyncRegion);
        }
        assert_eq!(suivi.mode(), ReplicationMode::Snapshot);
        assert_eq!(suivi.divergences(), 13);
    }

    #[test]
    fn t820d_le_mode_snapshot_continue_de_verifier() {
        // Point 3 de 5.12bis : le mode SNAPSHOT n'est pas une renonciation à la
        // correction. Une divergence y est comptée et resynchronisée, sans
        // bascule puisqu'il n'y a plus où basculer.
        let mut suivi = DivergenceTracker::with_default_tolerance(ReplicationMode::Snapshot);
        for attendu in 1..=5 {
            assert_eq!(suivi.record_divergence(), DivergenceResponse::ResyncRegion);
            assert_eq!(suivi.divergences(), attendu);
        }
        assert_eq!(suivi.mode(), ReplicationMode::Snapshot);
    }

    #[test]
    fn t820d_un_seuil_de_un_bascule_des_la_premiere() {
        let mut suivi = DivergenceTracker::new(ReplicationMode::Reconstruct, 1);
        assert_eq!(
            suivi.record_divergence(),
            DivergenceResponse::ResyncAndFallback
        );
    }

    #[test]
    fn t820d_un_seuil_nul_ne_bascule_pas_avant_d_avoir_diverge() {
        // Le registre interdit déjà zéro ; l'accepter ici ferait dépendre le
        // suivi d'une validation faite ailleurs.
        let suivi = DivergenceTracker::new(ReplicationMode::Reconstruct, 0);
        assert_eq!(suivi.tolerance(), 1);
        assert_eq!(suivi.mode(), ReplicationMode::Reconstruct);
    }

    #[test]
    fn t820d_le_suivi_est_par_assembly() {
        // Une assembly qui diverge n'entraîne pas celles qui vont bien.
        let mut fautive = DivergenceTracker::new(ReplicationMode::Reconstruct, 2);
        let saine = DivergenceTracker::new(ReplicationMode::Reconstruct, 2);
        fautive.record_divergence();
        fautive.record_divergence();

        assert_eq!(fautive.mode(), ReplicationMode::Snapshot);
        assert_eq!(saine.mode(), ReplicationMode::Reconstruct);
        assert_eq!(saine.divergences(), 0);
    }
}
