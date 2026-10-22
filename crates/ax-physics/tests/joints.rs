//! T-330 à T-333 : les joints de C-34 au banc de physique (ADR-151 D10).
//!
//! Un corps fixe sert d'ancre ; un corps dynamique lui est relié par un joint dont le repère est
//! à l'origine de l'ancre. Les tolérances de T-330 sont déclarées ici : un joint tient ses axes
//! bloqués à [`TOLERANCE_LINEAIRE`] et [`TOLERANCE_ANGULAIRE`] près.

use ax_math::{Quat, Vec3};
use ax_model::dm::handle::Handle;
use ax_model::dm::physics::event_kind;
use ax_physics::{
    AxisMode, BodyCollider, BodyId, BodyKind, BreakThresholds, ContactMaterial, JointId, JointKind,
    JointMotor, JointReport, JointSpec, PhysicsConfig, PhysicsWorld, Shape,
};

/// Écart toléré sur un axe linéaire bloqué, en blocs.
const TOLERANCE_LINEAIRE: f32 = 0.01;
/// Écart toléré sur un axe angulaire bloqué, en radians.
const TOLERANCE_ANGULAIRE: f32 = 0.01;
/// Un tick de Minecraft.
const TICK: f32 = 1.0 / 20.0;
const ASSEMBLY: Handle = Handle {
    index: 1,
    generation: 1,
};

fn monde(gravite: bool) -> PhysicsWorld {
    let mut world = PhysicsWorld::new(PhysicsConfig::new(1.0 / 60.0, 4).expect("configuration"));
    if !gravite {
        world.set_gravity(Vec3::ZERO);
    }
    world
}

fn ancre(world: &mut PhysicsWorld) -> BodyId {
    world
        .add_body(
            BodyKind::Static,
            Vec3::ZERO,
            Quat::IDENTITY,
            Shape::Ball { radius: 0.05 },
        )
        .unwrap()
}

/// Une boule de 0,25 bloc de rayon et de densité 1000 (environ 65 kg), à `position`.
fn boule(world: &mut PhysicsWorld, position: Vec3) -> BodyId {
    world
        .add_assembly(
            BodyKind::Dynamic,
            position,
            Quat::IDENTITY,
            &[BodyCollider {
                shape: Shape::Ball { radius: 0.25 },
                density: 1000.0,
                material: ContactMaterial::default(),
                translation: Vec3::ZERO,
                rotation: Quat::IDENTITY,
            }],
        )
        .unwrap()
}

/// Un joint dont le repère est à l'origine de l'ancre, son axe X sur `axe`, et vu du corps `b`
/// posé en `position_b`.
fn joint(kind: JointKind, axe: Vec3, position_b: Vec3) -> JointSpec {
    let rotation = Quat::from_rotation_arc(Vec3::X, axe.normalize());
    JointSpec {
        kind,
        frame_a: (Vec3::ZERO, rotation),
        frame_b: (-position_b, rotation),
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
    }
}

fn position(world: &PhysicsWorld, body: BodyId) -> Vec3 {
    world.pose(body).unwrap().translation
}

fn rotation(world: &PhysicsWorld, body: BodyId) -> Quat {
    world.pose(body).unwrap().rotation
}

/// Avance de `ticks` ticks et rend, pour chaque tick, ce que `mesure` lit.
fn derouler<T>(
    world: &mut PhysicsWorld,
    ticks: usize,
    mut mesure: impl FnMut(&PhysicsWorld) -> T,
) -> Vec<T> {
    (0..ticks)
        .map(|_| {
            world.advance(TICK);
            mesure(world)
        })
        .collect()
}

/// L'angle d'un pendule de `revolute` autour de Z : celui de la rotation du corps autour de Z.
fn angle_z(q: Quat) -> f32 {
    let (axis, angle) = q.to_axis_angle();
    if axis.z < 0.0 {
        -angle
    } else {
        angle
    }
}

// --- T-330 : chaque type tient ses degrés de liberté ---------------------------------------

#[test]
fn t330_fixed_tient_ses_six_axes() {
    let mut world = monde(true);
    let a = ancre(&mut world);
    let at = Vec3::new(1.0, 0.0, 0.0);
    let b = boule(&mut world, at);
    world
        .add_joint(ASSEMBLY, 0, a, b, &joint(JointKind::Fixed, Vec3::X, at))
        .unwrap();

    let ecarts = derouler(&mut world, 60, |w| {
        (
            (position(w, b) - at).length(),
            rotation(w, b).angle_between(Quat::IDENTITY),
        )
    });
    let (lin, ang) = ecarts
        .iter()
        .fold((0.0f32, 0.0f32), |(l, g), (x, y)| (l.max(*x), g.max(*y)));
    assert!(lin < TOLERANCE_LINEAIRE, "écart linéaire {lin}");
    assert!(ang < TOLERANCE_ANGULAIRE, "écart angulaire {ang}");
}

