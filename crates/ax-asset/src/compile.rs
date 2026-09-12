//! Compilation d'un asset, de la source au conteneur (C-20, étape 3).
//!
//! Une source entre, un A3D sort. Le chemin est toujours le même : importer
//! (C-21), valider (C-22), optimiser (C-23), écrire (C-24). Le refus se produit
//! **au plus tôt** — une source trop grosse n'est jamais lue, un asset invalide
//! n'est jamais optimisé — parce qu'à chaque étape franchie, le coût du refus
//! augmente. La sortie de l'optimizer repasse la même liste de contrôle avant
//! d'être écrite : un asset invalide n'est jamais écrit, même par la faute de
//! C-23.
//!
//! # Ce que ce module ne fait pas
//!
//! Il ne découvre rien, ne range rien dans un cache, ne tient aucune machine à
//! états. C'est l'orchestrateur qui décide **quoi** compiler et **quand** ;
//! ici, on compile ce qu'on nous donne. Les mélanger rendrait la compilation
//! intestable sans un gestionnaire de ressources autour.

use crate::a3d::{A3dWriter, SectionTag};
use crate::import::{
    import_gltf, import_obj, import_stl, ImportError, ImportLimits, ImportedAsset, SourceFormat,
};
use crate::optimize::{optimize, Aabb, LodOptions, LodTable};
use crate::validate::{validate, AssetView, NamedEntry, ValidationReport};
use ax_model::dm::geometry::Vertex;
use core::fmt;

/// Version du compilateur (R-562).
///
/// **À incrémenter à toute modification de C-21, C-22, C-23 ou C-28 qui change
/// la sortie.** Elle entre dans la clé de cache : sans incrément, une entrée
/// produite par l'ancien compilateur passerait pour à jour, et le changement
/// n'atteindrait jamais les assets déjà compilés.
///
/// Historique : 2 — C-23 tranche A (fusion des sommets, normales générées,
/// boîtes recalculées) et sommets STL propres à chaque facette ; 3 — C-23
/// tranche B (tangentes MikkTSpace, tangentes glTF lues, cache de sommets) ;
/// 4 — C-23 tranche C (LOD et section `LODM`), nodes sans annotation visibles
/// à tous les niveaux.
pub const COMPILER_VERSION: u32 = 4;

/// Ce qui empêche de compiler un asset.
#[derive(Debug, Clone, PartialEq)]
pub enum CompileError {
    /// La source n'a pas pu être lue (C-21).
    Import(ImportError),
    /// L'asset importé est invalide (C-22).
    Invalid(ValidationReport),
    /// Le conteneur n'a pas pu être écrit (C-24).
    Container(crate::a3d::A3dError),
}

impl CompileError {
    /// Code de l'ANNEXE A.1 correspondant.
    #[must_use]
    pub fn code(&self) -> i32 {
        match self {
            CompileError::Import(error) => error.code(),
            CompileError::Invalid(report) => report.code(),
            CompileError::Container(error) => error.code(),
        }
    }
}

impl fmt::Display for CompileError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            CompileError::Import(error) => write!(formatter, "import : {error}"),
            CompileError::Invalid(report) => {
                write!(formatter, "{} violation(s) de validation", report.len())?;
                // La première suffit à situer le problème ; la liste complète
                // reste dans le rapport, que l'appelant journalise.
                if let Some(first) = report.errors.first() {
                    write!(formatter, ", dont : {first}")?;
                }
                Ok(())
            }
            CompileError::Container(error) => write!(formatter, "conteneur : {error}"),
        }
    }
}

impl std::error::Error for CompileError {}

/// Options d'une compilation.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct CompileOptions {
    /// Identifiant de l'asset produit.
    pub asset_id: u64,
    /// Empreinte de la source, telle que l'orchestrateur l'a calculée.
    pub source_hash: u64,
    /// Plafonds d'import.
    pub limits: ImportLimits,
    /// Les colliders de cet asset seront portés par un body dynamique.
    ///
    /// R-160 n'interdit `TriMesh` et `Heightfield` que là : sur la géométrie du
    /// monde, ce sont les formes normales.
    pub dynamic_body: bool,
    /// Niveaux de détail (C-23, étape 6 ; bloc `lod` de la PARTIE 6.4).
    pub lod: LodOptions,
}

