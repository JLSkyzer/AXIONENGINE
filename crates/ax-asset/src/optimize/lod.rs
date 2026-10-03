//! Étape 6 — niveaux de détail.
//!
//! Contraction d'arêtes guidée par une métrique quadrique : c'est le
//! simplificateur de meshoptimizer, déjà présent pour le cache de sommets
//! (ADR-106). Les décisions propres à AXION sont consignées dans
//! `docs/decisions/ADR-107.md` ; les voici en bref.
//!
//! # Un LOD partage les sommets de sa source
//!
//! Le simplificateur ne crée aucun sommet : il choisit un sous-ensemble de
//! triangles sur les sommets d'origine. Un LOD est donc un `MeshDesc` qui
//! reprend la plage de sommets de sa source et n'apporte que ses indices. Deux
//! conséquences :
//!
//! - **R-550 tient par construction.** Chaque sommet d'un LOD *est* un sommet
//!   source : le sommet source le plus proche est lui-même, à distance nulle,
//!   et région et poids de déformation sont les siens ;
//! - un LOD ne coûte aucun sommet, seulement des indices.
//!
//! # Bords et coutures
//!
//! Le simplificateur classe chaque sommet. Un sommet de **bord** ouvert ne se
//! contracte que le long de ce bord, ce qui n'ouvre ni ne ferme aucun trou : la
//! topologie de bord est préservée (R-550) sans figer les bords, ce que ferait
//! `LockBorder` et qui empêcherait de simplifier une coque ouverte — une
//! carrosserie en est une. Un sommet de **couture** — même position, attributs
//! différents, ce que la fusion de l'étape 1 a laissé distinct — est traité de
//! même.
//!
//! # Niveaux, nodes et LOD d'auteur
//!
//! Un mesh est simplifié à partir du **plus bas niveau où un node visible le
//! porte**, et pour chaque niveau suivant où il est encore visible. Un node
//! limité au LOD 0 par son auteur — l'intérieur d'un véhicule, `lod=[0]` —
//! n'en reçoit aucun ; un node `lod=[1, 2, 3]` est le LOD 1 fourni par l'auteur,
//! qui remplace le LOD généré (R-550) et sert de source aux niveaux suivants,
//! avec des ratios pris relativement au sien.

use crate::import::ImportedAsset;
use crate::validate::triangle_area;
use ax_model::dm::geometry::MeshDesc;
use ax_model::dm::limits;
use ax_model::dm::scene::node_flags;

/// Nombre maximal de niveaux : le `lod_mask` d'un node est un `u8`.
pub const MAX_LOD_LEVELS: usize = 8;

/// Options de génération des LOD (PARTIE 6.4, bloc `lod`).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct LodOptions {
    /// La génération est active.
    pub enabled: bool,
    /// Nombre de niveaux, LOD 0 compris ; au plus [`MAX_LOD_LEVELS`].
    pub levels: u8,
    /// Part de triangles gardée à chaque niveau, relative au LOD 0. Seules les
    /// `levels` premières valeurs comptent ; la première vaut `1.0`.
    pub ratios: [f32; MAX_LOD_LEVELS],
    /// Erreur géométrique admise, relative à l'étendue du mesh.
    pub error_target: f32,
}

impl LodOptions {
    /// Les valeurs de la PARTIE 6.4 : LOD 0 source, LOD 1 ~50 %, LOD 2 ~25 %,
    /// LOD 3 ~10 %, erreur 0,02.
    pub const DEFAULT: Self = Self {
        enabled: true,
        levels: 4,
        ratios: [1.0, 0.5, 0.25, 0.1, 0.0, 0.0, 0.0, 0.0],
        error_target: 0.02,
    };

    /// Indique si les options décrivent une génération possible : niveaux
    /// dans les bornes, ratios finis dans `]0, 1]` et non croissants, erreur
    /// finie et positive.
    fn is_consistent(&self) -> bool {
        let levels = usize::from(self.levels);
        if !(1..=MAX_LOD_LEVELS).contains(&levels)
            || !self.error_target.is_finite()
            || self.error_target < 0.0
        {
            return false;
        }
        let ratios = &self.ratios[..levels];
        ratios
            .iter()
            .all(|ratio| ratio.is_finite() && *ratio > 0.0 && *ratio <= 1.0)
            && ratios.windows(2).all(|pair| pair[1] <= pair[0])
    }
}

