//! Transfert de la géométrie d'un asset chargé vers Java (ADR-119, IF-06).
//!
//! `axion_asset_geometry` dépose dans `AXION_BUF_ASSET_OUT`, en petit-boutiste,
//! aligné sur 4 :
//!
//! ```text
//! u32 mesh_count
//! u32 vertex_count
//! u32 index_count
//! u32 draw_count
//! MeshDesc[mesh_count]        48 octets chacun, DM-04 tel quel
//! Vertex[vertex_count]        48 octets chacun, DM-04 (R-140) = sommet GPU de §19.4
//! u32 indices[index_count]    locaux au mesh : sommet = vertices[mesh.vertex_offset + indice]
//! RestDraw[draw_count]        64 octets chacun
//! ```
//!
//! Les sommets restent **locaux à leur node** ; chaque [`RestDraw`] porte la
//! transformation de repos du node qui affiche un mesh. C'est la forme statique,
//! calculée une fois au chargement, de ce que `axion_render_prepare` (IF-05)
//! produira à chaque frame.

use super::geometry::{MeshDesc, Vertex};

/// Taille de l'en-tête du transfert : quatre dénombrements `u32`.
pub const TRANSFER_HEADER_BYTES: usize = 16;

/// Un mesh à dessiner dans la pose de repos d'un asset (ADR-119).
///
/// `model` est une `mat4x3` en **colonne-major** — la convention GLSL du bloc
/// d'instance de §19.4 : trois colonnes d'axes puis la translation. Elle place
/// les sommets du mesh, exprimés dans l'espace de leur node, dans l'espace de
/// l'asset.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct RestDraw {
    /// Index dans `MeshDesc[]` (niveau de détail 0).
    pub mesh: u32,
    /// Node qui porte le mesh.
    pub node: u32,
    /// Drapeaux du node (DM-03) : `NO_CULL`, `EMISSIVE`, `SHADOW_CASTER`…
    pub node_flags: u32,
    /// Réservé, à zéro.
    pub _pad: u32,
    /// Transformation de repos du node, `mat4x3` colonne-major.
    pub model: [f32; 12],
}

impl RestDraw {
    /// Taille d'un `RestDraw` sérialisé, en octets.
    pub const BYTES: usize = 64;

    /// Écrit l'entrée sur ses 64 octets petit-boutistes ; le champ réservé est
    /// écrit à zéro.
    pub fn write_le(&self, out: &mut Vec<u8>) {
        out.extend_from_slice(&self.mesh.to_le_bytes());
        out.extend_from_slice(&self.node.to_le_bytes());
        out.extend_from_slice(&self.node_flags.to_le_bytes());
        out.extend_from_slice(&0u32.to_le_bytes());
        for value in self.model {
            out.extend_from_slice(&value.to_le_bytes());
        }
    }

    /// Lit une entrée depuis ses 64 octets petit-boutistes ; le champ réservé
    /// est rendu à zéro.
    #[must_use]
    pub fn read_le(bytes: &[u8; Self::BYTES]) -> Self {
        let word = |at: usize| [bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]];
        let mut model = [0.0f32; 12];
        for (index, value) in model.iter_mut().enumerate() {
            *value = f32::from_le_bytes(word(16 + index * 4));
        }
        Self {
            mesh: u32::from_le_bytes(word(0)),
            node: u32::from_le_bytes(word(4)),
            node_flags: u32::from_le_bytes(word(8)),
            _pad: 0,
            model,
        }
    }
}

/// Taille d'un transfert, en octets ; `None` si elle déborde.
#[must_use]
pub fn geometry_transfer_len(
    meshes: usize,
    vertices: usize,
    indices: usize,
    draws: usize,
) -> Option<usize> {
    TRANSFER_HEADER_BYTES
        .checked_add(meshes.checked_mul(MeshDesc::BYTES)?)?
        .checked_add(vertices.checked_mul(Vertex::BYTES)?)?
        .checked_add(indices.checked_mul(4)?)?
        .checked_add(draws.checked_mul(RestDraw::BYTES)?)
}

