//! Lecture d'un fichier A3D (C-24, PARTIE 7.5).
//!
//! Tout ce que ce module lit vient d'un fichier : d'un cache que quelqu'un a pu
//! altérer, d'un disque qui a pu se corrompre, ou d'un resource pack qu'on n'a
//! pas écrit. Rien n'y est cru sur parole. Chaque taille annoncée est comparée
//! à la taille réelle **avant** toute allocation (R-900, R-901), et chaque
//! charge utile à son CRC avant d'être rendue (R-882).

use super::{
    header_checksum, A3dError, A3dHeader, SectionEntry, SectionMask, SectionTag, HEADER_BYTES,
    MAGIC, SECTION_ENTRY_BYTES, VERSION_MAJOR,
};

/// Plafonds appliqués à la lecture (R-901).
///
/// Ils sont **donnés**, jamais codés en dur : l'appelant les tire de la
/// configuration — `assets.max_compiled_bytes` — dont le registre est la source
/// unique. Écrire une valeur par défaut ici en créerait une seconde, qui
/// divergerait à la première modification.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct A3dLimits {
    max_bytes: u64,
}

impl A3dLimits {
    /// Fixe le plafond, en octets, d'un fichier comme d'une section.
    #[must_use]
    pub const fn new(max_bytes: u64) -> Self {
        Self { max_bytes }
    }

    /// Plafond en vigueur, en octets.
    #[must_use]
    pub const fn max_bytes(&self) -> u64 {
        self.max_bytes
    }
}

/// Fichier A3D ouvert, sans copie.
///
/// L'ouverture valide l'en-tête et la table des sections ; les charges utiles
/// ne sont ni vérifiées ni décompressées avant qu'on les demande. C'est
/// l'objectif 7.1 : une validation bon marché avant lecture, puis un chargement
/// partiel par masque de sections.
#[derive(Debug)]
pub struct A3dFile<'a> {
    bytes: &'a [u8],
    header: A3dHeader,
    entries: Vec<SectionEntry>,
}