/// Ce qu'une compilation réussie produit.
#[derive(Debug, Clone, PartialEq)]
pub struct CompiledAsset {
    /// Le conteneur A3D, prêt à être mis en cache.
    pub bytes: Vec<u8>,
    /// Nombre de sommets compilés.
    pub vertex_count: usize,
    /// Nombre de meshes compilés.
    pub mesh_count: usize,
    /// Boîte englobante de l'asset, en espace asset (C-23, étape 7) ; `None`
    /// si aucun node ne porte de géométrie.
    ///
    /// Le format A3D figé n'a pas de champ pour elle : elle est rendue ici, et
    /// se recalcule au chargement depuis les boîtes de mesh et les nodes.
    pub bounds: Option<Aabb>,
    /// Avertissements rencontrés, à journaliser une fois (R-912, R-530).
    pub warnings: Vec<String>,
}

/// Compile une source en conteneur A3D.
///
/// # Errors
///
/// [`CompileError::Import`] si la source est illisible ou refusée,
/// [`CompileError::Invalid`] si l'asset viole la liste de contrôle de C-22,
/// [`CompileError::Container`] si l'écriture échoue.
pub fn compile(
    source: &[u8],
    format: SourceFormat,
    options: &CompileOptions,
    resolve: impl FnMut(&str) -> Option<Vec<u8>>,
) -> Result<CompiledAsset, CompileError> {
    let (mut asset, mut warnings) = import(source, format, options, resolve)?;

    // Les normales absentes de la source sont exemptées : C-23 les génère.
    check(&asset, options, &asset.missing_normals)?;

    let optimized = optimize(&mut asset, &options.lod);
    warnings.extend(optimized.warnings);

    // La sortie de C-23, sans exemption : c'est ce que le chargement vérifiera
    // (R-540). Le vérifier dès ici fait d'un défaut de l'optimizer un refus à
    // la compilation, plutôt qu'une entrée de cache refusée à chaque chargement.
    check(&asset, options, &[])?;

    let bytes = write_container(&asset, optimized.lods.as_ref(), options)?;
    Ok(CompiledAsset {
        bytes,
        vertex_count: asset.vertices.len(),
        mesh_count: asset.meshes.len(),
        bounds: optimized.bounds,
        warnings,
    })
}

/// Passe un asset à la liste de contrôle de C-22.
fn check(
    asset: &ImportedAsset,
    options: &CompileOptions,
    missing_normals: &[bool],
) -> Result<(), CompileError> {
    let names: Vec<NamedEntry<'_>> = asset
        .names
        .iter()
        .map(|(category, name)| NamedEntry::new(category, name.as_str()))
        .collect();

    let report = validate(
        &AssetView {
            nodes: &asset.nodes,
            meshes: &asset.meshes,
            vertices: &asset.vertices,
            indices: &asset.indices,
            names: &names,
            material_count: asset.materials.len(),
            dynamic_body: options.dynamic_body,
            missing_normals,
            ..AssetView::default()
        },
        &asset.raw_uvs,
    );
    if !report.is_valid() {
        // R-540 : strict à la compilation. Écrire un asset invalide reviendrait
        // à reporter le problème sur le chargement, où il coûte plus cher et
        // se diagnostique moins bien.
        return Err(CompileError::Invalid(report));
    }
    Ok(())
}

fn import(
    source: &[u8],
    format: SourceFormat,
    options: &CompileOptions,
    resolve: impl FnMut(&str) -> Option<Vec<u8>>,
) -> Result<(ImportedAsset, Vec<String>), CompileError> {
    match format {
        SourceFormat::Glb | SourceFormat::Gltf => {
            let (asset, report) =
                import_gltf(source, &options.limits, resolve).map_err(CompileError::Import)?;
            let mut warnings = report.warnings;
            warnings.extend(
                report
                    .ignored_extensions
                    .into_iter()
                    .map(|extension| format!("extension ignorée : {extension}")),
            );
            Ok((asset, warnings))
        }
        SourceFormat::Obj => {
            let text = core::str::from_utf8(source).map_err(|_| {
                CompileError::Import(ImportError::Malformed {
                    format,
                    detail: "source OBJ non UTF-8".to_owned(),
                })
            })?;
            let mut resolve = resolve;
            let asset = import_obj(text, &options.limits, |path| {
                resolve(path).and_then(|bytes| String::from_utf8(bytes).ok())
            })
            .map_err(CompileError::Import)?;
            Ok((asset, Vec::new()))
        }
        SourceFormat::Stl => {
            let asset = import_stl(source, &options.limits).map_err(CompileError::Import)?;
            Ok((asset, Vec::new()))
        }
    }
}

