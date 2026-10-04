//! Mesure du pas de simulation sous la charge de tuiles du monde d'un essai en jeu (C-38).
//!
//! La scène reprend celle de l'essai du 2026-10-04, où le gouverneur FM-21 a relevé un p95 de
//! 62,9 ms : trois cubes posés au sol, et les sections 16³ de leur voisinage (rayon 3)
//! telles que le pont Forge les envoie — une boîte par bloc solide. Sol à y = 67 : les
//! sections y = 1 à 3 sont pleines, la section y = 4 a trois couches pleines, au-dessus
//! l'air. Le chronomètre entoure `advance_all`, exactement comme `axion_sim_collect`.
//!
//! ```bash
//! cargo run --release -p ax-physics --example charge_tuiles
//! ```

use std::time::Instant;

use ax_math::{DVec3, Quat, Vec3};
use ax_model::dm::geometry::WorldTransform;
use ax_physics::{
    BodyCollider, BodyKind, ContactMaterial, EntityProxy, Handle, ProxyShape, Shape, SimDriver,
};

const DIM: u64 = 0;
const TICK_DT: f32 = 1.0 / 20.0;
/// Débit du planificateur de tuiles : `world.tiles_per_tick`, défaut 8.
const TILES_PER_TICK: usize = 8;

/// Boîtes d'une section dont les `layers` couches du bas sont pleines : une par bloc.
fn boxes(layers: u32) -> Vec<[f32; 6]> {
    let mut boxes = Vec::new();
    for x in 0..16u32 {
        for y in 0..layers {
            for z in 0..16u32 {
                let (x, y, z) = (x as f32, y as f32, z as f32);
                boxes.push([x, y, z, x + 1.0, y + 1.0, z + 1.0]);
            }
        }
    }
    boxes
}

/// Les sections du voisinage des trois cubes, et leur nombre de couches pleines.
fn sections() -> Vec<([i32; 3], u32)> {
    let mut sections = Vec::new();
    for x in -5..=2 {
        for z in -6..=0 {
            for y in 1..=3 {
                sections.push(([x, y, z], 16));
            }
            sections.push(([x, 4, z], 3));
        }
    }
    sections
}

fn cube() -> BodyCollider {
    BodyCollider {
        shape: Shape::Cuboid {
            half_extents: [0.5, 0.5, 0.5],
        },
        density: 1000.0,
        material: ContactMaterial::default(),
        translation: Vec3::ZERO,
        rotation: Quat::IDENTITY,
    }
}

/// Le joueur, près des cubes : sans lui, R-612 les endormirait.
const OBSERVER: DVec3 = DVec3::new(-16.0, 68.0, -38.0);

/// Un tick tel que le joue `axion_sim_submit` puis `axion_sim_collect` ; rend la durée
/// d'`advance_all`, en ms.
fn tick(driver: &mut SimDriver, proxies: Vec<EntityProxy>) -> f64 {
    driver.begin_tick();
    driver.set_observers(DIM, &[OBSERVER]);
    if !proxies.is_empty() {
        driver.set_entity_proxies(DIM, proxies);
    }
    driver.sync_entity_proxies();
    driver.manage_activity();
    let started = Instant::now();
    driver.advance_all(TICK_DT);
    let elapsed = started.elapsed().as_secs_f64() * 1000.0;
    let _ = driver.drain_events();
    driver.release_idle_dimensions();
    elapsed
}

fn report(phase: &str, mut durations: Vec<f64>) {
    durations.sort_by(f64::total_cmp);
    let count = durations.len();
    let mean = durations.iter().sum::<f64>() / count as f64;
    // p95 au rang le plus proche, comme le gouverneur.
    let rank = (count * 95).div_ceil(100).max(1) - 1;
    println!(
        "{phase:<48} {count:>4} ticks  moyenne {mean:>8.2} ms  médiane {:>8.2} ms  p95 {:>8.2} ms  max {:>8.2} ms",
        durations[count / 2],
        durations[rank],
        durations[count - 1]
    );
}

