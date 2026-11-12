//! C-04 — source unique de la configuration d'AXION ENGINE.
//!
//! Toute option d'AXION est décrite ici une fois, et une seule : son chemin,
//! son type, son défaut, son domaine de validité et sa description. R-430
//! l'exige — chaque option a un défaut, une plage et une description, et
//! `CONFIGURATION.md` est **généré** depuis cette source plutôt qu'écrit à la
//! main. R-2400 étend la règle à l'ANNEXE A.3 du cahier des charges : une
//! divergence entre le code, la documentation et l'annexe casse le build.
//!
//! La chaîne complète de C-04 est :
//!
//! ```text
//! défauts compilés (ce module)
//!   -> <configDir>/axion-common.toml
//!   -> <configDir>/axion-client.toml | axion-server.toml
//!   -> propriétés -Daxion.<chemin>=<valeur>
//!   -> validation (type, plage, défaut) -> CBOR -> axion_init
//! ```
//!
//! Ce module porte les deux premiers maillons — les défauts et la validation.
//! La lecture des fichiers et les surcharges `-D` sont côté Java ; l'encodage
//! CBOR rejoint la frontière FFI (M0.4).
//!
//! Exigences : R-430, R-431, R-1830, R-2400.

mod defaults;

pub use defaults::{CLIENT, COMMON, SERVER};

/// Chemin de l'option qui fixe la part de parallélisme d'AXION (PARTIE 27.4).
///
/// Cette constante existe pour que les composants puissent lire l'option sans
/// la nommer : INV-06 veut qu'aucun système d'AXION ne référence le mod
/// d'optimisation tiers hors de C-76, et le registre de configuration est l'un
/// des rares endroits où son nom est admis — l'ANNEXE A.3 la déclare.
pub const CPU_SHARE_PATH: &str = "integration.rustforgex.cpu_share";

/// Chemin de l'option qui plafonne le nombre de workers (R-471).
pub const MAX_WORKERS_PATH: &str = "jobs.max_workers";

use core::fmt;

/// Fichier de configuration auquel une option appartient (24.4).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ConfigScope {
    /// `axion-common.toml` : simulation, budgets, limites, dommage, déformation.
    Common,
    /// `axion-client.toml` : rendu, qualité, debug, overlay.
    Client,
    /// `axion-server.toml` : réseau, limites globales, autorisations.
    Server,
}

impl ConfigScope {
    /// Nom du fichier correspondant.
    #[must_use]
    pub const fn file_name(self) -> &'static str {
        match self {
            ConfigScope::Common => "axion-common.toml",
            ConfigScope::Client => "axion-client.toml",
            ConfigScope::Server => "axion-server.toml",
        }
    }

    /// Options déclarées pour cette portée.
    #[must_use]
    pub const fn options(self) -> &'static [ConfigOption] {
        match self {
            ConfigScope::Common => COMMON,
            ConfigScope::Client => CLIENT,
            ConfigScope::Server => SERVER,
        }
    }

    /// Les trois portées.
    pub const ALL: [ConfigScope; 3] = [
        ConfigScope::Common,
        ConfigScope::Client,
        ConfigScope::Server,
    ];
}

/// Valeur d'une option de configuration.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum ConfigValue {
    /// Booléen.
    Bool(bool),
    /// Entier signé.
    Int(i64),
    /// Flottant double précision.
    Float(f64),
    /// Chaîne appartenant à un ensemble fermé de valeurs admises.
    Str(&'static str),
}

impl ConfigValue {
    /// Nom du type, tel qu'il apparaît dans la documentation générée.
    #[must_use]
    pub const fn type_name(self) -> &'static str {
        match self {
            ConfigValue::Bool(_) => "booléen",
            ConfigValue::Int(_) => "entier",
            ConfigValue::Float(_) => "flottant",
            ConfigValue::Str(_) => "chaîne",
        }
    }

    /// Rendu TOML de la valeur.
    #[must_use]
    pub fn to_toml(self) -> String {
        match self {
            ConfigValue::Bool(v) => v.to_string(),
            ConfigValue::Int(v) => v.to_string(),
            ConfigValue::Float(v) => format_float(v),
            ConfigValue::Str(v) => format!("\"{v}\""),
        }
    }
}

