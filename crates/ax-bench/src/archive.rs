//! Validation et archivage d'un resultat de benchmark (R-2230, R-2231).
//!
//! Un resultat n'est archivable qu'une fois sa provenance complete : sans
//! commit, date, processeur ni echantillon, il ne serait pas reproductible, et
//! le citer reviendrait a publier un chiffre sans preuve (R-2230). La validation
//! refuse donc d'ecrire un tel fichier plutot que d'en produire un trompeur.

use std::path::{Path, PathBuf};

use crate::result::{BenchmarkResult, Platform, HARNESS_VERSION_KEY, SCHEMA_VERSION};

/// Raison pour laquelle un resultat ne peut etre archive.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ArchiveError {
    /// Un champ de provenance exige par R-2231 manque ou est vide. Le nom du
    /// champ absent accompagne l'erreur.
    MissingProvenance(&'static str),
    /// Le numero de schema n'est pas [`SCHEMA_VERSION`] ; la valeur trouvee suit.
    WrongSchema(u32),
    /// Aucune execution, ou une execution sans echantillon (R-2231).
    NoSamples,
    /// La serialisation JSON a echoue.
    Serialize(String),
    /// L'ecriture du fichier a echoue.
    Io(String),
}

impl std::fmt::Display for ArchiveError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::MissingProvenance(field) => {
                write!(f, "champ de provenance absent ou vide : {field} (R-2231)")
            }
            Self::WrongSchema(found) => {
                write!(f, "schema {found} inattendu, {SCHEMA_VERSION} attendu")
            }
            Self::NoSamples => write!(f, "aucun echantillon a archiver (R-2231)"),
            Self::Serialize(msg) => write!(f, "serialisation JSON echouee : {msg}"),
            Self::Io(msg) => write!(f, "ecriture echouee : {msg}"),
        }
    }
}

impl std::error::Error for ArchiveError {}

/// Verifie qu'un resultat porte la provenance exigee par R-2231.
///
/// Sont requis : le numero de schema attendu, un identifiant de benchmark, un
/// commit, une date, le systeme, le processeur et un nombre de coeurs non nul,
/// la version du harnais dans `config`, et au moins une execution dont aucune
/// n'est vide. `gpu`, `driver` et `jvm` ne sont **pas** requis : un micro-
/// benchmark CPU tourne sur une machine qui a peut-etre un GPU, mais la mesure
/// ne le sollicite pas (voir [`crate::result::Platform`]).
///
/// Effet : lecture seule. Thread : quelconque. Cout : O(nombre d'executions).
/// Echec : la premiere exigence non satisfaite.
pub fn validate(result: &BenchmarkResult) -> Result<(), ArchiveError> {
    if result.schema != SCHEMA_VERSION {
        return Err(ArchiveError::WrongSchema(result.schema));
    }
    let require = |present: bool, field| {
        if present {
            Ok(())
        } else {
            Err(ArchiveError::MissingProvenance(field))
        }
    };
    require(!result.benchmark.trim().is_empty(), "benchmark")?;
    require(!result.commit.trim().is_empty(), "commit")?;
    require(!result.date.trim().is_empty(), "date")?;
    require(!result.platform.os.trim().is_empty(), "platform.os")?;
    require(!result.platform.cpu.trim().is_empty(), "platform.cpu")?;
    require(result.platform.cores > 0, "platform.cores")?;
    require(
        result.config.contains_key(HARNESS_VERSION_KEY),
        "config.harness_version",
    )?;
    if result.runs.is_empty() || result.runs.iter().any(|r| r.samples_ns.is_empty()) {
        return Err(ArchiveError::NoSamples);
    }
    Ok(())
}

/// Rend un resultat en JSON schema 2, indente et a cles triees.
///
/// Effet : aucun. Thread : quelconque. Cout : O(taille du resultat). Echec :
/// [`ArchiveError::Serialize`] — impossible en pratique pour un resultat bien
/// forme (aucun flottant non fini, aucune cle non-chaine), mais remonte plutot
/// que masquee par une panique.
pub fn to_json(result: &BenchmarkResult) -> Result<String, ArchiveError> {
    serde_json::to_string_pretty(result).map_err(|e| ArchiveError::Serialize(e.to_string()))
}

/// Archive un resultat sous `root`, apres l'avoir valide.
///
/// Le fichier va dans `<root>/<benchmark>/<plateforme>/<date>_<commit>.json`.
/// Le regroupement par benchmark puis par plateforme permet a la
/// non-regression (R-2250) de retrouver le dernier resultat d'une meme
/// plateforme, et le prefixe de date rend l'ordre lexicographique chronologique.
///
/// Effet : cree les dossiers manquants et ecrit un fichier. Thread : quelconque.
/// Cout : une ecriture disque. Echec : la validation (R-2231), la serialisation,
/// ou l'entree-sortie.
pub fn archive(result: &BenchmarkResult, root: &Path) -> Result<PathBuf, ArchiveError> {
    validate(result)?;
    let json = to_json(result)?;

    let dir = root
        .join(&result.benchmark)
        .join(platform_slug(&result.platform));
    std::fs::create_dir_all(&dir).map_err(|e| ArchiveError::Io(e.to_string()))?;

    let name = format!(
        "{}_{}.json",
        slugify(&result.date),
        truncate(&slugify(&result.commit), 12)
    );
    let file = dir.join(name);

    let mut body = json;
    body.push('\n');
    std::fs::write(&file, body.as_bytes()).map_err(|e| ArchiveError::Io(e.to_string()))?;
    Ok(file)
}

