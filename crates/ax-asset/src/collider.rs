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
//! Formes prises en charge : `auto_box`, `auto_sphere`, `auto_capsule`, `convex`
//! (enveloppe des sommets du mesh) et `auto_compound` (R-621). Seule `auto_convex`
//! (décomposition concave V-HACD, étape C-23) n'est **pas encore générée** : une
//! requête qui la vise est **avertie et ignorée** (R-912), jamais remplacée par une
//! forme que l'auteur n'a pas décrite. Le marquage REFITTABLE (R-623) et la
//! masse/COM déclarée (R-622) suivent avec leurs consommateurs.

use crate::optimize::Aabb;
use ax_model::dm::geometry::{MeshDesc, Transform, Vertex};
use ax_model::dm::physics::{
    collider_flags, ColliderDesc, ColliderShape, CONVEX_MAX_POINTS, CONVEX_MIN_POINTS,
};
use ax_model::dm::scene::{NodeDesc, NONE_U32};

/// Densité par défaut d'un collider auto-généré, en kg/m³.
///
/// Densité de l'eau — une valeur physique, pas un chiffre inventé —, employée
/// tant qu'aucun matériau ni definition ne la fournit (R-622, tranche masse/COM).
/// [`ColliderDesc`] exige une densité strictement positive : ce défaut la
/// garantit.
const AUTO_COLLIDER_DENSITY: f32 = 1000.0;

/// Nombre maximal de formes filles d'un composé (§10.3, R-621) : au-delà,
/// `auto_compound` avertit et n'en regroupe pas davantage.
const MAX_COMPOUND_PARTS: usize = 64;

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
    /// Une capsule englobante d'axe Y (`auto_capsule`).
    ///
    /// Le rayon couvre l'étendue en X et Z, la hauteur du segment couvre l'étendue
    /// en Y au-delà du rayon. La capsule du moteur est d'axe Y (DM-06) : une forme
    /// allongée selon X ou Z est donc englobée lâchement — un mesh non vertical se
    /// décrit mieux par `convex` ou `auto_box`.
    AutoCapsule,
    /// Une enveloppe convexe des sommets du mesh (`convex`).
    ///
    /// L'auteur déclare que le mesh du node est (ou peut être traité comme) convexe :
    /// ses sommets deviennent les points d'enveloppe (R-161, 4..256). Distinct
    /// d'`auto_convex`, qui **décompose** une forme concave en plusieurs convexes
    /// (V-HACD, R-162) et reste différé.
    Convex,
    /// Un composé regroupant les boîtes des nodes enfants (`auto_compound`, R-621).
    ///
    /// Le mode recommandé pour une carrosserie : chaque node enfant du node porteur
    /// contribue une boîte englobante de son mesh, placée à sa pose, réunies en un
    /// seul collider `Compound` (une pièce rigide, un matériau).
    Compound,
}

impl ColliderMode {
    /// Traduit la valeur textuelle d'un extra `shape` (C-32).
    ///
    /// Rend `Some` pour une forme **prise en charge** (`auto_box`, `auto_sphere`,
    /// `auto_capsule`, `convex`, `auto_compound`), `None` sinon. `auto_convex`
    /// (V-HACD), reconnue du CDC mais pas encore générée, rend aussi `None` :
    /// l'appelant avertit et n'attache pas de collider, plutôt que d'en fabriquer un
    /// que l'auteur n'a pas décrit.
    #[must_use]
    pub fn parse(shape: &str) -> Option<Self> {
        match shape {
            "auto_box" => Some(ColliderMode::AutoBox),
            "auto_sphere" => Some(ColliderMode::AutoSphere),
            "auto_capsule" => Some(ColliderMode::AutoCapsule),
            "convex" => Some(ColliderMode::Convex),
            "auto_compound" => Some(ColliderMode::Compound),
            _ => None,
        }
    }
}

