//! Section `ANIM` : `AnimationDesc[]`, `TrackDesc[]`, clés et noms (PARTIE 7.3, DM-10).
//!
//! Disposition, en petit-boutiste, fixée par `docs/decisions/ADR-155.md` (D2) :
//!
//! ```text
//! u32 animation_count
//! u32 track_count
//! u32 key_count                     flottants du tableau des clés
//! u32 names_size                    octets de la table des noms
//! AnimationDesc[animation_count]    24 octets chacune, disposition repr(C)
//! TrackDesc[track_count]            16 octets chacune, disposition repr(C)
//! NameRef[animation_count]          u32 offset, u32 length, relatifs aux noms
//! f32 keys[key_count]
//! u8[names_size]                    noms UTF-8, sans terminateur
//! ```
//!
//! Une piste désigne ses clés par le rang de leur premier flottant (`key_offset`) et leur nombre
//! (`key_count`) ; une clé occupe [`TrackDesc::key_stride`] flottants. Les noms sont ceux des
//! clips que joue `AnimationControl` (ADR-155 D5), dont `name_hash` est l'empreinte.

use super::{A3dError, SectionTag};
use ax_model::dm::animation::{AnimationDesc, TrackDesc};
use ax_model::dm::limits;
use ax_model::dm::scene::name_hash;

/// En-tête de la section : les trois dénombrements et la taille de la table des noms.
const HEADER_BYTES: usize = 16;

/// Référence d'un nom : décalage et longueur.
const NAME_REF_BYTES: usize = 8;

/// Contenu d'une section `ANIM`.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct AnimationTable {
    /// Animations.
    pub animations: Vec<AnimationDesc>,
    /// Nom de chaque animation, vide si elle n'en a pas.
    pub names: Vec<String>,
    /// Pistes de toutes les animations.
    pub tracks: Vec<TrackDesc>,
    /// Clés de toutes les pistes.
    pub keys: Vec<f32>,
}

impl AnimationTable {
    /// Les clés d'une piste, `None` si elle sort du tableau ou que son pas n'existe pas.
    #[must_use]
    pub fn keys_of(&self, track: &TrackDesc) -> Option<&[f32]> {
        let stride = track.key_stride()?;
        let start = track.key_offset as usize;
        let end = start.checked_add((track.key_count as usize).checked_mul(stride)?)?;
        self.keys.get(start..end)
    }
}

/// Encode une section `ANIM`.
///
/// # Errors
///
/// [`A3dError::MalformedSection`] si la table est incohérente — voir [`decode_animations`] —,
/// [`A3dError::SectionUnwritable`] si elle dépasse ce qu'un `u32` décrit.
pub fn encode_animations(table: &AnimationTable) -> Result<Vec<u8>, A3dError> {
    check(table)?;
    let animation_count = u32::try_from(table.animations.len()).map_err(|_| unwritable())?;
    let track_count = u32::try_from(table.tracks.len()).map_err(|_| unwritable())?;
    let key_count = u32::try_from(table.keys.len()).map_err(|_| unwritable())?;
    let names_size: usize = table.names.iter().map(String::len).sum();
    let names_size_u32 = u32::try_from(names_size).map_err(|_| unwritable())?;

    let mut out = Vec::with_capacity(
        HEADER_BYTES
            + table.animations.len() * (AnimationDesc::BYTES + NAME_REF_BYTES)
            + table.tracks.len() * TrackDesc::BYTES
            + table.keys.len() * 4
            + names_size,
    );
    for value in [animation_count, track_count, key_count, names_size_u32] {
        out.extend_from_slice(&value.to_le_bytes());
    }
    for animation in &table.animations {
        out.extend_from_slice(&animation.name_hash.to_le_bytes());
        out.extend_from_slice(&animation.duration.to_le_bytes());
        out.extend_from_slice(&animation.track_offset.to_le_bytes());
        out.extend_from_slice(&animation.track_count.to_le_bytes());
        out.extend_from_slice(&animation.flags.to_le_bytes());
    }
    for track in &table.tracks {
        out.extend_from_slice(&track.target_node.to_le_bytes());
        out.push(track.channel);
        out.push(track.interp);
        out.extend_from_slice(&[0; 2]);
        out.extend_from_slice(&track.key_offset.to_le_bytes());
        out.extend_from_slice(&track.key_count.to_le_bytes());
    }
    let mut offset = 0u32;
    for name in &table.names {
        // `names_size` tient dans un `u32` : chaque longueur et chaque décalage aussi.
        let length = name.len() as u32;
        out.extend_from_slice(&offset.to_le_bytes());
        out.extend_from_slice(&length.to_le_bytes());
        offset += length;
    }
    for key in &table.keys {
        out.extend_from_slice(&key.to_le_bytes());
    }
    for name in &table.names {
        out.extend_from_slice(name.as_bytes());
    }
    Ok(out)
}

