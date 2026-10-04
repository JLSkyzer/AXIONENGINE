//! Tests de C-31 : fondation du monde physique et catalogue des formes.

use ax_math::{DVec3, FloatingOrigin, Quat, Vec3};
use ax_physics::{
    body_state_flags, event_kind, BodyBounds, BodyCollider, BodyError, BodyId, BodyKind,
    CollisionGroups, CompoundPart, ConfigError, ContactMaterial, FluidEnvironment, Handle,
    LiftSurface, PhysicsConfig, PhysicsWorld, Shape, SimMode, SpatialFilter, Stage,
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
    assert!(
        end < start - 4.0,
        "la bille aurait dû tomber (start={start}, end={end})"
    );
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
    assert_eq!(
        run(),
        run(),
        "deux exécutions identiques doivent donner la même pose"
    );
}

#[test]
fn fixed_dt_hors_ensemble_refuse() {
    // R-990 : 1/50 n'appartient pas à {1/30, 1/60, 1/120}.
    assert_eq!(
        PhysicsConfig::new(1.0 / 50.0, 4),
        Err(ConfigError::FixedDtNotAllowed)
    );
    assert!(PhysicsConfig::new(1.0 / 30.0, 4).is_ok());
    assert!(PhysicsConfig::new(1.0 / 120.0, 4).is_ok());
}

#[test]
fn max_substeps_nul_refuse() {
    assert_eq!(
        PhysicsConfig::new(1.0 / 60.0, 0),
        Err(ConfigError::ZeroMaxSubsteps)
    );
}

#[test]
fn accumulateur_clampe_pas_de_spirale() {
    // R-990 : un gros retard ne déclenche jamais plus de max_substeps sous-pas.
    let mut world = PhysicsWorld::new(config());
    let substeps = world.advance(10.0); // dix secondes d'un coup
    assert_eq!(
        substeps, 4,
        "au plus max_substeps sous-pas malgré le retard"
    );
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
            .add_body(
                BodyKind::Dynamic,
                Vec3::new(0.0, 2.0, 0.0),
                Quat::IDENTITY,
                shape,
            )
            .expect("chaque primitive est valide");
    }
    assert_eq!(world.body_count(), 5);
}

#[test]
fn enveloppe_convexe_valide() {
    // Un tétraèdre : quatre points non coplanaires.
    let mut world = PhysicsWorld::new(config());
    let shape = Shape::ConvexHull {
        points: vec![
            [0.0, 0.0, 0.0],
            [1.0, 0.0, 0.0],
            [0.0, 1.0, 0.0],
            [0.0, 0.0, 1.0],
        ],
    };
    assert!(world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(0.0, 2.0, 0.0),
            Quat::IDENTITY,
            shape
        )
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
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(0.0, 2.0, 0.0),
            Quat::IDENTITY,
            shape
        )
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
    world.set_collision_groups(
        ball,
        CollisionGroups::from_indices(ball_memberships, ball_filter),
    );
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
    assert_eq!(
        world.is_ccd_enabled(ball),
        Some(false),
        "désactivée par défaut"
    );
    world.set_ccd_enabled(ball, true);
    assert_eq!(world.is_ccd_enabled(ball), Some(true));
    // Un corps avec CCD tombe toujours normalement.
    let start = world.pose(ball).unwrap().translation.y;
    for _ in 0..30 {
        world.advance(1.0 / 60.0);
    }
    assert!(
        world.pose(ball).unwrap().translation.y < start,
        "la bille tombe malgré la CCD"
    );
    world.set_ccd_enabled(ball, false);
    assert_eq!(world.is_ccd_enabled(ball), Some(false));
}

// R-612 et R-613 (gestion d'activité autour des joueurs) : `tests/activity.rs`.

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
    assert!(
        vy_freinee > vy_libre + 5.0,
        "vitesse plafonnée (freinée={vy_freinee}, libre={vy_libre})"
    );
}

#[test]
fn le_vent_pousse_via_la_trainee() {
    // §10.6 : le vent n'agit qu'à travers la traînée. Corps sans gravité, vent
    // de +x : la traînée le pousse vers +x.
    let mut world = PhysicsWorld::new(config());
    let voile = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::ZERO,
            Quat::IDENTITY,
            Shape::Ball { radius: 0.5 },
        )
        .unwrap();
    world.set_gravity_scale(voile, 0.0);
    world.set_drag(voile, 2.0, 2.0);
    world.set_wind(Vec3::new(5.0, 0.0, 0.0));
    for _ in 0..60 {
        world.advance(1.0 / 60.0);
    }
    let x = world.pose(voile).unwrap().translation.x;
    assert!(
        x > 0.5,
        "le vent aurait dû pousser le corps vers +x (x={x})"
    );
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
    assert_eq!(
        run(),
        run(),
        "la simulation avec forces reste reproductible"
    );
}

/// Une aile plate (boîte fine) sans gravité, portant une surface de normale et
/// d'aire données, placée dans un vent horizontal de +x.
fn aile(world: &mut PhysicsWorld, normal: Vec3) -> ax_physics::BodyId {
    let wing = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(0.0, 5.0, 0.0),
            Quat::IDENTITY,
            Shape::Cuboid {
                half_extents: [1.0, 0.1, 1.0],
            },
        )
        .expect("une aile est valide");
    world.set_gravity_scale(wing, 0.0);
    world.set_lift_surfaces(
        wing,
        vec![LiftSurface {
            local_point: Vec3::ZERO,
            local_normal: normal,
            area: 1.0,
            lift_coefficient: 0.5,
        }],
    );
    world.set_wind(Vec3::new(5.0, 0.0, 0.0));
    wing
}

#[test]
fn une_aile_dans_le_vent_porte() {
    // §10.6, R-1000 : une aile de normale +y dans un vent horizontal porte vers
    // le haut — le « vol » sans système dédié.
    let mut world = PhysicsWorld::new(config());
    let wing = aile(&mut world, Vec3::Y);
    let start = world.pose(wing).unwrap().translation.y;
    for _ in 0..60 {
        world.advance(1.0 / 60.0);
    }
    let end = world.pose(wing).unwrap().translation.y;
    assert!(
        end > start + 0.5,
        "l'aile aurait dû s'élever (start={start}, end={end})"
    );
}

#[test]
fn une_aile_de_profil_ne_porte_pas() {
    // Normale +x, alignée avec l'axe de l'écoulement (le vent est en x) : la
    // composante de la normale orthogonale à l'écoulement est nulle, pas de
    // portance. Sans gravité ni traînée, l'aile ne bouge pas.
    let mut world = PhysicsWorld::new(config());
    let wing = aile(&mut world, Vec3::X);
    let start = world.pose(wing).unwrap().translation;
    for _ in 0..60 {
        world.advance(1.0 / 60.0);
    }
    let end = world.pose(wing).unwrap().translation;
    assert!(
        (end - start).length() < 0.01,
        "une aile de profil ne devrait pas bouger"
    );
}

