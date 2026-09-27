//! Section `PHYS` : `ColliderDesc[]` + points d'enveloppe (C-32, DM-06).
//!
//! Disposition, en petit-boutiste, fixée par `docs/decisions/ADR-115.md` (§1 :
//! les tableaux annexes des formes indexées « ajoutés à la suite », `COMPILER_VERSION`
//! incrémenté à leur arrivée) :
//!
//! ```text
//! u32 collider_count
//! ColliderDesc[collider_count]      104 octets chacun, disposition repr(C)
//! u32 point_count                   annexe des points d'enveloppe (ConvexHull)
//! [f32;3] hull_points[point_count]  12 octets chacun
//! ```
//!
//! Les colliders sont écrits sur leurs **104 octets** `repr(C)` — la forme
//! (`ColliderShape`, enum `repr(C, u32)`) sur ses 28 octets, discriminant en
//! tête puis l'union dimensionnée sur la plus grande variante, remplissage à
//! zéro. Une forme `ConvexHull` porte `points_offset`/`points_count` **en indices
//! de points** dans l'annexe : ses points sont `hull_points[offset .. offset+count]`.
//! `Compound` (enfants) reste à venir avec `auto_compound`, en incrémentant de
//! nouveau `COMPILER_VERSION`.

use super::{A3dError, SectionTag};
use ax_model::dm::geometry::Transform;
use ax_model::dm::limits;
use ax_model::dm::physics::{ColliderDesc, ColliderShape, CONVEX_MAX_POINTS};

/// Taille d'une forme sérialisée : celle de `ColliderShape` (`repr(C, u32)`).
const SHAPE_BYTES: usize = 28;

/// Taille d'un collider sérialisé : celle de `ColliderDesc`.
pub const COLLIDER_BYTES: usize = 104;

/// En-tête de la section : nombre de colliders.
const HEADER_BYTES: usize = 4;

/// Taille d'un point d'enveloppe sérialisé (`[f32; 3]`).
const POINT_BYTES: usize = 12;

/// Plafond du nombre total de points d'enveloppe, avant toute allocation (R-901) :
/// au plus `MAX_COLLIDERS` colliders, chacun `CONVEX_MAX_POINTS` points.
const MAX_HULL_POINTS: usize = limits::MAX_COLLIDERS * CONVEX_MAX_POINTS as usize;

/// Nombre maximal de formes filles d'un compound (§10.3).
const MAX_COMPOUND_PARTS: usize = 64;

/// Plafond du nombre total d'enfants de compound, avant toute allocation (R-901) :
/// au plus `MAX_COLLIDERS` compounds, chacun d'au plus [`MAX_COMPOUND_PARTS`] filles.
const MAX_COMPOUND_CHILDREN: usize = limits::MAX_COLLIDERS * MAX_COMPOUND_PARTS;

/// Ce qu'une section `PHYS` décodée rend : les colliders, l'annexe des points
/// d'enveloppe (`ConvexHull`) et l'annexe des enfants de compound.
pub type DecodedPhys = (Vec<ColliderDesc>, Vec<[f32; 3]>, Vec<ColliderDesc>);

/// Encode une section `PHYS` : les colliders, l'annexe des points d'enveloppe, puis
/// l'annexe des enfants de compound.
///
/// Les formes `ConvexHull` référencent l'annexe des points par
/// `points_offset`/`points_count` (indices de points) ; les formes `Compound`
/// référencent l'annexe des enfants par `children_offset`/`children_count` (indices
/// d'enfants). C'est à l'appelant d'avoir posé ces indices en accord avec `points`
/// et `children`. Un enfant est un `ColliderDesc` comme un autre (même disposition),
/// sa pose relative portée par son `local` ; il ne doit pas être lui-même un
/// `Compound` (pas d'imbrication).
///
/// # Errors
///
/// [`A3dError::SectionUnwritable`] si un dénombrement dépasse ce qu'un `u32` décrit.
pub fn encode_colliders(
    colliders: &[ColliderDesc],
    points: &[[f32; 3]],
    children: &[ColliderDesc],
) -> Result<Vec<u8>, A3dError> {
    let count = u32::try_from(colliders.len()).map_err(|_| unwritable())?;
    let point_count = u32::try_from(points.len()).map_err(|_| unwritable())?;
    let child_count = u32::try_from(children.len()).map_err(|_| unwritable())?;
    let mut out = Vec::with_capacity(
        HEADER_BYTES
            + colliders.len() * COLLIDER_BYTES
            + HEADER_BYTES
            + points.len() * POINT_BYTES
            + HEADER_BYTES
            + children.len() * COLLIDER_BYTES,
    );
    out.extend_from_slice(&count.to_le_bytes());
    for collider in colliders {
        write_collider(&mut out, collider);
    }
    out.extend_from_slice(&point_count.to_le_bytes());
    for point in points {
        for value in point {
            out.extend_from_slice(&value.to_le_bytes());
        }
    }
    out.extend_from_slice(&child_count.to_le_bytes());
    for child in children {
        write_collider(&mut out, child);
    }
    Ok(out)
}

