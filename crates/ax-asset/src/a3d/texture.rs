//! Section `TEXR` : textures et leurs données (C-26, ADR-122 §2).
//!
//! Disposition, en petit-boutiste :
//!
//! ```text
//! u32 texture_count
//! u32 layout                       TEXR_LAYOUT
//! u32 blob_size
//! u32 réservé (0)
//! TextureDesc[texture_count]       16 octets chacun
//! u8 blob[blob_size]               octets PNG embarqués et chemins relatifs (UTF-8)
//! ```
//!
//! Une entrée est un couple (image, échantillonneur) : deux entrées qui partagent
//! une image partagent ses octets. Les images ne sont **pas décodées** (R-532) :
//! le lecteur vérifie seulement qu'une entrée `EMBEDDED` commence par un PNG dont
//! l'`IHDR` annonce les dimensions de l'entrée, et qu'une entrée `RESOURCE` est
//! un chemin relatif qu'admet R-531.
//!
//! Même règle de disposition que `MATL` : une autre disposition est ignorée, un
//! écart dans celle-ci refuse la section (`E-3007`).

use super::{A3dError, SectionTag};
use crate::import::check_relative_path;
use crate::png;
use ax_model::dm::limits;
use ax_model::dm::material::{texture_source, TextureDesc};

/// Disposition de la section décrite ici.
pub const TEXR_LAYOUT: u32 = 1;

/// En-tête : compte, disposition, taille des données, mot réservé.
const HEADER_BYTES: usize = 16;

/// Octets lus avant de savoir à quelle disposition on a affaire.
const LAYOUT_PROBE_BYTES: usize = 8;

/// Textures d'un asset et zone de données qu'elles désignent.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct TextureTable {
    /// Une entrée par couple (image, échantillonneur).
    pub entries: Vec<TextureDesc>,
    /// Octets PNG et chemins relatifs, désignés par les entrées.
    pub blob: Vec<u8>,
}

impl TextureTable {
    /// Octets d'une entrée — PNG (`EMBEDDED`) ou chemin UTF-8 (`RESOURCE`) — ou
    /// `None` si l'entrée n'existe pas ou désigne hors de la zone de données.
    #[must_use]
    pub fn data(&self, index: usize) -> Option<&[u8]> {
        let entry = self.entries.get(index)?;
        let start = entry.data_offset as usize;
        let end = start.checked_add(entry.data_size as usize)?;
        self.blob.get(start..end)
    }

    /// Chemin relatif d'une entrée `RESOURCE`, ou `None` pour une autre entrée.
    #[must_use]
    pub fn resource_path(&self, index: usize) -> Option<&str> {
        if self.entries.get(index)?.source != texture_source::RESOURCE {
            return None;
        }
        core::str::from_utf8(self.data(index)?).ok()
    }
}

/// Contenu d'une section `TEXR`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DecodedTextures {
    /// Disposition connue : la table, contrôlée entrée par entrée.
    Textures(TextureTable),
    /// Disposition inconnue, rendue telle qu'annoncée : la section est ignorée,
    /// les textures neutres s'appliquent.
    UnknownLayout(u32),
}

/// Encode une section `TEXR`.
///
/// Le validateur (C-22) a borné le compte à 128 ; l'encodeur ne fait que
/// sérialiser.
///
/// # Errors
///
/// [`A3dError::SectionUnwritable`] si le compte ou la zone de données dépasse ce
/// qu'un `u32` décrit.
pub fn encode_textures(table: &TextureTable) -> Result<Vec<u8>, A3dError> {
    let count = u32::try_from(table.entries.len()).map_err(|_| unwritable())?;
    let blob_size = u32::try_from(table.blob.len()).map_err(|_| unwritable())?;
    let mut out = Vec::with_capacity(
        HEADER_BYTES + table.entries.len() * TextureDesc::BYTES + table.blob.len(),
    );
    for word in [count, TEXR_LAYOUT, blob_size, 0] {
        out.extend_from_slice(&word.to_le_bytes());
    }
    for entry in &table.entries {
        entry.write_le(&mut out);
    }
    out.extend_from_slice(&table.blob);
    Ok(out)
}

