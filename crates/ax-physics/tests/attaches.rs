//! T-890, T-891, T-893 : les attaches de C-48 au banc de physique (ADR-171 D6).
//!
//! Une attache est un joint de C-34 entre deux assemblies, ou d'une assembly au monde ; ces tests la
//! posent directement sur le monde, comme le pilote le fait après avoir résolu les deux extrémités.

use ax_math::{Quat, Vec3};
use ax_model::dm::attachments::{attachment_kind, attachment_report_flags, detach_cause};
use ax_model::dm::handle::Handle;
use ax_model::dm::physics::event_kind;
use ax_physics::{
    AttachEnd, AttachError, AttachmentSpec, BodyCollider, BodyId, BodyKind, ContactMaterial,
    PhysicsConfig, PhysicsWorld, Shape,
};

/// Un tick de Minecraft.
const TICK: f32 = 1.0 / 20.0;

fn assembly(index: u32) -> Handle {
    Handle {
        index,
        generation: 1,
    }
}

fn monde(gravite: bool) -> PhysicsWorld {
    let mut world = PhysicsWorld::new(PhysicsConfig::new(1.0 / 60.0, 4).expect("configuration"));
    if !gravite {
        world.set_gravity(Vec3::ZERO);
    }
    world
}

/// Une caisse de `demi` demi-côtés et de densité 500, à `position`.
fn caisse(world: &mut PhysicsWorld, position: Vec3, demi: Vec3) -> BodyId {
    world
        .add_assembly(
            BodyKind::Dynamic,
            position,
            Quat::IDENTITY,
            &[BodyCollider {
                shape: Shape::Cuboid {
                    half_extents: demi.into(),
                },
                density: 500.0,
                material: ContactMaterial::default(),
                translation: Vec3::ZERO,
                rotation: Quat::IDENTITY,
            }],
        )
        .unwrap()
}

/// Une attache d'un type, sans seuil, son socket `a` en `ancrage` dans son corps.
fn attache(kind: u8, ancrage: Vec3) -> AttachmentSpec {
    AttachmentSpec {
        kind,
        frame_a: (ancrage, Quat::IDENTITY),
        max_force: f32::INFINITY,
        max_torque: f32::INFINITY,
        cone: 0.6,
        length: 3.0,
        min_length: 0.5,
        max_length: 10.0,
        speed: 1.0,
    }
}

fn position(world: &PhysicsWorld, body: BodyId) -> Vec3 {
    world.pose(body).unwrap().translation
}

/// L'ancrage d'un repère local d'un corps, en monde.
fn ancrage(world: &PhysicsWorld, body: BodyId, local: Vec3) -> Vec3 {
    let pose = world.pose(body).unwrap();
    pose.translation + pose.rotation * local
}

#[test]
fn t890_la_remorque_suit_sa_voiture_a_l_attelage() {
    let mut world = monde(false);
    let voiture = caisse(&mut world, Vec3::ZERO, Vec3::new(1.0, 0.5, 0.5));
    let remorque = caisse(
        &mut world,
        Vec3::new(-2.2, 0.0, 0.0),
        Vec3::new(1.0, 0.4, 0.5),
    );
    let (boule, timon) = (Vec3::new(-1.1, 0.0, 0.0), Vec3::new(1.1, 0.0, 0.0));
    world
        .attach(
            7,
            assembly(1),
            voiture,
            AttachEnd::Body {
                assembly: assembly(2),
                body: remorque,
                frame: (timon, Quat::IDENTITY),
            },
            &attache(attachment_kind::HITCH, boule),
        )
        .expect("attelage");
    let evenements = world.drain_events();
    assert!(
        evenements
            .iter()
            .any(|e| e.kind == event_kind::ATTACH && e.data == 7 && e.assembly_b == assembly(2)),
        "ATTACH porte l'identifiant et l'autre extrémité"
    );
    for _ in 0..40 {
        // La voiture tire vers +x : une impulsion par tick.
        world.apply_impulse(voiture, Vec3::new(400.0, 0.0, 0.0), Vec3::ZERO, false);
        world.advance(TICK);
    }
    let avance = position(&world, remorque).x - (-2.2);
    assert!(avance > 3.0, "la remorque a suivi : {avance} blocs");
    let ecart = ancrage(&world, voiture, boule).distance(ancrage(&world, remorque, timon));
    assert!(
        ecart < 0.05,
        "l'attelage tient ses ancrages ensemble : {ecart}"
    );
    let rapport = world.attachment_reports();
    assert_eq!(rapport.len(), 1);
    assert_eq!((rapport[0].id, rapport[0].flags), (7, 0));
    assert!(rapport[0].force > 0.0, "l'attelage transmet la traction");
}

