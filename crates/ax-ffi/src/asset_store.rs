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
    decode_geometry, decode_nodes, A3dError, A3dFile, A3dLimits, DecodedGeometry, SectionMask,
    SectionTag,
};
use ax_model::dm::handle::Handle;
use ax_model::dm::render::encode_geometry_transfer;
use ax_model::dm::scene::NodeDesc;
use ax_scene::{rest_draws, SceneError};

/// Sections que `axion_asset_load` sait charger (ADR-119) : `NODE` et `GEOM`.
#[must_use]
pub fn supported_sections() -> SectionMask {
    SectionMask::of(&[SectionTag::NODE, SectionTag::GEOM])
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
    /// La géométrie ne se décrit pas sur des dénombrements `u32` — ce que les
    /// plafonds de R-143 rendent impossible pour une section décodée.
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
            LoadError::TooLarge => formatter.write_str("géométrie trop grande pour être décrite"),
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
/// décodée en doublerait la mémoire pour rien.
#[derive(Debug, Clone, PartialEq)]
pub struct LoadedAsset {
    asset_id: u64,
    sections: SectionMask,
    transfer: Vec<u8>,
    nodes: Vec<NodeDesc>,
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

    /// Octets résidents, tels qu'imputés à l'arène `PERSISTENT` (R-480).
    #[must_use]
    pub fn resident_bytes(&self) -> usize {
        self.transfer.len() + self.nodes.len() * size_of::<NodeDesc>()
    }
}

/// Charge un asset depuis son conteneur A3D : validation du conteneur, contrôle
/// de l'identité, décodage des sections demandées, liste de dessin au repos.
///
/// `sections` doit être inclus dans [`supported_sections`] — c'est à l'appelant
/// de l'avoir vérifié. Une section demandée mais absente du fichier n'est pas une
/// faute (R-880) : elle est simplement vide.
///
/// # Errors
///
/// [`LoadError::Container`] si le conteneur ou une section est invalide (R-882,
/// R-890, R-900..R-902, `E-3007`) ; [`LoadError::WrongAsset`] si l'en-tête porte
/// un autre identifiant ; [`LoadError::Scene`] si la hiérarchie est invalide ou
/// désigne un mesh absent.
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

    Ok(LoadedAsset {
        asset_id,
        sections,
        transfer,
        nodes,
    })
}

fn empty_geometry() -> DecodedGeometry {
    DecodedGeometry {
        meshes: Vec::new(),
        vertices: Vec::new(),
        indices: Vec::new(),
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
    use ax_asset::collider::ColliderMode;
    use ax_asset::compile::{compile, CompileOptions};
    use ax_asset::import::{ImportLimits, SourceFormat};
    use ax_asset::optimize::LodOptions;
    use ax_model::dm::render::{geometry_transfer_len, RestDraw, TRANSFER_HEADER_BYTES};

    const LIMITS: A3dLimits = A3dLimits::new(1 << 24);

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
}
