//! ADR-151 D2 : une assembly porte plusieurs corps, un par node racine.
//!
//! Le corps racine d'abord : l'entité le suit, et les commandes sans node le visent. Les
//! corps d'une même assembly ne se touchent pas (§10.4), sauf déclaration ; l'assembly
//! reste l'unité d'activité, de garde-fou et de sommeil.

use ax_math::{DVec3, Quat, Vec3};
use ax_model::dm::geometry::WorldTransform;
use ax_model::dm::joints::joint_state_flags;
use ax_model::dm::physics::{body_state_flags, event_kind, BodyDesc};
use ax_physics::{
    AssemblyBody, AssemblyJoint, AxisMode, BodyCollider, BodyId, BodyKind, BreakThresholds,
    CollisionGroups, ContactMaterial, Handle, Jam, JointKind, JointSpec, Shape, SimDriver,
    SimSettings,
};

const ASSEMBLY: Handle = Handle {
    index: 7,
    generation: 1,
};

/// Une boîte d'un bloc de côté, de densité `density`, au centre de son corps.
fn boite(density: f32) -> BodyCollider {
    BodyCollider {
        shape: Shape::Cuboid {
            half_extents: [0.5; 3],
        },
        density,
        material: ContactMaterial::default(),
        translation: Vec3::ZERO,
        rotation: Quat::IDENTITY,
    }
}

/// Un corps dynamique d'une boîte, au node `node`, posé en `translation` dans l'assembly.
fn corps(node: u32, translation: Vec3) -> AssemblyBody {
    AssemblyBody {
        node,
        translation,
        rotation: Quat::IDENTITY,
        kind: BodyKind::Dynamic,
        colliders: vec![boite(1000.0)],
        desc: BodyDesc::undeclared(2),
    }
}

/// Un pilote sans gravité en dimension 0, de rayon et de plafond donnés.
fn pilote(radius: f32, cap: usize) -> SimDriver {
    let mut driver = SimDriver::with_settings(SimSettings {
        simulation_radius: radius,
        max_active_bodies: cap,
        ..SimSettings::default()
    });
    driver.apply_dimension_env(0, Vec3::ZERO, Vec3::ZERO, None);
    driver
}

fn spawn(position: [f64; 3], rotation: Quat) -> WorldTransform {
    WorldTransform {
        position,
        rotation: rotation.to_array(),
    }
}

fn creer(
    driver: &mut SimDriver,
    handle: Handle,
    at: WorldTransform,
    bodies: &[AssemblyBody],
    self_collision: bool,
) -> Option<Vec<BodyId>> {
    driver.create_assembly_bodies(
        0,
        handle,
        at,
        bodies,
        &[],
        CollisionGroups::ALL,
        0.0,
        self_collision,
    )
}

fn position(driver: &mut SimDriver, body: BodyId) -> Vec3 {
    driver
        .world_mut(0)
        .and_then(|world| world.pose(body))
        .expect("le corps existe")
        .translation
}

fn dort(driver: &mut SimDriver, body: BodyId) -> bool {
    driver
        .world_mut(0)
        .and_then(|world| world.is_sleeping(body))
        .expect("le corps existe")
}

#[test]
fn adr151_une_assembly_porte_ses_corps_le_corps_racine_d_abord() {
    let mut driver = pilote(128.0, 2048);
    let quart = Quat::from_rotation_y(core::f32::consts::FRAC_PI_2);
    let corps_crees = creer(
        &mut driver,
        ASSEMBLY,
        spawn([10.0, 64.0, 10.0], quart),
        &[corps(0, Vec3::ZERO), corps(3, Vec3::new(2.0, 0.0, 0.0))],
        false,
    )
    .expect("deux boîtes valides");

    assert_eq!(corps_crees.len(), 2);
    let (dimension, routes) = driver.assembly_bodies(ASSEMBLY).expect("routée");
    assert_eq!(dimension, 0);
    assert_eq!(routes, corps_crees.as_slice(), "le corps racine d'abord");
    assert_eq!(driver.assembly_body(ASSEMBLY), Some((0, corps_crees[0])));

    // Chaque corps rapporte son node ; le second est posé par la pose de spawn composée
    // avec la sienne : (2, 0, 0) tourné d'un quart de tour autour de Y donne (0, 0, -2).
    let reports = driver.collect_reports();
    let nodes: Vec<(Handle, u32)> = reports.states.iter().map(|s| (s.handle, s.node)).collect();
    assert_eq!(nodes, vec![(ASSEMBLY, 0), (ASSEMBLY, 3)]);
    let second = reports.states[1].position;
    assert!((second[0] - 10.0).abs() < 1e-4, "x = {}", second[0]);
    assert!((second[2] - 8.0).abs() < 1e-4, "z = {}", second[2]);
}

