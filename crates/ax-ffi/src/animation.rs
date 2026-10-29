//! L'animation d'un asset côté natif (C-37, ADR-155 tranche 3) : ses clips, chargés avec lui, et
//! une instance par assembly animée — son [`Animator`] et son graphe de scène vivant (C-30).
//!
//! L'instance évalue la pose locale de chaque node au temps demandé, la propage (étape 14), puis
//! rend la matrice de chaque dessin au repos, dans l'espace de l'asset, comme `RestDraw.model` : le
//! rendu les substitue au repos (`RENDER_OUT` schéma 3, décision de Killian).

use ax_anim::{Animator, ClipLibrary, TICKS_PER_SECOND};
use ax_asset::a3d::AnimationTable;
use ax_asset::validate::validate_animations;
use ax_math::{Affine3A, Quat, Vec3};
use ax_model::dm::animation::AnimCommand;
use ax_model::dm::geometry::Transform;
use ax_model::dm::scene::{node_state, NodeDesc};
use ax_scene::{mat4x3, SceneGraph};

/// Les clips d'un asset chargé et ce qu'il faut pour en tirer les matrices de ses dessins.
#[derive(Debug, Clone, PartialEq)]
pub struct AnimatedAsset {
    clips: ClipLibrary,
    /// Les nodes, l'état de chaque node animé posé à `ANIMATION_DRIVEN` (R-930).
    nodes: Vec<NodeDesc>,
    /// La pose locale de repos de chaque node.
    rest: Vec<Transform>,
    /// Les nodes animés, dans l'ordre : ceux dont l'évaluation pose la transformation locale.
    animated: Vec<u32>,
    /// Le node de chaque dessin au repos, dans l'ordre de la liste au repos.
    draw_nodes: Vec<u32>,
}

impl AnimatedAsset {
    /// Construit l'animation d'un asset depuis sa section `ANIM` ; `None` s'il n'a aucun clip.
    ///
    /// # Errors
    ///
    /// Un texte qui dit pourquoi la table est refusée : la seconde passe de C-22 (R-540) la juge,
    /// et la hiérarchie ne doit pas mettre un node animé au-dessus d'un node physique (R-931).
    /// L'asset se rend alors au repos, comme une table `LODM` refusée (ADR-154 D4).
    pub fn new(
        nodes: &[NodeDesc],
        table: AnimationTable,
        draw_nodes: Vec<u32>,
    ) -> Result<Option<Self>, String> {
        if table.animations.is_empty() {
            return Ok(None);
        }
        let report = validate_animations(nodes, &table.animations, &table.tracks, &table.keys);
        if let Some(error) = report.errors.first() {
            return Err(error.to_string());
        }
        let clips = ClipLibrary::new(
            table.animations,
            table.names,
            table.tracks,
            table.keys,
            nodes.len(),
        )
        .map_err(|error| format!("{error:?}"))?;
        let flags = clips.animated_nodes(nodes.len());
        let mut nodes = nodes.to_vec();
        let mut animated = Vec::new();
        for (index, (node, moves)) in nodes.iter_mut().zip(&flags).enumerate() {
            if *moves && node.state == node_state::STATIC {
                node.state = node_state::ANIMATION_DRIVEN;
                animated.push(index as u32);
            }
        }
        // Le graphe se construit une fois ici : une hiérarchie qu'il refuse est refusée au chargement,
        // jamais à la première frame animée.
        SceneGraph::from_nodes(&nodes).map_err(|error| error.to_string())?;
        let rest = nodes.iter().map(|node| node.local).collect();
        Ok(Some(Self {
            clips,
            nodes,
            rest,
            animated,
            draw_nodes,
        }))
    }

    /// Les clips de l'asset.
    #[must_use]
    pub fn clips(&self) -> &ClipLibrary {
        &self.clips
    }

    /// Le nombre de dessins au repos, donc de matrices par instance.
    #[must_use]
    pub fn draw_count(&self) -> usize {
        self.draw_nodes.len()
    }

    /// Octets résidents, pour le compte de l'arène `PERSISTENT` (R-480).
    #[must_use]
    pub fn resident_bytes(&self) -> usize {
        self.nodes.len() * (size_of::<NodeDesc>() + size_of::<Transform>())
            + (self.animated.len() + self.draw_nodes.len()) * size_of::<u32>()
    }
}

/// L'animation d'une assembly : ses couches et son graphe vivant.
#[derive(Debug, Clone)]
pub struct Instance {
    animator: Animator,
    graph: SceneGraph,
    pose: Vec<Transform>,
}

impl Instance {
    /// Une instance de l'asset, ses couches vides.
    ///
    /// # Panics
    ///
    /// Jamais : [`AnimatedAsset::new`] a déjà construit ce graphe.
    #[must_use]
    pub fn new(asset: &AnimatedAsset) -> Self {
        Self {
            animator: Animator::new(asset.nodes.len()),
            graph: SceneGraph::from_nodes(&asset.nodes).expect("graphe vérifié au chargement"),
            pose: asset.rest.clone(),
        }
    }

    /// Applique une commande ; une commande refusée ne change rien (`ax-anim`).
    ///
    /// # Errors
    ///
    /// Celles de [`Animator::apply`].
    pub fn apply(
        &mut self,
        asset: &AnimatedAsset,
        command: &AnimCommand,
        mask: &[u32],
    ) -> Result<(), ax_anim::AnimError> {
        self.animator.apply(&asset.clips, command, mask)
    }

    /// Évalue la pose au temps `tick`, la propage, et ajoute à `out` la matrice `mat4x3` de chaque
    /// dessin au repos, dans leur ordre. Un node que l'évaluation rendrait non fini garde sa pose
    /// d'avant : rien de non fini n'atteint le rendu.
    pub fn draw_matrices(&mut self, asset: &AnimatedAsset, tick: f64, out: &mut Vec<f32>) {
        self.animator.evaluate(
            &asset.clips,
            &asset.rest,
            tick / TICKS_PER_SECOND,
            &mut self.pose,
        );
        for &node in &asset.animated {
            let local = &self.pose[node as usize];
            let affine = Affine3A::from_scale_rotation_translation(
                Vec3::from(local.scale),
                Quat::from_array(local.rotation),
                Vec3::from(local.translation),
            );
            // Seul un node animé est écrit, et sous sa source : l'erreur est impossible ou laisse la
            // pose d'avant.
            let _ = self
                .graph
                .set_local(node, node_state::ANIMATION_DRIVEN, affine);
        }
        self.graph.propagate();
        let worlds = self.graph.worlds();
        for &node in &asset.draw_nodes {
            out.extend_from_slice(&mat4x3(&worlds[node as usize]));
        }
    }
}
