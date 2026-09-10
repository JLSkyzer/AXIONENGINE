//! Import de sources d'assets (C-21).
//!
//! Un fichier source vient d'un resource pack, donc de n'importe où. Les trois
//! règles qui encadrent sa lecture s'appliquent avant toute autre chose :
//!
//! - **R-533** : une source au-delà de `assets.max_source_bytes` est refusée
//!   *sans lecture complète*. Refuser après avoir lu 4 Gio ne protège de rien ;
//! - **R-531** : aucun chemin absolu, aucune sortie du répertoire de l'asset.
//!   Un `.mtl` ou une texture désignés par `../../../etc/passwd` ne sont pas une
//!   erreur de l'auteur, c'est une tentative ;
//! - **R-532** : les images sont extraites, **jamais décodées ici**. Un
//!   décodeur d'image est la surface d'attaque la plus large qu'un format
//!   d'asset puisse offrir ; celui de Minecraft est déjà là et déjà audité.
//!
//! # Ce que l'import produit
//!
//! Un [`ImportedAsset`] : les structures du modèle, plus les coordonnées de
//! texture **avant** normalisation. C-22 en a besoin — R-142 les borne à
//! `[-8, 9]` avant que l'optimizer ne les ramène dans `[0, 1]` — et une fois
//! quantifiées en `UNORM16` elles ne diraient plus rien.
//!
//! Ce que l'import **ne fait pas** : ni tangentes, ni décomposition convexe, ni
//! optimisation de cache de sommets. C'est C-23, et les mélanger rendrait
//! chacun invérifiable.

mod gltf;
mod gltf_extras;
mod obj;
mod stl;

pub use gltf::{import_gltf, GltfReport, ImageRef, SUPPORTED_EXTENSIONS};
pub use gltf_extras::{NodeAnnotations, NodeRole};
pub use obj::import_obj;
pub use stl::import_stl;

use ax_model::dm::geometry::{MeshDesc, Vertex};
use ax_model::dm::scene::NodeDesc;
use core::fmt;
use std::path::{Component, Path};

/// Format d'une source d'asset (C-21).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SourceFormat {
    /// glTF 2.0 binaire — recommandé, complet.
    Glb,
    /// glTF 2.0 JSON — supporté, complet.
    Gltf,
    /// Wavefront OBJ — supporté, statique.
    Obj,
    /// STL — supporté, géométrie seule.
    Stl,
}

impl SourceFormat {
    /// Reconnaît le format à l'extension du fichier.
    ///
    /// La comparaison est insensible à la casse : un exportateur qui écrit
    /// `.GLB` produit le même fichier.
    #[must_use]
    pub fn from_extension(extension: &str) -> Option<Self> {
        match extension.to_ascii_lowercase().as_str() {
            "glb" => Some(SourceFormat::Glb),
            "gltf" => Some(SourceFormat::Gltf),
            "obj" => Some(SourceFormat::Obj),
            "stl" => Some(SourceFormat::Stl),
            _ => None,
        }
    }

    /// Reconnaît le format au chemin du fichier.
    #[must_use]
    pub fn from_path(path: &Path) -> Option<Self> {
        path.extension()
            .and_then(|extension| extension.to_str())
            .and_then(Self::from_extension)
    }

    /// Nom du format, pour les messages.
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            SourceFormat::Glb => "glb",
            SourceFormat::Gltf => "gltf",
            SourceFormat::Obj => "obj",
            SourceFormat::Stl => "stl",
        }
    }
}

/// Plafonds appliqués à l'import.
///
/// Donnés, jamais codés en dur : la valeur vient de
/// `assets.max_source_bytes`, dont le registre de configuration est la source
/// unique.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ImportLimits {
    max_source_bytes: u64,
}

impl ImportLimits {
    /// Fixe la taille maximale d'une source, en octets.
    #[must_use]
    pub const fn new(max_source_bytes: u64) -> Self {
        Self { max_source_bytes }
    }

    /// Taille maximale en vigueur.
    #[must_use]
    pub const fn max_source_bytes(&self) -> u64 {
        self.max_source_bytes
    }

    /// Vérifie une taille annoncée **avant** de lire quoi que ce soit (R-533).
    ///
    /// # Errors
    ///
    /// [`ImportError::SourceTooLarge`] si la source dépasse le plafond.
    pub fn check_size(&self, format: SourceFormat, len: u64) -> Result<(), ImportError> {
        if len > self.max_source_bytes {
            return Err(ImportError::SourceTooLarge {
                format,
                len,
                limit: self.max_source_bytes,
            });
        }
        Ok(())
    }
}