/// Une caisse cubique immergée à y = −3, dans un fluide dont la densité est
/// donnée relativement à celle du corps (1 par défaut chez rapier).
fn caisse_immergee(world: &mut PhysicsWorld, densite_fluide: f32) -> ax_physics::BodyId {
    world.set_fluid(Some(FluidEnvironment {
        surface_y: 0.0,
        density: densite_fluide,
    }));
    world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(0.0, -3.0, 0.0),
            Quat::IDENTITY,
            Shape::Cuboid {
                half_extents: [0.5, 0.5, 0.5],
            },
        )
        .expect("une caisse est valide")
}

#[test]
fn flottabilite_fait_remonter() {
    // §10.6 : un fluide plus dense que le corps le fait remonter vers la surface.
    let mut world = PhysicsWorld::new(config());
    let caisse = caisse_immergee(&mut world, 2.0);
    world.set_drag(caisse, 1.0, 1.0); // amortit l'oscillation
    for _ in 0..180 {
        world.advance(1.0 / 60.0);
    }
    let y = world.pose(caisse).unwrap().translation.y;
    assert!(
        y > -1.5,
        "la caisse aurait dû remonter vers la surface (y={y})"
    );
}

#[test]
fn corps_plus_dense_que_le_fluide_coule() {
    // §10.6 : un fluide moins dense que le corps ne le soutient pas — il coule.
    let mut world = PhysicsWorld::new(config());
    let caisse = caisse_immergee(&mut world, 0.5);
    for _ in 0..120 {
        world.advance(1.0 / 60.0);
    }
    let y = world.pose(caisse).unwrap().translation.y;
    assert!(y < -3.5, "la caisse trop dense aurait dû couler (y={y})");
}

#[test]
fn gravity_scale_zero_fait_flotter() {
    // §10.6 : gravity_scale nul annule la chute.
    let mut world = PhysicsWorld::new(config());
    let flotteur = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(0.0, 5.0, 0.0),
            Quat::IDENTITY,
            Shape::Ball { radius: 0.5 },
        )
        .unwrap();
    world.set_gravity_scale(flotteur, 0.0);
    for _ in 0..120 {
        world.advance(1.0 / 60.0);
    }
    let y = world.pose(flotteur).unwrap().translation.y;
    assert!(
        (y - 5.0).abs() < 0.01,
        "sans gravité le corps ne tombe pas (y={y})"
    );
}

/// Un sol statique et une bille posée dessus, au repos : elle finit par dormir.
fn bille_au_repos(world: &mut PhysicsWorld) -> BodyId {
    world
        .add_body(
            BodyKind::Static,
            Vec3::new(0.0, -0.5, 0.0),
            Quat::IDENTITY,
            Shape::Cuboid {
                half_extents: [5.0, 0.5, 5.0],
            },
        )
        .expect("un sol est valide");
    world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(0.0, 0.5, 0.0),
            Quat::IDENTITY,
            Shape::Ball { radius: 0.5 },
        )
        .expect("une bille est valide")
}

#[test]
fn un_corps_qui_s_endort_emet_sleep() {
    // §10.7 : l'endormissement d'un corps produit un événement SLEEP portant son
    // identité (assembly, node, matériau).
    let mut world = PhysicsWorld::new(config());
    let ball = bille_au_repos(&mut world);
    world.set_body_identity(ball, Handle::new(7, 1), 3, 42);
    for _ in 0..180 {
        world.advance(1.0 / 60.0);
    }
    assert_eq!(
        world.is_sleeping(ball),
        Some(true),
        "la bille devrait dormir"
    );
    let events = world.drain_events();
    let sleep = events
        .iter()
        .find(|event| event.kind == event_kind::SLEEP)
        .expect("un événement SLEEP");
    assert_eq!(sleep.assembly_a, Handle::new(7, 1));
    assert_eq!(sleep.node_a, 3);
    assert_eq!(sleep.material_a, 42);
    assert_eq!(sleep.assembly_b, Handle::ABSENT);
    // Vidé : un second drain ne rend rien.
    assert!(world.drain_events().is_empty());
}

#[test]
fn sans_identite_l_evenement_est_absent() {
    // §10.7 : un corps sans identité déclarée émet à Handle::ABSENT.
    let mut world = PhysicsWorld::new(config());
    let _ball = bille_au_repos(&mut world);
    for _ in 0..180 {
        world.advance(1.0 / 60.0);
    }
    let events = world.drain_events();
    let sleep = events
        .iter()
        .find(|event| event.kind == event_kind::SLEEP)
        .expect("un événement SLEEP");
    assert_eq!(sleep.assembly_a, Handle::ABSENT);
}

#[test]
fn le_plafond_compte_les_pertes() {
    // R-1011 : au-delà du plafond, les événements sont écartés mais comptés —
    // jamais de perte silencieuse.
    let mut world = PhysicsWorld::new(config());
    world.set_max_events_per_tick(1);
    world
        .add_body(
            BodyKind::Static,
            Vec3::new(0.0, -0.5, 0.0),
            Quat::IDENTITY,
            Shape::Cuboid {
                half_extents: [10.0, 0.5, 10.0],
            },
        )
        .expect("un sol est valide");
    for i in 0..5 {
        world
            .add_body(
                BodyKind::Dynamic,
                Vec3::new(i as f32 * 2.0, 0.5, 0.0),
                Quat::IDENTITY,
                Shape::Ball { radius: 0.5 },
            )
            .expect("une bille est valide");
    }
    for _ in 0..180 {
        world.advance(1.0 / 60.0);
    }
    assert!(
        world.dropped_event_count() > 0,
        "les SLEEP au-delà du plafond doivent être comptés"
    );
    assert!(
        world.drain_events().len() <= 1,
        "le lot ne dépasse pas le plafond"
    );
}

/// Un sol statique (sommet en y = 0) et une bille lâchée de y = 2, avec leurs
/// identités. Renvoie (monde, bille, sol) après câblage, avant simulation.
fn monde_impact() -> (PhysicsWorld, BodyId, BodyId) {
    let mut world = PhysicsWorld::new(config());
    let ground = world
        .add_body(
            BodyKind::Static,
            Vec3::new(0.0, -0.5, 0.0),
            Quat::IDENTITY,
            Shape::Cuboid {
                half_extents: [5.0, 0.5, 5.0],
            },
        )
        .expect("un sol est valide");
    world.set_body_identity(ground, Handle::new(1, 1), 0, 3);
    let ball = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(0.0, 2.0, 0.0),
            Quat::IDENTITY,
            Shape::Ball { radius: 0.5 },
        )
        .expect("une bille est valide");
    world.set_body_identity(ball, Handle::new(5, 1), 2, 9);
    (world, ball, ground)
}

