//! Binaire `axion-cli` (C-74) : la couche d'entrées-sorties autour de [`ax_cli`].
//!
//! Toute la logique vit dans la bibliothèque, où elle se teste sans disque.
//! Ici : lire les fichiers, écrire le conteneur, résoudre les références
//! voisines d'une source, et traduire un [`ax_cli::CliError`] en code de sortie.

use ax_asset::a3d::A3dLimits;
use ax_asset::import::SourceFormat;
use ax_cli::{
    compile_source, inspect, max_compiled_bytes, options, parse, sibling_path, summary, CliError,
    Command, PROGRAM, USAGE,
};
use std::path::Path;
use std::process::ExitCode;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match run(&args) {
        Ok(lines) => {
            for line in lines {
                println!("{line}");
            }
            ExitCode::SUCCESS
        }
        Err(error) => {
            eprintln!("{PROGRAM} : {error}");
            // `ExitCode` ne porte que des `u8` ; les codes de la CLI (1, 2)
            // y tiennent.
            ExitCode::from(error.exit_code() as u8)
        }
    }
}

/// Exécute une commande et rend les lignes à afficher.
fn run(args: &[String]) -> Result<Vec<String>, CliError> {
    match parse(args)? {
        Command::Help => Ok(USAGE.lines().map(str::to_owned).collect()),
        Command::Compile {
            source,
            output,
            static_body,
        } => compile(&source, &output, static_body),
        Command::Inspect { file } => {
            let bytes = read(&file)?;
            inspect(&bytes, A3dLimits::new(max_compiled_bytes()))
        }
    }
}

/// Compile une source en conteneur A3D et l'écrit.
fn compile(source: &str, output: &str, static_body: bool) -> Result<Vec<String>, CliError> {
    let source_path = Path::new(source);
    let format = SourceFormat::from_path(source_path).ok_or_else(|| {
        CliError::UnsupportedFormat(
            source_path
                .extension()
                .and_then(|extension| extension.to_str())
                .unwrap_or("")
                .to_owned(),
        )
    })?;

    let bytes = read(source)?;
    let source_name = source_path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or(source);
    let opts = options(source_name, &bytes, static_body);

    // Les références voisines sont lues à côté de la source. Une lecture qui
    // échoue rend `None` : l'importeur la traite comme absente, un refus propre.
    let source_dir = source_path.parent().unwrap_or(Path::new("")).to_path_buf();
    let resolve = |relative: &str| -> Option<Vec<u8>> {
        let path = sibling_path(&source_dir, relative)?;
        std::fs::read(path).ok()
    };

    let compiled = compile_source(&bytes, format, resolve, &opts)?;
    write(output, &compiled.bytes)?;
    Ok(summary(&compiled, bytes.len(), output))
}

/// Lit un fichier, en nommant le chemin en cause si la lecture échoue.
fn read(path: &str) -> Result<Vec<u8>, CliError> {
    std::fs::read(path).map_err(|error| CliError::Io {
        path: path.to_owned(),
        detail: error.to_string(),
    })
}

/// Écrit un fichier, en créant son répertoire parent au besoin.
fn write(path: &str, bytes: &[u8]) -> Result<(), CliError> {
    if let Some(parent) = Path::new(path).parent() {
        if !parent.as_os_str().is_empty() {
            std::fs::create_dir_all(parent).map_err(|error| CliError::Io {
                path: parent.display().to_string(),
                detail: error.to_string(),
            })?;
        }
    }
    std::fs::write(path, bytes).map_err(|error| CliError::Io {
        path: path.to_owned(),
        detail: error.to_string(),
    })
}