/// Requête de collider issue des extras d'un node (R-620, priorité 1).
///
/// Construite à l'import pour chaque node `role=collider` dont la forme est prise
/// en charge ; consommée par [`build_colliders`], qui tire la géométrie de la
/// boîte du mesh du node.
// `Eq` n'est pas dérivable : `density` porte un `f32`. `PartialEq` suffit aux tests.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ColliderRequest {
    /// Index du node, dans l'ordre des nodes de l'asset.
    pub node: u32,
    /// Forme automatique demandée (prise en charge : `AutoBox`, `AutoSphere`,
    /// `AutoCapsule`, `Convex`, `Compound`).
    pub mode: ColliderMode,
    /// Masse volumique déclarée (kg/m³, R-622), ou le défaut du générateur.
    pub density: Option<f32>,
    /// Collider figé au refit (`NO_REFIT`, R-623).
    pub no_refit: bool,
}

/// Ce que [`build_colliders`] produit : les colliders et les avertissements.
#[derive(Debug, Default, Clone, PartialEq)]
pub struct ColliderBuild {
    /// Colliders générés (DM-06), dans l'ordre de production.
    pub colliders: Vec<ColliderDesc>,
    /// Points d'enveloppe convexe, référencés par les formes `ConvexHull`
    /// (`points_offset`/`points_count`, en indices). Sérialisés dans `PHYS`.
    pub hull_points: Vec<[f32; 3]>,
    /// Formes filles des composés, référencées par les formes `Compound`
    /// (`children_offset`/`children_count`, en indices). Sérialisées dans `PHYS`.
    pub compound_children: Vec<ColliderDesc>,
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
    vertices: &[Vertex],
    requests: &[ColliderRequest],
    definition_mode: ColliderMode,
    bounds: Option<Aabb>,
) -> ColliderBuild {
    if !requests.is_empty() {
        return per_node_colliders(nodes, meshes, vertices, requests);
    }
    // Par definition : englobant de l'asset. `Convex` n'a pas de source à ce
    // niveau (il tire ses points d'un mesh de node), aussi n'y est-il pas produit.
    let colliders = match (definition_mode, bounds) {
        (ColliderMode::AutoBox, Some(bounds)) => vec![auto_box(bounds)],
        (ColliderMode::AutoSphere, Some(bounds)) => vec![auto_sphere(bounds)],
        _ => Vec::new(),
    };
    ColliderBuild {
        colliders,
        hull_points: Vec::new(),
        compound_children: Vec::new(),
        warnings: Vec::new(),
    }
}

/// Génère un collider par requête, depuis la boîte du mesh du node visé, et lie
/// le node à son collider.
fn per_node_colliders(
    nodes: &mut [NodeDesc],
    meshes: &[MeshDesc],
    vertices: &[Vertex],
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
        // `Compound` regroupe les enfants : il ne dépend pas du mesh propre du
        // node porteur (qui peut ne pas en avoir). Les autres modes tirent leur
        // forme du mesh du node.
        let mut collider = if request.mode == ColliderMode::Compound {
            match compound_from_children(request.node, nodes, meshes, &mut build.compound_children)
            {
                Ok(collider) => collider,
                Err(reason) => {
                    build
                        .warnings
                        .push(format!("node collider {} : {reason}, ignoré", request.node));
                    continue;
                }
            }
        } else {
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
            match request.mode {
                ColliderMode::AutoBox => auto_box(aabb),
                ColliderMode::AutoSphere => auto_sphere(aabb),
                ColliderMode::AutoCapsule => auto_capsule(aabb),
                ColliderMode::Convex => {
                    match convex_from_mesh(mesh, vertices, &mut build.hull_points) {
                        Ok(collider) => collider,
                        Err(reason) => {
                            build
                                .warnings
                                .push(format!("node collider {} : {reason}, ignoré", request.node));
                            continue;
                        }
                    }
                }
                // `Compound` est traité plus haut ; `None` n'arrive pas en requête.
                ColliderMode::Compound | ColliderMode::None => continue,
            }
        };
        // Le collider hérite de la part du node : c'est ce lien que R-623 lira
        // pour décider du marquage REFITTABLE.
        collider.part = part;
        // Surcharges déclarées par les extras (R-620/R-622/R-623) : la densité
        // prime sur le défaut du générateur (la validation C-22 exige `> 0`) ;
        // `no_refit` fige le collider au refit.
        if let Some(density) = request.density {
            collider.density = density;
        }
        if request.no_refit {
            collider.flags |= collider_flags::NO_REFIT;
        }
        let index = build.colliders.len() as u32;
        if let Some(node) = nodes.get_mut(request.node as usize) {
            node.collider = index;
        }
        build.colliders.push(collider);
    }
    build
}

