//! Analyse des arguments de la ligne de commande.
//!
//! Écrite à la main, sans bibliothèque d'analyse d'arguments : la table 32.2
//! des dépendances n'en retient aucune, et la surface de la CLI est assez
//! petite pour qu'en ajouter une coûte plus qu'elle ne rapporte (R-2300).

use crate::CliError;

/// Une source à compiler, telle que `compile`, `validate` et `bench-asset` la
/// désignent.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SourceArgs {
    /// Chemin du fichier.
    pub path: String,
    /// Les colliders ne seront pas portés par un body dynamique (R-160).
    pub static_body: bool,
    /// Fichier d'options désigné par `--options` ; sans lui, le
    /// `<source>.axion.json` voisin, comme le jeu (ADR-135).
    pub options: Option<String>,
}

/// Une commande reconnue, avec ses arguments.
#[derive(Debug, Clone, PartialEq)]
pub enum Command {
    /// Compiler une source en conteneur A3D.
    Compile {
        /// La source.
        source: SourceArgs,
        /// Chemin du conteneur produit.
        output: String,
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
        /// La source.
        source: SourceArgs,
    },
    /// Valider un conteneur, une source ou un fichier d'options, sans rien
    /// écrire. Pour un conteneur ou un fichier d'options, `static_body` et
    /// `options` ne servent pas.
    Validate {
        /// Le fichier.
        file: SourceArgs,
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
        "compile" => {
            let (source, output) = parse_source("compile", rest, true)?;
            let output = output.ok_or_else(|| {
                CliError::Usage("compile attend une sortie : -o <sortie.a3d>".to_owned())
            })?;
            Ok(Command::Compile { source, output })
        }
        "inspect" => parse_inspect(rest),
        "diff" => parse_diff(rest),
        "validate" => {
            let (file, _) = parse_source("validate", rest, false)?;
            Ok(Command::Validate { file })
        }
        "lod" => parse_lod(rest),
        "bench-asset" => {
            let (source, _) = parse_source("bench-asset", rest, false)?;
            Ok(Command::BenchAsset { source })
        }
        other => Err(CliError::Usage(format!(
            "commande inconnue : « {other} ». Voir « {} help ».",
            crate::PROGRAM
        ))),
    }
}

/// Les arguments d'une commande qui lit une source : le chemin, `--static-body`,
/// `--options <fichier>`, et `-o <sortie>` si `with_output`.
fn parse_source(
    command: &str,
    args: &[String],
    with_output: bool,
) -> Result<(SourceArgs, Option<String>), CliError> {
    let mut path: Option<String> = None;
    let mut output: Option<String> = None;
    let mut options: Option<String> = None;
    let mut static_body = false;

    let mut index = 0;
    while index < args.len() {
        let arg = args[index].as_str();
        match arg {
            "-o" | "--out" if with_output => {
                index += 1;
                let value = value_of(args, index, arg, "un chemin de sortie")?;
                if output.replace(value).is_some() {
                    return Err(CliError::Usage("« -o » donné deux fois".to_owned()));
                }
            }
            "--options" => {
                index += 1;
                let value = value_of(args, index, arg, "un fichier d'options")?;
                if options.replace(value).is_some() {
                    return Err(CliError::Usage("« --options » donné deux fois".to_owned()));
                }
            }
            "--static-body" => static_body = true,
            _ if arg.starts_with('-') => {
                return Err(CliError::Usage(format!(
                    "option inconnue de {command} : « {arg} »"
                )));
            }
            _ => {
                if path.replace(arg.to_owned()).is_some() {
                    return Err(CliError::Usage(format!(
                        "{command} lit un seul fichier à la fois"
                    )));
                }
            }
        }
        index += 1;
    }

    let path = path.ok_or_else(|| CliError::Usage(format!("{command} attend un fichier")))?;
    Ok((
        SourceArgs {
            path,
            static_body,
            options,
        },
        output,
    ))
}

