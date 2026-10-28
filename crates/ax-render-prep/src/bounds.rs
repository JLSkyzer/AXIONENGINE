//! L'emprise d'un asset au repos, tirée de sa géométrie.

use crate::candidate::LocalBounds;
use ax_math::{Affine3A, Mat3A, Vec3, Vec3A};
use ax_model::dm::geometry::MeshDesc;
use ax_model::dm::render::RestDraw;

/// La sphère qui contient tout ce que l'asset dessine au repos : l'emprise de chaque mesh posé,
/// portée par la transformation de son node, puis réunie. `None` si l'asset ne dessine rien, si
/// un dessin désigne un mesh absent, ou si une valeur n'est pas finie — un asset qu'on ne sait
/// pas borner n'est pas rendu, plutôt que culé à tort.
#[must_use]
pub fn bounds_of(meshes: &[MeshDesc], draws: &[RestDraw]) -> Option<LocalBounds> {
    let mut min = Vec3::splat(f32::INFINITY);
    let mut max = Vec3::splat(f32::NEG_INFINITY);
    for draw in draws {
        let mesh = meshes.get(usize::try_from(draw.mesh).ok()?)?;
        let m = draw.model;
        let model = Affine3A {
            matrix3: Mat3A::from_cols(
                Vec3A::new(m[0], m[1], m[2]),
                Vec3A::new(m[3], m[4], m[5]),
                Vec3A::new(m[6], m[7], m[8]),
            ),
            translation: Vec3A::new(m[9], m[10], m[11]),
        };
        let (lo, hi) = (Vec3::from(mesh.aabb_min), Vec3::from(mesh.aabb_max));
        for corner in 0..8u8 {
            let local = Vec3::new(
                if corner & 1 == 0 { lo.x } else { hi.x },
                if corner & 2 == 0 { lo.y } else { hi.y },
                if corner & 4 == 0 { lo.z } else { hi.z },
            );
            let posed = model.transform_point3(local);
            min = min.min(posed);
            max = max.max(posed);
        }
    }
    (min.is_finite() && max.is_finite()).then(|| LocalBounds::from_aabb(min, max))
}
