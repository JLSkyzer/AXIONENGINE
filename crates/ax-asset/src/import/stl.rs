//! Import STL (C-21) — géométrie seule.
//!
//! Le STL ne porte ni matériau, ni hiérarchie, ni UV : un tas de triangles. Le
//! cahier des charges le retient pour les colliders et les formes simples, et
//! c'est exactement ce qu'on en tire.

use super::{ImportError, ImportLimits, ImportedAsset, SourceFormat};
use ax_model::dm::geometry::{MeshDesc, Transform, Vertex, NO_REGION_U8};
use ax_model::dm::scene::{node_flags, NodeDesc, NONE_U16, NONE_U32, NO_PARENT};

/// Taille de l'en-tête d'un STL binaire, en octets.
const BINARY_HEADER_BYTES: u64 = 80;

/// Taille du dénombrement de triangles, en octets.
const BINARY_COUNT_BYTES: u64 = 4;

/// Taille d'un triangle binaire : normale, trois sommets, attribut.
const BINARY_TRIANGLE_BYTES: u64 = 50;

/// Importe une source STL.
///
/// # Errors
///
/// [`ImportError::SourceTooLarge`] au-delà du plafond (R-533),
/// [`ImportError::DeclaredSizeMismatch`] si l'en-tête binaire annonce plus de
/// triangles que le fichier n'en contient, [`ImportError::Malformed`] si le
/// contenu est illisible.
pub fn import_stl(bytes: &[u8], limits: &ImportLimits) -> Result<ImportedAsset, ImportError> {
    limits.check_size(SourceFormat::Stl, bytes.len() as u64)?;
    check_declared_triangles(bytes)?;

    // Même filet que pour les deux autres importeurs : `stl_io` est un
    // analyseur tiers qui reçoit du contenu non fiable, et rien ne garantit
    // qu'il ne panique pas. Le fuzzing n'y a rien trouvé à ce jour, mais ne pas
    // l'envelopper serait le traiter autrement que ses voisins sans raison.
    let mesh = super::catch_parser_panic(SourceFormat::Stl, || {
        let mut cursor = std::io::Cursor::new(bytes);
        stl_io::read_stl(&mut cursor).map_err(|error| ImportError::Malformed {
            format: SourceFormat::Stl,
            detail: error.to_string(),
        })
    })?;

    let mut asset = ImportedAsset::default();

    for vertex in &mesh.vertices {
        let position = [vertex[0], vertex[1], vertex[2]];
        asset.vertices.push(canonical_vertex(position));
        // Le format ne porte pas d'UV : rien à contrôler côté R-142, et en
        // inventer donnerait un plaquage arbitraire.
        asset.raw_uvs.push([0.0, 0.0]);
    }

    for face in &mesh.faces {
        let normal = encode_normal([face.normal[0], face.normal[1], face.normal[2]]);
        for index in face.vertices {
            let Ok(index) = u32::try_from(index) else {
                return Err(ImportError::Malformed {
                    format: SourceFormat::Stl,
                    detail: "index de sommet hors des bornes d'un u32".to_owned(),
                });
            };
            asset.indices.push(index);
            // Le STL porte une normale **par facette**. La reporter sur les
            // sommets donne un rendu à facettes, ce qui est la lecture fidèle
            // du format ; lisser reviendrait à décider à la place de l'auteur.
            if let Some(vertex) = asset.vertices.get_mut(index as usize) {
                vertex.normal = normal;
            }
        }
    }

    asset.meshes.push(mesh_desc(&asset));
    asset.nodes.push(root_node());
    asset.names.push(("node", "stl_root".to_owned()));

    Ok(asset)
}

