//! Import glTF 2.0 et GLB (C-21) — le format recommandé.
//!
//! Le parsing est **strict** : aucune extension inconnue n'est interprétée,
//! aucun URI externe n'est suivi, aucun chemin ne remonte. La convention
//! d'orientation de glTF — Y vers le haut, main droite — est celle d'AXION
//! (R-461), et une unité glTF vaut un bloc : rien n'est converti, et c'est
//! délibéré. Une conversion silencieuse est ce qui fait qu'un modèle arrive à
//! l'envers sans que personne ne sache où.

use super::gltf_extras::{NodeAnnotations, NodeRole};
use super::{
    check_relative_path, ImportError, ImportLimits, ImportedAsset, ImportedMaterial, SourceFormat,
};
use ax_model::dm::geometry::{MeshDesc, Transform, Vertex, NO_REGION_U8};
use ax_model::dm::limits;
use ax_model::dm::scene::{node_flags, NodeDesc, NONE_U16, NONE_U32, NO_PARENT};

/// Extensions glTF supportées (R-530).
///
/// La liste est **fermée**. Une extension citée dans `extensionsRequired` et
/// absente d'ici fait refuser l'asset : l'accepter reviendrait à rendre un
/// modèle dont l'auteur a dit qu'il ne s'affiche pas correctement sans elle.
pub const SUPPORTED_EXTENSIONS: [&str; 8] = [
    "KHR_materials_emissive_strength",
    "KHR_texture_transform",
    "KHR_materials_unlit",
    "KHR_materials_clearcoat",
    "KHR_materials_sheen",
    "KHR_materials_anisotropy",
    "KHR_materials_ior",
    "KHR_materials_specular",
];

/// Ce que l'import glTF rapporte en plus de l'asset.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct GltfReport {
    /// Extensions citées dans `extensionsUsed` et non supportées.
    ///
    /// R-530 les fait ignorer **avec avertissement** : l'auteur a dit qu'elles
    /// étaient facultatives, le modèle s'affiche sans.
    pub ignored_extensions: Vec<String>,
    /// Annotations mal formées, rôles inconnus (R-912).
    pub warnings: Vec<String>,
    /// Images désignées, jamais décodées (R-532).
    pub images: Vec<ImageRef>,
}

/// Une image que l'asset désigne.
///
/// R-532 : extraite, **jamais décodée ici**. Le `ResourceManager` de Minecraft
/// s'en charge, et n'accepte que du PNG.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ImageRef {
    /// Chemin relatif, pour une image externe.
    pub uri: Option<String>,
    /// Type MIME déclaré, pour une image embarquée.
    pub mime_type: Option<String>,
}

/// Importe une source glTF ou GLB.
///
/// `resolve` fournit le contenu d'une ressource externe — tampon ou image —
/// désignée par un URI. Le chemin lui parvient **déjà validé** (R-531). Rendre
/// `None` déclare la ressource absente.
///
/// # Errors
///
/// [`ImportError::SourceTooLarge`] au-delà du plafond (R-533),
/// [`ImportError::ExternalPath`] sur un URI absolu ou remontant (R-531),
/// [`ImportError::UnsupportedExtension`] sur une extension requise et non
/// supportée (R-530), [`ImportError::UnsupportedImage`] sur une image qui n'est
/// pas du PNG (R-532), [`ImportError::Malformed`] si le contenu est illisible.
pub fn import_gltf(
    bytes: &[u8],
    limits: &ImportLimits,
    mut resolve: impl FnMut(&str) -> Option<Vec<u8>>,
) -> Result<(ImportedAsset, GltfReport), ImportError> {
    let format = if bytes.starts_with(b"glTF") {
        SourceFormat::Glb
    } else {
        SourceFormat::Gltf
    };
    limits.check_size(format, bytes.len() as u64)?;

    // La politique d'extensions est celle de R-530, pas celle de la
    // bibliothèque : elle s'applique donc **avant** l'analyse. La déléguer
    // ferait dépendre ce qu'AXION accepte de ce que la bibliothèque implémente,
    // et un asset refusé le serait alors pour la mauvaise raison.
    let mut report = GltfReport::default();
    check_extensions(json_text(bytes, format)?, &mut report)?;

    let document = gltf::Gltf::from_slice(bytes).map_err(|error| ImportError::Malformed {
        format,
        detail: error.to_string(),
    })?;

    let buffers = load_buffers(&document, format, &mut resolve)?;
    collect_images(&document, &mut report)?;

    let mut asset = ImportedAsset::default();
    let mut annotations = Vec::new();

    let placement = import_hierarchy(&document, &mut asset, &mut annotations, &mut report);
    import_meshes(&document, &buffers, &mut asset, format)?;
    import_materials(&document, &mut asset)?;
    check_skins(&document, format)?;

    // Les nodes ont été créés avant que les meshes n'existent : leur index de
    // mesh est posé maintenant, une fois que chacun connaît sa place.
    bind_meshes(&document, &placement, &mut asset);

    Ok((asset, report))
}

