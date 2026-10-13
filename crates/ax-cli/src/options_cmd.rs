//! Options de compilation dans la CLI : le `<source>.axion.json` voisin, que le
//! jeu lit aussi (ADR-135), ou celui que `--options` désigne ; et `validate` d'un
//! fichier d'options seul.
//!
//! La lecture est celle du jeu, [`ax_asset::options::parse_bytes`] (R-830).

use crate::CliError;
use ax_asset::options::{parse_bytes, AssetOptions, NormalsMode, ParsedOptions, UpAxis};
use std::path::{Path, PathBuf};

/// Suffixe d'un fichier d'options.
pub const OPTIONS_SUFFIX: &str = ".axion.json";

/// Le fichier d'options voisin d'une source : même répertoire, même nom sans
/// l'extension de la source — `voiture.glb` → `voiture.axion.json`. Le jeu suit
/// la même règle (ADR-135, point 1).
#[must_use]
pub fn sibling_options_path(source: &Path) -> PathBuf {
    let mut name = source
        .file_stem()
        .map(ToOwned::to_owned)
        .unwrap_or_default();
    name.push(OPTIONS_SUFFIX);
    source.with_file_name(name)
}

/// Un chemin désigne-t-il un fichier d'options ?
#[must_use]
pub fn is_options_file(path: &Path) -> bool {
    path.file_name()
        .and_then(|name| name.to_str())
        .is_some_and(|name| name.to_ascii_lowercase().ends_with(OPTIONS_SUFFIX))
}

/// Lit un fichier d'options par le lecteur du jeu.
///
/// # Errors
///
/// [`CliError::Options`] si le fichier est refusé : toutes ses fautes, à leur
/// chemin JSON.
pub fn read_options(bytes: &[u8]) -> Result<ParsedOptions, CliError> {
    parse_bytes(bytes).map_err(CliError::Options)
}

/// Valide un fichier d'options seul : les options qu'il donne, défauts compris.
///
/// # Errors
///
/// [`CliError::Options`] si le fichier est refusé.
pub fn validate_options(bytes: &[u8]) -> Result<Vec<String>, CliError> {
    let parsed = read_options(bytes)?;
    let mut lines = vec!["valide : options de compilation, schéma 1 (PARTIE 6.4)".to_owned()];
    lines.extend(describe_options(&parsed.options));
    lines.extend(
        parsed
            .warnings
            .iter()
            .map(|warning| format!("  avertissement : {warning}")),
    );
    Ok(lines)
}

/// Les options en vigueur, une ligne par famille.
#[must_use]
pub fn describe_options(options: &AssetOptions) -> Vec<String> {
    let oui = |flag: bool| if flag { "oui" } else { "non" };
    let lod = &options.lod;
    let ratios: Vec<String> = lod.ratios[..usize::from(lod.levels)]
        .iter()
        .map(f32::to_string)
        .collect();
    vec![
        format!(
            "  scale {} · up_axis {}",
            options.scale,
            match options.up_axis {
                UpAxis::Y => "y",
                UpAxis::Z => "z",
            }
        ),
        format!(
            "  normales {} · tangentes {}",
            match options.generate_normals {
                NormalsMode::IfMissing => "if_missing",
                NormalsMode::Always => "always",
            },
            oui(options.generate_tangents)
        ),
        if lod.enabled {
            format!(
                "  lod : {} niveau(x), ratios {}, erreur visée {}",
                lod.levels,
                ratios.join(", "),
                lod.error_target
            )
        } else {
            "  lod : désactivés".to_owned()
        },
        format!(
            "  collision : {} (au plus {} enveloppe(s))",
            options.collision.mode.name(),
            options.collision.max_hulls
        ),
        format!(
            "  matériau par défaut : rugosité {}, métal {}",
            options.materials.roughness, options.materials.metallic
        ),
        format!(
            "  compression {} · max_texture_size {}",
            oui(options.compress),
            options.max_texture_size
        ),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;
    use ax_asset::options::E_INVALID_OPTION;

    #[test]
    fn t580_le_fichier_d_options_voisin_porte_le_nom_de_la_source() {
        assert_eq!(
            sibling_options_path(Path::new("assets/voiture/voiture.glb")),
            Path::new("assets/voiture/voiture.axion.json")
        );
        assert_eq!(
            sibling_options_path(Path::new("caisse.obj")),
            Path::new("caisse.axion.json")
        );
        assert!(is_options_file(Path::new("voiture.axion.json")));
        assert!(is_options_file(Path::new("VOITURE.AXION.JSON")));
        assert!(!is_options_file(Path::new("voiture.json")));
        assert!(!is_options_file(Path::new("voiture.glb")));
    }

    #[test]
    fn t580_validate_d_un_fichier_d_options_montre_les_options_en_vigueur() {
        let lignes = validate_options(
            br#"{ "schema": 1, "scale": 0.5, "up_axis": "z",
                  "collision": { "mode": "auto_box" }, "deformation": {} }"#,
        )
        .unwrap();
        let texte = lignes.join("\n");
        assert!(
            lignes[0].starts_with("valide : options de compilation"),
            "{texte}"
        );
        assert!(texte.contains("scale 0.5 · up_axis z"), "{texte}");
        assert!(texte.contains("collision : auto_box"), "{texte}");
        assert!(
            texte.contains("lod : 4 niveau(x), ratios 1, 0.5, 0.25, 0.1"),
            "{texte}"
        );
        // `deformation` est validée, et signalée sans effet.
        assert!(
            texte.contains("avertissement : options « deformation »"),
            "{texte}"
        );
    }

    #[test]
    fn t580_un_fichier_d_options_fautif_est_refuse_avec_ses_fautes() {
        let refus = validate_options(br#"{ "schema": 1, "scal": 2 }"#).unwrap_err();
        let CliError::Options(erreur) = &refus else {
            panic!("refus inattendu : {refus}");
        };
        assert_eq!(erreur.code(), E_INVALID_OPTION);
        assert_eq!(refus.exit_code(), 1);
        assert!(refus.to_string().contains("$.scal"), "{refus}");
    }
}
