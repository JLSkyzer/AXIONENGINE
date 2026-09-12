//! Étape 1 — fusion des sommets identiques.
//!
//! # Tolérances déclarées
//!
//! Deux sommets fusionnent quand ils sont **identiques au bit près une fois
//! quantifiés** au format canonique, et portent le même marqueur de normale
//! absente. Les tolérances sont donc celles de la quantification faite par les
//! importeurs, et nulles au-delà :
//!
//! | Attribut | Tolérance |
//! |---|---|
//! | position | aucune : `f32` identiques, `+0.0` et `-0.0` confondus |
//! | normale, tangente | un pas `i8` normalisé, 1/127 |
//! | UV | un pas `UNORM16`, 1/65535 |
//! | poids d'os, couleur | un pas `UNORM8`, 1/255 |
//! | os, région, poids de déformation | aucune |
//!
//! Aucune tolérance de position n'est admise, et c'est délibéré : rapprocher
//! deux positions distinctes peut aplatir un triangle que C-22 a accepté, et
//! l'asset écrit ne serait plus celui qui a été validé. À positions égales, une
//! fusion ne change la géométrie d'aucun triangle.
//!
//! # Ordre de sortie
//!
//! Les sommets sortent dans l'ordre de leur **première apparition dans le
//! tampon d'indices**, et ceux qu'aucun triangle ne référence sont retirés.
//! L'ordre est stable d'une exécution à l'autre (R-553) et rapproche en mémoire
//! les sommets d'un même triangle.

use crate::import::ImportedAsset;
use ax_model::dm::geometry::Vertex;
use core::cmp::Ordering;

/// Aucun nouvel index attribué à ce sommet.
const UNASSIGNED: u32 = u32::MAX;

/// Fusionne les sommets identiques de chaque mesh.
///
/// Les indices restent locaux au mesh ; offsets et dénombrements sont mis à
/// jour, `raw_uvs` et `missing_normals` suivent leurs sommets.
pub(super) fn merge_vertices(asset: &mut ImportedAsset) {
    let mut vertices = Vec::with_capacity(asset.vertices.len());
    let mut raw_uvs = Vec::with_capacity(asset.vertices.len());
    let mut missing_normals = Vec::with_capacity(asset.vertices.len());
    let mut indices = Vec::with_capacity(asset.indices.len());

    // Réutilisés d'un mesh à l'autre.
    let mut order: Vec<u32> = Vec::new();
    let mut representative: Vec<u32> = Vec::new();
    let mut remap: Vec<u32> = Vec::new();

    for mesh in &mut asset.meshes {
        let vertex_offset = mesh.vertex_offset as usize;
        let source = &asset.vertices[vertex_offset..vertex_offset + mesh.vertex_count as usize];
        let missing = |local: usize| {
            asset
                .missing_normals
                .get(vertex_offset + local)
                .copied()
                .unwrap_or(false)
        };

        // Tri des sommets, départagé par l'index d'origine : l'ordre est total,
        // et chaque groupe de sommets identiques commence par le plus ancien.
        order.clear();
        order.extend(0..mesh.vertex_count);
        order.sort_unstable_by(|&a, &b| {
            let (a, b) = (a as usize, b as usize);
            compare(&source[a], missing(a), &source[b], missing(b)).then(a.cmp(&b))
        });

        representative.clear();
        representative.resize(source.len(), 0);
        let mut group = 0u32;
        for (rank, &local) in order.iter().enumerate() {
            let starts_group = rank == 0 || {
                let previous = order[rank - 1] as usize;
                let current = local as usize;
                compare(
                    &source[previous],
                    missing(previous),
                    &source[current],
                    missing(current),
                ) != Ordering::Equal
            };
            if starts_group {
                group = local;
            }
            representative[local as usize] = group;
        }

        // Compaction dans l'ordre de première apparition.
        remap.clear();
        remap.resize(source.len(), UNASSIGNED);
        let new_vertex_offset = vertices.len();
        let new_index_offset = indices.len();
        let index_offset = mesh.index_offset as usize;
        for &local in &asset.indices[index_offset..index_offset + mesh.index_count as usize] {
            let kept = representative[local as usize] as usize;
            if remap[kept] == UNASSIGNED {
                remap[kept] = (vertices.len() - new_vertex_offset) as u32;
                vertices.push(source[kept]);
                raw_uvs.push(
                    asset
                        .raw_uvs
                        .get(vertex_offset + kept)
                        .copied()
                        .unwrap_or([0.0; 2]),
                );
                missing_normals.push(missing(kept));
            }
            indices.push(remap[kept]);
        }

        mesh.vertex_offset = new_vertex_offset as u32;
        mesh.vertex_count = (vertices.len() - new_vertex_offset) as u32;
        mesh.index_offset = new_index_offset as u32;
    }

    asset.vertices = vertices;
    asset.raw_uvs = raw_uvs;
    asset.missing_normals = missing_normals;
    asset.indices = indices;
}

