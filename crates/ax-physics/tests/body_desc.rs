//! T-312 (ADR-130) — la description DM-08 d'un corps : masse, centre de masse et
//! inertie déclarés priment sur les densités (R-622), et ses réglages sont
//! transmis. Chaque propriété se vérifie par son effet.

use ax_math::{Quat, Vec3};
use ax_physics::{
    BodyCollider, BodyDesc, BodyId, BodyKind, ContactMaterial, PhysicsConfig, PhysicsWorld, Shape,
};

const TICK: f32 = 1.0 / 60.0;

fn config() -> PhysicsConfig {
    PhysicsConfig::new(TICK, 4).expect("configuration par défaut valide")
}

/// Un cube de 2 m de côté à 1000 kg/m³ : 8000 kg par les densités.
fn cube(world: &mut PhysicsWorld, kind: BodyKind) -> BodyId {
    world
        .add_assembly(
            kind,
            Vec3::ZERO,
            Quat::IDENTITY,
            &[BodyCollider {
                shape: Shape::Cuboid {
                    half_extents: [1.0, 1.0, 1.0],
                },
                density: 1000.0,
                material: ContactMaterial::default(),
                translation: Vec3::ZERO,
                rotation: Quat::IDENTITY,
            }],
        )
        .expect("un cube est une forme valide")
}

/// Sans gravité ni amortissement : seule l'impulsion fait bouger le corps.
fn en_apesanteur() -> BodyDesc {
    BodyDesc {
        gravity_scale: 0.0,
        ..BodyDesc::undeclared(2)
    }
}

fn proche(a: f32, b: f32, tolerance: f32) -> bool {
    (a - b).abs() <= tolerance * b.abs().max(1.0)
}

#[test]
fn t312_sans_rien_de_declare_la_masse_vient_des_densites() {
    let mut world = PhysicsWorld::new(config());
    let body = cube(&mut world, BodyKind::Dynamic);
    world.apply_body_desc(body, &BodyDesc::undeclared(2));
    let mass = world.body_mass(body).expect("le corps existe");
    assert!(
        proche(mass, 8000.0, 1e-4),
        "densité × volume, 8000 kg attendus, {mass}"
    );
}

#[test]
fn t312_la_masse_declaree_prime_sur_les_densites() {
    let mut world = PhysicsWorld::new(config());
    let body = cube(&mut world, BodyKind::Dynamic);
    world.apply_body_desc(
        body,
        &BodyDesc {
            mass: 500.0,
            ..en_apesanteur()
        },
    );
    let mass = world.body_mass(body).expect("le corps existe");
    assert!(
        proche(mass, 500.0, 1e-4),
        "masse déclarée, 500 kg attendus, {mass}"
    );
    // Une impulsion J au centre de masse donne Δv = J / m déclarée.
    world.apply_impulse(body, Vec3::new(1000.0, 0.0, 0.0), Vec3::ZERO, false);
    let v = world.velocity(body).expect("le corps existe");
    assert!(
        proche(v.x, 2.0, 1e-4),
        "Δv = 1000 / 500 = 2 m/s attendus, {}",
        v.x
    );
}

#[test]
fn t312_le_centre_de_masse_declare_est_celui_du_corps() {
    let mut world = PhysicsWorld::new(config());
    let body = cube(&mut world, BodyKind::Dynamic);
    world.apply_body_desc(
        body,
        &BodyDesc {
            center_of_mass: [0.5, 0.0, 0.0],
            ..en_apesanteur()
        },
    );
    let com = world.body_center_of_mass(body).expect("le corps existe");
    assert!(
        (com - Vec3::new(0.5, 0.0, 0.0)).length() < 1e-5,
        "centre déclaré, {com}"
    );
    // Masse inchangée : seul le centre est déclaré.
    assert!(proche(world.body_mass(body).unwrap(), 8000.0, 1e-4));
    // Une poussée en y appliquée au centre géométrique, hors du centre déclaré,
    // fait tourner le corps autour de z.
    world.apply_impulse(body, Vec3::new(0.0, 1000.0, 0.0), Vec3::ZERO, true);
    let w = world.angular_velocity(body).expect("le corps existe");
    assert!(w.z.abs() > 1e-3, "couple attendu autour de z, ω = {w}");
}

#[test]
fn t312_au_centre_declare_une_poussee_ne_fait_pas_tourner() {
    let mut world = PhysicsWorld::new(config());
    let body = cube(&mut world, BodyKind::Dynamic);
    world.apply_body_desc(
        body,
        &BodyDesc {
            center_of_mass: [0.5, 0.0, 0.0],
            ..en_apesanteur()
        },
    );
    world.apply_impulse(
        body,
        Vec3::new(0.0, 1000.0, 0.0),
        Vec3::new(0.5, 0.0, 0.0),
        true,
    );
    let w = world.angular_velocity(body).expect("le corps existe");
    assert!(
        w.length() < 1e-4,
        "poussée au centre déclaré : aucune rotation, ω = {w}"
    );
}