impl Default for LodOptions {
    fn default() -> Self {
        Self::DEFAULT
    }
}

/// Table LOD → meshes, contenu de la section `LODM`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LodTable {
    /// Nombre de niveaux décrits par chaque ligne.
    pub levels: u8,
    /// Une ligne par mesh source, dans l'ordre des meshes : `rows[m][l]` est le
    /// mesh à rendre au niveau `l` pour un node portant le mesh `m`. Un niveau
    /// sans LOD propre désigne le niveau précédent.
    pub rows: Vec<[u32; MAX_LOD_LEVELS]>,
}

impl LodTable {
    /// Disposition de la section `LODM`, en petit-boutiste :
    ///
    /// ```text
    /// u32 levels
    /// u32 row_count
    /// u32 mesh[row_count * levels]     ligne par mesh source, colonne par niveau
    /// ```
    ///
    /// Les meshes générés suivent les meshes sources dans `GEOM` ; une ligne
    /// n'existe que pour ces derniers.
    #[must_use]
    pub fn to_bytes(&self) -> Vec<u8> {
        let levels = usize::from(self.levels);
        let mut out = Vec::with_capacity(8 + self.rows.len() * levels * 4);
        out.extend_from_slice(&u32::from(self.levels).to_le_bytes());
        out.extend_from_slice(&(self.rows.len() as u32).to_le_bytes());
        for row in &self.rows {
            for mesh in &row[..levels] {
                out.extend_from_slice(&mesh.to_le_bytes());
            }
        }
        out
    }
}

/// Génère les LOD ; rend la table, ou `None` si aucun LOD n'a été produit.
///
/// Les meshes générés sont ajoutés après les meshes sources, et leurs indices
/// après les indices existants. La fusion ne doit plus être rejouée ensuite :
/// elle donnerait à chaque LOD sa propre copie de sommets.
pub(super) fn generate(
    asset: &mut ImportedAsset,
    options: &LodOptions,
    warnings: &mut Vec<String>,
) -> Option<LodTable> {
    if !options.enabled {
        return None;
    }
    if !options.is_consistent() {
        warnings.push(format!(
            "options de LOD incohérentes, aucun LOD généré : {options:?}"
        ));
        return None;
    }
    let levels = usize::from(options.levels);
    let level_bits = if levels >= MAX_LOD_LEVELS {
        u8::MAX
    } else {
        (1u8 << levels) - 1
    };

    // Niveaux où chaque mesh est rendu : l'union des nodes visibles qui le
    // portent, chacun avec tous ses meshes (ADR-122 §5). Un node non rendu —
    // collider, socket (R-910) — ne compte pas.
    let mut visible = vec![0u8; asset.meshes.len()];
    for node in &asset.nodes {
        if node.flags & node_flags::VISIBLE == 0 {
            continue;
        }
        for rank in node.meshes() {
            if let Some(mask) = visible.get_mut(rank as usize) {
                *mask |= node.lod_mask;
            }
        }
    }

    let source_count = asset.meshes.len();
    let mut rows = Vec::with_capacity(source_count);
    let mut generated = 0;
    let mut budget_reached = false;

    for (rank, visible_levels) in visible.iter().enumerate() {
        let mut row = [rank as u32; MAX_LOD_LEVELS];
        let mask = visible_levels & level_bits;
        if mask == 0 {
            rows.push(row);
            continue;
        }

        let source_level = mask.trailing_zeros() as usize;
        asset.meshes[rank].lod = source_level as u8;
        let base = asset.meshes[rank];
        let start = base.index_offset as usize;
        let base_indices = asset.indices[start..start + base.index_count as usize].to_vec();
        let vertex_start = base.vertex_offset as usize;
        let positions: Vec<[f32; 3]> = asset.vertices
            [vertex_start..vertex_start + base.vertex_count as usize]
            .iter()
            .map(|vertex| vertex.position)
            .collect();

        let mut current = rank as u32;
        let mut current_count = base_indices.len();
        for (level, slot) in row
            .iter_mut()
            .enumerate()
            .take(levels)
            .skip(source_level + 1)
        {
            if mask & (1 << level) != 0 && !budget_reached {
                let ratio = options.ratios[level] / options.ratios[source_level];
                let target =
                    ((base_indices.len() as f32 * ratio / 3.0).round() as usize).max(1) * 3;
                if target < current_count {
                    let mut simplified = meshopt::simplify_decoder(
                        &base_indices,
                        &positions,
                        target,
                        options.error_target,
                        meshopt::SimplifyOptions::None,
                        None,
                    );
                    drop_degenerate(&mut simplified, &positions);

                    // Jamais de mesh vide (R-550), et un niveau qui ne réduit
                    // rien n'est pas un niveau : il désigne le précédent.
                    if !simplified.is_empty() && simplified.len() < current_count {
                        if asset.indices.len() + simplified.len() > limits::MAX_INDICES {
                            warnings.push(format!(
                                "mesh {rank} : LOD {level} et suivants non générés, \
                                 le plafond de {} indices serait dépassé",
                                limits::MAX_INDICES
                            ));
                            budget_reached = true;
                        } else {
                            let ordered =
                                meshopt::optimize_vertex_cache(&simplified, positions.len());
                            current = asset.meshes.len() as u32;
                            current_count = ordered.len();
                            asset.meshes.push(MeshDesc {
                                index_offset: asset.indices.len() as u32,
                                index_count: ordered.len() as u32,
                                lod: level as u8,
                                ..base
                            });
                            asset.indices.extend_from_slice(&ordered);
                            generated += 1;
                        }
                    }
                }
            }
            *slot = current;
        }
        rows.push(row);
    }

    (generated > 0).then_some(LodTable {
        levels: options.levels,
        rows,
    })
}

