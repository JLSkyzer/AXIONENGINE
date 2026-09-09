//! R-2400 — le registre de configuration ne doit pas diverger de l'ANNEXE A.3.
//!
//! L'annexe du cahier des charges est la forme normative de la configuration.
//! Le registre de `ax-model` en est la source exécutable. Les deux doivent dire
//! exactement la même chose : mêmes options, mêmes défauts. Ce test lit
//! l'annexe telle qu'elle est écrite dans le cahier des charges et la compare
//! au registre, option par option.
//!
//! Il échoue donc aussi bien sur une option oubliée que sur un défaut recopié
//! de travers — les deux fautes qu'une transcription manuelle produit.

use std::collections::BTreeMap;
use std::path::PathBuf;

use ax_model::config::{self, ConfigScope, ConfigValue};

/// Valeur telle qu'elle est écrite dans l'annexe, avant comparaison.
#[derive(Debug, PartialEq)]
enum AnnexValue {
    Bool(bool),
    Int(i64),
    Float(f64),
    Str(String),
}

impl AnnexValue {
    /// Interprète le membre droit d'une affectation TOML.
    fn parse(raw: &str) -> Option<Self> {
        let raw = raw.trim();
        if raw == "true" {
            return Some(AnnexValue::Bool(true));
        }
        if raw == "false" {
            return Some(AnnexValue::Bool(false));
        }
        if let Some(inner) = raw.strip_prefix('"').and_then(|r| r.strip_suffix('"')) {
            return Some(AnnexValue::Str(inner.to_owned()));
        }
        // Un point distingue le flottant de l'entier, comme en TOML.
        if raw.contains('.') {
            return raw.parse::<f64>().ok().map(AnnexValue::Float);
        }
        raw.parse::<i64>().ok().map(AnnexValue::Int)
    }

    /// Compare à la valeur déclarée dans le registre.
    fn matches(&self, declared: ConfigValue) -> bool {
        match (self, declared) {
            (AnnexValue::Bool(a), ConfigValue::Bool(b)) => *a == b,
            (AnnexValue::Int(a), ConfigValue::Int(b)) => *a == b,
            // Comparaison exacte : les deux valeurs sont écrites à la main, à
            // partir du même texte. Une tolérance masquerait justement la
            // faute de recopie que ce test cherche.
            (AnnexValue::Float(a), ConfigValue::Float(b)) => *a == b,
            (AnnexValue::Str(a), ConfigValue::Str(b)) => a == b,
            _ => false,
        }
    }

    fn type_name(&self) -> &'static str {
        match self {
            AnnexValue::Bool(_) => "booléen",
            AnnexValue::Int(_) => "entier",
            AnnexValue::Float(_) => "flottant",
            AnnexValue::Str(_) => "chaîne",
        }
    }
}

fn spec_path() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../cdc/AXIONENGINE_Cahier_des_Charges_v1.0.md")
}

/// Extrait les options de l'ANNEXE A.3, par fichier de configuration.
///
/// L'annexe contient un bloc ```toml``` par fichier, chacun ouvert par un
/// commentaire `# axion-<portée>.toml`.
fn parse_annex() -> BTreeMap<String, BTreeMap<String, AnnexValue>> {
    let spec = std::fs::read_to_string(spec_path()).expect("cahier des charges illisible");

    let annex_start = spec
        .find("## ANNEXE A.3")
        .expect("ANNEXE A.3 introuvable dans le cahier des charges");
    let annex = &spec[annex_start..];
    let annex_end = annex.find("\n## ANNEXE A.4").unwrap_or(annex.len());
    let annex = &annex[..annex_end];

    let mut files: BTreeMap<String, BTreeMap<String, AnnexValue>> = BTreeMap::new();
    let mut current_file: Option<String> = None;
    let mut current_section = String::new();
    let mut in_block = false;

    for line in annex.lines() {
        let trimmed = line.trim();

        if trimmed.starts_with("```") {
            in_block = !in_block;
            if !in_block {
                current_file = None;
                current_section.clear();
            }
            continue;
        }
        if !in_block {
            continue;
        }

        if let Some(name) = trimmed.strip_prefix("# axion-") {
            let name = name.trim();
            if name.ends_with(".toml") {
                current_file = Some(format!("axion-{name}"));
                files.entry(format!("axion-{name}")).or_default();
                current_section.clear();
            }
            continue;
        }

        let Some(file) = current_file.clone() else {
            continue;
        };

        if let Some(section) = trimmed.strip_prefix('[').and_then(|s| s.strip_suffix(']')) {
            current_section = section.to_owned();
            continue;
        }

        // Le commentaire de fin de ligne porte le domaine, pas la valeur.
        let code = trimmed.split('#').next().unwrap_or("").trim();
        if code.is_empty() {
            continue;
        }

        // Une ligne peut porter plusieurs options séparées par « ; ».
        for assignment in code.split(';') {
            let Some((key, raw)) = assignment.split_once('=') else {
                continue;
            };
            let key = key.trim();
            if key.is_empty() {
                continue;
            }
            let value = AnnexValue::parse(raw).unwrap_or_else(|| {
                panic!("valeur illisible dans l'annexe : {current_section}.{key} = {raw}")
            });
            let path = if current_section.is_empty() {
                key.to_owned()
            } else {
                format!("{current_section}.{key}")
            };
            files
                .get_mut(&file)
                .expect("fichier absent")
                .insert(path, value);
        }
    }
    files
}