/// Ordre total sur les sommets quantifiés.
///
/// Le remplissage `_pad` n'y entre pas : il est réservé, et écrit à zéro quelle
/// que soit sa valeur en mémoire.
fn compare(a: &Vertex, a_missing: bool, b: &Vertex, b_missing: bool) -> Ordering {
    position_key(a)
        .cmp(&position_key(b))
        .then_with(|| a.normal.cmp(&b.normal))
        .then_with(|| a.tangent.cmp(&b.tangent))
        .then_with(|| a.uv0.cmp(&b.uv0))
        .then_with(|| a.uv1.cmp(&b.uv1))
        .then_with(|| a.color.cmp(&b.color))
        .then_with(|| a.bones.cmp(&b.bones))
        .then_with(|| a.weights.cmp(&b.weights))
        .then_with(|| a.region.cmp(&b.region))
        .then_with(|| a.def_w.cmp(&b.def_w))
        .then_with(|| a_missing.cmp(&b_missing))
}

/// Clé de position : les bits du `f32`, les deux zéros confondus.
///
/// `-0.0` et `+0.0` désignent le même point, mais leurs bits diffèrent. Ne pas
/// les confondre laisserait une couture là où un exportateur a écrit un signe
/// différent pour la même coordonnée. Les NaN n'ont pas à être traités : C-22
/// les a refusés.
fn position_key(vertex: &Vertex) -> [u32; 3] {
    vertex
        .position
        .map(|value| if value == 0.0 { 0 } else { value.to_bits() })
}

#[cfg(test)]
mod tests {
    use super::super::tests::{asset, sommet};
    use super::*;
    use ax_model::dm::geometry::MeshDesc;
    use ax_model::dm::scene::NONE_U16;

    #[test]
    fn t240_deux_sommets_identiques_fusionnent() {
        // Un quadrilatère dont la diagonale est dupliquée, comme l'écrit un
        // exportateur qui émet ses triangles un par un.
        let vertices = vec![
            sommet([0.0, 0.0, 0.0]),
            sommet([1.0, 0.0, 0.0]),
            sommet([1.0, 1.0, 0.0]),
            sommet([0.0, 0.0, 0.0]),
            sommet([1.0, 1.0, 0.0]),
            sommet([0.0, 1.0, 0.0]),
        ];
        let mut asset = asset(vertices, vec![0, 1, 2, 3, 4, 5], true);
        merge_vertices(&mut asset);

        assert_eq!(asset.vertices.len(), 4);
        assert_eq!(asset.indices, [0, 1, 2, 0, 2, 3]);
        assert_eq!(asset.meshes[0].vertex_count, 4);
        assert_eq!(asset.raw_uvs.len(), 4);
        assert_eq!(asset.missing_normals.len(), 4);
    }

    #[test]
    fn t240_une_normale_differente_empeche_la_fusion() {
        // Une arête vive : même position, deux normales. Les fusionner
        // lisserait ce que l'auteur a voulu anguleux.
        let mut a = sommet([0.0, 0.0, 0.0]);
        a.normal = [0, 0, 127, 0];
        let mut b = a;
        b.normal = [127, 0, 0, 0];
        let vertices = vec![
            a,
            sommet([1.0, 0.0, 0.0]),
            sommet([0.0, 1.0, 0.0]),
            b,
            sommet([0.0, 1.0, 0.0]),
            sommet([0.0, 0.0, 1.0]),
        ];
        let mut asset = asset(vertices, vec![0, 1, 2, 3, 4, 5], false);
        merge_vertices(&mut asset);

        assert_eq!(asset.vertices.len(), 5);
        assert_eq!(asset.vertices[0].normal, [0, 0, 127, 0]);
        assert_eq!(asset.vertices[3].normal, [127, 0, 0, 0]);
    }

