//! Binaire `axion-cli` (C-74) : la couche d'entrées-sorties autour de [`ax_cli`].
//!
//! Toute la logique vit dans la bibliothèque, où elle se teste sans disque.
//! Ici : lire les fichiers, écrire le conteneur, résoudre les références
//! voisines d'une source et son fichier d'options, et traduire un
//! [`ax_cli::CliError`] en code de sortie.

use ax_asset::a3d::A3dLimits;
use ax_asset::compile::CompileOptions;
use ax_asset::import::SourceFormat;
use ax_bench::MeasureConfig;
use ax_cli::{
    bench_asset, compile_source, diff, inspect, is_options_file, lod, max_compiled_bytes, options,
    parse, read_options, sibling_options_path, sibling_path, summary, validate_container,
    validate_options, validate_source, CliError, Command, SourceArgs, PROGRAM, USAGE,
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
        Command::Compile { source, output } => compile(&source, &output),
        Command::Inspect { file } => {
            let bytes = read(&file)?;
            inspect(&bytes, A3dLimits::new(max_compiled_bytes()))
        }
        Command::Diff { a, b } => {
            diff(&read(&a)?, &read(&b)?, A3dLimits::new(max_compiled_bytes()))
        }
        Command::Validate { file } => validate(&file),
        Command::Lod { file, ratios } => {
            lod(&read(&file)?, A3dLimits::new(max_compiled_bytes()), &ratios)
        }
        Command::BenchAsset { source } => {
            let source = Source::open(&source)?;
            let lines = bench_asset(
                &source.bytes,
                source.format,
                source.resolver(),
                &source.options,
                &MeasureConfig::QUICK,
            )?;
            Ok(source.annotate(lines))
        }
    }
}

/// Une source lue, avec ce qu'il faut pour la compiler comme le jeu.
struct Source {
    format: SourceFormat,
    bytes: Vec<u8>,
    options: CompileOptions,
    dir: PathBuf,
    /// Ce que la lecture des options a à dire : le fichier lu, ses avertissements.
    notes: Vec<String>,
}

impl Source {
    /// Lit une source et compose ses options de compilation : celles du fichier
    /// que `--options` désigne, sinon du `<source>.axion.json` voisin s'il existe,
    /// comme le jeu (ADR-135), sinon les défauts.
    fn open(args: &SourceArgs) -> Result<Self, CliError> {
        let source_path = Path::new(&args.path);
        let format = SourceFormat::from_path(source_path).ok_or_else(|| {
            CliError::UnsupportedFormat(
                source_path
                    .extension()
                    .and_then(|extension| extension.to_str())
                    .unwrap_or("")
                    .to_owned(),
            )
        })?;
        let bytes = read(source_path)?;
        let name = source_path
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or(&args.path);
        let mut options = options(name, &bytes, args.static_body);

        let mut notes = Vec::new();
        let chosen = match &args.options {
            Some(explicit) => Some(PathBuf::from(explicit)),
            None => Some(sibling_options_path(source_path)).filter(|sibling| sibling.is_file()),
        };
        if let Some(file) = chosen {
            let parsed = read_options(&read(&file)?)?;
            options.authored = parsed.options;
            notes.push(format!("  options : {}", file.display()));
            notes.extend(
                parsed
                    .warnings
                    .iter()
                    .map(|warning| format!("  avertissement : {warning}")),
            );
        }

        let dir = source_path.parent().unwrap_or(Path::new("")).to_path_buf();
        Ok(Self {
            format,
            bytes,
            options,
            dir,
            notes,
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

    /// Les lignes d'une commande, les notes des options sous la première.
    fn annotate(self, mut lines: Vec<String>) -> Vec<String> {
        let at = lines.len().min(1);
        lines.splice(at..at, self.notes);
        lines
    }
}

/// Compile une source en conteneur A3D et l'écrit.
fn compile(args: &SourceArgs, output: &str) -> Result<Vec<String>, CliError> {
    let source = Source::open(args)?;
    let compiled = compile_source(
        &source.bytes,
        source.format,
        source.resolver(),
        &source.options,
    )?;
    write(output, &compiled.bytes)?;
    let lines = summary(&compiled, source.bytes.len(), output);
    Ok(source.annotate(lines))
}

/// Valide un conteneur `.a3d`, une source ou un fichier d'options, sans rien
/// écrire.
fn validate(args: &SourceArgs) -> Result<Vec<String>, CliError> {
    let path = Path::new(&args.path);
    if is_options_file(path) {
        return validate_options(&read(path)?);
    }
    let limits = A3dLimits::new(max_compiled_bytes());
    let is_container = path
        .extension()
        .and_then(|extension| extension.to_str())
        .is_some_and(|extension| extension.eq_ignore_ascii_case("a3d"));
    if is_container {
        return validate_container(&read(path)?, limits);
    }
    if SourceFormat::from_path(path).is_none() {
        return Err(CliError::Usage(format!(
            "validate juge un conteneur .a3d, une source .glb, .gltf, .obj, .stl ou un \
             fichier .axion.json : « {} »",
            args.path
        )));
    }
    let source = Source::open(args)?;
    let lines = validate_source(
        &source.bytes,
        source.format,
        source.resolver(),
        &source.options,
        limits,
    )?;
    Ok(source.annotate(lines))
}

/// Lit un fichier, en nommant le chemin en cause si la lecture échoue.
fn read(path: impl AsRef<Path>) -> Result<Vec<u8>, CliError> {
    let path = path.as_ref();
    std::fs::read(path).map_err(|error| CliError::Io {
        path: path.display().to_string(),
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