#[test]
fn t330_revolute_ne_tourne_qu_autour_de_son_axe() {
    // Un pendule autour de Z : il tombe en tournant autour de Z, à un bloc de la charnière, sans
    // jamais quitter le plan z = 0 ni tourner autour d'un autre axe.
    let mut world = monde(true);
    let a = ancre(&mut world);
    let at = Vec3::new(1.0, 0.0, 0.0);
    let b = boule(&mut world, at);
    world
        .add_joint(ASSEMBLY, 0, a, b, &joint(JointKind::Revolute, Vec3::Z, at))
        .unwrap();

    let releves = derouler(&mut world, 40, |w| (position(w, b), rotation(w, b)));
    let (fin, _) = releves[releves.len() - 1];
    assert!(fin.y < -0.5, "le pendule est tombé : {fin}");
    for (p, q) in &releves {
        assert!(
            (p.length() - 1.0).abs() < TOLERANCE_LINEAIRE,
            "distance à la charnière {}",
            p.length()
        );
        assert!(p.z.abs() < TOLERANCE_LINEAIRE, "hors du plan : z = {}", p.z);
        let (axis, angle) = q.to_axis_angle();
        assert!(
            angle < TOLERANCE_ANGULAIRE || axis.z.abs() > 1.0 - TOLERANCE_ANGULAIRE,
            "axe {axis}"
        );
    }
}

#[test]
fn t330_prismatic_ne_glisse_que_le_long_de_son_axe() {
    // Une glissière verticale : la boule descend le long de Y, sans dériver ni tourner.
    let mut world = monde(true);
    let a = ancre(&mut world);
    let at = Vec3::new(0.0, -1.0, 0.0);
    let b = boule(&mut world, at);
    world
        .add_joint(ASSEMBLY, 0, a, b, &joint(JointKind::Prismatic, Vec3::Y, at))
        .unwrap();
    world.apply_impulse(b, Vec3::new(30.0, 0.0, 30.0), Vec3::ZERO, false);

    let releves = derouler(&mut world, 30, |w| (position(w, b), rotation(w, b)));
    assert!(releves[releves.len() - 1].0.y < -2.0, "elle glisse");
    for (p, q) in &releves {
        assert!(
            p.x.abs() < TOLERANCE_LINEAIRE && p.z.abs() < TOLERANCE_LINEAIRE,
            "dérive {p}"
        );
        assert!(
            q.angle_between(Quat::IDENTITY) < TOLERANCE_ANGULAIRE,
            "rotation"
        );
    }
}

#[test]
fn t330_spherical_garde_sa_distance_a_l_ancrage() {
    let mut world = monde(true);
    let a = ancre(&mut world);
    let at = Vec3::new(1.0, 0.0, 0.0);
    let b = boule(&mut world, at);
    world
        .add_joint(ASSEMBLY, 0, a, b, &joint(JointKind::Spherical, Vec3::X, at))
        .unwrap();
    world.apply_impulse(b, Vec3::new(0.0, 0.0, 120.0), Vec3::ZERO, false);

    let distances = derouler(&mut world, 60, |w| position(w, b).length());
    let pire = distances.iter().fold(0.0f32, |m, d| m.max((d - 1.0).abs()));
    assert!(pire < TOLERANCE_LINEAIRE, "écart à l'ancrage {pire}");
}

#[test]
fn t330_generic_libere_ses_axes_free_et_borne_ses_axes_limited() {
    // Y libre, X limité à ±0,2 ; tout le reste bloqué : la boule tombe le long de Y, poussée en X
    // elle s'arrête à 0,2, et ne bouge pas en z.
    let mut world = monde(true);
    let a = ancre(&mut world);
    let at = Vec3::new(0.0, -1.0, 0.0);
    let b = boule(&mut world, at);
    let mut spec = joint(JointKind::Generic, Vec3::X, at);
    spec.axes[0] = AxisMode::Limited([-0.2, 0.2]);
    spec.axes[1] = AxisMode::Free;
    world.add_joint(ASSEMBLY, 0, a, b, &spec).unwrap();
    world.apply_impulse(b, Vec3::new(200.0, 0.0, 200.0), Vec3::ZERO, false);

    let releves = derouler(&mut world, 30, |w| position(w, b));
    let fin = releves[releves.len() - 1];
    assert!(fin.y < -2.0, "Y libre : {fin}");
    for p in &releves {
        assert!(p.x <= 0.2 + TOLERANCE_LINEAIRE, "X limité : {p}");
        assert!(p.z.abs() < TOLERANCE_LINEAIRE, "Z bloqué : {p}");
    }
    assert!(
        (fin.x - 0.2).abs() < TOLERANCE_LINEAIRE,
        "poussée en butée : {fin}"
    );
}

