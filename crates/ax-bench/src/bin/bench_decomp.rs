//! ADR-146 §4 : la décomposition convexe de C-32 mesurée par résolution, pour fixer la plage et le
//! défaut de `collision.resolution` (ADR-108).
//!
//! Pour chaque modèle du corpus — les cinq exemples de R-1790 et une sphère UV fine, celle qui
//! avait montré le défaut de la tranche D —, et pour chaque résolution de `parry3d` (voxels le
//! long de l'axe le plus long), la décomposition telle que la fait `ax-asset` : durée médiane de
//! trois passages, pic de mémoire, enveloppes rendues et leurs sommets, volume des enveloppes
//! rapporté à celui du mesh. Les voxels de la grille s'en déduisent, à la manière de V-HACD,
//! dont `collision.resolution` reprend l'unité dans l'exemple du CDC.
//!
//! Chiffres d'un poste de développement : ils servent à choisir, ne se publient pas (R-2252).
//! Se compile en release.

use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::time::{Duration, Instant};

use ax_asset::collider::{model_geometry, Geometry};
use ax_asset::import::{import_gltf, ImportLimits};
use ax_bench::peak_bytes::{peak, PeakBytes};
use parry3d::math::Vector;
use parry3d::shape::{ConvexPolyhedron, Shape};
use parry3d::transformation::vhacd::{VHACDParameters, VHACD};
use serde::Serialize;

#[global_allocator]
static ALLOCATOR: PeakBytes = PeakBytes::new();

/// Résolutions balayées, le long de l'axe le plus long ; 64 est le défaut de `parry3d`.
const RESOLUTIONS: [u32; 9] = [16, 24, 32, 48, 64, 96, 128, 192, 256];

/// Passages par point ; la médiane fait foi.
const PASSAGES: usize = 3;

/// Au-delà, les résolutions suivantes du même modèle ne sont pas jouées.
const PLAFOND: Duration = Duration::from_secs(60);

/// Enveloppes demandées : le `max_hulls` par défaut d'`ax-asset`.
const MAX_HULLS: u32 = ax_asset::options::MAX_HULLS;

const HELP: &str = "bench-decomp — decomposition convexe par resolution (ADR-146 §4)\n\n\
Usage : bench-decomp [--out <fichier.json>]\n\n\
Balaye les resolutions de parry3d sur les cinq exemples de R-1790 et une sphere UV,\n\
et ecrit duree mediane, pic de memoire, enveloppes et volumes. Chiffres d'un poste\n\
de developpement : ils ne se publient pas (R-2252).";

#[derive(Debug, Serialize)]
struct Point {
    modele: String,
    triangles: usize,
    resolution: u32,
    voxels: u64,
    ns_median: u128,
    pic_octets: u64,
    enveloppes: usize,
    sommets: usize,
    volume_enveloppes: f64,
    volume_mesh: f64,
}

