//! Section `NODE` : `NodeDesc[]` et table des noms (PARTIE 7.3).
//!
//! Disposition, en petit-boutiste, fixée par `docs/decisions/ADR-110.md` :
//!
//! ```text
//! u32 node_count
//! u32 names_size                    octets de la table des noms
//! NodeDesc[node_count]              80 octets chacun, disposition repr(C)
//! NameRef[node_count]               u32 offset, u32 length, relatifs aux noms
//! u8[names_size]                    noms UTF-8, sans terminateur
//! ```
//!
//! Les nodes sont écrits sur leurs **80 octets** `repr(C)`, remplissage de fin
//! compris, pour être lisibles en place (R-881). Chaque nom est celui dont
//! `name_hash` est l'empreinte ; un node sans nom a un nom vide.

use super::{A3dError, SectionTag};
use ax_model::dm::geometry::Transform;
use ax_model::dm::limits;
use ax_model::dm::scene::{name_hash, NodeDesc};

/// Taille d'un node sérialisé : celle de `NodeDesc`.
pub const NODE_BYTES: usize = 80;

/// En-tête de la section : nombre de nodes et taille de la table des noms.
const HEADER_BYTES: usize = 8;

/// Référence d'un nom : décalage et longueur.
const NAME_REF_BYTES: usize = 8;

/// Contenu d'une section `NODE`.
#[derive(Debug, Clone, PartialEq)]
pub struct NodeTable {
    /// Nodes, en ordre topologique.
    pub nodes: Vec<NodeDesc>,
    /// Nom de chaque node, vide s'il n'en a pas.
    pub names: Vec<String>,
}

/// Encode une section `NODE`.
///
/// # Errors
///
/// [`A3dError::MalformedSection`] si les noms ne correspondent pas aux nodes —
/// en nombre ou en empreinte —, [`A3dError::SectionUnwritable`] si la table
/// dépasse ce qu'un `u32` décrit.
pub fn encode_nodes(nodes: &[NodeDesc], names: &[String]) -> Result<Vec<u8>, A3dError> {
    if names.len() != nodes.len() {
        return Err(malformed("un nom par node est attendu"));
    }
    for (node, name) in nodes.iter().zip(names) {
        // Une empreinte qui ne correspond pas à son nom ferait résoudre un nom
        // vers un autre node, sans erreur : refusé à l'écriture plutôt qu'à la
        // lecture de chaque instance.
        if node.name_hash != name_hash(name) {
            return Err(malformed("empreinte de nom incohérente"));
        }
    }
    let count = u32::try_from(nodes.len()).map_err(|_| unwritable())?;
    let names_size: usize = names.iter().map(String::len).sum();
    let names_size_u32 = u32::try_from(names_size).map_err(|_| unwritable())?;

    let mut out =
        Vec::with_capacity(HEADER_BYTES + nodes.len() * (NODE_BYTES + NAME_REF_BYTES) + names_size);
    out.extend_from_slice(&count.to_le_bytes());
    out.extend_from_slice(&names_size_u32.to_le_bytes());
    for node in nodes {
        write_node(&mut out, node);
    }
    let mut offset = 0u32;
    for name in names {
        // `names_size` tient dans un `u32` : chaque longueur et chaque
        // décalage aussi.
        let length = name.len() as u32;
        out.extend_from_slice(&offset.to_le_bytes());
        out.extend_from_slice(&length.to_le_bytes());
        offset += length;
    }
    for name in names {
        out.extend_from_slice(name.as_bytes());
    }
    Ok(out)
}

