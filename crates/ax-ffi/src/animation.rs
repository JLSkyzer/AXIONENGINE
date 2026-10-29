//! L'animation d'un asset côté natif (C-37, ADR-155 tranche 3) : ses clips, chargés avec lui, et
//! une instance par assembly animée — son [`Animator`] et son graphe de scène vivant (C-30).
//!
//! L'instance évalue la pose locale de chaque node au temps demandé, la propage (étape 14), puis
//! rend la matrice de chaque dessin au repos, dans l'espace de l'asset, comme `RestDraw.model` : le
//! rendu les substitue au repos (`RENDER_OUT` schéma 3, décision de Killian).
//!
//! # La palette (C-66, tranche 5)
//!
//! Un asset skinné porte son squelette (`SKEL`). La palette d'une instance est, pour chaque os,
//! `monde(node de l'os) × liaison inverse`, dans l'espace de l'asset : un sommet skinné y est porté
//! sans passer par la matrice du node de son mesh, que glTF ignore pour un mesh skinné. T-540 la
//! compare à ce calcul fait à part, os par os.
//!
//! # Les overlays (tranche 6b)
//!
//! Les overlays `bones`, `nodes` et `pivots` tracent la pose qu'une instance a évaluée en dernier :
//! chaque os depuis son os parent, le repère de chaque node, une croix à l'origine de chaque node
//! animé. Les points sont dans l'espace de l'asset ; l'appelant les place.

use ax_anim::{Animator, ClipLibrary, TICKS_PER_SECOND};
use ax_asset::a3d::AnimationTable;
use ax_asset::validate::validate_animations;
use ax_math::{Affine3A, Mat4, Quat, Vec3};
use ax_model::dm::animation::{AnimCommand, BoneDesc};
use ax_model::dm::debug::DebugSegment;
use ax_model::dm::geometry::Transform;
use ax_model::dm::scene::{node_state, NodeDesc, NONE_U16};
use ax_scene::{mat4x3, SceneGraph};
use std::time::Instant;

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
    /// Les os du squelette, dans l'ordre de `SKEL` : vide pour un asset sans skin.
    bones: Vec<Bone>,
}

/// Un os : son node, son os parent dans le squelette, et sa matrice de liaison inverse.
#[derive(Debug, Clone, Copy, PartialEq)]
struct Bone {
    node: u32,
    /// Le plus proche ancêtre de son node qui soit un os, tel que le compilateur l'a relevé (DM-10) ;
    /// `None` pour une racine du squelette.
    parent: Option<usize>,
    inverse_bind: Affine3A,
}

/// Écart toléré à la dernière ligne `(0, 0, 0, 1)` d'une liaison inverse, que glTF veut affine.
const AFFINE_TOLERANCE: f32 = 1e-5;

/// Ce qu'a coûté l'évaluation d'une pose, en nanosecondes (R-661) : l'échantillonnage des pistes,
/// étape 2 du pas, puis la propagation du graphe, étape 14 (R-660).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct PoseTimes {
    /// Étape 2 : les pistes échantillonnées, les nodes animés posés.
    pub animation_ns: u64,
    /// Étape 14 : le graphe propagé (C-30).
    pub propagate_ns: u64,
}

/// Longueur des axes de l'overlay `nodes`, en blocs, échelle du node comprise (tranche 6b).
pub const NODE_AXIS_LENGTH: f32 = 0.25;

/// Côté de la croix de l'overlay `pivots`, en blocs (tranche 6b).
pub const PIVOT_CROSS: f32 = 0.15;

impl AnimatedAsset {
    /// Construit l'animation d'un asset depuis ses sections `ANIM` et `SKEL` ; `None` s'il n'a aucun
    /// clip.
    ///
    /// # Errors
    ///
    /// Un texte qui dit pourquoi la table est refusée : la seconde passe de C-22 (R-540) la juge,
    /// et la hiérarchie ne doit pas mettre un node animé au-dessus d'un node physique (R-931). Un os
    /// qui désigne un node absent, ou dont la liaison inverse n'est pas finie et affine, refuse
    /// aussi la table. L'asset se rend alors au repos, comme une table `LODM` refusée (ADR-154 D4).
    pub fn new(
        nodes: &[NodeDesc],
        table: AnimationTable,
        draw_nodes: Vec<u32>,
        skeleton: &[BoneDesc],
    ) -> Result<Option<Self>, String> {
        if table.animations.is_empty() {
            return Ok(None);
        }
        let bones = skeleton
            .iter()
            .enumerate()
            .map(|(index, bone)| bone_of(index, bone, nodes.len(), skeleton.len()))
            .collect::<Result<Vec<_>, _>>()?;
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
            bones,
        }))
    }

    /// Le nombre d'os, donc de matrices de la palette d'une instance ; 0 sans skin.
    #[must_use]
    pub fn bone_count(&self) -> usize {
        self.bones.len()
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
            + self.bones.len() * size_of::<Bone>()
    }
}

