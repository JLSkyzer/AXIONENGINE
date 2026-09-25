//! Section `PHYS` : `ColliderDesc[]` (C-32, DM-06).
//!
//! Disposition, en petit-boutiste, fixée par `docs/decisions/ADR-115.md` :
//!
//! ```text
//! u32 collider_count
//! ColliderDesc[collider_count]      104 octets chacun, disposition repr(C)
//! ```
//!
//! Les colliders sont écrits sur leurs **104 octets** `repr(C)` — la forme
//! (`ColliderShape`, enum `repr(C, u32)`) sur ses 28 octets, discriminant en
//! tête puis l'union dimensionnée sur la plus grande variante, remplissage à
//! zéro. Seules `Sphere` et `Box` sont produites aujourd'hui ; les variantes
//! indexées et leurs tableaux annexes viendront avec leur producteur, en
//! incrémentant `COMPILER_VERSION` (ADR-115).

use super::{A3dError, SectionTag};
use ax_model::dm::geometry::Transform;
use ax_model::dm::limits;
use ax_model::dm::physics::{ColliderDesc, ColliderShape};

/// Taille d'une forme sérialisée : celle de `ColliderShape` (`repr(C, u32)`).
const SHAPE_BYTES: usize = 28;

/// Taille d'un collider sérialisé : celle de `ColliderDesc`.
pub const COLLIDER_BYTES: usize = 104;

/// En-tête de la section : nombre de colliders.
const HEADER_BYTES: usize = 4;

/// Encode une section `PHYS`.
///
/// # Errors
///
/// [`A3dError::SectionUnwritable`] si le nombre de colliders dépasse ce qu'un
/// `u32` décrit.
pub fn encode_colliders(colliders: &[ColliderDesc]) -> Result<Vec<u8>, A3dError> {
    let count = u32::try_from(colliders.len()).map_err(|_| unwritable())?;
    let mut out = Vec::with_capacity(HEADER_BYTES + colliders.len() * COLLIDER_BYTES);
    out.extend_from_slice(&count.to_le_bytes());
    for collider in colliders {
        write_collider(&mut out, collider);
    }
    Ok(out)
}

/// Décode une section `PHYS` venue d'un fichier qu'on ne croit pas sur parole.
///
/// # Errors
///
/// [`A3dError::MalformedSection`] au premier écart : en-tête tronqué, plus de
/// colliders que C-22 n'en admet — vérifié **avant** toute allocation (R-901) —,
/// taille incohérente, ou forme de discriminant inconnu.
pub fn decode_colliders(bytes: &[u8]) -> Result<Vec<ColliderDesc>, A3dError> {
    if bytes.len() < HEADER_BYTES {
        return Err(malformed("en-tête tronqué"));
    }
    let count = read_u32(bytes, 0) as usize;
    if count > limits::MAX_COLLIDERS {
        return Err(malformed("plus de colliders que C-22 n'en admet"));
    }
    if HEADER_BYTES.checked_add(count * COLLIDER_BYTES) != Some(bytes.len()) {
        return Err(malformed("taille incohérente avec le dénombrement"));
    }

    let mut colliders = Vec::with_capacity(count);
    for index in 0..count {
        colliders.push(read_collider(bytes, HEADER_BYTES + index * COLLIDER_BYTES)?);
    }
    Ok(colliders)
}

fn malformed(detail: &'static str) -> A3dError {
    A3dError::MalformedSection {
        tag: SectionTag::PHYS,
        detail,
    }
}

fn unwritable() -> A3dError {
    A3dError::SectionUnwritable(SectionTag::PHYS)
}

