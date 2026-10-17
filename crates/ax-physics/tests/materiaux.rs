//! ADR-149 : la table DM-07 de la session donne leur contact aux colliders et aux tuiles par leur
//! index ; à modes différents, le plus fort l'emporte ; une table nouvelle se propage à ce qui est
//! déjà posé ; les événements portent les matériaux des colliders ; le roulement freine sans
//! jamais inverser.

use ax_math::{Quat, Vec3};
use ax_model::dm::geometry::WorldTransform;
use ax_physics::{
    combine, event_kind, BodyCollider, BodyId, BodyKind, ContactMaterial, Handle, PhysicsMaterial,
    Shape, SimDriver,
};

/// Un matériau de contact ; le reste de DM-07 à des valeurs plausibles, sans effet ici.
fn materiau(friction: f32, restitution: f32, rolling: f32, modes: (u8, u8)) -> PhysicsMaterial {
    PhysicsMaterial {
        name_hash: 0,
        friction,
        restitution,
        rolling_friction: rolling,
        friction_combine: modes.0,
        restitution_combine: modes.1,
        _pad0: [0; 2],
        density: 1000.0,
        hardness: 0.5,
        stiffness: 1.0e9,
        elasticity: 0.5,
        plasticity: 0.5,
        yield_strength: 1.0e8,
        fracture_strength: 2.0e8,
        deformation_resistance: 1.0,
        max_strain: 0.2,
        damping: 0.1,
        fracture_energy: 1.0e4,
        hardness_pressure: 3.0e8,
        indenter_radius_ref: 0.05,
        spread_factor: 1.0,
        thickness_spread: 1.0,
        damage_multiplier: 1.0,
        energy_absorption: 0.5,
        scratch_resistance: 0.5,
        burn_threshold: 1.0e5,
        soil_rate: 0.0,
        sound_group: 0,
        particle_group: 0,
        flags: 0,
    }
}

const AVG: (u8, u8) = (combine::AVERAGE, combine::AVERAGE);

/// Une dalle de tuile 16 × 1 × 16 sur la section (0, 0, 0), au matériau `index`.
fn dalle(driver: &mut SimDriver, index: u16) -> BodyId {
    let contact = driver.material(index);
    assert!(driver.set_world_tile(0, [0, 0, 0], &[[0.0, 0.0, 0.0, 16.0, 1.0, 16.0]], contact));
    driver.world_tile_body(0, [0, 0, 0]).expect("tuile posée")
}

/// Une assembly d'un collider, au-dessus de la dalle, au matériau `index`.
fn assembly(driver: &mut SimDriver, handle: Handle, shape: Shape, y: f64, index: u16) -> BodyId {
    driver
        .create_assembly(
            0,
            handle,
            WorldTransform {
                position: [8.0, y, 8.0],
                rotation: [0.0, 0.0, 0.0, 1.0],
            },
            BodyKind::Dynamic,
            &[BodyCollider {
                shape,
                density: 1000.0,
                material: ContactMaterial::unresolved(index),
                translation: Vec3::ZERO,
                rotation: Quat::IDENTITY,
            }],
        )
        .expect("assembly créée")
}

fn cube() -> Shape {
    Shape::Cuboid {
        half_extents: [0.5; 3],
    }
}

/// Rapport tangentiel / normal du CONTACT_IMPULSE de la paire au tick suivant une poussée : le
/// frottement combiné, tant que le bloc glisse (Coulomb).
fn frottement_en_glissement(driver: &mut SimDriver, bloc: Handle) -> (f32, [u16; 2]) {
    driver.apply_impulse(bloc, Vec3::new(5000.0, 0.0, 0.0), Vec3::ZERO, false);
    driver.advance_all(1.0 / 60.0);
    let event = driver
        .drain_events()
        .into_iter()
        .find(|event| event.kind == event_kind::CONTACT_IMPULSE)
        .expect("le bloc pèse sur la dalle : un CONTACT_IMPULSE");
    let mut materiaux = [event.material_a, event.material_b];
    materiaux.sort_unstable();
    (event.tangent_impulse / event.impulse, materiaux)
}

#[test]
fn adr149_un_collider_et_une_tuile_prennent_le_contact_de_leur_index() {
    let mut driver = SimDriver::new();
    driver.apply_dimension_env(0, Vec3::new(0.0, -9.81, 0.0), Vec3::ZERO, None);
    let table = [
        materiau(0.6, 0.0, 0.0, AVG),
        materiau(0.2, 0.0, 0.0, (combine::MIN, combine::AVERAGE)),
        materiau(0.8, 0.0, 0.0, AVG),
    ];
    driver.set_physics_materials(&table);
    let tuile = dalle(&mut driver, 1);
    let bloc = Handle::new(1, 1);
    let corps = assembly(&mut driver, bloc, cube(), 1.5, 2);

    let world = driver.world_mut(0).expect("dimension 0");
    assert_eq!(
        world.body_materials(corps),
        vec![ContactMaterial::from_table(&table[2], 2)]
    );
    assert_eq!(
        world.body_materials(tuile),
        vec![ContactMaterial::from_table(&table[1], 1)]
    );

    for _ in 0..10 {
        driver.advance_all(1.0 / 60.0);
    }
    let _ = driver.drain_events();
    // D3 : la dalle exige `min` ; il l'emporte sur la moyenne du bloc — 0,2, et non 0,5.
    let (mu, materiaux) = frottement_en_glissement(&mut driver, bloc);
    assert!((mu - 0.2).abs() < 0.01, "min l'emporte : μ = {mu}");
    // D5 : l'événement porte les index des deux colliders, tuile comprise.
    assert_eq!(materiaux, [1, 2]);
}

