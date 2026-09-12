//! Étape 5 — cache de sommets et localité.
//!
//! Deux réordonnancements, dans cet ordre :
//!
//! 1. **les triangles**, pour que le GPU retrouve dans son cache de sommets
//!    transformés ceux du triangle suivant. C'est l'algorithme de meshoptimizer,
//!    dérivé de celui de Tom Forsyth (« Linear-Speed Vertex Cache
//!    Optimisation », 2006) avec des tables de score ajustées ;
//! 2. **les sommets**, dans l'ordre où le nouveau tampon d'indices les lit.
//!    C'est la fusion de l'étape 1 qui s'en charge, rejouée après : elle range
//!    déjà les sommets par première apparition, et une seconde passe ne fusionne
//!    rien de plus.
//!
//! Seul l'ordre change : ni le nombre de triangles, ni leur sens de parcours, ni
//! aucun sommet.

use crate::import::ImportedAsset;

/// Réordonne les triangles de chaque mesh pour le cache de sommets.
pub(super) fn optimize_vertex_cache(asset: &mut ImportedAsset) {
    for mesh in &asset.meshes {
        if mesh.index_count == 0 {
            continue;
        }
        let start = mesh.index_offset as usize;
        let range = start..start + mesh.index_count as usize;
        let optimized = meshopt::optimize_vertex_cache(
            &asset.indices[range.clone()],
            mesh.vertex_count as usize,
        );
        asset.indices[range].copy_from_slice(&optimized);
    }
}

#[cfg(test)]
mod tests {
    use super::super::tests::{asset, sommet};
    use super::*;

    /// Une grille de `n × n` carrés, triangles émis dans un ordre brassé.
    fn grille(n: u32) -> ImportedAsset {
        let mut vertices = Vec::new();
        for y in 0..=n {
            for x in 0..=n {
                vertices.push(sommet([x as f32, y as f32, 0.0]));
            }
        }
        let carres = n * n;
        let mut indices = Vec::new();
        for rang in 0..carres {
            // 7919 est premier avec le nombre de carrés : chaque carré sort une
            // fois, dans un ordre sans localité.
            let carre = (rang * 7919) % carres;
            let (x, y) = (carre % n, carre / n);
            let a = y * (n + 1) + x;
            let (b, c, d) = (a + 1, a + n + 2, a + n + 1);
            indices.extend([a, b, c, a, c, d]);
        }
        asset(vertices, indices, false)
    }

    /// Les triangles, chacun tourné pour commencer par son plus petit indice :
    /// le sens de parcours est conservé, l'ordre des triangles oublié.
    fn triangles(indices: &[u32]) -> Vec<[u32; 3]> {
        let mut out: Vec<[u32; 3]> = indices
            .chunks_exact(3)
            .map(|t| {
                let tourne = (0..3).min_by_key(|&k| t[k]).unwrap_or(0);
                [t[tourne], t[(tourne + 1) % 3], t[(tourne + 2) % 3]]
            })
            .collect();
        out.sort_unstable();
        out
    }

    fn acmr(asset: &ImportedAsset) -> f32 {
        meshopt::analyze_vertex_cache(&asset.indices, asset.vertices.len(), 16, 0, 0).acmr
    }

    #[test]
    fn t244_le_cache_de_sommets_est_mieux_servi() {
        let mut asset = grille(24);
        let avant = acmr(&asset);
        let triangles_avant = triangles(&asset.indices);

        optimize_vertex_cache(&mut asset);

        // ACMR : sommets transformés par triangle, sur un cache de 16. Plus
        // bas, c'est mieux ; le test ne vaut que comme comparaison, sur cette
        // grille, et n'annonce aucun gain en jeu.
        let apres = acmr(&asset);
        assert!(apres < avant, "ACMR {avant} -> {apres}");
        assert_eq!(triangles(&asset.indices), triangles_avant);
    }

    #[test]
    fn t244_le_reordonnancement_est_deterministe() {
        let mut une = grille(12);
        let mut deux = grille(12);
        optimize_vertex_cache(&mut une);
        optimize_vertex_cache(&mut deux);
        assert_eq!(une.indices, deux.indices);
    }
}