impl<'a> A3dFile<'a> {
    /// Ouvre un fichier et valide sa structure.
    ///
    /// # Errors
    ///
    /// [`A3dError::NotAnA3dFile`], [`A3dError::Truncated`],
    /// [`A3dError::HeaderCorrupted`], [`A3dError::UnsupportedMajor`],
    /// [`A3dError::SectionOutOfBounds`], [`A3dError::SectionTooLarge`] ou
    /// [`A3dError::DuplicateSection`] selon ce qui cloche.
    pub fn open(bytes: &'a [u8], limits: A3dLimits) -> Result<Self, A3dError> {
        if bytes.len() < HEADER_BYTES || bytes[0..4] != MAGIC {
            return Err(A3dError::NotAnA3dFile);
        }

        let mut raw = [0u8; HEADER_BYTES];
        raw.copy_from_slice(&bytes[0..HEADER_BYTES]);

        let announced_crc = u32::from_le_bytes([raw[36], raw[37], raw[38], raw[39]]);
        if header_checksum(&raw) != announced_crc {
            return Err(A3dError::HeaderCorrupted);
        }

        let header = A3dHeader {
            version_major: u16::from_le_bytes([raw[4], raw[5]]),
            version_minor: u16::from_le_bytes([raw[6], raw[7]]),
            flags: u32::from_le_bytes([raw[8], raw[9], raw[10], raw[11]]),
            section_count: u32::from_le_bytes([raw[12], raw[13], raw[14], raw[15]]),
            asset_id: u64::from_le_bytes(raw[16..24].try_into().expect("huit octets")),
            source_hash: u64::from_le_bytes(raw[24..32].try_into().expect("huit octets")),
            compiler_version: u32::from_le_bytes([raw[32], raw[33], raw[34], raw[35]]),
            total_size: u64::from_le_bytes(raw[40..48].try_into().expect("huit octets")),
        };

        // R-890 : une version majeure inconnue est un refus, pas une lecture
        // partielle. Une mineure supérieure se lit au contraire (R-891), les
        // sections qu'elle ajoute étant ignorées comme des inconnues.
        if header.version_major != VERSION_MAJOR {
            return Err(A3dError::UnsupportedMajor(header.version_major));
        }

        // R-900 : `offset + size <= total_size <= taille réelle`, vérifié avant
        // toute allocation. Le second maillon d'abord.
        let actual = bytes.len() as u64;
        if header.total_size > actual {
            return Err(A3dError::Truncated {
                announced: header.total_size,
                actual,
            });
        }
        if header.total_size > limits.max_bytes() {
            return Err(A3dError::SectionTooLarge {
                tag: SectionTag::META,
                announced: header.total_size,
                limit: limits.max_bytes(),
            });
        }

        // La table elle-même doit tenir dans le fichier, et son nombre
        // d'entrées est le premier chiffre venu de l'extérieur : le multiplier
        // sans précaution suffirait à déborder.
        let table_bytes = (header.section_count as u64)
            .checked_mul(SECTION_ENTRY_BYTES as u64)
            .ok_or(A3dError::HeaderCorrupted)?;
        let table_end = table_bytes
            .checked_add(HEADER_BYTES as u64)
            .ok_or(A3dError::HeaderCorrupted)?;
        if table_end > header.total_size {
            return Err(A3dError::HeaderCorrupted);
        }

        let mut entries = Vec::with_capacity(header.section_count as usize);
        for index in 0..header.section_count as usize {
            let base = HEADER_BYTES + index * SECTION_ENTRY_BYTES;
            let raw = &bytes[base..base + SECTION_ENTRY_BYTES];
            let entry = SectionEntry {
                tag: SectionTag::from_u32(u32::from_le_bytes(
                    raw[0..4].try_into().expect("quatre octets"),
                )),
                flags: u32::from_le_bytes(raw[4..8].try_into().expect("quatre octets")),
                offset: u64::from_le_bytes(raw[8..16].try_into().expect("huit octets")),
                size_compressed: u64::from_le_bytes(raw[16..24].try_into().expect("huit octets")),
                size_uncompressed: u32::from_le_bytes(
                    raw[24..28].try_into().expect("quatre octets"),
                ),
                crc32c: u32::from_le_bytes(raw[28..32].try_into().expect("quatre octets")),
            };

            // R-901 : la taille annoncée est plafonnée **avant** allocation.
            // C'est elle qui dimensionnerait le tampon de décompression.
            if entry.size_compressed > limits.max_bytes()
                || u64::from(entry.size_uncompressed) > limits.max_bytes()
            {
                return Err(A3dError::SectionTooLarge {
                    tag: entry.tag,
                    announced: entry.size_compressed.max(entry.size_uncompressed.into()),
                    limit: limits.max_bytes(),
                });
            }

            let end = entry
                .offset
                .checked_add(entry.size_compressed)
                .ok_or(A3dError::SectionOutOfBounds(entry.tag))?;
            if entry.offset < table_end || end > header.total_size {
                return Err(A3dError::SectionOutOfBounds(entry.tag));
            }

            if entries
                .iter()
                .any(|other: &SectionEntry| other.tag == entry.tag)
            {
                return Err(A3dError::DuplicateSection(entry.tag));
            }
            entries.push(entry);
        }

        Ok(Self {
            bytes,
            header,
            entries,
        })
    }

    /// En-tête du fichier.
    #[must_use]
    pub const fn header(&self) -> &A3dHeader {
        &self.header
    }

    /// Table des sections, dans l'ordre du fichier.
    #[must_use]
    pub fn entries(&self) -> &[SectionEntry] {
        &self.entries
    }

    /// Indique si le fichier porte cette section.
    #[must_use]
    pub fn has(&self, tag: SectionTag) -> bool {
        self.entries.iter().any(|entry| entry.tag == tag)
    }