/// Rend un flottant sans notation scientifique et sans zéros inutiles.
///
/// Les fichiers de configuration sont relus par des humains : `5000000` se
/// compare à l'œil, `5e6` beaucoup moins.
fn format_float(v: f64) -> String {
    if v == v.trunc() && v.abs() < 1e15 {
        format!("{v:.1}")
    } else {
        let mut s = format!("{v:.7}");
        while s.ends_with('0') && !s.ends_with(".0") {
            s.pop();
        }
        s
    }
}

/// Domaine de validité d'une option.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum ConfigDomain {
    /// Un booléen : les deux valeurs sont admises.
    Boolean,
    /// Intervalle entier fermé.
    IntRange {
        /// Borne inférieure admise.
        min: i64,
        /// Borne supérieure admise.
        max: i64,
    },
    /// Intervalle flottant fermé.
    FloatRange {
        /// Borne inférieure admise.
        min: f64,
        /// Borne supérieure admise.
        max: f64,
    },
    /// Ensemble fermé de flottants admis.
    ///
    /// Certaines options n'acceptent pas un intervalle mais quelques valeurs
    /// précises : un pas de temps de simulation intermédiaire, par exemple,
    /// désynchroniserait la simulation des ticks du serveur.
    FloatSet(&'static [f64]),
    /// Ensemble fermé de chaînes admises.
    Enumeration(&'static [&'static str]),
    /// Un des mots-clés donnés, ou la représentation décimale d'un entier
    /// positif.
    ///
    /// Forme de `integration.rustforgex.cpu_share`, qui accepte `auto`, `half`,
    /// `full` ou un nombre de cœurs.
    KeywordOrCount(&'static [&'static str]),
    /// Chaîne libre, dont la validité ne peut pas être décidée ici.
    ///
    /// Le nom d'une touche, par exemple, dépend du clavier et de la table de
    /// correspondance de Minecraft : le valider ici reviendrait à dupliquer
    /// une liste qui ne nous appartient pas.
    FreeText,
}

impl fmt::Display for ConfigDomain {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ConfigDomain::Boolean => f.write_str("true | false"),
            // Une borne égale au maximum du type n'est pas une limite métier :
            // elle dit seulement qu'aucune borne supérieure n'est spécifiée.
            ConfigDomain::IntRange { min, max } if *max == i64::MAX => write!(f, "{min}.."),
            ConfigDomain::IntRange { min, max } => write!(f, "{min}..{max}"),
            ConfigDomain::FloatRange { min, max } if *max == f64::MAX => {
                write!(f, "{}..", format_float(*min))
            }
            ConfigDomain::FloatRange { min, max } => {
                write!(f, "{}..{}", format_float(*min), format_float(*max))
            }
            ConfigDomain::FloatSet(values) => {
                let rendered: Vec<String> = values.iter().map(|v| format_float(*v)).collect();
                f.write_str(&rendered.join(" | "))
            }
            ConfigDomain::Enumeration(values) => f.write_str(&values.join(" | ")),
            ConfigDomain::KeywordOrCount(values) => {
                write!(f, "{} | <entier>", values.join(" | "))
            }
            ConfigDomain::FreeText => f.write_str("texte libre"),
        }
    }
}

impl ConfigDomain {
    /// Indique si le domaine accepte un booléen.
    #[must_use]
    pub const fn accepts_bool(&self) -> bool {
        matches!(self, ConfigDomain::Boolean)
    }

    /// Indique si le domaine accepte cet entier.
    #[must_use]
    pub const fn accepts_int(&self, value: i64) -> bool {
        match self {
            ConfigDomain::IntRange { min, max } => *min <= value && value <= *max,
            _ => false,
        }
    }

    /// Indique si le domaine accepte ce flottant.
    ///
    /// NaN et les infinis sont refusés d'emblée : ils n'appartiennent à aucun
    /// intervalle, et une comparaison naïve laisserait passer NaN, faux pour
    /// tout opérateur.
    #[must_use]
    pub fn accepts_float(&self, value: f64) -> bool {
        if !value.is_finite() {
            return false;
        }
        match self {
            ConfigDomain::FloatRange { min, max } => *min <= value && value <= *max,
            ConfigDomain::FloatSet(allowed) => allowed.contains(&value),
            _ => false,
        }
    }

