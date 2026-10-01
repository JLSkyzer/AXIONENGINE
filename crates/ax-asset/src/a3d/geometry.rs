//! Section `GEOM` : `MeshDesc[]`, `Vertex[]`, `u32 indices[]` (DM-04, PARTIE 7.3).
//!
//! Disposition, en petit-boutiste, telle que l'écrit le compilateur :
//!
//! ```text
//! u32 mesh_count
//! u32 vertex_count
//! u32 index_count
//! u32 réservé (0)
//! MeshDesc[mesh_count]        48 octets chacun
//! Vertex[vertex_count]        48 octets chacun
//! u32 indices[index_count]    locaux au mesh : sommet = vertices[mesh.vertex_offset + indice]
//! ```
//!
//! Le natif la décode pour remettre la géométrie à Java (ADR-119) : Java ne lit
//! jamais le contenu d'une section A3D. Ce qui sort d'ici est **cohérent** —
//! chaque indice désigne un sommet de son mesh —, si bien qu'un lecteur peut
//! indexer sans contrôler de nouveau.

use super::{A3dError, SectionTag};
use ax_model::dm::geometry::{MeshDesc, Vertex};
use ax_model::dm::limits;

/// En-tête de la section : trois dénombrements et un mot réservé.
const HEADER_BYTES: usize = 16;

/// Contenu d'une section `GEOM`.
#[derive(Debug, Clone, PartialEq)]
pub struct DecodedGeometry {
    /// Meshes, dans l'ordre de la section.
    pub meshes: Vec<MeshDesc>,
    /// Sommets de tous les meshes, en un tableau.
    pub vertices: Vec<Vertex>,
    /// Indices de tous les meshes, locaux à leur mesh.
    pub indices: Vec<u32>,
}

/// Décode une section `GEOM` venue d'un fichier qu'on ne croit pas sur parole.
///
/// # Errors
///
/// [`A3dError::MalformedSection`] au premier écart : en-tête tronqué, plus de
/// sommets ou d'indices que R-143 n'en admet — vérifié **avant** toute
/// allocation (R-901) —, taille incohérente avec les dénombrements, mesh dont
/// les sommets ou les indices débordent de leur tableau, dont les indices ne
/// forment pas des triangles, ou dont un indice désigne un sommet hors du mesh.
pub fn decode_geometry(bytes: &[u8]) -> Result<DecodedGeometry, A3dError> {
    if bytes.len() < HEADER_BYTES {
        return Err(malformed("en-tête tronqué"));
    }
    let mesh_count = read_u32(bytes, 0) as usize;
    let vertex_count = read_u32(bytes, 4) as usize;
    let index_count = read_u32(bytes, 8) as usize;

    // R-143, avant d'allouer quoi que ce soit (R-901).
    if vertex_count > limits::MAX_VERTICES {
        return Err(malformed("plus de sommets que R-143 n'en admet"));
    }
    if index_count > limits::MAX_INDICES {
        return Err(malformed("plus d'indices que R-143 n'en admet"));
    }

    // Le nombre de meshes n'a pas de plafond propre : c'est la taille de la
    // section, déjà bornée par le conteneur, qui le borne. Le produit est vérifié
    // — un dénombrement de 2³² meshes ferait déborder un calcul naïf.
    let vertices_start = mesh_count
        .checked_mul(MeshDesc::BYTES)
        .and_then(|meshes| meshes.checked_add(HEADER_BYTES));
    let indices_start = vertices_start.and_then(|start| {
        vertex_count
            .checked_mul(Vertex::BYTES)
            .and_then(|vertices| vertices.checked_add(start))
    });
    let end = indices_start.and_then(|start| {
        index_count
            .checked_mul(4)
            .and_then(|indices| indices.checked_add(start))
    });
    let (Some(vertices_start), Some(indices_start), Some(end)) =
        (vertices_start, indices_start, end)
    else {
        return Err(malformed("dénombrements démesurés"));
    };
    if end != bytes.len() {
        return Err(malformed("taille incohérente avec les dénombrements"));
    }

    // Les bornes sont acquises : chaque lecture ci-dessous tient dans la section.
    let mut meshes = Vec::with_capacity(mesh_count);
    for index in 0..mesh_count {
        meshes.push(MeshDesc::read_le(chunk(
            bytes,
            HEADER_BYTES + index * MeshDesc::BYTES,
        )));
    }
    let mut vertices = Vec::with_capacity(vertex_count);
    for index in 0..vertex_count {
        vertices.push(Vertex::read_le(chunk(
            bytes,
            vertices_start + index * Vertex::BYTES,
        )));
    }
    let mut indices = Vec::with_capacity(index_count);
    for index in 0..index_count {
        indices.push(read_u32(bytes, indices_start + index * 4));
    }

    for mesh in &meshes {
        check_mesh(mesh, vertex_count, &indices)?;
    }

    Ok(DecodedGeometry {
        meshes,
        vertices,
        indices,
    })
}

