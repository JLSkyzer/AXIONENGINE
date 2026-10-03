//! Étape 7 — boîtes englobantes par mesh et par asset.
//!
//! La boîte d'un mesh est en espace local, et DM-04 la porte. Celle de l'asset
//! est en espace asset : chaque mesh y est placé par la chaîne de transformations
//! de son node. Le format A3D figé n'a pas de champ pour elle ; elle est rendue à
//! l'appelant, et se recalcule au chargement depuis ce que le conteneur porte.

use crate::import::ImportedAsset;
use ax_model::dm::geometry::Transform;

/// Boîte englobante alignée sur les axes.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Aabb {
    /// Coin inférieur.
    pub min: [f32; 3],
    /// Coin supérieur.
    pub max: [f32; 3],
}

/// Recalcule la boîte de chaque mesh depuis ses sommets.
///
/// Un mesh sans sommet reçoit une boîte nulle, finie, que C-22 accepte : une
/// boîte d'un ensemble vide n'a pas de sens, et les importeurs font de même.
pub(super) fn update_mesh_bounds(asset: &mut ImportedAsset) {
    for mesh in &mut asset.meshes {
        let offset = mesh.vertex_offset as usize;
        let mut min = [f32::INFINITY; 3];
        let mut max = [f32::NEG_INFINITY; 3];
        for vertex in &asset.vertices[offset..offset + mesh.vertex_count as usize] {
            for axis in 0..3 {
                min[axis] = min[axis].min(vertex.position[axis]);
                max[axis] = max[axis].max(vertex.position[axis]);
            }
        }
        if mesh.vertex_count == 0 {
            min = [0.0; 3];
            max = [0.0; 3];
        }
        mesh.aabb_min = min;
        mesh.aabb_max = max;
    }
}

/// Transformation affine en espace asset : `p ↦ linear · p + translation`.
#[derive(Clone, Copy)]
struct Affine {
    linear: [[f64; 3]; 3],
    translation: [f64; 3],
}

impl Affine {
    /// Échelle, puis rotation, puis translation — l'ordre de glTF et de DM-02.
    fn from_local(local: &Transform) -> Self {
        let [x, y, z, w] = local.rotation.map(f64::from);
        let norm2 = x * x + y * y + z * z + w * w;
        // `2 / |q|²` plutôt que `2` : un quaternion que C-22 a accepté est
        // proche de l'unité sans l'être exactement, et la boîte doit suivre la
        // rotation qu'il désigne, pas l'erreur de sa norme.
        let s = if norm2 > 0.0 { 2.0 / norm2 } else { 0.0 };
        let rotation = [
            [
                1.0 - s * (y * y + z * z),
                s * (x * y - z * w),
                s * (x * z + y * w),
            ],
            [
                s * (x * y + z * w),
                1.0 - s * (x * x + z * z),
                s * (y * z - x * w),
            ],
            [
                s * (x * z - y * w),
                s * (y * z + x * w),
                1.0 - s * (x * x + y * y),
            ],
        ];
        let scale = local.scale.map(f64::from);
        let mut linear = [[0.0; 3]; 3];
        for row in 0..3 {
            for column in 0..3 {
                linear[row][column] = rotation[row][column] * scale[column];
            }
        }
        Self {
            linear,
            translation: local.translation.map(f64::from),
        }
    }

    /// `self ∘ inner` : applique `inner`, puis `self`.
    fn then_inner(&self, inner: &Self) -> Self {
        let mut linear = [[0.0; 3]; 3];
        for (row, out) in linear.iter_mut().enumerate() {
            for (column, cell) in out.iter_mut().enumerate() {
                *cell = (0..3)
                    .map(|k| self.linear[row][k] * inner.linear[k][column])
                    .sum();
            }
        }
        Self {
            linear,
            translation: self.apply(inner.translation),
        }
    }

    fn apply(&self, point: [f64; 3]) -> [f64; 3] {
        let mut out = self.translation;
        for (row, value) in out.iter_mut().enumerate() {
            for (column, coordinate) in point.iter().enumerate() {
                *value += self.linear[row][column] * coordinate;
            }
        }
        out
    }
}

/// Boîte de l'asset : union des boîtes de mesh, placées par leur node.
///
/// Les huit coins de chaque boîte locale sont transformés, ce qui englobe le
/// mesh sans le serrer au plus près sous rotation. Le passage de `f64` à `f32`
/// arrondit **vers l'extérieur** : un arrondi au plus proche pourrait laisser
/// un sommet dépasser d'un ulp.
pub(super) fn asset_bounds(asset: &ImportedAsset) -> Option<Aabb> {
    let mut world: Vec<Affine> = Vec::with_capacity(asset.nodes.len());
    let mut min = [f64::INFINITY; 3];
    let mut max = [f64::NEG_INFINITY; 3];
    let mut any = false;

    for node in &asset.nodes {
        let local = Affine::from_local(&node.local);
        // C-22 garantit l'ordre topologique : un parent précède ses enfants.
        let placed = world
            .get(node.parent as usize)
            .map_or(local, |parent| parent.then_inner(&local));
        world.push(placed);

        // Tous les meshes du node (ADR-122 §5) : un mesh glTF à plusieurs
        // primitives n'en comptait que la première.
        for rank in node.meshes() {
            let Some(mesh) = asset.meshes.get(rank as usize) else {
                continue;
            };
            if mesh.vertex_count == 0 {
                continue;
            }
            any = true;
            for corner in 0..8 {
                let pick = |axis: usize| {
                    if corner & (1 << axis) == 0 {
                        f64::from(mesh.aabb_min[axis])
                    } else {
                        f64::from(mesh.aabb_max[axis])
                    }
                };
                let point = placed.apply([pick(0), pick(1), pick(2)]);
                for axis in 0..3 {
                    min[axis] = min[axis].min(point[axis]);
                    max[axis] = max[axis].max(point[axis]);
                }
            }
        }
    }

    any.then(|| Aabb {
        min: min.map(round_down),
        max: max.map(round_up),
    })
}