#[test]
fn un_contact_emet_contact_start_complet() {
    // §10.7, R-615 : l'impact produit un CONTACT_START peuplé.
    let (mut world, _ball, _ground) = monde_impact();
    let mut start = None;
    for _ in 0..90 {
        world.advance(1.0 / 60.0);
        if let Some(event) = world
            .drain_events()
            .into_iter()
            .find(|event| event.kind == event_kind::CONTACT_START)
        {
            start = Some(event);
            break;
        }
    }
    let event = start.expect("un CONTACT_START à l'impact");

    // Normale quasi verticale, contact près du sol.
    assert!(
        event.normal[1].abs() > 0.9,
        "normale verticale attendue : {:?}",
        event.normal
    );
    assert!(
        event.point[1].abs() < 0.2,
        "contact près du sol : {:?}",
        event.point
    );
    assert!(
        event.impulse > 0.0,
        "impulsion positive attendue : {}",
        event.impulse
    );

    // Masse effective : au contact bas d'une sphère, r×n = 0, donc la masse
    // effective vaut la masse de la bille (rayon 0.5, densité 1) ≈ 0.524 kg. Le
    // sol statique n'y contribue pas.
    assert!(
        (0.45..0.65).contains(&event.effective_mass),
        "masse effective ≈ masse de la bille : {}",
        event.effective_mass
    );

    // La paire porte les deux identités, dans un ordre quelconque.
    let paire = [
        (event.assembly_a, event.material_a),
        (event.assembly_b, event.material_b),
    ];
    assert!(
        paire.contains(&(Handle::new(5, 1), 9)),
        "identité de la bille : {paire:?}"
    );
    assert!(
        paire.contains(&(Handle::new(1, 1), 3)),
        "identité du sol : {paire:?}"
    );
}

#[test]
fn la_fin_d_un_contact_emet_contact_end() {
    // §10.7 : quand le contact cesse (ici le sol est retiré), un CONTACT_END est
    // émis, sans données de contact.
    let (mut world, _ball, ground) = monde_impact();
    // Laisse la bille se poser et vide les CONTACT_START.
    for _ in 0..120 {
        world.advance(1.0 / 60.0);
    }
    let _ = world.drain_events();
    // Le retrait du sol fait cesser le contact.
    assert!(world.remove_body(ground));
    world.advance(1.0 / 60.0);
    let events = world.drain_events();
    let end = events
        .iter()
        .find(|event| event.kind == event_kind::CONTACT_END)
        .expect("un CONTACT_END après le retrait du sol");
    assert_eq!(end.impulse, 0.0);
    assert_eq!(end.point, [0.0; 3]);
}

#[test]
fn un_impact_emet_contact_impulse() {
    // §10.7 : un contact persistant assez fort produit un CONTACT_IMPULSE
    // au-dessus du seuil (R-1012).
    let (mut world, _ball, _ground) = monde_impact();
    let mut found = None;
    for _ in 0..90 {
        world.advance(1.0 / 60.0);
        if let Some(event) = world
            .drain_events()
            .into_iter()
            .find(|event| event.kind == event_kind::CONTACT_IMPULSE)
        {
            found = Some(event);
            break;
        }
    }
    let event = found.expect("un CONTACT_IMPULSE pendant l'impact");
    assert!(
        event.impulse >= 0.5,
        "impulsion au-dessus du seuil : {}",
        event.impulse
    );
    assert!(event.effective_mass > 0.0, "masse effective peuplée");
}

#[test]
fn un_seuil_haut_filtre_les_impulsions() {
    // R-1012 : sous un seuil très élevé, aucun CONTACT_IMPULSE ne remonte.
    let (mut world, _ball, _ground) = monde_impact();
    world.set_contact_event_threshold(1.0e6);
    let mut impulses = 0;
    for _ in 0..120 {
        world.advance(1.0 / 60.0);
        impulses += world
            .drain_events()
            .into_iter()
            .filter(|event| event.kind == event_kind::CONTACT_IMPULSE)
            .count();
    }
    assert_eq!(impulses, 0, "aucun CONTACT_IMPULSE sous un seuil énorme");
}

#[test]
fn un_capteur_emet_enter_puis_exit() {
    // §10.7 : un corps traversant un capteur produit SENSOR_ENTER puis
    // SENSOR_EXIT ; le capteur ne l'arrête pas.
    let mut world = PhysicsWorld::new(config());
    let sensor = world
        .add_body(
            BodyKind::Static,
            Vec3::new(0.0, 0.0, 0.0),
            Quat::IDENTITY,
            Shape::Cuboid {
                half_extents: [2.0, 0.5, 2.0],
            },
        )
        .expect("un capteur est valide");
    world.set_sensor(sensor, true);
    world.set_body_identity(sensor, Handle::new(9, 1), 0, 0);
    let ball = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(0.0, 5.0, 0.0),
            Quat::IDENTITY,
            Shape::Ball { radius: 0.3 },
        )
        .expect("une bille est valide");
    world.set_body_identity(ball, Handle::new(4, 1), 0, 0);

    let mut enter = None;
    let mut exit = false;
    for _ in 0..180 {
        world.advance(1.0 / 60.0);
        for event in world.drain_events() {
            if event.kind == event_kind::SENSOR_ENTER {
                enter = Some(event);
            }
            if event.kind == event_kind::SENSOR_EXIT {
                exit = true;
            }
        }
    }
    let enter = enter.expect("un SENSOR_ENTER quand la bille entre");
    assert!(
        exit,
        "un SENSOR_EXIT quand la bille ressort (elle traverse)"
    );
    // Le capteur ne résout rien : pas de données de contact.
    assert_eq!(enter.impulse, 0.0);
    let paire = [enter.assembly_a, enter.assembly_b];
    assert!(paire.contains(&Handle::new(9, 1)), "identité du capteur");
    assert!(paire.contains(&Handle::new(4, 1)), "identité de la bille");
    // La bille a bien traversé, pas rebondi.
    assert!(
        world.pose(ball).unwrap().translation.y < -1.0,
        "la bille traverse le capteur"
    );
}

#[test]
fn body_states_compose_la_position_monde() {
    // DM-08 : la position monde (f64) est recomposée depuis la simulation f32 par
    // l'origine flottante de la dimension.
    let mut world = PhysicsWorld::new(config());
    let ball = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(0.0, 5.0, 0.0),
            Quat::IDENTITY,
            Shape::Ball { radius: 0.5 },
        )
        .unwrap();
    world.set_body_identity(ball, Handle::new(3, 1), 7, 2);
    let origin = FloatingOrigin::new(DVec3::new(1000.0, 0.0, 0.0));
    let states = world.body_states(&origin);
    assert_eq!(states.len(), 1);
    let state = states[0];
    assert_eq!(state.handle, Handle::new(3, 1));
    assert!(
        (state.position[0] - 1000.0).abs() < 1e-3,
        "x monde : {}",
        state.position[0]
    );
    assert!(
        (state.position[1] - 5.0).abs() < 1e-3,
        "y monde : {}",
        state.position[1]
    );
}

#[test]
fn body_states_filtre_statiques_et_anonymes() {
    // DM-08 : seuls les corps mobiles identifiés sont rapportés.
    let mut world = PhysicsWorld::new(config());
    let sol = world
        .add_body(
            BodyKind::Static,
            Vec3::new(0.0, -0.5, 0.0),
            Quat::IDENTITY,
            Shape::Cuboid {
                half_extents: [5.0, 0.5, 5.0],
            },
        )
        .unwrap();
    world.set_body_identity(sol, Handle::new(1, 1), 0, 0);
    // Dynamique sans identité : exclu.
    world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(3.0, 5.0, 0.0),
            Quat::IDENTITY,
            Shape::Ball { radius: 0.5 },
        )
        .unwrap();
    // Dynamique identifié : inclus.
    let ball = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(0.0, 0.5, 0.0),
            Quat::IDENTITY,
            Shape::Ball { radius: 0.5 },
        )
        .unwrap();
    world.set_body_identity(ball, Handle::new(9, 1), 0, 0);
    for _ in 0..180 {
        world.advance(1.0 / 60.0);
    }
    let states = world.body_states(&FloatingOrigin::new(DVec3::ZERO));
    assert_eq!(states.len(), 1, "seul le dynamique identifié");
    assert_eq!(states[0].handle, Handle::new(9, 1));
    assert!(
        states[0].flags & body_state_flags::SLEEPING != 0,
        "la bille posée finit par dormir"
    );
}