#[test]
fn t330_rope_ne_depasse_jamais_sa_longueur() {
    // La boule part à 0,5 bloc sous l'ancre, la corde mesure 2 : elle tombe, la corde se tend,
    // et la distance ne dépasse jamais 2.
    let mut world = monde(true);
    let a = ancre(&mut world);
    let at = Vec3::new(0.0, -0.5, 0.0);
    let b = boule(&mut world, at);
    let mut spec = joint(JointKind::Rope, Vec3::X, at);
    spec.frame_b = (Vec3::ZERO, Quat::IDENTITY);
    spec.max_length = 2.0;
    world.add_joint(ASSEMBLY, 0, a, b, &spec).unwrap();

    let distances = derouler(&mut world, 60, |w| position(w, b).length());
    let plus_longue = distances.iter().fold(0.0f32, |m, d| m.max(*d));
    assert!(
        plus_longue <= 2.0 + TOLERANCE_LINEAIRE,
        "la corde s'est allongée : {plus_longue}"
    );
    assert!(plus_longue > 1.9, "la corde s'est tendue : {plus_longue}");
}

#[test]
fn t330_spring_oscille_vers_sa_longueur_de_repos_a_sa_frequence_propre() {
    // Sans gravité, la boule tirée à 1,5 bloc d'une longueur de repos de 1 : elle oscille à
    // ω = √(k/m), et l'amortissement réduit l'amplitude.
    let mut world = monde(false);
    let a = ancre(&mut world);
    let at = Vec3::new(1.5, 0.0, 0.0);
    let b = boule(&mut world, at);
    let masse = world.body_mass(b).unwrap();
    let raideur = 400.0;
    let mut spec = joint(JointKind::Spring, Vec3::X, at);
    spec.frame_b = (Vec3::ZERO, Quat::IDENTITY);
    spec.rest_length = 1.0;
    spec.stiffness = raideur;
    spec.damping = 5.0;
    world.add_joint(ASSEMBLY, 0, a, b, &spec).unwrap();

    let ticks = 200;
    let x = derouler(&mut world, ticks, |w| position(w, b).x - 1.0);
    // Période mesurée : entre deux passages par zéro dans le même sens.
    let montees: Vec<usize> = (1..ticks)
        .filter(|&i| x[i - 1] < 0.0 && x[i] >= 0.0)
        .collect();
    assert!(montees.len() >= 2, "oscille : {montees:?}");
    let periode = (montees[1] - montees[0]) as f32 * TICK;
    let attendue = 2.0 * core::f32::consts::PI * (masse / raideur).sqrt();
    assert!(
        (periode - attendue).abs() <= 0.1 * attendue + TICK,
        "période {periode} s, {attendue} s attendue"
    );
    let premiere = x[..montees[0]].iter().fold(0.0f32, |m, v| m.max(v.abs()));
    let derniere = x[montees[montees.len() - 1]..]
        .iter()
        .fold(0.0f32, |m, v| m.max(v.abs()));
    assert!(derniere < premiere, "amorti : {premiere} puis {derniere}");
}

// --- T-331 : limites, moteur, friction -----------------------------------------------------

#[test]
fn t331_un_revolute_reste_dans_ses_limites() {
    let mut world = monde(true);
    let a = ancre(&mut world);
    let at = Vec3::new(1.0, 0.0, 0.0);
    let b = boule(&mut world, at);
    let mut spec = joint(JointKind::Revolute, Vec3::Z, at);
    spec.limits = Some([-0.5, 0.5]);
    world.add_joint(ASSEMBLY, 0, a, b, &spec).unwrap();

    let angles = derouler(&mut world, 60, |w| angle_z(rotation(w, b)));
    let plus_bas = angles.iter().fold(0.0f32, |m, a| m.min(*a));
    assert!(
        plus_bas >= -0.5 - TOLERANCE_ANGULAIRE,
        "au-delà de la limite : {plus_bas}"
    );
    assert!(
        plus_bas < -0.45,
        "le pendule est allé jusqu'à sa butée : {plus_bas}"
    );
}

