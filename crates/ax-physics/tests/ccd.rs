//! CCD en anti-traversée (fiche 5.23, C-31) : « CCD sur déclaration, ou automatique si
//! `|v|·dt > 0.5 × plus petite dimension` ». Une bille de 20 cm lancée à 200 m/s parcourt
//! 3,3 m par sous-pas de 1/60 s : sans balayage, elle franchirait d'un bond une plaque de
//! 10 cm.

use ax_math::{Quat, Vec3};
use ax_physics::{
    BodyCollider, BodyId, BodyKind, ContactMaterial, PhysicsConfig, PhysicsWorld, Shape,
};

const DT: f32 = 1.0 / 60.0;

fn monde_sans_gravite() -> PhysicsWorld {
    let mut world = PhysicsWorld::new(PhysicsConfig::new(DT, 4).expect("configuration valide"));
    world.set_gravity(Vec3::ZERO);
    world
}

fn corps(world: &mut PhysicsWorld, kind: BodyKind, center: Vec3, shape: Shape) -> BodyId {
    world
        .add_assembly(
            kind,
            center,
            Quat::IDENTITY,
            &[BodyCollider {
                shape,
                density: 1000.0,
                material: ContactMaterial::default(),
                translation: Vec3::ZERO,
                rotation: Quat::IDENTITY,
            }],
        )
        .expect("forme valide")
}

/// Une plaque de 10 cm d'épaisseur en z = 0, de 4 × 4 m.
fn plaque(world: &mut PhysicsWorld, kind: BodyKind) -> BodyId {
    corps(
        world,
        kind,
        Vec3::ZERO,
        Shape::Cuboid {
            half_extents: [2.0, 2.0, 0.05],
        },
    )
}

/// Une bille de 10 cm de rayon en z = −3, lancée vers +z à `vitesse` m/s.
fn bille_lancee(world: &mut PhysicsWorld, vitesse: f32) -> BodyId {
    let bille = corps(
        world,
        BodyKind::Dynamic,
        Vec3::new(0.0, 0.0, -3.0),
        Shape::Ball { radius: 0.1 },
    );
    let masse = world.body_mass(bille).expect("le corps existe");
    world.apply_impulse(
        bille,
        Vec3::new(0.0, 0.0, masse * vitesse),
        Vec3::ZERO,
        false,
    );
    bille
}

/// Position z de la bille après une demi-seconde.
fn z_apres_une_demi_seconde(world: &mut PhysicsWorld, bille: BodyId) -> f32 {
    for _ in 0..10 {
        world.advance(1.0 / 20.0);
    }
    world.pose(bille).expect("le corps existe").translation.z
}

#[test]
fn une_bille_rapide_ne_traverse_pas_une_plaque_cinematique() {
    // Seule la CCD complète balaie les corps cinématiques : elle doit s'activer d'elle-même.
    let mut world = monde_sans_gravite();
    plaque(&mut world, BodyKind::Kinematic);
    let bille = bille_lancee(&mut world, 200.0);
    let z = z_apres_une_demi_seconde(&mut world, bille);
    assert!(z < 0.0, "la bille reste devant la plaque, z = {z}");
}

#[test]
fn une_bille_rapide_ne_traverse_pas_une_plaque_fixe() {
    let mut world = monde_sans_gravite();
    plaque(&mut world, BodyKind::Static);
    let bille = bille_lancee(&mut world, 200.0);
    let z = z_apres_une_demi_seconde(&mut world, bille);
    assert!(z < 0.0, "la bille reste devant la plaque, z = {z}");
}

#[test]
fn la_ccd_automatique_suit_le_seuil_de_la_fiche() {
    // Bille de 20 cm : seuil à |v|·dt > 0,1 m, soit 6 m/s à 1/60 s.
    let mut world = monde_sans_gravite();
    let lente = bille_lancee(&mut world, 5.0);
    world.advance(DT);
    assert_eq!(
        world.is_ccd_enabled(lente),
        Some(false),
        "5 m/s : sous le seuil"
    );

    let mut world = monde_sans_gravite();
    let rapide = bille_lancee(&mut world, 7.0);
    world.advance(DT);
    assert_eq!(
        world.is_ccd_enabled(rapide),
        Some(true),
        "7 m/s : au-delà du seuil"
    );
}

#[test]
fn une_ccd_declaree_reste_active_au_repos() {
    let mut world = monde_sans_gravite();
    let bille = bille_lancee(&mut world, 0.0);
    world.set_ccd_enabled(bille, true);
    world.advance(DT);
    assert_eq!(
        world.is_ccd_enabled(bille),
        Some(true),
        "déclarée, jamais basculée"
    );

    // Rendue à l'automatique, elle se coupe au repos.
    world.set_ccd_enabled(bille, false);
    world.advance(DT);
    assert_eq!(world.is_ccd_enabled(bille), Some(false));
}
