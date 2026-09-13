//! Le graphe de scène d'une assembly (C-30).

use crate::bitset::BitSet;
use ax_math::{Affine3A, Quat, Vec3};
use ax_model::dm::geometry::Transform;
use ax_model::dm::limits;
use ax_model::dm::scene::{node_flags, node_state, NodeDesc, NO_PARENT};
use core::fmt;

/// Ce qui empêche de construire ou de piloter un graphe.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SceneError {
    /// Plus de nodes que C-22 n'en admet.
    TooManyNodes {
        /// Nombre de nodes reçus.
        count: usize,
    },
    /// Un node désigne un parent qui ne le précède pas : l'ordre n'est pas
    /// topologique, et la propagation linéaire lirait un parent périmé.
    ParentAfterChild {
        /// Node fautif.
        node: u32,
        /// Parent désigné.
        parent: u32,
    },
    /// L'état du node n'est aucun de ceux de SM-03.
    UnknownState {
        /// Node fautif.
        node: u32,
        /// Valeur lue.
        state: u8,
    },
    /// Le quaternion de rotation est nul ou non fini.
    RotationNotNormalizable {
        /// Node fautif.
        node: u32,
    },
    /// La transform locale d'un asset n'est pas finie.
    NotFiniteInAsset {
        /// Node fautif.
        node: u32,
    },
    /// Un node `PHYSICS_DRIVEN` sous un parent `ANIMATION_DRIVEN` ou
    /// `PROCEDURAL` (R-931).
    PhysicsUnderAnimatedParent {
        /// Node physique.
        node: u32,
        /// Parent animé.
        parent: u32,
    },
    /// Index de node hors du graphe.
    NodeOutOfRange {
        /// Index demandé.
        node: u32,
        /// Nombre de nodes du graphe.
        count: u32,
    },
    /// Une source pilote un node qui n'est pas dans son état (R-930).
    WrongSource {
        /// Node visé.
        node: u32,
        /// État du node.
        state: u8,
        /// Source qui prétendait le piloter.
        source: u8,
    },
    /// Une transform reçue au runtime n'est pas finie.
    NotFiniteInput {
        /// Node visé.
        node: u32,
    },
}

impl SceneError {
    /// Code de l'ANNEXE A.1 correspondant.
    ///
    /// L'annexe n'a pas de code propre au graphe de scène. Chaque erreur prend
    /// celui de la cause qu'elle partage : la structure d'un asset (`E-3021`,
    /// `E-3050`, comme le validateur), le quaternion (`E-2010`), la hiérarchie
    /// d'une definition (`E-7002`), le défaut interne d'un appelant qui désigne
    /// un node inexistant ou qui ne lui revient pas (`E-2001`), l'état non fini
    /// reçu d'un système (`E-2030`, le seul « état non fini » de l'annexe, dont
    /// la réponse — rejeter et garder le dernier état valide — est la même).
    #[must_use]
    pub fn code(&self) -> i32 {
        match self {
            SceneError::ParentAfterChild { .. } => -3021,
            SceneError::TooManyNodes { .. }
            | SceneError::UnknownState { .. }
            | SceneError::NotFiniteInAsset { .. } => -3050,
            SceneError::RotationNotNormalizable { .. } => -2010,
            SceneError::PhysicsUnderAnimatedParent { .. } => -7002,
            SceneError::NodeOutOfRange { .. } | SceneError::WrongSource { .. } => -2001,
            SceneError::NotFiniteInput { .. } => -2030,
        }
    }
}

impl fmt::Display for SceneError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            SceneError::TooManyNodes { count } => {
                write!(formatter, "{count} nodes, maximum {}", limits::MAX_NODES)
            }
            SceneError::ParentAfterChild { node, parent } => write!(
                formatter,
                "node {node} : parent {parent} placé après lui, l'ordre doit être topologique"
            ),
            SceneError::UnknownState { node, state } => {
                write!(formatter, "node {node} : état {state} inconnu")
            }
            SceneError::RotationNotNormalizable { node } => {
                write!(formatter, "node {node} : quaternion non normalisable")
            }
            SceneError::NotFiniteInAsset { node } => {
                write!(formatter, "node {node} : transform d'asset non finie")
            }
            SceneError::PhysicsUnderAnimatedParent { node, parent } => write!(
                formatter,
                "node {node} : piloté par la physique sous le parent animé {parent}"
            ),
            SceneError::NodeOutOfRange { node, count } => {
                write!(formatter, "node {node} hors d'un graphe de {count} nodes")
            }
            SceneError::WrongSource {
                node,
                state,
                source,
            } => write!(
                formatter,
                "node {node} : la source {source} ne pilote pas un node d'état {state}"
            ),
            SceneError::NotFiniteInput { node } => {
                write!(formatter, "node {node} : transform reçue non finie")
            }
        }
    }
}

