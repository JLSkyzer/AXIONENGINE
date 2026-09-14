//! B-02 — propagation du graphe de scene vs nombre de nodes (PARTIE 30.2).
//!
//! On mesure [`SceneGraph::propagate`] sur un graphe entierement sale : c'est le
//! cout d'un parcours complet. Le graphe est reconstruit hors mesure a chaque
//! iteration (le setup de `iter_batched` n'est pas chronometre), pour qu'aucune
//! mesure ne beneficie d'un graphe deja propre. B-02 varie avec le nombre de
//! nodes ; on trace donc plusieurs tailles, avec un debit par node.

// La fonction d'entree du harnais est generee par `criterion_group!` : elle ne
// peut pas porter de documentation de notre part.
#![allow(missing_docs)]

use ax_model::dm::geometry::Transform;
use ax_model::dm::scene::{
    node_flags, node_state, NodeDesc, ALL_LODS, NONE_U16, NONE_U32, NO_PARENT,
};
use ax_scene::SceneGraph;
use criterion::{
    black_box, criterion_group, criterion_main, BatchSize, BenchmarkId, Criterion, Throughput,
};

/// Construit une chaine de `count` nodes : chaque node a pour parent le
/// precedent. L'ordre est topologique (un parent precede toujours son enfant),
/// et tous les nodes sont STATIC.
fn chain(count: u32) -> Vec<NodeDesc> {
    (0..count)
        .map(|i| NodeDesc {
            name_hash: 0,
            parent: if i == 0 { NO_PARENT } else { i - 1 },
            local: Transform {
                translation: [0.01, 0.0, 0.0],
                ..Transform::identity()
            },
            flags: node_flags::VISIBLE,
            mesh: NONE_U32,
            collider: NONE_U32,
            bone: NONE_U32,
            part: NONE_U16,
            region: NONE_U16,
            lod_mask: ALL_LODS,
            state: node_state::STATIC,
            _pad: [0; 2],
        })
        .collect()
}

fn bench_scene(c: &mut Criterion) {
    let mut group = c.benchmark_group("B-02/propagation");
    for &count in &[64u32, 256, 1024, 4096] {
        let nodes = chain(count);
        group.throughput(Throughput::Elements(u64::from(count)));
        group.bench_with_input(BenchmarkId::from_parameter(count), &nodes, |b, nodes| {
            b.iter_batched(
                || SceneGraph::from_nodes(nodes).expect("graphe valide"),
                |mut graph| black_box(graph.propagate()),
                BatchSize::SmallInput,
            );
        });
    }
    group.finish();
}

criterion_group!(benches, bench_scene);
criterion_main!(benches);