    /// Indique si le domaine accepte cette chaîne.
    #[must_use]
    pub fn accepts_str(&self, value: &str) -> bool {
        match self {
            ConfigDomain::Enumeration(allowed) => allowed.contains(&value),
            ConfigDomain::KeywordOrCount(allowed) => {
                allowed.contains(&value)
                    || (!value.is_empty() && value.bytes().all(|b| b.is_ascii_digit()))
            }
            ConfigDomain::FreeText => true,
            _ => false,
        }
    }
}

/// Valeur venue de l'extérieur, dont les chaînes ne sont pas statiques.
///
/// Une configuration décodée depuis un fichier ou depuis le CBOR reçu de Java
/// porte des chaînes possédées ; [`ConfigValue`] ne convient qu'aux défauts
/// compilés.
#[derive(Debug, Clone, PartialEq)]
pub enum ParsedValue {
    /// Booléen.
    Bool(bool),
    /// Entier signé.
    Int(i64),
    /// Flottant double précision.
    Float(f64),
    /// Chaîne possédée.
    Str(String),
}

impl ParsedValue {
    /// Nom du type, pour les messages de diagnostic.
    #[must_use]
    pub const fn type_name(&self) -> &'static str {
        match self {
            ParsedValue::Bool(_) => "booléen",
            ParsedValue::Int(_) => "entier",
            ParsedValue::Float(_) => "flottant",
            ParsedValue::Str(_) => "chaîne",
        }
    }
}

/// Une option de configuration, décrite une fois pour toutes.
#[derive(Debug, Clone, Copy)]
pub struct ConfigOption {
    /// Chemin pointé, tel qu'il apparaît en TOML et dans `-Daxion.<chemin>`.
    pub path: &'static str,
    /// Valeur par défaut.
    pub default: ConfigValue,
    /// Domaine de validité.
    pub domain: ConfigDomain,
    /// Rechargeable à chaud par `/axion config reload` (R-431).
    pub hot: bool,
    /// Imposée par le serveur au handshake ; la valeur locale du client est
    /// alors ignorée et un message le signale (R-1830).
    pub server_authoritative: bool,
    /// Description, reprise telle quelle dans la documentation générée.
    pub description: &'static str,
    /// La même description en anglais, pour la page anglaise de la
    /// documentation générée (`CONFIGURATION.en.md`).
    pub description_en: &'static str,
}

impl ConfigOption {
    /// Donne à l'option sa description anglaise, écrite sous la française
    /// dans le registre pour que l'une ne change pas sans l'autre.
    #[must_use]
    pub(crate) const fn en(mut self, description: &'static str) -> Self {
        self.description_en = description;
        self
    }

    /// Section TOML de l'option, c'est-à-dire son chemin sans le dernier
    /// segment.
    #[must_use]
    pub fn section(&self) -> &'static str {
        match self.path.rfind('.') {
            Some(index) => &self.path[..index],
            None => "",
        }
    }

    /// Nom de l'option à l'intérieur de sa section.
    #[must_use]
    pub fn key(&self) -> &'static str {
        match self.path.rfind('.') {
            Some(index) => &self.path[index + 1..],
            None => self.path,
        }
    }

    /// Propriété système qui surcharge cette option.
    #[must_use]
    pub fn system_property(&self) -> String {
        format!("axion.{}", self.path)
    }

    /// Valide une valeur contre le type et le domaine de l'option.
    ///
    /// # Erreurs
    ///
    /// [`ConfigError::TypeMismatch`] si le type ne correspond pas à celui du
    /// défaut, [`ConfigError::OutOfRange`] si la valeur sort du domaine.
    pub fn validate(&self, value: ConfigValue) -> Result<(), ConfigError> {
        let parsed = match value {
            ConfigValue::Bool(v) => ParsedValue::Bool(v),
            ConfigValue::Int(v) => ParsedValue::Int(v),
            ConfigValue::Float(v) => ParsedValue::Float(v),
            ConfigValue::Str(v) => ParsedValue::Str(v.to_owned()),
        };
        self.validate_parsed(&parsed)
    }

    /// Valide une valeur décodée depuis une source externe.
    ///
    /// # Erreurs
    ///
    /// [`ConfigError::TypeMismatch`] si le type ne correspond pas à celui de
    /// l'option, [`ConfigError::OutOfRange`] si la valeur sort du domaine.
    pub fn validate_parsed(&self, value: &ParsedValue) -> Result<(), ConfigError> {
        let type_matches = matches!(
            (&self.default, value),
            (ConfigValue::Bool(_), ParsedValue::Bool(_))
                | (ConfigValue::Int(_), ParsedValue::Int(_))
                | (ConfigValue::Float(_), ParsedValue::Float(_))
                | (ConfigValue::Str(_), ParsedValue::Str(_))
        );
        if !type_matches {
            return Err(ConfigError::TypeMismatch {
                path: self.path,
                expected: self.default.type_name(),
                found: value.type_name(),
            });
        }

        let accepted = match value {
            ParsedValue::Bool(_) => self.domain.accepts_bool(),
            ParsedValue::Int(v) => self.domain.accepts_int(*v),
            ParsedValue::Float(v) => self.domain.accepts_float(*v),
            ParsedValue::Str(v) => self.domain.accepts_str(v),
        };
        if accepted {
            Ok(())
        } else {
            Err(ConfigError::OutOfRange {
                path: self.path,
                domain: self.domain,
            })
        }
    }
}

