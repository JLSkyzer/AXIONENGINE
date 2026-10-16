//! R-501 par le calcul (ADR-133 A1) : compte les allocations des charges de B-08 sous
//! [`AllocationEvents`], lit la campagne B-08 archivée du même commit, et rend le verdict.
//!
//! Se compile en release, comme les binaires de B-08 : le compte est celui du code mesuré.

use std::process::ExitCode;

use ax_bench::alloc_events::{count, AllocationEvents};
use ax_bench::cases::memory;
use ax_bench::r501::{judge, Campaign, Inputs, THRESHOLD};
use ax_bench::{cli, platform_slug, Platform};

#[global_allocator]
static ALLOCATOR: AllocationEvents = AllocationEvents::new();

const HELP: &str = "bench-r501 — R-501 par le calcul (ADR-133 A1)\n\n\
Usage : bench-r501 --commit <sha> --cpu <modele> [--out <dir>]\n\n\
Compte les allocations de small_allocations et de physics_ticks, lit sous\n\
<out>/B-08/<plateforme>/ la derniere campagne complete du commit, et juge :\n\
allocations de physics_ticks x surcout d'une allocation (ecart des p50 de\n\
small_allocations, compteur contre systeme), rapporte au p50 de physics_ticks\n\
sous l'allocateur du systeme. Sortie en erreur si R-501 ne tient pas.\n\n\
Chiffres d'un poste de developpement : ils ne se publient pas (R-2252).";

fn main() -> ExitCode {
    if cfg!(debug_assertions) {
        eprintln!("bench-r501 : a compiler en release (cargo run --release), comme B-08");
        return ExitCode::from(2);
    }
    let args = match cli::parse(HELP) {
        Ok(args) => args,
        Err(message) => {
            eprintln!("bench-r501 : {message}");
            return ExitCode::from(2);
        }
    };
    let Some(commit) = args.commit else {
        eprintln!("bench-r501 : --commit requis (le commit de la campagne B-08)");
        return ExitCode::from(2);
    };
    let mut platform = Platform::detect();
    if let Some(cpu) = args.cpu {
        platform.cpu = cpu;
    }
    let dir = args.out.join("B-08").join(platform_slug(&platform));
    let campaign = match Campaign::load(&dir, &commit) {
        Ok(campaign) => campaign,
        Err(message) => {
            eprintln!("bench-r501 : {message}");
            return ExitCode::FAILURE;
        }
    };

    // B-08 mesure après échauffement : le compte aussi.
    memory::small_allocations();
    memory::physics_ticks();
    let ((), small) = count(memory::small_allocations);
    let ((), physics) = count(memory::physics_ticks);
    let mut world = memory::physics_world();
    let ((), steps) = count(|| memory::physics_steps(&mut world));

    let verdict = match judge(&Inputs {
        small_allocations: small,
        small_system_ns: campaign.small_system_ns,
        small_counted_ns: campaign.small_counted_ns,
        physics_allocations: physics,
        physics_system_ns: campaign.physics_system_ns,
    }) {
        Ok(verdict) => verdict,
        Err(message) => {
            eprintln!("bench-r501 : {message}");
            return ExitCode::FAILURE;
        }
    };

    println!(
        "bench-r501 : campagne B-08 du {} (commit {})",
        campaign.date, campaign.commit
    );
    println!(
        "  allocations comptees : small_allocations {small}, physics_ticks {physics} \
         (dont {steps} sur ses {} pas)",
        memory::TICKS
    );
    println!(
        "  surcout d'une allocation : {:.2} ns ; sur physics_ticks : {:.0} ns, {:.4} % de sa duree \
         (seuil {} %)",
        verdict.overhead_per_allocation_ns,
        verdict.telemetry_ns,
        verdict.fraction * 100.0,
        THRESHOLD * 100.0
    );
    println!("  chiffres d'un poste de developpement : a ne pas publier (R-2252)");
    if verdict.holds() {
        println!("  R-501 : tenu");
        ExitCode::SUCCESS
    } else {
        println!("  R-501 : NON TENU");
        ExitCode::FAILURE
    }
}