/// Vérifie qu'un STL binaire n'annonce pas plus de triangles qu'il n'en porte.
///
/// C'est le vecteur d'attaque le plus simple du format : quatre octets
/// annonçant quatre milliards de triangles dans un fichier de cent octets, et
/// un lecteur naïf qui pré-alloue. Le contrôle a lieu **avant** de confier quoi
/// que ce soit à l'analyseur.
fn check_declared_triangles(bytes: &[u8]) -> Result<(), ImportError> {
    let len = bytes.len() as u64;
    if len < BINARY_HEADER_BYTES + BINARY_COUNT_BYTES {
        // Trop court pour être un binaire : c'est peut-être un STL ASCII, que
        // l'analyseur saura reconnaître ou refuser.
        return Ok(());
    }
    // Un STL ASCII commence par « solid ». Le binaire n'a pas de marqueur, et
    // c'est cette absence qui oblige à deviner — les deux formats partagent
    // une extension.
    if bytes.starts_with(b"solid") {
        return Ok(());
    }

    let start = BINARY_HEADER_BYTES as usize;
    let declared = u64::from(u32::from_le_bytes(
        bytes[start..start + 4].try_into().expect("quatre octets"),
    ));
    let possible = (len - BINARY_HEADER_BYTES - BINARY_COUNT_BYTES) / BINARY_TRIANGLE_BYTES;

    if declared > possible {
        return Err(ImportError::DeclaredSizeMismatch {
            format: SourceFormat::Stl,
            what: "triangles",
            declared,
            possible,
        });
    }
    Ok(())
}

