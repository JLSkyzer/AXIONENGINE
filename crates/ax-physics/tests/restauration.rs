//! ADR-153 D3, D4 : une assembly rechargée reprend l'état que sa sauvegarde a gardé — la pose et les
//! vitesses de chaque corps, ses joints rompus ou grippés —, sans événement, et naît endormie comme la
//! gestion d'activité endort : ses vitesses gelées, rendues à son réveil.

use ax_math::{DVec3, Quat, Vec3};
use ax_model::dm::geometry::WorldTransform;
use ax_model::dm::joints::joint_state_flags;
use ax_model::dm::physics::{body_state_flags, event_kind, BodyDesc, BodyState};
use ax_physics::{
    AssemblyBody, AssemblyJoint, AxisMode, BodyCollider, BodyId, BodyKind, BreakThresholds,
    CollisionGroups, ContactMaterial, Handle, Jam, JointKind, JointSpec, SavedBody, SavedJoint,
    Shape, SimDriver, SimSettings,
};

const ASSEMBLY: Handle = Handle {
    index: 7,
    generation: 1,
};

const TICK: f32 = 1.0 / 20.0;

fn boite() -> BodyCollider {
    BodyCollider {
        shape: Shape::Cuboid {
            half_extents: [0.5; 3],
        },
        density: 1000.0,
        material: ContactMaterial::default(),
        translation: Vec3::ZERO,
        rotation: Quat::IDENTITY,
    }
}

fn corps(node: u32, translation: Vec3) -> AssemblyBody {
    AssemblyBody {
        node,
        translation,
        rotation: Quat::IDENTITY,
        kind: BodyKind::Dynamic,
        colliders: vec![boite()],
        desc: BodyDesc::undeclared(2),
    }
}

/// Un pilote sans gravité en dimension 0.
fn pilote() -> SimDriver {
    let mut driver = SimDriver::with_settings(SimSettings {
        simulation_radius: 64.0,
        max_active_bodies: 2048,
        ..SimSettings::default()
    });
    driver.apply_dimension_env(0, Vec3::ZERO, Vec3::ZERO, None);
    driver
}

fn joint(kind: JointKind, ancre_b: Vec3, jam: Option<Jam>) -> JointSpec {
    JointSpec {
        kind,
        frame_a: (Vec3::ZERO, Quat::IDENTITY),
        frame_b: (-ancre_b, Quat::IDENTITY),
        limits: jam.as_ref().map(|_| [-1.0, 1.0]),
        motor: None,
        friction: None,
        breaking: BreakThresholds::NONE,
        axes: [AxisMode::Locked; 6],
        max_length: 0.0,
        rest_length: 0.0,
        stiffness: 0.0,
        damping: 0.0,
        collide: false,
        jam,
    }
}

/// Trois corps — la racine, `b` à deux blocs sur une charnière grippable, `c` à deux blocs de l'autre
/// côté, soudé — à (0, 64, 0).
fn creer(driver: &mut SimDriver) -> Vec<BodyId> {
    let joints = [
        AssemblyJoint {
            index: 0,
            body_a: 0,
            body_b: 1,
            spec: joint(
                JointKind::Revolute,
                Vec3::new(2.0, 0.0, 0.0),
                Some(Jam {
                    curve: vec![[0.1, 0.5]],
                    friction: 0.0,
                }),
            ),
        },
        AssemblyJoint {
            index: 1,
            body_a: 0,
            body_b: 2,
            spec: joint(JointKind::Fixed, Vec3::new(-2.0, 0.0, 0.0), None),
        },
    ];
    driver
        .create_assembly_bodies(
            0,
            ASSEMBLY,
            WorldTransform {
                position: [0.0, 64.0, 0.0],
                rotation: [0.0, 0.0, 0.0, 1.0],
            },
            &[
                corps(0, Vec3::ZERO),
                corps(1, Vec3::new(2.0, 0.0, 0.0)),
                corps(2, Vec3::new(-2.0, 0.0, 0.0)),
            ],
            &joints,
            CollisionGroups::ALL,
            0.0,
            false,
        )
        .expect("trois boîtes valides")
}

/// L'état sauvegardé : chaque corps ailleurs que sa pose de repos, chacun à sa vitesse.
fn sauvegarde() -> Vec<SavedBody> {
    let tourne = Quat::from_rotation_y(0.5);
    vec![
        SavedBody {
            position: DVec3::new(10.25, 70.5, -3.125),
            rotation: tourne,
            linear: Vec3::new(3.0, 0.0, 0.0),
            angular: Vec3::new(0.0, 0.25, 0.0),
        },
        SavedBody {
            position: DVec3::new(12.0, 70.5, -2.0),
            rotation: tourne,
            linear: Vec3::new(3.0, 0.5, 0.0),
            angular: Vec3::new(0.0, 0.25, 0.0),
        },
        SavedBody {
            position: DVec3::new(8.5, 70.5, -4.25),
            rotation: tourne,
            linear: Vec3::new(3.0, -0.5, 0.0),
            angular: Vec3::new(0.0, 0.25, 0.0),
        },
    ]
}

