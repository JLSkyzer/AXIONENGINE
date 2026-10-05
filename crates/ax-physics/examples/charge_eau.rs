//! Mesure du pas de simulation et de la récolte sous la charge des volumes d'eau d'un lac
//! (C-38, R-642).
//!
//! L'essai en jeu du 2026-10-06 a vu le pas passer 4 à 9 ms dans son étape d'intégration avec
//! des cubes dans l'eau et près d'elle, puis, les cubes endormis par la dégradation, la récolte
//! coûter 8 à 11 ms hors du pas. La scène : un lac de 7 × 7 colonnes de sections, l'eau sur deux
//! sections de haut (de y = 32 à la surface, posée en y = 62 à 8/9 de bloc), un fond solide, et
//! six cubes flottants (densité 600). Le chronomètre entoure `advance_all`, puis
//! `collect_reports`, comme `axion_sim_collect`.
//!
//! La scène est jouée deux fois : l'eau en **une boîte par bloc**, ce que le pont Forge envoie,
//! puis **fusionnée** en une boîte par section et par hauteur d'eau.
//!
//! ```bash
//! cargo run --release -p ax-physics --example charge_eau
//! ```

use std::time::Instant;

use ax_math::{DVec3, Quat, Vec3};
use ax_model::dm::geometry::WorldTransform;
use ax_physics::{BodyCollider, BodyKind, ContactMaterial, Handle, Shape, SimDriver};

const DIM: u64 = 0;
const TICK_DT: f32 = 1.0 / 20.0;
/// Débit du planificateur de tuiles : `world.tiles_per_tick`, défaut 8.
const TILES_PER_TICK: usize = 8;
/// Masse volumique de l'eau douce, celle du pont Forge.
const WATER_DENSITY: f32 = 1000.0;
/// Hauteur d'un bloc d'eau sous l'air, celle que Minecraft donne à une source en surface.
const SURFACE_HEIGHT: f32 = 8.0 / 9.0;

/// L'eau d'une section : `full` couches pleines, puis, si `top`, une couche de surface de cette
/// hauteur. Une boîte par bloc d'eau.
fn water_per_block(full: u32, top: Option<f32>) -> Vec<[f32; 6]> {
    let mut boxes = Vec::new();
    for x in 0..16u32 {
        for z in 0..16u32 {
            let (fx, fz) = (x as f32, z as f32);
            for y in 0..full {
                let fy = y as f32;
                boxes.push([fx, fy, fz, fx + 1.0, fy + 1.0, fz + 1.0]);
            }
            if let Some(height) = top {
                let fy = full as f32;
                boxes.push([fx, fy, fz, fx + 1.0, fy + height, fz + 1.0]);
            }
        }
    }
    boxes
}

/// La même eau fusionnée : une boîte pour les couches pleines, une pour la surface.
fn water_merged(full: u32, top: Option<f32>) -> Vec<[f32; 6]> {
    let mut boxes = Vec::new();
    if full > 0 {
        boxes.push([0.0, 0.0, 0.0, 16.0, full as f32, 16.0]);
    }
    if let Some(height) = top {
        let fy = full as f32;
        boxes.push([0.0, fy, 0.0, 16.0, fy + height, 16.0]);
    }
    boxes
}

/// Une colonne du lac : le fond solide (section y = 1), l'eau pleine (y = 2), l'eau de surface
/// (y = 3 : quatorze couches pleines, puis la surface en y = 62).
#[derive(Clone, Copy)]
enum Tile {
    Floor,
    Water { full: u32, top: Option<f32> },
}

fn lake() -> Vec<([i32; 3], Tile)> {
    let mut tiles = Vec::new();
    for x in -4..=2 {
        for z in -3..=3 {
            tiles.push(([x, 1, z], Tile::Floor));
            tiles.push((
                [x, 2, z],
                Tile::Water {
                    full: 16,
                    top: None,
                },
            ));
            tiles.push((
                [x, 3, z],
                Tile::Water {
                    full: 14,
                    top: Some(SURFACE_HEIGHT),
                },
            ));
        }
    }
    tiles
}

