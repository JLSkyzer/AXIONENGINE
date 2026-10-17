//! T-550 — overlays du debug renderer (C-67, ADR-121 et son amendement A1) : `colliders` en
//! repère du corps ; `aabb`, `bodies`, `contacts`, `normals`, `world_tiles` et
//! `center_of_mass`, chacun dans son repère ; sélection par distance et budget, lecture
//! seule. Allumé, un overlay produit la bonne géométrie ; éteint, il ne coûte qu'un test de
//! drapeau (T-551, côté Java).

use ax_math::{DVec3, FloatingOrigin, Quat, Vec3};
use ax_model::dm::debug::{debug_body_flags, debug_frame, overlay, DebugSegment};
use ax_physics::{
    BodyCollider, BodyKind, CompoundPart, ContactMaterial, Handle, PhysicsConfig, PhysicsWorld,
    Shape, SimDriver, AXIS_LENGTH, CONTACT_CROSS, MASS_CROSS, NORMAL_LENGTH, ROUND_SUBDIVISIONS,
};
use std::collections::BTreeSet;

/// Les sept overlays que le natif sait tracer.
const TOUS: u64 = overlay::COLLIDERS
    | overlay::AABB
    | overlay::BODIES
    | overlay::CONTACTS
    | overlay::NORMALS
    | overlay::WORLD_TILES
    | overlay::CENTER_OF_MASS;

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
fn t550_une_boite_est_tracee_par_ses_douze_aretes_en_repere_du_corps() {
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
    world.set_body_identity(corps, Handle::new(4, 1), 0);

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
fn t550_un_convexe_est_trace_par_ses_vraies_aretes_sans_diagonales() {
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
    world.set_body_identity(corps, Handle::new(1, 1), 0);

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
fn t550_une_sphere_est_tracee_par_trois_cercles_a_son_rayon() {
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
    world.set_body_identity(corps, Handle::new(1, 1), 0);

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
fn t550_une_forme_composee_trace_chaque_enfant_a_sa_pose() {
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
    world.set_body_identity(corps, Handle::new(1, 1), 0);

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
fn t550_un_champ_de_hauteurs_est_trace_par_les_aretes_de_ses_triangles() {
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
    world.set_body_identity(sol, Handle::new(1, 1), 0);

    let segments = seul_trace(&world);
    assert_eq!(segments.len(), 16);
}

#[test]
fn t550_les_corps_d_assembly_sont_traces_statiques_compris_avec_leur_genre() {
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
        world.set_body_identity(id, Handle::new(index, 1), 0);
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
fn t550_un_corps_endormi_est_marque() {
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
    world.set_body_identity(bille, Handle::new(1, 1), 0);
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
        world.set_body_identity(id, Handle::new(index, 1), 0);
    }
    driver
}

#[test]
fn t550_le_budget_va_aux_corps_les_plus_proches_de_la_camera() {
    let driver = trois_boites();
    // Caméra près de x = 20 : ordre 3, 2, 1. Budget de deux boîtes (24 segments).
    let debug = driver.debug_overlays(0, DVec3::new(19.0, 0.0, 0.0), overlay::COLLIDERS, 24);
    let handles: Vec<u32> = debug.ranges.iter().map(|body| body.handle.index).collect();
    assert_eq!(
        handles,
        vec![3, 2],
        "les deux plus proches, du plus proche au plus lointain"
    );
    assert_eq!(debug.omitted_ranges, 1, "le plus lointain, omis et compté");
    assert_eq!(debug.segments.len(), 24);
    assert_eq!(debug.ranges[1].first_segment, 12);
    assert_eq!(debug.ranges[1].segment_count, 12);
}

#[test]
fn t550_un_corps_n_est_jamais_coupe() {
    let driver = trois_boites();
    // 11 segments ne suffisent à aucune boîte : rien n'est tracé, tout est compté.
    let debug = driver.debug_overlays(0, DVec3::ZERO, overlay::COLLIDERS, 11);
    assert!(debug.ranges.is_empty() && debug.segments.is_empty());
    assert_eq!(debug.omitted_ranges, 3);
}

#[test]
fn t550_a_distance_egale_l_ordre_suit_la_cle_du_handle() {
    let driver = trois_boites();
    // Caméra à x = 5 : les corps 1 et 2 sont à égale distance.
    let debug = driver.debug_overlays(0, DVec3::new(5.0, 0.0, 0.0), overlay::COLLIDERS, u32::MAX);
    let handles: Vec<u32> = debug.ranges.iter().map(|body| body.handle.index).collect();
    assert_eq!(handles, vec![1, 2, 3]);
}

#[test]
fn t550_une_dimension_inconnue_ne_trace_rien() {
    let driver = trois_boites();
    let debug = driver.debug_overlays(42, DVec3::ZERO, TOUS, u32::MAX);
    assert!(debug.ranges.is_empty() && debug.segments.is_empty());
    assert_eq!(debug.omitted_ranges, 0);
}

#[test]
fn t550_le_trace_ne_modifie_pas_la_simulation() {
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
        world.set_body_identity(cube, Handle::new(1, 1), 0);
        driver
    }
    let mut trace = pilote();
    let mut temoin = pilote();
    for _ in 0..120 {
        // Tous les overlays à la fois : aucun ne modifie le monde.
        let debug = trace.debug_overlays(0, DVec3::new(3.0, 2.0, 1.0), TOUS, u32::MAX);
        assert!(!debug.ranges.is_empty());
        trace.advance_all(1.0 / 20.0);
        temoin.advance_all(1.0 / 20.0);
    }
    assert_eq!(
        trace.collect_states(),
        temoin.collect_states(),
        "le tracé a modifié la simulation"
    );
}

/// Bornes d'un ensemble de segments : les plus petites et les plus grandes coordonnées.
fn bornes(segments: &[DebugSegment]) -> ([f32; 3], [f32; 3]) {
    let mut min = [f32::MAX; 3];
    let mut max = [f32::MIN; 3];
    for point in segments.iter().flat_map(|s| [s.a, s.b]) {
        for axe in 0..3 {
            min[axe] = min[axe].min(point[axe]);
            max[axe] = max[axe].max(point[axe]);
        }
    }
    (min, max)
}

/// Douze arêtes de boîte, distinctes, chacune alignée sur un axe : ni diagonale ni doublon.
fn douze_aretes_alignees(segments: &[DebugSegment]) {
    assert_eq!(segments.len(), 12);
    let arrondi = |p: [f32; 3]| p.map(|c| (f64::from(c) * 1000.0).round() as i64);
    let distinctes: BTreeSet<[[i64; 3]; 2]> = segments
        .iter()
        .map(|s| {
            let (a, b) = (arrondi(s.a), arrondi(s.b));
            if a <= b {
                [a, b]
            } else {
                [b, a]
            }
        })
        .collect();
    assert_eq!(distinctes.len(), 12, "douze arêtes distinctes");
    for segment in segments {
        let axes = (0..3)
            .filter(|&axe| (segment.a[axe] - segment.b[axe]).abs() > 1e-5)
            .count();
        assert_eq!(axes, 1, "une arête suit un seul axe : {segment:?}");
    }
}

fn proche(obtenu: [f32; 3], attendu: [f32; 3]) {
    for axe in 0..3 {
        assert!(
            (obtenu[axe] - attendu[axe]).abs() < 1e-4,
            "{obtenu:?} au lieu de {attendu:?}"
        );
    }
}

#[test]
fn t550_aabb_trace_la_boite_monde_de_chaque_collider_relative_a_la_camera() {
    // Une boîte de demi-côtés (1, 0,5, 1) tournée de 45° autour de y, à (100, 5, -50)
    // dans le monde — l'origine flottante en (100, 0, -50) —, vue d'une caméra en
    // (98, 5, -50) : son AABB s'étend de ±√2 en x et en z, de ±0,5 en y, autour de
    // (2, 0, 0) depuis la caméra.
    let mut driver = SimDriver::new();
    let world = driver.world_or_create(
        0,
        config(),
        FloatingOrigin::new(DVec3::new(100.0, 0.0, -50.0)),
    );
    let boite = world
        .add_body(
            BodyKind::Static,
            Vec3::new(0.0, 5.0, 0.0),
            Quat::from_rotation_y(std::f32::consts::FRAC_PI_4),
            Shape::Cuboid {
                half_extents: [1.0, 0.5, 1.0],
            },
        )
        .unwrap();
    world.set_body_identity(boite, Handle::new(1, 1), 0);

    let debug = driver.debug_overlays(0, DVec3::new(98.0, 5.0, -50.0), overlay::AABB, u32::MAX);
    assert_eq!(debug.ranges.len(), 1);
    let plage = debug.ranges[0];
    assert_eq!(plage.handle, Handle::new(1, 1));
    assert_eq!(
        (plage.overlay, plage.frame, plage.segment_count),
        (1, debug_frame::ORIGIN, 12)
    );
    douze_aretes_alignees(&debug.segments);
    let racine = std::f32::consts::SQRT_2;
    let (min, max) = bornes(&debug.segments);
    proche(min, [2.0 - racine, -0.5, -racine]);
    proche(max, [2.0 + racine, 0.5, racine]);
}

#[test]
fn t550_bodies_trace_les_trois_axes_du_corps_avec_son_etat() {
    let driver = trois_boites();
    let debug = driver.debug_overlays(0, DVec3::ZERO, overlay::BODIES, u32::MAX);
    assert_eq!(debug.ranges.len(), 3);
    for plage in &debug.ranges {
        assert_eq!(
            (plage.overlay, plage.frame, plage.segment_count),
            (2, debug_frame::BODY, 3)
        );
        assert_eq!(
            plage.flags & debug_body_flags::STATIC,
            debug_body_flags::STATIC
        );
    }
    let axe = |b: [f32; 3]| DebugSegment { a: [0.0; 3], b };
    assert_eq!(
        &debug.segments[..3],
        &[
            axe([AXIS_LENGTH, 0.0, 0.0]),
            axe([0.0, AXIS_LENGTH, 0.0]),
            axe([0.0, 0.0, AXIS_LENGTH]),
        ]
    );
}

#[test]
fn t550_center_of_mass_trace_une_croix_au_centre_de_masse_local() {
    // Deux boîtes égales, l'une à l'origine du corps, l'autre à x = 2 : le centre de masse
    // est entre elles, en repère du corps, quelle que soit la pose du corps.
    let mut world = PhysicsWorld::new(config());
    let boite = Shape::Cuboid {
        half_extents: [0.5, 0.5, 0.5],
    };
    let corps = world
        .add_assembly(
            BodyKind::Dynamic,
            Vec3::new(7.0, 10.0, 3.0),
            Quat::from_rotation_z(0.3),
            &[
                collider(boite.clone(), Vec3::new(0.0, 0.5, 0.0)),
                collider(boite, Vec3::new(2.0, 0.5, 0.0)),
            ],
        )
        .unwrap();
    world.set_body_identity(corps, Handle::new(1, 1), 0);
    let plages = world.debug_drafts(
        &FloatingOrigin::new(DVec3::ZERO),
        DVec3::ZERO,
        overlay::CENTER_OF_MASS,
    );
    assert_eq!(plages.len(), 1);
    assert_eq!(
        (plages[0].overlay, plages[0].frame),
        (31, debug_frame::BODY)
    );
    assert_eq!(plages[0].segments.len(), 3);
    for segment in &plages[0].segments {
        let milieu = [0, 1, 2].map(|axe| (segment.a[axe] + segment.b[axe]) * 0.5);
        proche(milieu, [1.0, 0.5, 0.0]);
        assert!((longueur(segment) - MASS_CROSS).abs() < 1e-5);
    }
}

#[test]
fn t550_contacts_et_normales_du_dernier_pas_vont_a_l_assembly() {
    // Un cube d'assembly posé sur un sol anonyme : ses contacts sont au sol (y = 0), rangés
    // sous l'assembly bien que le sol n'en porte pas, et leurs normales sont verticales.
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
    let cube = world
        .add_body(
            BodyKind::Dynamic,
            Vec3::new(0.0, 0.5, 0.0),
            Quat::IDENTITY,
            Shape::Cuboid {
                half_extents: [0.5, 0.5, 0.5],
            },
        )
        .unwrap();
    world.set_body_identity(cube, Handle::new(4, 1), 0);
    for _ in 0..20 {
        world.advance(1.0 / 60.0);
    }
    let plages = world.debug_drafts(
        &FloatingOrigin::new(DVec3::ZERO),
        DVec3::ZERO,
        overlay::CONTACTS | overlay::NORMALS,
    );
    assert_eq!(plages.len(), 2, "une plage de contacts, une de normales");
    let (contacts, normales) = (&plages[0], &plages[1]);
    for plage in [contacts, normales] {
        assert_eq!(plage.handle, Handle::new(4, 1));
        assert_eq!(plage.frame, debug_frame::ORIGIN);
    }
    assert_eq!((contacts.overlay, normales.overlay), (3, 4));
    let points = normales.segments.len();
    assert!(points >= 1, "le cube touche le sol");
    assert_eq!(contacts.segments.len(), 3 * points, "une croix par point");
    for croix in contacts.segments.chunks(3) {
        for segment in croix {
            assert!((longueur(segment) - CONTACT_CROSS).abs() < 1e-5);
        }
        let centre = [0, 1, 2].map(|axe| (croix[0].a[axe] + croix[0].b[axe]) * 0.5);
        assert!(centre[1].abs() < 0.02, "contact au sol : y = {}", centre[1]);
    }
    for normale in &normales.segments {
        assert!((longueur(normale) - NORMAL_LENGTH).abs() < 1e-4);
        assert!(
            ((normale.b[1] - normale.a[1]).abs() - NORMAL_LENGTH).abs() < 1e-3,
            "normale verticale : {normale:?}"
        );
    }
}

#[test]
fn t550_world_tiles_trace_les_tuiles_de_la_dimension_relatives_a_la_camera() {
    // Une boîte de tuile [0, 2] × [0, 1] × [0, 3] dans la section (1, 0, 0) : [16, 18] en x
    // dans le monde, [0, 2] depuis une caméra en x = 16. La tuile d'une autre dimension
    // n'est pas tracée.
    let mut driver = SimDriver::new();
    driver.world_or_create(0, config(), FloatingOrigin::new(DVec3::ZERO));
    let tuile = [[0.0, 0.0, 0.0, 2.0, 1.0, 3.0]];
    assert!(driver.set_world_tile(0, [1, 0, 0], &tuile, ContactMaterial::default()));
    let ailleurs = [[0.0, 0.0, 0.0, 1.0, 1.0, 1.0]];
    assert!(driver.set_world_tile(5, [0, 0, 0], &ailleurs, ContactMaterial::default()));

    let camera = DVec3::new(16.0, 0.0, 0.0);
    let debug = driver.debug_overlays(0, camera, overlay::WORLD_TILES, u32::MAX);
    assert_eq!(debug.ranges.len(), 1);
    let plage = debug.ranges[0];
    assert_eq!(plage.handle, Handle::ABSENT);
    assert_eq!(
        (plage.overlay, plage.frame, plage.flags, plage.segment_count),
        (30, debug_frame::ORIGIN, 0, 12)
    );
    let mut coins = BTreeSet::new();
    for x in [0, 2000] {
        for y in [0, 1000] {
            for z in [0, 3000] {
                coins.insert([x, y, z]);
            }
        }
    }
    assert_eq!(extremites(&debug.segments), coins);
    douze_aretes_alignees(&debug.segments);
    // Sans son overlay, une tuile ne sort pas : elle n'a pas d'assembly.
    assert!(driver
        .debug_overlays(0, camera, TOUS & !overlay::WORLD_TILES, u32::MAX)
        .ranges
        .is_empty());
}
