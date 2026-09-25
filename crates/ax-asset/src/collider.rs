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
    /// Une sphère englobante (`auto_sphere`).
    AutoSphere,
}

impl ColliderMode {
    /// Traduit la valeur textuelle d'un extra `shape` (C-32).
    ///
    /// Rend `Some` pour une forme automatique **prise en charge** (`auto_box`,
    /// `auto_sphere`), `None` sinon. Les formes reconnues du CDC mais pas encore
    /// générées (`auto_capsule`, `auto_convex`, `auto_compound`) rendent aussi
    /// `None` : l'appelant avertit et n'attache pas de collider, plutôt que d'en
    /// fabriquer un que l'auteur n'a pas décrit.
    #[must_use]
    pub fn parse(shape: &str) -> Option<Self> {
        match shape {
            "auto_box" => Some(ColliderMode::AutoBox),
            "auto_sphere" => Some(ColliderMode::AutoSphere),
            _ => None,
        }
    }
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
        (ColliderMode::AutoSphere, Some(bounds)) => vec![auto_sphere(bounds)],
        _ => Vec::new(),
    }
}

/// Milieu des bornes.
fn center(bounds: Aabb) -> [f32; 3] {
    [
        (bounds.max[0] + bounds.min[0]) * 0.5,
        (bounds.max[1] + bounds.min[1]) * 0.5,
        (bounds.max[2] + bounds.min[2]) * 0.5,
    ]
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
    primitive_collider(ColliderShape::Box { half_extents }, center(bounds))
}

/// Génère une sphère englobante depuis les bornes de l'asset (`auto_sphere`),
/// centrée sur le milieu des bornes.
///
/// Le rayon vaut la **demi-diagonale** de la boîte : la sphère contient alors ses
/// huit coins, donc toute la géométrie qu'elle borne. C'est un englobant lâche
/// mais correct — jamais plus petit que l'objet.
fn auto_sphere(bounds: Aabb) -> ColliderDesc {
    let dx = bounds.max[0] - bounds.min[0];
    let dy = bounds.max[1] - bounds.min[1];
    let dz = bounds.max[2] - bounds.min[2];
    let radius = 0.5 * (dx * dx + dy * dy + dz * dz).sqrt();
    primitive_collider(ColliderShape::Sphere { radius }, center(bounds))
}

/// Construit un `ColliderDesc` pour une forme primitive auto-générée, centrée en
/// `translation`, avec les défauts communs (densité, groupes, références absentes).
fn primitive_collider(shape: ColliderShape, translation: [f32; 3]) -> ColliderDesc {
    ColliderDesc {
        shape,
        local: Transform {
            translation,
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
    fn parse_reconnait_les_formes_prises_en_charge() {
        assert_eq!(ColliderMode::parse("auto_box"), Some(ColliderMode::AutoBox));
        assert_eq!(
            ColliderMode::parse("auto_sphere"),
            Some(ColliderMode::AutoSphere)
        );
        // Reconnues du CDC mais pas encore générées, et inconnues : `None`.
        assert_eq!(ColliderMode::parse("auto_convex"), None);
        assert_eq!(ColliderMode::parse("auto_compound"), None);
        assert_eq!(ColliderMode::parse("teleporteur"), None);
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

    #[test]
    fn auto_sphere_contient_les_coins() {
        // Cube [0,2]³ : la sphère est centrée en (1,1,1) et de rayon égal à la
        // demi-diagonale (√3), donc passe exactement par les huit coins.
        let colliders = build_colliders(ColliderMode::AutoSphere, Some(bounds([0.0; 3], [2.0; 3])));
        assert_eq!(colliders.len(), 1);
        let collider = colliders[0];
        assert_eq!(collider.local.translation, [1.0, 1.0, 1.0]);
        let ColliderShape::Sphere { radius } = collider.shape else {
            panic!("attendu une sphère, obtenu {:?}", collider.shape);
        };
        let demi_diagonale = 3.0_f32.sqrt();
        assert!(
            (radius - demi_diagonale).abs() < 1.0e-5,
            "rayon = demi-diagonale, obtenu {radius}"
        );
        // Un coin est à distance √3 du centre : la sphère l'atteint (englobe).
        assert!(
            radius >= demi_diagonale - 1.0e-5,
            "la sphère doit contenir les coins"
        );
    }
}
