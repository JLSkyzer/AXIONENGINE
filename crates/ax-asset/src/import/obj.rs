//! Import Wavefront OBJ (C-21) — statique.
//!
//! Le format porte des meshes, des UV, des normales et des matériaux basiques.
//! Ni hiérarchie animée, ni skin : le cahier des charges le donne pour
//! « supporté, statique », et c'est ce qu'on en tire.

use super::{
    check_relative_path, ImportError, ImportLimits, ImportedAsset, ImportedMaterial, SourceFormat,
};
use ax_model::dm::geometry::{MeshDesc, Transform, Vertex, NO_REGION_U8};
use ax_model::dm::scene::{node_flags, NodeDesc, NONE_U16, NONE_U32, NO_PARENT};

/// Importe une source OBJ.
///
/// `resolve_mtl` fournit le contenu d'une bibliothèque de matériaux désignée
/// par le fichier. Le chemin lui parvient **déjà validé** (R-531) : ni absolu,
/// ni remontant. Rendre `None` revient à déclarer la bibliothèque absente, ce
/// que l'import accepte — un OBJ sans `.mtl` reste un OBJ valide.
///
/// # Errors
///
/// [`ImportError::SourceTooLarge`] au-delà du plafond (R-533),
/// [`ImportError::ExternalPath`] si un `mtllib` sort du répertoire (R-531),
/// [`ImportError::Malformed`] si le contenu est illisible.
pub fn import_obj(
    source: &str,
    limits: &ImportLimits,
    resolve_mtl: impl FnMut(&str) -> Option<String>,
) -> Result<ImportedAsset, ImportError> {
    limits.check_size(SourceFormat::Obj, source.len() as u64)?;
    check_mtllib_paths(source)?;

    // L'analyseur veut un `Fn` pour résoudre les bibliothèques ; l'appelant,
    // lui, a toutes les raisons de tenir un état — un cache, un compteur de
    // lectures. La cellule fait le pont sans imposer l'un ou l'autre.
    let resolve_mtl = std::cell::RefCell::new(resolve_mtl);
    let mut cursor = std::io::BufReader::new(source.as_bytes());
    let (models, materials) = tobj::load_obj_buf(
        &mut cursor,
        &tobj::LoadOptions {
            // Le moteur ne connaît que des triangles : les quads d'un OBJ sont
            // triangulés ici plutôt que refusés, c'est une lecture fidèle du
            // format et non une réparation.
            triangulate: true,
            single_index: true,
            ..tobj::LoadOptions::default()
        },
        |path| {
            let name = path.to_string_lossy().to_string();
            match (resolve_mtl.borrow_mut())(&name) {
                Some(content) => {
                    tobj::load_mtl_buf(&mut std::io::BufReader::new(content.as_bytes()))
                }
                // Une bibliothèque absente n'est pas une erreur : l'OBJ garde
                // ses matériaux par défaut.
                None => Ok((Vec::new(), std::collections::HashMap::new())),
            }
        },
    )
    .map_err(|error| ImportError::Malformed {
        format: SourceFormat::Obj,
        detail: error.to_string(),
    })?;

    let mut asset = ImportedAsset::default();

    for (index, model) in models.iter().enumerate() {
        let vertex_offset = asset.vertices.len() as u32;
        let index_offset = asset.indices.len() as u32;
        append_model(&mut asset, model);

        asset
            .meshes
            .push(mesh_desc(&asset, model, vertex_offset, index_offset));
        asset.nodes.push(node_for(index));
        asset.names.push(("node", model.name.clone()));
    }

    if let Ok(materials) = materials {
        for material in &materials {
            asset.materials.push(convert_material(material)?);
            asset.names.push(("matériau", material.name.clone()));
        }
    }

    Ok(asset)
}

/// Vérifie les `mtllib` avant de confier quoi que ce soit à l'analyseur
/// (R-531).
///
/// Le contrôle a lieu sur le texte source : l'analyseur, lui, résoudrait le
/// chemin avant qu'on ait pu le refuser.
fn check_mtllib_paths(source: &str) -> Result<(), ImportError> {
    for line in source.lines() {
        let trimmed = line.trim_start();
        let Some(rest) = trimmed.strip_prefix("mtllib") else {
            continue;
        };
        // `mtllib` accepte plusieurs bibliothèques sur une ligne.
        for path in rest.split_whitespace() {
            check_relative_path(path)?;
        }
    }
    Ok(())
}

