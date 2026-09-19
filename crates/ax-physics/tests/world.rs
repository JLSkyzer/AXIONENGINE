//! Tests de C-31 : fondation du monde physique et catalogue des formes.

use ax_math::{Quat, Vec3};
use ax_physics::{
    BodyError, BodyKind, CollisionGroups, CompoundPart, ConfigError, PhysicsConfig, PhysicsWorld,
    Shape,
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

/// Hauteur d'une bille après 3 s, lâchée à y = 3 au-dessus d'un sol statique du
/// groupe 0. Les groupes de la bille décident si elle rencontre le sol (§10.4).
fn hauteur_bille_apres_3s(ball_memberships: &[u32], ball_filter: &[u32]) -> f32 {
    let mut world = PhysicsWorld::new(config());
    let ground = world
        .add_body(
            BodyKind::Static,
            Vec3::new(0.0, 0.0, 0.0),
            Quat::IDENTITY,
            Shape::Cuboid {
                half_extents: [5.0, 0.5, 5.0],
            },
        )
        .expect("un sol est valide");
    world.set_collision_groups(ground, CollisionGroups::from_indices(&[0], &[0]));
    let ball = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(0.0, 3.0, 0.0),
            Quat::IDENTITY,
            Shape::Ball { radius: 0.5 },
        )
        .expect("une bille est valide");
    world.set_collision_groups(ball, CollisionGroups::from_indices(ball_memberships, ball_filter));
    for _ in 0..180 {
        world.advance(1.0 / 60.0);
    }
    world.pose(ball).unwrap().translation.y
}

#[test]
fn groupes_compatibles_la_bille_repose() {
    // R-980 : bille membre du groupe 0 et filtrant 0, comme le sol — elles se
    // rencontrent, la bille s'arrête au-dessus du sol (sommet en y = 0.5).
    let y = hauteur_bille_apres_3s(&[0], &[0]);
    assert!(y > 0.5, "la bille aurait dû reposer sur le sol (y={y})");
}

#[test]
fn groupes_incompatibles_la_bille_traverse() {
    // R-980 : bille membre du groupe 1 et filtrant 1 ; le sol est dans le groupe
    // 0. Le test AND échoue des deux côtés — aucune collision, la bille traverse.
    let y = hauteur_bille_apres_3s(&[1], &[1]);
    assert!(y < -1.0, "la bille aurait dû traverser le sol (y={y})");
}

#[test]
fn ccd_se_declare_sur_un_corps() {
    // La CCD se déclare et se retire par corps ; rapier en tire ensuite les
    // sous-pas d'interpolation. Le comportement anti-traversée est le contrat de
    // rapier (renforcé par ses contacts spéculatifs) ; ici on vérifie que la
    // déclaration prend bien, et qu'elle n'empêche pas la simulation normale.
    let mut world = PhysicsWorld::new(config());
    let ball = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(0.0, 10.0, 0.0),
            Quat::IDENTITY,
            Shape::Ball { radius: 0.2 },
        )
        .expect("une bille est valide");
    assert_eq!(world.is_ccd_enabled(ball), Some(false), "désactivée par défaut");
    world.set_ccd_enabled(ball, true);
    assert_eq!(world.is_ccd_enabled(ball), Some(true));
    // Un corps avec CCD tombe toujours normalement.
    let start = world.pose(ball).unwrap().translation.y;
    for _ in 0..30 {
        world.advance(1.0 / 60.0);
    }
    assert!(world.pose(ball).unwrap().translation.y < start, "la bille tombe malgré la CCD");
    world.set_ccd_enabled(ball, false);
    assert_eq!(world.is_ccd_enabled(ball), Some(false));
}

#[test]
fn sommeil_force_hors_du_rayon() {
    // R-612 : au-delà du rayon, sommeil forcé — jamais suppression.
    let mut world = PhysicsWorld::new(config());
    let proche = world
        .add_body(BodyKind::Dynamic, Vec3::new(2.0, 0.0, 0.0), Quat::IDENTITY, Shape::Ball { radius: 0.5 })
        .unwrap();
    let loin = world
        .add_body(BodyKind::Dynamic, Vec3::new(50.0, 0.0, 0.0), Quat::IDENTITY, Shape::Ball { radius: 0.5 })
        .unwrap();
    assert_eq!(world.enforce_simulation_radius(Vec3::ZERO, 10.0), 1);
    assert_eq!(world.is_sleeping(proche), Some(false));
    assert_eq!(world.is_sleeping(loin), Some(true));
    // Jamais supprimé : les deux corps existent toujours.
    assert_eq!(world.body_count(), 2);
}

