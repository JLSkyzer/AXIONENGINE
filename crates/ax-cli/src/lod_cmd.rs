//! Commande `lod` : les niveaux de détail qu'un asset recevrait à d'autres ratios.
//!
//! Un aperçu, sans fichier produit — la fiche C-74 ne donne pas de sortie à
//! `lod` : les LOD que porte le conteneur, puis ceux que l'étape 6 de C-23 tirerait
//! de ses meshes sources aux ratios demandés, par le code même de la compilation
//! ([`ax_asset::optimize::preview_lods`], R-830). L'erreur géométrique visée reste
//! celle de la compilation (PARTIE 6.4, `lod.error_target`).

use crate::CliError;
use ax_asset::a3d::{A3dFile, A3dLimits, SectionMask, SectionTag};
use ax_asset::load::decode_sections;
use ax_asset::optimize::{preview_lods, LodOptions, LodTable, MAX_LOD_LEVELS};
use ax_model::dm::geometry::MeshDesc;

/// Combien de meshes sources `lod` détaille avant de s'en tenir au total.
const MESHES_SHOWN: usize = 16;

/// Les LOD du conteneur, puis ceux que les ratios donnés produiraient.
///
/// `ratios` a déjà été contrôlé par l'analyse des arguments : de 1 à
/// [`MAX_LOD_LEVELS`] valeurs dans `]0, 1]`, non croissantes, la première à 1.
///
/// # Errors
///
/// [`CliError::Container`] si le conteneur, `NODE`, `GEOM` ou `LODM` sont
/// invalides.
pub fn lod(bytes: &[u8], limits: A3dLimits, ratios: &[f32]) -> Result<Vec<String>, CliError> {
    let file = A3dFile::open(bytes, limits).map_err(CliError::Container)?;
    let sections = SectionMask::of(&[SectionTag::NODE, SectionTag::GEOM]);
    let decoded = decode_sections(&file, sections).map_err(CliError::Container)?;
    let meshes = &decoded.geometry.meshes;
    let table = match file
        .section(SectionTag::LODM)
        .map_err(CliError::Container)?
    {
        Some(payload) => {
            Some(LodTable::from_bytes(&payload, meshes.len()).map_err(CliError::Container)?)
        }
        None => None,
    };

    // Les LOD générés suivent les sources dans `GEOM`, indices compris : `LODM` a
    // une ligne par mesh source.
    let sources = table
        .as_ref()
        .map_or(meshes.len(), |table| table.rows.len());
    let source_meshes = &meshes[..sources];
    let index_end = source_meshes
        .iter()
        .map(|mesh| mesh.index_offset as usize + mesh.index_count as usize)
        .max()
        .unwrap_or(0);

    let mut padded = [0.0f32; MAX_LOD_LEVELS];
    padded[..ratios.len()].copy_from_slice(ratios);
    let options = LodOptions {
        enabled: true,
        levels: ratios.len() as u8,
        ratios: padded,
        error_target: LodOptions::DEFAULT.error_target,
    };
    let preview = preview_lods(
        &decoded.nodes,
        source_meshes,
        &decoded.geometry.vertices,
        &decoded.geometry.indices[..index_end],
        &options,
    );

    let mut lines = vec![format!(
        "LOD de {sources} mesh(s) source(s), erreur visée {}",
        options.error_target
    )];
    match &table {
        Some(table) => {
            lines.push(format!("  dans le fichier : {} niveau(x)", table.levels));
            describe(table, meshes, &mut lines);
        }
        None => lines.push("  dans le fichier : aucun LOD (pas de section LODM)".to_owned()),
    }
    let shown: Vec<String> = ratios.iter().map(f32::to_string).collect();
    match &preview.table {
        Some(generated) => {
            lines.push(format!("  aux ratios {} :", shown.join(", ")));
            describe(generated, &preview.meshes, &mut lines);
        }
        None => lines.push(format!(
            "  aux ratios {} : aucun LOD généré — aucun niveau ne réduit les meshes",
            shown.join(", ")
        )),
    }
    for warning in &preview.warnings {
        lines.push(format!("  avertissement : {warning}"));
    }
    Ok(lines)
}

