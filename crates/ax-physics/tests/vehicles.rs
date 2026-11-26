//! Tests de C-33 : les roues par rayon, la suspension et les pneus dans le pas (ADR-159, tranche 2),
//! puis le moteur, la direction, les freins, l'aérodynamique et le conducteur (tranche 3).

use ax_math::{Quat, Vec3};
use ax_model::dm::scene::NONE_U16;
use ax_model::dm::vehicle::{
    differential_kind, motor_coupling, native_input_flags, transmission_kind, vehicle_flags,
    vehicle_report_flags, wheel_flags, wheel_report_flags, AeroDesc, AssistDesc, BrakesDesc,
    DifferentialDesc, EnergyDesc, EngineDesc, GeneratorDesc, MotorDesc, SteeringDesc,
    SuspensionDesc, TireCurve, TransmissionDesc, VehicleDesc, VehicleInput, VehicleReport,
    WheelDesc,
};
use ax_physics::{
    BodyCollider, BodyId, BodyKind, CellMaterials, CollisionGroups, CombineRule, ContactMaterial,
    Handle, PhysicsConfig, PhysicsWorld, Shape, WheelMount, NO_MATERIAL,
};

const TICK: f32 = 1.0 / 60.0;
const GRAVITE: f32 = 9.81;
const ASSEMBLY: Handle = Handle {
    index: 1,
    generation: 1,
};

/// Masse visée du châssis, en kg.
const MASSE: f32 = 1200.0;
/// Demi-dimensions du châssis.
const DEMI: [f32; 3] = [0.9, 0.2, 2.0];

const RAYON: f32 = 0.45;
const REPOS: f32 = 0.5;
const DEBATTEMENT: f32 = 0.3;

fn monde() -> PhysicsWorld {
    PhysicsWorld::new(PhysicsConfig::new(TICK, 4).expect("configuration valide"))
}

fn contact(friction: f32) -> ContactMaterial {
    ContactMaterial {
        friction,
        ..ContactMaterial::default()
    }
}

fn collider(half_extents: [f32; 3], density: f32, friction: f32) -> BodyCollider {
    BodyCollider {
        shape: Shape::Cuboid { half_extents },
        density,
        material: contact(friction),
        translation: Vec3::ZERO,
        rotation: Quat::IDENTITY,
    }
}

/// Une dalle fixe de dessus à `y`, centrée en `(x, z)`.
fn dalle(
    world: &mut PhysicsWorld,
    x: f32,
    z: f32,
    y: f32,
    demi: [f32; 2],
    friction: f32,
) -> BodyId {
    world
        .add_assembly(
            BodyKind::Static,
            Vec3::new(x, y - 0.5, z),
            Quat::IDENTITY,
            &[collider([demi[0], 0.5, demi[1]], 1000.0, friction)],
        )
        .expect("dalle")
}

fn courbe() -> TireCurve {
    TireCurve {
        peak_slip: 0.12,
        peak_value: 1.0,
        tail_slip: 0.5,
        tail_value: 0.75,
    }
}

fn roue() -> WheelDesc {
    WheelDesc {
        node: 0,
        flags: 0,
        axle: NONE_U16,
        radius: RAYON,
        width: 0.24,
        mass: 20.0,
        suspension: SuspensionDesc {
            rest_length: REPOS,
            max_travel: DEBATTEMENT,
            stiffness: 30.0,
            damping_compression: 2.3,
            damping_rebound: 2.8,
            max_force: 60_000.0,
            anti_roll: 0.0,
        },
        mu_long: 1.0,
        mu_lat: 1.0,
        long: courbe(),
        lat: courbe(),
        wear_rate: 0.0,
        wear_grip_loss: 0.4,
        wet_grip: 1.0,
    }
}

fn description(roues: usize) -> VehicleDesc {
    VehicleDesc {
        flags: 0,
        energy: EnergyDesc::NONE,
        motors: Vec::new(),
        generator: None,
        engine: EngineDesc {
            idle_rpm: 800.0,
            max_rpm: 7000.0,
            inertia: 0.3,
            braking_torque: 25.0,
            starter_time: 0.0,
        },
        transmission: TransmissionDesc {
            kind: transmission_kind::DIRECT,
            final_drive: 1.0,
            shift_time: 0.0,
            auto_shift_up_rpm: 6200.0,
            auto_shift_down_rpm: 2200.0,
            efficiency: 1.0,
        },
        differential: DifferentialDesc {
            kind: 0,
            front_rear_split: 0.0,
            lock: 0.0,
        },
        steering: SteeringDesc {
            max_angle: 0.61,
            speed: 3.0,
            return_speed: 4.0,
            speed_sensitivity: 0.6,
            ackermann: 1.0,
        },
        brakes: BrakesDesc {
            max_torque_front: 3000.0,
            max_torque_rear: 1800.0,
            handbrake_torque: 4000.0,
            abs_slip_threshold: 0.15,
        },
        aero: AeroDesc {
            drag_coefficient: 0.0,
            frontal_area: 0.0,
            downforce_front: 0.0,
            downforce_rear: 0.0,
        },
        assists: AssistDesc {
            traction_slip_threshold: 0.2,
            stability_yaw_tolerance: 0.15,
        },
        wheels: vec![roue(); roues],
        torque_curve: Vec::new(),
        gears: Vec::new(),
        surfaces: Vec::new(),
    }
}

/// Les roues d'un véhicule de quatre roues, `(x, z)` : avant gauche, avant droite, arrière gauche,
/// arrière droite (-Z avant, +X droite).
const QUATRE: [(f32, f32); 4] = [(-0.8, -1.4), (0.8, -1.4), (-0.8, 1.4), (0.8, 1.4)];

/// Un véhicule de châssis `DEMI` et de masse `MASSE`, ses roues en `roues`, l'avant vers -Z.
fn poser(
    world: &mut PhysicsWorld,
    position: Vec3,
    rotation: Quat,
    desc: VehicleDesc,
    roues: &[(f32, f32)],
) -> BodyId {
    let volume = 8.0 * DEMI[0] * DEMI[1] * DEMI[2];
    let chassis = world
        .add_assembly(
            BodyKind::Dynamic,
            position,
            rotation,
            &[collider(DEMI, MASSE / volume, 0.5)],
        )
        .expect("châssis");
    let montures: Vec<WheelMount> = roues
        .iter()
        .map(|&(x, z)| WheelMount {
            body: 0,
            center: Vec3::new(x, -REPOS, z),
        })
        .collect();
    world.attach_vehicle(
        ASSEMBLY,
        &[chassis],
        desc,
        &montures,
        &[],
        CollisionGroups::ALL,
    );
    chassis
}

/// Comme [`poser`], sous le handle donné.
fn poser_sous(
    world: &mut PhysicsWorld,
    handle: Handle,
    position: Vec3,
    rotation: Quat,
    desc: VehicleDesc,
    roues: &[(f32, f32)],
) -> BodyId {
    let volume = 8.0 * DEMI[0] * DEMI[1] * DEMI[2];
    let chassis = world
        .add_assembly(
            BodyKind::Dynamic,
            position,
            rotation,
            &[collider(DEMI, MASSE / volume, 0.5)],
        )
        .expect("châssis");
    let montures: Vec<WheelMount> = roues
        .iter()
        .map(|&(x, z)| WheelMount {
            body: 0,
            center: Vec3::new(x, -REPOS, z),
        })
        .collect();
    world.attach_vehicle(
        handle,
        &[chassis],
        desc,
        &montures,
        &[],
        CollisionGroups::ALL,
    );
    chassis
}

/// Un véhicule de quatre roues sans moteur ni frein, dont le centre est à la hauteur `y`, l'avant
/// vers -Z.
fn vehicule(world: &mut PhysicsWorld, position: Vec3, rotation: Quat) -> BodyId {
    poser(world, position, rotation, description(4), &QUATRE)
}

fn avancer(world: &mut PhysicsWorld, secondes: f32) {
    for _ in 0..(secondes / TICK).round() as u32 {
        world.advance(TICK);
    }
}

/// La hauteur du centre au repos au-dessus d'un sol à 0 : celle du modèle, où la roue touche le sol (§6.5).
fn hauteur_equilibre() -> f32 {
    REPOS + RAYON
}

#[test]
fn t320_le_vehicule_repose_a_sa_compression_d_equilibre_sans_s_enfoncer_ni_deriver() {
    let mut world = monde();
    dalle(&mut world, 0.0, 0.0, 0.0, [20.0, 20.0], 0.8);
    let v = vehicule(&mut world, Vec3::new(0.0, 1.2, 0.0), Quat::IDENTITY);
    avancer(&mut world, 3.0);
    let a = world.pose(v).expect("pose").translation;
    assert!(
        (a.y - hauteur_equilibre()).abs() < 0.02,
        "hauteur {} au lieu de {}",
        a.y,
        hauteur_equilibre()
    );
    assert!(a.x.abs() < 1e-3 && a.z.abs() < 1e-3, "dérive {a:?}");
    let etat = world.vehicle_wheels(ASSEMBLY).expect("véhicule")[0];
    assert!(etat.on_ground);
    assert!((etat.compression - GRAVITE / 30.0).abs() < 0.05, "{etat:?}");
    avancer(&mut world, 5.0);
    let b = world.pose(v).expect("pose").translation;
    assert!((a.y - b.y).abs() < 0.005, "enfoncement {} -> {}", a.y, b.y);
}

#[test]
fn t320_au_repos_il_s_endort() {
    let mut world = monde();
    dalle(&mut world, 0.0, 0.0, 0.0, [20.0, 20.0], 0.8);
    let v = vehicule(
        &mut world,
        Vec3::new(0.0, hauteur_equilibre(), 0.0),
        Quat::IDENTITY,
    );
    avancer(&mut world, 6.0);
    assert_eq!(world.is_sleeping(v), Some(true));
}

#[test]
fn t326_une_chute_de_dix_blocs_n_explose_pas() {
    let mut world = monde();
    dalle(&mut world, 0.0, 0.0, 0.0, [20.0, 20.0], 0.8);
    let v = vehicule(&mut world, Vec3::new(0.0, 11.0, 0.0), Quat::IDENTITY);
    let mut plus_haut_apres_contact: f32 = 0.0;
    let mut touche = false;
    for _ in 0..(6.0 / TICK) as u32 {
        world.advance(TICK);
        let y = world.pose(v).expect("pose").translation.y;
        assert!(y.is_finite());
        touche |= world.vehicle_wheels(ASSEMBLY).expect("véhicule")[0].on_ground;
        if touche {
            plus_haut_apres_contact = plus_haut_apres_contact.max(y);
        }
    }
    assert!(touche);
    assert!(
        plus_haut_apres_contact < 11.0,
        "rebond à {plus_haut_apres_contact}"
    );
    let fin = world.pose(v).expect("pose").translation;
    assert!((fin.y - hauteur_equilibre()).abs() < 0.05, "fin {fin:?}");
    assert!(fin.x.abs() < 0.05 && fin.z.abs() < 0.05, "fin {fin:?}");
}

#[test]
fn t325_il_franchit_des_marches_d_un_demi_bloc_sans_blocage_ni_ejection() {
    let mut world = monde();
    dalle(&mut world, 0.0, 10.0, 0.0, [20.0, 20.0], 0.8);
    // Dix marches de 0,5 bloc de haut et 3 blocs de long, vers -Z, puis un palier.
    for marche in 0..10 {
        let z = -6.0 - 3.0 * marche as f32;
        dalle(
            &mut world,
            0.0,
            z,
            0.5 * (marche + 1) as f32,
            [20.0, 1.5],
            0.8,
        );
    }
    let palier_z = -6.0 - 3.0 * 10.0 - 8.5;
    dalle(&mut world, 0.0, palier_z, 5.0, [20.0, 10.0], 0.8);
    let v = vehicule(
        &mut world,
        Vec3::new(0.0, hauteur_equilibre(), 5.0),
        Quat::IDENTITY,
    );
    avancer(&mut world, 1.0);

    let poussee = 9000.0;
    let mut vitesse_verticale_max: f32 = 0.0;
    for _ in 0..(25.0 / TICK) as u32 {
        let z = world.pose(v).expect("pose").translation.z;
        if z < -42.0 {
            break;
        }
        world.apply_impulse(v, Vec3::NEG_Z * poussee * TICK, Vec3::ZERO, false);
        let avant = world.velocity(v).expect("vitesse");
        world.advance(TICK);
        vitesse_verticale_max = vitesse_verticale_max.max(avant.y.abs());
        let p = world.pose(v).expect("pose").translation;
        assert!(
            p.y < 5.0 + hauteur_equilibre() + 2.5,
            "éjecté : {p:?} à {}",
            avant.y
        );
    }
    let fin = world.pose(v).expect("pose").translation;
    assert!(fin.z < -42.0, "bloqué en {fin:?}");
    assert!(fin.x.abs() < 3.0, "dévié : {fin:?}");
    assert!(
        vitesse_verticale_max < 12.0,
        "vitesse verticale {vitesse_verticale_max}"
    );
}

#[test]
fn adr159_d4_la_roue_roule_a_la_vitesse_du_sol() {
    let mut world = monde();
    dalle(&mut world, 0.0, 0.0, 0.0, [40.0, 200.0], 0.8);
    let v = vehicule(
        &mut world,
        Vec3::new(0.0, hauteur_equilibre(), 100.0),
        Quat::IDENTITY,
    );
    avancer(&mut world, 1.0);
    world.apply_impulse(v, Vec3::NEG_Z * MASSE * 10.0, Vec3::ZERO, false);
    avancer(&mut world, 3.0);
    let vitesse = world.velocity(v).expect("vitesse");
    assert!(vitesse.z < -5.0, "il roule : {vitesse:?}");
    assert!(vitesse.x.abs() < 0.1, "tout droit : {vitesse:?}");
    let etat = world.vehicle_wheels(ASSEMBLY).expect("véhicule")[0];
    let ecart = etat.spin_speed * RAYON + vitesse.z;
    assert!(
        ecart.abs() < 0.2,
        "roue {} / sol {}",
        etat.spin_speed * RAYON,
        -vitesse.z
    );
}

#[test]
fn r1070_la_glace_laisse_filer_ce_que_la_route_arrete_de_cote() {
    let derive = |friction: f32| {
        let mut world = monde();
        dalle(&mut world, 0.0, 0.0, 0.0, [200.0, 200.0], friction);
        let v = vehicule(
            &mut world,
            Vec3::new(0.0, hauteur_equilibre(), 0.0),
            Quat::IDENTITY,
        );
        avancer(&mut world, 1.0);
        world.apply_impulse(v, Vec3::X * MASSE * 5.0, Vec3::new(0.0, -0.3, 0.0), true);
        avancer(&mut world, 2.0);
        world.pose(v).expect("pose").translation.x
    };
    let route = derive(1.0);
    let glace = derive(0.05);
    assert!(route < 3.0, "route : {route}");
    assert!(glace > 2.0 * route, "glace {glace}, route {route}");
}

#[test]
fn en_pente_il_descend_droit() {
    let mut world = monde();
    // Une pente de 10 degrés : l'avant du véhicule (-Z) regarde vers le haut, il descend en marche arrière.
    let pente = Quat::from_rotation_x(10.0_f32.to_radians());
    world
        .add_assembly(
            BodyKind::Static,
            Vec3::new(0.0, -0.5, 0.0),
            pente,
            &[collider([30.0, 0.5, 200.0], 1000.0, 0.8)],
        )
        .expect("pente");
    let v = vehicule(&mut world, Vec3::new(0.0, 1.0, 0.0), pente);
    avancer(&mut world, 3.0);
    let p = world.pose(v).expect("pose").translation;
    let w = world.velocity(v).expect("vitesse");
    assert!(w.z > 2.0, "il descend : {p:?} {w:?}");
    // Un véhicule à direction droite est neutre : une dérive reste, très faible devant la distance.
    assert!(p.x.abs() < 0.03 * p.z.abs(), "sans dériver : {p:?}");
}

#[test]
fn r1051_le_rayon_ignore_sa_propre_assembly() {
    // Les ancrages sont dans le châssis : un rayon qui le verrait mesurerait zéro, et il sauterait.
    let mut world = monde();
    dalle(&mut world, 0.0, 0.0, 0.0, [20.0, 20.0], 0.8);
    vehicule(
        &mut world,
        Vec3::new(0.0, hauteur_equilibre(), 0.0),
        Quat::IDENTITY,
    );
    avancer(&mut world, 3.0);
    let etat = world.vehicle_wheels(ASSEMBLY).expect("véhicule")[0];
    assert!(etat.on_ground, "{etat:?}");
    assert!((etat.compression - GRAVITE / 30.0).abs() < 0.05, "{etat:?}");
}

#[test]
fn adr159_d3_un_corps_dynamique_sous_la_roue_recoit_la_reaction() {
    // Un plateau sans appui, le véhicule posé dessus : en chute libre seul, il tombe plus vite chargé.
    let chute = |charge: bool| {
        let mut world = monde();
        let plateau = world
            .add_assembly(
                BodyKind::Dynamic,
                Vec3::new(0.0, 100.0, 0.0),
                Quat::IDENTITY,
                &[collider([3.0, 0.1, 3.0], 100.0, 0.8)],
            )
            .expect("plateau");
        if charge {
            let hauteur = 100.1 + hauteur_equilibre();
            vehicule(&mut world, Vec3::new(0.0, hauteur, 0.0), Quat::IDENTITY);
        }
        avancer(&mut world, 0.5);
        world.pose(plateau).expect("pose").translation.y
    };
    let seul = chute(false);
    let charge = chute(true);
    assert!(charge < seul - 0.05, "chargé {charge}, seul {seul}");
}