/// Erreur de configuration.
///
/// Le type n'est pas `Copy` : un chemin inconnu est repris tel que
/// l'utilisateur l'a écrit, pour que le message le lui montre.
#[derive(Debug, Clone, PartialEq)]
pub enum ConfigError {
    /// Aucune option ne porte ce chemin.
    ///
    /// Une option inconnue n'est jamais ignorée en silence : c'est le plus
    /// souvent une faute de frappe dans un fichier ou dans une propriété
    /// `-Daxion.*`, et l'ignorer laisserait l'utilisateur croire que son
    /// réglage s'applique.
    UnknownOption {
        /// Chemin refusé.
        path: String,
    },
    /// La valeur n'a pas le type de l'option.
    TypeMismatch {
        /// Chemin de l'option.
        path: &'static str,
        /// Type attendu.
        expected: &'static str,
        /// Type reçu.
        found: &'static str,
    },
    /// La valeur est hors du domaine admis.
    OutOfRange {
        /// Chemin de l'option.
        path: &'static str,
        /// Domaine admis.
        domain: ConfigDomain,
    },
}

impl fmt::Display for ConfigError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ConfigError::UnknownOption { path } => {
                write!(f, "option de configuration inconnue : {path}")
            }
            ConfigError::TypeMismatch {
                path,
                expected,
                found,
            } => write!(f, "{path} attend un {expected}, reçu un {found}"),
            ConfigError::OutOfRange { path, domain } => {
                write!(f, "{path} hors du domaine admis ({domain})")
            }
        }
    }
}

impl core::error::Error for ConfigError {}

/// Toutes les options déclarées, portée par portée.
pub fn all_options() -> impl Iterator<Item = (ConfigScope, &'static ConfigOption)> {
    ConfigScope::ALL
        .into_iter()
        .flat_map(|scope| scope.options().iter().map(move |option| (scope, option)))
}

/// Retrouve une option par son chemin, dans une portée donnée.
#[must_use]
pub fn find(scope: ConfigScope, path: &str) -> Option<&'static ConfigOption> {
    scope.options().iter().find(|option| option.path == path)
}

/// Valide une valeur pour le chemin donné.
///
/// # Erreurs
///
/// [`ConfigError::UnknownOption`] si le chemin n'est pas déclaré, sinon
/// l'erreur de [`ConfigOption::validate`].
pub fn validate(scope: ConfigScope, path: &str, value: ConfigValue) -> Result<(), ConfigError> {
    find(scope, path)
        .ok_or_else(|| ConfigError::UnknownOption {
            path: path.to_owned(),
        })?
        .validate(value)
}

/// Rend le fichier TOML par défaut d'une portée.
///
/// C'est ce rendu qui produit le fichier créé au premier démarrage, et c'est
/// lui que l'ANNEXE A.3 doit refléter (R-2400).
#[must_use]
pub fn render_toml(scope: ConfigScope) -> String {
    let mut out = format!("# {}\n", scope.file_name());
    let mut current_section = "";

    for option in scope.options() {
        if option.section() != current_section {
            current_section = option.section();
            out.push_str(&format!("\n[{current_section}]\n"));
        }
        let line = format!("{} = {}", option.key(), option.default.to_toml());
        // Le domaine est rappelé en commentaire, sauf pour les booléens dont il
        // n'apprend rien.
        if matches!(option.domain, ConfigDomain::Boolean) {
            out.push_str(&format!("{line}\n"));
        } else {
            out.push_str(&format!("{line:<34}# {}\n", option.domain));
        }
    }
    out
}

