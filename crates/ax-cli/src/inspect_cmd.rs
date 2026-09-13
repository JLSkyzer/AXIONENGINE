//! Commande `inspect` : ce qu'un conteneur A3D contient.
//!
//! La lecture est celle du jeu : [`ax_asset::a3d::A3dFile`] valide l'en-tête et
//! la table des sections sans rien croire sur parole, puis ce module met en
//! forme ce qu'il trouve. La table des nodes est décodée pour montrer les noms
//! (ADR-110) ; les sections que la chaîne d'aujourd'hui ne produit pas encore
//! — `DEFM`, `STRC` — apparaîtront ici dès qu'elles existeront, sans rien à
//! changer : `inspect` liste ce qui est présent.

use crate::CliError;
use ax_asset::a3d::{decode_nodes, A3dFile, A3dLimits, SectionTag};
use ax_model::config::{find, ConfigScope, ConfigValue};

/// Combien de noms de nodes `inspect` détaille avant de s'en tenir au compte.
const NODE_NAMES_SHOWN: usize = 16;

/// Plafond de taille d'un conteneur, lu dans le registre
/// (`assets.max_compiled_bytes`).
///
/// Source unique de la valeur (R-901) : la coder en dur en créerait une
/// seconde.
#[must_use]
pub fn max_compiled_bytes() -> u64 {
    match find(ConfigScope::Common, "assets.max_compiled_bytes").map(|option| option.default) {
        Some(ConfigValue::Int(value)) if value >= 0 => value as u64,
        _ => unreachable!("assets.max_compiled_bytes doit être un entier positif du registre"),
    }
}

/// Décrit un conteneur A3D, une ligne par fait.
///
/// # Errors
///
/// [`CliError::Container`] si l'en-tête ou la table des sections sont invalides,
/// ou si une section relue est corrompue.
pub fn inspect(bytes: &[u8], limits: A3dLimits) -> Result<Vec<String>, CliError> {
    let file = A3dFile::open(bytes, limits).map_err(CliError::Container)?;
    let header = file.header();

    let mut lines = vec![
        format!("A3D v{}.{}", header.version_major, header.version_minor),
        format!("  asset_id     : {:#018x}", header.asset_id),
        format!("  source_hash  : {:#018x}", header.source_hash),
        format!("  compilateur  : {}", header.compiler_version),
        format!("  taille       : {} octet(s)", header.total_size),
        format!("  sections     : {}", header.section_count),
    ];

    for entry in file.entries() {
        let compression = if entry.is_compressed() {
            format!(
                "zstd {} → {} octet(s)",
                entry.size_compressed, entry.size_uncompressed
            )
        } else {
            format!("{} octet(s)", entry.size_uncompressed)
        };
        lines.push(format!("  [{}] {compression}", entry.tag));

        if entry.tag == SectionTag::NODE {
            describe_nodes(&file, &mut lines)?;
        }
    }
    Ok(lines)
}

/// Détaille la table des nodes : combien, et leurs premiers noms.
fn describe_nodes(file: &A3dFile<'_>, lines: &mut Vec<String>) -> Result<(), CliError> {
    let Some(section) = file
        .section(SectionTag::NODE)
        .map_err(CliError::Container)?
    else {
        return Ok(());
    };
    let table = decode_nodes(&section).map_err(CliError::Container)?;
    lines.push(format!("        {} node(s)", table.nodes.len()));
    for name in table.names.iter().take(NODE_NAMES_SHOWN) {
        // Un node sans nom se voit tout de même : « (sans nom) » distingue un
        // node anonyme d'une ligne oubliée.
        let shown = if name.is_empty() { "(sans nom)" } else { name };
        lines.push(format!("        - {shown}"));
    }
    if table.names.len() > NODE_NAMES_SHOWN {
        lines.push(format!(
            "        … et {} de plus",
            table.names.len() - NODE_NAMES_SHOWN
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::compile_cmd::{compile_source, options};
    use ax_asset::import::SourceFormat;

    const TRIANGLE_OBJ: &str = "\
o triangle
v 0.0 0.0 0.0
v 1.0 0.0 0.0
v 0.0 1.0 0.0
vn 0.0 0.0 1.0
f 1//1 2//1 3//1
";

    fn conteneur() -> Vec<u8> {
        let opts = options("triangle.obj", TRIANGLE_OBJ.as_bytes(), false);
        compile_source(TRIANGLE_OBJ.as_bytes(), SourceFormat::Obj, |_| None, &opts)
            .unwrap()
            .bytes
    }

    #[test]
    fn t580_le_plafond_de_conteneur_vient_du_registre() {
        assert_eq!(max_compiled_bytes(), 268_435_456);
    }

    #[test]
    fn t580_inspect_decrit_en_tete_sections_et_nodes() {
        let bytes = conteneur();
        let lignes = inspect(&bytes, A3dLimits::new(max_compiled_bytes())).unwrap();
        let texte = lignes.join("\n");

        assert!(texte.starts_with("A3D v1.1"), "{texte}");
        assert!(texte.contains("[NODE]"), "{texte}");
        assert!(texte.contains("[GEOM]"), "{texte}");
        // Le nom du node de l'OBJ, décodé depuis la table (ADR-110).
        assert!(texte.contains("- triangle"), "{texte}");
        assert!(texte.contains("1 node(s)"), "{texte}");
    }

    #[test]
    fn t580_un_fichier_qui_n_est_pas_un_a3d_est_refuse() {
        let refus = inspect(b"ce n'est pas un A3D", A3dLimits::new(1 << 20)).unwrap_err();
        assert!(matches!(refus, CliError::Container(_)));
        assert_eq!(refus.exit_code(), 1);
    }
}
