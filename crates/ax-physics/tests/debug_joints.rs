//! ADR-151 D7 — overlays `joints` et `limits` : chaque joint d'un ancrage à l'autre avec son axe, et la
//! plage permise de son axe libre avec sa position courante, en repère d'origine ; leurs drapeaux disent
//! l'état du joint.

use ax_math::{DVec3, FloatingOrigin, Quat, Vec3};
use ax_model::dm::debug::{debug_body_flags, debug_frame, overlay, DebugSegment};
use ax_physics::{
    AxisMode, BodyCollider, BodyId, BodyKind, BreakThresholds, ContactMaterial, DebugDraft, Handle,
    Jam, JointId, JointKind, JointSpec, PhysicsConfig, PhysicsWorld, Shape, AXIS_LENGTH,
    JOINT_CROSS, LIMIT_ARC_STEPS, LIMIT_RADIUS, LIMIT_TICK,
};

fn assembly() -> Handle {
    Handle::new(9, 1)
}

/// Un monde sans gravité : rien ne bouge, les ancrages restent confondus.
fn monde() -> PhysicsWorld {
    let mut world = PhysicsWorld::new(PhysicsConfig::new(1.0 / 60.0, 4).expect("configuration"));
    world.set_gravity(Vec3::ZERO);
    world
}

/// Une ancre statique à l'origine, une boule à un bloc en x, et un joint de `kind` entre elles, son
/// repère à l'origine, son axe X sur `axe`, sa plage `limits`. Au repos, sa coordonnée vaut `ecart` :
/// le repère de la boule est tourné d'autant autour de l'axe (charnière), ou décalé d'autant le long de
/// lui (glissière).
fn relies(
    world: &mut PhysicsWorld,
    kind: JointKind,
    axe: Vec3,
    limits: [f32; 2],
    ecart: f32,
) -> (BodyId, JointId) {
    let a = world
        .add_body(
            BodyKind::Static,
            Vec3::ZERO,
            Quat::IDENTITY,
            Shape::Ball { radius: 0.05 },
        )
        .unwrap();
    let at = Vec3::new(1.0, 0.0, 0.0);
    let b = world
        .add_assembly(
            BodyKind::Dynamic,
            at,
            Quat::IDENTITY,
            &[BodyCollider {
                shape: Shape::Ball { radius: 0.25 },
                density: 1000.0,
                material: ContactMaterial::default(),
                translation: Vec3::ZERO,
                rotation: Quat::IDENTITY,
            }],
        )
        .unwrap();
    let rotation = Quat::from_rotation_arc(Vec3::X, axe.normalize());
    let spec = JointSpec {
        kind,
        frame_a: (Vec3::ZERO, rotation),
        frame_b: if kind == JointKind::Prismatic {
            (-at + axe.normalize() * ecart, rotation)
        } else {
            (-at, rotation * Quat::from_rotation_x(ecart))
        },
        limits: Some(limits),
        motor: None,
        friction: None,
        breaking: BreakThresholds::NONE,
        axes: [AxisMode::Locked; 6],
        max_length: 0.0,
        rest_length: 0.0,
        stiffness: 0.0,
        damping: 0.0,
        collide: false,
        jam: Some(Jam {
            curve: vec![[1.0, 0.0]],
            friction: 0.0,
        }),
    };
    let id = world.add_joint(assembly(), 3, a, b, &spec).unwrap();
    (b, id)
}

/// Les plages d'un overlay, la caméra en `camera`.
fn plages(world: &PhysicsWorld, mask: u64, bit: u64, camera: DVec3) -> Vec<DebugDraft> {
    world
        .debug_drafts(&FloatingOrigin::new(DVec3::ZERO), camera, mask)
        .into_iter()
        .filter(|draft| draft.overlay == overlay::rank(bit))
        .collect()
}

fn proche(obtenu: [f32; 3], attendu: Vec3) {
    assert!(
        (Vec3::from_array(obtenu) - attendu).length() < 1e-4,
        "{obtenu:?} pour {attendu:?}"
    );
}