#[test]
fn t331_le_moteur_atteint_sa_vitesse_sans_depasser_sa_force() {
    // Sans gravité : un moteur sans plafond atteint 2 rad/s ; plafonné bas, il n'y arrive pas
    // en un tick.
    let vitesse_apres = |max_force: f32, ticks: usize| {
        let mut world = monde(false);
        let a = ancre(&mut world);
        let at = Vec3::new(1.0, 0.0, 0.0);
        let b = boule(&mut world, at);
        let mut spec = joint(JointKind::Revolute, Vec3::Z, at);
        spec.motor = Some(JointMotor::Velocity {
            speed: 2.0,
            max_force,
        });
        world.add_joint(ASSEMBLY, 0, a, b, &spec).unwrap();
        derouler(&mut world, ticks, |w| w.angular_velocity(b).unwrap().z)[ticks - 1]
    };
    let libre = vitesse_apres(f32::INFINITY, 10);
    assert!((libre - 2.0).abs() < 0.02, "vitesse atteinte : {libre}");
    let bridee = vitesse_apres(1.0, 1);
    assert!(bridee < 0.1, "couple plafonné à 1 N·m : {bridee}");
}

#[test]
fn t331_le_servo_rejoint_sa_position_cible() {
    let mut world = monde(false);
    let a = ancre(&mut world);
    let at = Vec3::new(1.0, 0.0, 0.0);
    let b = boule(&mut world, at);
    let mut spec = joint(JointKind::Revolute, Vec3::Z, at);
    spec.motor = Some(JointMotor::Position {
        target: 1.0,
        speed: 0.0,
        stiffness: 2000.0,
        damping: 400.0,
        max_force: f32::INFINITY,
    });
    world.add_joint(ASSEMBLY, 0, a, b, &spec).unwrap();

    let angles = derouler(&mut world, 100, |w| angle_z(rotation(w, b)));
    let fin = angles[angles.len() - 1];
    assert!((fin - 1.0).abs() < 0.02, "angle final {fin}");
}

#[test]
fn t331_la_friction_retient_une_charge_plus_faible_et_cede_a_une_plus_forte() {
    // Un pendule horizontal : la pesanteur exerce m·g·1 sur la charnière. Une friction du double
    // le retient ; de la moitié, il tombe.
    let chute = |rapport: f32| {
        let mut world = monde(true);
        let a = ancre(&mut world);
        let at = Vec3::new(1.0, 0.0, 0.0);
        let b = boule(&mut world, at);
        let couple = world.body_mass(b).unwrap() * 9.81;
        let mut spec = joint(JointKind::Revolute, Vec3::Z, at);
        spec.friction = Some(rapport * couple);
        world.add_joint(ASSEMBLY, 0, a, b, &spec).unwrap();
        -derouler(&mut world, 20, |w| position(w, b).y)[19]
    };
    let retenue = chute(2.0);
    assert!(
        retenue < 0.01,
        "retenu par sa friction : chute de {retenue}"
    );
    let lachee = chute(0.5);
    assert!(lachee > 0.2, "la friction cède : chute de {lachee}");
}

#[test]
fn t331_un_moteur_tient_une_charge_en_porte_a_faux() {
    // ADR-152 : la boule pèse sur la charnière par un bras d'un bloc. Un moteur à vitesse nulle la
    // tient ; un servo la porte à la flèche de sa raideur, m·g/k.
    let chute = |moteur: JointMotor| {
        let mut world = monde(true);
        let a = ancre(&mut world);
        let at = Vec3::new(1.0, 0.0, 0.0);
        let b = boule(&mut world, at);
        let poids = world.body_mass(b).unwrap() * 9.81;
        let mut spec = joint(JointKind::Revolute, Vec3::Z, at);
        spec.motor = Some(moteur);
        world.add_joint(ASSEMBLY, 0, a, b, &spec).unwrap();
        (-derouler(&mut world, 20, |w| position(w, b).y)[19], poids)
    };
    let (arret, _) = chute(JointMotor::Velocity {
        speed: 0.0,
        max_force: f32::INFINITY,
    });
    assert!(arret < 0.01, "tenue par son moteur : chute de {arret}");
    let raideur = 1.0e5;
    let (fleche, poids) = chute(JointMotor::Position {
        target: 0.0,
        speed: 0.0,
        stiffness: raideur,
        damping: 1.0e4,
        max_force: f32::INFINITY,
    });
    let attendue = poids / raideur;
    assert!(
        (fleche - attendue).abs() < 0.05 * attendue,
        "flèche du servo {fleche}, attendue {attendue}"
    );
}

// --- T-332 : rupture -------------------------------------------------------------------------

/// Une boule suspendue par un joint `Fixed` : sa réaction vaut m·g en force et m·g·1 en couple.
fn suspendue(
    seuils: impl FnOnce(f32) -> BreakThresholds,
) -> (PhysicsWorld, BodyId, ax_physics::JointId) {
    let mut world = monde(true);
    let a = ancre(&mut world);
    let at = Vec3::new(1.0, 0.0, 0.0);
    let b = boule(&mut world, at);
    let poids = world.body_mass(b).unwrap() * 9.81;
    let mut spec = joint(JointKind::Fixed, Vec3::X, at);
    spec.breaking = seuils(poids);
    let id = world.add_joint(ASSEMBLY, 3, a, b, &spec).unwrap();
    (world, b, id)
}