/// Écrit un collider sur ses 104 octets `repr(C)`.
fn write_collider(out: &mut Vec<u8>, collider: &ColliderDesc) {
    write_shape(out, &collider.shape);
    for value in collider.local.translation {
        out.extend_from_slice(&value.to_le_bytes());
    }
    for value in collider.local.rotation {
        out.extend_from_slice(&value.to_le_bytes());
    }
    for value in collider.local.scale {
        out.extend_from_slice(&value.to_le_bytes());
    }
    out.extend_from_slice(&collider.material.to_le_bytes());
    out.extend_from_slice(&collider._pad.to_le_bytes());
    out.extend_from_slice(&collider.group.to_le_bytes());
    out.extend_from_slice(&collider.mask.to_le_bytes());
    out.extend_from_slice(&collider.flags.to_le_bytes());
    out.extend_from_slice(&collider.density.to_le_bytes());
    out.extend_from_slice(&collider.damage_zone.to_le_bytes());
    out.extend_from_slice(&collider.part.to_le_bytes());
    out.extend_from_slice(&collider.region.to_le_bytes());
    out.extend_from_slice(&collider._pad2.to_le_bytes());
    out.extend_from_slice(&collider.hull_points_offset.to_le_bytes());
    out.extend_from_slice(&collider.hull_points_count.to_le_bytes());
}

/// Écrit une forme sur ses 28 octets : discriminant `repr(C, u32)` en tête, puis
/// les champs de la variante, complétés de zéros jusqu'à la taille de l'union.
fn write_shape(out: &mut Vec<u8>, shape: &ColliderShape) {
    let start = out.len();
    match *shape {
        ColliderShape::Sphere { radius } => {
            out.extend_from_slice(&0u32.to_le_bytes());
            out.extend_from_slice(&radius.to_le_bytes());
        }
        ColliderShape::Box { half_extents } => {
            out.extend_from_slice(&1u32.to_le_bytes());
            for value in half_extents {
                out.extend_from_slice(&value.to_le_bytes());
            }
        }
        ColliderShape::Capsule {
            half_height,
            radius,
        } => {
            out.extend_from_slice(&2u32.to_le_bytes());
            out.extend_from_slice(&half_height.to_le_bytes());
            out.extend_from_slice(&radius.to_le_bytes());
        }
        ColliderShape::Cylinder {
            half_height,
            radius,
        } => {
            out.extend_from_slice(&3u32.to_le_bytes());
            out.extend_from_slice(&half_height.to_le_bytes());
            out.extend_from_slice(&radius.to_le_bytes());
        }
        ColliderShape::Cone {
            half_height,
            radius,
        } => {
            out.extend_from_slice(&4u32.to_le_bytes());
            out.extend_from_slice(&half_height.to_le_bytes());
            out.extend_from_slice(&radius.to_le_bytes());
        }
        ColliderShape::ConvexHull {
            points_offset,
            points_count,
        } => {
            out.extend_from_slice(&5u32.to_le_bytes());
            out.extend_from_slice(&points_offset.to_le_bytes());
            out.extend_from_slice(&points_count.to_le_bytes());
        }
        ColliderShape::TriMesh {
            vertices_offset,
            vertices_count,
            indices_offset,
            indices_count,
        } => {
            out.extend_from_slice(&6u32.to_le_bytes());
            out.extend_from_slice(&vertices_offset.to_le_bytes());
            out.extend_from_slice(&vertices_count.to_le_bytes());
            out.extend_from_slice(&indices_offset.to_le_bytes());
            out.extend_from_slice(&indices_count.to_le_bytes());
        }
        ColliderShape::Heightfield {
            rows,
            cols,
            data_offset,
            scale,
        } => {
            out.extend_from_slice(&7u32.to_le_bytes());
            out.extend_from_slice(&rows.to_le_bytes());
            out.extend_from_slice(&cols.to_le_bytes());
            out.extend_from_slice(&data_offset.to_le_bytes());
            for value in scale {
                out.extend_from_slice(&value.to_le_bytes());
            }
        }
        ColliderShape::Compound {
            children_offset,
            children_count,
        } => {
            out.extend_from_slice(&8u32.to_le_bytes());
            out.extend_from_slice(&children_offset.to_le_bytes());
            out.extend_from_slice(&children_count.to_le_bytes());
        }
    }
    out.resize(start + SHAPE_BYTES, 0);
}