/// Décode une section `ANIM` venue d'un fichier qu'on ne croit pas sur parole.
///
/// La section est jugée sur sa forme : dénombrements plafonnés **avant** toute allocation
/// (R-901), plages de pistes et de clés dans leurs tableaux, canal et interpolation connus, au
/// plus 256 pistes par animation (R-200), noms conformes à leurs empreintes. La valeur des clés —
/// finies, temps croissants — et les nodes visés se jugent avec le reste de l'asset, à la
/// validation (C-22).
///
/// # Errors
///
/// [`A3dError::MalformedSection`] au premier écart.
pub fn decode_animations(bytes: &[u8]) -> Result<AnimationTable, A3dError> {
    if bytes.len() < HEADER_BYTES {
        return Err(malformed("en-tête tronqué"));
    }
    let animation_count = read_u32(bytes, 0) as usize;
    let track_count = read_u32(bytes, 4) as usize;
    let key_count = read_u32(bytes, 8) as usize;
    let names_size = read_u32(bytes, 12) as usize;
    if animation_count > limits::MAX_ANIMATIONS {
        return Err(malformed("plus d'animations que C-22 n'en admet"));
    }
    if track_count > limits::MAX_ANIMATIONS * limits::MAX_ACTIVE_TRACKS {
        return Err(malformed(
            "plus de pistes que les animations n'en admettent",
        ));
    }
    let tracks_start = HEADER_BYTES + animation_count * AnimationDesc::BYTES;
    let refs_start = tracks_start + track_count * TrackDesc::BYTES;
    let keys_start = refs_start + animation_count * NAME_REF_BYTES;
    // Le nombre de clés n'est pas plafonné à part : il l'est par la taille de la section, que
    // la lecture du conteneur a déjà bornée.
    let Some(names_start) = key_count
        .checked_mul(4)
        .and_then(|keys| keys_start.checked_add(keys))
    else {
        return Err(malformed("taille incohérente avec les dénombrements"));
    };
    if names_start.checked_add(names_size) != Some(bytes.len()) {
        return Err(malformed("taille incohérente avec les dénombrements"));
    }

    let animations = (0..animation_count)
        .map(|index| {
            let at = HEADER_BYTES + index * AnimationDesc::BYTES;
            AnimationDesc {
                name_hash: u64::from_le_bytes(array(bytes, at)),
                duration: f32::from_le_bytes(array(bytes, at + 8)),
                track_offset: read_u32(bytes, at + 12),
                track_count: read_u32(bytes, at + 16),
                flags: read_u32(bytes, at + 20),
            }
        })
        .collect();
    let tracks = (0..track_count)
        .map(|index| {
            let at = tracks_start + index * TrackDesc::BYTES;
            TrackDesc {
                target_node: read_u32(bytes, at),
                channel: bytes[at + 4],
                interp: bytes[at + 5],
                _pad: [0; 2],
                key_offset: read_u32(bytes, at + 8),
                key_count: read_u32(bytes, at + 12),
            }
        })
        .collect();
    let keys = (0..key_count)
        .map(|index| f32::from_le_bytes(array(bytes, keys_start + index * 4)))
        .collect();

    let table_bytes = &bytes[names_start..];
    let mut names = Vec::with_capacity(animation_count);
    for index in 0..animation_count {
        let at = refs_start + index * NAME_REF_BYTES;
        let offset = read_u32(bytes, at) as usize;
        let length = read_u32(bytes, at + 4) as usize;
        if length > limits::MAX_NAME_BYTES {
            return Err(malformed("nom de plus de 64 octets"));
        }
        let Some(raw) = offset
            .checked_add(length)
            .and_then(|end| table_bytes.get(offset..end))
        else {
            return Err(malformed("nom hors de la table"));
        };
        let Ok(name) = core::str::from_utf8(raw) else {
            return Err(malformed("nom non UTF-8"));
        };
        names.push(name.to_owned());
    }

    let table = AnimationTable {
        animations,
        names,
        tracks,
        keys,
    };
    check(&table)?;
    Ok(table)
}