fn append_model(asset: &mut ImportedAsset, model: &tobj::Model) {
    let mesh = &model.mesh;
    let count = mesh.positions.len() / 3;

    for vertex in 0..count {
        let position = [
            mesh.positions[vertex * 3],
            mesh.positions[vertex * 3 + 1],
            mesh.positions[vertex * 3 + 2],
        ];

        let normal = if mesh.normals.len() >= (vertex + 1) * 3 {
            encode_normal([
                mesh.normals[vertex * 3],
                mesh.normals[vertex * 3 + 1],
                mesh.normals[vertex * 3 + 2],
            ])
        } else {
            // Une normale absente n'est pas inventée : C-23 la calculera depuis
            // la géométrie, ce qu'il fait mieux qu'une valeur par défaut.
            [0, 127, 0, 0]
        };

        let uv = if mesh.texcoords.len() >= (vertex + 1) * 2 {
            [mesh.texcoords[vertex * 2], mesh.texcoords[vertex * 2 + 1]]
        } else {
            [0.0, 0.0]
        };

        asset.vertices.push(Vertex {
            position,
            normal,
            // Les tangentes viennent de C-23, par mikktspace.
            tangent: [0; 4],
            uv0: quantize_uv(uv),
            uv1: [0; 2],
            color: [255; 4],
            bones: [0; 4],
            weights: [255, 0, 0, 0],
            region: NO_REGION_U8,
            def_w: 0,
            _pad: [0; 6],
        });
        // R-142 porte sur les valeurs avant normalisation : elles sont
        // conservées telles quelles pour que C-22 puisse les examiner.
        asset.raw_uvs.push(uv);
    }

    // Les indices restent **locaux au mesh** : c'est son `vertex_offset` qui
    // les situe dans l'asset. Les rendre absolus ici les ferait sortir de
    // `vertex_count`, que le validateur compare précisément à chacun.
    asset.indices.extend_from_slice(&mesh.indices);
}

/// Ramène une coordonnée de texture en `UNORM16`.
///
/// Les valeurs hors de `[0, 1]` sont **saturées**, pas repliées : c'est
/// l'optimizer qui les ramènera proprement (R-142), et un repli ici
/// déplacerait la texture sans que rien ne le signale. Les valeurs brutes sont
/// conservées à côté, et c'est sur elles que porte le contrôle.
fn quantize_uv(uv: [f32; 2]) -> [u16; 2] {
    let encode = |value: f32| {
        if value.is_finite() {
            (value.clamp(0.0, 1.0) * f32::from(u16::MAX)).round() as u16
        } else {
            0
        }
    };
    [encode(uv[0]), encode(uv[1])]
}

fn encode_normal(normal: [f32; 3]) -> [i8; 4] {
    let length = (normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2]).sqrt();
    if !length.is_finite() || length <= f32::EPSILON {
        return [0, 127, 0, 0];
    }
    let mut encoded = [0i8; 4];
    for (index, value) in normal.iter().enumerate() {
        encoded[index] = ((value / length) * 127.0).round().clamp(-127.0, 127.0) as i8;
    }
    encoded
}

fn mesh_desc(
    asset: &ImportedAsset,
    model: &tobj::Model,
    vertex_offset: u32,
    index_offset: u32,
) -> MeshDesc {
    let vertex_count = (asset.vertices.len() as u32) - vertex_offset;
    let index_count = (asset.indices.len() as u32) - index_offset;

    let mut min = [f32::INFINITY; 3];
    let mut max = [f32::NEG_INFINITY; 3];
    for vertex in &asset.vertices[vertex_offset as usize..] {
        for axis in 0..3 {
            min[axis] = min[axis].min(vertex.position[axis]);
            max[axis] = max[axis].max(vertex.position[axis]);
        }
    }
    if vertex_count == 0 {
        min = [0.0; 3];
        max = [0.0; 3];
    }

    MeshDesc {
        vertex_offset,
        vertex_count,
        index_offset,
        index_count,
        material: u16::try_from(model.mesh.material_id.unwrap_or(0)).unwrap_or(0),
        lod: 0,
        flags: 0,
        aabb_min: min,
        aabb_max: max,
        region: NONE_U16,
        _pad: 0,
    }
}

