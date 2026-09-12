//! T-220, T-228 — import glTF sur des documents réels.
//!
//! Les documents sont construits ici, entièrement : un fichier de fixture
//! binaire cacherait ce qui est testé, alors que ce sont précisément les
//! détails du document qui font l'objet de chaque test.

use ax_asset::import::{import_gltf, ImportError, ImportLimits, SUPPORTED_EXTENSIONS};
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

/// Un triangle éclairé : positions, UV, normales et tangentes au choix, et un
/// matériau à normal map.
fn triangle_eclaire(normales: bool, tangentes: bool) -> String {
    let mut buffer = Vec::new();
    let mut views = Vec::new();
    let mut accessors = Vec::new();
    let mut attributes = Vec::new();

    let mut ajoute = |buffer: &mut Vec<u8>, valeurs: &[f32], nombre: usize, genre: &str| {
        let debut = buffer.len();
        for valeur in valeurs {
            buffer.extend_from_slice(&valeur.to_le_bytes());
        }
        views.push(format!(
            "{{ \"buffer\": 0, \"byteOffset\": {debut}, \"byteLength\": {} }}",
            buffer.len() - debut
        ));
        let bornes = if genre == "VEC3" && accessors.is_empty() {
            ", \"min\": [0.0, 0.0, 0.0], \"max\": [1.0, 1.0, 0.0]"
        } else {
            ""
        };
        accessors.push(format!(
            "{{ \"bufferView\": {}, \"componentType\": 5126, \"count\": {nombre}, \
             \"type\": \"{genre}\"{bornes} }}",
            views.len() - 1
        ));
        accessors.len() - 1
    };

    let position = ajoute(
        &mut buffer,
        &[0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0],
        3,
        "VEC3",
    );
    attributes.push(format!("\"POSITION\": {position}"));
    let uv = ajoute(&mut buffer, &[0.0, 0.0, 1.0, 0.0, 0.0, 1.0], 3, "VEC2");
    attributes.push(format!("\"TEXCOORD_0\": {uv}"));
    if normales {
        let normal = ajoute(
            &mut buffer,
            &[0.0, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0],
            3,
            "VEC3",
        );
        attributes.push(format!("\"NORMAL\": {normal}"));
    }
    if tangentes {
        let tangent = ajoute(
            &mut buffer,
            &[
                1.0, 0.0, 0.0, -1.0, 1.0, 0.0, 0.0, -1.0, 1.0, 0.0, 0.0, -1.0,
            ],
            3,
            "VEC4",
        );
        attributes.push(format!("\"TANGENT\": {tangent}"));
    }

    let debut = buffer.len();
    for index in [0u16, 1, 2] {
        buffer.extend_from_slice(&index.to_le_bytes());
    }
    views.push(format!(
        "{{ \"buffer\": 0, \"byteOffset\": {debut}, \"byteLength\": 6 }}"
    ));
    accessors.push(format!(
        "{{ \"bufferView\": {}, \"componentType\": 5123, \"count\": 3, \"type\": \"SCALAR\" }}",
        views.len() - 1
    ));
    let indices = accessors.len() - 1;

    let encoded = encode(&buffer);
    let len = buffer.len();
    format!(
        "{{
  \"asset\": {{ \"version\": \"2.0\" }},
  \"scene\": 0,
  \"scenes\": [{{ \"nodes\": [0] }}],
  \"nodes\": [{{ \"name\": \"triangle\", \"mesh\": 0 }}],
  \"meshes\": [{{
    \"name\": \"triangle\",
    \"primitives\": [{{ \"attributes\": {{ {} }}, \"indices\": {indices}, \"material\": 0 }}]
  }}],
  \"materials\": [{{ \"name\": \"relief\", \"normalTexture\": {{ \"index\": 0 }} }}],
  \"textures\": [{{ \"source\": 0 }}],
  \"images\": [{{ \"uri\": \"relief.png\" }}],
  \"accessors\": [{}],
  \"bufferViews\": [{}],
  \"buffers\": [{{ \"byteLength\": {len}, \"uri\": \"{DATA_PREFIX}{encoded}\" }}]
}}",
        attributes.join(", "),
        accessors.join(", "),
        views.join(", ")
    )
}

#[test]
fn t220_une_normal_map_est_reperee_sur_le_materiau() {
    let (asset, _) = import_gltf(triangle_eclaire(true, false).as_bytes(), &LIMITS, |_| None)
        .expect("import refusé");
    assert!(asset.materials[0].has_normal_map);

    // Le triangle de base n'a qu'une couleur.
    let (asset, _) =
        import_gltf(triangle("", "").as_bytes(), &LIMITS, |_| None).expect("import refusé");
    assert!(!asset.materials[0].has_normal_map);
}

