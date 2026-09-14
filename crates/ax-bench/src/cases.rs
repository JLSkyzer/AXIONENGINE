//! Cas de benchmark cables sur les composants existants.
//!
//! Chaque cas definit *ce que* mesure un B-xx (PARTIE 30.2) : sa construction
//! d'entree et la routine mesuree. Les benches criterion (R-2240) reutilisent
//! les memes constructions d'entree, et le coureur de non-regression appelle
//! [`scene::ci_case`] / [`asset::ci_case`] : la definition d'un benchmark
//! n'existe ainsi qu'a un seul endroit.
//!
//! Seuls les benchmarks dont le composant existe figurent ici. Les autres
//! (physique, rendu, deformation...) rejoindront a mesure de leurs jalons ; le
//! harnais ne mesure pas ce qui n'existe pas encore (R-001).

use std::collections::BTreeMap;

use serde_json::Value;

use crate::result::Run;

/// Un cas pret pour le coureur de non-regression : son identifiant, ses
/// parametres (pour la provenance, R-2231) et ses executions mesurees.
pub struct CiCase {
    /// Identifiant du benchmark (PARTIE 30.2), p. ex. `B-02`.
    pub benchmark: &'static str,
    /// Parametres du cas, verses dans le resultat.
    pub parameters: BTreeMap<String, Value>,
    /// Executions mesurees.
    pub runs: Vec<Run>,
}

/// B-02 — propagation du graphe de scene.
pub mod scene {
    use super::{BTreeMap, CiCase, Value};
    use crate::measure::{measure, MeasureConfig};
    use crate::result::Run;
    use ax_model::dm::geometry::Transform;
    use ax_model::dm::scene::{
        node_flags, node_state, NodeDesc, ALL_LODS, NONE_U16, NONE_U32, NO_PARENT,
    };
    use ax_scene::SceneGraph;

    /// Tailles de graphe tracees par le bench criterion (B-02).
    pub const NODE_COUNTS: [u32; 4] = [64, 256, 1024, 4096];

    /// Taille representative du cas de non-regression CI.
    pub const CI_NODES: u32 = 1024;

    /// Construit une chaine de `count` nodes : chaque node a pour parent le
    /// precedent. L'ordre est topologique (un parent precede toujours son
    /// enfant), et tous les nodes sont STATIC.
    #[must_use]
    pub fn chain(count: u32) -> Vec<NodeDesc> {
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

    /// Mesure la construction puis la propagation d'un graphe de `count` nodes.
    ///
    /// Contrairement au bench criterion — qui isole `propagate` d'un graphe deja
    /// construit —, le cas CI mesure `from_nodes` **et** `propagate` : c'est un
    /// cycle complet, stable et representatif du sous-systeme, qui evite d'avoir
    /// a re-salir un graphe entre deux iterations.
    #[must_use]
    pub fn measure_propagation(count: u32, config: &MeasureConfig) -> Vec<Run> {
        let nodes = chain(count);
        measure(config, || {
            let mut graph = SceneGraph::from_nodes(&nodes).expect("graphe valide");
            std::hint::black_box(graph.propagate());
        })
    }

    /// Cas de non-regression B-02 : construction + propagation d'un graphe de
    /// [`CI_NODES`] nodes.
    #[must_use]
    pub fn ci_case(config: &MeasureConfig) -> CiCase {
        let mut parameters = BTreeMap::new();
        parameters.insert("nodes".to_string(), Value::from(CI_NODES));
        parameters.insert("shape".to_string(), Value::from("chain"));
        CiCase {
            benchmark: "B-02",
            parameters,
            runs: measure_propagation(CI_NODES, config),
        }
    }
}

/// B-09 — compilation d'asset.
pub mod asset {
    use super::{BTreeMap, CiCase, Value};
    use crate::measure::{measure, MeasureConfig};
    use crate::result::Run;
    use ax_asset::compile::{compile, CompileOptions};
    use ax_asset::import::{ImportLimits, SourceFormat};
    use ax_asset::optimize::LodOptions;

    /// Petit cube OBJ valide (4 sommets, 2 triangles).
    pub const CUBE_OBJ: &str = "\
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

    /// Cote de grille du cas de non-regression CI.
    pub const CI_GRID_SIDE: usize = 16;

    /// Genere un OBJ : une grille `side` x `side` de sommets dans le plan XY,
    /// deux triangles par cellule, chaque sommet portant sa propre UV et une
    /// normale commune `+Z`. Positions et UV distinctes, enroulement
    /// anti-horaire vu de `+Z` : un maillage valide qui grandit avec `side`.
    #[must_use]
    pub fn grid_obj(side: usize) -> String {
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

    /// Options d'une compilation de bench : identifiants fixes (simple
    /// metadonnee d'en-tete), plafond de source genereux, `dynamic_body` comme
    /// la CLI.
    #[must_use]
    pub fn options() -> CompileOptions {
        CompileOptions {
            asset_id: 1,
            source_hash: 2,
            limits: ImportLimits::new(128 * 1024 * 1024),
            dynamic_body: true,
            lod: LodOptions::DEFAULT,
        }
    }

    /// Mesure la compilation d'une source OBJ de bout en bout (B-09).
    #[must_use]
    pub fn measure_compilation(source: &str, config: &MeasureConfig) -> Vec<Run> {
        let bytes = source.as_bytes();
        let opts = options();
        measure(config, || {
            let out = compile(bytes, SourceFormat::Obj, &opts, |_| None)
                .expect("la source de bench compile");
            std::hint::black_box(out.bytes.len());
        })
    }

    /// Cas de non-regression B-09 : compilation d'une grille
    /// [`CI_GRID_SIDE`] x [`CI_GRID_SIDE`].
    #[must_use]
    pub fn ci_case(config: &MeasureConfig) -> CiCase {
        let source = grid_obj(CI_GRID_SIDE);
        let mut parameters = BTreeMap::new();
        parameters.insert("format".to_string(), Value::from("obj"));
        parameters.insert("grid_side".to_string(), Value::from(CI_GRID_SIDE));
        parameters.insert("source_bytes".to_string(), Value::from(source.len()));
        CiCase {
            benchmark: "B-09",
            parameters,
            runs: measure_compilation(&source, config),
        }
    }
}

#[cfg(test)]
mod tests {
    use crate::measure::MeasureConfig;

    // Une configuration minuscule : juste de quoi verifier que les cas
    // s'executent et produisent des echantillons, sans tenir une seconde.
    fn tiny() -> MeasureConfig {
        MeasureConfig {
            warmup: std::time::Duration::ZERO,
            runs: 2,
            run_duration: std::time::Duration::ZERO,
            max_samples_per_run: 4,
        }
    }

    #[test]
    fn t_scene_ci_case_runs() {
        let case = super::scene::ci_case(&tiny());
        assert_eq!(case.benchmark, "B-02");
        assert!(case.runs.iter().all(|r| !r.samples_ns.is_empty()));
    }

    #[test]
    fn t_asset_ci_case_runs() {
        let case = super::asset::ci_case(&tiny());
        assert_eq!(case.benchmark, "B-09");
        assert!(case.runs.iter().all(|r| !r.samples_ns.is_empty()));
    }
}
