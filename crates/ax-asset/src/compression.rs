//! La compression zstd du natif, hors des conteneurs A3D : les charges utiles réseau (C-51,
//! ADR-150 D5).
//!
//! Même libzstd et même tas compté (INV-08) que les sections compressées du conteneur (C-24) : le
//! natif n'en lie qu'une. Ce qui vient du réseau est hostile : [`decompress_bounded`] lit la taille
//! que la trame déclare et refuse au-delà de la borne de l'appelant **avant** d'allouer (R-1691),
//! puis exige que le contenu fasse exactement cette taille.

/// Niveau de compression des charges utiles réseau : le défaut de zstd. Elles partent une fois par
/// connexion, un niveau plus haut ne paierait pas son temps.
pub const NETWORK_LEVEL: i32 = 3;

/// Compresse en une trame zstd, de taille de contenu déclarée.
///
/// `None` si libzstd refuse : niveau hors de sa plage, mémoire refusée.
#[must_use]
pub fn compress(payload: &[u8], level: i32) -> Option<Vec<u8>> {
    crate::foreign_heap::compress(payload, level)
}

/// Décompresse une trame zstd venue d'ailleurs, sans jamais dépasser `max` octets.
///
/// `None` si ce n'est pas une trame zstd, si elle ne déclare pas sa taille, si la taille déclarée
/// dépasse `max`, ou si le contenu réel ne fait pas exactement la taille déclarée — une trame qui
/// ment sur sa taille est refusée, pas tronquée.
#[must_use]
pub fn decompress_bounded(frame: &[u8], max: usize) -> Option<Vec<u8>> {
    let declared = crate::foreign_heap::frame_content_size(frame)?;
    let size = usize::try_from(declared).ok().filter(|size| *size <= max)?;
    // libzstd refuse lui-même une trame dont le contenu ne fait pas la taille qu'elle déclare
    // (`une_trame_qui_ment_sur_sa_taille_est_refusee`).
    crate::foreign_heap::decompress(frame, size)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn une_trame_compressee_se_relit_a_l_identique() {
        for payload in [
            Vec::new(),
            b"axion".to_vec(),
            (0..200_000u32)
                .flat_map(|i| (i % 251).to_le_bytes())
                .collect::<Vec<u8>>(),
        ] {
            let frame = compress(&payload, NETWORK_LEVEL).expect("compression");
            assert_eq!(
                decompress_bounded(&frame, payload.len()),
                Some(payload.clone())
            );
        }
    }

    #[test]
    fn une_trame_plus_grande_que_la_borne_est_refusee() {
        let payload = vec![7u8; 4096];
        let frame = compress(&payload, NETWORK_LEVEL).expect("compression");
        assert_eq!(decompress_bounded(&frame, 4095), None);
        assert_eq!(decompress_bounded(&frame, 4096), Some(payload));
    }

    #[test]
    fn ce_qui_n_est_pas_une_trame_est_refuse() {
        assert_eq!(decompress_bounded(&[], 1024), None);
        assert_eq!(decompress_bounded(b"pas une trame zstd", 1024), None);
        let frame = compress(&[1, 2, 3, 4], NETWORK_LEVEL).expect("compression");
        assert_eq!(
            decompress_bounded(&frame[..frame.len() - 1], 1024),
            None,
            "trame tronquée"
        );
    }

    #[test]
    fn une_trame_qui_ment_sur_sa_taille_est_refusee() {
        // Trame à segment unique : la taille déclarée tient sur l'octet qui suit le magic et le
        // descripteur. libzstd compare la taille décodée à celle-ci, et refuse un écart.
        let mut menteuse = compress(&[5u8; 64], NETWORK_LEVEL).expect("compression");
        assert_eq!(menteuse[5], 64, "position de la taille déclarée");
        menteuse[5] = 65;
        assert_eq!(
            decompress_bounded(&menteuse, 1024),
            None,
            "annonce plus que son contenu"
        );
        menteuse[5] = 63;
        assert_eq!(
            decompress_bounded(&menteuse, 1024),
            None,
            "annonce moins que son contenu"
        );
    }

    #[test]
    fn une_borne_illimitee_n_alloue_rien_pour_une_trame_sans_taille_ou_invalide() {
        // Une trame valide qui ne déclare pas sa taille : magic, descripteur nul (ni segment unique
        // ni taille), fenêtre minimale, puis un dernier bloc brut vide.
        let sans_taille = [0x28, 0xB5, 0x2F, 0xFD, 0x00, 0x00, 0x01, 0x00, 0x00];
        assert_eq!(decompress_bounded(&sans_taille, usize::MAX), None);
        assert_eq!(decompress_bounded(b"pas une trame zstd", usize::MAX), None);
    }

    #[test]
    fn deux_trames_a_la_suite_depassent_la_taille_declaree_et_sont_refusees() {
        // La taille lue est celle de la première trame ; libzstd décompresse toutes les trames
        // concaténées : le contenu dépasse alors ce qui a été alloué, et la trame est refusée.
        let mut frames = compress(&[1u8; 64], NETWORK_LEVEL).expect("compression");
        frames.extend(compress(&[2u8; 64], NETWORK_LEVEL).expect("compression"));
        assert_eq!(decompress_bounded(&frames, 1 << 20), None);
    }
}