fn ruptures(world: &mut PhysicsWorld) -> Vec<u32> {
    world
        .drain_events()
        .into_iter()
        .filter(|event| event.kind == event_kind::JOINT_BROKEN)
        .map(|event| event.data)
        .collect()
}

#[test]
fn t332_la_force_rompt_au_dela_du_seuil_et_pas_en_deca() {
    // Le poids m·g est la réaction : 0,9 fois le seuil ne rompt pas ; 1,1 fois rompt, une fois.
    let (mut sous, _, id) = suspendue(|poids| BreakThresholds {
        force: poids / 0.9,
        ..BreakThresholds::NONE
    });
    derouler(&mut sous, 40, |_| ());
    assert!(!sous.joint_report(id).unwrap().broken, "0,9 fois le seuil");
    assert!(ruptures(&mut sous).is_empty());

    let (mut sur, b, id) = suspendue(|poids| BreakThresholds {
        force: poids / 1.1,
        ..BreakThresholds::NONE
    });
    derouler(&mut sur, 40, |_| ());
    assert!(sur.joint_report(id).unwrap().broken, "1,1 fois le seuil");
    assert_eq!(
        ruptures(&mut sur),
        vec![3],
        "un seul JOINT_BROKEN, au rang du joint"
    );
    assert!(position(&sur, b).y < -1.0, "libre, la boule tombe");
    assert_eq!(sur.joint_counts().broken, 1);
    assert_eq!(sur.joint_counts().active, 0);
}

#[test]
fn t332_le_couple_rompt_au_dela_du_seuil_et_pas_en_deca() {
    // Le bras d'un bloc : le couple de réaction vaut m·g·1.
    let (mut sous, _, id) = suspendue(|poids| BreakThresholds {
        torque: poids / 0.9,
        ..BreakThresholds::NONE
    });
    derouler(&mut sous, 40, |_| ());
    assert!(!sous.joint_report(id).unwrap().broken, "0,9 fois le seuil");

    let (mut sur, _, id) = suspendue(|poids| BreakThresholds {
        torque: poids / 1.1,
        ..BreakThresholds::NONE
    });
    derouler(&mut sur, 40, |_| ());
    assert!(sur.joint_report(id).unwrap().broken, "1,1 fois le seuil");
}

#[test]
fn t332_l_energie_d_un_choc_rompt_au_dela_du_seuil_et_pas_en_deca() {
    // Sans gravité, une boule tenue par un joint Fixed reçoit une impulsion J : le joint absorbe
    // l'énergie cinétique J²/(2m) pour l'arrêter.
    let energie_absorbee = |seuil_relatif: f32| {
        let mut world = monde(false);
        let a = ancre(&mut world);
        let at = Vec3::new(1.0, 0.0, 0.0);
        let b = boule(&mut world, at);
        let masse = world.body_mass(b).unwrap();
        let impulsion = 20.0 * masse;
        let cinetique = impulsion * impulsion / (2.0 * masse);
        let mut spec = joint(JointKind::Fixed, Vec3::X, at);
        spec.breaking = BreakThresholds {
            energy: cinetique * seuil_relatif,
            ..BreakThresholds::NONE
        };
        let id = world.add_joint(ASSEMBLY, 0, a, b, &spec).unwrap();
        world.advance(TICK);
        world.apply_impulse(b, Vec3::new(0.0, impulsion, 0.0), Vec3::ZERO, false);
        world.advance(TICK);
        world.joint_report(id).unwrap().broken
    };
    assert!(!energie_absorbee(1.0 / 0.9), "0,9 fois le seuil");
    assert!(energie_absorbee(1.0 / 1.1), "1,1 fois le seuil");
}

// --- T-332 : ce que l'étape 5 lit (ADR-152) --------------------------------------------------

/// `lu` vaut `attendu` à 1 % près.
fn a_un_pourcent(lu: f32, attendu: f32, quoi: &str) {
    assert!(
        (lu - attendu).abs() <= 0.01 * attendu,
        "{quoi} : lu {lu}, attendu {attendu}"
    );
}

/// Ce que l'étape 5 a lu de `id` après `ticks` ticks.
fn apres(world: &mut PhysicsWorld, id: JointId, ticks: usize) -> JointReport {
    derouler(world, ticks, |_| ());
    world.joint_report(id).unwrap()
}

#[test]
fn t332_un_porte_a_faux_transmet_son_poids_et_son_moment() {
    // Le joint Fixed porte la boule à un bloc : force m·g, couple m·g·1 autour de son ancrage.
    let (mut world, b, id) = suspendue(|_| BreakThresholds::NONE);
    let poids = world.body_mass(b).unwrap() * 9.81;
    let lu = apres(&mut world, id, 20);
    a_un_pourcent(lu.force, poids, "force");
    a_un_pourcent(lu.torque, poids, "couple");
}

