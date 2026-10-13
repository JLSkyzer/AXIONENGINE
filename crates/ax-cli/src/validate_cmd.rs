//! Commande `validate` : ce que le jeu accepterait.
//!
//! Chaque nature de fichier est jugée par le code même du jeu (R-830) :
//!
//! - un conteneur `.a3d`, par ce que le chargement en fait : la table des
//!   sections ([`A3dFile`]), les sections du rendu
//!   ([`ax_asset::load::decode_sections`]), la pose au repos de la hiérarchie
//!   ([`ax_scene::rest_draws`]), et la section `PHYS` telle que le serveur la
//!   décode. Un asset livré compilé ne saute jamais la validation (R-861) ;
//! - une source, par la compilation entière, sans rien écrire — import,
//!   contrôle, optimisation, contrôle de la sortie —, puis par le chargement du
//!   conteneur qu'elle produit, comme le jeu le chargerait.
//!
//! La restriction des formes d'un corps dynamique (INV-13) dépend de la
//! definition qui porte l'asset : elle se juge à la création de l'assembly, pas
//! sur l'asset seul.

use crate::compile_cmd::{compile_details, compile_source};
use crate::CliError;
use ax_asset::a3d::{decode_colliders, A3dFile, A3dLimits, SectionTag};
use ax_asset::compile::CompileOptions;
use ax_asset::import::SourceFormat;
use ax_asset::load::{decode_sections, render_sections};
use ax_scene::rest_draws;

/// Sections que le chargement d'aujourd'hui lit : celles du rendu, et `PHYS`.
const READ_SECTIONS: [SectionTag; 5] = [
    SectionTag::NODE,
    SectionTag::GEOM,
    SectionTag::MATL,
    SectionTag::TEXR,
    SectionTag::PHYS,
];

/// Valide un conteneur A3D comme le jeu le charge ; une ligne par fait.
///
/// # Errors
///
/// [`CliError::Container`] si le conteneur ou une section est invalide ;
/// [`CliError::Scene`] si la hiérarchie ne se laisse pas poser au repos.
pub fn validate_container(bytes: &[u8], limits: A3dLimits) -> Result<Vec<String>, CliError> {
    let file = A3dFile::open(bytes, limits).map_err(CliError::Container)?;
    let decoded = decode_sections(&file, render_sections()).map_err(CliError::Container)?;
    // Le rendu charge `NODE` et `GEOM` ensemble : la pose au repos se calcule
    // toujours, sur des tables vides si les sections manquent (R-880).
    let draws =
        rest_draws(&decoded.nodes, decoded.geometry.meshes.len()).map_err(CliError::Scene)?;
    let physics = match file
        .section(SectionTag::PHYS)
        .map_err(CliError::Container)?
    {
        Some(payload) => Some(decode_colliders(&payload).map_err(CliError::Container)?),
        None => None,
    };

    let header = file.header();
    let mut lines = vec![
        format!(
            "valide : conteneur A3D v{}.{}, chargé comme le jeu le charge",
            header.version_major, header.version_minor
        ),
        format!(
            "  nodes : {}, {} dessin(s) au repos",
            decoded.nodes.len(),
            draws.len()
        ),
        format!(
            "  géométrie : {} mesh(s), {} sommet(s), {} indice(s)",
            decoded.geometry.meshes.len(),
            decoded.geometry.vertices.len(),
            decoded.geometry.indices.len()
        ),
        format!(
            "  matériaux : {}, textures : {}",
            decoded.materials.len(),
            decoded.textures.entries.len()
        ),
    ];
    match physics {
        Some((colliders, hull_points, compound_children)) => lines.push(format!(
            "  physique : {} collider(s), {} point(s) d'enveloppe, {} enfant(s) de compound",
            colliders.len(),
            hull_points.len(),
            compound_children.len()
        )),
        None => lines.push("  physique : aucune section PHYS".to_owned()),
    }
    // Une section que le chargement ne lit pas n'est pas une faute (R-880) : elle
    // est nommée, pour que l'auteur sache qu'elle n'a pas été jugée.
    for entry in file.entries() {
        if !READ_SECTIONS.contains(&entry.tag) {
            lines.push(format!(
                "  [{}] présente, non lue par le chargement d'aujourd'hui",
                entry.tag
            ));
        }
    }
    Ok(lines)
}