/// Décode une section `TEXR` venue d'un fichier qu'on ne croit pas sur parole.
///
/// # Errors
///
/// [`A3dError::MalformedSection`] au premier écart dans la disposition connue :
/// en-tête tronqué, plus de textures que C-22 n'en admet — vérifié **avant**
/// toute allocation (R-901) —, réserve non nulle, taille incohérente, entrée
/// invalide, données hors de la zone, texture embarquée qui n'est pas le PNG
/// annoncé, chemin de ressource absolu, remontant ou non UTF-8. Une autre
/// disposition n'est pas une erreur : voir [`DecodedTextures::UnknownLayout`].
pub fn decode_textures(bytes: &[u8]) -> Result<DecodedTextures, A3dError> {
    if bytes.len() < LAYOUT_PROBE_BYTES {
        return Err(malformed("en-tête tronqué"));
    }
    let layout = read_u32(bytes, 4);
    if layout != TEXR_LAYOUT {
        return Ok(DecodedTextures::UnknownLayout(layout));
    }
    if bytes.len() < HEADER_BYTES {
        return Err(malformed("en-tête tronqué"));
    }
    let count = read_u32(bytes, 0) as usize;
    if count > limits::MAX_TEXTURES {
        return Err(malformed("plus de textures que C-22 n'en admet"));
    }
    if read_u32(bytes, 12) != 0 {
        return Err(malformed("réserve d'en-tête non nulle"));
    }
    // Le compte est borné : la fin des entrées ne peut pas déborder.
    let blob_start = HEADER_BYTES + count * TextureDesc::BYTES;
    let blob_size = read_u32(bytes, 8) as usize;
    if blob_start.checked_add(blob_size) != Some(bytes.len()) {
        return Err(malformed(
            "taille incohérente avec le compte et les données",
        ));
    }

    let table = TextureTable {
        entries: (0..count)
            .map(|index| {
                TextureDesc::read_le(chunk(bytes, HEADER_BYTES + index * TextureDesc::BYTES))
            })
            .collect(),
        blob: bytes[blob_start..].to_vec(),
    };
    for index in 0..count {
        check_entry(&table, index)?;
    }
    Ok(DecodedTextures::Textures(table))
}

/// Contrôle une entrée : ses champs, sa plage de données, puis ce que la plage
/// contient selon la provenance.
fn check_entry(table: &TextureTable, index: usize) -> Result<(), A3dError> {
    let entry = &table.entries[index];
    entry.check().map_err(malformed)?;
    let data = table
        .data(index)
        .ok_or_else(|| malformed("données d'une texture hors de la zone"))?;
    if entry.source == texture_source::EMBEDDED {
        let announced = (u32::from(entry.width), u32::from(entry.height));
        if png::dimensions(data) != Some(announced) {
            return Err(malformed("texture embarquée qui n'est pas le PNG annoncé"));
        }
    } else {
        // `check` a admis la provenance : c'est une ressource.
        let path = core::str::from_utf8(data)
            .map_err(|_| malformed("chemin de texture qui n'est pas de l'UTF-8"))?;
        check_relative_path(path).map_err(|_| malformed("chemin de texture refusé (R-531)"))?;
    }
    Ok(())
}

fn malformed(detail: &'static str) -> A3dError {
    A3dError::MalformedSection {
        tag: SectionTag::TEXR,
        detail,
    }
}

