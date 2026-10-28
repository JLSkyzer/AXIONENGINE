//! Étapes 1 et 2 de C-64 : distance et frustum.

use crate::camera::{Camera, Frustum};
use crate::candidate::{Candidate, LocalBounds};

/// Largeur d'un lot de [`cull_batched`].
pub const LANES: usize = 8;

/// Un candidat retenu.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Visible {
    /// Sa place dans la liste des candidats.
    pub index: u32,
    /// Son identifiant.
    pub id: u32,
    /// Distance du centre de sa sphère à la caméra, en blocs.
    pub distance: f32,
}

/// Ce que le culling a décidé d'une frame.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct CullStats {
    /// Candidats examinés.
    pub candidates: u32,
    /// Candidats retenus.
    pub visible: u32,
    /// Écartés par la distance (étape 1).
    pub culled_distance: u32,
    /// Écartés par le frustum (étape 2).
    pub culled_frustum: u32,
    /// Écartés faute d'asset connu du contexte de rendu.
    pub unknown_asset: u32,
}

#[derive(Clone, Copy)]
enum Decision {
    Visible(f32),
    Distance,
    Frustum,
    UnknownAsset,
}

/// La sphère monde d'un candidat : `[x, y, z, rayon]`, relative à la caméra. Partagée par les
/// deux chemins.
fn sphere(candidate: &Candidate, bounds: LocalBounds) -> [f32; 4] {
    let center = candidate.position + candidate.rotation * (bounds.center * candidate.scale);
    [
        center.x,
        center.y,
        center.z,
        bounds.radius * candidate.scale,
    ]
}

/// Distance signée du centre au plan, sans `mul_add`, dans un ordre fixe.
#[inline]
fn plane_distance(p: [f32; 4], x: f32, y: f32, z: f32) -> f32 {
    ((p[0] * x + p[1] * y) + p[2] * z) + p[3]
}

#[inline]
fn length(x: f32, y: f32, z: f32) -> f32 {
    ((x * x + y * y) + z * z).sqrt()
}

fn record(
    stats: &mut CullStats,
    out: &mut Vec<Visible>,
    index: usize,
    id: u32,
    decision: Decision,
) {
    stats.candidates += 1;
    match decision {
        Decision::Visible(distance) => {
            stats.visible += 1;
            out.push(Visible {
                index: index as u32,
                id,
                distance,
            });
        }
        Decision::Distance => stats.culled_distance += 1,
        Decision::Frustum => stats.culled_frustum += 1,
        Decision::UnknownAsset => stats.unknown_asset += 1,
    }
}

/// Culling de référence, candidat par candidat. `bounds` rend l'emprise locale de l'asset d'un
/// candidat, ou rien s'il est inconnu. Les retenus sont ajoutés à `out` dans l'ordre des candidats.
pub fn cull_scalar<F>(
    camera: &Camera,
    frustum: &Frustum,
    candidates: &[Candidate],
    bounds: F,
    out: &mut Vec<Visible>,
) -> CullStats
where
    F: Fn(&Candidate) -> Option<LocalBounds>,
{
    let planes = frustum.planes.map(|p| p.to_array());
    let mut stats = CullStats::default();
    for (index, candidate) in candidates.iter().enumerate() {
        let p = candidate.position;
        let decision = match bounds(candidate) {
            _ if candidate.flags & Candidate::NO_CULL != 0 => {
                Decision::Visible(length(p.x, p.y, p.z))
            }
            None => Decision::UnknownAsset,
            Some(local) => {
                let [x, y, z, r] = sphere(candidate, local);
                let distance = length(x, y, z);
                if distance - r > camera.max_distance {
                    Decision::Distance
                } else if planes.iter().any(|p| plane_distance(*p, x, y, z) < -r) {
                    Decision::Frustum
                } else {
                    Decision::Visible(distance)
                }
            }
        };
        record(&mut stats, out, index, candidate.id, decision);
    }
    stats
}

/// Culling par lots de [`LANES`] candidats, en colonnes : mêmes opérations que
/// [`cull_scalar`], donc mêmes décisions, dans le même ordre.
pub fn cull_batched<F>(
    camera: &Camera,
    frustum: &Frustum,
    candidates: &[Candidate],
    bounds: F,
    out: &mut Vec<Visible>,
) -> CullStats
where
    F: Fn(&Candidate) -> Option<LocalBounds>,
{
    let planes = frustum.planes.map(|p| p.to_array());
    let mut stats = CullStats::default();
    for (lot, chunk) in candidates.chunks(LANES).enumerate() {
        let mut known = [false; LANES];
        let (mut x, mut y, mut z, mut r) =
            ([0f32; LANES], [0f32; LANES], [0f32; LANES], [0f32; LANES]);
        for (lane, candidate) in chunk.iter().enumerate() {
            if let Some(local) = bounds(candidate) {
                known[lane] = true;
                [x[lane], y[lane], z[lane], r[lane]] = sphere(candidate, local);
            }
        }
        let mut distance = [0f32; LANES];
        let mut far = [false; LANES];
        for lane in 0..LANES {
            distance[lane] = length(x[lane], y[lane], z[lane]);
            far[lane] = distance[lane] - r[lane] > camera.max_distance;
        }
        let mut outside = [false; LANES];
        for p in &planes {
            for lane in 0..LANES {
                outside[lane] |= plane_distance(*p, x[lane], y[lane], z[lane]) < -r[lane];
            }
        }
        for (lane, candidate) in chunk.iter().enumerate() {
            let p = candidate.position;
            let decision = if candidate.flags & Candidate::NO_CULL != 0 {
                Decision::Visible(length(p.x, p.y, p.z))
            } else if !known[lane] {
                Decision::UnknownAsset
            } else if far[lane] {
                Decision::Distance
            } else if outside[lane] {
                Decision::Frustum
            } else {
                Decision::Visible(distance[lane])
            };
            record(&mut stats, out, lot * LANES + lane, candidate.id, decision);
        }
    }
    stats
}