#[test]
fn body_states_marque_in_fluid() {
    // DM-08 : un corps immergé porte le drapeau IN_FLUID.
    let mut world = PhysicsWorld::new(config());
    world.set_fluid(Some(FluidEnvironment {
        surface_y: 10.0,
        density: 2.0,
    }));
    let ball = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(0.0, 5.0, 0.0),
            Quat::IDENTITY,
            Shape::Ball { radius: 0.5 },
        )
        .unwrap();
    world.set_body_identity(ball, Handle::new(4, 1), 0, 0);
    let states = world.body_states(&FloatingOrigin::new(DVec3::ZERO));
    assert_eq!(states.len(), 1);
    assert!(
        states[0].flags & body_state_flags::IN_FLUID != 0,
        "immergé sous la surface"
    );
}

#[test]
fn contacts_deterministes() {
    // R-1020 : le lot d'événements de contact est reproductible.
    let run = || {
        let (mut world, _b, _g) = monde_impact();
        let mut events = Vec::new();
        for _ in 0..90 {
            world.advance(1.0 / 60.0);
            events.extend(world.drain_events());
        }
        events
    };
    assert_eq!(run(), run());
}

#[test]
fn evenements_deterministes() {
    // R-1020 : le lot d'événements est reproductible d'une exécution à l'autre.
    let run = || {
        let mut world = PhysicsWorld::new(config());
        let ball = bille_au_repos(&mut world);
        world.set_body_identity(ball, Handle::new(1, 1), 0, 0);
        for _ in 0..180 {
            world.advance(1.0 / 60.0);
        }
        world.drain_events()
    };
    assert_eq!(run(), run());
}

// --- Garde-fous R-180 / R-181 (tranche 4c) ---------------------------------

#[test]
fn le_clamp_borne_la_vitesse_lineaire() {
    // R-180 : au-delà de la borne, la vitesse est ramenée à celle-ci, le drapeau
    // CLAMPED est posé et le fait est journalisé.
    let mut world = PhysicsWorld::new(config());
    let ball = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(0.0, 5.0, 0.0),
            Quat::IDENTITY,
            Shape::Ball { radius: 0.5 },
        )
        .unwrap();
    world.set_body_identity(ball, Handle::new(1, 1), 0, 0);
    world.set_velocity_limits(ball, 5.0, 100.0);
    // Une forte impulsion propulse la bille bien au-delà de 5 m/s.
    world.apply_impulse(ball, Vec3::new(1000.0, 0.0, 0.0), Vec3::ZERO, false);

    world.advance(1.0 / 60.0);

    let speed = world.velocity(ball).unwrap().length();
    assert!(
        (speed - 5.0).abs() < 1.0e-2,
        "vitesse ramenée à la borne, obtenu {speed}"
    );
    let states = world.body_states(&FloatingOrigin::new(DVec3::ZERO));
    assert_eq!(states.len(), 1);
    assert!(
        states[0].flags & body_state_flags::CLAMPED != 0,
        "drapeau CLAMPED posé"
    );
    assert_eq!(world.clamp_journal_count(), 1, "un clamp journalisé");
    assert_eq!(world.invalid_state_count(), 0, "aucun état invalide");
}

#[test]
fn le_clamp_borne_la_vitesse_angulaire() {
    // R-180 : la borne angulaire agit indépendamment de la linéaire.
    let mut world = PhysicsWorld::new(config());
    let ball = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(0.0, 5.0, 0.0),
            Quat::IDENTITY,
            Shape::Ball { radius: 0.5 },
        )
        .unwrap();
    world.set_velocity_limits(ball, 300.0, 2.0);
    // Impulsion appliquée hors du centre : elle crée une rotation rapide.
    world.apply_impulse(
        ball,
        Vec3::new(0.0, 0.0, 500.0),
        Vec3::new(0.4, 0.0, 0.0),
        true,
    );

    world.advance(1.0 / 60.0);

    let ang_speed = world.angular_velocity(ball).unwrap().length();
    assert!(
        ang_speed <= 2.0 + 1.0e-2,
        "vitesse angulaire bornée, obtenu {ang_speed}"
    );
    assert_eq!(world.clamp_journal_count(), 1);
}

#[test]
fn sans_depassement_aucun_clamp() {
    // Sous les bornes par défaut (300 m/s), une impulsion modérée ne clampe rien.
    let mut world = PhysicsWorld::new(config());
    let ball = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(0.0, 5.0, 0.0),
            Quat::IDENTITY,
            Shape::Ball { radius: 0.5 },
        )
        .unwrap();
    world.set_body_identity(ball, Handle::new(2, 1), 0, 0);
    world.apply_impulse(ball, Vec3::new(1.0, 0.0, 0.0), Vec3::ZERO, false);

    world.advance(1.0 / 60.0);

    let states = world.body_states(&FloatingOrigin::new(DVec3::ZERO));
    assert!(
        states[0].flags & body_state_flags::CLAMPED == 0,
        "pas de clamp"
    );
    assert_eq!(world.clamp_journal_count(), 0);
}

#[test]
fn un_etat_non_fini_est_restaure_et_endormi() {
    // R-181 / FM-20 / E-2030 : une impulsion NaN rend l'état non fini ; le corps
    // revient au dernier état valide (sa pose de départ) et est endormi.
    let mut world = PhysicsWorld::new(config());
    let spawn = Vec3::new(0.0, 5.0, 0.0);
    let ball = world
        .add_body(
            BodyKind::Dynamic,
            spawn,
            Quat::IDENTITY,
            Shape::Ball { radius: 0.5 },
        )
        .unwrap();
    world.set_body_identity(ball, Handle::new(3, 1), 0, 0);
    world.apply_impulse(ball, Vec3::NAN, Vec3::ZERO, false);

    world.advance(1.0 / 60.0);

    assert_eq!(world.invalid_state_count(), 1, "un état invalide restauré");
    let states = world.body_states(&FloatingOrigin::new(DVec3::ZERO));
    assert_eq!(states.len(), 1);
    let position = states[0].position;
    assert!(
        position.iter().all(|c| c.is_finite()),
        "position finie après restauration"
    );
    assert!(
        (position[1] - 5.0).abs() < 1.0e-3,
        "revenu à la pose de départ, y={}",
        position[1]
    );
    assert!(
        states[0].flags & body_state_flags::SLEEPING != 0,
        "endormi de force"
    );
}

