//! Overlay `colliders` (C-67, ADR-121) : tracé des colliders en repère du corps,
//! sélection par distance et budget, lecture seule.

use ax_math::{DVec3, FloatingOrigin, Quat, Vec3};
use ax_model::dm::debug::{debug_body_flags, DebugSegment};
use ax_physics::{
    BodyCollider, BodyKind, CompoundPart, ContactMaterial, Handle, PhysicsConfig, PhysicsWorld,
    Shape, SimDriver, ROUND_SUBDIVISIONS,
};
use std::collections::BTreeSet;

fn config() -> PhysicsConfig {
    PhysicsConfig::new(1.0 / 60.0, 4).expect("configuration par défaut valide")
}

fn collider(shape: Shape, translation: Vec3) -> BodyCollider {
    BodyCollider {
        shape,
        density: 1.0,
        material: ContactMaterial::default(),
        translation,
        rotation: Quat::IDENTITY,
    }
}

/// Extrémités d'un tracé, arrondies au millième pour comparer des ensembles.
fn extremites(segments: &[DebugSegment]) -> BTreeSet<[i64; 3]> {
    let arrondi = |p: [f32; 3]| p.map(|c| (f64::from(c) * 1000.0).round() as i64);
    segments
        .iter()
        .flat_map(|s| [arrondi(s.a), arrondi(s.b)])
        .collect()
}

fn longueur(segment: &DebugSegment) -> f32 {
    (Vec3::from_array(segment.b) - Vec3::from_array(segment.a)).length()
}

/// Le seul corps tracé d'un monde.
fn seul_trace(world: &PhysicsWorld) -> Vec<DebugSegment> {
    let outlines = world.collider_outlines(&FloatingOrigin::new(DVec3::ZERO));
    assert_eq!(outlines.len(), 1, "un corps d'assembly");
    outlines.into_iter().next().unwrap().segments
}

#[test]
fn une_boite_est_tracee_par_ses_douze_aretes_en_repere_du_corps() {
    // Boîte (1, 0.25, 0.5) posée sur l'origine du corps : centre local (0, 0.25, 0).
    // Le corps est tourné et déplacé : le tracé, en repère du corps, n'en dépend pas.
    let mut world = PhysicsWorld::new(config());
    let corps = world
        .add_assembly(
            BodyKind::Dynamic,
            Vec3::new(40.0, 70.0, -12.0),
            Quat::from_rotation_z(0.7),
            &[collider(
                Shape::Cuboid {
                    half_extents: [1.0, 0.25, 0.5],
                },
                Vec3::new(0.0, 0.25, 0.0),
            )],
        )
        .unwrap();
    world.set_body_identity(corps, Handle::new(4, 1), 0, 0);

    let segments = seul_trace(&world);
    assert_eq!(segments.len(), 12);
    let mut coins = BTreeSet::new();
    for x in [-1000, 1000] {
        for y in [0, 500] {
            for z in [-500, 500] {
                coins.insert([x, y, z]);
            }
        }
    }
    assert_eq!(extremites(&segments), coins);
}

#[test]
fn un_convexe_est_trace_par_ses_vraies_aretes_sans_diagonales() {
    // Enveloppe d'un cube d'arête 1 : 12 arêtes de longueur 1. Les faces carrées
    // sont triangulées par l'enveloppe ; leurs diagonales (√2) ne sont pas des arêtes.
    let mut points = Vec::new();
    for x in [0.0, 1.0] {
        for y in [0.0, 1.0] {
            for z in [0.0, 1.0] {
                points.push([x, y, z]);
            }
        }
    }
    let mut world = PhysicsWorld::new(config());
    let corps = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(0.0, 5.0, 0.0),
            Quat::IDENTITY,
            Shape::ConvexHull { points },
        )
        .unwrap();
    world.set_body_identity(corps, Handle::new(1, 1), 0, 0);

    let segments = seul_trace(&world);
    assert_eq!(segments.len(), 12, "les 12 arêtes du cube, et elles seules");
    for segment in &segments {
        assert!(
            (longueur(segment) - 1.0).abs() < 1.0e-5,
            "diagonale tracée : {segment:?}"
        );
    }
}

#[test]
fn une_sphere_est_tracee_par_trois_cercles_a_son_rayon() {
    let mut world = PhysicsWorld::new(config());
    let corps = world
        .add_assembly(
            BodyKind::Dynamic,
            Vec3::new(0.0, 5.0, 0.0),
            Quat::IDENTITY,
            &[collider(
                Shape::Ball { radius: 0.5 },
                Vec3::new(0.0, 0.5, 0.0),
            )],
        )
        .unwrap();
    world.set_body_identity(corps, Handle::new(1, 1), 0, 0);

    let segments = seul_trace(&world);
    assert_eq!(segments.len(), 3 * ROUND_SUBDIVISIONS as usize);
    let centre = Vec3::new(0.0, 0.5, 0.0);
    for segment in &segments {
        for point in [segment.a, segment.b] {
            let rayon = (Vec3::from_array(point) - centre).length();
            assert!(
                (rayon - 0.5).abs() < 1.0e-5,
                "point hors de la sphère : {point:?}"
            );
        }
    }
}