fn etat(driver: &SimDriver, node: u32) -> BodyState {
    *driver
        .collect_reports()
        .states
        .iter()
        .find(|state| state.handle == ASSEMBLY && state.node == node)
        .expect("le corps est rapporté")
}

#[test]
fn adr153_d3_chaque_corps_reprend_sa_pose_et_ses_vitesses() {
    let mut driver = pilote();
    let corps_crees = creer(&mut driver);
    let gardes = sauvegarde();
    assert!(driver.restore_assembly(0, ASSEMBLY, &corps_crees, &gardes, &[]));

    for (node, garde) in gardes.iter().enumerate() {
        let lu = etat(&driver, u32::try_from(node).unwrap());
        assert_eq!(
            lu.position,
            garde.position.to_array(),
            "position du corps {node}"
        );
        assert_eq!(
            lu.rotation,
            garde.rotation.to_array(),
            "rotation du corps {node}"
        );
        assert_eq!(
            lu.lin_vel,
            garde.linear.to_array(),
            "vitesse du corps {node}"
        );
        assert_eq!(
            lu.ang_vel,
            garde.angular.to_array(),
            "vitesse angulaire du corps {node}"
        );
    }
}

#[test]
fn adr153_d4_une_assembly_chargee_nait_endormie_et_se_reveille_a_ses_vitesses() {
    let mut driver = pilote();
    let corps_crees = creer(&mut driver);
    let gardes = sauvegarde();
    driver.restore_assembly(0, ASSEMBLY, &corps_crees, &gardes, &[]);
    driver.suspend_assembly(0, corps_crees[0]);

    // Endormie : chaque corps dit SUSPENDED, ses vitesses nulles dans l'état, et ne bouge pas.
    driver.set_observers(0, &[]);
    driver.manage_activity();
    driver.advance_all(TICK);
    for (node, garde) in gardes.iter().enumerate() {
        let lu = etat(&driver, u32::try_from(node).unwrap());
        let attendu = body_state_flags::SLEEPING | body_state_flags::SUSPENDED;
        assert_eq!(lu.flags & attendu, attendu, "corps {node} endormi de force");
        assert_eq!(lu.lin_vel, [0.0; 3], "vitesses gelées, nulles dans l'état");
        assert_eq!(lu.position, garde.position.to_array(), "immobile");
    }

    // Un joueur à portée : la gestion d'activité la réveille, à ses vitesses.
    driver.set_observers(0, &[DVec3::new(10.0, 70.0, -3.0)]);
    driver.manage_activity();
    for (node, garde) in gardes.iter().enumerate() {
        let lu = etat(&driver, u32::try_from(node).unwrap());
        assert_eq!(
            lu.flags & body_state_flags::SUSPENDED,
            0,
            "corps {node} réveillé"
        );
        assert_eq!(
            lu.lin_vel,
            garde.linear.to_array(),
            "vitesse du corps {node} rendue"
        );
    }
    driver.advance_all(TICK);
    let racine = etat(&driver, 0);
    assert!(
        racine.position[0] > gardes[0].position.x + 0.1,
        "la racine reprend sa course : x = {}",
        racine.position[0]
    );
}

