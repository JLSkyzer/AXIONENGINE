//! S-05 (§29.7, ADR-151 D10) — 500 joints en chaînes et en grilles, au banc de physique : le temps du pas
//! mesuré, aucune valeur non finie, deux exécutions identiques au bit près.

use ax_math::{Quat, Vec3};
use ax_physics::{
    AxisMode, BodyCollider, BodyId, BodyKind, BreakThresholds, ContactMaterial, Handle, JointId,
    JointKind, JointSpec, PhysicsConfig, PhysicsWorld, Shape,
};
use std::f32::consts::FRAC_PI_2;
use std::time::{Duration, Instant};

const TICK: f32 = 1.0 / 20.0;

/// Dix secondes simulées.
const TICKS: usize = 200;

/// Dix chaînes de quatorze maillons : 140 joints. Lâchées à l'horizontale, elles se balancent.
const CHAINES: usize = 10;
const MAILLONS: usize = 14;

/// Deux grilles de dix plaques de côté, chacune soudée à ses voisines : 2 × 180 joints.
const GRILLES: usize = 2;
const COTE: usize = 10;

const ASSEMBLY: Handle = Handle {
    index: 1,
    generation: 1,
};

fn spec(kind: JointKind, frame_a: Vec3, frame_b: Vec3) -> JointSpec {
    JointSpec {
        kind,
        frame_a: (frame_a, Quat::IDENTITY),
        frame_b: (frame_b, Quat::IDENTITY),
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
    }
}

fn boite(world: &mut PhysicsWorld, at: Vec3, rotation: Quat, demi: [f32; 3]) -> BodyId {
    world
        .add_assembly(
            BodyKind::Dynamic,
            at,
            rotation,
            &[BodyCollider {
                shape: Shape::Cuboid { half_extents: demi },
                density: 500.0,
                material: ContactMaterial::default(),
                translation: Vec3::ZERO,
                rotation: Quat::IDENTITY,
            }],
        )
        .unwrap()
}

/// Le banc : un sol fixe, les chaînes tendues à l'horizontale depuis leurs ancres, les grilles lâchées
/// au-dessus du sol. Les chaînes, espacées d'un bloc en z, se balancent chacune dans son plan.
fn banc() -> (PhysicsWorld, Vec<BodyId>, Vec<JointId>) {
    let mut world = PhysicsWorld::new(PhysicsConfig::new(1.0 / 60.0, 4).expect("configuration"));
    world
        .add_body(
            BodyKind::Static,
            Vec3::new(0.0, -0.5, 0.0),
            Quat::IDENTITY,
            Shape::Cuboid {
                half_extents: [80.0, 0.5, 80.0],
            },
        )
        .unwrap();
    let mut corps = Vec::new();
    let mut joints = Vec::new();
    let mut rang = 0u16;
    let mut relier = |world: &mut PhysicsWorld, kind, a, b, frame_a, frame_b| {
        let id = world
            .add_joint(ASSEMBLY, rang, a, b, &spec(kind, frame_a, frame_b))
            .unwrap();
        rang += 1;
        id
    };
    // Un maillon couché : son y local, le long duquel il s'accroche, part vers -x.
    let couche = Quat::from_rotation_z(FRAC_PI_2);
    for chaine in 0..CHAINES {
        let z = chaine as f32;
        let mut haut = world
            .add_body(
                BodyKind::Static,
                Vec3::new(0.0, 12.0, z),
                Quat::IDENTITY,
                Shape::Ball { radius: 0.1 },
            )
            .unwrap();
        let mut sous_haut = Vec3::new(0.1, 0.0, 0.0);
        for maillon in 0..MAILLONS {
            let at = Vec3::new(0.325 + 0.45 * maillon as f32, 12.0, z);
            let b = boite(&mut world, at, couche, [0.05, 0.2, 0.05]);
            joints.push(relier(
                &mut world,
                JointKind::Spherical,
                haut,
                b,
                sous_haut,
                Vec3::new(0.0, 0.225, 0.0),
            ));
            corps.push(b);
            haut = b;
            sous_haut = Vec3::new(0.0, -0.225, 0.0);
        }
    }
    for grille in 0..GRILLES {
        let origine = Vec3::new(30.0 + 8.0 * grille as f32, 2.0 + 2.0 * grille as f32, 0.0);
        let mut plaques = Vec::new();
        for i in 0..COTE {
            for j in 0..COTE {
                let at = origine + Vec3::new(0.5 * i as f32, 0.0, 0.5 * j as f32);
                plaques.push(boite(&mut world, at, Quat::IDENTITY, [0.24, 0.05, 0.24]));
            }
        }
        for i in 0..COTE {
            for j in 0..COTE {
                let a = plaques[i * COTE + j];
                if i + 1 < COTE {
                    let b = plaques[(i + 1) * COTE + j];
                    joints.push(relier(
                        &mut world,
                        JointKind::Fixed,
                        a,
                        b,
                        Vec3::new(0.25, 0.0, 0.0),
                        Vec3::new(-0.25, 0.0, 0.0),
                    ));
                }
                if j + 1 < COTE {
                    let b = plaques[i * COTE + j + 1];
                    joints.push(relier(
                        &mut world,
                        JointKind::Fixed,
                        a,
                        b,
                        Vec3::new(0.0, 0.0, 0.25),
                        Vec3::new(0.0, 0.0, -0.25),
                    ));
                }
            }
        }
        corps.extend(plaques);
    }
    (world, corps, joints)
}