/// Décode une section `PHYS` venue d'un fichier qu'on ne croit pas sur parole :
/// rend les colliders, l'annexe des points d'enveloppe et l'annexe des enfants de
/// compound.
///
/// # Errors
///
/// [`A3dError::MalformedSection`] au premier écart : en-tête tronqué, dénombrement
/// au-delà de ce que C-22 admet — vérifié **avant** toute allocation (R-901) —,
/// taille incohérente, discriminant inconnu, `ConvexHull` dont les points débordent
/// de l'annexe, ou `Compound` dont les enfants débordent de l'annexe ou sont
/// eux-mêmes des `Compound` (pas d'imbrication).
pub fn decode_colliders(bytes: &[u8]) -> Result<DecodedPhys, A3dError> {
    if bytes.len() < HEADER_BYTES {
        return Err(malformed("en-tête tronqué"));
    }
    let count = read_u32(bytes, 0) as usize;
    if count > limits::MAX_COLLIDERS {
        return Err(malformed("plus de colliders que C-22 n'en admet"));
    }
    // Bornes du tableau de colliders, puis de l'en-tête de l'annexe des points.
    let Some(points_header) = HEADER_BYTES.checked_add(count * COLLIDER_BYTES) else {
        return Err(malformed("dénombrement de colliders démesuré"));
    };
    if points_header + HEADER_BYTES > bytes.len() {
        return Err(malformed("annexe des points absente"));
    }
    let point_count = read_u32(bytes, points_header) as usize;
    if point_count > MAX_HULL_POINTS {
        return Err(malformed("plus de points d'enveloppe qu'admis"));
    }
    let points_start = points_header + HEADER_BYTES;
    // En-tête de l'annexe des enfants, juste après les points.
    let Some(children_header) = points_start.checked_add(point_count * POINT_BYTES) else {
        return Err(malformed("annexe des points démesurée"));
    };
    if children_header + HEADER_BYTES > bytes.len() {
        return Err(malformed("annexe des enfants absente"));
    }
    let child_count = read_u32(bytes, children_header) as usize;
    if child_count > MAX_COMPOUND_CHILDREN {
        return Err(malformed("plus d'enfants de compound qu'admis"));
    }
    let children_start = children_header + HEADER_BYTES;
    if children_start.checked_add(child_count * COLLIDER_BYTES) != Some(bytes.len()) {
        return Err(malformed("taille incohérente avec le dénombrement"));
    }

    let mut colliders = Vec::with_capacity(count);
    for index in 0..count {
        colliders.push(read_collider(bytes, HEADER_BYTES + index * COLLIDER_BYTES)?);
    }
    let mut points = Vec::with_capacity(point_count);
    for index in 0..point_count {
        let at = points_start + index * POINT_BYTES;
        points.push([
            f32::from_le_bytes(array(bytes, at)),
            f32::from_le_bytes(array(bytes, at + 4)),
            f32::from_le_bytes(array(bytes, at + 8)),
        ]);
    }
    let mut children = Vec::with_capacity(child_count);
    for index in 0..child_count {
        children.push(read_collider(
            bytes,
            children_start + index * COLLIDER_BYTES,
        )?);
    }

    // Vérifications de cohérence sur données potentiellement altérées (R-901).
    for collider in &colliders {
        check_indices(&collider.shape, point_count, child_count)?;
    }
    for child in &children {
        // Un enfant peut porter une enveloppe convexe (ses points dans l'annexe),
        // mais pas être lui-même un compound : rapier ne les imbrique pas.
        if matches!(child.shape, ColliderShape::Compound { .. }) {
            return Err(malformed("compound imbriqué dans un compound"));
        }
        check_indices(&child.shape, point_count, 0)?;
    }
    Ok((colliders, points, children))
}

