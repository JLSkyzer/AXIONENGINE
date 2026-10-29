//! Squelettes et animations glTF (C-37, DM-10, ADR-155) : de la source aux sections `SKEL` et
//! `ANIM`.
//!
//! Les modèles sont écrits par code (`modeles_animes`), et leurs valeurs connues : chaque test
//! dit ce qu'il doit lire, à l'octet près.

mod modeles_animes;

use ax_asset::a3d::{
    decode_animations, decode_bones, decode_geometry, A3dFile, A3dLimits, SectionTag,
};
use ax_asset::compile::{compile, source_hash, CompileError, CompileOptions, COMPILER_VERSION};
use ax_asset::import::{import_gltf, ImportError, ImportLimits, ImportedAsset, SourceFormat};
use ax_asset::options::AssetOptions;
use ax_asset::validate::{validate, AssetView, Violation};
use ax_model::dm::animation::{animation_flags, channel, interp};
use ax_model::dm::geometry::mesh_flags;
use ax_model::dm::scene::{name_hash, node_flags, NONE_U16, NONE_U32};
use modeles_animes::{
    bras, bras_avec, deux_skins, liaison_inverse, pendule, rebond, Modele, DEMI_RACINE,
};

const LIMITS: ImportLimits = ImportLimits::new(1 << 20);

fn importe(source: &str) -> (ImportedAsset, Vec<String>) {
    let (asset, report) = import_gltf(source.as_bytes(), &LIMITS, |_| None).expect("import refusé");
    (asset, report.warnings)
}

fn refus(source: &str) -> String {
    match import_gltf(source.as_bytes(), &LIMITS, |_| None) {
        Err(ImportError::Malformed { detail, .. }) => detail,
        autre => panic!("refus attendu, obtenu {:?}", autre.map(|_| ())),
    }
}

fn compile_gltf(source: &str) -> Result<Vec<u8>, CompileError> {
    let options = CompileOptions {
        asset_id: 1,
        source_hash: source_hash(source.as_bytes()),
        limits: ImportLimits::new(1 << 24),
        dynamic_body: true,
        authored: AssetOptions::DEFAULT,
    };
    compile(source.as_bytes(), SourceFormat::Gltf, &options, |_| None)
        .map(|compiled| compiled.bytes)
}

#[test]
fn le_bras_donne_un_squelette_de_deux_os() {
    let (asset, avertissements) = importe(&bras());
    assert!(avertissements.is_empty(), "{avertissements:?}");

    // Nodes en largeur depuis les racines : bras, epaule, coude.
    assert_eq!(asset.bones.len(), 2);
    let (epaule, coude) = (asset.bones[0], asset.bones[1]);
    assert_eq!((epaule.node, coude.node), (1, 2));
    assert_eq!(epaule.name_hash, name_hash("epaule"));
    assert_eq!(coude.name_hash, name_hash("coude"));
    assert_eq!(
        epaule.parent, NONE_U16,
        "l'épaule est la racine du squelette"
    );
    assert_eq!(coude.parent, 0, "le coude pend à l'épaule");
    assert_eq!(epaule.inverse_bind, liaison_inverse(0.0));
    assert_eq!(coude.inverse_bind, liaison_inverse(1.0));
    // Chaque node d'os désigne son os ; les autres aucun.
    assert_eq!(
        asset.nodes.iter().map(|node| node.bone).collect::<Vec<_>>(),
        [NONE_U32, 0, 1]
    );

    assert_ne!(asset.meshes[0].flags & mesh_flags::SKINNED, 0);
    let os: Vec<[u8; 4]> = asset.vertices.iter().map(|vertex| vertex.bones).collect();
    assert_eq!(
        os,
        [
            [0, 0, 0, 0],
            [0, 0, 0, 0],
            [0, 1, 0, 0],
            [0, 1, 0, 0],
            [1, 0, 0, 0],
            [1, 0, 0, 0]
        ]
    );
    assert_eq!(
        asset.vertices[2]
            .weights
            .iter()
            .map(|w| u16::from(*w))
            .sum::<u16>(),
        255
    );
}