/// Rend le texte JSON du document.
///
/// Pour un `.gltf`, c'est le fichier entier. Pour un GLB, c'est le premier
/// morceau : douze octets d'en-tête, puis `[longueur][type][données]`, le type
/// `JSON` valant `0x4E4F534A`.
fn json_text(bytes: &[u8], format: SourceFormat) -> Result<&str, ImportError> {
    let malformed = |detail: &str| ImportError::Malformed {
        format,
        detail: detail.to_owned(),
    };

    let slice = if format == SourceFormat::Glb {
        const GLB_HEADER: usize = 12;
        const CHUNK_HEADER: usize = 8;
        const JSON_CHUNK: u32 = 0x4E4F_534A;

        if bytes.len() < GLB_HEADER + CHUNK_HEADER {
            return Err(malformed("GLB tronqué"));
        }
        let length = u32::from_le_bytes(
            bytes[GLB_HEADER..GLB_HEADER + 4]
                .try_into()
                .expect("quatre octets"),
        ) as usize;
        let kind = u32::from_le_bytes(
            bytes[GLB_HEADER + 4..GLB_HEADER + 8]
                .try_into()
                .expect("quatre octets"),
        );
        if kind != JSON_CHUNK {
            return Err(malformed("premier morceau GLB non JSON"));
        }
        let start = GLB_HEADER + CHUNK_HEADER;
        bytes
            .get(start..start + length)
            .ok_or_else(|| malformed("morceau JSON hors des bornes du GLB"))?
    } else {
        bytes
    };

    core::str::from_utf8(slice).map_err(|_| malformed("JSON non UTF-8"))
}

/// R-530 — les extensions requises doivent être supportées.
fn check_extensions(json: &str, report: &mut GltfReport) -> Result<(), ImportError> {
    for extension in string_array(json, "extensionsRequired") {
        if !SUPPORTED_EXTENSIONS.contains(&extension.as_str()) {
            return Err(ImportError::UnsupportedExtension(extension));
        }
    }
    for extension in string_array(json, "extensionsUsed") {
        if !SUPPORTED_EXTENSIONS.contains(&extension.as_str()) {
            // Citée dans `extensionsUsed` seulement : l'auteur a dit qu'elle
            // était facultative. Le modèle s'affiche sans, et l'avertissement
            // dit ce qui manque.
            report.ignored_extensions.push(extension);
        }
    }
    Ok(())
}

/// Lit un tableau de chaînes JSON, au premier niveau.
///
/// Un analyseur minimal, comme ailleurs dans ce crate : la table 32.2 écarte un
/// analyseur JSON du runtime, et ce qui est cherché ici tient en une forme.
fn string_array(json: &str, key: &str) -> Vec<String> {
    let needle = format!("\"{key}\"");
    let Some(at) = json.find(&needle) else {
        return Vec::new();
    };
    let after = &json[at + needle.len()..];
    let Some(open) = after.find('[') else {
        return Vec::new();
    };
    let Some(close) = after[open..].find(']') else {
        return Vec::new();
    };

    after[open + 1..open + close]
        .split(',')
        .filter_map(|item| {
            let trimmed = item.trim();
            let inner = trimmed.strip_prefix('"')?.strip_suffix('"')?;
            Some(inner.to_owned())
        })
        .collect()
}