fn main() -> ExitCode {
    if cfg!(debug_assertions) {
        eprintln!("bench-decomp : a compiler en release (cargo run --release)");
        return ExitCode::from(2);
    }
    let args: Vec<String> = std::env::args().skip(1).collect();
    let out = match args.as_slice() {
        [] => None,
        [flag, path] if flag == "--out" => Some(PathBuf::from(path)),
        _ => {
            eprintln!("{HELP}");
            return ExitCode::from(2);
        }
    };
    // Le pic n'est mesuré que sous l'allocateur qui le tient : une charge connue le prouve.
    let ((), probe) = peak(|| drop(std::hint::black_box(vec![0u8; 1 << 20])));
    if probe < 1 << 20 {
        eprintln!("bench-decomp : l'allocateur de pic n'est pas installe");
        return ExitCode::from(1);
    }

    let mut corpus = match exemples() {
        Ok(corpus) => corpus,
        Err(message) => {
            eprintln!("bench-decomp : {message}");
            return ExitCode::from(1);
        }
    };
    corpus.push(("sphere_uv".to_owned(), sphere_uv(32, 64)));

    let mut points = Vec::new();
    for (nom, (cloud, tris)) in &corpus {
        let volume_mesh = volume(cloud, tris);
        for resolution in RESOLUTIONS {
            let point = mesurer(nom, cloud, tris, resolution, volume_mesh);
            let lente = point.ns_median > PLAFOND.as_nanos();
            println!(
                "{:<12} r={:<4} voxels~{:<10} {:>9.1} ms  pic {:>8.1} Mio  {:>2} env.  {:>5} sommets  vol {:.4}/{:.4}",
                point.modele,
                point.resolution,
                point.voxels,
                point.ns_median as f64 / 1e6,
                point.pic_octets as f64 / f64::from(1u32 << 20),
                point.enveloppes,
                point.sommets,
                point.volume_enveloppes,
                point.volume_mesh
            );
            points.push(point);
            if lente {
                println!(
                    "{nom} : au-dela de {} s, resolutions suivantes non jouees",
                    PLAFOND.as_secs()
                );
                break;
            }
        }
    }

    if let Some(path) = out {
        let json = match serde_json::to_string_pretty(&points) {
            Ok(json) => json,
            Err(error) => {
                eprintln!("bench-decomp : {error}");
                return ExitCode::from(1);
            }
        };
        if let Err(error) = std::fs::write(&path, json) {
            eprintln!("bench-decomp : {} : {error}", path.display());
            return ExitCode::from(1);
        }
        println!("ecrit : {}", path.display());
    }
    ExitCode::SUCCESS
}

/// Les cinq exemples de R-1790, dans le repère de leur asset, comme les décompose `ax-asset`.
fn exemples() -> Result<Vec<(String, Geometry)>, String> {
    let dossier = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../java/axion-mod/src/main/resources/data/axion/axion/models/example");
    let mut corpus = Vec::new();
    for nom in ["barrier", "buggy", "crate", "flag", "winch_rope"] {
        let path = dossier.join(format!("{nom}.glb"));
        let bytes =
            std::fs::read(&path).map_err(|error| format!("{} : {error}", path.display()))?;
        let (asset, _) = import_gltf(&bytes, &ImportLimits::new(u64::MAX), |_| None)
            .map_err(|error| format!("{nom} : {error:?}"))?;
        let geometry = model_geometry(&asset.nodes, &asset.meshes, &asset.vertices, &asset.indices)
            .map_err(|error| format!("{nom} : {error}"))?;
        corpus.push((nom.to_owned(), geometry));
    }
    Ok(corpus)
}

/// Une sphère UV de rayon 0,5 : `anneaux` bandes de latitude, `secteurs` de longitude.
fn sphere_uv(anneaux: u32, secteurs: u32) -> Geometry {
    let mut cloud = vec![[0.0, 0.5, 0.0]];
    for anneau in 1..anneaux {
        let phi = std::f64::consts::PI * f64::from(anneau) / f64::from(anneaux);
        for secteur in 0..secteurs {
            let theta = std::f64::consts::TAU * f64::from(secteur) / f64::from(secteurs);
            #[allow(clippy::cast_possible_truncation)]
            cloud.push([
                (0.5 * phi.sin() * theta.cos()) as f32,
                (0.5 * phi.cos()) as f32,
                (0.5 * phi.sin() * theta.sin()) as f32,
            ]);
        }
    }
    cloud.push([0.0, -0.5, 0.0]);
    let sommet = |anneau: u32, secteur: u32| 1 + (anneau - 1) * secteurs + secteur % secteurs;
    let bas = 1 + (anneaux - 1) * secteurs;
    let mut tris = Vec::new();
    for secteur in 0..secteurs {
        tris.push([0, sommet(1, secteur + 1), sommet(1, secteur)]);
        tris.push([
            bas,
            sommet(anneaux - 1, secteur),
            sommet(anneaux - 1, secteur + 1),
        ]);
    }
    for anneau in 1..anneaux - 1 {
        for secteur in 0..secteurs {
            let (a, b) = (sommet(anneau, secteur), sommet(anneau, secteur + 1));
            let (c, d) = (sommet(anneau + 1, secteur), sommet(anneau + 1, secteur + 1));
            tris.push([a, b, d]);
            tris.push([a, d, c]);
        }
    }
    (cloud, tris)
}