#[test]
fn adr149_une_table_nouvelle_se_propage_aux_corps_et_tuiles_poses() {
    let mut driver = SimDriver::new();
    driver.apply_dimension_env(0, Vec3::new(0.0, -9.81, 0.0), Vec3::ZERO, None);
    // Le bloc et la dalle sont posés avant toute table : leur index attend.
    let tuile = dalle(&mut driver, 1);
    let bloc = Handle::new(1, 1);
    let corps = assembly(&mut driver, bloc, cube(), 1.5, 2);
    assert_eq!(
        driver.world_mut(0).unwrap().body_materials(corps),
        vec![ContactMaterial::unresolved(2)],
        "contact neutre en attendant la table"
    );

    let table = [
        materiau(0.6, 0.0, 0.0, AVG),
        materiau(0.2, 0.0, 0.0, (combine::MIN, combine::AVERAGE)),
        materiau(0.8, 0.0, 0.0, AVG),
    ];
    assert_eq!(
        driver.set_physics_materials(&table),
        2,
        "bloc et dalle résolus"
    );
    for _ in 0..10 {
        driver.advance_all(1.0 / 60.0);
    }
    let _ = driver.drain_events();
    let (mu, _) = frottement_en_glissement(&mut driver, bloc);
    assert!(
        (mu - 0.2).abs() < 0.01,
        "table reçue après la pose : μ = {mu}"
    );

    // Un rechargement change la dalle : moyenne, 0,6 — le bloc posé glisse désormais à 0,7.
    let rechargee = [table[0], materiau(0.6, 0.0, 0.0, AVG), table[2]];
    assert_eq!(driver.set_physics_materials(&rechargee), 2);
    assert_eq!(
        driver.world_mut(0).unwrap().body_materials(tuile),
        vec![ContactMaterial::from_table(&rechargee[1], 1)]
    );
    for _ in 0..30 {
        driver.advance_all(1.0 / 60.0);
    }
    let _ = driver.drain_events();
    let (mu, _) = frottement_en_glissement(&mut driver, bloc);
    assert!((mu - 0.7).abs() < 0.01, "rechargement appliqué : μ = {mu}");
}

/// Hauteur du rebond d'une bille de rayon 0,5 lâchée de 2 blocs au-dessus de la dalle : la plus
/// haute position de son point bas après le premier contact.
fn rebond(index_bille: u16, table: &[PhysicsMaterial]) -> f32 {
    let mut driver = SimDriver::new();
    driver.apply_dimension_env(0, Vec3::new(0.0, -9.81, 0.0), Vec3::ZERO, None);
    driver.set_physics_materials(table);
    dalle(&mut driver, 0);
    let bille = assembly(
        &mut driver,
        Handle::new(1, 1),
        Shape::Ball { radius: 0.5 },
        3.5,
        index_bille,
    );
    let mut touche = false;
    let mut plus_haut = f32::MIN;
    for _ in 0..240 {
        driver.advance_all(1.0 / 60.0);
        let world = driver.world_mut(0).unwrap();
        let y = world.pose(bille).expect("bille").translation.y;
        if !touche && world.velocity(bille).unwrap().y > 0.0 {
            touche = true;
        }
        if touche {
            plus_haut = plus_haut.max(y);
        }
    }
    plus_haut - 1.0 - 0.5
}

#[test]
fn adr149_une_bille_a_restitution_haute_en_max_rebondit() {
    let table = [
        materiau(0.6, 0.0, 0.0, AVG),
        materiau(0.6, 0.8, 0.0, (combine::AVERAGE, combine::MAX)),
    ];
    // D3 : la bille exige `max` sur la restitution ; la dalle (0) n'y peut rien. Lâchée de 2,
    // elle remonte vers e² × 2 ≈ 1,3.
    let haut = rebond(1, &table);
    assert!(haut > 0.9, "rebond de la bille en max : {haut}");
    // Acier générique sur la même dalle : aucune restitution, aucun rebond notable.
    let acier = rebond(0, &table);
    assert!(acier < 0.05, "pas de rebond sans restitution : {acier}");
}

