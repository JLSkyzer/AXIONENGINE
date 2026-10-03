//! Étape 3 — tangentes MikkTSpace, pour les meshes à normal map.
//!
//! Une normal map se lit dans l'espace tangent de la surface, et cet espace
//! doit être **celui dans lequel elle a été cuite**. MikkTSpace est l'algorithme
//! de Blender et la convention de glTF : générer les tangentes autrement
//! produirait un éclairage faux sur toute carte cuite par ces outils. D'où la
//! bibliothèque de référence plutôt qu'une réécriture.
//!
//! # Condition
//!
//! La fiche C-23 génère les tangentes « si normal map ou parallax ». La normal
//! map se lit dans le matériau source ; le parallax n'a **aucune source à la
//! compilation** — le slot `height` est résolu au rendu par C-26 — et n'entre
//! donc pas dans la condition tant qu'aucun format importé ne le porte. Un
//! mesh dont toutes les tangentes sont écrites par la source est laissé tel
//! quel.
//!
//! # Coutures
//!
//! MikkTSpace rend une tangente **par coin de triangle**. Un sommet partagé par
//! deux triangles dont les UV sont en miroir reçoit deux tangentes de signes
//! opposés : il doit être dédoublé. Le mesh est donc déplié en un sommet par
//! coin, puis la fusion de l'étape 1 est rejouée — elle refond tout ce qui est
//! redevenu identique, tangente comprise, et ne laisse dédoublés que les
//! sommets de couture.
//!
//! # Entrées
//!
//! Normales et UV sont lues **quantifiées**, telles que le shader les verra.
//! Les tangentes s'accordent ainsi aux valeurs rendues, pas à des flottants de
//! source que le rendu ne connaît plus.

use crate::import::{ImportedAsset, ImportedMaterial};
use ax_model::dm::geometry::{encode_tangent, Vertex};

/// Génère les tangentes des meshes qui en demandent ; rend le nombre de meshes
/// traités.
///
/// Les sommets des meshes traités sont dépliés en un sommet par coin : la
/// fusion doit être rejouée ensuite.
pub(super) fn generate(asset: &mut ImportedAsset, warnings: &mut Vec<String>) -> usize {
    let needs: Vec<bool> = asset
        .meshes
        .iter()
        .map(|mesh| {
            let offset = mesh.vertex_offset as usize;
            let normal_map = asset
                .materials
                .get(usize::from(mesh.material))
                .is_some_and(ImportedMaterial::has_normal_map);
            normal_map
                && mesh.vertex_count > 0
                && (0..mesh.vertex_count as usize)
                    .any(|local| !flag(&asset.authored_tangents, offset + local))
        })
        .collect();
    if !needs.contains(&true) {
        return 0;
    }

    let mut vertices = Vec::with_capacity(asset.vertices.len());
    let mut raw_uvs = Vec::with_capacity(asset.vertices.len());
    let mut missing_normals = Vec::with_capacity(asset.vertices.len());
    let mut authored_tangents = Vec::with_capacity(asset.vertices.len());
    let mut indices = Vec::with_capacity(asset.indices.len());
    let mut processed = 0;

    for (rank, mesh) in asset.meshes.iter_mut().enumerate() {
        let offset = mesh.vertex_offset as usize;
        let source = &asset.vertices[offset..offset + mesh.vertex_count as usize];
        let index_offset = mesh.index_offset as usize;
        let local_indices = &asset.indices[index_offset..index_offset + mesh.index_count as usize];
        let new_vertex_offset = vertices.len();
        let new_index_offset = indices.len();

        let generated = if needs[rank] {
            let mut corners = Corners::new(source, local_indices);
            if mikktspace::generate_tangents(&mut corners) {
                Some(corners.tangents)
            } else {
                warnings.push(format!(
                    "mesh {rank} : MikkTSpace n'a pas pu générer de tangentes, \
                     elles restent nulles et la normal map sera mal éclairée"
                ));
                None
            }
        } else {
            None
        };

        if let Some(tangents) = generated {
            processed += 1;
            for (corner, &local) in local_indices.iter().enumerate() {
                let global = offset + local as usize;
                let authored = flag(&asset.authored_tangents, global);
                let mut vertex = source[local as usize];
                if !authored {
                    vertex.tangent = encode_tangent(tangents[corner]);
                }
                vertices.push(vertex);
                raw_uvs.push(asset.raw_uvs.get(global).copied().unwrap_or([0.0; 2]));
                missing_normals.push(flag(&asset.missing_normals, global));
                authored_tangents.push(authored);
                indices.push(corner as u32);
            }
            mesh.vertex_count = local_indices.len() as u32;
        } else {
            for (local, vertex) in source.iter().enumerate() {
                let global = offset + local;
                vertices.push(*vertex);
                raw_uvs.push(asset.raw_uvs.get(global).copied().unwrap_or([0.0; 2]));
                missing_normals.push(flag(&asset.missing_normals, global));
                authored_tangents.push(flag(&asset.authored_tangents, global));
            }
            indices.extend_from_slice(local_indices);
        }
        mesh.vertex_offset = new_vertex_offset as u32;
        mesh.index_offset = new_index_offset as u32;
    }

    asset.vertices = vertices;
    asset.raw_uvs = raw_uvs;
    asset.missing_normals = missing_normals;
    asset.authored_tangents = authored_tangents;
    asset.indices = indices;
    processed
}

