//! Commande `diff` : ce qui distingue deux conteneurs A3D.
//!
//! Les deux fichiers sont relus par le code du jeu (R-830) : [`A3dFile`] pour
//! l'en-tête et la table des sections, les décodeurs d'`ax_asset::a3d` pour le
//! contenu. Les sections se comparent **décompressées** : deux conteneurs qui ne
//! diffèrent que par la compression portent le même asset, et le diff le dit.

use crate::CliError;
use ax_asset::a3d::{
    decode_colliders, decode_geometry, decode_materials, decode_nodes, decode_textures, A3dFile,
    A3dHeader, A3dLimits, DecodedMaterials, DecodedTextures, SectionTag,
};

/// Combien de noms de nodes ajoutés ou retirés `diff` détaille, de chaque côté.
const NODE_NAMES_SHOWN: usize = 16;

/// Compare deux conteneurs. La première ligne dit s'ils sont identiques, portent
/// le même contenu, ou diffèrent ; les suivantes disent en quoi.
///
/// # Errors
///
/// [`CliError::Container`] si l'un des deux n'est pas un conteneur lisible. Une
/// **section** illisible n'arrête pas la comparaison : elle est signalée à sa
/// place.
pub fn diff(a: &[u8], b: &[u8], limits: A3dLimits) -> Result<Vec<String>, CliError> {
    let left = A3dFile::open(a, limits).map_err(CliError::Container)?;
    let right = A3dFile::open(b, limits).map_err(CliError::Container)?;
    if a == b {
        return Ok(vec![
            "identiques : même conteneur, octet pour octet".to_owned()
        ]);
    }

    let mut details = Vec::new();
    let header_differs = header_lines(left.header(), right.header(), &mut details);
    let mut sections_differ = false;
    for tag in tags_of(&left, &right) {
        sections_differ |= section_lines(&left, &right, tag, &mut details);
    }

    let verdict = if header_differs || sections_differ {
        "différents"
    } else {
        "même contenu : seuls la compression ou le placement des sections diffèrent"
    };
    let mut lines = vec![verdict.to_owned()];
    lines.extend(details);
    Ok(lines)
}

/// Compare les champs de l'en-tête ; rend vrai si l'un de ceux qui décrivent
/// l'asset diffère. La taille totale suit la compression : elle est montrée, sans
/// compter comme une différence de contenu.
fn header_lines(a: &A3dHeader, b: &A3dHeader, lines: &mut Vec<String>) -> bool {
    let mut differs = false;
    let mut field = |name: &str, left: String, right: String, content: bool| {
        if left != right {
            lines.push(format!("  {name} : {left} → {right}"));
            differs |= content;
        }
    };
    field(
        "version",
        format!("{}.{}", a.version_major, a.version_minor),
        format!("{}.{}", b.version_major, b.version_minor),
        true,
    );
    field(
        "drapeaux",
        format!("{:#x}", a.flags),
        format!("{:#x}", b.flags),
        true,
    );
    field(
        "asset_id",
        format!("{:#018x}", a.asset_id),
        format!("{:#018x}", b.asset_id),
        true,
    );
    field(
        "source_hash",
        format!("{:#018x}", a.source_hash),
        format!("{:#018x}", b.source_hash),
        true,
    );
    field(
        "compilateur",
        a.compiler_version.to_string(),
        b.compiler_version.to_string(),
        true,
    );
    field(
        "taille",
        format!("{} octet(s)", a.total_size),
        format!("{} octet(s)", b.total_size),
        false,
    );
    differs
}

/// Les tags présents dans l'un ou l'autre fichier : ceux du premier dans son
/// ordre, puis ceux que seul le second porte.
fn tags_of(a: &A3dFile<'_>, b: &A3dFile<'_>) -> Vec<SectionTag> {
    let mut tags: Vec<SectionTag> = a.entries().iter().map(|entry| entry.tag).collect();
    for entry in b.entries() {
        if !tags.contains(&entry.tag) {
            tags.push(entry.tag);
        }
    }
    tags
}