#[test]
fn t891_une_corde_rompt_au_dela_de_sa_force_et_le_dit_une_fois() {
    let mut world = monde(true);
    let charge = caisse(&mut world, Vec3::new(0.0, -3.0, 0.0), Vec3::splat(0.5));
    // ~500 kg sous la gravité : ~4 900 N, pour une corde qui en tient 2 000.
    let mut corde = attache(attachment_kind::ROPE, Vec3::new(0.0, 0.5, 0.0));
    corde.max_force = 2000.0;
    world
        .attach(3, assembly(1), charge, AttachEnd::World(Vec3::ZERO), &corde)
        .unwrap();
    world.drain_events();
    let mut rompue = None;
    for tick in 0..40 {
        world.advance(TICK);
        let evenements = world.drain_events();
        if let Some(e) = evenements
            .iter()
            .find(|e| e.kind == event_kind::DETACH_ATTACHMENT)
        {
            assert_eq!((e.data, e.node_a), (3, detach_cause::BROKEN));
            assert!(
                e.assembly_b.is_absent(),
                "un ancrage au monde n'a pas d'autre assembly"
            );
            rompue = Some(tick);
            break;
        }
    }
    assert!(rompue.is_some(), "la corde a rompu");
    let rapport = world.attachment_reports();
    assert_eq!(rapport.len(), 1, "rapportée une dernière fois");
    assert_ne!(rapport[0].flags & attachment_report_flags::BROKEN, 0);
    world.advance(TICK);
    assert!(world.attachment_reports().is_empty(), "puis oubliée");
    assert!(world.attachment_ids().is_empty());
    let chute = position(&world, charge).y;
    world.advance(TICK * 10.0);
    assert!(
        position(&world, charge).y < chute,
        "la charge tombe librement"
    );
}

#[test]
fn t893_une_chaine_de_huit_attaches_pend_stable() {
    let mut world = monde(true);
    let maillons: Vec<BodyId> = (0..9)
        .map(|rang| {
            caisse(
                &mut world,
                Vec3::new(0.0, -1.0 - rang as f32, 0.0),
                Vec3::splat(0.3),
            )
        })
        .collect();
    world
        .attach(
            100,
            assembly(1),
            maillons[0],
            AttachEnd::World(Vec3::new(0.0, -0.5, 0.0)),
            &attache(attachment_kind::HITCH, Vec3::new(0.0, 0.5, 0.0)),
        )
        .unwrap();
    for rang in 0..8 {
        world
            .attach(
                rang as u32,
                assembly(rang as u32 + 1),
                maillons[rang],
                AttachEnd::Body {
                    assembly: assembly(rang as u32 + 2),
                    body: maillons[rang + 1],
                    frame: (Vec3::new(0.0, 0.5, 0.0), Quat::IDENTITY),
                },
                &attache(attachment_kind::HITCH, Vec3::new(0.0, -0.5, 0.0)),
            )
            .unwrap();
    }
    // Une poussée latérale au dernier maillon, puis 10 s.
    world.apply_impulse(maillons[8], Vec3::new(200.0, 0.0, 0.0), Vec3::ZERO, false);
    for _ in 0..200 {
        world.advance(TICK);
    }
    for (rang, maillon) in maillons.iter().enumerate() {
        let p = position(&world, *maillon);
        assert!(p.is_finite(), "maillon {rang} fini");
        assert!(
            p.length() < 10.5,
            "maillon {rang} au bout de sa chaîne : {p}"
        );
    }
    let rapports = world.attachment_reports();
    assert_eq!(rapports.len(), 9, "aucune attache rompue");
    for rapport in &rapports {
        assert!(
            rapport.distance < 0.05,
            "attache {} : écart {}",
            rapport.id,
            rapport.distance
        );
    }
}

