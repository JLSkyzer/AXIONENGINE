//! Quaternions `(x, y, z, w)` en `f32`, par les seules opérations exactes d'IEEE 754.
//!
//! L'échantillonnage tourne aussi côté serveur quand un collider dépend d'un node animé (R-1430) :
//! sa pose entre alors dans la physique, que `enhanced-determinism` rend identique d'une plateforme
//! à l'autre (ADR-116). Un `acos` ou un `sin` de la `libm` de la plateforme y ferait diverger les
//! machines. Aucune fonction transcendante n'est donc employée ici : additions, multiplications,
//! divisions et racines carrées, toutes correctement arrondies, sans `mul_add`.

/// Quaternion `(x, y, z, w)`.
pub type Quat = [f32; 4];

/// La rotation nulle.
pub const IDENTITY: Quat = [0.0, 0.0, 0.0, 1.0];

/// Nombre de termes de la série : 16 la mènent sous l'arrondi de `f32`.
const TERMS: usize = 16;

/// Correction `μ` du dernier terme, qui compense la troncature.
///
/// Trouvée par minimisation de l'écart maximal à `sin(tθ) / sin θ`, en `f64`, sur `θ ∈ [0, π/2]`
/// par pas de 0,5° et `t ∈ [0, 1]` par pas de 1/32 : l'écart y tombe à 3,1e-8, contre 1,0e-6 sans
/// correction (calcul du 2026-10-29). À huit termes, le mieux qu'on obtienne est 1,9e-5 : trop pour
/// une rotation, d'où seize.
const MU: f32 = 1.916_667_1;

/// Les coefficients `u_i = 1 / (i (2i + 1))` et `v_i = i / (2i + 1)` de la série, pour `i` de 1 à
/// [`TERMS`], le dernier multiplié par [`MU`].
const COEFFICIENTS: [(f32, f32); TERMS] = coefficients();

const fn coefficients() -> [(f32, f32); TERMS] {
    let mut out = [(0.0, 0.0); TERMS];
    let mut index = 0;
    while index < TERMS {
        let i = (index + 1) as f32;
        let (u, v) = (1.0 / (i * (2.0 * i + 1.0)), i / (2.0 * i + 1.0));
        out[index] = if index + 1 == TERMS {
            (u * MU, v * MU)
        } else {
            (u, v)
        };
        index += 1;
    }
    out
}

/// Le produit scalaire de deux quaternions.
#[must_use]
pub fn dot(a: &Quat, b: &Quat) -> f32 {
    a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
}

/// L'interpolation sphérique de `a` vers `b`, par le plus court chemin, comme glTF la demande
/// pour une rotation `LINEAR`.
///
/// `sin(tθ) / sin θ` y est développé en série de `cos θ - 1`, par la récurrence d'Eberly (« A Fast
/// and Accurate Algorithm for Computing SLERP », 2011) : chaque coefficient vaut le précédent fois
/// `(t² - i²) / (i (2i + 1))`. Tronquée à [`TERMS`] termes, le dernier corrigé par [`MU`], la série
/// est un polynôme de `cos θ` et de `t` : ni `acos` ni `sin` n'entrent dans le calcul. Le plus
/// court chemin ramène `θ` dans `[0, π/2]`, où son écart au slerp exact reste de l'ordre de
/// l'arrondi de `f32` (T-361).
#[must_use]
pub fn slerp(a: &Quat, b: &Quat, t: f32) -> Quat {
    let mut x = dot(a, b);
    // Le plus court chemin : `b` et `-b` sont la même rotation.
    let sign = if x >= 0.0 {
        1.0
    } else {
        x = -x;
        -1.0
    };
    let xm1 = x - 1.0;
    let d = 1.0 - t;
    let (t2, d2) = (t * t, d * d);
    let mut c_t = 1.0;
    let mut c_d = 1.0;
    for &(u, v) in COEFFICIENTS.iter().rev() {
        c_t = 1.0 + (u * t2 - v) * xm1 * c_t;
        c_d = 1.0 + (u * d2 - v) * xm1 * c_d;
    }
    let c_t = sign * t * c_t;
    let c_d = d * c_d;
    [
        a[0] * c_d + b[0] * c_t,
        a[1] * c_d + b[1] * c_t,
        a[2] * c_d + b[2] * c_t,
        a[3] * c_d + b[3] * c_t,
    ]
}

