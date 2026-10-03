//! Matériaux glTF → DM-05, textures → `TEXR` (C-21, C-26, ADR-122 §3).
//!
//! La correspondance est celle de la table d'ADR-122 §3, champ pour champ. Ce
//! que DM-05 ne peut pas porter n'est pas jeté en silence : chaque perte est
//! dite une fois, dans [`ImportedAsset::material_warnings`].
//!
//! # Une seule projection par matériau
//!
//! DM-05 n'a qu'un jeu de coordonnées pour ses six slots, `uv0`. Celui de
//! l'albedo le fixe — son `texCoord`, et sa `KHR_texture_transform`, que
//! l'import cuit dans `uv0` ; un autre slot projeté autrement est averti : il
//! sera échantillonné selon la projection de l'albedo.
//!
//! # L'ORM de DM-05 est celui de glTF
//!
//! glTF range le métal et la rugosité dans les canaux B et G de
//! `metallicRoughnessTexture`, et y place l'occlusion, canal R, quand l'auteur
//! les a empaquetées. DM-05 lit les trois dans `orm_tex`. Une
//! `occlusionTexture` portée par **la même image** est donc reprise ; dans une
//! autre image, elle est avertie et ignorée, et `occlusion_strength` vaut zéro :
//! le canal R de l'ORM n'est alors pas une occlusion, et le lire comme telle
//! assombrirait le modèle au hasard.

use super::gltf::decode_data_uri;
use super::material::{
    gltf_default_material, neutral_material, ImageKey, TextureTableBuilder, UvMapping,
    DEFAULT_ALPHA_CUTOFF,
};
use super::{check_relative_path, ImportError, ImportedAsset, ImportedMaterial, SourceFormat};
use ax_model::dm::material::{blend_mode, cull_mode, shading_model, texture_sampler, MaterialDesc};
use gltf::json::Value;
use gltf::material::AlphaMode;
use gltf::texture::{MagFilter, MinFilter, WrappingMode};

/// Nom de l'extension de transformation de texture (R-530).
const TEXTURE_TRANSFORM: &str = "KHR_texture_transform";

/// Ce que l'import des matériaux transmet à celui des meshes.
#[derive(Debug, Clone, PartialEq)]
pub(super) struct GltfMaterials {
    /// Projection de chaque matériau, dans l'ordre de la table.
    pub(super) mappings: Vec<UvMapping>,
    /// Index du matériau par défaut, ajouté en fin de table si une primitive
    /// n'en désigne aucun.
    pub(super) default: Option<u16>,
}

/// Traduit les matériaux du document en DM-05 et range leurs textures.
///
/// Le matériau par défaut est ajouté **avant** les meshes, en fin de table,
/// si une primitive au moins n'en désigne aucun : `MeshDesc.material` est
/// alors toujours un index valide (ADR-122 §3).
///
/// # Errors
///
/// [`ImportError::Malformed`] si une image désigne des octets hors de son
/// buffer ou un URI `data:` mal encodé, [`ImportError::ExternalPath`] si son
/// URI sort du répertoire de l'asset (R-531). Une image qui n'est pas un PNG,
/// ou trop grande, n'est pas une erreur : elle est refusée seule (`E-3004`,
/// `E-3006`) et ses slots restent vides.
pub(super) fn import_materials(
    document: &gltf::Gltf,
    buffers: &[Vec<u8>],
    format: SourceFormat,
    asset: &mut ImportedAsset,
) -> Result<GltfMaterials, ImportError> {
    let mut importer = Importer {
        buffers,
        format,
        builder: TextureTableBuilder::default(),
        samplers: vec![None; document.textures().len()],
        warnings: Vec::new(),
    };
    let mut mappings = Vec::with_capacity(document.materials().len() + 1);

    for material in document.materials() {
        let (desc, mapping) = importer.convert(&material)?;
        let name = material.name().unwrap_or("").to_owned();
        // Un matériau sans nom n'entre pas dans la règle d'unicité de C-22 :
        // il y entrait sous le nom « material », si bien que deux matériaux
        // sans nom — un cas courant — faisaient refuser un glTF valide.
        if !name.is_empty() {
            asset.names.push(("matériau", name.clone()));
        }
        asset.materials.push(ImportedMaterial { name, desc });
        mappings.push(mapping);
    }

    let unbound = document.meshes().any(|mesh| {
        mesh.primitives()
            .any(|primitive| primitive.material().index().is_none())
    });
    let mut default = None;
    if unbound {
        default = Some(u16::try_from(asset.materials.len()).unwrap_or(u16::MAX));
        asset.materials.push(ImportedMaterial {
            name: String::new(),
            desc: gltf_default_material(),
        });
        mappings.push(UvMapping::IDENTITY);
    }

    let (textures, refusals) = importer.builder.finish();
    asset.textures = textures;
    asset.texture_refusals = refusals;
    asset.material_warnings.extend(importer.warnings);
    Ok(GltfMaterials { mappings, default })
}