#[test]
fn une_forme_composee_trace_chaque_enfant_a_sa_pose() {
    let mut world = PhysicsWorld::new(config());
    let corps = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(0.0, 5.0, 0.0),
            Quat::IDENTITY,
            Shape::Compound {
                parts: vec![CompoundPart {
                    translation: Vec3::new(0.0, 0.0, -3.0),
                    rotation: Quat::IDENTITY,
                    shape: Shape::Cuboid {
                        half_extents: [0.5, 0.5, 0.5],
                    },
                }],
            },
        )
        .unwrap();
    world.set_body_identity(corps, Handle::new(1, 1), 0, 0);

    let segments = seul_trace(&world);
    assert_eq!(segments.len(), 12);
    for point in extremites(&segments) {
        assert!(
            point[2] == -3500 || point[2] == -2500,
            "enfant mal placé : {point:?}"
        );
    }
}

#[test]
fn un_champ_de_hauteurs_est_trace_par_les_aretes_de_ses_triangles() {
    // Grille de 3 × 3 hauteurs : 4 cellules, 8 triangles, 16 arêtes distinctes
    // (12 côtés de cellule et 4 diagonales).
    let mut world = PhysicsWorld::new(config());
    let sol = world
        .add_body(
            BodyKind::Static,
            Vec3::ZERO,
            Quat::IDENTITY,
            Shape::Heightfield {
                rows: 3,
                cols: 3,
                heights: vec![0.0; 9],
                scale: [4.0, 1.0, 4.0],
            },
        )
        .unwrap();
    world.set_body_identity(sol, Handle::new(1, 1), 0, 0);

    let segments = seul_trace(&world);
    assert_eq!(segments.len(), 16);
}

#[test]
fn les_corps_d_assembly_sont_traces_statiques_compris_avec_leur_genre() {
    let mut world = PhysicsWorld::new(config());
    for (index, kind, x) in [
        (1u32, BodyKind::Dynamic, 0.0f32),
        (2, BodyKind::Static, 3.0),
        (3, BodyKind::Kinematic, 6.0),
    ] {
        let id = world
            .add_body(
                kind,
                Vec3::new(x, 5.0, 0.0),
                Quat::IDENTITY,
                Shape::Cuboid {
                    half_extents: [0.5, 0.5, 0.5],
                },
            )
            .unwrap();
        world.set_body_identity(id, Handle::new(index, 1), 0, 0);
    }
    // Sans identité (une tuile du monde, par exemple) : jamais tracé.
    world
        .add_body(
            BodyKind::Static,
            Vec3::new(9.0, 5.0, 0.0),
            Quat::IDENTITY,
            Shape::Cuboid {
                half_extents: [0.5, 0.5, 0.5],
            },
        )
        .unwrap();

    let outlines = world.collider_outlines(&FloatingOrigin::new(DVec3::ZERO));
    assert_eq!(
        outlines.len(),
        3,
        "les trois corps d'assembly, pas le corps anonyme"
    );
    let genre = |index: u32| {
        outlines
            .iter()
            .find(|outline| outline.handle == Handle::new(index, 1))
            .map(|outline| outline.flags & (debug_body_flags::STATIC | debug_body_flags::KINEMATIC))
            .unwrap()
    };
    assert_eq!(genre(1), 0, "dynamique");
    assert_eq!(genre(2), debug_body_flags::STATIC);
    assert_eq!(genre(3), debug_body_flags::KINEMATIC);
}

#[test]
fn un_corps_endormi_est_marque() {
    let mut world = PhysicsWorld::new(config());
    world
        .add_body(
            BodyKind::Static,
            Vec3::new(0.0, -0.5, 0.0),
            Quat::IDENTITY,
            Shape::Cuboid {
                half_extents: [5.0, 0.5, 5.0],
            },
        )
        .unwrap();
    let bille = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(0.0, 0.5, 0.0),
            Quat::IDENTITY,
            Shape::Ball { radius: 0.5 },
        )
        .unwrap();
    world.set_body_identity(bille, Handle::new(1, 1), 0, 0);
    for _ in 0..180 {
        world.advance(1.0 / 60.0);
    }
    let outlines = world.collider_outlines(&FloatingOrigin::new(DVec3::ZERO));
    assert_eq!(outlines.len(), 1);
    assert!(
        outlines[0].flags & debug_body_flags::SLEEPING != 0,
        "la bille posée dort"
    );
}

