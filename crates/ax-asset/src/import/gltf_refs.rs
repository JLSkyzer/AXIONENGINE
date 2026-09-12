//! Vérification des références d'un document glTF, avant tout déréférencement.
//!
//! # Pourquoi ce module existe
//!
//! Un document glTF est un graphe d'indices : une primitive désigne un
//! accesseur par son rang, l'accesseur désigne une `bufferView`, celle-ci un
//! `buffer`. Rien n'oblige un fichier à ce que ces rangs existent.
//!
//! Le crate `gltf` les déréférence par `.nth(index).unwrap()` — donc il
//! **panique** sur un indice hors bornes. Et son propre validateur,
//! `gltf-json`, panique aussi : il écrit `root.accessors[index]` sans borne, ce
//! qui fait de la validation elle-même une surface d'attaque. Les deux ont été
//! trouvés par fuzzing (R-903).
//!
//! Sauter la validation du crate — `Gltf::from_slice_without_validation` — ne
//! suffit donc pas : cela déplace la panique du validateur vers l'accesseur.
//! Il faut vérifier les références **nous-mêmes**, avant d'en déréférencer une
//! seule.
//!
//! # Ce que le module vérifie
//!
//! Que tout indice d'un document désigne un élément qui existe. Rien d'autre :
//! ni les longueurs, ni les types, ni la cohérence sémantique — C-22 s'en
//! charge sur l'asset importé, et R-530 sur les extensions.
//!
//! Le balayage est **exhaustif sur le document**, pas seulement sur ce que
//! l'import lit aujourd'hui. Se limiter à ce qu'on déréférence obligerait à
//! revenir ici à chaque champ nouvellement lu, et c'est le genre de dette qu'on
//! oublie jusqu'à la panique suivante.

use super::{ImportError, SourceFormat};
use gltf::json::Root;

/// Vérifie que tout indice du document désigne un élément existant.
///
/// # Errors
///
/// [`ImportError::Malformed`] au premier indice hors bornes, en nommant ce qui
/// le porte et ce qu'il désignait.
pub(super) fn check_references(root: &Root, format: SourceFormat) -> Result<(), ImportError> {
    let verificateur = Verificateur { root, format };
    verificateur.verifie_tout()
}

struct Verificateur<'a> {
    root: &'a Root,
    format: SourceFormat,
}

