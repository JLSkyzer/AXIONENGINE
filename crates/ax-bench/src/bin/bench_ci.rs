//! `bench-ci` — coureur de non-regression (R-2250, R-2251).
//!
//! Mesure le sous-ensemble rapide des benchmarks dont le composant existe, le
//! compare au dernier resultat archive de la meme plateforme, et sort en echec
//! si le p95 a cru de plus de 20 % (R-2251). Les chiffres servent a detecter une
//! regression grossiere, pas a etre publies (R-2252).
//!
//! ```text
//! bench-ci [--out <dir>] [--commit <sha>] [--date <iso>] [--cpu <modele>] [--normative]
//! ```
//!
//! L'archivage n'a lieu que si la provenance est complete (`--commit`, `--date`,
//! `--cpu`) : R-2231 exige qu'un resultat archive soit reproductible. Sans elle,
//! le coureur mesure et compare tout de meme, mais n'ecrit rien.

use std::collections::BTreeMap;
use std::process::ExitCode;

use ax_bench::cases;
use ax_bench::{
    archive, compare_p95, latest_baseline, BenchmarkResult, MeasureConfig, Platform, Regression,
};

fn main() -> ExitCode {
    let args = match ax_bench::cli::parse(&help()) {
        Ok(args) => args,
        Err(message) => {
            eprintln!("bench-ci : {message}");
            eprintln!("essayez : bench-ci --help");
            return ExitCode::from(2);
        }
    };

    let config = if args.normative {
        MeasureConfig::NORMATIVE
    } else {
        MeasureConfig::QUICK
    };
    let mut platform = Platform::detect();
    if let Some(cpu) = &args.cpu {
        platform.cpu = cpu.clone();
    }
    let can_archive =
        args.commit.is_some() && args.date.is_some() && !platform.cpu.trim().is_empty();

    println!(
        "bench-ci : sous-ensemble rapide (R-2250), configuration {}",
        if args.normative {
            "normative"
        } else {
            "rapide"
        }
    );
    if !can_archive {
        println!("  archivage ignore : provenance incomplete (--commit, --date et --cpu requis)");
    }

    let cases = [
        cases::scene::ci_case(&config),
        cases::asset::ci_case(&config),
    ];

    let mut blocked = false;
    for case in cases {
        let benchmark = case.benchmark;
        let result = BenchmarkResult::new(
            benchmark,
            args.commit.clone().unwrap_or_default(),
            args.date.clone().unwrap_or_default(),
            platform.clone(),
            BTreeMap::new(),
            case.parameters,
            case.runs,
        )
        .expect("un cas CI porte toujours des echantillons");

        let verdict = match latest_baseline(&args.out, benchmark, &platform) {
            Some(baseline) => compare_p95(&result, &baseline),
            None => Regression::NoBaseline,
        };
        report(benchmark, &result, verdict);
        if verdict.blocks_merge() {
            blocked = true;
        }

        if can_archive {
            match archive(&result, &args.out) {
                Ok(path) => println!("  archive : {}", path.display()),
                Err(error) => {
                    eprintln!("  archivage echoue : {error}");
                    return ExitCode::FAILURE;
                }
            }
        }
    }

    if blocked {
        eprintln!("bench-ci : regression p95 superieure a 20 % (R-2251) — fusion bloquee");
        ExitCode::FAILURE
    } else {
        println!("bench-ci : aucune regression bloquante");
        ExitCode::SUCCESS
    }
}

/// Affiche les statistiques d'un resultat et le verdict de non-regression.
fn report(benchmark: &str, result: &BenchmarkResult, verdict: Regression) {
    let s = &result.stats;
    println!(
        "{benchmark} : p50={} p95={} p99={} ns (min {}, max {}, ecart-type {:.1})",
        s.p50_ns, s.p95_ns, s.p99_ns, s.min_ns, s.max_ns, s.stddev_ns
    );
    match verdict {
        Regression::NoBaseline => println!("  pas de reference comparable"),
        Regression::Ok { ratio } => println!("  p95 {:+.1} % vs reference — ok", ratio * 100.0),
        Regression::Regressed { ratio } => {
            println!(
                "  p95 {:+.1} % vs reference — REGRESSION (> 20 %)",
                ratio * 100.0
            );
        }
    }
}

fn help() -> String {
    format!(
        "bench-ci — coureur de non-regression (R-2250, R-2251)\n\n\
         Usage : bench-ci [OPTIONS]\n\n{}",
        ax_bench::cli::OPTIONS_HELP
    )
}