#[test]
fn adr151_d7_un_joint_trace_ses_ancrages_et_son_axe_en_repere_d_origine() {
    let mut world = monde();
    relies(&mut world, JointKind::Revolute, Vec3::Z, [-1.0, 1.0], 0.0);
    let camera = DVec3::new(0.0, 0.0, 10.0);

    let joints = plages(&world, overlay::JOINTS, overlay::JOINTS, camera);
    assert_eq!(joints.len(), 1, "une plage par joint");
    let plage = &joints[0];
    assert_eq!(plage.handle, assembly());
    assert_eq!(plage.frame, debug_frame::ORIGIN);
    assert_eq!(plage.flags, 0, "ni rompu ni grippé");
    assert!(
        plage.anchor.distance(DVec3::ZERO) < 1e-6,
        "ancré à l'ancrage du premier corps"
    );
    let decale = |p: Vec3| p - camera.as_vec3();
    // La croix de l'ancrage, le segment vers l'ancrage du second corps, l'axe.
    assert_eq!(plage.segments.len(), 5);
    let croix = &plage.segments[..3];
    for segment in croix {
        assert!((longueur(segment) - JOINT_CROSS).abs() < 1e-5);
    }
    proche(plage.segments[3].a, decale(Vec3::ZERO));
    proche(plage.segments[3].b, decale(Vec3::ZERO));
    proche(plage.segments[4].a, decale(Vec3::ZERO));
    proche(plage.segments[4].b, decale(Vec3::Z * AXIS_LENGTH));
}

fn longueur(segment: &DebugSegment) -> f32 {
    (Vec3::from_array(segment.b) - Vec3::from_array(segment.a)).length()
}

#[test]
fn adr151_d7_la_plage_d_une_charniere_est_un_arc_autour_de_son_axe() {
    let mut world = monde();
    relies(&mut world, JointKind::Revolute, Vec3::Z, [-1.0, 1.0], 0.3);

    let limites = plages(&world, overlay::LIMITS, overlay::LIMITS, DVec3::ZERO);
    assert_eq!(limites.len(), 1);
    let segments = &limites[0].segments;
    assert_eq!(
        segments.len(),
        LIMIT_ARC_STEPS as usize + 3,
        "l'arc, ses deux rayons, la position"
    );
    // Le repère porte X sur z : son Y reste y, son Z part vers −x. L'angle θ va vers cos θ · y − sin θ · x.
    let rayon = |angle: f32| Vec3::new(-angle.sin(), angle.cos(), 0.0) * LIMIT_RADIUS;
    for segment in &segments[..LIMIT_ARC_STEPS as usize] {
        for point in [segment.a, segment.b] {
            let p = Vec3::from_array(point);
            assert!(
                (p.length() - LIMIT_RADIUS).abs() < 1e-4,
                "sur l'arc : {p:?}"
            );
            assert!(
                p.z.abs() < 1e-5,
                "dans le plan perpendiculaire à l'axe : {p:?}"
            );
        }
    }
    proche(segments[0].a, rayon(-1.0));
    proche(segments[LIMIT_ARC_STEPS as usize - 1].b, rayon(1.0));
    let bornes = &segments[LIMIT_ARC_STEPS as usize..];
    proche(bornes[0].b, rayon(-1.0));
    proche(bornes[1].b, rayon(1.0));
    // Le rayon de la position, plus long, à 0,3 rad.
    proche(bornes[2].a, Vec3::ZERO);
    proche(bornes[2].b, rayon(0.3) * 1.25);
}