    #[test]
    fn t240_une_normale_absente_ne_fusionne_pas_avec_une_normale_ecrite() {
        let mut ecrite = sommet([0.0, 0.0, 0.0]);
        ecrite.normal = [0; 4];
        let vertices = vec![ecrite, ecrite, sommet([1.0, 0.0, 0.0])];
        let mut asset = asset(vertices, vec![0, 1, 2, 1, 0, 2], false);
        // Même valeur `[0; 4]`, mais l'une est absente et l'autre écrite nulle :
        // C-23 génère la première, C-22 refuse la seconde.
        asset.missing_normals[0] = true;
        merge_vertices(&mut asset);

        assert_eq!(asset.vertices.len(), 3);
        assert_eq!(asset.missing_normals, [true, false, false]);
    }

    #[test]
    fn t240_les_deux_zeros_designent_le_meme_point() {
        let vertices = vec![
            sommet([0.0, 0.0, 0.0]),
            sommet([1.0, 0.0, 0.0]),
            sommet([0.0, 1.0, 0.0]),
            sommet([-0.0, 0.0, -0.0]),
        ];
        let mut asset = asset(vertices, vec![0, 1, 2, 3, 1, 2], true);
        merge_vertices(&mut asset);

        assert_eq!(asset.vertices.len(), 3);
        assert_eq!(asset.indices, [0, 1, 2, 0, 1, 2]);
    }

    #[test]
    fn t240_une_position_voisine_ne_fusionne_pas() {
        // Aucune tolérance de position : rapprocher deux points distincts peut
        // aplatir un triangle que C-22 a accepté.
        let voisin = f32::from_bits(0.0f32.to_bits() + 1);
        let vertices = vec![
            sommet([0.0, 0.0, 0.0]),
            sommet([1.0, 0.0, 0.0]),
            sommet([0.0, 1.0, 0.0]),
            sommet([voisin, 0.0, 0.0]),
        ];
        let mut asset = asset(vertices, vec![0, 1, 2, 3, 1, 2], true);
        merge_vertices(&mut asset);

        assert_eq!(asset.vertices.len(), 4);
    }

    #[test]
    fn t240_un_sommet_sans_triangle_est_retire_et_l_ordre_suit_les_indices() {
        let vertices = vec![
            sommet([9.0, 9.0, 9.0]),
            sommet([0.0, 1.0, 0.0]),
            sommet([1.0, 0.0, 0.0]),
            sommet([0.0, 0.0, 0.0]),
        ];
        let mut asset = asset(vertices, vec![3, 2, 1], true);
        merge_vertices(&mut asset);

        assert_eq!(asset.indices, [0, 1, 2]);
        assert_eq!(
            asset
                .vertices
                .iter()
                .map(|vertex| vertex.position)
                .collect::<Vec<_>>(),
            [[0.0, 0.0, 0.0], [1.0, 0.0, 0.0], [0.0, 1.0, 0.0]]
        );
    }

    #[test]
    fn t240_la_fusion_ne_traverse_pas_les_meshes() {
        // Deux meshes, un triangle chacun, aux positions identiques : leurs
        // indices sont locaux, et un sommet ne peut appartenir qu'à un mesh.
        let triangle = [
            sommet([0.0, 0.0, 0.0]),
            sommet([1.0, 0.0, 0.0]),
            sommet([0.0, 1.0, 0.0]),
        ];
        let mut asset = asset(triangle.repeat(2), vec![0, 1, 2, 0, 1, 2], true);
        asset.meshes[0].vertex_count = 3;
        asset.meshes[0].index_count = 3;
        asset.meshes.push(MeshDesc {
            vertex_offset: 3,
            vertex_count: 3,
            index_offset: 3,
            index_count: 3,
            region: NONE_U16,
            ..asset.meshes[0]
        });
        merge_vertices(&mut asset);

        assert_eq!(asset.vertices.len(), 6);
        assert_eq!(asset.indices, [0, 1, 2, 0, 1, 2]);
        assert_eq!(asset.meshes[1].vertex_offset, 3);
        assert_eq!(asset.meshes[1].index_offset, 3);
    }
}
