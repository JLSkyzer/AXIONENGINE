//! Génération du code Java d'AXION ENGINE depuis `ax-model`.
//!
//! Le schéma de configuration est déclaré une seule fois, en Rust. Java a
//! pourtant besoin de le connaître : c'est lui qui lit les fichiers TOML,
//! applique les surcharges `-Daxion.*` et valide avant d'encoder pour le natif.
//! Plutôt que de tenir deux listes qui divergeraient, la classe Java est
//! **générée** depuis le registre, et un test de parité (T-005) vérifie qu'elle
//! n'a pas été modifiée à la main.

use ax_model::config::{ConfigDomain, ConfigOption, ConfigScope, ConfigValue};

/// Chemin du fichier Java généré, relatif à la racine du dépôt.
pub const JAVA_SCHEMA_PATH: &str =
    "java/axion-mod/src/main/java/dev/axion/config/ConfigSchema.java";

/// Répertoire des fichiers de configuration de référence, embarqués dans le
/// JAR et recopiés dans le répertoire de configuration au premier démarrage.
pub const TOML_RESOURCE_DIR: &str = "java/axion-mod/src/main/resources/axion/config";

/// Chemin de la ressource TOML d'une portée, relatif à la racine du dépôt.
#[must_use]
pub fn toml_resource_path(scope: ConfigScope) -> String {
    format!("{TOML_RESOURCE_DIR}/{}", scope.file_name())
}

/// Rend la classe Java du schéma de configuration.
#[must_use]
pub fn render_java_schema() -> String {
    let mut out = String::new();

    out.push_str(
        "package dev.axion.config;\n\
         \n\
         import java.util.List;\n\
         \n\
         /**\n\
         \x20* Schéma de la configuration d'AXION ENGINE.\n\
         \x20*\n\
         \x20* <p><strong>Fichier généré — ne pas modifier à la main.</strong> Les options sont\n\
         \x20* déclarées une seule fois, dans {@code crates/ax-model/src/config/defaults.rs}.\n\
         \x20* Régénérer avec :\n\
         \x20*\n\
         \x20* <pre>cargo run -p axion-codegen --bin gen_java_config</pre>\n\
         \x20*\n\
         \x20* <p>Le test de parité T-005 échoue si ce fichier diverge du registre.\n\
         \x20*\n\
         \x20* <p>Exigences : R-430, R-431, R-1830.\n\
         \x20*/\n\
         public final class ConfigSchema {\n\
         \n\
         \x20   private ConfigSchema() {\n\
         \x20       throw new AssertionError(\"classe utilitaire, non instanciable\");\n\
         \x20   }\n\
         \n\
         \x20   /** Fichier de configuration auquel une option appartient. */\n\
         \x20   public enum Scope {\n\
         \x20       /** {@code axion-common.toml}. */\n\
         \x20       COMMON(\"axion-common.toml\"),\n\
         \x20       /** {@code axion-client.toml}. */\n\
         \x20       CLIENT(\"axion-client.toml\"),\n\
         \x20       /** {@code axion-server.toml}. */\n\
         \x20       SERVER(\"axion-server.toml\");\n\
         \n\
         \x20       private final String fileName;\n\
         \n\
         \x20       Scope(String fileName) {\n\
         \x20           this.fileName = fileName;\n\
         \x20       }\n\
         \n\
         \x20       /** {@return le nom du fichier correspondant} */\n\
         \x20       public String fileName() {\n\
         \x20           return fileName;\n\
         \x20       }\n\
         \n\
         \x20       /** {@return les options déclarées pour cette portée} */\n\
         \x20       public List<Option> options() {\n\
         \x20           return switch (this) {\n\
         \x20               case COMMON -> COMMON_OPTIONS;\n\
         \x20               case CLIENT -> CLIENT_OPTIONS;\n\
         \x20               case SERVER -> SERVER_OPTIONS;\n\
         \x20           };\n\
         \x20       }\n\
         \x20   }\n\
         \n\
         \x20   /** Type d'une valeur de configuration. */\n\
         \x20   public enum Kind {\n\
         \x20       /** Booléen. */\n\
         \x20       BOOLEAN,\n\
         \x20       /** Entier signé. */\n\
         \x20       INTEGER,\n\
         \x20       /** Flottant double précision. */\n\
         \x20       FLOAT,\n\
         \x20       /** Chaîne. */\n\
         \x20       STRING\n\
         \x20   }\n\
         \n\
         \x20   /** Nature du domaine de validité d'une option. */\n\
         \x20   public enum DomainKind {\n\
         \x20       /** Les deux valeurs booléennes. */\n\
         \x20       BOOLEAN,\n\
         \x20       /** Intervalle entier fermé. */\n\
         \x20       INT_RANGE,\n\
         \x20       /** Intervalle flottant fermé. */\n\
         \x20       FLOAT_RANGE,\n\
         \x20       /** Ensemble fermé de flottants. */\n\
         \x20       FLOAT_SET,\n\
         \x20       /** Ensemble fermé de chaînes. */\n\
         \x20       ENUMERATION,\n\
         \x20       /** Un mot-clé de la liste, ou un entier positif. */\n\
         \x20       KEYWORD_OR_COUNT,\n\
         \x20       /** Chaîne libre. */\n\
         \x20       FREE_TEXT\n\
         \x20   }\n\
         \n\
         \x20   /**\n\
         \x20    * Domaine de validité d'une option.\n\
         \x20    *\n\
         \x20    * @param kind nature du domaine\n\
         \x20    * @param min borne inférieure, pour les intervalles\n\
         \x20    * @param max borne supérieure, pour les intervalles\n\
         \x20    * @param allowed valeurs admises, pour les ensembles fermés\n\
         \x20    */\n\
         \x20   public record Domain(DomainKind kind, double min, double max, List<String> allowed) {}\n\
         \n\
         \x20   /**\n\
         \x20    * Une option de configuration.\n\
         \x20    *\n\
         \x20    * @param path chemin pointé, tel qu'il apparaît en TOML\n\
         \x20    * @param kind type de la valeur\n\
         \x20    * @param defaultValue valeur par défaut\n\
         \x20    * @param domain domaine de validité\n\
         \x20    * @param hot rechargeable par {@code /axion config reload} (R-431)\n\
         \x20    * @param serverAuthoritative imposée par le serveur au handshake (R-1830)\n\
         \x20    * @param description description reprise dans la documentation générée\n\
         \x20    */\n\
         \x20   public record Option(\n\
         \x20           String path,\n\
         \x20           Kind kind,\n\
         \x20           Object defaultValue,\n\
         \x20           Domain domain,\n\
         \x20           boolean hot,\n\
         \x20           boolean serverAuthoritative,\n\
         \x20           String description) {\n\
         \n\
         \x20       /** {@return la propriété système qui surcharge cette option} */\n\
         \x20       public String systemProperty() {\n\
         \x20           return \"axion.\" + path;\n\
         \x20       }\n\
         \n\
         \x20       /** {@return la section TOML de l'option} */\n\
         \x20       public String section() {\n\
         \x20           return path.substring(0, path.lastIndexOf('.'));\n\
         \x20       }\n\
         \n\
         \x20       /** {@return le nom de l'option dans sa section} */\n\
         \x20       public String key() {\n\
         \x20           return path.substring(path.lastIndexOf('.') + 1);\n\
         \x20       }\n\
         \x20   }\n",
    );

    for (name, scope) in [
        ("COMMON_OPTIONS", ConfigScope::Common),
        ("CLIENT_OPTIONS", ConfigScope::Client),
        ("SERVER_OPTIONS", ConfigScope::Server),
    ] {
        out.push_str(&format!(
            "\n    /** Options de {{@code {}}}. */\n    public static final List<Option> {name} = List.of(\n",
            scope.file_name()
        ));
        let rendered: Vec<String> = scope.options().iter().map(render_option).collect();
        out.push_str(&rendered.join(",\n"));
        out.push_str(");\n");
    }

    out.push_str("}\n");
    out
}