/// L'os `index` de `SKEL`, vérifié contre les `node_count` nodes de l'asset et les `bone_count` os du
/// squelette.
fn bone_of(
    index: usize,
    bone: &BoneDesc,
    node_count: usize,
    bone_count: usize,
) -> Result<Bone, String> {
    if bone.node as usize >= node_count {
        return Err(format!(
            "os {index} : node {} hors des {node_count} de l'asset",
            bone.node
        ));
    }
    let parent = (bone.parent != NONE_U16).then_some(usize::from(bone.parent));
    if parent.is_some_and(|parent| parent >= bone_count) {
        return Err(format!(
            "os {index} : os parent {} hors des {bone_count} du squelette",
            bone.parent
        ));
    }
    let matrix = Mat4::from_cols_array(&bone.inverse_bind);
    let last = matrix.row(3);
    if !matrix.is_finite()
        || last.x.abs() > AFFINE_TOLERANCE
        || last.y.abs() > AFFINE_TOLERANCE
        || last.z.abs() > AFFINE_TOLERANCE
        || (last.w - 1.0).abs() > AFFINE_TOLERANCE
    {
        return Err(format!(
            "os {index} : liaison inverse non finie ou non affine"
        ));
    }
    Ok(Bone {
        node: bone.node,
        parent,
        inverse_bind: Affine3A::from_mat4(matrix),
    })
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
    /// d'avant : rien de non fini n'atteint le rendu. Rend la durée de chacune des deux étapes.
    pub fn draw_matrices(
        &mut self,
        asset: &AnimatedAsset,
        tick: f64,
        out: &mut Vec<f32>,
    ) -> PoseTimes {
        let started = Instant::now();
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
        let sampled = Instant::now();
        self.graph.propagate();
        let propagated = Instant::now();
        let worlds = self.graph.worlds();
        for &node in &asset.draw_nodes {
            out.extend_from_slice(&mat4x3(&worlds[node as usize]));
        }
        let nanos = |from: Instant, to: Instant| {
            u64::try_from(to.duration_since(from).as_nanos()).unwrap_or(u64::MAX)
        };
        PoseTimes {
            animation_ns: nanos(started, sampled),
            propagate_ns: nanos(sampled, propagated),
        }
    }

    /// Ajoute à `out` la palette de la pose que [`Self::draw_matrices`] a évaluée en dernier : la
    /// matrice `mat4x3` de chaque os, `monde(node) × liaison inverse`, dans l'espace de l'asset et
    /// dans l'ordre de `SKEL`. Rien pour un asset sans skin.
    pub fn palette(&self, asset: &AnimatedAsset, out: &mut Vec<f32>) {
        let worlds = self.graph.worlds();
        for bone in &asset.bones {
            out.extend_from_slice(&mat4x3(&(worlds[bone.node as usize] * bone.inverse_bind)));
        }
    }

    /// Ajoute à `out` les segments de l'overlay `bones`, à la pose que [`Self::draw_matrices`] a
    /// évaluée en dernier : de l'origine de l'os parent de chaque os — celui que `SKEL` lui donne, le
    /// plus proche ancêtre de son node qui soit un os — à la sienne. Une racine du squelette n'en a pas.
    /// Chaque point de l'espace de l'asset passe par `place`.
    pub fn bone_segments(
        &self,
        asset: &AnimatedAsset,
        place: impl Fn(Vec3) -> [f32; 3],
        out: &mut Vec<DebugSegment>,
    ) {
        let worlds = self.graph.worlds();
        for bone in &asset.bones {
            if let Some(parent) = bone.parent {
                let parent = asset.bones[parent].node as usize;
                out.push(DebugSegment {
                    a: place(Vec3::from(worlds[parent].translation)),
                    b: place(Vec3::from(worlds[bone.node as usize].translation)),
                });
            }
        }
    }

    /// Ajoute à `out` les segments de l'overlay `nodes` : le repère de chaque node à la dernière pose
    /// évaluée, trois axes de [`NODE_AXIS_LENGTH`] depuis son origine.
    pub fn node_segments(&self, place: impl Fn(Vec3) -> [f32; 3], out: &mut Vec<DebugSegment>) {
        for world in self.graph.worlds() {
            let origin = place(Vec3::from(world.translation));
            for axis in [Vec3::X, Vec3::Y, Vec3::Z] {
                out.push(DebugSegment {
                    a: origin,
                    b: place(world.transform_point3(axis * NODE_AXIS_LENGTH)),
                });
            }
        }
    }

    /// Ajoute à `out` les segments de l'overlay `pivots` : une croix de [`PIVOT_CROSS`] à l'origine de
    /// chaque node animé, autour de laquelle il tourne, à la dernière pose évaluée.
    pub fn pivot_segments(
        &self,
        asset: &AnimatedAsset,
        place: impl Fn(Vec3) -> [f32; 3],
        out: &mut Vec<DebugSegment>,
    ) {
        let worlds = self.graph.worlds();
        let half = PIVOT_CROSS * 0.5;
        for &node in &asset.animated {
            let center = Vec3::from(worlds[node as usize].translation);
            for axis in [Vec3::X, Vec3::Y, Vec3::Z] {
                out.push(DebugSegment {
                    a: place(center - axis * half),
                    b: place(center + axis * half),
                });
            }
        }
    }
}
