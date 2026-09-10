//! Ce que le validateur examine.

use ax_model::dm::geometry::{MeshDesc, Vertex};
use ax_model::dm::integrity::{DeformRegionDesc, StructuralLinkDesc};
use ax_model::dm::physics::ColliderDesc;
use ax_model::dm::scene::{NodeDesc, PartDesc};

/// Asset compilé, tel qu'il se présente à la validation.
///
/// Ce sont des **vues** : le validateur ne possède rien et ne copie rien. Il
/// est appelé deux fois sur la même donnée — à la compilation puis au
/// chargement (R-540) — et la seconde fois sur des tranches lues en place dans
/// un fichier A3D, qu'il serait absurde de dupliquer pour les relire.
#[derive(Debug, Default, Clone, Copy)]
pub struct AssetView<'a> {
    /// Hiérarchie de nodes, en ordre topologique.
    pub nodes: &'a [NodeDesc],
    /// Meshes.
    pub meshes: &'a [MeshDesc],
    /// Sommets de l'asset, tous meshes confondus.
    pub vertices: &'a [Vertex],
    /// Indices de l'asset, tous meshes confondus.
    pub indices: &'a [u32],
    /// Colliders.
    pub colliders: &'a [ColliderDesc],
    /// Parts.
    pub parts: &'a [PartDesc],
    /// Régions de déformation.
    pub regions: &'a [DeformRegionDesc],
    /// Liaisons structurelles.
    pub links: &'a [StructuralLinkDesc],
    /// Bitsets d'ancrage des régions, concaténés.
    pub anchor_masks: &'a [u8],
    /// Nombre d'os du squelette.
    pub bone_count: usize,
    /// Nombre de matériaux.
    pub material_count: usize,
    /// Nombre de textures.
    pub texture_count: usize,
    /// Nombre d'animations.
    pub animation_count: usize,
    /// Noms de l'asset, par catégorie, pour la vérification d'unicité.
    pub names: &'a [NamedEntry<'a>],
    /// Les colliders sont portés par un body dynamique.
    ///
    /// R-160 n'interdit `TriMesh` et `Heightfield` que là ; sur un body
    /// statique — la géométrie du monde — ce sont les formes normales.
    pub dynamic_body: bool,
}

/// Un nom déclaré par l'asset, avec sa catégorie.
///
/// Les noms sont uniques **par catégorie** : un node et une part peuvent
/// s'appeler pareil, deux parts non.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct NamedEntry<'a> {
    /// Catégorie : `"node"`, `"part"`, `"région"`, `"socket"`…
    pub category: &'static str,
    /// Nom, tel que l'auteur l'a écrit.
    pub name: &'a str,
}

impl<'a> NamedEntry<'a> {
    /// Compose une entrée nommée.
    #[must_use]
    pub const fn new(category: &'static str, name: &'a str) -> Self {
        Self { category, name }
    }
}