fn unwritable() -> A3dError {
    A3dError::SectionUnwritable(SectionTag::TEXR)
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
    use ax_model::dm::material::{texture_format, texture_sampler};

    const CHEMIN: &[u8] = b"tex/caisse.png";

    fn entree(source: u8, sampler: u8, size: (u16, u16), data: (u32, u32)) -> TextureDesc {
        TextureDesc {
            source,
            format: texture_format::PNG,
            sampler,
            _pad: 0,
            width: size.0,
            height: size.1,
            data_offset: data.0,
            data_size: data.1,
        }
    }

    /// Trois entrées : un PNG embarqué, le même PNG avec un autre
    /// échantillonneur — octets partagés —, et une ressource.
    fn table() -> TextureTable {
        let png = png::header_for_tests(64, 32);
        let png_len = png.len() as u32;
        let mut blob = png;
        blob.extend_from_slice(CHEMIN);
        TextureTable {
            entries: vec![
                entree(
                    texture_source::EMBEDDED,
                    texture_sampler::FILTER_NEAREST,
                    (64, 32),
                    (0, png_len),
                ),
                entree(
                    texture_source::EMBEDDED,
                    texture_sampler::FILTER_LINEAR | texture_sampler::CLAMP_U,
                    (64, 32),
                    (0, png_len),
                ),
                entree(
                    texture_source::RESOURCE,
                    texture_sampler::FILTER_UNDECLARED,
                    (0, 0),
                    (png_len, CHEMIN.len() as u32),
                ),
            ],
            blob,
        }
    }

    fn decodee(bytes: &[u8]) -> TextureTable {
        match decode_textures(bytes).expect("décodage") {
            DecodedTextures::Textures(table) => table,
            autre => panic!("table attendue, obtenu {autre:?}"),
        }
    }

    fn est_refusee(bytes: &[u8]) -> bool {
        matches!(
            decode_textures(bytes),
            Err(A3dError::MalformedSection {
                tag: SectionTag::TEXR,
                ..
            })
        )
    }

    /// La table encodée, son entrée `index` remplacée.
    fn avec_entree(index: usize, entry: TextureDesc) -> Vec<u8> {
        let mut table = table();
        table.entries[index] = entry;
        encode_textures(&table).expect("encodage")
    }

    #[test]
    fn t270_texr_fait_l_aller_retour() {
        let table = table();
        let relue = decodee(&encode_textures(&table).expect("encodage"));
        assert_eq!(relue, table);
        assert_eq!(relue.data(0), relue.data(1), "octets partagés");
        assert_eq!(relue.resource_path(2), Some("tex/caisse.png"));
        assert_eq!(
            relue.resource_path(0),
            None,
            "une entrée embarquée n'a pas de chemin"
        );
        assert_eq!(relue.data(3), None, "entrée inexistante");
    }

    #[test]
    fn t270_l_en_tete_de_texr_est_fige() {
        let octets = encode_textures(&table()).expect("encodage");
        let u32_at = |at: usize| u32::from_le_bytes(octets[at..at + 4].try_into().unwrap());
        let blob_size = table().blob.len();
        assert_eq!(u32_at(0), 3, "texture_count");
        assert_eq!(u32_at(4), TEXR_LAYOUT, "layout");
        assert_eq!(u32_at(8) as usize, blob_size, "blob_size");
        assert_eq!(u32_at(12), 0, "réservé");
        assert_eq!(octets.len(), 16 + 3 * 16 + blob_size);
        // La zone de données suit les entrées ; elle commence par la signature PNG.
        assert_eq!(octets[64..72], png::SIGNATURE);
    }

    #[test]
    fn t270_un_texr_d_une_autre_disposition_est_ignore() {
        let mut autre = encode_textures(&table()).expect("encodage");
        autre[4..8].copy_from_slice(&2u32.to_le_bytes());
        assert_eq!(
            decode_textures(&autre),
            Ok(DecodedTextures::UnknownLayout(2))
        );
    }

    #[test]
    fn t270_un_ecart_dans_la_disposition_connue_refuse_la_section() {
        let bon = encode_textures(&table()).expect("encodage");

        assert!(
            est_refusee(&bon[..7]),
            "trop court pour lire la disposition"
        );
        assert!(est_refusee(&bon[..12]), "en-tête tronqué");

        let mut trop = bon.clone();
        trop[0..4].copy_from_slice(&129u32.to_le_bytes());
        assert!(est_refusee(&trop), "plus de 128 textures");

        let mut reserve = bon.clone();
        reserve[12] = 1;
        assert!(est_refusee(&reserve), "réserve non nulle");

        let mut long = bon.clone();
        long.push(0);
        assert!(est_refusee(&long), "taille incohérente");

        let ressource = table().entries[2];
        assert!(
            est_refusee(&avec_entree(
                2,
                TextureDesc {
                    data_size: 10_000,
                    ..ressource
                }
            )),
            "données hors de la zone"
        );
        assert!(
            est_refusee(&avec_entree(
                2,
                TextureDesc {
                    format: 2,
                    ..ressource
                }
            )),
            "entrée invalide"
        );
    }

    #[test]
    fn t272_une_texture_embarquee_doit_etre_le_png_annonce() {
        let embarquee = table().entries[0];
        assert!(
            est_refusee(&avec_entree(
                0,
                TextureDesc {
                    width: 65,
                    ..embarquee
                }
            )),
            "dimensions annoncées qui ne sont pas celles de l'IHDR"
        );

        // Le chemin de la ressource n'est pas un PNG.
        let ressource = table().entries[2];
        assert!(est_refusee(&avec_entree(
            0,
            TextureDesc {
                data_offset: ressource.data_offset,
                data_size: ressource.data_size,
                ..embarquee
            }
        )));
    }

    #[test]
    fn t270_un_chemin_de_ressource_suit_r531() {
        for chemin in [
            &b"/tex.png"[..],
            b"../tex.png",
            b"a/../../tex.png",
            b"tex\\caisse.png",
            b"C:/tex.png",
            b"",
            &[0xFF, 0xFE][..],
        ] {
            let png = png::header_for_tests(4, 4);
            let mut blob = png.clone();
            blob.extend_from_slice(chemin);
            let table = TextureTable {
                entries: vec![entree(
                    texture_source::RESOURCE,
                    texture_sampler::FILTER_UNDECLARED,
                    (0, 0),
                    (png.len() as u32, chemin.len() as u32),
                )],
                blob,
            };
            assert!(
                est_refusee(&encode_textures(&table).expect("encodage")),
                "chemin admis à tort : {chemin:?}"
            );
        }
    }
}
