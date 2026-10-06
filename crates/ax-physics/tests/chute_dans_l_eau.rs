//! Un cube lâché de haut dans l'eau (C-38, R-642), tel que l'essai en jeu du 2026-10-06 l'a
//! tracé : il tombe de y = 87 dans un océan, y entre à 22 m/s, et ne doit ni se mettre à tourner
//! ni rejaillir.

use ax_math::{DVec3, Quat, Vec3};
use ax_model::dm::geometry::WorldTransform;
use ax_physics::{BodyCollider, BodyKind, ContactMaterial, Handle, Shape, SimDriver};

const DIM: u64 = 0;
const CUBE: Handle = Handle {
    index: 1,
    generation: 1,
};

/// Le cube de test du jeu : `auto_box` du maillage `[-0.5, 0, -0.5]..[0.5, 1, 0.5]`, l'origine
/// au centre de sa face du bas, la densité par défaut, celle de l'eau.
fn cube_de_test() -> BodyCollider {
    BodyCollider {
        shape: Shape::Cuboid {
            half_extents: [0.5; 3],
        },
        density: 1000.0,
        material: ContactMaterial::default(),
        translation: Vec3::new(0.0, 0.5, 0.0),
        rotation: Quat::IDENTITY,
    }
}

/// Un océan comme celui de l'essai : fond solide en y = 48, eau de y = 49 à la surface, posée
/// en y = 62 à 8/9 de bloc ; l'eau en boîtes fusionnées, comme le pont Forge l'envoie.
fn ocean(driver: &mut SimDriver) {
    for x in -11..=-5 {
        for z in -4..=2 {
            driver.set_world_tile(
                DIM,
                [x, 3, z],
                &[[0.0, 0.0, 0.0, 16.0, 1.0, 16.0]],
                ContactMaterial::default(),
            );
            driver.set_world_fluid_tile(
                DIM,
                [x, 3, z],
                &[
                    [0.0, 1.0, 0.0, 16.0, 14.0, 16.0],
                    [0.0, 14.0, 0.0, 16.0, 14.0 + 8.0 / 9.0, 16.0],
                ],
                1000.0,
            );
        }
    }
}

/// Lâche le cube de y = 87,11 au-dessus de `(x, z)` et rend, une fois dans l'eau, sa plus
/// grande vitesse de rotation, en rad/s, et le tick où il l'atteint.
fn chute(x: f64, z: f64) -> (f32, u32) {
    let mut driver = SimDriver::new();
    ocean(&mut driver);
    driver.create_assembly(
        DIM,
        CUBE,
        WorldTransform {
            position: [x, 87.11, z],
            rotation: [0.0, 0.0, 0.0, 1.0],
        },
        BodyKind::Dynamic,
        &[cube_de_test()],
    );
    let mut plus_vite = (0.0f32, 0u32);
    for tick in 0..200u32 {
        driver.begin_tick();
        driver.set_observers(DIM, &[DVec3::new(x, 90.0, z)]);
        driver.sync_entity_proxies();
        driver.manage_activity();
        driver.advance_all(1.0 / 20.0);
        let reports = driver.collect_reports();
        let etat = reports
            .states
            .iter()
            .find(|state| state.handle == CUBE)
            .expect("le cube est rapporté");
        let [wx, wy, wz] = etat.ang_vel;
        let rotation = (wx * wx + wy * wy + wz * wz).sqrt();
        if etat.position[1] < 63.0 && rotation > plus_vite.0 {
            plus_vite = (rotation, tick);
        }
        let _ = driver.drain_events();
        driver.release_idle_dimensions();
    }
    plus_vite
}

#[test]
fn t375_un_cube_qui_tombe_dans_l_eau_au_milieu_d_une_section_ne_se_met_pas_a_tourner() {
    let (rotation, tick) = chute(-119.5, -5.177);
    assert!(rotation < 1.0, "{rotation} rad/s au tick {tick}");
}

#[test]
fn t375_un_cube_qui_tombe_dans_l_eau_a_cheval_sur_deux_sections_ne_se_met_pas_a_tourner() {
    // Le point exact de l'essai : le cube couvre x ∈ [−113,5 ; −112,5], de part et d'autre de la
    // frontière x = −112 entre les sections −8 et −7.
    let (rotation, tick) = chute(-112.986, -5.177);
    assert!(rotation < 1.0, "{rotation} rad/s au tick {tick}");
}