#[test]
fn adr171_d5_un_treuil_rejoint_sa_longueur_cible_a_sa_vitesse() {
    let mut world = monde(true);
    let charge = caisse(&mut world, Vec3::new(0.0, -5.0, 0.0), Vec3::splat(0.4));
    let mut treuil = attache(attachment_kind::WINCH, Vec3::ZERO);
    treuil.length = 5.0;
    world
        .attach(
            1,
            assembly(1),
            charge,
            AttachEnd::World(Vec3::ZERO),
            &treuil,
        )
        .unwrap();
    assert!(world.set_attachment_target(1, 2.0));
    for _ in 0..20 {
        world.advance(TICK);
    }
    let apres_une_seconde = world.attachment_reports()[0].length;
    assert!(
        (apres_une_seconde - 4.0).abs() < 0.05,
        "1 bloc/s : {apres_une_seconde}"
    );
    for _ in 0..60 {
        world.advance(TICK);
    }
    assert!(
        (world.attachment_reports()[0].length - 2.0).abs() < 1e-4,
        "la cible est atteinte"
    );
    assert!(
        position(&world, charge).y > -2.2,
        "la charge est remontée : {}",
        position(&world, charge).y
    );
    assert!(
        world.set_attachment_target(1, 50.0),
        "une cible hors bornes se ramène à la plus longue"
    );
    assert!(!world.set_attachment_target(9, 1.0), "attache inconnue");
}

#[test]
fn adr171_d6_refus_et_retraits() {
    let mut world = monde(false);
    let a = caisse(&mut world, Vec3::ZERO, Vec3::splat(0.5));
    let b = caisse(&mut world, Vec3::new(2.0, 0.0, 0.0), Vec3::splat(0.5));
    let rigide = attache(attachment_kind::RIGID, Vec3::new(0.5, 0.0, 0.0));
    let vers_b = AttachEnd::Body {
        assembly: assembly(2),
        body: b,
        frame: (Vec3::new(-0.5, 0.0, 0.0), Quat::IDENTITY),
    };
    world.attach(1, assembly(1), a, vers_b, &rigide).unwrap();
    assert_eq!(
        world.attach(1, assembly(1), a, vers_b, &rigide),
        Err(AttachError::Duplicate)
    );
    let mut inconnu = rigide;
    inconnu.kind = 9;
    assert_eq!(
        world.attach(2, assembly(1), a, vers_b, &inconnu),
        Err(AttachError::InvalidSetting)
    );
    let soi = AttachEnd::Body {
        assembly: assembly(1),
        body: a,
        frame: (Vec3::ZERO, Quat::IDENTITY),
    };
    assert_eq!(
        world.attach(3, assembly(1), a, soi, &rigide),
        Err(AttachError::MissingBody)
    );
    let mut corde = attache(attachment_kind::WINCH, Vec3::ZERO);
    corde.length = 20.0;
    assert_eq!(
        world.attach(4, assembly(1), a, AttachEnd::World(Vec3::ZERO), &corde),
        Err(AttachError::InvalidSetting),
        "une longueur hors des bornes du treuil"
    );
    // L'ancrage au monde n'entre dans aucun rapport de corps.
    world
        .attach(
            5,
            assembly(1),
            a,
            AttachEnd::World(Vec3::new(0.0, 3.0, 0.0)),
            &attache(attachment_kind::ROPE, Vec3::ZERO),
        )
        .unwrap();
    let origine = ax_math::FloatingOrigin::new(ax_math::DVec3::ZERO);
    world.set_body_identity(a, assembly(1), 0);
    world.set_body_identity(b, assembly(2), 0);
    world.advance(TICK);
    assert_eq!(
        world.body_reports(&origine).states.len(),
        2,
        "deux corps, pas l'ancre"
    );
    assert_eq!(world.attachment_ids(), vec![1, 5]);
    // Défaire, puis retirer une extrémité : l'attache part avec elle, sans événement.
    assert!(world.detach(5));
    assert!(!world.detach(5));
    world.drain_events();
    world.remove_assembly_joints(assembly(2));
    assert!(
        world.attachment_ids().is_empty(),
        "l'attache part avec son assembly"
    );
    assert!(world.drain_events().is_empty());
}