/// Un pilote à trois boîtes d'assembly dans la dimension 0, à x = 0, 10 et 20.
fn trois_boites() -> SimDriver {
    let mut driver = SimDriver::new();
    let world = driver.world_or_create(0, config(), FloatingOrigin::new(DVec3::ZERO));
    for (index, x) in [(1u32, 0.0f32), (2, 10.0), (3, 20.0)] {
        let id = world
            .add_body(
                BodyKind::Static,
                Vec3::new(x, 0.0, 0.0),
                Quat::IDENTITY,
                Shape::Cuboid {
                    half_extents: [0.5, 0.5, 0.5],
                },
            )
            .unwrap();
        world.set_body_identity(id, Handle::new(index, 1), 0, 0);
    }
    driver
}

#[test]
fn le_budget_va_aux_corps_les_plus_proches_de_la_camera() {
    let driver = trois_boites();
    // Caméra près de x = 20 : ordre 3, 2, 1. Budget de deux boîtes (24 segments).
    let debug = driver.debug_colliders(0, DVec3::new(19.0, 0.0, 0.0), 24);
    let handles: Vec<u32> = debug.bodies.iter().map(|body| body.handle.index).collect();
    assert_eq!(
        handles,
        vec![3, 2],
        "les deux plus proches, du plus proche au plus lointain"
    );
    assert_eq!(debug.omitted_bodies, 1, "le plus lointain, omis et compté");
    assert_eq!(debug.segments.len(), 24);
    assert_eq!(debug.bodies[1].first_segment, 12);
    assert_eq!(debug.bodies[1].segment_count, 12);
}

#[test]
fn un_corps_n_est_jamais_coupe() {
    let driver = trois_boites();
    // 11 segments ne suffisent à aucune boîte : rien n'est tracé, tout est compté.
    let debug = driver.debug_colliders(0, DVec3::ZERO, 11);
    assert!(debug.bodies.is_empty() && debug.segments.is_empty());
    assert_eq!(debug.omitted_bodies, 3);
}

#[test]
fn a_distance_egale_l_ordre_suit_la_cle_du_handle() {
    let driver = trois_boites();
    // Caméra à x = 5 : les corps 1 et 2 sont à égale distance.
    let debug = driver.debug_colliders(0, DVec3::new(5.0, 0.0, 0.0), u32::MAX);
    let handles: Vec<u32> = debug.bodies.iter().map(|body| body.handle.index).collect();
    assert_eq!(handles, vec![1, 2, 3]);
}

#[test]
fn une_dimension_inconnue_ne_trace_rien() {
    let driver = trois_boites();
    let debug = driver.debug_colliders(42, DVec3::ZERO, u32::MAX);
    assert!(debug.bodies.is_empty() && debug.segments.is_empty());
    assert_eq!(debug.omitted_bodies, 0);
}

#[test]
fn le_trace_ne_modifie_pas_la_simulation() {
    // Deux pilotes identiques : un cube qui bascule sur un sol. L'un est tracé à
    // chaque tick, l'autre jamais ; leurs états restent identiques au bit près.
    fn pilote() -> SimDriver {
        let mut driver = SimDriver::new();
        let world = driver.world_or_create(0, config(), FloatingOrigin::new(DVec3::ZERO));
        world
            .add_body(
                BodyKind::Static,
                Vec3::new(0.0, -0.5, 0.0),
                Quat::IDENTITY,
                Shape::Cuboid {
                    half_extents: [5.0, 0.5, 5.0],
                },
            )
            .unwrap();
        let cube = world
            .add_assembly(
                BodyKind::Dynamic,
                Vec3::new(0.0, 2.0, 0.0),
                Quat::from_rotation_x(0.5) * Quat::from_rotation_z(0.6),
                &[collider(
                    Shape::Cuboid {
                        half_extents: [0.5, 0.5, 0.5],
                    },
                    Vec3::new(0.0, 0.5, 0.0),
                )],
            )
            .unwrap();
        world.set_body_identity(cube, Handle::new(1, 1), 0, 0);
        driver
    }
    let mut trace = pilote();
    let mut temoin = pilote();
    for _ in 0..120 {
        let debug = trace.debug_colliders(0, DVec3::new(3.0, 2.0, 1.0), u32::MAX);
        assert_eq!(debug.bodies.len(), 1);
        trace.advance_all(1.0 / 20.0);
        temoin.advance_all(1.0 / 20.0);
    }
    assert_eq!(
        trace.collect_states(),
        temoin.collect_states(),
        "le tracé a modifié la simulation"
    );
}