#[test]
fn plafond_endort_les_plus_eloignes() {
    // R-613 : au-delà du plafond, les plus éloignés s'endorment, déterministe.
    let mut world = PhysicsWorld::new(config());
    let ids: Vec<_> = (1..=5)
        .map(|i| {
            world
                .add_body(
                    BodyKind::Dynamic,
                    Vec3::new(i as f32, 0.0, 0.0),
                    Quat::IDENTITY,
                    Shape::Ball { radius: 0.3 },
                )
                .unwrap()
        })
        .collect();
    let slept = world.enforce_active_body_cap(Vec3::ZERO, 2);
    assert_eq!(slept.len(), 3, "trois corps de trop doivent s'endormir");
    // Les deux plus proches restent éveillés, les trois plus éloignés dorment.
    assert_eq!(world.is_sleeping(ids[0]), Some(false));
    assert_eq!(world.is_sleeping(ids[1]), Some(false));
    assert_eq!(world.is_sleeping(ids[2]), Some(true));
    assert_eq!(world.is_sleeping(ids[3]), Some(true));
    assert_eq!(world.is_sleeping(ids[4]), Some(true));
}

#[test]
fn plafond_non_atteint_n_endort_personne() {
    let mut world = PhysicsWorld::new(config());
    for i in 0..3 {
        world
            .add_body(
                BodyKind::Dynamic,
                Vec3::new(i as f32, 0.0, 0.0),
                Quat::IDENTITY,
                Shape::Ball { radius: 0.3 },
            )
            .unwrap();
    }
    assert!(world.enforce_active_body_cap(Vec3::ZERO, 5).is_empty());
}

fn bille_haute(world: &mut PhysicsWorld) -> ax_physics::BodyId {
    world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(0.0, 10.0, 0.0),
            Quat::IDENTITY,
            Shape::Ball { radius: 0.5 },
        )
        .expect("une bille est valide")
}

#[test]
fn la_trainee_ralentit_la_chute() {
    // §10.6 : une forte traînée plafonne la vitesse de chute à une valeur
    // terminale bien inférieure à la chute libre.
    let mut world = PhysicsWorld::new(config());
    let libre = bille_haute(&mut world);
    let freinee = bille_haute(&mut world);
    world.set_drag(freinee, 2.0, 1.0); // Cd·A = 2 m²
    for _ in 0..120 {
        world.advance(1.0 / 60.0);
    }
    let y_libre = world.pose(libre).unwrap().translation.y;
    let y_freinee = world.pose(freinee).unwrap().translation.y;
    assert!(
        y_freinee > y_libre + 5.0,
        "la bille freinée doit être bien plus haut (libre={y_libre}, freinée={y_freinee})"
    );
    // Sa vitesse de chute est plafonnée (terminale), loin de la chute libre.
    let vy_freinee = world.velocity(freinee).unwrap().y;
    let vy_libre = world.velocity(libre).unwrap().y;
    assert!(vy_freinee > vy_libre + 5.0, "vitesse plafonnée (freinée={vy_freinee}, libre={vy_libre})");
}

#[test]
fn le_vent_pousse_via_la_trainee() {
    // §10.6 : le vent n'agit qu'à travers la traînée. Corps sans gravité, vent
    // de +x : la traînée le pousse vers +x.
    let mut world = PhysicsWorld::new(config());
    let voile = world
        .add_body(BodyKind::Dynamic, Vec3::ZERO, Quat::IDENTITY, Shape::Ball { radius: 0.5 })
        .unwrap();
    world.set_gravity_scale(voile, 0.0);
    world.set_drag(voile, 2.0, 2.0);
    world.set_wind(Vec3::new(5.0, 0.0, 0.0));
    for _ in 0..60 {
        world.advance(1.0 / 60.0);
    }
    let x = world.pose(voile).unwrap().translation.x;
    assert!(x > 0.5, "le vent aurait dû pousser le corps vers +x (x={x})");
}

#[test]
fn meme_sequence_meme_resultat_avec_forces() {
    // R-1020 : le profil de forces vit dans une HashMap consultée par clé pendant
    // l'itération déterministe de rapier ; deux exécutions doivent coïncider.
    let run = || {
        let mut world = PhysicsWorld::new(config());
        world.set_wind(Vec3::new(3.0, 0.0, -1.0));
        let ball = bille_haute(&mut world);
        world.set_drag(ball, 1.5, 0.8);
        for _ in 0..200 {
            world.advance(1.0 / 60.0);
        }
        world.pose(ball).unwrap()
    };
    assert_eq!(run(), run(), "la simulation avec forces reste reproductible");
}

#[test]
fn gravity_scale_zero_fait_flotter() {
    // §10.6 : gravity_scale nul annule la chute.
    let mut world = PhysicsWorld::new(config());
    let flotteur = world
        .add_body(BodyKind::Dynamic, Vec3::new(0.0, 5.0, 0.0), Quat::IDENTITY, Shape::Ball { radius: 0.5 })
        .unwrap();
    world.set_gravity_scale(flotteur, 0.0);
    for _ in 0..120 {
        world.advance(1.0 / 60.0);
    }
    let y = world.pose(flotteur).unwrap().translation.y;
    assert!((y - 5.0).abs() < 0.01, "sans gravité le corps ne tombe pas (y={y})");
}