/// Décode une section `NODE` venue d'un fichier qu'on ne croit pas sur parole.
///
/// # Errors
///
/// [`A3dError::MalformedSection`] au premier écart : en-tête tronqué, plus de
/// nodes que C-22 n'en admet — vérifié **avant** toute allocation (R-901) —,
/// taille incohérente, nom hors de la table, non UTF-8, trop long, ou dont
/// l'empreinte n'est pas celle du node.
pub fn decode_nodes(bytes: &[u8]) -> Result<NodeTable, A3dError> {
    if bytes.len() < HEADER_BYTES {
        return Err(malformed("en-tête tronqué"));
    }
    let count = read_u32(bytes, 0) as usize;
    let names_size = read_u32(bytes, 4) as usize;
    if count > limits::MAX_NODES {
        return Err(malformed("plus de nodes que C-22 n'en admet"));
    }
    let names_start = HEADER_BYTES + count * (NODE_BYTES + NAME_REF_BYTES);
    if names_start.checked_add(names_size) != Some(bytes.len()) {
        return Err(malformed("taille incohérente avec les dénombrements"));
    }

    let mut nodes = Vec::with_capacity(count);
    for index in 0..count {
        nodes.push(read_node(bytes, HEADER_BYTES + index * NODE_BYTES));
    }

    let refs_start = HEADER_BYTES + count * NODE_BYTES;
    let table = &bytes[names_start..];
    let mut names = Vec::with_capacity(count);
    for (index, node) in nodes.iter().enumerate() {
        let at = refs_start + index * NAME_REF_BYTES;
        let offset = read_u32(bytes, at) as usize;
        let length = read_u32(bytes, at + 4) as usize;
        if length > limits::MAX_NAME_BYTES {
            return Err(malformed("nom de plus de 64 octets"));
        }
        let Some(raw) = offset
            .checked_add(length)
            .and_then(|end| table.get(offset..end))
        else {
            return Err(malformed("nom hors de la table"));
        };
        let Ok(name) = core::str::from_utf8(raw) else {
            return Err(malformed("nom non UTF-8"));
        };
        if name_hash(name) != node.name_hash {
            return Err(malformed("empreinte de nom incohérente"));
        }
        names.push(name.to_owned());
    }
    Ok(NodeTable { nodes, names })
}

fn malformed(detail: &'static str) -> A3dError {
    A3dError::MalformedSection {
        tag: SectionTag::NODE,
        detail,
    }
}

fn unwritable() -> A3dError {
    A3dError::SectionUnwritable(SectionTag::NODE)
}

/// Écrit un node sur ses 80 octets `repr(C)`.
fn write_node(out: &mut Vec<u8>, node: &NodeDesc) {
    out.extend_from_slice(&node.name_hash.to_le_bytes());
    out.extend_from_slice(&node.parent.to_le_bytes());
    for value in node.local.translation {
        out.extend_from_slice(&value.to_le_bytes());
    }
    for value in node.local.rotation {
        out.extend_from_slice(&value.to_le_bytes());
    }
    for value in node.local.scale {
        out.extend_from_slice(&value.to_le_bytes());
    }
    out.extend_from_slice(&node.flags.to_le_bytes());
    out.extend_from_slice(&node.mesh.to_le_bytes());
    out.extend_from_slice(&node.collider.to_le_bytes());
    out.extend_from_slice(&node.bone.to_le_bytes());
    out.extend_from_slice(&node.part.to_le_bytes());
    out.extend_from_slice(&node.region.to_le_bytes());
    out.push(node.lod_mask);
    out.push(node.state);
    out.extend_from_slice(&node.mesh_count.to_le_bytes());
    // Le remplissage de fin, qu'impose l'alignement sur le `u64` de tête :
    // réservé, écrit à zéro.
    out.extend_from_slice(&[0; 4]);
}