    /// Rend la charge utile d'une section, décompressée si besoin.
    ///
    /// Rend `None` si la section est absente : c'est un fait, pas une erreur —
    /// un serveur dédié n'a ni matériaux ni textures, et le lui reprocher
    /// n'aurait pas de sens.
    ///
    /// # Errors
    ///
    /// [`A3dError::SectionCorrupted`] si le CRC ne correspond pas (R-882), ou
    /// [`A3dError::DecompressionFailed`] si la décompression ne rend pas la
    /// taille annoncée.
    pub fn section(&self, tag: SectionTag) -> Result<Option<Vec<u8>>, A3dError> {
        let Some(entry) = self.entries.iter().find(|entry| entry.tag == tag) else {
            return Ok(None);
        };
        self.payload(entry).map(Some)
    }

    /// Charge toutes les sections que le masque retient (objectif 7.1).
    ///
    /// Les sections hors masque ne sont ni lues ni décompressées, et les tags
    /// inconnus sont ignorés proprement (R-880).
    ///
    /// # Errors
    ///
    /// Voir [`A3dFile::section`].
    pub fn load(&self, mask: SectionMask) -> Result<Vec<(SectionTag, Vec<u8>)>, A3dError> {
        let mut loaded = Vec::new();
        for entry in &self.entries {
            if !mask.contains(entry.tag) {
                continue;
            }
            loaded.push((entry.tag, self.payload(entry)?));
        }
        Ok(loaded)
    }

