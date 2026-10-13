//! Décodage d'un conteneur A3D au chargement, tel que le jeu le fait (IF-06,
//! ADR-119, ADR-122).
//!
//! Le moteur (`axion_asset_load`) et `axion-cli validate` passent par ces
//! fonctions : R-830 veut qu'un conteneur que la CLI déclare valide soit un
//! conteneur que le jeu charge, et R-861 qu'un asset livré compilé ne saute
//! jamais la validation.

use crate::a3d::{
    check_texture_slots, decode_geometry, decode_materials, decode_nodes, decode_textures,
    A3dError, A3dFile, DecodedGeometry, DecodedMaterials, DecodedTextures, SectionMask, SectionTag,
    TextureTable,
};
use ax_model::dm::material::{MaterialDesc, NO_TEXTURE};
use ax_model::dm::scene::NodeDesc;

/// Sections que le rendu charge : `NODE` et `GEOM` (ADR-119), `MATL` et `TEXR`
/// (ADR-122).
#[must_use]
pub fn render_sections() -> SectionMask {
    SectionMask::of(&[
        SectionTag::NODE,
        SectionTag::GEOM,
        SectionTag::MATL,
        SectionTag::TEXR,
    ])
}

/// Les sections d'un conteneur, décodées et contrôlées.
#[derive(Debug, Clone, PartialEq)]
pub struct DecodedSections {
    /// Nodes de la section `NODE`.
    pub nodes: Vec<NodeDesc>,
    /// Contenu de la section `GEOM`.
    pub geometry: DecodedGeometry,
    /// Matériaux de la section `MATL`, slots contrôlés contre `textures`.
    pub materials: Vec<MaterialDesc>,
    /// Table de la section `TEXR`.
    pub textures: TextureTable,
}

/// Décode les sections demandées d'un conteneur déjà ouvert.
///
/// Une section demandée mais absente du fichier n'est pas une faute (R-880) :
/// elle est simplement vide. `MATL` et `TEXR` ne se décodent qu'ensemble : les
/// slots des matériaux désignent les entrées de la table des textures.
///
/// # Errors
///
/// [`A3dError`] si une section est invalide (R-882, R-890, R-900..R-902,
/// `E-3007`), ou si un matériau désigne une texture hors de la table.
pub fn decode_sections(
    file: &A3dFile<'_>,
    sections: SectionMask,
) -> Result<DecodedSections, A3dError> {
    let nodes = if sections.contains(SectionTag::NODE) {
        match file.section(SectionTag::NODE)? {
            Some(bytes) => decode_nodes(&bytes)?.nodes,
            None => Vec::new(),
        }
    } else {
        Vec::new()
    };
    let geometry = if sections.contains(SectionTag::GEOM) {
        match file.section(SectionTag::GEOM)? {
            Some(bytes) => decode_geometry(&bytes)?,
            None => empty_geometry(),
        }
    } else {
        empty_geometry()
    };
    let (materials, textures) =
        if sections.contains(SectionTag::MATL) && sections.contains(SectionTag::TEXR) {
            decode_material_tables(file)?
        } else {
            (Vec::new(), TextureTable::default())
        };
    Ok(DecodedSections {
        nodes,
        geometry,
        materials,
        textures,
    })
}

fn empty_geometry() -> DecodedGeometry {
    DecodedGeometry {
        meshes: Vec::new(),
        vertices: Vec::new(),
        indices: Vec::new(),
    }
}

/// Décode `MATL` et `TEXR`, puis vérifie que chaque slot des matériaux désigne
/// une entrée de la table des textures (ADR-122 §6).
///
/// Une section d'une disposition inconnue — l'ancien `MATL` provisoire, ou une
/// disposition à venir — est ignorée, sans refuser l'asset (ADR-122 §1, R-880) :
///
/// - sans matériaux lisibles, aucun matériau : le client applique le matériau
///   par défaut ;
/// - sans textures lisibles, aucune texture, et les slots des matériaux sont
///   vidés : chacun prend la texture neutre.
///
/// Une texture ne sert que par un slot : sans matériau, la table des textures
/// n'est pas gardée — elle occuperait l'arène `PERSISTENT` pour rien.
fn decode_material_tables(
    file: &A3dFile<'_>,
) -> Result<(Vec<MaterialDesc>, TextureTable), A3dError> {
    let materials = match file.section(SectionTag::MATL)? {
        Some(bytes) => match decode_materials(&bytes)? {
            DecodedMaterials::Materials(materials) => materials,
            DecodedMaterials::UnknownLayout(_) => Vec::new(),
        },
        None => Vec::new(),
    };
    let textures = match file.section(SectionTag::TEXR)? {
        Some(bytes) => match decode_textures(&bytes)? {
            DecodedTextures::Textures(table) => Some(table),
            DecodedTextures::UnknownLayout(_) => None,
        },
        None => Some(TextureTable::default()),
    };

    if materials.is_empty() {
        return Ok((materials, TextureTable::default()));
    }
    let (materials, textures) = match textures {
        Some(textures) => (materials, textures),
        None => (
            materials.into_iter().map(without_textures).collect(),
            TextureTable::default(),
        ),
    };
    // Une section `TEXR` absente alors que des slots la désignent est une faute
    // du fichier, pas une disposition inconnue : refusée (`E-3007`).
    check_texture_slots(&materials, textures.entries.len())?;
    Ok((materials, textures))
}

/// Le matériau, ses six slots vidés : chacun prend la texture neutre. C'est ce
/// que devient un matériau quand la table des textures est d'une disposition
/// inconnue.
#[must_use]
pub fn without_textures(material: MaterialDesc) -> MaterialDesc {
    MaterialDesc {
        albedo_tex: NO_TEXTURE,
        normal_tex: NO_TEXTURE,
        orm_tex: NO_TEXTURE,
        emissive_tex: NO_TEXTURE,
        height_tex: NO_TEXTURE,
        damage_tex: NO_TEXTURE,
        ..material
    }
}