/// Échappe les barres verticales, qui délimitent les cellules d'un tableau
/// Markdown.
fn escape_pipes(text: &str) -> String {
    text.replace('|', "\\|")
}

/// Langue d'une page de la documentation générée.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DocLanguage {
    /// `CONFIGURATION.md`, la page de référence.
    French,
    /// `CONFIGURATION.en.md`, sa traduction.
    English,
}

impl DocLanguage {
    /// Toutes les langues, dans l'ordre où le générateur les écrit.
    pub const ALL: [DocLanguage; 2] = [DocLanguage::French, DocLanguage::English];

    /// Nom du fichier généré dans cette langue, sous `docs/guides/` : le
    /// suffixe est celui qu'attend le site de la documentation (ADR-101).
    #[must_use]
    pub const fn file_name(self) -> &'static str {
        match self {
            DocLanguage::French => "CONFIGURATION.md",
            DocLanguage::English => "CONFIGURATION.en.md",
        }
    }
}

/// Les textes fixes d'une page de la documentation générée.
struct Wording {
    intro: &'static str,
    header: &'static str,
    yes: &'static str,
    no: &'static str,
    server_overrides: &'static str,
}

impl Wording {
    const fn of(language: DocLanguage) -> Self {
        match language {
            DocLanguage::French => Wording {
                intro: "# Configuration\n\n\
                     Ce fichier est **généré** par `ax-model` : ne pas le modifier à la main.\n\
                     Toute option d'AXION est déclarée une seule fois, dans\n\
                     `crates/ax-model/src/config/defaults.rs`, avec son défaut, son domaine\n\
                     et sa description (R-430).\n\n\
                     Une option se surcharge par la propriété système `-Daxion.<chemin>`.\n\
                     La colonne « chaud » indique qu'elle est rechargeable par\n\
                     `/axion config reload` (R-431) ; la colonne « serveur » qu'elle est\n\
                     imposée par le serveur au handshake, la valeur locale du client étant\n\
                     alors ignorée (R-1830).\n",
                header: "| Option | Type | Défaut | Domaine | Chaud | Serveur | Description |",
                yes: "oui",
                no: "non",
                server_overrides: SERVER_OVERRIDES_SECTION,
            },
            DocLanguage::English => Wording {
                intro: "# Configuration\n\n\
                     This file is **generated** by `ax-model`: do not edit it by hand.\n\
                     Every AXION option is declared once, in\n\
                     `crates/ax-model/src/config/defaults.rs`, with its default, its domain\n\
                     and its description (R-430).\n\n\
                     An option can be overridden with the system property `-Daxion.<path>`.\n\
                     The \"hot\" column says it can be reloaded with\n\
                     `/axion config reload` (R-431); the \"server\" column, that the server\n\
                     imposes it at handshake, the client's local value then being\n\
                     ignored (R-1830).\n",
                header: "| Option | Type | Default | Domain | Hot | Server | Description |",
                yes: "yes",
                no: "no",
                server_overrides: SERVER_OVERRIDES_SECTION_EN,
            },
        }
    }
}

/// Le type d'une valeur, tel que la page de cette langue le nomme.
const fn type_label(value: ConfigValue, language: DocLanguage) -> &'static str {
    match language {
        DocLanguage::French => value.type_name(),
        DocLanguage::English => match value {
            ConfigValue::Bool(_) => "boolean",
            ConfigValue::Int(_) => "integer",
            ConfigValue::Float(_) => "float",
            ConfigValue::Str(_) => "string",
        },
    }
}

/// Le domaine d'une option, tel que la page de cette langue l'écrit : seuls
/// deux domaines portent des mots, les autres ne sont que des valeurs.
fn domain_label(domain: &ConfigDomain, language: DocLanguage) -> String {
    match (language, domain) {
        (DocLanguage::English, ConfigDomain::FreeText) => "free text".to_owned(),
        (DocLanguage::English, ConfigDomain::KeywordOrCount(values)) => {
            format!("{} | <integer>", values.join(" | "))
        }
        _ => domain.to_string(),
    }
}