/// Compare une section ; rend vrai si elle diffère.
fn section_lines(
    a: &A3dFile<'_>,
    b: &A3dFile<'_>,
    tag: SectionTag,
    lines: &mut Vec<String>,
) -> bool {
    let left = a.section(tag);
    let right = b.section(tag);
    match (left, right) {
        (Ok(Some(left)), Ok(Some(right))) => {
            if left == right {
                lines.push(format!("  [{tag}] identique"));
                false
            } else {
                lines.push(format!(
                    "  [{tag}] diffère : {} → {} octet(s)",
                    left.len(),
                    right.len()
                ));
                detail(tag, &left, &right, lines);
                true
            }
        }
        (Ok(Some(_)), Ok(None)) => {
            lines.push(format!("  [{tag}] seulement dans A"));
            true
        }
        (Ok(None), Ok(Some(_))) => {
            lines.push(format!("  [{tag}] seulement dans B"));
            true
        }
        // Absente des deux : impossible, le tag vient de l'une des tables.
        (Ok(None), Ok(None)) => false,
        (left, right) => {
            if let Err(error) = left {
                lines.push(format!("  [{tag}] illisible dans A : {error}"));
            }
            if let Err(error) = right {
                lines.push(format!("  [{tag}] illisible dans B : {error}"));
            }
            true
        }
    }
}

/// Ce qui a changé dans une section qui diffère, par son décodeur.
fn detail(tag: SectionTag, a: &[u8], b: &[u8], lines: &mut Vec<String>) {
    let counts: Result<Vec<(&str, usize, usize)>, String> = match tag {
        SectionTag::NODE => return node_detail(a, b, lines),
        SectionTag::GEOM => both(a, b, decode_geometry).map(|(a, b)| {
            vec![
                ("meshes", a.meshes.len(), b.meshes.len()),
                ("sommets", a.vertices.len(), b.vertices.len()),
                ("indices", a.indices.len(), b.indices.len()),
            ]
        }),
        SectionTag::PHYS => both(a, b, decode_colliders).map(|(a, b)| {
            vec![
                ("colliders", a.0.len(), b.0.len()),
                ("points d'enveloppe", a.1.len(), b.1.len()),
                ("enfants de compound", a.2.len(), b.2.len()),
            ]
        }),
        SectionTag::MATL => both(a, b, decode_materials)
            .map(|(a, b)| vec![("matériaux", materials_of(&a), materials_of(&b))]),
        SectionTag::TEXR => both(a, b, decode_textures).map(|(a, b)| {
            let (a_entries, a_blob) = textures_of(&a);
            let (b_entries, b_blob) = textures_of(&b);
            vec![
                ("textures", a_entries, b_entries),
                ("octets d'images et de chemins", a_blob, b_blob),
            ]
        }),
        // Les autres sections ne se décrivent que par leur taille.
        _ => return,
    };
    match counts {
        Ok(counts) => {
            let changed: Vec<String> = counts
                .iter()
                .filter(|(_, left, right)| left != right)
                .map(|(name, left, right)| format!("{name} : {left} → {right}"))
                .collect();
            if changed.is_empty() {
                lines.push("        mêmes dénombrements, contenu différent".to_owned());
            } else {
                lines.push(format!("        {}", changed.join(" ; ")));
            }
        }
        Err(error) => lines.push(format!("        {error}")),
    }
}

/// Décode une section des deux côtés ; l'erreur dit lequel a échoué.
fn both<T, E: core::fmt::Display>(
    a: &[u8],
    b: &[u8],
    decode: impl Fn(&[u8]) -> Result<T, E>,
) -> Result<(T, T), String> {
    let left = decode(a).map_err(|error| format!("illisible dans A : {error}"))?;
    let right = decode(b).map_err(|error| format!("illisible dans B : {error}"))?;
    Ok((left, right))
}

/// Nombre de matériaux d'une section `MATL` de disposition connue ; zéro sinon,
/// comme le jeu, qui ignore une disposition inconnue (ADR-122 §1).
fn materials_of(decoded: &DecodedMaterials) -> usize {
    match decoded {
        DecodedMaterials::Materials(materials) => materials.len(),
        DecodedMaterials::UnknownLayout(_) => 0,
    }
}

/// Entrées et taille du blob d'une section `TEXR` de disposition connue.
fn textures_of(decoded: &DecodedTextures) -> (usize, usize) {
    match decoded {
        DecodedTextures::Textures(table) => (table.entries.len(), table.blob.len()),
        DecodedTextures::UnknownLayout(_) => (0, 0),
    }
}

