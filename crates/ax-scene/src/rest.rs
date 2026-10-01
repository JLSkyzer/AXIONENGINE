//! Pose de repos d'un asset : ce qu'il faut dessiner, et où (ADR-119).
//!
//! Les sommets d'un asset sont exprimés dans l'espace de **leur node** ; c'est la
//! hiérarchie qui place chaque pièce. La liste de dessin au repos associe à
//! chaque mesh affiché la transformation monde, au repos, du node qui le porte —
//! calculée par le graphe de scène, qui en détient aussi la règle de visibilité.
//! C'est la forme statique de ce qu'IF-05 produira à chaque frame.

use crate::graph::{SceneError, SceneGraph};
use ax_math::Affine3A;
use ax_model::dm::render::RestDraw;
use ax_model::dm::scene::{NodeDesc, NONE_U32};

/// Bit du niveau de détail 0 dans `NodeDesc::lod_mask`.
const LOD0: u8 = 1;

/// Construit la liste de dessin d'un asset au repos, au niveau de détail 0.
///
/// Un node y figure s'il est **visible au sens du graphe** (R-952 : `VISIBLE`,
/// parent visible, non détaché, `INTERNAL` masqué tant qu'aucun dommage ne l'a
/// révélé — au repos, aucun ne l'est), s'il porte un mesh, et si son masque de
/// LOD retient le niveau 0. L'ordre est celui des nodes : déterministe.
///
/// # Errors
///
/// Celles de [`SceneGraph::from_nodes`] ; [`SceneError::MeshOutOfRange`] si un
/// node affiché désigne un mesh au-delà de `mesh_count` ;
/// [`SceneError::NotFiniteInAsset`] si une transformation monde n'est pas finie.
pub fn rest_draws(nodes: &[NodeDesc], mesh_count: usize) -> Result<Vec<RestDraw>, SceneError> {
    let mut graph = SceneGraph::from_nodes(nodes)?;
    graph.propagate();

    let mut draws = Vec::new();
    for (index, node) in nodes.iter().enumerate() {
        if !graph.visible().contains(index) || node.mesh == NONE_U32 || node.lod_mask & LOD0 == 0 {
            continue;
        }
        let id = index as u32;
        if node.mesh as usize >= mesh_count {
            return Err(SceneError::MeshOutOfRange {
                node: id,
                mesh: node.mesh,
                count: u32::try_from(mesh_count).unwrap_or(u32::MAX),
            });
        }
        let world = graph.worlds()[index];
        if !world.is_finite() {
            return Err(SceneError::NotFiniteInAsset { node: id });
        }
        draws.push(RestDraw {
            mesh: node.mesh,
            node: id,
            node_flags: node.flags,
            _pad: 0,
            model: mat4x3(&world),
        });
    }
    Ok(draws)
}

/// Une transformation affine en `mat4x3` colonne-major : les trois axes, puis la
/// translation (convention GLSL du bloc d'instance de §19.4).
fn mat4x3(world: &Affine3A) -> [f32; 12] {
    let axes = world.matrix3;
    let translation = world.translation;
    [
        axes.x_axis.x,
        axes.x_axis.y,
        axes.x_axis.z,
        axes.y_axis.x,
        axes.y_axis.y,
        axes.y_axis.z,
        axes.z_axis.x,
        axes.z_axis.y,
        axes.z_axis.z,
        translation.x,
        translation.y,
        translation.z,
    ]
}

#[cfg(test)]
mod tests {
    use super::*;
    use ax_model::dm::geometry::Transform;
    use ax_model::dm::scene::{node_flags, node_state, ALL_LODS, NONE_U16, NO_PARENT};
    use core::f32::consts::FRAC_PI_4;

    fn node(parent: u32, flags: u32, mesh: u32, translation: [f32; 3]) -> NodeDesc {
        NodeDesc {
            name_hash: 0,
            parent,
            local: Transform {
                translation,
                ..Transform::identity()
            },
            flags,
            mesh,
            collider: NONE_U32,
            bone: NONE_U32,
            part: NONE_U16,
            region: NONE_U16,
            lod_mask: ALL_LODS,
            state: node_state::STATIC,
            _pad: [0; 2],
        }
    }

    const VISIBLE: u32 = node_flags::VISIBLE;

    fn proche(a: f32, b: f32) -> bool {
        (a - b).abs() < 1e-5
    }

    #[test]
    fn un_node_racine_se_dessine_a_l_identite() {
        let draws = rest_draws(&[node(NO_PARENT, VISIBLE, 0, [0.0; 3])], 1).expect("liste");
        assert_eq!(draws.len(), 1);
        assert_eq!(draws[0].mesh, 0);
        assert_eq!(draws[0].node, 0);
        assert_eq!(
            draws[0].model,
            [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0]
        );
    }

