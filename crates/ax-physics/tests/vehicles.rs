//! Tests de C-33 : les roues par rayon, la suspension et les pneus dans le pas (ADR-159, tranche 2).

use ax_math::{Quat, Vec3};
use ax_model::dm::scene::NONE_U16;
use ax_model::dm::vehicle::{
    transmission_kind, wheel_report_flags, AeroDesc, BrakesDesc, DifferentialDesc, EngineDesc,
    SteeringDesc, SuspensionDesc, TireCurve, TransmissionDesc, VehicleDesc, WheelDesc,
};
use ax_physics::{
    BodyCollider, BodyId, BodyKind, CollisionGroups, ContactMaterial, Handle, PhysicsConfig,
    PhysicsWorld, Shape, WheelMount,
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
        wheels: vec![roue(); roues],
        torque_curve: Vec::new(),
        gears: Vec::new(),
        surfaces: Vec::new(),
    }
}

/// Un véhicule de quatre roues dont le centre est à la hauteur `y`, l'avant vers -Z.
fn vehicule(world: &mut PhysicsWorld, position: Vec3, rotation: Quat) -> BodyId {
    let volume = 8.0 * DEMI[0] * DEMI[1] * DEMI[2];
    let chassis = world
        .add_assembly(
            BodyKind::Dynamic,
            position,
            rotation,
            &[collider(DEMI, MASSE / volume, 0.5)],
        )
        .expect("châssis");
    let montures: Vec<WheelMount> = [(-0.8, -1.4), (0.8, -1.4), (-0.8, 1.4), (0.8, 1.4)]
        .iter()
        .map(|&(x, z)| WheelMount {
            body: 0,
            center: Vec3::new(x, -REPOS, z),
        })
        .collect();
    world.attach_vehicle(
        ASSEMBLY,
        &[chassis],
        description(4),
        &montures,
        CollisionGroups::ALL,
    );
    chassis
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