#[test]
fn une_position_hors_du_monde_est_restauree() {
    // R-181 : une position finie mais hors des limites du monde déclenche une
    // restauration. Un corps dynamique ne peut y parvenir par la dynamique (sa
    // vitesse est bornée) ; on l'y place donc directement, cas qu'un appelant
    // fautif pourrait provoquer. Faute d'état valide antérieur, il est parqué à
    // l'origine, endormi.
    let mut world = PhysicsWorld::new(config());
    let ball = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(2.0e7, 5.0, 0.0),
            Quat::IDENTITY,
            Shape::Ball { radius: 0.5 },
        )
        .unwrap();
    world.set_body_identity(ball, Handle::new(5, 1), 0, 0);

    world.advance(1.0 / 60.0);

    assert_eq!(
        world.invalid_state_count(),
        1,
        "un état hors du monde restauré"
    );
    let states = world.body_states(&FloatingOrigin::new(DVec3::ZERO));
    let position = states[0].position;
    assert!(
        position.iter().all(|c| c.is_finite()),
        "position finie après restauration"
    );
    assert!(
        position[0].abs() < 1.0,
        "parqué à l'origine, x={}",
        position[0]
    );
    assert!(
        states[0].flags & body_state_flags::SLEEPING != 0,
        "endormi de force"
    );
}

#[test]
fn la_journalisation_du_clamp_est_debitee_par_minute() {
    // R-180 : un clamp est journalisé au plus une fois par corps et par minute.
    // On maintient la bille au-dessus de sa borne à chaque tick ; le compteur ne
    // bouge qu'une fois avant la minute, une seconde fois après l'avoir franchie.
    let mut world = PhysicsWorld::new(config());
    let ball = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(0.0, 5.0, 0.0),
            Quat::IDENTITY,
            Shape::Ball { radius: 0.5 },
        )
        .unwrap();
    world.set_velocity_limits(ball, 1.0, 100.0);

    // Dix ticks (bien en deçà d'une minute) : un seul clamp journalisé.
    for _ in 0..10 {
        world.apply_impulse(ball, Vec3::new(100.0, 0.0, 0.0), Vec3::ZERO, false);
        world.advance(1.0 / 60.0);
    }
    assert_eq!(
        world.clamp_journal_count(),
        1,
        "un seul clamp dans la première minute"
    );

    // Assez de ticks pour franchir 60 s simulées (3600 sous-pas) : un second.
    for _ in 0..3600 {
        world.apply_impulse(ball, Vec3::new(100.0, 0.0, 0.0), Vec3::ZERO, false);
        world.advance(1.0 / 60.0);
    }
    assert_eq!(
        world.clamp_journal_count(),
        2,
        "un second clamp après la minute"
    );
}

#[test]
fn les_garde_fous_sont_deterministes() {
    // R-1020 : clamp et restauration produisent le même état d'une exécution à
    // l'autre.
    let run = || {
        let mut world = PhysicsWorld::new(config());
        let a = world
            .add_body(
                BodyKind::Dynamic,
                Vec3::new(0.0, 5.0, 0.0),
                Quat::IDENTITY,
                Shape::Ball { radius: 0.5 },
            )
            .unwrap();
        world.set_body_identity(a, Handle::new(7, 1), 0, 0);
        world.set_velocity_limits(a, 5.0, 100.0);
        let b = world
            .add_body(
                BodyKind::Dynamic,
                Vec3::new(3.0, 5.0, 0.0),
                Quat::IDENTITY,
                Shape::Ball { radius: 0.5 },
            )
            .unwrap();
        world.set_body_identity(b, Handle::new(8, 1), 0, 0);
        world.apply_impulse(a, Vec3::new(1000.0, 0.0, 0.0), Vec3::ZERO, false);
        world.apply_impulse(b, Vec3::NAN, Vec3::ZERO, false);
        for _ in 0..30 {
            world.advance(1.0 / 60.0);
        }
        (
            world.body_states(&FloatingOrigin::new(DVec3::ZERO)),
            world.clamp_journal_count(),
            world.invalid_state_count(),
        )
    };
    assert_eq!(run(), run());
}

#[test]
fn la_masse_vient_de_la_densite_du_collider() {
    // R-622 : la masse est calculée depuis la densité du collider. Un cube de
    // demi-dimensions [1,1,1] occupe 2×2×2 = 8 m³ ; à 1000 kg/m³, il pèse 8000 kg.
    let mut world = PhysicsWorld::new(config());
    let cube = world
        .add_assembly(
            BodyKind::Dynamic,
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
        .expect("un cube est une forme valide");
    let mass = world.body_mass(cube).expect("le corps existe");
    assert!(
        (mass - 8000.0).abs() < 1.0,
        "masse = densité × volume, attendu 8000, obtenu {mass}"
    );
}

#[test]
fn la_masse_est_proportionnelle_a_la_densite() {
    // Doubler la densité double la masse (R-622) : c'est ce qui distingue le vrai
    // câblage des densités d'un défaut rapier uniforme.
    let masse = |density: f32| {
        let mut world = PhysicsWorld::new(config());
        let ball = world
            .add_assembly(
                BodyKind::Dynamic,
                Vec3::ZERO,
                Quat::IDENTITY,
                &[BodyCollider {
                    shape: Shape::Ball { radius: 0.5 },
                    density,
                    material: ContactMaterial::default(),
                    translation: Vec3::ZERO,
                    rotation: Quat::IDENTITY,
                }],
            )
            .expect("une bille est valide");
        world.body_mass(ball).expect("le corps existe")
    };
    let simple = masse(1000.0);
    let double = masse(2000.0);
    assert!(simple > 0.0, "masse strictement positive");
    assert!(
        (double - 2.0 * simple).abs() < simple * 1.0e-3,
        "densité doublée → masse doublée, {double} vs 2×{simple}"
    );
}

#[test]
fn le_centre_de_masse_penche_vers_le_collider_dense() {
    // Deux cubes identiques, l'un en +x, l'autre en −x ; le +x deux fois plus
    // dense. Le centre de masse doit pencher vers le cube dense (R-622,
    // multi-matériaux). COM analytique : (2000·(+1) + 1000·(−1)) / 3000 = +1/3.
    let cube = || Shape::Cuboid {
        half_extents: [0.5, 0.5, 0.5],
    };
    let mut world = PhysicsWorld::new(config());
    let body = world
        .add_assembly(
            BodyKind::Dynamic,
            Vec3::ZERO,
            Quat::IDENTITY,
            &[
                BodyCollider {
                    shape: cube(),
                    density: 2000.0,
                    material: ContactMaterial::default(),
                    translation: Vec3::new(1.0, 0.0, 0.0),
                    rotation: Quat::IDENTITY,
                },
                BodyCollider {
                    shape: cube(),
                    density: 1000.0,
                    material: ContactMaterial::default(),
                    translation: Vec3::new(-1.0, 0.0, 0.0),
                    rotation: Quat::IDENTITY,
                },
            ],
        )
        .expect("deux cubes sont valides");
    let com = world.body_center_of_mass(body).expect("le corps existe");
    assert!(
        (com.x - 1.0 / 3.0).abs() < 0.05,
        "le COM penche vers le cube dense (+1/3 en x), obtenu {}",
        com.x
    );
    assert!(
        com.y.abs() < 1.0e-3 && com.z.abs() < 1.0e-3,
        "le COM reste centré en y et z, obtenu {com:?}"
    );
}

/// Un champ de hauteurs plat, minimal (2×2), pour les tests de forme.
fn flat_heightfield() -> Shape {
    Shape::Heightfield {
        rows: 2,
        cols: 2,
        heights: vec![0.0, 0.0, 0.0, 0.0],
        scale: [16.0, 1.0, 16.0],
    }
}

#[test]
fn un_champ_de_hauteurs_est_refuse_sur_un_corps_dynamique() {
    // INV-13 (R-970) : les formes concaves du monde n'existent pas sur du dynamique.
    let mut world = PhysicsWorld::new(config());
    let err = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::ZERO,
            Quat::IDENTITY,
            flat_heightfield(),
        )
        .expect_err("un champ de hauteurs dynamique est refusé");
    assert_eq!(err, BodyError::HeightfieldOnDynamicBody);
    // Refus avant toute insertion : le monde reste vide.
    assert_eq!(world.body_count(), 0);
}