/// Plus grand `f32` inférieur ou égal.
fn round_down(value: f64) -> f32 {
    let rounded = value as f32;
    if f64::from(rounded) > value {
        rounded.next_down()
    } else {
        rounded
    }
}

/// Plus petit `f32` supérieur ou égal.
fn round_up(value: f64) -> f32 {
    let rounded = value as f32;
    if f64::from(rounded) < value {
        rounded.next_up()
    } else {
        rounded
    }
}

#[cfg(test)]
mod tests {
    use super::super::tests::{asset, node, sommet};
    use super::*;
    use ax_model::dm::scene::{NONE_U32, NO_PARENT};

    fn triangle_unite() -> ImportedAsset {
        asset(
            vec![
                sommet([0.0, 0.0, 0.0]),
                sommet([1.0, 0.0, 0.0]),
                sommet([0.0, 1.0, 0.0]),
            ],
            vec![0, 1, 2],
            true,
        )
    }

    #[test]
    fn t242_la_boite_d_un_mesh_suit_ses_sommets() {
        let mut asset = triangle_unite();
        update_mesh_bounds(&mut asset);

        assert_eq!(asset.meshes[0].aabb_min, [0.0, 0.0, 0.0]);
        assert_eq!(asset.meshes[0].aabb_max, [1.0, 1.0, 0.0]);
    }

    #[test]
    fn t242_la_boite_de_l_asset_suit_la_chaine_des_nodes() {
        let mut asset = triangle_unite();
        update_mesh_bounds(&mut asset);

        // Un parent déplacé de 10 en X porte un enfant tourné de 90° autour de
        // Z et mis à l'échelle 2 : le triangle couvre alors x ∈ [8, 10],
        // y ∈ [0, 2].
        let half = core::f32::consts::FRAC_1_SQRT_2;
        let parent = Transform {
            translation: [10.0, 0.0, 0.0],
            ..Transform::identity()
        };
        let enfant = Transform {
            rotation: [0.0, 0.0, half, half],
            scale: [2.0; 3],
            ..Transform::identity()
        };
        asset.nodes = vec![node(NONE_U32, NO_PARENT, parent), node(0, 0, enfant)];

        let bounds = asset_bounds(&asset).expect("une géométrie est portée");
        let attendu = Aabb {
            min: [8.0, 0.0, 0.0],
            max: [10.0, 2.0, 0.0],
        };
        for axis in 0..3 {
            assert!(
                (bounds.min[axis] - attendu.min[axis]).abs() < 1e-5,
                "{bounds:?}"
            );
            assert!(
                (bounds.max[axis] - attendu.max[axis]).abs() < 1e-5,
                "{bounds:?}"
            );
        }
    }

    #[test]
    fn t242_la_boite_de_l_asset_couvre_tous_les_meshes_d_un_node() {
        // Un mesh glTF à deux primitives : le second triangle, en x ∈ [2, 3],
        // n'entrait pas dans la boîte quand le node ne portait que le premier.
        let mut asset = triangle_unite();
        let mut second = asset.meshes[0];
        second.vertex_offset = 3;
        asset.meshes.push(second);
        asset.vertices.extend([
            sommet([2.0, 0.0, 0.0]),
            sommet([3.0, 0.0, 0.0]),
            sommet([2.0, 1.0, 0.0]),
        ]);
        asset.nodes[0].mesh_count = 2;
        update_mesh_bounds(&mut asset);

        assert_eq!(
            asset_bounds(&asset),
            Some(Aabb {
                min: [0.0, 0.0, 0.0],
                max: [3.0, 1.0, 0.0],
            })
        );

        // Un seul mesh déclaré : le second n'est pas porté.
        asset.nodes[0].mesh_count = 1;
        assert_eq!(asset_bounds(&asset).map(|boite| boite.max[0]), Some(1.0));
    }

    #[test]
    fn t242_un_asset_sans_geometrie_portee_n_a_pas_de_boite() {
        let mut asset = triangle_unite();
        asset.nodes[0].mesh = NONE_U32;
        assert_eq!(asset_bounds(&asset), None);
    }

    #[test]
    fn t242_l_arrondi_vers_f32_se_fait_vers_l_exterieur() {
        let tiers = 1.0 / 3.0;
        assert!(f64::from(round_down(tiers)) <= tiers);
        assert!(f64::from(round_up(tiers)) >= tiers);
        assert_eq!(round_down(0.5), 0.5);
        assert_eq!(round_up(0.5), 0.5);
    }
}
