//! Étape 2 — génération des normales manquantes, pondérées par l'angle.
//!
//! La normale d'un sommet est la somme des normales unitaires des triangles
//! qui le portent, chacune pondérée par l'**angle** que le triangle ouvre en ce
//! sommet. Pondérer par l'aire ferait dépendre la normale du découpage : un
//! grand triangle voisin de trois petits l'emporterait, alors que la surface est
//! la même. L'angle, lui, ne dépend que de la forme autour du sommet.
//!
//! Seuls les sommets marqués absents par l'importeur sont concernés. Une
//! normale écrite par l'auteur n'est jamais recalculée.

use crate::import::ImportedAsset;
use ax_model::dm::geometry::encode_normal;
use core::f64::consts::FRAC_PI_2;

/// En deçà de cette fraction du poids total, la somme pondérée est tenue pour
/// annulée.
///
/// Deux triangles dos à dos partageant leurs sommets donnent une somme nulle au
/// bruit d'arrondi près — un bruit de l'ordre de `1e-16` fois le poids en
/// `f64`. Le seuil est sept ordres de grandeur au-dessus : une somme qui le
/// franchit a une direction portée par la géométrie, pas par l'arrondi.
const CANCELLED: f64 = 1e-9;

/// Génère les normales absentes ; rend le nombre de normales produites.
///
/// Un sommet qui ne peut en recevoir aucune reste marqué, avec sa normale
/// nulle : la validation de sortie le refuse. Sur un asset validé, cela
/// n'arrive pas, puisque tout sommet conservé par la fusion appartient à un
/// triangle d'aire non nulle.
pub(super) fn generate_missing(asset: &mut ImportedAsset) -> usize {
    let mut generated = 0;
    let mut sums: Vec<[f64; 3]> = Vec::new();
    let mut weights: Vec<f64> = Vec::new();
    let mut firsts: Vec<Option<[f64; 3]>> = Vec::new();

    for mesh in &asset.meshes {
        let offset = mesh.vertex_offset as usize;
        let count = mesh.vertex_count as usize;
        let Some(flags) = asset.missing_normals.get(offset..offset + count) else {
            continue;
        };
        if !flags.contains(&true) {
            continue;
        }

        sums.clear();
        sums.resize(count, [0.0; 3]);
        weights.clear();
        weights.resize(count, 0.0);
        firsts.clear();
        firsts.resize(count, None);

        let vertices = &asset.vertices[offset..offset + count];
        let index_offset = mesh.index_offset as usize;
        let indices = &asset.indices[index_offset..index_offset + mesh.index_count as usize];

        for triangle in indices.chunks_exact(3) {
            let corners = [
                triangle[0] as usize,
                triangle[1] as usize,
                triangle[2] as usize,
            ];
            let points = corners.map(|corner| vertices[corner].position.map(f64::from));
            let Some(unit) = unit_normal(points) else {
                continue;
            };
            for (rank, &corner) in corners.iter().enumerate() {
                if !flags[corner] {
                    continue;
                }
                let angle =
                    corner_angle(points[rank], points[(rank + 1) % 3], points[(rank + 2) % 3]);
                for axis in 0..3 {
                    sums[corner][axis] += unit[axis] * angle;
                }
                weights[corner] += angle;
                firsts[corner].get_or_insert(unit);
            }
        }

        for local in 0..count {
            if !asset.missing_normals[offset + local] {
                continue;
            }
            let direction = if length(sums[local]) > weights[local] * CANCELLED {
                Some(sums[local])
            } else {
                // Somme annulée : la normale du premier triangle, dans l'ordre
                // des indices. C'est une direction que la géométrie porte, et
                // le choix du premier la rend reproductible.
                firsts[local]
            };
            let Some(direction) = direction else {
                continue;
            };
            let normal = encode_normal(direction.map(|value| value as f32));
            if normal != [0; 4] {
                asset.vertices[offset + local].normal = normal;
                asset.missing_normals[offset + local] = false;
                generated += 1;
            }
        }
    }
    generated
}

