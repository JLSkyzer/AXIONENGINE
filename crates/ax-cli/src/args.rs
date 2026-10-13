//! Analyse des arguments de la ligne de commande.
//!
//! Écrite à la main, sans bibliothèque d'analyse d'arguments : la table 32.2
//! des dépendances n'en retient aucune, et la surface de la CLI est assez
//! petite pour qu'en ajouter une coûte plus qu'elle ne rapporte (R-2300).

use crate::CliError;

/// Une commande reconnue, avec ses arguments.
#[derive(Debug, Clone, PartialEq)]
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
    /// Comparer deux conteneurs A3D.
    Diff {
        /// Premier conteneur, « A ».
        a: String,
        /// Second conteneur, « B ».
        b: String,
    },
    /// Montrer les LOD qu'un conteneur recevrait à d'autres ratios.
    Lod {
        /// Chemin du conteneur.
        file: String,
        /// Part de triangles gardée à chaque niveau, LOD 0 compris.
        ratios: Vec<f32>,
    },
    /// Mesurer la compilation d'une source (B-09).
    BenchAsset {
        /// Chemin de la source.
        source: String,
        /// Ses colliders ne seront pas portés par un body dynamique (R-160).
        static_body: bool,
    },
    /// Valider un conteneur ou une source, sans rien écrire.
    Validate {
        /// Chemin du fichier.
        file: String,
        /// Pour une source : ses colliders ne seront pas portés par un body
        /// dynamique (R-160), comme `compile --static-body`.
        static_body: bool,
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
        "diff" => parse_diff(rest),
        "validate" => parse_validate(rest),
        "lod" => parse_lod(rest),
        "bench-asset" => parse_bench_asset(rest),
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

fn parse_diff(args: &[String]) -> Result<Command, CliError> {
    match args {
        [a, b] if !a.starts_with('-') && !b.starts_with('-') => Ok(Command::Diff {
            a: a.clone(),
            b: b.clone(),
        }),
        _ => Err(CliError::Usage(
            "diff attend deux fichiers A3D : diff <a.a3d> <b.a3d>".to_owned(),
        )),
    }
}

fn parse_lod(args: &[String]) -> Result<Command, CliError> {
    let mut file: Option<String> = None;
    let mut ratios: Option<Vec<f32>> = None;
    let mut index = 0;
    while index < args.len() {
        let arg = args[index].as_str();
        match arg {
            "--ratios" => {
                index += 1;
                let value = args.get(index).ok_or_else(|| {
                    CliError::Usage("« --ratios » attend une liste : 1,0.5,0.25".to_owned())
                })?;
                if ratios.replace(parse_ratios(value)?).is_some() {
                    return Err(CliError::Usage("« --ratios » donné deux fois".to_owned()));
                }
            }
            other if other.starts_with('-') => {
                return Err(CliError::Usage(format!(
                    "option inconnue de lod : « {other} »"
                )));
            }
            other => {
                if file.replace(other.to_owned()).is_some() {
                    return Err(CliError::Usage(
                        "lod lit un seul conteneur à la fois".to_owned(),
                    ));
                }
            }
        }
        index += 1;
    }
    let file = file.ok_or_else(|| CliError::Usage("lod attend un fichier A3D".to_owned()))?;
    let ratios = ratios
        .ok_or_else(|| CliError::Usage("lod attend des ratios : --ratios 1,0.5,0.25".to_owned()))?;
    Ok(Command::Lod { file, ratios })
}

/// Lit une liste de ratios : de 1 à 8 valeurs dans `]0, 1]`, non croissantes,
/// la première à 1 — le LOD 0 est la source (PARTIE 6.4).
fn parse_ratios(text: &str) -> Result<Vec<f32>, CliError> {
    let mut ratios = Vec::new();
    for part in text.split(',') {
        let ratio: f32 = part
            .trim()
            .parse()
            .map_err(|_| CliError::Usage(format!("ratio illisible : « {part} »")))?;
        if !(ratio.is_finite() && ratio > 0.0 && ratio <= 1.0) {
            return Err(CliError::Usage(format!(
                "ratio hors de ]0, 1] : « {part} »"
            )));
        }
        ratios.push(ratio);
    }
    if ratios.len() > ax_asset::optimize::MAX_LOD_LEVELS {
        return Err(CliError::Usage(format!(
            "au plus {} niveaux, LOD 0 compris",
            ax_asset::optimize::MAX_LOD_LEVELS
        )));
    }
    if ratios.first() != Some(&1.0) {
        return Err(CliError::Usage(
            "le premier ratio est celui du LOD 0 : 1".to_owned(),
        ));
    }
    if ratios.windows(2).any(|pair| pair[1] > pair[0]) {
        return Err(CliError::Usage(
            "les ratios ne croissent pas d'un niveau au suivant".to_owned(),
        ));
    }
    Ok(ratios)
}

fn parse_bench_asset(args: &[String]) -> Result<Command, CliError> {
    let mut source: Option<String> = None;
    let mut static_body = false;
    for arg in args {
        match arg.as_str() {
            "--static-body" => static_body = true,
            other if other.starts_with('-') => {
                return Err(CliError::Usage(format!(
                    "option inconnue de bench-asset : « {other} »"
                )));
            }
            other => {
                if source.replace(other.to_owned()).is_some() {
                    return Err(CliError::Usage(
                        "bench-asset mesure une seule source à la fois".to_owned(),
                    ));
                }
            }
        }
    }
    let source =
        source.ok_or_else(|| CliError::Usage("bench-asset attend une source".to_owned()))?;
    Ok(Command::BenchAsset {
        source,
        static_body,
    })
}

fn parse_validate(args: &[String]) -> Result<Command, CliError> {
    let mut file: Option<String> = None;
    let mut static_body = false;
    for arg in args {
        match arg.as_str() {
            "--static-body" => static_body = true,
            other if other.starts_with('-') => {
                return Err(CliError::Usage(format!(
                    "option inconnue de validate : « {other} »"
                )));
            }
            other => {
                if file.replace(other.to_owned()).is_some() {
                    return Err(CliError::Usage(
                        "validate juge un seul fichier à la fois".to_owned(),
                    ));
                }
            }
        }
    }
    let file = file.ok_or_else(|| CliError::Usage("validate attend un fichier".to_owned()))?;
    Ok(Command::Validate { file, static_body })
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
    fn t580_diff_prend_deux_fichiers() {
        assert_eq!(
            parse_of(&["diff", "a.a3d", "b.a3d"]).unwrap(),
            Command::Diff {
                a: "a.a3d".to_owned(),
                b: "b.a3d".to_owned(),
            }
        );
        assert!(matches!(parse_of(&["diff"]), Err(CliError::Usage(_))));
        assert!(matches!(
            parse_of(&["diff", "a.a3d"]),
            Err(CliError::Usage(_))
        ));
        assert!(matches!(
            parse_of(&["diff", "a.a3d", "b.a3d", "c.a3d"]),
            Err(CliError::Usage(_))
        ));
        assert!(matches!(
            parse_of(&["diff", "a.a3d", "--court"]),
            Err(CliError::Usage(_))
        ));
    }

    #[test]
    fn t580_validate_prend_un_fichier_et_le_drapeau_des_corps_statiques() {
        assert_eq!(
            parse_of(&["validate", "voiture.a3d"]).unwrap(),
            Command::Validate {
                file: "voiture.a3d".to_owned(),
                static_body: false,
            }
        );
        assert_eq!(
            parse_of(&["validate", "--static-body", "monde.obj"]).unwrap(),
            Command::Validate {
                file: "monde.obj".to_owned(),
                static_body: true,
            }
        );
        assert!(matches!(parse_of(&["validate"]), Err(CliError::Usage(_))));
        assert!(matches!(
            parse_of(&["validate", "a.a3d", "b.a3d"]),
            Err(CliError::Usage(_))
        ));
        assert!(matches!(
            parse_of(&["validate", "a.a3d", "--strict"]),
            Err(CliError::Usage(_))
        ));
    }

    #[test]
    fn t580_lod_prend_un_fichier_et_des_ratios_controles() {
        assert_eq!(
            parse_of(&["lod", "voiture.a3d", "--ratios", "1,0.5,0.25"]).unwrap(),
            Command::Lod {
                file: "voiture.a3d".to_owned(),
                ratios: vec![1.0, 0.5, 0.25],
            }
        );
        for refus in [
            &["lod", "voiture.a3d"][..],
            &["lod", "--ratios", "1,0.5"],
            &["lod", "v.a3d", "--ratios"],
            &["lod", "v.a3d", "--ratios", "0.5,0.25"],
            &["lod", "v.a3d", "--ratios", "1,0.5,0.75"],
            &["lod", "v.a3d", "--ratios", "1,0"],
            &["lod", "v.a3d", "--ratios", "1,1.5"],
            &["lod", "v.a3d", "--ratios", "1,moitié"],
            &["lod", "v.a3d", "--ratios", "1,1,1,1,1,1,1,1,1"],
            &["lod", "v.a3d", "--ratios", "1", "--ratios", "1"],
        ] {
            assert!(
                matches!(parse_of(refus), Err(CliError::Usage(_))),
                "{refus:?} accepté"
            );
        }
    }

    #[test]
    fn t580_bench_asset_prend_une_source() {
        assert_eq!(
            parse_of(&["bench-asset", "voiture.glb"]).unwrap(),
            Command::BenchAsset {
                source: "voiture.glb".to_owned(),
                static_body: false,
            }
        );
        assert!(matches!(
            parse_of(&["bench-asset"]),
            Err(CliError::Usage(_))
        ));
        assert!(matches!(
            parse_of(&["bench-asset", "a.glb", "b.glb"]),
            Err(CliError::Usage(_))
        ));
        assert!(matches!(
            parse_of(&["bench-asset", "a.glb", "--longtemps"]),
            Err(CliError::Usage(_))
        ));
    }

    #[test]
    fn t580_une_commande_inconnue_est_un_mesusage() {
        let error = parse_of(&["frobnique"]).unwrap_err();
        assert_eq!(error.exit_code(), 2);
    }
}