/// La cohérence de la table : ce que l'encodeur refuse d'écrire et le décodeur de lire.
fn check(table: &AnimationTable) -> Result<(), A3dError> {
    if table.names.len() != table.animations.len() {
        return Err(malformed("un nom par animation est attendu"));
    }
    if table.animations.len() > limits::MAX_ANIMATIONS {
        return Err(malformed("plus d'animations que C-22 n'en admet"));
    }
    for (animation, name) in table.animations.iter().zip(&table.names) {
        if name.len() > limits::MAX_NAME_BYTES {
            return Err(malformed("nom de plus de 64 octets"));
        }
        if animation.name_hash != name_hash(name) {
            return Err(malformed("empreinte de nom incohérente"));
        }
        if animation.track_count as usize > limits::MAX_ACTIVE_TRACKS {
            return Err(malformed("animation de plus de 256 pistes (R-200)"));
        }
        let end = (animation.track_offset as usize).checked_add(animation.track_count as usize);
        if end.is_none_or(|end| end > table.tracks.len()) {
            return Err(malformed("pistes d'animation hors du tableau"));
        }
    }
    for track in &table.tracks {
        if track.key_stride().is_none() {
            return Err(malformed("canal ou interpolation inconnus"));
        }
        if track.key_count == 0 {
            return Err(malformed("piste sans clé"));
        }
        if table.keys_of(track).is_none() {
            return Err(malformed("clés de piste hors du tableau"));
        }
    }
    Ok(())
}

fn malformed(detail: &'static str) -> A3dError {
    A3dError::MalformedSection {
        tag: SectionTag::ANIM,
        detail,
    }
}

fn unwritable() -> A3dError {
    A3dError::SectionUnwritable(SectionTag::ANIM)
}

fn read_u32(bytes: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(array(bytes, at))
}