impl std::error::Error for SceneError {}

/// Graphe de scène d'une assembly.
///
/// Les colonnes de la fiche C-30, en tableaux parallèles indexés par node.
/// Elles sont en lecture seule depuis l'extérieur : écrire une transform locale
/// sans marquer le node sale la ferait ignorer par la propagation, et c'est un
/// défaut qu'aucun test ne verrait tant que rien d'autre ne bouge. Les
/// écritures passent donc par des méthodes qui tiennent le suivi.
#[derive(Debug, Clone)]
pub struct SceneGraph {
    parent: Vec<u32>,
    local: Vec<Affine3A>,
    world: Vec<Affine3A>,
    flags: Vec<u32>,
    dirty: BitSet,
    mesh: Vec<u32>,
    bone: Vec<u32>,
    part: Vec<u16>,
    region: Vec<u16>,
    visible: BitSet,
    state: Vec<u8>,
    /// Transform monde reçue de C-31, en attente de propagation (R-600).
    physics_world: Vec<Affine3A>,
    physics_pending: BitSet,
    /// Nodes `INTERNAL` révélés par l'étape de dommage de leur part (R-952).
    revealed: BitSet,
    /// Nodes dont la transform monde a changé à la dernière propagation.
    changed: BitSet,
    visibility_dirty: bool,
}

impl SceneGraph {
    /// Construit le graphe d'une assembly depuis les nodes de son asset.
    ///
    /// Tous les nodes sont sales : la première propagation calcule toutes les
    /// transforms monde. Un node déclaré `DETACHED` emporte ses descendants,
    /// comme [`Self::detach`].
    ///
    /// # Errors
    ///
    /// [`SceneError::TooManyNodes`], [`SceneError::ParentAfterChild`],
    /// [`SceneError::UnknownState`], [`SceneError::RotationNotNormalizable`],
    /// [`SceneError::NotFiniteInAsset`], et
    /// [`SceneError::PhysicsUnderAnimatedParent`] (R-931, `E-7002`).
    pub fn from_nodes(nodes: &[NodeDesc]) -> Result<Self, SceneError> {
        if nodes.len() > limits::MAX_NODES {
            return Err(SceneError::TooManyNodes { count: nodes.len() });
        }
        let count = nodes.len();
        let mut local = Vec::with_capacity(count);

        for (index, node) in nodes.iter().enumerate() {
            let id = index as u32;
            if node.parent != NO_PARENT && node.parent >= id {
                return Err(SceneError::ParentAfterChild {
                    node: id,
                    parent: node.parent,
                });
            }
            if !node_state::is_known(node.state) {
                return Err(SceneError::UnknownState {
                    node: id,
                    state: node.state,
                });
            }
            if node.state == node_state::PHYSICS_DRIVEN && node.parent != NO_PARENT {
                let parent_state = nodes[node.parent as usize].state;
                if matches!(
                    parent_state,
                    node_state::ANIMATION_DRIVEN | node_state::PROCEDURAL
                ) {
                    return Err(SceneError::PhysicsUnderAnimatedParent {
                        node: id,
                        parent: node.parent,
                    });
                }
            }
            local.push(to_affine(id, &node.local)?);
        }

        let mut graph = Self {
            parent: nodes.iter().map(|node| node.parent).collect(),
            local,
            world: vec![Affine3A::IDENTITY; count],
            flags: nodes.iter().map(|node| node.flags).collect(),
            dirty: BitSet::filled(count),
            mesh: nodes.iter().map(|node| node.mesh).collect(),
            bone: nodes.iter().map(|node| node.bone).collect(),
            part: nodes.iter().map(|node| node.part).collect(),
            region: nodes.iter().map(|node| node.region).collect(),
            visible: BitSet::new(count),
            state: nodes.iter().map(|node| node.state).collect(),
            physics_world: vec![Affine3A::IDENTITY; count],
            physics_pending: BitSet::new(count),
            revealed: BitSet::new(count),
            changed: BitSet::new(count),
            visibility_dirty: true,
        };
        for index in 0..count {
            if graph.state[index] == node_state::DETACHED {
                graph.detach_subtree(index);
            }
        }
        Ok(graph)
    }