    #[test]
    fn un_enfant_herite_de_la_transformation_de_son_parent() {
        // Le parent ne porte pas de mesh : il ne se dessine pas, mais il place
        // son enfant. Sans lui, la pièce serait dessinée à l'origine.
        let nodes = [
            node(NO_PARENT, VISIBLE, NONE_U32, [1.0, 0.0, 0.0]),
            node(0, VISIBLE, 0, [0.0, 2.0, 0.0]),
        ];
        let draws = rest_draws(&nodes, 1).expect("liste");
        assert_eq!(draws.len(), 1);
        assert_eq!(draws[0].node, 1);
        assert_eq!(&draws[0].model[9..12], &[1.0, 2.0, 0.0]);
    }

    #[test]
    fn la_matrice_est_colonne_major() {
        // Parent tourné de 90° autour de Y : l'axe X de l'enfant devient -Z.
        // La première colonne (indices 0..3) porte donc (0, 0, -1).
        let mut racine = node(NO_PARENT, VISIBLE, NONE_U32, [0.0; 3]);
        racine.local.rotation = [0.0, FRAC_PI_4.sin(), 0.0, FRAC_PI_4.cos()];
        let nodes = [racine, node(0, VISIBLE, 0, [1.0, 0.0, 0.0])];
        let draws = rest_draws(&nodes, 1).expect("liste");
        let m = draws[0].model;
        assert!(
            proche(m[0], 0.0) && proche(m[1], 0.0) && proche(m[2], -1.0),
            "axe X : {m:?}"
        );
        // La translation de l'enfant, tournée elle aussi : (1,0,0) -> (0,0,-1).
        assert!(proche(m[9], 0.0) && proche(m[10], 0.0) && proche(m[11], -1.0));
    }

    #[test]
    fn la_visibilite_est_celle_du_graphe() {
        let nodes = [
            // Racine masquée : elle et son enfant visible disparaissent (R-952).
            node(NO_PARENT, 0, 0, [0.0; 3]),
            node(0, VISIBLE, 1, [0.0; 3]),
            // Élément interne : masqué au repos, aucun dommage ne l'a révélé.
            node(NO_PARENT, VISIBLE | node_flags::INTERNAL, 2, [0.0; 3]),
            // Seul celui-ci se dessine.
            node(NO_PARENT, VISIBLE, 3, [0.0; 3]),
        ];
        let draws = rest_draws(&nodes, 4).expect("liste");
        assert_eq!(draws.len(), 1);
        assert_eq!(draws[0].mesh, 3);
        assert_eq!(draws[0].node, 3);
    }

    #[test]
    fn seuls_les_nodes_porteurs_d_un_mesh_au_lod_0_se_dessinent() {
        let mut hors_lod0 = node(NO_PARENT, VISIBLE, 1, [0.0; 3]);
        hors_lod0.lod_mask = 0b10;
        let nodes = [
            node(NO_PARENT, VISIBLE, NONE_U32, [0.0; 3]),
            hors_lod0,
            node(NO_PARENT, VISIBLE | node_flags::NO_CULL, 0, [0.0; 3]),
        ];
        let draws = rest_draws(&nodes, 2).expect("liste");
        assert_eq!(draws.len(), 1);
        assert_eq!(draws[0].node, 2);
        // Les drapeaux du node voyagent : NO_CULL décidera du culling côté rendu.
        assert_eq!(draws[0].node_flags, VISIBLE | node_flags::NO_CULL);
    }

    #[test]
    fn un_mesh_absent_refuse_l_asset() {
        let refus = rest_draws(&[node(NO_PARENT, VISIBLE, 5, [0.0; 3])], 2).unwrap_err();
        assert_eq!(
            refus,
            SceneError::MeshOutOfRange {
                node: 0,
                mesh: 5,
                count: 2
            }
        );
        assert_eq!(refus.code(), -3050);
    }

    #[test]
    fn une_hierarchie_hors_ordre_est_refusee_par_le_graphe() {
        // L'erreur du graphe remonte telle quelle.
        let nodes = [
            node(1, VISIBLE, 0, [0.0; 3]),
            node(NO_PARENT, VISIBLE, 0, [0.0; 3]),
        ];
        assert!(rest_draws(&nodes, 1).is_err());
    }

    #[test]
    fn un_asset_sans_node_ne_dessine_rien() {
        assert!(rest_draws(&[], 3).expect("liste").is_empty());
    }
}
