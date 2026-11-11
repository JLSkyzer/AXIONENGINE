//! Genere le builder Java des definitions depuis le schema publie (ADR-162 D4, sous-tranche 2c).
//!
//! ```bash
//! cargo run -p axion-codegen --bin gen_definition_builder
//! ```
//!
//! Le test de parite `parite_builder` echoue si le fichier produit diverge du schema.

use axion_codegen::definition_builder::{latest_schema, render_definition_builder, BUILDER_PATH};
use std::path::PathBuf;
use std::process::ExitCode;

fn main() -> ExitCode {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..");
    let (version, schema) = match latest_schema(&root) {
        Ok(found) => found,
        Err(err) => {
            eprintln!("{err}");
            return ExitCode::FAILURE;
        }
    };
    let text = match std::fs::read_to_string(&schema) {
        Ok(text) => text,
        Err(err) => {
            eprintln!("{} illisible : {err}", schema.display());
            return ExitCode::FAILURE;
        }
    };
    let java = match render_definition_builder(&text, version) {
        Ok(java) => java,
        Err(err) => {
            eprintln!("{err}");
            return ExitCode::FAILURE;
        }
    };
    if let Err(err) = std::fs::write(root.join(BUILDER_PATH), java) {
        eprintln!("ecriture de {BUILDER_PATH} impossible : {err}");
        return ExitCode::FAILURE;
    }
    println!("{BUILDER_PATH} genere depuis definition-{version}.json");
    ExitCode::SUCCESS
}