/// Vérifie qu'un mesh désigne des plages existantes et des triangles dont
/// chaque indice reste dans ses propres sommets.
fn check_mesh(mesh: &MeshDesc, vertex_count: usize, indices: &[u32]) -> Result<(), A3dError> {
    let vertex_end = (mesh.vertex_offset as usize).checked_add(mesh.vertex_count as usize);
    if vertex_end.is_none_or(|end| end > vertex_count) {
        return Err(malformed("sommets d'un mesh hors du tableau"));
    }
    let start = mesh.index_offset as usize;
    let Some(range) = start
        .checked_add(mesh.index_count as usize)
        .and_then(|end| indices.get(start..end))
    else {
        return Err(malformed("indices d'un mesh hors du tableau"));
    };
    if !mesh.index_count.is_multiple_of(3) {
        return Err(malformed(
            "indices d'un mesh qui ne forment pas des triangles",
        ));
    }
    // Les indices sont locaux au mesh (C-22) : un indice qui sort du mesh
    // désignerait le sommet d'un autre, ou rien du tout.
    if range.iter().any(|local| *local >= mesh.vertex_count) {
        return Err(malformed("indice hors des sommets de son mesh"));
    }
    Ok(())
}

fn malformed(detail: &'static str) -> A3dError {
    A3dError::MalformedSection {
        tag: SectionTag::GEOM,
        detail,
    }
}

fn read_u32(bytes: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(*chunk(bytes, at))
}