/// État de la traduction : la table des textures en construction, et ce qui
/// a été dit.
struct Importer<'d> {
    buffers: &'d [Vec<u8>],
    format: SourceFormat,
    builder: TextureTableBuilder,
    /// Bits d'échantillonneur de chaque texture glTF, calculés une fois : un
    /// avertissement de répétition en miroir n'est dit qu'une fois par texture.
    samplers: Vec<Option<u8>>,
    warnings: Vec<String>,
}

impl Importer<'_> {
    fn warn(&mut self, message: String) {
        self.warnings.push(message);
    }

    /// Un matériau glTF, traduit en DM-05 (ADR-122 §3).
    fn convert(
        &mut self,
        material: &gltf::Material<'_>,
    ) -> Result<(MaterialDesc, UvMapping), ImportError> {
        let label = material_label(material);
        let pbr = material.pbr_metallic_roughness();
        let mut desc = neutral_material(
            material.name().unwrap_or(""),
            pbr.metallic_factor(),
            pbr.roughness_factor(),
        );
        desc.albedo_factor = pbr.base_color_factor();

        // L'albedo fixe la projection de `uv0` ; les autres slots s'y comparent.
        let mapping = match pbr.base_color_texture() {
            Some(info) => {
                desc.albedo_tex = self.slot(&info.texture())?;
                info_mapping(&info)
            }
            None => UvMapping::IDENTITY,
        };

        if let Some(info) = pbr.metallic_roughness_texture() {
            desc.orm_tex = self.slot(&info.texture())?;
            self.compare(
                &label,
                "metallicRoughnessTexture",
                info_mapping(&info),
                mapping,
            );
            desc.occlusion_strength = self.packed_occlusion(material, &label, &info, mapping);
        } else if material.occlusion_texture().is_some() {
            self.warn(format!(
                "matériau {label} : occlusionTexture sans metallicRoughnessTexture, \
                 ignorée — DM-05 lit l'occlusion dans le canal R de l'ORM"
            ));
        }

        if let Some(normal) = material.normal_texture() {
            desc.normal_tex = self.slot(&normal.texture())?;
            desc.normal_scale = normal.scale();
            let own = raw_mapping(
                normal.tex_coord(),
                normal.extension_value(TEXTURE_TRANSFORM),
            );
            self.compare(&label, "normalTexture", own, mapping);
        }

        // `KHR_materials_emissive_strength` multiplie le facteur : glTF borne
        // `emissiveFactor` à 1, l'extension seule porte une émission plus vive.
        let strength = material.emissive_strength().unwrap_or(1.0);
        desc.emissive_factor = material
            .emissive_factor()
            .map(|component| component * strength);
        if let Some(info) = material.emissive_texture() {
            desc.emissive_tex = self.slot(&info.texture())?;
            self.compare(&label, "emissiveTexture", info_mapping(&info), mapping);
        }

        desc.blend_mode = match material.alpha_mode() {
            AlphaMode::Opaque => blend_mode::OPAQUE,
            AlphaMode::Mask => blend_mode::CUTOUT,
            AlphaMode::Blend => blend_mode::TRANSLUCENT,
        };
        desc.alpha_cutoff = self.alpha_cutoff(material, &label, desc.blend_mode);
        desc.cull_mode = if material.double_sided() {
            cull_mode::NONE
        } else {
            cull_mode::BACK
        };

        self.layers(material, &label, &mut desc);
        // Un seul modèle d'éclairage par matériau : `UNLIT` ignore tout le
        // reste, et le vernis prime sur le lustre — les deux facteurs restent
        // écrits, le modèle ne dit que lequel le rendu doit honorer.
        desc.shading_model = if material.unlit() {
            shading_model::UNLIT
        } else if desc.clearcoat > 0.0 {
            shading_model::PBR_CLEARCOAT
        } else if desc.sheen > 0.0 {
            shading_model::PBR_SHEEN
        } else {
            shading_model::PBR
        };

        // Admises par R-530, sans champ dans DM-05.
        for (present, extension) in [
            (material.ior().is_some(), "KHR_materials_ior"),
            (material.specular().is_some(), "KHR_materials_specular"),
        ] {
            if present {
                self.warn(format!(
                    "matériau {label} : {extension} sans champ DM-05, ignorée"
                ));
            }
        }

        Ok((desc, mapping))
    }

    /// Force d'occlusion de l'ORM : celle de `occlusionTexture` si elle est
    /// dans la même image que `metallicRoughnessTexture`, nulle sinon.
    fn packed_occlusion(
        &mut self,
        material: &gltf::Material<'_>,
        label: &str,
        orm: &gltf::texture::Info<'_>,
        mapping: UvMapping,
    ) -> f32 {
        let Some(occlusion) = material.occlusion_texture() else {
            return 0.0;
        };
        if occlusion.texture().source().index() != orm.texture().source().index() {
            self.warn(format!(
                "matériau {label} : occlusionTexture dans une autre image que \
                 metallicRoughnessTexture, ignorée — DM-05 lit l'occlusion dans le \
                 canal R de l'ORM"
            ));
            return 0.0;
        }
        let own = raw_mapping(
            occlusion.tex_coord(),
            occlusion.extension_value(TEXTURE_TRANSFORM),
        );
        self.compare(label, "occlusionTexture", own, mapping);
        occlusion.strength()
    }

    /// Avertit d'un slot projeté autrement que l'albedo.
    fn compare(&mut self, label: &str, slot: &str, own: UvMapping, reference: UvMapping) {
        if own != reference {
            self.warn(format!(
                "matériau {label} : {slot} projetée autrement que l'albedo (TEXCOORD \
                 ou KHR_texture_transform) — échantillonnée selon la projection de \
                 l'albedo, la seule que uv0 porte"
            ));
        }
    }

    /// Seuil de découpe, ramené dans `[0, 1]`.
    ///
    /// Le schéma glTF ne borne `alphaCutoff` que par le bas ; au-delà de 1,
    /// tout texel serait découpé. DM-05 le borne à `[0, 1]`, et le ramener
    /// vaut mieux que refuser un fichier conforme. L'écart n'est dit qu'en
    /// mode `MASK`, le seul où le seuil compte.
    fn alpha_cutoff(&mut self, material: &gltf::Material<'_>, label: &str, blend: u8) -> f32 {
        let Some(cutoff) = material.alpha_cutoff() else {
            return DEFAULT_ALPHA_CUTOFF;
        };
        let bounded = if cutoff.is_nan() {
            DEFAULT_ALPHA_CUTOFF
        } else {
            cutoff.clamp(0.0, 1.0)
        };
        if bounded != cutoff && blend == blend_mode::CUTOUT {
            self.warn(format!(
                "matériau {label} : alphaCutoff {cutoff} ramené à {bounded}"
            ));
        }
        bounded
    }

    /// Vernis, lustre et anisotropie : extensions que le crate `gltf` ne
    /// modélise pas, lues dans leur JSON brut.
    fn layers(&mut self, material: &gltf::Material<'_>, label: &str, desc: &mut MaterialDesc) {
        const CLEARCOAT: &str = "KHR_materials_clearcoat";
        const SHEEN: &str = "KHR_materials_sheen";
        const ANISOTROPY: &str = "KHR_materials_anisotropy";

        if let Some(clearcoat) = self.extension(
            material,
            label,
            CLEARCOAT,
            &["clearcoatFactor", "clearcoatRoughnessFactor"],
        ) {
            desc.clearcoat = self.number(label, CLEARCOAT, clearcoat, "clearcoatFactor", 0.0);
            desc.clearcoat_roughness =
                self.number(label, CLEARCOAT, clearcoat, "clearcoatRoughnessFactor", 0.0);
        }
        // DM-05 ne porte qu'une intensité de lustre : la composante la plus forte
        // de `sheenColorFactor`. Sa teinte n'a pas de champ.
        if let Some(sheen) = self.extension(material, label, SHEEN, &["sheenColorFactor"]) {
            desc.sheen = self.strongest_component(label, SHEEN, sheen, "sheenColorFactor");
        }
        if let Some(anisotropy) =
            self.extension(material, label, ANISOTROPY, &["anisotropyStrength"])
        {
            desc.anisotropy = self.number(label, ANISOTROPY, anisotropy, "anisotropyStrength", 0.0);
        }
    }

    /// Le JSON d'une extension de matériau, après avoir averti de ce qu'il
    /// porte sans équivalent dans DM-05.
    fn extension<'m>(
        &mut self,
        material: &'m gltf::Material<'_>,
        label: &str,
        name: &str,
        mapped: &[&str],
    ) -> Option<&'m Value> {
        let value = material.extension_value(name)?;
        let Some(object) = value.as_object() else {
            self.warn(format!(
                "matériau {label} : {name} n'est pas un objet JSON, ignorée"
            ));
            return None;
        };
        let ignored: Vec<&str> = object
            .keys()
            .map(String::as_str)
            .filter(|key| !mapped.contains(key) && !matches!(*key, "extensions" | "extras"))
            .collect();
        if !ignored.is_empty() {
            self.warn(format!(
                "matériau {label} : {name} — {} sans équivalent DM-05, ignoré(s)",
                ignored.join(", ")
            ));
        }
        Some(value)
    }

    /// Un nombre d'une extension ; sa valeur par défaut s'il est absent ou
    /// illisible, ce dernier cas averti.
    fn number(
        &mut self,
        label: &str,
        extension: &str,
        object: &Value,
        key: &str,
        default: f32,
    ) -> f32 {
        let Some(value) = object.get(key) else {
            return default;
        };
        if let Some(number) = value.as_f64() {
            return number as f32;
        }
        self.warn(format!(
            "matériau {label} : {extension}.{key} n'est pas un nombre, {default} retenu"
        ));
        default
    }

    /// La plus forte composante d'un triplet de couleur ; zéro s'il est absent
    /// ou illisible, ce dernier cas averti.
    fn strongest_component(
        &mut self,
        label: &str,
        extension: &str,
        object: &Value,
        key: &str,
    ) -> f32 {
        let Some(value) = object.get(key) else {
            return 0.0;
        };
        if let Some([red, green, blue]) = value.as_array().map(Vec::as_slice) {
            if let (Some(red), Some(green), Some(blue)) =
                (red.as_f64(), green.as_f64(), blue.as_f64())
            {
                return red.max(green).max(blue) as f32;
            }
        }
        self.warn(format!(
            "matériau {label} : {extension}.{key} n'est pas un triplet de nombres, 0 retenu"
        ));
        0.0
    }

    /// L'entrée de `TEXR` d'une texture glTF ; [`NO_TEXTURE`] si son image est
    /// refusée.
    ///
    /// L'image n'est lue qu'à sa première rencontre : un URI `data:` partagé
    /// par plusieurs slots n'est décodé qu'une fois.
    ///
    /// [`NO_TEXTURE`]: ax_model::dm::material::NO_TEXTURE
    fn slot(&mut self, texture: &gltf::Texture<'_>) -> Result<u16, ImportError> {
        let image = texture.source();
        let key = ImageKey::Indexed(image.index());
        if !self.builder.has_image(&key) {
            let designation = image_designation(&image);
            match image.source() {
                gltf::image::Source::View { view, mime_type } => {
                    let bytes = view_bytes(self.buffers, &view, self.format)?;
                    self.builder
                        .add_embedded(key.clone(), &designation, Some(mime_type), bytes);
                }
                gltf::image::Source::Uri { uri, mime_type } => match decode_data_uri(uri) {
                    Some(decoded) => {
                        let bytes = decoded?;
                        self.builder
                            .add_embedded(key.clone(), &designation, mime_type, &bytes);
                    }
                    None => {
                        check_relative_path(uri)?;
                        self.builder.add_resource(key.clone(), uri, mime_type);
                    }
                },
            }
        }
        let sampler = self.sampler_bits(texture);
        Ok(self.builder.texture(key, sampler))
    }

    /// Bits d'échantillonneur d'une texture (ADR-122 §2).
    ///
    /// Le filtrage est celui de `magFilter` ; à défaut, celui que `minFilter`
    /// applique au sein d'un niveau ; à défaut encore, « non déclaré », que le
    /// client rend au plus proche (ADR-122, décision 4). La répétition en
    /// miroir n'existe pas dans les `RenderType` vanilla : rendue en
    /// répétition simple, avec avertissement.
    fn sampler_bits(&mut self, texture: &gltf::Texture<'_>) -> u8 {
        if let Some(Some(bits)) = self.samplers.get(texture.index()) {
            return *bits;
        }
        let sampler = texture.sampler();
        let mut bits = match (sampler.mag_filter(), sampler.min_filter()) {
            (Some(MagFilter::Nearest), _)
            | (
                None,
                Some(
                    MinFilter::Nearest
                    | MinFilter::NearestMipmapNearest
                    | MinFilter::NearestMipmapLinear,
                ),
            ) => texture_sampler::FILTER_NEAREST,
            (Some(MagFilter::Linear), _)
            | (
                None,
                Some(
                    MinFilter::Linear
                    | MinFilter::LinearMipmapNearest
                    | MinFilter::LinearMipmapLinear,
                ),
            ) => texture_sampler::FILTER_LINEAR,
            (None, None) => texture_sampler::FILTER_UNDECLARED,
        };
        for (wrap, clamp, axis) in [
            (sampler.wrap_s(), texture_sampler::CLAMP_U, "wrapS"),
            (sampler.wrap_t(), texture_sampler::CLAMP_V, "wrapT"),
        ] {
            match wrap {
                WrappingMode::ClampToEdge => bits |= clamp,
                WrappingMode::MirroredRepeat => self.warn(format!(
                    "texture {} : {axis} MIRRORED_REPEAT rendu en répétition simple",
                    texture_label(texture)
                )),
                WrappingMode::Repeat => {}
            }
        }
        if let Some(slot) = self.samplers.get_mut(texture.index()) {
            *slot = Some(bits);
        }
        bits
    }
}