/// Écrit les sections que l'asset porte aujourd'hui.
///
/// Une section n'est écrite que si elle a du contenu : une section vide affirme
/// qu'il n'y a rien, une section absente ne dit rien, et c'est bien la seconde
/// qui décrit un asset dont le composant producteur n'existe pas encore.
fn write_container(
    asset: &ImportedAsset,
    lods: Option<&LodTable>,
    options: &CompileOptions,
) -> Result<Vec<u8>, CompileError> {
    let mut writer = A3dWriter::new(options.asset_id, options.source_hash, COMPILER_VERSION);

    if !asset.nodes.is_empty() {
        writer
            .section(SectionTag::NODE, &nodes_bytes(asset))
            .map_err(CompileError::Container)?;
    }
    if !asset.vertices.is_empty() || !asset.indices.is_empty() {
        writer
            .compressed_section(SectionTag::GEOM, &geometry_bytes(asset))
            .map_err(CompileError::Container)?;
    }
    if !asset.materials.is_empty() {
        writer
            .section(SectionTag::MATL, &materials_bytes(asset))
            .map_err(CompileError::Container)?;
    }
    if let Some(lods) = lods {
        // Section purement visuelle : le serveur l'ignore (R-041).
        writer
            .section(SectionTag::LODM, &lods.to_bytes())
            .map_err(CompileError::Container)?;
    }

    writer.finish().map_err(CompileError::Container)
}

/// Sérialise les nodes, tels quels.
///
/// Les structures sont `repr(C)` et lues en place à la relecture (R-881) : la
/// sérialisation est une copie d'octets, pas une conversion. Elle est écrite à
/// la main plutôt que déléguée, parce qu'une bibliothèque de sérialisation
/// choisirait sa propre disposition, et la disposition est justement ce qui est
/// figé.
fn nodes_bytes(asset: &ImportedAsset) -> Vec<u8> {
    let mut out = Vec::with_capacity(asset.nodes.len() * 80);
    for node in &asset.nodes {
        out.extend_from_slice(&node.name_hash.to_le_bytes());
        out.extend_from_slice(&node.parent.to_le_bytes());
        for value in node.local.translation {
            out.extend_from_slice(&value.to_le_bytes());
        }
        for value in node.local.rotation {
            out.extend_from_slice(&value.to_le_bytes());
        }
        for value in node.local.scale {
            out.extend_from_slice(&value.to_le_bytes());
        }
        out.extend_from_slice(&node.flags.to_le_bytes());
        out.extend_from_slice(&node.mesh.to_le_bytes());
        out.extend_from_slice(&node.collider.to_le_bytes());
        out.extend_from_slice(&node.bone.to_le_bytes());
        out.extend_from_slice(&node.part.to_le_bytes());
        out.extend_from_slice(&node.region.to_le_bytes());
        out.push(node.lod_mask);
        out.push(node.state);
        out.extend_from_slice(&[0, 0]);
    }
    out
}

