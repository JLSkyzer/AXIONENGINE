//! Génération des colliders à la compilation (C-32, fiche 5.24).
//!
//! Un collider ([`ColliderDesc`], DM-06) peut venir, par priorité (R-620) : des
//! extras d'un node → d'une definition → d'une génération automatique
//! (`auto_box`, `auto_sphere`, `auto_capsule`, `auto_convex`, `auto_compound`)
//! → d'aucune source. La génération est faite **à la compilation** (R-620),
//! jamais au runtime.
//!
//! # État
//!
//! Deux sources sont câblées, dans l'ordre de priorité R-620 :
//!
//! 1. **extras de node** — un node `role=collider` porte une forme (`shape`)
//!    résolue en [`ColliderRequest`] à l'import ; son collider est tiré de la
//!    boîte de **son** mesh. C'est la voie principale d'un asset authoré ;
//! 2. **definition** — à défaut de tout node collider, un [`ColliderMode`]
//!    demandé pour l'asset entier tire un englobant de ses bornes.
//!
//! Le défaut est [`ColliderMode::None`] et aucune requête : rien n'est généré
//! sans demande explicite.
//!
//! Formes prises en charge : `auto_box`, `auto_sphere`. `auto_capsule`,
//! `auto_convex` (V-HACD, étape C-23) et `auto_compound` (R-621) sont reconnues
//! mais pas encore générées : une requête qui les vise est **avertie et ignorée**
//! (R-912), jamais remplacée par une forme que l'auteur n'a pas décrite. La
//! masse/le COM (R-622), le marquage REFITTABLE (R-623) et la sérialisation vers
//! la section A3D `PHYS` (gel derrière ADR) suivent.

use crate::optimize::Aabb;
use ax_model::dm::geometry::{MeshDesc, Transform};
use ax_model::dm::physics::{ColliderDesc, ColliderShape};
use ax_model::dm::scene::{NodeDesc, NONE_U32};

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

/// Requête de collider issue des extras d'un node (R-620, priorité 1).
///
/// Construite à l'import pour chaque node `role=collider` dont la forme est prise
/// en charge ; consommée par [`build_colliders`], qui tire la géométrie de la
/// boîte du mesh du node.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ColliderRequest {
    /// Index du node, dans l'ordre des nodes de l'asset.
    pub node: u32,
    /// Forme automatique demandée (prise en charge : `AutoBox`, `AutoSphere`).
    pub mode: ColliderMode,
}

/// Ce que [`build_colliders`] produit : les colliders et les avertissements.
#[derive(Debug, Default, Clone, PartialEq)]
pub struct ColliderBuild {
    /// Colliders générés (DM-06), dans l'ordre de production.
    pub colliders: Vec<ColliderDesc>,
    /// Avertissements (R-912), à journaliser une fois.
    pub warnings: Vec<String>,
}

/// Produit les colliders d'un asset (C-32).
///
/// Priorité R-620 : si des `requests` (extras de node) existent, les colliders en
/// sont tirés — un par node, depuis la boîte de son mesh. Sinon, `definition_mode`
/// tire un englobant des `bounds` de l'asset (l'optimizer les calcule, C-23 étape
/// 7). `nodes` est muté pour lier chaque node à son collider (`node.collider`).
#[must_use]
pub fn build_colliders(
    nodes: &mut [NodeDesc],
    meshes: &[MeshDesc],
    requests: &[ColliderRequest],
    definition_mode: ColliderMode,
    bounds: Option<Aabb>,
) -> ColliderBuild {
    if !requests.is_empty() {
        return per_node_colliders(nodes, meshes, requests);
    }
    let colliders = match (definition_mode, bounds) {
        (ColliderMode::AutoBox, Some(bounds)) => vec![auto_box(bounds)],
        (ColliderMode::AutoSphere, Some(bounds)) => vec![auto_sphere(bounds)],
        _ => Vec::new(),
    };
    ColliderBuild {
        colliders,
        warnings: Vec::new(),
    }
}