/// Ce qui empêche d'importer une source.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ImportError {
    /// Extension inconnue.
    UnsupportedFormat(String),
    /// Source au-delà du plafond (R-533, `E-3005`).
    SourceTooLarge {
        /// Format concerné.
        format: SourceFormat,
        /// Taille annoncée.
        len: u64,
        /// Plafond en vigueur.
        limit: u64,
    },
    /// Chemin absolu ou sortant du répertoire de l'asset (R-531, `E-3002`).
    ExternalPath(String),
    /// Extension glTF requise et non supportée (R-530, `E-3003`).
    UnsupportedExtension(String),
    /// Image dans un format autre que PNG (R-532, `E-3004`).
    UnsupportedImage {
        /// Image concernée : son URI, ou « embarquée ».
        designation: String,
        /// Type MIME déclaré.
        mime_type: String,
    },
    /// Source illisible ou mal formée.
    Malformed {
        /// Format concerné.
        format: SourceFormat,
        /// Ce qui cloche.
        detail: String,
    },
    /// Une taille annoncée par l'en-tête ne correspond pas au fichier.
    ///
    /// Séparée de [`ImportError::Malformed`] parce qu'elle est le vecteur
    /// d'attaque le plus simple d'un format binaire : annoncer quatre
    /// milliards de triangles dans un fichier de cent octets.
    DeclaredSizeMismatch {
        /// Format concerné.
        format: SourceFormat,
        /// Ce qui est dénombré.
        what: &'static str,
        /// Nombre annoncé.
        declared: u64,
        /// Nombre que la taille du fichier permet.
        possible: u64,
    },
}

impl ImportError {
    /// Code de l'ANNEXE A.1 correspondant.
    #[must_use]
    pub const fn code(&self) -> i32 {
        match self {
            // `E-3002` : URI externe ou chemin sortant.
            ImportError::ExternalPath(_) => -3002,
            // `E-3003` : extension glTF requise non supportée.
            ImportError::UnsupportedExtension(_) => -3003,
            // `E-3004` : format d'image non supporté.
            ImportError::UnsupportedImage { .. } => -3004,
            // `E-3005` : source trop volumineuse.
            ImportError::SourceTooLarge { .. } => -3005,
            // Le reste refuse une source qu'on ne sait pas lire. L'ANNEXE A.1
            // range les violations de contenu dans la plage de validation ;
            // aucun code n'est inventé (voir ADR-102).
            _ => -3050,
        }
    }
}

impl fmt::Display for ImportError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ImportError::UnsupportedFormat(extension) => {
                write!(formatter, "extension « {extension} » non supportée")
            }
            ImportError::SourceTooLarge { format, len, limit } => write!(
                formatter,
                "source {format:?} de {len} octets, plafond {limit}",
                format = format.name()
            ),
            ImportError::ExternalPath(path) => write!(
                formatter,
                "chemin « {path} » : absolu ou sortant du répertoire de l'asset"
            ),
            ImportError::UnsupportedExtension(extension) => write!(
                formatter,
                "extension glTF « {extension} » requise et non supportée"
            ),
            ImportError::UnsupportedImage {
                designation,
                mime_type,
            } => write!(
                formatter,
                "image « {designation} » de type {mime_type} : seul le PNG est lu"
            ),
            ImportError::Malformed { format, detail } => {
                write!(formatter, "source {} mal formée : {detail}", format.name())
            }
            ImportError::DeclaredSizeMismatch {
                format,
                what,
                declared,
                possible,
            } => write!(
                formatter,
                "source {} : {declared} {what} annoncés, {possible} possibles \
                 au vu de la taille du fichier",
                format.name()
            ),
        }
    }
}

impl std::error::Error for ImportError {}

/// Vérifie qu'un chemin relatif reste sous le répertoire de l'asset (R-531).
///
/// Un `.mtl` ou une texture désignés par `../../../etc/passwd` ne sont pas une
/// erreur de l'auteur : c'est une tentative, et la refuser vaut mieux que la
/// normaliser.
///
/// # Errors
///
/// [`ImportError::ExternalPath`] si le chemin est absolu, remonte, ou désigne
/// une racine.
pub fn check_relative_path(path: &str) -> Result<(), ImportError> {
    let external = || ImportError::ExternalPath(path.to_owned());

    if path.is_empty() {
        return Err(external());
    }
    // Un antislash est un séparateur sur l'une des plateformes supportées :
    // le laisser passer rendrait le contrôle inopérant là-bas.
    if path.contains('\\') {
        return Err(external());
    }
    // Une lettre de lecteur — « C: » — est une racine sans être un `/`.
    if path.len() >= 2 && path.as_bytes()[1] == b':' {
        return Err(external());
    }

    let candidate = Path::new(path);
    for component in candidate.components() {
        match component {
            Component::Normal(_) | Component::CurDir => {}
            Component::ParentDir | Component::RootDir | Component::Prefix(_) => {
                return Err(external())
            }
        }
    }
    Ok(())
}

