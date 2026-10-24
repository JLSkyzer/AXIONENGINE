//! Géométrie de debug (C-67, ADR-121 et son amendement A1) : contenu du tampon `DEBUG`.
//!
//! Le natif y dépose, à la demande, la géométrie des overlays allumés ; Java la
//! dessine. Charge utile (schéma 2 de `DEBUG`), petit-boutiste :
//!
//! ```text
//! offset 0   u32 range_count
//!        4   u32 segment_count
//!        8   u32 flags            debug_flags::TRUNCATED
//!        12  u32 omitted_ranges
//!        16  DebugRange[range_count]       32 o chacune
//!            DebugSegment[segment_count]   24 o chacun
//! ```
//!
//! Chaque plage dit son overlay et son repère ([`debug_frame`]). En **repère du
//! corps** (= espace de l'asset), Java place les segments à la pose interpolée qu'il
//! donne déjà au maillage, si bien que les lignes suivent le corps dessiné. En
//! **repère d'origine**, les segments sont dans le monde, relatifs à la position
//! caméra passée à l'appel : Java la rajoute en `double`, ce qui garde la précision
//! loin de l'origine du monde.

use crate::dm::handle::Handle;

/// Taille de l'en-tête de la charge utile : quatre `u32`.
pub const DEBUG_HEADER_BYTES: usize = 16;

/// Overlays du debug renderer : bit `i` = rang de l'overlay dans la liste du
/// §31.4 (`colliders` = 0, `aabb` = 1, … `quality` = 34). Ajout en fin
/// uniquement.
pub mod overlay {
    /// `colliders` : les colliders des corps, tournés avec eux (ADR-121).
    pub const COLLIDERS: u64 = 1 << 0;
    /// `aabb` : l'AABB monde de chaque collider des corps (A1 d'ADR-121).
    pub const AABB: u64 = 1 << 1;
    /// `bodies` : les axes de chaque corps, à son origine (A1).
    pub const BODIES: u64 = 1 << 2;
    /// `contacts` : les points de contact du dernier pas (A1).
    pub const CONTACTS: u64 = 1 << 3;
    /// `normals` : la normale de chaque point de contact (A1).
    pub const NORMALS: u64 = 1 << 4;
    /// `joints` : chaque joint, d'un ancrage à l'autre, avec son axe (ADR-151 D7).
    pub const JOINTS: u64 = 1 << 5;
    /// `limits` : la plage permise de l'axe libre de chaque joint limité, et sa position
    /// courante (ADR-151 D7).
    pub const LIMITS: u64 = 1 << 6;
    /// `world_tiles` : les tuiles de collision du monde (C-38, A1).
    pub const WORLD_TILES: u64 = 1 << 30;
    /// `center_of_mass` : le centre de masse de chaque corps (A1).
    pub const CENTER_OF_MASS: u64 = 1 << 31;

    /// Nombre d'overlays de la liste du §31.4 : le masque tient sur 64 bits.
    pub const COUNT: u32 = 35;

    // Vérifié à la compilation : un overlay ajouté au-delà de 64 casserait le masque.
    const _: () = assert!(COUNT <= u64::BITS);

    /// Le rang au §31.4 d'un overlay désigné par son bit.
    #[must_use]
    pub const fn rank(bit: u64) -> u32 {
        bit.trailing_zeros()
    }
}

/// Repère des segments d'une plage.
pub mod debug_frame {
    /// Repère du corps : l'espace de l'asset, placé par Java à la pose interpolée.
    pub const BODY: u32 = 0;
    /// Monde, relatif à la position caméra passée à l'appel.
    pub const ORIGIN: u32 = 1;
}

/// Drapeaux de l'en-tête.
pub mod debug_flags {
    /// Des plages ont été omises faute de budget ; `omitted_ranges` dit combien.
    pub const TRUNCATED: u32 = 1;
}