#[test]
fn adr151_les_corps_d_une_assembly_ne_se_touchent_pas_sauf_declaration() {
    // Deux boîtes qui se chevauchent d'un demi-bloc : séparées, elles s'écartent ; d'une
    // même assembly, elles restent où elles sont (§10.4) ; déclarées, elles s'écartent.
    let ecart_apres = |self_collision: bool, meme_assembly: bool| -> f32 {
        let mut driver = pilote(128.0, 2048);
        let at = spawn([0.0, 64.0, 0.0], Quat::IDENTITY);
        let (a, b) = if meme_assembly {
            let ids = creer(
                &mut driver,
                ASSEMBLY,
                at,
                &[corps(0, Vec3::ZERO), corps(1, Vec3::new(0.5, 0.0, 0.0))],
                self_collision,
            )
            .unwrap();
            (ids[0], ids[1])
        } else {
            let a = creer(&mut driver, ASSEMBLY, at, &[corps(0, Vec3::ZERO)], false).unwrap()[0];
            let at_b = spawn([0.5, 64.0, 0.0], Quat::IDENTITY);
            let autre = Handle::new(8, 1);
            let b = creer(&mut driver, autre, at_b, &[corps(0, Vec3::ZERO)], false).unwrap()[0];
            (a, b)
        };
        for _ in 0..20 {
            driver.advance_all(1.0 / 20.0);
        }
        (position(&mut driver, b) - position(&mut driver, a)).length()
    };

    let filtrees = ecart_apres(false, true);
    assert!(
        (filtrees - 0.5).abs() < 1e-5,
        "aucun contact interne : {filtrees}"
    );
    let declarees = ecart_apres(true, true);
    assert!(
        declarees > 0.9,
        "collisions internes déclarées : {declarees}"
    );
    let separees = ecart_apres(false, false);
    assert!(separees > 0.9, "deux assemblies se touchent : {separees}");
}

#[test]
fn adr151_retirer_l_assembly_retire_tous_ses_corps() {
    let mut driver = pilote(128.0, 2048);
    creer(
        &mut driver,
        ASSEMBLY,
        spawn([0.0, 64.0, 0.0], Quat::IDENTITY),
        &[corps(0, Vec3::ZERO), corps(1, Vec3::new(3.0, 0.0, 0.0))],
        false,
    )
    .unwrap();
    assert_eq!(driver.world_mut(0).unwrap().body_count(), 2);

    assert!(driver.apply_remove_assembly(ASSEMBLY));

    assert_eq!(driver.world_mut(0).unwrap().body_count(), 0);
    assert!(driver.assembly_bodies(ASSEMBLY).is_none());
    assert!(driver.collect_reports().states.is_empty());
}

#[test]
fn adr151_la_gestion_d_activite_endort_et_reveille_l_assembly_entiere() {
    let mut driver = pilote(32.0, 2048);
    let ids = creer(
        &mut driver,
        ASSEMBLY,
        spawn([0.0, 64.0, 0.0], Quat::IDENTITY),
        &[corps(0, Vec3::ZERO), corps(1, Vec3::new(3.0, 0.0, 0.0))],
        false,
    )
    .unwrap();

    // Aucun joueur à portée : les deux corps s'endorment, et le compte est en corps.
    driver.begin_tick();
    driver.set_observers(0, &[DVec3::new(500.0, 64.0, 0.0)]);
    let report = driver.manage_activity();
    assert_eq!(report.slept_by_radius, 2);
    assert!(dort(&mut driver, ids[0]) && dort(&mut driver, ids[1]));

    // Un joueur revient : les deux se réveillent ensemble.
    driver.begin_tick();
    driver.set_observers(0, &[DVec3::new(1.0, 64.0, 0.0)]);
    let report = driver.manage_activity();
    assert_eq!(report.woken, 2);
    assert_eq!(report.active, 2);
    assert!(!dort(&mut driver, ids[0]) && !dort(&mut driver, ids[1]));
}