#[test]
fn adr159_un_corps_retire_emporte_son_vehicule() {
    let mut world = monde();
    let v = vehicule(&mut world, Vec3::new(0.0, 5.0, 0.0), Quat::IDENTITY);
    assert!(world.vehicle_wheels(ASSEMBLY).is_some());
    world.remove_body(v);
    assert!(world.vehicle_wheels(ASSEMBLY).is_none());
    avancer(&mut world, 0.1);
}

#[test]
fn adr159_d3_une_tuile_qui_change_sous_un_vehicule_endormi_le_reveille() {
    let mut world = monde();
    let sol = dalle(&mut world, 0.0, 0.0, 0.0, [20.0, 20.0], 0.8);
    let v = vehicule(
        &mut world,
        Vec3::new(0.0, hauteur_equilibre(), 0.0),
        Quat::IDENTITY,
    );
    avancer(&mut world, 6.0);
    assert_eq!(world.is_sleeping(v), Some(true));

    // Loin : il dort toujours.
    world.wake_vehicles_near(Vec3::new(100.0, -1.0, 100.0), Vec3::new(116.0, 15.0, 116.0));
    assert_eq!(world.is_sleeping(v), Some(true));

    world.remove_body(sol);
    world.wake_vehicles_near(Vec3::new(-8.0, -8.0, -8.0), Vec3::new(8.0, 8.0, 8.0));
    avancer(&mut world, 1.0);
    let y = world.pose(v).expect("pose").translation.y;
    assert!(y < hauteur_equilibre() - 2.0, "il tombe : {y}");
    let etat = world.vehicle_wheels(ASSEMBLY).expect("véhicule")[0];
    assert!(!etat.on_ground);
}

#[test]
fn adr159_les_overlays_tracent_rayons_roues_et_suspensions() {
    use ax_math::{DVec3, FloatingOrigin};
    use ax_model::dm::debug::{debug_frame, overlay};

    let mut world = monde();
    dalle(&mut world, 0.0, 0.0, 0.0, [20.0, 20.0], 0.8);
    vehicule(
        &mut world,
        Vec3::new(0.0, hauteur_equilibre(), 0.0),
        Quat::IDENTITY,
    );
    avancer(&mut world, 0.5);
    let origine = FloatingOrigin::new(DVec3::ZERO);
    let plages = world.debug_drafts(
        &origine,
        DVec3::ZERO,
        overlay::RAYCASTS | overlay::WHEELS | overlay::SUSPENSION,
    );
    let segments = |bit: u64| {
        let plage = plages
            .iter()
            .find(|plage| plage.overlay == overlay::rank(bit))
            .expect("une plage");
        assert_eq!(plage.frame, debug_frame::ORIGIN);
        assert_eq!(plage.handle, ASSEMBLY);
        plage.segments.clone()
    };
    assert_eq!(segments(overlay::RAYCASTS).len(), 4, "un rayon par roue");
    assert_eq!(segments(overlay::SUSPENSION).len(), 4);
    assert_eq!(
        segments(overlay::WHEELS).len(),
        4 * 16,
        "seize traits par cercle"
    );
    let rayon = segments(overlay::RAYCASTS)[0];
    assert!(rayon.b[1].abs() < 0.01, "le rayon finit au sol : {rayon:?}");
    assert!(world
        .debug_drafts(&origine, DVec3::ZERO, overlay::COLLIDERS)
        .iter()
        .all(|p| p.overlay != overlay::rank(overlay::RAYCASTS)));
}

#[test]
fn adr159_d9_au_repos_la_compression_publiee_est_nulle() {
    let mut world = monde();
    dalle(&mut world, 0.0, 0.0, 0.0, [20.0, 20.0], 0.8);
    vehicule(&mut world, Vec3::new(0.0, 1.2, 0.0), Quat::IDENTITY);
    let mut dernier = Vec::new();
    for _ in 0..40 {
        avancer(&mut world, 0.05);
        let lu = world.wheel_reports();
        if !lu.is_empty() {
            dernier = lu;
        }
    }
    assert_eq!(dernier.len(), 4);
    for roue in &dernier {
        assert_ne!(roue.flags & wheel_report_flags::ON_GROUND, 0, "{roue:?}");
        assert!(roue.compression.abs() < 0.05, "{roue:?}");
        assert_eq!(roue.steer_angle, 0.0, "{roue:?}");
    }
    // La valeur publiée retranche bien l'équilibre : la compression brute, elle, n'est pas nulle.
    let brute = world.vehicle_wheels(ASSEMBLY).expect("véhicule")[0].compression;
    assert!(brute > 0.1, "compression brute {brute}");
}

#[test]
fn adr159_d9_un_vehicule_endormi_ne_rapporte_plus_ses_roues() {
    let mut world = monde();
    dalle(&mut world, 0.0, 0.0, 0.0, [20.0, 20.0], 0.8);
    vehicule(&mut world, Vec3::new(0.0, 1.2, 0.0), Quat::IDENTITY);
    assert_eq!(world.wheel_reports().len(), 4, "éveillé au départ");
    avancer(&mut world, 12.0);
    assert!(world.wheel_reports().is_empty(), "endormi, il se tait");
}

// --- Tranche 3 : moteur, direction, freins, aérodynamique, conducteur. ---

/// Une berline : l'avant braque, l'arrière entraîne et porte le frein à main ; boîte automatique à
/// six rapports et marche arrière, les valeurs des exemples de 12.5 et 12.6.
fn berline() -> VehicleDesc {
    let mut d = description(4);
    d.flags = vehicle_flags::POWERTRAIN;
    d.transmission = TransmissionDesc {
        kind: transmission_kind::AUTOMATIC,
        final_drive: 3.7,
        shift_time: 0.25,
        auto_shift_up_rpm: 6200.0,
        auto_shift_down_rpm: 2200.0,
        efficiency: 0.92,
    };
    d.torque_curve = vec![
        [0.0, 0.0],
        [1000.0, 180.0],
        [3000.0, 260.0],
        [5500.0, 240.0],
        [7000.0, 180.0],
    ];
    d.gears = vec![-3.2, 0.0, 3.5, 2.1, 1.4, 1.0, 0.8];
    for (rang, roue) in d.wheels.iter_mut().enumerate() {
        roue.flags = if rang < 2 {
            wheel_flags::STEERED
        } else {
            wheel_flags::DRIVEN | wheel_flags::HANDBRAKE
        };
    }
    d
}

fn entree(throttle: f32, brake: f32, steer: f32) -> VehicleInput {
    VehicleInput {
        throttle,
        brake,
        steer,
        ..VehicleInput::NONE
    }
}

/// Le conducteur, assis, commande `e`.
fn conduire(world: &mut PhysicsWorld, e: VehicleInput) {
    assert_eq!(
        world.set_vehicle_input(ASSEMBLY, &e, true),
        Some(false),
        "un véhicule, une entrée finie"
    );
}

/// Une berline posée sur un sol plat de `demi` blocs de demi-côté et d'adhérence `friction`, au repos.
fn sur_le_plat(desc: VehicleDesc, demi: [f32; 2], friction: f32) -> (PhysicsWorld, BodyId) {
    let mut world = monde();
    dalle(&mut world, 0.0, 0.0, 0.0, demi, friction);
    let v = poser(
        &mut world,
        Vec3::new(0.0, hauteur_equilibre(), 0.0),
        Quat::IDENTITY,
        desc,
        &QUATRE,
    );
    conduire(&mut world, VehicleInput::NONE);
    avancer(&mut world, 0.5);
    (world, v)
}

/// La vitesse vers l'avant d'un véhicule posé sans rotation : vers -Z.
fn vers_l_avant(world: &PhysicsWorld, v: BodyId) -> f32 {
    -world.velocity(v).expect("vitesse").z
}

/// Le cap, en radians : l'angle de l'avant (-Z) autour de +Y, positif à gauche.
fn cap(world: &PhysicsWorld, v: BodyId) -> f32 {
    let avant = world.pose(v).expect("pose").rotation * Vec3::NEG_Z;
    (-avant.x).atan2(-avant.z)
}

/// Une boîte directe et un moteur dont le couple ne s'annule pas à l'arrêt, comme un moteur électrique,
/// et une traînée qui plafonne la vitesse sous le régime maximal.
fn electrique() -> VehicleDesc {
    let mut d = berline();
    d.transmission.kind = transmission_kind::DIRECT;
    d.transmission.final_drive = 8.0;
    d.transmission.efficiency = 0.95;
    d.engine.max_rpm = 9000.0;
    d.torque_curve = vec![[0.0, 300.0], [3000.0, 300.0], [9000.0, 100.0]];
    d.aero.drag_coefficient = 1.0;
    d.aero.frontal_area = 2.0;
    d
}

/// La vitesse où la poussée du moteur de [`electrique`] égale sa traînée, roues sans glissement.
fn vitesse_plafond(d: &VehicleDesc) -> f32 {
    let couple = |rpm: f32| {
        let c = &d.torque_curve;
        if rpm <= c[1][0] {
            c[0][1]
        } else {
            c[1][1] + (c[2][1] - c[1][1]) * (rpm - c[1][0]) / (c[2][0] - c[1][0])
        }
    };
    let ecart = |v: f32| {
        let rpm = v / RAYON * d.transmission.final_drive * 60.0 / std::f32::consts::TAU;
        let poussee = couple(rpm) * d.transmission.final_drive * d.transmission.efficiency / RAYON;
        poussee - 0.5 * 1.225 * d.aero.drag_coefficient * d.aero.frontal_area * v * v
    };
    let (mut bas, mut haut) = (0.0f32, 200.0f32);
    for _ in 0..60 {
        let milieu = 0.5 * (bas + haut);
        if ecart(milieu) > 0.0 {
            bas = milieu;
        } else {
            haut = milieu;
        }
    }
    bas
}

#[test]
fn t321_l_acceleration_est_croissante_monotone_et_plafonnee() {
    let d = electrique();
    let plafond = vitesse_plafond(&d);
    let regime_max =
        d.engine.max_rpm * std::f32::consts::TAU / 60.0 / d.transmission.final_drive * RAYON;
    assert!(
        plafond < 0.95 * regime_max,
        "la traînée plafonne avant le régime maximal"
    );
    let (mut world, v) = sur_le_plat(d, [40.0, 2500.0], 1.0);
    conduire(&mut world, entree(1.0, 0.0, 0.0));
    let mut precedente = vers_l_avant(&world, v);
    let mut a_10_s = 0.0;
    let mut a_40_s = 0.0;
    for tick in 1..=(50.0 / TICK) as u32 {
        world.advance(TICK);
        let vitesse = vers_l_avant(&world, v);
        assert!(
            vitesse >= precedente - 1e-3,
            "vitesse décroissante à {} s : {precedente} puis {vitesse}",
            tick as f32 * TICK
        );
        precedente = vitesse;
        if tick == (10.0 / TICK) as u32 {
            a_10_s = vitesse;
        }
        if tick == (40.0 / TICK) as u32 {
            a_40_s = vitesse;
        }
    }
    assert!(a_10_s > 0.5 * plafond, "croissante : {a_10_s} à 10 s");
    assert!(
        (precedente - a_40_s).abs() < 0.01 * plafond,
        "plafonnée : {a_40_s} à 40 s, {precedente} à 50 s"
    );
    assert!(
        (precedente - plafond).abs() < 0.03 * plafond,
        "au plafond de la traînée : {precedente} au lieu de {plafond}"
    );
    let p = world.pose(v).expect("pose").translation;
    assert!(p.x.abs() < 0.01 * p.z.abs(), "tout droit : {p:?}");
}

#[test]
fn adr167_un_couple_regle_accelere_plus_et_la_base_le_rend() {
    // D6 : le couple effectif met la courbe à l'échelle, véhicule en marche ; le réglage de base rend le véhicule
    // d'origine, au bit près de ce qu'il fait sans réglage.
    let vitesse_apres = |reglage: Option<ax_vehicle::tuning::Tuning>| {
        let (mut world, v) = sur_le_plat(electrique(), [40.0, 2500.0], 1.0);
        conduire(&mut world, entree(1.0, 0.0, 0.0));
        avancer(&mut world, 1.0);
        if let Some(reglage) = reglage {
            assert_eq!(world.set_vehicle_tuning(ASSEMBLY, &reglage), Some(()));
        }
        avancer(&mut world, 2.0);
        vers_l_avant(&world, v)
    };
    let base = vitesse_apres(None);
    let regle = vitesse_apres(Some(ax_vehicle::tuning::Tuning {
        torque: 450.0,
        ..ax_vehicle::tuning::Tuning::base()
    }));
    assert!(
        regle > base * 1.15,
        "le couple ×1,5 accélère plus : {regle} m/s contre {base} m/s"
    );
    let rendu = vitesse_apres(Some(ax_vehicle::tuning::Tuning::base()));
    assert_eq!(
        rendu.to_bits(),
        base.to_bits(),
        "la base rend le véhicule d'origine"
    );
    assert_eq!(
        monde().set_vehicle_tuning(ASSEMBLY, &ax_vehicle::tuning::Tuning::base()),
        None,
        "sans véhicule, rien"
    );
}

#[test]
fn r1082_sans_moteur_le_couple_des_roues_fait_avancer_le_vehicule() {
    // ADR-167 D9 : sans powertrain, le couple qu'un contrôleur pose sur chaque roue est tout son couple. Il vaut
    // jusqu'au suivant ; une liste vide le retire ; une liste d'une autre longueur que les roues est refusée.
    let (mut world, v) = sur_le_plat(description(4), [40.0, 2500.0], 1.0);
    assert_eq!(
        world.set_wheel_torques(ASSEMBLY, &[0.0, 0.0, 300.0, 300.0]),
        Some(())
    );
    avancer(&mut world, 2.0);
    let lance = vers_l_avant(&world, v);
    assert!(
        lance > 1.0,
        "les roues arrière poussent le véhicule : {lance} m/s"
    );
    assert_eq!(
        world.set_wheel_torques(ASSEMBLY, &[300.0, 300.0]),
        None,
        "deux couples pour quatre roues"
    );
    assert_eq!(world.set_wheel_torques(ASSEMBLY, &[]), Some(()));
    // Les roues qui patinaient rendent d'abord leur inertie en rattrapant le sol ; ensuite, plus rien ne pousse.
    avancer(&mut world, 0.5);
    let rattrape = vers_l_avant(&world, v);
    avancer(&mut world, 1.0);
    let libre = vers_l_avant(&world, v);
    assert!(
        libre <= rattrape + 1e-3,
        "sans couple, il n'accélère plus : {libre} m/s après {rattrape} m/s"
    );
    assert_eq!(
        monde().set_wheel_torques(ASSEMBLY, &[]),
        None,
        "sans véhicule, rien"
    );
}

#[test]
fn r1082_le_couple_des_roues_s_ajoute_au_moteur() {
    // ADR-167 D9 : avec un motopropulseur, le couple d'une roue s'ajoute à sa part de l'arbre ; un couple nul rend le
    // véhicule d'origine, au bit près.
    let vitesse = |couple: Option<f32>| {
        let (mut world, v) = sur_le_plat(electrique(), [40.0, 2500.0], 1.0);
        conduire(&mut world, entree(1.0, 0.0, 0.0));
        if let Some(couple) = couple {
            assert_eq!(
                world.set_wheel_torques(ASSEMBLY, &[0.0, 0.0, couple, couple]),
                Some(())
            );
        }
        avancer(&mut world, 2.0);
        vers_l_avant(&world, v)
    };
    let base = vitesse(None);
    let pousse = vitesse(Some(600.0));
    assert!(
        pousse > base * 1.1,
        "le couple des roues s'ajoute : {pousse} m/s contre {base} m/s"
    );
    assert_eq!(
        vitesse(Some(0.0)).to_bits(),
        base.to_bits(),
        "un couple nul ne change rien"
    );
}

#[test]
fn adr167_la_masse_se_met_a_l_echelle_de_son_origine_centre_garde() {
    // D6, CAP-34 : masse et inertie multipliées, centre de masse gardé ; chaque réglage repart de l'origine.
    let mut world = monde();
    dalle(&mut world, 0.0, 0.0, 0.0, [10.0, 10.0], 1.0);
    let v = vehicule(
        &mut world,
        Vec3::new(0.0, hauteur_equilibre(), 0.0),
        Quat::IDENTITY,
    );
    let masse = world.body_mass(v).expect("masse");
    let centre = world.body_center_of_mass(v).expect("centre");
    world.scale_body_mass(v, 2.0);
    assert!((world.body_mass(v).expect("masse") - 2.0 * masse).abs() < 1e-2 * masse);
    assert!((world.body_center_of_mass(v).expect("centre") - centre).length() < 1e-4);
    world.scale_body_mass(v, 0.5);
    assert!(
        (world.body_mass(v).expect("masse") - 0.5 * masse).abs() < 1e-2 * masse,
        "0,5 de l'origine, pas de la masse doublée"
    );
    world.scale_body_mass(v, 1.0);
    assert!((world.body_mass(v).expect("masse") - masse).abs() < 1e-3 * masse);
    world.scale_body_mass(v, f32::NAN);
    world.scale_body_mass(v, 0.0);
    assert!(
        (world.body_mass(v).expect("masse") - masse).abs() < 1e-3 * masse,
        "un facteur non positif ou non fini ne change rien"
    );
}