/// Drapeaux d'une plage d'un corps : son genre et son état. Aucun drapeau de
/// genre : corps dynamique. Nuls pour ce qui n'est pas un corps d'assembly.
pub mod debug_body_flags {
    /// Corps statique.
    pub const STATIC: u32 = 1;
    /// Corps cinématique.
    pub const KINEMATIC: u32 = 1 << 1;
    /// Corps endormi.
    pub const SLEEPING: u32 = 1 << 2;
    /// Joint rompu : plages `joints` (ADR-151 D7).
    pub const JOINT_BROKEN: u32 = 1 << 3;
    /// Joint grippé : plages `joints` et `limits` (ADR-151 D7).
    pub const JOINT_JAMMED: u32 = 1 << 4;
}

/// Une plage de segments : ceux d'un overlay pour un corps ou une tuile, dans un
/// repère.
///
/// **Disposition figée** : 32 octets, alignement 4.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DebugRange {
    /// Assembly du corps (DM-01) ; [`Handle::ABSENT`] pour une tuile du monde.
    pub handle: Handle,
    /// Rang de l'overlay au §31.4, voir [`overlay`].
    pub overlay: u32,
    /// Repère des segments, voir [`debug_frame`].
    pub frame: u32,
    /// Premier segment, dans `DebugSegment[]`.
    pub first_segment: u32,
    /// Nombre de segments.
    pub segment_count: u32,
    /// Drapeaux, voir [`debug_body_flags`].
    pub flags: u32,
    /// Réservé, à zéro.
    pub _pad: u32,
}

impl DebugRange {
    /// Taille de la structure sur la frontière, en octets.
    pub const BYTES: usize = 32;

    /// Sérialise en little-endian, dans la disposition figée, à la fin de `out`.
    pub fn write_le(&self, out: &mut Vec<u8>) {
        for value in [
            self.handle.index,
            self.handle.generation,
            self.overlay,
            self.frame,
            self.first_segment,
            self.segment_count,
            self.flags,
            0,
        ] {
            out.extend_from_slice(&value.to_le_bytes());
        }
    }

    /// Relit une plage écrite par [`DebugRange::write_le`].
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
        DebugRange {
            handle: Handle::new(at(0), at(1)),
            overlay: at(2),
            frame: at(3),
            first_segment: at(4),
            segment_count: at(5),
            flags: at(6),
            _pad: at(7),
        }
    }
}

/// Un segment de ligne, dans le repère de sa plage.
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
pub fn debug_payload_len(ranges: usize, segments: usize) -> Option<usize> {
    ranges
        .checked_mul(DebugRange::BYTES)?
        .checked_add(segments.checked_mul(DebugSegment::BYTES)?)?
        .checked_add(DEBUG_HEADER_BYTES)
}