/// Compose le sommet canonique d'une position.
fn canonical_vertex(position: [f32; 3]) -> Vertex {
    Vertex {
        position,
        // La normale est posée par la facette ; celle-ci n'est qu'un point de
        // départ valide, jamais rendu tel quel.
        normal: [0, 127, 0, 0],
        // Les tangentes viennent de C-23, par mikktspace. Les inventer ici
        // produirait un éclairage faux plutôt qu'un éclairage absent.
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

/// Encode une normale en `i8` normalisée.
fn encode_normal(normal: [f32; 3]) -> [i8; 4] {
    let length = (normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2]).sqrt();
    if !length.is_finite() || length <= f32::EPSILON {
        // Une facette sans normale exploitable garde la valeur de départ : on
        // ne remplace pas une direction absente par une direction inventée.
        return [0, 127, 0, 0];
    }

    let mut encoded = [0i8; 4];
    for (index, value) in normal.iter().enumerate() {
        encoded[index] = ((value / length) * 127.0).round().clamp(-127.0, 127.0) as i8;
    }
    encoded
}

fn mesh_desc(asset: &ImportedAsset) -> MeshDesc {
    let mut min = [f32::INFINITY; 3];
    let mut max = [f32::NEG_INFINITY; 3];
    for vertex in &asset.vertices {
        for axis in 0..3 {
            min[axis] = min[axis].min(vertex.position[axis]);
            max[axis] = max[axis].max(vertex.position[axis]);
        }
    }
    if asset.vertices.is_empty() {
        // Une boîte englobante d'un ensemble vide n'a pas de sens ; celle-ci
        // est dégénérée mais finie, et le validateur l'accepte comme telle.
        min = [0.0; 3];
        max = [0.0; 3];
    }

    MeshDesc {
        vertex_offset: 0,
        vertex_count: asset.vertices.len() as u32,
        index_offset: 0,
        index_count: asset.indices.len() as u32,
        material: 0,
        lod: 0,
        flags: 0,
        aabb_min: min,
        aabb_max: max,
        region: NONE_U16,
        _pad: 0,
    }
}

fn root_node() -> NodeDesc {
    NodeDesc {
        name_hash: 0,
        parent: NO_PARENT,
        local: Transform::identity(),
        flags: node_flags::VISIBLE,
        mesh: 0,
        collider: NONE_U32,
        bone: NONE_U32,
        part: NONE_U16,
        region: NONE_U16,
        lod_mask: 1,
        state: 0,
        _pad: [0; 2],
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Construit un STL binaire d'un triangle.
    fn binaire(triangles: &[([f32; 3], [[f32; 3]; 3])]) -> Vec<u8> {
        let mut bytes = vec![0u8; BINARY_HEADER_BYTES as usize];
        bytes.extend_from_slice(&(triangles.len() as u32).to_le_bytes());
        for (normal, corners) in triangles {
            for value in normal {
                bytes.extend_from_slice(&value.to_le_bytes());
            }
            for corner in corners {
                for value in corner {
                    bytes.extend_from_slice(&value.to_le_bytes());
                }
            }
            bytes.extend_from_slice(&0u16.to_le_bytes());
        }
        bytes
    }

    fn limits() -> ImportLimits {
        ImportLimits::new(1 << 20)
    }

    #[test]
    fn t223_un_stl_binaire_donne_ses_triangles() {
        let bytes = binaire(&[(
            [0.0, 0.0, 1.0],
            [[0.0, 0.0, 0.0], [1.0, 0.0, 0.0], [0.0, 1.0, 0.0]],
        )]);

        let asset = import_stl(&bytes, &limits()).expect("import refusé");
        assert_eq!(asset.indices.len(), 3);
        assert_eq!(asset.vertices.len(), 3);
        assert_eq!(asset.meshes.len(), 1);
        assert_eq!(asset.nodes.len(), 1);
        assert_eq!(asset.meshes[0].aabb_min, [0.0, 0.0, 0.0]);
        assert_eq!(asset.meshes[0].aabb_max, [1.0, 1.0, 0.0]);
    }

    #[test]
    fn t223_la_normale_de_facette_est_reportee_sur_les_sommets() {
        let bytes = binaire(&[(
            [0.0, 0.0, 1.0],
            [[0.0, 0.0, 0.0], [1.0, 0.0, 0.0], [0.0, 1.0, 0.0]],
        )]);

        let asset = import_stl(&bytes, &limits()).expect("import refusé");
        // Le STL porte une normale par facette : la reporter est la lecture
        // fidèle du format, lisser déciderait à la place de l'auteur.
        for vertex in &asset.vertices {
            assert_eq!(vertex.normal, [0, 0, 127, 0]);
        }
    }

    #[test]
    fn t223_une_normale_de_facette_nulle_ne_produit_pas_de_direction_inventee() {
        let bytes = binaire(&[(
            [0.0, 0.0, 0.0],
            [[0.0, 0.0, 0.0], [1.0, 0.0, 0.0], [0.0, 1.0, 0.0]],
        )]);

        let asset = import_stl(&bytes, &limits()).expect("import refusé");
        for vertex in &asset.vertices {
            assert_eq!(vertex.normal, [0, 127, 0, 0], "direction inventée");
        }
    }

    #[test]
    fn t224_un_denombrement_mensonger_est_refuse_avant_toute_allocation() {
        let mut bytes = binaire(&[(
            [0.0, 0.0, 1.0],
            [[0.0, 0.0, 0.0], [1.0, 0.0, 0.0], [0.0, 1.0, 0.0]],
        )]);
        // Quatre milliards de triangles annoncés dans un fichier de cent
        // trente-quatre octets : le vecteur d'attaque le plus simple du format.
        let start = BINARY_HEADER_BYTES as usize;
        bytes[start..start + 4].copy_from_slice(&u32::MAX.to_le_bytes());

        let refus = import_stl(&bytes, &limits()).unwrap_err();
        assert!(
            matches!(refus, ImportError::DeclaredSizeMismatch { declared, .. }
                if declared == u64::from(u32::MAX)),
            "{refus:?}"
        );
    }

    #[test]
    fn t224_un_stl_ascii_est_reconnu() {
        let source = b"solid test
facet normal 0 0 1
  outer loop
    vertex 0 0 0
    vertex 1 0 0
    vertex 0 1 0
  endloop
endfacet
endsolid test
";
        let asset = import_stl(source, &limits()).expect("import refusé");
        assert_eq!(asset.indices.len(), 3);
    }

    #[test]
    fn t221_une_source_trop_volumineuse_est_refusee() {
        let bytes = binaire(&[(
            [0.0, 0.0, 1.0],
            [[0.0, 0.0, 0.0], [1.0, 0.0, 0.0], [0.0, 1.0, 0.0]],
        )]);

        let refus = import_stl(&bytes, &ImportLimits::new(64)).unwrap_err();
        assert_eq!(refus.code(), -3005);
    }

    #[test]
    fn t224_une_source_illisible_est_refusee_sans_paniquer() {
        // Des octets qui ne sont ni un binaire cohérent ni de l'ASCII.
        let refus = import_stl(&[0xFF; 32], &limits()).unwrap_err();
        assert!(matches!(refus, ImportError::Malformed { .. }), "{refus:?}");
    }
}