    /// Nombre de nodes.
    #[must_use]
    pub fn len(&self) -> usize {
        self.parent.len()
    }

    /// Indique si le graphe n'a aucun node.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.parent.is_empty()
    }

    /// Parent de chaque node, `NO_PARENT` pour une racine.
    #[must_use]
    pub fn parents(&self) -> &[u32] {
        &self.parent
    }

    /// Transform locale de chaque node.
    #[must_use]
    pub fn locals(&self) -> &[Affine3A] {
        &self.local
    }

    /// Transform monde de chaque node, en espace de l'assembly.
    ///
    /// À jour après [`Self::propagate`]. L'espace est celui de la simulation
    /// locale de l'assembly, en `f32` autour de son origine flottante (R-462).
    #[must_use]
    pub fn worlds(&self) -> &[Affine3A] {
        &self.world
    }

    /// Drapeaux de chaque node, voir `node_flags`.
    #[must_use]
    pub fn flags(&self) -> &[u32] {
        &self.flags
    }

    /// Mesh porté par chaque node.
    #[must_use]
    pub fn meshes(&self) -> &[u32] {
        &self.mesh
    }

    /// Os associé à chaque node.
    #[must_use]
    pub fn bones(&self) -> &[u32] {
        &self.bone
    }

    /// Part d'appartenance de chaque node.
    #[must_use]
    pub fn parts(&self) -> &[u16] {
        &self.part
    }

    /// Région de déformation de chaque node.
    #[must_use]
    pub fn regions(&self) -> &[u16] {
        &self.region
    }

    /// État de chaque node, voir `node_state`.
    #[must_use]
    pub fn states(&self) -> &[u8] {
        &self.state
    }

    /// Nodes visibles, à jour après [`Self::propagate`].
    #[must_use]
    pub fn visible(&self) -> &BitSet {
        &self.visible
    }

    /// Nodes sales, en attente de propagation.
    #[must_use]
    pub fn dirty(&self) -> &BitSet {
        &self.dirty
    }

    /// Nodes dont la transform monde a changé à la dernière propagation.
    ///
    /// C'est ce que lisent le rendu et la physique pour ne rafraîchir que ce
    /// qui a bougé.
    #[must_use]
    pub fn changed(&self) -> &BitSet {
        &self.changed
    }

    /// Indique si un node `INTERNAL` a été révélé (R-952).
    #[must_use]
    pub fn is_revealed(&self, node: u32) -> bool {
        self.revealed.contains(node as usize)
    }

    /// Écrit la transform locale d'un node, depuis la source qui le pilote.
    ///
    /// Seules les sources de transform locale — `ANIMATION_DRIVEN`,
    /// `PROCEDURAL`, `JOINT_DRIVEN` — écrivent ici, et chacune ne pilote que les
    /// nodes de son état : la priorité de R-930 a été tranchée à la compilation,
    /// un node n'a qu'une source au runtime. Un node `STATIC` est constant ; un
    /// node `PHYSICS_DRIVEN` reçoit sa transform monde par
    /// [`Self::set_physics_world`].
    ///
    /// # Errors
    ///
    /// [`SceneError::NodeOutOfRange`], [`SceneError::WrongSource`],
    /// [`SceneError::NotFiniteInput`]. Le graphe n'est pas modifié.
    pub fn set_local(&mut self, node: u32, source: u8, local: Affine3A) -> Result<(), SceneError> {
        let index = self.check(node)?;
        let state = self.state[index];
        let drives_local = matches!(
            source,
            node_state::ANIMATION_DRIVEN | node_state::PROCEDURAL | node_state::JOINT_DRIVEN
        );
        if !drives_local || state != source {
            return Err(SceneError::WrongSource {
                node,
                state,
                source,
            });
        }
        if !local.is_finite() {
            return Err(SceneError::NotFiniteInput { node });
        }
        self.local[index] = local;
        self.dirty.insert(index);
        Ok(())
    }

    /// Transmet la transform monde qu'un body impose à son node (R-600).
    ///
    /// Elle est appliquée à la propagation suivante, avant les enfants du node,
    /// et sa transform locale en est recalculée.
    ///
    /// # Errors
    ///
    /// [`SceneError::NodeOutOfRange`], [`SceneError::WrongSource`] si le node
    /// n'est pas `PHYSICS_DRIVEN`, [`SceneError::NotFiniteInput`].
    pub fn set_physics_world(&mut self, node: u32, world: Affine3A) -> Result<(), SceneError> {
        let index = self.check(node)?;
        let state = self.state[index];
        if state != node_state::PHYSICS_DRIVEN {
            return Err(SceneError::WrongSource {
                node,
                state,
                source: node_state::PHYSICS_DRIVEN,
            });
        }
        if !world.is_finite() {
            return Err(SceneError::NotFiniteInput { node });
        }
        self.physics_world[index] = world;
        self.physics_pending.insert(index);
        Ok(())
    }

    /// Détache un node et ses descendants : ils appartiennent désormais à une
    /// autre assembly (débris). Rend le nombre de nodes détachés.
    ///
    /// Un node détaché n'est plus propagé ni visible ici ; sa dernière
    /// transform monde reste lisible, pour que l'assembly de débris parte de là.
    ///
    /// # Errors
    ///
    /// [`SceneError::NodeOutOfRange`].
    pub fn detach(&mut self, node: u32) -> Result<usize, SceneError> {
        let index = self.check(node)?;
        Ok(self.detach_subtree(index))
    }

    /// Révèle ou masque un node `INTERNAL` (R-952).
    ///
    /// C'est l'étape de dommage de la part qui le couvre qui en décide ; le
    /// graphe n'en tient que le résultat.
    ///
    /// # Errors
    ///
    /// [`SceneError::NodeOutOfRange`].
    pub fn set_revealed(&mut self, node: u32, revealed: bool) -> Result<(), SceneError> {
        let index = self.check(node)?;
        if self.revealed.contains(index) != revealed {
            self.revealed.set(index, revealed);
            self.visibility_dirty = true;
        }
        Ok(())
    }

    /// Propage les transforms ; rend le nombre de transforms monde recalculées.
    ///
    /// Un seul parcours, dans l'ordre des index. Un node est recalculé s'il est
    /// sale, si son parent vient de changer, ou si la physique lui a transmis
    /// une transform monde. Un node `PHYSICS_DRIVEN` dont seul le parent a
    /// bougé garde sa transform monde — le body la possède — et seule sa
    /// transform locale suit.
    pub fn propagate(&mut self) -> usize {
        self.changed.clear();
        let mut updated = 0;

        for index in 0..self.len() {
            let state = self.state[index];
            if state == node_state::DETACHED {
                continue;
            }
            let parent = self.parent[index];
            let (parent_world, parent_changed) = if parent == NO_PARENT {
                (None, false)
            } else {
                let parent = parent as usize;
                (Some(self.world[parent]), self.changed.contains(parent))
            };

            if state == node_state::PHYSICS_DRIVEN && self.physics_pending.contains(index) {
                let world = self.physics_world[index];
                self.world[index] = world;
                self.local[index] = relative_to(parent_world, world, self.local[index]);
                self.physics_pending.remove(index);
                self.changed.insert(index);
                updated += 1;
            } else if self.dirty.contains(index) {
                self.world[index] = compose(parent_world, self.local[index]);
                self.changed.insert(index);
                updated += 1;
            } else if parent_changed {
                if state == node_state::PHYSICS_DRIVEN {
                    self.local[index] =
                        relative_to(parent_world, self.world[index], self.local[index]);
                } else {
                    self.world[index] = compose(parent_world, self.local[index]);
                    self.changed.insert(index);
                    updated += 1;
                }
            }
        }
        self.dirty.clear();

        if self.visibility_dirty {
            self.update_visibility();
            self.visibility_dirty = false;
        }
        updated
    }

    fn check(&self, node: u32) -> Result<usize, SceneError> {
        let index = node as usize;
        if index < self.len() {
            Ok(index)
        } else {
            Err(SceneError::NodeOutOfRange {
                node,
                count: self.len() as u32,
            })
        }
    }

    /// Passe un node et ses descendants à `DETACHED` ; rend leur nombre.
    ///
    /// L'ordre topologique suffit à trouver les descendants en un parcours :
    /// un node est dans le sous-arbre si son parent y est déjà.
    fn detach_subtree(&mut self, root: usize) -> usize {
        let mut subtree = BitSet::new(self.len());
        subtree.insert(root);
        for index in root + 1..self.len() {
            let parent = self.parent[index];
            if parent != NO_PARENT && subtree.contains(parent as usize) {
                subtree.insert(index);
            }
        }
        for index in subtree.iter() {
            self.state[index] = node_state::DETACHED;
            self.physics_pending.remove(index);
            self.dirty.remove(index);
        }
        self.visibility_dirty = true;
        subtree.count()
    }

    /// Recalcule la visibilité de tous les nodes.
    ///
    /// Un node est visible s'il porte `VISIBLE`, n'est pas détaché, si son
    /// parent est visible, et, s'il est `INTERNAL`, s'il a été révélé (R-952) :
    /// masquer un compartiment masque ce qu'il contient.
    fn update_visibility(&mut self) {
        for index in 0..self.len() {
            let flags = self.flags[index];
            let parent = self.parent[index];
            let parent_visible = parent == NO_PARENT || self.visible.contains(parent as usize);
            let visible = self.state[index] != node_state::DETACHED
                && flags & node_flags::VISIBLE != 0
                && parent_visible
                && (flags & node_flags::INTERNAL == 0 || self.revealed.contains(index));
            self.visible.set(index, visible);
        }
    }
}

