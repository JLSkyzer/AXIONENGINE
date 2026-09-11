//! Empreinte déterministe de champ (C-16, `digest64`).

/// Graine de l'empreinte de champ.
///
/// Fixée, et faisant partie du contrat : deux extrémités qui emploieraient des
/// graines différentes ne se compareraient jamais égales, et chaque tick
/// conclurait à une divergence.
pub const DIGEST_SEED: u64 = 0;

/// Empreinte 64 bits d'une suite d'octets.
///
/// XXH64, dont l'implémentation est **figée** : elle entre dans la vérification
/// périodique de reconstruction (5.12bis), et une empreinte qui changerait
/// ferait diverger toutes les assemblies déjà répliquées — sans qu'aucune
/// d'elles n'ait changé.
///
/// Deux garde-fous tiennent cette promesse. La version de la bibliothèque est
/// épinglée au correctif près, et un vecteur officiel de XXH64 est vérifié par
/// les tests : si l'implémentation cessait d'être XXH64, on le saurait avant
/// qu'un joueur ne le découvre.
#[must_use]
pub fn digest64(bytes: &[u8]) -> u64 {
    xxhash_rust::xxh64::xxh64(bytes, DIGEST_SEED)
}

/// Empreinte d'une suite de valeurs `i8`, telle qu'un champ quantifié en porte.
///
/// Elle passe par les octets, sans conversion : la représentation d'un `i8` est
/// la même partout, et le complément à deux est imposé par le langage.
#[must_use]
pub fn digest_field(values: &[i8]) -> u64 {
    // `i8` et `u8` ont la même taille et le même alignement ; seule leur
    // interprétation diffère, et l'empreinte ne les interprète pas.
    let bytes: Vec<u8> = values.iter().map(|value| *value as u8).collect();
    digest64(&bytes)
}

/// Mélange deux valeurs 64 bits en une.
///
/// Sert à dériver une graine de plusieurs identifiants (R-512). Le mélange
/// passe par l'empreinte plutôt que par un `ou exclusif` : deux identifiants
/// échangés donneraient sinon la même graine, et deux impacts symétriques
/// tireraient la même suite.
#[must_use]
pub fn mix64(first: u64, second: u64) -> u64 {
    let mut bytes = [0u8; 16];
    bytes[0..8].copy_from_slice(&first.to_le_bytes());
    bytes[8..16].copy_from_slice(&second.to_le_bytes());
    digest64(&bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn t820_l_empreinte_est_bien_xxh64() {
        // Vecteur officiel de XXH64 : chaîne vide, graine nulle. C'est le seul
        // point d'ancrage extérieur du noyau — tout le reste est figé par les
        // vecteurs d'or, qui ne diraient pas si l'algorithme avait changé de
        // nature.
        assert_eq!(digest64(&[]), 0xEF46_DB37_51D8_E999);
    }

    #[test]
    fn t820_l_empreinte_depend_de_chaque_octet() {
        let reference = digest64(b"champ de deformation");
        assert_ne!(reference, digest64(b"champ de deformatioN"));
        assert_ne!(reference, digest64(b"champ de deformation "));
        assert_ne!(reference, digest64(b"hamp de deformation"));
    }

    #[test]
    fn t820_l_empreinte_est_stable_d_un_appel_a_l_autre() {
        let data: Vec<u8> = (0..1024).map(|index| (index % 251) as u8).collect();
        let first = digest64(&data);
        for _ in 0..100 {
            assert_eq!(digest64(&data), first);
        }
    }

    #[test]
    fn t820_l_empreinte_d_un_champ_suit_ses_valeurs() {
        let field: Vec<i8> = (-100..100).map(|value| value as i8).collect();
        let reference = digest_field(&field);

        let mut modified = field.clone();
        modified[50] = modified[50].wrapping_add(1);
        assert_ne!(reference, digest_field(&modified), "un pas modifié passe");

        // Un champ vide a une empreinte, et c'est celle de la suite vide.
        assert_eq!(digest_field(&[]), digest64(&[]));
    }

    #[test]
    fn t820_l_empreinte_d_un_champ_distingue_le_signe() {
        // Le complément à deux : -1 et 255 ont les mêmes bits, ce qui est
        // voulu ; mais 1 et -1 n'en ont pas.
        assert_ne!(digest_field(&[1]), digest_field(&[-1]));
    }

    #[test]
    fn t820_le_melange_n_est_pas_commutatif() {
        // Deux identifiants échangés donneraient la même graine avec un ou
        // exclusif, et deux impacts symétriques tireraient la même suite.
        assert_ne!(mix64(1, 2), mix64(2, 1));
        assert_ne!(mix64(0, 0), 0);
        assert_ne!(mix64(u64::MAX, 0), mix64(0, u64::MAX));
    }

    #[test]
    fn t820_le_melange_est_stable() {
        assert_eq!(
            mix64(0x0102_0304_0506_0708, 42),
            mix64(0x0102_0304_0506_0708, 42)
        );
    }
}
