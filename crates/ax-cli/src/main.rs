//! Binaire `axion-cli` (C-74) : la couche d'entrées-sorties autour de [`ax_cli`].
//!
//! Toute la logique vit dans la bibliothèque, où elle se teste sans disque.
//! Ici : lire les fichiers, écrire le conteneur, résoudre les références
//! voisines d'une source, et traduire un [`ax_cli::CliError`] en code de sortie.

use ax_asset::a3d::A3dLimits;
use ax_asset::compile::CompileOptions;
use ax_asset::import::SourceFormat;
use ax_cli::{
    compile_source, diff, inspect, max_compiled_bytes, options, parse, sibling_path, summary,
    validate_container, validate_source, CliError, Command, PROGRAM, USAGE,
};
use std::path::{Path, PathBuf};
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
        Command::Diff { a, b } => {
            diff(&read(&a)?, &read(&b)?, A3dLimits::new(max_compiled_bytes()))
        }
        Command::Validate { file, static_body } => validate(&file, static_body),
    }
}

/// Une source lue, avec ce qu'il faut pour la compiler comme le jeu.
struct Source {
    format: SourceFormat,
    bytes: Vec<u8>,
    options: CompileOptions,
    dir: PathBuf,
}

impl Source {
    /// Lit une source et compose ses options de compilation.
    fn open(path: &str, static_body: bool) -> Result<Self, CliError> {
        let source_path = Path::new(path);
        let format = SourceFormat::from_path(source_path).ok_or_else(|| {
            CliError::UnsupportedFormat(
                source_path
                    .extension()
                    .and_then(|extension| extension.to_str())
                    .unwrap_or("")
                    .to_owned(),
            )
        })?;
        let bytes = read(path)?;
        let name = source_path
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or(path);
        let options = options(name, &bytes, static_body);
        let dir = source_path.parent().unwrap_or(Path::new("")).to_path_buf();
        Ok(Self {
            format,
            bytes,
            options,
            dir,
        })
    }

    /// Les références voisines, lues à côté de la source. Une lecture qui échoue
    /// rend `None` : l'importeur la traite comme absente, un refus propre.
    fn resolver(&self) -> impl FnMut(&str) -> Option<Vec<u8>> + '_ {
        |relative: &str| {
            let path = sibling_path(&self.dir, relative)?;
            std::fs::read(path).ok()
        }
    }
}

/// Compile une source en conteneur A3D et l'écrit.
fn compile(source: &str, output: &str, static_body: bool) -> Result<Vec<String>, CliError> {
    let source = Source::open(source, static_body)?;
    let compiled = compile_source(
        &source.bytes,
        source.format,
        source.resolver(),
        &source.options,
    )?;
    write(output, &compiled.bytes)?;
    Ok(summary(&compiled, source.bytes.len(), output))
}

/// Valide un conteneur `.a3d`, ou une source, sans rien écrire.
fn validate(file: &str, static_body: bool) -> Result<Vec<String>, CliError> {
    let limits = A3dLimits::new(max_compiled_bytes());
    let is_container = Path::new(file)
        .extension()
        .and_then(|extension| extension.to_str())
        .is_some_and(|extension| extension.eq_ignore_ascii_case("a3d"));
    if is_container {
        return validate_container(&read(file)?, limits);
    }
    if SourceFormat::from_path(Path::new(file)).is_none() {
        return Err(CliError::Usage(format!(
            "validate juge un conteneur .a3d ou une source .glb, .gltf, .obj, .stl : « {file} »"
        )));
    }
    let source = Source::open(file, static_body)?;
    validate_source(
        &source.bytes,
        source.format,
        source.resolver(),
        &source.options,
        limits,
    )
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