/// Lit un node ; `at + NODE_BYTES` est dans les bornes, l'appelant l'a vérifié.
fn read_node(bytes: &[u8], at: usize) -> NodeDesc {
    let f32_at = |offset: usize| f32::from_le_bytes(array(bytes, at + offset));
    NodeDesc {
        name_hash: u64::from_le_bytes(array(bytes, at)),
        parent: read_u32(bytes, at + 8),
        local: Transform {
            translation: [f32_at(12), f32_at(16), f32_at(20)],
            rotation: [f32_at(24), f32_at(28), f32_at(32), f32_at(36)],
            scale: [f32_at(40), f32_at(44), f32_at(48)],
        },
        flags: read_u32(bytes, at + 52),
        mesh: read_u32(bytes, at + 56),
        collider: read_u32(bytes, at + 60),
        bone: read_u32(bytes, at + 64),
        part: u16::from_le_bytes(array(bytes, at + 68)),
        region: u16::from_le_bytes(array(bytes, at + 70)),
        lod_mask: bytes[at + 72],
        state: bytes[at + 73],
        mesh_count: u16::from_le_bytes(array(bytes, at + 74)),
    }
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
    use ax_model::dm::scene::{node_flags, ALL_LODS, NONE_U16, NONE_U32, NO_PARENT};

    fn node(name: &str, parent: u32) -> NodeDesc {
        NodeDesc {
            name_hash: name_hash(name),
            parent,
            local: Transform {
                translation: [1.0, 2.0, 3.0],
                ..Transform::identity()
            },
            flags: node_flags::VISIBLE,
            mesh: 0,
            collider: NONE_U32,
            bone: NONE_U32,
            part: NONE_U16,
            region: NONE_U16,
            lod_mask: ALL_LODS,
            state: 0,
            mesh_count: 0,
        }
    }

    fn table() -> (Vec<NodeDesc>, Vec<String>) {
        (
            vec![
                // Un mesh glTF à deux primitives : deux meshes, un node.
                NodeDesc {
                    mesh_count: 2,
                    ..node("chassis", NO_PARENT)
                },
                node("", 0),
                node("roue_avant", 0),
            ],
            vec!["chassis".to_owned(), String::new(), "roue_avant".to_owned()],
        )
    }

    #[test]
    fn t211_la_table_des_nodes_fait_l_aller_retour() {
        let (nodes, names) = table();
        let bytes = encode_nodes(&nodes, &names).expect("encodage");
        let decoded = decode_nodes(&bytes).expect("décodage");
        assert_eq!(decoded.nodes, nodes);
        assert_eq!(decoded.names, names);
    }

    #[test]
    fn t211_la_disposition_de_la_section_node_est_figee() {
        // Octets écrits à la main, champ par champ, sans passer par
        // l'encodeur : c'est la disposition de l'ADR-110 qui est vérifiée,
        // pas la cohérence de l'encodeur avec lui-même.
        let nodes = vec![NodeDesc {
            mesh_count: 3,
            ..node("a", NO_PARENT)
        }];
        let bytes = encode_nodes(&nodes, &["a".to_owned()]).expect("encodage");

        let mut attendu = Vec::new();
        attendu.extend_from_slice(&1u32.to_le_bytes());
        attendu.extend_from_slice(&1u32.to_le_bytes());
        attendu.extend_from_slice(&0xaf63_dc4c_8601_ec8c_u64.to_le_bytes());
        attendu.extend_from_slice(&u32::MAX.to_le_bytes());
        for value in [1.0f32, 2.0, 3.0, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0] {
            attendu.extend_from_slice(&value.to_le_bytes());
        }
        attendu.extend_from_slice(&node_flags::VISIBLE.to_le_bytes());
        attendu.extend_from_slice(&0u32.to_le_bytes());
        attendu.extend_from_slice(&u32::MAX.to_le_bytes());
        attendu.extend_from_slice(&u32::MAX.to_le_bytes());
        attendu.extend_from_slice(&u16::MAX.to_le_bytes());
        attendu.extend_from_slice(&u16::MAX.to_le_bytes());
        // lod_mask, state, mesh_count (ADR-122 §5), remplissage de fin.
        attendu.extend_from_slice(&[ALL_LODS, 0, 3, 0, 0, 0, 0, 0]);
        attendu.extend_from_slice(&0u32.to_le_bytes());
        attendu.extend_from_slice(&1u32.to_le_bytes());
        attendu.push(b'a');

        assert_eq!(bytes.len(), HEADER_BYTES + NODE_BYTES + NAME_REF_BYTES + 1);
        assert_eq!(bytes, attendu);
    }

    #[test]
    fn t253_une_table_mensongere_est_refusee_sans_paniquer() {
        let (nodes, names) = table();
        let bytes = encode_nodes(&nodes, &names).expect("encodage");

        // Tronquée, ou prolongée d'un octet.
        assert!(decode_nodes(&bytes[..bytes.len() - 1]).is_err());
        let mut long = bytes.clone();
        long.push(0);
        assert!(decode_nodes(&long).is_err());
        assert!(decode_nodes(&[1, 2, 3]).is_err());

        // Un dénombrement démesuré est refusé avant toute allocation.
        let mut enorme = bytes.clone();
        enorme[0..4].copy_from_slice(&u32::MAX.to_le_bytes());
        assert!(decode_nodes(&enorme).is_err());

        // Un nom qui déborde de la table.
        let refs = HEADER_BYTES + nodes.len() * NODE_BYTES;
        let mut deborde = bytes.clone();
        deborde[refs..refs + 4].copy_from_slice(&u32::MAX.to_le_bytes());
        assert!(decode_nodes(&deborde).is_err());

        // Un nom altéré ne correspond plus à son empreinte.
        let mut altere = bytes.clone();
        let last = altere.len() - 1;
        altere[last] = b'X';
        let refus = decode_nodes(&altere).unwrap_err();
        assert_eq!(refus.code(), -3007);
        assert!(refus.to_string().contains("empreinte"), "{refus}");

        // Un octet non UTF-8.
        let mut invalide = bytes;
        let last = invalide.len() - 1;
        invalide[last] = 0xFF;
        assert!(decode_nodes(&invalide).is_err());
    }

    #[test]
    fn t211_un_nom_qui_ne_correspond_pas_a_son_empreinte_n_est_pas_ecrit() {
        let (nodes, mut names) = table();
        names[0] = "autre".to_owned();
        assert!(encode_nodes(&nodes, &names).is_err());
        assert!(encode_nodes(&nodes, &names[..2]).is_err());
    }
}