/// Charge les tampons, en refusant tout ce qui sort du répertoire de l'asset.
fn load_buffers(
    document: &gltf::Gltf,
    format: SourceFormat,
    resolve: &mut impl FnMut(&str) -> Option<Vec<u8>>,
) -> Result<Vec<Vec<u8>>, ImportError> {
    let mut buffers = Vec::with_capacity(document.buffers().len());

    for buffer in document.buffers() {
        let data = match buffer.source() {
            gltf::buffer::Source::Bin => document.blob.clone().unwrap_or_default(),
            gltf::buffer::Source::Uri(uri) => {
                if let Some(payload) = decode_data_uri(uri) {
                    payload?
                } else {
                    check_relative_path(uri)?;
                    resolve(uri).ok_or_else(|| ImportError::Malformed {
                        format,
                        detail: format!("tampon « {uri} » introuvable"),
                    })?
                }
            }
        };
        buffers.push(data);
    }
    Ok(buffers)
}

/// R-532 — les images sont désignées, jamais décodées.
fn collect_images(document: &gltf::Gltf, report: &mut GltfReport) -> Result<(), ImportError> {
    for image in document.images() {
        match image.source() {
            gltf::image::Source::Uri { uri, mime_type } => {
                if decode_data_uri(uri).is_none() {
                    check_relative_path(uri)?;
                }
                check_image_type(mime_type, uri)?;
                report.images.push(ImageRef {
                    uri: Some(uri.to_owned()),
                    mime_type: mime_type.map(ToOwned::to_owned),
                });
            }
            gltf::image::Source::View { mime_type, .. } => {
                check_image_type(Some(mime_type), "<embarquée>")?;
                report.images.push(ImageRef {
                    uri: None,
                    mime_type: Some(mime_type.to_owned()),
                });
            }
        }
    }
    Ok(())
}

/// Refuse une image qui n'est pas du PNG (R-532, `E-3004`).
///
/// Le type est vérifié **avant** que quoi que ce soit ne touche l'image : c'est
/// tout l'intérêt de ne pas décoder ici.
fn check_image_type(mime_type: Option<&str>, designation: &str) -> Result<(), ImportError> {
    match mime_type {
        Some("image/png") | None => Ok(()),
        Some(other) => Err(ImportError::UnsupportedImage {
            designation: designation.to_owned(),
            mime_type: other.to_owned(),
        }),
    }
}

/// Décode un URI `data:` en base64.
///
/// Rend `None` si l'URI n'en est pas un. Le décodage est écrit ici plutôt
/// qu'importé : trente lignes ne justifient pas une dépendance de plus
/// (R-2300), et celle-ci se placerait sur un chemin qui lit des données
/// hostiles.
fn decode_data_uri(uri: &str) -> Option<Result<Vec<u8>, ImportError>> {
    let rest = uri.strip_prefix("data:")?;
    let payload = rest.split_once(";base64,").map(|(_, payload)| payload)?;
    Some(
        decode_base64(payload).ok_or_else(|| ImportError::Malformed {
            format: SourceFormat::Gltf,
            detail: "URI data: mal encodé".to_owned(),
        }),
    )
}

/// Décode du base64 standard, avec ou sans remplissage.
fn decode_base64(input: &str) -> Option<Vec<u8>> {
    let value = |byte: u8| -> Option<u32> {
        Some(match byte {
            b'A'..=b'Z' => u32::from(byte - b'A'),
            b'a'..=b'z' => u32::from(byte - b'a') + 26,
            b'0'..=b'9' => u32::from(byte - b'0') + 52,
            b'+' => 62,
            b'/' => 63,
            _ => return None,
        })
    };

    let trimmed = input.trim_end_matches('=');
    let mut out = Vec::with_capacity(trimmed.len() * 3 / 4);
    let mut accumulator = 0u32;
    let mut bits = 0u32;

    for byte in trimmed.bytes() {
        if byte.is_ascii_whitespace() {
            continue;
        }
        accumulator = (accumulator << 6) | value(byte)?;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push(u8::try_from((accumulator >> bits) & 0xFF).ok()?);
        }
    }
    Some(out)
}