/// Marqueur d'un sommet ; absent vaut faux.
fn flag(flags: &[bool], index: usize) -> bool {
    flags.get(index).copied().unwrap_or(false)
}

/// Un mesh déplié en coins de triangles, dans la forme que MikkTSpace consulte.
struct Corners {
    positions: Vec<[f32; 3]>,
    normals: Vec<[f32; 3]>,
    uvs: Vec<[f32; 2]>,
    tangents: Vec<[f32; 4]>,
}

impl Corners {
    fn new(vertices: &[Vertex], indices: &[u32]) -> Self {
        let corner = |local: &u32| vertices[*local as usize];
        Self {
            positions: indices.iter().map(|local| corner(local).position).collect(),
            normals: indices
                .iter()
                .map(|local| {
                    let normal = corner(local).normal;
                    [0, 1, 2].map(|axis| f32::from(normal[axis]) / 127.0)
                })
                .collect(),
            uvs: indices
                .iter()
                .map(|local| {
                    corner(local)
                        .uv0
                        .map(|value| f32::from(value) / f32::from(u16::MAX))
                })
                .collect(),
            tangents: vec![[0.0; 4]; indices.len()],
        }
    }
}

impl mikktspace::Geometry for Corners {
    fn num_faces(&self) -> usize {
        self.positions.len() / 3
    }

    fn num_vertices_of_face(&self, _face: usize) -> usize {
        3
    }

    fn position(&self, face: usize, vert: usize) -> [f32; 3] {
        self.positions[face * 3 + vert]
    }

    fn normal(&self, face: usize, vert: usize) -> [f32; 3] {
        self.normals[face * 3 + vert]
    }

    fn tex_coord(&self, face: usize, vert: usize) -> [f32; 2] {
        self.uvs[face * 3 + vert]
    }

    fn set_tangent_encoded(&mut self, tangent: [f32; 4], face: usize, vert: usize) {
        self.tangents[face * 3 + vert] = tangent;
    }
}

#[cfg(test)]
mod tests {
    use super::super::merge::merge_vertices;
    use super::super::tests::{asset, sommet};
    use super::*;

    /// Un sommet tourné vers +Z, avec ses UV en unités de texture.
    fn coin(position: [f32; 3], uv: [f32; 2]) -> Vertex {
        let mut vertex = sommet(position);
        vertex.normal = [0, 0, 127, 0];
        vertex.uv0 = uv.map(|value| (value * f32::from(u16::MAX)).round() as u16);
        vertex
    }

