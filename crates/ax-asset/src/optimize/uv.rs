//! Étape 4 — coordonnées de texture ramenées dans `[0, 1]` (R-142, ADR-122 §4).
//!
//! Les importeurs conservent les UV de la source telles quelles, dans
//! `raw_uvs`, et n'en quantifient que la part comprise dans `[0, 1]` : une
//! texture répétée y serait écrasée. Ici, chaque mesh reçoit la plus petite
//! plage entière qui contient ses UV — commune à U et V, `[0, 1]` dès qu'elle
//! suffit —, et ses sommets sont requantifiés dans cette plage.
//!
//! L'étape vient **avant** la fusion : deux sommets ne fusionnent qu'identiques
//! une fois quantifiés, et c'est la quantification finale qui doit en décider.
//! Fusion et LOD conservent ensuite la plage de leur mesh : la fusion ne
//! touche pas aux descripteurs, et un LOD copie celui de sa source.
//!
//! Aucune fonction de `libm` : `floor`, `ceil` et `round` sont exacts, le reste
//! n'est qu'additions, soustractions, multiplications et une division. L'étape
//! est donc identique entre plateformes (R-553).

use crate::import::ImportedAsset;
use ax_model::dm::geometry::UvRange;

/// Ramène les UV de chaque mesh dans sa plage, et l'inscrit dans le mesh.
///
/// C-22 a refusé toute UV non finie ou hors de `[-8, 9]` : la plage existe
/// toujours. Un mesh sans sommet reçoit `[0, 1]`.
pub(super) fn normalize_uvs(asset: &mut ImportedAsset) {
    for mesh in &mut asset.meshes {
        let start = mesh.vertex_offset as usize;
        let end = start + mesh.vertex_count as usize;
        let Some(raw) = asset.raw_uvs.get(start..end) else {
            // Sans UV brutes, rien à requantifier : le mesh garde ce que
            // l'import a quantifié, dans `[0, 1]`.
            continue;
        };
        let range = extent(raw)
            .and_then(|(low, high)| UvRange::covering(low, high))
            .unwrap_or(UvRange::UNIT);
        mesh.uv0_range = range.to_bits();
        for (vertex, uv) in asset.vertices[start..end].iter_mut().zip(raw) {
            vertex.uv0 = uv.map(|value| range.quantize(value));
        }
    }
}

/// Plus petite et plus grande coordonnée, U et V confondus ; `None` sans
/// sommet.
fn extent(uvs: &[[f32; 2]]) -> Option<(f32, f32)> {
    let mut values = uvs.iter().flatten();
    let first = *values.next()?;
    Some(values.fold((first, first), |(low, high), value| {
        (low.min(*value), high.max(*value))
    }))
}

#[cfg(test)]
mod tests {
    use super::super::tests::{asset, sommet};
    use super::*;

    /// Un triangle dont les UV brutes sont données.
    fn triangle(uvs: [[f32; 2]; 3]) -> ImportedAsset {
        let mut asset = asset(
            vec![
                sommet([0.0, 0.0, 0.0]),
                sommet([1.0, 0.0, 0.0]),
                sommet([0.0, 1.0, 0.0]),
            ],
            vec![0, 1, 2],
            false,
        );
        asset.raw_uvs = uvs.to_vec();
        asset
    }

    #[test]
    fn t240_un_mesh_dans_l_unite_garde_la_plage_zero() {
        let mut asset = triangle([[0.0, 0.0], [0.5, 1.0], [1.0, 0.25]]);
        normalize_uvs(&mut asset);
        assert_eq!(asset.meshes[0].uv0_range, 0);
        let uvs: Vec<[u16; 2]> = asset.vertices.iter().map(|vertex| vertex.uv0).collect();
        assert_eq!(uvs, [[0, 0], [32768, 65535], [65535, 16384]]);
    }

    #[test]
    fn t240_une_texture_repetee_garde_ses_tuiles() {
        // Un sol répété trois fois : la première version écrêtait tout à 1, et
        // la texture s'étirait d'une seule tuile sur tout le sol.
        let mut asset = triangle([[0.0, 0.0], [3.0, 0.0], [0.0, 1.5]]);
        normalize_uvs(&mut asset);

        let plage = UvRange::from_bits(asset.meshes[0].uv0_range).expect("plage valide");
        assert_eq!((plage.min(), plage.span()), (0, 3));
        let relues: Vec<[f32; 2]> = asset
            .vertices
            .iter()
            .map(|vertex| vertex.uv0.map(|q| plage.dequantize(q)))
            .collect();
        for (relue, brute) in relues.iter().zip(&asset.raw_uvs) {
            for axe in 0..2 {
                assert!(
                    (relue[axe] - brute[axe]).abs() <= 3.0 / 65535.0,
                    "{relue:?} contre {brute:?}"
                );
            }
        }
        // Les bornes de la plage sont atteintes exactement.
        assert_eq!(asset.vertices[1].uv0[0], u16::MAX);
        assert_eq!(asset.vertices[0].uv0, [0, 0]);
    }

    #[test]
    fn t240_une_plage_negative_et_commune_a_u_et_v() {
        // U dans [-0,5 ; 0,5], V dans [0 ; 2] : une plage commune, [-1, 2].
        let mut asset = triangle([[-0.5, 0.0], [0.5, 2.0], [0.0, 1.0]]);
        normalize_uvs(&mut asset);
        let plage = UvRange::from_bits(asset.meshes[0].uv0_range).expect("plage valide");
        assert_eq!((plage.min(), plage.span()), (-1, 3));
    }

    #[test]
    fn t240_chaque_mesh_a_sa_plage() {
        let mut asset = triangle([[0.0, 0.0], [1.0, 0.0], [0.0, 1.0]]);
        // Un second mesh, sur trois sommets de plus, répété deux fois.
        let mut second = asset.meshes[0];
        second.vertex_offset = 3;
        asset.meshes.push(second);
        asset.vertices.extend_from_within(0..3);
        asset.raw_uvs.extend([[0.0, 0.0], [2.0, 0.0], [0.0, 2.0]]);
        asset.missing_normals.extend([false; 3]);

        normalize_uvs(&mut asset);
        assert_eq!(asset.meshes[0].uv0_range, 0);
        assert_eq!(
            UvRange::from_bits(asset.meshes[1].uv0_range),
            UvRange::new(0, 2)
        );
        assert_eq!(asset.vertices[4].uv0, [u16::MAX, 0]);
        assert_eq!(asset.vertices[1].uv0, [u16::MAX, 0]);
    }

    #[test]
    fn t240_sans_uv_brutes_le_mesh_est_laisse_tel_quel() {
        let mut asset = triangle([[0.0, 0.0], [3.0, 0.0], [0.0, 1.0]]);
        asset.raw_uvs.clear();
        let avant = asset.clone();
        normalize_uvs(&mut asset);
        assert_eq!(asset, avant);
    }
}
