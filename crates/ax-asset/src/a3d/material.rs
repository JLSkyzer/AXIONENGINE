//! Section `MATL` : `MaterialDesc[]` (DM-05, C-26, ADR-122 §1).
//!
//! Disposition, en petit-boutiste :
//!
//! ```text
//! u32 material_count
//! u32 layout                       MATL_LAYOUT
//! u32 réservé (0)
//! u32 réservé (0)
//! MaterialDesc[material_count]     96 octets chacun (DM-05)
//! ```
//!
//! Le mot `layout` distingue cette disposition de toute autre. Une section
//! `MATL` d'une autre disposition — celle, provisoire, qu'écrivait le
//! compilateur avant ADR-122, dont le second mot est le rouge d'une couleur —
//! n'est pas une faute : elle est **ignorée**, dans l'esprit de R-880, et le
//! lecteur retombe sur le matériau par défaut. Un écart *dans* la disposition
//! connue, en revanche, refuse la section (`E-3007`).

use super::{A3dError, SectionTag};
use ax_model::dm::limits;
use ax_model::dm::material::{MaterialDesc, NO_TEXTURE};

/// Disposition de la section décrite ici.
pub const MATL_LAYOUT: u32 = 1;

/// En-tête : compte, disposition, deux mots réservés.
const HEADER_BYTES: usize = 16;

/// Octets lus avant de savoir à quelle disposition on a affaire.
const LAYOUT_PROBE_BYTES: usize = 8;

/// Contenu d'une section `MATL`.
#[derive(Debug, Clone, PartialEq)]
pub enum DecodedMaterials {
    /// Disposition connue : les matériaux, contrôlés un à un.
    Materials(Vec<MaterialDesc>),
    /// Disposition inconnue, rendue telle qu'annoncée : la section est ignorée,
    /// le matériau par défaut s'applique.
    UnknownLayout(u32),
}

/// Encode une section `MATL`.
///
/// Le validateur (C-22) a borné le compte à 256 ; l'encodeur ne fait que
/// sérialiser.
///
/// # Errors
///
/// [`A3dError::SectionUnwritable`] si le compte dépasse ce qu'un `u32` décrit.
pub fn encode_materials(materials: &[MaterialDesc]) -> Result<Vec<u8>, A3dError> {
    let count = u32::try_from(materials.len()).map_err(|_| unwritable())?;
    let mut out = Vec::with_capacity(HEADER_BYTES + materials.len() * MaterialDesc::BYTES);
    for word in [count, MATL_LAYOUT, 0, 0] {
        out.extend_from_slice(&word.to_le_bytes());
    }
    for material in materials {
        material.write_le(&mut out);
    }
    Ok(out)
}

/// Décode une section `MATL` venue d'un fichier qu'on ne croit pas sur parole.
///
/// # Errors
///
/// [`A3dError::MalformedSection`] au premier écart dans la disposition connue :
/// en-tête tronqué, plus de matériaux que C-22 n'en admet — vérifié **avant**
/// toute allocation (R-901) —, réserve non nulle, taille incohérente avec le
/// compte, matériau dont une énumération, un drapeau ou un facteur est invalide.
/// Une autre disposition n'est pas une erreur : voir
/// [`DecodedMaterials::UnknownLayout`].
pub fn decode_materials(bytes: &[u8]) -> Result<DecodedMaterials, A3dError> {
    if bytes.len() < LAYOUT_PROBE_BYTES {
        return Err(malformed("en-tête tronqué"));
    }
    let layout = read_u32(bytes, 4);
    if layout != MATL_LAYOUT {
        return Ok(DecodedMaterials::UnknownLayout(layout));
    }
    if bytes.len() < HEADER_BYTES {
        return Err(malformed("en-tête tronqué"));
    }
    let count = read_u32(bytes, 0) as usize;
    if count > limits::MAX_MATERIALS {
        return Err(malformed("plus de matériaux que C-22 n'en admet"));
    }
    if read_u32(bytes, 8) != 0 || read_u32(bytes, 12) != 0 {
        return Err(malformed("réserve d'en-tête non nulle"));
    }
    // Le compte est borné : le produit ne peut pas déborder.
    if bytes.len() != HEADER_BYTES + count * MaterialDesc::BYTES {
        return Err(malformed("taille incohérente avec le compte"));
    }

    let mut materials = Vec::with_capacity(count);
    for index in 0..count {
        let material =
            MaterialDesc::read_le(chunk(bytes, HEADER_BYTES + index * MaterialDesc::BYTES));
        material.check().map_err(malformed)?;
        materials.push(material);
    }
    Ok(DecodedMaterials::Materials(materials))
}

