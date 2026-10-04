//! T-302 et T-303 : gestion d'activité autour des joueurs (R-612, R-613, ADR-123 §3).
//!
//! Tout passe par l'API du pilote : observateurs du tick, puis `manage_activity`, comme
//! le fera la frontière avant chaque pas.

use ax_math::{DVec3, Quat, Vec3};
use ax_model::dm::geometry::WorldTransform;
use ax_physics::{
    ActivityReport, BodyCollider, BodyId, BodyKind, ContactMaterial, Handle, Shape, SimDriver,
    SimSettings,
};

/// Un pilote de rayon `radius` et de plafond `cap`, sans gravité dans les dimensions 0 et
/// 1 : un corps n'y bouge que de la vitesse qu'on lui donne.
fn pilote(radius: f32, cap: usize) -> SimDriver {
    let mut driver = SimDriver::with_settings(SimSettings {
        simulation_radius: radius,
        max_active_bodies: cap,
        ..SimSettings::default()
    });
    for dimension in [0, 1] {
        driver.apply_dimension_env(dimension, Vec3::ZERO, Vec3::ZERO, None);
    }
    driver
}

/// Une bille dynamique de 0,5 bloc de rayon (environ 524 kg), au handle `(index, 1)`.
fn bille(driver: &mut SimDriver, dimension: u64, index: u32, position: [f64; 3]) -> BodyId {
    driver
        .create_assembly(
            dimension,
            Handle::new(index, 1),
            WorldTransform {
                position,
                rotation: [0.0, 0.0, 0.0, 1.0],
            },
            BodyKind::Dynamic,
            &[BodyCollider {
                shape: Shape::Ball { radius: 0.5 },
                density: 1000.0,
                material: ContactMaterial::default(),
                translation: Vec3::ZERO,
                rotation: Quat::IDENTITY,
            }],
        )
        .expect("une bille est valide")
}

fn dort(driver: &mut SimDriver, dimension: u64, body: BodyId) -> bool {
    driver
        .world_mut(dimension)
        .and_then(|world| world.is_sleeping(body))
        .expect("le corps existe")
}

/// Ouvre un tick où la dimension a un joueur par position, et gère l'activité.
fn tick(driver: &mut SimDriver, dimension: u64, observers: &[[f64; 3]]) -> ActivityReport {
    driver.begin_tick();
    let positions: Vec<DVec3> = observers.iter().copied().map(DVec3::from_array).collect();
    driver.set_observers(dimension, &positions);
    driver.manage_activity()
}

#[test]
fn t302_hors_du_rayon_de_tout_joueur_un_corps_s_endort_sans_disparaitre() {
    // R-612 : le rayon se mesure depuis le plus proche des joueurs.
    let mut driver = pilote(32.0, 2048);
    let pres_du_premier = bille(&mut driver, 0, 1, [5.0, 0.0, 0.0]);
    let pres_du_second = bille(&mut driver, 0, 2, [95.0, 0.0, 0.0]);
    let entre_les_deux = bille(&mut driver, 0, 3, [50.0, 0.0, 0.0]);
    let au_loin = bille(&mut driver, 0, 4, [300.0, 0.0, 0.0]);

    let report = tick(&mut driver, 0, &[[0.0; 3], [100.0, 0.0, 0.0]]);

    assert_eq!(report.slept_by_radius, 2);
    assert!(!dort(&mut driver, 0, pres_du_premier));
    assert!(!dort(&mut driver, 0, pres_du_second));
    assert!(dort(&mut driver, 0, entre_les_deux));
    assert!(dort(&mut driver, 0, au_loin));
    // Endort, jamais ne supprime.
    assert_eq!(driver.world_mut(0).unwrap().body_count(), 4);
}

#[test]
fn t302_sans_joueur_dans_sa_dimension_un_corps_s_endort() {
    // Les joueurs d'une dimension ne gardent éveillés que les corps de celle-ci : aux mêmes
    // coordonnées, le corps d'une dimension sans joueur s'endort.
    let mut driver = pilote(32.0, 2048);
    let ici = bille(&mut driver, 0, 1, [0.0; 3]);
    let ailleurs = bille(&mut driver, 1, 2, [0.0; 3]);

    let report = tick(&mut driver, 0, &[[1.0, 0.0, 0.0]]);

    assert_eq!(report.slept_by_radius, 1);
    assert!(!dort(&mut driver, 0, ici));
    assert!(dort(&mut driver, 1, ailleurs));
}

