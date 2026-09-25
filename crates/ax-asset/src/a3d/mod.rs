//! Conteneur A3D (C-24, PARTIE 7).

mod node;
mod physics;
mod read;
mod write;

pub use node::{decode_nodes, encode_nodes, NodeTable, NODE_BYTES};
pub use physics::{decode_colliders, encode_colliders, COLLIDER_BYTES};
pub use read::{A3dFile, A3dLimits};
pub use write::A3dWriter;

use core::fmt;

/// Les quatre premiers octets d'un fichier A3D.
///
/// Le cahier des charges écrit `u32 magic = 0x41_33_44_00 ("A3D\0")`. Les deux
/// notations ne coïncident pas en little-endian, que la PARTIE 7 impose par
/// ailleurs : ce sont les **octets** qui font foi, comme pour l'en-tête des
/// tampons partagés (R-271). Un fichier A3D commence donc par `A3D\0` lisible
/// dans un éditeur hexadécimal, et sa lecture en `u32` little-endian donne
/// [`MAGIC_LE`].
pub const MAGIC: [u8; 4] = *b"A3D\0";

/// [`MAGIC`] tel qu'une lecture `u32` little-endian le rend.
pub const MAGIC_LE: u32 = u32::from_le_bytes(MAGIC);

/// Version majeure du format. Un écart est un refus (R-890).
pub const VERSION_MAJOR: u16 = 1;

/// Version mineure du format. Une version supérieure se lit partiellement
/// (R-891).
pub const VERSION_MINOR: u16 = 1;

/// Taille de l'en-tête, en octets.
pub const HEADER_BYTES: usize = 64;

/// Taille d'une entrée de la table des sections, en octets.
pub const SECTION_ENTRY_BYTES: usize = 32;

/// Alignement des sections, en octets (R-881).
///
/// Les structures `repr(C)` du modèle sont lues **en place** dans le tampon :
/// un alignement moindre rendrait cette lecture illégale.
pub const SECTION_ALIGN: usize = 16;

/// Position du champ `header_crc32c` dans l'en-tête.
const HEADER_CRC_OFFSET: usize = 36;

/// Drapeau de section : la charge utile est compressée en zstd.
pub const SECTION_FLAG_COMPRESSED: u32 = 1;

/// Tag d'une section.
///
/// Quatre octets ASCII. Le type conserve les tags **inconnus** tels quels :
/// R-880 veut qu'un lecteur les ignore proprement, ce qu'il ne pourrait pas
/// faire s'il les refusait à la lecture de la table.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct SectionTag([u8; 4]);

impl SectionTag {
    /// Hiérarchie des nœuds et table des noms.
    pub const NODE: Self = Self(*b"NODE");
    /// Meshes, sommets et indices.
    pub const GEOM: Self = Self(*b"GEOM");
    /// Matériaux.
    pub const MATL: Self = Self(*b"MATL");
    /// Textures extraites et leurs identifiants de ressource.
    pub const TEXR: Self = Self(*b"TEXR");
    /// Colliders, convexes, heightfields, points d'enveloppe.
    pub const PHYS: Self = Self(*b"PHYS");
    /// Squelette.
    pub const SKEL: Self = Self(*b"SKEL");
    /// Animations et pistes.
    pub const ANIM: Self = Self(*b"ANIM");
    /// Parts et zones de dommage.
    pub const PART: Self = Self(*b"PART");
    /// Régions de déformation et bitsets d'ancrage.
    pub const DEFM: Self = Self(*b"DEFM");
    /// Liaisons structurelles et ordre topologique.
    pub const STRC: Self = Self(*b"STRC");
    /// Zones d'usure.
    pub const WEAR: Self = Self(*b"WEAR");
    /// Ensembles de particules.
    ///
    /// La PARTIE 7 l'écrit `PART_SET`, qui ne tient pas dans les quatre octets
    /// d'un tag ; `PSET` est la forme retenue, `PART` étant déjà pris.
    pub const PSET: Self = Self(*b"PSET");
    /// Table LOD vers meshes.
    pub const LODM: Self = Self(*b"LODM");
    /// Sockets nommés.
    pub const SOCK: Self = Self(*b"SOCK");
    /// Ensembles de décalques prédéfinis.
    pub const DECL: Self = Self(*b"DECL");
    /// Métadonnées : noms, unités, auteur, licence, options de compilation.
    pub const META: Self = Self(*b"META");
    /// Extras auteur, non interprétés.
    pub const EXTR: Self = Self(*b"EXTR");