/// Vérifie que chaque slot de texture désigne une entrée de `TEXR`, ou est vide.
///
/// Contrôle croisé des deux sections, que ni l'un ni l'autre de leurs lecteurs
/// ne peut faire seul : sans lui, un slot désignerait une texture absente.
///
/// # Errors
///
/// [`A3dError::MalformedSection`] (`MATL`) au premier slot hors de la table.
pub fn check_texture_slots(
    materials: &[MaterialDesc],
    texture_count: usize,
) -> Result<(), A3dError> {
    let outside = |slot: u16| slot != NO_TEXTURE && usize::from(slot) >= texture_count;
    if materials
        .iter()
        .any(|material| material.texture_slots().into_iter().any(outside))
    {
        return Err(malformed("slot de texture hors de la table TEXR"));
    }
    Ok(())
}

fn malformed(detail: &'static str) -> A3dError {
    A3dError::MalformedSection {
        tag: SectionTag::MATL,
        detail,
    }
}

fn unwritable() -> A3dError {
    A3dError::SectionUnwritable(SectionTag::MATL)
}

fn read_u32(bytes: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(*chunk(bytes, at))
}

/// Tranche de taille fixe ; `at + N` est dans les bornes, l'appelant l'a vérifié.
fn chunk<const N: usize>(bytes: &[u8], at: usize) -> &[u8; N] {
    bytes[at..at + N]
        .try_into()
        .expect("borne vérifiée par l'appelant")
}

#[cfg(test)]
mod tests {
    use super::*;
    use ax_model::dm::material::{
        blend_mode, cull_mode, material_flags, shading_model, NO_WEAR_PROFILE,
    };

    fn materiau(name_hash: u64, albedo_tex: u16) -> MaterialDesc {
        MaterialDesc {
            name_hash,
            albedo_tex,
            normal_tex: NO_TEXTURE,
            orm_tex: NO_TEXTURE,
            emissive_tex: NO_TEXTURE,
            height_tex: NO_TEXTURE,
            damage_tex: NO_TEXTURE,
            albedo_factor: [0.8, 0.1, 0.1, 1.0],
            emissive_factor: [0.0; 3],
            metallic: 1.0,
            roughness: 0.4,
            occlusion_strength: 1.0,
            normal_scale: 1.0,
            alpha_cutoff: 0.5,
            parallax_scale: 0.0,
            clearcoat: 0.0,
            clearcoat_roughness: 0.0,
            sheen: 0.0,
            anisotropy: 0.0,
            blend_mode: blend_mode::OPAQUE,
            cull_mode: cull_mode::BACK,
            shading_model: shading_model::PBR,
            _pad: 0,
            flags: material_flags::VERTEX_COLOR,
            wear_profile: NO_WEAR_PROFILE,
        }
    }

    fn est_refusee(bytes: &[u8]) -> bool {
        matches!(
            decode_materials(bytes),
            Err(A3dError::MalformedSection {
                tag: SectionTag::MATL,
                ..
            })
        )
    }