/// Tranche de taille fixe ; `at + N` est dans les bornes, l'appelant l'a vérifié.
fn chunk<const N: usize>(bytes: &[u8], at: usize) -> &[u8; N] {
    bytes[at..at + N]
        .try_into()
        .expect("borne vérifiée par l'appelant")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::a3d::{A3dFile, A3dLimits};
    use crate::collider::ColliderMode;
    use crate::compile::{compile, CompileOptions};
    use crate::import::{ImportLimits, SourceFormat};
    use crate::optimize::LodOptions;
    use ax_model::dm::geometry::{mesh_flags, NO_REGION_U16, NO_REGION_U8};

    fn sommet(position: [f32; 3]) -> Vertex {
        Vertex {
            position,
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
        }
    }

    fn mesh(
        vertex_offset: u32,
        vertex_count: u32,
        index_offset: u32,
        index_count: u32,
    ) -> MeshDesc {
        MeshDesc {
            vertex_offset,
            vertex_count,
            index_offset,
            index_count,
            material: 0,
            lod: 0,
            flags: mesh_flags::DOUBLE_SIDED,
            aabb_min: [0.0; 3],
            aabb_max: [1.0; 3],
            region: NO_REGION_U16,
            _pad: 0,
        }
    }

    /// Sérialise une section `GEOM` comme le compilateur l'écrit.
    fn section(meshes: &[MeshDesc], vertices: &[Vertex], indices: &[u32]) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(&(meshes.len() as u32).to_le_bytes());
        out.extend_from_slice(&(vertices.len() as u32).to_le_bytes());
        out.extend_from_slice(&(indices.len() as u32).to_le_bytes());
        out.extend_from_slice(&0u32.to_le_bytes());
        for mesh in meshes {
            mesh.write_le(&mut out);
        }
        for vertex in vertices {
            vertex.write_le(&mut out);
        }
        for index in indices {
            out.extend_from_slice(&index.to_le_bytes());
        }
        out
    }

    /// Deux meshes partageant le tableau de sommets : le second commence au
    /// sommet 3, et ses indices sont **locaux** (0, 1, 2), pas 3, 4, 5.
    fn deux_triangles() -> (Vec<MeshDesc>, Vec<Vertex>, Vec<u32>) {
        let meshes = vec![mesh(0, 3, 0, 3), mesh(3, 3, 3, 3)];
        let vertices = vec![
            sommet([0.0, 0.0, 0.0]),
            sommet([1.0, 0.0, 0.0]),
            sommet([0.0, 1.0, 0.0]),
            sommet([0.0, 0.0, 1.0]),
            sommet([1.0, 0.0, 1.0]),
            sommet([0.0, 1.0, 1.0]),
        ];
        (meshes, vertices, vec![0, 1, 2, 0, 2, 1])
    }

    #[test]
    fn t250_une_section_geom_fait_l_aller_retour() {
        let (meshes, vertices, indices) = deux_triangles();
        let decoded = decode_geometry(&section(&meshes, &vertices, &indices)).expect("décodage");
        assert_eq!(decoded.meshes, meshes);
        assert_eq!(decoded.vertices, vertices);
        assert_eq!(decoded.indices, indices);
    }

    #[test]
    fn t250_la_disposition_de_geom_est_figee() {
        // Octets écrits à la main : un mesh, un triangle. C'est DM-04 qui est
        // vérifié, pas la cohérence du décodeur avec un encodeur.
        let mut octets = Vec::new();
        for value in [1u32, 3, 3, 0] {
            octets.extend_from_slice(&value.to_le_bytes());
        }
        // MeshDesc : plages, matériau 7, LOD 0, drapeaux, AABB, région, réservé.
        for value in [0u32, 3, 0, 3] {
            octets.extend_from_slice(&value.to_le_bytes());
        }
        octets.extend_from_slice(&7u16.to_le_bytes());
        octets.push(0);
        octets.push(mesh_flags::TRANSPARENT);
        for value in [0.0f32, 0.0, 0.0, 1.0, 1.0, 0.0] {
            octets.extend_from_slice(&value.to_le_bytes());
        }
        octets.extend_from_slice(&NO_REGION_U16.to_le_bytes());
        octets.extend_from_slice(&0u16.to_le_bytes());
        // Trois sommets : position, puis 36 octets d'attributs à des valeurs fixes.
        for x in [0.0f32, 1.0, 0.0] {
            for value in [x, if x == 0.0 { 0.0 } else { 0.5 }, 0.0] {
                octets.extend_from_slice(&value.to_le_bytes());
            }
            octets.extend_from_slice(&[0, 127, 0, 0]); // normale +Y
            octets.extend_from_slice(&[0; 4]); // tangente
            octets.extend_from_slice(&[0; 8]); // uv0, uv1
            octets.extend_from_slice(&[255; 4]); // couleur
            octets.extend_from_slice(&[0; 4]); // os
            octets.extend_from_slice(&[255, 0, 0, 0]); // poids
            octets.push(NO_REGION_U8);
            octets.push(0); // def_w
            octets.extend_from_slice(&[0; 6]);
        }
        for index in [2u32, 1, 0] {
            octets.extend_from_slice(&index.to_le_bytes());
        }

        let decoded = decode_geometry(&octets).expect("décodage");
        assert_eq!(decoded.meshes.len(), 1);
        assert_eq!(decoded.meshes[0].material, 7);
        assert_eq!(decoded.meshes[0].flags, mesh_flags::TRANSPARENT);
        assert_eq!(decoded.meshes[0].aabb_max, [1.0, 1.0, 0.0]);
        assert_eq!(decoded.vertices.len(), 3);
        assert_eq!(decoded.vertices[1].position, [1.0, 0.5, 0.0]);
        assert_eq!(decoded.vertices[1].normal, [0, 127, 0, 0]);
        assert_eq!(decoded.indices, vec![2, 1, 0]);
    }

    #[test]
    fn t250_le_decodeur_relit_ce_que_le_compilateur_ecrit() {
        // Un cube réel, compilé de bout en bout : c'est l'écrivain de production
        // qui est relu, pas une section fabriquée pour le test.
        const CUBE_OBJ: &str = "\
v 0.0 0.0 0.0
v 1.0 0.0 0.0
v 1.0 1.0 0.0
v 0.0 1.0 0.0
v 0.0 0.0 1.0
v 1.0 0.0 1.0
v 1.0 1.0 1.0
v 0.0 1.0 1.0
f 1 2 3
f 1 3 4
f 5 7 6
f 5 8 7
f 1 5 6
f 1 6 2
f 4 3 7
f 4 7 8
f 1 4 8
f 1 8 5
f 2 6 7
f 2 7 3
";
        let options = CompileOptions {
            asset_id: 1,
            source_hash: 0,
            limits: ImportLimits::new(1 << 20),
            dynamic_body: true,
            lod: LodOptions::DEFAULT,
            collider_mode: ColliderMode::None,
        };
        let compiled = compile(CUBE_OBJ.as_bytes(), SourceFormat::Obj, &options, |_| None)
            .expect("compilation refusée");
        let file = A3dFile::open(&compiled.bytes, A3dLimits::new(1 << 24)).expect("relecture");
        let geom = file
            .section(SectionTag::GEOM)
            .expect("GEOM lisible")
            .expect("GEOM présente");

        let decoded = decode_geometry(&geom).expect("décodage");
        assert!(!decoded.meshes.is_empty());
        // Douze triangles au niveau de détail 0, chaque indice dans son mesh.
        let base: usize = decoded
            .meshes
            .iter()
            .filter(|mesh| mesh.lod == 0)
            .map(|mesh| mesh.index_count as usize)
            .sum();
        assert_eq!(base, 36);
    }

    #[test]
    fn t253_une_section_tronquee_ou_prolongee_est_refusee() {
        let (meshes, vertices, indices) = deux_triangles();
        let octets = section(&meshes, &vertices, &indices);
        assert!(decode_geometry(&octets[..octets.len() - 1]).is_err());
        let mut long = octets.clone();
        long.push(0);
        assert!(decode_geometry(&long).is_err());
        assert!(decode_geometry(&octets[..HEADER_BYTES - 1]).is_err());
    }

    #[test]
    fn t253_un_denombrement_demesure_est_refuse_avant_allocation() {
        // Aucun tableau n'est alloué : l'en-tête seul suffit au refus.
        let entete = |meshes: u32, vertices: u32, indices: u32| {
            let mut out = Vec::new();
            for value in [meshes, vertices, indices, 0] {
                out.extend_from_slice(&value.to_le_bytes());
            }
            out
        };
        let sommets = (limits::MAX_VERTICES + 1) as u32;
        let indices = (limits::MAX_INDICES + 1) as u32;
        assert!(decode_geometry(&entete(0, sommets, 0)).is_err());
        assert!(decode_geometry(&entete(0, 0, indices)).is_err());
        // 2³²−1 meshes : le produit déborderait un calcul naïf.
        assert!(decode_geometry(&entete(u32::MAX, 0, 0)).is_err());
    }

    #[test]
    fn t253_un_mesh_hors_de_ses_tableaux_est_refuse() {
        let (mut meshes, vertices, indices) = deux_triangles();
        meshes[1].vertex_count = 4; // 3 + 4 > 6 sommets
        assert!(decode_geometry(&section(&meshes, &vertices, &indices)).is_err());

        let (mut meshes, vertices, indices) = deux_triangles();
        meshes[1].index_offset = 4; // 4 + 3 > 6 indices
        assert!(decode_geometry(&section(&meshes, &vertices, &indices)).is_err());

        let (mut meshes, vertices, indices) = deux_triangles();
        meshes[0].vertex_offset = u32::MAX; // débordement de l'addition
        assert!(decode_geometry(&section(&meshes, &vertices, &indices)).is_err());
    }

    #[test]
    fn t253_des_indices_qui_ne_forment_pas_des_triangles_sont_refuses() {
        let (mut meshes, vertices, indices) = deux_triangles();
        meshes[1].index_count = 2;
        assert!(decode_geometry(&section(&meshes, &vertices, &indices)).is_err());
    }

    #[test]
    fn t253_un_indice_hors_de_son_mesh_est_refuse_et_porte_e3007() {
        // L'indice 3 existe dans le tableau global (six sommets) mais pas dans
        // le second mesh, qui n'en compte que trois : il est local.
        let (meshes, vertices, mut indices) = deux_triangles();
        indices[4] = 3;
        let refus = decode_geometry(&section(&meshes, &vertices, &indices)).unwrap_err();
        assert_eq!(refus.code(), -3007);
    }

    #[test]
    fn une_section_vide_est_valide() {
        let decoded = decode_geometry(&section(&[], &[], &[])).expect("décodage");
        assert!(decoded.meshes.is_empty() && decoded.vertices.is_empty());
    }
}