impl Verificateur<'_> {
    /// Rend l'erreur d'un indice hors bornes.
    ///
    /// Le message nomme **où** l'indice se trouve et **quoi** il désignait :
    /// « meshes[2].primitives[0].attributes » dit à l'auteur du pack quelle
    /// ligne de son fichier reprendre, là où « indice invalide » l'oblige à
    /// chercher.
    fn hors_bornes(&self, ou: &str, quoi: &str, index: usize, total: usize) -> ImportError {
        ImportError::Malformed {
            format: self.format,
            detail: format!("{ou} désigne {quoi} n°{index}, il n'y en a que {total}"),
        }
    }

    fn verifie(&self, ou: &str, quoi: &str, index: usize, total: usize) -> Result<(), ImportError> {
        if index >= total {
            return Err(self.hors_bornes(ou, quoi, index, total));
        }
        Ok(())
    }

    fn verifie_tout(&self) -> Result<(), ImportError> {
        self.buffer_views()?;
        self.accessors()?;
        self.images()?;
        self.textures()?;
        self.materials()?;
        self.meshes()?;
        self.nodes()?;
        self.skins()?;
        self.scenes()?;
        self.animations()?;
        Ok(())
    }

    fn buffer_views(&self) -> Result<(), ImportError> {
        let total = self.root.buffers.len();
        for (rang, vue) in self.root.buffer_views.iter().enumerate() {
            self.verifie(
                &format!("bufferViews[{rang}].buffer"),
                "le buffer",
                vue.buffer.value(),
                total,
            )?;
        }
        Ok(())
    }

    fn accessors(&self) -> Result<(), ImportError> {
        let total = self.root.buffer_views.len();
        for (rang, accesseur) in self.root.accessors.iter().enumerate() {
            if let Some(vue) = accesseur.buffer_view {
                self.verifie(
                    &format!("accessors[{rang}].bufferView"),
                    "la bufferView",
                    vue.value(),
                    total,
                )?;
            }
            // Un accesseur « sparse » porte deux références de plus, et rien
            // n'oblige un fichier à les rendre valides sous prétexte que la
            // première l'est.
            if let Some(sparse) = &accesseur.sparse {
                self.verifie(
                    &format!("accessors[{rang}].sparse.indices.bufferView"),
                    "la bufferView",
                    sparse.indices.buffer_view.value(),
                    total,
                )?;
                self.verifie(
                    &format!("accessors[{rang}].sparse.values.bufferView"),
                    "la bufferView",
                    sparse.values.buffer_view.value(),
                    total,
                )?;
            }
        }
        Ok(())
    }

    fn images(&self) -> Result<(), ImportError> {
        let total = self.root.buffer_views.len();
        for (rang, image) in self.root.images.iter().enumerate() {
            if let Some(vue) = image.buffer_view {
                self.verifie(
                    &format!("images[{rang}].bufferView"),
                    "la bufferView",
                    vue.value(),
                    total,
                )?;
            }
        }
        Ok(())
    }

    fn textures(&self) -> Result<(), ImportError> {
        for (rang, texture) in self.root.textures.iter().enumerate() {
            self.verifie(
                &format!("textures[{rang}].source"),
                "l'image",
                texture.source.value(),
                self.root.images.len(),
            )?;
            if let Some(sampler) = texture.sampler {
                self.verifie(
                    &format!("textures[{rang}].sampler"),
                    "le sampler",
                    sampler.value(),
                    self.root.samplers.len(),
                )?;
            }
        }
        Ok(())
    }

    fn materials(&self) -> Result<(), ImportError> {
        let total = self.root.textures.len();
        for (rang, materiau) in self.root.materials.iter().enumerate() {
            let pbr = &materiau.pbr_metallic_roughness;
            for (nom, info) in [
                (
                    "pbrMetallicRoughness.baseColorTexture",
                    &pbr.base_color_texture,
                ),
                (
                    "pbrMetallicRoughness.metallicRoughnessTexture",
                    &pbr.metallic_roughness_texture,
                ),
            ] {
                if let Some(info) = info {
                    self.verifie(
                        &format!("materials[{rang}].{nom}"),
                        "la texture",
                        info.index.value(),
                        total,
                    )?;
                }
            }
            if let Some(normale) = &materiau.normal_texture {
                self.verifie(
                    &format!("materials[{rang}].normalTexture"),
                    "la texture",
                    normale.index.value(),
                    total,
                )?;
            }
            if let Some(occlusion) = &materiau.occlusion_texture {
                self.verifie(
                    &format!("materials[{rang}].occlusionTexture"),
                    "la texture",
                    occlusion.index.value(),
                    total,
                )?;
            }
            if let Some(emissive) = &materiau.emissive_texture {
                self.verifie(
                    &format!("materials[{rang}].emissiveTexture"),
                    "la texture",
                    emissive.index.value(),
                    total,
                )?;
            }
        }
        Ok(())
    }

    fn meshes(&self) -> Result<(), ImportError> {
        let accesseurs = self.root.accessors.len();
        let materiaux = self.root.materials.len();

        for (mesh, maillage) in self.root.meshes.iter().enumerate() {
            for (prim, primitive) in maillage.primitives.iter().enumerate() {
                let ou = format!("meshes[{mesh}].primitives[{prim}]");

                // C'est cette boucle-ci qui manquait : `POSITION` désignant un
                // accesseur inexistant faisait paniquer `gltf-json` dans son
                // propre validateur.
                for indice in primitive.attributes.values() {
                    self.verifie(
                        &format!("{ou}.attributes"),
                        "l'accesseur",
                        indice.value(),
                        accesseurs,
                    )?;
                }
                if let Some(indices) = primitive.indices {
                    self.verifie(
                        &format!("{ou}.indices"),
                        "l'accesseur",
                        indices.value(),
                        accesseurs,
                    )?;
                }
                if let Some(materiau) = primitive.material {
                    self.verifie(
                        &format!("{ou}.material"),
                        "le matériau",
                        materiau.value(),
                        materiaux,
                    )?;
                }
                if let Some(cibles) = &primitive.targets {
                    for (cible, morph) in cibles.iter().enumerate() {
                        for indice in [morph.positions, morph.normals, morph.tangents]
                            .into_iter()
                            .flatten()
                        {
                            self.verifie(
                                &format!("{ou}.targets[{cible}]"),
                                "l'accesseur",
                                indice.value(),
                                accesseurs,
                            )?;
                        }
                    }
                }
            }
        }
        Ok(())
    }

    fn nodes(&self) -> Result<(), ImportError> {
        let total = self.root.nodes.len();
        for (rang, node) in self.root.nodes.iter().enumerate() {
            if let Some(mesh) = node.mesh {
                self.verifie(
                    &format!("nodes[{rang}].mesh"),
                    "le mesh",
                    mesh.value(),
                    self.root.meshes.len(),
                )?;
            }
            if let Some(skin) = node.skin {
                self.verifie(
                    &format!("nodes[{rang}].skin"),
                    "le skin",
                    skin.value(),
                    self.root.skins.len(),
                )?;
            }
            if let Some(camera) = node.camera {
                self.verifie(
                    &format!("nodes[{rang}].camera"),
                    "la caméra",
                    camera.value(),
                    self.root.cameras.len(),
                )?;
            }
            if let Some(enfants) = &node.children {
                for enfant in enfants {
                    self.verifie(
                        &format!("nodes[{rang}].children"),
                        "le node",
                        enfant.value(),
                        total,
                    )?;
                }
            }
        }
        Ok(())
    }

    fn skins(&self) -> Result<(), ImportError> {
        let nodes = self.root.nodes.len();
        for (rang, skin) in self.root.skins.iter().enumerate() {
            if let Some(matrices) = skin.inverse_bind_matrices {
                self.verifie(
                    &format!("skins[{rang}].inverseBindMatrices"),
                    "l'accesseur",
                    matrices.value(),
                    self.root.accessors.len(),
                )?;
            }
            if let Some(squelette) = skin.skeleton {
                self.verifie(
                    &format!("skins[{rang}].skeleton"),
                    "le node",
                    squelette.value(),
                    nodes,
                )?;
            }
            for joint in &skin.joints {
                self.verifie(
                    &format!("skins[{rang}].joints"),
                    "le node",
                    joint.value(),
                    nodes,
                )?;
            }
        }
        Ok(())
    }

    fn scenes(&self) -> Result<(), ImportError> {
        let nodes = self.root.nodes.len();
        for (rang, scene) in self.root.scenes.iter().enumerate() {
            for node in &scene.nodes {
                self.verifie(
                    &format!("scenes[{rang}].nodes"),
                    "le node",
                    node.value(),
                    nodes,
                )?;
            }
        }
        if let Some(scene) = self.root.scene {
            self.verifie("scene", "la scène", scene.value(), self.root.scenes.len())?;
        }
        Ok(())
    }

    fn animations(&self) -> Result<(), ImportError> {
        let accesseurs = self.root.accessors.len();
        for (rang, animation) in self.root.animations.iter().enumerate() {
            for (echantillonneur, sampler) in animation.samplers.iter().enumerate() {
                let ou = format!("animations[{rang}].samplers[{echantillonneur}]");
                self.verifie(
                    &format!("{ou}.input"),
                    "l'accesseur",
                    sampler.input.value(),
                    accesseurs,
                )?;
                self.verifie(
                    &format!("{ou}.output"),
                    "l'accesseur",
                    sampler.output.value(),
                    accesseurs,
                )?;
            }
            for (canal, channel) in animation.channels.iter().enumerate() {
                let ou = format!("animations[{rang}].channels[{canal}]");
                self.verifie(
                    &format!("{ou}.sampler"),
                    "l'échantillonneur",
                    channel.sampler.value(),
                    animation.samplers.len(),
                )?;
                self.verifie(
                    &format!("{ou}.target.node"),
                    "le node",
                    channel.target.node.value(),
                    self.root.nodes.len(),
                )?;
            }
        }
        Ok(())
    }
}
