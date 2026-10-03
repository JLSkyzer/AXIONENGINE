//! Import Wavefront OBJ (C-21) — statique.
//!
//! Le format porte des meshes, des UV, des normales et des matériaux basiques.
//! Ni hiérarchie animée, ni skin : le cahier des charges le donne pour
//! « supporté, statique », et c'est ce qu'on en tire.

use super::material::{plain_default_material, UvMapping};
use super::obj_material;
use super::{
    check_relative_path, ImportError, ImportLimits, ImportedAsset, ImportedMaterial, SourceFormat,
};
use ax_model::dm::geometry::{encode_normal, MeshDesc, Transform, UvRange, Vertex, NO_REGION_U8};
use ax_model::dm::scene::{
    name_hash, node_flags, NodeDesc, ALL_LODS, NONE_U16, NONE_U32, NO_PARENT,
};

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
    // Bibliothèques lues mais illisibles. `tobj` n'en dit rien dès qu'une autre
    // a fourni des matériaux : sans ce relevé, les leurs disparaîtraient en
    // silence derrière le matériau par défaut.
    let unreadable = std::cell::RefCell::new(Vec::new());
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
                        let loaded = if check_mtl_triplets(&content).is_err() {
                            Err(tobj::LoadError::MaterialParseError)
                        } else {
                            tobj::load_mtl_buf(&mut std::io::BufReader::new(content.as_bytes()))
                        };
                        if let Err(error) = &loaded {
                            unreadable.borrow_mut().push(format!("{name} ({error})"));
                        }
                        loaded
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
    for library in unreadable.into_inner() {
        asset.material_warnings.push(format!(
            "bibliothèque de matériaux « {library} » illisible : ses matériaux sont \
             remplacés par le matériau par défaut"
        ));
    }

    // `tobj` ne rend une erreur que si aucune bibliothèque n'a fourni de
    // matériau, et elle vient d'être dite.
    let library = materials.unwrap_or_default();
    let mappings = obj_material::convert_library(&library, &mut asset)?;

    // Le matériau par défaut, en fin de table, pour tout objet sans matériau
    // connu : `usemtl` absent, ou désignant une bibliothèque absente ou
    // illisible. `MeshDesc.material` est alors toujours un index valide.
    let default = u16::try_from(library.len()).unwrap_or(u16::MAX);
    let known = |model: &tobj::Model| model.mesh.material_id.is_some_and(|id| id < library.len());
    if !models.iter().all(known) {
        asset.materials.push(ImportedMaterial {
            name: String::new(),
            desc: plain_default_material(),
        });
    }

    for (index, model) in models.iter().enumerate() {
        let (material, mapping) = match model.mesh.material_id {
            Some(id) if id < library.len() => (u16::try_from(id).unwrap_or(u16::MAX), mappings[id]),
            _ => (default, UvMapping::IDENTITY),
        };
        // `TRANSPARENT` et `DOUBLE_SIDED` recopiés du matériau (ADR-122 §3).
        let flags = asset
            .materials
            .get(usize::from(material))
            .map_or(0, |entry| entry.desc.mesh_flags());

        let vertex_offset = asset.vertices.len() as u32;
        let index_offset = asset.indices.len() as u32;
        append_model(&mut asset, model, mapping);

        asset.meshes.push(mesh_desc(
            &asset,
            vertex_offset,
            index_offset,
            material,
            flags,
        ));

        // `tobj` découpe un objet à chaque `usemtl` : ses morceaux arrivent
        // consécutifs, sous le même nom, et leurs meshes aussi. Ils forment un
        // seul node, qui les porte tous (ADR-122 §5) ; deux nodes du même nom
        // seraient refusés pour doublon (C-22). Au-delà de ce que `mesh_count`
        // compte, un second node naît — et le doublon le fait refuser, plutôt
        // qu'un mesh porté par personne.
        let continues = index > 0 && models[index - 1].name == model.name;
        match asset.nodes.last_mut() {
            Some(node) if continues && node.mesh_count < u16::MAX => node.mesh_count += 1,
            _ => {
                let mesh = u32::try_from(asset.meshes.len() - 1).unwrap_or(u32::MAX);
                asset.nodes.push(node_for(mesh, &model.name));
                if !model.name.is_empty() {
                    asset.names.push(("node", model.name.clone()));
                }
                asset.node_names.push(model.name.clone());
            }
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

/// Ajoute les sommets et les indices d'un objet.
///
/// Ses coordonnées de texture reçoivent la projection de `map_Kd` dans l'espace
/// du MTL, puis passent à l'origine **en haut à gauche** de l'image, celle de
/// glTF, de Minecraft et d'AXION : l'OBJ compte `v` depuis le bas, en
/// convention OpenGL — celle des exports de Blender. Sans ce passage, toute
/// texture d'un OBJ apparaîtrait retournée.
fn append_model(asset: &mut ImportedAsset, model: &tobj::Model, mapping: UvMapping) {
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
            let [u, v] =
                mapping.apply([mesh.texcoords[vertex * 2], mesh.texcoords[vertex * 2 + 1]]);
            [u, 1.0 - v]
        } else {
            [0.0, 0.0]
        };

        asset.vertices.push(Vertex {
            position,
            normal,
            // Les tangentes viennent de C-23, par mikktspace.
            tangent: [0; 4],
            // Dans `[0, 1]`, **saturé**, jamais replié : l'optimizer
            // requantifie dans la plage du mesh, depuis les valeurs brutes
            // conservées à côté — c'est sur elles que porte R-142.
            uv0: uv.map(|value| UvRange::UNIT.quantize(value)),
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

fn mesh_desc(
    asset: &ImportedAsset,
    vertex_offset: u32,
    index_offset: u32,
    material: u16,
    flags: u8,
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
        material,
        lod: 0,
        flags,
        aabb_min: min,
        aabb_max: max,
        region: NONE_U16,
        uv0_range: 0,
    }
}

/// Le node d'un objet, portant son premier mesh.
fn node_for(mesh: u32, name: &str) -> NodeDesc {
    NodeDesc {
        name_hash: name_hash(name),
        parent: NO_PARENT,
        local: Transform::identity(),
        flags: node_flags::VISIBLE,
        mesh,
        collider: NONE_U32,
        bone: NONE_U32,
        part: NONE_U16,
        region: NONE_U16,
        // L'OBJ ne porte aucune annotation : visible à tous les niveaux (R-913).
        lod_mask: ALL_LODS,
        state: 0,
        mesh_count: 1,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ax_model::dm::geometry::mesh_flags;
    use ax_model::dm::material::{blend_mode, texture_sampler, NO_TEXTURE};

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
    fn t225_une_carte_de_relief_du_mtl_est_reperee() {
        let source = "mtllib relief.mtl\nusemtl relief\nv 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n";
        // `map_Bump` est rangé par `tobj` ; `norm`, l'extension PBR, ne l'est
        // pas et doit être cherché dans les paramètres bruts.
        for ligne in ["map_Bump relief.png", "bump relief.png", "norm relief.png"] {
            let asset = import_obj(source, &limits(), |_| {
                Some(format!("newmtl relief\n{ligne}\n"))
            })
            .expect("import refusé");
            assert!(asset.materials[0].has_normal_map(), "{ligne}");
            assert_eq!(
                asset.textures.resource_path(0),
                Some("relief.png"),
                "{ligne}"
            );
        }

        let asset = import_obj(source, &limits(), |_| {
            Some("newmtl relief\nKd 1 1 1\n".to_owned())
        })
        .expect("import refusé");
        assert!(!asset.materials[0].has_normal_map());
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
        assert!((material.desc.albedo_factor[0] - 0.8).abs() < 1e-6);
        assert_eq!(asset.meshes[0].material, 0);
        // R-532 : le chemin est conservé, l'image n'est pas touchée.
        assert_eq!(
            asset
                .textures
                .resource_path(usize::from(material.desc.albedo_tex)),
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
        // Un OBJ sans `.mtl` reste un OBJ valide, au matériau par défaut.
        let asset = import_obj(&source, &limits(), sans_mtl).expect("import refusé");
        assert_eq!(asset.indices.len(), 3);
        assert_eq!(asset.materials.len(), 1);
        assert_eq!(asset.materials[0].desc, plain_default_material());
        assert_eq!(asset.meshes[0].material, 0);
        assert!(
            asset.material_warnings.is_empty(),
            "absente n'est pas illisible"
        );
    }

    #[test]
    fn t271_un_materiau_mtl_complet_se_traduit_en_dm05() {
        let source = format!("mtllib vitre.mtl\nusemtl vitre\n{TRIANGLE}");
        let mtl = "\
newmtl vitre
Kd 0.2 0.4 0.6
d 0.5
Ke 1.0 0.5 0.0
Ns 1000
Pm 0.25
map_Kd -s 2 2 -o 0.5 0 -clamp on vitre.png
map_Bump -bm 0.5 relief.png
map_Ke lueur.png
map_Ks reflet.png
";
        let asset =
            import_obj(&source, &limits(), |_| Some(mtl.to_owned())).expect("import refusé");

        let desc = asset.materials[0].desc;
        assert_eq!(desc.albedo_factor, [0.2, 0.4, 0.6, 0.5]);
        assert_eq!(desc.blend_mode, blend_mode::TRANSLUCENT, "d < 1");
        assert_eq!(desc.emissive_factor, [1.0, 0.5, 0.0]);
        assert_eq!(desc.metallic, 0.25);
        assert!((desc.roughness - (2.0f32 / 1002.0).sqrt().sqrt()).abs() < 1e-6);
        assert_eq!(desc.normal_scale, 0.5, "-bm");
        assert_eq!(desc.check(), Ok(()));
        // Le mesh recopie la translucidité de son matériau.
        assert_eq!(asset.meshes[0].flags, mesh_flags::TRANSPARENT);

        let chemin = |slot: u16| asset.textures.resource_path(usize::from(slot));
        assert_eq!(chemin(desc.albedo_tex), Some("vitre.png"));
        assert_eq!(chemin(desc.normal_tex), Some("relief.png"));
        assert_eq!(chemin(desc.emissive_tex), Some("lueur.png"));
        assert_eq!(
            asset.textures.entries[usize::from(desc.albedo_tex)].sampler,
            texture_sampler::CLAMP_U | texture_sampler::CLAMP_V
        );
        assert_eq!(desc.orm_tex, NO_TEXTURE);

        // `map_Ks` n'a pas de slot ; relief et lueur ne sont pas projetés
        // comme l'albedo : tout est dit.
        let dits = asset.material_warnings.join("\n");
        assert!(dits.contains("map_Ks"), "{dits}");
        assert!(dits.contains("map_Bump projetée autrement"), "{dits}");
        assert!(dits.contains("map_Ke projetée autrement"), "{dits}");
    }

    #[test]
    fn t271_la_projection_de_map_kd_est_cuite_puis_v_retourne() {
        // `vt 0.25 0.75` : u' = 2·0,25 + 0,5 = 1, v' = 2·0,75 = 1,5, puis
        // l'origine passe en haut de l'image : v = 1 − 1,5 = −0,5.
        let source = "\
mtllib bois.mtl
usemtl bois
v 0.0 0.0 0.0
v 1.0 0.0 0.0
v 0.0 1.0 0.0
vt 0.25 0.75
vt 0.0 0.0
vt 0.0 1.0
f 1/1 2/2 3/3
";
        let mtl = "newmtl bois\nmap_Kd -s 2 2 -o 0.5 0 bois.png\n";
        let asset = import_obj(source, &limits(), |_| Some(mtl.to_owned())).expect("import refusé");
        assert_eq!(asset.raw_uvs, [[1.0, -0.5], [0.5, 1.0], [0.5, -1.0]]);
    }

    #[test]
    fn t271_sans_projection_v_est_seulement_retourne() {
        let source = TRIANGLE.replace("vt 0.0 1.0", "vt 0.25 0.75");
        let asset = import_obj(&source, &limits(), sans_mtl).expect("import refusé");
        // L'OBJ compte v depuis le bas de l'image ; AXION, comme glTF et
        // Minecraft, depuis le haut.
        assert_eq!(asset.raw_uvs, [[0.0, 1.0], [1.0, 1.0], [0.25, 0.25]]);
    }

    #[test]
    fn t271_un_objet_sans_materiau_connu_recoit_le_materiau_par_defaut() {
        let source = "\
mtllib lib.mtl
o connu
usemtl peinture
v 0 0 0
v 1 0 0
v 0 1 0
f 1 2 3
o inconnu
usemtl absent
v 5 0 0
v 6 0 0
v 5 1 0
f 4 5 6
";
        let asset = import_obj(source, &limits(), |_| {
            Some("newmtl peinture\nKd 1 0 0\n".to_owned())
        })
        .expect("import refusé");
        assert_eq!(asset.materials.len(), 2, "peinture, puis le défaut");
        assert_eq!(asset.meshes[0].material, 0);
        assert_eq!(asset.meshes[1].material, 1);
        assert_eq!(asset.materials[1].desc, plain_default_material());
    }

    #[test]
    fn t271_une_bibliotheque_illisible_est_dite() {
        let source = format!("mtllib cassee.mtl\nusemtl rouge\n{TRIANGLE}");
        // Deux valeurs là où `Kd` en veut trois : refusée avant `tobj`.
        let asset = import_obj(&source, &limits(), |_| {
            Some("newmtl rouge\nKd 1 0\n".to_owned())
        })
        .expect("import refusé");
        assert_eq!(asset.materials.len(), 1, "le matériau par défaut seul");
        assert_eq!(
            asset.material_warnings.len(),
            1,
            "{:?}",
            asset.material_warnings
        );
        assert!(asset.material_warnings[0].contains("cassee.mtl"));
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
    fn t225_un_objet_a_plusieurs_materiaux_est_un_seul_node() {
        // `tobj` découpe l'objet à chaque `usemtl` : deux modèles du même nom.
        // Ils étaient deux nodes « caisse », et C-22 refusait l'asset pour
        // doublon — tout OBJ multi-matériau de Blender.
        let source = "\
mtllib caisse.mtl
o caisse
usemtl bois
v 0 0 0
v 1 0 0
v 0 1 0
v 1 1 0
f 1 2 3
usemtl metal
f 2 4 3
o roue
usemtl metal
v 5 0 0
v 6 0 0
v 5 1 0
f 5 6 7
";
        let asset = import_obj(source, &limits(), |_| {
            Some("newmtl bois\nKd 1 0 0\nnewmtl metal\nKd 0 0 1\n".to_owned())
        })
        .expect("import refusé");

        assert_eq!(asset.meshes.len(), 3);
        assert_eq!(asset.node_names, ["caisse", "roue"]);
        assert_eq!(
            asset.nodes[0].meshes(),
            0..2,
            "la caisse porte ses deux meshes"
        );
        assert_eq!(asset.nodes[1].meshes(), 2..3);
        let materiaux: Vec<u16> = asset.meshes.iter().map(|mesh| mesh.material).collect();
        assert_eq!(materiaux, [0, 1, 1]);
        assert_eq!(
            asset
                .names
                .iter()
                .filter(|(categorie, _)| *categorie == "node")
                .count(),
            2
        );

        // Et C-22 l'accepte, là où il refusait le doublon.
        let names: Vec<crate::validate::NamedEntry<'_>> = asset
            .names
            .iter()
            .map(|(categorie, nom)| crate::validate::NamedEntry::new(categorie, nom.as_str()))
            .collect();
        let materials: Vec<_> = asset
            .materials
            .iter()
            .map(|material| material.desc)
            .collect();
        let report = crate::validate::validate(
            &crate::validate::AssetView {
                nodes: &asset.nodes,
                meshes: &asset.meshes,
                vertices: &asset.vertices,
                indices: &asset.indices,
                names: &names,
                materials: Some(&materials),
                missing_normals: &asset.missing_normals,
                ..crate::validate::AssetView::default()
            },
            &asset.raw_uvs,
        );
        assert!(report.is_valid(), "{:?}", report.errors);
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
