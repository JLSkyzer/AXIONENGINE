//! C-74 — `axion-cli`, l'outil en ligne de commande d'AXION.
//!
//! La CLI **partage exactement** le code du jeu (R-830) : `compile` appelle
//! [`ax_asset::compile::compile`], `inspect` relit un conteneur par
//! [`ax_asset::a3d::A3dFile`]. Aucun chemin parallèle, aucune divergence
//! possible entre ce que produit l'outil et ce que produit le moteur.
//!
//! # Ce que porte cette bibliothèque
//!
//! Tout ce qui se teste sans toucher au disque : l'analyse des arguments, la
//! compilation d'octets en octets, la description d'un conteneur. Le binaire
//! ([`crate::main`]) n'ajoute que la lecture et l'écriture des fichiers, et la
//! résolution des références voisines d'une source.
//!
//! # Sous-commandes
//!
//! `compile`, `inspect`, `diff`, `validate` et `lod` sont celles que la chaîne
//! d'assets d'aujourd'hui (C-21..C-24) permet. `deform` et `replay` dépendent de
//! composants de la M6 (C-28, C-41, C-42) et viendront avec eux ; elles ne sont
//! pas encore déclarées, plutôt que reconnues et refusées.

mod args;
mod compile_cmd;
mod diff_cmd;
mod inspect_cmd;
mod lod_cmd;
mod paths;
mod validate_cmd;

pub use args::{parse, Command};
pub use compile_cmd::{
    asset_id_of, compile_source, max_source_bytes, options, source_hash, summary, CompiledOutput,
};
pub use diff_cmd::diff;
pub use inspect_cmd::{inspect, max_compiled_bytes};
pub use lod_cmd::lod;
pub use paths::sibling_path;
pub use validate_cmd::{validate_container, validate_source};

use ax_asset::a3d::A3dError;
use ax_asset::compile::CompileError;
use ax_scene::SceneError;
use core::fmt;

/// Nom du programme, tel qu'il apparaît dans l'aide.
pub const PROGRAM: &str = "axion-cli";

/// Texte d'aide, listant les sous-commandes disponibles.
pub const USAGE: &str = "\
axion-cli — outil de la chaîne d'assets AXION (C-74)

USAGE :
    axion-cli compile <source> -o <sortie.a3d> [--static-body]
    axion-cli inspect <fichier.a3d>
    axion-cli diff <a.a3d> <b.a3d>
    axion-cli validate <fichier.a3d | source> [--static-body]
    axion-cli lod <fichier.a3d> --ratios 1,0.5,0.25
    axion-cli help

COMMANDES :
    compile   Compile une source (.glb .gltf .obj .stl) en conteneur A3D,
              par le même pipeline que le jeu (R-830).
    inspect   Décrit un conteneur A3D : en-tête, sections, table des nodes.
    diff      Compare deux conteneurs A3D : en-tête, puis chaque section,
              décompressée et décodée.
    validate  Juge un fichier comme le jeu : un .a3d par son chargement, une
              source par sa compilation puis le chargement du résultat.
              Rien n'est écrit.
    lod       Montre les LOD que porte un conteneur, puis ceux que la
              compilation produirait aux ratios donnés (LOD 0 compris).
    help      Affiche cette aide.

Les commandes deform et replay arrivent avec la déformation (M6).";

/// Ce qui empêche une commande d'aboutir.
#[derive(Debug, Clone, PartialEq)]
pub enum CliError {
    /// Les arguments ne forment pas une commande valide.
    Usage(String),
    /// Une source d'extension inconnue.
    UnsupportedFormat(String),
    /// La compilation a refusé la source (C-21..C-24).
    Compile(CompileError),
    /// La lecture d'un conteneur a échoué (C-24).
    Container(A3dError),
    /// La hiérarchie d'un conteneur ne se pose pas au repos (C-30).
    Scene(SceneError),
    /// Une entrée-sortie a échoué, avec le chemin en cause.
    Io {
        /// Fichier concerné.
        path: String,
        /// Message du système.
        detail: String,
    },
}

impl CliError {
    /// Code de sortie du processus : `0` en succès, `2` pour un mésusage,
    /// `1` pour tout échec de traitement.
    ///
    /// Un mésusage se distingue d'une erreur de traitement : le premier se
    /// corrige en relisant la ligne de commande, le second en corrigeant la
    /// source ou le fichier.
    #[must_use]
    pub fn exit_code(&self) -> i32 {
        match self {
            CliError::Usage(_) => 2,
            _ => 1,
        }
    }
}

impl fmt::Display for CliError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            CliError::Usage(message) => write!(formatter, "{message}"),
            CliError::UnsupportedFormat(extension) => write!(
                formatter,
                "extension « {extension} » inconnue : .glb, .gltf, .obj ou .stl attendus"
            ),
            CliError::Compile(error) => write!(formatter, "compilation : {error}"),
            CliError::Container(error) => write!(formatter, "conteneur : {error}"),
            CliError::Scene(error) => write!(formatter, "hiérarchie : {error}"),
            CliError::Io { path, detail } => write!(formatter, "{path} : {detail}"),
        }
    }
}

impl std::error::Error for CliError {}
