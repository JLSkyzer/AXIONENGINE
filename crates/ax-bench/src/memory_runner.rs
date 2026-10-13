//! Coureur de B-08 (R-501, ADR-133 D2) : mesure les charges de
//! [`crate::cases::memory`] sous l'allocateur que le binaire appelant a installé, et
//! archive un résultat par charge, sa variante dans le nom (`<charge>-<allocateur>`).
//!
//! Le binaire `bench-alloc-system` garde l'allocateur du système ;
//! `bench-alloc-counted` installe l'allocateur compteur. L'écart se lit en comparant
//! leurs résultats archivés, à commit, date et plateforme égaux.

use std::collections::BTreeMap;
use std::process::ExitCode;

use crate::{archive, cases, cli, BenchmarkResult, MeasureConfig, Platform};

/// Mesure les trois charges de B-08 et rend le code de sortie du binaire.
#[must_use]
pub fn run(allocator: &str) -> ExitCode {
    let help = format!(
        "bench-alloc-{allocator} — B-08, surcout de l'allocateur compteur (R-501)\n\n\
         Usage : bench-alloc-{allocator} [OPTIONS]\n\n{}",
        cli::OPTIONS_HELP
    );
    let args = match cli::parse(&help) {
        Ok(args) => args,
        Err(message) => {
            eprintln!("bench-alloc-{allocator} : {message}");
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
        "bench-alloc-{allocator} : B-08, configuration {}",
        if args.normative {
            "normative"
        } else {
            "rapide"
        }
    );
    if !can_archive {
        println!("  archivage ignore : provenance incomplete (--commit, --date et --cpu requis)");
    }
    for case in cases::memory::ci_cases(&config, allocator) {
        let workload = case.parameters["workload"]
            .as_str()
            .unwrap_or("?")
            .to_string();
        let Some(result) = BenchmarkResult::new(
            case.benchmark,
            args.commit.clone().unwrap_or_default(),
            args.date.clone().unwrap_or_default(),
            platform.clone(),
            BTreeMap::new(),
            case.parameters,
            case.runs,
        ) else {
            eprintln!("  {workload} : aucun echantillon");
            return ExitCode::FAILURE;
        };
        let s = &result.stats;
        println!(
            "  {workload} : p50={} p95={} p99={} ns (min {}, max {})",
            s.p50_ns, s.p95_ns, s.p99_ns, s.min_ns, s.max_ns
        );
        if can_archive {
            match archive(&result, &args.out) {
                Ok(path) => println!("    archive : {}", path.display()),
                Err(error) => {
                    eprintln!("    archivage echoue : {error}");
                    return ExitCode::FAILURE;
                }
            }
        }
    }
    ExitCode::SUCCESS
}