#[test]
fn adr151_d7_la_plage_d_une_glissiere_est_un_segment_le_long_de_son_axe() {
    let mut world = monde();
    relies(
        &mut world,
        JointKind::Prismatic,
        Vec3::X,
        [-0.25, 0.75],
        0.4,
    );

    let limites = plages(&world, overlay::LIMITS, overlay::LIMITS, DVec3::ZERO);
    assert_eq!(limites.len(), 1);
    let segments = &limites[0].segments;
    assert_eq!(
        segments.len(),
        6,
        "la plage, un trait par borne, la croix de la position"
    );
    proche(segments[0].a, Vec3::new(-0.25, 0.0, 0.0));
    proche(segments[0].b, Vec3::new(0.75, 0.0, 0.0));
    for (trait_, borne) in segments[1..3].iter().zip([-0.25, 0.75]) {
        proche(trait_.a, Vec3::new(borne, -LIMIT_TICK * 0.5, 0.0));
        proche(trait_.b, Vec3::new(borne, LIMIT_TICK * 0.5, 0.0));
    }
    for segment in &segments[3..] {
        let milieu = (Vec3::from_array(segment.a) + Vec3::from_array(segment.b)) * 0.5;
        proche(milieu.to_array(), Vec3::new(0.4, 0.0, 0.0));
    }
    // L'ancrage de la boule est à 0,4 bloc de celui de l'ancre : le lien les relie.
    let joints = plages(&world, overlay::JOINTS, overlay::JOINTS, DVec3::ZERO);
    proche(joints[0].segments[3].a, Vec3::ZERO);
    proche(joints[0].segments[3].b, Vec3::new(0.4, 0.0, 0.0));
}

#[test]
fn adr151_d7_un_joint_rompu_garde_ses_ancrages_sans_plage() {
    let mut world = monde();
    let (_, id) = relies(&mut world, JointKind::Revolute, Vec3::Z, [-1.0, 1.0], 0.0);
    assert!(world.break_joint(id));

    let joints = plages(
        &world,
        overlay::JOINTS | overlay::LIMITS,
        overlay::JOINTS,
        DVec3::ZERO,
    );
    assert_eq!(joints.len(), 1, "un joint rompu reste tracé");
    assert_eq!(joints[0].flags, debug_body_flags::JOINT_BROKEN);
    assert!(
        plages(
            &world,
            overlay::JOINTS | overlay::LIMITS,
            overlay::LIMITS,
            DVec3::ZERO
        )
        .is_empty(),
        "plus de plage"
    );
}

#[test]
fn adr151_d7_un_joint_grippe_montre_sa_plage_resserree() {
    let mut world = monde();
    let (_, id) = relies(&mut world, JointKind::Revolute, Vec3::Z, [-1.0, 1.0], 0.0);
    // La courbe va de (0, 1) à (1, 0) : un demi-bloc de désalignement laisse la moitié de la plage,
    // resserrée vers la position 0.
    assert!(world.jam_joint(id, 0.5));

    let tout = overlay::JOINTS | overlay::LIMITS;
    assert_eq!(
        plages(&world, tout, overlay::JOINTS, DVec3::ZERO)[0].flags,
        debug_body_flags::JOINT_JAMMED
    );
    let limites = plages(&world, tout, overlay::LIMITS, DVec3::ZERO);
    assert_eq!(limites[0].flags, debug_body_flags::JOINT_JAMMED);
    let bornes = &limites[0].segments[LIMIT_ARC_STEPS as usize..];
    let rayon = |angle: f32| Vec3::new(-angle.sin(), angle.cos(), 0.0) * LIMIT_RADIUS;
    proche(bornes[0].b, rayon(-0.5));
    proche(bornes[1].b, rayon(0.5));
}

#[test]
fn adr151_d7_chaque_overlay_ne_trace_que_ce_qu_on_lui_demande() {
    let mut world = monde();
    relies(&mut world, JointKind::Revolute, Vec3::Z, [-1.0, 1.0], 0.0);
    assert!(plages(&world, overlay::JOINTS, overlay::LIMITS, DVec3::ZERO).is_empty());
    assert!(plages(&world, overlay::LIMITS, overlay::JOINTS, DVec3::ZERO).is_empty());
    let autres = world.debug_drafts(
        &FloatingOrigin::new(DVec3::ZERO),
        DVec3::ZERO,
        overlay::CONTACTS,
    );
    assert!(
        autres
            .iter()
            .all(|draft| draft.overlay != overlay::rank(overlay::JOINTS)
                && draft.overlay != overlay::rank(overlay::LIMITS)),
        "éteints, ils ne tracent rien"
    );
}
