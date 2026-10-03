//! Métriques du graphe de scène.

use crate::graph::SceneGraph;
use ax_telemetry::{MetricId, Telemetry, TelemetryBuilder, TelemetryError};
use std::time::Instant;

/// Métriques de C-30.
#[derive(Debug, Clone, Copy)]
pub struct SceneMetrics {
    propagate_ns: MetricId,
}

impl SceneMetrics {
    /// Nom de la durée de propagation, comptée dans `budgets.sim_ns_per_tick`.
    pub const PROPAGATE_NS: &'static str = "axion.scene.propagate_ns";

    /// Déclare les métriques.
    ///
    /// # Errors
    ///
    /// Remonte l'erreur du registre si elles sont déjà déclarées.
    pub fn register(builder: &mut TelemetryBuilder) -> Result<Self, TelemetryError> {
        Ok(Self {
            propagate_ns: builder.duration(Self::PROPAGATE_NS)?,
        })
    }

    /// Propage un graphe en mesurant la durée ; rend le nombre de transforms
    /// monde recalculées.
    ///
    /// La mesure est prise **par graphe** : les assemblies se propagent en
    /// parallèle (C-30), et un chronomètre autour du lot mesurerait le pool,
    /// pas le travail.
    pub fn propagate(&self, graph: &mut SceneGraph, telemetry: &Telemetry) -> usize {
        let start = Instant::now();
        let updated = graph.propagate();
        let nanos = u64::try_from(start.elapsed().as_nanos()).unwrap_or(u64::MAX);
        telemetry.record(self.propagate_ns, nanos);
        updated
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ax_math::{Affine3A, Vec3};
    use ax_model::dm::geometry::Transform;
    use ax_model::dm::scene::{
        node_flags, node_state, NodeDesc, ALL_LODS, NONE_U16, NONE_U32, NO_PARENT,
    };

    #[test]
    fn chaque_propagation_mesuree_laisse_une_duree() {
        let mut builder = Telemetry::builder();
        let metrics = SceneMetrics::register(&mut builder).expect("déclaration");
        let telemetry = builder.build();

        let nodes = [NodeDesc {
            name_hash: 0,
            parent: NO_PARENT,
            local: Transform::identity(),
            flags: node_flags::VISIBLE,
            mesh: NONE_U32,
            collider: NONE_U32,
            bone: NONE_U32,
            part: NONE_U16,
            region: NONE_U16,
            lod_mask: ALL_LODS,
            state: node_state::PROCEDURAL,
            mesh_count: 0,
        }];
        let mut graph = SceneGraph::from_nodes(&nodes).expect("graphe");

        assert_eq!(metrics.propagate(&mut graph, &telemetry), 1);
        graph
            .set_local(
                0,
                node_state::PROCEDURAL,
                Affine3A::from_translation(Vec3::X),
            )
            .expect("source légitime");
        assert_eq!(metrics.propagate(&mut graph, &telemetry), 1);

        let id = telemetry
            .id(SceneMetrics::PROPAGATE_NS)
            .expect("métrique déclarée");
        assert_eq!(telemetry.snapshot(id).count, 2);
    }

    #[test]
    fn declarer_deux_fois_les_metriques_est_refuse() {
        let mut builder = Telemetry::builder();
        SceneMetrics::register(&mut builder).expect("première déclaration");
        assert!(SceneMetrics::register(&mut builder).is_err());
    }
}
