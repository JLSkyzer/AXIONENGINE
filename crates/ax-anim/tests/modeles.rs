//! Les clips glTF de référence (ADR-155 D7) : importés par `ax-asset`, comme le jeu les importe,
//! puis joués ici. Les valeurs attendues se calculent à la main.

#[path = "../../ax-asset/tests/modeles_animes/mod.rs"]
mod modeles_animes;

use ax_anim::{Animator, ClipLibrary};
use ax_asset::import::{import_gltf, ImportLimits, ImportedAsset};
use ax_model::dm::geometry::Transform;

fn joue(source: &str, clip: &str, instants: &[f64]) -> Vec<Vec<Transform>> {
    let (asset, _): (ImportedAsset, _) =
        import_gltf(source.as_bytes(), &ImportLimits::new(1 << 20), |_| None).expect("import");
    let table = asset.animations;
    let clips = ClipLibrary::new(
        table.animations,
        table.names,
        table.tracks,
        table.keys,
        asset.nodes.len(),
    )
    .expect("bibliothèque");
    let repos: Vec<Transform> = asset.nodes.iter().map(|node| node.local).collect();
    let mut animateur = Animator::new(asset.nodes.len());
    let index = clips.find(clip).expect("clip");
    animateur
        .play(&clips, index, 0, 1.0, 0.0, 0.0)
        .expect("lecture");
    instants
        .iter()
        .map(|instant| {
            let mut pose = repos.clone();
            animateur.evaluate(&clips, &repos, *instant, &mut pose);
            pose
        })
        .collect()
}

fn proche(a: &[f32], b: &[f32]) -> bool {
    a.iter().zip(b).all(|(x, y)| (x - y).abs() < 1e-6)
}

#[test]
fn le_bras_plie_son_coude_d_un_huitieme_de_tour_a_mi_course() {
    let poses = joue(&modeles_animes::bras(), "plier", &[0.0, 0.5, 1.0, 3.0]);
    let huitieme = core::f32::consts::FRAC_PI_8;
    // Nodes : bras, epaule, coude. Le coude tourne autour de z, sa translation reste celle du
    // repos ; l'épaule ne bouge pas.
    assert!(proche(&poses[0][2].rotation, &[0.0, 0.0, 0.0, 1.0]));
    assert!(proche(
        &poses[1][2].rotation,
        &[0.0, 0.0, huitieme.sin(), huitieme.cos()]
    ));
    assert!(proche(
        &poses[2][2].rotation,
        &[
            0.0,
            0.0,
            modeles_animes::DEMI_RACINE,
            modeles_animes::DEMI_RACINE
        ]
    ));
    assert_eq!(poses[1][2].translation, [0.0, 1.0, 0.0]);
    assert_eq!(poses[1][1], Transform::identity());
    // Sans `axion.loop`, le clip tient sa dernière pose.
    assert_eq!(poses[3][2], poses[2][2]);
}

#[test]
fn le_pendule_tient_son_palier_jusqu_a_la_cle_suivante() {
    let poses = joue(
        &modeles_animes::pendule(),
        "balancer",
        &[0.49, 0.5, 0.99, 1.0, 0.4],
    );
    let x: Vec<f32> = poses.iter().map(|pose| pose[0].translation[0]).collect();
    assert_eq!(x, [0.0, 1.0, 1.0, 0.0, 0.0]);
    // La rotation linéaire atteint son quart de tour à 0,8 s et le tient.
    let huitieme = core::f32::consts::FRAC_PI_8;
    let a_mi_course = joue(&modeles_animes::pendule(), "balancer", &[0.4])[0][0].rotation;
    assert!(proche(
        &a_mi_course,
        &[0.0, 0.0, huitieme.sin(), huitieme.cos()]
    ));
}

#[test]
fn le_rebond_monte_d_un_bloc_a_mi_course() {
    // Hermite : p0 = p1 = 0, tangentes 4 et -4 sur une seconde. À u = 1/2, h10 = 1/8 et
    // h11 = -1/8 : y = 4/8 + 4/8 = 1.
    let poses = joue(&modeles_animes::rebond(), "rebond", &[0.0, 0.5, 1.0]);
    let y: Vec<f32> = poses.iter().map(|pose| pose[0].translation[1]).collect();
    assert_eq!(y, [0.0, 1.0, 0.0]);
}
