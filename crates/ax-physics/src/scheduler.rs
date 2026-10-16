//! Ordonnanceur de simulation (C-40, fiche 5.32).
//!
//! L'**ordre d'un pas est normatif et figé** (R-660) : toute variation exige un ADR. Ce
//! module en fait l'autorité — l'énumération [`Stage`] et son ordre [`Stage::ORDER`] — de
//! sorte que les composants à venir (chaîne de dommage C-41..C-45, particules C-36…)
//! s'insèrent à leur place réservée sans jamais réordonner le pas.
//!
//! Au jalon M3, seules quelques étapes existent (commandes, intégration, contacts,
//! sérialisation) ; les autres sont **réservées** et documentées. La chaîne de dommage
//! (étapes 7 à 12) s'exécute **une seule fois par tick**, après le dernier sous-pas
//! (R-660), pour rendre son coût indépendant du nombre de sous-pas.

/// Une étape du pas de simulation (C-40, R-660). L'ordre des variantes **est** l'ordre
/// normatif — voir [`Stage::ORDER`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Stage {
    /// 1. Application des commandes (spawn, despawn, forces, entrées, tuiles monde,
    ///    impacts externes, réparations, attaches).
    Commands,
    /// 2. Animation procédurale et échantillonnage des pistes pilotant nodes, sockets et
    ///    repères de joint. (Côté client aussi, R-662.)
    Animation,
    /// 3. Véhicules : entrées, raycasts de roues, forces de suspension et de pneu.
    Vehicles,
    /// 4. Intégration physique (Rapier) : forces → vitesses → positions → contacts.
    Integration,
    /// 5. Joints et ruptures de joints.
    Joints,
    /// 6. Collecte des contacts et des événements.
    Contacts,
    /// 7. Solveur d'impacts (C-41) : contacts → `ImpactDesc` enrichis. (Chaîne de dommage.)
    Impacts,
    /// 8. Modèle de dommage (C-35) : dommage visuel, structurel, usure. (Chaîne de dommage.)
    Damage,
    /// 9. Déformation (C-42) : champ élastique et plastique, par région. (Chaîne de dommage ;
    ///    côté client aussi pour l'élastique et le rejeu déterministe du plastique, R-662.)
    Deformation,
    /// 10. Intégrité structurelle (C-43) : propagation, seuils. (Chaîne de dommage.)
    Integrity,
    /// 11. Rupture et détachement (C-44) : débris, coupure de liaisons. (Chaîne de dommage.)
    Fracture,
    /// 12. Refit de collider (C-45), budgété, au plus N par tick. (Chaîne de dommage.)
    ColliderRefit,
    /// 13. Particules (C-36). (Côté client aussi, R-662.)
    Particles,
    /// 14. Propagation du scene graph (C-30). (Côté client aussi, R-662.)
    SceneGraph,
    /// 15. Sérialisation des états, événements, pages de champ et paquets réseau.
    Serialization,
}

impl Stage {
    /// L'ordre normatif du pas (R-660), figé. Toute variation exige un ADR.
    pub const ORDER: [Stage; 15] = [
        Stage::Commands,
        Stage::Animation,
        Stage::Vehicles,
        Stage::Integration,
        Stage::Joints,
        Stage::Contacts,
        Stage::Impacts,
        Stage::Damage,
        Stage::Deformation,
        Stage::Integrity,
        Stage::Fracture,
        Stage::ColliderRefit,
        Stage::Particles,
        Stage::SceneGraph,
        Stage::Serialization,
    ];

    /// Rang 1-basé de l'étape dans le pas (1..=15), tel que R-660 le numérote.
    #[must_use]
    pub fn index(self) -> u32 {
        Self::ORDER
            .iter()
            .position(|&stage| stage == self)
            .expect("toute étape est dans ORDER") as u32
            + 1
    }

    /// Vrai si l'étape fait partie de la **chaîne de dommage** (étapes 7 à 12), qui
    /// s'exécute une seule fois par tick après le dernier sous-pas (R-660).
    #[must_use]
    pub fn is_damage_chain(self) -> bool {
        matches!(
            self,
            Stage::Impacts
                | Stage::Damage
                | Stage::Deformation
                | Stage::Integrity
                | Stage::Fracture
                | Stage::ColliderRefit
        )
    }

    /// Vrai si l'étape appartient au **bloc des sous-pas** (étapes 3 à 6 : véhicules,
    /// intégration, joints, contacts), le seul qui se répète au sein d'un tick (R-990, R-660).
    #[must_use]
    pub fn is_substep(self) -> bool {
        (3..=6).contains(&self.index())
    }

