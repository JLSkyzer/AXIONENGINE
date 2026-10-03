//! Transferts d'un asset chargé vers Java (IF-06) : sa géométrie (ADR-119), ses
//! matériaux et ses textures (ADR-122).
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
//!
//! La table des matériaux et des textures prend le même chemin, déposée par
//! `axion_asset_materials` : voir [`encode_material_transfer`].

use super::geometry::{MeshDesc, Vertex};
use super::material::{texture_source, MaterialDesc, TextureDesc};

/// Taille de l'en-tête du transfert : quatre dénombrements `u32`.
pub const TRANSFER_HEADER_BYTES: usize = 16;

/// Taille de l'en-tête du transfert des matériaux (ADR-122 §6) : trois
/// dénombrements `u32` et une réserve.
pub const MATERIAL_TRANSFER_HEADER_BYTES: usize = 16;

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

/// Taille d'un transfert de matériaux, en octets ; `None` si elle déborde.
#[must_use]
pub fn material_transfer_len(
    materials: usize,
    textures: usize,
    path_bytes: usize,
) -> Option<usize> {
    MATERIAL_TRANSFER_HEADER_BYTES
        .checked_add(materials.checked_mul(MaterialDesc::BYTES)?)?
        .checked_add(textures.checked_mul(TextureDesc::BYTES)?)?
        .checked_add(path_bytes)
}

