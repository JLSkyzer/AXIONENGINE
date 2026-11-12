//! L'état complet d'une assembly, tel que `SIM_OUT` le porte (ADR-168 D1) : une section après les motopropulseurs
//! (schéma 6), un enregistrement par assembly dont l'export est demandé (`SET_STATE_EXPORT`).
//!
//! ```text
//! section     8 o : nombre u32 | 4 o à 0
//! entrée     16 o : handle (index u32, génération u32) | longueur u32 | statut u32, puis `longueur` octets
//! ```
//!
//! Les octets sont le bloc opaque d'`ax-physics` (`world::state`), toujours d'une longueur multiple de 8 ; le statut
//! est celui de la dernière restauration de l'assembly (`SET_ASSEMBLY_STATE`) : 0 si aucune n'a échoué depuis le
//! dernier export, sinon le code de [`state_status`].

use super::handle::Handle;

/// Le statut de la dernière restauration d'une assembly exportée.
pub mod state_status {
    /// Aucune restauration n'a échoué depuis le dernier export.
    pub const OK: u32 = 0;
    /// Un bloc plus court ou plus long que ses comptes.
    pub const LENGTH: u32 = 1;
    /// Une version inconnue.
    pub const VERSION: u32 = 2;
    /// Un compte au-delà de ce que le format porte.
    pub const TOO_LARGE: u32 = 3;
    /// Une valeur non finie.
    pub const NOT_FINITE: u32 = 4;
    /// Un quaternion non unitaire.
    pub const ROTATION: u32 = 5;
    /// L'assembly n'existe pas.
    pub const UNKNOWN_ASSEMBLY: u32 = 6;
    /// Des comptes qui ne sont pas ceux de l'assembly vivante.
    pub const MISMATCH: u32 = 7;
    /// Un joint intact dans l'état, rompu ici : l'assembly doit se recréer.
    pub const IRRECONCILABLE: u32 = 8;
}

/// L'état exporté d'une assembly.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AssemblyStateReport {
    /// L'assembly.
    pub handle: Handle,
    /// Le statut de sa dernière restauration ([`state_status`]).
    pub status: u32,
    /// Le bloc opaque de son état.
    pub bytes: Vec<u8>,
}

impl AssemblyStateReport {
    /// Octets de l'en-tête de la section.
    pub const SECTION_HEADER_BYTES: usize = 8;
    /// Octets de l'en-tête d'une entrée.
    pub const ENTRY_HEADER_BYTES: usize = 16;

    /// Écrit la section : le nombre d'entrées, puis chacune, en-tête et octets, alignée sur 8.
    ///
    /// # Panics
    ///
    /// Au-delà de `u32::MAX` entrées ou d'un bloc de `u32::MAX` octets.
    pub fn write_section(states: &[Self], out: &mut Vec<u8>) {
        let count = u32::try_from(states.len()).expect("au plus u32::MAX états");
        out.extend_from_slice(&count.to_le_bytes());
        out.extend_from_slice(&[0u8; 4]);
        for state in states {
            out.extend_from_slice(&state.handle.index.to_le_bytes());
            out.extend_from_slice(&state.handle.generation.to_le_bytes());
            let length = u32::try_from(state.bytes.len()).expect("au plus u32::MAX octets");
            out.extend_from_slice(&length.to_le_bytes());
            out.extend_from_slice(&state.status.to_le_bytes());
            out.extend_from_slice(&state.bytes);
            let padding = (8 - state.bytes.len() % 8) % 8;
            out.extend(std::iter::repeat_n(0u8, padding));
        }
    }

    /// Lit la section au début de `bytes` ; `None` si elle est tronquée.
    #[must_use]
    pub fn read_section(bytes: &[u8]) -> Option<Vec<Self>> {
        let count = u32::from_le_bytes(bytes.get(..4)?.try_into().ok()?);
        let mut at = Self::SECTION_HEADER_BYTES;
        let mut states = Vec::new();
        for _ in 0..count {
            let word = |offset: usize| -> Option<u32> {
                Some(u32::from_le_bytes(
                    bytes.get(at + offset..at + offset + 4)?.try_into().ok()?,
                ))
            };
            let handle = Handle {
                index: word(0)?,
                generation: word(4)?,
            };
            let length = usize::try_from(word(8)?).ok()?;
            let status = word(12)?;
            let start = at.checked_add(Self::ENTRY_HEADER_BYTES)?;
            let end = start.checked_add(length)?;
            states.push(Self {
                handle,
                status,
                bytes: bytes.get(start..end)?.to_vec(),
            });
            at = end.checked_add((8 - length % 8) % 8)?;
        }
        Some(states)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn la_section_fait_l_aller_retour_alignee_sur_8() {
        let states = vec![
            AssemblyStateReport {
                handle: Handle {
                    index: 7,
                    generation: 1,
                },
                status: state_status::OK,
                bytes: (0..16).collect(),
            },
            AssemblyStateReport {
                handle: Handle {
                    index: 9,
                    generation: 2,
                },
                status: state_status::MISMATCH,
                bytes: vec![1, 2, 3],
            },
        ];
        let mut out = Vec::new();
        AssemblyStateReport::write_section(&states, &mut out);
        assert_eq!(
            out.len(),
            8 + 16 + 16 + 16 + 8,
            "le second bloc s'aligne sur 8"
        );
        assert_eq!(AssemblyStateReport::read_section(&out), Some(states));
        assert_eq!(
            AssemblyStateReport::read_section(&out[..out.len() - 9]),
            None,
            "tronquée"
        );
    }
}