/// Valide une source : la compilation entière du jeu, puis le chargement du
/// conteneur produit. Rien n'est écrit.
///
/// # Errors
///
/// [`CliError::Compile`] si la source est refusée ; les erreurs de
/// [`validate_container`] si le conteneur produit ne se chargerait pas.
pub fn validate_source(
    source: &[u8],
    format: SourceFormat,
    resolve: impl FnMut(&str) -> Option<Vec<u8>>,
    options: &CompileOptions,
    limits: A3dLimits,
) -> Result<Vec<String>, CliError> {
    let compiled = compile_source(source, format, resolve, options)?;
    let mut lines = vec!["valide : source compilable par le jeu ; rien n'est écrit".to_owned()];
    lines.extend(compile_details(&compiled, source.len()));
    let loaded = validate_container(&compiled.bytes, limits)?;
    lines.push("  le conteneur produit :".to_owned());
    lines.extend(loaded.into_iter().skip(1).map(|line| format!("  {line}")));
    Ok(lines)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::compile_cmd::options;
    use ax_asset::a3d::{decode_nodes, encode_nodes, A3dWriter};

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

    fn limits() -> A3dLimits {
        A3dLimits::new(1 << 20)
    }

    fn conteneur() -> Vec<u8> {
        let opts = options("carre.obj", CARRE_OBJ.as_bytes(), false);
        compile_source(CARRE_OBJ.as_bytes(), SourceFormat::Obj, |_| None, &opts)
            .expect("compilation")
            .bytes
    }

    #[test]
    fn t580_un_conteneur_compile_est_valide() {
        let lignes = validate_container(&conteneur(), limits()).unwrap();
        let texte = lignes.join("\n");
        assert!(
            lignes[0].starts_with("valide : conteneur A3D v1.1"),
            "{texte}"
        );
        assert!(texte.contains("nodes : 1, 1 dessin(s) au repos"), "{texte}");
        assert!(
            texte.contains("1 mesh(s), 4 sommet(s), 6 indice(s)"),
            "{texte}"
        );
        assert!(texte.contains("aucune section PHYS"), "{texte}");
    }

    #[test]
    fn t580_une_section_corrompue_est_refusee() {
        let mut bytes = conteneur();
        let file = A3dFile::open(&bytes, limits()).unwrap();
        let geom = file
            .entries()
            .iter()
            .find(|entry| entry.tag == SectionTag::GEOM)
            .expect("section GEOM")
            .offset as usize;
        // Un octet de la charge utile changé : son CRC32C ne correspond plus.
        bytes[geom] ^= 0xFF;
        let refus = validate_container(&bytes, limits()).unwrap_err();
        assert!(matches!(refus, CliError::Container(_)), "{refus}");
        assert_eq!(refus.exit_code(), 1);
    }

    #[test]
    fn t580_une_hierarchie_que_le_jeu_ne_poserait_pas_est_refusee() {
        // Le node désigne un mesh que la géométrie n'a pas : le chargement du jeu
        // le refuse en posant la hiérarchie au repos, `validate` aussi.
        let bytes = conteneur();
        let file = A3dFile::open(&bytes, limits()).unwrap();
        let header = file.header();
        let mut table = decode_nodes(&file.section(SectionTag::NODE).unwrap().unwrap()).unwrap();
        table.nodes[0].mesh = 7;
        let mut writer =
            A3dWriter::new(header.asset_id, header.source_hash, header.compiler_version);
        for entry in file.entries() {
            let payload = if entry.tag == SectionTag::NODE {
                encode_nodes(&table.nodes, &table.names).unwrap()
            } else {
                file.section(entry.tag).unwrap().unwrap()
            };
            writer.section(entry.tag, &payload).unwrap();
        }
        let fautif = writer.finish().unwrap();

        let refus = validate_container(&fautif, limits()).unwrap_err();
        assert!(matches!(refus, CliError::Scene(_)), "{refus}");
    }

    #[test]
    fn t580_une_source_est_compilee_puis_chargee_sans_rien_ecrire() {
        let opts = options("carre.obj", CARRE_OBJ.as_bytes(), false);
        let lignes = validate_source(
            CARRE_OBJ.as_bytes(),
            SourceFormat::Obj,
            |_| None,
            &opts,
            limits(),
        )
        .unwrap();
        let texte = lignes.join("\n");
        assert!(
            lignes[0].starts_with("valide : source compilable"),
            "{texte}"
        );
        assert!(texte.contains("4 sommet(s), 1 mesh(s)"), "{texte}");
        assert!(texte.contains("le conteneur produit"), "{texte}");
        assert!(texte.contains("1 dessin(s) au repos"), "{texte}");
    }

    #[test]
    fn t580_une_source_refusee_n_est_pas_valide() {
        // Un OBJ qui n'est pas de l'UTF-8 est refusé à l'import.
        let source: &[u8] = b"\xff\xfe pas de l'utf8";
        let opts = options("illisible.obj", source, false);
        let refus =
            validate_source(source, SourceFormat::Obj, |_| None, &opts, limits()).unwrap_err();
        assert!(matches!(refus, CliError::Compile(_)), "{refus}");
    }

    #[test]
    fn t580_un_objet_sans_face_compile_un_conteneur_que_le_jeu_charge() {
        // Trouvé par `validate` : le compilateur acceptait un objet sans face mais
        // n'écrivait pas `GEOM`, et le node désignait un mesh absent, que le
        // chargement refusait. Le compilateur et le chargement doivent s'accorder.
        let source: &[u8] = b"o vide\n";
        let opts = options("vide.obj", source, false);
        let lignes = validate_source(source, SourceFormat::Obj, |_| None, &opts, limits())
            .expect("le conteneur produit se charge");
        let texte = lignes.join("\n");
        assert!(
            texte.contains("1 mesh(s), 0 sommet(s), 0 indice(s)"),
            "{texte}"
        );
    }
}