/// Slug de plateforme, `<os>-<cpu>`, sur des caracteres surs pour un chemin.
///
/// C'est le sous-dossier ou vivent les resultats d'une meme machine ; la
/// non-regression (R-2250) s'en sert pour ne comparer qu'entre resultats d'une
/// meme plateforme.
///
/// Effet : aucun. Thread : quelconque. Cout : O(taille des chaines).
pub fn platform_slug(platform: &Platform) -> String {
    let slug = slugify(&format!("{}-{}", platform.os, platform.cpu));
    if slug.is_empty() {
        "inconnu".to_string()
    } else {
        slug
    }
}

/// Reduit une chaine a des minuscules alphanumeriques ASCII, tout autre
/// caractere devenant un tiret, sans tiret en bordure.
fn slugify(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        if c.is_ascii_alphanumeric() {
            out.push(c.to_ascii_lowercase());
        } else {
            out.push('-');
        }
    }
    out.trim_matches('-').to_string()
}

/// Tronque une chaine a au plus `max` caracteres, sur une frontiere de
/// caractere.
fn truncate(s: &str, max: usize) -> String {
    s.chars().take(max).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::result::{Platform, Run};
    use std::collections::BTreeMap;
    use std::sync::atomic::{AtomicU64, Ordering};

    fn good_result() -> BenchmarkResult {
        let mut platform = Platform::detect();
        platform.cpu = "CPU de test".to_string();
        BenchmarkResult::new(
            "B-02",
            "0123456789abcdef",
            "2026-09-14T12:00:00Z",
            platform,
            BTreeMap::new(),
            BTreeMap::new(),
            vec![Run {
                samples_ns: vec![10, 20, 30],
            }],
        )
        .unwrap()
    }

    #[test]
    fn t_validate_accepts_complete_result() {
        assert_eq!(validate(&good_result()), Ok(()));
    }

    #[test]
    fn t_validate_rejects_missing_cpu() {
        let mut r = good_result();
        r.platform.cpu = "   ".to_string();
        assert_eq!(
            validate(&r),
            Err(ArchiveError::MissingProvenance("platform.cpu"))
        );
    }

    #[test]
    fn t_validate_rejects_missing_commit() {
        let mut r = good_result();
        r.commit = String::new();
        assert_eq!(validate(&r), Err(ArchiveError::MissingProvenance("commit")));
    }

    #[test]
    fn t_validate_rejects_empty_run() {
        let mut r = good_result();
        r.runs.push(Run { samples_ns: vec![] });
        assert_eq!(validate(&r), Err(ArchiveError::NoSamples));
    }

    #[test]
    fn t_validate_rejects_wrong_schema() {
        let mut r = good_result();
        r.schema = 1;
        assert_eq!(validate(&r), Err(ArchiveError::WrongSchema(1)));
    }

    #[test]
    fn t_validate_rejects_missing_harness_version() {
        let mut r = good_result();
        r.config.remove(HARNESS_VERSION_KEY);
        assert_eq!(
            validate(&r),
            Err(ArchiveError::MissingProvenance("config.harness_version"))
        );
    }

    #[test]
    fn t_json_roundtrips_and_names_schema() {
        let r = good_result();
        let json = to_json(&r).unwrap();
        assert!(json.contains("\"schema\": 2"));
        let back: BenchmarkResult = serde_json::from_str(&json).unwrap();
        assert_eq!(back, r);
    }

    #[test]
    fn t_slugify_is_path_safe() {
        assert_eq!(slugify("2026-09-14T12:00:00Z"), "2026-09-14t12-00-00z");
        assert_eq!(slugify("Intel(R) Core(TM)"), "intel-r--core-tm");
    }

    #[test]
    fn t_archive_writes_parseable_file() {
        static COUNTER: AtomicU64 = AtomicU64::new(0);
        let unique = format!(
            "{}-{}",
            std::process::id(),
            COUNTER.fetch_add(1, Ordering::Relaxed)
        );
        let root = std::env::temp_dir().join(format!("ax-bench-archive-{unique}"));
        let r = good_result();

        let path = archive(&r, &root).unwrap();
        assert!(path.starts_with(&root));
        let text = std::fs::read_to_string(&path).unwrap();
        let back: BenchmarkResult = serde_json::from_str(&text).unwrap();
        assert_eq!(back, r);
        assert!(text.ends_with('\n'));

        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn t_archive_refuses_incomplete() {
        let mut r = good_result();
        r.date = String::new();
        let root = std::env::temp_dir().join("ax-bench-should-not-exist");
        assert_eq!(
            archive(&r, &root),
            Err(ArchiveError::MissingProvenance("date"))
        );
    }
}
