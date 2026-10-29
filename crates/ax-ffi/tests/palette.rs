//! T-540 (ADR-155 D7) : la palette d'une pose connue est celle du calcul de référence, os par os,
//! liaison inverse comprise. Et l'overlay `bones` (tranche 6b) relie chaque os à l'os parent que
//! `SKEL` lui donne.
//!
//! Le bras à deux os écrit par code est compilé par le vrai compilateur, ses sections `NODE`, `ANIM` et
//! `SKEL` relues ; son coude, plié par le clip `plier`, donne une palette que l'on compare à
//! `T(0, 1, 0) · Rz(θ) · T(0, -1, 0)`, calculée à part en `f64`.

#[path = "../../ax-asset/tests/modeles_animes/mod.rs"]
mod modeles_animes;

use ax_asset::a3d::{
    decode_animations, decode_bones, decode_nodes, A3dFile, A3dLimits, SectionTag,
};
use ax_asset::compile::{compile, CompileOptions};
use ax_asset::import::{ImportLimits, SourceFormat};
use ax_asset::options::AssetOptions;
use ax_math::Vec3;
use ax_model::dm::animation::{anim_action, AnimCommand, BoneDesc};
use ax_model::dm::geometry::Transform;
use ax_model::dm::scene::{NONE_U32, NO_PARENT};
use axion_native::animation::{AnimatedAsset, Instance};

/// Les sections d'un modèle compilé : ses nodes et leurs noms, ses clips, ses os.
struct Charge {
    nodes: ax_asset::a3d::NodeTable,
    table: ax_asset::a3d::AnimationTable,
    os: Vec<BoneDesc>,
}

fn charger(gltf: &str) -> Charge {
    let options = CompileOptions {
        asset_id: 0x0A51_0C0B_E000_0540,
        source_hash: 0,
        limits: ImportLimits::new(1 << 20),
        dynamic_body: true,
        authored: AssetOptions::DEFAULT,
    };
    let bytes = compile(gltf.as_bytes(), SourceFormat::Gltf, &options, |_| None)
        .expect("compilation")
        .bytes;
    let file = A3dFile::open(&bytes, A3dLimits::new(1 << 20)).expect("A3D");
    let section = |tag| file.section(tag).expect("lisible").expect("présente");
    Charge {
        nodes: decode_nodes(&section(SectionTag::NODE)).expect("NODE"),
        table: decode_animations(&section(SectionTag::ANIM)).expect("ANIM"),
        os: decode_bones(&section(SectionTag::SKEL)).expect("SKEL"),
    }
}

fn animation(charge: &Charge) -> AnimatedAsset {
    AnimatedAsset::new(
        &charge.nodes.nodes,
        charge.table.clone(),
        Vec::new(),
        &charge.os,
    )
    .expect("table acceptée")
    .expect("des clips")
}

/// La palette de l'instance au tick `tick`, le clip 0 joué depuis le tick 0 si `jouer`.
fn palette(asset: &AnimatedAsset, jouer: bool, tick: f64) -> Vec<f32> {
    let mut instance = Instance::new(asset);
    if jouer {
        let commande = AnimCommand {
            tick: 0.0,
            action: anim_action::PLAY,
            layer: 0,
            clip: 0,
            weight: 1.0,
            fade: 0.0,
            speed: 1.0,
            mask_count: 0,
            origin: 0.0,
        };
        instance.apply(asset, &commande, &[]).expect("commande");
    }
    instance.draw_matrices(asset, tick, &mut Vec::new());
    let mut out = Vec::new();
    instance.palette(asset, &mut out);
    out
}

/// La référence d'un os, en `mat4x3` : identité pour l'épaule, le coude tourné de `theta` autour
/// de son pivot à `(0, 1, 0)` — `T(0, 1, 0) · Rz(θ) · T(0, -1, 0)`, colonnes puis translation.
fn reference(nom: &str, theta: f64) -> [f64; 12] {
    match nom {
        "epaule" => [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0],
        "coude" => {
            let (s, c) = theta.sin_cos();
            [c, s, 0.0, -s, c, 0.0, 0.0, 0.0, 1.0, s, 1.0 - c, 0.0]
        }
        autre => panic!("os inattendu : {autre}"),
    }
}

fn comparer(charge: &Charge, palette: &[f32], theta: f64) {
    assert_eq!(palette.len(), charge.os.len() * 12, "une matrice par os");
    for (rang, os) in charge.os.iter().enumerate() {
        let nom = &charge.nodes.names[os.node as usize];
        let attendue = reference(nom, theta);
        for (k, valeur) in palette[rang * 12..rang * 12 + 12].iter().enumerate() {
            assert!(
                (f64::from(*valeur) - attendue[k]).abs() < 1e-5,
                "os {rang} ({nom}), composante {k} : {valeur} pour {}",
                attendue[k]
            );
        }
    }
}

#[test]
fn t540_la_palette_est_celle_du_calcul_de_reference() {
    let charge = charger(&modeles_animes::bras());
    let asset = animation(&charge);
    assert_eq!(asset.bone_count(), 2);
    // Liés à leur pose de repos, les os donnent l'identité tant que rien ne joue.
    comparer(&charge, &palette(&asset, false, 20.0), 0.0);
    // Le coude se plie linéairement d'un quart de tour en une seconde : 45° à 0,5 s, 90° à 1 s.
    comparer(
        &charge,
        &palette(&asset, true, 10.0),
        std::f64::consts::FRAC_PI_4,
    );
    comparer(
        &charge,
        &palette(&asset, true, 20.0),
        std::f64::consts::FRAC_PI_2,
    );
}

