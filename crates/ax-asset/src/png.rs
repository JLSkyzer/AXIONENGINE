//! En-tête d'une image PNG, lu sans décoder l'image (R-532).
//!
//! L'importeur extrait les images sans les décoder : le décodage revient au
//! `ResourceManager`, côté Java. Il lui suffit de reconnaître un PNG et d'en
//! lire les dimensions, pour refuser au plus tôt ce que Java refuserait de toute
//! façon — un autre format (`E-3004`), une image de plus de 4096 pixels de côté
//! (`E-3006`).
//!
//! La spécification PNG du W3C fixe ce qu'il faut lire : la signature de huit
//! octets, puis le chunk `IHDR`, toujours le premier — longueur 13, type, puis
//! largeur et hauteur en grand-boutiste, toutes deux non nulles.

/// Signature de huit octets de tout fichier PNG.
pub const SIGNATURE: [u8; 8] = [0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A];

/// Longueur de la charge du chunk `IHDR`.
const IHDR_LENGTH: u32 = 13;

/// Octets lus : signature, longueur et type du chunk, largeur, hauteur.
const HEADER_BYTES: usize = 24;

/// Dimensions d'une image PNG, lues dans son `IHDR`.
///
/// Rend `None` si les octets ne commencent pas par la signature PNG suivie d'un
/// `IHDR` bien formé : longueur 13, dimensions non nulles. Le reste de l'image
/// n'est pas lu, son intégrité non plus : c'est l'affaire du décodeur.
#[must_use]
pub fn dimensions(bytes: &[u8]) -> Option<(u32, u32)> {
    if bytes.len() < HEADER_BYTES || bytes[..SIGNATURE.len()] != SIGNATURE {
        return None;
    }
    let big_endian =
        |at: usize| u32::from_be_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]]);
    if big_endian(8) != IHDR_LENGTH || &bytes[12..16] != b"IHDR" {
        return None;
    }
    let (width, height) = (big_endian(16), big_endian(20));
    if width == 0 || height == 0 {
        return None;
    }
    Some((width, height))
}

/// En-tête PNG minimal — signature et `IHDR` complet, sans données d'image —
/// pour les tests qui n'ont besoin que de dimensions lisibles.
#[cfg(test)]
pub(crate) fn header_for_tests(width: u32, height: u32) -> Vec<u8> {
    let mut out = SIGNATURE.to_vec();
    out.extend_from_slice(&IHDR_LENGTH.to_be_bytes());
    out.extend_from_slice(b"IHDR");
    out.extend_from_slice(&width.to_be_bytes());
    out.extend_from_slice(&height.to_be_bytes());
    // Profondeur 8, RGBA, compression, filtre, entrelacement ; puis un CRC que
    // personne ne vérifie ici.
    out.extend_from_slice(&[8, 6, 0, 0, 0]);
    out.extend_from_slice(&[0; 4]);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn t272_les_dimensions_viennent_de_l_ihdr() {
        assert_eq!(dimensions(&header_for_tests(256, 128)), Some((256, 128)));
        assert_eq!(dimensions(&header_for_tests(1, 4097)), Some((1, 4097)));
    }

    #[test]
    fn t272_ce_qui_n_est_pas_un_png_n_a_pas_de_dimensions() {
        let png = header_for_tests(16, 16);

        let mut jpeg = png.clone();
        jpeg[..3].copy_from_slice(&[0xFF, 0xD8, 0xFF]);
        assert_eq!(dimensions(&jpeg), None, "signature JPEG");

        assert_eq!(dimensions(&png[..23]), None, "en-tête tronqué");

        let mut longueur = png.clone();
        longueur[11] = 12;
        assert_eq!(dimensions(&longueur), None, "IHDR d'une autre longueur");

        let mut chunk = png.clone();
        chunk[12..16].copy_from_slice(b"IDAT");
        assert_eq!(dimensions(&chunk), None, "IHDR pas en premier");

        assert_eq!(dimensions(&header_for_tests(0, 16)), None, "largeur nulle");
        assert_eq!(dimensions(&header_for_tests(16, 0)), None, "hauteur nulle");
    }
}
