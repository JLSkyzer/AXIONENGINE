//! Genere la classe Java du schema de configuration depuis `ax-model`.
//!
//! ```bash
//! cargo run -p axion-codegen --bin gen_java_config
//! ```
//!
//! Le test de parite T-005 echoue si le fichier produit diverge du registre.

use std::path::PathBuf;
use std::process::ExitCode;

fn main() -> ExitCode {
    let target = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .join(axion_codegen::JAVA_SCHEMA_PATH);

    if let Some(parent) = target.parent() {
        if let Err(err) = std::fs::create_dir_all(parent) {
            eprintln!("creation de {} impossible : {err}", parent.display());
            return ExitCode::FAILURE;
        }
    }

    if let Err(err) = std::fs::write(&target, axion_codegen::render_java_schema()) {
        eprintln!("ecriture de {} impossible : {err}", target.display());
        return ExitCode::FAILURE;
    }
    println!("{} genere depuis ax-model", axion_codegen::JAVA_SCHEMA_PATH);

    let buffers = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .join(axion_codegen::JAVA_BUFFER_PATH);
    if let Some(parent) = buffers.parent() {
        if let Err(err) = std::fs::create_dir_all(parent) {
            eprintln!("creation de {} impossible : {err}", parent.display());
            return ExitCode::FAILURE;
        }
    }
    if let Err(err) = std::fs::write(&buffers, axion_codegen::render_java_buffer_kinds()) {
        eprintln!("ecriture de {} impossible : {err}", buffers.display());
        return ExitCode::FAILURE;
    }
    println!("{} genere depuis ax-model", axion_codegen::JAVA_BUFFER_PATH);

    // Les fichiers de reference embarques dans le JAR : Java les recopie dans
    // le repertoire de configuration au premier demarrage, plutot que de
    // redupliquer la logique de rendu.
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..");
    if let Err(err) = std::fs::create_dir_all(root.join(axion_codegen::TOML_RESOURCE_DIR)) {
        eprintln!("creation du repertoire de ressources impossible : {err}");
        return ExitCode::FAILURE;
    }
    for scope in ax_model::config::ConfigScope::ALL {
        let relative = axion_codegen::toml_resource_path(scope);
        let path = root.join(&relative);
        if let Err(err) = std::fs::write(&path, ax_model::config::render_toml(scope)) {
            eprintln!("ecriture de {} impossible : {err}", path.display());
            return ExitCode::FAILURE;
        }
        println!("{relative} genere depuis ax-model");
    }

    ExitCode::SUCCESS
}
