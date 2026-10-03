//! T-590 — R-893 : le format A3D ne change pas par accident.
//!
//! R-893 veut qu'une évolution du format s'accompagne d'un test de migration et
//! d'un fichier d'exemple de l'ancienne version. Le voici pour la version 1.1,
//! la première : le fichier est **versionné dans le dépôt**, et ce test le relit
//! puis vérifie que l'écrivain le reproduit encore octet pour octet.
//!
//! Un changement du format fait donc échouer ce test. Ce n'est pas un obstacle
//! à franchir : c'est la question « la version a-t-elle été incrémentée, la
//! migration écrite, et l'ancien fichier conservé ? » posée au bon moment.

use ax_asset::a3d::{
    decode_geometry, decode_materials, decode_nodes, decode_textures, A3dFile, A3dLimits,
    A3dWriter, DecodedMaterials, DecodedTextures, SectionTag, VERSION_MAJOR, VERSION_MINOR,
};
use ax_model::dm::geometry::UvRange;
use ax_model::dm::material::texture_source;
use ax_model::dm::scene::name_hash;

/// Fichier de référence, tel qu'il est versionné.
const FIXTURE: &[u8] = include_bytes!("fixtures/a3d/v1.1-minimal.a3d");

/// Triangle à un matériau, compilé par le compilateur 6, avant ADR-122 : sa
/// section `MATL` a la disposition provisoire — un compte, puis par matériau
/// quatre flottants de couleur et un chemin. Conservé pour R-893.
const MATL_PROVISOIRE: &[u8] = include_bytes!("fixtures/a3d/v1.1-matl-provisoire.a3d");

/// Un node portant un mesh glTF à deux primitives, compilé par le compilateur 7,
/// avant la plage d'UV et `mesh_count` (ADR-122 §4 et §5). La première primitive
/// a des UV répétées — u jusqu'à 3 —, écrêtées à 1 ; elle porte le matériau
/// « bois », texturé d'un PNG de 2×2 embarqué. La seconde, en x ∈ [2, 3], porte
/// « metal », et le node ne la désignait pas. Conservé pour R-893.
const COMPILATEUR_7: &[u8] = include_bytes!("fixtures/a3d/v1.1-compilateur-7.a3d");

const LIMITS: A3dLimits = A3dLimits::new(1 << 20);

/// Reconstruit le fichier de référence.
///
/// Toute valeur y est fixe : un horodatage ou un identifiant tiré au hasard
/// rendrait la comparaison impossible, et c'est cette comparaison qui fait tout
/// l'intérêt du fichier.
fn reference() -> Vec<u8> {
    let mut writer = A3dWriter::new(0x0102_0304_0506_0708, 0x1112_1314_1516_1718, 1);
    writer
        .section(SectionTag::NODE, b"racine\0chassis\0roue_avant_gauche\0")
        .expect("NODE");
    writer
        .compressed_section(SectionTag::GEOM, &vec![0x5A; 4096])
        .expect("GEOM");
    writer
        .section(SectionTag::META, b"axion:test/minimal")
        .expect("META");
    writer.finish().expect("écriture")
}

#[test]
fn t590_le_fichier_de_reference_se_relit() {
    let file = A3dFile::open(FIXTURE, LIMITS).expect("fichier de référence illisible");

    assert_eq!(file.header().version_major, VERSION_MAJOR);
    assert_eq!(file.header().version_minor, VERSION_MINOR);
    assert_eq!(file.header().asset_id, 0x0102_0304_0506_0708);
    assert_eq!(file.header().source_hash, 0x1112_1314_1516_1718);
    assert_eq!(file.header().compiler_version, 1);
    assert_eq!(file.header().section_count, 3);

    assert_eq!(
        file.section(SectionTag::NODE).expect("NODE"),
        Some(b"racine\0chassis\0roue_avant_gauche\0".to_vec())
    );
    assert_eq!(
        file.section(SectionTag::GEOM).expect("GEOM"),
        Some(vec![0x5A; 4096])
    );
    assert_eq!(
        file.section(SectionTag::META).expect("META"),
        Some(b"axion:test/minimal".to_vec())
    );
}

#[test]
fn t591_l_ecrivain_reproduit_le_fichier_de_reference() {
    let produit = reference();

    assert_eq!(
        produit.len(),
        FIXTURE.len(),
        "la taille du format a changé : {} octets contre {}",
        produit.len(),
        FIXTURE.len()
    );

    if produit != FIXTURE {
        let divergence = produit
            .iter()
            .zip(FIXTURE)
            .position(|(left, right)| left != right)
            .unwrap_or(0);
        panic!(
            "le format A3D a changé, première divergence à l'octet {divergence} \
             (attendu {:#04x}, produit {:#04x}). Si le changement est voulu : \
             incrémenter la version, écrire la migration, conserver l'ancien \
             fichier — R-893.",
            FIXTURE[divergence], produit[divergence]
        );
    }
}

