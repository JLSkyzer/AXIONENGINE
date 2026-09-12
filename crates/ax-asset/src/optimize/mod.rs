//! C-23 — Asset Optimizer.
//!
//! L'optimizer reçoit un asset **déjà validé** : l'ordre fixé par le cahier des
//! charges est C-21 → C-22 → C-23 → C-28 → C-24. C'est ce qui lui permet de ne
//! pas être défensif — indices dans les bornes, positions finies, triangles
//! d'aire non nulle sont acquis. En contrepartie, `compile` fait repasser sa
//! sortie par le validateur, sans exemption : un défaut de l'optimizer y
//! devient un refus nommé plutôt qu'un asset écrit en cache.
//!
//! # Étapes de la fiche C-23 portées ici
//!
//! | Étape | Où |
//! |---|---|
//! | 1. fusion des vertices identiques, tolérances déclarées | module `merge` |
//! | 2. génération des normales manquantes, angle-weighted | module `normals` |
//! | 4. quantification vers le format `Vertex` canonique | importeurs, C-21 |
//! | 7. AABB par mesh et par asset | module `bounds` |
//!
//! Les étapes 3, 5, 6, 8 et 9 — tangentes MikkTSpace, cache de sommets, LOD,
//! décomposition convexe, points d'enveloppe — sont les tranches suivantes du
//! jalon M2 ; l'étape 11 est l'écriture du conteneur, dans `compile`.
//!
//! # Déterminisme (R-553)
//!
//! Mêmes entrées et même version, même sortie **au bit près** (T-243). Deux
//! choses y veillent :
//!
//! - **aucune table de hachage.** L'ordre d'itération d'une `HashMap` est semé
//!   au hasard à chaque exécution ; la fusion procède par tri, départagé par
//!   l'index d'origine, ce qui en fait un ordre total ;
//! - **aucune fonction de `libm`.** Addition, soustraction, multiplication,
//!   division et racine carrée sont arrondies exactement par IEEE 754, et Rust
//!   ne les contracte jamais en FMA. L'arc tangente dont la pondération par
//!   angle a besoin est écrite ici avec ces seules opérations : celle de la
//!   bibliothèque C n'est pas tenue d'être correctement arrondie, et ne l'est
//!   pas de la même façon d'une plateforme à l'autre.

mod bounds;
mod merge;
mod normals;

pub use bounds::Aabb;

use crate::import::ImportedAsset;

/// Ce que l'optimizer a fait d'un asset.
#[derive(Debug, Default, Clone, Copy, PartialEq)]
pub struct OptimizeReport {
    /// Sommets retirés : fusionnés avec un sommet identique, ou référencés par
    /// aucun triangle.
    pub removed_vertices: usize,
    /// Normales générées depuis la géométrie.
    pub generated_normals: usize,
    /// Boîte englobante de l'asset, en espace asset ; `None` si aucun node ne
    /// porte de géométrie.
    pub bounds: Option<Aabb>,
}

/// Optimise un asset validé par C-22.
///
/// L'asset doit avoir passé [`crate::validate::validate`] : l'optimizer indexe
/// ses tableaux sans revérifier les bornes que le validateur garantit.
pub(crate) fn optimize(asset: &mut ImportedAsset) -> OptimizeReport {
    let before = asset.vertices.len();
    merge::merge_vertices(asset);
    let removed_vertices = before - asset.vertices.len();

    let generated_normals = normals::generate_missing(asset);

    // Les boîtes se calculent en dernier : la fusion a pu retirer des sommets
    // qui les élargissaient sans être rendus.
    bounds::update_mesh_bounds(asset);

    OptimizeReport {
        removed_vertices,
        generated_normals,
        bounds: bounds::asset_bounds(asset),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ax_model::dm::geometry::{MeshDesc, Transform, Vertex, NO_REGION_U8};
    use ax_model::dm::scene::{node_flags, NodeDesc, NONE_U16, NONE_U32, NO_PARENT};

    pub(super) fn sommet(position: [f32; 3]) -> Vertex {
        Vertex {
            position,
            normal: [0; 4],
            tangent: [0; 4],
            uv0: [0; 2],
            uv1: [0; 2],
            color: [255; 4],
            bones: [0; 4],
            weights: [255, 0, 0, 0],
            region: NO_REGION_U8,
            def_w: 0,
            _pad: [0; 6],
        }
    }

    pub(super) fn node(mesh: u32, parent: u32, local: Transform) -> NodeDesc {
        NodeDesc {
            name_hash: 0,
            parent,
            local,
            flags: node_flags::VISIBLE,
            mesh,
            collider: NONE_U32,
            bone: NONE_U32,
            part: NONE_U16,
            region: NONE_U16,
            lod_mask: 1,
            state: 0,
            _pad: [0; 2],
        }
    }

    /// Un asset d'un seul mesh, porté par un node racine.
    ///
    /// La boîte englobante est laissée fausse exprès : c'est à l'optimizer de
    /// la recalculer.
    pub(super) fn asset(vertices: Vec<Vertex>, indices: Vec<u32>, missing: bool) -> ImportedAsset {
        let count = vertices.len();
        ImportedAsset {
            nodes: vec![node(0, NO_PARENT, Transform::identity())],
            meshes: vec![MeshDesc {
                vertex_offset: 0,
                vertex_count: count as u32,
                index_offset: 0,
                index_count: indices.len() as u32,
                material: 0,
                lod: 0,
                flags: 0,
                aabb_min: [-1000.0; 3],
                aabb_max: [1000.0; 3],
                region: NONE_U16,
                _pad: 0,
            }],
            raw_uvs: vec![[0.0; 2]; count],
            missing_normals: vec![missing; count],
            vertices,
            indices,
            ..ImportedAsset::default()
        }
    }

    #[test]
    fn t243_l_optimizer_est_deterministe_au_bit_pres() {
        // Un cube sans normale dont chaque face porte ses propres sommets :
        // fusion, génération et boîtes ont toutes du travail.
        let coins = [
            [0.0, 0.0, 0.0],
            [1.0, 0.0, 0.0],
            [1.0, 1.0, 0.0],
            [0.0, 1.0, 0.0],
            [0.0, 0.0, 1.0],
            [1.0, 0.0, 1.0],
            [1.0, 1.0, 1.0],
            [0.0, 1.0, 1.0],
        ];
        let faces: [[usize; 4]; 6] = [
            [0, 3, 2, 1],
            [4, 5, 6, 7],
            [0, 1, 5, 4],
            [2, 3, 7, 6],
            [1, 2, 6, 5],
            [0, 4, 7, 3],
        ];
        let mut vertices = Vec::new();
        let mut indices = Vec::new();
        for face in faces {
            let base = vertices.len() as u32;
            vertices.extend(face.iter().map(|coin| sommet(coins[*coin])));
            indices.extend([base, base + 1, base + 2, base, base + 2, base + 3]);
        }
        let source = asset(vertices, indices, true);

        let mut une = source.clone();
        let mut deux = source.clone();
        let rapport_une = optimize(&mut une);
        let rapport_deux = optimize(&mut deux);

        assert_eq!(rapport_une, rapport_deux);
        assert_eq!(une, deux);
        // Le cube fermé ne garde que ses huit coins, chacun avec une normale.
        assert_eq!(une.vertices.len(), 8);
        assert_eq!(rapport_une.removed_vertices, 16);
        assert_eq!(rapport_une.generated_normals, 8);
        assert!(une.missing_normals.iter().all(|missing| !missing));
    }
}
