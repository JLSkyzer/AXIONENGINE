//! T-361 — l'interpolation est conforme à glTF 2.0 : comparaison à un échantillonneur de
//! référence, écrit ici en `f64` d'après la spécification (annexe C de glTF 2.0), par recherche
//! linéaire et `acos`/`sin` pour le slerp.

use ax_anim::{sample, Cursor};
use ax_model::dm::animation::{channel, interp, TrackDesc};

fn piste(channel: u8, interp: u8, count: u32) -> TrackDesc {
    TrackDesc {
        target_node: 0,
        channel,
        interp,
        _pad: [0; 2],
        key_offset: 0,
        key_count: count,
    }
}

fn rotation(axe: [f64; 3], angle: f64) -> [f32; 4] {
    let n = (axe[0] * axe[0] + axe[1] * axe[1] + axe[2] * axe[2]).sqrt();
    let (s, c) = ((angle / 2.0).sin(), (angle / 2.0).cos());
    [
        (axe[0] / n * s) as f32,
        (axe[1] / n * s) as f32,
        (axe[2] / n * s) as f32,
        c as f32,
    ]
}

/// L'échantillonneur de référence : la spécification glTF, en `f64`, sans curseur.
fn reference(track: &TrackDesc, keys: &[f32], time: f64) -> [f64; 4] {
    let components = channel::components(track.channel).unwrap();
    let stride = track.key_stride().unwrap();
    let count = track.key_count as usize;
    let cubic = track.interp == interp::CUBIC_SPLINE;
    let at = |key: usize, slot: usize| -> [f64; 4] {
        let mut out = [0.0; 4];
        for (axis, value) in out.iter_mut().enumerate().take(components) {
            *value = f64::from(keys[key * stride + 1 + slot * components + axis]);
        }
        out
    };
    let value = |key: usize| at(key, usize::from(cubic));
    let time_of = |key: usize| f64::from(keys[key * stride]);
    let normalise = |q: [f64; 4]| {
        let n = (q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]).sqrt();
        q.map(|v| v / n)
    };
    if time <= time_of(0) {
        return value(0);
    }
    if time >= time_of(count - 1) {
        return value(count - 1);
    }
    let key = (0..count - 1)
        .find(|&key| time_of(key) <= time && time < time_of(key + 1))
        .unwrap();
    let (t0, t1) = (time_of(key), time_of(key + 1));
    let span = t1 - t0;
    let u = (time - t0) / span;
    match track.interp {
        interp::STEP => value(key),
        interp::CUBIC_SPLINE => {
            let (u2, u3) = (u * u, u * u * u);
            let (p0, m0, p1, m1) = (value(key), at(key, 2), value(key + 1), at(key + 1, 0));
            let out = [0, 1, 2, 3].map(|i| {
                (2.0 * u3 - 3.0 * u2 + 1.0) * p0[i]
                    + (u3 - 2.0 * u2 + u) * span * m0[i]
                    + (-2.0 * u3 + 3.0 * u2) * p1[i]
                    + (u3 - u2) * span * m1[i]
            });
            if track.channel == channel::ROTATION {
                normalise(out)
            } else {
                out
            }
        }
        _ if track.channel == channel::ROTATION => {
            let (a, mut b) = (value(key), value(key + 1));
            let mut d: f64 = (0..4).map(|i| a[i] * b[i]).sum();
            if d < 0.0 {
                b = b.map(|v| -v);
                d = -d;
            }
            if d > 1.0 - 1e-12 {
                return [0, 1, 2, 3].map(|i| a[i] + (b[i] - a[i]) * u);
            }
            let theta = d.acos();
            let (wa, wb) = (
                ((1.0 - u) * theta).sin() / theta.sin(),
                (u * theta).sin() / theta.sin(),
            );
            [0, 1, 2, 3].map(|i| a[i] * wa + b[i] * wb)
        }
        _ => {
            let (a, b) = (value(key), value(key + 1));
            [0, 1, 2, 3].map(|i| a[i] + (b[i] - a[i]) * u)
        }
    }
}

/// Les instants de l'essai : en avant par pas fins, en arrière, puis par sauts, pour éprouver le
/// curseur dans tous ses chemins ; avant la première clé et après la dernière compris.
fn instants() -> Vec<f64> {
    let mut out: Vec<f64> = (-50..=250).map(|pas| f64::from(pas) * 0.01).collect();
    out.extend((-50..=250).rev().map(|pas| f64::from(pas) * 0.01));
    let mut graine = 12_345u32;
    for _ in 0..400 {
        graine = graine.wrapping_mul(1_103_515_245).wrapping_add(12_345);
        out.push(f64::from(graine >> 8) / f64::from(1u32 << 24) * 3.0 - 0.5);
    }
    out
}

