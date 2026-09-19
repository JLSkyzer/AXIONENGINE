//! Tests de C-31 : fondation du monde physique et catalogue des formes.

use ax_math::{Quat, Vec3};
use ax_physics::{
    BodyError, BodyKind, CompoundPart, ConfigError, PhysicsConfig, PhysicsWorld, Shape,
};

fn config() -> PhysicsConfig {
    PhysicsConfig::new(1.0 / 60.0, 4).expect("configuration par défaut valide")
}

#[test]
fn une_bille_tombe_sous_la_gravite() {
    let mut world = PhysicsWorld::new(config());
    let ball = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(0.0, 10.0, 0.0),
            Quat::IDENTITY,
            Shape::Ball { radius: 0.5 },
        )
        .expect("une bille est une forme valide");
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
        world
            .add_body(
                BodyKind::Static,
                Vec3::new(0.0, 0.0, 0.0),
                Quat::IDENTITY,
                Shape::Cuboid {
                    half_extents: [5.0, 0.5, 5.0],
                },
            )
            .expect("un sol est valide");
        let ball = world
            .add_body(
                BodyKind::Dynamic,
                Vec3::new(0.1, 5.0, -0.2),
                Quat::IDENTITY,
                Shape::Ball { radius: 0.5 },
            )
            .expect("une bille est valide");
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
    let ball = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(0.0, 1.0, 0.0),
            Quat::IDENTITY,
            Shape::Ball { radius: 0.25 },
        )
        .expect("une bille est valide");
    assert_eq!(world.body_count(), 1);
    assert!(world.remove_body(ball));
    assert_eq!(world.body_count(), 0);
    assert!(world.pose(ball).is_none());
    // Un second retrait ne trouve plus rien.
    assert!(!world.remove_body(ball));
}

#[test]
fn toutes_les_primitives_s_ajoutent() {
    // §10.3 : le catalogue portable par un corps dynamique.
    let mut world = PhysicsWorld::new(config());
    let primitives = [
        Shape::Cuboid {
            half_extents: [0.5, 0.5, 0.5],
        },
        Shape::Ball { radius: 0.5 },
        Shape::Capsule {
            half_height: 0.5,
            radius: 0.25,
        },
        Shape::Cylinder {
            half_height: 0.5,
            radius: 0.25,
        },
        Shape::Cone {
            half_height: 0.5,
            radius: 0.25,
        },
    ];
    for shape in primitives {
        world
            .add_body(BodyKind::Dynamic, Vec3::new(0.0, 2.0, 0.0), Quat::IDENTITY, shape)
            .expect("chaque primitive est valide");
    }
    assert_eq!(world.body_count(), 5);
}

#[test]
fn enveloppe_convexe_valide() {
    // Un tétraèdre : quatre points non coplanaires.
    let mut world = PhysicsWorld::new(config());
    let shape = Shape::ConvexHull {
        points: vec![[0.0, 0.0, 0.0], [1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]],
    };
    assert!(world
        .add_body(BodyKind::Dynamic, Vec3::new(0.0, 2.0, 0.0), Quat::IDENTITY, shape)
        .is_ok());
}

#[test]
fn enveloppe_convexe_trop_peu_de_points() {
    let mut world = PhysicsWorld::new(config());
    let shape = Shape::ConvexHull {
        points: vec![[0.0, 0.0, 0.0], [1.0, 0.0, 0.0], [0.0, 1.0, 0.0]],
    };
    assert_eq!(
        world.add_body(BodyKind::Dynamic, Vec3::ZERO, Quat::IDENTITY, shape),
        Err(BodyError::ConvexHullTooFewPoints)
    );
}

#[test]
fn enveloppe_convexe_trop_de_points() {
    let mut world = PhysicsWorld::new(config());
    let shape = Shape::ConvexHull {
        points: vec![[0.0, 0.0, 0.0]; 257],
    };
    assert_eq!(
        world.add_body(BodyKind::Dynamic, Vec3::ZERO, Quat::IDENTITY, shape),
        Err(BodyError::ConvexHullTooManyPoints)
    );
}

#[test]
fn enveloppe_convexe_degeneree() {
    // Quatre points confondus : un seul point distinct, aucune enveloppe. rapier
    // tolère des points coplanaires (il en fait une enveloppe plate) ; il ne rend
    // `None` que pour une entrée réellement dégénérée, ce que ce cas garantit.
    let mut world = PhysicsWorld::new(config());
    let shape = Shape::ConvexHull {
        points: vec![[0.5, 0.5, 0.5]; 4],
    };
    assert_eq!(
        world.add_body(BodyKind::Dynamic, Vec3::ZERO, Quat::IDENTITY, shape),
        Err(BodyError::DegenerateConvexHull)
    );
}

#[test]
fn compose_valide() {
    let mut world = PhysicsWorld::new(config());
    let shape = Shape::Compound {
        parts: vec![
            CompoundPart {
                translation: Vec3::ZERO,
                rotation: Quat::IDENTITY,
                shape: Shape::Ball { radius: 0.5 },
            },
            CompoundPart {
                translation: Vec3::new(1.0, 0.0, 0.0),
                rotation: Quat::IDENTITY,
                shape: Shape::Cuboid {
                    half_extents: [0.5, 0.5, 0.5],
                },
            },
        ],
    };
    assert!(world
        .add_body(BodyKind::Dynamic, Vec3::new(0.0, 2.0, 0.0), Quat::IDENTITY, shape)
        .is_ok());
}

#[test]
fn compose_vide_refuse() {
    let mut world = PhysicsWorld::new(config());
    let shape = Shape::Compound { parts: vec![] };
    assert_eq!(
        world.add_body(BodyKind::Dynamic, Vec3::ZERO, Quat::IDENTITY, shape),
        Err(BodyError::EmptyCompound)
    );
}

#[test]
fn compose_trop_de_parts_refuse() {
    let mut world = PhysicsWorld::new(config());
    let part = CompoundPart {
        translation: Vec3::ZERO,
        rotation: Quat::IDENTITY,
        shape: Shape::Ball { radius: 0.1 },
    };
    let shape = Shape::Compound {
        parts: vec![part; 65],
    };
    assert_eq!(
        world.add_body(BodyKind::Dynamic, Vec3::ZERO, Quat::IDENTITY, shape),
        Err(BodyError::CompoundTooManyParts)
    );
}