#[test]
fn t302_un_corps_fige_reprend_sa_course_au_retour_d_un_joueur() {
    // R-612 : un corps endormi par le rayon se réveille quand un joueur revient à portée,
    // avec la vitesse qu'il avait — le sommeil forcé suspend, il ne freine pas (R-1890).
    let mut driver = pilote(32.0, 2048);
    let corps = bille(&mut driver, 0, 1, [0.0; 3]);
    driver.apply_impulse(
        Handle::new(1, 1),
        Vec3::new(5000.0, 0.0, 0.0),
        Vec3::ZERO,
        false,
    );
    let elan = driver.world_mut(0).unwrap().velocity(corps).unwrap();
    assert!(elan.x > 5.0, "l'impulsion lance la bille : {elan}");

    // Aucun joueur à portée : la bille se fige, et ne bouge plus de tick en tick.
    assert_eq!(
        tick(&mut driver, 0, &[[500.0, 0.0, 0.0]]).slept_by_radius,
        1
    );
    let figee = driver
        .world_mut(0)
        .unwrap()
        .pose(corps)
        .unwrap()
        .translation;
    for _ in 0..20 {
        tick(&mut driver, 0, &[[500.0, 0.0, 0.0]]);
        driver.advance_all(1.0 / 20.0);
    }
    assert!(dort(&mut driver, 0, corps));
    assert_eq!(
        driver
            .world_mut(0)
            .unwrap()
            .pose(corps)
            .unwrap()
            .translation,
        figee
    );

    // Un joueur revient : la bille repart, à la vitesse qu'elle avait.
    assert_eq!(tick(&mut driver, 0, &[[10.0, 0.0, 0.0]]).woken, 1);
    assert!(!dort(&mut driver, 0, corps));
    assert_eq!(driver.world_mut(0).unwrap().velocity(corps).unwrap(), elan);
    driver.advance_all(1.0 / 20.0);
    let reprise = driver
        .world_mut(0)
        .unwrap()
        .pose(corps)
        .unwrap()
        .translation;
    assert!(
        reprise.x > figee.x,
        "la bille avance de nouveau : {reprise}"
    );
}

#[test]
fn t302_un_corps_endormi_naturellement_n_est_pas_reveille() {
    // R-612 ne réveille que les corps qu'il a endormis : un corps au repos dort à portée.
    let mut driver = SimDriver::new();
    let corps = bille(&mut driver, 0, 1, [0.0, 0.5, 0.0]);
    sol(&mut driver);
    assert!(
        se_pose_et_s_endort(&mut driver, corps, true),
        "la bille au repos s'endort d'elle-même"
    );

    let report = tick(&mut driver, 0, &[[0.0, 0.0, 2.0]]);

    assert_eq!(report, ActivityReport::default());
    assert!(dort(&mut driver, 0, corps));
}

/// Pose un sol statique de 10 × 10 blocs, face supérieure en y = 0, dans la dimension 0.
fn sol(driver: &mut SimDriver) {
    driver
        .world_mut(0)
        .expect("la dimension existe")
        .add_body(
            BodyKind::Static,
            Vec3::new(0.0, -0.5, 0.0),
            Quat::IDENTITY,
            Shape::Cuboid {
                half_extents: [5.0, 0.5, 5.0],
            },
        )
        .expect("un sol est valide");
}

/// Fige en l'air, sans joueur, une bille lancée à l'horizontale ; le jeu la réveille
/// ensuite d'une poussée vers le bas. Rend la bille, éveillée, son élan suspendu oublié.
fn bille_figee_puis_reveillee_par_le_jeu(driver: &mut SimDriver, pas_avant: bool) -> BodyId {
    let corps = bille(driver, 0, 1, [0.0, 3.0, 0.0]);
    sol(driver);
    driver.apply_impulse(
        Handle::new(1, 1),
        Vec3::new(5000.0, 0.0, 0.0),
        Vec3::ZERO,
        false,
    );
    tick(driver, 0, &[]);
    assert!(dort(driver, 0, corps));
    if pas_avant {
        driver.advance_all(1.0 / 20.0);
        assert!(dort(driver, 0, corps), "figée, elle ne bouge pas");
    }
    driver.apply_impulse(
        Handle::new(1, 1),
        Vec3::new(0.0, -500.0, 0.0),
        Vec3::ZERO,
        false,
    );
    assert!(!dort(driver, 0, corps));
    corps
}