fn render_option(option: &ConfigOption) -> String {
    format!(
        "            new Option(\n\
         \x20                   \"{}\",\n\
         \x20                   Kind.{},\n\
         \x20                   {},\n\
         \x20                   {},\n\
         \x20                   {},\n\
         \x20                   {},\n\
         \x20                   \"{}\")",
        option.path,
        java_kind(option.default),
        java_default(option.default),
        java_domain(option.domain),
        option.hot,
        option.server_authoritative,
        escape_java(option.description),
    )
}

fn java_kind(value: ConfigValue) -> &'static str {
    match value {
        ConfigValue::Bool(_) => "BOOLEAN",
        ConfigValue::Int(_) => "INTEGER",
        ConfigValue::Float(_) => "FLOAT",
        ConfigValue::Str(_) => "STRING",
    }
}

fn java_default(value: ConfigValue) -> String {
    match value {
        ConfigValue::Bool(v) => v.to_string(),
        // Suffixe `L` : les budgets en nanosecondes et les tailles en octets
        // dépassent la capacité d'un `int`.
        ConfigValue::Int(v) => format!("{v}L"),
        ConfigValue::Float(v) => format!("{v:?}"),
        ConfigValue::Str(v) => format!("\"{}\"", escape_java(v)),
    }
}

fn java_domain(domain: ConfigDomain) -> String {
    let list = |values: &[&str]| {
        if values.is_empty() {
            "List.of()".to_owned()
        } else {
            let items: Vec<String> = values
                .iter()
                .map(|v| format!("\"{}\"", escape_java(v)))
                .collect();
            format!("List.of({})", items.join(", "))
        }
    };

    match domain {
        ConfigDomain::Boolean => "new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of())".to_owned(),
        ConfigDomain::IntRange { min, max } => format!(
            "new Domain(DomainKind.INT_RANGE, {min}.0, {}, List.of())",
            // `i64::MAX` ne se représente pas exactement en `double` ; la borne
            // dit seulement qu'aucun maximum n'est spécifié.
            if max == i64::MAX {
                "Double.MAX_VALUE".to_owned()
            } else {
                format!("{max}.0")
            }
        ),
        ConfigDomain::FloatRange { min, max } => format!(
            "new Domain(DomainKind.FLOAT_RANGE, {min:?}, {}, List.of())",
            if max == f64::MAX {
                "Double.MAX_VALUE".to_owned()
            } else {
                format!("{max:?}")
            }
        ),
        ConfigDomain::FloatSet(values) => {
            let items: Vec<String> = values.iter().map(|v| format!("\"{v:?}\"")).collect();
            format!(
                "new Domain(DomainKind.FLOAT_SET, 0.0, 0.0, List.of({}))",
                items.join(", ")
            )
        }
        ConfigDomain::Enumeration(values) => format!(
            "new Domain(DomainKind.ENUMERATION, 0.0, 0.0, {})",
            list(values)
        ),
        ConfigDomain::KeywordOrCount(values) => format!(
            "new Domain(DomainKind.KEYWORD_OR_COUNT, 0.0, 0.0, {})",
            list(values)
        ),
        ConfigDomain::FreeText => {
            "new Domain(DomainKind.FREE_TEXT, 0.0, 0.0, List.of())".to_owned()
        }
    }
}

/// Échappe ce qui ne peut pas figurer tel quel dans un littéral Java.
fn escape_java(text: &str) -> String {
    text.replace('\\', "\\\\").replace('"', "\\\"")
}
