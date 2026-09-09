//! Génère `CONFIGURATION.md` depuis la source unique de la configuration.
//!
//! R-430 : la documentation des options est générée, jamais écrite à la main,
//! et une option non documentée casse le build (T-021). Le job `docs` de la CI
//! exécute ce binaire, puis vérifie que rien n'a bougé dans l'arbre de travail.
//!
//! ```bash
//! cargo run -p ax-model --bin gen_config_docs
//! ```

use std::path::PathBuf;
use std::process::ExitCode;

fn main() -> ExitCode {
    let target = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../CONFIGURATION.md");

    let rendered = ax_model::config::render_markdown();
    match std::fs::write(&target, rendered) {
        Ok(()) => {
            println!("CONFIGURATION.md généré depuis crates/ax-model/src/config/defaults.rs");
            ExitCode::SUCCESS
        }
        Err(err) => {
            eprintln!("écriture de {} impossible : {err}", target.display());
            ExitCode::FAILURE
        }
    }
}