/// Construit un collider `ConvexHull` depuis les sommets du mesh d'un node
/// `role=collider shape=convex` : leurs positions deviennent les points d'enveloppe,
/// ajoutés au `pool`. rapier calcule l'enveloppe elle-même au runtime.
///
/// Rend `Err(raison)` si les sommets sortent du tampon, ou si leur nombre est hors
/// de `[4, 256]` (R-161) — réduire un nuage plus dense relève d'`auto_convex`
/// (V-HACD, R-162), pas encore disponible ; un mesh trop maigre n'est pas un volume.
fn convex_from_mesh(
    mesh: &MeshDesc,
    vertices: &[Vertex],
    pool: &mut Vec<[f32; 3]>,
) -> Result<ColliderDesc, String> {
    let start = mesh.vertex_offset as usize;
    let end = start.saturating_add(mesh.vertex_count as usize);
    let Some(slice) = vertices.get(start..end) else {
        return Err("sommets du mesh hors du tampon".to_owned());
    };
    let point_count = slice.len();
    if point_count < CONVEX_MIN_POINTS as usize {
        return Err(format!(
            "enveloppe convexe de {point_count} points, moins que le minimum {CONVEX_MIN_POINTS}"
        ));
    }
    if point_count > CONVEX_MAX_POINTS as usize {
        return Err(format!(
            "enveloppe convexe de {point_count} points, plus que {CONVEX_MAX_POINTS} — \
             la réduction (auto_convex/V-HACD) n'est pas encore disponible"
        ));
    }
    let offset = u32::try_from(pool.len()).map_err(|_| "pool de points saturé".to_owned())?;
    pool.extend(slice.iter().map(|vertex| vertex.position));
    // Les points sont déjà dans le repère du mesh : le collider n'a pas de
    // translation de recentrage (contrairement à auto_box/auto_sphere).
    Ok(primitive_collider(
        ColliderShape::ConvexHull {
            points_offset: offset,
            points_count: point_count as u32,
        },
        [0.0; 3],
    ))
}