    /// Vrai si l'étape s'exécute **côté client** (R-662) : animation, déformation
    /// (élastique + rejeu plastique), particules, scene graph — plus l'interpolation, qui
    /// n'est pas une étape du pas. Les étapes 3 à 12 ne tournent côté client que pour le
    /// véhicule sous prédiction locale, et jamais pour produire du plastique autoritatif
    /// (INV-17) — cette classification vise l'exécution **autoritaire** du pas.
    #[must_use]
    pub fn runs_on_client(self) -> bool {
        matches!(
            self,
            Stage::Animation | Stage::Deformation | Stage::Particles | Stage::SceneGraph
        )
    }
}

/// Rôle de la simulation : autorité serveur, ou client (prédiction/interpolation, R-662).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum SimMode {
    /// Serveur : autorité, exécute toutes les étapes présentes du pas.
    #[default]
    Server,
    /// Client : n'exécute pas l'intégration physique **autoritaire** (R-662, INV-17) ; il
    /// reçoit les états du serveur et interpole. Les étapes purement client (animation,
    /// déformation élastique, particules, scene graph) restent à leur charge.
    Client,
}

/// Durée mesurée de chaque étape sur le dernier tick, en nanosecondes (métrique de R-661).
///
/// Purement **observationnelle** : jamais lue par la simulation (R-1020). Le budget par
/// étape et la dégradation qu'un dépassement déclenche (PARTIE 25.6) arriveront avec le
/// gouverneur de qualité, leur consommateur ; ici on fournit la métrique par étape.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct StageDurations {
    nanos: [u64; 15],
}

impl StageDurations {
    /// Remet toutes les durées à zéro (début d'un tick).
    pub fn reset(&mut self) {
        self.nanos = [0; 15];
    }

    /// Ajoute `nanos` à l'étape donnée (accumulation sur les sous-pas d'un tick).
    pub fn add(&mut self, stage: Stage, nanos: u64) {
        self.nanos[(stage.index() - 1) as usize] += nanos;
    }

    /// Durée cumulée de l'étape sur le tick, en nanosecondes.
    #[must_use]
    pub fn get(&self, stage: Stage) -> u64 {
        self.nanos[(stage.index() - 1) as usize]
    }
}

/// Les étapes **réellement exécutées** sur un tick, dans leur ordre, avec leur durée
/// (T-390, T-391).
///
/// [`Stage::ORDER`] dit l'ordre prévu ; ce journal dit l'ordre suivi, que [`check_order`]
/// confronte à R-660. Deux passages consécutifs d'une même étape — l'étape 1 se fait en
/// deux temps, au `submit` puis au début du `collect` — ne s'inscrivent qu'une fois, leurs
/// durées s'additionnant. Le journal garde sa capacité d'un tick à l'autre : en régime, il
/// n'alloue plus.
///
/// Purement **observationnel**, comme [`StageDurations`] : jamais lu par la simulation.
#[derive(Debug, Clone, Default)]
pub struct StageLog {
    executed: Vec<Stage>,
    durations: StageDurations,
}

impl StageLog {
    /// Vide le journal (début d'un tick).
    pub fn reset(&mut self) {
        self.executed.clear();
        self.durations.reset();
    }

    /// Inscrit l'exécution d'une étape et sa durée.
    pub fn record(&mut self, stage: Stage, nanos: u64) {
        if self.executed.last() != Some(&stage) {
            self.executed.push(stage);
        }
        self.durations.add(stage, nanos);
    }

    /// Étapes exécutées depuis le dernier [`reset`](Self::reset), dans leur ordre.
    #[must_use]
    pub fn executed(&self) -> &[Stage] {
        &self.executed
    }

    /// Durée cumulée d'une étape sur le tick, en nanosecondes.
    #[must_use]
    pub fn duration(&self, stage: Stage) -> u64 {
        self.durations.get(stage)
    }
}