#[test]
fn le_registre_ne_diverge_pas_de_l_annexe() {
    let annex = parse_annex();
    assert_eq!(
        annex.len(),
        3,
        "l'annexe devrait décrire trois fichiers, {} trouvés",
        annex.len()
    );

    let mut ecarts: Vec<String> = Vec::new();

    for scope in ConfigScope::ALL {
        let file = scope.file_name();
        let attendu = annex
            .get(file)
            .unwrap_or_else(|| panic!("{file} absent de l'annexe"));

        // Toute option de l'annexe est déclarée, avec le même défaut.
        for (path, value) in attendu {
            match config::find(scope, path) {
                None => ecarts.push(format!(
                    "{file} : {path} est dans l'annexe, absent du registre"
                )),
                Some(option) => {
                    if !value.matches(option.default) {
                        ecarts.push(format!(
                            "{file} : {path} vaut {:?} ({}) dans l'annexe, {:?} dans le registre",
                            value,
                            value.type_name(),
                            option.default,
                        ));
                    }
                }
            }
        }

        // Et réciproquement : rien n'est déclaré qui ne figure dans l'annexe.
        for option in scope.options() {
            if !attendu.contains_key(option.path) {
                ecarts.push(format!(
                    "{file} : {} est dans le registre, absent de l'annexe",
                    option.path
                ));
            }
        }
    }

    assert!(
        ecarts.is_empty(),
        "le registre et l'ANNEXE A.3 divergent sur {} point(s) :\n  {}",
        ecarts.len(),
        ecarts.join("\n  ")
    );
}

#[test]
fn configuration_md_est_a_jour() {
    // R-430 : `CONFIGURATION.md` est généré, jamais écrit à la main. Ce test
    // est l'équivalent local du job `docs` de la CI : il échoue dès qu'une
    // option est ajoutée, modifiée ou décrite autrement sans régénérer le
    // fichier. Le remède tient en une commande, rappelée dans le message.
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../CONFIGURATION.md");
    let sur_disque = std::fs::read_to_string(&path).unwrap_or_else(|err| {
        panic!(
            "{} illisible ({err}) — le générer avec :\n  \
             cargo run -p ax-model --bin gen_config_docs",
            path.display()
        )
    });

    // Les fins de ligne diffèrent selon la configuration git du poste ; seul
    // le contenu nous intéresse.
    let attendu = config::render_markdown().replace("\r\n", "\n");
    assert_eq!(
        sur_disque.replace("\r\n", "\n"),
        attendu,
        "CONFIGURATION.md a divergé de la source unique — le régénérer avec :\n  \
         cargo run -p ax-model --bin gen_config_docs"
    );
}

#[test]
fn le_rendu_toml_couvre_toutes_les_options_de_l_annexe() {
    // Le fichier livré à l'utilisateur doit contenir chaque option de l'annexe,
    // dans sa section : c'est ce fichier qu'il ouvrira pour régler le mod.
    for scope in ConfigScope::ALL {
        let rendu = config::render_toml(scope);
        for option in scope.options() {
            assert!(
                rendu.contains(&format!("[{}]", option.section())),
                "{} : section [{}] absente du rendu",
                scope.file_name(),
                option.section()
            );
            assert!(
                rendu.contains(&format!("{} = ", option.key())),
                "{} : option {} absente du rendu",
                scope.file_name(),
                option.path
            );
        }
    }
}