/// Construit un collider `Compound` (R-621) regroupant les boîtes des nodes enfants
/// du node `parent` : chaque enfant portant un mesh contribue une boîte englobante,
/// placée à la pose du node enfant, ajoutée au pool d'enfants.
///
/// La pose de chaque boîte compose la pose du node enfant et le centre de sa boîte.
/// La composition est exacte quand le node enfant n'a pas de rotation (le cas d'une
/// carrosserie et du test) ; sous rotation, le décalage du centre n'est pas encore
/// tourné — un raffinement quand des pièces tournées de compound arriveront.
///
/// Rend `Err(raison)` s'il n'y a aucun enfant avec mesh à regrouper.
fn compound_from_children(
    parent: u32,
    nodes: &[NodeDesc],
    meshes: &[MeshDesc],
    pool: &mut Vec<ColliderDesc>,
) -> Result<ColliderDesc, String> {
    let offset = u32::try_from(pool.len()).map_err(|_| "pool d'enfants saturé".to_owned())?;
    let mut count = 0u32;
    for node in nodes.iter().filter(|node| node.parent == parent) {
        if node.mesh == NONE_U32 {
            continue;
        }
        let Some(mesh) = meshes.get(node.mesh as usize) else {
            continue;
        };
        if count as usize >= MAX_COMPOUND_PARTS {
            return Err(format!("plus de {MAX_COMPOUND_PARTS} formes filles"));
        }
        let aabb = Aabb {
            min: mesh.aabb_min,
            max: mesh.aabb_max,
        };
        // Boîte du mesh (centrée sur le centre de l'AABB), placée à la pose du node
        // enfant : translation additionnée, rotation et échelle transmises.
        let mut child = auto_box(aabb);
        let center = child.local.translation;
        child.local.translation = [
            node.local.translation[0] + center[0],
            node.local.translation[1] + center[1],
            node.local.translation[2] + center[2],
        ];
        child.local.rotation = node.local.rotation;
        child.local.scale = node.local.scale;
        pool.push(child);
        count += 1;
    }
    if count == 0 {
        return Err("aucun enfant avec mesh à regrouper".to_owned());
    }
    // Le composé porte sa propre pose à l'identité : les enfants portent la leur.
    Ok(primitive_collider(
        ColliderShape::Compound {
            children_offset: offset,
            children_count: count,
        },
        [0.0; 3],
    ))
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

/// Génère une capsule englobante d'axe Y depuis les bornes (`auto_capsule`),
/// centrée sur le milieu des bornes.
///
/// Le rayon vaut la plus grande demi-dimension en X ou Z — la section circulaire
/// couvre les deux —, et la demi-hauteur du segment couvre ce qui dépasse en Y.
/// Quand la demi-hauteur en Y n'excède pas le rayon (mesh trapu), le segment est
/// nul et la capsule dégénère en sphère de ce rayon : un englobant lâche mais
/// correct, jamais plus petit que l'objet.
fn auto_capsule(bounds: Aabb) -> ColliderDesc {
    let half_x = (bounds.max[0] - bounds.min[0]) * 0.5;
    let half_y = (bounds.max[1] - bounds.min[1]) * 0.5;
    let half_z = (bounds.max[2] - bounds.min[2]) * 0.5;
    let radius = half_x.max(half_z);
    let half_height = (half_y - radius).max(0.0);
    primitive_collider(
        ColliderShape::Capsule {
            half_height,
            radius,
        },
        center(bounds),
    )
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
        build_colliders(&mut [], &[], &[], &[], mode, bounds).colliders
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
        assert_eq!(
            ColliderMode::parse("auto_capsule"),
            Some(ColliderMode::AutoCapsule)
        );
        assert_eq!(ColliderMode::parse("convex"), Some(ColliderMode::Convex));
        assert_eq!(
            ColliderMode::parse("auto_compound"),
            Some(ColliderMode::Compound)
        );
        // `auto_convex` (décomposition V-HACD) est distinct de `convex` (une seule
        // enveloppe des sommets du mesh) et reste différé ; inconnu : `None`.
        assert_eq!(ColliderMode::parse("auto_convex"), None);
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
            density: None,
            no_refit: false,
        }];

        let build = build_colliders(
            &mut nodes,
            &meshes,
            &[],
            &requests,
            ColliderMode::None,
            None,
        );

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
            density: None,
            no_refit: false,
        }];

        let build = build_colliders(&mut nodes, &[], &[], &requests, ColliderMode::None, None);

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
            density: None,
            no_refit: false,
        }];

        let build = build_colliders(
            &mut nodes,
            &meshes,
            &[],
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

    /// Un sommet de test à la position donnée (autres champs neutres).
    fn sommet(position: [f32; 3]) -> Vertex {
        Vertex {
            position,
            normal: [0; 4],
            tangent: [0; 4],
            uv0: [0; 2],
            uv1: [0; 2],
            color: [255; 4],
            bones: [0; 4],
            weights: [255, 0, 0, 0],
            region: ax_model::dm::geometry::NO_REGION_U8,
            def_w: 0,
            _pad: [0; 6],
        }
    }

    #[test]
    fn un_node_convex_produit_une_enveloppe_de_ses_sommets() {
        // Un node `shape: convex` : les sommets de son mesh deviennent les points
        // d'enveloppe (R-161), ajoutés au pool ; le collider les référence par
        // offset/count.
        let mut nodes = vec![node(0, NONE_U16)];
        let meshes = vec![MeshDesc {
            vertex_offset: 0,
            vertex_count: 4,
            ..mesh([0.0; 3], [1.0; 3])
        }];
        // Un tétraèdre : quatre sommets non coplanaires.
        let vertices = vec![
            sommet([0.0, 0.0, 0.0]),
            sommet([1.0, 0.0, 0.0]),
            sommet([0.0, 1.0, 0.0]),
            sommet([0.0, 0.0, 1.0]),
        ];
        let requests = vec![ColliderRequest {
            node: 0,
            mode: ColliderMode::Convex,
            density: None,
            no_refit: false,
        }];

        let build = build_colliders(
            &mut nodes,
            &meshes,
            &vertices,
            &requests,
            ColliderMode::None,
            None,
        );

        assert_eq!(build.colliders.len(), 1);
        assert_eq!(
            build.colliders[0].shape,
            ColliderShape::ConvexHull {
                points_offset: 0,
                points_count: 4,
            }
        );
        assert!(build.warnings.is_empty());
        assert_eq!(
            build.hull_points,
            vec![
                [0.0, 0.0, 0.0],
                [1.0, 0.0, 0.0],
                [0.0, 1.0, 0.0],
                [0.0, 0.0, 1.0],
            ]
        );
        assert_eq!(nodes[0].collider, 0, "le node est lié à son collider");
    }

    #[test]
    fn un_node_convex_sans_assez_de_sommets_est_averti() {
        // Trois sommets ne forment pas un volume (R-161 : au moins quatre).
        let mut nodes = vec![node(0, NONE_U16)];
        let meshes = vec![MeshDesc {
            vertex_offset: 0,
            vertex_count: 3,
            ..mesh([0.0; 3], [1.0; 3])
        }];
        let vertices = vec![
            sommet([0.0, 0.0, 0.0]),
            sommet([1.0, 0.0, 0.0]),
            sommet([0.0, 1.0, 0.0]),
        ];
        let requests = vec![ColliderRequest {
            node: 0,
            mode: ColliderMode::Convex,
            density: None,
            no_refit: false,
        }];

        let build = build_colliders(
            &mut nodes,
            &meshes,
            &vertices,
            &requests,
            ColliderMode::None,
            None,
        );

        assert!(build.colliders.is_empty(), "aucune enveloppe sous 4 points");
        assert_eq!(build.warnings.len(), 1, "l'ignorance est avertie (R-912)");
        assert!(build.hull_points.is_empty());
    }

    #[test]
    fn auto_compound_regroupe_les_boites_des_enfants() {
        // Node porteur (index 0, sans mesh) et deux enfants avec mesh, en ±x.
        let parent = node(NONE_U32, NONE_U16);
        let mut enfant_gauche = node(0, NONE_U16);
        enfant_gauche.parent = 0;
        enfant_gauche.local.translation = [-1.0, 0.0, 0.0];
        let mut enfant_droit = node(1, NONE_U16);
        enfant_droit.parent = 0;
        enfant_droit.local.translation = [1.0, 0.0, 0.0];
        let mut nodes = vec![parent, enfant_gauche, enfant_droit];
        // Deux meshes centrés sur l'origine : le centre de l'AABB est nul, donc la
        // pose de chaque boîte est exactement celle du node enfant.
        let meshes = vec![mesh([-0.5; 3], [0.5; 3]), mesh([-0.5; 3], [0.5; 3])];
        let requests = vec![ColliderRequest {
            node: 0,
            mode: ColliderMode::Compound,
            density: None,
            no_refit: false,
        }];

        let build = build_colliders(
            &mut nodes,
            &meshes,
            &[],
            &requests,
            ColliderMode::None,
            None,
        );

        // Un seul collider (le compound) sur le node porteur ; deux enfants.
        assert_eq!(build.colliders.len(), 1);
        assert_eq!(
            build.colliders[0].shape,
            ColliderShape::Compound {
                children_offset: 0,
                children_count: 2,
            }
        );
        assert_eq!(nodes[0].collider, 0, "le node porteur est lié au compound");
        assert_eq!(build.compound_children.len(), 2);
        // Chaque enfant est une boîte, placée à la pose de son node.
        for child in &build.compound_children {
            assert!(matches!(child.shape, ColliderShape::Box { .. }));
        }
        assert_eq!(
            build.compound_children[0].local.translation,
            [-1.0, 0.0, 0.0]
        );
        assert_eq!(
            build.compound_children[1].local.translation,
            [1.0, 0.0, 0.0]
        );
        assert!(build.warnings.is_empty());
    }

    #[test]
    fn auto_compound_sans_enfant_avec_mesh_est_averti() {
        // Un node porteur sans enfant : rien à regrouper.
        let mut nodes = vec![node(NONE_U32, NONE_U16)];
        let requests = vec![ColliderRequest {
            node: 0,
            mode: ColliderMode::Compound,
            density: None,
            no_refit: false,
        }];

        let build = build_colliders(&mut nodes, &[], &[], &requests, ColliderMode::None, None);

        assert!(build.colliders.is_empty(), "aucun compound sans enfant");
        assert_eq!(build.warnings.len(), 1, "l'ignorance est avertie (R-912)");
        assert!(build.compound_children.is_empty());
    }

    #[test]
    fn un_node_auto_capsule_recoit_une_capsule_depuis_son_mesh() {
        // Un mesh haut (Y) et étroit (X, Z) : demi-dimensions [0.5, 2.0, 0.5].
        let mut nodes = vec![node(0, NONE_U16)];
        let meshes = vec![mesh([-0.5, -2.0, -0.5], [0.5, 2.0, 0.5])];
        let requests = vec![ColliderRequest {
            node: 0,
            mode: ColliderMode::AutoCapsule,
            density: None,
            no_refit: false,
        }];

        let build = build_colliders(
            &mut nodes,
            &meshes,
            &[],
            &requests,
            ColliderMode::None,
            None,
        );

        assert_eq!(build.colliders.len(), 1);
        let ColliderShape::Capsule {
            half_height,
            radius,
        } = build.colliders[0].shape
        else {
            panic!("attendu une capsule, obtenu {:?}", build.colliders[0].shape);
        };
        assert_eq!(radius, 0.5, "rayon = max(demi-X, demi-Z)");
        assert_eq!(
            half_height, 1.5,
            "demi-hauteur = demi-Y − rayon = 2.0 − 0.5"
        );
        assert!(build.warnings.is_empty());
    }

    #[test]
    fn auto_capsule_trapue_degenere_en_sphere() {
        // Un mesh large et plat : demi-dimensions [2.0, 0.5, 2.0]. Le rayon couvre
        // X/Z et déjà Y, donc le segment est nul (capsule = sphère englobante).
        let mut nodes = vec![node(0, NONE_U16)];
        let meshes = vec![mesh([-2.0, -0.5, -2.0], [2.0, 0.5, 2.0])];
        let requests = vec![ColliderRequest {
            node: 0,
            mode: ColliderMode::AutoCapsule,
            density: None,
            no_refit: false,
        }];

        let build = build_colliders(
            &mut nodes,
            &meshes,
            &[],
            &requests,
            ColliderMode::None,
            None,
        );

        let ColliderShape::Capsule {
            half_height,
            radius,
        } = build.colliders[0].shape
        else {
            panic!("attendu une capsule");
        };
        assert_eq!(radius, 2.0);
        assert_eq!(half_height, 0.0, "segment nul : la capsule est une sphère");
    }

    #[test]
    fn les_extras_density_et_no_refit_surchargent_le_collider() {
        // Densité déclarée (acier) et no_refit posés par les extras du node.
        let mut nodes = vec![node(0, NONE_U16)];
        let meshes = vec![mesh([0.0; 3], [1.0; 3])];
        let requests = vec![ColliderRequest {
            node: 0,
            mode: ColliderMode::AutoBox,
            density: Some(7850.0),
            no_refit: true,
        }];

        let build = build_colliders(
            &mut nodes,
            &meshes,
            &[],
            &requests,
            ColliderMode::None,
            None,
        );

        assert_eq!(build.colliders.len(), 1);
        assert_eq!(
            build.colliders[0].density, 7850.0,
            "la densité déclarée prime sur le défaut du générateur"
        );
        assert_ne!(
            build.colliders[0].flags & collider_flags::NO_REFIT,
            0,
            "NO_REFIT posé (R-623)"
        );
    }
}