#[test]
fn un_champ_de_hauteurs_dynamique_est_refuse_meme_enfoui_dans_un_compound() {
    // La garde INV-13 descend dans les compounds : une fille concave reste interdite.
    let mut world = PhysicsWorld::new(config());
    let err = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::ZERO,
            Quat::IDENTITY,
            Shape::Compound {
                parts: vec![CompoundPart {
                    translation: Vec3::ZERO,
                    rotation: Quat::IDENTITY,
                    shape: flat_heightfield(),
                }],
            },
        )
        .expect_err("un compound à fille champ de hauteurs est refusé sur dynamique");
    assert_eq!(err, BodyError::HeightfieldOnDynamicBody);
    assert_eq!(world.body_count(), 0);
}

#[test]
fn un_champ_de_hauteurs_est_accepte_sur_un_corps_statique() {
    // Sur du décor statique, le champ de hauteurs est une forme valide (C-38, R-641).
    let mut world = PhysicsWorld::new(config());
    let body = world
        .add_body(
            BodyKind::Static,
            Vec3::ZERO,
            Quat::IDENTITY,
            flat_heightfield(),
        )
        .expect("un champ de hauteurs statique est accepté");
    assert_eq!(world.body_count(), 1);
    assert!(world.body_center_of_mass(body).is_some());
}

#[test]
fn un_champ_de_hauteurs_valide_ses_dimensions() {
    let mut world = PhysicsWorld::new(config());
    let attempt = |world: &mut PhysicsWorld, shape: Shape| {
        world.add_body(BodyKind::Static, Vec3::ZERO, Quat::IDENTITY, shape)
    };

    // Moins de 2 lignes : aucune cellule.
    assert_eq!(
        attempt(
            &mut world,
            Shape::Heightfield {
                rows: 1,
                cols: 2,
                heights: vec![0.0, 0.0],
                scale: [16.0, 1.0, 16.0],
            }
        ),
        Err(BodyError::HeightfieldTooSmall)
    );
    // Nombre de hauteurs incohérent.
    assert_eq!(
        attempt(
            &mut world,
            Shape::Heightfield {
                rows: 2,
                cols: 2,
                heights: vec![0.0, 0.0, 0.0],
                scale: [16.0, 1.0, 16.0],
            }
        ),
        Err(BodyError::HeightfieldSizeMismatch)
    );
    // Échelle x nulle.
    assert_eq!(
        attempt(
            &mut world,
            Shape::Heightfield {
                rows: 2,
                cols: 2,
                heights: vec![0.0, 0.0, 0.0, 0.0],
                scale: [0.0, 1.0, 16.0],
            }
        ),
        Err(BodyError::HeightfieldInvalidScale)
    );
    // Hauteur non finie.
    assert_eq!(
        attempt(
            &mut world,
            Shape::Heightfield {
                rows: 2,
                cols: 2,
                heights: vec![0.0, f32::NAN, 0.0, 0.0],
                scale: [16.0, 1.0, 16.0],
            }
        ),
        Err(BodyError::HeightfieldNonFiniteHeight)
    );
    // Aucun de ces refus n'a inséré de corps.
    assert_eq!(world.body_count(), 0);
}

// --- C-39 : requêtes spatiales (fiche 5.31) --------------------------------------------

/// Un sol statique 20×2×20 centré à l'origine, avancé un pas pour peupler le broad-phase.
fn world_with_ground() -> (PhysicsWorld, BodyId) {
    let mut world = PhysicsWorld::new(config());
    let ground = world
        .add_body(
            BodyKind::Static,
            Vec3::ZERO,
            Quat::IDENTITY,
            Shape::Cuboid {
                half_extents: [10.0, 1.0, 10.0],
            },
        )
        .expect("un sol est valide");
    // Une requête lit la géométrie laissée par le dernier pas : on avance une fois pour
    // que le collider entre dans le broad-phase.
    world.advance(1.0 / 60.0);
    (world, ground)
}

#[test]
fn un_rayon_touche_le_sol() {
    let (world, ground) = world_with_ground();
    let hit = world
        .raycast(
            Vec3::new(0.0, 10.0, 0.0),
            Vec3::new(0.0, -1.0, 0.0),
            50.0,
            SpatialFilter::new(),
        )
        .expect("le rayon touche le sol");
    assert_eq!(hit.body, ground);
    // Sommet du sol à y=1 : distance 10 - 1 = 9.
    assert!(
        (hit.distance - 9.0).abs() < 1.0e-3,
        "distance {}",
        hit.distance
    );
    assert!(
        (hit.point.y - 1.0).abs() < 1.0e-3,
        "point.y {}",
        hit.point.y
    );
    assert!(
        hit.normal.y > 0.9,
        "normale vers le haut, obtenu {:?}",
        hit.normal
    );
}

#[test]
fn un_rayon_dans_le_vide_ne_touche_rien() {
    let (world, _) = world_with_ground();
    assert!(
        world
            .raycast(
                Vec3::new(0.0, 10.0, 0.0),
                Vec3::new(0.0, 1.0, 0.0), // vers le haut, loin du sol
                50.0,
                SpatialFilter::new(),
            )
            .is_none(),
        "aucun corps au-dessus"
    );
}

#[test]
fn le_filtre_exclut_un_corps() {
    let (world, ground) = world_with_ground();
    let hit = world.raycast(
        Vec3::new(0.0, 10.0, 0.0),
        Vec3::new(0.0, -1.0, 0.0),
        50.0,
        SpatialFilter::new().excluding(ground),
    );
    assert!(hit.is_none(), "le sol exclu n'est plus heurté");
}

#[test]
fn overlap_trouve_le_sol() {
    let (world, ground) = world_with_ground();
    // Une petite boîte à l'origine recouvre le sol.
    let bodies = world.overlap(
        &Shape::Cuboid {
            half_extents: [0.5, 0.5, 0.5],
        },
        Vec3::new(0.0, 0.5, 0.0),
        Quat::IDENTITY,
        SpatialFilter::new(),
    );
    assert!(
        bodies.contains(&ground),
        "le sol est recouvert, obtenu {bodies:?}"
    );
    // Loin du sol : aucun recouvrement.
    let none = world.overlap(
        &Shape::Ball { radius: 0.5 },
        Vec3::new(0.0, 50.0, 0.0),
        Quat::IDENTITY,
        SpatialFilter::new(),
    );
    assert!(none.is_empty(), "rien à 50 blocs de haut, obtenu {none:?}");
}