/// Normale unitaire d'un triangle, dans le sens de ses indices.
fn unit_normal([a, b, c]: [[f64; 3]; 3]) -> Option<[f64; 3]> {
    let normal = cross(sub(b, a), sub(c, a));
    let len = length(normal);
    (len.is_finite() && len > 0.0).then(|| normal.map(|value| value / len))
}

/// Angle ouvert en `corner` par les arêtes vers `a` et `b`, en radians.
///
/// Calculé par `2 · atan(|u − v| / |u + v|)` sur les arêtes normalisées plutôt
/// que par `acos(u · v)` : l'arc cosinus perd toute précision près de 0 et de
/// π, là où la forme retenue reste exacte.
fn corner_angle(corner: [f64; 3], a: [f64; 3], b: [f64; 3]) -> f64 {
    let (u, v) = (sub(a, corner), sub(b, corner));
    let (length_u, length_v) = (length(u), length(v));
    if length_u == 0.0 || length_v == 0.0 {
        return 0.0;
    }
    let u = u.map(|value| value / length_u);
    let v = v.map(|value| value / length_v);
    2.0 * atan_ratio(length(sub(u, v)), length(add(u, v)))
}

/// `atan(y / x)` pour `y` et `x` positifs ou nuls, non tous deux nuls.
fn atan_ratio(y: f64, x: f64) -> f64 {
    if x == 0.0 {
        FRAC_PI_2
    } else if y <= x {
        atan_unit(y / x)
    } else {
        FRAC_PI_2 - atan_unit(x / y)
    }
}

/// Arc tangente sur `[0, 1]`, par les seules opérations exactement arrondies.
///
/// Trois divisions de l'angle par deux, `atan(t) = 2 · atan(t / (1 + √(1 + t²)))`,
/// ramènent l'argument sous `tan(π/32) ≈ 0,0985` ; la série de Taylor jusqu'au
/// terme `t¹³` y laisse une erreur bornée par `t¹⁵ / 15`, soit moins de `1e-16`,
/// sous la précision d'un `f64`.
fn atan_unit(t: f64) -> f64 {
    let mut t = t;
    for _ in 0..3 {
        t /= 1.0 + (1.0 + t * t).sqrt();
    }
    let t2 = t * t;
    let series = t
        * (1.0
            - t2 * (1.0 / 3.0
                - t2 * (1.0 / 5.0
                    - t2 * (1.0 / 7.0 - t2 * (1.0 / 9.0 - t2 * (1.0 / 11.0 - t2 / 13.0))))));
    8.0 * series
}

fn add(a: [f64; 3], b: [f64; 3]) -> [f64; 3] {
    [a[0] + b[0], a[1] + b[1], a[2] + b[2]]
}

fn sub(a: [f64; 3], b: [f64; 3]) -> [f64; 3] {
    [a[0] - b[0], a[1] - b[1], a[2] - b[2]]
}

fn cross(a: [f64; 3], b: [f64; 3]) -> [f64; 3] {
    [
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    ]
}

fn length(a: [f64; 3]) -> f64 {
    (a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).sqrt()
}

#[cfg(test)]
mod tests {
    use super::super::tests::{asset, sommet};
    use super::*;
    use core::f64::consts::{FRAC_PI_4, PI};

    #[test]
    fn t241_l_arc_tangente_tient_la_precision_d_un_f64() {
        // `f64::atan` ne sert ici que de référence : c'est justement celle
        // qu'on n'emploie pas, faute d'arrondi garanti.
        for step in 0..=10_000 {
            let t = f64::from(step) / 10_000.0;
            let ecart = (atan_unit(t) - t.atan()).abs();
            assert!(ecart < 1e-15, "atan({t}) : écart {ecart:e}");
        }
        assert_eq!(atan_ratio(1.0, 0.0), FRAC_PI_2);
        assert!((atan_ratio(1.0, 1.0) - FRAC_PI_4).abs() < 1e-15);
    }

