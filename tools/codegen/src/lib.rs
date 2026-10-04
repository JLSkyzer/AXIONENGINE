//! Génération du code Java d'AXION ENGINE depuis `ax-model`.
//!
//! Le schéma de configuration est déclaré une seule fois, en Rust. Java a
//! pourtant besoin de le connaître : c'est lui qui lit les fichiers TOML,
//! applique les surcharges `-Daxion.*` et valide avant d'encoder pour le natif.
//! Plutôt que de tenir deux listes qui divergeraient, la classe Java est
//! **générée** depuis le registre, et un test de parité (T-005) vérifie qu'elle
//! n'a pas été modifiée à la main.

use ax_model::buffer::{BufferKind, HEADER_BYTES, MAGIC};
use ax_model::config::{ConfigDomain, ConfigOption, ConfigScope, ConfigValue};
use ax_model::dm::physics::{event_data, event_kind};

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

/// Chemin du fichier Java des constantes de tampon, relatif à la racine.
pub const JAVA_BUFFER_PATH: &str = "java/axion-mod/src/main/java/dev/axion/bridge/BufferKinds.java";

/// Rend la classe Java des constantes de tampon (IF-02).
///
/// Les valeurs numériques des kinds et la disposition de l'en-tête font partie
/// de l'ABI : les tenir à la main des deux côtés serait la garantie qu'elles
/// divergent un jour.
///
/// Le rendu assemble des lignes plutôt qu'un littéral à continuations : ces
/// dernières recopient l'indentation du source Rust dans le fichier produit.
#[must_use]
pub fn render_java_buffer_kinds() -> String {
    // « AXNB » relu comme un entier little-endian, tel que Java le lira dans un
    // ByteBuffer configuré selon R-271.
    let magic_le = u32::from_le_bytes(MAGIC);

    let mut lines: Vec<String> = vec![
        "package dev.axion.bridge;".to_owned(),
        String::new(),
        "/**".to_owned(),
        " * Constantes des tampons de transfert (IF-02).".to_owned(),
        " *".to_owned(),
        " * <p><strong>Fichier généré — ne pas modifier à la main.</strong> La source est"
            .to_owned(),
        " * {@code crates/ax-model/src/buffer.rs}. Régénérer avec :".to_owned(),
        " *".to_owned(),
        " * <pre>cargo run -p axion-codegen --bin gen_java_config</pre>".to_owned(),
        " *".to_owned(),
        " * <p>Les valeurs numériques font partie de l'ABI : les changer romprait la".to_owned(),
        " * compatibilité avec un binaire déjà distribué.".to_owned(),
        " */".to_owned(),
        "public final class BufferKinds {".to_owned(),
        String::new(),
        "    private BufferKinds() {".to_owned(),
        "        throw new AssertionError(\"classe de constantes, non instanciable\");".to_owned(),
        "    }".to_owned(),
        String::new(),
        "    /** Taille de l'en-tête de tout tampon, en octets. */".to_owned(),
        format!("    public static final int HEADER_BYTES = {HEADER_BYTES};"),
        String::new(),
        "    /**".to_owned(),
        "     * Magic ouvrant tout tampon, lu comme un entier little-endian.".to_owned(),
        "     *".to_owned(),
        "     * <p>Correspond aux quatre octets {@code 'A' 'X' 'N' 'B'}. Un".to_owned(),
        "     * {@link java.nio.ByteBuffer} doit être en little-endian (R-271) pour que".to_owned(),
        "     * cette comparaison ait un sens.".to_owned(),
        "     */".to_owned(),
        format!("    public static final int MAGIC = 0x{magic_le:08X};"),
    ];

    for kind in BufferKind::ALL {
        lines.push(String::new());
        lines.push(format!("    /** {}. */", describe_kind(kind)));
        lines.push(format!(
            "    public static final int {} = {};",
            kind.name(),
            kind.as_u32()
        ));
    }

    // Versions de schéma des charges utiles (R-262), pour les seuls kinds qui en
    // déclarent une : le natif l'écrit dans l'en-tête, Java la compare avant de
    // lire ce qu'elle annonce.
    for kind in BufferKind::ALL {
        let version = kind.schema_version();
        if version == 0 {
            continue;
        }
        lines.push(String::new());
        lines.push(format!(
            "    /** Version du schéma de la charge de {{@code {}}}, lue à l'octet 12 de l'en-tête. */",
            kind.name()
        ));
        lines.push(format!(
            "    public static final int {}_SCHEMA = {version};",
            kind.name()
        ));
    }

    lines.push("}".to_owned());
    lines.join(
        "
",
    ) + "
"
}