#[test]
fn r1020_deux_mondes_identiques_tirent_la_meme_remorque_au_bit_pres() {
    let rejouer = || {
        let mut world = monde(true);
        let voiture = caisse(&mut world, Vec3::ZERO, Vec3::new(1.0, 0.5, 0.5));
        let remorque = caisse(
            &mut world,
            Vec3::new(-2.2, 0.0, 0.0),
            Vec3::new(1.0, 0.4, 0.5),
        );
        world
            .attach(
                1,
                assembly(1),
                voiture,
                AttachEnd::Body {
                    assembly: assembly(2),
                    body: remorque,
                    frame: (Vec3::new(1.1, 0.0, 0.0), Quat::IDENTITY),
                },
                &attache(attachment_kind::HITCH, Vec3::new(-1.1, 0.0, 0.0)),
            )
            .unwrap();
        for _ in 0..60 {
            world.apply_impulse(voiture, Vec3::new(300.0, 0.0, 100.0), Vec3::ZERO, false);
            world.advance(TICK);
        }
        let p = position(&world, remorque);
        [p.x.to_bits(), p.y.to_bits(), p.z.to_bits()]
    };
    assert_eq!(rejouer(), rejouer());
}

#[test]
fn adr171_une_corde_qui_se_retend_s_etire_au_lieu_de_claquer() {
    // ADR-171, précision 8d : une corde molle qui se retend est un choc. Deux caisses de 200 kg, l'une s'éloignant à
    // 3 m/s : rigide, la corde transmet ce choc en un sous-pas et une corde de 10 kN rompt ; elle s'étire, tient, et
    // retient la caisse. La rupture se juge à chaque sous-pas ; le rapport ne dit que le dernier d'un tick.
    let mut world = monde(false);
    let demi = Vec3::new(0.5, 0.5, 0.2);
    let voiture = caisse(&mut world, Vec3::ZERO, demi);
    let remorque = caisse(&mut world, Vec3::new(-3.0, 0.0, 0.0), demi);
    assert!((world.body_mass(remorque).unwrap() - 200.0).abs() < 1.0);
    let mut corde = attache(attachment_kind::ROPE, Vec3::new(-0.5, 0.0, 0.0));
    corde.length = 4.0;
    corde.max_force = 10_000.0;
    world
        .attach(
            5,
            assembly(1),
            voiture,
            AttachEnd::Body {
                assembly: assembly(2),
                body: remorque,
                frame: (Vec3::new(0.5, 0.0, 0.0), Quat::IDENTITY),
            },
            &corde,
        )
        .unwrap();
    // Molle de 2 m ; la voiture part à 3 m/s.
    world.apply_impulse(voiture, Vec3::new(600.0, 0.0, 0.0), Vec3::ZERO, false);
    world.drain_events();
    let mut pic: f32 = 0.0;
    for _ in 0..60 {
        world.advance(TICK);
        assert!(
            !world
                .drain_events()
                .iter()
                .any(|e| e.kind == event_kind::DETACH_ATTACHMENT),
            "la corde de 10 kN a rompu au choc"
        );
        pic = pic.max(world.attachment_reports()[0].force);
    }
    assert!(pic > 1000.0, "la corde s'est tendue : {pic} N");
    let ecart = ancrage(&world, voiture, Vec3::new(-0.5, 0.0, 0.0)).distance(ancrage(
        &world,
        remorque,
        Vec3::new(0.5, 0.0, 0.0),
    ));
    assert!(ecart < 4.2, "la corde retient la remorque : {ecart} m");
}

