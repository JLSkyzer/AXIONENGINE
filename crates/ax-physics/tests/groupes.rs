//! Groupes de collision d'une assembly (§10.4, R-980) : la definition les déclare, la
//! frontière les lit dans `PHYS`, le pilote les applique au corps ; les tuiles du monde
//! sont membres du seul groupe `world`.

use ax_math::{Quat, Vec3};
use ax_model::dm::geometry::WorldTransform;
use ax_model::dm::physics::BodyDesc;
use ax_physics::{
    BodyCollider, BodyKind, CollisionGroups, ContactMaterial, Handle, ReservedGroup, Shape,
    SimDriver,
};

fn bit(group: ReservedGroup) -> u32 {
    1 << group.bit()
}

/// Un plancher d'un bloc d'épaisseur, de y = 0 à y = 1, sur la section (0, 0, 0).
fn plancher(driver: &mut SimDriver) {
    assert!(driver.set_world_tile(
        0,
        [0, 0, 0],
        &[[0.0, 0.0, 0.0, 16.0, 1.0, 16.0]],
        ContactMaterial::default()
    ));
}

/// Une bille dynamique de rayon 0,5 lâchée en (8, `y`, 8), sous ses groupes.
fn bille(driver: &mut SimDriver, index: u32, y: f64, groups: CollisionGroups) {
    let created = driver.create_assembly_with_desc(
        0,
        Handle::new(index, 1),
        WorldTransform {
            position: [8.0, y, 8.0],
            rotation: [0.0, 0.0, 0.0, 1.0],
        },
        BodyKind::Dynamic,
        &[BodyCollider {
            shape: Shape::Ball { radius: 0.5 },
            density: 1000.0,
            material: ContactMaterial::default(),
            translation: Vec3::ZERO,
            rotation: Quat::IDENTITY,
        }],
        &BodyDesc::undeclared(2),
        groups,
    );
    assert!(created.is_some(), "bille {index} créée");
}

/// Hauteur de chaque bille après deux secondes simulées, par index de handle.
fn hauteurs_apres_deux_secondes(driver: &mut SimDriver) -> Vec<(u32, f64)> {
    for _ in 0..120 {
        driver.advance_all(1.0 / 60.0);
    }
    let mut hauteurs: Vec<(u32, f64)> = driver
        .collect_states()
        .iter()
        .map(|state| (state.handle.index, state.position[1]))
        .collect();
    hauteurs.sort_by_key(|(index, _)| *index);
    hauteurs
}

#[test]
fn une_assembly_sans_groupe_declare_se_pose_sur_le_monde() {
    let mut driver = SimDriver::new();
    plancher(&mut driver);
    bille(&mut driver, 1, 3.0, CollisionGroups::ALL);
    let y = hauteurs_apres_deux_secondes(&mut driver)[0].1;
    assert!(
        (y - 1.5).abs() < 0.05,
        "posée sur le plancher, centre à 1,5 : y = {y}"
    );
}

#[test]
fn une_assembly_qui_ne_filtre_pas_le_monde_le_traverse() {
    // Le filtre retient tout sauf `world` : si la tuile était membre de tous les groupes,
    // comme avant R-980, elle arrêterait encore la bille.
    let mut driver = SimDriver::new();
    plancher(&mut driver);
    bille(
        &mut driver,
        1,
        3.0,
        CollisionGroups::from_masks(bit(ReservedGroup::Assembly), !bit(ReservedGroup::World)),
    );
    let y = hauteurs_apres_deux_secondes(&mut driver)[0].1;
    assert!(y < 0.0, "la bille traverse le plancher : y = {y}");
}

#[test]
fn deux_assemblies_qui_ne_se_filtrent_pas_se_traversent() {
    // La première ne heurte que le monde ; la seconde, membre du seul groupe `assembly`,
    // n'est donc pas retenue par elle et se pose au même endroit, à travers elle.
    let mut driver = SimDriver::new();
    plancher(&mut driver);
    bille(
        &mut driver,
        1,
        3.0,
        CollisionGroups::from_masks(bit(ReservedGroup::Assembly), bit(ReservedGroup::World)),
    );
    bille(
        &mut driver,
        2,
        5.0,
        CollisionGroups::from_masks(bit(ReservedGroup::Assembly), u32::MAX),
    );
    let hauteurs = hauteurs_apres_deux_secondes(&mut driver);
    let (y1, y2) = (hauteurs[0].1, hauteurs[1].1);
    assert!(
        (y1 - 1.5).abs() < 0.05,
        "la première est sur le plancher : y = {y1}"
    );
    assert!(
        (y2 - 1.5).abs() < 0.05,
        "la seconde aussi, à travers la première : y = {y2}"
    );
}

#[test]
fn deux_assemblies_du_meme_groupe_s_empilent() {
    // Témoin du précédent : avec le défaut d'une definition (membre d'`assembly`, heurte
    // tout), la seconde bille se pose sur la première.
    let mut driver = SimDriver::new();
    plancher(&mut driver);
    let defaut = CollisionGroups::from_masks(bit(ReservedGroup::Assembly), u32::MAX);
    bille(&mut driver, 1, 3.0, defaut);
    bille(&mut driver, 2, 5.0, defaut);
    let hauteurs = hauteurs_apres_deux_secondes(&mut driver);
    let (y1, y2) = (hauteurs[0].1, hauteurs[1].1);
    assert!(
        (y1 - 1.5).abs() < 0.05,
        "la première est sur le plancher : y = {y1}"
    );
    assert!(
        y2 > y1 + 0.9,
        "la seconde repose sur la première : {y1} puis {y2}"
    );
}

#[test]
fn la_tuile_du_monde_est_membre_du_seul_groupe_world() {
    assert_eq!(
        CollisionGroups::WORLD_TILE,
        CollisionGroups::from_masks(bit(ReservedGroup::World), u32::MAX)
    );
}