/// Lance une berline à 20 m/s puis freine à fond jusqu'à l'arrêt ; rend la vitesse au début du
/// freinage et la distance parcourue.
fn freiner(desc: VehicleDesc, friction: f32) -> (f32, f32) {
    let (mut world, v) = sur_le_plat(desc, [40.0, 400.0], friction);
    world.apply_impulse(v, Vec3::NEG_Z * MASSE * 20.0, Vec3::ZERO, false);
    world.advance(TICK);
    let depart = world.pose(v).expect("pose").translation.z;
    let vitesse = vers_l_avant(&world, v);
    conduire(&mut world, entree(0.0, 1.0, 0.0));
    for _ in 0..(10.0 / TICK) as u32 {
        world.advance(TICK);
        if vers_l_avant(&world, v) < 0.05 {
            break;
        }
    }
    assert!(vers_l_avant(&world, v) < 0.05, "arrêté");
    (vitesse, depart - world.pose(v).expect("pose").translation.z)
}

/// Une berline sur un champ de hauteurs plat à deux cellules, l'une à gauche de l'autre, d'adhérences `gauche` et
/// `droite`, freinée à fond depuis 20 m/s pendant une seconde : son cap, positif à gauche.
fn cap_apres_freinage_sur_deux_sols(gauche: f32, droite: f32) -> f32 {
    cap_apres_freinage(contact(gauche), contact(droite), 0.0, 0.0)
}

/// Le même essai avec les matériaux des deux cellules, sur une route mouillée de `mouille`, les pneus usés de `usure`.
fn cap_apres_freinage(
    gauche: ContactMaterial,
    droite: ContactMaterial,
    mouille: f32,
    usure: f32,
) -> f32 {
    let mut world = monde();
    let sol = world
        .add_assembly(
            BodyKind::Static,
            Vec3::ZERO,
            Quat::IDENTITY,
            &[BodyCollider {
                shape: Shape::Heightfield {
                    rows: 2,
                    cols: 3,
                    heights: vec![0.0; 6],
                    scale: [80.0, 1.0, 800.0],
                },
                density: 1.0,
                material: contact(1.0),
                translation: Vec3::ZERO,
                rotation: Quat::IDENTITY,
            }],
        )
        .expect("champ");
    let cellules = CellMaterials::new(2, [80.0, 800.0], vec![gauche, droite]);
    world.set_cell_materials(sol, cellules.expect("cellules"));
    let v = poser(
        &mut world,
        Vec3::new(0.0, hauteur_equilibre(), 0.0),
        Quat::IDENTITY,
        berline(),
        &QUATRE,
    );
    conduire(&mut world, VehicleInput::NONE);
    avancer(&mut world, 0.5);
    assert!(world.set_vehicle_wet(ASSEMBLY, mouille));
    for roue in 0..QUATRE.len() {
        assert!(world.set_wheel_wear(ASSEMBLY, roue, usure));
    }
    world.apply_impulse(v, Vec3::NEG_Z * MASSE * 20.0, Vec3::ZERO, false);
    world.advance(TICK);
    conduire(&mut world, entree(0.0, 1.0, 0.0));
    avancer(&mut world, 1.0);
    cap(&world, v)
}

#[test]
fn adr180_la_roue_lit_le_materiau_de_la_cellule_qu_elle_touche() {
    let egal = cap_apres_freinage_sur_deux_sols(1.0, 1.0);
    // Freiné plus fort d'un côté, le véhicule tourne vers lui.
    let droite = cap_apres_freinage_sur_deux_sols(0.2, 1.0);
    let gauche = cap_apres_freinage_sur_deux_sols(1.0, 0.2);
    assert!(egal.abs() < 0.02, "sol égal : {egal} rad");
    assert!(droite < -0.1, "adhérent à droite : {droite} rad");
    assert!(gauche > 0.1, "adhérent à gauche : {gauche} rad");
}

#[test]
fn adr180_le_mouille_et_l_usure_s_appliquent_au_materiau_de_la_cellule() {
    // Même adhérence au sec des deux côtés ; mouillée, la cellule de gauche n'en garde qu'un cinquième.
    let glissante = ContactMaterial {
        wet: 0.2,
        ..contact(1.0)
    };
    let sec = cap_apres_freinage(glissante, contact(1.0), 0.0, 0.0);
    let mouille = cap_apres_freinage(glissante, contact(1.0), 1.0, 0.0);
    let use_et_mouille = cap_apres_freinage(glissante, contact(1.0), 1.0, 1.0);
    assert!(sec.abs() < 0.02, "au sec : {sec} rad");
    assert!(mouille < -0.1, "mouillé, adhérent à droite : {mouille} rad");
    assert!(
        use_et_mouille < -0.1,
        "mouillé et usé, adhérent à droite : {use_et_mouille} rad"
    );
}

#[test]
fn t322_la_distance_d_arret_est_reproductible_et_coherente_avec_le_freinage() {
    let (vitesse, distance) = freiner(berline(), 1.0);
    let (vitesse_bis, distance_bis) = freiner(berline(), 1.0);
    assert_eq!(
        (vitesse.to_bits(), distance.to_bits()),
        (vitesse_bis.to_bits(), distance_bis.to_bits()),
        "reproductible au bit près"
    );
    // Freins plus forts que l'adhérence : les roues bloquent, et le pneu freine entre son pic et sa
    // queue (tail_value 0,75). Calcul, pas une mesure : v² / (2 µ g).
    let au_pic = vitesse * vitesse / (2.0 * GRAVITE);
    let a_la_queue = vitesse * vitesse / (2.0 * 0.75 * GRAVITE);
    assert!(
        distance > au_pic && distance < 1.05 * a_la_queue,
        "{distance} m à {vitesse} m/s, attendu entre {au_pic} et {a_la_queue}"
    );
    let (_, sur_la_glace) = freiner(berline(), 0.3);
    assert!(
        sur_la_glace > 2.5 * distance,
        "R-1070 : {sur_la_glace} contre {distance}"
    );
}