/// Vitesse angulaire de roulement (autour de z) d'une bille lancée sur la dalle, tick après tick.
fn roulement(rolling: f32) -> Vec<f32> {
    let mut driver = SimDriver::new();
    driver.apply_dimension_env(0, Vec3::new(0.0, -9.81, 0.0), Vec3::ZERO, None);
    driver.set_physics_materials(&[materiau(0.8, 0.0, rolling, AVG)]);
    dalle(&mut driver, 0);
    let handle = Handle::new(1, 1);
    let bille = assembly(&mut driver, handle, Shape::Ball { radius: 0.5 }, 1.5, 0);
    for _ in 0..10 {
        driver.advance_all(1.0 / 60.0);
    }
    // Une poussée horizontale : le frottement la met à rouler.
    let masse = 1000.0 * 4.0 / 3.0 * std::f32::consts::PI * 0.125;
    driver.apply_impulse(handle, Vec3::new(3.0 * masse, 0.0, 0.0), Vec3::ZERO, false);
    (0..180)
        .map(|_| {
            driver.advance_all(1.0 / 60.0);
            driver
                .world_mut(0)
                .unwrap()
                .angular_velocity(bille)
                .unwrap()
                .z
        })
        .collect()
}

#[test]
fn adr149_le_roulement_freine_une_bille_sans_jamais_l_inverser() {
    let libre = roulement(0.0);
    let freinee = roulement(0.05);
    // Rouler vers +x tourne autour de -z.
    let depart = libre[20];
    assert!(depart < -1.0, "la bille roule : ω_z = {depart}");
    // Sans roulement, elle roule encore à la fin.
    assert!(libre[179] < 0.9 * depart, "sans frein : {}", libre[179]);
    // Avec, une sphère pleine qui roule décélère de μr·g / (1 + 2/5) : sur les 3 s, sa vitesse
    // angulaire perd μr·g·t / (1,4·r) par rapport à la bille libre.
    let attendu = libre[179] + 0.05 * 9.81 * 3.0 / (1.4 * 0.5);
    assert!(
        (freinee[179] - attendu).abs() < 0.15 * attendu.abs(),
        "le roulement freine selon μr : {} attendu {attendu}, libre {}",
        freinee[179],
        libre[179]
    );
    // Freinée, jamais inversée : ω_z ne change pas de signe.
    assert!(
        freinee.iter().all(|&omega| omega <= 1.0e-4),
        "rotation inversée : {freinee:?}"
    );
}

#[test]
fn adr149_un_roulement_fort_arrete_la_bille_sans_la_renvoyer() {
    // μr = 5 : l'impulsion de freinage d'un seul sous-pas dépasse ce qui arrêterait la rotation.
    // Bornée par cet arrêt (D6), elle l'arrête sans la faire repartir en sens inverse.
    let freinee = roulement(5.0);
    assert!(
        freinee.iter().all(|&omega| omega <= 1.0e-4),
        "rotation inversée : {freinee:?}"
    );
    assert!(
        freinee[179].abs() < 0.05,
        "la bille tourne encore : {}",
        freinee[179]
    );
}

/// Vitesse angulaire verticale finale d'une bille mise à tourner sur place, comme une toupie.
fn toupie(rolling: f32) -> f32 {
    let mut driver = SimDriver::new();
    driver.apply_dimension_env(0, Vec3::new(0.0, -9.81, 0.0), Vec3::ZERO, None);
    driver.set_physics_materials(&[materiau(0.8, 0.0, rolling, AVG)]);
    dalle(&mut driver, 0);
    let bille = assembly(
        &mut driver,
        Handle::new(1, 1),
        Shape::Ball { radius: 0.5 },
        1.5,
        0,
    );
    for _ in 0..10 {
        driver.advance_all(1.0 / 60.0);
    }
    // Deux impulsions opposées de part et d'autre : un couple pur autour de la verticale.
    let world = driver.world_mut(0).unwrap();
    world.apply_impulse(
        bille,
        Vec3::new(0.0, 0.0, 200.0),
        Vec3::new(0.5, 0.0, 0.0),
        true,
    );
    world.apply_impulse(
        bille,
        Vec3::new(0.0, 0.0, -200.0),
        Vec3::new(-0.5, 0.0, 0.0),
        true,
    );
    for _ in 0..120 {
        driver.advance_all(1.0 / 60.0);
    }
    driver
        .world_mut(0)
        .unwrap()
        .angular_velocity(bille)
        .unwrap()
        .y
}

#[test]
fn adr149_le_roulement_ne_freine_pas_une_rotation_autour_de_la_normale() {
    // Tourner sur place autour de la normale du contact n'est pas rouler (D6) : le frottement de
    // roulement n'y change rien.
    let libre = toupie(0.0);
    let freinee = toupie(0.05);
    assert!(libre.abs() > 1.0, "la toupie tourne : ω_y = {libre}");
    assert!(
        (freinee - libre).abs() < 1.0e-3 * libre.abs(),
        "roulement appliqué à une rotation autour de la normale : {freinee} contre {libre}"
    );
}