/// Construit la hiérarchie **en ordre topologique** (R-130).
///
/// glTF n'impose aucun ordre à ses nodes : un parent peut y être déclaré après
/// son enfant. AXION l'exige, et le validateur le vérifie — le parcours en
/// largeur depuis les racines donne cet ordre, sans qu'il faille trier après
/// coup.
fn import_hierarchy(
    document: &gltf::Gltf,
    asset: &mut ImportedAsset,
    annotations: &mut Vec<NodeAnnotations>,
    report: &mut GltfReport,
) -> Vec<Option<u32>> {
    // Position de chaque node glTF dans l'asset, une fois placé.
    let mut placement = vec![None; document.nodes().len()];
    let mut queue: Vec<(gltf::Node, u32)> = Vec::new();

    for scene in document.scenes() {
        for node in scene.nodes() {
            queue.push((node, NO_PARENT));
        }
    }

    let mut cursor = 0;
    while cursor < queue.len() {
        let (node, parent) = queue[cursor].clone();
        cursor += 1;

        let annotation = node
            .extras()
            .as_ref()
            .map_or_else(NodeAnnotations::default, |extras| {
                NodeAnnotations::parse(extras.get())
            });
        for warning in &annotation.warnings {
            report.warnings.push(format!(
                "node « {} » : {warning}",
                node.name().unwrap_or("<sans nom>")
            ));
        }

        let index = asset.nodes.len() as u32;
        placement[node.index()] = Some(index);
        asset.nodes.push(node_desc(&node, parent, &annotation));
        asset
            .names
            .push(("node", node.name().unwrap_or("node").to_owned()));
        annotations.push(annotation);

        for child in node.children() {
            queue.push((child, index));
        }
    }

    placement
}

fn node_desc(node: &gltf::Node, parent: u32, annotation: &NodeAnnotations) -> NodeDesc {
    let (translation, rotation, scale) = node.transform().decomposed();

    let mut flags = 0;
    // R-910 : un collider, un socket, une zone de dommage, une région ou un
    // ancrage n'est jamais rendu.
    if annotation.role.is_rendered() {
        flags |= node_flags::VISIBLE;
    }
    match annotation.role {
        NodeRole::Wheel => flags |= node_flags::WHEEL,
        NodeRole::Seat => flags |= node_flags::SEAT,
        NodeRole::Socket => flags |= node_flags::SOCKET,
        NodeRole::Light => flags |= node_flags::LIGHT,
        NodeRole::ClothAnchor => flags |= node_flags::CLOTH_ANCHOR,
        NodeRole::Internal => flags |= node_flags::INTERNAL,
        _ => {}
    }

    let lod_mask = annotation
        .lod
        .iter()
        .filter(|level| **level < 8)
        .fold(0u8, |mask, level| mask | (1 << level));

    NodeDesc {
        name_hash: fnv1a64(node.name().unwrap_or("")),
        parent,
        local: Transform {
            translation,
            rotation,
            scale,
        },
        flags,
        // Posé par `bind_meshes`, une fois les meshes construits.
        mesh: NONE_U32,
        collider: NONE_U32,
        bone: NONE_U32,
        part: NONE_U16,
        region: NONE_U16,
        // Un node sans annotation de LOD apparaît au niveau zéro : l'absence
        // d'annotation ne doit pas rendre le node invisible (R-913).
        lod_mask: if lod_mask == 0 { 1 } else { lod_mask },
        state: 0,
        _pad: [0; 2],
    }
}

/// Associe à chaque node l'index du premier mesh de sa primitive.
///
/// « Une primitive = un mesh » : un mesh glTF à plusieurs primitives en produit
/// plusieurs, et le node désigne le premier.
///
/// L'association passe par la table de placement, pas par le nom : deux nodes
/// peuvent porter le même nom — glTF ne l'interdit pas — et les retrouver par
/// là ferait désigner le mauvais mesh à l'un des deux.
fn bind_meshes(document: &gltf::Gltf, placement: &[Option<u32>], asset: &mut ImportedAsset) {
    let mut first_of_gltf_mesh = Vec::with_capacity(document.meshes().len());
    let mut cursor = 0u32;
    for mesh in document.meshes() {
        first_of_gltf_mesh.push(cursor);
        cursor += mesh.primitives().len() as u32;
    }

    for node in document.nodes() {
        let (Some(mesh), Some(Some(placed))) = (node.mesh(), placement.get(node.index())) else {
            continue;
        };
        if let Some(desc) = asset.nodes.get_mut(*placed as usize) {
            desc.mesh = first_of_gltf_mesh[mesh.index()];
        }
    }
}

