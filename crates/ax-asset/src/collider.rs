//! Génération des colliders à la compilation (C-32, fiche 5.24).
//!
//! Un collider ([`ColliderDesc`], DM-06) peut venir, par priorité (R-620) : des
//! extras d'un node → d'une definition → d'une génération automatique
//! (`auto_box`, `auto_sphere`, `auto_capsule`, `auto_convex`, `auto_compound`)
//! → d'aucune source. La génération est faite **à la compilation** (R-620),
//! jamais au runtime.
//!
//! # État (tranche T1)
//!
//! Cette tranche porte la source par **definition** (un mode demandé pour tout
//! l'asset) et le générateur `auto_box` : un asset dont la definition réclame
//! `AutoBox` reçoit une boîte englobante alignée sur ses bornes. Le défaut est
//! [`ColliderMode::None`] — « → aucun » : un asset qui ne réclame rien ne reçoit
//! aucun collider, si bien qu'aucune géométrie existante n'en gagne un par
//! surprise.
//!
//! Les sources par **extras de node**, les autres formes automatiques
//! (`auto_sphere`, `auto_capsule`, `auto_compound`), la masse/le COM (R-622) et
//! le marquage REFITTABLE (R-623) arrivent aux tranches suivantes. La
//! sérialisation vers la section A3D `PHYS` (T5) attend un ADR de disposition.

use crate::optimize::Aabb;
use ax_model::dm::geometry::Transform;
use ax_model::dm::physics::{ColliderDesc, ColliderShape};

/// Densité par défaut d'un collider auto-généré, en kg/m³.
///
/// Densité de l'eau — une valeur physique, pas un chiffre inventé —, employée
/// tant qu'aucun matériau ni definition ne la fournit (R-622, tranche masse/COM).
/// [`ColliderDesc`] exige une densité strictement positive : ce défaut la
/// garantit.
const AUTO_COLLIDER_DENSITY: f32 = 1000.0;

/// Groupe et masque par défaut : membre de tous les groupes, entre en collision
/// avec tous (comme `CollisionGroups::ALL` au runtime). Le filtrage fin
/// (`collision_group` / `collides_with` de la definition) arrive plus tard.
const ALL_GROUPS: u32 = u32::MAX;

/// Référence absente (matériau, part, région, zone de dommage) : convention DM
/// du `u16::MAX`.
const NONE_U16: u16 = u16::MAX;

/// Mode de génération automatique de collider réclamé par la definition (R-620).
///
/// C'est la source de **second rang** de la chaîne R-620 (après les extras d'un
/// node, avant « aucun »). Le défaut [`None`](Self::None) est « aucun » : rien
/// n'est généré sans demande explicite.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum ColliderMode {
    /// Aucun collider auto-généré.
    #[default]
    None,
    /// Une boîte englobante alignée sur les axes (`auto_box`).
    AutoBox,
}

/// Produit les colliders d'un asset (C-32).
///
/// En T1, seule la source par definition est câblée : `mode` décide. `bounds`
/// est la boîte englobante de l'asset, telle que l'optimizer la calcule (C-23,
/// étape 7) ; `None` (aucune géométrie) ne produit aucun collider.
#[must_use]
pub fn build_colliders(mode: ColliderMode, bounds: Option<Aabb>) -> Vec<ColliderDesc> {
    match (mode, bounds) {
        (ColliderMode::AutoBox, Some(bounds)) => vec![auto_box(bounds)],
        _ => Vec::new(),
    }
}

/// Génère une boîte alignée sur les axes depuis les bornes de l'asset
/// (`auto_box`), centrée sur le milieu des bornes.
///
/// Une géométrie plate (une borne d'épaisseur nulle sur un axe) donne une boîte
/// à demi-dimension nulle, que le validateur refuse (`InvalidShapeDimension`) :
/// réclamer `auto_box` sur une surface est une erreur d'auteur, signalée comme
/// telle plutôt que corrigée en silence par une épaisseur inventée.
fn auto_box(bounds: Aabb) -> ColliderDesc {
    let half_extents = [
        (bounds.max[0] - bounds.min[0]) * 0.5,
        (bounds.max[1] - bounds.min[1]) * 0.5,
        (bounds.max[2] - bounds.min[2]) * 0.5,
    ];
    let center = [
        (bounds.max[0] + bounds.min[0]) * 0.5,
        (bounds.max[1] + bounds.min[1]) * 0.5,
        (bounds.max[2] + bounds.min[2]) * 0.5,
    ];
    ColliderDesc {
        shape: ColliderShape::Box { half_extents },
        local: Transform {
            translation: center,
            ..Transform::identity()
        },
        material: NONE_U16,
        _pad: 0,
        group: ALL_GROUPS,
        mask: ALL_GROUPS,
        // REFITTABLE sera posé selon la région de déformation de la part (R-623),
        // à la tranche dédiée ; aucune part n'est encore attachée ici.
        flags: 0,
        density: AUTO_COLLIDER_DENSITY,
        damage_zone: NONE_U16,
        part: NONE_U16,
        region: NONE_U16,
        _pad2: 0,
        hull_points_offset: 0,
        hull_points_count: 0,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bounds(min: [f32; 3], max: [f32; 3]) -> Aabb {
        Aabb { min, max }
    }

    #[test]
    fn le_mode_none_ne_genere_aucun_collider() {
        assert!(build_colliders(ColliderMode::None, Some(bounds([0.0; 3], [1.0; 3]))).is_empty());
    }

    #[test]
    fn sans_bornes_aucun_collider() {
        assert!(build_colliders(ColliderMode::AutoBox, None).is_empty());
    }

    #[test]
    fn auto_box_couvre_les_bornes_et_se_centre() {
        // Bornes asymétriques : la boîte doit être centrée sur leur milieu.
        let colliders = build_colliders(
            ColliderMode::AutoBox,
            Some(bounds([-1.0, 0.0, 2.0], [3.0, 4.0, 6.0])),
        );
        assert_eq!(colliders.len(), 1);
        let collider = colliders[0];
        assert_eq!(
            collider.shape,
            ColliderShape::Box {
                half_extents: [2.0, 2.0, 2.0],
            }
        );
        assert_eq!(collider.local.translation, [1.0, 2.0, 4.0]);
        assert!(
            collider.density > 0.0,
            "densité strictement positive (DM-06)"
        );
        assert_eq!(collider.part, NONE_U16, "aucune part attachée en T1");
        assert_eq!(collider.flags, 0, "REFITTABLE arrive plus tard");
    }
}