#[test]
fn un_sweep_touche_le_sol_devant() {
    let (world, ground) = world_with_ground();
    // Une bille lâchée de haut, balayée vers le bas, touche le sol.
    let hit = world
        .sweep(
            &Shape::Ball { radius: 0.5 },
            Vec3::new(0.0, 10.0, 0.0),
            Quat::IDENTITY,
            Vec3::new(0.0, -1.0, 0.0),
            50.0,
            SpatialFilter::new(),
        )
        .expect("le balayage touche le sol");
    assert_eq!(hit.body, ground);
    // Contact quand le bas de la bille atteint y=1 : centre à y≈1.5, distance ≈ 8.5.
    assert!(
        (hit.time_of_impact - 8.5).abs() < 0.1,
        "toi {}",
        hit.time_of_impact
    );
}

#[test]
fn une_requete_ne_mute_pas_le_monde() {
    // R-650 : les requêtes sont en lecture seule.
    let (world, _) = world_with_ground();
    let before = world.body_count();
    let _ = world.raycast(
        Vec3::new(0.0, 10.0, 0.0),
        Vec3::NEG_Y,
        50.0,
        SpatialFilter::new(),
    );
    let _ = world.overlap(
        &Shape::Ball { radius: 1.0 },
        Vec3::ZERO,
        Quat::IDENTITY,
        SpatialFilter::new(),
    );
    assert_eq!(world.body_count(), before, "aucun corps ajouté ni retiré");
}

#[test]
fn le_raycast_par_lot_rend_un_resultat_par_rayon() {
    let (world, ground) = world_with_ground();
    let rays = [
        (Vec3::new(0.0, 10.0, 0.0), Vec3::NEG_Y, 50.0), // touche
        (Vec3::new(0.0, 10.0, 0.0), Vec3::Y, 50.0),     // manque
    ];
    let results = world.raycast_batch(&rays, SpatialFilter::new());
    assert_eq!(results.len(), 2);
    assert_eq!(results[0].expect("touche").body, ground);
    assert!(results[1].is_none(), "le second rayon manque");
}

// --- C-40 : ordonnanceur de simulation (fiche 5.32) ------------------------------------

#[test]
fn le_serveur_integre_et_mesure_l_etape() {
    // Mode serveur (défaut) : une bille tombe, et l'étape d'intégration est mesurée.
    let mut world = PhysicsWorld::new(config());
    let ball = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(0.0, 10.0, 0.0),
            Quat::IDENTITY,
            Shape::Ball { radius: 0.5 },
        )
        .unwrap();
    let before = world.pose(ball).unwrap().translation.y;
    let substeps = world.advance(1.0 / 60.0);
    assert!(substeps >= 1, "le serveur exécute au moins un sous-pas");
    assert!(
        world.pose(ball).unwrap().translation.y < before,
        "la bille tombe sous la gravité"
    );
    // R-661 : l'étape d'intégration a une métrique (non nulle après un sous-pas).
    assert!(
        world.stage_duration(Stage::Integration) > 0,
        "l'étape d'intégration est mesurée"
    );
}

#[test]
fn le_client_n_integre_pas_l_autoritaire() {
    // R-662 (INV-17) : en mode client, aucune intégration autoritaire — la bille ne bouge
    // pas et aucun sous-pas ne s'exécute.
    let mut world = PhysicsWorld::new(config());
    world.set_mode(SimMode::Client);
    assert_eq!(world.mode(), SimMode::Client);
    let ball = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(0.0, 10.0, 0.0),
            Quat::IDENTITY,
            Shape::Ball { radius: 0.5 },
        )
        .unwrap();
    let before = world.pose(ball).unwrap().translation.y;
    for _ in 0..10 {
        assert_eq!(world.advance(1.0 / 60.0), 0, "aucun sous-pas côté client");
    }
    assert_eq!(
        world.pose(ball).unwrap().translation.y,
        before,
        "la bille ne bouge pas sans intégration autoritaire"
    );
    assert_eq!(world.stage_duration(Stage::Integration), 0);
}
// --- ADR-120 : emprise physique courante des corps rapportés ------------------

/// Écart toléré sur une emprise calculée en `f32`.
const EPS_EMPRISE: f32 = 1.0e-5;

/// Vérifie une emprise rapportée, composante par composante.
fn assert_emprise(bounds: &BodyBounds, min: [f32; 3], max: [f32; 3]) {
    for axis in 0..3 {
        assert!(
            (bounds.min[axis] - min[axis]).abs() < EPS_EMPRISE,
            "min[{axis}] = {}, attendu {} ({bounds:?})",
            bounds.min[axis],
            min[axis]
        );
        assert!(
            (bounds.max[axis] - max[axis]).abs() < EPS_EMPRISE,
            "max[{axis}] = {}, attendu {} ({bounds:?})",
            bounds.max[axis],
            max[axis]
        );
    }
}

/// Le cube de test d'AXION : une boîte de 1 bloc posée sur l'origine du corps
/// (bas-centre), comme le collider `auto_box` du contenu de test.
fn cube_bas_centre() -> BodyCollider {
    BodyCollider {
        shape: Shape::Cuboid {
            half_extents: [0.5, 0.5, 0.5],
        },
        density: 1.0,
        material: ContactMaterial::default(),
        translation: Vec3::new(0.0, 0.5, 0.0),
        rotation: Quat::IDENTITY,
    }
}

#[test]
fn adr120_etats_et_emprises_sont_apparies() {
    // Un même parcours produit les deux tableaux : même longueur, même ordre, et
    // l'emprise de rang i est celle du corps de rang i — ici des formes de
    // tailles distinctes, reconnaissables. Statique et anonyme : ni état ni
    // emprise.
    let mut world = PhysicsWorld::new(config());
    let sol = world
        .add_body(
            BodyKind::Static,
            Vec3::new(0.0, -0.5, 0.0),
            Quat::IDENTITY,
            Shape::Cuboid {
                half_extents: [5.0, 0.5, 5.0],
            },
        )
        .unwrap();
    world.set_body_identity(sol, Handle::new(1, 1), 0, 0);
    world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(9.0, 5.0, 0.0),
            Quat::IDENTITY,
            Shape::Ball { radius: 3.0 },
        )
        .unwrap();
    let bille = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(0.0, 5.0, 0.0),
            Quat::IDENTITY,
            Shape::Ball { radius: 0.5 },
        )
        .unwrap();
    world.set_body_identity(bille, Handle::new(2, 1), 0, 0);
    let caisse = world
        .add_body(
            BodyKind::Kinematic,
            Vec3::new(4.0, 5.0, 0.0),
            Quat::IDENTITY,
            Shape::Cuboid {
                half_extents: [1.0, 2.0, 3.0],
            },
        )
        .unwrap();
    world.set_body_identity(caisse, Handle::new(3, 1), 0, 0);

    let reports = world.body_reports(&FloatingOrigin::new(DVec3::ZERO));
    assert_eq!(reports.states.len(), 2);
    assert_eq!(reports.bounds.len(), reports.states.len());
    for (state, bounds) in reports.states.iter().zip(&reports.bounds) {
        if state.handle == Handle::new(2, 1) {
            assert_emprise(bounds, [-0.5; 3], [0.5; 3]);
        } else {
            assert_eq!(state.handle, Handle::new(3, 1));
            assert_emprise(bounds, [-1.0, -2.0, -3.0], [1.0, 2.0, 3.0]);
        }
    }
    assert_eq!(world.bounds_unavailable_count(), 0);
}