#[test]
fn t312_une_masse_seule_met_l_inertie_a_l_echelle() {
    // Même impulsion hors du centre : moitié moins de masse, même répartition,
    // donc moitié moins d'inertie — la rotation double.
    let rotation = |desc: BodyDesc| {
        let mut world = PhysicsWorld::new(config());
        let body = cube(&mut world, BodyKind::Dynamic);
        world.apply_body_desc(body, &desc);
        world.apply_impulse(
            body,
            Vec3::new(0.0, 100.0, 0.0),
            Vec3::new(1.0, 0.0, 0.0),
            true,
        );
        world.angular_velocity(body).expect("le corps existe").z
    };
    let calculee = rotation(en_apesanteur());
    let moitie = rotation(BodyDesc {
        mass: 4000.0,
        ..en_apesanteur()
    });
    assert!(calculee.abs() > 0.0);
    assert!(
        proche(moitie, 2.0 * calculee, 1e-3),
        "rotation doublée attendue : {moitie} contre 2 × {calculee}"
    );
}

#[test]
fn t312_l_inertie_declaree_regit_la_rotation() {
    let mut world = PhysicsWorld::new(config());
    let body = cube(&mut world, BodyKind::Dynamic);
    world.apply_body_desc(
        body,
        &BodyDesc {
            mass: 10.0,
            center_of_mass: [0.0, 0.0, 0.0],
            inertia: [2.0, 2.0, 2.0],
            ..en_apesanteur()
        },
    );
    // Impulsion (0, 4, 0) en (1, 0, 0) : moment r × J = (0, 0, 4) ; ω = 4 / 2 = 2 rad/s.
    world.apply_impulse(
        body,
        Vec3::new(0.0, 4.0, 0.0),
        Vec3::new(1.0, 0.0, 0.0),
        true,
    );
    let w = world.angular_velocity(body).expect("le corps existe");
    assert!(
        proche(w.z, 2.0, 1e-4),
        "ω = 2 rad/s attendus autour de z, {w}"
    );
}

#[test]
fn t312_amortissements_ccd_gravite_et_plafonds_sont_transmis() {
    let mut world = PhysicsWorld::new(config());
    let body = cube(&mut world, BodyKind::Dynamic);
    world.apply_body_desc(
        body,
        &BodyDesc {
            ccd: 1,
            linear_damping: 0.5,
            angular_damping: 0.25,
            gravity_scale: 0.0,
            max_linear_vel: 5.0,
            ..BodyDesc::undeclared(2)
        },
    );
    assert_eq!(world.damping(body), Some((0.5, 0.25)));
    assert_eq!(world.is_ccd_enabled(body), Some(true));
    // Sans gravité, le corps ne tombe pas.
    let depart = world.pose(body).unwrap().translation.y;
    for _ in 0..60 {
        world.advance(TICK);
    }
    assert!((world.pose(body).unwrap().translation.y - depart).abs() < 1e-4);
    // Le plafond de vitesse déclaré borne une impulsion énorme (R-180).
    world.apply_impulse(body, Vec3::new(1.0e6, 0.0, 0.0), Vec3::ZERO, false);
    world.advance(TICK);
    let v = world.velocity(body).unwrap().length();
    assert!(v <= 5.0 + 1e-3, "vitesse bornée à 5 m/s attendue, {v}");
}

#[test]
fn t312_un_corps_qui_ne_peut_dormir_reste_eveille() {
    let endormi = |can_sleep: u8| {
        let mut world = PhysicsWorld::new(config());
        let body = cube(&mut world, BodyKind::Dynamic);
        world.apply_body_desc(
            body,
            &BodyDesc {
                can_sleep,
                ..en_apesanteur()
            },
        );
        // Immobile et sans gravité, cinq secondes : bien au-delà du délai de sommeil.
        for _ in 0..300 {
            world.advance(TICK);
        }
        world.is_sleeping(body).expect("le corps existe")
    };
    assert!(endormi(1), "un corps endormissable immobile s'endort");
    assert!(!endormi(0), "un corps qui ne peut dormir reste éveillé");
}

#[test]
fn t312_un_corps_statique_ignore_sa_description() {
    let mut world = PhysicsWorld::new(config());
    let body = cube(&mut world, BodyKind::Static);
    world.apply_body_desc(
        body,
        &BodyDesc {
            mass: 500.0,
            ..BodyDesc::undeclared(0)
        },
    );
    // rapier rapporte pour un corps fixe la masse de ses colliders ; la masse
    // déclarée, elle, n'y est pas appliquée.
    let mass = world.body_mass(body).expect("le corps existe");
    assert!(
        proche(mass, 8000.0, 1e-4),
        "la description d'un corps statique est ignorée, {mass}"
    );
}