/// Vérifie qu'une suite d'étapes exécutées respecte l'ordre normatif de R-660.
///
/// L'ordre ne recule jamais, sauf **au sein du bloc des sous-pas** (étapes 3 à 6), où un
/// recul marque le début d'un nouveau sous-pas. Il s'ensuit que la chaîne de dommage
/// (7 à 12) passe après le dernier sous-pas et une seule fois, que les commandes (1)
/// précèdent tout le reste et que la sérialisation (15) clôt le tick.
///
/// # Errors
///
/// Le premier écart, nommé : l'étape fautive et celle qu'elle suit.
pub fn check_order(executed: &[Stage]) -> Result<(), String> {
    for pair in executed.windows(2) {
        let (previous, stage) = (pair[0], pair[1]);
        let recul = stage.index() <= previous.index();
        if recul && !(previous.is_substep() && stage.is_substep()) {
            return Err(format!(
                "{stage:?} (étape {}) après {previous:?} (étape {}) : R-660 ne le permet qu'au \
                 sein du bloc des sous-pas (3 à 6)",
                stage.index(),
                previous.index()
            ));
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn l_ordre_est_celui_de_r660() {
        // L'ordre normatif figé (R-660) : 15 étapes, dans cette séquence exacte.
        assert_eq!(Stage::ORDER.len(), 15);
        let attendu = [
            Stage::Commands,
            Stage::Animation,
            Stage::Vehicles,
            Stage::Integration,
            Stage::Joints,
            Stage::Contacts,
            Stage::Impacts,
            Stage::Damage,
            Stage::Deformation,
            Stage::Integrity,
            Stage::Fracture,
            Stage::ColliderRefit,
            Stage::Particles,
            Stage::SceneGraph,
            Stage::Serialization,
        ];
        assert_eq!(Stage::ORDER, attendu);
    }

    #[test]
    fn l_index_est_1_base_et_suit_l_ordre() {
        for (position, &stage) in Stage::ORDER.iter().enumerate() {
            assert_eq!(stage.index(), position as u32 + 1);
        }
        assert_eq!(Stage::Commands.index(), 1);
        assert_eq!(Stage::Integration.index(), 4);
        assert_eq!(Stage::Serialization.index(), 15);
    }

    #[test]
    fn la_chaine_de_dommage_est_7_a_12() {
        for &stage in &Stage::ORDER {
            let dans_la_chaine = (7..=12).contains(&stage.index());
            assert_eq!(
                stage.is_damage_chain(),
                dans_la_chaine,
                "{stage:?} (étape {})",
                stage.index()
            );
        }
    }

    #[test]
    fn les_etapes_client_sont_celles_de_r662() {
        // R-662 : animation (2), déformation (9), particules (13), scene graph (14).
        let client: Vec<u32> = Stage::ORDER
            .iter()
            .filter(|s| s.runs_on_client())
            .map(|s| s.index())
            .collect();
        assert_eq!(client, vec![2, 9, 13, 14]);
    }

    #[test]
    fn les_durees_s_accumulent_et_se_remettent_a_zero() {
        let mut d = StageDurations::default();
        assert_eq!(d.get(Stage::Integration), 0);
        d.add(Stage::Integration, 100);
        d.add(Stage::Integration, 50);
        assert_eq!(d.get(Stage::Integration), 150);
        assert_eq!(d.get(Stage::Contacts), 0);
        d.reset();
        assert_eq!(d.get(Stage::Integration), 0);
    }

    #[test]
    fn le_bloc_des_sous_pas_est_3_a_6() {
        let bloc: Vec<u32> = Stage::ORDER
            .iter()
            .filter(|s| s.is_substep())
            .map(|s| s.index())
            .collect();
        assert_eq!(bloc, vec![3, 4, 5, 6]);
    }

    #[test]
    fn t391_le_journal_fusionne_deux_passages_consecutifs_et_cumule_leurs_durees() {
        let mut log = StageLog::default();
        log.record(Stage::Commands, 10);
        log.record(Stage::Commands, 5);
        log.record(Stage::Integration, 100);
        log.record(Stage::Contacts, 20);
        log.record(Stage::Integration, 90);
        assert_eq!(
            log.executed(),
            [
                Stage::Commands,
                Stage::Integration,
                Stage::Contacts,
                Stage::Integration
            ]
        );
        assert_eq!(log.duration(Stage::Commands), 15);
        assert_eq!(log.duration(Stage::Integration), 190);
        assert_eq!(log.duration(Stage::Serialization), 0);
        log.reset();
        assert!(log.executed().is_empty());
        assert_eq!(log.duration(Stage::Integration), 0);
    }

    #[test]
    fn t390_un_tick_conforme_a_r660_passe() {
        use Stage::*;
        // Trois sous-pas, puis la sérialisation ; la chaîne de dommage une fois, après eux.
        assert_eq!(check_order(&[]), Ok(()));
        assert_eq!(
            check_order(&[
                Commands,
                Integration,
                Contacts,
                Integration,
                Contacts,
                Integration,
                Contacts,
                Serialization
            ]),
            Ok(())
        );
        assert_eq!(
            check_order(&[
                Commands,
                Animation,
                Vehicles,
                Integration,
                Joints,
                Contacts,
                Vehicles,
                Integration,
                Contacts,
                Impacts,
                Damage,
                Deformation,
                Integrity,
                Fracture,
                ColliderRefit,
                Particles,
                SceneGraph,
                Serialization
            ]),
            Ok(())
        );
    }

    #[test]
    fn t390_un_tick_hors_de_r660_est_refuse() {
        use Stage::*;
        // La chaîne de dommage dans la boucle des sous-pas.
        assert!(check_order(&[Integration, Contacts, Impacts, Integration, Contacts]).is_err());
        // La sérialisation avant l'intégration.
        assert!(check_order(&[Commands, Serialization, Integration]).is_err());
        // Des commandes après l'intégration.
        assert!(check_order(&[Integration, Commands]).is_err());
        // Deux fois la chaîne de dommage.
        assert!(check_order(&[Contacts, Damage, Impacts]).is_err());
        let refus = check_order(&[Commands, Serialization, Integration]).unwrap_err();
        assert!(
            refus.contains("Integration") && refus.contains("Serialization"),
            "{refus}"
        );
    }
}