/// Laisse la bille tomber et se poser jusqu'à son sommeil naturel, avec ou sans la
/// gestion d'activité à chaque tick ; vrai si elle s'est endormie.
fn se_pose_et_s_endort(driver: &mut SimDriver, corps: BodyId, avec_gestion: bool) -> bool {
    for _ in 0..200 {
        if avec_gestion {
            tick(driver, 0, &[[0.0, 0.0, 2.0]]);
        }
        driver.advance_all(1.0 / 20.0);
        if dort(driver, 0, corps) {
            return true;
        }
    }
    false
}

#[test]
fn t302_reveille_par_le_jeu_un_corps_fige_oublie_son_elan_suspendu() {
    // Une impulsion venue du jeu réveille un corps figé : il repart de son état courant.
    // Rendormi de lui-même, il ne doit pas recevoir au retour d'un joueur l'élan d'avant —
    // ce serait une vitesse fantôme. Ici, le relevé d'activité constate le réveil.
    let mut driver = SimDriver::new();
    let corps = bille_figee_puis_reveillee_par_le_jeu(&mut driver, false);
    assert!(se_pose_et_s_endort(&mut driver, corps, true));

    let report = tick(&mut driver, 0, &[[0.0, 0.0, 2.0]]);

    assert_eq!(report, ActivityReport::default());
    assert!(dort(&mut driver, 0, corps));
}

#[test]
fn t302_un_reveil_constate_par_le_pas_oublie_aussi_l_elan_suspendu() {
    // Même scénario sans relevé pendant la chute : c'est la transition de sommeil, vue
    // après un sous-pas, qui constate le réveil.
    let mut driver = SimDriver::new();
    let corps = bille_figee_puis_reveillee_par_le_jeu(&mut driver, true);
    assert!(se_pose_et_s_endort(&mut driver, corps, false));

    let report = tick(&mut driver, 0, &[[0.0, 0.0, 2.0]]);

    assert_eq!(report, ActivityReport::default());
    assert!(dort(&mut driver, 0, corps));
}

#[test]
fn t303_au_dela_du_plafond_les_plus_eloignes_s_endorment() {
    let mut driver = pilote(128.0, 2);
    let corps: Vec<BodyId> = (1..=5)
        .map(|i| bille(&mut driver, 0, i, [f64::from(i) * 3.0, 0.0, 0.0]))
        .collect();

    let report = tick(&mut driver, 0, &[[0.0; 3]]);

    assert_eq!(report.slept_by_cap, 3);
    let dorment: Vec<bool> = corps.iter().map(|c| dort(&mut driver, 0, *c)).collect();
    assert_eq!(dorment, [false, false, true, true, true]);
}

#[test]
fn t303_sous_le_plafond_personne_ne_s_endort() {
    let mut driver = pilote(128.0, 5);
    for i in 1..=3 {
        bille(&mut driver, 0, i, [f64::from(i) * 3.0, 0.0, 0.0]);
    }
    assert_eq!(tick(&mut driver, 0, &[[0.0; 3]]), ActivityReport::default());
}

#[test]
fn t303_a_distance_egale_les_plus_anciens_s_endorment_d_abord() {
    let mut driver = pilote(128.0, 1);
    let premier = bille(&mut driver, 0, 1, [3.0, 0.0, 0.0]);
    let deuxieme = bille(&mut driver, 0, 2, [-3.0, 0.0, 0.0]);
    let troisieme = bille(&mut driver, 0, 3, [0.0, 0.0, 3.0]);

    let report = tick(&mut driver, 0, &[[0.0; 3]]);

    assert_eq!(report.slept_by_cap, 2);
    assert!(dort(&mut driver, 0, premier));
    assert!(dort(&mut driver, 0, deuxieme));
    assert!(!dort(&mut driver, 0, troisieme));
}

#[test]
fn t303_l_anciennete_est_le_rang_de_creation() {
    // `rapier` réutilise l'emplacement d'un corps retiré : son ordre d'itération n'est pas
    // l'ancienneté. Le dernier créé reste le plus jeune.
    let mut driver = pilote(128.0, 1);
    bille(&mut driver, 0, 1, [3.0, 0.0, 0.0]);
    let ancien = bille(&mut driver, 0, 2, [-3.0, 0.0, 0.0]);
    assert!(driver.apply_remove_assembly(Handle::new(1, 1)));
    let jeune = bille(&mut driver, 0, 3, [0.0, 0.0, 3.0]);
    let ordre: Vec<Handle> = driver
        .collect_states()
        .iter()
        .map(|state| state.handle)
        .collect();
    assert_eq!(
        ordre,
        [Handle::new(3, 1), Handle::new(2, 1)],
        "le plus jeune occupe l'emplacement libéré, itéré en premier"
    );

    tick(&mut driver, 0, &[[0.0; 3]]);

    assert!(dort(&mut driver, 0, ancien));
    assert!(!dort(&mut driver, 0, jeune));
}