/// Génère un collider par requête, depuis la boîte du mesh du node visé, et lie
/// le node à son collider.
fn per_node_colliders(
    nodes: &mut [NodeDesc],
    meshes: &[MeshDesc],
    requests: &[ColliderRequest],
) -> ColliderBuild {
    let mut build = ColliderBuild::default();
    for request in requests {
        // On relève d'abord ce dont on a besoin, pour clore l'emprunt en lecture
        // du node avant de le muter.
        let Some((mesh_index, part)) = nodes
            .get(request.node as usize)
            .map(|node| (node.mesh, node.part))
        else {
            continue;
        };
        if mesh_index == NONE_U32 {
            build
                .warnings
                .push(format!("node collider {} sans mesh, ignoré", request.node));
            continue;
        }
        let Some(mesh) = meshes.get(mesh_index as usize) else {
            build
                .warnings
                .push(format!("node collider {} : mesh introuvable", request.node));
            continue;
        };
        let aabb = Aabb {
            min: mesh.aabb_min,
            max: mesh.aabb_max,
        };
        let mut collider = match request.mode {
            ColliderMode::AutoBox => auto_box(aabb),
            ColliderMode::AutoSphere => auto_sphere(aabb),
            // Une requête ne porte que des formes prises en charge : les autres
            // sont écartées à l'import, avant d'arriver ici.
            ColliderMode::None => continue,
        };
        // Le collider hérite de la part du node : c'est ce lien que R-623 lira
        // pour décider du marquage REFITTABLE.
        collider.part = part;
        let index = build.colliders.len() as u32;
        if let Some(node) = nodes.get_mut(request.node as usize) {
            node.collider = index;
        }
        build.colliders.push(collider);
    }
    build
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

    /// Colliders produits par la source **definition** (aucune requête de node).
    fn from_definition(mode: ColliderMode, bounds: Option<Aabb>) -> Vec<ColliderDesc> {
        build_colliders(&mut [], &[], &[], mode, bounds).colliders
    }

    /// Un node de test référençant un mesh et une part.
    fn node(mesh: u32, part: u16) -> NodeDesc {
        NodeDesc {
            name_hash: 0,
            parent: u32::MAX,
            local: Transform::identity(),
            flags: 0,
            mesh,
            collider: NONE_U32,
            bone: NONE_U32,
            part,
            region: NONE_U16,
            lod_mask: u8::MAX,
            state: 0,
            _pad: [0; 2],
        }
    }

    /// Un mesh de test réduit à sa boîte englobante.
    fn mesh(aabb_min: [f32; 3], aabb_max: [f32; 3]) -> MeshDesc {
        MeshDesc {
            vertex_offset: 0,
            vertex_count: 0,
            index_offset: 0,
            index_count: 0,
            material: 0,
            lod: 0,
            flags: 0,
            aabb_min,
            aabb_max,
            region: NONE_U16,
            _pad: 0,
        }
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
        assert!(from_definition(ColliderMode::None, Some(bounds([0.0; 3], [1.0; 3]))).is_empty());
    }

    #[test]
    fn sans_bornes_aucun_collider() {
        assert!(from_definition(ColliderMode::AutoBox, None).is_empty());
    }

    #[test]
    fn auto_box_couvre_les_bornes_et_se_centre() {
        // Bornes asymétriques : la boîte doit être centrée sur leur milieu.
        let colliders = from_definition(
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
        assert_eq!(collider.flags, 0, "REFITTABLE arrive plus tard");
    }

    #[test]
    fn auto_sphere_contient_les_coins() {
        // Cube [0,2]³ : la sphère est centrée en (1,1,1) et de rayon égal à la
        // demi-diagonale (√3), donc passe exactement par les huit coins.
        let colliders = from_definition(ColliderMode::AutoSphere, Some(bounds([0.0; 3], [2.0; 3])));
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

    #[test]
    fn un_node_collider_recoit_une_boite_depuis_son_mesh() {
        // La requête (extras de node) prime sur le mode de definition.
        let mut nodes = vec![node(0, 5)];
        let meshes = vec![mesh([0.0, 0.0, 0.0], [2.0, 4.0, 6.0])];
        let requests = vec![ColliderRequest {
            node: 0,
            mode: ColliderMode::AutoBox,
        }];

        let build = build_colliders(&mut nodes, &meshes, &requests, ColliderMode::None, None);

        assert_eq!(build.colliders.len(), 1);
        assert_eq!(
            build.colliders[0].shape,
            ColliderShape::Box {
                half_extents: [1.0, 2.0, 3.0],
            }
        );
        assert_eq!(
            build.colliders[0].part, 5,
            "le collider hérite de la part du node"
        );
        assert_eq!(nodes[0].collider, 0, "le node est lié à son collider");
        assert!(build.warnings.is_empty());
    }

    #[test]
    fn un_node_collider_sans_mesh_est_averti_et_ignore() {
        let mut nodes = vec![node(NONE_U32, NONE_U16)];
        let requests = vec![ColliderRequest {
            node: 0,
            mode: ColliderMode::AutoBox,
        }];

        let build = build_colliders(&mut nodes, &[], &requests, ColliderMode::None, None);

        assert!(build.colliders.is_empty(), "aucun collider sans mesh");
        assert_eq!(build.warnings.len(), 1, "l'ignorance est avertie (R-912)");
        assert_eq!(nodes[0].collider, NONE_U32, "aucun lien posé");
    }

    #[test]
    fn les_requetes_l_emportent_sur_le_mode_definition() {
        // Un node collider présent : le mode de definition n'est pas consulté.
        let mut nodes = vec![node(0, NONE_U16)];
        let meshes = vec![mesh([0.0; 3], [1.0; 3])];
        let requests = vec![ColliderRequest {
            node: 0,
            mode: ColliderMode::AutoSphere,
        }];

        let build = build_colliders(
            &mut nodes,
            &meshes,
            &requests,
            ColliderMode::AutoBox,
            Some(bounds([0.0; 3], [10.0; 3])),
        );

        assert_eq!(build.colliders.len(), 1, "une seule source à la fois");
        assert!(
            matches!(build.colliders[0].shape, ColliderShape::Sphere { .. }),
            "la requête de node (sphère) prime sur le mode definition (boîte)"
        );
    }
}
