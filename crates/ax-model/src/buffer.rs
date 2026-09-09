//! IF-02 — en-tête des tampons de transfert entre Java et Rust.
//!
//! Toute donnée qui traverse la frontière voyage dans un tampon partagé, jamais
//! en paramètre d'appel : R-250 impose qu'elle traverse **au plus une fois par
//! tick et par direction, sous forme de lot**. Les fonctions de l'ABI ne
//! portent donc que des scalaires — un identifiant de contexte, des compteurs —
//! et le contenu vit dans la mémoire décrite ici.
//!
//! Chaque tampon commence par un en-tête de 32 octets, en little-endian
//! (R-271) :
//!
//! ```text
//! offset  taille  champ
//!      0       4  magic « AXNB »
//!      4       4  kind            (BufferKind)
//!      8       4  generation      (invalide les vues Java au réemploi)
//!     12       4  schema_version  (propre au kind)
//!     16       8  payload_len     (octets utiles après l'en-tête)
//!     24       4  element_count
//!     28       4  crc32c          (0 = non calculé)
//! ```
//!
//! Le little-endian est imposé, et ce n'est pas neutre côté Java : un
//! `ByteBuffer` est **big-endian par défaut**. Toute vue doit être basculée par
//! `order(ByteOrder.LITTLE_ENDIAN)`, faute de quoi chaque entier est lu à
//! l'envers sans que rien ne le signale.
//!
//! Exigences : R-250, R-262, R-270, R-271.

use core::fmt;

/// Taille de l'en-tête, en octets (R-271).
pub const HEADER_BYTES: usize = 32;

/// Magic ouvrant tout tampon AXION.
pub const MAGIC: [u8; 4] = *b"AXNB";

/// Nature d'un tampon de transfert (IF-02).
///
/// Les valeurs numériques font partie de l'ABI : elles ne changent jamais, et
/// un nouveau kind prend le numéro suivant. `0` reste invalide, ce qui fait
/// qu'un tampon oublié à zéro est rejeté au lieu de passer pour un tampon de
/// simulation.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[repr(u32)]
pub enum BufferKind {
    /// Commandes de simulation, Java vers Rust.
    SimIn = 1,
    /// États de bodies, Rust vers Java.
    SimOut = 2,
    /// Événements physiques, de dommage, de rupture et de détachement.
    Events = 3,
    /// Impacts d'origine Minecraft : explosion, projectile, feu, API.
    ImpactIn = 4,
    /// Pages de champ de déformation modifiées, pour téléversement GPU.
    DeformOut = 5,
    /// Paquets de déformation sérialisés, du serveur vers le réseau.
    DeformNet = 6,
    /// Instances visibles, matrices, palettes, décalques, LOD.
    RenderOut = 7,
    /// Instances de la passe d'ombre.
    ShadowOut = 8,
    /// Source d'un asset à compiler.
    AssetIn = 9,
    /// Asset compilé.
    AssetOut = 10,
    /// Charges utiles réseau sérialisées.
    NetOut = 11,
    /// Blobs de persistance sérialisés.
    Persist = 12,
    /// Géométrie de debug.
    Debug = 13,
}

impl BufferKind {
    /// Tous les kinds, dans l'ordre de leur valeur.
    pub const ALL: [BufferKind; 13] = [
        BufferKind::SimIn,
        BufferKind::SimOut,
        BufferKind::Events,
        BufferKind::ImpactIn,
        BufferKind::DeformOut,
        BufferKind::DeformNet,
        BufferKind::RenderOut,
        BufferKind::ShadowOut,
        BufferKind::AssetIn,
        BufferKind::AssetOut,
        BufferKind::NetOut,
        BufferKind::Persist,
        BufferKind::Debug,
    ];

    /// Convertit une valeur d'ABI en kind.
    ///
    /// Un kind inconnu n'est jamais deviné : une valeur venant de Java est une
    /// donnée externe, que l'interdiction 3.13 refuse de croire sur parole.
    #[must_use]
    pub const fn from_u32(value: u32) -> Option<BufferKind> {
        Some(match value {
            1 => BufferKind::SimIn,
            2 => BufferKind::SimOut,
            3 => BufferKind::Events,
            4 => BufferKind::ImpactIn,
            5 => BufferKind::DeformOut,
            6 => BufferKind::DeformNet,
            7 => BufferKind::RenderOut,
            8 => BufferKind::ShadowOut,
            9 => BufferKind::AssetIn,
            10 => BufferKind::AssetOut,
            11 => BufferKind::NetOut,
            12 => BufferKind::Persist,
            13 => BufferKind::Debug,
            _ => return None,
        })
    }

