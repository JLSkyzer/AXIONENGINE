//! B-08, variante de référence : l'allocateur du système (voir
//! `ax_bench::memory_runner`).

fn main() -> std::process::ExitCode {
    ax_bench::memory_runner::run("system")
}
