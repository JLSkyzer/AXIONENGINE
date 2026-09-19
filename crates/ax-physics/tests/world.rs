//! Tests de la tranche 1 de C-31 : fondation du monde physique.

use ax_math::{Quat, Vec3};
use ax_physics::{BodyKind, ConfigError, PhysicsConfig, PhysicsWorld, Shape};

fn config() -> PhysicsConfig {
    PhysicsConfig::new(1.0 / 60.0, 4).expect("configuration par défaut valide")
}

#[test]
fn une_bille_tombe_sous_la_gravite() {
    let mut world = PhysicsWorld::new(config());
    let ball = world.add_body(
        BodyKind::Dynamic,
        Vec3::new(0.0, 10.0, 0.0),
        Quat::IDENTITY,
        Shape::Ball { radius: 0.5 },
    );
    let start = world.pose(ball).unwrap().translation.y;
    // Une seconde de simulation, un tick à la fois.
    for _ in 0..60 {
        world.advance(1.0 / 60.0);
    }
    let end = world.pose(ball).unwrap().translation.y;
    // Chute libre : ½·g·t² ≈ 4.9 m en 1 s. On exige une chute nette sans imposer
    // la valeur analytique, le solveur intégrant par pas.
    assert!(end < start - 4.0, "la bille aurait dû tomber (start={start}, end={end})");
}

#[test]
fn meme_sequence_meme_resultat() {
    // R-1020 : reproductibilité sur une même machine et un même binaire.
    let run = || {
        let mut world = PhysicsWorld::new(config());
        let _sol = world.add_body(
            BodyKind::Static,
            Vec3::new(0.0, 0.0, 0.0),
            Quat::IDENTITY,
            Shape::Cuboid {
                half_extents: [5.0, 0.5, 5.0],
            },
        );
        let ball = world.add_body(
            BodyKind::Dynamic,
            Vec3::new(0.1, 5.0, -0.2),
            Quat::IDENTITY,
            Shape::Ball { radius: 0.5 },
        );
        // Cinq secondes : la bille tombe, rebondit un peu, puis s'endort.
        for _ in 0..300 {
            world.advance(1.0 / 60.0);
        }
        world.pose(ball).unwrap()
    };
    assert_eq!(run(), run(), "deux exécutions identiques doivent donner la même pose");
}

#[test]
fn fixed_dt_hors_ensemble_refuse() {
    // R-990 : 1/50 n'appartient pas à {1/30, 1/60, 1/120}.
    assert_eq!(PhysicsConfig::new(1.0 / 50.0, 4), Err(ConfigError::FixedDtNotAllowed));
    assert!(PhysicsConfig::new(1.0 / 30.0, 4).is_ok());
    assert!(PhysicsConfig::new(1.0 / 120.0, 4).is_ok());
}

#[test]
fn max_substeps_nul_refuse() {
    assert_eq!(PhysicsConfig::new(1.0 / 60.0, 0), Err(ConfigError::ZeroMaxSubsteps));
}

#[test]
fn accumulateur_clampe_pas_de_spirale() {
    // R-990 : un gros retard ne déclenche jamais plus de max_substeps sous-pas.
    let mut world = PhysicsWorld::new(config());
    let substeps = world.advance(10.0); // dix secondes d'un coup
    assert_eq!(substeps, 4, "au plus max_substeps sous-pas malgré le retard");
}

#[test]
fn un_corps_retire_n_a_plus_de_pose() {
    let mut world = PhysicsWorld::new(config());
    let ball = world.add_body(
        BodyKind::Dynamic,
        Vec3::new(0.0, 1.0, 0.0),
        Quat::IDENTITY,
        Shape::Ball { radius: 0.25 },
    );
    assert_eq!(world.body_count(), 1);
    assert!(world.remove_body(ball));
    assert_eq!(world.body_count(), 0);
    assert!(world.pose(ball).is_none());
    // Un second retrait ne trouve plus rien.
    assert!(!world.remove_body(ball));
}