#[test]
fn t332_une_chaine_transmet_le_poids_de_ce_qu_elle_porte() {
    // Deux boules en porte-à-faux, l'une au bout de l'autre : le premier joint porte les deux, à
    // un et deux blocs de son ancrage ; le second, la dernière, à un bloc du sien.
    let mut world = monde(true);
    let ancrage = ancre(&mut world);
    let premiere = boule(&mut world, Vec3::new(1.0, 0.0, 0.0));
    let seconde = boule(&mut world, Vec3::new(2.0, 0.0, 0.0));
    let poids = world.body_mass(premiere).unwrap() * 9.81;
    let haut = world
        .add_joint(
            ASSEMBLY,
            0,
            ancrage,
            premiere,
            &joint(JointKind::Fixed, Vec3::X, Vec3::new(1.0, 0.0, 0.0)),
        )
        .unwrap();
    let mut bas = joint(JointKind::Fixed, Vec3::X, Vec3::new(1.0, 0.0, 0.0));
    bas.frame_a = (Vec3::ZERO, Quat::IDENTITY);
    let bas = world
        .add_joint(ASSEMBLY, 1, premiere, seconde, &bas)
        .unwrap();
    derouler(&mut world, 20, |_| ());
    let (haut, bas) = (
        world.joint_report(haut).unwrap(),
        world.joint_report(bas).unwrap(),
    );
    a_un_pourcent(haut.force, 2.0 * poids, "force du premier");
    a_un_pourcent(haut.torque, 3.0 * poids, "couple du premier");
    a_un_pourcent(bas.force, poids, "force du second");
    a_un_pourcent(bas.torque, poids, "couple du second");
}

#[test]
fn t332_une_corde_transmet_sa_tension_et_aucun_couple() {
    let mut world = monde(true);
    let a = ancre(&mut world);
    let b = boule(&mut world, Vec3::new(0.0, -1.0, 0.0));
    let poids = world.body_mass(b).unwrap() * 9.81;
    let mut spec = joint(JointKind::Rope, Vec3::X, Vec3::new(0.0, -1.0, 0.0));
    spec.frame_b = (Vec3::ZERO, Quat::IDENTITY);
    spec.max_length = 1.0;
    let id = world.add_joint(ASSEMBLY, 0, a, b, &spec).unwrap();
    let lu = apres(&mut world, id, 20);
    a_un_pourcent(lu.force, poids, "tension");
    assert!(
        lu.torque < 0.01 * poids,
        "couple d'une corde : {}",
        lu.torque
    );
}

#[test]
fn t332_une_butee_un_moteur_ou_une_friction_transmettent_le_moment_de_la_charge() {
    // Une charnière horizontale porte la boule à un bloc : son moment passe par sa butée, son
    // moteur ou sa friction, selon ce qui la retient.
    let lu = |reglage: &dyn Fn(&mut JointSpec, f32)| {
        let mut world = monde(true);
        let a = ancre(&mut world);
        let at = Vec3::new(1.0, 0.0, 0.0);
        let b = boule(&mut world, at);
        let poids = world.body_mass(b).unwrap() * 9.81;
        let mut spec = joint(JointKind::Revolute, Vec3::Z, at);
        reglage(&mut spec, poids);
        let id = world.add_joint(ASSEMBLY, 0, a, b, &spec).unwrap();
        (apres(&mut world, id, 60), poids)
    };
    let (butee, poids) = lu(&|spec, _| spec.limits = Some([-0.5, 0.5]));
    a_un_pourcent(butee.torque, poids * 0.5f32.cos(), "couple en butée");
    a_un_pourcent(butee.force, poids, "force en butée");
    let (moteur, poids) = lu(&|spec, _| {
        spec.motor = Some(JointMotor::Velocity {
            speed: 0.0,
            max_force: f32::INFINITY,
        });
    });
    a_un_pourcent(moteur.torque, poids, "couple du moteur");
    let (friction, poids) = lu(&|spec, poids| spec.friction = Some(2.0 * poids));
    a_un_pourcent(friction.torque, poids, "couple de la friction");
}