/// Projection d'une `textureInfo` : son `texCoord`, et sa
/// `KHR_texture_transform`, dont le `texCoord` prime s'il est donné.
fn info_mapping(info: &gltf::texture::Info<'_>) -> UvMapping {
    match info.texture_transform() {
        Some(transform) => UvMapping::from_transform(
            transform.tex_coord().unwrap_or_else(|| info.tex_coord()),
            transform.offset(),
            transform.rotation(),
            transform.scale(),
        ),
        None => UvMapping {
            set: info.tex_coord(),
            affine: None,
        },
    }
}

/// Projection d'une texture de normale ou d'occlusion, dont le crate `gltf`
/// laisse la `KHR_texture_transform` en JSON brut.
///
/// Elle ne sert qu'à la comparer à celle de l'albedo : une valeur illisible
/// prend sa valeur par défaut, et l'écart éventuel est averti par la
/// comparaison.
fn raw_mapping(tex_coord: u32, transform: Option<&Value>) -> UvMapping {
    let Some(transform) = transform else {
        return UvMapping {
            set: tex_coord,
            affine: None,
        };
    };
    let pair = |key: &str, default: [f32; 2]| -> [f32; 2] {
        transform
            .get(key)
            .and_then(Value::as_array)
            .and_then(|items| match items.as_slice() {
                [first, second] => Some([first.as_f64()? as f32, second.as_f64()? as f32]),
                _ => None,
            })
            .unwrap_or(default)
    };
    let rotation = transform
        .get("rotation")
        .and_then(Value::as_f64)
        .map_or(0.0, |rotation| rotation as f32);
    let set = transform
        .get("texCoord")
        .and_then(Value::as_u64)
        .and_then(|set| u32::try_from(set).ok())
        .unwrap_or(tex_coord);
    UvMapping::from_transform(
        set,
        pair("offset", [0.0; 2]),
        rotation,
        pair("scale", [1.0; 2]),
    )
}

