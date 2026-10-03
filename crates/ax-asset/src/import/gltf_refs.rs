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
//! Trois familles de mines, distinctes, et **trouvées l'une après l'autre par
//! le fuzzer** — chacune une fois la précédente couverte :
//!
//! 1. **Les indices.** Tout rang doit désigner un élément qui existe.
//! 2. **Les énumérations.** `gltf-json` modélise par `Checked<T>` les champs
//!    dont glTF fixe les valeurs admises — `mode`, `componentType`,
//!    `alphaMode`… Une valeur inconnue devient `Invalid`, et tout `.unwrap()`
//!    dessus panique. `Primitive::mode()` en est un, et l'import l'appelle en
//!    premier : un `"mode": 99` suffisait.
//! 3. **La forme des attributs.** glTF fixe le type et le composant de chaque
//!    sémantique : `POSITION` est un `VEC3` de `FLOAT`. Un accesseur qui déclare
//!    autre chose fait lire au crate `gltf` un élément d'une taille qui n'est
//!    pas celle qu'il attend.
//!
//! Que les trois se soient découvertes en cascade est l'enseignement principal :
//! couvrir une famille ne dit rien des autres, et le raisonnement ne les
//! énumère pas — seule l'exécution le fait. C'est la raison pour laquelle ce
//! module balaie le document entier plutôt que ce que l'import déréférence
//! aujourd'hui.
//!
//! Rien d'autre : ni les longueurs de tampon, ni la cohérence sémantique —
//! C-22 s'en charge sur l'asset importé, et R-530 sur les extensions.
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

    /// Refuse une énumération dont la valeur n'appartient pas à glTF 2.0.
    ///
    /// Seconde famille de mines du format, distincte des indices. `gltf-json`
    /// modélise ces champs par `Checked<T>` : une valeur inconnue devient
    /// `Invalid`, et tout `.unwrap()` dessus **panique**. `Primitive::mode()` en
    /// est un, et l'import l'appelle en premier — un `"mode": 99` suffisait.
    /// Trouvé par fuzzing (R-903).
    ///
    /// Refuser est conforme : glTF fixe les valeurs admises de chacune de ces
    /// énumérations. Un fichier qui en invente une n'est pas un fichier glTF
    /// que l'on interprète de travers, c'en est un qu'on ne sait pas lire.
    fn valide<T>(
        &self,
        ou: &str,
        valeur: &gltf::json::validation::Checked<T>,
    ) -> Result<(), ImportError> {
        if matches!(valeur, gltf::json::validation::Checked::Invalid) {
            return Err(ImportError::Malformed {
                format: self.format,
                detail: format!("{ou} porte une valeur que glTF 2.0 ne définit pas"),
            });
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
        self.semantiques()?;
        self.samplers()?;
        self.cameras()?;
        Ok(())
    }

    /// Refuse un attribut dont l'accesseur n'a pas la forme que glTF impose.
    ///
    /// Troisieme famille de mines, et la plus sournoise. glTF 2.0 fixe le type
    /// et le composant de chaque semantique : `POSITION` est un `VEC3` de
    /// `FLOAT`, `JOINTS_n` un `VEC4` d'entiers non signes. Un fichier qui
    /// declare autre chose fait lire au lecteur du crate `gltf` un element
    /// d'une taille qui n'est pas celle qu'il attend.
    ///
    /// En version de debogage, il s'en apercoit par un `debug_assert_eq!` et
    /// **panique** — c'est ainsi que le fuzzer l'a trouve, `cargo-fuzz` activant
    /// les assertions. Dans le binaire livre, l'assertion n'existe pas : le
    /// lecteur poursuit et rend une geometrie fausse, **en silence**. Le second
    /// cas est le pire des deux, et c'est celui que cette verification arrete.
    fn semantiques(&self) -> Result<(), ImportError> {
        use gltf::json::accessor::{ComponentType, Type};
        use gltf::json::mesh::Semantic;
        use gltf::json::validation::Checked;

        for (mesh, maillage) in self.root.meshes.iter().enumerate() {
            for (prim, primitive) in maillage.primitives.iter().enumerate() {
                for (semantique, indice) in &primitive.attributes {
                    let Checked::Valid(semantique) = semantique else {
                        // Deja refuse par `meshes()` ; la boucle ne suppose rien.
                        continue;
                    };
                    // Les semantiques que le format ne fixe pas — attributs
                    // personnalises prefixes d'un tiret bas — n'ont pas de forme
                    // imposee, et l'import ne les lit pas.
                    //
                    // `COLOR_n` admet deux types : `read_colors` du crate `gltf`
                    // aiguille sur la paire (type, composant) et atteint un
                    // `unreachable!()` sur toute autre — l'import le lit depuis
                    // C-26 (ADR-122 §3).
                    let attendu: (&[Type], &[ComponentType]) = match semantique {
                        Semantic::Positions | Semantic::Normals => {
                            (&[Type::Vec3], &[ComponentType::F32])
                        }
                        Semantic::Tangents => (&[Type::Vec4], &[ComponentType::F32]),
                        Semantic::Colors(_) => (
                            &[Type::Vec3, Type::Vec4],
                            &[ComponentType::F32, ComponentType::U8, ComponentType::U16],
                        ),
                        Semantic::TexCoords(_) => (
                            &[Type::Vec2],
                            &[ComponentType::F32, ComponentType::U8, ComponentType::U16],
                        ),
                        Semantic::Joints(_) => {
                            (&[Type::Vec4], &[ComponentType::U8, ComponentType::U16])
                        }
                        Semantic::Weights(_) => (
                            &[Type::Vec4],
                            &[ComponentType::F32, ComponentType::U8, ComponentType::U16],
                        ),
                        _ => continue,
                    };

                    let Some(accesseur) = self.root.accessors.get(indice.value()) else {
                        // `meshes()` a deja refuse un indice hors bornes.
                        continue;
                    };
                    let ou = format!("meshes[{mesh}].primitives[{prim}].attributes.{semantique:?}");

                    if !matches!(accesseur.type_, Checked::Valid(reel) if attendu.0.contains(&reel))
                    {
                        return Err(ImportError::Malformed {
                            format: self.format,
                            detail: format!(
                                "{ou} : glTF impose un accesseur de type {:?}",
                                attendu.0
                            ),
                        });
                    }
                    let composant_admis = matches!(
                        accesseur.component_type,
                        Checked::Valid(reel) if attendu.1.contains(&reel.0)
                    );
                    if !composant_admis {
                        return Err(ImportError::Malformed {
                            format: self.format,
                            detail: format!(
                                "{ou} : composant non admis par glTF pour cette semantique"
                            ),
                        });
                    }
                }
            }
        }
        Ok(())
    }

    fn samplers(&self) -> Result<(), ImportError> {
        for (rang, sampler) in self.root.samplers.iter().enumerate() {
            if let Some(filtre) = &sampler.mag_filter {
                self.valide(&format!("samplers[{rang}].magFilter"), filtre)?;
            }
            if let Some(filtre) = &sampler.min_filter {
                self.valide(&format!("samplers[{rang}].minFilter"), filtre)?;
            }
            self.valide(&format!("samplers[{rang}].wrapS"), &sampler.wrap_s)?;
            self.valide(&format!("samplers[{rang}].wrapT"), &sampler.wrap_t)?;
        }
        Ok(())
    }

    fn cameras(&self) -> Result<(), ImportError> {
        for (rang, camera) in self.root.cameras.iter().enumerate() {
            self.valide(&format!("cameras[{rang}].type"), &camera.type_)?;
        }
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
            if let Some(cible) = &vue.target {
                self.valide(&format!("bufferViews[{rang}].target"), cible)?;
            }
            // glTF borne le pas a [4, 252] et le veut multiple de quatre. Il
            // entre dans le meme calcul d'etendue que `count`, et un pas
            // aberrant y fait deborder l'arithmetique.
            if let Some(pas) = vue.byte_stride {
                let pas = pas.0;
                if !(4..=252).contains(&pas) || pas % 4 != 0 {
                    return Err(ImportError::Malformed {
                        format: self.format,
                        detail: format!(
                            "bufferViews[{rang}].byteStride vaut {pas} : glTF le veut \
                             entre 4 et 252, et multiple de quatre"
                        ),
                    });
                }
            }
        }
        Ok(())
    }

    fn accessors(&self) -> Result<(), ImportError> {
        let total = self.root.buffer_views.len();
        for (rang, accesseur) in self.root.accessors.iter().enumerate() {
            self.valide(
                &format!("accessors[{rang}].componentType"),
                &accesseur.component_type,
            )?;
            self.valide(&format!("accessors[{rang}].type"), &accesseur.type_)?;
            // Cinquieme famille : les contraintes numeriques du format. glTF
            // impose `count >= 1`, et le lecteur du crate calcule
            // `stride * (count - 1)` : un `count` nul y soustrait sous zero.
            // En debogage il panique, en release il boucle l'entier et rend
            // silencieusement un accesseur vide. Trouve par fuzzing (R-903).
            if accesseur.count.0 == 0 {
                return Err(ImportError::Malformed {
                    format: self.format,
                    detail: format!("accessors[{rang}].count vaut zero, glTF en veut au moins un"),
                });
            }
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
                self.valide(
                    &format!("accessors[{rang}].sparse.indices.componentType"),
                    &sparse.indices.component_type,
                )?;
                if sparse.count.0 == 0 {
                    return Err(ImportError::Malformed {
                        format: self.format,
                        detail: format!(
                            "accessors[{rang}].sparse.count vaut zero, glTF en veut au moins un"
                        ),
                    });
                }
            }
        }
        Ok(())
    }

    /// Quatrieme famille : les champs que glTF rend **obligatoires**.
    ///
    /// Une image porte soit un `uri`, soit une `bufferView` — jamais ni l'un ni
    /// l'autre. Et si elle passe par une `bufferView`, le `mimeType` devient
    /// obligatoire, faute de quoi rien ne dit ce que les octets contiennent.
    ///
    /// `Image::source()` du crate `gltf` deballe les deux sans verifier : une
    /// image sans `uri` ni `bufferView` le fait paniquer, une image en
    /// `bufferView` sans `mimeType` aussi. Trouve par fuzzing (R-903), apres que
    /// les indices, les enumerations et les formes d'attribut aient ete
    /// couverts — c'est la quatrieme nature de defaut du meme format.
    fn images(&self) -> Result<(), ImportError> {
        let total = self.root.buffer_views.len();
        for (rang, image) in self.root.images.iter().enumerate() {
            let malformed = |detail: String| ImportError::Malformed {
                format: self.format,
                detail,
            };

            match (&image.buffer_view, &image.uri) {
                (Some(vue), _) => {
                    self.verifie(
                        &format!("images[{rang}].bufferView"),
                        "la bufferView",
                        vue.value(),
                        total,
                    )?;
                    if image.mime_type.is_none() {
                        return Err(malformed(format!(
                            "images[{rang}] passe par une bufferView sans declarer \
                             de mimeType : rien ne dit ce que les octets contiennent"
                        )));
                    }
                }
                (None, Some(_)) => {}
                (None, None) => {
                    return Err(malformed(format!(
                        "images[{rang}] ne porte ni uri ni bufferView : elle ne \
                         designe aucune donnee"
                    )));
                }
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
            self.valide(
                &format!("materials[{rang}].alphaMode"),
                &materiau.alpha_mode,
            )?;
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
                self.valide(&format!("{ou}.mode"), &primitive.mode)?;
                for semantique in primitive.attributes.keys() {
                    self.valide(&format!("{ou}.attributes"), semantique)?;
                }
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
                self.valide(&format!("{ou}.interpolation"), &sampler.interpolation)?;
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
                self.valide(&format!("{ou}.target.path"), &channel.target.path)?;
            }
        }
        Ok(())
    }
}