#[test]
fn un_parent_d_os_peut_suivre_son_enfant_dans_le_squelette() {
    // Le coude d'abord : l'ordre des os est celui du skin, pas celui de la hiérarchie, et le
    // parent d'un os peut avoir un index plus grand que lui. Les indices des sommets suivent.
    let (asset, _) = importe(&bras_avec(
        [2, 1],
        [liaison_inverse(1.0), liaison_inverse(0.0)],
    ));
    assert_eq!((asset.bones[0].node, asset.bones[1].node), (2, 1));
    assert_eq!(asset.bones[0].parent, 1);
    assert_eq!(asset.bones[1].parent, NONE_U16);
    assert_eq!(asset.vertices[0].bones, [1, 0, 0, 0]);
    assert_eq!(asset.vertices[2].bones, [1, 0, 0, 0]);
    assert_eq!(asset.vertices[4].bones, [0, 0, 0, 0]);
}

#[test]
fn le_clip_plier_garde_ses_temps_et_ses_valeurs() {
    let (asset, _) = importe(&bras());
    let table = &asset.animations;
    assert_eq!(table.names, ["plier"]);
    assert_eq!(table.animations.len(), 1);
    let plier = table.animations[0];
    assert_eq!(plier.name_hash, name_hash("plier"));
    assert_eq!(plier.duration, 1.0);
    assert_eq!((plier.track_offset, plier.track_count), (0, 1));
    assert_eq!(plier.flags, 0, "sans annotation, un clip ne boucle pas");

    let piste = table.tracks[0];
    assert_eq!(piste.target_node, 2);
    assert_eq!(piste.channel, channel::ROTATION);
    assert_eq!(piste.interp, interp::LINEAR);
    assert_eq!(piste.key_count, 2);
    assert_eq!(
        table.keys_of(&piste).expect("clés"),
        [
            0.0,
            0.0,
            0.0,
            0.0,
            1.0,
            1.0,
            0.0,
            0.0,
            DEMI_RACINE,
            DEMI_RACINE
        ]
    );
    assert_ne!(
        asset.nodes[2].flags & node_flags::ANIMATED,
        0,
        "le coude est animé"
    );
    assert_eq!(
        asset.nodes[1].flags & node_flags::ANIMATED,
        0,
        "l'épaule ne l'est pas"
    );
    assert!(asset
        .names
        .iter()
        .any(|(categorie, nom)| *categorie == "animation" && nom == "plier"));
}

#[test]
fn l_annotation_axion_loop_fait_boucler_le_clip() {
    let source = bras().replace(
        "\"name\": \"plier\",",
        "\"name\": \"plier\", \"extras\": { \"axion\": { \"loop\": true } },",
    );
    let (asset, _) = importe(&source);
    assert_eq!(asset.animations.animations[0].flags, animation_flags::LOOP);
}

#[test]
fn le_pendule_porte_un_palier_et_une_rotation_lineaire() {
    let (asset, _) = importe(&pendule());
    let table = &asset.animations;
    assert_eq!(table.animations[0].duration, 1.0, "la plus longue piste");
    assert_eq!(table.animations[0].track_count, 2);
    let (pas, rotation) = (table.tracks[0], table.tracks[1]);
    assert_eq!(
        (pas.channel, pas.interp),
        (channel::TRANSLATION, interp::STEP)
    );
    assert_eq!(
        table.keys_of(&pas).expect("clés"),
        [0.0, 0.0, 0.0, 0.0, 0.5, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0]
    );
    // Sans `interpolation`, glTF dit linéaire.
    assert_eq!(
        (rotation.channel, rotation.interp),
        (channel::ROTATION, interp::LINEAR)
    );
    assert_eq!(rotation.key_offset, 12);
    assert!(
        asset.bones.is_empty(),
        "un pendule de node n'a pas de squelette"
    );
}

