//! Assets chargés par le natif (IF-06, ADR-119).
//!
//! §4.10 : Rust possède les assets compilés ; Java n'en détient qu'un handle
//! (DM-01) et les vues que lui prêtent les tampons. Chaque asset chargé occupe un
//! slot dont la génération avance à la libération (R-110) : un handle rendu ne
//! désigne plus rien — jamais l'asset qui a pris sa place.
//!
//! Le décodage ([`decode_render_asset`]) est une fonction pure : la frontière
//! l'appelle **hors** du verrou de session, sur une copie du conteneur, pour
//! qu'un gros asset ne fige pas le cycle de simulation.

use core::fmt;
use core::mem::size_of;

use ax_asset::a3d::{
    check_texture_slots, decode_geometry, decode_materials, decode_nodes, decode_textures,
    A3dError, A3dFile, A3dLimits, DecodedGeometry, DecodedMaterials, DecodedTextures, SectionMask,
    SectionTag, TextureTable,
};
use ax_model::dm::handle::Handle;
use ax_model::dm::material::{texture_source, MaterialDesc, TextureDesc, NO_TEXTURE};
use ax_model::dm::render::{encode_geometry_transfer, encode_material_transfer};
use ax_model::dm::scene::NodeDesc;
use ax_scene::{rest_draws, SceneError};

/// Sections que `axion_asset_load` sait charger : `NODE` et `GEOM` (ADR-119),
/// `MATL` et `TEXR` (ADR-122).
#[must_use]
pub fn supported_sections() -> SectionMask {
    SectionMask::of(&[
        SectionTag::NODE,
        SectionTag::GEOM,
        SectionTag::MATL,
        SectionTag::TEXR,
    ])
}

/// Masque de sections qu'accepte `axion_asset_load`, ou `None`.
///
/// Sont refusés le masque vide — un chargement qui « réussirait » sans rien
/// charger ferait croire le contraire —, un bit hors de [`supported_sections`],
/// et l'une de `MATL` et `TEXR` sans l'autre : les slots des matériaux
/// désignent les entrées de la table des textures, si bien qu'aucune des deux
/// ne se contrôle seule, et des textures que rien ne désigne ne servent à rien.
#[must_use]
pub fn requested_sections(bits: u32) -> Option<SectionMask> {
    if bits == 0 || bits & !supported_sections().bits() != 0 {
        return None;
    }
    let sections = SectionMask::from_bits(bits);
    (sections.contains(SectionTag::MATL) == sections.contains(SectionTag::TEXR)).then_some(sections)
}

/// Ce qui empêche de charger un asset.
#[derive(Debug, Clone, PartialEq)]
pub enum LoadError {
    /// Le conteneur ou l'une de ses sections est invalide.
    Container(A3dError),
    /// La hiérarchie de l'asset ne se laisse pas propager, ou désigne un mesh
    /// absent.
    Scene(SceneError),
    /// Les octets ne sont pas ceux de l'asset annoncé.
    WrongAsset {
        /// Identifiant demandé par l'appelant.
        expected: u64,
        /// Identifiant porté par l'en-tête du conteneur.
        found: u64,
    },
    /// Un transfert ne se décrit pas sur des dénombrements `u32` — ce que les
    /// plafonds de R-143 et de C-22 rendent impossible pour des sections
    /// décodées.
    TooLarge,
}

impl LoadError {
    /// Code de l'ANNEXE A.1.
    ///
    /// Un conteneur ou une section invalide garde le code de sa cause (`E-3007`,
    /// `E-3008`…), une hiérarchie le sien (`E-3021`, `E-3050`). Des octets qui ne
    /// sont pas ceux de l'asset annoncé sont une faute d'appelant sur la donnée
    /// d'un tampon : `E-2002`.
    #[must_use]
    pub fn code(&self) -> i32 {
        match self {
            LoadError::Container(error) => error.code(),
            LoadError::Scene(error) => error.code(),
            LoadError::WrongAsset { .. } => -2002,
            LoadError::TooLarge => -3007,
        }
    }
}

