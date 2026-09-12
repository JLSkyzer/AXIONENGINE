//! Réparations autorisées (R-542).
//!
//! La liste est **fermée**. Un validateur qui répare ce qu'il veut ne valide
//! plus rien : il transforme un modèle fautif en modèle plausible, et l'auteur
//! ne saura jamais que son export était cassé. Les cinq réparations de R-542
//! ont en commun d'avoir une seule issue raisonnable — une normale nulle n'a
//! qu'une valeur de remplacement possible pour un triangle donné.
//!
//! Chaque réparation est **journalisée** : elle a changé la donnée de l'auteur,
//! et il doit pouvoir le savoir.

use ax_model::dm::geometry::{encode_normal, Vertex};

/// Une réparation appliquée.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Repair {
    /// Normale nulle recalculée depuis la géométrie environnante.
    NormalRecomputed {
        /// Sommet réparé.
        vertex: usize,
    },
    /// Poids d'os renormalisés pour que leur somme fasse 255.
    WeightsRenormalized {
        /// Sommet réparé.
        vertex: usize,
    },
    /// Triangle dégénéré supprimé.
    DegenerateTriangleRemoved {
        /// Mesh concerné.
        mesh: usize,
        /// Rang du triangle supprimé.
        triangle: usize,
    },
    /// Poids de déformation manquant, calculé par distance au bord de la
    /// région.
    DeformWeightComputed {
        /// Sommet réparé.
        vertex: usize,
    },
}

/// Journal des réparations d'un asset.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct RepairLog {
    /// Réparations appliquées, dans l'ordre.
    pub repairs: Vec<Repair>,
}

impl RepairLog {
    /// Indique si rien n'a été réparé.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.repairs.is_empty()
    }

    /// Nombre de réparations.
    #[must_use]
    pub fn len(&self) -> usize {
        self.repairs.len()
    }
}

/// Renormalise les poids d'os d'un sommet pour que leur somme fasse 255.
///
/// Rend `false` si la somme est nulle : il n'y a alors rien à renormaliser, et
/// répartir arbitrairement le poids sur des os choisis au hasard produirait un
/// mouvement absurde plutôt qu'une erreur visible. C'est une violation, pas une
/// réparation.
pub fn renormalize_weights(vertex: &mut Vertex) -> bool {
    let sum = vertex.weight_sum();
    if sum == 0 {
        return false;
    }
    if sum == Vertex::WEIGHT_SUM {
        return false;
    }

    // Répartition proportionnelle, le reste allant au poids dominant : la somme
    // doit valoir exactement 255, un arrondi par sommet ne suffit pas.
    let mut scaled = [0u16; 4];
    for (index, weight) in vertex.weights.iter().enumerate() {
        scaled[index] = u16::from(*weight) * Vertex::WEIGHT_SUM / sum;
    }
    let total: u16 = scaled.iter().sum();
    let dominant = scaled
        .iter()
        .enumerate()
        .max_by_key(|(_, value)| **value)
        .map_or(0, |(index, _)| index);
    scaled[dominant] += Vertex::WEIGHT_SUM - total;

    for (index, value) in scaled.iter().enumerate() {
        vertex.weights[index] = u8::try_from(*value).unwrap_or(u8::MAX);
    }
    true
}

/// Recalcule la normale d'un sommet depuis la normale d'un triangle qui le
/// porte.
///
/// Rend `false` si la normale du triangle n'est pas utilisable : on ne remplace
/// pas une normale absente par une direction inventée.
pub fn recompute_normal(vertex: &mut Vertex, triangle_normal: [f32; 3]) -> bool {
    let normal = encode_normal(triangle_normal);
    if normal == [0; 4] {
        return false;
    }
    vertex.normal = normal;
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::validate::rules::vertex as sommet;

    #[test]
    fn t232_des_poids_non_normalises_sont_ramenes_a_255() {
        let mut vertex = sommet([0.0; 3]);
        vertex.weights = [100, 50, 25, 25];
        assert!(renormalize_weights(&mut vertex));
        assert_eq!(vertex.weight_sum(), Vertex::WEIGHT_SUM);
    }

    #[test]
    fn t232_la_renormalisation_conserve_l_ordre_des_poids() {
        let mut vertex = sommet([0.0; 3]);
        vertex.weights = [10, 40, 20, 30];
        assert!(renormalize_weights(&mut vertex));

        assert_eq!(vertex.weight_sum(), Vertex::WEIGHT_SUM);
        // Le poids dominant doit le rester : l'inverser changerait l'os qui
        // pilote le sommet.
        assert!(vertex.weights[1] > vertex.weights[3]);
        assert!(vertex.weights[3] > vertex.weights[2]);
        assert!(vertex.weights[2] > vertex.weights[0]);
    }

    #[test]
    fn t232_des_poids_nuls_ne_sont_pas_repares() {
        let mut vertex = sommet([0.0; 3]);
        vertex.weights = [0; 4];
        // Répartir arbitrairement produirait un mouvement absurde plutôt qu'une
        // erreur visible : c'est une violation, pas une réparation.
        assert!(!renormalize_weights(&mut vertex));
        assert_eq!(vertex.weights, [0; 4]);
    }

    #[test]
    fn t232_des_poids_deja_normalises_ne_sont_pas_touches() {
        let mut vertex = sommet([0.0; 3]);
        vertex.weights = [255, 0, 0, 0];
        assert!(!renormalize_weights(&mut vertex));
        assert_eq!(vertex.weights, [255, 0, 0, 0]);
    }

    #[test]
    fn t232_une_normale_nulle_est_recalculee() {
        let mut vertex = sommet([0.0; 3]);
        vertex.normal = [0; 4];

        assert!(recompute_normal(&mut vertex, [0.0, 2.0, 0.0]));
        assert_eq!(vertex.normal, [0, 127, 0, 0]);
    }

    #[test]
    fn t232_une_normale_de_triangle_inutilisable_ne_repare_rien() {
        let mut vertex = sommet([0.0; 3]);
        vertex.normal = [0; 4];

        // On ne remplace pas une normale absente par une direction inventée.
        assert!(!recompute_normal(&mut vertex, [0.0; 3]));
        assert!(!recompute_normal(&mut vertex, [f32::NAN, 0.0, 0.0]));
        assert_eq!(vertex.normal, [0; 4]);
    }

    #[test]
    fn t232_le_journal_retient_ce_qui_a_ete_repare() {
        // Une réparation a changé la donnée de l'auteur : il doit pouvoir le
        // savoir.
        let mut log = RepairLog::default();
        assert!(log.is_empty());

        log.repairs.push(Repair::NormalRecomputed { vertex: 12 });
        log.repairs.push(Repair::DegenerateTriangleRemoved {
            mesh: 0,
            triangle: 7,
        });
        assert_eq!(log.len(), 2);
        assert!(!log.is_empty());
    }
}