/// Rend la documentation de référence de la configuration, dans une langue.
///
/// R-430 : `CONFIGURATION.md` est généré depuis cette source, et une option non
/// documentée casse le build (T-021). Sa traduction, `CONFIGURATION.en.md`,
/// l'est de même, depuis la description anglaise de chaque option.
#[must_use]
pub fn render_markdown(language: DocLanguage) -> String {
    let wording = Wording::of(language);
    let mut out = String::from(wording.intro);

    for scope in ConfigScope::ALL {
        out.push_str(&format!("\n## `{}`\n", scope.file_name()));
        let mut current_section = "";
        for option in scope.options() {
            if option.section() != current_section {
                current_section = option.section();
                out.push_str(&format!(
                    "\n### `[{current_section}]`\n\n{}\n|---|---|---|---|---|---|---|\n",
                    wording.header
                ));
            }
            out.push_str(&format!(
                "| `{}` | {} | `{}` | `{}` | {} | {} | {} |\n",
                option.key(),
                type_label(option.default, language),
                escape_pipes(&option.default.to_toml()),
                // Un domaine énuméré sépare ses valeurs par « | », qui
                // couperait la cellule du tableau s'il n'était pas échappé.
                escape_pipes(&domain_label(&option.domain, language)),
                if option.hot { wording.yes } else { wording.no },
                if option.server_authoritative {
                    wording.yes
                } else {
                    wording.no
                },
                match language {
                    DocLanguage::French => option.description,
                    DocLanguage::English => option.description_en,
                },
            ));
        }
    }
    out.push_str(wording.server_overrides);
    out
}

/// Le fichier des surcharges de l'administrateur (ADR-165 D4). Ses clés nomment
/// du contenu — un matériau, une définition, un pneu, un bloc — et ne sont donc
/// pas des options déclarées : la couche Forge l'écrit, commenté, au premier
/// démarrage, et le relit à chaque rechargement des données.
const SERVER_OVERRIDES_SECTION: &str = "\n## `axion-server-overrides.toml`\n\n\
     Les surcharges de l'administrateur du serveur (ADR-162 D8, ADR-165 D4) : des\n\
     multiplicateurs bornés et des correspondances de blocs, appliqués au contenu\n\
     résolu avant sa validation ; aucun nouveau contenu. Le serveur envoie aux\n\
     clients le contenu surchargé : ils n'ont pas besoin de ce fichier.\n\n\
     Le serveur l'écrit au premier démarrage, entièrement commenté — il ne porte\n\
     alors aucune surcharge —, et le relit au démarrage et à chaque `/reload`.\n\
     Chaque surcharge, appliquée ou refusée avec sa cause, s'écrit au journal du\n\
     serveur ; `/axion status` en donne le compte.\n\n\
     | Section | Clé | Effet |\n\
     |---|---|---|\n\
     | `[physics_materials.\"<ns>:<matériau>\"]` | une grandeur numérique du matériau (DM-07) | la multiplie |\n\
     | `[definitions.\"<ns>:<définition>\"]` | `mass` | multiplie toutes les masses écrites : `physics.mass`, corps, pièces, roues |\n\
     | `[definitions.\"<ns>:<définition>\"]` | `engine_torque` | multiplie chaque couple de `powertrain.engine.torque_curve` |\n\
     | `[definitions.\"<ns>:<définition>\"]` | `brake_torque` | multiplie `brakes.max_torque_front`, `max_torque_rear` et `handbrake_torque` |\n\
     | `[tires.\"<ns>:<pneu>\"]` | `grip` | multiplie `mu_long` et `mu_lat` |\n\
     | `[block_materials]` | `\"<ns>:<bloc>\"` ou `\"#<ns>:<étiquette>\"` | le matériau d'un bloc ou d'une étiquette de blocs, lu après les correspondances des mods |\n\n\
     Un multiplicateur va de 0,1 à 10 et ne touche que les valeurs écrites ; le\n\
     produit s'arrondit à douze chiffres significatifs. Une surcharge est refusée,\n\
     et dite au journal avec sa cause, si sa cible n'existe pas, si sa clé est\n\
     inconnue, si son multiplicateur sort des bornes, si sa cible n'écrit aucune\n\
     valeur à multiplier, si une valeur multipliée sort de son domaine, si une\n\
     définition surchargée ne passe plus son schéma, ou si une correspondance de\n\
     blocs nomme un matériau qu'aucun mod ne déclare.\n";

