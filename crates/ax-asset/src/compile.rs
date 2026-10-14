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
use crate::collider::build_colliders;
use crate::import::{
    import_gltf, import_obj, import_stl, plain_default_material, ImportError, ImportLimits,
    ImportedAsset, ImportedMaterial, SourceFormat,
};
use crate::optimize::{optimize, Aabb, LodTable};
use crate::options::{AssetOptions, MaterialDefaults, NormalsMode, OptionsOrigin, UpAxis};
use crate::validate::{validate, AssetView, NamedEntry, ValidationReport};
use ax_model::dm::geometry::{MeshDesc, Vertex};
use ax_model::dm::material::{texture_source, MaterialDesc};
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
/// (ADR-122 §4 et §5) ; 9 — la section `GEOM` est écrite dès qu'un mesh existe,
/// même sans triangle : un objet sans face ne laisse plus un node désigner un
/// mesh absent, que le chargement refusait ; 10 — sections compressées par zstd
/// au niveau 9 que fixe R-552, au lieu de 3 ; en jeu, l'en-tête porte l'empreinte
/// de la source, comme en CLI (R-830).
pub const COMPILER_VERSION: u32 = 10;

/// Empreinte FNV-1a 64 bits d'une source, champ `source_hash` de l'en-tête A3D.
///
/// La même que celle des noms (DM-01), sur les octets bruts : une source binaire
/// — un `.glb`, un `.stl` — n'est pas de l'UTF-8. `axion-cli` et le jeu la
/// calculent par cette seule fonction, si bien que la même source donne le même
/// en-tête des deux côtés (R-830).
#[must_use]
pub fn source_hash(bytes: &[u8]) -> u64 {
    const FNV_OFFSET: u64 = 0xcbf2_9ce4_8422_2325;
    const FNV_PRIME: u64 = 0x0000_0100_0000_01b3;
    let mut hash = FNV_OFFSET;
    for byte in bytes {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(FNV_PRIME);
    }
    hash
}

/// Ce qui empêche de compiler un asset.
#[derive(Debug, Clone, PartialEq)]
pub enum CompileError {
    /// La source n'a pas pu être lue (C-21).
    Import(ImportError),
    /// L'asset importé est invalide (C-22).
    Invalid(ValidationReport),
    /// Le conteneur n'a pas pu être écrit (C-24).
    Container(crate::a3d::A3dError),
    /// Les options de l'auteur sont refusées (PARTIE 6.4, ADR-135).
    Options(crate::options::OptionsError),
}

impl CompileError {
    /// Code de l'ANNEXE A.1 correspondant.
    #[must_use]
    pub fn code(&self) -> i32 {
        match self {
            CompileError::Import(error) => error.code(),
            CompileError::Invalid(report) => report.code(),
            CompileError::Container(error) => error.code(),
            CompileError::Options(error) => error.code(),
        }
    }
}

