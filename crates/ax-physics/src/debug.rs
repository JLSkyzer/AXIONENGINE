//! Tracé des overlays du debug renderer (C-67, ADR-121 et son amendement A1).
//!
//! Lecture seule : rien ici ne modifie le monde physique, et le déterminisme
//! (R-1020) n'en dépend donc pas. Chaque overlay produit des plages de segments,
//! chacune dans son repère : **repère du corps** — l'espace de l'asset — pour ce
//! que Java place à la pose interpolée du maillage (colliders, axes, centre de
//! masse) ; **repère d'origine** — le monde, relatif à la caméra de l'appel —
//! pour ce qui ne tourne pas avec le corps (AABB, contacts, normales, tuiles).

use crate::sim::handle_key;
use ax_math::{DVec3, Vec3};
use ax_model::dm::debug::{DebugRange, DebugSegment};
use ax_model::dm::handle::Handle;
use rapier3d::parry::shape::{ConvexPolyhedron, HeightField, Shape as ParryShape};
use rapier3d::prelude::{Pose as RapierPose, Vector};
use std::collections::BTreeSet;

/// Segments par cercle des formes rondes (sphère, capsule, cylindre, cône) :
/// une constante de tracé, sans effet sur la simulation.
pub const ROUND_SUBDIVISIONS: u32 = 16;

/// Longueur des axes de l'overlay `bodies`, en mètres (A1 d'ADR-121).
pub const AXIS_LENGTH: f32 = 0.5;

/// Côté de la croix d'un point de contact, en mètres (A1 d'ADR-121).
pub const CONTACT_CROSS: f32 = 0.1;

/// Longueur d'une normale de contact, en mètres (A1 d'ADR-121).
pub const NORMAL_LENGTH: f32 = 0.25;

/// Côté de la croix du centre de masse, en mètres (A1 d'ADR-121).
pub const MASS_CROSS: f32 = 0.2;

/// Une plage d'un overlay, avant sélection : ses segments et ce qui la classe.
#[derive(Debug, Clone, PartialEq)]
pub struct DebugDraft {
    /// Assembly du corps ; [`Handle::ABSENT`] pour une tuile du monde.
    pub handle: Handle,
    /// Rang de l'overlay au §31.4 (`ax_model::dm::debug::overlay::rank`).
    pub overlay: u32,
    /// Repère des segments (`ax_model::dm::debug::debug_frame`).
    pub frame: u32,
    /// Drapeaux, voir `ax_model::dm::debug::debug_body_flags`.
    pub flags: u32,
    /// Ancre monde (`f64`) — position du corps, centre de la tuile —, pour le tri
    /// par distance à la caméra.
    pub anchor: DVec3,
    /// Segments, dans le repère de la plage.
    pub segments: Vec<DebugSegment>,
}

/// Géométrie retenue pour un appel : plages triées, segments à plat.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct DebugSelection {
    /// Plages tracées, de la plus proche à la plus lointaine.
    pub ranges: Vec<DebugRange>,
    /// Segments de toutes les plages, chacun dans la plage qui le désigne.
    pub segments: Vec<DebugSegment>,
    /// Plages omises faute de budget.
    pub omitted_ranges: u32,
}

/// Les douze arêtes de la boîte `[min, max]`, chaque coin passé par `place`.
pub(crate) fn box_edges(
    min: Vec3,
    max: Vec3,
    place: impl Fn(Vec3) -> [f32; 3],
    out: &mut Vec<DebugSegment>,
) {
    let corner = |i: usize| {
        Vec3::new(
            if i & 1 == 0 { min.x } else { max.x },
            if i & 2 == 0 { min.y } else { max.y },
            if i & 4 == 0 { min.z } else { max.z },
        )
    };
    // Deux coins se rejoignent par une arête s'ils ne diffèrent que d'un bit.
    for i in 0..8usize {
        for bit in [1usize, 2, 4] {
            if i & bit == 0 {
                out.push(DebugSegment {
                    a: place(corner(i)),
                    b: place(corner(i | bit)),
                });
            }
        }
    }
}

/// Une croix de trois segments de côté `side`, centrée en `center`, alignée sur
/// les axes, chaque extrémité passée par `place`.
pub(crate) fn cross(
    center: Vec3,
    side: f32,
    place: impl Fn(Vec3) -> [f32; 3],
    out: &mut Vec<DebugSegment>,
) {
    let half = side * 0.5;
    for axis in [Vec3::X, Vec3::Y, Vec3::Z] {
        out.push(DebugSegment {
            a: place(center - axis * half),
            b: place(center + axis * half),
        });
    }
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

/// Retient les plages à tracer : les plus proches de la caméra d'abord, puis par
/// rang d'overlay, puis par clé du handle — un tri stable, qui laisse en dernier
/// ressort l'ordre déterministe de production. Au plus `max_segments` segments. Une
/// plage qui ne tient pas est omise et comptée, jamais coupée, et le parcours
/// continue ; une plage sans segment n'a rien à tracer et n'est pas comptée.
#[must_use]
pub fn select_drafts(
    mut drafts: Vec<DebugDraft>,
    camera: DVec3,
    max_segments: u32,
) -> DebugSelection {
    drafts.retain(|draft| !draft.segments.is_empty());
    drafts.sort_by(|a, b| {
        a.anchor
            .distance_squared(camera)
            .total_cmp(&b.anchor.distance_squared(camera))
            .then_with(|| a.overlay.cmp(&b.overlay))
            .then_with(|| handle_key(a.handle).cmp(&handle_key(b.handle)))
    });

    let mut selection = DebugSelection::default();
    let mut remaining = max_segments as usize;
    for draft in drafts {
        let count = draft.segments.len();
        if count > remaining {
            selection.omitted_ranges = selection.omitted_ranges.saturating_add(1);
            continue;
        }
        remaining -= count;
        // Les deux tiennent sur `u32` : leur total est borné par `max_segments`.
        let first_segment = u32::try_from(selection.segments.len()).unwrap_or(u32::MAX);
        let segment_count = u32::try_from(count).unwrap_or(u32::MAX);
        selection.ranges.push(DebugRange {
            handle: draft.handle,
            overlay: draft.overlay,
            frame: draft.frame,
            first_segment,
            segment_count,
            flags: draft.flags,
            _pad: 0,
        });
        selection.segments.extend(draft.segments);
    }
    selection
}
