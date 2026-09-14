//! B-02 — propagation du graphe de scene vs nombre de nodes (PARTIE 30.2).
//!
//! On mesure [`SceneGraph::propagate`] sur un graphe entierement sale : c'est le
//! cout d'un parcours complet. Le graphe est reconstruit hors mesure a chaque
//! iteration (le setup de `iter_batched` n'est pas chronometre), pour qu'aucune
//! mesure ne beneficie d'un graphe deja propre. B-02 varie avec le nombre de
//! nodes ; on trace donc plusieurs tailles, avec un debit par node.
//!
//! La construction d'entree vit dans `ax_bench::cases::scene` : le coureur de
//! non-regression mesure le meme cas, la definition de B-02 n'existe qu'une fois.

// La fonction d'entree du harnais est generee par `criterion_group!` : elle ne
// peut pas porter de documentation de notre part.
#![allow(missing_docs)]

use ax_bench::cases::scene::{chain, NODE_COUNTS};
use ax_scene::SceneGraph;
use criterion::{
    black_box, criterion_group, criterion_main, BatchSize, BenchmarkId, Criterion, Throughput,
};

fn bench_scene(c: &mut Criterion) {
    let mut group = c.benchmark_group("B-02/propagation");
    for &count in &NODE_COUNTS {
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