    /// Valeur d'ABI du kind.
    #[must_use]
    pub const fn as_u32(self) -> u32 {
        self as u32
    }

    /// Nom du kind, tel qu'il apparaît dans les métriques et les constantes
    /// Java générées.
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            BufferKind::SimIn => "SIM_IN",
            BufferKind::SimOut => "SIM_OUT",
            BufferKind::Events => "EVENTS",
            BufferKind::ImpactIn => "IMPACT_IN",
            BufferKind::DeformOut => "DEFORM_OUT",
            BufferKind::DeformNet => "DEFORM_NET",
            BufferKind::RenderOut => "RENDER_OUT",
            BufferKind::ShadowOut => "SHADOW_OUT",
            BufferKind::AssetIn => "ASSET_IN",
            BufferKind::AssetOut => "ASSET_OUT",
            BufferKind::NetOut => "NET_OUT",
            BufferKind::Persist => "PERSIST",
            BufferKind::Debug => "DEBUG",
        }
    }

    /// Sens de circulation de la donnée.
    #[must_use]
    pub const fn direction(self) -> Direction {
        match self {
            BufferKind::SimIn
            | BufferKind::ImpactIn
            | BufferKind::AssetIn
            | BufferKind::Persist => Direction::JavaToNative,
            _ => Direction::NativeToJava,
        }
    }
}

impl fmt::Display for BufferKind {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.name())
    }
}

/// Sens de circulation d'un tampon.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Direction {
    /// Java écrit, le natif lit.
    JavaToNative,
    /// Le natif écrit, Java lit.
    NativeToJava,
}

/// Erreur de lecture d'un en-tête de tampon.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HeaderError {
    /// Le tampon est plus court que l'en-tête.
    TooShort {
        /// Taille observée.
        len: usize,
    },
    /// Le magic attendu est absent : ce n'est pas un tampon AXION.
    BadMagic,
    /// Le kind ne correspond à aucune valeur connue.
    UnknownKind {
        /// Valeur lue.
        value: u32,
    },
    /// La charge utile annoncée déborde du tampon.
    PayloadOverflow {
        /// Longueur annoncée.
        declared: u64,
        /// Place réellement disponible après l'en-tête.
        available: u64,
    },
}

impl HeaderError {
    /// Code d'erreur de l'ANNEXE A.1.
    ///
    /// `E-2002` couvre le tampon invalide ou périmé : toutes ces causes
    /// aboutissent au même refus côté FFI (R-491).
    #[must_use]
    pub const fn code(self) -> i32 {
        -2002
    }
}

impl fmt::Display for HeaderError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            HeaderError::TooShort { len } => {
                write!(f, "tampon de {len} octets, plus court que l'en-tête")
            }
            HeaderError::BadMagic => f.write_str("magic absent : ce n'est pas un tampon AXION"),
            HeaderError::UnknownKind { value } => write!(f, "kind de tampon inconnu : {value}"),
            HeaderError::PayloadOverflow {
                declared,
                available,
            } => write!(
                f,
                "charge utile annoncée de {declared} octets pour {available} disponibles"
            ),
        }
    }
}

impl core::error::Error for HeaderError {}

/// En-tête d'un tampon de transfert.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BufferHeader {
    /// Nature du tampon.
    pub kind: BufferKind,
    /// Génération du tampon.
    ///
    /// Un tampon peut être réalloué entre deux ticks ; Java re-acquiert dès que
    /// la génération change, et l'usage d'une vue périmée est refusé avec
    /// `E-2002` (R-270).
    pub generation: u32,
    /// Version du schéma de la charge utile, propre au kind.
    pub schema_version: u32,
    /// Nombre d'octets utiles après l'en-tête.
    pub payload_len: u64,
    /// Nombre d'éléments dans la charge utile.
    pub element_count: u32,
    /// Somme de contrôle CRC-32C de la charge utile, ou `0` si non calculée.
    ///
    /// Optionnelle par R-271 : elle est activée par
    /// `debug.checksum_buffers` quand on cherche une corruption.
    pub crc32c: u32,
}