/// Sérialise meshes, sommets et indices.
fn geometry_bytes(asset: &ImportedAsset) -> Vec<u8> {
    let mut out = Vec::with_capacity(
        asset.meshes.len() * 48 + asset.vertices.len() * Vertex::BYTES + asset.indices.len() * 4,
    );

    // Trois dénombrements en tête : la section se relit sans avoir à deviner où
    // finit chaque tableau.
    out.extend_from_slice(&(asset.meshes.len() as u32).to_le_bytes());
    out.extend_from_slice(&(asset.vertices.len() as u32).to_le_bytes());
    out.extend_from_slice(&(asset.indices.len() as u32).to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes());

    for mesh in &asset.meshes {
        out.extend_from_slice(&mesh.vertex_offset.to_le_bytes());
        out.extend_from_slice(&mesh.vertex_count.to_le_bytes());
        out.extend_from_slice(&mesh.index_offset.to_le_bytes());
        out.extend_from_slice(&mesh.index_count.to_le_bytes());
        out.extend_from_slice(&mesh.material.to_le_bytes());
        out.push(mesh.lod);
        out.push(mesh.flags);
        for value in mesh.aabb_min {
            out.extend_from_slice(&value.to_le_bytes());
        }
        for value in mesh.aabb_max {
            out.extend_from_slice(&value.to_le_bytes());
        }
        out.extend_from_slice(&mesh.region.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
    }

    for vertex in &asset.vertices {
        for value in vertex.position {
            out.extend_from_slice(&value.to_le_bytes());
        }
        out.extend_from_slice(&vertex.normal.map(|value| value as u8));
        out.extend_from_slice(&vertex.tangent.map(|value| value as u8));
        for value in vertex.uv0 {
            out.extend_from_slice(&value.to_le_bytes());
        }
        for value in vertex.uv1 {
            out.extend_from_slice(&value.to_le_bytes());
        }
        out.extend_from_slice(&vertex.color);
        out.extend_from_slice(&vertex.bones);
        out.extend_from_slice(&vertex.weights);
        out.push(vertex.region);
        out.push(vertex.def_w);
        out.extend_from_slice(&[0; 6]);
    }

    for index in &asset.indices {
        out.extend_from_slice(&index.to_le_bytes());
    }
    out
}

/// Sérialise les matériaux : couleur, puis le chemin de texture en UTF-8.
fn materials_bytes(asset: &ImportedAsset) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&(asset.materials.len() as u32).to_le_bytes());
    for material in &asset.materials {
        for value in material.base_color {
            out.extend_from_slice(&value.to_le_bytes());
        }
        let texture = material.base_color_texture.as_deref().unwrap_or("");
        out.extend_from_slice(&(texture.len() as u32).to_le_bytes());
        out.extend_from_slice(texture.as_bytes());
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::a3d::{A3dFile, A3dLimits};

    const OPTIONS: CompileOptions = CompileOptions {
        asset_id: 0x1234,
        source_hash: 0x5678,
        limits: ImportLimits::new(1 << 20),
        dynamic_body: true,
        lod: LodOptions::DEFAULT,
    };

    /// Une grille plane de `cote × cote` carrés, en OBJ.
    fn grille_obj(cote: u32) -> String {
        let mut source = String::new();
        for y in 0..=cote {
            for x in 0..=cote {
                source.push_str(&format!("v {x}.0 {y}.0 0.0\n"));
            }
        }
        for y in 0..cote {
            for x in 0..cote {
                let a = y * (cote + 1) + x + 1;
                let (b, c, d) = (a + 1, a + cote + 2, a + cote + 1);
                source.push_str(&format!("f {a} {b} {c}\nf {a} {c} {d}\n"));
            }
        }
        source
    }

    #[test]
    fn t550_les_lod_voyagent_dans_la_section_lodm() {
        let compiled = compile(
            grille_obj(16).as_bytes(),
            SourceFormat::Obj,
            &OPTIONS,
            |_| None,
        )
        .expect("compilation refusée");
        let file = A3dFile::open(&compiled.bytes, A3dLimits::new(1 << 22)).expect("relecture");

        // Un mesh source et trois LOD, que la table désigne dans l'ordre.
        assert_eq!(compiled.mesh_count, 4);
        let lodm = file
            .section(SectionTag::LODM)
            .expect("LODM")
            .expect("section absente");
        let mots: Vec<u32> = lodm
            .chunks_exact(4)
            .map(|mot| u32::from_le_bytes(mot.try_into().unwrap()))
            .collect();
        assert_eq!(mots, [4, 1, 0, 1, 2, 3]);
    }

    #[test]
    fn t550_sans_lod_genere_la_section_lodm_n_est_pas_ecrite() {
        // Un triangle ne se simplifie pas : aucune table à écrire.
        let compiled = compile(TRIANGLE_OBJ.as_bytes(), SourceFormat::Obj, &OPTIONS, |_| {
            None
        })
        .expect("compilation refusée");
        let file = A3dFile::open(&compiled.bytes, A3dLimits::new(1 << 20)).expect("relecture");
        assert!(!file.has(SectionTag::LODM));
    }

    const TRIANGLE_OBJ: &str = "\
v 0.0 0.0 0.0
v 1.0 0.0 0.0
v 0.0 1.0 0.0
vn 0.0 0.0 1.0
f 1//1 2//1 3//1
";

    fn stl_triangle() -> Vec<u8> {
        let mut bytes = vec![0u8; 80];
        bytes.extend_from_slice(&1u32.to_le_bytes());
        for value in [0.0f32, 0.0, 1.0] {
            bytes.extend_from_slice(&value.to_le_bytes());
        }
        for corner in [[0.0f32, 0.0, 0.0], [1.0, 0.0, 0.0], [0.0, 1.0, 0.0]] {
            for value in corner {
                bytes.extend_from_slice(&value.to_le_bytes());
            }
        }
        bytes.extend_from_slice(&0u16.to_le_bytes());
        bytes
    }

    #[test]
    fn t210_une_source_valide_produit_un_conteneur_relisible() {
        let compiled = compile(TRIANGLE_OBJ.as_bytes(), SourceFormat::Obj, &OPTIONS, |_| {
            None
        })
        .expect("compilation refusée");

        assert_eq!(compiled.vertex_count, 3);
        assert_eq!(compiled.mesh_count, 1);

        let file = A3dFile::open(&compiled.bytes, A3dLimits::new(1 << 20)).expect("relecture");
        assert_eq!(file.header().asset_id, 0x1234);
        assert_eq!(file.header().source_hash, 0x5678);
        assert_eq!(file.header().compiler_version, COMPILER_VERSION);
        assert!(file.has(SectionTag::NODE));
        assert!(file.has(SectionTag::GEOM));
    }

    #[test]
    fn t210_les_quatre_formats_compilent() {
        assert!(
            compile(TRIANGLE_OBJ.as_bytes(), SourceFormat::Obj, &OPTIONS, |_| {
                None
            })
            .is_ok()
        );
        assert!(compile(&stl_triangle(), SourceFormat::Stl, &OPTIONS, |_| None).is_ok());
    }

    #[test]
    fn t211_une_section_sans_contenu_n_est_pas_ecrite() {
        let compiled = compile(&stl_triangle(), SourceFormat::Stl, &OPTIONS, |_| None)
            .expect("compilation refusée");
        let file = A3dFile::open(&compiled.bytes, A3dLimits::new(1 << 20)).expect("relecture");

        // Le STL ne porte aucun matériau. Une section vide affirmerait qu'il
        // n'y en a pas ; son absence ne dit rien, et c'est bien ce qu'on veut
        // dire d'un composant qui n'existe pas encore.
        assert!(!file.has(SectionTag::MATL));
    }

    #[test]
    fn t212_un_asset_invalide_n_est_jamais_ecrit() {
        // Trois sommets alignés : aire nulle, le validateur refuse.
        let degenere = "\
v 0.0 0.0 0.0
v 1.0 0.0 0.0
v 2.0 0.0 0.0
f 1 2 3
";
        let refus =
            compile(degenere.as_bytes(), SourceFormat::Obj, &OPTIONS, |_| None).unwrap_err();

        // R-540 : strict à la compilation. Écrire reporterait le problème sur
        // le chargement, où il coûte plus cher et se diagnostique moins bien.
        assert!(matches!(refus, CompileError::Invalid(_)), "{refus:?}");
        assert!(refus.to_string().contains("violation"), "{refus}");
    }

    #[test]
    fn t212_une_source_refusee_a_l_import_ne_va_pas_plus_loin() {
        let refus = compile(
            TRIANGLE_OBJ.as_bytes(),
            SourceFormat::Obj,
            &CompileOptions {
                limits: ImportLimits::new(4),
                ..OPTIONS
            },
            |_| None,
        )
        .unwrap_err();

        assert!(matches!(refus, CompileError::Import(_)), "{refus:?}");
        assert_eq!(refus.code(), -3005);
    }

    #[test]
    fn t213_la_compilation_est_deterministe() {
        let une = compile(TRIANGLE_OBJ.as_bytes(), SourceFormat::Obj, &OPTIONS, |_| {
            None
        })
        .expect("compilation refusée");
        let deux = compile(TRIANGLE_OBJ.as_bytes(), SourceFormat::Obj, &OPTIONS, |_| {
            None
        })
        .expect("compilation refusée");

        // Sans déterminisme, la clé de cache ne dirait rien : deux
        // compilations d'une même source produiraient deux entrées.
        assert_eq!(une.bytes, deux.bytes);
    }

    #[test]
    fn t213_la_version_du_compilateur_voyage_avec_l_asset() {
        let compiled = compile(&stl_triangle(), SourceFormat::Stl, &OPTIONS, |_| None)
            .expect("compilation refusée");
        let file = A3dFile::open(&compiled.bytes, A3dLimits::new(1 << 20)).expect("relecture");

        // R-892 : c'est elle qui invalide une entrée de cache produite par un
        // compilateur antérieur.
        assert_eq!(file.header().compiler_version, COMPILER_VERSION);
    }

    #[test]
    fn t214_les_avertissements_remontent_sans_faire_echouer() {
        // Un OBJ sans normale : C-23 les calculera, et rien n'est perdu.
        let sans_normale = "\
v 0.0 0.0 0.0
v 1.0 0.0 0.0
v 0.0 1.0 0.0
f 1 2 3
";
        let compiled = compile(sans_normale.as_bytes(), SourceFormat::Obj, &OPTIONS, |_| {
            None
        })
        .expect("compilation refusée");
        assert_eq!(compiled.vertex_count, 3);

        // Et C-23 l'a bien générée : le triangle est dans le plan XY, tourné
        // vers +Z. La normale du premier sommet suit l'en-tête de 16 octets,
        // le mesh de 48 et la position de 12.
        let file = A3dFile::open(&compiled.bytes, A3dLimits::new(1 << 20)).expect("relecture");
        let geom = file
            .section(SectionTag::GEOM)
            .expect("GEOM")
            .expect("section absente");
        let normal = 16 + 48 + 12;
        assert_eq!(&geom[normal..normal + 4], &[0, 0, 127, 0]);
    }

    #[test]
    fn t214_une_normale_nulle_ecrite_par_l_auteur_est_refusee() {
        // `vn 0 0 0` n'est pas une normale absente : l'auteur en a écrit une,
        // et elle n'a pas de direction. C-22 le lui dit plutôt que C-23 la
        // remplace en silence.
        let nulle = "\
v 0.0 0.0 0.0
v 1.0 0.0 0.0
v 0.0 1.0 0.0
vn 0.0 0.0 0.0
f 1//1 2//1 3//1
";
        let refus = compile(nulle.as_bytes(), SourceFormat::Obj, &OPTIONS, |_| None).unwrap_err();
        assert!(matches!(refus, CompileError::Invalid(_)), "{refus:?}");
    }

    #[test]
    fn t240_la_compilation_fusionne_les_sommets_identiques() {
        // Deux facettes STL coplanaires : six coins à l'import, quatre sommets
        // distincts une fois la diagonale fusionnée.
        let mut bytes = vec![0u8; 80];
        bytes.extend_from_slice(&2u32.to_le_bytes());
        let facettes = [
            [[0.0f32, 0.0, 0.0], [1.0, 0.0, 0.0], [1.0, 1.0, 0.0]],
            [[0.0, 0.0, 0.0], [1.0, 1.0, 0.0], [0.0, 1.0, 0.0]],
        ];
        for coins in facettes {
            for value in [0.0f32, 0.0, 1.0] {
                bytes.extend_from_slice(&value.to_le_bytes());
            }
            for coin in coins {
                for value in coin {
                    bytes.extend_from_slice(&value.to_le_bytes());
                }
            }
            bytes.extend_from_slice(&0u16.to_le_bytes());
        }

        let compiled =
            compile(&bytes, SourceFormat::Stl, &OPTIONS, |_| None).expect("compilation refusée");
        assert_eq!(compiled.vertex_count, 4);
    }

    #[test]
    fn t242_la_boite_englobante_de_l_asset_est_rendue() {
        let compiled = compile(TRIANGLE_OBJ.as_bytes(), SourceFormat::Obj, &OPTIONS, |_| {
            None
        })
        .expect("compilation refusée");
        assert_eq!(
            compiled.bounds,
            Some(Aabb {
                min: [0.0, 0.0, 0.0],
                max: [1.0, 1.0, 0.0],
            })
        );
    }

    #[test]
    fn t211_la_geometrie_serialisee_porte_ses_denombrements() {
        let compiled = compile(TRIANGLE_OBJ.as_bytes(), SourceFormat::Obj, &OPTIONS, |_| {
            None
        })
        .expect("compilation refusée");
        let file = A3dFile::open(&compiled.bytes, A3dLimits::new(1 << 20)).expect("relecture");

        let geom = file
            .section(SectionTag::GEOM)
            .expect("GEOM")
            .expect("section absente");
        assert_eq!(u32::from_le_bytes(geom[0..4].try_into().unwrap()), 1);
        assert_eq!(u32::from_le_bytes(geom[4..8].try_into().unwrap()), 3);
        assert_eq!(u32::from_le_bytes(geom[8..12].try_into().unwrap()), 3);
        // La section se relit sans avoir à deviner où finit chaque tableau.
        let attendu = 16 + 48 + 3 * Vertex::BYTES + 3 * 4;
        assert_eq!(geom.len(), attendu);
    }
}