#[test]
fn adr151_une_assembly_reste_a_portee_tant_que_l_un_de_ses_corps_l_est() {
    // R-612 par assembly : sa racine est à 60 blocs du joueur, au-delà du rayon de 32, mais
    // son second corps est à 10 blocs ; elle reste éveillée, entière.
    let mut driver = pilote(32.0, 2048);
    let ids = creer(
        &mut driver,
        ASSEMBLY,
        spawn([60.0, 64.0, 0.0], Quat::IDENTITY),
        &[corps(0, Vec3::ZERO), corps(1, Vec3::new(-50.0, 0.0, 0.0))],
        false,
    )
    .unwrap();

    driver.begin_tick();
    driver.set_observers(0, &[DVec3::new(0.0, 64.0, 0.0)]);
    let report = driver.manage_activity();

    assert_eq!(report.slept_by_radius, 0);
    assert_eq!(report.active, 2);
    assert!(!dort(&mut driver, ids[0]) && !dort(&mut driver, ids[1]));
}

#[test]
fn adr151_le_plafond_de_corps_actifs_prend_une_assembly_entiere_ou_pas_du_tout() {
    // R-613 compte des corps : sous un plafond de deux, une assembly de trois corps, plus
    // proche, ne tient pas ; la bille d'un corps, plus loin, garde sa place.
    let mut driver = pilote(128.0, 2);
    let trois = creer(
        &mut driver,
        ASSEMBLY,
        spawn([0.0, 64.0, 0.0], Quat::IDENTITY),
        &[
            corps(0, Vec3::ZERO),
            corps(1, Vec3::new(3.0, 0.0, 0.0)),
            corps(2, Vec3::new(6.0, 0.0, 0.0)),
        ],
        false,
    )
    .unwrap();
    let seul = creer(
        &mut driver,
        Handle::new(9, 1),
        spawn([40.0, 64.0, 0.0], Quat::IDENTITY),
        &[corps(0, Vec3::ZERO)],
        false,
    )
    .unwrap()[0];

    driver.begin_tick();
    driver.set_observers(0, &[DVec3::new(0.0, 64.0, 0.0)]);
    let report = driver.manage_activity();

    assert_eq!(report.slept_by_cap, 3);
    assert_eq!(report.active, 1);
    assert!(trois.iter().all(|id| dort(&mut driver, *id)));
    assert!(!dort(&mut driver, seul));
}

#[test]
fn adr151_un_corps_hors_du_monde_ramene_toute_son_assembly() {
    // R-181 : le second corps naît hors du monde ; au premier pas il est restauré, et le
    // corps racine avec lui, endormi — un seul état invalide compté, un seul RECOVERED.
    let mut driver = pilote(128.0, 2048);
    let ids = creer(
        &mut driver,
        ASSEMBLY,
        spawn([0.0, 64.0, 0.0], Quat::IDENTITY),
        &[corps(0, Vec3::ZERO), corps(1, Vec3::new(2.0e7, 0.0, 0.0))],
        false,
    )
    .unwrap();
    driver.world_mut(0).unwrap().apply_impulse(
        ids[0],
        Vec3::new(0.0, 0.0, 5000.0),
        Vec3::ZERO,
        false,
    );

    driver.advance_all(1.0 / 20.0);

    let world = driver.world_mut(0).unwrap();
    assert_eq!(world.invalid_state_count(), 1);
    let recovered: Vec<_> = world
        .drain_events()
        .into_iter()
        .filter(|event| event.kind == event_kind::RECOVERED)
        .collect();
    assert_eq!(recovered.len(), 1);
    assert_eq!(recovered[0].node_a, 1, "le corps fautif");
    let reports = driver.collect_reports();
    for state in &reports.states {
        assert!(
            state.flags & body_state_flags::SLEEPING != 0,
            "node {} endormi",
            state.node
        );
    }
    let racine = reports.states.iter().find(|state| state.node == 0).unwrap();
    assert!(
        racine.position[2].abs() < 1e-3,
        "le corps racine revient à sa pose valide : z = {}",
        racine.position[2]
    );
}