/// Construit les meshes : une primitive glTF donne un mesh AXION.
fn import_meshes(
    document: &gltf::Gltf,
    buffers: &[Vec<u8>],
    asset: &mut ImportedAsset,
    format: SourceFormat,
) -> Result<(), ImportError> {
    for mesh in document.meshes() {
        for primitive in mesh.primitives() {
            if primitive.mode() != gltf::mesh::Mode::Triangles {
                // Le moteur ne connaît que des triangles. Convertir un
                // `TriangleStrip` serait possible, mais silencieux : mieux vaut
                // que l'auteur triangule à l'export et sache ce qu'il envoie.
                return Err(ImportError::Malformed {
                    format,
                    detail: format!(
                        "primitive en mode {:?} : seuls les triangles sont lus",
                        primitive.mode()
                    ),
                });
            }

            // Le type de composant des indices est vérifié **avant** toute
            // lecture. glTF 2.0 n'admet pour un accesseur d'indices que
            // `UNSIGNED_BYTE`, `UNSIGNED_SHORT` et `UNSIGNED_INT` ; le refuser
            // ici est donc conforme à la spécification, pas un contournement.
            //
            // Sans cette vérification, `read_indices` du crate `gltf` atteint
            // un `unreachable!()` et **panique**. Trouvé par fuzzing (R-903) :
            // un accesseur déclarant `5122` — `SHORT`, signé — suffit, et un
            // exportateur bogué peut l'émettre. La panique était contenue par
            // le pool de jobs et par la frontière FFI, mais un asset qui doit
            // être refusé se refuse ; il ne panique pas.
            if let Some(indices) = primitive.indices() {
                let data_type = indices.data_type();
                if !matches!(
                    data_type,
                    gltf::accessor::DataType::U8
                        | gltf::accessor::DataType::U16
                        | gltf::accessor::DataType::U32
                ) {
                    return Err(ImportError::Malformed {
                        format,
                        detail: format!(
                            "accesseur d'indices de type {data_type:?} : glTF 2.0 \
                             n'admet que des entiers non signés"
                        ),
                    });
                }
            }

            let reader = primitive.reader(|buffer| buffers.get(buffer.index()).map(Vec::as_slice));
            let vertex_offset = asset.vertices.len() as u32;
            let index_offset = asset.indices.len() as u32;

            let Some(positions) = reader.read_positions() else {
                return Err(ImportError::Malformed {
                    format,
                    detail: "primitive sans position".to_owned(),
                });
            };
            let positions: Vec<[f32; 3]> = positions.collect();

            let normals: Vec<[f32; 3]> = reader
                .read_normals()
                .map(Iterator::collect)
                .unwrap_or_default();
            let uvs: Vec<[f32; 2]> = reader
                .read_tex_coords(0)
                .map(|coords| coords.into_f32().collect())
                .unwrap_or_default();
            let joints: Vec<[u16; 4]> = reader
                .read_joints(0)
                .map(|joints| joints.into_u16().collect())
                .unwrap_or_default();
            let weights: Vec<[f32; 4]> = reader
                .read_weights(0)
                .map(|weights| weights.into_f32().collect())
                .unwrap_or_default();

            for (index, position) in positions.iter().enumerate() {
                let uv = uvs.get(index).copied().unwrap_or([0.0, 0.0]);
                asset.vertices.push(Vertex {
                    position: *position,
                    normal: normals
                        .get(index)
                        .map_or([0, 127, 0, 0], |normal| encode_normal(*normal)),
                    // Les tangentes viennent de C-23, par mikktspace.
                    tangent: [0; 4],
                    uv0: quantize_uv(uv),
                    uv1: [0; 2],
                    color: [255; 4],
                    bones: joints.get(index).map_or([0; 4], |joint| {
                        [
                            u8::try_from(joint[0]).unwrap_or(0),
                            u8::try_from(joint[1]).unwrap_or(0),
                            u8::try_from(joint[2]).unwrap_or(0),
                            u8::try_from(joint[3]).unwrap_or(0),
                        ]
                    }),
                    weights: weights
                        .get(index)
                        .map_or([255, 0, 0, 0], |weight| quantize_weights(*weight)),
                    region: NO_REGION_U8,
                    def_w: 0,
                    _pad: [0; 6],
                });
                // R-142 porte sur les valeurs avant normalisation.
                asset.raw_uvs.push(uv);
            }

            match reader.read_indices() {
                Some(indices) => asset.indices.extend(indices.into_u32()),
                // Une primitive sans indices est une liste de triangles
                // implicite : glTF l'autorise, et l'expliciter ici évite un cas
                // particulier partout ailleurs.
                None => asset
                    .indices
                    .extend(0..u32::try_from(positions.len()).unwrap_or(0)),
            }

            let vertex_count = asset.vertices.len() as u32 - vertex_offset;
            let index_count = asset.indices.len() as u32 - index_offset;
            let (aabb_min, aabb_max) = bounds(&asset.vertices[vertex_offset as usize..]);

            asset.meshes.push(MeshDesc {
                vertex_offset,
                vertex_count,
                index_offset,
                index_count,
                material: primitive
                    .material()
                    .index()
                    .and_then(|index| u16::try_from(index).ok())
                    .unwrap_or(0),
                lod: 0,
                flags: if joints.is_empty() {
                    0
                } else {
                    ax_model::dm::geometry::mesh_flags::SKINNED
                },
                aabb_min,
                aabb_max,
                region: NONE_U16,
                _pad: 0,
            });
        }
    }
    Ok(())
}