/// Matériau tel que la source le décrit.
///
/// Les images sont **désignées**, jamais décodées (R-532) : le chemin est
/// conservé, le `ResourceManager` de Minecraft s'en occupe.
#[derive(Debug, Clone, PartialEq)]
pub struct ImportedMaterial {
    /// Nom donné par l'auteur.
    pub name: String,
    /// Couleur de base, RGBA linéaire.
    pub base_color: [f32; 4],
    /// Chemin relatif de la texture de couleur de base, s'il y en a une.
    pub base_color_texture: Option<String>,
}

/// Ce qu'une source produit, avant optimisation.
#[derive(Debug, Default, Clone, PartialEq)]
pub struct ImportedAsset {
    /// Hiérarchie, en ordre topologique.
    pub nodes: Vec<NodeDesc>,
    /// Meshes.
    pub meshes: Vec<MeshDesc>,
    /// Sommets, au format canonique.
    pub vertices: Vec<Vertex>,
    /// Indices.
    pub indices: Vec<u32>,
    /// Coordonnées de texture **avant** normalisation, dans l'ordre des
    /// sommets. C'est sur elles que porte R-142.
    pub raw_uvs: Vec<[f32; 2]>,
    /// Matériaux.
    pub materials: Vec<ImportedMaterial>,
    /// Noms déclarés par la source, par catégorie.
    pub names: Vec<(&'static str, String)>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn t220_les_extensions_sont_reconnues_quelle_que_soit_la_casse() {
        assert_eq!(SourceFormat::from_extension("glb"), Some(SourceFormat::Glb));
        assert_eq!(SourceFormat::from_extension("GLB"), Some(SourceFormat::Glb));
        assert_eq!(
            SourceFormat::from_extension("gltf"),
            Some(SourceFormat::Gltf)
        );
        assert_eq!(SourceFormat::from_extension("Obj"), Some(SourceFormat::Obj));
        assert_eq!(SourceFormat::from_extension("stl"), Some(SourceFormat::Stl));
        assert_eq!(SourceFormat::from_extension("fbx"), None);
        assert_eq!(SourceFormat::from_extension(""), None);

        assert_eq!(
            SourceFormat::from_path(Path::new("models/voiture.GLB")),
            Some(SourceFormat::Glb)
        );
        assert_eq!(SourceFormat::from_path(Path::new("sans_extension")), None);
    }

    #[test]
    fn t221_une_source_trop_volumineuse_est_refusee_avant_lecture() {
        // R-533 : refuser après avoir lu quatre gigaoctets ne protège de rien.
        let limits = ImportLimits::new(1024);
        assert!(limits.check_size(SourceFormat::Stl, 1024).is_ok());

        let refus = limits.check_size(SourceFormat::Stl, 1025).unwrap_err();
        assert_eq!(refus.code(), -3005);
        assert!(matches!(refus, ImportError::SourceTooLarge { .. }));
    }

    #[test]
    fn t222_un_chemin_sortant_est_refuse() {
        // R-531 : ce n'est pas une erreur de l'auteur, c'est une tentative.
        for chemin in [
            "../secret.png",
            "textures/../../secret.png",
            "/etc/passwd",
            "C:/Windows/system32/config",
            "textures\\..\\secret.png",
            "",
        ] {
            let refus =
                check_relative_path(chemin).unwrap_err_or_else(|| panic!("{chemin} accepté"));
            assert_eq!(refus.code(), -3002, "{chemin}");
        }
    }

    #[test]
    fn t222_un_chemin_relatif_normal_est_admis() {
        for chemin in [
            "texture.png",
            "textures/carrosserie.png",
            "./textures/carrosserie.png",
        ] {
            assert!(check_relative_path(chemin).is_ok(), "{chemin} refusé");
        }
    }

    /// Petit confort de lecture pour les tests ci-dessus.
    trait UnwrapErrOr {
        fn unwrap_err_or_else(self, fallback: impl FnOnce() -> ImportError) -> ImportError;
    }

    impl UnwrapErrOr for Result<(), ImportError> {
        fn unwrap_err_or_else(self, fallback: impl FnOnce() -> ImportError) -> ImportError {
            match self {
                Ok(()) => fallback(),
                Err(error) => error,
            }
        }
    }
}