#[test]
fn adr151_la_masse_de_l_assembly_se_repartit_au_prorata_des_densites() {
    let masses = |bodies: &[AssemblyBody], assembly_mass: f32| -> Option<Vec<f32>> {
        let mut driver = pilote(128.0, 2048);
        let ids = driver.create_assembly_bodies(
            0,
            ASSEMBLY,
            spawn([0.0, 64.0, 0.0], Quat::IDENTITY),
            bodies,
            &[],
            CollisionGroups::ALL,
            assembly_mass,
            false,
        )?;
        let world = driver.world_mut(0).unwrap();
        Some(ids.iter().map(|id| world.body_mass(*id).unwrap()).collect())
    };
    let deux = [corps(0, Vec3::ZERO), corps(1, Vec3::new(3.0, 0.0, 0.0))];

    // Deux boîtes égales : 100 kg pour l'assembly, 50 chacune.
    let egales = masses(&deux, 100.0).unwrap();
    assert!(
        (egales[0] - 50.0).abs() < 1e-3 && (egales[1] - 50.0).abs() < 1e-3,
        "{egales:?}"
    );

    // Une masse déclarée de 30 kg sur le second : le reste au premier.
    let mut declaree = deux.clone();
    declaree[1].desc.mass = 30.0;
    let partagees = masses(&declaree, 100.0).unwrap();
    assert!((partagees[0] - 70.0).abs() < 1e-3 && (partagees[1] - 30.0).abs() < 1e-3);

    // Des masses déclarées qui atteignent celle de l'assembly : refusée, rien ne reste.
    declaree[1].desc.mass = 100.0;
    assert!(masses(&declaree, 100.0).is_none());
}

#[test]
fn adr151_une_forme_refusee_n_en_laisse_aucun() {
    let mut driver = pilote(128.0, 2048);
    let mut fautif = corps(1, Vec3::new(3.0, 0.0, 0.0));
    fautif.colliders[0].shape = Shape::ConvexHull {
        points: vec![[0.0; 3], [1.0, 0.0, 0.0]],
    };
    let crees = creer(
        &mut driver,
        ASSEMBLY,
        spawn([0.0, 64.0, 0.0], Quat::IDENTITY),
        &[corps(0, Vec3::ZERO), fautif],
        false,
    );

    assert!(crees.is_none());
    assert_eq!(driver.world_mut(0).unwrap().body_count(), 0);
    assert!(driver.assembly_bodies(ASSEMBLY).is_none());
}

/// Un joint `Fixed` entre le corps `a` et le corps `b` posé en `b_at` dans l'assembly, ancré à
/// l'origine du repère de `a`.
fn soudure(a: usize, b: usize, b_at: Vec3) -> AssemblyJoint {
    AssemblyJoint {
        index: 4,
        body_a: a,
        body_b: b,
        spec: JointSpec {
            kind: JointKind::Fixed,
            frame_a: (Vec3::ZERO, Quat::IDENTITY),
            frame_b: (-b_at, Quat::IDENTITY),
            limits: None,
            motor: None,
            friction: None,
            breaking: BreakThresholds::NONE,
            axes: [AxisMode::Locked; 6],
            max_length: 0.0,
            rest_length: 0.0,
            stiffness: 0.0,
            damping: 0.0,
            collide: false,
            jam: None,
        },
    }
}

/// Une assembly dont la racine est statique et le second corps pend à deux blocs, sous la
/// pesanteur, reliés par `joints`.
fn pendue(driver: &mut SimDriver, joints: &[AssemblyJoint]) -> Option<Vec<BodyId>> {
    driver.apply_dimension_env(0, Vec3::new(0.0, -9.81, 0.0), Vec3::ZERO, None);
    let mut racine = corps(0, Vec3::ZERO);
    racine.kind = BodyKind::Static;
    racine.desc = BodyDesc::undeclared(0);
    driver.create_assembly_bodies(
        0,
        ASSEMBLY,
        spawn([0.0, 64.0, 0.0], Quat::IDENTITY),
        &[racine, corps(1, Vec3::new(2.0, 0.0, 0.0))],
        joints,
        CollisionGroups::ALL,
        0.0,
        false,
    )
}

