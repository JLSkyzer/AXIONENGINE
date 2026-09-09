//! T-005 — parité entre le registre Rust et la classe Java générée.
//!
//! Le schéma de configuration existe sous deux formes : le registre de
//! `ax-model`, qui fait autorité, et la classe Java que le mod utilise pour
//! lire les fichiers et valider les valeurs. La seconde est dérivée de la
//! première. Ce test échoue dès qu'elles divergent — parce qu'une option a été
//! ajoutée sans régénérer, ou parce que le fichier généré a été modifié à la
//! main.
//!
//! C'est le pendant local du job `codegen-parity` de la CI, dont R-2350 fait un
//! bloquant de fusion.

use std::path::PathBuf;

#[test]
fn la_classe_java_est_a_jour() {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .join(axion_codegen::JAVA_SCHEMA_PATH);

    let sur_disque = std::fs::read_to_string(&path).unwrap_or_else(|err| {
        panic!(
            "{} illisible ({err}) — le générer avec :\n  \
             cargo run -p axion-codegen --bin gen_java_config",
            path.display()
        )
    });

    // Les fins de ligne dépendent de la configuration git du poste ; seul le
    // contenu compte.
    let attendu = axion_codegen::render_java_schema().replace("\r\n", "\n");
    assert_eq!(
        sur_disque.replace("\r\n", "\n"),
        attendu,
        "la classe Java a divergé du registre — la régénérer avec :\n  \
         cargo run -p axion-codegen --bin gen_java_config"
    );
}

#[test]
fn toute_option_du_registre_figure_dans_la_classe() {
    // Une vérification indépendante du rendu : même si la mise en forme
    // changeait, chaque chemin d'option doit rester présent. Ce test attrape
    // une génération partielle que la comparaison littérale, elle, verrait
    // comme un simple écart de contenu.
    let java = axion_codegen::render_java_schema();
    for scope in ax_model::config::ConfigScope::ALL {
        for option in scope.options() {
            assert!(
                java.contains(&format!("\"{}\"", option.path)),
                "{} absent de la classe Java générée",
                option.path
            );
        }
    }
}