/// Encode le transfert de géométrie.
///
/// Rend `None` si un dénombrement ne tient pas dans un `u32` — ce que les
/// plafonds de R-143 empêchent bien avant.
#[must_use]
pub fn encode_geometry_transfer(
    meshes: &[MeshDesc],
    vertices: &[Vertex],
    indices: &[u32],
    draws: &[RestDraw],
) -> Option<Vec<u8>> {
    let counts = [
        u32::try_from(meshes.len()).ok()?,
        u32::try_from(vertices.len()).ok()?,
        u32::try_from(indices.len()).ok()?,
        u32::try_from(draws.len()).ok()?,
    ];
    let len = geometry_transfer_len(meshes.len(), vertices.len(), indices.len(), draws.len())?;
    let mut out = Vec::with_capacity(len);
    for count in counts {
        out.extend_from_slice(&count.to_le_bytes());
    }
    for mesh in meshes {
        mesh.write_le(&mut out);
    }
    for vertex in vertices {
        vertex.write_le(&mut out);
    }
    for index in indices {
        out.extend_from_slice(&index.to_le_bytes());
    }
    for draw in draws {
        draw.write_le(&mut out);
    }
    debug_assert_eq!(out.len(), len);
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dm::geometry::{mesh_flags, NO_REGION_U16, NO_REGION_U8};
    use core::mem::{align_of, offset_of, size_of};

    fn identite() -> [f32; 12] {
        [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0]
    }

    #[test]
    fn la_disposition_de_rest_draw_est_figee() {
        assert_eq!(size_of::<RestDraw>(), RestDraw::BYTES);
        assert_eq!(align_of::<RestDraw>(), 4);
        assert_eq!(offset_of!(RestDraw, mesh), 0);
        assert_eq!(offset_of!(RestDraw, node), 4);
        assert_eq!(offset_of!(RestDraw, node_flags), 8);
        assert_eq!(offset_of!(RestDraw, _pad), 12);
        assert_eq!(offset_of!(RestDraw, model), 16);
    }

    #[test]
    fn le_transfert_est_fige_octet_par_octet() {
        // Un triangle, un node translaté : octets écrits à la main d'après
        // ADR-119, pas déduits de l'encodeur.
        let mesh = MeshDesc {
            vertex_offset: 0,
            vertex_count: 3,
            index_offset: 0,
            index_count: 3,
            material: 0,
            lod: 0,
            flags: mesh_flags::DOUBLE_SIDED,
            aabb_min: [0.0; 3],
            aabb_max: [1.0; 3],
            region: NO_REGION_U16,
            _pad: 0,
        };
        let sommet = |x: f32| Vertex {
            position: [x, 0.0, 0.0],
            normal: [0, 127, 0, 0],
            tangent: [0; 4],
            uv0: [0; 2],
            uv1: [0; 2],
            color: [255; 4],
            bones: [0; 4],
            weights: [255, 0, 0, 0],
            region: NO_REGION_U8,
            def_w: 0,
            _pad: [0; 6],
        };
        let mut model = identite();
        model[9] = 2.0; // translation x
        let draw = RestDraw {
            mesh: 0,
            node: 1,
            node_flags: 1,
            _pad: 0xDEAD,
            model,
        };

        let octets = encode_geometry_transfer(
            &[mesh],
            &[sommet(0.0), sommet(1.0), sommet(0.5)],
            &[0, 1, 2],
            &[draw],
        )
        .expect("encodage");

        let mut attendu = Vec::new();
        for count in [1u32, 3, 3, 1] {
            attendu.extend_from_slice(&count.to_le_bytes());
        }
        mesh.write_le(&mut attendu);
        for x in [0.0f32, 1.0, 0.5] {
            sommet(x).write_le(&mut attendu);
        }
        for index in [0u32, 1, 2] {
            attendu.extend_from_slice(&index.to_le_bytes());
        }
        // RestDraw à la main : mesh, node, drapeaux, zéro réservé, puis la matrice.
        attendu.extend_from_slice(&0u32.to_le_bytes());
        attendu.extend_from_slice(&1u32.to_le_bytes());
        attendu.extend_from_slice(&1u32.to_le_bytes());
        attendu.extend_from_slice(&0u32.to_le_bytes());
        for value in [
            1.0f32, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 2.0, 0.0, 0.0,
        ] {
            attendu.extend_from_slice(&value.to_le_bytes());
        }

        assert_eq!(octets.len(), 16 + 48 + 3 * 48 + 3 * 4 + 64);
        assert_eq!(octets, attendu);
        assert_eq!(Some(octets.len()), geometry_transfer_len(1, 3, 3, 1));
    }

    #[test]
    fn rest_draw_fait_l_aller_retour() {
        let draw = RestDraw {
            mesh: 7,
            node: 3,
            node_flags: 0x0000_0201,
            _pad: 0,
            model: [0.0, 1.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 4.0, 5.0, 6.0],
        };
        let mut octets = Vec::new();
        draw.write_le(&mut octets);
        assert_eq!(
            RestDraw::read_le(octets.as_slice().try_into().expect("64 octets")),
            draw
        );
    }

    #[test]
    fn un_transfert_vide_est_valide() {
        // Un asset sans géométrie ni node rend des comptes nuls (ADR-119 §3).
        let octets = encode_geometry_transfer(&[], &[], &[], &[]).expect("encodage");
        assert_eq!(octets, vec![0u8; TRANSFER_HEADER_BYTES]);
    }

    #[test]
    fn une_taille_demesuree_ne_deborde_pas() {
        assert_eq!(geometry_transfer_len(usize::MAX, 0, 0, 0), None);
        assert_eq!(geometry_transfer_len(0, 0, usize::MAX / 2, 0), None);
    }
}
