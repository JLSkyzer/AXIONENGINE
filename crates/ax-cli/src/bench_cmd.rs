//! Commande `bench-asset` : ce que coûte la compilation d'une source (B-09).
//!
//! La compilation mesurée est celle du jeu ([`compile_source`], R-830), par le
//! harnais C-72 ([`ax_bench::measure`], [`ax_bench::Stats`]) : échauffement,
//! plusieurs exécutions, centiles — jamais la moyenne seule (PARTIE 30.3). Les
//! fichiers voisins que la source désigne sont lus une fois, avant la mesure :
//! c'est la compilation qu'on chronomètre, pas le disque.
//!
//! C'est une mesure locale, pour l'auteur qui règle son asset : elle ne se
//! publie pas (R-2252), et la sortie le dit.

use crate::compile_cmd::compile_source;
use crate::CliError;
use ax_asset::compile::CompileOptions;
use ax_asset::import::SourceFormat;
use ax_bench::{measure, MeasureConfig, Stats};
use std::collections::BTreeMap;

/// Mesure la compilation d'une source ; une ligne par fait.
///
/// `resolve` fournit les fichiers voisins de la source ; il n'est appelé que
/// pendant la première compilation, qui valide la source et les garde en
/// mémoire pour les suivantes.
///
/// # Errors
///
/// [`CliError::Compile`] si la source est refusée : on ne mesure pas ce que le
/// jeu ne compilerait pas.
pub fn bench_asset(
    source: &[u8],
    format: SourceFormat,
    mut resolve: impl FnMut(&str) -> Option<Vec<u8>>,
    options: &CompileOptions,
    config: &MeasureConfig,
) -> Result<Vec<String>, CliError> {
    let mut siblings: BTreeMap<String, Option<Vec<u8>>> = BTreeMap::new();
    let first = compile_source(
        source,
        format,
        |relative: &str| {
            let found = resolve(relative);
            siblings.insert(relative.to_owned(), found.clone());
            found
        },
        options,
    )?;

    let runs = measure(config, || {
        let compiled = compile_source(
            source,
            format,
            |relative: &str| siblings.get(relative).cloned().flatten(),
            options,
        );
        std::hint::black_box(compiled.map(|compiled| compiled.bytes.len()).ok());
    });
    let samples: Vec<u64> = runs
        .iter()
        .flat_map(|run| run.samples_ns.iter().copied())
        .collect();
    // `measure` rend au moins un échantillon par exécution : une statistique sans
    // échantillon n'existe pas, et rien ne s'invente à sa place (R-2230).
    let stats = Stats::from_samples(&samples).ok_or_else(|| {
        CliError::Usage("la configuration de mesure n'a produit aucun échantillon".to_owned())
    })?;

    Ok(vec![
        "bench-asset : B-09, compilation d'asset par le pipeline du jeu".to_owned(),
        format!(
            "  {} octet(s) source → {} octet(s) A3D, {} sommet(s), {} mesh(s)",
            source.len(),
            first.bytes.len(),
            first.vertex_count,
            first.mesh_count
        ),
        format!(
            "  {} exécution(s), {} échantillon(s), après {} ms d'échauffement",
            runs.len(),
            samples.len(),
            config.warmup.as_millis()
        ),
        format!(
            "  p50 {} · p95 {} · p99 {} · min {} · max {} · écart-type {}",
            duration(stats.p50_ns),
            duration(stats.p95_ns),
            duration(stats.p99_ns),
            duration(stats.min_ns),
            duration(stats.max_ns),
            duration(stats.stddev_ns.round() as u64)
        ),
        "  mesure locale, sur cette machine : non publiable (R-2252)".to_owned(),
    ])
}

/// Une durée lisible, dans l'unité qui lui convient.
fn duration(ns: u64) -> String {
    if ns >= 1_000_000 {
        format!("{:.3} ms", ns as f64 / 1e6)
    } else if ns >= 1_000 {
        format!("{:.1} µs", ns as f64 / 1e3)
    } else {
        format!("{ns} ns")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::compile_cmd::options;
    use std::time::Duration;

    const CARRE_OBJ: &str = "\
o carre
mtllib carre.mtl
v 0.0 0.0 0.0
v 1.0 0.0 0.0
v 1.0 1.0 0.0
v 0.0 1.0 0.0
vn 0.0 0.0 1.0
usemtl bois
f 1//1 2//1 3//1
f 1//1 3//1 4//1
";

    const CARRE_MTL: &str = "newmtl bois\nKd 0.6 0.4 0.2\n";

    /// Une configuration minuscule : de quoi vérifier le déroulement, sans
    /// tenir une seconde.
    fn minuscule() -> MeasureConfig {
        MeasureConfig {
            warmup: Duration::ZERO,
            runs: 2,
            run_duration: Duration::ZERO,
            max_samples_per_run: 4,
        }
    }

    #[test]
    fn t580_bench_asset_mesure_la_compilation_et_le_dit_locale() {
        let mut lectures = 0;
        let opts = options("carre.obj", CARRE_OBJ.as_bytes(), false);
        let lignes = bench_asset(
            CARRE_OBJ.as_bytes(),
            SourceFormat::Obj,
            |relative| {
                lectures += 1;
                (relative == "carre.mtl").then(|| CARRE_MTL.as_bytes().to_vec())
            },
            &opts,
            &minuscule(),
        )
        .unwrap();
        let texte = lignes.join("\n");

        assert!(lignes[0].contains("B-09"), "{texte}");
        assert!(texte.contains("4 sommet(s), 1 mesh(s)"), "{texte}");
        assert!(texte.contains("2 exécution(s)"), "{texte}");
        assert!(texte.contains("p50 ") && texte.contains("p95 "), "{texte}");
        assert!(texte.contains("non publiable (R-2252)"), "{texte}");
        // Le `.mtl` n'est lu qu'à la première compilation : la mesure porte sur la
        // compilation, pas sur le disque.
        assert_eq!(lectures, 1, "{texte}");
    }

    #[test]
    fn t580_bench_asset_ne_mesure_pas_une_source_refusee() {
        let source: &[u8] = b"\xff\xfe pas de l'utf8";
        let opts = options("illisible.obj", source, false);
        let refus =
            bench_asset(source, SourceFormat::Obj, |_| None, &opts, &minuscule()).unwrap_err();
        assert!(matches!(refus, CliError::Compile(_)), "{refus}");
    }

    #[test]
    fn t580_une_duree_se_lit_dans_son_unite() {
        assert_eq!(duration(850), "850 ns");
        assert_eq!(duration(12_345), "12.3 µs");
        assert_eq!(duration(4_567_890), "4.568 ms");
    }
}
