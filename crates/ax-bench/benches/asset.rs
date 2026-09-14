//! B-09 — compilation d'asset par format, taille et options (PARTIE 30.2).
//!
//! On mesure [`ax_asset::compile::compile`] de bout en bout — import (C-21),
//! validation (C-22), optimizer (C-23), ecriture (C-24) — sur une source OBJ.
//! La taille varie d'un petit cube a une grille plus dense, ce qui exerce la
//! fusion des sommets, les tangentes, les LOD et l'ecriture du conteneur. Le
//! debit est rapporte en octets de source, ce qui donne un cout par octet.

// La fonction d'entree du harnais est generee par `criterion_group!` : elle ne
// peut pas porter de documentation de notre part.
#![allow(missing_docs)]

use ax_asset::compile::{compile, CompileOptions};
use ax_asset::import::{ImportLimits, SourceFormat};
use ax_asset::optimize::LodOptions;
use criterion::{black_box, criterion_group, criterion_main, BenchmarkId, Criterion, Throughput};

const CUBE_OBJ: &str = "\
o cube
v -1.0 -1.0 -1.0
v  1.0 -1.0 -1.0
v  1.0  1.0 -1.0
v -1.0  1.0 -1.0
vt 0.0 0.0
vt 1.0 0.0
vt 1.0 1.0
vt 0.0 1.0
vn 0.0 0.0 -1.0
f 1/1/1 2/2/1 3/3/1
f 1/1/1 3/3/1 4/4/1
";

/// Genere un OBJ : une grille `side` x `side` de sommets dans le plan XY, deux
/// triangles par cellule, chaque sommet portant sa propre UV et une normale
/// commune `+Z`. Positions et UV distinctes, enroulement anti-horaire vu de
/// `+Z` : un maillage valide qui grandit avec `side`.
fn grid_obj(side: usize) -> String {
    assert!(side >= 2, "une grille a au moins deux sommets par cote");
    let mut s = String::new();
    s.push_str("o grid\n");
    let last = (side - 1) as f32;
    for iy in 0..side {
        for ix in 0..side {
            s.push_str(&format!("v {} {} 0.0\n", ix as f32, iy as f32));
        }
    }
    for iy in 0..side {
        for ix in 0..side {
            s.push_str(&format!("vt {} {}\n", ix as f32 / last, iy as f32 / last));
        }
    }
    s.push_str("vn 0.0 0.0 1.0\n");
    // Index OBJ 1-base du sommet (ix, iy).
    let vid = |ix: usize, iy: usize| iy * side + ix + 1;
    for iy in 0..side - 1 {
        for ix in 0..side - 1 {
            let a = vid(ix, iy);
            let b = vid(ix + 1, iy);
            let c = vid(ix + 1, iy + 1);
            let d = vid(ix, iy + 1);
            s.push_str(&format!("f {a}/{a}/1 {b}/{b}/1 {c}/{c}/1\n"));
            s.push_str(&format!("f {a}/{a}/1 {c}/{c}/1 {d}/{d}/1\n"));
        }
    }
    s
}

/// Options d'une compilation de bench : identifiants fixes (simple metadonnee
/// d'en-tete), plafond de source genereux, `dynamic_body` comme la CLI.
fn options() -> CompileOptions {
    CompileOptions {
        asset_id: 1,
        source_hash: 2,
        limits: ImportLimits::new(128 * 1024 * 1024),
        dynamic_body: true,
        lod: LodOptions::DEFAULT,
    }
}

fn bench_asset(c: &mut Criterion) {
    let cube = CUBE_OBJ.to_string();
    let grid16 = grid_obj(16);
    let grid32 = grid_obj(32);
    let cases = [
        ("cube", cube.as_str()),
        ("grille-16", grid16.as_str()),
        ("grille-32", grid32.as_str()),
    ];

    let opts = options();
    let mut group = c.benchmark_group("B-09/compilation-obj");
    for (label, src) in cases {
        let bytes = src.as_bytes();
        group.throughput(Throughput::Bytes(bytes.len() as u64));
        group.bench_with_input(BenchmarkId::from_parameter(label), bytes, |b, bytes| {
            b.iter(|| {
                let out = compile(bytes, SourceFormat::Obj, &opts, |_| None)
                    .expect("la source de bench compile");
                black_box(out.bytes.len())
            });
        });
    }
    group.finish();
}

criterion_group!(benches, bench_asset);
criterion_main!(benches);