#[test]
fn le_rebond_garde_ses_tangentes_dans_l_ordre_de_gltf() {
    let (asset, _) = importe(&rebond());
    let piste = asset.animations.tracks[0];
    assert_eq!(piste.interp, interp::CUBIC_SPLINE);
    assert_eq!(piste.key_stride(), Some(10));
    assert_eq!(
        asset.animations.keys_of(&piste).expect("clés"),
        [
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 4.0,
            0.0, // temps, entrée, valeur, sortie
            1.0, 0.0, -4.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        ]
    );
}

#[test]
fn deux_skins_se_fusionnent_par_node_et_les_sommets_suivent() {
    let (asset, _) = importe(&deux_skins(liaison_inverse(0.0)));
    // Nodes : peau_haute, peau_basse, epaule, coude. L'épaule vient du premier skin, le coude du
    // second ; l'épaule partagée n'est qu'un os.
    assert_eq!(asset.bones.len(), 2);
    assert_eq!((asset.bones[0].node, asset.bones[1].node), (2, 3));
    assert_eq!(asset.bones[1].parent, 0);
    // Le joint local 0 désigne l'épaule dans le premier skin, le coude dans le second.
    assert!(asset.vertices[..3]
        .iter()
        .all(|vertex| vertex.bones[0] == 0));
    assert!(asset.vertices[3..]
        .iter()
        .all(|vertex| vertex.bones[0] == 1));
}

#[test]
fn un_os_lie_par_deux_matrices_est_refuse() {
    let detail = refus(&deux_skins(liaison_inverse(0.5)));
    assert!(
        detail.contains("epaule") && detail.contains("deux matrices"),
        "{detail}"
    );
}

#[test]
fn un_sommet_qui_designe_un_joint_hors_de_son_skin_est_refuse() {
    // Un skin réduit à l'épaule : les sommets qui suivent le coude désignent le joint 1, avec un
    // poids.
    let detail = refus(&bras().replace("\"joints\": [1, 2]", "\"joints\": [1]"));
    assert!(detail.contains("joint 1"), "{detail}");
}

#[test]
fn des_joints_sans_skin_sont_dessines_rigides_avec_un_avertissement() {
    let source = bras().replace(", \"skin\": 0", "");
    let (asset, avertissements) = importe(&source);
    assert_eq!(asset.meshes[0].flags & mesh_flags::SKINNED, 0);
    assert!(
        avertissements
            .iter()
            .any(|texte| texte.contains("JOINTS_0")),
        "{avertissements:?}"
    );
    // Le skin existe toujours : l'asset garde son squelette, la peau ne s'y lie plus.
    assert_eq!(asset.bones.len(), 2);
    compile_gltf(&source).expect("un mesh rigide dans un asset à squelette se compile");
}

