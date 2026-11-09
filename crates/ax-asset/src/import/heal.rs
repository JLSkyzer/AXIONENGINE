//! Remise en état de la géométrie importée, avant la validation (R-142, R-542, ADR-159).
//!
//! Un modèle trouvé dans la nature porte des coordonnées de texture loin de l'origine et des
//! triangles d'aire nulle : ni l'un ni l'autre ne se voit à l'écran, et aucun des deux n'empêche
//! de le dessiner. L'import y remédie, une fois, à la compilation, et le dit :
//!
//! - un triangle sorti des bornes de R-142 est ramené près de l'origine par un **décalage entier
//!   pair**, qui ne change rien en répétition ni en miroir ; ses sommets partagés avec un triangle
//!   décalé autrement sont dupliqués ;
//! - un triangle plus étendu que ces bornes est resserré autour de son centre : sa texture
//!   n'aurait de toute façon pas pu y tenir ;
//! - un triangle d'aire nulle est retiré (R-542).
//!
//! Un mesh sans rien à reprendre est recopié tel quel, au bit près (R-870). Ce qui n'est pas fini,
//! ou un indice hors du mesh, reste au validateur.

use std::collections::HashMap;

use ax_model::dm::geometry::{UvRange, Vertex};
use ax_model::dm::limits;

use super::ImportedAsset;
use crate::validate::triangle_area;

/// Ce que la remise en état a fait.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Healed {
    /// Triangles ramenés près de l'origine.
    pub shifted: usize,
    /// Triangles resserrés, plus étendus que les bornes de R-142.
    pub squeezed: usize,
    /// Triangles d'aire nulle retirés.
    pub removed: usize,
}

fn in_bounds(value: f32) -> bool {
    (limits::MIN_UV..=limits::MAX_UV).contains(&value)
}

/// Décalage entier pair qui amène `center` dans `[0, 2[`.
fn even_shift(center: f32) -> f32 {
    (center / 2.0).floor() * 2.0
}

/// Les trois coordonnées d'un triangle, dans les bornes : rend aussi s'il a été resserré.
fn fit_axis(values: [f32; 3]) -> ([f32; 3], f32, bool) {
    let center = (values[0] + values[1] + values[2]) / 3.0;
    let shift = even_shift(center);
    let moved = values.map(|value| value - shift);
    if moved.iter().copied().all(in_bounds) {
        return (moved, shift, false);
    }
    // Plus étendu que les bornes : autour de son centre, à une demi-plage de part et d'autre.
    let middle = center - shift;
    let reach = moved
        .iter()
        .map(|value| (value - middle).abs())
        .fold(0.0, f32::max);
    let room = (limits::MAX_UV - middle).min(middle - limits::MIN_UV);
    let scale = room / reach;
    (
        moved.map(|value| middle + (value - middle) * scale),
        shift,
        true,
    )
}

/// Un mesh a-t-il quelque chose à reprendre ? Non s'il porte une valeur que seul le validateur juge.
fn needs_healing(asset: &ImportedAsset, offset: usize, count: usize, indices: &[u32]) -> bool {
    let uvs = &asset.raw_uvs[offset..offset + count];
    if uvs.iter().flatten().any(|value| !value.is_finite())
        || indices.iter().any(|index| *index as usize >= count)
        || !indices.len().is_multiple_of(3)
    {
        return false;
    }
    let degenerate = |triangle: &[u32]| {
        let positions =
            [0, 1, 2].map(|corner| asset.vertices[offset + triangle[corner] as usize].position);
        triangle_area(&positions) < limits::MIN_TRIANGLE_AREA
    };
    // Un mesh dont rien ne resterait n'est pas à réparer : le validateur le refuse.
    if !indices.is_empty() && indices.chunks_exact(3).all(degenerate) {
        return false;
    }
    uvs.iter().flatten().any(|value| !in_bounds(*value)) || indices.chunks_exact(3).any(degenerate)
}