/// Lit un collider ; `at + COLLIDER_BYTES` est dans les bornes (vérifié par
/// l'appelant).
fn read_collider(bytes: &[u8], at: usize) -> Result<ColliderDesc, A3dError> {
    let f32_at = |offset: usize| f32::from_le_bytes(array(bytes, at + offset));
    let u16_at = |offset: usize| u16::from_le_bytes(array(bytes, at + offset));
    Ok(ColliderDesc {
        shape: read_shape(bytes, at)?,
        local: Transform {
            translation: [f32_at(28), f32_at(32), f32_at(36)],
            rotation: [f32_at(40), f32_at(44), f32_at(48), f32_at(52)],
            scale: [f32_at(56), f32_at(60), f32_at(64)],
        },
        material: u16_at(68),
        _pad: 0,
        group: read_u32(bytes, at + 72),
        mask: read_u32(bytes, at + 76),
        flags: read_u32(bytes, at + 80),
        density: f32_at(84),
        damage_zone: u16_at(88),
        part: u16_at(90),
        region: u16_at(92),
        _pad2: 0,
        hull_points_offset: read_u32(bytes, at + 96),
        hull_points_count: read_u32(bytes, at + 100),
    })
}

/// Lit une forme depuis ses 28 octets ; un discriminant inconnu est refusé
/// (données hostiles).
fn read_shape(bytes: &[u8], at: usize) -> Result<ColliderShape, A3dError> {
    // Les champs de la variante suivent le discriminant, à l'octet `at + 4`.
    let f = |index: usize| f32::from_le_bytes(array(bytes, at + 4 + index * 4));
    let u = |index: usize| read_u32(bytes, at + 4 + index * 4);
    Ok(match read_u32(bytes, at) {
        0 => ColliderShape::Sphere { radius: f(0) },
        1 => ColliderShape::Box {
            half_extents: [f(0), f(1), f(2)],
        },
        2 => ColliderShape::Capsule {
            half_height: f(0),
            radius: f(1),
        },
        3 => ColliderShape::Cylinder {
            half_height: f(0),
            radius: f(1),
        },
        4 => ColliderShape::Cone {
            half_height: f(0),
            radius: f(1),
        },
        5 => ColliderShape::ConvexHull {
            points_offset: u(0),
            points_count: u(1),
        },
        6 => ColliderShape::TriMesh {
            vertices_offset: u(0),
            vertices_count: u(1),
            indices_offset: u(2),
            indices_count: u(3),
        },
        7 => ColliderShape::Heightfield {
            rows: u(0),
            cols: u(1),
            data_offset: u(2),
            scale: [f(3), f(4), f(5)],
        },
        8 => ColliderShape::Compound {
            children_offset: u(0),
            children_count: u(1),
        },
        _ => return Err(malformed("forme de collider de discriminant inconnu")),
    })
}

fn read_u32(bytes: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(array(bytes, at))
}