fn import_materials(document: &gltf::Gltf, asset: &mut ImportedAsset) -> Result<(), ImportError> {
    for material in document.materials() {
        let pbr = material.pbr_metallic_roughness();
        let texture = match pbr.base_color_texture() {
            Some(info) => match info.texture().source().source() {
                gltf::image::Source::Uri { uri, .. } => {
                    if decode_data_uri(uri).is_none() {
                        check_relative_path(uri)?;
                    }
                    Some(uri.to_owned())
                }
                // Une image embarquée n'a pas de chemin : elle voyagera dans la
                // section `TEXR` avec l'asset.
                gltf::image::Source::View { .. } => None,
            },
            None => None,
        };

        asset.materials.push(ImportedMaterial {
            name: material.name().unwrap_or("material").to_owned(),
            base_color: pbr.base_color_factor(),
            base_color_texture: texture,
        });
        asset
            .names
            .push(("matériau", material.name().unwrap_or("material").to_owned()));
    }
    Ok(())
}

/// R-200 — 128 os au maximum par squelette (`E-3060`).
fn check_skins(document: &gltf::Gltf, format: SourceFormat) -> Result<(), ImportError> {
    for skin in document.skins() {
        let joints = skin.joints().len();
        if joints > limits::MAX_BONES {
            return Err(ImportError::Malformed {
                format,
                detail: format!("{joints} os, maximum {}", limits::MAX_BONES),
            });
        }
    }
    Ok(())
}

