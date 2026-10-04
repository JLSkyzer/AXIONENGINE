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
fn les_constantes_de_tampon_sont_a_jour() {
    // Les valeurs numériques des kinds et la disposition de l'en-tête font
    // partie de l'ABI : si le fichier Java diverge du registre Rust, les deux
    // côtés de la frontière ne parlent plus du même format, sans qu'aucune
    // erreur de compilation ne le signale.
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .join(axion_codegen::JAVA_BUFFER_PATH);

    let sur_disque = std::fs::read_to_string(&path).unwrap_or_else(|err| {
        panic!(
            "{} illisible ({err}) — le générer avec : cargo run -p axion-codegen --bin gen_java_config",
            path.display()
        )
    });

    let attendu = axion_codegen::render_java_buffer_kinds().replace("\r\n", "\n");
    assert_eq!(
        sur_disque.replace("\r\n", "\n"),
        attendu,
        "les constantes de tampon ont divergé — les régénérer avec : cargo run -p axion-codegen --bin gen_java_config"
    );
}

#[test]
fn chaque_kind_figure_dans_les_constantes_java() {
    let java = axion_codegen::render_java_buffer_kinds();
    for kind in ax_model::buffer::BufferKind::ALL {
        assert!(
            java.contains(&format!("int {} = {};", kind.name(), kind.as_u32())),
            "{kind} absent des constantes Java, ou mal numéroté"
        );
    }
    // L'en-tête et le magic doivent traverser tels quels.
    assert!(java.contains(&format!(
        "HEADER_BYTES = {};",
        ax_model::buffer::HEADER_BYTES
    )));
    let magic = u32::from_le_bytes(ax_model::buffer::MAGIC);
    assert!(java.contains(&format!("MAGIC = 0x{magic:08X};")));
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

#[test]
fn les_codes_d_evenement_sont_a_jour() {
    // Les genres d'événement et le sens de `data` traversent la frontière (§10.7, ADR-123) :
    // un consommateur Java qui lirait un code périmé confondrait deux faits sans qu'aucune
    // erreur ne le signale.
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .join(axion_codegen::JAVA_EVENT_CODES_PATH);

    let sur_disque = std::fs::read_to_string(&path).unwrap_or_else(|err| {
        panic!(
            "{} illisible ({err}) — le générer avec : cargo run -p axion-codegen --bin gen_java_config",
            path.display()
        )
    });

    let attendu = axion_codegen::render_java_event_codes().replace("\r\n", "\n");
    assert_eq!(
        sur_disque.replace("\r\n", "\n"),
        attendu,
        "les codes d'événement ont divergé — les régénérer avec : cargo run -p axion-codegen --bin gen_java_config"
    );
}

#[test]
fn chaque_code_d_evenement_figure_dans_la_classe_java() {
    use ax_model::dm::physics::{event_data, event_kind};

    let java = axion_codegen::render_java_event_codes();
    for (name, value, _) in axion_codegen::EVENT_KINDS
        .iter()
        .chain(axion_codegen::EVENT_DATA.iter())
    {
        assert!(
            java.contains(&format!("int {name} = {value};")),
            "{name} absent des codes Java, ou mal numéroté"
        );
    }
    // Les tables prennent leurs valeurs dans le DM ; on épingle celles que Java consomme
    // déjà, pour qu'une table qui confondrait deux noms se voie.
    assert!(java.contains(&format!(
        "int CONTACT_IMPULSE = {};",
        event_kind::CONTACT_IMPULSE
    )));
    assert!(java.contains(&format!("int CLAMPED = {};", event_kind::CLAMPED)));
    assert!(java.contains(&format!("int RECOVERED = {};", event_kind::RECOVERED)));
    assert!(java.contains(&format!(
        "int CONTACT_OTHER_ENTITY = {};",
        event_data::CONTACT_OTHER_ENTITY
    )));
    assert!(java.contains(&format!(
        "int RECOVERED_INVALID_STATE = {};",
        event_data::RECOVERED_INVALID_STATE
    )));
}