fn describe_kind(kind: BufferKind) -> &'static str {
    match kind {
        BufferKind::SimIn => "Commandes de simulation, Java vers le natif",
        BufferKind::SimOut => "États de bodies, natif vers Java",
        BufferKind::Events => "Événements physiques, de dommage, de rupture et de détachement",
        BufferKind::ImpactIn => "Impacts d'origine Minecraft",
        BufferKind::DeformOut => "Pages de champ de déformation modifiées",
        BufferKind::DeformNet => "Paquets de déformation sérialisés",
        BufferKind::RenderOut => "Instances visibles, matrices, palettes, décalques, LOD",
        BufferKind::ShadowOut => "Instances de la passe d'ombre",
        BufferKind::AssetIn => "Source d'un asset à compiler",
        BufferKind::AssetOut => "Asset compilé",
        BufferKind::NetOut => "Charges utiles réseau sérialisées",
        BufferKind::Persist => "Blobs de persistance sérialisés",
        BufferKind::Debug => "Géométrie de debug",
    }
}

/// Chemin du fichier Java des codes d'événements physiques, relatif à la racine.
pub const JAVA_EVENT_CODES_PATH: &str =
    "java/axion-mod/src/main/java/dev/axion/physics/PhysicsEventCodes.java";

/// Genres d'un `PhysicsEvent` (§10.7, ADR-113) : nom, valeur gelée, description.
///
/// La valeur vient de `ax-model` : elle ne peut pas diverger. Un genre ajouté au DM sans
/// figurer ici manque seulement à Java, qui ignore un genre inconnu (ADR-113).
pub const EVENT_KINDS: [(&str, u32, &str); 13] = [
    (
        "CONTACT_START",
        event_kind::CONTACT_START,
        "Début d'un contact.",
    ),
    ("CONTACT_END", event_kind::CONTACT_END, "Fin d'un contact."),
    (
        "CONTACT_IMPULSE",
        event_kind::CONTACT_IMPULSE,
        "Impulsion d'un contact persistant.",
    ),
    (
        "SENSOR_ENTER",
        event_kind::SENSOR_ENTER,
        "Entrée dans un capteur.",
    ),
    (
        "SENSOR_EXIT",
        event_kind::SENSOR_EXIT,
        "Sortie d'un capteur.",
    ),
    (
        "JOINT_BROKEN",
        event_kind::JOINT_BROKEN,
        "Rupture d'une liaison.",
    ),
    (
        "JOINT_JAMMED",
        event_kind::JOINT_JAMMED,
        "Blocage d'une liaison.",
    ),
    ("SLEEP", event_kind::SLEEP, "Endormissement d'un corps."),
    ("WAKE", event_kind::WAKE, "Réveil d'un corps."),
    (
        "CLAMPED",
        event_kind::CLAMPED,
        "Grandeur clampée (budget dépassé).",
    ),
    (
        "RECOVERED",
        event_kind::RECOVERED,
        "Retour à un état valide.",
    ),
    ("ATTACH", event_kind::ATTACH, "Attache d'un élément."),
    (
        "DETACH_ATTACHMENT",
        event_kind::DETACH_ATTACHMENT,
        "Détachement d'un attachement.",
    ),
];