/// Les nodes : leur nombre, et les noms qui n'existent que d'un côté.
fn node_detail(a: &[u8], b: &[u8], lines: &mut Vec<String>) {
    let (left, right) = match both(a, b, decode_nodes) {
        Ok(tables) => tables,
        Err(error) => {
            lines.push(format!("        {error}"));
            return;
        }
    };
    if left.nodes.len() != right.nodes.len() {
        lines.push(format!(
            "        nodes : {} → {}",
            left.nodes.len(),
            right.nodes.len()
        ));
    }
    let removed: Vec<&String> = left
        .names
        .iter()
        .filter(|name| !right.names.contains(name))
        .collect();
    let added: Vec<&String> = right
        .names
        .iter()
        .filter(|name| !left.names.contains(name))
        .collect();
    for (sign, names) in [('-', removed), ('+', added)] {
        for name in names.iter().take(NODE_NAMES_SHOWN) {
            let shown = if name.is_empty() { "(sans nom)" } else { name };
            lines.push(format!("        {sign} {shown}"));
        }
        if names.len() > NODE_NAMES_SHOWN {
            lines.push(format!(
                "        {sign} … et {} de plus",
                names.len() - NODE_NAMES_SHOWN
            ));
        }
    }
    if left.nodes.len() == right.nodes.len() && left.names == right.names {
        lines.push("        mêmes noms, contenu différent".to_owned());
    }
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

    const CARRE_OBJ: &str = "\
o carre
v 0.0 0.0 0.0
v 1.0 0.0 0.0
v 1.0 1.0 0.0
v 0.0 1.0 0.0
vn 0.0 0.0 1.0
f 1//1 2//1 3//1
f 1//1 3//1 4//1
";

    fn conteneur(name: &str, source: &str) -> Vec<u8> {
        let opts = options(name, source.as_bytes(), false);
        compile_source(source.as_bytes(), SourceFormat::Obj, |_| None, &opts)
            .expect("compilation")
            .bytes
    }

    fn limits() -> A3dLimits {
        A3dLimits::new(1 << 20)
    }

    #[test]
    fn t580_deux_conteneurs_egaux_sont_identiques() {
        let bytes = conteneur("triangle.obj", TRIANGLE_OBJ);
        let lignes = diff(&bytes, &bytes, limits()).unwrap();
        assert_eq!(
            lignes,
            vec!["identiques : même conteneur, octet pour octet"]
        );
    }

    #[test]
    fn t580_le_diff_dit_l_en_tete_et_les_sections_qui_changent() {
        let a = conteneur("triangle.obj", TRIANGLE_OBJ);
        let b = conteneur("carre.obj", CARRE_OBJ);
        let lignes = diff(&a, &b, limits()).unwrap();
        let texte = lignes.join("\n");

        assert_eq!(lignes[0], "différents", "{texte}");
        assert!(texte.contains("asset_id : "), "{texte}");
        assert!(texte.contains("source_hash : "), "{texte}");
        assert!(texte.contains("[GEOM] diffère"), "{texte}");
        assert!(texte.contains("sommets : 3 → 4"), "{texte}");
        assert!(texte.contains("indices : 3 → 6"), "{texte}");
        // Les noms des nodes, décodés des deux côtés.
        assert!(texte.contains("- triangle"), "{texte}");
        assert!(texte.contains("+ carre"), "{texte}");
    }

    #[test]
    fn t580_un_meme_asset_sous_un_autre_nom_ne_differe_que_par_l_en_tete() {
        // Même source, autre nom : l'asset_id change, aucune section.
        let a = conteneur("triangle.obj", TRIANGLE_OBJ);
        let b = conteneur("autre.obj", TRIANGLE_OBJ);
        let lignes = diff(&a, &b, limits()).unwrap();
        let texte = lignes.join("\n");

        assert_eq!(lignes[0], "différents", "{texte}");
        assert!(texte.contains("asset_id : "), "{texte}");
        assert!(!texte.contains("source_hash"), "{texte}");
        assert!(texte.contains("[GEOM] identique"), "{texte}");
        assert!(texte.contains("[NODE] identique"), "{texte}");
        assert!(!texte.contains("diffère"), "{texte}");
    }

    #[test]
    fn t580_un_fichier_qui_n_est_pas_un_a3d_est_refuse() {
        let bytes = conteneur("triangle.obj", TRIANGLE_OBJ);
        let refus = diff(&bytes, b"pas un A3D", limits()).unwrap_err();
        assert!(matches!(refus, CliError::Container(_)));
        assert_eq!(refus.exit_code(), 1);
    }
}