/// Retire les triangles dont l'aire tomberait sous le seuil de C-22.
///
/// La contraction peut aplatir un triangle qui ne l'était pas. Le garder ferait
/// refuser l'asset entier par la validation de sortie ; le retirer est la
/// réparation que R-542 admet, appliquée à une géométrie que C-23 a produite.
fn drop_degenerate(indices: &mut Vec<u32>, positions: &[[f32; 3]]) {
    let kept: Vec<u32> = indices
        .chunks_exact(3)
        .filter(|triangle| {
            let area = triangle_area(&[
                positions[triangle[0] as usize],
                positions[triangle[1] as usize],
                positions[triangle[2] as usize],
            ]);
            area.is_finite() && area > limits::MIN_TRIANGLE_AREA
        })
        .flatten()
        .copied()
        .collect();
    *indices = kept;
}

#[cfg(test)]
mod tests {
    use super::super::tests::{asset, sommet};
    use super::*;
    use ax_model::dm::scene::ALL_LODS;
    use std::collections::BTreeMap;

    const COTE: u32 = 24;

    /// Une grille plane de `COTE × COTE` carrés, visible à tous les niveaux.
    ///
    /// La moitié gauche appartient à la région 0, la droite à la région 1, et
    /// le poids de déformation croît avec x : de quoi voir qu'un LOD n'échange
    /// aucun attribut entre sommets.
    fn grille() -> ImportedAsset {
        let mut vertices = Vec::new();
        for y in 0..=COTE {
            for x in 0..=COTE {
                let mut vertex = sommet([x as f32, y as f32, 0.0]);
                vertex.normal = [0, 0, 127, 0];
                vertex.region = region_attendue(x as f32);
                vertex.def_w = poids_attendu(x as f32);
                vertices.push(vertex);
            }
        }
        let mut indices = Vec::new();
        for y in 0..COTE {
            for x in 0..COTE {
                let a = y * (COTE + 1) + x;
                let (b, c, d) = (a + 1, a + COTE + 2, a + COTE + 1);
                indices.extend([a, b, c, a, c, d]);
            }
        }
        let mut asset = asset(vertices, indices, false);
        asset.nodes[0].lod_mask = ALL_LODS;
        asset
    }

    fn region_attendue(x: f32) -> u8 {
        u8::from(x >= (COTE / 2) as f32)
    }

    fn poids_attendu(x: f32) -> u8 {
        (x * 10.0) as u8
    }

    fn indices_du_mesh(asset: &ImportedAsset, mesh: usize) -> &[u32] {
        let desc = asset.meshes[mesh];
        let start = desc.index_offset as usize;
        &asset.indices[start..start + desc.index_count as usize]
    }