/// Encode la charge utile de `DEBUG` (schéma 2).
///
/// Rend `None` si un dénombrement ne tient pas sur `u32`, ou si la plage de
/// segments d'une plage sort du tableau des segments : une charge incohérente
/// n'est jamais déposée.
#[must_use]
pub fn encode_debug_payload(
    ranges: &[DebugRange],
    segments: &[DebugSegment],
    flags: u32,
    omitted_ranges: u32,
) -> Option<Vec<u8>> {
    let range_count = u32::try_from(ranges.len()).ok()?;
    let segment_count = u32::try_from(segments.len()).ok()?;
    for range in ranges {
        let end = range.first_segment.checked_add(range.segment_count)?;
        if end > segment_count {
            return None;
        }
    }
    let len = debug_payload_len(ranges.len(), segments.len())?;
    let mut out = Vec::with_capacity(len);
    for value in [range_count, segment_count, flags, omitted_ranges] {
        out.extend_from_slice(&value.to_le_bytes());
    }
    for range in ranges {
        range.write_le(&mut out);
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
    fn dm_debug_range_disposition_figee() {
        use core::mem::{align_of, offset_of, size_of};
        assert_eq!(size_of::<DebugRange>(), DebugRange::BYTES);
        assert_eq!(align_of::<DebugRange>(), 4);
        assert_eq!(offset_of!(DebugRange, handle), 0);
        assert_eq!(offset_of!(DebugRange, overlay), 8);
        assert_eq!(offset_of!(DebugRange, frame), 12);
        assert_eq!(offset_of!(DebugRange, first_segment), 16);
        assert_eq!(offset_of!(DebugRange, segment_count), 20);
        assert_eq!(offset_of!(DebugRange, flags), 24);
        assert_eq!(offset_of!(DebugRange, _pad), 28);
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
        let ranges = [DebugRange {
            handle: Handle::new(7, 1),
            overlay: overlay::rank(overlay::CONTACTS),
            frame: debug_frame::ORIGIN,
            first_segment: 0,
            segment_count: 1,
            flags: debug_body_flags::SLEEPING,
            _pad: 0,
        }];
        let segments = [DebugSegment {
            a: [-0.5, 0.0, 0.25],
            b: [0.5, 1.0, -0.25],
        }];
        let bytes = encode_debug_payload(&ranges, &segments, debug_flags::TRUNCATED, 3).unwrap();
        assert_eq!(
            bytes.len(),
            DEBUG_HEADER_BYTES + DebugRange::BYTES + DebugSegment::BYTES
        );

        let u32_at = |at: usize| u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap());
        let f32_at = |at: usize| f32::from_le_bytes(bytes[at..at + 4].try_into().unwrap());
        // En-tête.
        assert_eq!(u32_at(0), 1, "range_count");
        assert_eq!(u32_at(4), 1, "segment_count");
        assert_eq!(u32_at(8), debug_flags::TRUNCATED, "flags");
        assert_eq!(u32_at(12), 3, "omitted_ranges");
        // DebugRange à 16.
        assert_eq!(u32_at(16), 7, "handle.index");
        assert_eq!(u32_at(20), 1, "handle.generation");
        assert_eq!(u32_at(24), 3, "overlay : contacts, rang 3");
        assert_eq!(u32_at(28), debug_frame::ORIGIN, "frame");
        assert_eq!(u32_at(32), 0, "first_segment");
        assert_eq!(u32_at(36), 1, "segment_count");
        assert_eq!(u32_at(40), debug_body_flags::SLEEPING, "flags");
        assert_eq!(u32_at(44), 0, "_pad");
        // DebugSegment à 48.
        assert_eq!(f32_at(48), -0.5);
        assert_eq!(f32_at(56), 0.25);
        assert_eq!(f32_at(60), 0.5);
        assert_eq!(f32_at(68), -0.25);

        let range = DebugRange::read_le(bytes[16..48].try_into().unwrap());
        assert_eq!(range, ranges[0]);
        let segment = DebugSegment::read_le(bytes[48..72].try_into().unwrap());
        assert_eq!(segment, segments[0]);
    }

    #[test]
    fn une_plage_de_segments_hors_du_tableau_n_est_jamais_encodee() {
        let ranges = [DebugRange {
            handle: Handle::new(1, 1),
            overlay: 0,
            frame: debug_frame::BODY,
            first_segment: 1,
            segment_count: 1,
            flags: 0,
            _pad: 0,
        }];
        let segments = [DebugSegment {
            a: [0.0; 3],
            b: [1.0; 3],
        }];
        assert!(encode_debug_payload(&ranges, &segments, 0, 0).is_none());
    }

    #[test]
    fn une_charge_vide_est_valide() {
        let bytes = encode_debug_payload(&[], &[], 0, 0).unwrap();
        assert_eq!(bytes, vec![0u8; DEBUG_HEADER_BYTES]);
    }

    #[test]
    fn les_overlays_portent_leur_rang_du_paragraphe_31_4() {
        // La tenue du masque sur 64 bits est vérifiée à la compilation (module `overlay`).
        for (bit, rang) in [
            (overlay::COLLIDERS, 0),
            (overlay::AABB, 1),
            (overlay::BODIES, 2),
            (overlay::CONTACTS, 3),
            (overlay::NORMALS, 4),
            (overlay::WORLD_TILES, 30),
            (overlay::CENTER_OF_MASS, 31),
        ] {
            assert_eq!(overlay::rank(bit), rang);
        }
    }
}