    /// Rend les octets **stockés** d'une section, sans vérification ni
    /// décompression.
    ///
    /// Réservé au diagnostic : c'est ce qu'on regarde quand on soupçonne une
    /// corruption, et le vérifier serait précisément ce qu'on cherche à
    /// contourner.
    #[must_use]
    pub fn stored_bytes(&self, tag: SectionTag) -> Option<&'a [u8]> {
        let entry = self.entries.iter().find(|entry| entry.tag == tag)?;
        let start = usize::try_from(entry.offset).ok()?;
        let len = usize::try_from(entry.size_compressed).ok()?;
        self.bytes.get(start..start + len)
    }

    fn payload(&self, entry: &SectionEntry) -> Result<Vec<u8>, A3dError> {
        let start =
            usize::try_from(entry.offset).map_err(|_| A3dError::SectionOutOfBounds(entry.tag))?;
        let len = usize::try_from(entry.size_compressed)
            .map_err(|_| A3dError::SectionOutOfBounds(entry.tag))?;
        let stored = self
            .bytes
            .get(start..start + len)
            .ok_or(A3dError::SectionOutOfBounds(entry.tag))?;

        // R-882 : le CRC est vérifié **avant** la décompression. L'inverse
        // ferait passer des octets corrompus dans un décompresseur, ce qui est
        // exactement ce qu'on ne veut pas d'une donnée venue de l'extérieur.
        if crc32c::crc32c(stored) != entry.crc32c {
            return Err(A3dError::SectionCorrupted(entry.tag));
        }

        if !entry.is_compressed() {
            if stored.len() != entry.size_uncompressed as usize {
                return Err(A3dError::SectionCorrupted(entry.tag));
            }
            return Ok(stored.to_vec());
        }

        // R-902 : la taille de sortie est connue à l'avance et plafonnée ; la
        // décompression n'est jamais un flux non borné.
        let expected = entry.size_uncompressed as usize;
        let decoded = zstd::bulk::decompress(stored, expected)
            .map_err(|_| A3dError::DecompressionFailed(entry.tag))?;
        if decoded.len() != expected {
            return Err(A3dError::DecompressionFailed(entry.tag));
        }
        Ok(decoded)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::a3d::{A3dWriter, SECTION_FLAG_COMPRESSED};

    const LIMITS: A3dLimits = A3dLimits::new(1 << 20);

    fn fichier() -> Vec<u8> {
        let mut writer = A3dWriter::new(0x1234, 0x5678, 9);
        writer
            .section(SectionTag::NODE, b"des noeuds")
            .expect("NODE");
        writer
            .compressed_section(SectionTag::GEOM, &vec![7u8; 4096])
            .expect("GEOM");
        writer.section(SectionTag::META, b"meta").expect("META");
        writer.finish().expect("écriture")
    }

    #[test]
    fn t252_un_fichier_valide_se_relit_entierement() {
        let bytes = fichier();
        let file = A3dFile::open(&bytes, LIMITS).expect("lecture");

        assert_eq!(file.header().asset_id, 0x1234);
        assert_eq!(file.header().section_count, 3);
        assert!(file.has(SectionTag::NODE));
        assert!(!file.has(SectionTag::PHYS));
        assert_eq!(
            file.section(SectionTag::NODE).expect("NODE"),
            Some(b"des noeuds".to_vec())
        );
        assert_eq!(
            file.section(SectionTag::GEOM).expect("GEOM"),
            Some(vec![7u8; 4096])
        );
    }

    #[test]
    fn t252_le_masque_ne_charge_que_ce_qu_il_designe() {
        let bytes = fichier();
        let file = A3dFile::open(&bytes, LIMITS).expect("lecture");

        let charge = file
            .load(SectionMask::of(&[SectionTag::NODE, SectionTag::PHYS]))
            .expect("chargement");
        // PHYS est absente du fichier : le masque la demande, elle ne vient pas.
        assert_eq!(charge.len(), 1);
        assert_eq!(charge[0].0, SectionTag::NODE);

        assert_eq!(file.load(SectionMask::none()).expect("vide").len(), 0);
        assert_eq!(file.load(SectionMask::all()).expect("tout").len(), 3);
    }

    #[test]
    fn t253_un_fichier_qui_n_en_est_pas_un_est_refuse() {
        assert_eq!(
            A3dFile::open(b"", LIMITS).err(),
            Some(A3dError::NotAnA3dFile)
        );
        assert_eq!(
            A3dFile::open(&[0u8; 64], LIMITS).err(),
            Some(A3dError::NotAnA3dFile)
        );
        // Assez long, bon magic, mais rien derrière : le CRC le dit.
        let mut faux = vec![0u8; 64];
        faux[0..4].copy_from_slice(&MAGIC);
        assert_eq!(
            A3dFile::open(&faux, LIMITS).err(),
            Some(A3dError::HeaderCorrupted)
        );
    }

    #[test]
    fn t253_un_octet_modifie_dans_l_entete_est_vu() {
        let original = fichier();
        // Chaque octet de l'en-tête, sauf le champ de CRC lui-même : le
        // modifier ne fait que produire un CRC qui ne correspond plus.
        for index in (0..HEADER_BYTES).filter(|index| !(36..40).contains(index)) {
            let mut altere = original.clone();
            altere[index] ^= 0xFF;
            assert!(
                A3dFile::open(&altere, LIMITS).is_err(),
                "octet {index} modifié sans être vu"
            );
        }
    }

    #[test]
    fn t253_une_section_corrompue_est_vue_avant_toute_decompression() {
        let original = fichier();
        let file = A3dFile::open(&original, LIMITS).expect("lecture");
        let geom = *file
            .entries()
            .iter()
            .find(|entry| entry.tag == SectionTag::GEOM)
            .expect("GEOM");

        let mut altere = original.clone();
        altere[geom.offset as usize] ^= 0xFF;

        let file = A3dFile::open(&altere, LIMITS).expect("l'en-tête reste valide");
        // R-882 : c'est la section qui est invalidée, pas le fichier entier.
        assert_eq!(
            file.section(SectionTag::GEOM).err(),
            Some(A3dError::SectionCorrupted(SectionTag::GEOM))
        );
        assert!(file.section(SectionTag::NODE).is_ok(), "voisine touchée");
    }

    #[test]
    fn t253_un_fichier_tronque_est_refuse() {
        let original = fichier();
        let mut tronque = original.clone();
        tronque.truncate(original.len() - 1);

        match A3dFile::open(&tronque, LIMITS).err() {
            Some(A3dError::Truncated { announced, actual }) => {
                assert_eq!(announced as usize, original.len());
                assert_eq!(actual as usize, original.len() - 1);
            }
            autre => panic!("troncature non vue : {autre:?}"),
        }
    }

    #[test]
    fn t253_une_version_majeure_inconnue_est_refusee() {
        let mut bytes = fichier();
        bytes[4..6].copy_from_slice(&2u16.to_le_bytes());
        reparer_crc(&mut bytes);

        // R-890 : refus explicite, et recompilation si la source est là.
        assert_eq!(
            A3dFile::open(&bytes, LIMITS).err(),
            Some(A3dError::UnsupportedMajor(2))
        );
    }

    #[test]
    fn t253_une_version_mineure_superieure_se_lit() {
        let mut bytes = fichier();
        bytes[6..8].copy_from_slice(&99u16.to_le_bytes());
        reparer_crc(&mut bytes);

        // R-891 : les sections connues se lisent, les autres sont ignorées.
        let file = A3dFile::open(&bytes, LIMITS).expect("lecture refusée");
        assert_eq!(file.header().version_minor, 99);
        assert!(file.section(SectionTag::NODE).expect("NODE").is_some());
    }

    #[test]
    fn t253_une_section_inconnue_est_ignoree_proprement() {
        let mut writer = A3dWriter::new(1, 2, 3);
        writer.section(SectionTag::NODE, b"connue").expect("NODE");
        writer
            .section(SectionTag::from_bytes(*b"ZZZZ"), b"venue du futur")
            .expect("inconnue");
        let bytes = writer.finish().expect("écriture");

        // R-880 : le fichier reste lisible, et la section inconnue reste
        // visible dans la table sans être interprétée.
        let file = A3dFile::open(&bytes, LIMITS).expect("lecture");
        assert_eq!(file.entries().len(), 2);
        assert_eq!(
            file.section(SectionTag::NODE).expect("NODE"),
            Some(b"connue".to_vec())
        );
        assert_eq!(file.load(SectionMask::all()).expect("chargement").len(), 1);
    }

    #[test]
    fn t253_une_section_hors_bornes_est_refusee() {
        let mut bytes = fichier();
        // Première entrée de la table : on l'envoie au-delà de la fin.
        let offset_field = HEADER_BYTES + 8;
        bytes[offset_field..offset_field + 8].copy_from_slice(&u64::MAX.to_le_bytes());
        reparer_crc(&mut bytes);

        assert!(matches!(
            A3dFile::open(&bytes, LIMITS).err(),
            Some(A3dError::SectionOutOfBounds(_))
        ));
    }

    #[test]
    fn t253_une_section_dans_la_table_est_refusee() {
        let mut bytes = fichier();
        // Une section qui commencerait dans la table des sections : de quoi
        // faire lire à un lecteur naïf sa propre structure comme une charge
        // utile.
        let offset_field = HEADER_BYTES + 8;
        bytes[offset_field..offset_field + 8].copy_from_slice(&(HEADER_BYTES as u64).to_le_bytes());
        reparer_crc(&mut bytes);

        assert!(matches!(
            A3dFile::open(&bytes, LIMITS).err(),
            Some(A3dError::SectionOutOfBounds(_))
        ));
    }

    #[test]
    fn t253_une_taille_annoncee_hors_limite_est_refusee_avant_allocation() {
        let mut bytes = fichier();
        // R-901 : c'est cette valeur qui dimensionnerait le tampon de
        // décompression. Elle est plafonnée avant qu'on alloue quoi que ce soit.
        let size_field = HEADER_BYTES + 24;
        bytes[size_field..size_field + 4].copy_from_slice(&u32::MAX.to_le_bytes());
        reparer_crc(&mut bytes);

        assert!(matches!(
            A3dFile::open(&bytes, A3dLimits::new(4096)).err(),
            Some(A3dError::SectionTooLarge { .. })
        ));
    }

    #[test]
    fn t253_un_nombre_de_sections_absurde_est_refuse() {
        let mut bytes = fichier();
        bytes[12..16].copy_from_slice(&u32::MAX.to_le_bytes());
        reparer_crc(&mut bytes);

        // Le produit `section_count * 32` déborderait un calcul naïf, et la
        // table ne tient de toute façon pas dans le fichier.
        assert_eq!(
            A3dFile::open(&bytes, LIMITS).err(),
            Some(A3dError::HeaderCorrupted)
        );
    }

    #[test]
    fn t253_une_section_qui_ment_sur_sa_taille_decompressee_est_refusee() {
        let mut writer = A3dWriter::new(1, 2, 3);
        writer
            .compressed_section(SectionTag::GEOM, &vec![3u8; 8192])
            .expect("GEOM");
        let mut bytes = writer.finish().expect("écriture");

        // La charge utile est intacte, son CRC aussi ; seule la taille
        // décompressée annoncée est fausse. La décompression bornée s'en rend
        // compte plutôt que de rendre un contenu partiel.
        let size_field = HEADER_BYTES + 24;
        bytes[size_field..size_field + 4].copy_from_slice(&64u32.to_le_bytes());
        reparer_crc(&mut bytes);

        let file = A3dFile::open(&bytes, LIMITS).expect("lecture");
        assert_eq!(
            file.section(SectionTag::GEOM).err(),
            Some(A3dError::DecompressionFailed(SectionTag::GEOM))
        );
    }

    #[test]
    fn t253_une_section_non_compressee_qui_ment_sur_sa_taille_est_refusee() {
        let mut bytes = fichier();
        let size_field = HEADER_BYTES + 24;
        bytes[size_field..size_field + 4].copy_from_slice(&3u32.to_le_bytes());
        reparer_crc(&mut bytes);

        let file = A3dFile::open(&bytes, LIMITS).expect("lecture");
        assert_eq!(
            file.section(SectionTag::NODE).err(),
            Some(A3dError::SectionCorrupted(SectionTag::NODE))
        );
    }

    #[test]
    fn t253_deux_sections_du_meme_tag_sont_refusees_a_la_lecture() {
        let mut bytes = fichier();
        // La deuxième entrée reçoit le tag de la première : le fichier devient
        // ambigu, et l'écrivain n'est pas le seul à pouvoir le produire.
        let tag_field = HEADER_BYTES + SECTION_ENTRY_BYTES;
        bytes[tag_field..tag_field + 4].copy_from_slice(&SectionTag::NODE.as_u32().to_le_bytes());
        reparer_crc(&mut bytes);

        assert_eq!(
            A3dFile::open(&bytes, LIMITS).err(),
            Some(A3dError::DuplicateSection(SectionTag::NODE))
        );
    }

    #[test]
    fn t253_les_octets_stockes_restent_consultables_pour_le_diagnostic() {
        let bytes = fichier();
        let file = A3dFile::open(&bytes, LIMITS).expect("lecture");

        assert_eq!(
            file.stored_bytes(SectionTag::NODE),
            Some(&b"des noeuds"[..])
        );
        assert_eq!(file.stored_bytes(SectionTag::PHYS), None);
    }

    #[test]
    fn un_drapeau_de_compression_sur_une_section_qui_ne_l_est_pas_est_vu() {
        let mut bytes = fichier();
        let flags_field = HEADER_BYTES + 4;
        bytes[flags_field..flags_field + 4].copy_from_slice(&SECTION_FLAG_COMPRESSED.to_le_bytes());
        reparer_crc(&mut bytes);

        let file = A3dFile::open(&bytes, LIMITS).expect("lecture");
        assert_eq!(
            file.section(SectionTag::NODE).err(),
            Some(A3dError::DecompressionFailed(SectionTag::NODE))
        );
    }

    /// Recalcule le CRC de l'en-tête après une altération volontaire.
    ///
    /// Sans cela, chaque test de robustesse échouerait sur le CRC avant
    /// d'atteindre la vérification qu'il vise, et ne prouverait rien de plus
    /// que l'existence du CRC.
    fn reparer_crc(bytes: &mut [u8]) {
        let mut header = [0u8; HEADER_BYTES];
        header.copy_from_slice(&bytes[0..HEADER_BYTES]);
        let checksum = header_checksum(&header);
        bytes[36..40].copy_from_slice(&checksum.to_le_bytes());
    }
}
