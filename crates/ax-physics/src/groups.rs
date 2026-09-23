//! Groupes et filtre de collision (§10.4, R-980).
//!
//! Une paire de colliders interagit si, et seulement si, chacun est membre d'un
//! groupe que l'autre filtre :
//!
//! ```text
//! (membership_a & filter_b) != 0  &&  (membership_b & filter_a) != 0
//! ```
//!
//! C'est exactement le test « AND » des `InteractionGroups` de `rapier`. Les
//! groupes sont data-driven, désignés **par nom** (R-980) : huit sont réservés,
//! les autres s'enregistrent à la demande, dans la limite des 32 bits d'un
//! masque.

use rapier3d::prelude::{Group, InteractionGroups};

/// Nombre total de groupes distincts (largeur du masque `rapier`).
pub const GROUP_COUNT: u32 = 32;

/// Les huit groupes réservés (R-980), chacun à un bit fixe.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReservedGroup {
    /// Collision du monde (tuiles, terrain).
    World,
    /// Corps d'une assembly.
    Assembly,
    /// Part d'une assembly.
    Part,
    /// Débris.
    Debris,
    /// Proxy cinématique d'une entité vanilla.
    EntityProxy,
    /// Capteur.
    Sensor,
    /// Particule.
    Particle,
    /// Aide au débogage.
    Debug,
}

impl ReservedGroup {
    /// Les huit groupes, dans l'ordre de leurs bits (0 à 7).
    pub const ALL: [ReservedGroup; 8] = [
        ReservedGroup::World,
        ReservedGroup::Assembly,
        ReservedGroup::Part,
        ReservedGroup::Debris,
        ReservedGroup::EntityProxy,
        ReservedGroup::Sensor,
        ReservedGroup::Particle,
        ReservedGroup::Debug,
    ];

    /// Indice de bit du groupe, de 0 à 7.
    #[must_use]
    pub const fn bit(self) -> u32 {
        match self {
            ReservedGroup::World => 0,
            ReservedGroup::Assembly => 1,
            ReservedGroup::Part => 2,
            ReservedGroup::Debris => 3,
            ReservedGroup::EntityProxy => 4,
            ReservedGroup::Sensor => 5,
            ReservedGroup::Particle => 6,
            ReservedGroup::Debug => 7,
        }
    }

    /// Nom réservé du groupe.
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            ReservedGroup::World => "world",
            ReservedGroup::Assembly => "assembly",
            ReservedGroup::Part => "part",
            ReservedGroup::Debris => "debris",
            ReservedGroup::EntityProxy => "entity_proxy",
            ReservedGroup::Sensor => "sensor",
            ReservedGroup::Particle => "particle",
            ReservedGroup::Debug => "debug",
        }
    }
}

/// Ce qui empêche d'enregistrer un groupe (§10.4).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GroupError {
    /// Les 32 groupes sont déjà pris.
    Full,
}

impl core::fmt::Display for GroupError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        match self {
            Self::Full => f.write_str("les 32 groupes de collision sont déjà pris"),
        }
    }
}

impl std::error::Error for GroupError {}

/// Registre des groupes de collision, désignés par nom (R-980).
///
/// Les huit groupes réservés occupent les bits 0 à 7 dès la création ; un groupe
/// tiers s'enregistre au premier bit libre (8 à 31). Le registre n'est pas
/// parcouru pendant la simulation : sa recherche linéaire est un coût de
/// configuration, pas de tick (R-1020).
#[derive(Debug, Clone)]
pub struct GroupRegistry {
    names: Vec<String>,
}

impl GroupRegistry {
    /// Crée un registre avec les huit groupes réservés déjà en place.
    #[must_use]
    pub fn new() -> Self {
        let names = ReservedGroup::ALL
            .iter()
            .map(|group| group.name().to_string())
            .collect();
        Self { names }
    }

    /// Indice de bit d'un groupe déjà connu, ou `None`.
    #[must_use]
    pub fn index_of(&self, name: &str) -> Option<u32> {
        self.names
            .iter()
            .position(|known| known == name)
            .map(|index| index as u32)
    }

    /// Enregistre un groupe par nom et rend son indice de bit.
    ///
    /// Idempotent : un nom déjà connu — réservé ou non — rend son indice
    /// existant sans en consommer un nouveau.
    ///
    /// # Errors
    /// [`GroupError::Full`] si les 32 bits sont déjà attribués.
    pub fn register(&mut self, name: &str) -> Result<u32, GroupError> {
        if let Some(index) = self.index_of(name) {
            return Ok(index);
        }
        if self.names.len() as u32 >= GROUP_COUNT {
            return Err(GroupError::Full);
        }
        self.names.push(name.to_string());
        Ok((self.names.len() - 1) as u32)
    }