#[test]
fn une_piste_de_morphing_est_ignoree_avec_un_avertissement() {
    let mut modele = Modele::default();
    let (positions, indices) = modele.triangle();
    let temps = modele.flottants(&[0.0, 1.0], "SCALAR");
    let poids = modele.flottants(&[0.0, 1.0], "SCALAR");
    let translations = modele.flottants(&[0.0, 0.0, 0.0, 0.0, 2.0, 0.0], "VEC3");
    let source = modele.document(&format!(
        "\"scenes\": [{{ \"nodes\": [0] }}],
  \"nodes\": [{{ \"name\": \"forme\", \"mesh\": 0 }}],
  \"meshes\": [{{ \"primitives\": [{{ \"attributes\": {{ \"POSITION\": {positions} }},
    \"indices\": {indices} }}] }}],
  \"animations\": [{{ \"name\": \"gonfler\",
    \"samplers\": [{{ \"input\": {temps}, \"output\": {poids} }},
                  {{ \"input\": {temps}, \"output\": {translations} }}],
    \"channels\": [{{ \"sampler\": 0, \"target\": {{ \"node\": 0, \"path\": \"weights\" }} }},
                   {{ \"sampler\": 1, \"target\": {{ \"node\": 0, \"path\": \"translation\" }} }}] }}]"
    ));
    let (asset, avertissements) = importe(&source);
    assert_eq!(asset.animations.animations[0].track_count, 1);
    assert_eq!(asset.animations.tracks[0].channel, channel::TRANSLATION);
    assert!(
        avertissements
            .iter()
            .any(|texte| texte.contains("morphing")),
        "{avertissements:?}"
    );
}

#[test]
fn le_bras_compile_porte_skel_et_anim() {
    let octets = compile_gltf(&bras()).expect("compilation");
    let fichier = A3dFile::open(&octets, A3dLimits::new(1 << 24)).expect("conteneur");
    assert_eq!(fichier.header().compiler_version, COMPILER_VERSION);
    assert_eq!(COMPILER_VERSION, 15);

    let (importe, _) = importe(&bras());
    let skel = fichier
        .section(SectionTag::SKEL)
        .expect("lecture")
        .expect("SKEL");
    assert_eq!(decode_bones(&skel).expect("os"), importe.bones);
    let anim = fichier
        .section(SectionTag::ANIM)
        .expect("lecture")
        .expect("ANIM");
    assert_eq!(
        decode_animations(&anim).expect("animations"),
        importe.animations
    );

    // Les indices d'os traversent l'optimiseur avec leurs sommets.
    let geom = fichier
        .section(SectionTag::GEOM)
        .expect("lecture")
        .expect("GEOM");
    let geometrie = decode_geometry(&geom).expect("géométrie");
    let mut os: Vec<[u8; 4]> = geometrie
        .vertices
        .iter()
        .map(|vertex| vertex.bones)
        .collect();
    os.sort_unstable();
    assert_eq!(
        os,
        [
            [0, 0, 0, 0],
            [0, 0, 0, 0],
            [0, 1, 0, 0],
            [0, 1, 0, 0],
            [1, 0, 0, 0],
            [1, 0, 0, 0]
        ]
    );
}

#[test]
fn un_asset_sans_animation_ni_skin_n_ecrit_ni_skel_ni_anim() {
    let source = rebond();
    let debut = source.find(",\n  \"animations\"").expect("animations");
    let fin = source.find(",\n  \"accessors\"").expect("accesseurs");
    let octets =
        compile_gltf(&format!("{}{}", &source[..debut], &source[fin..])).expect("compilation");
    let fichier = A3dFile::open(&octets, A3dLimits::new(1 << 24)).expect("conteneur");
    assert!(!fichier.has(SectionTag::SKEL));
    assert!(!fichier.has(SectionTag::ANIM));
}

#[test]
fn des_temps_de_cles_desordonnes_sont_refuses_par_la_validation() {
    // Les temps du palier, 0 / 0,5 / 1, deviennent 0 / 1 / 0,5.
    let (asset, _) = importe(&pendule());
    let mut desordonne = asset.animations.clone();
    assert_eq!((desordonne.keys[4], desordonne.keys[8]), (0.5, 1.0));
    desordonne.keys.swap(4, 8);
    let vue = |table: &ax_asset::a3d::AnimationTable| {
        validate(
            &AssetView {
                nodes: &asset.nodes,
                meshes: &asset.meshes,
                vertices: &asset.vertices,
                indices: &asset.indices,
                missing_normals: &asset.missing_normals,
                animations: &table.animations,
                tracks: &table.tracks,
                keys: &table.keys,
                ..AssetView::default()
            },
            &[],
        )
    };
    let sain = vue(&asset.animations);
    assert!(sain.is_valid(), "{:?}", sain.errors);
    let rapport = vue(&desordonne);
    assert!(
        rapport
            .errors
            .iter()
            .any(|erreur| erreur.violation == Violation::KeyTimesNotIncreasing { key: 2 }),
        "{:?}",
        rapport.errors
    );
}