impl fmt::Display for LoadError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            LoadError::Container(error) => write!(formatter, "conteneur A3D : {error}"),
            LoadError::Scene(error) => write!(formatter, "hiérarchie de l'asset : {error}"),
            LoadError::WrongAsset { expected, found } => write!(
                formatter,
                "le tampon porte l'asset {found:#018x}, pas l'asset {expected:#018x} annoncé"
            ),
            LoadError::TooLarge => formatter.write_str("transfert trop grand pour être décrit"),
        }
    }
}

impl From<A3dError> for LoadError {
    fn from(error: A3dError) -> Self {
        LoadError::Container(error)
    }
}

impl From<SceneError> for LoadError {
    fn from(error: SceneError) -> Self {
        LoadError::Scene(error)
    }
}

/// Un asset chargé, résident dans la session.
///
/// La géométrie est conservée **sous sa forme de transfert** (ADR-119 §3) : les
/// `MeshDesc`, `Vertex` et indices de DM-04 s'y lisent en place, et la remettre à
/// Java ne coûte qu'une copie — sous verrou, c'est ce qui compte. La garder aussi
/// décodée en doublerait la mémoire pour rien. Les matériaux de même (ADR-122
/// §6) ; la table des textures reste celle de `TEXR`, d'où les PNG embarqués
/// partent un par un.
#[derive(Debug, Clone, PartialEq)]
pub struct LoadedAsset {
    asset_id: u64,
    sections: SectionMask,
    transfer: Vec<u8>,
    nodes: Vec<NodeDesc>,
    /// Transfert des matériaux ; vide sans `MATL | TEXR`.
    materials: Vec<u8>,
    /// Table de `TEXR` ; vide sans `MATL | TEXR`.
    textures: TextureTable,
}

impl LoadedAsset {
    /// Identifiant de l'asset (DM-01).
    #[must_use]
    pub fn asset_id(&self) -> u64 {
        self.asset_id
    }

    /// Sections demandées au chargement.
    #[must_use]
    pub fn sections(&self) -> SectionMask {
        self.sections
    }

    /// Transfert de géométrie (ADR-119), si `NODE` et `GEOM` ont été chargés.
    ///
    /// Sans les nodes, la géométrie n'a pas de pose : la rendre ferait dessiner
    /// chaque pièce à l'origine.
    #[must_use]
    pub fn geometry_transfer(&self) -> Option<&[u8]> {
        let both =
            self.sections.contains(SectionTag::NODE) && self.sections.contains(SectionTag::GEOM);
        both.then_some(self.transfer.as_slice())
    }

    /// Transfert des matériaux et des textures (ADR-122 §6), si `MATL` et `TEXR`
    /// ont été chargés.
    #[must_use]
    pub fn material_transfer(&self) -> Option<&[u8]> {
        let both =
            self.sections.contains(SectionTag::MATL) && self.sections.contains(SectionTag::TEXR);
        both.then_some(self.materials.as_slice())
    }

    /// Octets PNG de la texture de rang `texture` (ADR-122 §6) ; `None` si le
    /// rang est hors de la table — vide sans `TEXR` — ou si la texture n'est pas
    /// embarquée.
    #[must_use]
    pub fn embedded_texture(&self, texture: u32) -> Option<&[u8]> {
        let index = usize::try_from(texture).ok()?;
        if self.textures.entries.get(index)?.source != texture_source::EMBEDDED {
            return None;
        }
        self.textures.data(index)
    }

    /// Octets résidents, tels qu'imputés à l'arène `PERSISTENT` (R-480).
    #[must_use]
    pub fn resident_bytes(&self) -> usize {
        self.transfer.len()
            + self.nodes.len() * size_of::<NodeDesc>()
            + self.materials.len()
            + self.textures.entries.len() * size_of::<TextureDesc>()
            + self.textures.blob.len()
    }
}