    #[test]
    fn t801_un_lod_conserve_region_et_poids_de_chaque_sommet() {
        let mut asset = grille();
        let table = generate(&mut asset, &LodOptions::DEFAULT, &mut Vec::new())
            .expect("des LOD sont générés");

        assert_eq!(asset.meshes.len(), 4);
        for mesh in 1..asset.meshes.len() {
            let desc = asset.meshes[mesh];
            // Même plage de sommets que la source : chaque sommet d'un LOD est
            // un sommet source, à distance nulle de lui-même (R-550).
            assert_eq!(desc.vertex_offset, asset.meshes[0].vertex_offset);
            assert_eq!(desc.vertex_count, asset.meshes[0].vertex_count);
            for &local in indices_du_mesh(&asset, mesh) {
                let vertex = asset.vertices[(desc.vertex_offset + local) as usize];
                let x = vertex.position[0];
                assert_eq!(vertex.region, region_attendue(x), "LOD {mesh}, x = {x}");
                assert_eq!(vertex.def_w, poids_attendu(x), "LOD {mesh}, x = {x}");
            }
        }
        assert_eq!(table.rows, vec![[0, 1, 2, 3, 0, 0, 0, 0]]);
    }

    #[test]
    fn t550_les_niveaux_decroissent_sans_jamais_etre_vides() {
        let mut asset = grille();
        generate(&mut asset, &LodOptions::DEFAULT, &mut Vec::new()).expect("des LOD");

        let source = asset.meshes[0].index_count as usize;
        let mut precedent = source;
        for (level, ratio) in [(1, 0.5), (2, 0.25), (3, 0.1)] {
            let desc = asset.meshes[level];
            let count = desc.index_count as usize;
            let cible = ((source as f32 * ratio / 3.0).round() as usize).max(1) * 3;
            assert_eq!(desc.lod, level as u8);
            assert!(count > 0, "LOD {level} vide");
            assert!(count <= cible, "LOD {level} : {count} indices pour {cible}");
            assert!(count < precedent, "LOD {level} ne réduit rien");
            precedent = count;
        }
    }

    #[test]
    fn t550_la_topologie_de_bord_est_preservee() {
        let mut asset = grille();
        generate(&mut asset, &LodOptions::DEFAULT, &mut Vec::new()).expect("des LOD");
        let bord = |position: [f32; 3]| {
            let limite = COTE as f32;
            position[0] == 0.0
                || position[1] == 0.0
                || position[0] == limite
                || position[1] == limite
        };

        for mesh in 1..asset.meshes.len() {
            let indices = indices_du_mesh(&asset, mesh);
            // Une arête de bord n'appartient qu'à un triangle. Si l'une avait
            // une extrémité à l'intérieur de la grille, le LOD aurait ouvert un
            // trou.
            let mut aretes: BTreeMap<(u32, u32), u32> = BTreeMap::new();
            for triangle in indices.chunks_exact(3) {
                for k in 0..3 {
                    let (a, b) = (triangle[k], triangle[(k + 1) % 3]);
                    *aretes.entry((a.min(b), a.max(b))).or_insert(0) += 1;
                }
            }
            for ((a, b), usages) in aretes {
                if usages == 1 {
                    for extremite in [a, b] {
                        let position = asset.vertices[extremite as usize].position;
                        assert!(
                            bord(position),
                            "LOD {mesh} : bord intérieur en {position:?}"
                        );
                    }
                }
            }

            // Les quatre coins restent : la silhouette n'a pas bougé.
            let mut min = [f32::INFINITY; 2];
            let mut max = [f32::NEG_INFINITY; 2];
            for &local in indices {
                let position = asset.vertices[local as usize].position;
                for axe in 0..2 {
                    min[axe] = min[axe].min(position[axe]);
                    max[axe] = max[axe].max(position[axe]);
                }
            }
            assert_eq!((min, max), ([0.0; 2], [COTE as f32; 2]), "LOD {mesh}");
        }
    }