/// La même section, pour la page anglaise.
const SERVER_OVERRIDES_SECTION_EN: &str = "\n## `axion-server-overrides.toml`\n\n\
     The server administrator's overrides (ADR-162 D8, ADR-165 D4): bounded\n\
     multipliers and block mappings, applied to the resolved content before it\n\
     is validated; no new content. The server sends the overridden content to\n\
     clients: they do not need this file.\n\n\
     The server writes it on first startup, fully commented out — it then\n\
     carries no override — and rereads it at startup and on every `/reload`.\n\
     Each override, applied or refused with its reason, is written to the\n\
     server log; `/axion status` gives the count.\n\n\
     | Section | Key | Effect |\n\
     |---|---|---|\n\
     | `[physics_materials.\"<ns>:<material>\"]` | a numeric quantity of the material (DM-07) | multiplies it |\n\
     | `[definitions.\"<ns>:<definition>\"]` | `mass` | multiplies every written mass: `physics.mass`, bodies, parts, wheels |\n\
     | `[definitions.\"<ns>:<definition>\"]` | `engine_torque` | multiplies each torque of `powertrain.engine.torque_curve` |\n\
     | `[definitions.\"<ns>:<definition>\"]` | `brake_torque` | multiplies `brakes.max_torque_front`, `max_torque_rear` and `handbrake_torque` |\n\
     | `[tires.\"<ns>:<tire>\"]` | `grip` | multiplies `mu_long` and `mu_lat` |\n\
     | `[block_materials]` | `\"<ns>:<block>\"` or `\"#<ns>:<tag>\"` | the material of a block or of a block tag, read after the mods' mappings |\n\n\
     A multiplier ranges from 0.1 to 10 and only touches written values; the\n\
     product is rounded to twelve significant digits. An override is refused,\n\
     and logged with its reason, if its target does not exist, if its key is\n\
     unknown, if its multiplier is out of bounds, if its target writes no value\n\
     to multiply, if a multiplied value leaves its domain, if an overridden\n\
     definition no longer passes its schema, or if a block mapping names a\n\
     material that no mod declares.\n";

#[cfg(test)]
mod tests {
    use super::*;

    /// R-430 — toute option déclarée a une description non vide et un chemin
    /// bien formé. Une option sans description casserait la documentation
    /// générée, donc le build (T-021).
    #[test]
    fn toute_option_est_documentee() {
        for (scope, option) in all_options() {
            assert!(
                !option.description.trim().is_empty(),
                "{} : {} sans description",
                scope.file_name(),
                option.path
            );
            // La page anglaise se génère comme la française : une option sans
            // traduction y laisserait une cellule vide.
            assert!(
                !option.description_en.trim().is_empty(),
                "{} : {} sans description anglaise (`.en(...)`)",
                scope.file_name(),
                option.path
            );
            assert!(
                option.path.contains('.'),
                "{} : chemin sans section",
                option.path
            );
            assert!(
                !option.key().is_empty() && !option.section().is_empty(),
                "{} : chemin mal formé",
                option.path
            );
        }
    }

    /// Aucun chemin n'est déclaré deux fois dans une même portée : la source
    /// unique n'a de valeur que si elle est effectivement unique.
    #[test]
    fn aucun_chemin_duplique() {
        for scope in ConfigScope::ALL {
            let mut seen: Vec<&str> = Vec::new();
            for option in scope.options() {
                assert!(
                    !seen.contains(&option.path),
                    "{} : {} déclaré deux fois",
                    scope.file_name(),
                    option.path
                );
                seen.push(option.path);
            }
        }
    }

    /// Le défaut de chaque option appartient à son propre domaine. Un défaut
    /// invalide rendrait le mod inutilisable dès le premier démarrage.
    #[test]
    fn tout_defaut_est_valide() {
        for (scope, option) in all_options() {
            assert!(
                option.validate(option.default).is_ok(),
                "{} : le défaut de {} est hors de son domaine",
                scope.file_name(),
                option.path
            );
        }
    }