impl fmt::Display for CompileError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            CompileError::Import(error) => write!(formatter, "import : {error}"),
            CompileError::Options(error) => write!(formatter, "options : {error}"),
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
    /// Ce que l'auteur a demandé : les options de la PARTIE 6.4 (ADR-135), lues
    /// du `.axion.json` par [`crate::options::parse`], ou leurs défauts. Le mode
    /// de génération automatique de colliders en fait partie (C-32, R-620) :
    /// [`ColliderMode::None`](crate::collider::ColliderMode::None) par défaut,
    /// rien n'est généré sans demande.
    pub authored: AssetOptions,
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
    let (mut asset, mut warnings, root_extras) = import(source, format, options, resolve)?;
    let options = &effective_options(options, root_extras.as_deref(), &mut warnings)?;
    let authored = &options.authored;
    // Sans option, l'asset n'est pas touché : la sortie reste celle d'avant, au bit
    // près (R-870).
    if authored.scale != 1.0 || authored.up_axis != UpAxis::Y {
        reframe(&mut asset, authored.scale, authored.up_axis);
    }
    warnings.extend(oversized_textures(&asset, authored.max_texture_size));
    if authored.generate_normals == NormalsMode::Always {
        // Toutes les normales sont recalculées (ADR-135) : celles de l'auteur ne
        // sont pas jugées, C-23 les remplace.
        asset.missing_normals = vec![true; asset.vertices.len()];
    }

    // Les normales absentes de la source sont exemptées : C-23 les génère.
    check(&asset, options, &asset.missing_normals)?;

    let optimized = optimize(&mut asset, &authored.lod, authored.generate_tangents);
    warnings.extend(optimized.warnings);
    if !authored.generate_tangents && asset.materials.iter().any(ImportedMaterial::has_normal_map) {
        warnings.push(
            "generate_tangents est faux : un mesh à carte de normales sans tangentes \
             d'auteur n'en reçoit pas"
                .to_owned(),
        );
    }

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
        &authored.collision,
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
) -> Result<(ImportedAsset, Vec<String>, Option<String>), CompileError> {
    let (asset, mut warnings, root_extras) = import_source(source, format, options, resolve)?;
    warnings.extend(asset.texture_refusals.iter().map(ToString::to_string));
    warnings.extend(asset.material_warnings.iter().cloned());
    Ok((asset, warnings, root_extras))
}

fn import_source(
    source: &[u8],
    format: SourceFormat,
    options: &CompileOptions,
    resolve: impl FnMut(&str) -> Option<Vec<u8>>,
) -> Result<(ImportedAsset, Vec<String>, Option<String>), CompileError> {
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
            Ok((asset, warnings, report.root_extras))
        }
        SourceFormat::Obj => {
            let text = core::str::from_utf8(source).map_err(|_| {
                CompileError::Import(ImportError::Malformed {
                    format,
                    detail: "source OBJ non UTF-8".to_owned(),
                })
            })?;
            let mut resolve = resolve;
            let mut asset = import_obj(text, &options.limits, |path| {
                resolve(path).and_then(|bytes| String::from_utf8(bytes).ok())
            })
            .map_err(CompileError::Import)?;
            apply_material_defaults(&mut asset, &options.authored.materials);
            Ok((asset, Vec::new(), None))
        }
        SourceFormat::Stl => {
            let mut asset = import_stl(source, &options.limits).map_err(CompileError::Import)?;
            apply_material_defaults(&mut asset, &options.authored.materials);
            Ok((asset, Vec::new(), None))
        }
    }
}

/// Les options qui s'appliquent (ADR-135, point 5) : celles d'un `.axion.json` s'il
/// y en a un — un bloc `axion` des extras racine d'un glTF est alors ignoré, et
/// signalé —, sinon ce bloc, lu par le même lecteur, sinon les défauts.
fn effective_options(
    options: &CompileOptions,
    root_extras: Option<&str>,
    warnings: &mut Vec<String>,
) -> Result<CompileOptions, CompileError> {
    let Some(extras) = root_extras else {
        return Ok(*options);
    };
    let Some(block) = crate::options::gltf_root_block(extras).map_err(CompileError::Options)?
    else {
        return Ok(*options);
    };
    if options.authored.origin == OptionsOrigin::File {
        warnings.push(
            "bloc « axion » des extras racine ignoré : le fichier .axion.json l'emporte en \
             entier (ADR-135)"
                .to_owned(),
        );
        return Ok(*options);
    }
    let parsed = crate::options::parse_gltf_block(&block).map_err(CompileError::Options)?;
    warnings.extend(parsed.warnings);
    Ok(CompileOptions {
        authored: parsed.options,
        ..*options
    })
}

