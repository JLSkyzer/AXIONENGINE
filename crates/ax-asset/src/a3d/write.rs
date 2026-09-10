//! Écriture d'un fichier A3D (C-24, PARTIE 7).

use super::{
    align_up, header_checksum, A3dError, SectionTag, HEADER_BYTES, MAGIC, SECTION_ENTRY_BYTES,
    SECTION_FLAG_COMPRESSED, VERSION_MAJOR, VERSION_MINOR,
};

/// Niveau de compression zstd des sections compressées.
///
/// Trois : le niveau par défaut de zstd. Le fichier est produit une fois et lu
/// souvent, mais il l'est pendant une compilation d'asset que R-521 interdit de
/// laisser bloquer le jeu — un niveau plus agressif y prendrait un temps que
/// personne n'a demandé.
const COMPRESSION_LEVEL: i32 = 3;

/// Section en attente d'écriture.
struct Pending {
    tag: SectionTag,
    stored: Vec<u8>,
    size_uncompressed: u32,
    flags: u32,
}

/// Constructeur de fichier A3D.
///
/// La sortie est **déterministe** : mêmes sections dans le même ordre, mêmes
/// octets. R-2342 l'exige du build, et un fichier reproductible est aussi ce
/// qui permet de comparer deux compilations pour savoir si quelque chose a
/// changé.
pub struct A3dWriter {
    asset_id: u64,
    source_hash: u64,
    compiler_version: u32,
    flags: u32,
    sections: Vec<Pending>,
}

impl A3dWriter {
    /// Commence un fichier.
    ///
    /// `source_hash` et `compiler_version` sont ce qui permet au cache de savoir
    /// qu'une entrée est périmée (R-892) : ils sont donnés par l'appelant, qui
    /// seul connaît la source dont il compile.
    #[must_use]
    pub fn new(asset_id: u64, source_hash: u64, compiler_version: u32) -> Self {
        Self {
            asset_id,
            source_hash,
            compiler_version,
            flags: 0,
            sections: Vec::new(),
        }
    }

    /// Ajoute une section stockée telle quelle.
    ///
    /// # Errors
    ///
    /// [`A3dError::DuplicateSection`] si le tag est déjà présent — deux
    /// sections d'un même tag rendraient la lecture ambiguë —, et
    /// [`A3dError::SectionUnwritable`] si la charge utile dépasse ce que la
    /// table peut décrire.
    pub fn section(&mut self, tag: SectionTag, payload: &[u8]) -> Result<&mut Self, A3dError> {
        let size = u32::try_from(payload.len()).map_err(|_| A3dError::SectionUnwritable(tag))?;
        self.push(Pending {
            tag,
            stored: payload.to_vec(),
            size_uncompressed: size,
            flags: 0,
        })
    }

    /// Ajoute une section compressée en zstd.
    ///
    /// Si la compression n'y gagne rien — cas courant d'une petite section —,
    /// la charge utile est stockée telle quelle : payer une décompression pour
    /// grossir le fichier n'aurait pas de sens.
    ///
    /// # Errors
    ///
    /// Voir [`A3dWriter::section`].
    pub fn compressed_section(
        &mut self,
        tag: SectionTag,
        payload: &[u8],
    ) -> Result<&mut Self, A3dError> {
        let size = u32::try_from(payload.len()).map_err(|_| A3dError::SectionUnwritable(tag))?;

        let compressed = zstd::bulk::compress(payload, COMPRESSION_LEVEL)
            .map_err(|_| A3dError::SectionUnwritable(tag))?;

        if compressed.len() >= payload.len() {
            return self.section(tag, payload);
        }

        self.push(Pending {
            tag,
            stored: compressed,
            size_uncompressed: size,
            flags: SECTION_FLAG_COMPRESSED,
        })
    }

    fn push(&mut self, pending: Pending) -> Result<&mut Self, A3dError> {
        if self.sections.iter().any(|other| other.tag == pending.tag) {
            return Err(A3dError::DuplicateSection(pending.tag));
        }
        self.sections.push(pending);
        Ok(self)
    }

