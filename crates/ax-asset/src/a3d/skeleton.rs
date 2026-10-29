//! Section `SKEL` : `BoneDesc[]` (PARTIE 7.3, DM-10).
//!
//! Disposition, en petit-boutiste, fixée par `docs/decisions/ADR-155.md` (D2) :
//!
//! ```text
//! u32 bone_count
//! u32 réservé, à zéro
//! BoneDesc[bone_count]              80 octets chacun, disposition repr(C)
//! ```
//!
//! Un squelette par asset, d'au plus 128 os (R-200). Le nom d'un os est celui de son node : la
//! section n'en porte que l'empreinte.

use super::{A3dError, SectionTag};
use ax_model::dm::animation::BoneDesc;
use ax_model::dm::limits;
use ax_model::dm::scene::NONE_U16;

/// En-tête de la section : nombre d'os, puis un mot réservé qui aligne les os sur 8 octets.
const HEADER_BYTES: usize = 8;

/// Encode une section `SKEL`.
///
/// # Errors
///
/// [`A3dError::MalformedSection`] si le squelette dépasse 128 os ou qu'un parent ne désigne pas
/// un autre os du squelette.
pub fn encode_bones(bones: &[BoneDesc]) -> Result<Vec<u8>, A3dError> {
    check(bones)?;
    let mut out = Vec::with_capacity(HEADER_BYTES + bones.len() * BoneDesc::BYTES);
    // `check` borne le nombre à 128 : il tient dans un `u32`.
    out.extend_from_slice(&(bones.len() as u32).to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes());
    for bone in bones {
        out.extend_from_slice(&bone.name_hash.to_le_bytes());
        out.extend_from_slice(&bone.parent.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out.extend_from_slice(&bone.node.to_le_bytes());
        for value in bone.inverse_bind {
            out.extend_from_slice(&value.to_le_bytes());
        }
    }
    Ok(out)
}

/// Décode une section `SKEL` venue d'un fichier qu'on ne croit pas sur parole.
///
/// # Errors
///
/// [`A3dError::MalformedSection`] au premier écart : en-tête tronqué, plus de 128 os — vérifié
/// **avant** toute allocation (R-901) —, taille incohérente, parent hors du squelette ou
/// désignant l'os lui-même.
pub fn decode_bones(bytes: &[u8]) -> Result<Vec<BoneDesc>, A3dError> {
    if bytes.len() < HEADER_BYTES {
        return Err(malformed("en-tête tronqué"));
    }
    let count = u32::from_le_bytes(array(bytes, 0)) as usize;
    if count > limits::MAX_BONES {
        return Err(malformed("plus de 128 os (R-200)"));
    }
    if HEADER_BYTES + count * BoneDesc::BYTES != bytes.len() {
        return Err(malformed("taille incohérente avec le nombre d'os"));
    }
    let bones: Vec<BoneDesc> = (0..count)
        .map(|index| {
            let at = HEADER_BYTES + index * BoneDesc::BYTES;
            let mut inverse_bind = [0.0; 16];
            for (column, value) in inverse_bind.iter_mut().enumerate() {
                *value = f32::from_le_bytes(array(bytes, at + 16 + column * 4));
            }
            BoneDesc {
                name_hash: u64::from_le_bytes(array(bytes, at)),
                parent: u16::from_le_bytes(array(bytes, at + 8)),
                _pad: 0,
                node: u32::from_le_bytes(array(bytes, at + 12)),
                inverse_bind,
            }
        })
        .collect();
    check(&bones)?;
    Ok(bones)
}

/// Les contraintes de la section elle-même ; le node de chaque os se juge avec les nodes, à la
/// validation (C-22).
fn check(bones: &[BoneDesc]) -> Result<(), A3dError> {
    if bones.len() > limits::MAX_BONES {
        return Err(malformed("plus de 128 os (R-200)"));
    }
    for (index, bone) in bones.iter().enumerate() {
        if bone.parent != NONE_U16
            && (usize::from(bone.parent) >= bones.len() || usize::from(bone.parent) == index)
        {
            return Err(malformed("parent d'os hors du squelette"));
        }
    }
    Ok(())
}

fn malformed(detail: &'static str) -> A3dError {
    A3dError::MalformedSection {
        tag: SectionTag::SKEL,
        detail,
    }
}

fn array<const N: usize>(bytes: &[u8], at: usize) -> [u8; N] {
    let mut out = [0; N];
    out.copy_from_slice(&bytes[at..at + N]);
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use ax_model::dm::scene::name_hash;

    fn bone(name: &str, parent: u16, node: u32) -> BoneDesc {
        let mut inverse_bind = [0.0; 16];
        for (index, value) in inverse_bind.iter_mut().enumerate() {
            *value = index as f32 * 0.5;
        }
        BoneDesc {
            name_hash: name_hash(name),
            parent,
            _pad: 0,
            node,
            inverse_bind,
        }
    }

    #[test]
    fn le_squelette_fait_l_aller_retour() {
        let bones = vec![bone("epaule", NONE_U16, 1), bone("coude", 0, 2)];
        let bytes = encode_bones(&bones).expect("encodage");
        assert_eq!(bytes.len(), HEADER_BYTES + 2 * BoneDesc::BYTES);
        assert_eq!(decode_bones(&bytes).expect("décodage"), bones);
    }

    #[test]
    fn la_disposition_de_la_section_skel_est_figee() {
        // Octets écrits à la main, champ par champ : c'est la disposition d'ADR-155 D2 qui est
        // vérifiée, pas la cohérence de l'encodeur avec lui-même.
        let bones = vec![bone("a", NONE_U16, 7)];
        let mut attendu = Vec::new();
        attendu.extend_from_slice(&1u32.to_le_bytes());
        attendu.extend_from_slice(&0u32.to_le_bytes());
        attendu.extend_from_slice(&0xaf63_dc4c_8601_ec8c_u64.to_le_bytes());
        attendu.extend_from_slice(&u16::MAX.to_le_bytes());
        attendu.extend_from_slice(&0u16.to_le_bytes());
        attendu.extend_from_slice(&7u32.to_le_bytes());
        for index in 0..16 {
            attendu.extend_from_slice(&(index as f32 * 0.5).to_le_bytes());
        }
        assert_eq!(encode_bones(&bones).expect("encodage"), attendu);
    }

    #[test]
    fn un_squelette_mensonger_est_refuse_sans_paniquer() {
        let bones = vec![bone("epaule", NONE_U16, 1), bone("coude", 0, 2)];
        let bytes = encode_bones(&bones).expect("encodage");

        assert!(decode_bones(&bytes[..bytes.len() - 1]).is_err());
        assert!(decode_bones(&[1, 2, 3]).is_err());

        // Un dénombrement démesuré est refusé avant toute allocation.
        let mut enorme = bytes.clone();
        enorme[0..4].copy_from_slice(&u32::MAX.to_le_bytes());
        assert!(decode_bones(&enorme).is_err());

        // Un parent hors du squelette, ou l'os lui-même.
        let parent = HEADER_BYTES + BoneDesc::BYTES + 8;
        let mut hors = bytes.clone();
        hors[parent..parent + 2].copy_from_slice(&2u16.to_le_bytes());
        let refus = decode_bones(&hors).unwrap_err();
        assert_eq!(refus.code(), -3007);
        let mut lui_meme = bytes;
        lui_meme[parent..parent + 2].copy_from_slice(&1u16.to_le_bytes());
        assert!(decode_bones(&lui_meme).is_err());
    }

    #[test]
    fn plus_de_128_os_ne_s_ecrivent_pas() {
        let bones = vec![bone("os", NONE_U16, 0); limits::MAX_BONES + 1];
        assert!(encode_bones(&bones).is_err());
        assert!(encode_bones(&bones[..limits::MAX_BONES]).is_ok());
    }
}