/// `scale` et `up_axis` (R-871) : l'asset entier passe dans le repère du moteur,
/// avant que C-22 ne le juge.
///
/// La transformation `G` — rotation de −90° autour de X pour une source Z en haut,
/// puis échelle uniforme — est **cuite** dans l'asset : chaque sommet reçoit `G`, et
/// chaque node son conjugué `G·L·G⁻¹` (translation tournée et mise à l'échelle, axe
/// de rotation tourné, échelle propre permutée). Un sommet arrive ainsi, dans le
/// monde, en `G·W·v`, là où l'auteur l'a placé, tourné et mis à l'échelle. Les
/// colliders, tirés ensuite des meshes dans leur propre espace, suivent d'eux-mêmes.
///
/// La rotation `(x, y, z) → (x, z, −y)` est une permutation : exacte, normales
/// quantifiées comprises.
fn reframe(asset: &mut ImportedAsset, scale: f32, up_axis: UpAxis) {
    let z_up = up_axis == UpAxis::Z;
    let turn = |v: [f32; 3]| if z_up { [v[0], v[2], -v[1]] } else { v };
    let turn_i8 = |v: [i8; 4]| {
        if z_up {
            [v[0], v[2], v[1].saturating_neg(), v[3]]
        } else {
            v
        }
    };
    let scaled = |v: [f32; 3]| [v[0] * scale, v[1] * scale, v[2] * scale];

    for vertex in &mut asset.vertices {
        vertex.position = scaled(turn(vertex.position));
        vertex.normal = turn_i8(vertex.normal);
        vertex.tangent = turn_i8(vertex.tangent);
    }
    for mesh in &mut asset.meshes {
        // Sous (x, z, −y), le minimum en Z vient du maximum en Y.
        let (low, high) = if z_up {
            (
                [mesh.aabb_min[0], mesh.aabb_min[2], -mesh.aabb_max[1]],
                [mesh.aabb_max[0], mesh.aabb_max[2], -mesh.aabb_min[1]],
            )
        } else {
            (mesh.aabb_min, mesh.aabb_max)
        };
        mesh.aabb_min = scaled(low);
        mesh.aabb_max = scaled(high);
    }
    for node in &mut asset.nodes {
        node.local.translation = scaled(turn(node.local.translation));
        let rotation = node.local.rotation;
        let axis = turn([rotation[0], rotation[1], rotation[2]]);
        node.local.rotation = [axis[0], axis[1], axis[2], rotation[3]];
        if z_up {
            let own = node.local.scale;
            node.local.scale = [own[0], own[2], own[1]];
        }
    }
}

/// Le matériau par défaut d'une source qui n'en porte aucun — un STL, un OBJ sans
/// `.mtl` — prend la rugosité et le métal du bloc `materials` (ADR-135). Il se
/// reconnaît à son nom vide et à ses valeurs ; un matériau de `.mtl` porte le nom
/// de son `newmtl`, et ceux d'un glTF les défauts de leur propre spécification.
fn apply_material_defaults(asset: &mut ImportedAsset, defaults: &MaterialDefaults) {
    let plain = plain_default_material();
    for material in &mut asset.materials {
        if material.name.is_empty() && material.desc == plain {
            material.desc.roughness = defaults.roughness;
            material.desc.metallic = defaults.metallic;
        }
    }
}