impl BufferHeader {
    /// Écrit l'en-tête au début de `out`.
    ///
    /// # Panics
    ///
    /// Si `out` fait moins de [`HEADER_BYTES`] octets. L'appelant maîtrise la
    /// taille du tampon qu'il alloue : s'y tromper est un défaut de
    /// programmation, pas une donnée douteuse.
    pub fn write(&self, out: &mut [u8]) {
        assert!(
            out.len() >= HEADER_BYTES,
            "tampon de {} octets, {HEADER_BYTES} requis pour l'en-tête",
            out.len()
        );
        out[0..4].copy_from_slice(&MAGIC);
        out[4..8].copy_from_slice(&self.kind.as_u32().to_le_bytes());
        out[8..12].copy_from_slice(&self.generation.to_le_bytes());
        out[12..16].copy_from_slice(&self.schema_version.to_le_bytes());
        out[16..24].copy_from_slice(&self.payload_len.to_le_bytes());
        out[24..28].copy_from_slice(&self.element_count.to_le_bytes());
        out[28..32].copy_from_slice(&self.crc32c.to_le_bytes());
    }

    /// Lit et valide un en-tête au début de `raw`.
    ///
    /// La validation couvre ce que R-491 exige avant toute lecture d'un tampon
    /// fourni par Java : magic, kind, longueur de charge utile.
    ///
    /// # Erreurs
    ///
    /// [`HeaderError`] si le tampon n'est pas un tampon AXION exploitable.
    pub fn read(raw: &[u8]) -> Result<Self, HeaderError> {
        if raw.len() < HEADER_BYTES {
            return Err(HeaderError::TooShort { len: raw.len() });
        }
        if raw[0..4] != MAGIC {
            return Err(HeaderError::BadMagic);
        }

        let kind_value = u32::from_le_bytes([raw[4], raw[5], raw[6], raw[7]]);
        let kind = BufferKind::from_u32(kind_value)
            .ok_or(HeaderError::UnknownKind { value: kind_value })?;

        let payload_len = u64::from_le_bytes([
            raw[16], raw[17], raw[18], raw[19], raw[20], raw[21], raw[22], raw[23],
        ]);
        let available = (raw.len() - HEADER_BYTES) as u64;
        if payload_len > available {
            return Err(HeaderError::PayloadOverflow {
                declared: payload_len,
                available,
            });
        }

        Ok(Self {
            kind,
            generation: u32::from_le_bytes([raw[8], raw[9], raw[10], raw[11]]),
            schema_version: u32::from_le_bytes([raw[12], raw[13], raw[14], raw[15]]),
            payload_len,
            element_count: u32::from_le_bytes([raw[24], raw[25], raw[26], raw[27]]),
            crc32c: u32::from_le_bytes([raw[28], raw[29], raw[30], raw[31]]),
        })
    }