fn bounds(vertices: &[Vertex]) -> ([f32; 3], [f32; 3]) {
    if vertices.is_empty() {
        return ([0.0; 3], [0.0; 3]);
    }
    let mut min = [f32::INFINITY; 3];
    let mut max = [f32::NEG_INFINITY; 3];
    for vertex in vertices {
        for axis in 0..3 {
            min[axis] = min[axis].min(vertex.position[axis]);
            max[axis] = max[axis].max(vertex.position[axis]);
        }
    }
    (min, max)
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

/// Quantifie les poids d'os, somme exactement 255 (DM-04).
fn quantize_weights(weights: [f32; 4]) -> [u8; 4] {
    let total: f32 = weights.iter().filter(|value| value.is_finite()).sum();
    if !total.is_finite() || total <= 0.0 {
        // Des poids inutilisables ne sont pas répartis au hasard : le
        // validateur le constatera, ce qui vaut mieux qu'un mouvement absurde.
        return [0; 4];
    }

    let mut scaled = [0u16; 4];
    for (index, weight) in weights.iter().enumerate() {
        let normalized = if weight.is_finite() {
            *weight / total
        } else {
            0.0
        };
        scaled[index] = (normalized * 255.0).round().clamp(0.0, 255.0) as u16;
    }

    // L'arrondi ne tombe pas juste : le reste va au poids dominant, faute de
    // quoi la somme s'écarterait de 255 et le validateur refuserait le sommet.
    let sum: u16 = scaled.iter().sum();
    let dominant = scaled
        .iter()
        .enumerate()
        .max_by_key(|(_, value)| **value)
        .map_or(0, |(index, _)| index);
    match sum.cmp(&255) {
        core::cmp::Ordering::Less => scaled[dominant] += 255 - sum,
        core::cmp::Ordering::Greater => scaled[dominant] -= sum - 255,
        core::cmp::Ordering::Equal => {}
    }

    let mut out = [0u8; 4];
    for (index, value) in scaled.iter().enumerate() {
        out[index] = u8::try_from(*value).unwrap_or(u8::MAX);
    }
    out
}

/// FNV-1a 64 bits, l'empreinte de nom de DM-01.
fn fnv1a64(value: &str) -> u64 {
    let mut hash = 0xcbf2_9ce4_8422_2325_u64;
    for byte in value.as_bytes() {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    hash
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn t228_le_base64_se_decode() {
        assert_eq!(decode_base64("QUJD").expect("décodage"), b"ABC");
        assert_eq!(decode_base64("QUJDRA==").expect("décodage"), b"ABCD");
        assert_eq!(decode_base64("").expect("décodage"), b"");
        // Un caractère hors alphabet est refusé plutôt qu'ignoré : l'ignorer
        // décalerait tout ce qui suit.
        assert!(decode_base64("QU!D").is_none());
    }

    #[test]
    fn t228_un_uri_data_est_reconnu() {
        let decoded = decode_data_uri("data:application/octet-stream;base64,QUJD")
            .expect("URI data non reconnu")
            .expect("décodage");
        assert_eq!(decoded, b"ABC");

        // Un chemin relatif n'en est pas un.
        assert!(decode_data_uri("buffer.bin").is_none());
    }

    #[test]
    fn t228_les_poids_quantifies_somment_a_255() {
        assert_eq!(quantize_weights([1.0, 0.0, 0.0, 0.0]), [255, 0, 0, 0]);
        assert_eq!(
            quantize_weights([0.25, 0.25, 0.25, 0.25])
                .iter()
                .map(|weight| u16::from(*weight))
                .sum::<u16>(),
            255
        );
        // Un tiers chacun ne tombe pas juste : le reste va au dominant.
        let tiers = quantize_weights([1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0, 0.0]);
        assert_eq!(tiers.iter().map(|w| u16::from(*w)).sum::<u16>(), 255);
    }

    #[test]
    fn t228_des_poids_inutilisables_ne_sont_pas_repartis_au_hasard() {
        assert_eq!(quantize_weights([0.0; 4]), [0; 4]);
        assert_eq!(quantize_weights([f32::NAN, 0.0, 0.0, 0.0]), [0; 4]);
    }

    #[test]
    fn t228_les_extensions_supportees_sont_celles_de_r530() {
        assert_eq!(SUPPORTED_EXTENSIONS.len(), 8);
        assert!(SUPPORTED_EXTENSIONS.contains(&"KHR_texture_transform"));
        assert!(!SUPPORTED_EXTENSIONS.contains(&"KHR_draco_mesh_compression"));
    }

    #[test]
    fn t228_seul_le_png_est_accepte() {
        assert!(check_image_type(Some("image/png"), "t.png").is_ok());
        // Sans type déclaré, c'est l'extension qui tranchera côté Java.
        assert!(check_image_type(None, "t.png").is_ok());

        let refus = check_image_type(Some("image/jpeg"), "t.jpg").unwrap_err();
        assert_eq!(refus.code(), -3004);
    }
}