/// Charge un asset depuis son conteneur A3D : validation du conteneur, contrôle
/// de l'identité, décodage des sections demandées, liste de dessin au repos,
/// table des matériaux et des textures.
///
/// `sections` doit avoir été admis par [`requested_sections`] — c'est à
/// l'appelant de l'avoir vérifié ; `MATL` et `TEXR` ne se décodent qu'ensemble.
/// Une section demandée mais absente du fichier n'est pas une faute (R-880) :
/// elle est simplement vide.
///
/// # Errors
///
/// [`LoadError::Container`] si le conteneur ou une section est invalide (R-882,
/// R-890, R-900..R-902, `E-3007`), ou si un matériau désigne une texture hors
/// de la table ; [`LoadError::WrongAsset`] si l'en-tête porte un autre
/// identifiant ; [`LoadError::Scene`] si la hiérarchie est invalide ou désigne
/// un mesh absent.
pub fn decode_render_asset(
    container: &[u8],
    asset_id: u64,
    sections: SectionMask,
    limits: A3dLimits,
) -> Result<LoadedAsset, LoadError> {
    let file = A3dFile::open(container, limits)?;
    let found = file.header().asset_id;
    if found != asset_id {
        return Err(LoadError::WrongAsset {
            expected: asset_id,
            found,
        });
    }

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

    // La pose n'existe qu'avec les deux : des nodes sans géométrie ne désignent
    // rien à dessiner, une géométrie sans nodes rien qui la place.
    let draws = if sections.contains(SectionTag::NODE) && sections.contains(SectionTag::GEOM) {
        rest_draws(&nodes, geometry.meshes.len())?
    } else {
        Vec::new()
    };

    let transfer = encode_geometry_transfer(
        &geometry.meshes,
        &geometry.vertices,
        &geometry.indices,
        &draws,
    )
    .ok_or(LoadError::TooLarge)?;

    let (materials, textures) =
        if sections.contains(SectionTag::MATL) && sections.contains(SectionTag::TEXR) {
            let (materials, textures) = decode_material_tables(&file)?;
            let transfer = encode_material_transfer(&materials, &textures.entries, &textures.blob)
                .ok_or(LoadError::TooLarge)?;
            (transfer, textures)
        } else {
            (Vec::new(), TextureTable::default())
        };

    Ok(LoadedAsset {
        asset_id,
        sections,
        transfer,
        nodes,
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

/// Le matériau, ses six slots vidés : chacun prend la texture neutre.
fn without_textures(material: MaterialDesc) -> MaterialDesc {
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

/// Un slot du magasin : sa génération, et l'asset qu'il porte s'il est occupé.
#[derive(Debug)]
struct Slot {
    generation: u32,
    asset: Option<LoadedAsset>,
}

/// Assets chargés, adressés par handle générationnel (DM-01).
#[derive(Debug, Default)]
pub struct AssetStore {
    slots: Vec<Slot>,
    free: Vec<u32>,
    live: usize,
}

impl AssetStore {
    /// Magasin vide.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Range un asset et rend son handle.
    ///
    /// Un slot libéré est réemployé, sous une génération nouvelle : le handle
    /// qui le désignait reste périmé.
    pub fn insert(&mut self, asset: LoadedAsset) -> Handle {
        self.live += 1;
        if let Some(index) = self.free.pop() {
            let slot = &mut self.slots[index as usize];
            slot.asset = Some(asset);
            return Handle::new(index, slot.generation);
        }
        let index = u32::try_from(self.slots.len()).expect("plus de 2³² assets chargés");
        // Génération 1 : 0 est réservée à « absent » (R-111).
        self.slots.push(Slot {
            generation: 1,
            asset: Some(asset),
        });
        Handle::new(index, 1)
    }

    /// Asset désigné par le handle, s'il est encore chargé.
    #[must_use]
    pub fn get(&self, handle: Handle) -> Option<&LoadedAsset> {
        let slot = self.slots.get(handle.index as usize)?;
        if handle.generation == 0 || slot.generation != handle.generation {
            return None;
        }
        slot.asset.as_ref()
    }

    /// Retire l'asset désigné et rend ce qu'il portait ; `None` si le handle est
    /// périmé, sans effet de bord (R-110).
    pub fn remove(&mut self, handle: Handle) -> Option<LoadedAsset> {
        let slot = self.slots.get_mut(handle.index as usize)?;
        if handle.generation == 0 || slot.generation != handle.generation {
            return None;
        }
        let asset = slot.asset.take()?;
        // La génération avance ; elle ne repasse jamais par 0 (R-111).
        slot.generation = slot.generation.wrapping_add(1).max(1);
        self.free.push(handle.index);
        self.live -= 1;
        Some(asset)
    }

    /// Nombre d'assets chargés.
    #[must_use]
    pub fn live(&self) -> usize {
        self.live
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ax_asset::a3d::{A3dWriter, MATL_LAYOUT, TEXR_LAYOUT};
    use ax_asset::collider::ColliderMode;
    use ax_asset::compile::{compile, CompileOptions};
    use ax_asset::import::{ImportLimits, SourceFormat};
    use ax_asset::optimize::LodOptions;
    use ax_model::dm::render::{
        geometry_transfer_len, material_transfer_len, RestDraw, MATERIAL_TRANSFER_HEADER_BYTES,
        TRANSFER_HEADER_BYTES,
    };

    const LIMITS: A3dLimits = A3dLimits::new(1 << 24);

    /// Produit par le compilateur 7 (R-893) : `MATL` et `TEXR` d'ADR-122, deux
    /// matériaux dont « bois », texturé d'un PNG de 2×2 embarqué.
    const COMPILATEUR_7: &[u8] =
        include_bytes!("../../ax-asset/tests/fixtures/a3d/v1.1-compilateur-7.a3d");

    /// Produit par le compilateur 6, avant ADR-122 : `MATL` à la disposition
    /// provisoire, pas de `TEXR`.
    const MATL_PROVISOIRE: &[u8] =
        include_bytes!("../../ax-asset/tests/fixtures/a3d/v1.1-matl-provisoire.a3d");

    fn asset_id(container: &[u8]) -> u64 {
        A3dFile::open(container, LIMITS)
            .expect("conteneur")
            .header()
            .asset_id
    }

    fn section_of(container: &[u8], tag: SectionTag) -> Vec<u8> {
        A3dFile::open(container, LIMITS)
            .expect("conteneur")
            .section(tag)
            .expect("section lisible")
            .expect("section présente")
    }

    /// [`COMPILATEUR_7`] réécrit, ses sections `MATL` et `TEXR` remplacées par
    /// celles données ; `None` omet la section.
    fn recompose(matl: Option<&[u8]>, texr: Option<&[u8]>) -> Vec<u8> {
        let file = A3dFile::open(COMPILATEUR_7, LIMITS).expect("fixture");
        let header = file.header();
        let mut writer =
            A3dWriter::new(header.asset_id, header.source_hash, header.compiler_version);
        for tag in [SectionTag::NODE, SectionTag::GEOM] {
            writer
                .section(tag, &section_of(COMPILATEUR_7, tag))
                .expect("écriture");
        }
        for (tag, payload) in [(SectionTag::MATL, matl), (SectionTag::TEXR, texr)] {
            if let Some(payload) = payload {
                writer.section(tag, payload).expect("écriture");
            }
        }
        writer.finish().expect("écriture")
    }

    /// En-tête d'une section `MATL` ou `TEXR` sans entrée, de disposition donnée.
    fn empty_section(layout: u32) -> Vec<u8> {
        [0u32, layout, 0, 0]
            .iter()
            .flat_map(|word| word.to_le_bytes())
            .collect()
    }

    fn load_all(container: &[u8]) -> Result<LoadedAsset, LoadError> {
        decode_render_asset(container, asset_id(container), supported_sections(), LIMITS)
    }

    /// Les quatre mots de l'en-tête d'un transfert de matériaux.
    fn counts(transfer: &[u8]) -> [u32; 4] {
        [0, 4, 8, 12].map(|at| u32_at(transfer, at))
    }

    fn material_at(transfer: &[u8], rank: usize) -> MaterialDesc {
        let at = MATERIAL_TRANSFER_HEADER_BYTES + rank * MaterialDesc::BYTES;
        MaterialDesc::read_le(
            transfer[at..at + MaterialDesc::BYTES]
                .try_into()
                .expect("96 octets"),
        )
    }

    fn texture_at(transfer: &[u8], materials: usize, rank: usize) -> TextureDesc {
        let at = MATERIAL_TRANSFER_HEADER_BYTES
            + materials * MaterialDesc::BYTES
            + rank * TextureDesc::BYTES;
        TextureDesc::read_le(
            transfer[at..at + TextureDesc::BYTES]
                .try_into()
                .expect("16 octets"),
        )
    }

    /// Un cube unité en OBJ, compilé de bout en bout par l'écrivain de production.
    fn cube(asset_id: u64) -> Vec<u8> {
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
        let options = CompileOptions {
            asset_id,
            source_hash: 0,
            limits: ImportLimits::new(1 << 20),
            dynamic_body: true,
            lod: LodOptions::DEFAULT,
            collider_mode: ColliderMode::None,
        };
        compile(CUBE_OBJ.as_bytes(), SourceFormat::Obj, &options, |_| None)
            .expect("compilation refusée")
            .bytes
    }

    fn u32_at(bytes: &[u8], at: usize) -> u32 {
        u32::from_le_bytes(bytes[at..at + 4].try_into().expect("quatre octets"))
    }

    #[test]
    fn un_node_a_deux_meshes_donne_deux_dessins() {
        // Un objet OBJ à deux matériaux : `tobj` le découpe en deux morceaux,
        // l'import les range sous un node qui porte les deux (ADR-122 §5), et la
        // liste de dessin en émet un par mesh — même node, même pose.
        const CAISSE_OBJ: &str = "\
mtllib caisse.mtl
o caisse
usemtl bois
v 0.0 0.0 0.0
v 1.0 0.0 0.0
v 0.0 1.0 0.0
v 1.0 1.0 0.0
f 1 2 3
usemtl metal
f 2 4 3
";
        let options = CompileOptions {
            asset_id: 7,
            source_hash: 0,
            limits: ImportLimits::new(1 << 20),
            dynamic_body: true,
            lod: LodOptions::DEFAULT,
            collider_mode: ColliderMode::None,
        };
        let bytes = compile(CAISSE_OBJ.as_bytes(), SourceFormat::Obj, &options, |_| {
            Some(b"newmtl bois\nKd 1 0 0\nnewmtl metal\nKd 0 0 1\n".to_vec())
        })
        .expect("compilation refusée")
        .bytes;
        let asset =
            decode_render_asset(&bytes, 7, supported_sections(), LIMITS).expect("chargement");
        let transfer = asset.geometry_transfer().expect("NODE | GEOM chargés");

        let meshes = u32_at(transfer, 0) as usize;
        let draws = u32_at(transfer, 12) as usize;
        assert_eq!(meshes, 2);
        assert_eq!(draws, 2, "un dessin par mesh du node");
        let draws_start = transfer.len() - draws * RestDraw::BYTES;
        let dessin = |rank: usize| {
            let at = draws_start + rank * RestDraw::BYTES;
            RestDraw::read_le(
                transfer[at..at + RestDraw::BYTES]
                    .try_into()
                    .expect("64 octets"),
            )
        };
        let (premier, second) = (dessin(0), dessin(1));
        assert_eq!((premier.mesh, second.mesh), (0, 1));
        assert_eq!(premier.node, second.node);
        assert_eq!(premier.model, second.model);
    }

    #[test]
    fn un_cube_compile_se_charge_et_se_transfere() {
        let asset =
            decode_render_asset(&cube(42), 42, supported_sections(), LIMITS).expect("chargement");
        let transfer = asset.geometry_transfer().expect("NODE | GEOM chargés");

        let meshes = u32_at(transfer, 0) as usize;
        let vertices = u32_at(transfer, 4) as usize;
        let indices = u32_at(transfer, 8) as usize;
        let draws = u32_at(transfer, 12) as usize;
        assert!(meshes >= 1 && vertices >= 8);
        assert!(draws >= 1, "un node affiche le cube");
        assert_eq!(
            Some(transfer.len()),
            geometry_transfer_len(meshes, vertices, indices, draws)
        );

        // Chaque entrée de dessin désigne un mesh existant (ADR-119 §3).
        let draws_start = transfer.len() - draws * RestDraw::BYTES;
        for rank in 0..draws {
            let at = draws_start + rank * RestDraw::BYTES;
            let draw = RestDraw::read_le(
                transfer[at..at + RestDraw::BYTES]
                    .try_into()
                    .expect("64 octets"),
            );
            assert!((draw.mesh as usize) < meshes);
        }
        assert!(asset.resident_bytes() >= transfer.len());
        assert!(transfer.len() > TRANSFER_HEADER_BYTES);
    }

    #[test]
    fn des_octets_d_un_autre_asset_sont_refuses_e2002() {
        let refus = decode_render_asset(&cube(42), 43, supported_sections(), LIMITS).unwrap_err();
        assert_eq!(
            refus,
            LoadError::WrongAsset {
                expected: 43,
                found: 42
            }
        );
        assert_eq!(refus.code(), -2002);
    }

    #[test]
    fn une_section_geom_corrompue_est_refusee_e3007() {
        let original = cube(7);
        // On vise un octet de GEOM, section chargée : son CRC le voit avant toute
        // décompression (R-882). Viser « le dernier octet » toucherait peut-être
        // une section non demandée, et le test passerait pour une mauvaise raison.
        let file = A3dFile::open(&original, LIMITS).expect("relecture");
        let geom = *file
            .entries()
            .iter()
            .find(|entry| entry.tag == SectionTag::GEOM)
            .expect("GEOM présente");
        let mut corrompu = original.clone();
        corrompu[geom.offset as usize] ^= 0xFF;

        let refus = decode_render_asset(&corrompu, 7, supported_sections(), LIMITS).unwrap_err();
        assert_eq!(
            refus,
            LoadError::Container(A3dError::SectionCorrupted(SectionTag::GEOM))
        );
        assert_eq!(refus.code(), -3007);
    }

    #[test]
    fn un_conteneur_tronque_ou_etranger_est_refuse() {
        let original = cube(7);
        let tronque = &original[..original.len() - 1];
        assert!(decode_render_asset(tronque, 7, supported_sections(), LIMITS).is_err());
        assert!(decode_render_asset(b"pas un A3D", 7, supported_sections(), LIMITS).is_err());
    }

    #[test]
    fn sans_les_nodes_la_geometrie_n_est_pas_remise() {
        let asset = decode_render_asset(&cube(1), 1, SectionMask::of(&[SectionTag::GEOM]), LIMITS)
            .expect("chargement");
        assert_eq!(asset.geometry_transfer(), None);
        // La géométrie reste résidente, sans liste de dessin.
        assert!(asset.resident_bytes() > TRANSFER_HEADER_BYTES);
    }

    #[test]
    fn un_handle_rendu_est_perime_et_son_slot_reemploye() {
        let asset =
            decode_render_asset(&cube(5), 5, supported_sections(), LIMITS).expect("chargement");
        let mut store = AssetStore::new();

        let premier = store.insert(asset.clone());
        assert_eq!(premier, Handle::new(0, 1));
        assert_eq!(store.get(premier).map(LoadedAsset::asset_id), Some(5));
        assert_eq!(store.live(), 1);

        assert!(store.remove(premier).is_some());
        assert_eq!(store.live(), 0);
        // Périmé : ni lu, ni retiré une seconde fois, sans effet de bord.
        assert!(store.get(premier).is_none());
        assert!(store.remove(premier).is_none());
        assert_eq!(store.live(), 0);

        // Le slot sert de nouveau, sous une autre génération.
        let second = store.insert(asset);
        assert_eq!(second, Handle::new(0, 2));
        assert!(
            store.get(premier).is_none(),
            "l'ancien handle ne désigne pas le nouvel asset"
        );
        assert!(store.get(second).is_some());

        // « Absent » {0,0} et un index hors du magasin ne désignent rien.
        assert!(store.get(Handle::ABSENT).is_none());
        assert!(store.get(Handle::new(9, 1)).is_none());
    }

    #[test]
    fn matl_et_texr_se_demandent_ensemble() {
        let bit = |tag: SectionTag| SectionMask::of(&[tag]).bits();
        let [node, geom, matl, texr, phys] = [
            SectionTag::NODE,
            SectionTag::GEOM,
            SectionTag::MATL,
            SectionTag::TEXR,
            SectionTag::PHYS,
        ]
        .map(bit);
        for admis in [
            node | geom,
            node | geom | matl | texr,
            matl | texr,
            node,
            geom,
        ] {
            assert_eq!(
                requested_sections(admis).map(SectionMask::bits),
                Some(admis),
                "{admis:#b}"
            );
        }
        for refuse in [
            0,
            matl,
            texr,
            node | geom | matl,
            node | geom | texr,
            node | geom | phys,
            1 << 31,
        ] {
            assert_eq!(requested_sections(refuse), None, "{refuse:#b}");
        }
    }

    #[test]
    fn t270_les_materiaux_et_leur_png_se_chargent_et_se_transferent() {
        let asset = load_all(COMPILATEUR_7).expect("chargement");
        let transfer = asset.material_transfer().expect("MATL | TEXR chargés");
        assert_eq!(counts(transfer), [2, 1, 0, 0], "deux matériaux, un PNG");
        assert_eq!(Some(transfer.len()), material_transfer_len(2, 1, 0));
        assert_eq!(material_at(transfer, 0).albedo_tex, 0, "« bois » texturé");

        // L'entrée embarquée ne porte que la taille de son PNG : ses octets
        // partent seuls, par leur rang.
        let png = asset.embedded_texture(0).expect("PNG embarqué");
        let entry = texture_at(transfer, 2, 0);
        assert_eq!(entry.source, texture_source::EMBEDDED);
        assert_eq!(
            (entry.data_offset, entry.data_size as usize),
            (0, png.len())
        );
        assert_eq!(ax_asset::png::dimensions(png), Some((2, 2)));
        assert_eq!(asset.embedded_texture(1), None, "hors de la table");
        assert_eq!(asset.embedded_texture(u32::MAX), None);

        // Sans MATL | TEXR, rien de cela n'est chargé ni compté ; avec, la
        // table des textures l'est en plus du transfert.
        let node_geom = SectionMask::of(&[SectionTag::NODE, SectionTag::GEOM]);
        let without =
            decode_render_asset(COMPILATEUR_7, asset_id(COMPILATEUR_7), node_geom, LIMITS)
                .expect("chargement");
        assert_eq!(without.material_transfer(), None);
        assert_eq!(without.embedded_texture(0), None, "TEXR non chargée");
        assert_eq!(
            asset.resident_bytes(),
            without.resident_bytes() + transfer.len() + size_of::<TextureDesc>() + png.len()
        );
    }

    #[test]
    fn t270_un_matl_provisoire_donne_une_table_vide_sans_refuser_l_asset() {
        // ADR-122 §1 : la disposition provisoire est ignorée ; pas de TEXR.
        let asset = load_all(MATL_PROVISOIRE).expect("chargement");
        assert_eq!(asset.material_transfer(), Some(&[0u8; 16][..]));
        assert!(asset.geometry_transfer().is_some(), "le reste se charge");
    }

    #[test]
    fn t270_un_texr_illisible_laisse_les_materiaux_sans_texture() {
        let matl = section_of(COMPILATEUR_7, SectionTag::MATL);
        let DecodedMaterials::Materials(originals) = decode_materials(&matl).expect("MATL") else {
            panic!("MATL d'ADR-122 attendue");
        };
        let container = recompose(Some(&matl), Some(&empty_section(TEXR_LAYOUT + 1)));

        let asset = load_all(&container).expect("chargement");
        let transfer = asset.material_transfer().expect("MATL | TEXR chargés");
        assert_eq!(counts(transfer), [2, 0, 0, 0]);
        for (rank, original) in originals.iter().enumerate() {
            let material = material_at(transfer, rank);
            assert_eq!(material.texture_slots(), [NO_TEXTURE; 6], "texture neutre");
            assert_eq!(material, without_textures(*original), "le reste demeure");
        }
        assert_eq!(asset.embedded_texture(0), None);
    }

    #[test]
    fn t270_des_slots_sans_texr_sont_refuses_e3007() {
        let container = recompose(Some(&section_of(COMPILATEUR_7, SectionTag::MATL)), None);
        let refus = load_all(&container).unwrap_err();
        assert!(
            matches!(
                refus,
                LoadError::Container(A3dError::MalformedSection {
                    tag: SectionTag::MATL,
                    ..
                })
            ),
            "{refus:?}"
        );
        assert_eq!(refus.code(), -3007);

        // Sans MATL | TEXR demandés, la faute n'est pas lue : l'asset se charge.
        let node_geom = SectionMask::of(&[SectionTag::NODE, SectionTag::GEOM]);
        assert!(decode_render_asset(&container, asset_id(&container), node_geom, LIMITS).is_ok());
    }

    #[test]
    fn t270_des_textures_que_rien_ne_designe_ne_sont_pas_gardees() {
        // Un MATL d'une disposition inconnue ne donne aucun matériau : la table
        // des textures, que plus rien ne désigne, n'est pas gardée.
        let texr = section_of(COMPILATEUR_7, SectionTag::TEXR);
        let unknown_matl = empty_section(MATL_LAYOUT + 1);
        let asset = load_all(&recompose(Some(&unknown_matl), Some(&texr))).expect("chargement");
        assert_eq!(asset.material_transfer(), Some(&[0u8; 16][..]));
        assert_eq!(asset.embedded_texture(0), None);

        // Elle reste contrôlée : une réserve d'en-tête non nulle la refuse.
        let mut corrupted = texr;
        corrupted[12] = 1;
        let refus = load_all(&recompose(Some(&unknown_matl), Some(&corrupted))).unwrap_err();
        assert_eq!(refus.code(), -3007);
    }

    #[test]
    fn t270_une_texture_de_ressource_voyage_par_son_chemin() {
        const PANNEAU_OBJ: &str = "\
mtllib panneau.mtl
o panneau
usemtl peint
v 0.0 0.0 0.0
v 1.0 0.0 0.0
v 0.0 1.0 0.0
vt 0.0 0.0
vt 1.0 0.0
vt 0.0 1.0
f 1/1 2/2 3/3
";
        let options = CompileOptions {
            asset_id: 9,
            source_hash: 0,
            limits: ImportLimits::new(1 << 20),
            dynamic_body: true,
            lod: LodOptions::DEFAULT,
            collider_mode: ColliderMode::None,
        };
        let bytes = compile(PANNEAU_OBJ.as_bytes(), SourceFormat::Obj, &options, |_| {
            Some(b"newmtl peint\nKd 1 1 1\nmap_Kd tex/panneau.png\n".to_vec())
        })
        .expect("compilation refusée")
        .bytes;

        let asset = load_all(&bytes).expect("chargement");
        let transfer = asset.material_transfer().expect("MATL | TEXR chargés");
        let path = b"tex/panneau.png";
        assert_eq!(counts(transfer), [1, 1, path.len() as u32, 0]);
        assert_eq!(material_at(transfer, 0).albedo_tex, 0);
        let entry = texture_at(transfer, 1, 0);
        assert_eq!(entry.source, texture_source::RESOURCE);
        assert_eq!(
            (entry.data_offset, entry.data_size as usize),
            (0, path.len())
        );
        assert!(transfer.ends_with(path), "le chemin clôt le transfert");
        // Une ressource ne voyage pas par `axion_asset_texture` : Java la charge
        // des resource packs.
        assert_eq!(asset.embedded_texture(0), None);
    }
}