/// Encode le transfert des matériaux et des textures d'un asset chargé
/// (ADR-122 §6), que `axion_asset_materials` dépose dans `AXION_BUF_ASSET_OUT` :
///
/// ```text
/// u32 material_count
/// u32 texture_count
/// u32 path_bytes
/// u32 0
/// MaterialDesc[material_count]   96 octets chacun, DM-05 tel quel
/// TextureDesc[texture_count]     16 octets chacun
/// u8 paths[path_bytes]           chemins relatifs, UTF-8
/// ```
///
/// `textures` et `data` sont la table de `TEXR`. Le transfert ne porte que ses
/// chemins : une entrée `RESOURCE` y désigne le sien dans `paths` — un chemin
/// que deux entrées partagent n'y figure qu'une fois —, une entrée `EMBEDDED` y
/// a `data_offset = 0` et pour `data_size` la taille de son PNG. Ses octets
/// passent par `axion_asset_texture`, **une texture par appel** : une table de
/// 128 textures de 4096² ne transite pas d'un bloc.
///
/// Rend `None` si un dénombrement ne tient pas dans un `u32`, si une entrée
/// désigne des octets hors de `data`, ou si sa provenance est inconnue — ce que
/// le décodage de `TEXR` a déjà refusé.
#[must_use]
pub fn encode_material_transfer(
    materials: &[MaterialDesc],
    textures: &[TextureDesc],
    data: &[u8],
) -> Option<Vec<u8>> {
    let mut paths: Vec<u8> = Vec::new();
    // Chemins déjà copiés, par plage dans `data` : au plus 128 entrées (C-22),
    // une recherche linéaire suffit et garde l'ordre déterministe.
    let mut placed: Vec<((u32, u32), u32)> = Vec::new();
    let mut entries = Vec::with_capacity(textures.len());
    for texture in textures {
        let start = texture.data_offset as usize;
        let bytes = data.get(start..start.checked_add(texture.data_size as usize)?)?;
        let mut entry = *texture;
        entry.data_offset = match texture.source {
            texture_source::EMBEDDED => 0,
            texture_source::RESOURCE => {
                let key = (texture.data_offset, texture.data_size);
                if let Some((_, offset)) = placed.iter().find(|(range, _)| *range == key) {
                    *offset
                } else {
                    let offset = u32::try_from(paths.len()).ok()?;
                    paths.extend_from_slice(bytes);
                    placed.push((key, offset));
                    offset
                }
            }
            _ => return None,
        };
        entries.push(entry);
    }

    let counts = [
        u32::try_from(materials.len()).ok()?,
        u32::try_from(entries.len()).ok()?,
        u32::try_from(paths.len()).ok()?,
        0,
    ];
    let len = material_transfer_len(materials.len(), entries.len(), paths.len())?;
    let mut out = Vec::with_capacity(len);
    for count in counts {
        out.extend_from_slice(&count.to_le_bytes());
    }
    for material in materials {
        material.write_le(&mut out);
    }
    for entry in &entries {
        entry.write_le(&mut out);
    }
    out.extend_from_slice(&paths);
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
            uv0_range: 0,
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

    /// Un matériau dont l'albedo désigne la texture 0, la normale la texture 2.
    fn materiau() -> MaterialDesc {
        use crate::dm::material::{blend_mode, cull_mode, shading_model, NO_TEXTURE};
        MaterialDesc {
            name_hash: 0x0102_0304_0506_0708,
            albedo_tex: 0,
            normal_tex: 2,
            orm_tex: NO_TEXTURE,
            emissive_tex: NO_TEXTURE,
            height_tex: NO_TEXTURE,
            damage_tex: NO_TEXTURE,
            albedo_factor: [1.0, 0.5, 0.25, 1.0],
            emissive_factor: [0.0; 3],
            metallic: 0.0,
            roughness: 0.5,
            occlusion_strength: 1.0,
            normal_scale: 1.0,
            alpha_cutoff: 0.5,
            parallax_scale: 0.0,
            clearcoat: 0.0,
            clearcoat_roughness: 0.0,
            sheen: 0.0,
            anisotropy: 0.0,
            blend_mode: blend_mode::OPAQUE,
            cull_mode: cull_mode::BACK,
            shading_model: shading_model::PBR,
            _pad: 0,
            flags: 0,
            wear_profile: u16::MAX,
        }
    }

    fn texture(source: u8, sampler: u8, data_offset: u32, data_size: u32) -> TextureDesc {
        use crate::dm::material::texture_format;
        let (width, height) = if source == texture_source::EMBEDDED {
            (2, 2)
        } else {
            (0, 0)
        };
        TextureDesc {
            source,
            format: texture_format::PNG,
            sampler,
            _pad: 0,
            width,
            height,
            data_offset,
            data_size,
        }
    }

    #[test]
    fn t270_le_transfert_des_materiaux_est_fige_octet_par_octet() {
        // La table de TEXR : un PNG de 33 octets, puis un chemin que deux
        // entrées partagent — même image, deux échantillonneurs.
        let mut data = vec![0xAB; 33];
        data.extend_from_slice(b"tex/a.png");
        let textures = [
            texture(texture_source::EMBEDDED, 1, 0, 33),
            texture(texture_source::RESOURCE, 0, 33, 9),
            texture(texture_source::RESOURCE, 2, 33, 9),
        ];
        let transfert =
            encode_material_transfer(&[materiau()], &textures, &data).expect("transfert");

        let mut attendu = Vec::new();
        for mot in [1u32, 3, 9, 0] {
            attendu.extend_from_slice(&mot.to_le_bytes());
        }
        materiau().write_le(&mut attendu);
        // TextureDesc : provenance, format, échantillonneur, réserve, largeur,
        // hauteur, décalage, taille. Le PNG ne voyage pas ici : décalage nul,
        // sa taille seule ; les chemins pointent dans `paths`, une fois chacun.
        attendu.extend_from_slice(&[1, 1, 1, 0]);
        attendu.extend_from_slice(&2u16.to_le_bytes());
        attendu.extend_from_slice(&2u16.to_le_bytes());
        attendu.extend_from_slice(&0u32.to_le_bytes());
        attendu.extend_from_slice(&33u32.to_le_bytes());
        for echantillonneur in [0u8, 2] {
            attendu.extend_from_slice(&[2, 1, echantillonneur, 0]);
            attendu.extend_from_slice(&[0; 4]);
            attendu.extend_from_slice(&0u32.to_le_bytes());
            attendu.extend_from_slice(&9u32.to_le_bytes());
        }
        attendu.extend_from_slice(b"tex/a.png");

        assert_eq!(transfert, attendu);
        assert_eq!(Some(transfert.len()), material_transfer_len(1, 3, 9));
    }

    #[test]
    fn t270_un_transfert_de_materiaux_vide_est_valide() {
        // Un asset sans MATL, ou dont le MATL est d'une disposition inconnue :
        // des comptes nuls, et le matériau par défaut côté client.
        let transfert = encode_material_transfer(&[], &[], &[]).expect("transfert");
        assert_eq!(transfert, [0u8; 16]);
    }

    #[test]
    fn t270_une_entree_hors_de_ses_donnees_n_est_pas_transferee() {
        let data = b"tex/a.png";
        assert_eq!(
            encode_material_transfer(&[], &[texture(texture_source::RESOURCE, 0, 4, 9)], data),
            None,
            "chemin hors de la zone de données"
        );
        assert_eq!(
            encode_material_transfer(&[], &[texture(texture_source::EMBEDDED, 0, 0, 33)], data),
            None,
            "PNG hors de la zone de données"
        );
        assert_eq!(
            encode_material_transfer(&[], &[texture(7, 0, 0, 9)], data),
            None,
            "provenance inconnue"
        );
        assert_eq!(material_transfer_len(usize::MAX, 0, 0), None);
    }
}