    /// Emprunte la charge utile décrite par cet en-tête.
    ///
    /// # Panics
    ///
    /// Si `raw` ne contient pas la charge utile annoncée. Un en-tête issu de
    /// [`read`](Self::read) l'a déjà vérifié.
    #[must_use]
    pub fn payload<'a>(&self, raw: &'a [u8]) -> &'a [u8] {
        let end = HEADER_BYTES + self.payload_len as usize;
        &raw[HEADER_BYTES..end]
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> BufferHeader {
        BufferHeader {
            kind: BufferKind::SimOut,
            generation: 0x0A0B_0C0D,
            schema_version: 3,
            payload_len: 8,
            element_count: 2,
            crc32c: 0xDEAD_BEEF,
        }
    }

    /// L'en-tête fait exactement 32 octets, et chaque champ est à sa place, en
    /// little-endian (R-271). Ce test décrit l'ABI octet par octet : le faire
    /// échouer, c'est rompre la compatibilité binaire.
    #[test]
    fn disposition_binaire_de_l_entete() {
        let mut raw = [0u8; HEADER_BYTES];
        sample().write(&mut raw);

        assert_eq!(&raw[0..4], b"AXNB");
        // kind = 2, little-endian : l'octet de poids faible vient en premier.
        assert_eq!(&raw[4..8], &[2, 0, 0, 0]);
        assert_eq!(&raw[8..12], &[0x0D, 0x0C, 0x0B, 0x0A]);
        assert_eq!(&raw[12..16], &[3, 0, 0, 0]);
        assert_eq!(&raw[16..24], &[8, 0, 0, 0, 0, 0, 0, 0]);
        assert_eq!(&raw[24..28], &[2, 0, 0, 0]);
        assert_eq!(&raw[28..32], &[0xEF, 0xBE, 0xAD, 0xDE]);
    }

    /// Un en-tête écrit se relit à l'identique.
    #[test]
    fn aller_retour() {
        let mut raw = [0u8; HEADER_BYTES + 8];
        let header = sample();
        header.write(&mut raw);

        assert_eq!(BufferHeader::read(&raw).unwrap(), header);
    }

    /// La charge utile commence juste après l'en-tête, et s'arrête à la
    /// longueur annoncée.
    #[test]
    fn charge_utile_delimitee() {
        let mut raw = vec![0u8; HEADER_BYTES + 16];
        let header = BufferHeader {
            payload_len: 4,
            ..sample()
        };
        header.write(&mut raw);
        raw[HEADER_BYTES..HEADER_BYTES + 4].copy_from_slice(&[1, 2, 3, 4]);

        let read = BufferHeader::read(&raw).unwrap();
        assert_eq!(read.payload(&raw), &[1, 2, 3, 4]);
    }

    /// Un tampon plus court que l'en-tête est refusé plutôt que lu hors bornes.
    #[test]
    fn tampon_trop_court_refuse() {
        for len in 0..HEADER_BYTES {
            let raw = vec![0u8; len];
            assert_eq!(
                BufferHeader::read(&raw).unwrap_err(),
                HeaderError::TooShort { len }
            );
        }
    }

    /// Un tampon qui n'ouvre pas sur le magic n'est pas un tampon AXION.
    #[test]
    fn magic_absent_refuse() {
        let mut raw = [0u8; HEADER_BYTES];
        sample().write(&mut raw);
        raw[2] = b'X';

        assert_eq!(BufferHeader::read(&raw).unwrap_err(), HeaderError::BadMagic);
    }

    /// Un kind inconnu est refusé, y compris `0`, qui est la valeur d'un tampon
    /// oublié à zéro.
    #[test]
    fn kind_inconnu_refuse() {
        for value in [0u32, 14, 99, u32::MAX] {
            let mut raw = [0u8; HEADER_BYTES];
            sample().write(&mut raw);
            raw[4..8].copy_from_slice(&value.to_le_bytes());

            assert_eq!(
                BufferHeader::read(&raw).unwrap_err(),
                HeaderError::UnknownKind { value }
            );
        }
    }

    /// Une charge utile annoncée plus grande que le tampon est refusée : c'est
    /// exactement ce qui permettrait une lecture hors bornes (R-491).
    #[test]
    fn charge_utile_qui_deborde_refusee() {
        let mut raw = [0u8; HEADER_BYTES + 4];
        BufferHeader {
            payload_len: 5,
            ..sample()
        }
        .write(&mut raw);

        assert_eq!(
            BufferHeader::read(&raw).unwrap_err(),
            HeaderError::PayloadOverflow {
                declared: 5,
                available: 4,
            }
        );

        // Le cas extrême doit être refusé sans déborder ni boucler.
        let mut raw = [0u8; HEADER_BYTES];
        BufferHeader {
            payload_len: u64::MAX,
            ..sample()
        }
        .write(&mut raw);
        assert!(matches!(
            BufferHeader::read(&raw).unwrap_err(),
            HeaderError::PayloadOverflow { .. }
        ));
    }

    /// Les valeurs numériques des kinds font partie de l'ABI : les changer
    /// romprait la compatibilité avec un JAR déjà distribué.
    #[test]
    fn valeurs_d_abi_des_kinds() {
        assert_eq!(BufferKind::SimIn.as_u32(), 1);
        assert_eq!(BufferKind::Debug.as_u32(), 13);
        assert_eq!(BufferKind::ALL.len(), 13);

        for (index, kind) in BufferKind::ALL.iter().enumerate() {
            assert_eq!(kind.as_u32(), index as u32 + 1, "{kind} mal numéroté");
            assert_eq!(BufferKind::from_u32(kind.as_u32()), Some(*kind));
        }
        // Zéro reste invalide, pour qu'un champ non initialisé soit rejeté.
        assert_eq!(BufferKind::from_u32(0), None);
    }

    /// Le sens de circulation est cohérent avec le nom du kind.
    #[test]
    fn sens_de_circulation() {
        assert_eq!(BufferKind::SimIn.direction(), Direction::JavaToNative);
        assert_eq!(BufferKind::ImpactIn.direction(), Direction::JavaToNative);
        assert_eq!(BufferKind::SimOut.direction(), Direction::NativeToJava);
        assert_eq!(BufferKind::RenderOut.direction(), Direction::NativeToJava);
    }
}
