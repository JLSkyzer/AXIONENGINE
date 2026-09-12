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
//! | 3. tangentes MikkTSpace si normal map | module `tangents`, puis fusion rejouée |
//! | 4. quantification vers le format `Vertex` canonique | importeurs, C-21 |
//! | 5. cache de sommets et localité | module `cache`, puis fusion rejouée |
//! | 7. AABB par mesh et par asset | module `bounds` |
//!
//! Les étapes 6, 8 et 9 — LOD, décomposition convexe, points d'enveloppe —
//! sont les tranches suivantes du jalon M2 ; l'étape 11 est l'écriture du
//! conteneur, dans `compile`.
//!
//! # Déterminisme (R-553)
//!
//! Mêmes entrées et même version, même sortie **au bit près** (T-243).
//!
//! - **aucune table de hachage.** L'ordre d'itération d'une `HashMap` est semé
//!   au hasard à chaque exécution ; la fusion procède par tri, départagé par
//!   l'index d'origine, ce qui en fait un ordre total ;
//! - **fusion, normales et boîtes : aucune fonction de `libm`.** Addition,
//!   soustraction, multiplication, division et racine carrée sont arrondies
//!   exactement par IEEE 754, et Rust ne les contracte jamais en FMA. L'arc
//!   tangente dont la pondération par angle a besoin est écrite ici avec ces
//!   seules opérations : celle de la bibliothèque C n'est pas tenue d'être
//!   correctement arrondie, et ne l'est pas de la même façon d'une plateforme à
//!   l'autre. Ces étapes sont donc identiques **entre plateformes** ;
//! - **tangentes et cache de sommets : identiques sur une même plateforme.**
//!   MikkTSpace pondère par `acos`, meshoptimizer est du C++ dont le
//!   compilateur peut contracter les flottants. Ces sorties ne servent qu'au
//!   rendu, et les assets ne passent jamais par le réseau (R-1640) : c'est la
//!   garantie dont elles ont l'usage. Voir `docs/decisions/ADR-106.md`.

mod bounds;
mod cache;
mod merge;
mod normals;
mod tangents;

pub use bounds::Aabb;

use crate::import::ImportedAsset;

/// Ce que l'optimizer a fait d'un asset.
#[derive(Debug, Default, Clone, PartialEq)]
pub struct OptimizeReport {
    /// Sommets retirés, en solde : fusionnés avec un sommet identique ou
    /// référencés par aucun triangle, moins ceux que les coutures de tangentes
    /// ont dédoublés.
    pub removed_vertices: usize,
    /// Normales générées depuis la géométrie.
    pub generated_normals: usize,
    /// Meshes dont les tangentes ont été générées.
    pub tangent_meshes: usize,
    /// Boîte englobante de l'asset, en espace asset ; `None` si aucun node ne
    /// porte de géométrie.
    pub bounds: Option<Aabb>,
    /// Avertissements, à journaliser une fois (R-912).
    pub warnings: Vec<String>,
}

/// Optimise un asset validé par C-22.
///
/// L'asset doit avoir passé [`crate::validate::validate`] : l'optimizer indexe
/// ses tableaux sans revérifier les bornes que le validateur garantit.
pub(crate) fn optimize(asset: &mut ImportedAsset) -> OptimizeReport {
    let before = asset.vertices.len();
    let mut warnings = Vec::new();

    merge::merge_vertices(asset);
    let generated_normals = normals::generate_missing(asset);

    // Les tangentes suivent les normales, qu'elles doivent connaître. Le mesh
    // traité est déplié en coins : la fusion rejouée refond ce qui est redevenu
    // identique et ne laisse dédoublées que les coutures.
    let tangent_meshes = tangents::generate(asset, &mut warnings);
    if tangent_meshes > 0 {
        merge::merge_vertices(asset);
    }

    // Les triangles sont réordonnés pour le cache, puis la fusion rejouée range
    // les sommets dans l'ordre où ce nouveau tampon les lit.
    cache::optimize_vertex_cache(asset);
    merge::merge_vertices(asset);

    // Les boîtes se calculent en dernier : la fusion a pu retirer des sommets
    // qui les élargissaient sans être rendus.
    bounds::update_mesh_bounds(asset);

    OptimizeReport {
        removed_vertices: before.saturating_sub(asset.vertices.len()),
        generated_normals,
        tangent_meshes,
        bounds: bounds::asset_bounds(asset),
        warnings,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::import::ImportedMaterial;
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

    /// Un cube sans normale dont chaque face porte ses propres sommets, UV
    /// couvrant la face.
    fn cube() -> ImportedAsset {
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
        let uvs = [[0, 0], [u16::MAX, 0], [u16::MAX, u16::MAX], [0, u16::MAX]];
        let mut vertices = Vec::new();
        let mut indices = Vec::new();
        for face in faces {
            let base = vertices.len() as u32;
            vertices.extend(face.iter().zip(uvs).map(|(coin, uv)| {
                let mut vertex = sommet(coins[*coin]);
                vertex.uv0 = uv;
                vertex
            }));
            indices.extend([base, base + 1, base + 2, base, base + 2, base + 3]);
        }
        asset(vertices, indices, true)
    }

    #[test]
    fn t243_l_optimizer_est_deterministe_au_bit_pres() {
        // Sans normal map : fusion, génération, cache et boîtes ont tous du
        // travail, les tangentes non.
        let mut source = cube();
        for vertex in &mut source.vertices {
            vertex.uv0 = [0; 2];
        }

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
        assert_eq!(rapport_une.tangent_meshes, 0);
        assert!(une.missing_normals.iter().all(|missing| !missing));
    }

    #[test]
    fn t243_les_tangentes_et_le_cache_sont_deterministes() {
        let mut source = cube();
        source.materials.push(ImportedMaterial {
            name: "carrosserie".to_owned(),
            base_color: [1.0; 4],
            base_color_texture: None,
            has_normal_map: true,
        });

        let mut une = source.clone();
        let mut deux = source.clone();
        let rapport_une = optimize(&mut une);
        let rapport_deux = optimize(&mut deux);

        assert_eq!(rapport_une, rapport_deux);
        assert_eq!(une, deux);
        assert_eq!(rapport_une.tangent_meshes, 1);
        assert!(
            rapport_une.warnings.is_empty(),
            "{:?}",
            rapport_une.warnings
        );
        assert!(une.vertices.iter().all(|vertex| vertex.tangent != [0; 4]));
    }
}