#[test]
fn t332_un_moteur_ou_une_friction_lineaires_transmettent_le_poids() {
    // Une glissière verticale : la boule pend le long de son axe libre. Son poids passe par son
    // moteur ou par sa friction, pas par ses axes bloqués.
    let lu = |reglage: &dyn Fn(&mut JointSpec, f32)| {
        let mut world = monde(true);
        let a = ancre(&mut world);
        let at = Vec3::new(0.0, -1.0, 0.0);
        let b = boule(&mut world, at);
        let poids = world.body_mass(b).unwrap() * 9.81;
        let mut spec = joint(JointKind::Prismatic, Vec3::Y, at);
        reglage(&mut spec, poids);
        let id = world.add_joint(ASSEMBLY, 0, a, b, &spec).unwrap();
        (apres(&mut world, id, 20), poids)
    };
    let (moteur, poids) = lu(&|spec, _| {
        spec.motor = Some(JointMotor::Velocity {
            speed: 0.0,
            max_force: f32::INFINITY,
        });
    });
    a_un_pourcent(moteur.force, poids, "force du moteur");
    let (friction, poids) = lu(&|spec, poids| spec.friction = Some(2.0 * poids));
    a_un_pourcent(friction.force, poids, "force de la friction");
}

#[test]
fn t332_une_butee_heurtee_absorbe_l_energie_du_choc_et_pas_avant() {
    // Sans gravité, la boule tourne autour de la charnière à 2 rad/s — l'impulsion est donnée au
    // centre de percussion, aucun axe bloqué ne la reçoit — et heurte sa butée à 0,3 rad, qui
    // absorbe toute son énergie, ½·(I + m)·ω².
    let rupture = |seuil_relatif: f32, ticks: usize| {
        let mut world = monde(false);
        let a = ancre(&mut world);
        let at = Vec3::new(1.0, 0.0, 0.0);
        let b = boule(&mut world, at);
        let masse = world.body_mass(b).unwrap();
        let inertie = 0.4 * masse * 0.25 * 0.25;
        let vitesse = 2.0;
        let cinetique = 0.5 * (inertie + masse) * vitesse * vitesse;
        let mut spec = joint(JointKind::Revolute, Vec3::Z, at);
        spec.limits = Some([-0.3, 0.3]);
        spec.breaking = BreakThresholds {
            energy: cinetique * seuil_relatif,
            ..BreakThresholds::NONE
        };
        let id = world.add_joint(ASSEMBLY, 0, a, b, &spec).unwrap();
        let percussion = Vec3::new(inertie / masse, 0.0, 0.0);
        world.apply_impulse(b, Vec3::new(0.0, masse * vitesse, 0.0), percussion, true);
        derouler(&mut world, ticks, |_| ());
        world.joint_report(id).unwrap().broken
    };
    assert!(!rupture(0.05, 2), "rien d'absorbé avant la butée");
    assert!(!rupture(1.0 / 0.9, 10), "0,9 fois le seuil");
    assert!(rupture(1.0 / 1.1, 10), "1,1 fois le seuil");
}

#[test]
fn t332_une_corde_tendue_d_un_coup_absorbe_l_energie_du_choc() {
    // Sans gravité, la boule s'éloigne de l'ancre à 4 blocs/s ; la corde, lâche de 0,5 bloc, se
    // tend et absorbe son énergie, ½·m·v².
    let rupture = |seuil_relatif: f32| {
        let mut world = monde(false);
        let a = ancre(&mut world);
        let b = boule(&mut world, Vec3::new(1.0, 0.0, 0.0));
        let masse = world.body_mass(b).unwrap();
        let cinetique = 0.5 * masse * 16.0;
        let mut spec = joint(JointKind::Rope, Vec3::X, Vec3::new(1.0, 0.0, 0.0));
        spec.frame_b = (Vec3::ZERO, Quat::IDENTITY);
        spec.max_length = 1.5;
        spec.breaking = BreakThresholds {
            energy: cinetique * seuil_relatif,
            ..BreakThresholds::NONE
        };
        let id = world.add_joint(ASSEMBLY, 0, a, b, &spec).unwrap();
        world.apply_impulse(b, Vec3::new(4.0 * masse, 0.0, 0.0), Vec3::ZERO, false);
        derouler(&mut world, 10, |_| ());
        world.joint_report(id).unwrap().broken
    };
    assert!(!rupture(1.0 / 0.9), "0,9 fois le seuil");
    assert!(rupture(1.0 / 1.1), "1,1 fois le seuil");
}

#[test]
fn t332_l_energie_d_un_choc_se_mesure_entre_les_deux_corps() {
    // Sans gravité, deux boules soudées sur la ligne de leurs centres ; la première reçoit une
    // vitesse v : le joint absorbe ce qu'un choc mou entre deux masses égales perd, ¼·m·v².
    let rupture = |seuil_relatif: f32| {
        let mut world = monde(false);
        let a = boule(&mut world, Vec3::ZERO);
        let b = boule(&mut world, Vec3::new(1.0, 0.0, 0.0));
        let masse = world.body_mass(a).unwrap();
        let perdue = 0.25 * masse * 9.0;
        let mut spec = joint(JointKind::Fixed, Vec3::X, Vec3::new(1.0, 0.0, 0.0));
        spec.frame_a = (Vec3::new(0.5, 0.0, 0.0), Quat::IDENTITY);
        spec.frame_b = (Vec3::new(-0.5, 0.0, 0.0), Quat::IDENTITY);
        spec.breaking = BreakThresholds {
            energy: perdue * seuil_relatif,
            ..BreakThresholds::NONE
        };
        let id = world.add_joint(ASSEMBLY, 0, a, b, &spec).unwrap();
        world.apply_impulse(a, Vec3::new(3.0 * masse, 0.0, 0.0), Vec3::ZERO, false);
        derouler(&mut world, 2, |_| ());
        world.joint_report(id).unwrap().broken
    };
    assert!(!rupture(1.0 / 0.9), "0,9 fois le seuil");
    assert!(rupture(1.0 / 1.1), "1,1 fois le seuil");
}