#[test]
fn t303_le_plafond_compte_toutes_les_dimensions() {
    // `budgets.max_active_bodies` est un budget serveur : les corps de toutes les
    // dimensions se le partagent, les plus proches de leurs joueurs d'abord.
    let mut driver = pilote(128.0, 2);
    let a = bille(&mut driver, 0, 1, [3.0, 0.0, 0.0]);
    let b = bille(&mut driver, 0, 2, [12.0, 0.0, 0.0]);
    let c = bille(&mut driver, 1, 3, [6.0, 0.0, 0.0]);
    let d = bille(&mut driver, 1, 4, [9.0, 0.0, 0.0]);
    driver.begin_tick();
    driver.set_observers(0, &[DVec3::ZERO]);
    driver.set_observers(1, &[DVec3::ZERO]);

    let report = driver.manage_activity();

    assert_eq!(report.slept_by_cap, 2);
    assert!(!dort(&mut driver, 0, a));
    assert!(!dort(&mut driver, 1, c));
    assert!(dort(&mut driver, 1, d));
    assert!(dort(&mut driver, 0, b));
}

#[test]
fn t303_une_place_liberee_reveille_le_corps_fige_le_plus_proche() {
    let mut driver = pilote(128.0, 1);
    bille(&mut driver, 0, 1, [3.0, 0.0, 0.0]);
    let moyen = bille(&mut driver, 0, 2, [30.0, 0.0, 0.0]);
    let loin = bille(&mut driver, 0, 3, [60.0, 0.0, 0.0]);
    assert_eq!(tick(&mut driver, 0, &[[0.0; 3]]).slept_by_cap, 2);

    assert!(driver.apply_remove_assembly(Handle::new(1, 1)));
    let report = tick(&mut driver, 0, &[[0.0; 3]]);

    assert_eq!(report.woken, 1);
    assert!(!dort(&mut driver, 0, moyen));
    assert!(dort(&mut driver, 0, loin));
}

#[test]
fn t303_un_corps_fige_nettement_plus_proche_prend_la_place() {
    let mut driver = pilote(128.0, 1);
    let a = bille(&mut driver, 0, 1, [10.0, 0.0, 0.0]);
    let b = bille(&mut driver, 0, 2, [40.0, 0.0, 0.0]);
    tick(&mut driver, 0, &[[0.0; 3]]);
    assert!(dort(&mut driver, 0, b));

    // Le joueur s'approche de b, plus proche que a de dix blocs seulement : rien ne change.
    assert_eq!(
        tick(&mut driver, 0, &[[30.0, 0.0, 0.0]]),
        ActivityReport::default()
    );
    // Tout contre b, plus proche de trente blocs : b prend la place de a.
    let report = tick(&mut driver, 0, &[[40.0, 0.0, 0.0]]);
    assert_eq!((report.slept_by_cap, report.woken), (1, 1));
    assert!(dort(&mut driver, 0, a));
    assert!(!dort(&mut driver, 0, b));
}

#[test]
fn t303_meme_scene_memes_decisions() {
    // R-1020 : distances répétées et deux dimensions, pour éprouver tous les départages.
    let scene = || {
        let mut driver = pilote(40.0, 7);
        let mut corps = Vec::new();
        for i in 0..24u32 {
            let dimension = u64::from(i % 2);
            let angle = f64::from(i) * 0.7;
            let distance = f64::from(i % 6) * 9.0 + 3.0;
            let position = [distance * angle.cos(), 0.0, distance * angle.sin()];
            corps.push((dimension, bille(&mut driver, dimension, i + 1, position)));
        }
        driver.begin_tick();
        driver.set_observers(0, &[DVec3::ZERO]);
        driver.set_observers(1, &[DVec3::ZERO]);
        let report = driver.manage_activity();
        let dorment: Vec<bool> = corps
            .iter()
            .map(|&(dimension, body)| dort(&mut driver, dimension, body))
            .collect();
        (report, dorment)
    };

    let (report, dorment) = scene();

    assert_eq!(scene(), (report, dorment.clone()));
    assert_eq!(report.slept_by_cap + report.slept_by_radius, 24 - 7);
    assert_eq!(dorment.iter().filter(|dort| !**dort).count(), 7);
}