#[test]
fn adr120_l_emprise_suit_l_orientation_du_corps() {
    // Le cas observé en jeu : le cube posé sur son origine (bas-centre). Droit,
    // son emprise est [-0.5, 0.5] × [0, 1] × [-0.5, 0.5] ; tourné, elle englobe.
    let demi_diagonale = std::f32::consts::FRAC_1_SQRT_2;
    let cas = [
        // Droit.
        (Quat::IDENTITY, [-0.5, 0.0, -0.5], [0.5, 1.0, 0.5]),
        // 45° autour de Y : demi-largeur 0.5·√2 en x et z, hauteur inchangée.
        (
            Quat::from_rotation_y(std::f32::consts::FRAC_PI_4),
            [-demi_diagonale, 0.0, -demi_diagonale],
            [demi_diagonale, 1.0, demi_diagonale],
        ),
        // Basculé de 90° autour de X : le centre passe de (0, 0.5, 0) à
        // (0, 0, 0.5) ; l'origine est au milieu d'une face latérale.
        (
            Quat::from_rotation_x(std::f32::consts::FRAC_PI_2),
            [-0.5, -0.5, 0.0],
            [0.5, 0.5, 1.0],
        ),
    ];
    for (rotation, min, max) in cas {
        let mut world = PhysicsWorld::new(config());
        let cube = world
            .add_assembly(
                BodyKind::Dynamic,
                Vec3::new(3.0, 7.0, -2.0),
                rotation,
                &[cube_bas_centre()],
            )
            .unwrap();
        world.set_body_identity(cube, Handle::new(1, 1), 0, 0);
        let reports = world.body_reports(&FloatingOrigin::new(DVec3::ZERO));
        assert_emprise(&reports.bounds[0], min, max);
    }
}

#[test]
fn adr120_l_emprise_est_l_union_des_colliders() {
    // Plusieurs colliders, dont une forme composée : l'emprise les couvre tous.
    let mut world = PhysicsWorld::new(config());
    let bille_deportee = BodyCollider {
        shape: Shape::Ball { radius: 0.25 },
        density: 1.0,
        material: ContactMaterial::default(),
        translation: Vec3::new(2.0, 0.0, 0.0),
        rotation: Quat::IDENTITY,
    };
    let compose = BodyCollider {
        shape: Shape::Compound {
            parts: vec![CompoundPart {
                translation: Vec3::new(0.0, 0.0, -3.0),
                rotation: Quat::IDENTITY,
                shape: Shape::Cuboid {
                    half_extents: [0.5, 0.5, 0.5],
                },
            }],
        },
        density: 1.0,
        material: ContactMaterial::default(),
        translation: Vec3::ZERO,
        rotation: Quat::IDENTITY,
    };
    let corps = world
        .add_assembly(
            BodyKind::Dynamic,
            Vec3::new(0.0, 10.0, 0.0),
            Quat::IDENTITY,
            &[cube_bas_centre(), bille_deportee, compose],
        )
        .unwrap();
    world.set_body_identity(corps, Handle::new(1, 1), 0, 0);
    let reports = world.body_reports(&FloatingOrigin::new(DVec3::ZERO));
    assert_emprise(&reports.bounds[0], [-0.5, -0.5, -3.5], [2.25, 1.0, 0.5]);
}

#[test]
fn adr120_l_emprise_suit_la_pose_restauree() {
    // R-181 : un corps hors du monde est ramené à l'origine par `set_position`,
    // qui ne resynchronise ses colliders qu'au pas suivant. Une emprise lue dans
    // le cache des colliders resterait à 2e7 blocs de la pose rapportée ; celle
    // d'ADR-120 part de la pose rapportée.
    let mut world = PhysicsWorld::new(config());
    let bille = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(2.0e7, 5.0, 0.0),
            Quat::IDENTITY,
            Shape::Ball { radius: 0.5 },
        )
        .unwrap();
    world.set_body_identity(bille, Handle::new(5, 1), 0, 0);

    world.advance(1.0 / 60.0);

    assert_eq!(
        world.invalid_state_count(),
        1,
        "la pose a bien été restaurée"
    );
    let reports = world.body_reports(&FloatingOrigin::new(DVec3::ZERO));
    assert!(reports.states[0].position[0].abs() < 1.0, "pose restaurée");
    assert_emprise(&reports.bounds[0], [-0.5; 3], [0.5; 3]);
}

#[test]
fn adr120_l_emprise_ne_perd_rien_loin_de_l_origine() {
    // L'emprise est calculée sous la seule rotation du corps : loin de l'origine
    // de simulation, elle garde la précision d'un corps à l'origine. Une
    // soustraction « emprise absolue − translation » y perdrait de l'ordre d'un
    // ulp de 3e4 (environ 2e-3), bien au-delà de la tolérance.
    let angle = std::f32::consts::FRAC_PI_6;
    let mut world = PhysicsWorld::new(config());
    let caisse = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(30_000.3, 5.0, -30_000.7),
            Quat::from_rotation_z(angle),
            Shape::Cuboid {
                half_extents: [0.5, 0.5, 0.5],
            },
        )
        .unwrap();
    world.set_body_identity(caisse, Handle::new(1, 1), 0, 0);
    let reports = world.body_reports(&FloatingOrigin::new(DVec3::ZERO));
    // Demi-étendue d'une boîte tournée de 30° autour de z : 0.5·(cos 30° + sin 30°).
    let demi = 0.5 * (angle.cos() + angle.sin());
    assert_emprise(&reports.bounds[0], [-demi, -demi, -0.5], [demi, demi, 0.5]);
}

#[test]
fn adr120_une_emprise_incalculable_est_nulle_et_comptee() {
    // Un corps sans collider n'a pas d'emprise : il est rapporté avec la boîte
    // nulle, jamais une valeur inventée, et l'événement est compté.
    let mut world = PhysicsWorld::new(config());
    let vide = world
        .add_assembly(
            BodyKind::Kinematic,
            Vec3::new(0.0, 5.0, 0.0),
            Quat::IDENTITY,
            &[],
        )
        .unwrap();
    world.set_body_identity(vide, Handle::new(1, 1), 0, 0);
    let reports = world.body_reports(&FloatingOrigin::new(DVec3::ZERO));
    assert_eq!(reports.bounds, vec![BodyBounds::EMPTY]);
    assert_eq!(world.bounds_unavailable_count(), 1);
}