/// Une exécution : le temps de chaque pas, et les poses finales au bit près. Chaque tick, toute pose et
/// tout effort de joint sont finis. Le banc a travaillé : chaque chaîne a basculé sous son ancre, chaque
/// grille est posée sur le sol.
fn executer() -> (Vec<Duration>, Vec<[u32; 7]>) {
    let (mut world, corps, joints) = banc();
    assert_eq!(joints.len(), 500, "500 joints");
    let mut pas = Vec::with_capacity(TICKS);
    let mut plus_bas = [f32::INFINITY; CHAINES];
    for tick in 0..TICKS {
        let debut = Instant::now();
        world.advance(TICK);
        pas.push(debut.elapsed());
        for (chaine, bas) in plus_bas.iter_mut().enumerate() {
            let dernier = corps[chaine * MAILLONS + MAILLONS - 1];
            *bas = bas.min(world.pose(dernier).unwrap().translation.y);
        }
        for id in &corps {
            let pose = world.pose(*id).expect("corps présent");
            assert!(
                pose.translation.is_finite() && pose.rotation.is_finite(),
                "tick {tick} : pose non finie"
            );
        }
        for id in &joints {
            let effort = world.joint_report(*id).expect("joint présent");
            assert!(
                effort.force.is_finite() && effort.torque.is_finite() && effort.energy.is_finite(),
                "tick {tick} : effort non fini"
            );
            assert!(!effort.broken, "aucun joint ne rompt");
        }
    }
    // Tendu à la verticale, le dernier maillon est à 6,075 blocs sous son pivot, soit y = 5,925.
    for (chaine, bas) in plus_bas.iter().enumerate() {
        assert!(
            *bas < 7.0,
            "la chaîne {chaine} a basculé : y le plus bas {bas}"
        );
    }
    for id in &corps[CHAINES * MAILLONS..] {
        let y = world.pose(*id).unwrap().translation.y;
        assert!(
            (0.0..0.2).contains(&y),
            "une plaque posée sur le sol : y = {y}"
        );
    }
    let poses = corps
        .iter()
        .map(|id| {
            let pose = world.pose(*id).unwrap();
            let (t, r) = (pose.translation, pose.rotation);
            [t.x, t.y, t.z, r.x, r.y, r.z, r.w].map(f32::to_bits)
        })
        .collect();
    (pas, poses)
}

#[test]
fn s05_cinq_cents_joints_en_chaines_et_en_grilles() {
    let (pas, poses) = executer();
    let (_, encore) = executer();
    assert_eq!(poses, encore, "deux exécutions identiques au bit près");

    let mut tries = pas.clone();
    tries.sort();
    let total: Duration = pas.iter().sum();
    let moyen = total / pas.len() as u32;
    let median = tries[tries.len() / 2];
    let pire = tries[tries.len() - 1];
    // Mesuré, pas jugé : aucun budget de pas n'est fixé pour S-05 ; le chiffre dépend de la machine et du
    // profil de compilation des tests.
    eprintln!(
        "S-05 : 500 joints, {} pas de {TICK} s ; moyen {:.3} ms, médian {:.3} ms, pire {:.3} ms",
        pas.len(),
        moyen.as_secs_f64() * 1e3,
        median.as_secs_f64() * 1e3,
        pire.as_secs_f64() * 1e3
    );
}