/// Les octets d'une image portée par une `bufferView`.
fn view_bytes<'b>(
    buffers: &'b [Vec<u8>],
    view: &gltf::buffer::View<'_>,
    format: SourceFormat,
) -> Result<&'b [u8], ImportError> {
    let outside = || ImportError::Malformed {
        format,
        detail: format!("bufferViews[{}] sort de son buffer", view.index()),
    };
    let buffer = buffers.get(view.buffer().index()).ok_or_else(outside)?;
    let end = view
        .offset()
        .checked_add(view.length())
        .ok_or_else(outside)?;
    buffer.get(view.offset()..end).ok_or_else(outside)
}

/// Comment nommer un matériau dans un message.
fn material_label(material: &gltf::Material<'_>) -> String {
    match material.name() {
        Some(name) if !name.is_empty() => format!("« {name} »"),
        _ => format!("n°{}", material.index().unwrap_or_default()),
    }
}

/// Comment nommer une texture dans un message.
fn texture_label(texture: &gltf::Texture<'_>) -> String {
    match texture.name() {
        Some(name) if !name.is_empty() => format!("« {name} »"),
        _ => format!("n°{}", texture.index()),
    }
}

/// Comment nommer une image dans un refus : son nom, son chemin, ou son rang —
/// jamais le contenu d'un URI `data:`.
fn image_designation(image: &gltf::Image<'_>) -> String {
    if let Some(name) = image.name().filter(|name| !name.is_empty()) {
        return name.to_owned();
    }
    match image.source() {
        gltf::image::Source::Uri { uri, .. } if !uri.starts_with("data:") => uri.to_owned(),
        _ => format!("image n°{}", image.index()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn t271_une_transformation_brute_se_lit_comme_la_typee() {
        let brute: Value = gltf::json::deserialize::from_str(
            r#"{ "offset": [0.5, 0.25], "rotation": 0.0, "scale": [2.0, 4.0], "texCoord": 1 }"#,
        )
        .expect("JSON");
        assert_eq!(
            raw_mapping(0, Some(&brute)),
            UvMapping::from_transform(1, [0.5, 0.25], 0.0, [2.0, 4.0])
        );
        assert_eq!(
            raw_mapping(2, None),
            UvMapping {
                set: 2,
                affine: None
            }
        );
    }
}
