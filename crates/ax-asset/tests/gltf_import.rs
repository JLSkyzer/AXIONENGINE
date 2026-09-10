//! T-220, T-228 — import glTF sur des documents réels.
//!
//! Les documents sont construits ici, entièrement : un fichier de fixture
//! binaire cacherait ce qui est testé, alors que ce sont précisément les
//! détails du document qui font l'objet de chaque test.

use ax_asset::import::{import_gltf, ImportLimits, SUPPORTED_EXTENSIONS};
use ax_asset::validate::{validate, AssetView, NamedEntry};
use ax_model::dm::scene::node_flags;

const LIMITS: ImportLimits = ImportLimits::new(1 << 20);

/// Préfixe d'un URI de données binaires.
const DATA_PREFIX: &str = "data:application/octet-stream;base64,";

/// Un triangle, ses données dans un `data:` URI.
///
/// Trois positions en `f32`, puis trois indices en `u16` : l'encodage base64
/// est celui que produit n'importe quel exportateur pour un `.gltf` autonome.
fn triangle(extras: &str, extensions: &str) -> String {
    let mut buffer = Vec::new();
    for position in [[0.0f32, 0.0, 0.0], [1.0, 0.0, 0.0], [0.0, 1.0, 0.0]] {
        for value in position {
            buffer.extend_from_slice(&value.to_le_bytes());
        }
    }
    let index_offset = buffer.len();
    for index in [0u16, 1, 2] {
        buffer.extend_from_slice(&index.to_le_bytes());
    }

    let encoded = encode(&buffer);
    let len = buffer.len();
    format!(
        "{{
  \"asset\": {{ \"version\": \"2.0\" }},
  {extensions}
  \"scene\": 0,
  \"scenes\": [{{ \"nodes\": [0] }}],
  \"nodes\": [{{ \"name\": \"triangle\", \"mesh\": 0{extras} }}],
  \"meshes\": [{{
    \"name\": \"triangle\",
    \"primitives\": [{{
      \"attributes\": {{ \"POSITION\": 0 }},
      \"indices\": 1,
      \"material\": 0
    }}]
  }}],
  \"materials\": [{{
    \"name\": \"peinture\",
    \"pbrMetallicRoughness\": {{ \"baseColorFactor\": [0.8, 0.1, 0.1, 1.0] }}
  }}],
  \"accessors\": [
    {{ \"bufferView\": 0, \"componentType\": 5126, \"count\": 3, \"type\": \"VEC3\",
      \"min\": [0.0, 0.0, 0.0], \"max\": [1.0, 1.0, 0.0] }},
    {{ \"bufferView\": 1, \"componentType\": 5123, \"count\": 3, \"type\": \"SCALAR\" }}
  ],
  \"bufferViews\": [
    {{ \"buffer\": 0, \"byteOffset\": 0, \"byteLength\": 36 }},
    {{ \"buffer\": 0, \"byteOffset\": {index_offset}, \"byteLength\": 6 }}
  ],
  \"buffers\": [{{ \"byteLength\": {len}, \"uri\": \"{DATA_PREFIX}{encoded}\" }}]
}}"
    )
}

const ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

/// Encode en base64 standard, avec remplissage.
fn encode(bytes: &[u8]) -> String {
    let mut out = String::new();
    for chunk in bytes.chunks(3) {
        let mut block = [0u8; 3];
        block[..chunk.len()].copy_from_slice(chunk);
        let value = (u32::from(block[0]) << 16) | (u32::from(block[1]) << 8) | u32::from(block[2]);
        let produced = chunk.len() + 1;
        for shift in [18, 12, 6, 0].into_iter().take(produced) {
            out.push(ALPHABET[((value >> shift) & 0x3F) as usize] as char);
        }
        for _ in 0..3 - chunk.len() {
            out.push('=');
        }
    }
    out
}

/// Décode du base64, pour les besoins des tests.
fn decode(input: &str) -> Vec<u8> {
    let mut out = Vec::new();
    let mut accumulator = 0u32;
    let mut bits = 0u32;
    for byte in input.trim_end_matches('=').bytes() {
        let value = ALPHABET
            .iter()
            .position(|item| *item == byte)
            .expect("caractère base64") as u32;
        accumulator = (accumulator << 6) | value;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push(((accumulator >> bits) & 0xFF) as u8);
        }
    }
    out
}

#[test]
fn t220_un_gltf_minimal_donne_son_triangle() {
    let source = triangle("", "");
    let (asset, report) = import_gltf(source.as_bytes(), &LIMITS, |_| None).expect("import refusé");

    assert_eq!(asset.nodes.len(), 1);
    assert_eq!(asset.meshes.len(), 1);
    assert_eq!(asset.vertices.len(), 3);
    assert_eq!(asset.indices, vec![0, 1, 2]);
    assert_eq!(asset.materials.len(), 1);
    assert_eq!(asset.materials[0].name, "peinture");
    assert!(report.warnings.is_empty(), "{:?}", report.warnings);
    // Le node désigne bien son mesh.
    assert_eq!(asset.nodes[0].mesh, 0);
}

#[test]
fn t220_un_gltf_importe_est_accepte_par_le_validateur() {
    let source = triangle("", "");
    let (asset, _) = import_gltf(source.as_bytes(), &LIMITS, |_| None).expect("import refusé");

    let names = [NamedEntry::new("node", "triangle")];
    let report = validate(
        &AssetView {
            nodes: &asset.nodes,
            meshes: &asset.meshes,
            vertices: &asset.vertices,
            indices: &asset.indices,
            names: &names,
            material_count: asset.materials.len(),
            dynamic_body: true,
            ..AssetView::default()
        },
        &asset.raw_uvs,
    );

    assert!(report.is_valid(), "{:?}", report.errors);
}

#[test]
fn t228_une_extension_requise_et_non_supportee_est_refusee() {
    // R-530 : l'auteur a dit que le modèle ne s'affiche pas correctement sans
    // elle ; l'accepter reviendrait à le rendre quand même.
    let source = triangle(
        "",
        "\"extensionsRequired\": [\"KHR_draco_mesh_compression\"],
  \"extensionsUsed\": [\"KHR_draco_mesh_compression\"],",
    );

    let refus = import_gltf(source.as_bytes(), &LIMITS, |_| None).unwrap_err();
    assert_eq!(refus.code(), -3003);
}

#[test]
fn t228_une_extension_seulement_utilisee_est_ignoree_avec_avertissement() {
    let source = triangle("", "\"extensionsUsed\": [\"KHR_materials_variants\"],");
    let (asset, report) = import_gltf(source.as_bytes(), &LIMITS, |_| None).expect("import refusé");

    // R-530 : l'auteur l'a donnée pour facultative, le modèle s'affiche sans.
    assert_eq!(report.ignored_extensions, vec!["KHR_materials_variants"]);
    assert_eq!(asset.vertices.len(), 3);
}

#[test]
fn t228_une_extension_supportee_ne_produit_aucun_avertissement() {
    let source = triangle(
        "",
        "\"extensionsUsed\": [\"KHR_texture_transform\"],
  \"extensionsRequired\": [\"KHR_texture_transform\"],",
    );
    let (_, report) = import_gltf(source.as_bytes(), &LIMITS, |_| None).expect("import refusé");
    assert!(report.ignored_extensions.is_empty());
    assert!(SUPPORTED_EXTENSIONS.contains(&"KHR_texture_transform"));
}

#[test]
fn t228_un_uri_de_tampon_sortant_est_refuse() {
    // R-531 : le tampon d'un asset ne vit pas trois répertoires plus haut.
    let complet = triangle("", "");
    let source = remplacer_uri(&complet, "../../../etc/passwd");

    let refus = import_gltf(source.as_bytes(), &LIMITS, |_| None).unwrap_err();
    assert_eq!(refus.code(), -3002);
}

#[test]
fn t228_un_tampon_externe_passe_par_le_resolveur() {
    let complet = triangle("", "");
    let charge = charge_utile(&complet);
    let source = remplacer_uri(&complet, "buffer.bin");

    let mut demandes = Vec::new();
    let (asset, _) = import_gltf(source.as_bytes(), &LIMITS, |uri| {
        demandes.push(uri.to_owned());
        Some(decode(&charge))
    })
    .expect("import refusé");

    assert_eq!(demandes, vec!["buffer.bin"]);
    assert_eq!(asset.vertices.len(), 3);
}

#[test]
fn t228_une_image_non_png_est_refusee() {
    // R-532 : seul le PNG est lu, et le type est vérifié avant que quoi que ce
    // soit ne touche l'image.
    let source = triangle("", "").replace(
        "\"materials\":",
        "\"images\": [{ \"uri\": \"carrosserie.jpg\", \"mimeType\": \"image/jpeg\" }],
  \"materials\":",
    );

    let refus = import_gltf(source.as_bytes(), &LIMITS, |_| None).unwrap_err();
    assert_eq!(refus.code(), -3004);
}

#[test]
fn t228_une_image_png_est_designee_sans_etre_decodee() {
    let source = triangle("", "").replace(
        "\"materials\":",
        "\"images\": [{ \"uri\": \"carrosserie.png\", \"mimeType\": \"image/png\" }],
  \"materials\":",
    );

    let (_, report) = import_gltf(source.as_bytes(), &LIMITS, |_| None).expect("import refusé");
    assert_eq!(report.images.len(), 1);
    assert_eq!(report.images[0].uri.as_deref(), Some("carrosserie.png"));
}

#[test]
fn t228_une_image_sortante_est_refusee() {
    let source = triangle("", "").replace(
        "\"materials\":",
        "\"images\": [{ \"uri\": \"../../secret.png\", \"mimeType\": \"image/png\" }],
  \"materials\":",
    );

    assert_eq!(
        import_gltf(source.as_bytes(), &LIMITS, |_| None)
            .unwrap_err()
            .code(),
        -3002
    );
}

#[test]
fn t228_les_annotations_axion_sont_lues() {
    let source = triangle(
        ", \"extras\": { \"axion\": { \"role\": \"collider\", \"part\": \"capot\" } }",
        "",
    );
    let (asset, report) = import_gltf(source.as_bytes(), &LIMITS, |_| None).expect("import refusé");

    // R-910 : un collider n'est jamais rendu.
    assert_eq!(
        asset.nodes[0].flags & node_flags::VISIBLE,
        0,
        "un collider ne doit pas être rendu"
    );
    assert!(report.warnings.is_empty());
}

#[test]
fn t228_un_role_inconnu_avertit_sans_faire_echouer_l_import() {
    let source = triangle(
        ", \"extras\": { \"axion\": { \"role\": \"teleporteur\" } }",
        "",
    );
    let (asset, report) = import_gltf(source.as_bytes(), &LIMITS, |_| None).expect("import refusé");

    // R-912 : avertissement et défaut, pas un refus.
    assert_eq!(report.warnings.len(), 1, "{:?}", report.warnings);
    assert!(report.warnings[0].contains("teleporteur"));
    assert_ne!(asset.nodes[0].flags & node_flags::VISIBLE, 0);
}

#[test]
fn t913_un_gltf_sans_annotation_produit_un_asset_complet() {
    // R-913 : les annotations contrôlent, elles n'activent pas.
    let source = triangle("", "");
    let (asset, _) = import_gltf(source.as_bytes(), &LIMITS, |_| None).expect("import refusé");

    assert_ne!(asset.nodes[0].flags & node_flags::VISIBLE, 0);
    assert_eq!(asset.nodes[0].lod_mask, 1, "le node doit exister au LOD 0");
    assert_eq!(asset.meshes.len(), 1);
}

#[test]
fn t221_une_source_trop_volumineuse_est_refusee() {
    let source = triangle("", "");
    let refus = import_gltf(source.as_bytes(), &ImportLimits::new(64), |_| None).unwrap_err();
    assert_eq!(refus.code(), -3005);
}

#[test]
fn t228_un_document_illisible_est_refuse_sans_paniquer() {
    for source in [&b"{"[..], b"pas du json", &[0xFF; 64]] {
        assert!(import_gltf(source, &LIMITS, |_| None).is_err());
    }
}

/// Remplace l'URI du tampon par un autre.
fn remplacer_uri(source: &str, uri: &str) -> String {
    let debut = source.find(DATA_PREFIX).expect("uri de données");
    let fin = debut + source[debut..].find('"').expect("fin de l'uri");
    format!("{}{uri}{}", &source[..debut], &source[fin..])
}

/// Rend la charge utile base64 du tampon.
fn charge_utile(source: &str) -> String {
    let debut = source.find(DATA_PREFIX).expect("uri de données") + DATA_PREFIX.len();
    let fin = debut + source[debut..].find('"').expect("fin de l'uri");
    source[debut..fin].to_owned()
}