fn node_for(index: usize) -> NodeDesc {
    NodeDesc {
        name_hash: 0,
        parent: NO_PARENT,
        local: Transform::identity(),
        flags: node_flags::VISIBLE,
        mesh: index as u32,
        collider: NONE_U32,
        bone: NONE_U32,
        part: NONE_U16,
        region: NONE_U16,
        lod_mask: 1,
        state: 0,
        _pad: [0; 2],
    }
}

/// Convertit un matériau OBJ.
///
/// La texture est **désignée**, jamais décodée (R-532) : son chemin est validé
/// puis conservé, et le `ResourceManager` de Minecraft s'en occupera.
fn convert_material(material: &tobj::Material) -> Result<ImportedMaterial, ImportError> {
    let base_color_texture = match &material.diffuse_texture {
        Some(path) => {
            check_relative_path(path)?;
            Some(path.clone())
        }
        None => None,
    };

    let diffuse = material.diffuse.unwrap_or([1.0, 1.0, 1.0]);
    Ok(ImportedMaterial {
        name: material.name.clone(),
        base_color: [
            diffuse[0],
            diffuse[1],
            diffuse[2],
            material.dissolve.unwrap_or(1.0),
        ],
        base_color_texture,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    const TRIANGLE: &str = "\
v 0.0 0.0 0.0
v 1.0 0.0 0.0
v 0.0 1.0 0.0
vt 0.0 0.0
vt 1.0 0.0
vt 0.0 1.0
vn 0.0 0.0 1.0
f 1/1/1 2/2/1 3/3/1
";

    fn limits() -> ImportLimits {
        ImportLimits::new(1 << 20)
    }

    fn sans_mtl(_: &str) -> Option<String> {
        None
    }

    #[test]
    fn t225_un_obj_simple_donne_son_triangle() {
        let asset = import_obj(TRIANGLE, &limits(), sans_mtl).expect("import refusé");

        assert_eq!(asset.meshes.len(), 1);
        assert_eq!(asset.indices.len(), 3);
        assert_eq!(asset.vertices.len(), 3);
        assert_eq!(asset.meshes[0].aabb_min, [0.0, 0.0, 0.0]);
        assert_eq!(asset.meshes[0].aabb_max, [1.0, 1.0, 0.0]);
        // La normale déclarée est reprise, pas recalculée.
        assert_eq!(asset.vertices[0].normal, [0, 0, 127, 0]);
    }

    #[test]
    fn t225_les_coordonnees_brutes_sont_conservees_pour_r142() {
        let source = TRIANGLE.replace("vt 1.0 0.0", "vt 12.0 0.0");
        let asset = import_obj(&source, &limits(), sans_mtl).expect("import refusé");

        // Une fois quantifiée en UNORM16, la valeur ne dirait plus rien : c'est
        // la brute que C-22 examine.
        assert!(
            asset.raw_uvs.iter().any(|uv| uv[0] > 9.0),
            "coordonnée brute perdue : {:?}",
            asset.raw_uvs
        );
    }

    #[test]
    fn t225_une_coordonnee_hors_bornes_est_saturee_jamais_repliee() {
        let source = TRIANGLE.replace("vt 1.0 0.0", "vt 2.0 0.0");
        let asset = import_obj(&source, &limits(), sans_mtl).expect("import refusé");

        // Un repli déplacerait la texture sans que rien ne le signale.
        let sature = asset
            .vertices
            .iter()
            .any(|vertex| vertex.uv0[0] == u16::MAX);
        assert!(sature, "coordonnée repliée au lieu d'être saturée");
    }

    #[test]
    fn t225_un_quad_est_triangule() {
        let source = "\
v 0.0 0.0 0.0
v 1.0 0.0 0.0
v 1.0 1.0 0.0
v 0.0 1.0 0.0
f 1 2 3 4
";
        let asset = import_obj(source, &limits(), sans_mtl).expect("import refusé");
        // Le moteur ne connaît que des triangles ; deux pour un quad.
        assert_eq!(asset.indices.len(), 6);
        assert_eq!(asset.indices.len() % 3, 0);
    }

    #[test]
    fn t222_un_mtllib_sortant_est_refuse() {
        let source = format!("mtllib ../../../etc/passwd\n{TRIANGLE}");
        let refus = import_obj(&source, &limits(), sans_mtl).unwrap_err();

        assert_eq!(refus.code(), -3002);
        assert!(matches!(refus, ImportError::ExternalPath(_)), "{refus:?}");
    }

    #[test]
    fn t222_un_mtllib_absolu_est_refuse() {
        let source = format!("mtllib /etc/passwd\n{TRIANGLE}");
        assert_eq!(
            import_obj(&source, &limits(), sans_mtl).unwrap_err().code(),
            -3002
        );
    }

    #[test]
    fn t226_un_materiau_est_lu_et_sa_texture_designee_sans_etre_decodee() {
        let source = format!("mtllib voiture.mtl\nusemtl carrosserie\n{TRIANGLE}");
        let mtl = "\
newmtl carrosserie
Kd 0.8 0.1 0.1
d 1.0
map_Kd textures/carrosserie.png
";
        let asset = import_obj(&source, &limits(), |path| {
            assert_eq!(path, "voiture.mtl");
            Some(mtl.to_owned())
        })
        .expect("import refusé");

        assert_eq!(asset.materials.len(), 1);
        let material = &asset.materials[0];
        assert_eq!(material.name, "carrosserie");
        assert!((material.base_color[0] - 0.8).abs() < 1e-6);
        // R-532 : le chemin est conservé, l'image n'est pas touchée.
        assert_eq!(
            material.base_color_texture.as_deref(),
            Some("textures/carrosserie.png")
        );
    }

    #[test]
    fn t226_une_texture_sortante_est_refusee() {
        let source = format!("mtllib voiture.mtl\n{TRIANGLE}");
        let mtl = "newmtl vol\nmap_Kd ../../../secret.png\n";

        let refus = import_obj(&source, &limits(), |_| Some(mtl.to_owned())).unwrap_err();
        assert_eq!(refus.code(), -3002);
    }

    #[test]
    fn t226_une_bibliotheque_absente_n_est_pas_une_erreur() {
        let source = format!("mtllib absente.mtl\n{TRIANGLE}");
        // Un OBJ sans `.mtl` reste un OBJ valide.
        let asset = import_obj(&source, &limits(), sans_mtl).expect("import refusé");
        assert!(asset.materials.is_empty());
        assert_eq!(asset.indices.len(), 3);
    }

    #[test]
    fn t221_une_source_trop_volumineuse_est_refusee() {
        let refus = import_obj(TRIANGLE, &ImportLimits::new(16), sans_mtl).unwrap_err();
        assert_eq!(refus.code(), -3005);
    }

    #[test]
    fn t225_plusieurs_objets_gardent_des_indices_coherents() {
        let source = "\
o premier
v 0.0 0.0 0.0
v 1.0 0.0 0.0
v 0.0 1.0 0.0
f 1 2 3
o second
v 5.0 0.0 0.0
v 6.0 0.0 0.0
v 5.0 1.0 0.0
f 4 5 6
";
        let asset = import_obj(source, &limits(), sans_mtl).expect("import refusé");

        assert_eq!(asset.meshes.len(), 2);
        assert_eq!(asset.vertices.len(), 6);
        // Chaque mesh porte son décalage : ses indices restent locaux, et le
        // second mesh ne désigne pas les sommets du premier.
        assert_eq!(asset.meshes[1].vertex_offset, 3);
        for index in &asset.indices[asset.meshes[1].index_offset as usize..] {
            assert!(
                *index < asset.meshes[1].vertex_count,
                "indice {index} hors du second mesh"
            );
        }
    }
}