#[test]
fn adr151_les_joints_relient_les_corps_de_l_assembly() {
    // Soudé à la racine statique, le second corps reste en place ; sans joint, il tombe.
    let hauteur = |joints: &[AssemblyJoint]| {
        let mut driver = pilote(128.0, 2048);
        let ids = pendue(&mut driver, joints).expect("assembly valide");
        for _ in 0..20 {
            driver.advance_all(1.0 / 20.0);
        }
        let counts = driver.world_mut(0).unwrap().joint_counts();
        (position(&mut driver, ids[1]).y, counts.active)
    };
    let (tenu, actifs) = hauteur(&[soudure(0, 1, Vec3::new(2.0, 0.0, 0.0))]);
    assert_eq!(actifs, 1);
    let (libre, aucun) = hauteur(&[]);
    assert_eq!(aucun, 0);
    let depart = position_initiale();
    assert!((tenu - depart).abs() < 0.01, "soudé : {tenu}");
    assert!(libre < depart - 1.0, "libre, il tombe : {libre}");
}

/// La hauteur de spawn des assemblies de ces tests, dans le repère local du monde.
fn position_initiale() -> f32 {
    let mut driver = pilote(128.0, 2048);
    let ids = pendue(&mut driver, &[]).expect("assembly valide");
    position(&mut driver, ids[1]).y
}

#[test]
fn adr151_un_joint_refuse_n_en_laisse_aucun() {
    // Un joint vers un corps hors de l'assembly : ni corps ni joint ne restent.
    let mut driver = pilote(128.0, 2048);
    let joints = [
        soudure(0, 1, Vec3::new(2.0, 0.0, 0.0)),
        soudure(0, 5, Vec3::new(2.0, 0.0, 0.0)),
    ];
    assert!(pendue(&mut driver, &joints).is_none());
    let world = driver.world_mut(0).unwrap();
    assert_eq!(world.body_count(), 0);
    assert_eq!(world.joint_counts().active, 0);
    assert!(driver.assembly_bodies(ASSEMBLY).is_none());
}

#[test]
fn adr151_retirer_l_assembly_retire_ses_joints() {
    let mut driver = pilote(128.0, 2048);
    pendue(&mut driver, &[soudure(0, 1, Vec3::new(2.0, 0.0, 0.0))]).unwrap();
    assert!(driver.apply_remove_assembly(ASSEMBLY));
    assert_eq!(driver.world_mut(0).unwrap().joint_counts().active, 0);
}

/// Une charnière grippable de la racine au second corps : plage [−1, 1], grippage à moitié à
/// 0,1 bloc.
fn charniere(b_at: Vec3) -> AssemblyJoint {
    let mut joint = soudure(0, 1, b_at);
    joint.spec.kind = JointKind::Revolute;
    joint.spec.limits = Some([-1.0, 1.0]);
    joint.spec.jam = Some(Jam {
        curve: vec![[0.1, 0.5], [0.2, 0.0]],
        friction: 0.0,
    });
    joint
}