#[test]
fn adr171_une_corde_posee_sur_une_assembly_nee_endormie_la_reveille() {
    // T-891 au jeu : le maillon naît endormi par la gestion d'activité (ADR-153 D4) ; une corde de 2 m vers un point à
    // 3,5 m au-dessus, qui tient 50 N, doit le réveiller, le tirer et rompre.
    let mut world = monde(true);
    let charge = caisse(&mut world, Vec3::ZERO, Vec3::new(0.25, 0.25, 0.25));
    world.suspend_assembly(charge);
    assert_eq!(world.is_sleeping(charge), Some(true));
    let mut corde = attache(attachment_kind::ROPE, Vec3::new(0.0, 0.25, 0.0));
    corde.length = 2.0;
    corde.max_force = 50.0;
    world
        .attach(
            9,
            assembly(1),
            charge,
            AttachEnd::World(Vec3::new(0.0, 3.75, 0.0)),
            &corde,
        )
        .unwrap();
    world.drain_events();
    let mut rompue = false;
    for _ in 0..20 {
        world.advance(TICK);
        rompue |= world
            .drain_events()
            .iter()
            .any(|e| e.kind == event_kind::DETACH_ATTACHMENT && e.data == 9);
    }
    assert!(
        rompue,
        "la corde rompt ; le maillon dort encore : {:?}",
        world.is_sleeping(charge)
    );
}

#[test]
fn t891_une_corde_posee_a_n_importe_quel_instant_se_tend_et_rompt() {
    // Une corde courte et fragile, posée sur une caisse qui repose : elle se tend et rompt, que la caisse soit éveillée,
    // endormie, ou sur le point de s'endormir au pas même où on l'attache — le pas l'endormait alors avant de résoudre
    // le joint, que rien ne tendait plus.
    let mut ratees = Vec::new();
    // Un pas à la fois, au 1/60 s : l'instant où la caisse s'endort tombe dans l'intervalle.
    for pas in 1u32..240 {
        let mut world = monde(true);
        world
            .add_assembly(
                BodyKind::Static,
                Vec3::new(0.0, -0.5, 0.0),
                Quat::IDENTITY,
                &[BodyCollider {
                    shape: Shape::Cuboid {
                        half_extents: [8.0, 0.5, 8.0],
                    },
                    density: 1.0,
                    material: ContactMaterial::default(),
                    translation: Vec3::ZERO,
                    rotation: Quat::IDENTITY,
                }],
            )
            .unwrap();
        let charge = caisse(&mut world, Vec3::new(0.0, 0.25, 0.0), Vec3::splat(0.2));
        for _ in 0..pas {
            world.advance(1.0 / 60.0);
        }
        let mut corde = attache(attachment_kind::ROPE, Vec3::new(0.0, 0.2, 0.0));
        corde.max_force = 50.0;
        corde.length = 2.0;
        world
            .attach(
                3,
                assembly(1),
                charge,
                AttachEnd::World(Vec3::new(0.0, 4.4, 0.0)),
                &corde,
            )
            .unwrap();
        let mut rompue = false;
        for _ in 0..60 {
            world.advance(TICK);
            if world
                .drain_events()
                .iter()
                .any(|e| e.kind == event_kind::DETACH_ATTACHMENT)
            {
                rompue = true;
                break;
            }
        }
        if !rompue {
            ratees.push(pas);
        }
    }
    assert!(
        ratees.is_empty(),
        "posée à ces pas, la corde ne s'est jamais tendue : {ratees:?}"
    );
}