#[test]
fn t590_un_matl_anterieur_a_adr_122_est_ignore_sans_refuser_l_asset() {
    let file = A3dFile::open(MATL_PROVISOIRE, LIMITS).expect("fichier illisible");
    assert_eq!(file.header().compiler_version, 6, "produit avant ADR-122");

    // La disposition provisoire n'est pas celle d'ADR-122 : son second mot est
    // le rouge de la couleur du matériau, 0,8. La section est ignorée, pas
    // refusée — le matériau par défaut s'appliquera.
    let matl = file
        .section(SectionTag::MATL)
        .expect("MATL lisible")
        .expect("MATL présente");
    assert_eq!(
        decode_materials(&matl),
        Ok(DecodedMaterials::UnknownLayout(0.8f32.to_bits()))
    );

    // Le reste de l'asset se lit toujours.
    let geom = file
        .section(SectionTag::GEOM)
        .expect("GEOM lisible")
        .expect("GEOM présente");
    assert_eq!(
        decode_geometry(&geom).expect("GEOM décodée").meshes.len(),
        1
    );
}

/// Les sections d'un fichier, décodées.
fn section(file: &A3dFile<'_>, tag: SectionTag) -> Vec<u8> {
    file.section(tag)
        .expect("section lisible")
        .expect("section présente")
}

#[test]
fn t590_un_fichier_du_compilateur_6_se_lit_un_mesh_par_node_uv_dans_l_unite() {
    // ADR-122 §8 : réserves nulles — `mesh_count` 0 vaut un mesh, `uv0_range`
    // 0 vaut [0, 1].
    let file = A3dFile::open(MATL_PROVISOIRE, LIMITS).expect("fichier illisible");
    let nodes = decode_nodes(&section(&file, SectionTag::NODE))
        .expect("NODE")
        .nodes;
    for node in &nodes {
        assert_eq!(node.mesh_count, 0, "réserve nulle avant ADR-122");
        assert_eq!(node.meshes().len(), 1);
    }
    let geom = decode_geometry(&section(&file, SectionTag::GEOM)).expect("GEOM décodée");
    for mesh in &geom.meshes {
        assert_eq!(UvRange::from_bits(mesh.uv0_range), Some(UvRange::UNIT));
    }
}

#[test]
fn t590_un_fichier_du_compilateur_7_se_lit_avec_ses_materiaux_et_ses_textures() {
    let file = A3dFile::open(COMPILATEUR_7, LIMITS).expect("fichier illisible");
    assert_eq!(file.header().compiler_version, 7, "produit avant T-a3");

    // Un node, deux meshes, mais le node ne désigne que le premier : c'est ce
    // que le compilateur 7 écrivait, et ce qu'un lecteur doit en tirer.
    let nodes = decode_nodes(&section(&file, SectionTag::NODE))
        .expect("NODE")
        .nodes;
    assert_eq!(nodes.len(), 1);
    assert_eq!(nodes[0].meshes(), 0..1);
    let geom = decode_geometry(&section(&file, SectionTag::GEOM)).expect("GEOM décodée");
    assert_eq!(geom.meshes.len(), 2);
    for mesh in &geom.meshes {
        assert_eq!(UvRange::from_bits(mesh.uv0_range), Some(UvRange::UNIT));
    }
    // Les UV répétées étaient écrêtées : u = 3 lu 1, la répétition perdue.
    assert!(
        geom.vertices.iter().any(|vertex| vertex.uv0[0] == u16::MAX),
        "u = 3 écrêté à 1"
    );

    // MATL à la disposition d'ADR-122, TEXR avec son PNG embarqué.
    let Ok(DecodedMaterials::Materials(materials)) =
        decode_materials(&section(&file, SectionTag::MATL))
    else {
        panic!("MATL illisible");
    };
    assert_eq!(
        materials.iter().map(|m| m.name_hash).collect::<Vec<_>>(),
        [name_hash("bois"), name_hash("metal")]
    );
    assert_eq!(materials[0].albedo_tex, 0);
    let Ok(DecodedTextures::Textures(textures)) =
        decode_textures(&section(&file, SectionTag::TEXR))
    else {
        panic!("TEXR illisible");
    };
    assert_eq!(textures.entries.len(), 1);
    assert_eq!(textures.entries[0].source, texture_source::EMBEDDED);
    assert_eq!(
        (textures.entries[0].width, textures.entries[0].height),
        (2, 2)
    );
}