#[test]
fn adr151_jam_joint_et_break_joint_visent_un_joint_par_son_rang() {
    let mut driver = pilote(128.0, 2048);
    pendue(&mut driver, &[charniere(Vec3::new(2.0, 0.0, 0.0))]).unwrap();
    driver.advance_all(1.0 / 20.0);
    let etat = |driver: &mut SimDriver| driver.collect_reports().joints;
    let libre = etat(&mut driver);
    assert_eq!(libre.len(), 1, "un état par joint d'assembly éveillée");
    assert_eq!(
        (libre[0].handle, libre[0].index, libre[0].flags),
        (ASSEMBLY, 4, 0)
    );
    // Le second corps, une boîte d'une tonne, pend à deux blocs de l'ancrage : la force porte son
    // poids, le couple ce poids au bout d'un bras de deux blocs.
    let poids = 1000.0 * 9.81;
    assert!(
        (libre[0].force - poids).abs() < 0.05 * poids,
        "force {}",
        libre[0].force
    );
    assert!(
        (libre[0].torque - 2.0 * poids).abs() < 0.05 * 2.0 * poids,
        "couple {}",
        libre[0].torque
    );

    assert!(driver.apply_jam_joint(ASSEMBLY, 4, 0.1));
    let grippe = etat(&mut driver);
    assert_eq!(grippe[0].flags, joint_state_flags::JAMMED);
    assert!((grippe[0].fraction - 0.5).abs() < 1e-6);
    assert!(
        !driver.apply_jam_joint(ASSEMBLY, 5, 0.1),
        "aucun joint de ce rang"
    );
    let autre = Handle {
        index: 8,
        generation: 1,
    };
    assert!(
        !driver.apply_jam_joint(autre, 4, 0.1),
        "aucune assembly de ce handle"
    );

    assert!(driver.apply_break_joint(ASSEMBLY, 4));
    assert!(!driver.apply_break_joint(ASSEMBLY, 4), "déjà rompu");
    let rompu = etat(&mut driver);
    assert_eq!(
        rompu[0].flags,
        joint_state_flags::BROKEN | joint_state_flags::JAMMED
    );
    let events = driver.drain_events();
    assert!(events
        .iter()
        .any(|event| event.kind == event_kind::JOINT_BROKEN && event.data == 4));
}

#[test]
fn adr151_jam_joint_ne_vise_que_le_joint_de_l_assembly_nommee() {
    // Deux assemblies, chacune une charnière de même rang : gripper l'une laisse l'autre libre.
    let mut driver = pilote(128.0, 2048);
    pendue(&mut driver, &[charniere(Vec3::new(2.0, 0.0, 0.0))]).unwrap();
    let voisine = Handle {
        index: 8,
        generation: 1,
    };
    let mut racine = corps(0, Vec3::ZERO);
    racine.kind = BodyKind::Static;
    racine.desc = BodyDesc::undeclared(0);
    driver
        .create_assembly_bodies(
            0,
            voisine,
            spawn([20.0, 64.0, 0.0], Quat::IDENTITY),
            &[racine, corps(1, Vec3::new(2.0, 0.0, 0.0))],
            &[charniere(Vec3::new(2.0, 0.0, 0.0))],
            CollisionGroups::ALL,
            0.0,
            false,
        )
        .unwrap();
    driver.advance_all(1.0 / 20.0);

    assert!(driver.apply_jam_joint(voisine, 4, 0.1));
    let etats = driver.collect_reports().joints;
    let flags_de = |handle| {
        etats
            .iter()
            .find(|etat| etat.handle == handle)
            .map(|etat| etat.flags)
    };
    assert_eq!(flags_de(voisine), Some(joint_state_flags::JAMMED));
    assert_eq!(flags_de(ASSEMBLY), Some(0), "l'autre assembly reste libre");
}

#[test]
fn adr151_une_assembly_endormie_ne_rapporte_pas_ses_joints() {
    // Sans gravité, la soudure tient le second corps immobile : l'assembly s'endort, et ses
    // joints ne sont plus rapportés ; une impulsion la réveille.
    let mut driver = pilote(128.0, 2048);
    let ids = driver
        .create_assembly_bodies(
            0,
            ASSEMBLY,
            spawn([0.0, 64.0, 0.0], Quat::IDENTITY),
            &[corps(0, Vec3::ZERO), corps(1, Vec3::new(2.0, 0.0, 0.0))],
            &[soudure(0, 1, Vec3::new(2.0, 0.0, 0.0))],
            CollisionGroups::ALL,
            0.0,
            false,
        )
        .unwrap();
    driver.advance_all(1.0 / 20.0);
    assert_eq!(driver.collect_reports().joints.len(), 1, "éveillée");
    for _ in 0..200 {
        driver.advance_all(1.0 / 20.0);
    }
    assert!(
        dort(&mut driver, ids[0]) && dort(&mut driver, ids[1]),
        "endormie"
    );
    assert!(driver.collect_reports().joints.is_empty(), "endormie, rien");
}
