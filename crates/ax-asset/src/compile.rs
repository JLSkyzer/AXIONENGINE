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

use crate::a3d::{
    encode_colliders, encode_materials, encode_nodes, encode_textures, A3dWriter, SectionTag,
};
use crate::collider::{build_colliders, ColliderMode};
use crate::import::{
    import_gltf, import_obj, import_stl, ImportError, ImportLimits, ImportedAsset, SourceFormat,
};
use crate::optimize::{optimize, Aabb, LodOptions, LodTable};
use crate::validate::{validate, AssetView, NamedEntry, ValidationReport};
use ax_model::dm::geometry::{MeshDesc, Vertex};
use ax_model::dm::material::MaterialDesc;
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
/// à tous les niveaux ; 5 — section `NODE` avec sa table des noms et ses nodes
/// sur 80 octets (ADR-110), empreintes de nom des nodes OBJ et STL ; 6 — section
/// `PHYS` (colliders C-32, ADR-115) ; 7 — sections `MATL` (DM-05) et `TEXR`
/// (ADR-122) : matériaux et textures importés, matériau par défaut, drapeaux de
/// mesh recopiés, couleurs de sommet `COLOR_0`, projection de l'albedo cuite
/// dans `uv0`, `v` des OBJ compté depuis le haut de l'image ; 8 — UV ramenés
/// dans `[0, 1]` sous une plage par mesh (`uv0_range`, R-142), un node porte
/// tous ses meshes (`mesh_count`) : primitives glTF, morceaux d'un objet OBJ
/// (ADR-122 §4 et §5).
pub const COMPILER_VERSION: u32 = 8;

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
    /// Mode de génération automatique de collider réclamé par la definition
    /// (C-32, R-620). [`ColliderMode::None`] par défaut : rien n'est généré sans
    /// demande explicite.
    pub collider_mode: ColliderMode,
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
    /// Nombre de colliders générés (C-32).
    pub collider_count: usize,
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

    // C-32 : les colliders se génèrent une fois les bornes connues (l'optimizer
    // calcule les boîtes à l'étape 7). Ils repassent donc la liste de contrôle
    // ci-dessous, au même titre que la géométrie optimisée. Les requêtes issues
    // des extras de node priment sur le mode de definition (R-620).
    let built = build_colliders(
        &mut asset.nodes,
        &asset.meshes,
        &asset.vertices,
        &asset.indices,
        &asset.collider_requests,
        options.collider_mode,
        optimized.bounds,
    );
    warnings.extend(built.warnings);
    asset.colliders = built.colliders;
    asset.hull_points = built.hull_points;
    asset.compound_children = built.compound_children;

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
        collider_count: asset.colliders.len(),
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
    let materials = material_table(asset);

    let report = validate(
        &AssetView {
            nodes: &asset.nodes,
            meshes: &asset.meshes,
            vertices: &asset.vertices,
            indices: &asset.indices,
            colliders: &asset.colliders,
            names: &names,
            // Toujours une table à la compilation, même vide : les importeurs
            // donnent à chaque mesh un matériau, et c'est vérifié.
            materials: Some(&materials),
            texture_count: asset.textures.entries.len(),
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

/// Les matériaux de l'asset, tels que `MATL` les porte.
fn material_table(asset: &ImportedAsset) -> Vec<MaterialDesc> {
    asset
        .materials
        .iter()
        .map(|material| material.desc)
        .collect()
}

/// Importe la source, et rassemble ce que l'import a laissé de côté.
///
/// Une texture refusée ou une propriété sans champ DM-05 n'empêchent pas la
/// compilation : elles sont dites une fois, ici, avec le reste (ADR-122 §3).
fn import(
    source: &[u8],
    format: SourceFormat,
    options: &CompileOptions,
    resolve: impl FnMut(&str) -> Option<Vec<u8>>,
) -> Result<(ImportedAsset, Vec<String>), CompileError> {
    let (asset, mut warnings) = import_source(source, format, options, resolve)?;
    warnings.extend(asset.texture_refusals.iter().map(ToString::to_string));
    warnings.extend(asset.material_warnings.iter().cloned());
    Ok((asset, warnings))
}

fn import_source(
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
        let nodes =
            encode_nodes(&asset.nodes, &asset.node_names).map_err(CompileError::Container)?;
        writer
            .section(SectionTag::NODE, &nodes)
            .map_err(CompileError::Container)?;
    }
    if !asset.vertices.is_empty() || !asset.indices.is_empty() {
        writer
            .compressed_section(SectionTag::GEOM, &geometry_bytes(asset))
            .map_err(CompileError::Container)?;
    }
    if !asset.materials.is_empty() {
        // Sections du client seul (R-041) : un serveur les ignore.
        let matl = encode_materials(&material_table(asset)).map_err(CompileError::Container)?;
        writer
            .section(SectionTag::MATL, &matl)
            .map_err(CompileError::Container)?;
    }
    if !asset.textures.entries.is_empty() {
        // Non compressée : ses octets sont des PNG, déjà compressés, et des
        // chemins de quelques dizaines d'octets.
        let texr = encode_textures(&asset.textures).map_err(CompileError::Container)?;
        writer
            .section(SectionTag::TEXR, &texr)
            .map_err(CompileError::Container)?;
    }
    if let Some(lods) = lods {
        // Section purement visuelle : le serveur l'ignore (R-041).
        writer
            .section(SectionTag::LODM, &lods.to_bytes())
            .map_err(CompileError::Container)?;
    }
    if !asset.colliders.is_empty() {
        // Section `PHYS` : les colliders du runtime (C-32, ADR-115).
        let phys = encode_colliders(
            &asset.colliders,
            &asset.hull_points,
            &asset.compound_children,
        )
        .map_err(CompileError::Container)?;
        writer
            .section(SectionTag::PHYS, &phys)
            .map_err(CompileError::Container)?;
    }

    writer.finish().map_err(CompileError::Container)
}

/// Sérialise meshes, sommets et indices.
fn geometry_bytes(asset: &ImportedAsset) -> Vec<u8> {
    let mut out = Vec::with_capacity(
        16 + asset.meshes.len() * MeshDesc::BYTES
            + asset.vertices.len() * Vertex::BYTES
            + asset.indices.len() * 4,
    );

    // Trois dénombrements en tête : la section se relit sans avoir à deviner où
    // finit chaque tableau.
    out.extend_from_slice(&(asset.meshes.len() as u32).to_le_bytes());
    out.extend_from_slice(&(asset.vertices.len() as u32).to_le_bytes());
    out.extend_from_slice(&(asset.indices.len() as u32).to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes());

    // La sérialisation de DM-04 vit avec ses types : le transfert de géométrie
    // vers Java (ADR-119) emploie la même, et une seconde copie divergerait.
    for mesh in &asset.meshes {
        mesh.write_le(&mut out);
    }
    for vertex in &asset.vertices {
        vertex.write_le(&mut out);
    }

    for index in &asset.indices {
        out.extend_from_slice(&index.to_le_bytes());
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::a3d::{A3dFile, A3dLimits};
    use ax_model::dm::geometry::UvRange;

    const OPTIONS: CompileOptions = CompileOptions {
        asset_id: 0x1234,
        source_hash: 0x5678,
        limits: ImportLimits::new(1 << 20),
        dynamic_body: true,
        lod: LodOptions::DEFAULT,
        collider_mode: ColliderMode::None,
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
    fn t211_la_section_node_porte_le_nom_de_chaque_node() {
        let source = format!("o triangle\n{TRIANGLE_OBJ}");
        let compiled = compile(source.as_bytes(), SourceFormat::Obj, &OPTIONS, |_| None)
            .expect("compilation refusée");
        let file = A3dFile::open(&compiled.bytes, A3dLimits::new(1 << 20)).expect("relecture");
        let node = file
            .section(SectionTag::NODE)
            .expect("NODE")
            .expect("section absente");

        // Le nom se relit, et son empreinte est celle du node : un nom de
        // definition se résoudra contre elle (ADR-110).
        let table = crate::a3d::decode_nodes(&node).expect("table des nodes");
        assert_eq!(table.names, ["triangle"]);
        assert_eq!(
            table.nodes[0].name_hash,
            ax_model::dm::scene::name_hash("triangle")
        );
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

    /// Un cube unité `[0,1]³`, sans normale (C-23 les génère). Non plat : un
    /// `auto_box` en tire une boîte non dégénérée.
    const CUBE_OBJ: &str = "\
v 0.0 0.0 0.0
v 1.0 0.0 0.0
v 1.0 1.0 0.0
v 0.0 1.0 0.0
v 0.0 0.0 1.0
v 1.0 0.0 1.0
v 1.0 1.0 1.0
v 0.0 1.0 1.0
f 1 2 3
f 1 3 4
f 5 7 6
f 5 8 7
f 1 5 6
f 1 6 2
f 4 3 7
f 4 7 8
f 1 4 8
f 1 8 5
f 2 6 7
f 2 7 3
";

    #[test]
    fn t310_le_mode_autobox_produit_un_collider() {
        // La definition réclame `auto_box` : le compilateur génère une boîte
        // englobante, qui repasse la validation (densité positive, dimensions
        // valides) puisque la compilation aboutit.
        let options = CompileOptions {
            collider_mode: ColliderMode::AutoBox,
            ..OPTIONS
        };
        let compiled = compile(CUBE_OBJ.as_bytes(), SourceFormat::Obj, &options, |_| None)
            .expect("compilation refusée");
        assert_eq!(compiled.collider_count, 1, "auto_box génère un collider");
    }

    #[test]
    fn t310_le_mode_autosphere_produit_un_collider() {
        // Une sphère englobante passe elle aussi la validation de bout en bout.
        let options = CompileOptions {
            collider_mode: ColliderMode::AutoSphere,
            ..OPTIONS
        };
        let compiled = compile(CUBE_OBJ.as_bytes(), SourceFormat::Obj, &options, |_| None)
            .expect("compilation refusée");
        assert_eq!(compiled.collider_count, 1, "auto_sphere génère un collider");
    }

    #[test]
    fn t310_sans_mode_aucun_collider() {
        // Le défaut est « aucun » : une géométrie ordinaire ne gagne pas de
        // collider par surprise.
        let compiled = compile(CUBE_OBJ.as_bytes(), SourceFormat::Obj, &OPTIONS, |_| None)
            .expect("compilation refusée");
        assert_eq!(
            compiled.collider_count, 0,
            "aucune source ne réclame de collider"
        );
    }

    #[test]
    fn t311_les_colliders_voyagent_dans_la_section_phys() {
        // Le collider auto-généré traverse le conteneur : la section PHYS se
        // relit et redonne une boîte (ADR-115).
        let options = CompileOptions {
            collider_mode: ColliderMode::AutoBox,
            ..OPTIONS
        };
        let compiled = compile(CUBE_OBJ.as_bytes(), SourceFormat::Obj, &options, |_| None)
            .expect("compilation refusée");
        let file = A3dFile::open(&compiled.bytes, A3dLimits::new(1 << 20)).expect("relecture");
        let phys = file
            .section(SectionTag::PHYS)
            .expect("PHYS")
            .expect("section absente");
        let (colliders, _points, _children) =
            crate::a3d::decode_colliders(&phys).expect("décodage PHYS");
        assert_eq!(colliders.len(), 1);
        assert!(
            matches!(
                colliders[0].shape,
                ax_model::dm::physics::ColliderShape::Box { .. }
            ),
            "une boîte englobante"
        );
    }

    /// glTF minimal dont l'unique node porte `role=collider`, `shape=auto_box`.
    /// Trois sommets couvrant l'AABB [0,0,0]–[1,1,1] (non dégénéré sur les trois
    /// axes, donc une boîte valide). Buffer en base64 : positions f32 puis indices
    /// u16.
    const GLTF_COLLIDER: &str = concat!(
        r#"{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"#,
        r#""nodes":[{"name":"collideur","mesh":0,"extras":{"axion":"#,
        r#"{"role":"collider","shape":"auto_box"}}}],"#,
        r#""meshes":[{"name":"boite","primitives":[{"attributes":{"POSITION":0},"indices":1}]}],"#,
        r#""accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","#,
        r#""min":[0.0,0.0,0.0],"max":[1.0,1.0,1.0]},"#,
        r#"{"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"}],"#,
        r#""bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},"#,
        r#"{"buffer":0,"byteOffset":36,"byteLength":6}],"#,
        r#""buffers":[{"byteLength":42,"uri":"data:application/octet-stream;base64,"#,
        "AAAAAAAAAAAAAAAAAACAPwAAAAAAAAAAAAAAAAAAgD8AAIA/AAABAAIA",
        r#""}]}"#,
    );

    #[test]
    fn t311_un_node_collider_gltf_produit_une_section_phys() {
        // Le chemin authoré (R-620, priorité 1) : un node `role=collider` de la
        // source produit sa boîte, sérialisée dans PHYS et relisible. C'est ce que
        // le runtime consommera (Option A, ADR-115).
        let compiled = compile(
            GLTF_COLLIDER.as_bytes(),
            SourceFormat::Gltf,
            &OPTIONS,
            |_| None,
        )
        .expect("compilation refusée");
        assert_eq!(
            compiled.collider_count, 1,
            "le node collider produit une boîte"
        );

        let file = A3dFile::open(&compiled.bytes, A3dLimits::new(1 << 20)).expect("relecture");
        let phys = file
            .section(SectionTag::PHYS)
            .expect("PHYS")
            .expect("section absente");
        let (colliders, _points, _children) =
            crate::a3d::decode_colliders(&phys).expect("décodage PHYS");
        assert_eq!(colliders.len(), 1);
        let ax_model::dm::physics::ColliderShape::Box { half_extents } = colliders[0].shape else {
            panic!("attendu une boîte, obtenu {:?}", colliders[0].shape);
        };
        // AABB [0,1]³ → demi-dimensions [0.5, 0.5, 0.5].
        for extent in half_extents {
            assert!((extent - 0.5).abs() < 1.0e-6, "demi-dimension {extent}");
        }
    }

    /// glTF dont l'unique node porte `role=collider`, `shape=convex` : un tétraèdre
    /// de quatre sommets non coplanaires. Buffer base64 : positions f32 puis indices
    /// u16 (quatre faces).
    const GLTF_CONVEX: &str = concat!(
        r#"{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"#,
        r#""nodes":[{"name":"coque","mesh":0,"extras":{"axion":"#,
        r#"{"role":"collider","shape":"convex"}}}],"#,
        r#""meshes":[{"name":"tetra","primitives":[{"attributes":{"POSITION":0},"indices":1}]}],"#,
        r#""accessors":[{"bufferView":0,"componentType":5126,"count":4,"type":"VEC3","#,
        r#""min":[0.0,0.0,0.0],"max":[1.0,1.0,1.0]},"#,
        r#"{"bufferView":1,"componentType":5123,"count":12,"type":"SCALAR"}],"#,
        r#""bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":48},"#,
        r#"{"buffer":0,"byteOffset":48,"byteLength":24}],"#,
        r#""buffers":[{"byteLength":72,"uri":"data:application/octet-stream;base64,"#,
        "AAAAAAAAAAAAAAAAAACAPwAAAAAAAAAAAAAAAAAAgD8AAAAAAAAAAAAAAAAAAIA/AAACAAEAAAABAAMAAAADAAIAAQACAAMA",
        r#""}]}"#,
    );

    #[test]
    fn t311_un_node_convex_gltf_produit_une_enveloppe_dans_phys() {
        // Le chemin authoré convexe (R-620 priorité 1, R-161) : les sommets du mesh
        // deviennent les points d'enveloppe, sérialisés dans PHYS et relisibles.
        let compiled = compile(GLTF_CONVEX.as_bytes(), SourceFormat::Gltf, &OPTIONS, |_| {
            None
        })
        .expect("compilation refusée");
        assert_eq!(
            compiled.collider_count, 1,
            "le node convexe produit une enveloppe"
        );

        let file = A3dFile::open(&compiled.bytes, A3dLimits::new(1 << 20)).expect("relecture");
        let phys = file
            .section(SectionTag::PHYS)
            .expect("PHYS")
            .expect("section absente");
        let (colliders, points, _children) =
            crate::a3d::decode_colliders(&phys).expect("décodage PHYS");
        assert_eq!(colliders.len(), 1);
        let ax_model::dm::physics::ColliderShape::ConvexHull {
            points_offset,
            points_count,
        } = colliders[0].shape
        else {
            panic!(
                "attendu une enveloppe convexe, obtenu {:?}",
                colliders[0].shape
            );
        };
        assert_eq!(points_offset, 0);
        assert_eq!(points_count, 4, "les quatre sommets du tétraèdre");
        assert_eq!(points.len(), 4, "l'annexe porte les quatre points");
    }

    /// glTF dont le node porteur `role=collider shape=auto_compound` a deux enfants
    /// à mesh (un cube réutilisé), placés en ±x. Buffer base64 : positions f32 (8
    /// sommets) puis indices u16 (12 faces).
    const GLTF_COMPOUND: &str = concat!(
        r#"{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"#,
        r#""nodes":[{"name":"corps","extras":{"axion":{"role":"collider","#,
        r#""shape":"auto_compound"}},"children":[1,2]},"#,
        r#"{"name":"g","mesh":0,"translation":[-1.0,0.0,0.0]},"#,
        r#"{"name":"d","mesh":0,"translation":[1.0,0.0,0.0]}],"#,
        r#""meshes":[{"name":"cube","primitives":[{"attributes":{"POSITION":0},"indices":1}]}],"#,
        r#""accessors":[{"bufferView":0,"componentType":5126,"count":8,"type":"VEC3","#,
        r#""min":[-0.5,-0.5,-0.5],"max":[0.5,0.5,0.5]},"#,
        r#"{"bufferView":1,"componentType":5123,"count":36,"type":"SCALAR"}],"#,
        r#""bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":96},"#,
        r#"{"buffer":0,"byteOffset":96,"byteLength":72}],"#,
        r#""buffers":[{"byteLength":168,"uri":"data:application/octet-stream;base64,"#,
        "AAAAvwAAAL8AAAC/AAAAPwAAAL8AAAC/AAAAPwAAAD8AAAC/AAAAvwAAAD8AAAC/AAAAvwAAAL8A",
        "AAA/AAAAPwAAAL8AAAA/AAAAPwAAAD8AAAA/AAAAvwAAAD8AAAA/AAACAAEAAAADAAIABAAFAAYA",
        "BAAGAAcAAAABAAUAAAAFAAQAAgADAAcAAgAHAAYAAQACAAYAAQAGAAUAAAAEAAcAAAAHAAMA",
        r#""}]}"#,
    );

    #[test]
    fn t311_un_node_auto_compound_gltf_produit_un_compound_dans_phys() {
        // Le chemin authoré compound (R-621) : les meshes des nodes enfants sont
        // regroupés en un seul collider Compound, ses filles dans l'annexe.
        let compiled = compile(
            GLTF_COMPOUND.as_bytes(),
            SourceFormat::Gltf,
            &OPTIONS,
            |_| None,
        )
        .expect("compilation refusée");
        assert_eq!(compiled.collider_count, 1, "un seul collider : le compound");

        let file = A3dFile::open(&compiled.bytes, A3dLimits::new(1 << 20)).expect("relecture");
        let phys = file
            .section(SectionTag::PHYS)
            .expect("PHYS")
            .expect("section absente");
        let (colliders, _points, children) =
            crate::a3d::decode_colliders(&phys).expect("décodage PHYS");
        assert_eq!(colliders.len(), 1);
        let ax_model::dm::physics::ColliderShape::Compound {
            children_offset,
            children_count,
        } = colliders[0].shape
        else {
            panic!("attendu un compound, obtenu {:?}", colliders[0].shape);
        };
        assert_eq!(children_offset, 0);
        assert_eq!(children_count, 2, "deux enfants regroupés");
        assert_eq!(children.len(), 2);
        for child in &children {
            assert!(matches!(
                child.shape,
                ax_model::dm::physics::ColliderShape::Box { .. }
            ));
        }
    }

    #[test]
    fn t311_sans_collider_pas_de_section_phys() {
        // Une section absente ne dit rien : c'est ce qu'on veut d'un asset sans
        // collider, plutôt qu'une section vide.
        let compiled = compile(CUBE_OBJ.as_bytes(), SourceFormat::Obj, &OPTIONS, |_| None)
            .expect("compilation refusée");
        let file = A3dFile::open(&compiled.bytes, A3dLimits::new(1 << 20)).expect("relecture");
        assert!(!file.has(SectionTag::PHYS));
    }

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

        // Le STL ne porte aucune texture : pas de `TEXR`. Une section vide
        // affirmerait qu'il n'y en a pas ; son absence ne dit rien de plus que
        // la vérité.
        assert!(!file.has(SectionTag::TEXR));
        // Son mesh désigne le matériau par défaut, qui voyage dans `MATL`.
        let matl = file
            .section(SectionTag::MATL)
            .expect("MATL")
            .expect("section absente");
        assert_eq!(
            crate::a3d::decode_materials(&matl),
            Ok(crate::a3d::DecodedMaterials::Materials(vec![
                crate::import::plain_default_material()
            ]))
        );
    }

    /// Un PNG de 2×2 pixels — signature et `IHDR`, le compilateur n'en lit pas
    /// davantage (R-532).
    fn png_2x2() -> Vec<u8> {
        crate::png::header_for_tests(2, 2)
    }

    /// glTF d'un triangle texturé par une image embarquée en `data:`, plus une
    /// image JPEG qu'un second matériau désigne.
    fn gltf_texture(png: &[u8]) -> String {
        let image = encode_base64(png);
        format!(
            concat!(
                r#"{{"asset":{{"version":"2.0"}},"scene":0,"scenes":[{{"nodes":[0]}}],"#,
                r#""nodes":[{{"name":"caisse","mesh":0}}],"#,
                r#""meshes":[{{"primitives":[{{"attributes":{{"POSITION":0,"TEXCOORD_0":1}},"#,
                r#""indices":2,"material":0}}]}}],"#,
                r#""materials":[{{"name":"bois","pbrMetallicRoughness":{{"baseColorTexture":{{"index":0}}}}}},"#,
                r#"{{"name":"photo","pbrMetallicRoughness":{{"baseColorTexture":{{"index":1}}}}}}],"#,
                r#""textures":[{{"source":0,"sampler":0}},{{"source":1}}],"#,
                r#""samplers":[{{"magFilter":9729,"wrapS":33071}}],"#,
                r#""images":[{{"uri":"data:image/png;base64,{image}"}},"#,
                r#"{{"uri":"photo.jpg","mimeType":"image/jpeg"}}],"#,
                r#""accessors":[{{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","#,
                r#""min":[0.0,0.0,0.0],"max":[1.0,1.0,0.0]}},"#,
                r#"{{"bufferView":1,"componentType":5126,"count":3,"type":"VEC2"}},"#,
                r#"{{"bufferView":2,"componentType":5123,"count":3,"type":"SCALAR"}}],"#,
                r#""bufferViews":[{{"buffer":0,"byteOffset":0,"byteLength":36}},"#,
                r#"{{"buffer":0,"byteOffset":36,"byteLength":24}},"#,
                r#"{{"buffer":0,"byteOffset":60,"byteLength":6}}],"#,
                r#""buffers":[{{"byteLength":66,"uri":"data:application/octet-stream;base64,{buffer}"}}]}}"#,
            ),
            image = image,
            buffer = encode_base64(&triangle_buffer()),
        )
    }

    /// Positions d'un triangle, ses UV, puis ses indices `u16`.
    fn triangle_buffer() -> Vec<u8> {
        let mut bytes = Vec::new();
        for value in [0.0f32, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0] {
            bytes.extend_from_slice(&value.to_le_bytes());
        }
        for value in [0.0f32, 0.0, 1.0, 0.0, 0.0, 1.0] {
            bytes.extend_from_slice(&value.to_le_bytes());
        }
        for index in [0u16, 1, 2] {
            bytes.extend_from_slice(&index.to_le_bytes());
        }
        bytes
    }

    /// Base64 standard, avec remplissage — l'inverse de celui de l'import.
    fn encode_base64(bytes: &[u8]) -> String {
        const ALPHABET: &[u8; 64] =
            b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        let mut out = String::new();
        for chunk in bytes.chunks(3) {
            let word = chunk.iter().enumerate().fold(0u32, |word, (rank, byte)| {
                word | (u32::from(*byte) << (16 - 8 * rank))
            });
            for rank in 0..4 {
                if rank <= chunk.len() {
                    let sextet = (word >> (18 - 6 * rank)) & 63;
                    out.push(char::from(ALPHABET[sextet as usize]));
                } else {
                    out.push('=');
                }
            }
        }
        out
    }

    #[test]
    fn t270_les_materiaux_et_les_textures_voyagent_dans_matl_et_texr() {
        let png = png_2x2();
        let compiled = compile(
            gltf_texture(&png).as_bytes(),
            SourceFormat::Gltf,
            &OPTIONS,
            |_| None,
        )
        .expect("compilation refusée");
        let file = A3dFile::open(&compiled.bytes, A3dLimits::new(1 << 20)).expect("relecture");

        let matl = file
            .section(SectionTag::MATL)
            .expect("MATL")
            .expect("section absente");
        let Ok(crate::a3d::DecodedMaterials::Materials(materials)) =
            crate::a3d::decode_materials(&matl)
        else {
            panic!("MATL illisible");
        };
        assert_eq!(materials.len(), 2);
        assert_eq!(
            materials[0].name_hash,
            ax_model::dm::scene::name_hash("bois")
        );
        assert_eq!(materials[0].albedo_tex, 0);
        // L'image JPEG est refusée seule : le slot reste vide, l'asset compile.
        assert_eq!(materials[1].albedo_tex, ax_model::dm::material::NO_TEXTURE);

        let texr = file
            .section(SectionTag::TEXR)
            .expect("TEXR")
            .expect("section absente");
        let Ok(crate::a3d::DecodedTextures::Textures(table)) = crate::a3d::decode_textures(&texr)
        else {
            panic!("TEXR illisible");
        };
        assert_eq!(table.entries.len(), 1);
        // Les octets du PNG, tels quels (R-532), et son échantillonneur.
        assert_eq!(table.data(0), Some(png.as_slice()));
        assert_eq!(
            table.entries[0].sampler,
            ax_model::dm::material::texture_sampler::FILTER_LINEAR
                | ax_model::dm::material::texture_sampler::CLAMP_U
        );
        assert_eq!(
            crate::a3d::check_texture_slots(&materials, table.entries.len()),
            Ok(())
        );

        // Le refus est dit, avec son code.
        assert!(
            compiled
                .warnings
                .iter()
                .any(|warning| warning.contains("photo.jpg") && warning.contains("E-3004")),
            "{:?}",
            compiled.warnings
        );
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

    /// Un node portant un mesh glTF à deux primitives : la première aux UV
    /// répétées — u jusqu'à 3, v jusqu'à 2 —, la seconde décalée en x ∈ [2, 3].
    fn gltf_deux_primitives() -> String {
        let mut buffer = Vec::new();
        for value in [0.0f32, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0] {
            buffer.extend_from_slice(&value.to_le_bytes());
        }
        for value in [0.0f32, 0.0, 3.0, 0.0, 0.0, 2.0] {
            buffer.extend_from_slice(&value.to_le_bytes());
        }
        for index in [0u16, 1, 2] {
            buffer.extend_from_slice(&index.to_le_bytes());
        }
        // Alignement des positions suivantes sur quatre octets.
        buffer.extend_from_slice(&[0, 0]);
        for value in [2.0f32, 0.0, 0.0, 3.0, 0.0, 0.0, 2.0, 1.0, 0.0] {
            buffer.extend_from_slice(&value.to_le_bytes());
        }
        for index in [0u16, 1, 2] {
            buffer.extend_from_slice(&index.to_le_bytes());
        }
        format!(
            concat!(
                r#"{{"asset":{{"version":"2.0"}},"scene":0,"scenes":[{{"nodes":[0]}}],"#,
                r#""nodes":[{{"name":"caisse","mesh":0}}],"#,
                r#""meshes":[{{"primitives":["#,
                r#"{{"attributes":{{"POSITION":0,"TEXCOORD_0":1}},"indices":2}},"#,
                r#"{{"attributes":{{"POSITION":3}},"indices":4}}]}}],"#,
                r#""accessors":["#,
                r#"{{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","#,
                r#""min":[0.0,0.0,0.0],"max":[1.0,1.0,0.0]}},"#,
                r#"{{"bufferView":1,"componentType":5126,"count":3,"type":"VEC2"}},"#,
                r#"{{"bufferView":2,"componentType":5123,"count":3,"type":"SCALAR"}},"#,
                r#"{{"bufferView":3,"componentType":5126,"count":3,"type":"VEC3","#,
                r#""min":[2.0,0.0,0.0],"max":[3.0,1.0,0.0]}},"#,
                r#"{{"bufferView":4,"componentType":5123,"count":3,"type":"SCALAR"}}],"#,
                r#""bufferViews":[{{"buffer":0,"byteOffset":0,"byteLength":36}},"#,
                r#"{{"buffer":0,"byteOffset":36,"byteLength":24}},"#,
                r#"{{"buffer":0,"byteOffset":60,"byteLength":6}},"#,
                r#"{{"buffer":0,"byteOffset":68,"byteLength":36}},"#,
                r#"{{"buffer":0,"byteOffset":104,"byteLength":6}}],"#,
                r#""buffers":[{{"byteLength":{longueur},"uri":"data:application/octet-stream;base64,{tampon}"}}]}}"#,
            ),
            longueur = buffer.len(),
            tampon = encode_base64(&buffer),
        )
    }

    #[test]
    fn t291_un_node_porte_toutes_ses_primitives_sous_leurs_plages_d_uv() {
        let compiled = compile(
            gltf_deux_primitives().as_bytes(),
            SourceFormat::Gltf,
            &OPTIONS,
            |_| None,
        )
        .expect("compilation refusée");

        // La boîte couvre les deux primitives : la seconde n'y entrait pas.
        assert_eq!(
            compiled.bounds,
            Some(Aabb {
                min: [0.0, 0.0, 0.0],
                max: [3.0, 1.0, 0.0],
            })
        );
        assert_eq!(compiled.mesh_count, 2);

        let file = A3dFile::open(&compiled.bytes, A3dLimits::new(1 << 20)).expect("relecture");
        assert_eq!(file.header().compiler_version, COMPILER_VERSION);
        let nodes = crate::a3d::decode_nodes(
            &file
                .section(SectionTag::NODE)
                .expect("NODE")
                .expect("section absente"),
        )
        .expect("table des nodes")
        .nodes;
        assert_eq!(nodes[0].meshes(), 0..2, "le node porte ses deux primitives");

        let geom = crate::a3d::decode_geometry(
            &file
                .section(SectionTag::GEOM)
                .expect("GEOM")
                .expect("section absente"),
        )
        .expect("géométrie");
        // La primitive répétée garde ses tuiles, sous la plage [0, 3] ; l'autre,
        // sans UV, reste dans [0, 1].
        let plage = UvRange::from_bits(geom.meshes[0].uv0_range).expect("plage valide");
        assert_eq!((plage.min(), plage.span()), (0, 3));
        assert_eq!(geom.meshes[1].uv0_range, 0);

        let premier = &geom.meshes[0];
        let debut = premier.vertex_offset as usize;
        let relues: Vec<[f32; 2]> = geom.vertices[debut..debut + premier.vertex_count as usize]
            .iter()
            .map(|vertex| vertex.uv0.map(|q| plage.dequantize(q)))
            .collect();
        let max = relues.iter().fold([f32::MIN; 2], |max, uv| {
            [max[0].max(uv[0]), max[1].max(uv[1])]
        });
        assert_eq!(max, [3.0, 2.0], "u jusqu'à 3, v jusqu'à 2 : {relues:?}");
    }
}