#[test]
fn t540_la_liaison_inverse_compte_et_l_ordre_des_os_est_celui_de_skel() {
    // Les os du skin listés à rebours de la hiérarchie : chaque matrice de la palette reste celle du
    // node de son os, liaison inverse comprise.
    let charge = charger(&modeles_animes::bras_avec(
        [2, 1],
        [
            modeles_animes::liaison_inverse(1.0),
            modeles_animes::liaison_inverse(0.0),
        ],
    ));
    let asset = animation(&charge);
    comparer(
        &charge,
        &palette(&asset, true, 20.0),
        std::f64::consts::FRAC_PI_2,
    );

    // Une liaison inverse fausse se voit : celle du coude prise pour l'identité, sa matrice garde le
    // pivot au lieu de le ramener.
    let mut faussee = charger(&modeles_animes::bras());
    for os in &mut faussee.os {
        os.inverse_bind = modeles_animes::liaison_inverse(0.0);
    }
    let asset = animation(&faussee);
    let fausse = palette(&asset, true, 20.0);
    let rang = faussee
        .os
        .iter()
        .position(|os| faussee.nodes.names[os.node as usize] == "coude")
        .expect("coude");
    assert!(
        (fausse[rang * 12 + 10] - 1.0).abs() < 1e-5,
        "translation y du coude sans liaison"
    );
}

#[test]
fn un_os_hors_de_l_asset_ou_non_affine_refuse_la_table() {
    let charge = charger(&modeles_animes::bras());
    let mut hors = charge.os.clone();
    hors[0].node = charge.nodes.nodes.len() as u32;
    assert!(
        AnimatedAsset::new(&charge.nodes.nodes, charge.table.clone(), Vec::new(), &hors).is_err()
    );

    // Chaque composante de la dernière ligne, colonne par colonne aux rangs 3, 7, 11 et 15 : `(0, 0, 0, 1)`
    // ou la table est refusée.
    for (rang, valeur) in [(3, 0.5), (7, 0.5), (11, 0.5), (15, 2.0)] {
        let mut projective = charge.os.clone();
        projective[1].inverse_bind[rang] = valeur;
        assert!(
            AnimatedAsset::new(
                &charge.nodes.nodes,
                charge.table.clone(),
                Vec::new(),
                &projective
            )
            .is_err(),
            "dernière ligne faussée au rang {rang}"
        );
    }

    let mut parent_hors = charge.os.clone();
    parent_hors[1].parent = charge.os.len() as u16;
    assert!(
        AnimatedAsset::new(
            &charge.nodes.nodes,
            charge.table.clone(),
            Vec::new(),
            &parent_hors
        )
        .is_err(),
        "un os parent hors du squelette"
    );

    let mut infinie = charge.os.clone();
    infinie[0].inverse_bind[12] = f32::INFINITY;
    assert!(AnimatedAsset::new(
        &charge.nodes.nodes,
        charge.table.clone(),
        Vec::new(),
        &infinie
    )
    .is_err());

    // Sans squelette, aucune palette.
    let sans = AnimatedAsset::new(&charge.nodes.nodes, charge.table.clone(), Vec::new(), &[])
        .expect("acceptée")
        .expect("des clips");
    assert_eq!(sans.bone_count(), 0);
    assert!(palette(&sans, true, 20.0).is_empty());
}

/// Overlay `bones` (tranche 6b) : un node qui n'est pas un os, glissé entre l'épaule et le coude à
/// (0, 0,5, 0), ne coupe pas l'os du coude de l'épaule, son os parent dans `SKEL`. Les index qui suivent
/// le node inséré avancent d'un, dans les parents, les pistes et les os.
#[test]
fn adr155_l_os_du_coude_part_de_l_epaule_par_dela_un_node_intermediaire() {
    let charge = charger(&modeles_animes::bras());
    let coude = charge.table.tracks[0].target_node as usize;
    let epaule = charge.nodes.nodes[coude].parent;
    let decale = |node: u32| {
        if node != NO_PARENT && node as usize >= coude {
            node + 1
        } else {
            node
        }
    };
    let mut nodes = charge.nodes.nodes.clone();
    let mut milieu = nodes[epaule as usize];
    milieu.name_hash ^= 1;
    milieu.parent = epaule;
    milieu.bone = NONE_U32;
    milieu.local = Transform {
        translation: [0.0, 0.5, 0.0],
        rotation: [0.0, 0.0, 0.0, 1.0],
        scale: [1.0; 3],
    };
    for node in &mut nodes {
        node.parent = decale(node.parent);
    }
    nodes.insert(coude, milieu);
    nodes[coude + 1].parent = coude as u32;
    let mut table = charge.table.clone();
    for track in &mut table.tracks {
        track.target_node = decale(track.target_node);
    }
    let mut os = charge.os.clone();
    for bone in &mut os {
        bone.node = decale(bone.node);
    }
    let asset = AnimatedAsset::new(&nodes, table, Vec::new(), &os)
        .expect("acceptée")
        .expect("des clips");
    let mut instance = Instance::new(&asset);
    instance.draw_matrices(&asset, 0.0, &mut Vec::new());
    let mut segments = Vec::new();
    instance.bone_segments(&asset, |point: Vec3| point.to_array(), &mut segments);
    assert_eq!(segments.len(), 1, "l'épaule est la racine du squelette");
    assert!(Vec3::from(segments[0].a).length() < 1e-5, "{segments:?}");
    assert!(
        (Vec3::from(segments[0].b) - Vec3::new(0.0, 1.5, 0.0)).length() < 1e-5,
        "{segments:?}"
    );
}
