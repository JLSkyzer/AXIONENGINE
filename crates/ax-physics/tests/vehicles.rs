//! Tests de C-33 : les roues par rayon, la suspension et les pneus dans le pas (ADR-159, tranche 2),
//! puis le moteur, la direction, les freins, l'aérodynamique et le conducteur (tranche 3).

use ax_math::{Quat, Vec3};
use ax_model::dm::scene::NONE_U16;
use ax_model::dm::vehicle::{
    differential_kind, transmission_kind, vehicle_flags, wheel_flags, wheel_report_flags, AeroDesc,
    AssistDesc, BrakesDesc, DifferentialDesc, EngineDesc, SteeringDesc, SuspensionDesc, TireCurve,
    TransmissionDesc, VehicleDesc, VehicleInput, WheelDesc,
};
use ax_physics::{
    BodyCollider, BodyId, BodyKind, CollisionGroups, CombineRule, ContactMaterial, Handle,
    PhysicsConfig, PhysicsWorld, Shape, WheelMount,
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
    }
}

fn description(roues: usize) -> VehicleDesc {
    VehicleDesc {
        flags: 0,
        engine: EngineDesc {
            idle_rpm: 800.0,
            max_rpm: 7000.0,
            inertia: 0.3,
            braking_torque: 25.0,
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