    #[test]
    fn t550_tous_les_meshes_d_un_node_recoivent_leurs_lod() {
        // Un mesh glTF à deux primitives : le node les porte toutes deux, et la
        // seconde n'était ni comptée visible, ni simplifiée.
        let mut asset = grille();
        let mut second = asset.meshes[0];
        second.vertex_offset = asset.vertices.len() as u32;
        second.index_offset = asset.indices.len() as u32;
        second.uv0_range = ax_model::dm::geometry::UvRange::new(0, 4)
            .expect("[0, 4]")
            .to_bits();
        asset.vertices.extend_from_within(..);
        asset.indices.extend_from_within(..);
        asset.raw_uvs.extend_from_within(..);
        asset.missing_normals.extend_from_within(..);
        asset.meshes.push(second);
        asset.nodes[0].mesh_count = 2;

        let table = generate(&mut asset, &LodOptions::DEFAULT, &mut Vec::new()).expect("des LOD");
        assert_eq!(table.rows.len(), 2);
        assert_eq!(asset.meshes.len(), 8, "deux sources, trois LOD chacune");
        assert_eq!(table.rows[1], [1, 5, 6, 7, 1, 1, 1, 1]);
        // Un LOD garde la plage d'UV de sa source (ADR-122 §4).
        for rang in &table.rows[1][1..4] {
            assert_eq!(asset.meshes[*rang as usize].uv0_range, second.uv0_range);
        }
    }

    #[test]
    fn t550_un_node_limite_au_lod_0_ne_recoit_aucun_lod() {
        // L'intérieur d'un véhicule, `lod=[0]` (R-950).
        let mut asset = grille();
        asset.nodes[0].lod_mask = 1;
        let avant = asset.meshes.len();

        assert_eq!(
            generate(&mut asset, &LodOptions::DEFAULT, &mut Vec::new()),
            None
        );
        assert_eq!(asset.meshes.len(), avant);
    }

    #[test]
    fn t550_un_lod_d_auteur_sert_de_source_aux_niveaux_suivants() {
        // Un node `lod=[1, 2, 3]` : son mesh est le LOD 1 de l'auteur.
        let mut asset = grille();
        asset.nodes[0].lod_mask = 0b1110;
        let table = generate(&mut asset, &LodOptions::DEFAULT, &mut Vec::new()).expect("des LOD");

        assert_eq!(asset.meshes[0].lod, 1);
        assert_eq!(asset.meshes.len(), 3);
        assert_eq!(asset.meshes[1].lod, 2);
        // LOD 2 : 0,25 / 0,5 du mesh de l'auteur.
        let source = asset.meshes[0].index_count as usize;
        let cible = ((source as f32 * 0.5 / 3.0).round() as usize) * 3;
        assert!(asset.meshes[1].index_count as usize <= cible);
        assert_eq!(table.rows, vec![[0, 0, 1, 2, 0, 0, 0, 0]]);
    }

    #[test]
    fn t550_une_generation_desactivee_ne_touche_a_rien() {
        let mut asset = grille();
        let avant = asset.clone();
        let options = LodOptions {
            enabled: false,
            ..LodOptions::DEFAULT
        };
        assert_eq!(generate(&mut asset, &options, &mut Vec::new()), None);
        assert_eq!(asset, avant);
    }

    #[test]
    fn t550_des_options_incoherentes_avertissent_sans_rien_generer() {
        let mut asset = grille();
        let avant = asset.clone();
        let mut warnings = Vec::new();
        let options = LodOptions {
            ratios: [1.0, 0.25, 0.5, 0.1, 0.0, 0.0, 0.0, 0.0],
            ..LodOptions::DEFAULT
        };

        assert_eq!(generate(&mut asset, &options, &mut warnings), None);
        assert_eq!(asset, avant);
        assert_eq!(warnings.len(), 1);
    }

    #[test]
    fn t550_la_table_suit_sa_disposition() {
        let table = LodTable {
            levels: 2,
            rows: vec![[0, 3, 0, 0, 0, 0, 0, 0], [1, 1, 0, 0, 0, 0, 0, 0]],
        };
        let octets = table.to_bytes();
        let mots: Vec<u32> = octets
            .chunks_exact(4)
            .map(|mot| u32::from_le_bytes(mot.try_into().unwrap()))
            .collect();
        assert_eq!(mots, [2, 2, 0, 3, 1, 1]);
    }
}