    /// Produit le fichier complet.
    ///
    /// # Errors
    ///
    /// [`A3dError::SectionUnwritable`] si le fichier ne tient pas dans les
    /// champs de l'en-tête.
    pub fn finish(&self) -> Result<Vec<u8>, A3dError> {
        let table_bytes = self.sections.len() * SECTION_ENTRY_BYTES;
        let mut cursor = align_up(HEADER_BYTES + table_bytes);

        // Les positions se calculent avant d'écrire quoi que ce soit : la table
        // précède les sections, et elle doit déjà savoir où chacune atterrit.
        let mut offsets = Vec::with_capacity(self.sections.len());
        for pending in &self.sections {
            offsets.push(cursor);
            cursor = align_up(cursor + pending.stored.len());
        }
        let total_size = cursor;

        let mut out = vec![0u8; total_size];

        // --- table des sections ---
        for (index, (pending, offset)) in self.sections.iter().zip(&offsets).enumerate() {
            let base = HEADER_BYTES + index * SECTION_ENTRY_BYTES;
            let entry = &mut out[base..base + SECTION_ENTRY_BYTES];
            entry[0..4].copy_from_slice(&pending.tag.as_u32().to_le_bytes());
            entry[4..8].copy_from_slice(&pending.flags.to_le_bytes());
            entry[8..16].copy_from_slice(&(*offset as u64).to_le_bytes());
            entry[16..24].copy_from_slice(&(pending.stored.len() as u64).to_le_bytes());
            entry[24..28].copy_from_slice(&pending.size_uncompressed.to_le_bytes());
            entry[28..32].copy_from_slice(&crc32c::crc32c(&pending.stored).to_le_bytes());
        }

        // --- sections ---
        for (pending, offset) in self.sections.iter().zip(&offsets) {
            out[*offset..*offset + pending.stored.len()].copy_from_slice(&pending.stored);
        }

        // --- en-tête ---
        let count = u32::try_from(self.sections.len())
            .map_err(|_| A3dError::SectionUnwritable(SectionTag::META))?;
        let header = &mut out[0..HEADER_BYTES];
        header[0..4].copy_from_slice(&MAGIC);
        header[4..6].copy_from_slice(&VERSION_MAJOR.to_le_bytes());
        header[6..8].copy_from_slice(&VERSION_MINOR.to_le_bytes());
        header[8..12].copy_from_slice(&self.flags.to_le_bytes());
        header[12..16].copy_from_slice(&count.to_le_bytes());
        header[16..24].copy_from_slice(&self.asset_id.to_le_bytes());
        header[24..32].copy_from_slice(&self.source_hash.to_le_bytes());
        header[32..36].copy_from_slice(&self.compiler_version.to_le_bytes());
        // Le CRC reste nul le temps du calcul, qui l'exclut de toute façon.
        header[40..48].copy_from_slice(&(total_size as u64).to_le_bytes());

        let mut fixed = [0u8; HEADER_BYTES];
        fixed.copy_from_slice(&out[0..HEADER_BYTES]);
        let checksum = header_checksum(&fixed);
        out[36..40].copy_from_slice(&checksum.to_le_bytes());

        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::a3d::{A3dFile, A3dLimits, SECTION_ALIGN};

    const LIMITS: A3dLimits = A3dLimits::new(1 << 20);

    #[test]
    fn t251_un_fichier_vide_reste_lisible() {
        let bytes = A3dWriter::new(7, 9, 3).finish().expect("écriture");

        let file = A3dFile::open(&bytes, LIMITS).expect("lecture");
        assert_eq!(file.header().section_count, 0);
        assert_eq!(file.header().asset_id, 7);
        assert_eq!(file.header().source_hash, 9);
        assert_eq!(file.header().compiler_version, 3);
        assert_eq!(file.header().total_size as usize, bytes.len());
    }

    #[test]
    fn t251_les_sections_sont_alignees_sur_seize_octets() {
        let mut writer = A3dWriter::new(1, 1, 1);
        // Des tailles délibérément non alignées : c'est le remplissage qui est
        // vérifié, et R-881 en fait la condition d'une lecture en place.
        writer.section(SectionTag::NODE, &[1u8; 7]).expect("NODE");
        writer.section(SectionTag::GEOM, &[2u8; 33]).expect("GEOM");
        writer.section(SectionTag::META, &[3u8; 1]).expect("META");
        let bytes = writer.finish().expect("écriture");

        let file = A3dFile::open(&bytes, LIMITS).expect("lecture");
        for entry in file.entries() {
            assert_eq!(
                entry.offset as usize % SECTION_ALIGN,
                0,
                "section {} mal alignée",
                entry.tag
            );
        }
    }

    #[test]
    fn t251_la_sortie_est_deterministe() {
        let build = || {
            let mut writer = A3dWriter::new(42, 4242, 1);
            writer.section(SectionTag::NODE, b"noeuds").expect("NODE");
            writer
                .compressed_section(SectionTag::GEOM, &vec![0u8; 4096])
                .expect("GEOM");
            writer.finish().expect("écriture")
        };

        // Deux compilations identiques doivent produire le même fichier, sans
        // quoi comparer deux exécutions ne dirait rien (R-2342).
        assert_eq!(build(), build());
        // Et le remplissage est bien nul, jamais de la mémoire résiduelle.
        let bytes = build();
        let file = A3dFile::open(&bytes, LIMITS).expect("lecture");
        let entry = file.entries()[0];
        let fin = (entry.offset + entry.size_compressed) as usize;
        let suivant = file.entries()[1].offset as usize;
        assert!(bytes[fin..suivant].iter().all(|byte| *byte == 0));
    }

    #[test]
    fn t251_une_section_compressee_se_relit() {
        // Un contenu très répétitif : la compression y gagne franchement.
        let payload: Vec<u8> = (0..8192).map(|index| (index % 7) as u8).collect();

        let mut writer = A3dWriter::new(1, 2, 3);
        writer
            .compressed_section(SectionTag::GEOM, &payload)
            .expect("GEOM");
        let bytes = writer.finish().expect("écriture");

        let file = A3dFile::open(&bytes, LIMITS).expect("lecture");
        let entry = file.entries()[0];
        assert!(entry.is_compressed(), "la section n'a pas été compressée");
        assert!(entry.size_compressed < u64::from(entry.size_uncompressed));
        assert_eq!(
            file.section(SectionTag::GEOM).expect("lecture"),
            Some(payload)
        );
    }

    #[test]
    fn t251_une_section_incompressible_est_stockee_telle_quelle() {
        // Une suite sans redondance : zstd ne peut que l'allonger.
        let payload: Vec<u8> = (0..64u32)
            .flat_map(|n| n.wrapping_mul(2_654_435_761).to_le_bytes())
            .collect();

        let mut writer = A3dWriter::new(1, 2, 3);
        writer
            .compressed_section(SectionTag::EXTR, &payload)
            .expect("EXTR");
        let bytes = writer.finish().expect("écriture");

        let file = A3dFile::open(&bytes, LIMITS).expect("lecture");
        let entry = file.entries()[0];
        // Payer une décompression pour grossir le fichier n'aurait pas de sens.
        assert!(!entry.is_compressed());
        assert_eq!(entry.size_compressed, payload.len() as u64);
        assert_eq!(
            file.section(SectionTag::EXTR).expect("lecture"),
            Some(payload)
        );
    }

    #[test]
    fn t251_deux_sections_du_meme_tag_sont_refusees() {
        let mut writer = A3dWriter::new(1, 2, 3);
        writer.section(SectionTag::NODE, b"a").expect("NODE");
        // Deux sections d'un même tag rendraient la lecture ambiguë.
        assert_eq!(
            writer.section(SectionTag::NODE, b"b").err(),
            Some(A3dError::DuplicateSection(SectionTag::NODE))
        );
    }

    #[test]
    fn t251_une_section_vide_est_admise() {
        let mut writer = A3dWriter::new(1, 2, 3);
        writer.section(SectionTag::EXTR, &[]).expect("EXTR");
        let bytes = writer.finish().expect("écriture");

        let file = A3dFile::open(&bytes, LIMITS).expect("lecture");
        // Une section présente et vide ne dit pas la même chose qu'une section
        // absente : la première affirme qu'il n'y a rien, la seconde ne dit rien.
        assert_eq!(
            file.section(SectionTag::EXTR).expect("lecture"),
            Some(Vec::new())
        );
        assert_eq!(file.section(SectionTag::NODE).expect("lecture"), None);
    }
}