    #[test]
    fn t241_les_angles_d_un_triangle_font_pi() {
        let (a, b, c) = ([0.0, 0.0, 0.0], [3.0, 0.1, 0.0], [0.4, 2.0, 0.7]);
        let somme = corner_angle(a, b, c) + corner_angle(b, c, a) + corner_angle(c, a, b);
        assert!((somme - PI).abs() < 1e-14, "{somme}");
    }

    #[test]
    fn t241_la_normale_est_ponderee_par_l_angle_et_non_par_l_aire() {
        // Au sommet 0, deux triangles : l'un dans le plan XY, d'angle droit et
        // d'aire 0,5 ; l'autre dans le plan XZ, d'angle π/4 et d'aire 2.
        // Pondéré par l'angle : (0, π/4, π/2), soit (0, 1, 2)/√5.
        // Pondéré par l'aire, Y l'emporterait : (0, 4, 1).
        let vertices = vec![
            sommet([0.0, 0.0, 0.0]),
            sommet([1.0, 0.0, 0.0]),
            sommet([0.0, 1.0, 0.0]),
            sommet([0.0, 0.0, 2.0]),
            sommet([2.0, 0.0, 2.0]),
        ];
        let mut asset = asset(vertices, vec![0, 1, 2, 0, 3, 4], true);
        let generated = generate_missing(&mut asset);

        assert_eq!(generated, 5);
        // 127/√5 = 56,8 et 254/√5 = 113,6.
        assert_eq!(asset.vertices[0].normal, [0, 57, 114, 0]);
        assert_eq!(asset.vertices[1].normal, [0, 0, 127, 0]);
        assert_eq!(asset.vertices[3].normal, [0, 127, 0, 0]);
    }

    #[test]
    fn t241_une_normale_ecrite_n_est_jamais_recalculee() {
        let mut vertices = vec![
            sommet([0.0, 0.0, 0.0]),
            sommet([1.0, 0.0, 0.0]),
            sommet([0.0, 1.0, 0.0]),
        ];
        vertices[0].normal = [127, 0, 0, 0];
        let mut asset = asset(vertices, vec![0, 1, 2], true);
        asset.missing_normals[0] = false;

        assert_eq!(generate_missing(&mut asset), 2);
        assert_eq!(asset.vertices[0].normal, [127, 0, 0, 0]);
    }

    #[test]
    fn t241_une_somme_annulee_prend_la_normale_du_premier_triangle() {
        // Deux faces dos à dos sur les mêmes sommets : les normales s'annulent.
        let vertices = vec![
            sommet([0.0, 0.0, 0.0]),
            sommet([1.0, 0.0, 0.0]),
            sommet([0.0, 1.0, 0.0]),
        ];
        let mut asset = asset(vertices, vec![0, 1, 2, 0, 2, 1], true);

        assert_eq!(generate_missing(&mut asset), 3);
        for vertex in &asset.vertices {
            assert_eq!(vertex.normal, [0, 0, 127, 0]);
        }
    }

    #[test]
    fn t241_un_sommet_sans_triangle_reste_marque() {
        // Hors du chemin de `compile`, où la fusion l'aurait retiré : la
        // normale reste nulle et marquée, et la validation de sortie refuse.
        let vertices = vec![
            sommet([0.0, 0.0, 0.0]),
            sommet([1.0, 0.0, 0.0]),
            sommet([0.0, 1.0, 0.0]),
            sommet([5.0, 5.0, 5.0]),
        ];
        let mut asset = asset(vertices, vec![0, 1, 2], true);

        assert_eq!(generate_missing(&mut asset), 3);
        assert!(asset.missing_normals[3]);
        assert_eq!(asset.vertices[3].normal, [0; 4]);
    }
}