fn array<const N: usize>(bytes: &[u8], at: usize) -> [u8; N] {
    let mut out = [0; N];
    out.copy_from_slice(&bytes[at..at + N]);
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use core::mem::size_of;

    fn collider(shape: ColliderShape) -> ColliderDesc {
        ColliderDesc {
            shape,
            local: Transform {
                translation: [1.0, 2.0, 3.0],
                ..Transform::identity()
            },
            material: 7,
            _pad: 0,
            group: 0xAABB_CCDD,
            mask: 0x0011_2233,
            flags: 8,
            density: 1000.0,
            damage_zone: u16::MAX,
            part: 5,
            region: u16::MAX,
            _pad2: 0,
            hull_points_offset: 0,
            hull_points_count: 0,
        }
    }

    #[test]
    fn les_octets_serialises_egalent_la_taille_repr_c() {
        // La sérialisation champ par champ doit couvrir exactement la struct
        // `repr(C)` : sa taille verrouille la disposition (ADR-115).
        assert_eq!(COLLIDER_BYTES, size_of::<ColliderDesc>());
        assert_eq!(SHAPE_BYTES, size_of::<ColliderShape>());
    }

    #[test]
    fn t311_les_colliders_font_l_aller_retour() {
        let colliders = vec![
            collider(ColliderShape::Box {
                half_extents: [0.5, 1.0, 1.5],
            }),
            collider(ColliderShape::Sphere { radius: 2.0 }),
        ];
        let bytes = encode_colliders(&colliders).expect("encodage");
        assert_eq!(bytes.len(), HEADER_BYTES + 2 * COLLIDER_BYTES);
        let decoded = decode_colliders(&bytes).expect("décodage");
        assert_eq!(decoded, colliders);
    }

    #[test]
    fn t311_la_disposition_de_la_section_phys_est_figee() {
        // Octets écrits à la main : c'est la disposition d'ADR-115 qui est
        // vérifiée, pas la cohérence de l'encodeur avec lui-même.
        let colliders = vec![collider(ColliderShape::Box {
            half_extents: [0.5, 1.0, 1.5],
        })];
        let bytes = encode_colliders(&colliders).expect("encodage");

        let mut attendu = Vec::new();
        attendu.extend_from_slice(&1u32.to_le_bytes()); // collider_count
                                                        // shape : Box (disc 1) + demi-dimensions, complété à 28 octets.
        attendu.extend_from_slice(&1u32.to_le_bytes());
        for value in [0.5f32, 1.0, 1.5] {
            attendu.extend_from_slice(&value.to_le_bytes());
        }
        attendu.resize(HEADER_BYTES + SHAPE_BYTES, 0);
        // local : translation (1,2,3), rotation identité, échelle (1,1,1).
        for value in [1.0f32, 2.0, 3.0, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0] {
            attendu.extend_from_slice(&value.to_le_bytes());
        }
        attendu.extend_from_slice(&7u16.to_le_bytes()); // material
        attendu.extend_from_slice(&0u16.to_le_bytes()); // _pad
        attendu.extend_from_slice(&0xAABB_CCDDu32.to_le_bytes()); // group
        attendu.extend_from_slice(&0x0011_2233u32.to_le_bytes()); // mask
        attendu.extend_from_slice(&8u32.to_le_bytes()); // flags
        attendu.extend_from_slice(&1000.0f32.to_le_bytes()); // density
        attendu.extend_from_slice(&u16::MAX.to_le_bytes()); // damage_zone
        attendu.extend_from_slice(&5u16.to_le_bytes()); // part
        attendu.extend_from_slice(&u16::MAX.to_le_bytes()); // region
        attendu.extend_from_slice(&0u16.to_le_bytes()); // _pad2
        attendu.extend_from_slice(&0u32.to_le_bytes()); // hull_points_offset
        attendu.extend_from_slice(&0u32.to_le_bytes()); // hull_points_count

        assert_eq!(bytes.len(), HEADER_BYTES + COLLIDER_BYTES);
        assert_eq!(bytes, attendu);
    }

    #[test]
    fn t311_une_section_phys_mensongere_est_refusee_sans_paniquer() {
        let colliders = vec![collider(ColliderShape::Sphere { radius: 1.0 })];
        let bytes = encode_colliders(&colliders).expect("encodage");

        // Tronquée, ou prolongée d'un octet.
        assert!(decode_colliders(&bytes[..bytes.len() - 1]).is_err());
        let mut long = bytes.clone();
        long.push(0);
        assert!(decode_colliders(&long).is_err());
        assert!(decode_colliders(&[1, 2, 3]).is_err());

        // Un dénombrement démesuré est refusé avant toute allocation.
        let mut enorme = bytes.clone();
        enorme[0..4].copy_from_slice(&u32::MAX.to_le_bytes());
        assert!(decode_colliders(&enorme).is_err());

        // Un discriminant de forme inconnu est refusé.
        let mut inconnu = bytes;
        inconnu[HEADER_BYTES..HEADER_BYTES + 4].copy_from_slice(&99u32.to_le_bytes());
        let refus = decode_colliders(&inconnu).unwrap_err();
        assert_eq!(refus.code(), -3007);
    }
}