/// Les textures embarquées au-delà de `max_texture_size` : signalées, non réduites
/// — le natif ne décode pas les images (R-532), C-26 les réduira (ADR-135).
fn oversized_textures(asset: &ImportedAsset, max_size: u32) -> Vec<String> {
    asset
        .textures
        .entries
        .iter()
        .enumerate()
        .filter(|(_, entry)| {
            entry.source == texture_source::EMBEDDED
                && (u32::from(entry.width) > max_size || u32::from(entry.height) > max_size)
        })
        .map(|(index, entry)| {
            format!(
                "texture {index} ({}×{}) au-delà de max_texture_size {max_size} : signalée, \
                 non réduite avant C-26",
                entry.width, entry.height
            )
        })
        .collect()
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
    // Un mesh sans triangle reste un mesh : ses nodes le désignent. Ne pas écrire
    // `GEOM` pour lui laisserait un node pointer vers un mesh absent, ce que le
    // chargement refuse (`rest_draws`).
    if !asset.meshes.is_empty() || !asset.vertices.is_empty() || !asset.indices.is_empty() {
        // `compress: false` (PARTIE 6.4) écrit la géométrie telle quelle.
        let geometry = geometry_bytes(asset);
        if options.authored.compress {
            writer.compressed_section(SectionTag::GEOM, &geometry)
        } else {
            writer.section(SectionTag::GEOM, &geometry)
        }
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
    use crate::collider::ColliderMode;
    use ax_model::dm::geometry::UvRange;

    const OPTIONS: CompileOptions = CompileOptions {
        asset_id: 0x1234,
        source_hash: 0x5678,
        limits: ImportLimits::new(1 << 20),
        dynamic_body: true,
        authored: AssetOptions::DEFAULT,
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
    fn lod_les_lod_voyagent_dans_la_section_lodm() {
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
    fn lod_sans_lod_genere_la_section_lodm_n_est_pas_ecrite() {
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
            authored: AssetOptions::with_collider_mode(ColliderMode::AutoBox),
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
            authored: AssetOptions::with_collider_mode(ColliderMode::AutoSphere),
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
            authored: AssetOptions::with_collider_mode(ColliderMode::AutoBox),
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
    fn t211_un_objet_sans_face_garde_son_mesh_dans_geom() {
        // Un objet OBJ sans face donne un mesh sans triangle, que son node désigne :
        // `GEOM` doit le porter, sans quoi le node désignerait un mesh absent.
        let compiled = compile(b"o vide\n", SourceFormat::Obj, &OPTIONS, |_| None)
            .expect("compilation refusée");
        let file = A3dFile::open(&compiled.bytes, A3dLimits::new(1 << 20)).expect("relecture");
        let geom = file
            .section(SectionTag::GEOM)
            .expect("GEOM lisible")
            .expect("GEOM écrite pour un mesh sans triangle");
        let geometry = crate::a3d::decode_geometry(&geom).expect("GEOM décodée");
        assert_eq!(geometry.meshes.len(), 1);
        assert!(geometry.vertices.is_empty() && geometry.indices.is_empty());
        let nodes = crate::a3d::decode_nodes(
            &file
                .section(SectionTag::NODE)
                .expect("NODE")
                .expect("NODE écrite"),
        )
        .expect("NODE décodée")
        .nodes;
        for node in &nodes {
            for mesh in node.meshes() {
                assert!(
                    (mesh as usize) < geometry.meshes.len(),
                    "le node désigne le mesh {mesh}, absent"
                );
            }
        }
    }

    /// Les options de l'auteur, sur les identifiants et plafonds des tests.
    fn avec(authored: AssetOptions) -> CompileOptions {
        CompileOptions {
            authored,
            ..OPTIONS
        }
    }

    #[test]
    fn options_compress_faux_ecrit_la_geometrie_sans_compression() {
        let source = grille_obj(8);
        let geom = |options: &CompileOptions| {
            let compiled = compile(source.as_bytes(), SourceFormat::Obj, options, |_| None)
                .expect("compilation refusée");
            let file = A3dFile::open(&compiled.bytes, A3dLimits::new(1 << 20)).expect("relecture");
            let entry = *file
                .entries()
                .iter()
                .find(|entry| entry.tag == SectionTag::GEOM)
                .expect("GEOM");
            let payload = file
                .section(SectionTag::GEOM)
                .expect("GEOM lisible")
                .expect("GEOM");
            (entry.is_compressed(), payload)
        };
        let (compressee, contenu) = geom(&OPTIONS);
        let (brute, meme_contenu) = geom(&avec(AssetOptions {
            compress: false,
            ..AssetOptions::DEFAULT
        }));
        assert!(compressee, "par défaut, la géométrie est compressée");
        assert!(!brute, "compress: false l'écrit telle quelle");
        assert_eq!(contenu, meme_contenu, "le contenu ne change pas");
    }

    #[test]
    fn options_le_materiau_par_defaut_prend_le_bloc_materials() {
        let matl = |options: &CompileOptions| {
            let compiled = compile(&stl_triangle(), SourceFormat::Stl, options, |_| None)
                .expect("compilation refusée");
            let file = A3dFile::open(&compiled.bytes, A3dLimits::new(1 << 20)).expect("relecture");
            let bytes = file.section(SectionTag::MATL).expect("MATL").expect("MATL");
            match crate::a3d::decode_materials(&bytes).expect("MATL décodé") {
                crate::a3d::DecodedMaterials::Materials(materials) => materials[0],
                other => panic!("disposition inattendue : {other:?}"),
            }
        };
        let defaut = matl(&OPTIONS);
        assert_eq!((defaut.roughness, defaut.metallic), (0.6, 0.0), "§6.4");
        let choisi = matl(&avec(AssetOptions {
            materials: crate::options::MaterialDefaults {
                roughness: 0.2,
                metallic: 1.0,
            },
            ..AssetOptions::DEFAULT
        }));
        assert_eq!((choisi.roughness, choisi.metallic), (0.2, 1.0));
    }

    #[test]
    fn options_generate_normals_always_recalcule_celles_de_l_auteur() {
        // Un triangle du plan XY, tourné vers +Z, dont l'auteur a écrit la normale -Z.
        const RETOURNE: &str = "o t\nv 0 0 0\nv 1 0 0\nv 0 1 0\nvn 0 0 -1\nf 1//1 2//1 3//1\n";
        let normale_z = |options: &CompileOptions| {
            let compiled = compile(RETOURNE.as_bytes(), SourceFormat::Obj, options, |_| None)
                .expect("compilation refusée");
            let file = A3dFile::open(&compiled.bytes, A3dLimits::new(1 << 20)).expect("relecture");
            let bytes = file.section(SectionTag::GEOM).expect("GEOM").expect("GEOM");
            let geometry = crate::a3d::decode_geometry(&bytes).expect("GEOM décodé");
            geometry.vertices[0].normal[2]
        };
        assert!(
            normale_z(&OPTIONS) < 0,
            "if_missing garde la normale de l'auteur"
        );
        assert!(
            normale_z(&avec(AssetOptions {
                generate_normals: NormalsMode::Always,
                ..AssetOptions::DEFAULT
            })) > 0,
            "always la recalcule depuis la géométrie"
        );
    }

    #[test]
    fn options_generate_tangents_faux_n_en_genere_aucune_et_le_dit() {
        // Un carré dont le matériau porte une carte de normales : C-23 lui génère
        // des tangentes, sauf si l'auteur l'interdit.
        const CARRE: &str = "mtllib carre.mtl\no carre\nv 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\n\
                             vt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\nvn 0 0 1\nusemtl relief\n\
                             f 1/1/1 2/2/1 3/3/1\nf 1/1/1 3/3/1 4/4/1\n";
        const MTL: &str = "newmtl relief\nKd 1 1 1\nmap_Bump relief.png\n";
        let compiler = |options: &CompileOptions| {
            let compiled = compile(CARRE.as_bytes(), SourceFormat::Obj, options, |path| {
                (path == "carre.mtl").then(|| MTL.as_bytes().to_vec())
            })
            .expect("compilation refusée");
            let file = A3dFile::open(&compiled.bytes, A3dLimits::new(1 << 20)).expect("relecture");
            let bytes = file.section(SectionTag::GEOM).expect("GEOM").expect("GEOM");
            let geometry = crate::a3d::decode_geometry(&bytes).expect("GEOM décodé");
            (geometry.vertices[0].tangent, compiled.warnings)
        };
        let (avec_tangentes, _) = compiler(&OPTIONS);
        assert_ne!(
            avec_tangentes, [0; 4],
            "par défaut, une tangente est générée"
        );
        let (sans, avertissements) = compiler(&avec(AssetOptions {
            generate_tangents: false,
            ..AssetOptions::DEFAULT
        }));
        assert_eq!(sans, [0; 4], "generate_tangents: false n'en génère aucune");
        assert!(
            avertissements
                .iter()
                .any(|avertissement| avertissement.contains("generate_tangents est faux")),
            "{avertissements:?}"
        );
    }

    #[test]
    fn options_une_texture_embarquee_trop_grande_est_signalee() {
        let source = gltf_texture(&png_2x2());
        let avertissements = |options: &CompileOptions| {
            compile(source.as_bytes(), SourceFormat::Gltf, options, |_| None)
                .expect("compilation refusée")
                .warnings
        };
        let signalee = |avertissements: &[String]| {
            avertissements
                .iter()
                .any(|avertissement| avertissement.contains("max_texture_size"))
        };
        assert!(!signalee(&avertissements(&OPTIONS)), "2×2 tient dans 2048");
        let petites = avertissements(&avec(AssetOptions {
            max_texture_size: 1,
            ..AssetOptions::DEFAULT
        }));
        assert!(signalee(&petites), "{petites:?}");
    }

    #[test]
    fn options_up_axis_z_et_scale_placent_l_asset_dans_le_repere_du_moteur() {
        // Un triangle dressé selon Z, l'axe vertical d'un export Blender.
        const DEBOUT: &str = "o t\nv 0 0 0\nv 1 0 0\nv 0 0 2\nvn 0 -1 0\nf 1//1 2//1 3//1\n";
        let compiler = |options: &CompileOptions| {
            compile(DEBOUT.as_bytes(), SourceFormat::Obj, options, |_| None)
                .expect("compilation refusée")
        };
        let proche = |a: [f32; 3], b: [f32; 3]| a.iter().zip(&b).all(|(x, y)| (x - y).abs() < 1e-3);
        let defaut = compiler(&OPTIONS).bounds.expect("bornes");
        assert!(proche(defaut.max, [1.0, 0.0, 2.0]), "{defaut:?}");

        let redresse = compiler(&avec(AssetOptions {
            up_axis: UpAxis::Z,
            scale: 0.5,
            ..AssetOptions::DEFAULT
        }));
        let bornes = redresse.bounds.expect("bornes");
        // (x, y, z) → (x, z, −y), puis × 0,5 : le sommet haut de 2 en Z l'est de 1 en Y.
        assert!(proche(bornes.min, [0.0, 0.0, 0.0]), "{bornes:?}");
        assert!(proche(bornes.max, [0.5, 1.0, 0.0]), "{bornes:?}");
        let file = A3dFile::open(&redresse.bytes, A3dLimits::new(1 << 20)).expect("relecture");
        let bytes = file.section(SectionTag::GEOM).expect("GEOM").expect("GEOM");
        let geometry = crate::a3d::decode_geometry(&bytes).expect("GEOM décodé");
        // La normale −Y de la source devient +Z : tournée, pas recalculée.
        assert!(
            geometry.vertices[0].normal[2] > 0,
            "{:?}",
            geometry.vertices[0].normal
        );
    }

    #[test]
    fn options_reframe_conjugue_les_nodes_et_garde_chaque_sommet_a_sa_place() {
        // Le node d'un OBJ, translaté, tourné de 90° autour de Z et étiré, sous Z en
        // haut : la translation tourne, l'axe de rotation tourne, l'échelle se permute.
        let (mut asset, _, _) = import(
            b"o t\nv 0 0 4\nv 1 0 4\nv 0 1 4\nf 1 2 3\n",
            SourceFormat::Obj,
            &OPTIONS,
            |_| None,
        )
        .expect("import");
        let demi = core::f32::consts::FRAC_1_SQRT_2;
        asset.nodes[0].local = ax_model::dm::geometry::Transform {
            translation: [1.0, 2.0, 3.0],
            rotation: [0.0, 0.0, demi, demi],
            scale: [2.0, 3.0, 1.0],
        };
        let premier = asset.vertices[0].position;
        assert_eq!(premier, [0.0, 0.0, 4.0]);

        reframe(&mut asset, 2.0, UpAxis::Z);
        let local = asset.nodes[0].local;
        assert_eq!(local.translation, [2.0, 6.0, -4.0]);
        assert_eq!(local.rotation, [0.0, demi, 0.0, demi]);
        assert_eq!(local.scale, [2.0, 1.0, 3.0]);
        assert_eq!(asset.vertices[0].position, [0.0, 8.0, 0.0]);
    }

    /// glTF d'un triangle, ses `extras` racine donnés.
    fn gltf_triangle(extras: &str) -> String {
        format!(
            concat!(
                r#"{{"asset":{{"version":"2.0"}},"scene":0,"scenes":[{{"nodes":[0]}}],"#,
                r#""nodes":[{{"name":"t","mesh":0}}],"#,
                r#""meshes":[{{"primitives":[{{"attributes":{{"POSITION":0}},"indices":2}}]}}],"#,
                r#""accessors":[{{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","#,
                r#""min":[0.0,0.0,0.0],"max":[1.0,1.0,0.0]}},"#,
                r#"{{"bufferView":1,"componentType":5126,"count":3,"type":"VEC2"}},"#,
                r#"{{"bufferView":2,"componentType":5123,"count":3,"type":"SCALAR"}}],"#,
                r#""bufferViews":[{{"buffer":0,"byteOffset":0,"byteLength":36}},"#,
                r#"{{"buffer":0,"byteOffset":36,"byteLength":24}},"#,
                r#"{{"buffer":0,"byteOffset":60,"byteLength":6}}],"#,
                r#""buffers":[{{"byteLength":66,"uri":"data:application/octet-stream;base64,{buffer}"}}],"#,
                r#""extras":{extras}}}"#,
            ),
            buffer = encode_base64(&triangle_buffer()),
            extras = extras,
        )
    }

    #[test]
    fn options_le_bloc_des_extras_gltf_s_applique_sans_fichier() {
        let source = gltf_triangle(r#"{"axion":{"schema":1,"scale":2}}"#);
        let compiled = compile(source.as_bytes(), SourceFormat::Gltf, &OPTIONS, |_| None)
            .expect("compilation refusée");
        let bornes = compiled.bounds.expect("bornes");
        assert!((bornes.max[0] - 2.0).abs() < 1e-3, "{bornes:?}");
    }

    #[test]
    fn options_un_fichier_l_emporte_sur_le_bloc_des_extras_et_le_dit() {
        let source = gltf_triangle(r#"{"axion":{"schema":1,"scale":2}}"#);
        let du_fichier = avec(AssetOptions {
            origin: OptionsOrigin::File,
            ..AssetOptions::DEFAULT
        });
        let compiled = compile(source.as_bytes(), SourceFormat::Gltf, &du_fichier, |_| None)
            .expect("compilation refusée");
        let bornes = compiled.bounds.expect("bornes");
        assert!(
            (bornes.max[0] - 1.0).abs() < 1e-3,
            "le bloc est ignoré : {bornes:?}"
        );
        assert!(
            compiled
                .warnings
                .iter()
                .any(|avertissement| avertissement.contains("bloc « axion »")),
            "{:?}",
            compiled.warnings
        );
    }

    #[test]
    fn options_un_bloc_des_extras_fautif_refuse_la_compilation_a_son_chemin() {
        let source = gltf_triangle(r#"{"axion":{"schema":1,"scal":2}}"#);
        let refus = compile(source.as_bytes(), SourceFormat::Gltf, &OPTIONS, |_| None)
            .expect_err("bloc fautif accepté");
        let CompileError::Options(erreur) = &refus else {
            panic!("refus inattendu : {refus}");
        };
        assert_eq!(erreur.faults[0].path, "$.extras.axion.scal");
        assert_eq!(refus.code(), crate::options::E_INVALID_OPTION);
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
