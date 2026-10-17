//! Table des matériaux physiques de la session (DM-07, ADR-149).
//!
//! Java la pousse par `SET_PHYSICS_MATERIALS` ; un matériau y garde son rang pour la session,
//! et c'est ce rang que portent les colliders, les tuiles et les événements.

use crate::body::ContactMaterial;
use ax_model::dm::physics::PhysicsMaterial;

/// La table des matériaux de la session, réduite à ce que la simulation consomme : le groupe
/// CONTACT de chaque entrée, assaini.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct MaterialTable {
    entries: Vec<ContactMaterial>,
}

impl MaterialTable {
    /// La table des entrées DM-07 reçues, dans leur ordre : l'index d'un matériau est son rang.
    /// Au-delà de `u16::MAX` entrées, le reste est ignoré : `0xFFFF` dit « aucun matériau ».
    #[must_use]
    pub fn from_dm(materials: &[PhysicsMaterial]) -> Self {
        let entries = materials
            .iter()
            .zip(0..u16::MAX)
            .map(|(material, index)| ContactMaterial::from_table(material, index))
            .collect();
        Self { entries }
    }

    /// Le contact de l'index `index` ; un index hors de la table — aucun matériau, ou une table
    /// pas encore reçue — rend le contact neutre qui le garde, pour la table suivante.
    #[must_use]
    pub fn resolve(&self, index: u16) -> ContactMaterial {
        self.entries
            .get(usize::from(index))
            .copied()
            .unwrap_or_else(|| ContactMaterial::unresolved(index))
    }

    /// Nombre d'entrées.
    #[must_use]
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    /// Vrai si aucune table n'a été reçue.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }
}