/// Transform monde d'un node depuis celle de son parent.
fn compose(parent_world: Option<Affine3A>, local: Affine3A) -> Affine3A {
    match parent_world {
        Some(parent_world) => parent_world * local,
        None => local,
    }
}

/// Transform locale qui place `world` sous `parent_world`.
///
/// Un parent non inversible — échelle nulle — ne permet pas de la calculer :
/// la transform locale précédente est gardée, et la transform monde, qui est
/// celle que la physique impose, reste juste.
fn relative_to(parent_world: Option<Affine3A>, world: Affine3A, previous: Affine3A) -> Affine3A {
    match parent_world {
        None => world,
        Some(parent_world) => {
            let local = parent_world.inverse() * world;
            if local.is_finite() {
                local
            } else {
                previous
            }
        }
    }
}

/// Convertit la transform d'un asset, en normalisant son quaternion.
///
/// C-22 admet une norme dans `[0.9, 1.1]` ; la rotation désignée est celle du
/// quaternion normalisé, et l'échelle ne doit pas hériter de l'écart de norme.
fn to_affine(node: u32, transform: &Transform) -> Result<Affine3A, SceneError> {
    let rotation = Quat::from_array(transform.rotation);
    let norm = rotation.length();
    if !norm.is_finite() || norm <= 0.0 {
        return Err(SceneError::RotationNotNormalizable { node });
    }
    if !transform.is_finite() {
        return Err(SceneError::NotFiniteInAsset { node });
    }
    Ok(Affine3A::from_scale_rotation_translation(
        Vec3::from(transform.scale),
        rotation.normalize(),
        Vec3::from(transform.translation),
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use ax_model::dm::scene::{ALL_LODS, NONE_U16, NONE_U32};
    use core::f32::consts::FRAC_PI_2;

    fn node(parent: u32, state: u8, translation: [f32; 3]) -> NodeDesc {
        NodeDesc {
            name_hash: 0,
            parent,
            local: Transform {
                translation,
                ..Transform::identity()
            },
            flags: node_flags::VISIBLE,
            mesh: NONE_U32,
            collider: NONE_U32,
            bone: NONE_U32,
            part: NONE_U16,
            region: NONE_U16,
            lod_mask: ALL_LODS,
            state,
            _pad: [0; 2],
        }
    }

    fn translation(graph: &SceneGraph, node: usize) -> Vec3 {
        Vec3::from(graph.worlds()[node].translation)
    }

    fn proche(a: Vec3, b: Vec3) -> bool {
        (a - b).length() < 1e-5
    }

    #[test]
    fn t290_la_propagation_compose_la_chaine_des_parents() {
        let mut enfant = node(0, node_state::STATIC, [0.0; 3]);
        enfant.local.rotation = Quat::from_rotation_z(FRAC_PI_2).to_array();
        enfant.local.scale = [2.0; 3];
        let nodes = [
            node(NO_PARENT, node_state::STATIC, [1.0, 0.0, 0.0]),
            enfant,
            node(1, node_state::STATIC, [1.0, 0.0, 0.0]),
        ];
        let mut graph = SceneGraph::from_nodes(&nodes).expect("graphe");

        assert_eq!(graph.propagate(), 3);
        // (1, 0, 0) + rotation de 90° autour de Z de (2, 0, 0) = (1, 2, 0).
        assert!(proche(translation(&graph, 2), Vec3::new(1.0, 2.0, 0.0)));
    }

    #[test]
    fn t290_seuls_les_nodes_sales_et_leurs_descendants_sont_recalcules() {
        let nodes = [
            node(NO_PARENT, node_state::STATIC, [0.0; 3]),
            node(0, node_state::ANIMATION_DRIVEN, [1.0, 0.0, 0.0]),
            node(1, node_state::STATIC, [0.0, 1.0, 0.0]),
            node(0, node_state::STATIC, [0.0, 0.0, 1.0]),
        ];
        let mut graph = SceneGraph::from_nodes(&nodes).expect("graphe");
        assert_eq!(graph.propagate(), 4);

        // Rien n'a bougé : le parcours ne fait que lire des bits.
        assert_eq!(graph.propagate(), 0);
        assert!(graph.changed().none());

        graph
            .set_local(
                1,
                node_state::ANIMATION_DRIVEN,
                Affine3A::from_translation(Vec3::new(5.0, 0.0, 0.0)),
            )
            .expect("source légitime");
        assert_eq!(graph.propagate(), 2);
        assert_eq!(graph.changed().iter().collect::<Vec<_>>(), [1, 2]);
        assert!(proche(translation(&graph, 2), Vec3::new(5.0, 1.0, 0.0)));
        assert!(proche(translation(&graph, 3), Vec3::new(0.0, 0.0, 1.0)));
    }

    #[test]
    fn t290_un_ordre_non_topologique_est_refuse() {
        let nodes = [
            node(1, node_state::STATIC, [0.0; 3]),
            node(NO_PARENT, node_state::STATIC, [0.0; 3]),
        ];
        let refus = SceneGraph::from_nodes(&nodes).unwrap_err();
        assert_eq!(refus, SceneError::ParentAfterChild { node: 0, parent: 1 });
        assert_eq!(refus.code(), -3021);
    }

    #[test]
    fn t290_une_transform_d_asset_inexploitable_est_refusee() {
        let mut nul = node(NO_PARENT, node_state::STATIC, [0.0; 3]);
        nul.local.rotation = [0.0; 4];
        assert_eq!(SceneGraph::from_nodes(&[nul]).unwrap_err().code(), -2010);

        let infini = node(NO_PARENT, node_state::STATIC, [f32::NAN, 0.0, 0.0]);
        assert_eq!(
            SceneGraph::from_nodes(&[infini]).unwrap_err(),
            SceneError::NotFiniteInAsset { node: 0 }
        );

        let inconnu = node(NO_PARENT, 9, [0.0; 3]);
        assert_eq!(
            SceneGraph::from_nodes(&[inconnu]).unwrap_err().code(),
            -3050
        );
    }

    #[test]
    fn t291_r600_un_node_physique_recoit_sa_transform_monde_et_recalcule_sa_locale() {
        // Le parent est piloté par un joint : c'est le seul pilotage de
        // transform locale que R-931 admet au-dessus d'un node physique.
        let nodes = [
            node(NO_PARENT, node_state::JOINT_DRIVEN, [10.0, 0.0, 0.0]),
            node(0, node_state::PHYSICS_DRIVEN, [0.0; 3]),
            node(1, node_state::STATIC, [0.0, 1.0, 0.0]),
        ];
        let mut graph = SceneGraph::from_nodes(&nodes).expect("graphe");
        graph.propagate();

        graph
            .set_physics_world(1, Affine3A::from_translation(Vec3::new(12.0, 0.0, 0.0)))
            .expect("node physique");
        assert_eq!(graph.propagate(), 2);
        assert!(proche(translation(&graph, 1), Vec3::new(12.0, 0.0, 0.0)));
        assert!(proche(
            Vec3::from(graph.locals()[1].translation),
            Vec3::new(2.0, 0.0, 0.0)
        ));
        // L'enfant suit le body.
        assert!(proche(translation(&graph, 2), Vec3::new(12.0, 1.0, 0.0)));

        // Le parent bouge : le body garde sa place, seule sa locale suit, et
        // ses enfants ne sont pas recalculés.
        graph
            .set_local(
                0,
                node_state::JOINT_DRIVEN,
                Affine3A::from_translation(Vec3::new(20.0, 0.0, 0.0)),
            )
            .expect("source légitime");
        assert_eq!(graph.propagate(), 1);
        assert!(proche(translation(&graph, 1), Vec3::new(12.0, 0.0, 0.0)));
        assert!(proche(
            Vec3::from(graph.locals()[1].translation),
            Vec3::new(-8.0, 0.0, 0.0)
        ));
        assert!(proche(translation(&graph, 2), Vec3::new(12.0, 1.0, 0.0)));
    }

    #[test]
    fn t291_r931_un_node_physique_sous_un_parent_anime_est_refuse() {
        for parent_state in [node_state::ANIMATION_DRIVEN, node_state::PROCEDURAL] {
            let nodes = [
                node(NO_PARENT, parent_state, [0.0; 3]),
                node(0, node_state::PHYSICS_DRIVEN, [0.0; 3]),
            ];
            let refus = SceneGraph::from_nodes(&nodes).unwrap_err();
            assert_eq!(
                refus,
                SceneError::PhysicsUnderAnimatedParent { node: 1, parent: 0 }
            );
            assert_eq!(refus.code(), -7002);
        }

        // Sous un joint, c'est le mécanisme même de R-932 : admis.
        let nodes = [
            node(NO_PARENT, node_state::JOINT_DRIVEN, [0.0; 3]),
            node(0, node_state::PHYSICS_DRIVEN, [0.0; 3]),
        ];
        assert!(SceneGraph::from_nodes(&nodes).is_ok());
    }

    #[test]
    fn t291_r930_une_source_ne_pilote_que_les_nodes_de_son_etat() {
        let nodes = [
            node(NO_PARENT, node_state::STATIC, [0.0; 3]),
            node(0, node_state::PROCEDURAL, [0.0; 3]),
        ];
        let mut graph = SceneGraph::from_nodes(&nodes).expect("graphe");
        let deplacement = Affine3A::from_translation(Vec3::X);

        // Un node statique est constant.
        let refus = graph
            .set_local(0, node_state::STATIC, deplacement)
            .unwrap_err();
        assert_eq!(refus.code(), -2001);
        // L'animation ne pilote pas un node procédural.
        assert!(matches!(
            graph.set_local(1, node_state::ANIMATION_DRIVEN, deplacement),
            Err(SceneError::WrongSource { .. })
        ));
        // La physique ne pilote qu'un node physique.
        assert!(matches!(
            graph.set_physics_world(1, deplacement),
            Err(SceneError::WrongSource { .. })
        ));
        // Hors du graphe.
        assert_eq!(
            graph
                .set_local(7, node_state::PROCEDURAL, deplacement)
                .unwrap_err(),
            SceneError::NodeOutOfRange { node: 7, count: 2 }
        );
        // Un refus ne salit rien.
        graph.propagate();
        assert_eq!(graph.propagate(), 0);
    }

    #[test]
    fn t291_une_transform_non_finie_recue_est_refusee_sans_rien_modifier() {
        let nodes = [node(NO_PARENT, node_state::PROCEDURAL, [1.0, 0.0, 0.0])];
        let mut graph = SceneGraph::from_nodes(&nodes).expect("graphe");
        graph.propagate();

        let refus = graph
            .set_local(
                0,
                node_state::PROCEDURAL,
                Affine3A::from_translation(Vec3::new(f32::NAN, 0.0, 0.0)),
            )
            .unwrap_err();
        assert_eq!(refus.code(), -2030);
        assert_eq!(graph.propagate(), 0);
        assert!(proche(translation(&graph, 0), Vec3::X));
    }

    #[test]
    fn t292_un_node_detache_emporte_ses_descendants() {
        let nodes = [
            node(NO_PARENT, node_state::ANIMATION_DRIVEN, [0.0; 3]),
            node(0, node_state::STATIC, [1.0, 0.0, 0.0]),
            node(1, node_state::ANIMATION_DRIVEN, [0.0, 1.0, 0.0]),
            node(0, node_state::STATIC, [0.0, 0.0, 1.0]),
        ];
        let mut graph = SceneGraph::from_nodes(&nodes).expect("graphe");
        graph.propagate();

        assert_eq!(graph.detach(1), Ok(2));
        assert_eq!(graph.states()[1], node_state::DETACHED);
        assert_eq!(graph.states()[2], node_state::DETACHED);

        // La racine bouge : le capot suit, la porte détachée non.
        graph
            .set_local(
                0,
                node_state::ANIMATION_DRIVEN,
                Affine3A::from_translation(Vec3::new(0.0, 5.0, 0.0)),
            )
            .expect("source légitime");
        assert_eq!(graph.propagate(), 2);
        assert!(proche(translation(&graph, 1), Vec3::new(1.0, 0.0, 0.0)));
        assert!(proche(translation(&graph, 3), Vec3::new(0.0, 5.0, 1.0)));

        // Invisible ici, et plus pilotable par sa source d'origine.
        assert!(!graph.visible().contains(1) && !graph.visible().contains(2));
        assert!(graph.visible().contains(3));
        assert!(matches!(
            graph.set_local(2, node_state::ANIMATION_DRIVEN, Affine3A::IDENTITY),
            Err(SceneError::WrongSource { .. })
        ));
    }

    #[test]
    fn t612_la_visibilite_suit_drapeaux_parents_et_revelation() {
        let mut cache = node(NO_PARENT, node_state::STATIC, [0.0; 3]);
        cache.flags = 0;
        let mut compartiment = node(NO_PARENT, node_state::STATIC, [0.0; 3]);
        compartiment.flags = node_flags::VISIBLE | node_flags::INTERNAL;
        let nodes = [
            cache,
            node(0, node_state::STATIC, [0.0; 3]),
            compartiment,
            node(2, node_state::STATIC, [0.0; 3]),
        ];
        let mut graph = SceneGraph::from_nodes(&nodes).expect("graphe");
        graph.propagate();

        // Un parent masqué masque ses enfants ; un compartiment interne reste
        // masqué, avec son contenu, tant que la part est intacte.
        assert!(graph.visible().none());

        // R-952 : l'étape de dommage le révèle.
        graph.set_revealed(2, true).expect("node existant");
        graph.propagate();
        assert_eq!(graph.visible().iter().collect::<Vec<_>>(), [2, 3]);
        assert!(graph.is_revealed(2));
    }

    #[test]
    fn un_graphe_se_propage_sur_un_autre_thread_que_celui_qui_l_a_construit() {
        // C-30 : parallélisable entre assemblies. L'ordonnanceur envoie chaque
        // graphe à un worker ; il faut donc qu'il soit `Send`.
        fn envoyable<T: Send>() {}
        envoyable::<SceneGraph>();

        let nodes = [node(NO_PARENT, node_state::STATIC, [3.0, 0.0, 0.0])];
        let graph = SceneGraph::from_nodes(&nodes).expect("graphe");
        let graph = std::thread::spawn(move || {
            let mut graph = graph;
            graph.propagate();
            graph
        })
        .join()
        .expect("thread");
        assert!(proche(translation(&graph, 0), Vec3::new(3.0, 0.0, 0.0)));
    }
}