#[test]
fn t220_les_tangentes_ecrites_par_la_source_sont_lues() {
    let (asset, _) = import_gltf(triangle_eclaire(true, true).as_bytes(), &LIMITS, |_| None)
        .expect("import refusé");

    // w = -1 : la bitangente est retournée, et le signe doit survivre.
    for vertex in &asset.vertices {
        assert_eq!(vertex.tangent, [127, 0, 0, -127]);
    }
    assert_eq!(asset.authored_tangents, [true; 3]);
}

#[test]
fn t220_sans_normale_les_tangentes_ecrites_sont_ignorees() {
    // glTF 2.0 : sans NORMAL, les tangentes fournies doivent être ignorées.
    let (asset, _) = import_gltf(triangle_eclaire(false, true).as_bytes(), &LIMITS, |_| None)
        .expect("import refusé");

    for vertex in &asset.vertices {
        assert_eq!(vertex.tangent, [0; 4]);
    }
    assert_eq!(asset.authored_tangents, [false; 3]);
    assert_eq!(asset.missing_normals, [true; 3]);
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
fn t680_un_accesseur_d_indices_signe_est_refuse_et_ne_panique_pas() {
    // Trouvé par fuzzing (R-903), à partir de la graine `triangle.gltf` du
    // corpus. `5122` est `SHORT`, signé ; glTF 2.0 n'admet pour des indices que
    // des entiers non signés, et `read_indices` du crate `gltf` atteint un
    // `unreachable!()` sur tout le reste. Sans la vérification préalable, cet
    // appel **panique**.
    //
    // Le cas n'est pas théorique : un exportateur qui confond `5122` et `5123`
    // produit un fichier que tous les autres champs rendent plausible.
    let source = triangle("", "").replace("\"componentType\": 5123", "\"componentType\": 5122");
    let refus = import_gltf(source.as_bytes(), &LIMITS, |_| None).unwrap_err();

    let message = format!("{refus:?}");
    assert!(
        message.contains("indices"),
        "le refus doit désigner l'accesseur fautif : {message}"
    );
}

#[test]
fn t680_les_trois_types_d_indices_admis_restent_acceptes() {
    // La réciproque : la vérification ne doit refuser que ce que glTF interdit.
    // `5121` est `UNSIGNED_BYTE`, `5123` `UNSIGNED_SHORT`, `5125`
    // `UNSIGNED_INT` — les trois seuls que la spécification admet.
    for composant in ["5121", "5123", "5125"] {
        let source = triangle("", "").replace(
            "\"componentType\": 5123",
            &format!("\"componentType\": {composant}"),
        );
        let resultat = import_gltf(source.as_bytes(), &LIMITS, |_| None);
        assert!(
            resultat.is_ok(),
            "componentType {composant} refusé : {:?}",
            resultat.err()
        );
    }
}

#[test]
fn t680_une_reference_hors_bornes_est_refusee_et_designee() {
    // Trouvé par fuzzing (R-903). `gltf-json` valide le document en indexant
    // `root.accessors[…]` avec un indice venu du document, **sans vérifier la
    // borne** : un `POSITION` désignant l'accesseur 99 quand deux sont déclarés
    // faisait paniquer la bibliothèque dans son propre validateur.
    //
    // AXION ne lui confie plus ce jugement. Le document est désérialisé sans
    // validation, et les références sont vérifiées ici — ce qui rend un refus
    // **et** un message qui désigne le champ fautif, là où une panique retenue
    // ne disait que « l'analyseur a paniqué ».
    let source = triangle("", "").replace("\"POSITION\": 0", "\"POSITION\": 99");
    let refus = import_gltf(source.as_bytes(), &LIMITS, |_| None).unwrap_err();

    assert!(
        matches!(refus, ImportError::Malformed { .. }),
        "refus attendu, et non une panique retenue : {refus:?}"
    );
    let message = format!("{refus}");
    assert!(
        message.contains("meshes[0].primitives[0].attributes") && message.contains("99"),
        "le message doit désigner le champ fautif : {message}"
    );
    assert_eq!(refus.code(), -3050, "code de l'ANNEXE A.1 inchangé");
}

#[test]
fn t680_une_enumeration_inconnue_est_refusee() {
    // Seconde famille de mines, trouvée par le fuzzer **après** que les indices
    // aient été couverts : `gltf-json` modélise par `Checked<T>` les champs dont
    // glTF fixe les valeurs, et tout `.unwrap()` sur un `Invalid` panique.
    // `Primitive::mode()` en est un, et l'import l'appelle en premier.
    for (quoi, avant, apres) in [
        (
            "mode de primitive",
            "\"indices\": 1",
            "\"mode\": 99, \"indices\": 1",
        ),
        (
            "componentType",
            "\"componentType\": 5126",
            "\"componentType\": 1234",
        ),
        (
            "type d'accesseur",
            "\"type\": \"VEC3\"",
            "\"type\": \"VEC9\"",
        ),
    ] {
        let source = triangle("", "").replace(avant, apres);
        let resultat = import_gltf(source.as_bytes(), &LIMITS, |_| None);
        assert!(
            resultat.is_err(),
            "une valeur inconnue de « {quoi} » a été acceptée"
        );
    }
}

#[test]
fn t680_une_image_sans_source_est_refusee() {
    // Quatrième famille : les champs que glTF rend obligatoires. Une image
    // porte soit un `uri`, soit une `bufferView` — `Image::source()` du crate
    // déballe les deux sans vérifier.
    let sans_rien = triangle("", "").replace(
        "\"materials\": [",
        "\"images\": [{ \"name\": \"vide\" }],\n  \"materials\": [",
    );
    assert!(
        import_gltf(sans_rien.as_bytes(), &LIMITS, |_| None).is_err(),
        "une image sans uri ni bufferView a été acceptée"
    );

    // Et la `bufferView` sans `mimeType` : rien ne dirait ce que les octets
    // contiennent.
    let sans_mime = triangle("", "").replace(
        "\"materials\": [",
        "\"images\": [{ \"bufferView\": 0 }],\n  \"materials\": [",
    );
    assert!(
        import_gltf(sans_mime.as_bytes(), &LIMITS, |_| None).is_err(),
        "une image en bufferView sans mimeType a été acceptée"
    );
}

#[test]
fn t680_les_contraintes_numeriques_du_format_sont_tenues() {
    // Cinquième famille. Le lecteur calcule `stride * (count - 1)` : un `count`
    // nul y soustrait sous zéro. En release l'entier boucle et l'accesseur rend
    // silencieusement du vide — c'est le comportement le plus dangereux des
    // deux, et celui qu'aucune panique ne signalerait.
    for (quoi, avant, apres) in [
        (
            "count nul",
            "\"count\": 3, \"type\": \"VEC3\"",
            "\"count\": 0, \"type\": \"VEC3\"",
        ),
        (
            "byteStride non multiple de quatre",
            "\"byteOffset\": 0, \"byteLength\": 36",
            "\"byteOffset\": 0, \"byteLength\": 36, \"byteStride\": 7",
        ),
        (
            "byteStride au-delà de 252",
            "\"byteOffset\": 0, \"byteLength\": 36",
            "\"byteOffset\": 0, \"byteLength\": 36, \"byteStride\": 256",
        ),
    ] {
        let source = triangle("", "").replace(avant, apres);
        assert!(
            import_gltf(source.as_bytes(), &LIMITS, |_| None).is_err(),
            "« {quoi} » a été accepté"
        );
    }

    // La réciproque : un pas valide reste accepté.
    let valide = triangle("", "").replace(
        "\"byteOffset\": 0, \"byteLength\": 36",
        "\"byteOffset\": 0, \"byteLength\": 36, \"byteStride\": 12",
    );
    assert!(
        import_gltf(valide.as_bytes(), &LIMITS, |_| None).is_ok(),
        "un byteStride de 12 a été refusé"
    );
}

#[test]
fn t680_un_attribut_personnalise_reste_accepte() {
    // La réciproque, et elle compte : glTF autorise les attributs préfixés d'un
    // tiret bas. Refuser toute énumération inconnue en bloc rejetterait des
    // fichiers parfaitement légaux si la bibliothèque les classait `Invalid`.
    // La fonctionnalité `extras` est activée pour cette raison : elle les
    // capture au lieu de les invalider.
    let source = triangle("", "").replace(
        "\"attributes\": { \"POSITION\": 0 }",
        "\"attributes\": { \"POSITION\": 0, \"_BATCHID\": 0 }",
    );
    let resultat = import_gltf(source.as_bytes(), &LIMITS, |_| None);
    assert!(
        resultat.is_ok(),
        "un attribut personnalisé a été refusé : {:?}",
        resultat.err()
    );
}

#[test]
fn t680_toutes_les_familles_de_reference_sont_verifiees() {
    // Le balayage est exhaustif sur le document, pas seulement sur ce que
    // l'import lit aujourd'hui : se limiter à ce qu'on déréférence obligerait à
    // revenir ici à chaque champ nouvellement lu, et c'est le genre de dette
    // qu'on oublie jusqu'à la panique suivante.
    for (quoi, avant, apres) in [
        ("accesseur d'indices", "\"indices\": 1", "\"indices\": 7"),
        ("matériau", "\"material\": 0", "\"material\": 7"),
        ("mesh d'un node", "\"mesh\": 0", "\"mesh\": 7"),
        ("bufferView", "\"bufferView\": 0", "\"bufferView\": 7"),
        ("buffer", "\"buffer\": 0", "\"buffer\": 7"),
        ("node d'une scène", "\"nodes\": [0]", "\"nodes\": [7]"),
    ] {
        let source = triangle("", "").replace(avant, apres);
        let resultat = import_gltf(source.as_bytes(), &LIMITS, |_| None);
        assert!(
            resultat.is_err(),
            "une référence « {quoi} » hors bornes a été acceptée"
        );
    }
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
            // Le triangle ne porte pas de normale : absente, pas nulle, et
            // C-23 la générera.
            missing_normals: &asset.missing_normals,
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