/// Triangles de chaque niveau : le total, puis mesh source par mesh source.
fn describe(table: &LodTable, meshes: &[MeshDesc], lines: &mut Vec<String>) {
    let levels = usize::from(table.levels);
    let triangles = |mesh: u32| meshes.get(mesh as usize).map_or(0, |m| m.index_count / 3);
    let mut totals = vec![0u64; levels];
    for row in &table.rows {
        for (total, mesh) in totals.iter_mut().zip(&row[..levels]) {
            *total += u64::from(triangles(*mesh));
        }
    }
    let joined = |values: Vec<String>| values.join(" / ");
    lines.push(format!(
        "    total : {} triangle(s)",
        joined(totals.iter().map(u64::to_string).collect())
    ));
    for (rank, row) in table.rows.iter().enumerate().take(MESHES_SHOWN) {
        lines.push(format!(
            "    mesh {rank} : {}",
            joined(
                row[..levels]
                    .iter()
                    .map(|mesh| triangles(*mesh).to_string())
                    .collect()
            )
        ));
    }
    if table.rows.len() > MESHES_SHOWN {
        lines.push(format!(
            "    … et {} mesh(s) de plus",
            table.rows.len() - MESHES_SHOWN
        ));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::compile_cmd::{compile_source, options};
    use ax_asset::import::SourceFormat;
    use core::fmt::Write as _;

    /// Une grille plane de `cote × cote` carrés, assez fine pour être simplifiée.
    fn grille_obj(cote: u32) -> String {
        let mut obj = String::from("o grille\nvn 0 0 1\n");
        for y in 0..=cote {
            for x in 0..=cote {
                writeln!(obj, "v {x} {y} 0").unwrap();
            }
        }
        for y in 0..cote {
            for x in 0..cote {
                let a = y * (cote + 1) + x + 1;
                let (b, c, d) = (a + 1, a + cote + 2, a + cote + 1);
                writeln!(obj, "f {a}//1 {b}//1 {c}//1").unwrap();
                writeln!(obj, "f {a}//1 {c}//1 {d}//1").unwrap();
            }
        }
        obj
    }

    fn conteneur(source: &str) -> Vec<u8> {
        let opts = options("grille.obj", source.as_bytes(), false);
        compile_source(source.as_bytes(), SourceFormat::Obj, |_| None, &opts)
            .expect("compilation")
            .bytes
    }

    fn limits() -> A3dLimits {
        A3dLimits::new(1 << 22)
    }

    #[test]
    fn t580_lod_montre_les_niveaux_du_fichier_et_ceux_des_ratios() {
        let bytes = conteneur(&grille_obj(20));
        let lignes = lod(&bytes, limits(), &[1.0, 0.5]).unwrap();
        let texte = lignes.join("\n");

        assert!(
            lignes[0].starts_with("LOD de 1 mesh(s) source(s)"),
            "{texte}"
        );
        // La compilation a généré les quatre niveaux de la PARTIE 6.4.
        assert!(texte.contains("dans le fichier : 4 niveau(x)"), "{texte}");
        assert!(texte.contains("aux ratios 1, 0.5 :"), "{texte}");
        // 20 × 20 carrés : 800 triangles au LOD 0, des deux côtés.
        assert!(texte.contains("total : 800 / "), "{texte}");
    }

    #[test]
    fn t580_lod_rejoue_les_ratios_de_la_compilation_a_l_identique() {
        // R-830 : aux ratios de la compilation, l'aperçu donne les LOD que la
        // compilation a écrits.
        let bytes = conteneur(&grille_obj(20));
        let defaut = LodOptions::DEFAULT;
        let ratios = &defaut.ratios[..usize::from(defaut.levels)];
        let lignes = lod(&bytes, limits(), ratios).unwrap();
        let fichier = lignes
            .iter()
            .skip_while(|ligne| !ligne.starts_with("  dans le fichier"))
            .nth(1)
            .expect("total du fichier");
        let apercu = lignes
            .iter()
            .skip_while(|ligne| !ligne.starts_with("  aux ratios"))
            .nth(1)
            .expect("total de l'aperçu");
        assert_eq!(fichier, apercu, "{}", lignes.join("\n"));
    }

    #[test]
    fn t580_lod_d_un_asset_sans_lod() {
        let triangle = "o t\nv 0 0 0\nv 1 0 0\nv 0 1 0\nvn 0 0 1\nf 1//1 2//1 3//1\n";
        let bytes = conteneur(triangle);
        let texte = lod(&bytes, limits(), &[1.0, 0.5]).unwrap().join("\n");
        assert!(texte.contains("aucun LOD (pas de section LODM)"), "{texte}");
        assert!(texte.contains("aucun LOD généré"), "{texte}");
    }
}