/// La valeur qui suit une option ; son absence est un mésusage, pas un chemin
/// vide silencieux.
fn value_of(args: &[String], index: usize, option: &str, what: &str) -> Result<String, CliError> {
    args.get(index)
        .cloned()
        .ok_or_else(|| CliError::Usage(format!("« {option} » attend {what}")))
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
                let value = value_of(args, index, arg, "une liste : 1,0.5,0.25")?;
                if ratios.replace(parse_ratios(&value)?).is_some() {
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

#[cfg(test)]
mod tests {
    use super::*;

    fn parse_of(args: &[&str]) -> Result<Command, CliError> {
        let owned: Vec<String> = args.iter().map(|arg| (*arg).to_owned()).collect();
        parse(&owned)
    }

    fn source(path: &str, static_body: bool, options: Option<&str>) -> SourceArgs {
        SourceArgs {
            path: path.to_owned(),
            static_body,
            options: options.map(str::to_owned),
        }
    }

    fn mesusage(args: &[&str]) -> bool {
        matches!(parse_of(args), Err(CliError::Usage(_)))
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
                source: source("voiture.glb", false, None),
                output: "voiture.a3d".to_owned(),
            }
        );
        // L'ordre des arguments et des options est libre.
        assert_eq!(
            parse_of(&["compile", "--static-body", "-o", "s.a3d", "monde.obj"]).unwrap(),
            Command::Compile {
                source: source("monde.obj", true, None),
                output: "s.a3d".to_owned(),
            }
        );
    }

    #[test]
    fn t580_compile_exige_source_et_sortie() {
        assert!(mesusage(&["compile"]));
        assert!(mesusage(&["compile", "x.glb"]));
        assert!(mesusage(&["compile", "-o"]));
        assert!(mesusage(&["compile", "a.glb", "b.glb", "-o", "o.a3d"]));
        assert!(mesusage(&["compile", "x.glb", "-o", "a", "-o", "b"]));
        assert!(mesusage(&["compile", "x.glb", "-o", "o.a3d", "--rapide"]));
    }

    #[test]
    fn t580_options_designe_un_fichier_d_options() {
        assert_eq!(
            parse_of(&[
                "compile",
                "voiture.glb",
                "--options",
                "autres.axion.json",
                "-o",
                "v.a3d"
            ])
            .unwrap(),
            Command::Compile {
                source: source("voiture.glb", false, Some("autres.axion.json")),
                output: "v.a3d".to_owned(),
            }
        );
        assert_eq!(
            parse_of(&["validate", "voiture.glb", "--options", "o.axion.json"]).unwrap(),
            Command::Validate {
                file: source("voiture.glb", false, Some("o.axion.json")),
            }
        );
        assert_eq!(
            parse_of(&["bench-asset", "--options", "o.axion.json", "voiture.glb"]).unwrap(),
            Command::BenchAsset {
                source: source("voiture.glb", false, Some("o.axion.json")),
            }
        );
        assert!(mesusage(&["compile", "x.glb", "-o", "o.a3d", "--options"]));
        assert!(mesusage(&[
            "validate",
            "x.glb",
            "--options",
            "a",
            "--options",
            "b"
        ]));
        // `-o` n'appartient qu'à compile.
        assert!(mesusage(&["validate", "x.glb", "-o", "o.a3d"]));
    }

    #[test]
    fn t580_inspect_prend_un_fichier() {
        assert_eq!(
            parse_of(&["inspect", "voiture.a3d"]).unwrap(),
            Command::Inspect {
                file: "voiture.a3d".to_owned(),
            }
        );
        assert!(mesusage(&["inspect"]));
        assert!(mesusage(&["inspect", "a.a3d", "b.a3d"]));
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
        assert!(mesusage(&["diff"]));
        assert!(mesusage(&["diff", "a.a3d"]));
        assert!(mesusage(&["diff", "a.a3d", "b.a3d", "c.a3d"]));
        assert!(mesusage(&["diff", "a.a3d", "--court"]));
    }

    #[test]
    fn t580_validate_prend_un_fichier_et_le_drapeau_des_corps_statiques() {
        assert_eq!(
            parse_of(&["validate", "voiture.a3d"]).unwrap(),
            Command::Validate {
                file: source("voiture.a3d", false, None),
            }
        );
        assert_eq!(
            parse_of(&["validate", "--static-body", "monde.obj"]).unwrap(),
            Command::Validate {
                file: source("monde.obj", true, None),
            }
        );
        assert!(mesusage(&["validate"]));
        assert!(mesusage(&["validate", "a.a3d", "b.a3d"]));
        assert!(mesusage(&["validate", "a.a3d", "--strict"]));
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
            assert!(mesusage(refus), "{refus:?} accepté");
        }
    }

    #[test]
    fn t580_bench_asset_prend_une_source() {
        assert_eq!(
            parse_of(&["bench-asset", "voiture.glb"]).unwrap(),
            Command::BenchAsset {
                source: source("voiture.glb", false, None),
            }
        );
        assert!(mesusage(&["bench-asset"]));
        assert!(mesusage(&["bench-asset", "a.glb", "b.glb"]));
        assert!(mesusage(&["bench-asset", "a.glb", "--longtemps"]));
    }

    #[test]
    fn t580_une_commande_inconnue_est_un_mesusage() {
        let error = parse_of(&["frobnique"]).unwrap_err();
        assert_eq!(error.exit_code(), 2);
    }
}
