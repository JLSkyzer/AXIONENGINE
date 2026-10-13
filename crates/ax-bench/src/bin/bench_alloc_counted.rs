//! B-08, variante mesurée : l'allocateur compteur installé comme allocateur global,
//! comme dans la bibliothèque native (voir `ax_bench::memory_runner`, ADR-133 D2).

#[global_allocator]
static ALLOCATOR: ax_mem::CountingAllocator = ax_mem::CountingAllocator::new();

fn main() -> std::process::ExitCode {
    let code = ax_bench::memory_runner::run("counted");
    // La preuve que la mesure a tourné sous le compteur : il a vu passer des octets.
    println!(
        "  allocateur compteur actif : {} octets vivants a la fin",
        ALLOCATOR.live_bytes()
    );
    code
}