#[test]
fn adr153_d3_un_joint_restaure_rompu_ou_grippe_ne_s_annonce_pas() {
    let mut driver = pilote();
    let corps_crees = creer(&mut driver);
    let gardes = sauvegarde();
    let joints = [
        SavedJoint {
            broken: false,
            misalignment: 0.1,
        },
        SavedJoint {
            broken: true,
            misalignment: 0.0,
        },
    ];
    assert!(driver.restore_assembly(0, ASSEMBLY, &corps_crees, &gardes, &joints));
    driver.suspend_assembly(0, corps_crees[0]);

    // Endormie, elle rapporte quand même ses joints au premier tick, une fois.
    driver.set_observers(0, &[]);
    driver.manage_activity();
    driver.advance_all(TICK);
    let reports = driver.collect_reports();
    let etats: Vec<_> = reports
        .joints
        .iter()
        .filter(|state| state.handle == ASSEMBLY)
        .collect();
    assert_eq!(etats.len(), 2, "les deux joints rapportés une fois");
    assert_eq!(
        etats[0].flags,
        joint_state_flags::JAMMED,
        "la charnière grippée"
    );
    assert!(
        (etats[0].fraction - 0.5).abs() < 1e-6,
        "à mi-plage : {}",
        etats[0].fraction
    );
    assert_eq!(
        etats[1].flags,
        joint_state_flags::BROKEN,
        "la soudure rompue"
    );

    let annonces: Vec<_> = driver
        .drain_events()
        .into_iter()
        .filter(|event| {
            event.kind == event_kind::JOINT_BROKEN || event.kind == event_kind::JOINT_JAMMED
        })
        .collect();
    assert!(
        annonces.is_empty(),
        "un état rendu ne s'annonce pas : {annonces:?}"
    );

    driver.advance_all(TICK);
    assert!(
        driver
            .collect_reports()
            .joints
            .iter()
            .all(|state| state.handle != ASSEMBLY),
        "endormie, elle ne les rapporte plus"
    );
}

#[test]
fn adr153_d3_un_etat_sans_un_corps_par_corps_est_refuse_sans_effet() {
    let mut driver = pilote();
    let corps_crees = creer(&mut driver);
    let avant = etat(&driver, 1);
    assert!(!driver.restore_assembly(0, ASSEMBLY, &corps_crees, &sauvegarde()[..2], &[]));
    assert_eq!(etat(&driver, 1), avant, "rien n'a bougé");
}

/// Une porte : un montant fixe, et un battant dynamique sur une charnière.
fn creer_porte(driver: &mut SimDriver) -> Vec<BodyId> {
    let mut montant = corps(0, Vec3::ZERO);
    montant.kind = BodyKind::Static;
    montant.desc = BodyDesc::undeclared(0);
    let gond = AssemblyJoint {
        index: 0,
        body_a: 0,
        body_b: 1,
        spec: joint(JointKind::Revolute, Vec3::new(1.0, 0.0, 0.0), None),
    };
    driver
        .create_assembly_bodies(
            0,
            ASSEMBLY,
            WorldTransform {
                position: [0.0, 64.0, 0.0],
                rotation: [0.0, 0.0, 0.0, 1.0],
            },
            &[montant, corps(1, Vec3::new(1.0, 0.0, 0.0))],
            &[gond],
            CollisionGroups::ALL,
            0.0,
            false,
        )
        .expect("un montant et un battant valides")
}

#[test]
fn adr153_d4_une_assembly_a_racine_fixe_naît_endormie_et_se_reveille() {
    let mut driver = pilote();
    let corps_crees = creer_porte(&mut driver);
    driver.suspend_assembly(0, corps_crees[0]);
    driver.set_observers(0, &[]);
    driver.manage_activity();
    driver.advance_all(TICK);
    assert_ne!(
        etat(&driver, 1).flags & body_state_flags::SUSPENDED,
        0,
        "le battant dort, gelé"
    );

    driver.set_observers(0, &[DVec3::new(0.0, 64.0, 0.0)]);
    let rapport = driver.manage_activity();
    assert_eq!(rapport.woken, 2, "l'assembly entière, réveillée à portée");
    assert_eq!(
        etat(&driver, 1).flags & body_state_flags::SUSPENDED,
        0,
        "le battant réveillé"
    );

    // R-612 : hors de portée de tout joueur, elle s'endort de nouveau.
    driver.set_observers(0, &[DVec3::new(500.0, 64.0, 0.0)]);
    let rapport = driver.manage_activity();
    assert_eq!(rapport.slept_by_radius, 2, "endormie hors de portée");
}

#[test]
fn adr153_d4_a_portee_d_un_joueur_elle_est_reveillee_par_la_gestion_d_activite() {
    // Un joueur déjà à portée au premier tick : née endormie, l'assembly est réveillée par la gestion
    // d'activité, qui la compte, à ses vitesses ; sans ce sommeil de naissance, rien ne serait réveillé.
    let mut driver = pilote();
    let corps_crees = creer(&mut driver);
    let gardes = sauvegarde();
    driver.restore_assembly(0, ASSEMBLY, &corps_crees, &gardes, &[]);
    driver.suspend_assembly(0, corps_crees[0]);
    driver.set_observers(0, &[DVec3::new(10.0, 70.0, -3.0)]);
    let rapport = driver.manage_activity();
    assert_eq!(rapport.woken, 3, "ses trois corps réveillés");
    assert_eq!(rapport.slept_by_radius, 0);
    assert_eq!(etat(&driver, 0).lin_vel, gardes[0].linear.to_array());
}