    /// Tous les tags normatifs, dans l'ordre de la table de la PARTIE 7.
    pub const NORMATIVE: [SectionTag; 17] = [
        Self::NODE,
        Self::GEOM,
        Self::MATL,
        Self::TEXR,
        Self::PHYS,
        Self::SKEL,
        Self::ANIM,
        Self::PART,
        Self::DEFM,
        Self::STRC,
        Self::WEAR,
        Self::PSET,
        Self::LODM,
        Self::SOCK,
        Self::DECL,
        Self::META,
        Self::EXTR,
    ];

    /// Construit un tag depuis ses quatre octets.
    #[must_use]
    pub const fn from_bytes(bytes: [u8; 4]) -> Self {
        Self(bytes)
    }

    /// Construit un tag depuis sa forme stockée.
    #[must_use]
    pub const fn from_u32(raw: u32) -> Self {
        Self(raw.to_le_bytes())
    }

    /// Forme stockée du tag.
    #[must_use]
    pub const fn as_u32(self) -> u32 {
        u32::from_le_bytes(self.0)
    }

    /// Octets du tag.
    #[must_use]
    pub const fn as_bytes(self) -> [u8; 4] {
        self.0
    }

    /// Indique si le tag fait partie de ceux que la PARTIE 7 déclare.
    #[must_use]
    pub fn is_normative(self) -> bool {
        Self::NORMATIVE.contains(&self)
    }
}

impl fmt::Display for SectionTag {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        // Un tag inconnu peut ne pas être imprimable : il s'écrit alors en
        // hexadécimal plutôt que de produire des caractères de contrôle dans
        // un message d'erreur.
        if self.0.iter().all(|byte| byte.is_ascii_graphic()) {
            formatter.write_str(core::str::from_utf8(&self.0).unwrap_or("????"))
        } else {
            write!(formatter, "0x{:08x}", self.as_u32())
        }
    }
}

/// Sélection de sections à charger (objectif 7.1).
///
/// Un serveur dédié n'a que faire des matériaux ni des textures, et les charger
/// coûterait de la mémoire pour rien. Le masque porte sur les tags normatifs ;
/// les autres sont ignorés de toute façon (R-880).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct SectionMask(u32);

impl SectionMask {
    /// Masque vide.
    #[must_use]
    pub const fn none() -> Self {
        Self(0)
    }

    /// Masque de toutes les sections normatives.
    #[must_use]
    pub fn all() -> Self {
        Self((1u32 << SectionTag::NORMATIVE.len()) - 1)
    }

    /// Masque des tags donnés.
    #[must_use]
    pub fn of(tags: &[SectionTag]) -> Self {
        let mut mask = Self::none();
        for tag in tags {
            mask = mask.with(*tag);
        }
        mask
    }

    /// Ajoute un tag au masque.
    ///
    /// Un tag non normatif ne change rien : il n'a pas de place dans le masque,
    /// et lui en inventer une le rendrait indiscernable d'un autre.
    #[must_use]
    pub fn with(self, tag: SectionTag) -> Self {
        match SectionTag::NORMATIVE.iter().position(|known| *known == tag) {
            Some(index) => Self(self.0 | (1u32 << index)),
            None => self,
        }
    }

    /// Indique si le masque retient ce tag.
    #[must_use]
    pub fn contains(self, tag: SectionTag) -> bool {
        match SectionTag::NORMATIVE.iter().position(|known| *known == tag) {
            Some(index) => self.0 & (1u32 << index) != 0,
            None => false,
        }
    }

    /// Forme entière du masque, telle qu'elle traverse la frontière (IF-06).
    #[must_use]
    pub const fn bits(self) -> u32 {
        self.0
    }

    /// Construit un masque depuis sa forme entière.
    ///
    /// Les bits au-delà des tags normatifs sont écartés : ils ne désignent
    /// rien, et les conserver ferait croire à une sélection plus large.
    #[must_use]
    pub fn from_bits(bits: u32) -> Self {
        Self(bits & Self::all().0)
    }
}

/// En-tête d'un fichier A3D (7.2).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct A3dHeader {
    /// Version majeure du format.
    pub version_major: u16,
    /// Version mineure du format.
    pub version_minor: u16,
    /// Drapeaux du fichier. Aucun n'est défini à ce jour ; ils sont conservés
    /// tels quels pour l'extensibilité vers l'avant.
    pub flags: u32,
    /// Nombre d'entrées de la table des sections.
    pub section_count: u32,
    /// Identifiant de l'asset.
    pub asset_id: u64,
    /// Empreinte de la source dont il est issu.
    pub source_hash: u64,
    /// Version du compilateur qui l'a produit (R-892).
    pub compiler_version: u32,
    /// Taille totale annoncée du fichier, en octets.
    pub total_size: u64,
}