/// Un point de mesure : la décomposition d'`ax-asset` à cette résolution.
fn mesurer(
    nom: &str,
    cloud: &[[f32; 3]],
    tris: &[[u32; 3]],
    resolution: u32,
    volume_mesh: f64,
) -> Point {
    let points: Vec<Vector> = cloud
        .iter()
        .map(|p| Vector::new(p[0], p[1], p[2]))
        .collect();
    let params = VHACDParameters {
        max_convex_hulls: MAX_HULLS,
        resolution,
        ..VHACDParameters::default()
    };
    let mut durees = Vec::with_capacity(PASSAGES);
    let mut pic = 0;
    let mut hulls = Vec::new();
    for _ in 0..PASSAGES {
        let start = Instant::now();
        let (rendu, octets) = peak(|| {
            let decomposition = VHACD::decompose(&params, &points, tris, false);
            decomposition.compute_convex_hulls(params.convex_hull_downsampling)
        });
        let duree = start.elapsed();
        durees.push(duree);
        pic = pic.max(octets);
        hulls = rendu;
        if duree > PLAFOND {
            break;
        }
    }
    durees.sort();
    let volume_enveloppes = hulls
        .iter()
        .filter_map(|(sommets, _)| ConvexPolyhedron::from_convex_hull(sommets))
        .map(|hull| f64::from(hull.mass_properties(1.0).mass()))
        .sum();
    Point {
        modele: nom.to_owned(),
        triangles: tris.len(),
        resolution,
        voxels: voxels(cloud, resolution),
        ns_median: durees[durees.len() / 2].as_nanos(),
        pic_octets: pic,
        enveloppes: hulls.len(),
        sommets: hulls.iter().map(|(sommets, _)| sommets.len()).sum(),
        volume_enveloppes,
        volume_mesh,
    }
}

/// Voxels de la grille à cette résolution : `resolution` sur l'axe le plus long, les autres axes
/// au prorata, au moins un — une estimation, `parry3d` ne rendant pas sa grille.
fn voxels(cloud: &[[f32; 3]], resolution: u32) -> u64 {
    let mut min = [f64::INFINITY; 3];
    let mut max = [f64::NEG_INFINITY; 3];
    for p in cloud {
        for axe in 0..3 {
            min[axe] = min[axe].min(f64::from(p[axe]));
            max[axe] = max[axe].max(f64::from(p[axe]));
        }
    }
    let mut cotes: Vec<f64> = (0..3).map(|axe| (max[axe] - min[axe]).max(0.0)).collect();
    cotes.sort_by(|a, b| b.total_cmp(a));
    if cotes[0] <= 0.0 {
        return 0;
    }
    let r = f64::from(resolution);
    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    let axe = |cote: f64| ((r * cote / cotes[0]).ceil().max(1.0)) as u64;
    u64::from(resolution) * axe(cotes[1]) * axe(cotes[2])
}

/// Volume enfermé par le mesh, par le théorème de la divergence ; proche de zéro pour un mesh
/// ouvert, ce que le rapport dit tel quel.
fn volume(cloud: &[[f32; 3]], tris: &[[u32; 3]]) -> f64 {
    let p = |i: u32| cloud[i as usize].map(f64::from);
    let total: f64 = tris
        .iter()
        .map(|&[a, b, c]| {
            let (a, b, c) = (p(a), p(b), p(c));
            a[0] * (b[1] * c[2] - b[2] * c[1]) - a[1] * (b[0] * c[2] - b[2] * c[0])
                + a[2] * (b[0] * c[1] - b[1] * c[0])
        })
        .sum();
    (total / 6.0).abs()
}