    #[test]
    fn t270_matl_fait_l_aller_retour() {
        let materiaux = vec![materiau(1, 0), materiau(2, NO_TEXTURE)];
        let octets = encode_materials(&materiaux).expect("encodage");
        assert_eq!(
            decode_materials(&octets),
            Ok(DecodedMaterials::Materials(materiaux))
        );
    }

    #[test]
    fn t270_l_en_tete_de_matl_est_fige() {
        let octets = encode_materials(&[materiau(7, 3)]).expect("encodage");
        assert_eq!(octets.len(), 16 + 96);
        let u32_at = |at: usize| u32::from_le_bytes(octets[at..at + 4].try_into().unwrap());
        assert_eq!(u32_at(0), 1, "material_count");
        assert_eq!(u32_at(4), MATL_LAYOUT, "layout");
        assert_eq!(u32_at(8), 0, "réservé");
        assert_eq!(u32_at(12), 0, "réservé");
        // Le premier matériau suit l'en-tête : son empreinte de nom, puis l'albedo.
        assert_eq!(u64::from_le_bytes(octets[16..24].try_into().unwrap()), 7);
        assert_eq!(u16::from_le_bytes([octets[24], octets[25]]), 3);
    }

    #[test]
    fn t270_un_matl_d_une_autre_disposition_est_ignore() {
        // L'ancien MATL provisoire : un compte, puis par matériau quatre flottants
        // de couleur et une chaîne de chemin. Son second mot est le rouge.
        let mut provisoire = Vec::new();
        provisoire.extend_from_slice(&1u32.to_le_bytes());
        for value in [1.0f32, 0.5, 0.25, 1.0] {
            provisoire.extend_from_slice(&value.to_le_bytes());
        }
        provisoire.extend_from_slice(&0u32.to_le_bytes());
        assert_eq!(
            decode_materials(&provisoire),
            Ok(DecodedMaterials::UnknownLayout(1.0f32.to_bits()))
        );

        let mut future = encode_materials(&[materiau(1, 0)]).expect("encodage");
        future[4..8].copy_from_slice(&2u32.to_le_bytes());
        assert_eq!(
            decode_materials(&future),
            Ok(DecodedMaterials::UnknownLayout(2))
        );
    }

    #[test]
    fn t270_un_ecart_dans_la_disposition_connue_refuse_la_section() {
        let bon = encode_materials(&[materiau(1, 0)]).expect("encodage");

        assert!(
            est_refusee(&bon[..7]),
            "trop court pour lire la disposition"
        );
        assert!(est_refusee(&bon[..12]), "en-tête tronqué");

        let mut trop = bon.clone();
        trop[0..4].copy_from_slice(&257u32.to_le_bytes());
        assert!(est_refusee(&trop), "plus de 256 matériaux");

        let mut reserve = bon.clone();
        reserve[12] = 1;
        assert!(est_refusee(&reserve), "réserve non nulle");

        let mut long = bon.clone();
        long.push(0);
        assert!(est_refusee(&long), "taille incohérente");

        let mut enumeration = bon.clone();
        enumeration[16 + 88] = 9;
        assert!(est_refusee(&enumeration), "mode de mélange inconnu");

        let mut nan = bon.clone();
        nan[16 + 52..16 + 56].copy_from_slice(&f32::NAN.to_le_bytes());
        assert!(est_refusee(&nan), "rugosité non finie");
    }

    #[test]
    fn t270_les_slots_designent_la_table_des_textures() {
        assert!(check_texture_slots(&[materiau(1, 0)], 1).is_ok());
        assert!(check_texture_slots(&[materiau(1, NO_TEXTURE)], 0).is_ok());
        assert!(check_texture_slots(&[materiau(1, 1)], 1).is_err());

        let mut emissive = materiau(1, NO_TEXTURE);
        emissive.emissive_tex = 4;
        assert!(check_texture_slots(&[emissive], 4).is_err());
        assert!(check_texture_slots(&[emissive], 5).is_ok());
    }
}
