//! Import Wavefront OBJ (C-21) — statique.
//!
//! Le format porte des meshes, des UV, des normales et des matériaux basiques.
//! Ni hiérarchie animée, ni skin : le cahier des charges le donne pour
//! « supporté, statique », et c'est ce qu'on en tire.

use super::{
    check_relative_path, ImportError, ImportLimits, ImportedAsset, ImportedMaterial, SourceFormat,
};
use ax_model::dm::geometry::{encode_normal, MeshDesc, Transform, Vertex, NO_REGION_U8};
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
    check_face_indices(source)?;

    // L'analyseur veut un `Fn` pour résoudre les bibliothèques ; l'appelant,
    // lui, a toutes les raisons de tenir un état — un cache, un compteur de
    // lectures. La cellule fait le pont sans imposer l'un ou l'autre.
    let resolve_mtl = std::cell::RefCell::new(resolve_mtl);
    let mut cursor = std::io::BufReader::new(source.as_bytes());
    let (models, materials) = super::catch_parser_panic(SourceFormat::Obj, || {
        tobj::load_obj_buf(
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
                        // La bibliothèque de matériaux est vérifiée avant d'être
                        // confiée à l'analyseur, pour la même raison que les
                        // indices de face : `tobj::parse_float3` termine par
                        // `.try_into().unwrap()` sur un `Vec` qu'il vient de
                        // collecter, et un `Ka 0.0 0.0` — deux valeurs là où le
                        // format en veut trois — le fait paniquer. Trouvé par
                        // fuzzing (R-903).
                        if check_mtl_triplets(&content).is_err() {
                            return Err(tobj::LoadError::MaterialParseError);
                        }
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
        })
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

/// Refuse une déclaration de couleur à moins de trois composantes.
///
/// `Ka`, `Kd`, `Ks`, `Ke` et `Tf` désignent chacun un triplet. `tobj` les lit
/// par `parse_float3`, qui collecte au plus trois valeurs puis fait
/// `.try_into().unwrap()` : deux valeurs suffisent à le faire paniquer.
///
/// Le contrôle ne juge que le **nombre** de composantes. Une valeur illisible
/// reste l'affaire de l'analyseur, qui la refuse proprement par
/// `MaterialParseError` — la reprendre ici dédoublerait sa grammaire.
fn check_mtl_triplets(mtl: &str) -> Result<(), ()> {
    const TRIPLETS: [&str; 5] = ["Ka", "Kd", "Ks", "Ke", "Tf"];

    for line in mtl.lines() {
        let mut mots = line.split_whitespace();
        let Some(mot_cle) = mots.next() else {
            continue;
        };
        if TRIPLETS.contains(&mot_cle) && mots.take(3).count() < 3 {
            return Err(());
        }
    }
    Ok(())
}

/// Refuse un indice de face hors bornes **avant** de confier le texte à
/// l'analyseur.
///
/// Trouvé par fuzzing (R-903). `tobj` résout un indice négatif en le comptant
/// depuis la fin, puis vérifie ses bornes par `vn * 3 + 2 >= normal.len()` — le
/// produit est calculé **avant** la comparaison. Un indice de `-21` avec une
/// seule normale déclarée donne `1 - 21 = -20`, qui devient un `usize` immense,
/// et la multiplication déborde. La panique était contenue par le pool de jobs
/// et par la frontière FFI, mais un OBJ malformé se refuse ; il ne panique pas.
///
/// Le contrôle est **conservateur** : il ne refuse que ce qui est hors bornes
/// sous toute lecture du format. Un indice positif est comparé au total du
/// fichier, un indice négatif au nombre d'éléments déjà déclarés — la règle que
/// `tobj` applique. Un fichier valide passe donc, quelle que soit la lecture.
fn check_face_indices(source: &str) -> Result<(), ImportError> {
    let totaux = declared_counts(source);
    let mut vus = Counts::default();

    for line in source.lines() {
        let mut mots = line.split_whitespace();
        match mots.next() {
            Some("v") => vus.positions += 1,
            Some("vt") => vus.texcoords += 1,
            Some("vn") => vus.normals += 1,
            Some("f") => {
                for reference in mots {
                    // Trois champs au plus — `sommet/texture/normale`. Ce qui
                    // suit n'appartient pas au format ; l'analyseur l'ignore, et
                    // le vérifier reviendrait à inventer une règle.
                    for (rang, champ) in reference.split('/').take(3).enumerate() {
                        if champ.is_empty() {
                            continue;
                        }
                        let (total, deja_vus, nom) = match rang {
                            0 => (totaux.positions, vus.positions, "sommet"),
                            1 => (totaux.texcoords, vus.texcoords, "coordonnée de texture"),
                            _ => (totaux.normals, vus.normals, "normale"),
                        };
                        check_index(champ, total, deja_vus, nom)?;
                    }
                }
            }
            _ => {}
        }
    }
    Ok(())
}

/// Nombre d'éléments de chaque sorte.
#[derive(Debug, Default, Clone, Copy)]
struct Counts {
    positions: i64,
    texcoords: i64,
    normals: i64,
}

/// Compte ce que le fichier déclare, sans rien interpréter.
fn declared_counts(source: &str) -> Counts {
    let mut counts = Counts::default();
    for line in source.lines() {
        match line.split_whitespace().next() {
            Some("v") => counts.positions += 1,
            Some("vt") => counts.texcoords += 1,
            Some("vn") => counts.normals += 1,
            _ => {}
        }
    }
    counts
}

/// Vérifie un indice unique.
///
/// `i64` et non `usize` : c'est le seul type qui représente à la fois un indice
/// négatif du format et un entier assez grand pour que l'analyse échoue plutôt
/// que de déborder. Un nombre qui n'entre pas dans un `i64` est refusé comme
/// illisible, ce qu'il est.
fn check_index(champ: &str, total: i64, deja_vus: i64, nom: &str) -> Result<(), ImportError> {
    let Ok(indice) = champ.parse::<i64>() else {
        return Err(ImportError::Malformed {
            format: SourceFormat::Obj,
            detail: format!("indice de {nom} illisible : « {champ} »"),
        });
    };

    let hors_bornes = if indice == 0 {
        // L'OBJ compte à partir de 1 ; zéro ne désigne rien.
        true
    } else if indice > 0 {
        indice > total
    } else {
        -indice > deja_vus
    };

    if hors_bornes {
        return Err(ImportError::Malformed {
            format: SourceFormat::Obj,
            detail: format!("indice de {nom} hors bornes : {indice}, pour {total} déclaré(s)"),
        });
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

        let (normal, missing) = if mesh.normals.len() >= (vertex + 1) * 3 {
            let normal = encode_normal([
                mesh.normals[vertex * 3],
                mesh.normals[vertex * 3 + 1],
                mesh.normals[vertex * 3 + 2],
            ]);
            // Un `vn 0 0 0` écrit par l'auteur reste nul : C-22 le refuse.
            (normal, false)
        } else {
            // Une normale absente n'est pas inventée : elle est marquée, et
            // C-23 la génère depuis la géométrie.
            ([0; 4], true)
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
        asset.missing_normals.push(missing);
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
        // `map_Bump` et `bump` sont rangés par `tobj` en `normal_texture` ;
        // `norm`, l'extension PBR du MTL, lui est inconnue et reste dans les
        // paramètres bruts. Les deux désignent une carte de relief, et l'une
        // comme l'autre demande des tangentes.
        has_normal_map: material.normal_texture.is_some()
            || material.unknown_param.contains_key("norm"),
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

    #[test]
    fn t221_une_carte_de_relief_du_mtl_est_reperee() {
        let source = "mtllib relief.mtl\nusemtl relief\nv 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n";
        // `map_Bump` est rangé par `tobj` ; `norm`, l'extension PBR, ne l'est
        // pas et doit être cherché dans les paramètres bruts.
        for ligne in ["map_Bump relief.png", "bump relief.png", "norm relief.png"] {
            let asset = import_obj(source, &limits(), |_| {
                Some(format!("newmtl relief\n{ligne}\n"))
            })
            .expect("import refusé");
            assert!(asset.materials[0].has_normal_map, "{ligne}");
        }

        let asset = import_obj(source, &limits(), |_| {
            Some("newmtl relief\nKd 1 1 1\n".to_owned())
        })
        .expect("import refusé");
        assert!(!asset.materials[0].has_normal_map);
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
    fn t680_un_indice_negatif_hors_bornes_est_refuse_et_ne_panique_pas() {
        // Trouvé par fuzzing (R-903), à partir d'une graine du corpus. `tobj`
        // résout `-21` en `1 - 21 = -20`, qui devient un `usize` immense, puis
        // `vn * 3` déborde — le contrôle de bornes calcule le produit avant de
        // comparer. Sans la passe de vérification, cet appel **panique**.
        let source = "\
v 0.0 0.0 0.0
v 1.0 0.0 0.0
v 0.0 1.0 0.0
vn 0.0 0.0 1.0
f 1//-1 2//-21 3//-1
";
        let refus = import_obj(source, &limits(), sans_mtl).unwrap_err();
        assert!(
            matches!(refus, ImportError::Malformed { .. }),
            "refus attendu, obtenu {refus:?}"
        );
    }

    #[test]
    fn t680_les_indices_hors_bornes_sont_refuses_dans_les_trois_champs() {
        // Les trois champs d'une référence de face ont chacun leur compte.
        // Vérifier le premier et oublier les deux autres était exactement la
        // faute de `tobj`.
        let base = "v 0.0 0.0 0.0\nvt 0.0 0.0\nvn 0.0 0.0 1.0\n";
        for face in [
            "f 9/1/1 1/1/1 1/1/1",                    // sommet
            "f 1/9/1 1/1/1 1/1/1",                    // coordonnée de texture
            "f 1/1/9 1/1/1 1/1/1",                    // normale
            "f 0/1/1 1/1/1 1/1/1",                    // zéro : l'OBJ compte à partir de 1
            "f 1/1/1 1/1/99999999999999999999 1/1/1", // au-delà d'un i64
        ] {
            let source = format!("{base}{face}\n");
            let resultat = import_obj(&source, &limits(), sans_mtl);
            assert!(
                resultat.is_err(),
                "« {face} » aurait dû être refusé, il a été accepté"
            );
        }
    }

    #[test]
    fn t680_un_indice_negatif_valide_reste_accepte() {
        // La réciproque, qui dit que la passe ne refuse pas ce que le format
        // autorise : compter depuis la fin est légal, et un fichier qui le fait
        // doit passer.
        let source = "\
v 0.0 0.0 0.0
v 1.0 0.0 0.0
v 0.0 1.0 0.0
vn 0.0 0.0 1.0
f -3//-1 -2//-1 -1//-1
";
        let asset = import_obj(source, &limits(), sans_mtl).expect("indices négatifs valides");
        assert_eq!(asset.vertices.len(), 3);
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