/// Le cercle des moindres carrés de points `(x, z)` : son centre et son rayon (méthode de Kåsa).
fn cercle(points: &[(f64, f64)]) -> (f64, f64, f64) {
    let n = points.len() as f64;
    let (mx, mz) = points
        .iter()
        .fold((0.0, 0.0), |(a, b), (x, z)| (a + x / n, b + z / n));
    let (mut suu, mut svv, mut suv, mut suuu, mut svvv, mut suvv, mut svuu) =
        (0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    for (x, z) in points {
        let (u, v) = (x - mx, z - mz);
        suu += u * u;
        svv += v * v;
        suv += u * v;
        suuu += u * u * u;
        svvv += v * v * v;
        suvv += u * v * v;
        svuu += v * u * u;
    }
    let (b1, b2) = (0.5 * (suuu + suvv), 0.5 * (svvv + svuu));
    let det = suu * svv - suv * suv;
    let uc = (b1 * svv - b2 * suv) / det;
    let vc = (suu * b2 - suv * b1) / det;
    let rayon = (uc * uc + vc * vc + (suu + svv) / n).sqrt();
    (mx + uc, mz + vc, rayon)
}

#[test]
fn t323_le_rayon_du_virage_suit_l_angle_et_l_empattement() {
    let mut d = berline();
    d.steering.speed_sensitivity = 0.0;
    let (mut world, v) = sur_le_plat(d, [200.0, 200.0], 1.0);
    let mut points = Vec::new();
    for tick in 0..(30.0 / TICK) as u32 {
        // Une allure lente et tenue : le pneu glisse peu, la géométrie l'emporte.
        let vitesse = world.velocity(v).expect("vitesse").length();
        let gaz = ((3.0 - vitesse) * 0.3).clamp(0.0, 0.3);
        conduire(&mut world, entree(gaz, 0.0, 0.5));
        world.advance(TICK);
        if tick as f32 * TICK > 10.0 {
            let p = world.pose(v).expect("pose").translation;
            points.push((f64::from(p.x), f64::from(p.z)));
        }
    }
    let (cx, _, rayon) = cercle(&points);
    // Calcul : le centre du virage est sur l'essieu arrière, à L / tan δ ; le centre de masse, à
    // 1,4 m devant cet essieu, décrit un cercle de rayon √((L / tan δ)² + 1,4²).
    let angle = 0.5 * 0.61f64;
    let empattement = 2.8f64;
    let attendu = ((empattement / angle.tan()).powi(2) + 1.4 * 1.4).sqrt();
    assert!(
        (rayon - attendu).abs() < 0.1 * attendu,
        "rayon {rayon} au lieu de {attendu}"
    );
    assert!(cx > 0.0, "à droite : centre en x = {cx}");
}

#[test]
fn adr159_d7_braquer_a_droite_tourne_a_droite() {
    let (mut world, v) = sur_le_plat(berline(), [200.0, 200.0], 1.0);
    conduire(&mut world, entree(0.3, 0.0, 1.0));
    avancer(&mut world, 3.0);
    let roues = world.vehicle_wheels(ASSEMBLY).expect("véhicule");
    assert!(roues[0].steer_angle > 0.1 && roues[1].steer_angle > roues[0].steer_angle);
    assert_eq!(roues[2].steer_angle, 0.0, "l'arrière ne braque pas");
    assert!(
        world.angular_velocity(v).expect("lacet").y < -0.1,
        "rotation horaire vue de dessus"
    );
    assert!(cap(&world, v) < -0.3, "cap {}", cap(&world, v));
    assert!(world.pose(v).expect("pose").translation.x > 0.5);
}

/// Une berline qui accélère de -Z sur une route de friction 1, à travers une plaque de glace de
/// `z = -30` à `z = -50`. Rend le glissement des roues motrices relevé à chaque tick, avec la position.
fn traverser_la_glace(desc: VehicleDesc, gaz: f32) -> Vec<(f32, f32)> {
    let mut world = monde();
    dalle(&mut world, 0.0, -5.0, 0.0, [20.0, 25.0], 1.0);
    dalle(&mut world, 0.0, -40.0, 0.0, [20.0, 10.0], 0.05);
    dalle(&mut world, 0.0, -150.0, 0.0, [20.0, 100.0], 1.0);
    let v = poser(
        &mut world,
        Vec3::new(0.0, hauteur_equilibre(), 10.0),
        Quat::IDENTITY,
        desc,
        &QUATRE,
    );
    conduire(&mut world, VehicleInput::NONE);
    avancer(&mut world, 0.5);
    conduire(&mut world, entree(gaz, 0.0, 0.0));
    let mut releves = Vec::new();
    for _ in 0..(25.0 / TICK) as u32 {
        world.advance(TICK);
        let z = world.pose(v).expect("pose").translation.z;
        let roues = world.vehicle_wheels(ASSEMBLY).expect("véhicule");
        releves.push((z, roues[2].slip_long.max(roues[3].slip_long)));
        if z < -120.0 {
            break;
        }
    }
    releves
}

#[test]
fn t324_sur_la_glace_le_pneu_decroche_puis_reprend_sans_branche_dediee() {
    let releves = traverser_la_glace(berline(), 0.5);
    // Les roues arrière sont à +1,4 du centre : sur la glace quand le centre est entre -51,4 et -31,4.
    let pire = |de: f32, a: f32| {
        releves
            .iter()
            .filter(|(z, _)| *z < de && *z > a)
            .fold(0.0f32, |m, (_, g)| m.max(*g))
    };
    let avant = pire(5.0, -25.0);
    let glace = pire(-33.0, -50.0);
    let apres = releves
        .iter()
        .filter(|(z, _)| *z < -75.0)
        .map(|(_, g)| *g)
        .next_back()
        .expect("sorti de la glace");
    assert!(avant < 0.12, "sur la route, sous le pic : {avant}");
    assert!(glace > 0.5, "sur la glace, il patine : {glace}");
    assert!(apres < 0.12, "revenu sur la route, il reprend : {apres}");
}

#[test]
fn t327_six_roues_sans_code_specifique() {
    let mut d = berline();
    d.wheels = vec![roue(); 6];
    for (rang, roue) in d.wheels.iter_mut().enumerate() {
        roue.flags = if rang < 2 {
            wheel_flags::STEERED
        } else {
            wheel_flags::DRIVEN | wheel_flags::HANDBRAKE
        };
    }
    let six = [
        (-0.8, -1.6),
        (0.8, -1.6),
        (-0.8, 0.6),
        (0.8, 0.6),
        (-0.8, 1.8),
        (0.8, 1.8),
    ];
    let mut world = monde();
    dalle(&mut world, 0.0, 0.0, 0.0, [200.0, 200.0], 1.0);
    let v = poser(
        &mut world,
        Vec3::new(0.0, hauteur_equilibre(), 0.0),
        Quat::IDENTITY,
        d,
        &six,
    );
    conduire(&mut world, VehicleInput::NONE);
    avancer(&mut world, 1.0);
    let repos = world.pose(v).expect("pose").translation;
    assert!(
        (repos.y - hauteur_equilibre()).abs() < 0.03,
        "posé : {repos:?}"
    );
    conduire(&mut world, entree(0.4, 0.0, 0.0));
    avancer(&mut world, 4.0);
    let tout_droit = world.pose(v).expect("pose").translation;
    assert!(tout_droit.z < -5.0, "il avance : {tout_droit:?}");
    conduire(&mut world, entree(0.3, 0.0, -1.0));
    avancer(&mut world, 3.0);
    assert!(
        cap(&world, v) > 0.3,
        "il tourne à gauche : {}",
        cap(&world, v)
    );
    let roues = world.vehicle_wheels(ASSEMBLY).expect("véhicule");
    assert_eq!(roues.len(), 6);
    assert!(roues.iter().all(|r| r.on_ground));
}

#[test]
fn t327_une_roue_et_un_patin_sans_code_specifique() {
    let mut d = berline();
    d.wheels = vec![roue()];
    d.wheels[0].flags = wheel_flags::DRIVEN;
    let mut world = monde();
    dalle(&mut world, 0.0, 0.0, 0.0, [200.0, 200.0], 1.0);
    // Le patin : une semelle glissante sous l'avant du châssis, au niveau du sol ; son frottement est
    // le plus petit des deux, pas leur moyenne.
    let volume = 8.0 * DEMI[0] * DEMI[1] * DEMI[2];
    let patin = BodyCollider {
        shape: Shape::Cuboid {
            half_extents: [0.3, 0.05, 0.3],
        },
        density: 1.0,
        material: ContactMaterial {
            friction_combine: CombineRule::Min,
            ..contact(0.02)
        },
        translation: Vec3::new(0.0, -hauteur_equilibre() + 0.05, -1.4),
        rotation: Quat::IDENTITY,
    };
    let chassis = world
        .add_assembly(
            BodyKind::Dynamic,
            Vec3::new(0.0, hauteur_equilibre() + 0.05, 0.0),
            Quat::IDENTITY,
            &[collider(DEMI, MASSE / volume, 0.5), patin],
        )
        .expect("châssis");
    world.attach_vehicle(
        ASSEMBLY,
        &[chassis],
        d,
        &[WheelMount {
            body: 0,
            center: Vec3::new(0.0, -REPOS, 1.4),
        }],
        &[],
        CollisionGroups::ALL,
    );
    conduire(&mut world, VehicleInput::NONE);
    avancer(&mut world, 1.0);
    conduire(&mut world, entree(0.3, 0.0, 0.0));
    avancer(&mut world, 5.0);
    let p = world.pose(chassis).expect("pose");
    assert!(p.translation.z < -5.0, "il avance : {:?}", p.translation);
    assert!(p.translation.is_finite());
    assert!((p.rotation * Vec3::Y).y > 0.9, "sans se retourner");
    let roue = world.vehicle_wheels(ASSEMBLY).expect("véhicule")[0];
    assert!(roue.on_ground && roue.spin_speed > 1.0, "{roue:?}");
}

/// Une berline dont la roue arrière droite est au-dessus d'une fosse ; rend sa vitesse après une
/// seconde d'accélération, et la vitesse de rotation de la roue en l'air.
fn sortir_de_la_fosse(kind: u8) -> (f32, f32) {
    let mut d = berline();
    d.differential.kind = kind;
    let mut world = monde();
    // La fosse : x > 0,4 et 0,9 < z < 3, sous la roue arrière droite (0,8 ; 1,4).
    dalle(&mut world, -9.8, 0.0, 0.0, [10.2, 30.0], 1.0);
    dalle(&mut world, 10.4, -14.55, 0.0, [10.0, 15.45], 1.0);
    dalle(&mut world, 10.4, 16.5, 0.0, [10.0, 13.5], 1.0);
    let v = poser(
        &mut world,
        Vec3::new(0.0, hauteur_equilibre(), 0.0),
        Quat::IDENTITY,
        d,
        &QUATRE,
    );
    conduire(&mut world, VehicleInput::NONE);
    avancer(&mut world, 1.0);
    assert!(!world.vehicle_wheels(ASSEMBLY).expect("véhicule")[3].on_ground);
    // En marche avant, la roue en l'air rejoint le sol après 0,5 m : on juge avant.
    conduire(&mut world, entree(0.6, 0.0, 0.0));
    let mut en_l_air = 0.0;
    for _ in 0..(1.0 / TICK) as u32 {
        world.advance(TICK);
        let roue = world.vehicle_wheels(ASSEMBLY).expect("véhicule")[3];
        if roue.on_ground {
            break;
        }
        en_l_air = roue.spin_speed;
    }
    (vers_l_avant(&world, v), en_l_air)
}

#[test]
fn t328_bloque_une_roue_en_l_air_ne_consomme_pas_tout_le_couple() {
    let (libre, roue_libre) = sortir_de_la_fosse(differential_kind::OPEN);
    let (bloque, roue_bloquee) = sortir_de_la_fosse(differential_kind::LOCKED);
    assert!(
        bloque > 2.0 * libre.max(0.05),
        "bloqué {bloque} m/s, libre {libre} m/s"
    );
    assert!(
        roue_libre > 2.0 * roue_bloquee,
        "la roue en l'air s'emballe en libre : {roue_libre} contre {roue_bloquee}"
    );
}

/// Une berline posée sur une pente de 10 degrés, l'avant vers le haut ; rend sa dérive pendant cinq
/// secondes, une fois posée, et si elle était encore éveillée à la fin.
fn sur_la_pente(conducteur: Option<VehicleInput>) -> (f32, bool) {
    let mut world = monde();
    let pente = Quat::from_rotation_x(10.0_f32.to_radians());
    world
        .add_assembly(
            BodyKind::Static,
            Vec3::new(0.0, -0.5, 0.0),
            pente,
            &[collider([30.0, 0.5, 200.0], 1000.0, 1.0)],
        )
        .expect("pente");
    let v = poser(
        &mut world,
        Vec3::new(0.0, 1.0, 0.0),
        pente,
        berline(),
        &QUATRE,
    );
    if let Some(e) = conducteur {
        conduire(&mut world, e);
    }
    avancer(&mut world, 3.0);
    let a = world.pose(v).expect("pose").translation;
    avancer(&mut world, 5.0);
    let b = world.pose(v).expect("pose").translation;
    ((b - a).length(), world.is_sleeping(v) == Some(false))
}

#[test]
fn t320_le_frein_a_main_tient_la_pente_sans_deriver() {
    // Le conducteur touche à peine le volant : le véhicule reste éveillé, et la tenue se mesure sous
    // le pas, pas sous le sommeil.
    let (derive, eveille) = sur_la_pente(Some(VehicleInput {
        handbrake: 1.0,
        steer: 0.001,
        ..VehicleInput::NONE
    }));
    assert!(eveille, "mesurée éveillé");
    assert!(derive < 0.002, "dérive de {derive} m en 5 s");
    let (libre, _) = sur_la_pente(Some(VehicleInput::NONE));
    assert!(libre > 1.0, "frein relâché, il descend : {libre} m");
}

#[test]
fn adr159_q3_sans_conducteur_il_ne_devale_pas() {
    let (derive, _) = sur_la_pente(None);
    assert!(derive < 0.005, "dérive de {derive} m en 5 s");
}

#[test]
fn adr163_d1_le_rapport_du_vehicule_dit_son_regime_sa_charge_son_rapport_et_son_conducteur() {
    let (mut world, _) = sur_le_plat(berline(), [100.0, 100.0], 1.0);
    conduire(&mut world, entree(0.6, 0.0, 0.4));
    avancer(&mut world, 2.0);
    let rapports = world.vehicle_reports();
    assert_eq!(rapports.len(), 1, "un véhicule éveillé, un rapport");
    let rapport = rapports[0];
    let telemetrie = world.vehicle_telemetry(ASSEMBLY).expect("véhicule");
    assert_eq!(rapport.handle, ASSEMBLY);
    assert!(
        (rapport.rpm - telemetrie.engine_rpm).abs() < 1e-3,
        "régime {} contre {}",
        rapport.rpm,
        telemetrie.engine_rpm
    );
    assert!(
        rapport.rpm > 800.0,
        "le moteur tourne au-dessus du ralenti : {}",
        rapport.rpm
    );
    assert_eq!(rapport.gear, telemetrie.gear);
    assert!(rapport.gear >= 1, "en marche avant : {}", rapport.gear);
    let attendue = if telemetrie.shifting { 0.0 } else { 0.6 };
    assert!(
        rapport.load <= 0.6 + 1e-6 && (telemetrie.shifting || rapport.load > 0.0),
        "charge {} pour un accélérateur à 0,6 (attendue au plus {attendue})",
        rapport.load
    );
    assert_ne!(
        rapport.flags & vehicle_report_flags::RUNNING,
        0,
        "un motopropulseur tourne"
    );
    assert_ne!(
        rapport.flags & vehicle_report_flags::DRIVER,
        0,
        "le conducteur est assis"
    );
    assert_eq!(
        rapport.flags & vehicle_report_flags::SHIFTING != 0,
        telemetrie.shifting
    );
    // La direction est la position que la vitesse de braquage a atteinte, celle que les roues suivent.
    assert!(
        (rapport.steer - telemetrie.steer).abs() < 1e-6,
        "direction {} contre {}",
        rapport.steer,
        telemetrie.steer
    );
    assert!(
        rapport.steer > 0.3 && rapport.steer <= 0.4 + 1e-6,
        "la direction a rejoint la consigne de 0,4 : {}",
        rapport.steer
    );

    // Sans conducteur, le drapeau tombe, et le pied levé ne charge plus le moteur.
    assert_eq!(
        world.set_vehicle_input(ASSEMBLY, &VehicleInput::NONE, false),
        Some(false)
    );
    avancer(&mut world, 0.5);
    let rapport = world.vehicle_reports()[0];
    assert_eq!(
        rapport.flags & vehicle_report_flags::DRIVER,
        0,
        "personne au volant"
    );
    assert!(
        rapport.load.abs() < 1e-6,
        "charge {} sans accélérateur",
        rapport.load
    );
}

#[test]
fn adr159_d6_en_automatique_le_frein_tenu_a_l_arret_recule() {
    let (mut world, v) = sur_le_plat(berline(), [100.0, 100.0], 1.0);
    let depart = world.pose(v).expect("pose").translation.z;
    conduire(&mut world, entree(0.0, 0.6, 0.0));
    avancer(&mut world, 3.0);
    let telemetrie = world.vehicle_telemetry(ASSEMBLY).expect("véhicule");
    assert_eq!(telemetrie.gear, -1, "marche arrière engagée");
    let recul = world.pose(v).expect("pose").translation.z - depart;
    assert!(recul > 1.0, "il recule vers +Z : {recul} m");
    // L'accélérateur, en marche arrière, freine.
    conduire(&mut world, entree(1.0, 0.0, 0.0));
    avancer(&mut world, 1.0);
    assert!(world.velocity(v).expect("vitesse").z.abs() < 0.5);
}

#[test]
fn en_automatique_la_boite_monte_ses_rapports_sans_depasser_le_regime_maximal() {
    let (mut world, v) = sur_le_plat(berline(), [40.0, 1500.0], 1.0);
    conduire(&mut world, entree(0.6, 0.0, 0.0));
    let mut rapport = 1;
    let mut montees = 0;
    let mut regime_max: f32 = 0.0;
    for _ in 0..(20.0 / TICK) as u32 {
        world.advance(TICK);
        let t = world.vehicle_telemetry(ASSEMBLY).expect("véhicule");
        regime_max = regime_max.max(t.engine_rpm);
        if t.gear > rapport {
            montees += 1;
        }
        assert!(
            t.gear >= rapport,
            "jamais de descente en accélérant : {} puis {}",
            rapport,
            t.gear
        );
        rapport = t.gear;
    }
    assert!(montees >= 2, "{montees} montée(s), rapport {rapport}");
    assert!(regime_max <= 7000.5, "régime {regime_max}");
    assert!(vers_l_avant(&world, v) > 25.0);
}

#[test]
fn en_manuelle_le_point_mort_n_entraine_rien() {
    let mut d = berline();
    d.transmission.kind = transmission_kind::MANUAL;
    let (mut world, v) = sur_le_plat(d, [100.0, 100.0], 1.0);
    conduire(&mut world, entree(1.0, 0.0, 0.0));
    avancer(&mut world, 2.0);
    let t = world.vehicle_telemetry(ASSEMBLY).expect("véhicule");
    assert_eq!(t.gear, 0);
    assert!(
        t.engine_rpm > 6900.0,
        "le moteur s'emballe à vide : {}",
        t.engine_rpm
    );
    assert!(vers_l_avant(&world, v).abs() < 0.01, "rien n'avance");
    conduire(
        &mut world,
        VehicleInput {
            gear: 1,
            ..entree(0.5, 0.0, 0.0)
        },
    );
    avancer(&mut world, 2.0);
    assert_eq!(world.vehicle_telemetry(ASSEMBLY).expect("véhicule").gear, 1);
    assert!(vers_l_avant(&world, v) > 1.0, "en première, il part");
}

#[test]
fn r1090_l_abs_raccourcit_le_freinage_et_garde_les_roues_vivantes() {
    let (_, bloque) = freiner(berline(), 1.0);
    let mut d = berline();
    d.flags |= vehicle_flags::ABS;
    let (_, abs) = freiner(d, 1.0);
    assert!(
        abs < 0.92 * bloque,
        "ABS {abs} m, roues bloquées {bloque} m"
    );
}

#[test]
fn r1090_l_antipatinage_limite_le_patinage_et_tire_plus_loin() {
    let lancer = |d: VehicleDesc| {
        let (mut world, v) = sur_le_plat(d, [40.0, 300.0], 0.3);
        conduire(&mut world, entree(1.0, 0.0, 0.0));
        let mut pire: f32 = 0.0;
        for _ in 0..(3.0 / TICK) as u32 {
            world.advance(TICK);
            let r = world.vehicle_wheels(ASSEMBLY).expect("véhicule");
            pire = pire.max(r[2].slip_long);
        }
        (vers_l_avant(&world, v), pire)
    };
    let (sans, patinage_sans) = lancer(berline());
    let mut d = berline();
    d.flags |= vehicle_flags::TRACTION_CONTROL;
    let (avec, patinage_avec) = lancer(d);
    assert!(
        patinage_sans > 1.0,
        "sans antipatinage, il patine : {patinage_sans}"
    );
    assert!(
        patinage_avec < 0.5 * patinage_sans,
        "{patinage_avec} contre {patinage_sans}"
    );
    assert!(avec > sans, "plus vite avec : {avec} contre {sans}");
}

#[test]
fn r1090_le_controle_de_stabilite_ramene_le_lacet_vers_celui_vise() {
    // Un arrière moins adhérent en latéral que l'avant : à la limite, sans correcteur, il part en
    // tête-à-queue.
    let survireuse = || {
        let mut d = berline();
        for roue in d.wheels.iter_mut().skip(2) {
            roue.mu_lat = 0.7;
        }
        d
    };
    let virer = |d: VehicleDesc| {
        let (mut world, v) = sur_le_plat(d, [400.0, 400.0], 0.6);
        world.apply_impulse(v, Vec3::NEG_Z * MASSE * 18.0, Vec3::ZERO, false);
        avancer(&mut world, 0.5);
        let mut ecart = 0.0;
        for _ in 0..(2.5 / TICK) as u32 {
            conduire(&mut world, entree(0.0, 0.0, 1.0));
            world.advance(TICK);
            let vitesse = vers_l_avant(&world, v);
            let t = world.vehicle_telemetry(ASSEMBLY).expect("véhicule");
            let angle = t.steer * 0.61 * (1.0 - 0.6 * (vitesse.abs() / 30.0).min(1.0));
            let vise = ax_vehicle::brakes::target_yaw_rate(vitesse, angle, 2.8, 0.6, GRAVITE);
            ecart += (world.angular_velocity(v).expect("lacet").y - vise).abs() * TICK;
        }
        (ecart, cap(&world, v))
    };
    let (sans, cap_sans) = virer(survireuse());
    let mut d = survireuse();
    d.flags |= vehicle_flags::STABILITY_CONTROL;
    let (avec, cap_avec) = virer(d);
    assert!(avec < 0.5 * sans, "écart cumulé {avec} avec, {sans} sans");
    assert!(
        cap_sans < -2.0,
        "sans correcteur, il tourne sur lui-même : {cap_sans}"
    );
    assert!(cap_avec > -2.0, "avec : {cap_avec}");
}

#[test]
fn aero_l_appui_charge_les_roues_avec_la_vitesse() {
    let charge = |appui: f32| {
        let mut d = berline();
        d.aero.downforce_front = appui;
        d.aero.downforce_rear = appui;
        let (mut world, v) = sur_le_plat(d, [40.0, 800.0], 1.0);
        world.apply_impulse(v, Vec3::NEG_Z * MASSE * 30.0, Vec3::ZERO, false);
        avancer(&mut world, 1.0);
        world
            .vehicle_wheels(ASSEMBLY)
            .expect("véhicule")
            .iter()
            .map(|r| r.load)
            .sum::<f32>()
    };
    let sans = charge(0.0);
    let avec = charge(2.0);
    // Calcul : ½ ρ c v² à chaque essieu, vers 29 m/s : environ 2 000 N.
    assert!(
        (sans - MASSE * GRAVITE).abs() < 0.05 * MASSE * GRAVITE,
        "{sans}"
    );
    assert!(avec > sans + 1000.0, "appui : {avec} contre {sans}");
}

#[test]
fn adr159_d3_une_entree_reveille_le_vehicule_et_le_conducteur_seul_non() {
    let (mut world, v) = sur_le_plat(berline(), [100.0, 100.0], 1.0);
    avancer(&mut world, 6.0);
    assert_eq!(
        world.is_sleeping(v),
        Some(true),
        "conducteur assis, sans entrée : il dort"
    );
    conduire(
        &mut world,
        VehicleInput {
            handbrake: 1.0,
            ..VehicleInput::NONE
        },
    );
    avancer(&mut world, 0.1);
    assert_eq!(
        world.is_sleeping(v),
        Some(true),
        "le frein à main seul ne le réveille pas"
    );
    conduire(&mut world, entree(0.5, 0.0, 0.0));
    assert_eq!(world.is_sleeping(v), Some(false));
    avancer(&mut world, 1.0);
    assert!(vers_l_avant(&world, v) > 0.5);
}

#[test]
fn r1110_une_entree_non_finie_compte_pour_zero() {
    let (mut world, v) = sur_le_plat(berline(), [100.0, 100.0], 1.0);
    let folle = VehicleInput {
        throttle: f32::NAN,
        steer: 9.0,
        ..VehicleInput::NONE
    };
    assert_eq!(world.set_vehicle_input(ASSEMBLY, &folle, true), Some(true));
    avancer(&mut world, 1.0);
    assert_eq!(
        world.vehicle_telemetry(ASSEMBLY).expect("véhicule").steer,
        1.0,
        "bornée à 1"
    );
    assert!(vers_l_avant(&world, v).abs() < 0.01, "NaN n'accélère pas");
    let autre = Handle {
        index: 9,
        generation: 1,
    };
    assert_eq!(
        world.set_vehicle_input(autre, &folle, true),
        None,
        "pas un véhicule"
    );
}

#[test]
fn adr159_d8_le_conducteur_absent_serre_le_frein_et_present_le_relache() {
    let (mut world, v) = sur_le_plat(berline(), [100.0, 100.0], 1.0);
    world.apply_impulse(v, Vec3::NEG_Z * MASSE * 3.0, Vec3::ZERO, false);
    avancer(&mut world, 1.0);
    let roule = vers_l_avant(&world, v);
    assert!(roule > 1.5, "conducteur assis, roues libres : {roule}");
    assert_eq!(
        world.set_vehicle_input(ASSEMBLY, &VehicleInput::NONE, false),
        Some(false)
    );
    avancer(&mut world, 2.0);
    assert!(
        vers_l_avant(&world, v).abs() < 0.05,
        "siège libre : frein serré"
    );
    assert!(!world.vehicle_telemetry(ASSEMBLY).expect("véhicule").driver);
}

/// Les entrées d'une manœuvre : pied au plancher, la direction qui va et vient.
fn manoeuvre(ticks: u32) -> Vec<VehicleInput> {
    (0..ticks)
        .map(|k| entree(1.0, 0.0, if (k / 10) % 2 == 0 { 0.6 } else { -0.6 }))
        .collect()
}

/// Joue des entrées, une par tick, et rend la pose et la vitesse du châssis à la fin.
fn jouer(world: &mut PhysicsWorld, v: BodyId, entrees: &[VehicleInput]) -> (Vec3, Quat, Vec3) {
    for e in entrees {
        conduire(world, *e);
        world.advance(TICK);
    }
    let (position, rotation, vitesse, _) = world.live_body(v).expect("châssis");
    (position, rotation, vitesse)
}

#[test]
fn adr168_d1_l_etat_du_vehicule_se_relit_et_se_rend() {
    let (mut world, _) = sur_le_plat(berline(), [40.0, 2500.0], 1.0);
    conduire(&mut world, entree(1.0, 0.0, 0.4));
    avancer(&mut world, 1.0);
    let etat = world.vehicle_snapshot(ASSEMBLY).expect("un véhicule");
    assert_eq!(etat.wheels.len(), 4);
    assert!(
        etat.engine_speed > 0.0 && etat.steering > 0.0,
        "il roule, braqué : {etat:?}"
    );
    // Dans le bloc, puis relu : le même état.
    let bloc = ax_physics::state::AssemblyState {
        bodies: Vec::new(),
        joints: Vec::new(),
        vehicle: Some(etat.clone()),
    };
    let relu = ax_physics::state::AssemblyState::decode(&bloc.encode()).expect("lisible");
    assert_eq!(relu.vehicle.as_ref(), Some(&etat));

    avancer(&mut world, 0.5);
    assert_ne!(
        world.vehicle_snapshot(ASSEMBLY).as_ref(),
        Some(&etat),
        "le véhicule a changé"
    );
    assert!(world.restore_vehicle(ASSEMBLY, &etat));
    assert_eq!(
        world.vehicle_snapshot(ASSEMBLY),
        Some(etat),
        "rendu tel quel"
    );
    let mut trois = world.vehicle_snapshot(ASSEMBLY).unwrap();
    trois.wheels.pop();
    assert!(
        !world.restore_vehicle(ASSEMBLY, &trois),
        "trois roues pour quatre : refusé"
    );
    assert!(
        !monde().restore_vehicle(ASSEMBLY, &trois),
        "sans véhicule, rien"
    );
}

#[test]
fn adr168_d5_restaurer_puis_rejouer_redonne_la_course_d_origine() {
    // R-1682 : le client restaure l'état du serveur puis rejoue ses entrées, un tick par entrée. Sur une même
    // plateforme, l'état exporté suffit à refaire la même course au bit près : la rotation des roues, gardée par
    // leur entraînement, comprise. Ici le châssis ne touche rien : l'état de contact que rapier garde d'un pas à
    // l'autre, et que le bloc ne porte pas, n'entre pas en jeu.
    let (mut world, v) = sur_le_plat(berline(), [40.0, 2500.0], 1.0);
    conduire(&mut world, entree(1.0, 0.0, 0.0));
    avancer(&mut world, 1.0);
    let corps = world.live_body(v).expect("châssis");
    let vehicule = world.vehicle_snapshot(ASSEMBLY).expect("véhicule");
    let entrees = manoeuvre(64);
    let origine = jouer(&mut world, v, &entrees);
    let fin = world.vehicle_snapshot(ASSEMBLY);
    assert!((origine.0 - corps.0).length() > 3.0, "la manœuvre a roulé");

    world.set_live_body(v, corps.0, corps.1, corps.2, corps.3);
    assert!(world.restore_vehicle(ASSEMBLY, &vehicule));
    let rejeu = jouer(&mut world, v, &entrees);
    assert_eq!(
        rejeu, origine,
        "le rejeu redonne la pose et la vitesse d'origine"
    );
    assert_eq!(
        world.vehicle_snapshot(ASSEMBLY),
        fin,
        "et l'état du véhicule"
    );
}

/// La berline, avec un démarreur de `temps` secondes (ADR-176 D1).
fn berline_a_demarreur(temps: f32) -> VehicleDesc {
    let mut d = berline();
    d.engine.starter_time = temps;
    d
}

/// Une entrée de conducteur, contact mis ou coupé.
fn avec_contact(e: VehicleInput, mis: bool) -> VehicleInput {
    VehicleInput {
        native_flags: if mis { native_input_flags::IGNITION } else { 0 },
        ..e
    }
}

#[test]
fn adr176_d1_un_moteur_a_demarreur_ne_donne_rien_tant_que_le_contact_n_est_pas_mis() {
    let (mut world, v) = sur_le_plat(berline_a_demarreur(0.5), [100.0, 100.0], 1.0);
    let etat = world.vehicle_snapshot(ASSEMBLY).expect("véhicule");
    assert!(!etat.engine.running(), "il attend son démarreur : {etat:?}");
    assert_eq!(etat.engine_speed, 0.0, "arrêté, il ne tourne pas");
    conduire(&mut world, avec_contact(entree(1.0, 0.0, 0.0), false));
    avancer(&mut world, 2.0);
    assert!(
        vers_l_avant(&world, v).abs() < 0.05,
        "plein gaz, contact coupé : il n'avance pas ({})",
        vers_l_avant(&world, v)
    );
    let rapport = world.vehicle_telemetry(ASSEMBLY).expect("véhicule");
    assert_eq!(rapport.engine_rpm, 0.0, "régime nul");
    for rapport in world.vehicle_reports() {
        assert_eq!(
            rapport.flags & vehicle_report_flags::RUNNING,
            0,
            "pas tournant"
        );
    }
}

#[test]
fn adr176_d1_le_contact_lance_le_demarreur_puis_le_moteur_tourne_et_emporte_le_vehicule() {
    let (mut world, v) = sur_le_plat(berline_a_demarreur(0.5), [100.0, 100.0], 1.0);
    conduire(&mut world, avec_contact(entree(1.0, 0.0, 0.0), true));
    avancer(&mut world, 0.25);
    let pendant = world.vehicle_snapshot(ASSEMBLY).expect("véhicule");
    assert!(
        pendant.engine.cranking(),
        "le démarreur tourne : {pendant:?}"
    );
    assert!(
        vers_l_avant(&world, v).abs() < 0.05,
        "le démarreur ne donne aucun couple"
    );
    avancer(&mut world, 0.5);
    let apres = world.vehicle_snapshot(ASSEMBLY).expect("véhicule");
    assert!(apres.engine.running(), "le moteur a pris : {apres:?}");
    avancer(&mut world, 3.0);
    assert!(
        vers_l_avant(&world, v) > 3.0,
        "il roule : {}",
        vers_l_avant(&world, v)
    );
    let rapports = world.vehicle_reports();
    assert_eq!(rapports.len(), 1);
    assert_ne!(
        rapports[0].flags & vehicle_report_flags::RUNNING,
        0,
        "tournant"
    );
    assert!(
        rapports[0].rpm >= 800.0,
        "au moins au ralenti : {}",
        rapports[0].rpm
    );
}

#[test]
fn adr176_d1_couper_le_contact_arrete_le_moteur_et_la_boite_automatique_passe_au_point_mort() {
    let (mut world, v) = sur_le_plat(berline_a_demarreur(0.2), [200.0, 200.0], 1.0);
    conduire(&mut world, avec_contact(entree(1.0, 0.0, 0.0), true));
    avancer(&mut world, 4.0);
    let avant = vers_l_avant(&world, v);
    assert!(avant > 3.0, "il roule : {avant}");
    conduire(&mut world, avec_contact(entree(1.0, 0.0, 0.0), false));
    avancer(&mut world, 0.5);
    let etat = world.vehicle_snapshot(ASSEMBLY).expect("véhicule");
    assert!(!etat.engine.running(), "arrêté : {etat:?}");
    assert_eq!(etat.load, 0.0, "plus de charge");
    // Plein gaz et moteur arrêté : le véhicule n'accélère plus, il roule sur son erreur (aucune traînée dans cette
    // description). Moteur tournant, il aurait gagné plusieurs m/s en deux secondes.
    let apres_coupure = vers_l_avant(&world, v);
    avancer(&mut world, 2.0);
    assert!(
        vers_l_avant(&world, v) < apres_coupure + 0.05,
        "sans moteur, le gaz ne donne plus rien : {} puis {}",
        apres_coupure,
        vers_l_avant(&world, v)
    );
}

#[test]
fn adr176_d1_le_contact_seul_reveille_un_vehicule_gare() {
    let (mut world, _) = sur_le_plat(berline_a_demarreur(0.4), [100.0, 100.0], 1.0);
    avancer(&mut world, 1.0);
    // Aucun accélérateur, aucun frein, aucune direction : seul le contact change.
    conduire(&mut world, avec_contact(VehicleInput::NONE, true));
    avancer(&mut world, 1.0);
    let etat = world.vehicle_snapshot(ASSEMBLY).expect("véhicule");
    assert!(
        etat.engine.running(),
        "le démarreur a tourné jusqu'au bout : {etat:?}"
    );
    conduire(&mut world, avec_contact(VehicleInput::NONE, false));
    avancer(&mut world, 0.2);
    let etat = world.vehicle_snapshot(ASSEMBLY).expect("véhicule");
    assert!(
        !etat.engine.running(),
        "et le contact coupé l'a arrêté : {etat:?}"
    );
}

#[test]
fn adr176_d1_sans_demarreur_le_contact_est_sans_effet() {
    let (mut world, v) = sur_le_plat(berline(), [100.0, 100.0], 1.0);
    conduire(&mut world, avec_contact(entree(1.0, 0.0, 0.0), false));
    avancer(&mut world, 3.0);
    assert!(vers_l_avant(&world, v) > 3.0, "il roule contact « coupé »");
    let etat = world.vehicle_snapshot(ASSEMBLY).expect("véhicule");
    assert!(etat.engine.running());
}

#[test]
fn adr176_d1_l_etat_du_moteur_et_le_contact_passent_dans_le_bloc_d_etat() {
    let (mut world, _) = sur_le_plat(berline_a_demarreur(0.5), [100.0, 100.0], 1.0);
    conduire(&mut world, avec_contact(entree(0.3, 0.0, 0.0), true));
    avancer(&mut world, 0.25);
    let etat = world.vehicle_snapshot(ASSEMBLY).expect("véhicule");
    assert!(etat.engine.cranking());
    assert_ne!(etat.input.native_flags & native_input_flags::IGNITION, 0);
    let bloc = ax_physics::state::AssemblyState {
        bodies: Vec::new(),
        joints: Vec::new(),
        vehicle: Some(etat.clone()),
    };
    let relu = ax_physics::state::AssemblyState::decode(&bloc.encode()).expect("lisible");
    assert_eq!(
        relu.vehicle.as_ref(),
        Some(&etat),
        "le démarreur et le contact se relisent"
    );
    // Rendu au monde, l'état refait le même démarrage.
    avancer(&mut world, 1.0);
    assert!(world.restore_vehicle(ASSEMBLY, &etat));
    assert_eq!(world.vehicle_snapshot(ASSEMBLY), Some(etat));
}

/// La berline à démarreur avec un petit réservoir (0,5 l) et une batterie (ADR-176 D3) : 36 l/h à vide, 360 l/h à pleine
/// charge, à régime maximal.
fn berline_a_reservoir() -> VehicleDesc {
    let mut d = berline_a_demarreur(0.2);
    d.energy = EnergyDesc {
        capacities: [0.5, 1.0, 0.0, 0.0],
        fuel_store: 0,
        battery_store: 1,
        starter_draw: 0.02,
        charge_rate: 0.002,
        consumption: vec![[0.0, 36.0], [1.0, 360.0]],
    };
    d
}

/// Pose l'énergie d'un véhicule : le test fait ce que `SET_ENERGY` fera.
fn poser_l_energie(world: &mut PhysicsWorld, fuel: f64, battery: f64) {
    let mut etat = world.vehicle_snapshot(ASSEMBLY).expect("véhicule");
    etat.energy.stores = [fuel, battery, 0.0, 0.0];
    assert!(world.restore_vehicle(ASSEMBLY, &etat));
}

#[test]
fn adr176_d3_un_vehicule_neuf_a_son_reservoir_plein_et_le_brule_en_roulant() {
    let (mut world, v) = sur_le_plat(berline_a_reservoir(), [200.0, 200.0], 1.0);
    let neuf = world.vehicle_snapshot(ASSEMBLY).expect("véhicule");
    assert_eq!(neuf.energy.stores[0], 0.5f32 as f64);
    assert_eq!(neuf.energy.stores[1], 1.0);
    conduire(&mut world, avec_contact(entree(1.0, 0.0, 0.0), true));
    avancer(&mut world, 3.0);
    let apres = world.vehicle_snapshot(ASSEMBLY).expect("véhicule");
    assert!(apres.engine.running(), "il roule : {apres:?}");
    assert!(
        apres.energy.stores[0] < neuf.energy.stores[0] && apres.energy.stores[0] > 0.0,
        "il a brûlé un peu : {} litre",
        apres.energy.stores[0]
    );
    assert!(
        apres.energy.stores[1] > 0.99 && apres.energy.stores[1] <= 1.0,
        "le démarreur a tiré 0,004, le moteur l'a rendu : {}",
        apres.energy.stores[1]
    );
    assert!(vers_l_avant(&world, v) > 3.0);
}

#[test]
fn adr176_d3_le_reservoir_vide_arrete_le_moteur_et_il_ne_repart_pas_sans_nouveau_front() {
    let (mut world, _) = sur_le_plat(berline_a_reservoir(), [2000.0, 2000.0], 1.0);
    conduire(&mut world, avec_contact(entree(1.0, 0.0, 0.0), true));
    for _ in 0..60 {
        avancer(&mut world, 1.0);
        if !world
            .vehicle_snapshot(ASSEMBLY)
            .expect("véhicule")
            .engine
            .running()
        {
            break;
        }
    }
    let panne = world.vehicle_snapshot(ASSEMBLY).expect("véhicule");
    assert!(!panne.engine.running(), "panne sèche : {panne:?}");
    assert_eq!(panne.energy.stores[0], 0.0);
    assert_eq!(panne.load, 0.0, "plus de charge moteur");
    // On remplit, contact toujours mis : pas de front, pas de démarrage.
    poser_l_energie(&mut world, 0.5, 1.0);
    avancer(&mut world, 1.0);
    assert!(
        !world
            .vehicle_snapshot(ASSEMBLY)
            .expect("véhicule")
            .engine
            .running(),
        "plein fait, contact tenu : il reste arrêté"
    );
    // Le contact coupé puis remis relance le démarreur.
    conduire(&mut world, avec_contact(entree(0.0, 0.0, 0.0), false));
    avancer(&mut world, 0.2);
    conduire(&mut world, avec_contact(entree(0.0, 0.0, 0.0), true));
    avancer(&mut world, 1.0);
    assert!(
        world
            .vehicle_snapshot(ASSEMBLY)
            .expect("véhicule")
            .engine
            .running(),
        "un nouveau front le relance"
    );
}

#[test]
fn adr176_d3_sans_carburant_ou_sans_batterie_le_demarreur_ne_part_pas() {
    for (fuel, battery) in [(0.0, 1.0), (0.5, 0.0)] {
        let (mut world, _) = sur_le_plat(berline_a_reservoir(), [100.0, 100.0], 1.0);
        poser_l_energie(&mut world, fuel, battery);
        conduire(&mut world, avec_contact(entree(0.0, 0.0, 0.0), true));
        avancer(&mut world, 1.0);
        let etat = world.vehicle_snapshot(ASSEMBLY).expect("véhicule");
        assert!(
            !etat.engine.running() && !etat.engine.cranking(),
            "carburant {fuel}, batterie {battery} : {etat:?}"
        );
        assert_eq!(
            etat.energy.stores[1], battery,
            "un démarreur qui ne part pas ne tire rien"
        );
    }
}

#[test]
fn adr176_d3_un_vehicule_endormi_ne_brule_rien() {
    let (mut world, _) = sur_le_plat(berline_a_reservoir(), [100.0, 100.0], 1.0);
    conduire(&mut world, avec_contact(VehicleInput::NONE, true));
    avancer(&mut world, 1.0);
    // Le moteur tourne au ralenti ; sans autre commande, le véhicule finit par s'endormir.
    avancer(&mut world, 10.0);
    assert!(world.vehicle_reports().is_empty(), "il dort");
    let avant = world.vehicle_snapshot(ASSEMBLY).expect("véhicule").energy;
    avancer(&mut world, 10.0);
    assert_eq!(
        world.vehicle_snapshot(ASSEMBLY).expect("véhicule").energy,
        avant,
        "endormi, il ne brûle rien (la combustion suit l'éveil)"
    );
}

#[test]
fn adr176_d3_la_consommation_est_deterministe_au_bit_pres() {
    let course = || {
        let (mut world, _) = sur_le_plat(berline_a_reservoir(), [200.0, 200.0], 1.0);
        conduire(&mut world, avec_contact(entree(0.8, 0.0, 0.1), true));
        avancer(&mut world, 4.0);
        world.vehicle_snapshot(ASSEMBLY).expect("véhicule").energy
    };
    let (a, b) = (course(), course());
    assert_eq!(a.stores[0].to_bits(), b.stores[0].to_bits());
    assert_eq!(a.stores[1].to_bits(), b.stores[1].to_bits());
}

#[test]
fn adr176_d3_l_energie_se_relit_dans_le_bloc_d_etat_et_reste_dans_les_capacites() {
    let (mut world, _) = sur_le_plat(berline_a_reservoir(), [100.0, 100.0], 1.0);
    conduire(&mut world, avec_contact(entree(0.3, 0.0, 0.0), true));
    avancer(&mut world, 2.0);
    let etat = world.vehicle_snapshot(ASSEMBLY).expect("véhicule");
    let bloc = ax_physics::state::AssemblyState {
        bodies: Vec::new(),
        joints: Vec::new(),
        vehicle: Some(etat.clone()),
    };
    let octets = bloc.encode();
    let relu = ax_physics::state::AssemblyState::decode(&octets).expect("lisible");
    assert_eq!(
        relu.vehicle.as_ref(),
        Some(&etat),
        "carburant et batterie se relisent"
    );
    // Un état relu qui dépasse les capacités y est ramené.
    poser_l_energie(&mut world, 1e6, 1e6);
    let borne = world.vehicle_snapshot(ASSEMBLY).expect("véhicule");
    assert_eq!(borne.energy.stores[0], 0.5f32 as f64);
    assert_eq!(borne.energy.stores[1], 1.0);
}

#[test]
fn adr176_d3_set_energy_pose_le_plein_sans_reveiller_et_garde_ce_qui_est_nan() {
    let (mut world, _) = sur_le_plat(berline_a_reservoir(), [100.0, 100.0], 1.0);
    avancer(&mut world, 3.0);
    assert!(world.vehicle_reports().is_empty(), "il dort");
    assert!(world.set_vehicle_energy(ASSEMBLY, [0.25, f32::NAN, f32::NAN, f32::NAN]));
    let etat = world.vehicle_snapshot(ASSEMBLY).expect("véhicule");
    assert_eq!(etat.energy.stores[0], 0.25);
    assert_eq!(etat.energy.stores[1], 1.0, "NaN garde la batterie");
    assert!(
        world.vehicle_reports().is_empty(),
        "un plein ne réveille pas"
    );
    assert!(world.set_vehicle_energy(ASSEMBLY, [99.0, -3.0, 0.0, 0.0]));
    let etat = world.vehicle_snapshot(ASSEMBLY).expect("véhicule");
    assert_eq!(etat.energy.stores[0], 0.5f32 as f64, "ramené à la capacité");
    assert_eq!(etat.energy.stores[1], 0.0, "ramené à zéro");
    assert!(
        !monde().set_vehicle_energy(ASSEMBLY, [1.0; 4]),
        "pas de véhicule"
    );
}

#[test]
fn adr176_d3_le_rapport_porte_le_carburant_et_la_batterie() {
    let (mut world, _) = sur_le_plat(berline_a_reservoir(), [100.0, 100.0], 1.0);
    conduire(&mut world, avec_contact(entree(0.5, 0.0, 0.0), true));
    avancer(&mut world, 1.0);
    let rapports = world.vehicle_reports();
    assert_eq!(rapports.len(), 1);
    let etat = world.vehicle_snapshot(ASSEMBLY).expect("véhicule");
    assert_eq!(rapports[0].stores[0], etat.energy.stores[0] as f32);
    assert_eq!(rapports[0].stores[1], etat.energy.stores[1] as f32);
    assert!(rapports[0].stores[0] > 0.0 && rapports[0].stores[0] < 0.5);
}

#[test]
fn adr182_le_moteur_brule_la_reserve_qu_il_nomme_et_laisse_les_autres() {
    // Le moteur au rang 2, le démarreur au rang 0, une cargaison au rang 1 : chacun la sienne.
    let mut d = berline_a_reservoir();
    d.energy.capacities = [1.0, 5.0, 0.5, 0.0];
    d.energy.fuel_store = 2;
    d.energy.battery_store = 0;
    let (mut world, _) = sur_le_plat(d, [200.0, 200.0], 1.0);
    conduire(&mut world, avec_contact(entree(1.0, 0.0, 0.0), true));
    avancer(&mut world, 3.0);
    let etat = world.vehicle_snapshot(ASSEMBLY).expect("véhicule");
    assert!(etat.engine.running(), "{etat:?}");
    assert!(
        etat.energy.stores[2] < 0.5 && etat.energy.stores[2] > 0.0,
        "le moteur brûle le rang 2 : {:?}",
        etat.energy
    );
    assert!(etat.energy.stores[0] > 0.99, "le démarreur au rang 0");
    assert_eq!(etat.energy.stores[1], 5.0, "la cargaison ne bouge pas");
    let rapport = world.vehicle_reports()[0];
    assert_eq!(
        rapport.stores,
        etat.energy.stores.map(|store| store as f32),
        "le rapport porte les quatre réserves"
    );
    // La cargaison se pose et se garde, sans toucher au reste.
    assert!(world.set_vehicle_energy(ASSEMBLY, [f32::NAN, 2.0, f32::NAN, f32::NAN]));
    let pose = world.vehicle_snapshot(ASSEMBLY).expect("véhicule");
    assert_eq!(pose.energy.stores[1], 2.0);
    assert_eq!(pose.energy.stores[2], etat.energy.stores[2]);
}

/// La berline dont les pneus s'usent de `taux` par kilojoule de frottement (ADR-176 D4).
fn berline_a_pneus_uses(taux: f32) -> VehicleDesc {
    let mut d = berline();
    for roue in &mut d.wheels {
        roue.wear_rate = taux;
        roue.wear_grip_loss = 0.4;
    }
    d
}

fn usures(world: &PhysicsWorld) -> Vec<f64> {
    world
        .vehicle_snapshot(ASSEMBLY)
        .expect("véhicule")
        .wheels
        .iter()
        .map(|roue| roue.wear)
        .collect()
}

#[test]
fn adr176_d4_un_pneu_qui_patine_s_use_et_un_pneu_qui_roule_a_peine() {
    // Plein gaz de l'arrêt : les roues motrices (les deux dernières) patinent, les roues avant roulent.
    let (mut world, _) = sur_le_plat(berline_a_pneus_uses(0.02), [300.0, 300.0], 1.0);
    conduire(&mut world, entree(1.0, 0.0, 0.0));
    avancer(&mut world, 3.0);
    let depart = usures(&world);
    assert!(
        depart[2] > 0.0 && depart[3] > 0.0,
        "les roues motrices s'usent : {depart:?}"
    );
    assert!(
        depart[2] > 4.0 * depart[0].max(1e-12) && depart[3] > 4.0 * depart[1].max(1e-12),
        "bien plus que les roues avant, qui roulent : {depart:?}"
    );
    // Lancé, puis laissé rouler en ligne droite, sans gaz ni frein : le pneu ne glisse plus, l'usure ne monte presque plus.
    conduire(&mut world, entree(0.0, 0.0, 0.0));
    avancer(&mut world, 2.0);
    let avant = usures(&world);
    avancer(&mut world, 3.0);
    let apres = usures(&world);
    let gain = apres[2] - avant[2];
    assert!(
        gain >= 0.0 && gain < 0.2 * (depart[2]).max(1e-9),
        "à vitesse établie, peu d'usure ({gain}) devant celle du départ ({})",
        depart[2]
    );
}

#[test]
fn adr176_d4_un_pneu_use_adhere_moins_et_un_pneu_sans_taux_ne_s_use_jamais() {
    let course = |usure: f32| {
        let (mut world, v) = sur_le_plat(berline_a_pneus_uses(0.0), [300.0, 300.0], 1.0);
        for roue in 0..4 {
            assert!(world.set_wheel_wear(ASSEMBLY, roue, usure));
        }
        conduire(&mut world, entree(1.0, 0.0, 0.0));
        avancer(&mut world, 3.0);
        (vers_l_avant(&world, v), usures(&world))
    };
    let (neuf, usures_neuf) = course(0.0);
    let (use_, _) = course(1.0);
    assert!(
        use_ < neuf,
        "un pneu usé à fond accélère moins : {use_} contre {neuf}"
    );
    assert!(
        usures_neuf.iter().all(|u| *u == 0.0),
        "sans taux, aucune usure : {usures_neuf:?}"
    );
}

#[test]
fn adr176_d4_un_vehicule_endormi_n_use_rien_et_set_wheel_wear_ne_le_reveille_pas() {
    let (mut world, _) = sur_le_plat(berline_a_pneus_uses(0.05), [100.0, 100.0], 1.0);
    avancer(&mut world, 3.0);
    assert!(world.vehicle_reports().is_empty(), "il dort");
    assert!(usures(&world).iter().all(|u| *u == 0.0));
    assert!(world.set_wheel_wear(ASSEMBLY, 1, 0.25));
    assert!(
        world.vehicle_reports().is_empty(),
        "poser l'usure ne réveille pas"
    );
    assert!(world.set_wheel_wear(ASSEMBLY, 2, f32::NAN), "NaN garde");
    assert!(world.set_wheel_wear(ASSEMBLY, 3, 9.0));
    assert!(!world.set_wheel_wear(ASSEMBLY, 9, 0.5), "pas de roue 9");
    assert!(!monde().set_wheel_wear(ASSEMBLY, 0, 0.5), "pas de véhicule");
    let u = usures(&world);
    assert_eq!(u, vec![0.0, f64::from(0.25f32), 0.0, 1.0], "borné à [0, 1]");
}

#[test]
fn adr176_d4_l_usure_se_relit_dans_le_bloc_d_etat_et_se_rend() {
    let (mut world, _) = sur_le_plat(berline_a_pneus_uses(0.02), [300.0, 300.0], 1.0);
    conduire(&mut world, entree(1.0, 0.0, 0.0));
    avancer(&mut world, 2.0);
    let etat = world.vehicle_snapshot(ASSEMBLY).expect("véhicule");
    assert!(etat.wheels.iter().any(|roue| roue.wear > 0.0));
    let bloc = ax_physics::state::AssemblyState {
        bodies: Vec::new(),
        joints: Vec::new(),
        vehicle: Some(etat.clone()),
    };
    let octets = bloc.encode();
    assert_eq!(octets.len() % 8, 0, "un bloc reste un multiple de 8");
    let relu = ax_physics::state::AssemblyState::decode(&octets).expect("lisible");
    assert_eq!(relu.vehicle.as_ref(), Some(&etat), "l'usure se relit");
    avancer(&mut world, 2.0);
    assert!(world.restore_vehicle(ASSEMBLY, &etat));
    assert_eq!(world.vehicle_snapshot(ASSEMBLY), Some(etat), "et se rend");
    let rapports = world.wheel_reports();
    assert!(rapports.iter().all(|r| r.wear >= 0.0 && r.wear <= 1.0));
}

#[test]
fn adr176_d4_un_pneu_use_montre_un_second_cercle_dans_l_overlay_des_roues() {
    use ax_math::{DVec3, FloatingOrigin};
    use ax_model::dm::debug::overlay;

    let (mut world, _) = sur_le_plat(berline_a_pneus_uses(0.0), [20.0, 20.0], 1.0);
    let origine = FloatingOrigin::new(DVec3::ZERO);
    let traits = |world: &PhysicsWorld| {
        world
            .debug_drafts(&origine, DVec3::ZERO, overlay::WHEELS)
            .iter()
            .find(|plage| plage.overlay == overlay::rank(overlay::WHEELS))
            .map_or(0, |plage| plage.segments.len())
    };
    conduire(&mut world, entree(0.0, 0.0, 0.0));
    assert_eq!(
        traits(&world),
        4 * 16,
        "des pneus neufs : un cercle par roue"
    );
    assert!(world.set_wheel_wear(ASSEMBLY, 2, 0.5));
    assert_eq!(
        traits(&world),
        4 * 16 + 16,
        "un pneu usé : un second cercle, plus petit"
    );
    for roue in 0..4 {
        assert!(world.set_wheel_wear(ASSEMBLY, roue, 1.0));
    }
    assert_eq!(traits(&world), 4 * 16 * 2);
}

/// Une dalle dont le matériau perd de son frottement mouillé : `wet` est son facteur (ADR-176 D5).
fn dalle_a_mouille(world: &mut PhysicsWorld, demi: [f32; 2], friction: f32, wet: f32) {
    let mut sol = collider([demi[0], 0.5, demi[1]], 1000.0, friction);
    sol.material.wet = wet;
    world
        .add_assembly(
            BodyKind::Static,
            Vec3::new(0.0, -0.5, 0.0),
            Quat::IDENTITY,
            &[sol],
        )
        .expect("dalle");
}

/// La berline de `desc` sur une dalle de matériau `wet`, `mouille` posé sur le véhicule, plein gaz trois secondes : la
/// vitesse.
fn vitesse_apres_trois_secondes(desc: VehicleDesc, wet_materiau: f32, mouille: f32) -> f32 {
    let mut world = monde();
    dalle_a_mouille(&mut world, [300.0, 300.0], 1.0, wet_materiau);
    let v = poser(
        &mut world,
        Vec3::new(0.0, hauteur_equilibre(), 0.0),
        Quat::IDENTITY,
        desc,
        &QUATRE,
    );
    conduire(&mut world, VehicleInput::NONE);
    avancer(&mut world, 0.5);
    assert!(world.set_vehicle_wet(ASSEMBLY, mouille));
    conduire(&mut world, entree(1.0, 0.0, 0.0));
    avancer(&mut world, 3.0);
    vers_l_avant(&world, v)
}

#[test]
fn adr176_d5_une_route_mouillee_adhere_moins_selon_son_materiau() {
    let mut desc = berline();
    desc.wheels.iter_mut().for_each(|roue| roue.wet_grip = 1.0);
    let sec = vitesse_apres_trois_secondes(desc.clone(), 0.5, 0.0);
    let humide = vitesse_apres_trois_secondes(desc.clone(), 0.5, 0.5);
    let mouille = vitesse_apres_trois_secondes(desc.clone(), 0.5, 1.0);
    assert!(
        mouille < humide && humide < sec,
        "plus il est mouillé, moins il accélère : sec {sec}, humide {humide}, mouillé {mouille}"
    );
    // Un matériau qui ne perd rien (wet = 1, le défaut) n'a aucune raison de glisser plus : au bit près.
    let insensible = vitesse_apres_trois_secondes(desc.clone(), 1.0, 1.0);
    let reference = vitesse_apres_trois_secondes(desc, 1.0, 0.0);
    assert_eq!(
        insensible.to_bits(),
        reference.to_bits(),
        "un matériau sans `wet` ne change rien"
    );
}

#[test]
fn adr176_d5_un_pneu_pluie_rend_ce_que_la_route_mouillee_ote() {
    let mut ordinaire = berline();
    ordinaire
        .wheels
        .iter_mut()
        .for_each(|roue| roue.wet_grip = 1.0);
    let mut pluie = berline();
    pluie.wheels.iter_mut().for_each(|roue| roue.wet_grip = 2.0);
    let sec = vitesse_apres_trois_secondes(ordinaire.clone(), 0.5, 0.0);
    let ordinaire_mouille = vitesse_apres_trois_secondes(ordinaire, 0.5, 1.0);
    let pluie_mouille = vitesse_apres_trois_secondes(pluie, 0.5, 1.0);
    assert!(ordinaire_mouille < sec, "le pneu ordinaire glisse");
    // wet (0,5) x wet_grip (2) = 1 : le pneu pluie retrouve l'adhérence du sec, au bit près.
    assert_eq!(
        pluie_mouille.to_bits(),
        sec.to_bits(),
        "pluie {pluie_mouille}, sec {sec}"
    );
}

#[test]
fn adr176_d5_set_vehicle_wet_ne_reveille_pas_borne_et_garde_le_nan() {
    let (mut world, _) = sur_le_plat(berline(), [100.0, 100.0], 1.0);
    avancer(&mut world, 3.0);
    assert!(world.vehicle_reports().is_empty(), "il dort");
    let mouille = |world: &PhysicsWorld| world.vehicle_snapshot(ASSEMBLY).expect("véhicule").wet;
    assert!(world.set_vehicle_wet(ASSEMBLY, 0.4));
    assert!(
        world.vehicle_reports().is_empty(),
        "poser le mouillé ne réveille pas"
    );
    assert_eq!(mouille(&world), f64::from(0.4f32));
    assert!(world.set_vehicle_wet(ASSEMBLY, f32::NAN), "NaN garde");
    assert_eq!(mouille(&world), f64::from(0.4f32));
    assert!(world.set_vehicle_wet(ASSEMBLY, 9.0));
    assert_eq!(mouille(&world), 1.0, "borné");
    assert!(world.set_vehicle_wet(ASSEMBLY, -3.0));
    assert_eq!(mouille(&world), 0.0, "borné");
    assert!(!monde().set_vehicle_wet(ASSEMBLY, 0.5), "pas de véhicule");
}

#[test]
fn adr176_d5_le_mouille_se_relit_dans_le_bloc_d_etat_et_se_rend() {
    let (mut world, _) = sur_le_plat(berline(), [300.0, 300.0], 1.0);
    conduire(&mut world, entree(1.0, 0.0, 0.0));
    assert!(world.set_vehicle_wet(ASSEMBLY, 0.6));
    avancer(&mut world, 1.0);
    let etat = world.vehicle_snapshot(ASSEMBLY).expect("véhicule");
    assert_eq!(etat.wet, f64::from(0.6f32));
    let bloc = ax_physics::state::AssemblyState {
        bodies: Vec::new(),
        joints: Vec::new(),
        vehicle: Some(etat.clone()),
    };
    let octets = bloc.encode();
    assert_eq!(octets.len() % 8, 0, "un bloc reste un multiple de 8");
    let relu = ax_physics::state::AssemblyState::decode(&octets).expect("lisible");
    assert_eq!(relu.vehicle.as_ref(), Some(&etat), "le mouillé se relit");
    assert!(world.set_vehicle_wet(ASSEMBLY, 0.0));
    assert!(world.restore_vehicle(ASSEMBLY, &etat));
    assert_eq!(world.vehicle_snapshot(ASSEMBLY), Some(etat), "et se rend");
}

/// Le coût au natif de l'énergie, de l'usure et du mouillé (ADR-176, grille D7) : un pas de simulation d'un véhicule plein gaz,
/// avec et sans ces trois états. Une mesure, pas un test : `cargo test -p ax-physics --test vehicles --release -- --ignored
/// --nocapture adr176_cout`. Chaque configuration se joue sept fois ; la médiane et le minimum sont affichés, en nanosecondes par
/// pas.
#[test]
#[ignore = "mesure : à lancer à la main, en release"]
fn adr176_cout_de_l_energie_de_l_usure_et_du_mouille() {
    use std::time::Instant;

    fn pas(desc: VehicleDesc, contact: bool, mouille: f32) -> f64 {
        let (mut world, _) = sur_le_plat(desc, [400.0, 400.0], 1.0);
        conduire(&mut world, avec_contact(entree(1.0, 0.0, 0.0), contact));
        world.set_vehicle_wet(ASSEMBLY, mouille);
        avancer(&mut world, 1.0);
        let n = 2000;
        let debut = Instant::now();
        for _ in 0..n {
            world.advance(TICK);
        }
        debut.elapsed().as_nanos() as f64 / f64::from(n)
    }

    let mut tout = berline_a_reservoir();
    for roue in &mut tout.wheels {
        roue.wear_rate = 0.02;
        roue.wear_grip_loss = 0.4;
        roue.wet_grip = 1.2;
    }
    let configurations: [(&str, VehicleDesc, bool, f32); 4] = [
        ("sans rien (berline)", berline(), false, 0.0),
        (
            "energie (reservoir, batterie, demarreur)",
            berline_a_reservoir(),
            true,
            0.0,
        ),
        ("usure des pneus", berline_a_pneus_uses(0.02), false, 0.0),
        ("energie + usure + mouille a 0,5", tout, true, 0.5),
    ];
    for (nom, desc, contact, mouille) in configurations {
        let mut mesures: Vec<f64> = (0..7)
            .map(|_| pas(desc.clone(), contact, mouille))
            .collect();
        mesures.sort_by(|a, b| a.total_cmp(b));
        println!(
            "COUT {nom} : mediane {:.0} ns/pas, minimum {:.0} ns/pas",
            mesures[3], mesures[0]
        );
    }
}

/// Le coût au natif d'un sol dont chaque tuile porte plusieurs matériaux (ADR-180, précision 14d) : 128 véhicules sur un
/// sol plat de sections, en une boîte par section, en trois couches de trois colliders, et les trois couches en un seul
/// composé. Une mesure, pas un test : `cargo test -p ax-physics --test vehicles --release -- --ignored --nocapture
/// adr180_cout`.
#[test]
#[ignore = "mesure : à lancer à la main, en release"]
fn adr180_cout_d_un_sol_a_plusieurs_materiaux() {
    use ax_physics::{CompoundPart, Stage};
    use std::time::Instant;

    // Les couches d'un monde plat dans la section du sol, sur son épaisseur de quatre blocs : roche-mère, terre, herbe.
    const COUCHES: [(f32, f32, f32); 3] = [(0.0, 1.0, 0.9), (1.0, 3.0, 0.7), (3.0, 4.0, 0.8)];

    fn boite(bas: f32, haut: f32) -> (Vec3, Shape) {
        (
            Vec3::new(8.0, (bas + haut) * 0.5, 8.0),
            Shape::Cuboid {
                half_extents: [8.0, (haut - bas) * 0.5, 8.0],
            },
        )
    }

    fn colliders(forme: usize) -> Vec<BodyCollider> {
        let un = |centre: Vec3, shape: Shape, friction: f32| BodyCollider {
            shape,
            density: 1.0,
            material: contact(friction),
            translation: centre,
            rotation: Quat::IDENTITY,
        };
        match forme {
            0 => {
                let (centre, shape) = boite(0.0, 4.0);
                vec![un(centre, shape, 0.8)]
            }
            1 => COUCHES
                .iter()
                .map(|&(bas, haut, friction)| {
                    let (centre, shape) = boite(bas, haut);
                    un(centre, shape, friction)
                })
                .collect(),
            _ => {
                let parts = COUCHES
                    .iter()
                    .map(|&(bas, haut, _)| {
                        let (translation, shape) = boite(bas, haut);
                        CompoundPart {
                            translation,
                            rotation: Quat::IDENTITY,
                            shape,
                        }
                    })
                    .collect();
                vec![un(Vec3::ZERO, Shape::Compound { parts }, 0.8)]
            }
        }
    }

    fn passe(forme: usize) -> (f64, f64, f64) {
        let mut world = monde();
        for x in 0..13 {
            for z in 0..7 {
                let tuile = world
                    .add_assembly(
                        BodyKind::Static,
                        Vec3::new(x as f32 * 16.0, 0.0, z as f32 * 16.0),
                        Quat::IDENTITY,
                        &colliders(forme),
                    )
                    .expect("tuile");
                world.set_collision_groups(tuile, CollisionGroups::WORLD_TILE);
            }
        }
        let volume = 8.0 * DEMI[0] * DEMI[1] * DEMI[2];
        let mut handles = Vec::new();
        for rang in 0..128u32 {
            let position = Vec3::new(
                10.0 + (rang % 16) as f32 * 12.0,
                4.0 + hauteur_equilibre(),
                10.0 + (rang / 16) as f32 * 12.0,
            );
            let chassis = world
                .add_assembly(
                    BodyKind::Dynamic,
                    position,
                    Quat::IDENTITY,
                    &[collider(DEMI, MASSE / volume, 0.5)],
                )
                .expect("châssis");
            let montures: Vec<WheelMount> = QUATRE
                .iter()
                .map(|&(x, z)| WheelMount {
                    body: 0,
                    center: Vec3::new(x, -REPOS, z),
                })
                .collect();
            let handle = Handle::new(rang + 1, 1);
            world.attach_vehicle(
                handle,
                &[chassis],
                berline(),
                &montures,
                &[],
                CollisionGroups::ALL,
            );
            handles.push(handle);
        }
        // En cercle, lentement : chacun reste sur son carré de sol.
        for &handle in &handles {
            world.set_vehicle_input(handle, &entree(0.15, 0.0, 1.0), true);
        }
        avancer(&mut world, 5.0);
        let n = 600;
        let (mut integration, mut vehicules) = (0u64, 0u64);
        let debut = Instant::now();
        for _ in 0..n {
            world.advance(TICK);
            integration += world.stage_duration(Stage::Integration);
            vehicules += world.stage_duration(Stage::Vehicles);
        }
        let total = debut.elapsed().as_nanos() as f64 / f64::from(n) / 1e6;
        (
            total,
            integration as f64 / f64::from(n) / 1e6,
            vehicules as f64 / f64::from(n) / 1e6,
        )
    }

    let noms = [
        "une boite par section",
        "trois colliders par section",
        "un compose de trois boites",
    ];
    let mut mesures = [Vec::new(), Vec::new(), Vec::new()];
    // Les formes alternent : une dérive de la machine les touche toutes.
    for _ in 0..7 {
        for (forme, passes) in mesures.iter_mut().enumerate() {
            passes.push(passe(forme));
        }
    }
    for (nom, mut passes) in noms.into_iter().zip(mesures) {
        passes.sort_by(|a, b| a.0.total_cmp(&b.0));
        let (total, integration, vehicules) = passes[3];
        println!(
            "COUT {nom} : mediane {total:.3} ms/pas (minimum {:.3}), integration {integration:.3}, vehicules {vehicules:.3}",
            passes[0].0
        );
    }
}

#[test]
fn adr181_chaque_roue_rapporte_le_materiau_qu_elle_touche() {
    let materiau = |index: u16| ContactMaterial {
        index,
        ..contact(0.8)
    };
    // À gauche, une dalle d'une boîte au matériau 3 ; à droite, un champ de hauteurs à une cellule au matériau 5, dont
    // le dominant, 9, ne doit sortir nulle part.
    let mut world = monde();
    let dalle = |world: &mut PhysicsWorld, x: f32, shape: Shape, material: ContactMaterial| {
        world
            .add_assembly(
                BodyKind::Static,
                Vec3::new(x, 0.0, 0.0),
                Quat::IDENTITY,
                &[BodyCollider {
                    shape,
                    density: 1.0,
                    material,
                    translation: Vec3::ZERO,
                    rotation: Quat::IDENTITY,
                }],
            )
            .expect("sol")
    };
    let boite = Shape::Cuboid {
        half_extents: [10.0, 0.5, 40.0],
    };
    let gauche = dalle(&mut world, -10.0, boite, materiau(3));
    // La boîte a son dessus à y = 0,5 : le champ s'y tient aussi.
    let champ = Shape::Heightfield {
        rows: 2,
        cols: 2,
        heights: vec![0.5; 4],
        scale: [20.0, 1.0, 80.0],
    };
    let champ = dalle(&mut world, 10.0, champ, materiau(9));
    let cellules = CellMaterials::new(1, [20.0, 80.0], vec![materiau(5)]).expect("cellule");
    world.set_cell_materials(champ, cellules);
    let v = poser(
        &mut world,
        Vec3::new(0.0, 0.5 + hauteur_equilibre(), 0.0),
        Quat::IDENTITY,
        berline(),
        &QUATRE,
    );
    conduire(&mut world, VehicleInput::NONE);
    avancer(&mut world, 0.5);
    let materiaux = |world: &PhysicsWorld| {
        world
            .wheel_reports()
            .iter()
            .map(|roue| roue.material)
            .collect::<Vec<_>>()
    };
    // QUATRE : gauche, droite, gauche, droite.
    assert_eq!(materiaux(&world), vec![3, 5, 3, 5]);
    // Le sol retiré, les roues en l'air n'en touchent aucun.
    world.remove_body(gauche);
    world.remove_body(champ);
    avancer(&mut world, 0.05);
    assert!(world.pose(v).is_some());
    assert_eq!(materiaux(&world), vec![NO_MATERIAL; 4]);
}

// --- ADR-182 D3 : les moteurs électriques. ---

/// Une berline électrique : un moteur sur les deux roues arrière, tirant la réserve de rang 0 (kWh), sans moteur à
/// combustion, boîte directe.
fn electrique_pure(capacite: f32) -> VehicleDesc {
    let mut d = berline();
    d.flags = vehicle_flags::POWERTRAIN | vehicle_flags::NO_ENGINE;
    d.transmission.kind = transmission_kind::DIRECT;
    d.transmission.final_drive = 1.0;
    d.torque_curve.clear();
    d.gears.clear();
    d.energy = EnergyDesc {
        capacities: [capacite, 0.0, 0.0, 0.0],
        ..EnergyDesc::NONE
    };
    d.motors = vec![MotorDesc {
        store: 0,
        coupling: motor_coupling::WHEELS,
        wheels: 0b1100,
        max_rpm: 9000.0,
        efficiency: 0.9,
        ratio: 8.0,
        regen_lift_off: 20.0,
        regen_brake: 60.0,
        regen_efficiency: 0.7,
        torque_curve: vec![[0.0, 150.0], [4000.0, 150.0], [9000.0, 60.0]],
    }];
    d
}

/// L'entrée, le premier moteur électrique autorisé.
fn moteur_autorise(e: VehicleInput) -> VehicleInput {
    VehicleInput {
        native_flags: native_input_flags::motor(0),
        ..e
    }
}

fn rapport(world: &PhysicsWorld) -> VehicleReport {
    world.vehicle_reports()[0]
}

fn reserve(world: &PhysicsWorld) -> f64 {
    world
        .vehicle_snapshot(ASSEMBLY)
        .expect("véhicule")
        .energy
        .stores[0]
}

/// L'énergie cinétique de translation du châssis, en kWh.
fn cinetique(vitesse: f32) -> f64 {
    0.5 * f64::from(MASSE) * f64::from(vitesse * vitesse) / 3.6e6
}

#[test]
fn adr182_d3_un_vehicule_electrique_roule_sur_sa_reserve_et_paie_la_puissance() {
    let (mut world, v) = sur_le_plat(electrique_pure(50.0), [400.0, 400.0], 1.0);
    conduire(&mut world, moteur_autorise(entree(1.0, 0.0, 0.0)));
    let mut par_les_rapports = 0.0f64;
    for _ in 0..240 {
        world.advance(TICK);
        par_les_rapports += f64::from(rapport(&world).motor_power[0]) * f64::from(TICK) / 3600.0;
    }
    let vitesse = vers_l_avant(&world, v);
    assert!(vitesse > 5.0, "il roule : {vitesse}");
    let tiree = 50.0 - reserve(&world);
    // La puissance rapportée est celle du dernier sous-pas de chaque tick : à quelques pour cent près.
    assert!(
        (par_les_rapports - tiree).abs() < tiree * 0.05,
        "rapportée {par_les_rapports}, tirée {tiree}"
    );
    let mecanique = tiree * 0.9;
    assert!(
        mecanique > cinetique(vitesse) && mecanique < cinetique(vitesse) * 1.6,
        "le travail du moteur couvre l'élan et les pertes : {mecanique} pour {}",
        cinetique(vitesse)
    );
    let r = rapport(&world);
    let attendu = vitesse / RAYON * 8.0 * 60.0 / std::f32::consts::TAU;
    assert!(
        (r.motor_rpm[0] - attendu).abs() < attendu * 0.15,
        "le régime suit les roues : {} pour {attendu}",
        r.motor_rpm[0]
    );
    assert_eq!(r.rpm, 0.0, "aucun moteur à combustion");
    assert_eq!(r.flags & vehicle_report_flags::RUNNING, 0);
}

#[test]
fn adr182_d3_sans_autorisation_ou_a_vide_le_moteur_ne_donne_rien() {
    let (mut world, v) = sur_le_plat(electrique_pure(50.0), [400.0, 400.0], 1.0);
    conduire(&mut world, entree(1.0, 0.0, 0.0));
    avancer(&mut world, 2.0);
    assert!(vers_l_avant(&world, v).abs() < 0.05, "sans autorisation");
    assert_eq!(reserve(&world), 50.0);

    let (mut world, v) = sur_le_plat(electrique_pure(50.0), [400.0, 400.0], 1.0);
    assert!(world.set_vehicle_energy(ASSEMBLY, [0.0, f32::NAN, f32::NAN, f32::NAN]));
    conduire(&mut world, moteur_autorise(entree(1.0, 0.0, 0.0)));
    avancer(&mut world, 2.0);
    assert!(vers_l_avant(&world, v).abs() < 0.05, "réserve vide");
    assert_eq!(reserve(&world), 0.0);
}

#[test]
fn adr182_d3_au_lever_de_pied_il_recupere_sans_jamais_repartir_en_arriere() {
    let lancer = |d: VehicleDesc| {
        let (mut world, v) = sur_le_plat(d, [400.0, 400.0], 1.0);
        conduire(&mut world, moteur_autorise(entree(1.0, 0.0, 0.0)));
        avancer(&mut world, 4.0);
        // La réserve à demi : de la place pour récupérer.
        assert!(world.set_vehicle_energy(ASSEMBLY, [25.0, f32::NAN, f32::NAN, f32::NAN]));
        conduire(&mut world, moteur_autorise(VehicleInput::NONE));
        (world, v)
    };
    let (mut world, v) = lancer(electrique_pure(50.0));
    let mut sans = electrique_pure(50.0);
    sans.motors[0].regen_lift_off = 0.0;
    sans.motors[0].regen_brake = 0.0;
    let (mut libre, v_libre) = lancer(sans);
    let au_lacher = vers_l_avant(&world, v);
    world.advance(TICK);
    assert!(
        rapport(&world).motor_power[0] < 0.0,
        "il rend de la puissance"
    );
    libre.advance(TICK);
    assert_eq!(
        rapport(&libre).motor_power[0],
        0.0,
        "sans récupération, rien"
    );
    avancer(&mut libre, 2.0);
    let mut le_plus_bas = f32::MAX;
    for _ in 0..120 {
        world.advance(TICK);
        le_plus_bas = le_plus_bas.min(vers_l_avant(&world, v));
    }
    assert!(
        vers_l_avant(&world, v) < vers_l_avant(&libre, v_libre) - 0.5,
        "la récupération freine : {} contre {}",
        vers_l_avant(&world, v),
        vers_l_avant(&libre, v_libre)
    );
    for _ in 0..2400 {
        world.advance(TICK);
        le_plus_bas = le_plus_bas.min(vers_l_avant(&world, v));
    }
    assert!(le_plus_bas > -0.05, "jamais en arrière : {le_plus_bas}");
    assert!(
        vers_l_avant(&world, v).abs() < 0.3,
        "arrêté : {}",
        vers_l_avant(&world, v)
    );
    let rendue = reserve(&world) - 25.0;
    assert!(
        rendue > cinetique(au_lacher) * 0.2 && rendue < cinetique(au_lacher) * 0.8,
        "une part de l'élan revient : {rendue} pour {}",
        cinetique(au_lacher)
    );
}

#[test]
fn adr182_d3_le_frein_recupere_davantage_et_une_reserve_pleine_ne_recupere_rien() {
    let lancer = |plein: bool| {
        let (mut world, v) = sur_le_plat(electrique_pure(50.0), [400.0, 400.0], 1.0);
        conduire(&mut world, moteur_autorise(entree(1.0, 0.0, 0.0)));
        avancer(&mut world, 4.0);
        let contenu = if plein { 50.0 } else { 25.0 };
        assert!(world.set_vehicle_energy(ASSEMBLY, [contenu, f32::NAN, f32::NAN, f32::NAN]));
        (world, v)
    };
    let (mut pied, _) = lancer(false);
    conduire(&mut pied, moteur_autorise(VehicleInput::NONE));
    pied.advance(TICK);
    let (mut frein, _) = lancer(false);
    conduire(&mut frein, moteur_autorise(entree(0.0, 0.5, 0.0)));
    frein.advance(TICK);
    assert!(
        rapport(&frein).motor_power[0] < rapport(&pied).motor_power[0] * 1.5,
        "la pédale ajoute de la récupération : {} contre {}",
        rapport(&frein).motor_power[0],
        rapport(&pied).motor_power[0]
    );
    let (mut plein, _) = lancer(true);
    conduire(&mut plein, moteur_autorise(VehicleInput::NONE));
    avancer(&mut plein, 1.0);
    assert_eq!(rapport(&plein).motor_power[0], 0.0, "plein : rien");
    assert_eq!(reserve(&plein), 50.0);
}

#[test]
fn adr182_d3_un_moteur_sur_la_boite_avance_et_recule_avec_le_rapport_engage() {
    let mut d = electrique_pure(50.0);
    d.transmission = berline().transmission;
    d.gears = berline().gears;
    d.motors[0].coupling = motor_coupling::TRANSMISSION;
    d.motors[0].wheels = 0;
    d.motors[0].ratio = 0.0;
    let (mut world, v) = sur_le_plat(d, [400.0, 400.0], 1.0);
    conduire(&mut world, moteur_autorise(entree(0.6, 0.0, 0.0)));
    avancer(&mut world, 3.0);
    assert!(vers_l_avant(&world, v) > 3.0, "{}", vers_l_avant(&world, v));
    assert!(rapport(&world).motor_rpm[0] > 100.0);
    assert!(reserve(&world) < 50.0);
    // Le frein tenu à l'arrêt engage la marche arrière ; il devient alors l'accélérateur (ADR-159 D6).
    conduire(&mut world, moteur_autorise(entree(0.0, 0.6, 0.0)));
    avancer(&mut world, 12.0);
    assert!(
        vers_l_avant(&world, v) < -1.0,
        "en arrière : {}",
        vers_l_avant(&world, v)
    );
    assert_eq!(rapport(&world).gear, -1);
}

// --- ADR-182 D4 : l'hybride, paramètre et prolongateur d'autonomie. ---

/// Une berline électrique à prolongateur : le moteur à combustion de la berline brûle la réserve de rang 1 et charge
/// la batterie de rang 0 (petite, pour la voir se remplir en quelques secondes) entre 30 % et 60 %.
fn a_prolongateur() -> VehicleDesc {
    let mut d = electrique_pure(0.05);
    let thermique = berline();
    d.flags = vehicle_flags::POWERTRAIN;
    d.engine = thermique.engine;
    d.torque_curve = thermique.torque_curve;
    d.energy = EnergyDesc {
        capacities: [0.05, 40.0, 0.0, 0.0],
        fuel_store: 1,
        consumption: vec![[0.0, 2.0], [1.0, 20.0]],
        ..EnergyDesc::NONE
    };
    d.generator = Some(GeneratorDesc {
        store: 0,
        power_kw: 30.0,
        efficiency: 0.9,
        start_below: 0.3,
        stop_above: 0.6,
    });
    d
}

/// Le moteur à combustion tourne, d'après le rapport ; un véhicule endormi n'en rapporte pas.
fn tourne(world: &PhysicsWorld) -> bool {
    world
        .vehicle_reports()
        .first()
        .is_some_and(|r| r.flags & vehicle_report_flags::RUNNING != 0)
}

fn contenus(world: &PhysicsWorld) -> [f64; 4] {
    world
        .vehicle_snapshot(ASSEMBLY)
        .expect("véhicule")
        .energy
        .stores
}

#[test]
fn adr182_d4_le_prolongateur_charge_entre_ses_seuils_sans_entrainer_les_roues() {
    let (mut world, v) = sur_le_plat(a_prolongateur(), [400.0, 400.0], 1.0);
    assert!(world.set_vehicle_energy(ASSEMBLY, [0.01, f32::NAN, f32::NAN, f32::NAN]));
    // Un coup de frein réveille le véhicule ; ensuite aucune pédale : seul le prolongateur travaille, et il le tient
    // éveillé jusqu'à son seuil haut.
    conduire(&mut world, moteur_autorise(entree(0.0, 1.0, 0.0)));
    world.advance(TICK);
    assert_ne!(
        rapport(&world).flags & vehicle_report_flags::RUNNING,
        0,
        "sous 30 % : il démarre"
    );
    conduire(&mut world, moteur_autorise(VehicleInput::NONE));
    for _ in 0..30 {
        world.advance(TICK);
    }
    let r = rapport(&world);
    assert!(
        r.rpm > 1000.0,
        "il tourne à son régime de puissance : {}",
        r.rpm
    );
    assert!(
        r.load > 0.0 && r.load <= 1.0,
        "sa charge est sa puissance : {}",
        r.load
    );
    let mut arrete_a = None;
    for _ in 0..600 {
        world.advance(TICK);
        // Arrêté, il laisse le véhicule s'endormir : il n'y a alors plus de rapport.
        if !tourne(&world) {
            arrete_a = Some(contenus(&world)[0] / 0.05);
            break;
        }
    }
    let seuil = arrete_a.expect("il s'arrête au seuil haut");
    assert!(
        (0.6..0.66).contains(&seuil),
        "arrêt au-dessus de 60 % : {seuil}"
    );
    let apres = contenus(&world);
    assert!(apres[1] < 40.0, "il a brûlé du carburant");
    assert!(
        vers_l_avant(&world, v).abs() < 0.05,
        "les roues ne sont pas entraînées : {}",
        vers_l_avant(&world, v)
    );
    // Entre les deux seuils, réveillé, il reste arrêté.
    assert!(world.set_vehicle_energy(ASSEMBLY, [0.022, f32::NAN, f32::NAN, f32::NAN]));
    conduire(&mut world, moteur_autorise(entree(0.0, 1.0, 0.0)));
    world.advance(TICK);
    assert!(!tourne(&world), "44 % : pas encore");
    // Carburant vide : sous le seuil, il ne charge pas.
    assert!(world.set_vehicle_energy(ASSEMBLY, [0.01, 0.0, f32::NAN, f32::NAN]));
    conduire(&mut world, moteur_autorise(VehicleInput::NONE));
    conduire(&mut world, moteur_autorise(entree(0.0, 1.0, 0.0)));
    avancer(&mut world, TICK);
    assert!(!tourne(&world), "sans carburant");
    assert_eq!(contenus(&world)[0], f64::from(0.01_f32));
}

#[test]
fn adr182_d4_l_etat_du_prolongateur_se_sauve_et_se_rejoue() {
    let (mut world, _) = sur_le_plat(a_prolongateur(), [400.0, 400.0], 1.0);
    assert!(world.set_vehicle_energy(ASSEMBLY, [0.01, f32::NAN, f32::NAN, f32::NAN]));
    conduire(&mut world, moteur_autorise(entree(0.0, 1.0, 0.0)));
    world.advance(TICK);
    conduire(&mut world, moteur_autorise(VehicleInput::NONE));
    avancer(&mut world, 0.5);
    // Au-dessus du seuil bas, sous le seuil haut : il charge parce qu'il avait commencé.
    assert!(world.set_vehicle_energy(ASSEMBLY, [0.02, f32::NAN, f32::NAN, f32::NAN]));
    avancer(&mut world, TICK);
    let etat = world.vehicle_snapshot(ASSEMBLY).expect("véhicule");
    assert!(etat.generating, "40 % : il continue");
    avancer(&mut world, 0.3);
    let reference = contenus(&world);
    assert!(world.restore_vehicle(ASSEMBLY, &etat));
    avancer(&mut world, 0.3);
    assert_eq!(contenus(&world), reference, "rejoué à l'identique");
}

#[test]
fn adr182_d4_le_couple_d_un_moteur_se_regle_et_se_coupe() {
    let rouler = |couple: f32| {
        let (mut world, v) = sur_le_plat(electrique_pure(50.0), [400.0, 400.0], 1.0);
        let mut reglage = ax_vehicle::tuning::Tuning::base();
        reglage.motors[0] = couple;
        assert_eq!(world.set_vehicle_tuning(ASSEMBLY, &reglage), Some(()));
        conduire(&mut world, moteur_autorise(entree(1.0, 0.0, 0.0)));
        avancer(&mut world, 3.0);
        vers_l_avant(&world, v)
    };
    let base = rouler(f32::NAN);
    assert!(rouler(0.0).abs() < 0.05, "coupé");
    assert!(rouler(300.0) > base * 1.4, "doublé");
    assert!(
        (rouler(150.0) - base).abs() < 1e-4,
        "le pic de la courbe est la base"
    );
}

#[test]
fn adr182_d4_en_parallele_les_couples_s_additionnent() {
    let rouler = |autorise: bool| {
        let mut d = berline();
        d.energy = EnergyDesc {
            capacities: [10.0, 0.0, 0.0, 0.0],
            ..EnergyDesc::NONE
        };
        d.motors = electrique_pure(10.0).motors;
        let (mut world, v) = sur_le_plat(d, [400.0, 400.0], 1.0);
        let e = entree(0.5, 0.0, 0.0);
        conduire(&mut world, if autorise { moteur_autorise(e) } else { e });
        avancer(&mut world, 3.0);
        (vers_l_avant(&world, v), contenus(&world)[0])
    };
    let (thermique, pleine) = rouler(false);
    let (hybride, entamee) = rouler(true);
    assert!(hybride > thermique + 1.0, "{hybride} contre {thermique}");
    assert_eq!(pleine, 10.0, "sans autorisation, rien ne sort");
    assert!(entamee < 10.0);
}

/// La durée de l'étape des véhicules, en microsecondes par tick, sur 5 000 ticks pleins gaz.
fn cout_des_vehicules(desc: VehicleDesc, entree_tenue: VehicleInput) -> f64 {
    use ax_physics::Stage;
    let prolongateur = desc.generator.is_some();
    let (mut world, _) = sur_le_plat(desc, [4000.0, 4000.0], 1.0);
    if prolongateur {
        // La batterie sous son seuil : le prolongateur tourne pendant toute la mesure.
        assert!(world.set_vehicle_energy(ASSEMBLY, [100.0, f32::NAN, f32::NAN, f32::NAN]));
    }
    conduire(&mut world, entree_tenue);
    avancer(&mut world, 1.0);
    let n = 5_000u32;
    let mut vehicules = 0u64;
    for _ in 0..n {
        world.advance(TICK);
        vehicules += world.stage_duration(Stage::Vehicles);
    }
    vehicules as f64 / f64::from(n) / 1e3
}

#[test]
#[ignore = "mesure : à lancer à la main, en release"]
fn adr182_cout_des_moteurs_electriques_et_du_prolongateur() {
    let quatre = {
        let mut d = electrique_pure(1000.0);
        let un = d.motors[0].clone();
        d.motors = (0..4)
            .map(|rank| MotorDesc {
                wheels: 1 << rank,
                ..un.clone()
            })
            .collect();
        d
    };
    let mut hybride = a_prolongateur();
    hybride.energy.capacities[0] = 1000.0;
    hybride.energy.capacities[1] = 1000.0;
    let plein = entree(1.0, 0.0, 0.0);
    let avec = VehicleInput {
        native_flags: native_input_flags::MOTORS,
        ..plein
    };
    // Les mêmes cas en alternance, sept fois : le minimum de chacun écarte le bruit de la machine.
    let mut mesures = [f64::MAX; 4];
    for _ in 0..7 {
        for (rang, mesure) in mesures.iter_mut().enumerate() {
            let valeur = match rang {
                0 => cout_des_vehicules(berline(), plein),
                1 => cout_des_vehicules(electrique_pure(1000.0), moteur_autorise(plein)),
                2 => cout_des_vehicules(quatre.clone(), avec),
                _ => cout_des_vehicules(hybride.clone(), moteur_autorise(plein)),
            };
            *mesure = mesure.min(valeur);
        }
    }
    println!(
        "étape des véhicules, µs/tick : thermique {:.2}, un moteur {:.2}, quatre moteurs {:.2}, hybride série {:.2}",
        mesures[0], mesures[1], mesures[2], mesures[3]
    );
}

// --- R-1061, ADR-159 D5 : l'anti-roulis. ---

/// La berline aux deux essieux déclarés, d'anti-roulis `raideur` sur chacun.
fn a_essieux(raideur: f32) -> VehicleDesc {
    let mut d = berline();
    for (rang, roue) in d.wheels.iter_mut().enumerate() {
        roue.axle = u16::from(rang >= 2);
        roue.suspension.anti_roll = raideur;
    }
    d
}

/// Le roulis, en radians : l'angle du haut du véhicule avec la verticale, autour de son avant.
fn roulis(world: &PhysicsWorld, v: BodyId) -> f32 {
    let rotation = world.pose(v).expect("pose").rotation;
    let droite = rotation * Vec3::X;
    droite.y.asin()
}

/// Le plus grand roulis d'un virage tenu à droite après quatre secondes de ligne droite.
fn roulis_en_virage(desc: VehicleDesc) -> f32 {
    let (mut world, v) = sur_le_plat(desc, [400.0, 400.0], 1.0);
    conduire(&mut world, entree(0.5, 0.0, 0.0));
    avancer(&mut world, 4.0);
    conduire(&mut world, entree(0.3, 0.0, 0.35));
    let mut pire = 0.0f32;
    for _ in 0..90 {
        world.advance(TICK);
        pire = pire.max(roulis(&world, v).abs());
    }
    pire
}

#[test]
fn r1061_l_anti_roulis_d_un_essieu_redresse_en_virage() {
    let sans = roulis_en_virage(a_essieux(0.0));
    let avec = roulis_en_virage(a_essieux(25.0));
    assert!(sans > 0.02, "le virage fait pencher : {sans}");
    assert!(
        avec < sans * 0.75,
        "l'anti-roulis redresse : {avec} contre {sans}"
    );
}

#[test]
fn r1061_sans_essieu_declare_ou_a_plat_l_anti_roulis_ne_change_rien() {
    // À l'arrêt sur le plat, les deux roues d'un essieu sont aussi comprimées : rien ne passe de l'une à l'autre.
    let poser_au_repos = |raideur: f32| {
        let (mut world, v) = sur_le_plat(a_essieux(raideur), [20.0, 20.0], 1.0);
        avancer(&mut world, 1.0);
        world.pose(v).expect("pose").translation.y
    };
    assert!((poser_au_repos(0.0) - poser_au_repos(25.0)).abs() < 1e-4);
    // Un essieu qui n'a qu'une roue déclarée ne reçoit rien : l'anti-roulis ne se devine pas.
    let seul = |raideur: f32| {
        let mut d = a_essieux(raideur);
        d.wheels[1].axle = NONE_U16;
        d.wheels[3].axle = NONE_U16;
        roulis_en_virage(d)
    };
    assert_eq!(seul(0.0), seul(25.0));
}

// --- CAP-53 : ce que la fréquence des sous-pas change, en précision et en coût. ---

/// La durée d'un tick de jeu, que le pilote donne au monde.
const TICK_DE_JEU: f32 = 0.05;

/// Un monde au pas physique `dt`, une dalle, et des berlines écartées de dix blocs, la première à l'origine.
fn monde_au_pas(dt: f32, vehicules: u32) -> (PhysicsWorld, Vec<BodyId>) {
    let mut world = PhysicsWorld::new(PhysicsConfig::new(dt, 8).expect("pas autorisé"));
    dalle(&mut world, 0.0, 0.0, 0.0, [4000.0, 4000.0], 1.0);
    let mut poses = Vec::new();
    for rang in 0..vehicules {
        let handle = Handle {
            index: 1 + rang,
            generation: 1,
        };
        let at = Vec3::new(rang as f32 * 10.0, hauteur_equilibre(), 0.0);
        poses.push(poser_sous(
            &mut world,
            handle,
            at,
            Quat::IDENTITY,
            berline(),
            &QUATRE,
        ));
        assert_eq!(
            world.set_vehicle_input(handle, &VehicleInput::NONE, true),
            Some(false)
        );
    }
    for _ in 0..20 {
        world.advance(TICK_DE_JEU);
    }
    (world, poses)
}

/// Le scénario d'une berline au pas `dt` : tremblement au repos (mm), temps pour 20 m/s (s), distance d'arrêt depuis
/// là (m), puis position (m) et cap (degrés) après un virage de trois secondes.
fn scenario(dt: f32) -> (f32, f32, f32, [f32; 2], f32) {
    let (mut world, v) = monde_au_pas(dt, 1);
    let v = v[0];
    // Un conducteur au frein à main tient la berline éveillée sans la faire reculer : son tremblement se mesure, pas
    // son sommeil.
    conduire(
        &mut world,
        VehicleInput {
            handbrake: 1.0,
            ..VehicleInput::NONE
        },
    );
    let hauteurs: Vec<f32> = (0..40)
        .map(|_| {
            world.advance(TICK_DE_JEU);
            world.pose(v).expect("pose").translation.y
        })
        .collect();
    let moyenne = hauteurs.iter().sum::<f32>() / hauteurs.len() as f32;
    let tremblement = hauteurs
        .iter()
        .map(|y| (y - moyenne).abs())
        .fold(0.0, f32::max)
        * 1e3;
    conduire(&mut world, entree(1.0, 0.0, 0.0));
    let mut t20 = f32::NAN;
    let mut avant = 0.0;
    for tick in 1..600 {
        world.advance(TICK_DE_JEU);
        let vitesse = vers_l_avant(&world, v);
        if vitesse >= 20.0 {
            t20 = (tick as f32 - (vitesse - 20.0) / (vitesse - avant)) * TICK_DE_JEU;
            break;
        }
        avant = vitesse;
    }
    let depart = world.pose(v).expect("pose").translation.z;
    conduire(&mut world, entree(0.0, 1.0, 0.0));
    for _ in 0..200 {
        world.advance(TICK_DE_JEU);
        if vers_l_avant(&world, v) < 0.05 {
            break;
        }
    }
    let arret = depart - world.pose(v).expect("pose").translation.z;
    conduire(&mut world, entree(0.5, 0.0, 0.0));
    for _ in 0..80 {
        world.advance(TICK_DE_JEU);
    }
    conduire(&mut world, entree(0.3, 0.0, 0.35));
    for _ in 0..60 {
        world.advance(TICK_DE_JEU);
    }
    let fin = world.pose(v).expect("pose").translation;
    (
        tremblement,
        t20,
        arret,
        [fin.x, fin.z],
        cap(&world, v).to_degrees(),
    )
}

/// Le temps réel d'un tick de jeu pour 32 berlines à plein gaz, en millisecondes ; le minimum de cinq passes.
fn cout_au_pas(dt: f32) -> f64 {
    (0..5)
        .map(|_| {
            let (mut world, poses) = monde_au_pas(dt, 32);
            for rang in 0..poses.len() as u32 {
                let handle = Handle {
                    index: 1 + rang,
                    generation: 1,
                };
                assert_eq!(
                    world.set_vehicle_input(handle, &entree(0.5, 0.0, 0.2), true),
                    Some(false)
                );
            }
            let debut = std::time::Instant::now();
            for _ in 0..400 {
                world.advance(TICK_DE_JEU);
            }
            debut.elapsed().as_secs_f64() * 1e3 / 400.0
        })
        .fold(f64::MAX, f64::min)
}

#[test]
#[ignore = "mesure : à lancer à la main, en release"]
fn cap53_frequences_des_sous_pas() {
    for hz in [30.0f32, 60.0, 120.0] {
        let (tremblement, t20, arret, fin, cap) = scenario(1.0 / hz);
        println!(
            "CAP53 physique {hz:>3} Hz : tremblement {tremblement:.3} mm, 20 m/s en {t20:.3} s, arrêt {arret:.3} m, \
             virage fini en ({:.3}, {:.3}) cap {cap:.2} deg, 32 véhicules {:.3} ms/tick",
            fin[0],
            fin[1],
            cout_au_pas(1.0 / hz)
        );
    }
}