fn main() {
    let sections = sections();
    let total: usize = sections
        .iter()
        .map(|(_, layers)| 256 * *layers as usize)
        .sum();
    println!(
        "{} sections, {total} boîtes statiques au total (une par bloc)\n",
        sections.len()
    );

    let mut driver = SimDriver::new();
    // Les trois cubes de la sauvegarde, posés au sol (y = 67).
    for (index, (x, z)) in [(-19.11, -43.77), (-17.71, -40.86), (-13.59, -41.66)]
        .into_iter()
        .enumerate()
    {
        driver.create_assembly(
            DIM,
            Handle {
                index: index as u32 + 1,
                generation: 1,
            },
            WorldTransform {
                position: [x, 67.5, z],
                rotation: [0.0, 0.0, 0.0, 1.0],
            },
            BodyKind::Dynamic,
            &[cube()],
        );
    }

    // 1. Diffusion des tuiles : huit sections par tick, comme le planificateur.
    let mut streaming = Vec::new();
    let mut apply = Vec::new();
    for batch in sections.chunks(TILES_PER_TICK) {
        let started = Instant::now();
        for (section, layers) in batch {
            driver.set_world_tile(DIM, *section, &boxes(*layers), ContactMaterial::default());
        }
        apply.push(started.elapsed().as_secs_f64() * 1000.0);
        streaming.push(tick(&mut driver, Vec::new()));
    }
    report("pose des tuiles (hors advance_all)", apply);
    report("advance_all pendant la diffusion", streaming);

    // 2. Régime établi : plus aucune tuile ne change.
    let steady: Vec<f64> = (0..200).map(|_| tick(&mut driver, Vec::new())).collect();
    report("advance_all, régime établi", steady);

    // 3. Une section neuve posée par tick, hors du voisinage : insertion seule, sans retrait.
    let mut insert_only = Vec::new();
    for index in 0..100 {
        let section = [3 + index / 7, 1, -6 + index % 7];
        driver.set_world_tile(DIM, section, &boxes(16), ContactMaterial::default());
        insert_only.push(tick(&mut driver, Vec::new()));
    }
    report("advance_all, une section neuve par tick", insert_only);
    for index in 0..100 {
        driver.remove_world_tile(DIM, [3 + index / 7, 1, -6 + index % 7]);
    }
    tick(&mut driver, Vec::new());

    // 4. Une section reconstruite par tick (invalidation : retrait puis pose).
    let mut rebuild = Vec::new();
    for index in 0..100 {
        let (section, layers) = sections[index % sections.len()];
        driver.set_world_tile(DIM, section, &boxes(layers), ContactMaterial::default());
        rebuild.push(tick(&mut driver, Vec::new()));
    }
    report("advance_all, une section reconstruite par tick", rebuild);

    // 5. Une entité qui entre puis sort du rayon d'influence, tick après tick : les ticks
    // où son proxy naît, et ceux où il est retiré.
    let proxy = EntityProxy {
        entity: 500,
        center: DVec3::new(-17.7, 67.7, -39.5),
        half_extents: [0.45, 0.7, 0.45],
        velocity: Vec3::ZERO,
        shape: ProxyShape::Capsule,
    };
    let mut enters = Vec::new();
    let mut leaves = Vec::new();
    for _ in 0..50 {
        enters.push(tick(&mut driver, vec![proxy]));
        leaves.push(tick(&mut driver, Vec::new()));
    }
    report("advance_all, tick où un proxy apparaît", enters);
    report("advance_all, tick où ce proxy est retiré", leaves);

    // 6. La même entité, présente à chaque tick.
    let present: Vec<f64> = (0..100).map(|_| tick(&mut driver, vec![proxy])).collect();
    report("advance_all, un proxy présent à chaque tick", present);
}