fn floating_cube() -> BodyCollider {
    BodyCollider {
        shape: Shape::Cuboid {
            half_extents: [0.5, 0.5, 0.5],
        },
        density: 600.0,
        material: ContactMaterial::default(),
        translation: Vec3::ZERO,
        rotation: Quat::IDENTITY,
    }
}

/// Le joueur, au bord du lac : sans lui, R-612 endormirait les cubes.
const OBSERVER: DVec3 = DVec3::new(-8.0, 64.0, 0.0);

/// Un tick tel que le jouent `axion_sim_submit` puis `axion_sim_collect` ; rend la durée
/// d'`advance_all` puis celle de `collect_reports`, en ms.
fn tick(driver: &mut SimDriver) -> (f64, f64) {
    driver.begin_tick();
    driver.set_observers(DIM, &[OBSERVER]);
    driver.sync_entity_proxies();
    driver.manage_activity();
    let started = Instant::now();
    driver.advance_all(TICK_DT);
    let step = started.elapsed().as_secs_f64() * 1000.0;
    let started = Instant::now();
    let reports = driver.collect_reports();
    let collect = started.elapsed().as_secs_f64() * 1000.0;
    std::hint::black_box(reports);
    let _ = driver.drain_events();
    driver.release_idle_dimensions();
    (step, collect)
}

fn report(phase: &str, mut durations: Vec<f64>) {
    durations.sort_by(f64::total_cmp);
    let count = durations.len();
    let mean = durations.iter().sum::<f64>() / count as f64;
    // p95 au rang le plus proche, comme le gouverneur.
    let rank = (count * 95).div_ceil(100).max(1) - 1;
    println!(
        "{phase:<44} {count:>4} ticks  moyenne {mean:>8.3} ms  médiane {:>8.3} ms  p95 {:>8.3} ms  max {:>8.3} ms",
        durations[count / 2],
        durations[rank],
        durations[count - 1]
    );
}

/// Joue toute la scène avec l'eau que `water` donne pour une section.
fn scenario(title: &str, water: fn(u32, Option<f32>) -> Vec<[f32; 6]>) {
    let tiles = lake();
    let total: usize = tiles
        .iter()
        .map(|(_, tile)| match *tile {
            Tile::Floor => 0,
            Tile::Water { full, top } => water(full, top).len(),
        })
        .sum();
    println!("\n{title} : {} sections, {total} boîtes d'eau", tiles.len());

    let mut driver = SimDriver::new();
    // Six cubes flottants, posés à la surface.
    for index in 0..6u32 {
        driver.create_assembly(
            DIM,
            Handle {
                index: index + 1,
                generation: 1,
            },
            WorldTransform {
                position: [
                    -40.0 + 12.0 * f64::from(index),
                    62.6,
                    f64::from(index) * 3.0,
                ],
                rotation: [0.0, 0.0, 0.0, 1.0],
            },
            BodyKind::Dynamic,
            &[floating_cube()],
        );
    }

    // 1. Diffusion : huit sections par tick, comme le planificateur.
    let mut apply = Vec::new();
    let mut streaming = Vec::new();
    for batch in tiles.chunks(TILES_PER_TICK) {
        let started = Instant::now();
        for (section, tile) in batch {
            match *tile {
                Tile::Floor => {
                    driver.set_world_tile(
                        DIM,
                        *section,
                        &[[0.0, 0.0, 0.0, 16.0, 16.0, 16.0]],
                        ContactMaterial::default(),
                    );
                }
                Tile::Water { full, top } => {
                    driver.set_world_fluid_tile(DIM, *section, &water(full, top), WATER_DENSITY);
                }
            }
        }
        apply.push(started.elapsed().as_secs_f64() * 1000.0);
        streaming.push(tick(&mut driver).0);
    }
    report("pose des tuiles (hors advance_all)", apply);
    report("advance_all pendant la diffusion", streaming);

    // 2. Régime établi : les cubes flottent, plus aucune tuile ne change.
    let (steps, collects): (Vec<f64>, Vec<f64>) = (0..200).map(|_| tick(&mut driver)).unzip();
    report("advance_all, régime établi", steps);
    report("collect_reports, régime établi", collects);
}

fn main() {
    scenario("Une boîte d'eau par bloc", water_per_block);
    scenario("Eau fusionnée", water_merged);
}