fn compare(track: &TrackDesc, keys: &[f32], tolerance: f64) {
    let mut cursor = Cursor::default();
    let components = channel::components(track.channel).unwrap();
    let mut pire = 0.0f64;
    for time in instants() {
        let obtenu = sample(track, keys, time as f32, &mut cursor);
        let attendu = reference(track, keys, f64::from(time as f32));
        for axis in 0..components {
            pire = pire.max((f64::from(obtenu[axis]) - attendu[axis]).abs());
        }
    }
    assert!(
        pire <= tolerance,
        "canal {}, interpolation {} : écart {pire:e}",
        track.channel,
        track.interp
    );
}

/// Des temps irréguliers : 0, 0,3, 1,1, 2,0 secondes.
const TEMPS: [f32; 4] = [0.0, 0.3, 1.1, 2.0];

fn cles(valeurs: &[&[f32]]) -> Vec<f32> {
    let mut out = Vec::new();
    for (temps, valeur) in TEMPS.iter().zip(valeurs) {
        out.push(*temps);
        out.extend_from_slice(valeur);
    }
    out
}

#[test]
fn t361_translation_en_palier_et_lineaire() {
    let valeurs: [&[f32]; 4] = [
        &[0.0, 0.0, 0.0],
        &[2.0, -1.0, 0.5],
        &[-3.0, 4.0, 1.0],
        &[10.0, 0.0, -2.0],
    ];
    let keys = cles(&valeurs);
    compare(&piste(channel::TRANSLATION, interp::STEP, 4), &keys, 0.0);
    compare(&piste(channel::TRANSLATION, interp::LINEAR, 4), &keys, 4e-6);
    compare(&piste(channel::SCALE, interp::LINEAR, 4), &keys, 4e-6);
}

#[test]
fn t361_rotation_lineaire_par_slerp_et_plus_court_chemin() {
    let a = rotation([0.0, 1.0, 0.0], 0.2);
    let b = rotation([1.0, 0.5, 0.0], 2.4);
    // L'opposé d'une rotation proche : le plus court chemin passe par `-c`.
    let c = rotation([1.0, 0.5, 0.0], 2.6).map(|v| -v);
    let d = rotation([0.0, 0.0, 1.0], -1.0);
    let keys = cles(&[&a, &b, &c, &d]);
    compare(&piste(channel::ROTATION, interp::LINEAR, 4), &keys, 4e-7);
    compare(&piste(channel::ROTATION, interp::STEP, 4), &keys, 0.0);
}

#[test]
fn t361_spline_cubique_en_translation_et_en_rotation() {
    // Chaque clé : tangente d'entrée, valeur, tangente de sortie.
    let mut translation = Vec::new();
    let mut rotation_cles = Vec::new();
    for (rang, temps) in TEMPS.iter().enumerate() {
        let r = rang as f32;
        translation.push(*temps);
        translation.extend_from_slice(&[r, -r, 0.5 * r]);
        translation.extend_from_slice(&[r * r, 1.0 - r, 2.0]);
        translation.extend_from_slice(&[-r, r * 0.5, 3.0 - r]);

        rotation_cles.push(*temps);
        rotation_cles.extend_from_slice(&[0.1 * r, 0.2, -0.1, 0.05]);
        rotation_cles.extend_from_slice(&rotation(
            [1.0, f64::from(r), 0.3],
            0.5 + 0.7 * f64::from(r),
        ));
        rotation_cles.extend_from_slice(&[-0.2, 0.1 * r, 0.3, -0.05]);
    }
    compare(
        &piste(channel::TRANSLATION, interp::CUBIC_SPLINE, 4),
        &translation,
        2e-5,
    );
    compare(
        &piste(channel::ROTATION, interp::CUBIC_SPLINE, 4),
        &rotation_cles,
        1e-6,
    );
}

#[test]
fn t361_une_piste_d_une_seule_cle_est_constante() {
    let keys = [0.4, 1.0, 2.0, 3.0];
    let mut cursor = Cursor::default();
    for time in [-1.0, 0.0, 0.4, 5.0] {
        assert_eq!(
            sample(
                &piste(channel::TRANSLATION, interp::LINEAR, 1),
                &keys,
                time,
                &mut cursor
            ),
            [1.0, 2.0, 3.0, 0.0]
        );
    }
}
