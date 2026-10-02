//! Tracé des colliders pour l'overlay `colliders` du debug renderer (C-67,
//! ADR-121).
//!
//! Lecture seule : rien ici ne modifie le monde physique, et le déterminisme
//! (R-1020) n'en dépend donc pas. Les segments sont produits en **repère du
//! corps** — l'espace de l'asset — pour que Java les place à la pose interpolée
//! qu'il donne déjà au maillage.

use crate::sim::handle_key;
use ax_math::DVec3;
use ax_model::dm::debug::{DebugBody, DebugSegment};
use ax_model::dm::handle::Handle;
use rapier3d::parry::shape::{ConvexPolyhedron, HeightField, Shape as ParryShape};
use rapier3d::prelude::{Pose as RapierPose, Vector};
use std::collections::BTreeSet;

/// Segments par cercle des formes rondes (sphère, capsule, cylindre, cône) :
/// une constante de tracé, sans effet sur la simulation.
pub const ROUND_SUBDIVISIONS: u32 = 16;

/// Arêtes des colliders d'un corps d'assembly, avant sélection.
#[derive(Debug, Clone, PartialEq)]
pub struct ColliderOutline {
    /// Assembly du corps.
    pub handle: Handle,
    /// Drapeaux, voir `ax_model::dm::debug::debug_body_flags`.
    pub flags: u32,
    /// Position monde du corps (`f64`), pour le tri par distance à la caméra.
    pub position: DVec3,
    /// Arêtes, en repère du corps.
    pub segments: Vec<DebugSegment>,
}

/// Géométrie retenue pour un appel : corps triés, segments à plat.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct DebugColliders {
    /// Corps tracés, du plus proche au plus lointain.
    pub bodies: Vec<DebugBody>,
    /// Segments de tous les corps, chacun dans la plage de son corps.
    pub segments: Vec<DebugSegment>,
    /// Corps omis faute de budget.
    pub omitted_bodies: u32,
}

/// Ajoute à `out` les arêtes d'une forme placée par `pose` dans le repère du
/// corps.
///
/// Boîte, sphère, capsule, cylindre et cône : tracés de parry (`to_outline`).
/// Polyèdre convexe : ses **vraies arêtes**, prises sur le contour de ses faces
/// — `edges()` garde les arêtes supprimées à la fusion des faces coplanaires.
/// Champ de hauteurs : les arêtes de ses triangles de collision. parry 0.30.2 ne
/// compile le `to_outline` ni de l'un ni de l'autre (modules commentés, celui du
/// champ de hauteurs n'étant qu'un bouchon qui panique à l'appel). Forme
/// composée : chaque enfant à sa pose. Toute autre forme : sa boîte locale,
/// plutôt que rien.
pub(crate) fn append_outline(
    shape: &dyn ParryShape,
    pose: &RapierPose,
    out: &mut Vec<DebugSegment>,
) {
    if let Some(compound) = shape.as_compound() {
        for (child_pose, child) in compound.shapes() {
            append_outline(child.as_ref(), &(pose * child_pose), out);
        }
        return;
    }
    let (points, edges) = if let Some(cuboid) = shape.as_cuboid() {
        cuboid.to_outline()
    } else if let Some(ball) = shape.as_ball() {
        ball.to_outline(ROUND_SUBDIVISIONS)
    } else if let Some(capsule) = shape.as_capsule() {
        capsule.to_outline(ROUND_SUBDIVISIONS)
    } else if let Some(cylinder) = shape.as_cylinder() {
        cylinder.to_outline(ROUND_SUBDIVISIONS)
    } else if let Some(cone) = shape.as_cone() {
        cone.to_outline(ROUND_SUBDIVISIONS)
    } else if let Some(heightfield) = shape.as_heightfield() {
        heightfield_outline(heightfield)
    } else if let Some(polyhedron) = shape.as_convex_polyhedron() {
        convex_outline(polyhedron)
    } else {
        shape.compute_local_aabb().to_outline()
    };
    for [i, j] in edges {
        let (Some(a), Some(b)) = (points.get(i as usize), points.get(j as usize)) else {
            continue;
        };
        out.push(DebugSegment {
            a: pose.transform_point(*a).to_array(),
            b: pose.transform_point(*b).to_array(),
        });
    }
}