    /// Les options d'une même section sont contiguës : le rendu TOML n'émet
    /// un en-tête `[section]` qu'au changement, et une section éclatée
    /// produirait deux en-têtes identiques, donc un fichier invalide.
    #[test]
    fn les_sections_sont_contigues() {
        for scope in ConfigScope::ALL {
            let mut seen: Vec<&str> = Vec::new();
            let mut current = "";
            for option in scope.options() {
                if option.section() != current {
                    assert!(
                        !seen.contains(&option.section()),
                        "{} : la section [{}] est éclatée",
                        scope.file_name(),
                        option.section()
                    );
                    current = option.section();
                    seen.push(current);
                }
            }
        }
    }

    /// La validation rejette le mauvais type, le hors-plage et le chemin
    /// inconnu, chacun avec son code de l'ANNEXE A.1.
    #[test]
    fn la_validation_refuse_ce_qui_doit_etre_refuse() {
        let scope = ConfigScope::Common;

        assert!(validate(scope, "sim.max_substeps", ConfigValue::Int(4)).is_ok());

        let hors_plage = validate(scope, "sim.max_substeps", ConfigValue::Int(99)).unwrap_err();
        assert!(matches!(hors_plage, ConfigError::OutOfRange { .. }));

        let mauvais_type =
            validate(scope, "sim.max_substeps", ConfigValue::Bool(true)).unwrap_err();
        assert!(matches!(mauvais_type, ConfigError::TypeMismatch { .. }));

        let inconnue = validate(scope, "sim.pas_une_option", ConfigValue::Int(1)).unwrap_err();
        assert!(matches!(inconnue, ConfigError::UnknownOption { .. }));
    }

    /// Une énumération n'accepte que ses valeurs déclarées.
    #[test]
    fn les_enumerations_sont_fermees() {
        let scope = ConfigScope::Common;
        assert!(validate(scope, "deformation.quality", ConfigValue::Str("auto")).is_ok());
        assert!(validate(scope, "deformation.quality", ConfigValue::Str("ultra")).is_ok());
        assert!(validate(scope, "deformation.quality", ConfigValue::Str("maximum")).is_err());
    }

    /// NaN n'appartient à aucun intervalle et doit être refusé.
    #[test]
    fn nan_est_refuse() {
        let scope = ConfigScope::Common;
        assert!(validate(scope, "physics.gravity", ConfigValue::Float(f64::NAN)).is_err());
        assert!(validate(scope, "physics.gravity", ConfigValue::Float(f64::INFINITY)).is_err());
        assert!(validate(scope, "physics.gravity", ConfigValue::Float(-9.81)).is_ok());
    }

    /// La propriété système suit le chemin de l'option (`-Daxion.<chemin>`).
    #[test]
    fn propriete_systeme_derivee_du_chemin() {
        let option = find(ConfigScope::Common, "general.enabled").unwrap();
        assert_eq!(option.system_property(), "axion.general.enabled");
        assert_eq!(option.section(), "general");
        assert_eq!(option.key(), "enabled");
    }

    /// Le rendu TOML produit un fichier structuré, sans notation scientifique.
    #[test]
    fn rendu_toml_lisible() {
        let toml = render_toml(ConfigScope::Common);
        assert!(toml.starts_with("# axion-common.toml\n"));
        assert!(toml.contains("[general]\nenabled = true\n"));
        // Un défaut rendu en `5e6` serait illisible et, recopié dans un
        // fichier, ambigu quant à son type.
        assert!(
            !toml.contains("e+") && !toml.contains("e-"),
            "notation scientifique dans les nombres"
        );
    }

    /// La documentation générée couvre toutes les options.
    #[test]
    fn documentation_generee_complete() {
        for language in DocLanguage::ALL {
            let markdown = render_markdown(language);
            for (_, option) in all_options() {
                assert!(
                    markdown.contains(&format!("| `{}` |", option.key())),
                    "{} absent de {}",
                    option.path,
                    language.file_name()
                );
            }
        }
    }

    /// La page anglaise porte la description anglaise de chaque option, et
    /// aucun mot français de la page de référence.
    #[test]
    fn page_anglaise_traduite() {
        let markdown = render_markdown(DocLanguage::English);
        for (_, option) in all_options() {
            assert!(
                markdown.contains(option.description_en),
                "{} : description anglaise absente",
                option.path
            );
        }
        for french in [
            "| Défaut |",
            "| booléen |",
            "| oui |",
            "| non |",
            "texte libre",
            "<entier>",
        ] {
            assert!(
                !markdown.contains(french),
                "« {french} » dans la page anglaise"
            );
        }
    }
}
