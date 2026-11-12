//! Génère `CONFIGURATION.md` et sa traduction `CONFIGURATION.en.md` depuis la
//! source unique de la configuration.
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

use ax_model::config::{render_markdown, DocLanguage};

fn main() -> ExitCode {
    let guides = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../docs/guides");

    for language in DocLanguage::ALL {
        let target = guides.join(language.file_name());
        if let Err(err) = std::fs::write(&target, render_markdown(language)) {
            eprintln!("écriture de {} impossible : {err}", target.display());
            return ExitCode::FAILURE;
        }
        println!(
            "{} généré depuis crates/ax-model/src/config/defaults.rs",
            language.file_name()
        );
    }
    ExitCode::SUCCESS
}
