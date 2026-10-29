//! L'échantillonnage d'une piste à un instant (§18.2) : recherche à curseur, `STEP`, `LINEAR`,
//! `CUBIC_SPLINE`, conformes à glTF 2.0.
//!
//! Avant la première clé, la valeur est celle de la première ; après la dernière, celle de la
//! dernière. Une rotation `LINEAR` est un slerp par le plus court chemin ([`crate::quat::slerp`]) ;
//! une rotation `CUBIC_SPLINE` est l'interpolation d'Hermite de ses quatre composantes, puis
//! normalisée, comme glTF le prescrit.

use crate::quat;
use ax_model::dm::animation::{channel, interp, TrackDesc};

/// Le curseur d'une piste : la dernière clé de début d'intervalle trouvée.
///
/// Une animation avance d'une frame à l'autre, si bien que l'intervalle cherché est presque
/// toujours celui du curseur ou le suivant : la recherche binaire n'a lieu qu'après un saut, et la
/// recherche coûte `O(1)` amorti (§18.2).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Cursor(u32);

/// Échantillonne une piste à l'instant `time`, en secondes de clip.
///
/// `keys` sont les clés de la piste ([`crate::ClipLibrary::keys`]). La valeur est écrite dans les
/// trois premières composantes de la sortie pour une translation ou une échelle, dans les quatre
/// pour une rotation.
#[must_use]
pub fn sample(track: &TrackDesc, keys: &[f32], time: f32, cursor: &mut Cursor) -> [f32; 4] {
    let components = channel::components(track.channel).unwrap_or(3);
    let stride = track.key_stride().unwrap_or(1 + components);
    let count = track.key_count as usize;
    let cubic = track.interp == interp::CUBIC_SPLINE;
    // Le rang de la valeur dans une clé : après le temps, et après la tangente d'entrée en cubique.
    let value_at = if cubic { 1 + components } else { 1 };
    let time_of = |key: usize| keys[key * stride];
    let value = |key: usize| read(keys, key * stride + value_at, components);

    if count == 1 || time <= time_of(0) {
        return finish(track, value(0));
    }
    if time >= time_of(count - 1) {
        return finish(track, value(count - 1));
    }
    let key = locate(keys, stride, count, time, cursor);
    let (t0, t1) = (time_of(key), time_of(key + 1));
    let span = t1 - t0;
    let u = (time - t0) / span;

    let result = match track.interp {
        interp::STEP => value(key),
        interp::CUBIC_SPLINE => {
            let (u2, u3) = (u * u, u * u * u);
            let h00 = 2.0 * u3 - 3.0 * u2 + 1.0;
            let h10 = u3 - 2.0 * u2 + u;
            let h01 = -2.0 * u3 + 3.0 * u2;
            let h11 = u3 - u2;
            // La tangente de sortie de la clé `key`, celle d'entrée de la suivante.
            let out_tangent = read(keys, key * stride + 1 + 2 * components, components);
            let in_tangent = read(keys, (key + 1) * stride + 1, components);
            let (p0, p1) = (value(key), value(key + 1));
            let mut out = [0.0; 4];
            for axis in 0..components {
                out[axis] = h00 * p0[axis]
                    + h10 * span * out_tangent[axis]
                    + h01 * p1[axis]
                    + h11 * span * in_tangent[axis];
            }
            out
        }
        _ if track.channel == channel::ROTATION => quat::slerp(&value(key), &value(key + 1), u),
        _ => {
            let (a, b) = (value(key), value(key + 1));
            let mut out = [0.0; 4];
            for axis in 0..components {
                out[axis] = a[axis] + (b[axis] - a[axis]) * u;
            }
            out
        }
    };
    finish(track, result)
}

/// La clé qui ouvre l'intervalle de `time`, strictement entre la première et la dernière.
fn locate(keys: &[f32], stride: usize, count: usize, time: f32, cursor: &mut Cursor) -> usize {
    let time_of = |key: usize| keys[key * stride];
    let inside = |key: usize| key + 1 < count && time_of(key) <= time && time < time_of(key + 1);
    let current = cursor.0 as usize;
    let key = if inside(current) {
        current
    } else if inside(current + 1) {
        current + 1
    } else {
        // Le premier temps qui dépasse `time`, moins un : `time` est dans `[t0, t_last[`.
        let (mut low, mut high) = (0, count - 1);
        while low < high {
            let middle = (low + high) / 2;
            if time_of(middle + 1) <= time {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        low
    };
    cursor.0 = key as u32;
    key
}

fn read(keys: &[f32], at: usize, components: usize) -> [f32; 4] {
    let mut out = [0.0; 4];
    out[..components].copy_from_slice(&keys[at..at + components]);
    out
}

/// Une rotation cubique sort de la sphère unité : glTF la normalise.
fn finish(track: &TrackDesc, value: [f32; 4]) -> [f32; 4] {
    if track.channel == channel::ROTATION && track.interp == interp::CUBIC_SPLINE {
        quat::normalize(&value)
    } else {
        value
    }
}