/// Vérifie que les index d'une forme indexée restent dans leurs annexes.
fn check_indices(
    shape: &ColliderShape,
    point_count: usize,
    child_count: usize,
) -> Result<(), A3dError> {
    match *shape {
        ColliderShape::ConvexHull {
            points_offset,
            points_count,
        } => {
            let end = (points_offset as usize).checked_add(points_count as usize);
            if end.is_none_or(|end| end > point_count) {
                return Err(malformed("points d'une enveloppe convexe hors de l'annexe"));
            }
        }
        ColliderShape::Compound {
            children_offset,
            children_count,
        } => {
            let end = (children_offset as usize).checked_add(children_count as usize);
            if end.is_none_or(|end| end > child_count) {
                return Err(malformed("enfants d'un compound hors de l'annexe"));
            }
        }
        _ => {}
    }
    Ok(())
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
        let bytes = encode_colliders(&colliders, &[], &[]).expect("encodage");
        // + en-têtes des annexes points et enfants (comptes nuls), même sans contenu.
        assert_eq!(
            bytes.len(),
            HEADER_BYTES + 2 * COLLIDER_BYTES + HEADER_BYTES + HEADER_BYTES
        );
        let (decoded, points, children) = decode_colliders(&bytes).expect("décodage");
        assert_eq!(decoded, colliders);
        assert!(points.is_empty());
        assert!(children.is_empty());
    }

    #[test]
    fn t311_une_enveloppe_convexe_fait_l_aller_retour_avec_ses_points() {
        // Un tétraèdre : quatre points, une enveloppe convexe qui les référence.
        let cloud = vec![
            [0.0, 0.0, 0.0],
            [1.0, 0.0, 0.0],
            [0.0, 1.0, 0.0],
            [0.0, 0.0, 1.0],
        ];
        let colliders = vec![collider(ColliderShape::ConvexHull {
            points_offset: 0,
            points_count: 4,
        })];
        let bytes = encode_colliders(&colliders, &cloud, &[]).expect("encodage");
        let (decoded, points, _children) = decode_colliders(&bytes).expect("décodage");
        assert_eq!(decoded, colliders);
        assert_eq!(points, cloud);
    }

    #[test]
    fn t311_un_compound_fait_l_aller_retour_avec_ses_enfants() {
        // Un compound de deux enfants (une boîte, une sphère), placés par leur
        // `local` ; les enfants vivent dans l'annexe dédiée.
        let parent = collider(ColliderShape::Compound {
            children_offset: 0,
            children_count: 2,
        });
        let children = vec![
            collider(ColliderShape::Box {
                half_extents: [0.5, 0.5, 0.5],
            }),
            collider(ColliderShape::Sphere { radius: 0.25 }),
        ];
        let bytes = encode_colliders(&[parent], &[], &children).expect("encodage");
        let (decoded, _points, decoded_children) = decode_colliders(&bytes).expect("décodage");
        assert_eq!(decoded, vec![parent]);
        assert_eq!(decoded_children, children);
    }

    #[test]
    fn t311_un_compound_imbrique_est_refuse() {
        // Un enfant ne peut pas être lui-même un compound (rapier ne les imbrique
        // pas) : refusé au décodage.
        let parent = collider(ColliderShape::Compound {
            children_offset: 0,
            children_count: 1,
        });
        let children = vec![collider(ColliderShape::Compound {
            children_offset: 0,
            children_count: 0,
        })];
        let bytes = encode_colliders(&[parent], &[], &children).expect("encodage");
        assert!(decode_colliders(&bytes).is_err());
    }

    #[test]
    fn t311_un_compound_hors_annexe_est_refuse() {
        // Le compound réclame deux enfants mais l'annexe n'en porte qu'un.
        let parent = collider(ColliderShape::Compound {
            children_offset: 0,
            children_count: 2,
        });
        let children = vec![collider(ColliderShape::Sphere { radius: 1.0 })];
        let bytes = encode_colliders(&[parent], &[], &children).expect("encodage");
        assert!(decode_colliders(&bytes).is_err());
    }

    #[test]
    fn t311_une_enveloppe_hors_annexe_est_refusee() {
        // La forme réclame 4 points mais l'annexe n'en porte que 3 : débordement.
        let colliders = vec![collider(ColliderShape::ConvexHull {
            points_offset: 0,
            points_count: 4,
        })];
        let cloud = vec![[0.0, 0.0, 0.0], [1.0, 0.0, 0.0], [0.0, 1.0, 0.0]];
        let bytes = encode_colliders(&colliders, &cloud, &[]).expect("encodage");
        assert!(decode_colliders(&bytes).is_err());
    }

    #[test]
    fn t311_la_disposition_de_la_section_phys_est_figee() {
        // Octets écrits à la main : c'est la disposition d'ADR-115 qui est
        // vérifiée, pas la cohérence de l'encodeur avec lui-même.
        let colliders = vec![collider(ColliderShape::Box {
            half_extents: [0.5, 1.0, 1.5],
        })];
        let bytes = encode_colliders(&colliders, &[], &[]).expect("encodage");

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
        attendu.extend_from_slice(&0u32.to_le_bytes()); // point_count de l'annexe
        attendu.extend_from_slice(&0u32.to_le_bytes()); // child_count de l'annexe

        assert_eq!(
            bytes.len(),
            HEADER_BYTES + COLLIDER_BYTES + HEADER_BYTES + HEADER_BYTES
        );
        assert_eq!(bytes, attendu);
    }

    #[test]
    fn t311_une_section_phys_mensongere_est_refusee_sans_paniquer() {
        let colliders = vec![collider(ColliderShape::Sphere { radius: 1.0 })];
        let bytes = encode_colliders(&colliders, &[], &[]).expect("encodage");

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
