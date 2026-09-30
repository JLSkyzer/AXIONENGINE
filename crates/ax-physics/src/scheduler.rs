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
}
