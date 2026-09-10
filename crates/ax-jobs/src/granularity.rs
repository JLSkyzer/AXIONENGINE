//! Granularité adaptative des tâches (R-473).

/// Durée visée par tâche, en nanosecondes (R-473).
///
/// C'est une **cible de découpage**, pas une mesure : au-dessous, le coût
/// d'ordonnancement domine ; au-dessus, une tâche trop longue retarde
/// l'annulation et déséquilibre le vol de travail.
pub const TARGET_TASK_NANOS: u64 = 100_000;

/// Découpage adaptatif d'un lot d'éléments en tâches.
///
/// La granularité n'est pas devinée : elle part d'une valeur initiale et se
/// corrige à chaque tâche **mesurée**. Un lot dont chaque élément coûte cher
/// converge vers de petites tâches, un lot d'éléments bon marché vers de
/// grandes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Granularity {
    items_per_task: usize,
    min_items: usize,
    max_items: usize,
    observations: u64,
}

impl Granularity {
    /// Crée un découpage borné.
    ///
    /// `initial` est le point de départ, corrigé dès la première mesure. Les
    /// bornes sont celles du domaine appelant : un lot de 8 éléments n'a pas de
    /// raison d'accepter des tâches de 4096.
    ///
    /// # Panics
    ///
    /// Si `min_items` vaut zéro ou dépasse `max_items` : une tâche vide ne
    /// terminerait jamais un lot, et des bornes inversées n'ont pas de sens.
    #[must_use]
    pub fn new(initial: usize, min_items: usize, max_items: usize) -> Self {
        assert!(min_items >= 1, "une tâche porte au moins un élément");
        assert!(min_items <= max_items, "bornes de granularité inversées");
        Self {
            items_per_task: initial.clamp(min_items, max_items),
            min_items,
            max_items,
            observations: 0,
        }
    }

    /// Nombre d'éléments à confier à la prochaine tâche.
    #[must_use]
    pub const fn items_per_task(&self) -> usize {
        self.items_per_task
    }

    /// Nombre de mesures prises en compte jusqu'ici.
    #[must_use]
    pub const fn observations(&self) -> u64 {
        self.observations
    }

    /// Nombre de tâches nécessaires pour couvrir `items` éléments.
    #[must_use]
    pub const fn tasks_for(&self, items: usize) -> usize {
        if items == 0 {
            return 0;
        }
        items.div_ceil(self.items_per_task)
    }

    /// Prend en compte une tâche exécutée.
    ///
    /// Une mesure inexploitable — aucun élément, ou durée nulle parce que
    /// l'horloge n'a pas la résolution nécessaire — est **ignorée** plutôt que
    /// extrapolée : corriger la granularité sur une durée qu'on n'a pas su
    /// mesurer reviendrait à inventer un chiffre.
    pub fn observe(&mut self, items: usize, elapsed_nanos: u64) {
        if items == 0 || elapsed_nanos == 0 {
            return;
        }

        // Coût par élément, arrondi au-dessus : sous-estimer conduirait à des
        // tâches trop longues, qui retardent l'annulation.
        let per_item = elapsed_nanos.div_ceil(items as u64).max(1);
        let ideal = (TARGET_TASK_NANOS / per_item).max(1) as usize;

        // Correction par moyenne avec la valeur courante : une seule tâche
        // ralentie par une préemption ne doit pas faire basculer le découpage
        // d'un extrême à l'autre.
        let corrected = (self.items_per_task + ideal) / 2;
        self.items_per_task = corrected.clamp(self.min_items, self.max_items);
        self.observations += 1;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Fait converger le découpage sur un coût par élément constant.
    fn converge(granularity: &mut Granularity, nanos_per_item: u64, rounds: usize) {
        for _ in 0..rounds {
            let items = granularity.items_per_task();
            granularity.observe(items, items as u64 * nanos_per_item);
        }
    }

    #[test]
    fn t172_le_decoupage_converge_vers_la_duree_visee() {
        // Un élément à 1 µs : viser 100 µs demande environ 100 éléments.
        let mut granularity = Granularity::new(1, 1, 4096);
        converge(&mut granularity, 1_000, 40);

        let items = granularity.items_per_task();
        let duree = items as u64 * 1_000;
        assert!(
            duree.abs_diff(TARGET_TASK_NANOS) <= TARGET_TASK_NANOS / 10,
            "tâche de {duree} ns pour {items} éléments, cible {TARGET_TASK_NANOS}"
        );
    }

    #[test]
    fn t172_un_element_couteux_donne_des_taches_courtes() {
        // Un élément à 1 ms dépasse déjà la cible à lui seul.
        let mut granularity = Granularity::new(512, 1, 4096);
        converge(&mut granularity, 1_000_000, 40);
        assert_eq!(granularity.items_per_task(), 1);
    }

    #[test]
    fn t172_un_element_bon_marche_donne_de_grandes_taches_bornees() {
        let mut granularity = Granularity::new(1, 1, 256);
        converge(&mut granularity, 1, 40);
        // Sans borne, 100 µs à 1 ns l'élément demanderait 100 000 éléments :
        // le maximum du domaine prime.
        assert_eq!(granularity.items_per_task(), 256);
    }

    #[test]
    fn t172_une_mesure_inexploitable_est_ignoree() {
        let mut granularity = Granularity::new(64, 1, 4096);

        granularity.observe(0, 500_000);
        granularity.observe(10, 0);

        assert_eq!(
            granularity.items_per_task(),
            64,
            "granularité corrigée sans mesure"
        );
        assert_eq!(granularity.observations(), 0);
    }

    #[test]
    fn t172_une_mesure_aberrante_ne_fait_pas_basculer_le_decoupage() {
        let mut granularity = Granularity::new(1, 1, 4096);
        converge(&mut granularity, 1_000, 40);
        let stable = granularity.items_per_task();

        // Une tâche cent fois plus lente que d'habitude : une préemption, une
        // pause du système. Le découpage bouge, mais ne s'effondre pas.
        granularity.observe(stable, stable as u64 * 100_000);
        assert!(
            granularity.items_per_task() >= stable / 2,
            "découpage effondré : {} après {stable}",
            granularity.items_per_task()
        );
    }

    #[test]
    fn t172_le_nombre_de_taches_couvre_le_lot() {
        let granularity = Granularity::new(64, 1, 4096);
        assert_eq!(granularity.tasks_for(0), 0);
        assert_eq!(granularity.tasks_for(1), 1);
        assert_eq!(granularity.tasks_for(64), 1);
        assert_eq!(granularity.tasks_for(65), 2);
        assert_eq!(granularity.tasks_for(1000), 16);
    }

    #[test]
    #[should_panic(expected = "au moins un élément")]
    fn une_tache_vide_est_refusee() {
        let _ = Granularity::new(8, 0, 64);
    }
}
