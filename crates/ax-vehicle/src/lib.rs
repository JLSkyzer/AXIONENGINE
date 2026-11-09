//! C-33 : le modèle de véhicule (PARTIE 12, ADR-159 D2).
//!
//! Des fonctions pures, sans Rapier ni monde : `ax-physics` lance les rayons et applique les
//! forces, le client les rejoue à l'identique pour la prédiction. Le moteur ne connaît aucun nom
//! de véhicule (INV-01) ; tout vient de la definition.

pub mod suspension;
pub mod tire;