fn array<const N: usize>(bytes: &[u8], at: usize) -> [u8; N] {
    let mut out = [0; N];
    out.copy_from_slice(&bytes[at..at + N]);
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use ax_model::dm::animation::{animation_flags, channel, interp};

    fn table() -> AnimationTable {
        AnimationTable {
            animations: vec![
                AnimationDesc {
                    name_hash: name_hash("lever"),
                    duration: 1.0,
                    track_offset: 0,
                    track_count: 2,
                    flags: animation_flags::LOOP,
                },
                AnimationDesc {
                    name_hash: name_hash(""),
                    duration: 0.5,
                    track_offset: 2,
                    track_count: 1,
                    flags: 0,
                },
            ],
            names: vec!["lever".to_owned(), String::new()],
            tracks: vec![
                // Deux clés de translation linéaires : 2 × 4 flottants.
                TrackDesc {
                    target_node: 1,
                    channel: channel::TRANSLATION,
                    interp: interp::LINEAR,
                    _pad: [0; 2],
                    key_offset: 0,
                    key_count: 2,
                },
                // Une clé de rotation en palier : 5 flottants.
                TrackDesc {
                    target_node: 2,
                    channel: channel::ROTATION,
                    interp: interp::STEP,
                    _pad: [0; 2],
                    key_offset: 8,
                    key_count: 1,
                },
                // Une clé d'échelle cubique : 1 + 3 × 3 flottants.
                TrackDesc {
                    target_node: 0,
                    channel: channel::SCALE,
                    interp: interp::CUBIC_SPLINE,
                    _pad: [0; 2],
                    key_offset: 13,
                    key_count: 1,
                },
            ],
            keys: (0..23).map(|value| value as f32 * 0.25).collect(),
        }
    }

    #[test]
    fn la_table_des_animations_fait_l_aller_retour() {
        let table = table();
        let bytes = encode_animations(&table).expect("encodage");
        let decoded = decode_animations(&bytes).expect("décodage");
        assert_eq!(decoded, table);
        assert_eq!(
            decoded.keys_of(&decoded.tracks[1]).map(<[f32]>::len),
            Some(5)
        );
    }

    #[test]
    fn la_disposition_de_la_section_anim_est_figee() {
        // Octets écrits à la main, champ par champ : la disposition d'ADR-155 D2.
        let table = AnimationTable {
            animations: vec![AnimationDesc {
                name_hash: name_hash("a"),
                duration: 2.0,
                track_offset: 0,
                track_count: 1,
                flags: animation_flags::LOOP,
            }],
            names: vec!["a".to_owned()],
            tracks: vec![TrackDesc {
                target_node: 3,
                channel: channel::SCALE,
                interp: interp::STEP,
                _pad: [0; 2],
                key_offset: 0,
                key_count: 1,
            }],
            keys: vec![0.0, 1.0, 2.0, 3.0],
        };
        let mut attendu = Vec::new();
        for value in [1u32, 1, 4, 1] {
            attendu.extend_from_slice(&value.to_le_bytes());
        }
        attendu.extend_from_slice(&0xaf63_dc4c_8601_ec8c_u64.to_le_bytes());
        attendu.extend_from_slice(&2.0f32.to_le_bytes());
        for value in [0u32, 1, 1] {
            attendu.extend_from_slice(&value.to_le_bytes());
        }
        attendu.extend_from_slice(&3u32.to_le_bytes());
        attendu.extend_from_slice(&[channel::SCALE, interp::STEP, 0, 0]);
        attendu.extend_from_slice(&0u32.to_le_bytes());
        attendu.extend_from_slice(&1u32.to_le_bytes());
        attendu.extend_from_slice(&0u32.to_le_bytes());
        attendu.extend_from_slice(&1u32.to_le_bytes());
        for value in [0.0f32, 1.0, 2.0, 3.0] {
            attendu.extend_from_slice(&value.to_le_bytes());
        }
        attendu.push(b'a');
        assert_eq!(encode_animations(&table).expect("encodage"), attendu);
    }

    #[test]
    fn une_table_mensongere_est_refusee_sans_paniquer() {
        let table = table();
        let bytes = encode_animations(&table).expect("encodage");

        assert!(decode_animations(&bytes[..bytes.len() - 1]).is_err());
        assert!(decode_animations(&[1, 2, 3]).is_err());
        let mut long = bytes.clone();
        long.push(0);
        assert!(decode_animations(&long).is_err());

        // Des dénombrements démesurés sont refusés avant toute allocation.
        for field in [0, 4, 8] {
            let mut enorme = bytes.clone();
            enorme[field..field + 4].copy_from_slice(&u32::MAX.to_le_bytes());
            assert!(decode_animations(&enorme).is_err(), "champ {field}");
        }

        let track =
            |index: usize| HEADER_BYTES + 2 * AnimationDesc::BYTES + index * TrackDesc::BYTES;
        // Un canal inconnu, une interpolation inconnue.
        let mut canal = bytes.clone();
        canal[track(0) + 4] = 3;
        let refus = decode_animations(&canal).unwrap_err();
        assert_eq!(refus.code(), -3007);
        let mut interpolation = bytes.clone();
        interpolation[track(0) + 5] = 3;
        assert!(decode_animations(&interpolation).is_err());
        // Des clés qui débordent du tableau.
        let mut cles = bytes.clone();
        cles[track(2) + 12..track(2) + 16].copy_from_slice(&2u32.to_le_bytes());
        assert!(decode_animations(&cles).is_err());
        // Une piste sans clé.
        let mut vide = bytes.clone();
        vide[track(1) + 12..track(1) + 16].copy_from_slice(&0u32.to_le_bytes());
        assert!(decode_animations(&vide).is_err());
        // Des pistes d'animation hors du tableau.
        let mut pistes = bytes.clone();
        let count = HEADER_BYTES + AnimationDesc::BYTES + 16;
        pistes[count..count + 4].copy_from_slice(&2u32.to_le_bytes());
        assert!(decode_animations(&pistes).is_err());
        // Un nom altéré ne correspond plus à son empreinte.
        let mut altere = bytes;
        let last = altere.len() - 1;
        altere[last] = b'X';
        assert!(decode_animations(&altere)
            .unwrap_err()
            .to_string()
            .contains("empreinte"));
    }

    #[test]
    fn une_animation_de_plus_de_256_pistes_ne_s_ecrit_pas() {
        let piste = TrackDesc {
            target_node: 0,
            channel: channel::TRANSLATION,
            interp: interp::STEP,
            _pad: [0; 2],
            key_offset: 0,
            key_count: 1,
        };
        let table = |pistes: usize| AnimationTable {
            animations: vec![AnimationDesc {
                name_hash: name_hash(""),
                duration: 0.0,
                track_offset: 0,
                track_count: pistes as u32,
                flags: 0,
            }],
            names: vec![String::new()],
            tracks: vec![piste; pistes],
            keys: vec![0.0; 4],
        };
        assert!(encode_animations(&table(limits::MAX_ACTIVE_TRACKS)).is_ok());
        assert!(encode_animations(&table(limits::MAX_ACTIVE_TRACKS + 1)).is_err());
    }
}