    fn materiau(has_normal_map: bool) -> ImportedMaterial {
        let mut desc = crate::import::plain_default_material();
        // Le slot désigne une entrée de `TEXR` : une normal map utilisable.
        desc.normal_tex = if has_normal_map {
            0
        } else {
            ax_model::dm::material::NO_TEXTURE
        };
        ImportedMaterial {
            name: "carrosserie".to_owned(),
            desc,
        }
    }

    /// Un carré dans le plan XY, UV alignés sur les axes.
    fn carre(has_normal_map: bool) -> ImportedAsset {
        let mut asset = asset(
            vec![
                coin([0.0, 0.0, 0.0], [0.0, 0.0]),
                coin([1.0, 0.0, 0.0], [1.0, 0.0]),
                coin([1.0, 1.0, 0.0], [1.0, 1.0]),
                coin([0.0, 1.0, 0.0], [0.0, 1.0]),
            ],
            vec![0, 1, 2, 0, 2, 3],
            false,
        );
        asset.materials.push(materiau(has_normal_map));
        asset
    }

    #[test]
    fn t244_la_tangente_suit_la_direction_des_u() {
        let mut asset = carre(true);
        assert_eq!(generate(&mut asset, &mut Vec::new()), 1);
        merge_vertices(&mut asset);

        // U croît vers +X, V vers +Y, normale +Z : tangente +X, et la
        // bitangente w · (N × T) = +Y garde son signe.
        assert_eq!(asset.vertices.len(), 4);
        for vertex in &asset.vertices {
            assert_eq!(vertex.tangent, [127, 0, 0, 127]);
        }
    }

    #[test]
    fn t244_une_couture_en_miroir_dedouble_ses_sommets() {
        // Deux carrés côte à côte, le second aux U en miroir. Les deux sommets
        // de l'arête commune ont mêmes position, normale et UV : la fusion les
        // partage, et MikkTSpace leur donne une tangente par côté.
        let mut asset = asset(
            vec![
                coin([0.0, 0.0, 0.0], [0.0, 0.0]),
                coin([1.0, 0.0, 0.0], [1.0, 0.0]),
                coin([1.0, 1.0, 0.0], [1.0, 1.0]),
                coin([0.0, 1.0, 0.0], [0.0, 1.0]),
                coin([-1.0, 0.0, 0.0], [1.0, 0.0]),
                coin([0.0, 0.0, 0.0], [0.0, 0.0]),
                coin([0.0, 1.0, 0.0], [0.0, 1.0]),
                coin([-1.0, 1.0, 0.0], [1.0, 1.0]),
            ],
            vec![0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7],
            false,
        );
        asset.materials.push(materiau(true));
        merge_vertices(&mut asset);
        assert_eq!(asset.vertices.len(), 6);

        generate(&mut asset, &mut Vec::new());
        merge_vertices(&mut asset);

        assert_eq!(asset.vertices.len(), 8);
        for (rank, &local) in asset.indices.iter().enumerate() {
            let tangent = asset.vertices[local as usize].tangent;
            let attendue = if rank < 6 {
                [127, 0, 0, 127]
            } else {
                // U décroît vers +X : tangente -X, orientation retournée.
                [-127, 0, 0, -127]
            };
            assert_eq!(tangent, attendue, "coin {rank}");
        }
    }

    #[test]
    fn t244_sans_normal_map_rien_n_est_genere() {
        let mut asset = carre(false);
        let avant = asset.clone();
        assert_eq!(generate(&mut asset, &mut Vec::new()), 0);
        assert_eq!(asset, avant);
    }

    #[test]
    fn t244_une_tangente_ecrite_par_la_source_est_conservee() {
        let mut asset = carre(true);
        for vertex in &mut asset.vertices {
            vertex.tangent = [0, 127, 0, -127];
        }
        asset.authored_tangents = vec![true; 4];
        let avant = asset.clone();

        assert_eq!(generate(&mut asset, &mut Vec::new()), 0);
        assert_eq!(asset, avant);
    }
}