/// Entrée de la table des sections (7.2).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SectionEntry {
    /// Tag de la section.
    pub tag: SectionTag,
    /// Drapeaux, dont [`SECTION_FLAG_COMPRESSED`].
    pub flags: u32,
    /// Position de la charge utile stockée, depuis le début du fichier.
    pub offset: u64,
    /// Taille de la charge utile **telle qu'elle est stockée**.
    pub size_compressed: u64,
    /// Taille de la charge utile une fois décompressée.
    pub size_uncompressed: u32,
    /// CRC32C de la charge utile stockée.
    pub crc32c: u32,
}

impl SectionEntry {
    /// Indique si la charge utile est compressée.
    #[must_use]
    pub const fn is_compressed(&self) -> bool {
        self.flags & SECTION_FLAG_COMPRESSED != 0
    }
}

/// Ce qui empêche de lire ou d'écrire un fichier A3D.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum A3dError {
    /// Le fichier ne commence pas par [`MAGIC`].
    NotAnA3dFile,
    /// Le fichier est plus court que ce qu'il annonce.
    Truncated {
        /// Taille annoncée par l'en-tête.
        announced: u64,
        /// Taille réelle.
        actual: u64,
    },
    /// L'en-tête ne correspond pas à son CRC.
    HeaderCorrupted,
    /// Une section ne correspond pas à son CRC (R-882).
    SectionCorrupted(SectionTag),
    /// Une section déborde du fichier.
    SectionOutOfBounds(SectionTag),
    /// Une section annonce une taille au-delà de la limite admise (R-901).
    SectionTooLarge {
        /// Section fautive.
        tag: SectionTag,
        /// Taille annoncée.
        announced: u64,
        /// Limite en vigueur.
        limit: u64,
    },
    /// La décompression n'a pas produit la taille annoncée.
    DecompressionFailed(SectionTag),
    /// Version majeure inconnue (R-890).
    UnsupportedMajor(u16),
    /// Deux sections portent le même tag.
    DuplicateSection(SectionTag),
    /// La charge utile dépasse ce qu'une entrée de table peut décrire.
    SectionUnwritable(SectionTag),
    /// Le contenu d'une section ne suit pas sa disposition.
    MalformedSection {
        /// Section fautive.
        tag: SectionTag,
        /// Ce qui ne va pas.
        detail: &'static str,
    },
}

impl A3dError {
    /// Code de l'ANNEXE A.1 correspondant.
    #[must_use]
    pub const fn code(&self) -> i32 {
        match self {
            // `E-3008` : version majeure A3D inconnue, refus et recompilation.
            A3dError::UnsupportedMajor(_) => -3008,
            // `E-3007` : section A3D corrompue, asset invalidé. Tout ce qui
            // rend un fichier illisible relève de ce code : l'ANNEXE A.1 n'en
            // ouvre pas d'autre pour le conteneur, et en inventer un
            // reviendrait à l'étendre sans y toucher (voir ADR-102).
            _ => -3007,
        }
    }
}

impl fmt::Display for A3dError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            A3dError::NotAnA3dFile => formatter.write_str("ce n'est pas un fichier A3D"),
            A3dError::Truncated { announced, actual } => write!(
                formatter,
                "fichier tronqué : {announced} octets annoncés, {actual} présents"
            ),
            A3dError::HeaderCorrupted => formatter.write_str("en-tête A3D corrompu"),
            A3dError::SectionCorrupted(tag) => write!(formatter, "section {tag} corrompue"),
            A3dError::SectionOutOfBounds(tag) => {
                write!(formatter, "section {tag} hors des bornes du fichier")
            }
            A3dError::SectionTooLarge {
                tag,
                announced,
                limit,
            } => write!(
                formatter,
                "section {tag} : {announced} octets annoncés, limite {limit}"
            ),
            A3dError::DecompressionFailed(tag) => {
                write!(formatter, "section {tag} : décompression impossible")
            }
            A3dError::UnsupportedMajor(major) => {
                write!(formatter, "version majeure A3D {major} inconnue")
            }
            A3dError::DuplicateSection(tag) => write!(formatter, "section {tag} en double"),
            A3dError::SectionUnwritable(tag) => {
                write!(formatter, "section {tag} trop grande pour être décrite")
            }
            A3dError::MalformedSection { tag, detail } => {
                write!(formatter, "section {tag} mal formée : {detail}")
            }
        }
    }
}

impl std::error::Error for A3dError {}

/// Calcule le CRC de l'en-tête, champ de CRC mis à zéro.
///
/// Le champ vit au milieu de l'en-tête ; le calcul porte pourtant sur les
/// **soixante-quatre** octets, avec ces quatre-là annulés. Ne couvrir que ce
/// qui le précède laisserait `total_size` sans protection, alors que R-900 en
/// fait le rempart de toutes les vérifications de bornes.
pub(crate) fn header_checksum(header: &[u8; HEADER_BYTES]) -> u32 {
    let mut copy = *header;
    copy[HEADER_CRC_OFFSET..HEADER_CRC_OFFSET + 4].fill(0);
    crc32c::crc32c(&copy)
}

