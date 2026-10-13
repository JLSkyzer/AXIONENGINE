//! Options communes des coureurs de mesure : où archiver, et la provenance qu'un
//! résultat archivé doit porter (R-2231).

use std::path::PathBuf;

/// Options d'un coureur.
#[derive(Debug, Clone)]
pub struct Args {
    /// Dossier des résultats archivés.
    pub out: PathBuf,
    /// Empreinte du commit mesuré.
    pub commit: Option<String>,
    /// Date ISO 8601 de la mesure.
    pub date: Option<String>,
    /// Modèle de processeur.
    pub cpu: Option<String>,
    /// Configuration normative plutôt que rapide.
    pub normative: bool,
}

/// Lit les options de la ligne de commande ; `-h` affiche `help` et termine.
///
/// # Errors
///
/// Un argument inconnu, ou un drapeau sans sa valeur.
pub fn parse(help: &str) -> Result<Args, String> {
    let mut parsed = Args {
        out: PathBuf::from("benchmarks/results"),
        commit: None,
        date: None,
        cpu: None,
        normative: false,
    };
    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--out" => parsed.out = PathBuf::from(value(&mut args, "--out")?),
            "--commit" => parsed.commit = Some(value(&mut args, "--commit")?),
            "--date" => parsed.date = Some(value(&mut args, "--date")?),
            "--cpu" => parsed.cpu = Some(value(&mut args, "--cpu")?),
            "--normative" => parsed.normative = true,
            "-h" | "--help" => {
                println!("{help}");
                std::process::exit(0);
            }
            other => return Err(format!("argument inconnu : {other}")),
        }
    }
    Ok(parsed)
}

/// Lit la valeur d'un drapeau, ou explique laquelle manque.
fn value(args: &mut impl Iterator<Item = String>, flag: &str) -> Result<String, String> {
    args.next()
        .ok_or_else(|| format!("{flag} attend une valeur"))
}

/// Texte d'aide des options communes.
pub const OPTIONS_HELP: &str = "Options :\n\
\x20 --out <dir>       dossier des resultats archives (defaut : benchmarks/results)\n\
\x20 --commit <sha>    empreinte du commit mesure (provenance)\n\
\x20 --date <iso>      date ISO 8601 de la mesure (provenance)\n\
\x20 --cpu <modele>    modele de processeur (provenance)\n\
\x20 --normative       configuration normative (30 s + 5 x 60 s) au lieu de la rapide\n\
\x20 -h, --help        affiche cette aide\n\n\
Sans --commit, --date et --cpu, la mesure a lieu mais rien n'est archive (R-2231).";
