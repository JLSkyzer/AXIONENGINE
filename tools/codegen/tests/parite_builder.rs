//! ADR-162 D4 — parité entre le schéma de definition publié et le builder Java généré.
//!
//! Le builder est dérivé du schéma : ce test échoue dès qu'ils divergent, parce que le schéma a changé sans
//! régénérer le builder, ou parce que le builder a été modifié à la main.

use axion_codegen::definition_builder::{latest_schema, render_definition_builder, BUILDER_PATH};
use std::path::PathBuf;

const REGENERER: &str = "cargo run -p axion-codegen --bin gen_definition_builder";

#[test]
fn le_builder_java_est_a_jour() {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..");
    let (version, schema) = latest_schema(&root).expect("schéma publié");
    let text = std::fs::read_to_string(&schema).expect("schéma lisible");
    let attendu = render_definition_builder(&text, version).expect("builder générable");
    let sur_disque = std::fs::read_to_string(root.join(BUILDER_PATH)).unwrap_or_else(|err| {
        panic!("{BUILDER_PATH} illisible ({err}) — le générer avec : {REGENERER}")
    });
    assert_eq!(
        sur_disque.replace("\r\n", "\n"),
        attendu.replace("\r\n", "\n"),
        "le builder Java a divergé du schéma — le régénérer avec : {REGENERER}"
    );
}

#[test]
fn un_pluriel_inconnu_fait_echouer_la_generation() {
    let schema = r#"{"properties": {"schema": {"type": "integer", "const": 3},
        "kind": {"type": "string", "enum": ["vehicle"]}, "asset": {"type": "string"},
        "gadgets": {"type": "array", "items": {"type": "object", "properties": {"a": {"type": "number"}}}}}}"#;
    let erreur =
        render_definition_builder(schema, 3).expect_err("gadgets n'a pas de singulier connu");
    assert!(erreur.contains("gadgets"), "{erreur}");
}

#[test]
fn une_version_qui_ne_correspond_pas_fait_echouer_la_generation() {
    let schema = r#"{"properties": {"schema": {"type": "integer", "const": 2},
        "kind": {"type": "string", "enum": ["vehicle"]}, "asset": {"type": "string"}}}"#;
    assert!(render_definition_builder(schema, 3).is_err());
}