/// Remet la géométrie en état, et dit ce qui a été fait.
pub fn heal_geometry(asset: &mut ImportedAsset) -> Healed {
    let mut healed = Healed::default();
    let meshes = asset.meshes.clone();
    let any = meshes.iter().any(|mesh| {
        let first = mesh.index_offset as usize;
        needs_healing(
            asset,
            mesh.vertex_offset as usize,
            mesh.vertex_count as usize,
            &asset.indices[first..first + mesh.index_count as usize],
        )
    });
    if !any {
        return healed;
    }

    let mut vertices: Vec<Vertex> = Vec::with_capacity(asset.vertices.len());
    let mut raw_uvs: Vec<[f32; 2]> = Vec::with_capacity(asset.raw_uvs.len());
    let mut missing_normals: Vec<bool> = Vec::with_capacity(asset.missing_normals.len());
    let mut authored_tangents: Vec<bool> = Vec::with_capacity(asset.authored_tangents.len());
    let mut new_indices: Vec<u32> = Vec::with_capacity(asset.indices.len());

    for (rank, mesh) in meshes.iter().enumerate() {
        let offset = mesh.vertex_offset as usize;
        let count = mesh.vertex_count as usize;
        let first = mesh.index_offset as usize;
        let indices = &asset.indices[first..first + mesh.index_count as usize];
        let new_offset = vertices.len();
        let new_first = new_indices.len();

        if needs_healing(asset, offset, count, indices) {
            // Un sommet par couple (sommet d'origine, décalage) : les triangles qui s'accordent le partagent.
            let mut seen: HashMap<(u32, [u32; 2]), u32> = HashMap::new();
            for triangle in indices.chunks_exact(3) {
                let positions = [0, 1, 2]
                    .map(|corner| asset.vertices[offset + triangle[corner] as usize].position);
                if triangle_area(&positions) < limits::MIN_TRIANGLE_AREA {
                    healed.removed += 1;
                    continue;
                }
                let uvs = [0, 1, 2].map(|corner| asset.raw_uvs[offset + triangle[corner] as usize]);
                let outside = uvs.iter().flatten().any(|value| !in_bounds(*value));
                let (fitted, shift, squeezed) = if outside {
                    let (us, shift_u, squeezed_u) = fit_axis(uvs.map(|uv| uv[0]));
                    let (vs, shift_v, squeezed_v) = fit_axis(uvs.map(|uv| uv[1]));
                    (
                        [0, 1, 2].map(|corner| [us[corner], vs[corner]]),
                        [shift_u, shift_v],
                        squeezed_u || squeezed_v,
                    )
                } else {
                    (uvs, [0.0, 0.0], false)
                };
                if outside {
                    healed.shifted += 1;
                }
                if squeezed {
                    healed.squeezed += 1;
                }
                for corner in 0..3 {
                    let source = triangle[corner];
                    // Un triangle resserré n'a pas de voisin avec lequel partager ses sommets.
                    let key = (source, shift.map(f32::to_bits));
                    let reuse = if squeezed {
                        None
                    } else {
                        seen.get(&key).copied()
                    };
                    let index = reuse.unwrap_or_else(|| {
                        let index = u32::try_from(vertices.len() - new_offset).unwrap_or(u32::MAX);
                        let at = offset + source as usize;
                        let mut vertex = asset.vertices[at];
                        vertex.uv0 = fitted[corner].map(|value| UvRange::UNIT.quantize(value));
                        vertices.push(vertex);
                        raw_uvs.push(fitted[corner]);
                        if let Some(missing) = asset.missing_normals.get(at) {
                            missing_normals.push(*missing);
                        }
                        // Vide si la source n'écrit aucune tangente.
                        if let Some(authored) = asset.authored_tangents.get(at) {
                            authored_tangents.push(*authored);
                        }
                        if !squeezed {
                            seen.insert(key, index);
                        }
                        index
                    });
                    new_indices.push(index);
                }
            }
        } else {
            vertices.extend_from_slice(&asset.vertices[offset..offset + count]);
            raw_uvs.extend_from_slice(&asset.raw_uvs[offset..offset + count]);
            if let Some(slice) = asset.missing_normals.get(offset..offset + count) {
                missing_normals.extend_from_slice(slice);
            }
            if let Some(slice) = asset.authored_tangents.get(offset..offset + count) {
                authored_tangents.extend_from_slice(slice);
            }
            new_indices.extend_from_slice(indices);
        }

        let target = &mut asset.meshes[rank];
        target.vertex_offset = u32::try_from(new_offset).unwrap_or(u32::MAX);
        target.vertex_count = u32::try_from(vertices.len() - new_offset).unwrap_or(u32::MAX);
        target.index_offset = u32::try_from(new_first).unwrap_or(u32::MAX);
        target.index_count = u32::try_from(new_indices.len() - new_first).unwrap_or(u32::MAX);
    }

    asset.vertices = vertices;
    asset.raw_uvs = raw_uvs;
    asset.missing_normals = missing_normals;
    asset.authored_tangents = authored_tangents;
    asset.indices = new_indices;
    healed
}
