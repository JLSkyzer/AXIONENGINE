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
}

impl ConfigOption {
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

/// Rend la documentation de référence de la configuration.
///
/// R-430 : `CONFIGURATION.md` est généré depuis cette source, et une option non
/// documentée casse le build (T-021).
#[must_use]
pub fn render_markdown() -> String {
    let mut out = String::from(
        "# Configuration\n\n\
         Ce fichier est **généré** par `ax-model` : ne pas le modifier à la main.\n\
         Toute option d'AXION est déclarée une seule fois, dans\n\
         `crates/ax-model/src/config/defaults.rs`, avec son défaut, son domaine\n\
         et sa description (R-430).\n\n\
         Une option se surcharge par la propriété système `-Daxion.<chemin>`.\n\
         La colonne « chaud » indique qu'elle est rechargeable par\n\
         `/axion config reload` (R-431) ; la colonne « serveur » qu'elle est\n\
         imposée par le serveur au handshake, la valeur locale du client étant\n\
         alors ignorée (R-1830).\n",
    );

    for scope in ConfigScope::ALL {
        out.push_str(&format!("\n## `{}`\n", scope.file_name()));
        let mut current_section = "";
        for option in scope.options() {
            if option.section() != current_section {
                current_section = option.section();
                out.push_str(&format!(
                    "\n### `[{current_section}]`\n\n\
                     | Option | Type | Défaut | Domaine | Chaud | Serveur | Description |\n\
                     |---|---|---|---|---|---|---|\n"
                ));
            }
            out.push_str(&format!(
                "| `{}` | {} | `{}` | `{}` | {} | {} | {} |\n",
                option.key(),
                option.default.type_name(),
                escape_pipes(&option.default.to_toml()),
                // Un domaine énuméré sépare ses valeurs par « | », qui
                // couperait la cellule du tableau s'il n'était pas échappé.
                escape_pipes(&option.domain.to_string()),
                if option.hot { "oui" } else { "non" },
                if option.server_authoritative {
                    "oui"
                } else {
                    "non"
                },
                option.description,
            ));
        }
    }
    out
}

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
        let markdown = render_markdown();
        for (_, option) in all_options() {
            assert!(
                markdown.contains(&format!("| `{}` |", option.key())),
                "{} absent de la documentation générée",
                option.path
            );
        }
    }
}