/// Le fond réel sous la chute de l'essai, à z = −5 : son dessus est à y = 48 sous x = −113, à 49
/// sous x = −112 et à 50 sous x = −111 — une marche par bloc, de l'eau au-dessus jusqu'à la surface.
fn marches(driver: &mut SimDriver) {
    for z in -2..=0 {
        for x in -9..=-6 {
            // Le socle, en y = 47 : section 2, dernière couche.
            driver.set_world_tile(
                DIM,
                [x, 2, z],
                &[[0.0, 15.0, 0.0, 16.0, 16.0, 16.0]],
                ContactMaterial::default(),
            );
            let (solide, eau): (&[[f32; 6]], &[[f32; 6]]) = if x == -7 {
                // Bloc −112 : dessus à 49 ; blocs −111 et au-delà : dessus à 50.
                (
                    &[
                        [0.0, 0.0, 0.0, 1.0, 1.0, 16.0],
                        [1.0, 0.0, 0.0, 16.0, 2.0, 16.0],
                    ],
                    &[
                        [0.0, 1.0, 0.0, 1.0, 14.0, 16.0],
                        [1.0, 2.0, 0.0, 16.0, 14.0, 16.0],
                        [0.0, 14.0, 0.0, 16.0, 14.0 + 8.0 / 9.0, 16.0],
                    ],
                )
            } else {
                // Bloc −113 et la section −8 : dessus à 48.
                (
                    &[],
                    &[
                        [0.0, 0.0, 0.0, 16.0, 14.0, 16.0],
                        [0.0, 14.0, 0.0, 16.0, 14.0 + 8.0 / 9.0, 16.0],
                    ],
                )
            };
            driver.set_world_tile(DIM, [x, 3, z], solide, ContactMaterial::default());
            driver.set_world_fluid_tile(DIM, [x, 3, z], eau, 1000.0);
        }
    }
}

#[test]
fn t375_un_cube_pose_sur_une_marche_du_fond_ne_s_emballe_pas() {
    // L'essai du 2026-10-06 à 19:08 : le cube, posé à cheval sur la marche entre x = −113 et
    // x = −112, a pris 3 rad/s de plus à chaque tick jusqu'au plafond de 47 rad/s. Les coins de son
    // AABB qui débordaient dans la roche comptaient comme hors de l'eau : la poussée, décentrée
    // vers les autres, faisait un couple constant. Sans source d'énergie, il doit rester calme.
    let mut driver = SimDriver::new();
    marches(&mut driver);
    driver.create_assembly(
        DIM,
        CUBE,
        WorldTransform {
            position: [-112.0, 87.0, -5.2],
            rotation: [0.0, 0.0, 0.0, 1.0],
        },
        BodyKind::Dynamic,
        &[cube_de_test()],
    );
    let mut pose = None;
    let mut plus_vif = (0.0f32, 0u32);
    for tick in 0..400u32 {
        driver.begin_tick();
        driver.set_observers(DIM, &[DVec3::new(-112.0, 90.0, -5.2)]);
        driver.sync_entity_proxies();
        driver.manage_activity();
        driver.advance_all(1.0 / 20.0);
        let reports = driver.collect_reports();
        let etat = reports
            .states
            .iter()
            .find(|state| state.handle == CUBE)
            .expect("le cube est rapporté");
        let v2: f32 = etat.lin_vel.iter().map(|v| v * v).sum();
        let w2: f32 = etat.ang_vel.iter().map(|w| w * w).sum();
        // Énergie cinétique, en kJ : masse 1 000 kg, inertie m/6 d'un cube d'un mètre.
        let energie = (0.5 * 1000.0 * v2 + 0.5 * (1000.0 / 6.0) * w2) / 1000.0;
        if pose.is_none() && etat.position[1] < 50.5 && v2 < 4.0 {
            pose = Some(tick);
        }
        if pose.is_some_and(|t| tick > t + 2) && energie > plus_vif.0 {
            plus_vif = (energie, tick);
        }
        let _ = driver.drain_events();
        driver.release_idle_dimensions();
    }
    let pose = pose.expect("le cube atteint le fond");
    assert!(
        plus_vif.0 < 2.0,
        "posé au tick {pose}, {} kJ au tick {}",
        plus_vif.0,
        plus_vif.1
    );
}