/// Le quaternion ramené à la norme 1 ; un quaternion nul devient la rotation nulle.
#[must_use]
pub fn normalize(q: &Quat) -> Quat {
    let norm = dot(q, q).sqrt();
    if norm > 0.0 && norm.is_finite() {
        [q[0] / norm, q[1] / norm, q[2] / norm, q[3] / norm]
    } else {
        IDENTITY
    }
}

/// Le produit de Hamilton `a · b` : la rotation `b`, puis `a`.
#[must_use]
pub fn mul(a: &Quat, b: &Quat) -> Quat {
    [
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    ]
}

/// Le conjugué : l'inverse d'une rotation unitaire.
#[must_use]
pub fn conjugate(q: &Quat) -> Quat {
    [-q[0], -q[1], -q[2], q[3]]
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Le slerp de glTF, en `f64`, par `acos` et `sin` : la référence.
    fn reference(a: &Quat, b: &Quat, t: f64) -> [f64; 4] {
        let a = a.map(f64::from);
        let mut b = b.map(f64::from);
        let mut d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
        if d < 0.0 {
            b = b.map(|v| -v);
            d = -d;
        }
        if d > 1.0 - 1e-12 {
            return [0, 1, 2, 3].map(|i| a[i] + (b[i] - a[i]) * t);
        }
        let theta = d.acos();
        let s = theta.sin();
        let (wa, wb) = (((1.0 - t) * theta).sin() / s, (t * theta).sin() / s);
        [0, 1, 2, 3].map(|i| a[i] * wa + b[i] * wb)
    }

    fn rotation(axe: [f32; 3], angle: f32) -> Quat {
        let n = (axe[0] * axe[0] + axe[1] * axe[1] + axe[2] * axe[2]).sqrt();
        let (s, c) = ((angle / 2.0).sin(), (angle / 2.0).cos());
        [axe[0] / n * s, axe[1] / n * s, axe[2] / n * s, c]
    }

    #[test]
    fn t361_le_slerp_suit_la_reference_sur_tout_l_intervalle() {
        let mut pire = 0.0f64;
        // Des angles de 0 à presque un demi-tour, dans les deux hémisphères.
        for pas in 0..=60 {
            let angle = pas as f32 * core::f32::consts::PI * 2.0 / 61.0;
            let a = rotation([0.3, 1.0, -0.2], 0.4);
            let b = mul(&rotation([1.0, -0.5, 0.7], angle), &a);
            for b in [b, b.map(|v| -v)] {
                for u in 0..=16 {
                    let t = u as f32 / 16.0;
                    let obtenu = slerp(&a, &b, t);
                    let attendu = reference(&a, &b, f64::from(t));
                    for i in 0..4 {
                        pire = pire.max((f64::from(obtenu[i]) - attendu[i]).abs());
                    }
                }
            }
        }
        // Mesuré à 1,4e-7 le 2026-10-29 : l'arrondi de `f32`, la série en étant à 3e-8.
        assert!(pire < 3e-7, "écart maximal {pire:e}");
    }

    #[test]
    fn le_slerp_rend_ses_extremites() {
        let a = rotation([0.0, 1.0, 0.0], 0.3);
        let b = rotation([1.0, 0.0, 0.0], 2.0);
        let debut = slerp(&a, &b, 0.0);
        let fin = slerp(&a, &b, 1.0);
        for i in 0..4 {
            assert!((debut[i] - a[i]).abs() < 1e-6);
            assert!((fin[i] - b[i]).abs() < 1e-6);
        }
    }

    #[test]
    fn le_produit_et_le_conjugue_s_annulent() {
        let q = rotation([0.2, 0.9, 0.4], 1.3);
        let neutre = mul(&q, &conjugate(&q));
        for (obtenu, attendu) in neutre.iter().zip(IDENTITY) {
            assert!((obtenu - attendu).abs() < 1e-6);
        }
        assert_eq!(normalize(&[0.0; 4]), IDENTITY);
        let n = normalize(&[0.0, 0.0, 3.0, 4.0]);
        assert_eq!(n, [0.0, 0.0, 0.6, 0.8]);
    }
}
