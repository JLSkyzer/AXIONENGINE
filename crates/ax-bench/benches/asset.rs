//! B-09 — compilation d'asset par format, taille et options (PARTIE 30.2).
//!
//! On mesure [`ax_asset::compile::compile`] de bout en bout — import (C-21),
//! validation (C-22), optimizer (C-23), ecriture (C-24) — sur une source OBJ.
//! La taille varie d'un petit cube a une grille plus dense, ce qui exerce la
//! fusion des sommets, les tangentes, les LOD et l'ecriture du conteneur. Le
//! debit est rapporte en octets de source, ce qui donne un cout par octet.
//!
//! Les sources vivent dans `ax_bench::cases::asset` : le coureur de
//! non-regression mesure le meme cas, la definition de B-09 n'existe qu'une fois.

// La fonction d'entree du harnais est generee par `criterion_group!` : elle ne
// peut pas porter de documentation de notre part.
#![allow(missing_docs)]

use ax_asset::compile::compile;
use ax_asset::import::SourceFormat;
use ax_bench::cases::asset::{grid_obj, options, CUBE_OBJ};
use criterion::{black_box, criterion_group, criterion_main, BenchmarkId, Criterion, Throughput};

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