/// Sens du champ `data` d'un `PhysicsEvent` selon son genre (ADR-123) : nom, valeur,
/// description.
pub const EVENT_DATA: [(&str, u32, &str); 5] = [
    (
        "CONTACT_OTHER_ENTITY",
        event_data::CONTACT_OTHER_ENTITY,
        "Contact : l'autre corps est le proxy d'une entité vanilla (ADR-123 §6).",
    ),
    (
        "RECOVERED_INVALID_STATE",
        event_data::RECOVERED_INVALID_STATE,
        "{@code RECOVERED} : restauré d'un état non fini ou hors du monde (E-2030).",
    ),
    (
        "CLAMPED_VELOCITY",
        event_data::CLAMPED_VELOCITY,
        "{@code CLAMPED} : vitesse bornée (R-180), au plus une fois par minute.",
    ),
    (
        "CLAMPED_BUDGET",
        event_data::CLAMPED_BUDGET,
        "{@code CLAMPED} : corps endormi par la dégradation de budget (FM-21).",
    ),
    (
        "CLAMPED_STACKING",
        event_data::CLAMPED_STACKING,
        "{@code CLAMPED} : empilement agité sur place, amorti puis endormi (FM-22).",
    ),
];

/// Rend la classe Java des codes d'événements physiques (§10.7, ADR-113, ADR-123).
///
/// Les deux modules du DM, `event_kind` et `event_data`, deviennent deux classes imbriquées
/// aux mêmes noms de constantes : `Kind` et `Data`.
#[must_use]
pub fn render_java_event_codes() -> String {
    let mut lines: Vec<String> = vec![
        "package dev.axion.physics;".to_owned(),
        String::new(),
        "/**".to_owned(),
        " * Codes des événements physiques (§10.7) : genres et sens du champ {@code data}."
            .to_owned(),
        " *".to_owned(),
        " * <p><strong>Fichier généré — ne pas modifier à la main.</strong> La source est"
            .to_owned(),
        " * {@code crates/ax-model/src/dm/physics.rs} ({@code event_kind}, {@code event_data}). \
         Régénérer"
            .to_owned(),
        " * avec :".to_owned(),
        " *".to_owned(),
        " * <pre>cargo run -p axion-codegen --bin gen_java_config</pre>".to_owned(),
        " *".to_owned(),
        " * <p>Les valeurs sont gelées avec la structure de l'événement (ADR-113) et le sens de"
            .to_owned(),
        " * {@code data} ratifié avec ADR-123. Un lecteur ignore un genre ou un code inconnu."
            .to_owned(),
        " */".to_owned(),
        "public final class PhysicsEventCodes {".to_owned(),
        String::new(),
        "    private PhysicsEventCodes() {".to_owned(),
        "        throw new AssertionError(\"classe de constantes, non instanciable\");".to_owned(),
        "    }".to_owned(),
    ];
    render_java_constants(
        &mut lines,
        "Kind",
        "Genres d'événement ({@code PhysicsEvent.kind}).",
        &EVENT_KINDS,
    );
    render_java_constants(
        &mut lines,
        "Data",
        "Sens du champ {@code PhysicsEvent.data}, selon le genre.",
        &EVENT_DATA,
    );
    lines.push("}".to_owned());
    lines.join("\n") + "\n"
}

/// Ajoute une classe imbriquée de constantes `int` au rendu.
fn render_java_constants(
    lines: &mut Vec<String>,
    class: &str,
    summary: &str,
    constants: &[(&str, u32, &str)],
) {
    lines.push(String::new());
    lines.push(format!("    /** {summary} */"));
    lines.push(format!("    public static final class {class} {{"));
    lines.push(String::new());
    lines.push(format!("        private {class}() {{"));
    lines.push(
        "            throw new AssertionError(\"classe de constantes, non instanciable\");"
            .to_owned(),
    );
    lines.push("        }".to_owned());
    for (name, value, description) in constants {
        lines.push(String::new());
        lines.push(format!("        /** {description} */"));
        lines.push(format!("        public static final int {name} = {value};"));
    }
    lines.push("    }".to_owned());
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
