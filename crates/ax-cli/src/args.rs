//! Analyse des arguments de la ligne de commande.
//!
//! Écrite à la main, sans bibliothèque d'analyse d'arguments : la table 32.2
//! des dépendances n'en retient aucune, et la surface de la CLI est assez
//! petite pour qu'en ajouter une coûte plus qu'elle ne rapporte (R-2300).

use crate::CliError;

/// Une commande reconnue, avec ses arguments.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Command {
    /// Compiler une source en conteneur A3D.
    Compile {
        /// Chemin de la source.
        source: String,
        /// Chemin du conteneur produit.
        output: String,
        /// Les colliders ne seront pas portés par un body dynamique (R-160).
        static_body: bool,
    },
    /// Décrire un conteneur A3D.
    Inspect {
        /// Chemin du conteneur.
        file: String,
    },
    /// Afficher l'aide.
    Help,
}

/// Analyse les arguments (hors nom du programme).
///
/// # Errors
///
/// [`CliError::Usage`] si la commande est inconnue, si un argument obligatoire
/// manque, ou si une option est employée à tort.
pub fn parse(args: &[String]) -> Result<Command, CliError> {
    let Some((command, rest)) = args.split_first() else {
        return Ok(Command::Help);
    };

    match command.as_str() {
        "help" | "--help" | "-h" => Ok(Command::Help),
        "compile" => parse_compile(rest),
        "inspect" => parse_inspect(rest),
        other => Err(CliError::Usage(format!(
            "commande inconnue : « {other} ». Voir « {} help ».",
            crate::PROGRAM
        ))),
    }
}

fn parse_compile(args: &[String]) -> Result<Command, CliError> {
    let mut source: Option<String> = None;
    let mut output: Option<String> = None;
    let mut static_body = false;

    let mut index = 0;
    while index < args.len() {
        let arg = args[index].as_str();
        match arg {
            "-o" | "--out" => {
                // La valeur suit l'option ; son absence est un mésusage, pas
                // un chemin vide silencieux.
                index += 1;
                let value = args.get(index).ok_or_else(|| {
                    CliError::Usage(format!("« {arg} » attend un chemin de sortie"))
                })?;
                if output.replace(value.clone()).is_some() {
                    return Err(CliError::Usage("« -o » donné deux fois".to_owned()));
                }
            }
            "--static-body" => static_body = true,
            _ if arg.starts_with('-') => {
                return Err(CliError::Usage(format!(
                    "option inconnue de compile : « {arg} »"
                )));
            }
            _ => {
                if source.replace(arg.to_owned()).is_some() {
                    return Err(CliError::Usage(
                        "une seule source à la fois est compilée".to_owned(),
                    ));
                }
            }
        }
        index += 1;
    }

    let source = source.ok_or_else(|| CliError::Usage("compile attend une source".to_owned()))?;
    let output = output
        .ok_or_else(|| CliError::Usage("compile attend une sortie : -o <sortie.a3d>".to_owned()))?;
    Ok(Command::Compile {
        source,
        output,
        static_body,
    })
}

fn parse_inspect(args: &[String]) -> Result<Command, CliError> {
    match args {
        [file] if !file.starts_with('-') => Ok(Command::Inspect { file: file.clone() }),
        [] => Err(CliError::Usage("inspect attend un fichier A3D".to_owned())),
        _ => Err(CliError::Usage(
            "inspect attend un seul fichier A3D".to_owned(),
        )),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse_of(args: &[&str]) -> Result<Command, CliError> {
        let owned: Vec<String> = args.iter().map(|arg| (*arg).to_owned()).collect();
        parse(&owned)
    }

    #[test]
    fn t580_sans_argument_l_aide_s_affiche() {
        assert_eq!(parse_of(&[]).unwrap(), Command::Help);
        assert_eq!(parse_of(&["help"]).unwrap(), Command::Help);
        assert_eq!(parse_of(&["-h"]).unwrap(), Command::Help);
    }

    #[test]
    fn t580_compile_lit_source_sortie_et_drapeau() {
        assert_eq!(
            parse_of(&["compile", "voiture.glb", "-o", "voiture.a3d"]).unwrap(),
            Command::Compile {
                source: "voiture.glb".to_owned(),
                output: "voiture.a3d".to_owned(),
                static_body: false,
            }
        );
        // L'ordre des arguments et des options est libre.
        assert_eq!(
            parse_of(&["compile", "--static-body", "-o", "s.a3d", "monde.obj"]).unwrap(),
            Command::Compile {
                source: "monde.obj".to_owned(),
                output: "s.a3d".to_owned(),
                static_body: true,
            }
        );
    }

    #[test]
    fn t580_compile_exige_source_et_sortie() {
        assert!(matches!(parse_of(&["compile"]), Err(CliError::Usage(_))));
        assert!(matches!(
            parse_of(&["compile", "x.glb"]),
            Err(CliError::Usage(_))
        ));
        assert!(matches!(
            parse_of(&["compile", "-o"]),
            Err(CliError::Usage(_))
        ));
        assert!(matches!(
            parse_of(&["compile", "a.glb", "b.glb", "-o", "o.a3d"]),
            Err(CliError::Usage(_))
        ));
        assert!(matches!(
            parse_of(&["compile", "x.glb", "-o", "a", "-o", "b"]),
            Err(CliError::Usage(_))
        ));
        assert!(matches!(
            parse_of(&["compile", "x.glb", "-o", "o.a3d", "--rapide"]),
            Err(CliError::Usage(_))
        ));
    }

    #[test]
    fn t580_inspect_prend_un_fichier() {
        assert_eq!(
            parse_of(&["inspect", "voiture.a3d"]).unwrap(),
            Command::Inspect {
                file: "voiture.a3d".to_owned(),
            }
        );
        assert!(matches!(parse_of(&["inspect"]), Err(CliError::Usage(_))));
        assert!(matches!(
            parse_of(&["inspect", "a.a3d", "b.a3d"]),
            Err(CliError::Usage(_))
        ));
    }

    #[test]
    fn t580_une_commande_inconnue_est_un_mesusage() {
        let error = parse_of(&["frobnique"]).unwrap_err();
        assert_eq!(error.exit_code(), 2);
    }
}