    /// Nombre de groupes enregistrés.
    #[must_use]
    pub fn len(&self) -> usize {
        self.names.len()
    }

    /// Indique si le registre est vide. Il ne l'est jamais : les huit réservés
    /// y sont toujours. Présent pour la cohérence avec [`Self::len`].
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.names.is_empty()
    }
}

impl Default for GroupRegistry {
    fn default() -> Self {
        Self::new()
    }
}

/// Appartenance et filtre d'un corps, en masques de bits de groupes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CollisionGroups {
    memberships: u32,
    filter: u32,
}

impl CollisionGroups {
    /// Membre de tous les groupes, entrant en collision avec tous : le défaut
    /// d'un corps auquel aucun groupe n'a été attribué.
    pub const ALL: Self = Self {
        memberships: u32::MAX,
        filter: u32::MAX,
    };

    /// Construit des groupes depuis des indices de bits (ceux du registre).
    ///
    /// Un indice hors de `0..32` est ignoré : le registre n'en produit jamais.
    #[must_use]
    pub fn from_indices(memberships: &[u32], filter: &[u32]) -> Self {
        Self {
            memberships: mask_of(memberships),
            filter: mask_of(filter),
        }
    }

    /// Masque d'appartenance.
    #[must_use]
    pub fn memberships(&self) -> u32 {
        self.memberships
    }

    /// Masque de filtre.
    #[must_use]
    pub fn filter(&self) -> u32 {
        self.filter
    }

    /// Traduit en `InteractionGroups` de `rapier`, en mode « AND » — le test
    /// symétrique de §10.4.
    pub(crate) fn to_rapier(self) -> InteractionGroups {
        InteractionGroups::all()
            .with_memberships(Group::from_bits_retain(self.memberships))
            .with_filter(Group::from_bits_retain(self.filter))
    }
}

/// Réduit des indices de bits en un masque, en ignorant ceux hors `0..32`.
fn mask_of(indices: &[u32]) -> u32 {
    indices
        .iter()
        .filter(|&&index| index < GROUP_COUNT)
        .fold(0, |mask, &index| mask | (1 << index))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn les_reserves_ont_des_bits_distincts_de_0_a_7() {
        for (position, group) in ReservedGroup::ALL.iter().enumerate() {
            assert_eq!(group.bit(), position as u32, "{} mal placé", group.name());
        }
        // Huit bits distincts.
        let mask = ReservedGroup::ALL
            .iter()
            .fold(0u32, |m, g| m | (1 << g.bit()));
        assert_eq!(mask.count_ones(), 8);
    }

    #[test]
    fn le_registre_place_les_reserves_par_nom() {
        let registry = GroupRegistry::new();
        for group in ReservedGroup::ALL {
            assert_eq!(registry.index_of(group.name()), Some(group.bit()));
        }
        assert_eq!(registry.index_of("inconnu"), None);
        assert_eq!(registry.len(), 8);
    }

    #[test]
    fn enregistrer_un_groupe_tiers_prend_le_premier_bit_libre() {
        let mut registry = GroupRegistry::new();
        assert_eq!(registry.register("vehicle").unwrap(), 8);
        assert_eq!(registry.register("cargo").unwrap(), 9);
        // Idempotent : le même nom rend le même indice.
        assert_eq!(registry.register("vehicle").unwrap(), 8);
        // Un nom réservé rend son indice réservé, sans consommer de bit.
        assert_eq!(registry.register("world").unwrap(), 0);
        assert_eq!(registry.len(), 10);
    }

    #[test]
    fn le_registre_refuse_au_dela_de_32_groupes() {
        let mut registry = GroupRegistry::new();
        for i in 0..24 {
            registry
                .register(&format!("g{i}"))
                .expect("les bits 8..31 sont libres");
        }
        assert_eq!(registry.len(), 32);
        assert_eq!(registry.register("un_de_trop"), Err(GroupError::Full));
    }

    #[test]
    fn from_indices_construit_les_masques() {
        let groups = CollisionGroups::from_indices(&[0, 2], &[2]);
        assert_eq!(groups.memberships(), 0b101);
        assert_eq!(groups.filter(), 0b100);
        // Un indice hors bornes est ignoré.
        assert_eq!(CollisionGroups::from_indices(&[64], &[]).memberships(), 0);
    }
}
