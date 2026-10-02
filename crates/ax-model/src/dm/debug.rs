//! Géométrie de debug (C-67, ADR-121) : contenu du tampon `DEBUG`.
//!
//! Le natif y dépose, à la demande, la géométrie des overlays allumés ; Java la
//! dessine. Charge utile (schéma 1 de `DEBUG`), petit-boutiste :
//!
//! ```text
//! offset 0   u32 body_count
//!        4   u32 segment_count
//!        8   u32 flags            debug_flags::TRUNCATED
//!        12  u32 omitted_bodies
//!        16  DebugBody[body_count]         24 o chacun
//!            DebugSegment[segment_count]   24 o chacun
//! ```
//!
//! Les segments sont en **repère du corps** (= espace de l'asset) : Java les place
//! à la pose interpolée qu'il donne déjà au maillage, si bien que les lignes
//! suivent le corps dessiné sans décalage.

use crate::dm::handle::Handle;

/// Taille de l'en-tête de la charge utile : quatre `u32`.
pub const DEBUG_HEADER_BYTES: usize = 16;

/// Overlays du debug renderer : bit `i` = rang de l'overlay dans la liste du
/// §31.4 (`colliders` = 0, `aabb` = 1, … `quality` = 34). Ajout en fin
/// uniquement.
pub mod overlay {
    /// `colliders` : les colliders des corps, tournés avec eux (ADR-121).
    pub const COLLIDERS: u64 = 1 << 0;

    /// Nombre d'overlays de la liste du §31.4 : le masque tient sur 64 bits.
    pub const COUNT: u32 = 35;

    // Vérifié à la compilation : un overlay ajouté au-delà de 64 casserait le masque.
    const _: () = assert!(COUNT <= u64::BITS);
}

/// Drapeaux de l'en-tête.
pub mod debug_flags {
    /// Des corps ont été omis faute de budget ; `omitted_bodies` dit combien.
    pub const TRUNCATED: u32 = 1;
}

/// Drapeaux d'un corps tracé. Aucun drapeau de genre : corps dynamique.
pub mod debug_body_flags {
    /// Corps statique.
    pub const STATIC: u32 = 1;
    /// Corps cinématique.
    pub const KINEMATIC: u32 = 1 << 1;
    /// Corps endormi.
    pub const SLEEPING: u32 = 1 << 2;
}

/// Un corps tracé et la plage de ses segments.
///
/// **Disposition figée** : 24 octets, alignement 4.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DebugBody {
    /// Assembly du corps (DM-01).
    pub handle: Handle,
    /// Premier segment, dans `DebugSegment[]`.
    pub first_segment: u32,
    /// Nombre de segments.
    pub segment_count: u32,
    /// Drapeaux, voir [`debug_body_flags`].
    pub flags: u32,
    /// Réservé, à zéro.
    pub _pad: u32,
}

impl DebugBody {
    /// Taille de la structure sur la frontière, en octets.
    pub const BYTES: usize = 24;

    /// Sérialise en little-endian, dans la disposition figée, à la fin de `out`.
    pub fn write_le(&self, out: &mut Vec<u8>) {
        for value in [
            self.handle.index,
            self.handle.generation,
            self.first_segment,
            self.segment_count,
            self.flags,
            0,
        ] {
            out.extend_from_slice(&value.to_le_bytes());
        }
    }

    /// Relit un corps écrit par [`DebugBody::write_le`].
    #[must_use]
    pub fn read_le(bytes: &[u8; Self::BYTES]) -> Self {
        let at = |rank: usize| {
            let start = rank * 4;
            u32::from_le_bytes([
                bytes[start],
                bytes[start + 1],
                bytes[start + 2],
                bytes[start + 3],
            ])
        };
        DebugBody {
            handle: Handle::new(at(0), at(1)),
            first_segment: at(2),
            segment_count: at(3),
            flags: at(4),
            _pad: at(5),
        }
    }
}

/// Un segment de ligne, en repère du corps.
///
/// **Disposition figée** : 24 octets, alignement 4.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct DebugSegment {
    /// Première extrémité.
    pub a: [f32; 3],
    /// Seconde extrémité.
    pub b: [f32; 3],
}

impl DebugSegment {
    /// Taille de la structure sur la frontière, en octets.
    pub const BYTES: usize = 24;

    /// Sérialise en little-endian, dans la disposition figée, à la fin de `out`.
    pub fn write_le(&self, out: &mut Vec<u8>) {
        for value in self.a.iter().chain(self.b.iter()) {
            out.extend_from_slice(&value.to_le_bytes());
        }
    }

    /// Relit un segment écrit par [`DebugSegment::write_le`].
    #[must_use]
    pub fn read_le(bytes: &[u8; Self::BYTES]) -> Self {
        let at = |rank: usize| {
            let start = rank * 4;
            f32::from_le_bytes([
                bytes[start],
                bytes[start + 1],
                bytes[start + 2],
                bytes[start + 3],
            ])
        };
        DebugSegment {
            a: [at(0), at(1), at(2)],
            b: [at(3), at(4), at(5)],
        }
    }
}

/// Taille de la charge utile pour ces dénombrements, ou `None` si elle déborde.
#[must_use]
pub fn debug_payload_len(bodies: usize, segments: usize) -> Option<usize> {
    bodies
        .checked_mul(DebugBody::BYTES)?
        .checked_add(segments.checked_mul(DebugSegment::BYTES)?)?
        .checked_add(DEBUG_HEADER_BYTES)
}