#[test]
fn t332_le_travail_d_un_moteur_n_est_pas_une_energie_absorbee() {
    // Un moteur fait tourner la boule à 2 rad/s contre la pesanteur : il travaille, mais ses axes
    // bloqués n'arrêtent rien — l'énergie absorbée reste nulle.
    let mut world = monde(true);
    let a = ancre(&mut world);
    let at = Vec3::new(1.0, 0.0, 0.0);
    let b = boule(&mut world, at);
    let mut spec = joint(JointKind::Revolute, Vec3::Z, at);
    spec.motor = Some(JointMotor::Velocity {
        speed: 2.0,
        max_force: f32::INFINITY,
    });
    spec.breaking = BreakThresholds {
        energy: 1.0,
        ..BreakThresholds::NONE
    };
    let id = world.add_joint(ASSEMBLY, 0, a, b, &spec).unwrap();
    let lu = apres(&mut world, id, 40);
    assert!(!lu.broken, "énergie lue : {}", lu.energy);
    assert!(lu.torque > 0.0, "le moteur porte la charge");
}

#[test]
fn t332_les_joints_d_une_longue_chaine_publient_leur_effort() {
    // Deux cents boules pendues l'une sous l'autre : assez de joints par couleur pour que rapier
    // d'origine les résolve par sa voie SIMD, qui ne publie pas leur effort (ADR-152). Le soutien
    // descend la chaîne d'un joint environ par itération du solveur : en trois secondes, la moitié
    // haute porte déjà plus d'une boule chacun.
    let mut world = monde(true);
    let mut haut = ancre(&mut world);
    let mut joints = Vec::new();
    let mut poids = 0.0;
    for rang in 1..=200u16 {
        let at = Vec3::new(0.0, -f32::from(rang), 0.0);
        let b = boule(&mut world, at);
        poids = world.body_mass(b).unwrap() * 9.81;
        let mut spec = joint(JointKind::Spherical, Vec3::X, Vec3::new(0.0, -1.0, 0.0));
        spec.frame_a = (Vec3::ZERO, Quat::IDENTITY);
        joints.push(world.add_joint(ASSEMBLY, rang, haut, b, &spec).unwrap());
        haut = b;
    }
    derouler(&mut world, 60, |_| ());
    for (rang, id) in joints.iter().take(100).enumerate() {
        let lu = world.joint_report(*id).unwrap();
        assert!(lu.force > poids, "joint {rang} : force {}", lu.force);
    }
}

// --- T-333 : sommeil -------------------------------------------------------------------------

#[test]
fn t333_un_joint_entre_deux_corps_endormis_n_est_pas_resolu() {
    // Deux boules reliées, posées l'une près de l'autre dans un monde sans gravité : elles
    // s'endorment, et leur joint n'est plus résolu (R-631) ; une impulsion sur l'une réveille les
    // deux, et le joint l'est de nouveau.
    let mut world = monde(false);
    let a = boule(&mut world, Vec3::ZERO);
    let at = Vec3::new(1.0, 0.0, 0.0);
    let b = boule(&mut world, at);
    world
        .add_joint(ASSEMBLY, 0, a, b, &joint(JointKind::Fixed, Vec3::X, at))
        .unwrap();
    world.advance(TICK);
    assert_eq!(
        world.joint_counts().solved,
        1,
        "éveillés, le joint est résolu"
    );

    derouler(&mut world, 60, |_| ());
    assert!(
        world.is_sleeping(a).unwrap() && world.is_sleeping(b).unwrap(),
        "endormis"
    );
    assert_eq!(
        world.joint_counts().solved,
        0,
        "endormis, le joint n'est pas résolu"
    );

    world.apply_impulse(a, Vec3::new(0.0, 50.0, 0.0), Vec3::ZERO, false);
    world.advance(TICK);
    assert!(
        !world.is_sleeping(b).unwrap(),
        "le corps relié se réveille avec l'autre"
    );
    assert_eq!(world.joint_counts().solved, 1);
}