/// Vraies arêtes d'un polyèdre convexe : les côtés du contour de chaque face,
/// chacun une fois (une arête borde deux faces). Ordre déterministe.
fn convex_outline(polyhedron: &ConvexPolyhedron) -> (Vec<Vector>, Vec<[u32; 2]>) {
    let adjacency = polyhedron.vertices_adj_to_face();
    let mut edges = BTreeSet::new();
    for face in polyhedron.faces() {
        let start = face.first_vertex_or_edge as usize;
        let count = face.num_vertices_or_edges as usize;
        let Some(ring) = adjacency.get(start..start + count) else {
            continue;
        };
        for k in 0..count {
            let (u, v) = (ring[k], ring[(k + 1) % count]);
            edges.insert(if u < v { [u, v] } else { [v, u] });
        }
    }
    (polyhedron.points().to_vec(), edges.into_iter().collect())
}

/// Arêtes d'un champ de hauteurs : celles de ses triangles de collision,
/// cellules retirées exclues, chacune une fois.
///
/// Les sommets sont ceux de la grille : `triangles_vids_at` et `triangles_at`
/// rendent, pour une cellule, les mêmes triangles dans le même ordre de
/// sommets (source de parry 0.30.2 lu), ce qui associe chaque identifiant à sa
/// position sans recalculer la géométrie de la grille.
fn heightfield_outline(heightfield: &HeightField) -> (Vec<Vector>, Vec<[u32; 2]>) {
    let grid = (heightfield.nrows() + 1) * (heightfield.ncols() + 1);
    let mut points = vec![Vector::ZERO; grid];
    let mut edges = BTreeSet::new();
    for i in 0..heightfield.nrows() {
        for j in 0..heightfield.ncols() {
            let (left_ids, right_ids) = heightfield.triangles_vids_at(i, j);
            let (left, right) = heightfield.triangles_at(i, j);
            for (ids, triangle) in [(left_ids, left), (right_ids, right)] {
                let (Some(ids), Some(triangle)) = (ids, triangle) else {
                    continue;
                };
                for (id, point) in ids.iter().zip([triangle.a, triangle.b, triangle.c]) {
                    if let Some(slot) = points.get_mut(*id as usize) {
                        *slot = point;
                    }
                }
                for (u, v) in [(ids[0], ids[1]), (ids[1], ids[2]), (ids[2], ids[0])] {
                    edges.insert(if u < v { [u, v] } else { [v, u] });
                }
            }
        }
    }
    (points, edges.into_iter().collect())
}

/// Retient les corps à tracer : les plus proches de la caméra d'abord (égalité
/// départagée par la clé du handle), au plus `max_segments` segments. Un corps
/// qui ne tient pas est omis et compté, jamais coupé, et le parcours continue ;
/// un corps sans arête n'a rien à tracer et n'est pas compté.
#[must_use]
pub fn select_outlines(
    mut outlines: Vec<ColliderOutline>,
    camera: DVec3,
    max_segments: u32,
) -> DebugColliders {
    outlines.retain(|outline| !outline.segments.is_empty());
    outlines.sort_by(|a, b| {
        a.position
            .distance_squared(camera)
            .total_cmp(&b.position.distance_squared(camera))
            .then_with(|| handle_key(a.handle).cmp(&handle_key(b.handle)))
    });

    let mut selection = DebugColliders::default();
    let mut remaining = max_segments as usize;
    for outline in outlines {
        let count = outline.segments.len();
        if count > remaining {
            selection.omitted_bodies = selection.omitted_bodies.saturating_add(1);
            continue;
        }
        remaining -= count;
        // Les deux tiennent sur `u32` : leur total est borné par `max_segments`.
        let first_segment = u32::try_from(selection.segments.len()).unwrap_or(u32::MAX);
        let segment_count = u32::try_from(count).unwrap_or(u32::MAX);
        selection.bodies.push(DebugBody {
            handle: outline.handle,
            first_segment,
            segment_count,
            flags: outline.flags,
            _pad: 0,
        });
        selection.segments.extend(outline.segments);
    }
    selection
}