/// Arrondit une position au prochain multiple de [`SECTION_ALIGN`].
pub(crate) const fn align_up(value: usize) -> usize {
    value.next_multiple_of(SECTION_ALIGN)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn t250_le_magic_est_lisible_dans_un_editeur_hexadecimal() {
        assert_eq!(&MAGIC, b"A3D\0");
        // La constante du cahier des charges, 0x41334400, est la transcription
        // big-endian des mêmes octets : c'est bien le même fichier.
        assert_eq!(MAGIC_LE.to_le_bytes(), MAGIC);
        assert_eq!(0x4133_4400_u32.to_be_bytes(), MAGIC);
    }

    #[test]
    fn t250_les_tags_normatifs_sont_distincts_et_imprimables() {
        let mut vus = Vec::new();
        for tag in SectionTag::NORMATIVE {
            assert!(!vus.contains(&tag), "{tag} en double");
            assert!(
                tag.as_bytes().iter().all(u8::is_ascii_graphic),
                "{tag} non imprimable"
            );
            assert!(tag.is_normative());
            vus.push(tag);
        }
        assert_eq!(vus.len(), 17, "la table de la PARTIE 7 en compte dix-sept");
    }

    #[test]
    fn t250_un_tag_survit_a_son_aller_retour() {
        for tag in SectionTag::NORMATIVE {
            assert_eq!(SectionTag::from_u32(tag.as_u32()), tag);
        }
        // Un tag inconnu est conservé tel quel : R-880 veut qu'il soit ignoré,
        // pas refusé.
        let inconnu = SectionTag::from_bytes(*b"ZZZZ");
        assert!(!inconnu.is_normative());
        assert_eq!(SectionTag::from_u32(inconnu.as_u32()), inconnu);
    }

    #[test]
    fn t250_un_tag_non_imprimable_s_ecrit_en_hexadecimal() {
        let tag = SectionTag::from_bytes([0x00, 0x01, 0x02, 0x03]);
        assert_eq!(tag.to_string(), "0x03020100");
    }

    #[test]
    fn t250_le_masque_designe_les_sections_normatives() {
        let mask = SectionMask::of(&[SectionTag::NODE, SectionTag::GEOM]);
        assert!(mask.contains(SectionTag::NODE));
        assert!(mask.contains(SectionTag::GEOM));
        assert!(!mask.contains(SectionTag::MATL));

        assert!(SectionMask::all().contains(SectionTag::EXTR));
        assert!(!SectionMask::none().contains(SectionTag::NODE));

        // Un tag inconnu n'entre pas dans le masque : lui donner une place le
        // rendrait indiscernable d'un autre.
        let inconnu = SectionTag::from_bytes(*b"ZZZZ");
        assert!(!SectionMask::all().contains(inconnu));
        assert_eq!(SectionMask::none().with(inconnu), SectionMask::none());
    }

    #[test]
    fn t250_le_masque_survit_a_sa_forme_entiere() {
        let mask = SectionMask::of(&[SectionTag::PHYS, SectionTag::DEFM]);
        assert_eq!(SectionMask::from_bits(mask.bits()), mask);
        // Les bits au-delà des tags normatifs ne désignent rien.
        assert_eq!(SectionMask::from_bits(u32::MAX), SectionMask::all());
    }

    #[test]
    fn t250_l_alignement_arrondit_vers_le_haut() {
        assert_eq!(align_up(0), 0);
        assert_eq!(align_up(1), 16);
        assert_eq!(align_up(16), 16);
        assert_eq!(align_up(17), 32);
    }

    #[test]
    fn t250_le_crc_d_entete_couvre_la_taille_totale() {
        let mut header = [0u8; HEADER_BYTES];
        let reference = header_checksum(&header);

        // Le champ de CRC lui-même n'entre pas dans le calcul.
        header[HEADER_CRC_OFFSET..HEADER_CRC_OFFSET + 4]
            .copy_from_slice(&0xDEAD_BEEF_u32.to_le_bytes());
        assert_eq!(header_checksum(&header), reference);

        // `total_size`, qui suit le champ de CRC, y entre : sans cela, R-900
        // s'appuierait sur une valeur non protégée.
        header[40..48].copy_from_slice(&1_u64.to_le_bytes());
        assert_ne!(header_checksum(&header), reference);
    }

    #[test]
    fn les_codes_d_erreur_sont_ceux_de_l_annexe() {
        assert_eq!(A3dError::UnsupportedMajor(2).code(), -3008);
        assert_eq!(A3dError::NotAnA3dFile.code(), -3007);
        assert_eq!(A3dError::SectionCorrupted(SectionTag::NODE).code(), -3007);
    }
}