/// Encode la charge utile de `DEBUG` (schéma 1).
///
/// Rend `None` si un dénombrement ne tient pas sur `u32`, ou si la plage de
/// segments d'un corps sort du tableau des segments : une charge incohérente
/// n'est jamais déposée.
#[must_use]
pub fn encode_debug_payload(
    bodies: &[DebugBody],
    segments: &[DebugSegment],
    flags: u32,
    omitted_bodies: u32,
) -> Option<Vec<u8>> {
    let body_count = u32::try_from(bodies.len()).ok()?;
    let segment_count = u32::try_from(segments.len()).ok()?;
    for body in bodies {
        let end = body.first_segment.checked_add(body.segment_count)?;
        if end > segment_count {
            return None;
        }
    }
    let len = debug_payload_len(bodies.len(), segments.len())?;
    let mut out = Vec::with_capacity(len);
    for value in [body_count, segment_count, flags, omitted_bodies] {
        out.extend_from_slice(&value.to_le_bytes());
    }
    for body in bodies {
        body.write_le(&mut out);
    }
    for segment in segments {
        segment.write_le(&mut out);
    }
    debug_assert_eq!(out.len(), len);
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dm_debug_body_disposition_figee() {
        use core::mem::{align_of, offset_of, size_of};
        assert_eq!(size_of::<DebugBody>(), DebugBody::BYTES);
        assert_eq!(align_of::<DebugBody>(), 4);
        assert_eq!(offset_of!(DebugBody, handle), 0);
        assert_eq!(offset_of!(DebugBody, first_segment), 8);
        assert_eq!(offset_of!(DebugBody, segment_count), 12);
        assert_eq!(offset_of!(DebugBody, flags), 16);
        assert_eq!(offset_of!(DebugBody, _pad), 20);
    }

    #[test]
    fn dm_debug_segment_disposition_figee() {
        use core::mem::{align_of, offset_of, size_of};
        assert_eq!(size_of::<DebugSegment>(), DebugSegment::BYTES);
        assert_eq!(align_of::<DebugSegment>(), 4);
        assert_eq!(offset_of!(DebugSegment, a), 0);
        assert_eq!(offset_of!(DebugSegment, b), 12);
    }

    #[test]
    fn la_charge_respecte_la_disposition_octet_par_octet() {
        let bodies = [DebugBody {
            handle: Handle::new(7, 1),
            first_segment: 0,
            segment_count: 1,
            flags: debug_body_flags::SLEEPING,
            _pad: 0,
        }];
        let segments = [DebugSegment {
            a: [-0.5, 0.0, 0.25],
            b: [0.5, 1.0, -0.25],
        }];
        let bytes = encode_debug_payload(&bodies, &segments, debug_flags::TRUNCATED, 3).unwrap();
        assert_eq!(
            bytes.len(),
            DEBUG_HEADER_BYTES + DebugBody::BYTES + DebugSegment::BYTES
        );

        let u32_at = |at: usize| u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap());
        let f32_at = |at: usize| f32::from_le_bytes(bytes[at..at + 4].try_into().unwrap());
        // En-tête.
        assert_eq!(u32_at(0), 1, "body_count");
        assert_eq!(u32_at(4), 1, "segment_count");
        assert_eq!(u32_at(8), debug_flags::TRUNCATED, "flags");
        assert_eq!(u32_at(12), 3, "omitted_bodies");
        // DebugBody à 16.
        assert_eq!(u32_at(16), 7, "handle.index");
        assert_eq!(u32_at(20), 1, "handle.generation");
        assert_eq!(u32_at(24), 0, "first_segment");
        assert_eq!(u32_at(28), 1, "segment_count");
        assert_eq!(u32_at(32), debug_body_flags::SLEEPING, "flags");
        assert_eq!(u32_at(36), 0, "_pad");
        // DebugSegment à 40.
        assert_eq!(f32_at(40), -0.5);
        assert_eq!(f32_at(48), 0.25);
        assert_eq!(f32_at(52), 0.5);
        assert_eq!(f32_at(60), -0.25);

        let body = DebugBody::read_le(bytes[16..40].try_into().unwrap());
        assert_eq!(body, bodies[0]);
        let segment = DebugSegment::read_le(bytes[40..64].try_into().unwrap());
        assert_eq!(segment, segments[0]);
    }

    #[test]
    fn une_plage_de_segments_hors_du_tableau_n_est_jamais_encodee() {
        let bodies = [DebugBody {
            handle: Handle::new(1, 1),
            first_segment: 1,
            segment_count: 1,
            flags: 0,
            _pad: 0,
        }];
        let segments = [DebugSegment {
            a: [0.0; 3],
            b: [1.0; 3],
        }];
        assert!(encode_debug_payload(&bodies, &segments, 0, 0).is_none());
    }

    #[test]
    fn une_charge_vide_est_valide() {
        let bytes = encode_debug_payload(&[], &[], 0, 0).unwrap();
        assert_eq!(bytes, vec![0u8; DEBUG_HEADER_BYTES]);
    }

    #[test]
    fn colliders_est_le_rang_zero_du_paragraphe_31_4() {
        // La tenue du masque sur 64 bits est vérifiée à la compilation (module `overlay`).
        assert_eq!(overlay::COLLIDERS, 1);
    }
}
