//! Matériaux et textures à l'import (C-21, C-26, ADR-122 §3).
//!
//! Ce que glTF, OBJ et STL partagent : les matériaux par défaut, et la
//! construction de la table des textures — une entrée par couple (image,
//! échantillonneur), les octets d'une image n'étant copiés qu'une fois, et le
//! refus d'une image n'étant dit qu'une fois, quel que soit le nombre de slots
//! qui la désignent.
//!
//! Les images ne sont **jamais décodées** (R-532) : une image embarquée n'est
//! lue que jusqu'à son en-tête `IHDR`, pour en connaître le format et les
//! dimensions ; une image désignée par un chemin n'est pas lue du tout — Java la
//! chargera depuis les resource packs.

use super::TextureRefusal;
use crate::a3d::TextureTable;
use crate::png;
use ax_model::dm::limits;
use ax_model::dm::material::{
    blend_mode, cull_mode, shading_model, texture_format, texture_source, MaterialDesc,
    TextureDesc, NO_TEXTURE, NO_WEAR_PROFILE,
};
use ax_model::dm::scene::name_hash;
use std::collections::HashMap;
use std::path::Path;

/// Rugosité d'un matériau que la source décrit sans PBR (§6.4,
/// `materials.default_roughness`).
pub(crate) const DEFAULT_ROUGHNESS: f32 = 0.6;

/// Métal d'un matériau que la source décrit sans PBR (§6.4,
/// `materials.default_metallic`).
pub(crate) const DEFAULT_METALLIC: f32 = 0.0;

/// Seuil de découpe par défaut de glTF 2.0, repris quand la source n'en dit
/// rien.
pub(crate) const DEFAULT_ALPHA_CUTOFF: f32 = 0.5;

/// Seul type d'image admis (R-532).
const PNG_MIME: &str = "image/png";

/// Matériau neutre : blanc, opaque, faces arrière éliminées, aucune texture.
pub(crate) fn neutral_material(name: &str, metallic: f32, roughness: f32) -> MaterialDesc {
    MaterialDesc {
        name_hash: name_hash(name),
        albedo_tex: NO_TEXTURE,
        normal_tex: NO_TEXTURE,
        orm_tex: NO_TEXTURE,
        emissive_tex: NO_TEXTURE,
        height_tex: NO_TEXTURE,
        damage_tex: NO_TEXTURE,
        albedo_factor: [1.0; 4],
        emissive_factor: [0.0; 3],
        metallic,
        roughness,
        occlusion_strength: 1.0,
        normal_scale: 1.0,
        alpha_cutoff: DEFAULT_ALPHA_CUTOFF,
        parallax_scale: 0.0,
        clearcoat: 0.0,
        clearcoat_roughness: 0.0,
        sheen: 0.0,
        anisotropy: 0.0,
        blend_mode: blend_mode::OPAQUE,
        cull_mode: cull_mode::BACK,
        shading_model: shading_model::PBR,
        _pad: 0,
        flags: 0,
        wear_profile: NO_WEAR_PROFILE,
    }
}

/// Matériau par défaut d'une primitive glTF qui n'en désigne aucun : celui de
/// la spécification glTF 2.0 — blanc, métal et rugosité à 1, opaque, une seule
/// face.
pub(crate) fn gltf_default_material() -> MaterialDesc {
    neutral_material("", 1.0, 1.0)
}

/// Matériau par défaut d'une source sans matériau (OBJ sans bibliothèque, STL),
/// aux valeurs du §6.4.
pub(crate) fn plain_default_material() -> MaterialDesc {
    neutral_material("", DEFAULT_METALLIC, DEFAULT_ROUGHNESS)
}

/// Transformation affine identité : `[a, b, c, d, e, f]` de [`UvMapping`].
const IDENTITY_AFFINE: [f32; 6] = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0];

/// Projection des coordonnées de texture d'un matériau sur `uv0`.
///
/// DM-05 n'a qu'un jeu de coordonnées pour ses six slots : celui de l'albedo
/// le fixe, et l'import y cuit sa transformation — `KHR_texture_transform` en
/// glTF, options `-s` et `-o` de `map_Kd` en MTL (ADR-122 §3).
#[derive(Debug, Clone, Copy, PartialEq)]
pub(crate) struct UvMapping {
    /// Jeu de coordonnées lu, `TEXCOORD_n` en glTF ; toujours 0 en OBJ.
    pub(crate) set: u32,
    /// Transformation sous forme affine `[a, b, c, d, e, f]` :
    /// `u' = a·u + b·v + c`, `v' = d·u + e·v + f` ; `None` pour l'identité.
    pub(crate) affine: Option<[f32; 6]>,
}

impl UvMapping {
    /// Premier jeu, sans transformation.
    pub(crate) const IDENTITY: Self = Self {
        set: 0,
        affine: None,
    };

    /// Projection d'une transformation `KHR_texture_transform`.
    ///
    /// La spécification compose `translation × rotation × échelle`, la rotation
    /// ayant pour colonnes `(cos, sin)` et `(−sin, cos)` :
    /// `u' = cos·sx·u − sin·sy·v + ox`, `v' = sin·sx·u + cos·sy·v + oy`. Sans
    /// rotation, c'est aussi la lecture des options `-s` et `-o` du MTL.
    pub(crate) fn from_transform(
        set: u32,
        offset: [f32; 2],
        rotation: f32,
        scale: [f32; 2],
    ) -> Self {
        let (sin, cos) = rotation.sin_cos();
        let affine = [
            cos * scale[0],
            -sin * scale[1],
            offset[0],
            sin * scale[0],
            cos * scale[1],
            offset[1],
        ];
        Self {
            set,
            affine: (affine != IDENTITY_AFFINE).then_some(affine),
        }
    }

    /// Applique la projection à une coordonnée lue dans la source.
    pub(crate) fn apply(&self, uv: [f32; 2]) -> [f32; 2] {
        match self.affine {
            None => uv,
            Some([a, b, c, d, e, f]) => [a * uv[0] + b * uv[1] + c, d * uv[0] + e * uv[1] + f],
        }
    }
}

/// Image d'une texture, telle que la source la désigne : la clé sous laquelle
/// elle n'est lue, stockée et refusée qu'une fois.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub(crate) enum ImageKey {
    /// Image d'un document glTF, par son index.
    Indexed(usize),
    /// Image désignée par un chemin (bibliothèque MTL).
    Path(String),
}

/// Ce que l'on sait d'une image déjà rencontrée.
#[derive(Debug, Clone, Copy)]
enum Image {
    /// Refusée : les slots qui la désignent restent vides.
    Refused,
    /// Rangée dans la zone de données.
    Stored {
        source: u8,
        width: u16,
        height: u16,
        offset: u32,
        size: u32,
    },
}

/// Construit la table des textures d'un asset.
///
/// En deux temps : l'image est rangée — ou refusée — une fois, puis chaque
/// échantillonneur qui la lit en fait une entrée. L'appelant ne lit donc les
/// octets d'une image que si [`TextureTableBuilder::has_image`] dit qu'elle
/// n'a pas encore été vue : un URI `data:` partagé par dix slots n'est décodé
/// qu'une fois.
#[derive(Debug, Default)]
pub(crate) struct TextureTableBuilder {
    table: TextureTable,
    images: HashMap<ImageKey, Image>,
    entries: HashMap<(ImageKey, u8), u16>,
    refusals: Vec<TextureRefusal>,
}

impl TextureTableBuilder {
    /// L'image a déjà été rencontrée, rangée ou refusée.
    pub(crate) fn has_image(&self, key: &ImageKey) -> bool {
        self.images.contains_key(key)
    }

    /// Range une image embarquée : octets PNG copiés tels quels (R-532).
    ///
    /// `declared` est le type MIME que la source déclare, s'il y en a un.
    /// L'image est refusée — et le refus dit une fois — si ce type n'est pas
    /// PNG, si ses octets ne sont pas un PNG, ou si elle dépasse 4096 pixels de
    /// côté. Sans effet sur une image déjà rencontrée.
    pub(crate) fn add_embedded(
        &mut self,
        key: ImageKey,
        designation: &str,
        declared: Option<&str>,
        bytes: &[u8],
    ) {
        if self.has_image(&key) {
            return;
        }
        let image = self.store_embedded(designation, declared, bytes);
        self.images.insert(key, image);
    }

    /// Range une image désignée par un chemin relatif déjà contrôlé (R-531) :
    /// le chemin est conservé, l'image n'est pas lue.
    ///
    /// Refusée si le type déclaré — ou, faute de déclaration, l'extension du
    /// chemin — n'est pas PNG. Un chemin sans extension passe : seule la
    /// signature, que Java vérifiera au chargement, en décidera. Sans effet sur
    /// une image déjà rencontrée.
    pub(crate) fn add_resource(&mut self, key: ImageKey, path: &str, declared: Option<&str>) {
        if self.has_image(&key) {
            return;
        }
        let refused = match declared {
            Some(_) => self.refuse_declared(path, declared),
            None => self.refuse_extension(path),
        };
        let image = if refused {
            Image::Refused
        } else {
            let (offset, size) = self.append(path.as_bytes());
            Image::Stored {
                source: texture_source::RESOURCE,
                width: 0,
                height: 0,
                offset,
                size,
            }
        };
        self.images.insert(key, image);
    }

    /// L'entrée (image, échantillonneur), créée au premier besoin.
    ///
    /// Rend [`NO_TEXTURE`] si l'image a été refusée — le slot reste vide, la
    /// texture neutre s'appliquera — ou n'a pas été rangée.
    ///
    /// Au-delà de 65 535 entrées, l'index ne tient plus dans un slot : le slot
    /// reste vide, et le validateur refuse de toute façon l'asset sur le compte
    /// des textures, qui dépasse alors de loin les 128 de C-22.
    pub(crate) fn texture(&mut self, key: ImageKey, sampler: u8) -> u16 {
        let Some(Image::Stored {
            source,
            width,
            height,
            offset,
            size,
        }) = self.images.get(&key).copied()
        else {
            return NO_TEXTURE;
        };
        let lookup = (key, sampler);
        if let Some(index) = self.entries.get(&lookup) {
            return *index;
        }
        let index = u16::try_from(self.table.entries.len())
            .ok()
            .filter(|index| *index != NO_TEXTURE)
            .unwrap_or(NO_TEXTURE);
        self.table.entries.push(TextureDesc {
            source,
            format: texture_format::PNG,
            sampler,
            _pad: 0,
            width,
            height,
            data_offset: offset,
            data_size: size,
        });
        self.entries.insert(lookup, index);
        index
    }

    /// La table et les refus, dans l'ordre où ils sont survenus.
    pub(crate) fn finish(self) -> (TextureTable, Vec<TextureRefusal>) {
        (self.table, self.refusals)
    }

    fn store_embedded(&mut self, designation: &str, declared: Option<&str>, bytes: &[u8]) -> Image {
        if self.refuse_declared(designation, declared) {
            return Image::Refused;
        }
        let Some((width, height)) = png::dimensions(bytes) else {
            self.refusals.push(TextureRefusal::NotPng {
                designation: designation.to_owned(),
                detail: "ses octets ne sont pas un PNG".to_owned(),
            });
            return Image::Refused;
        };
        let side = 1..=limits::MAX_TEXTURE_SIDE;
        let (Ok(width_u16), Ok(height_u16)) = (u16::try_from(width), u16::try_from(height)) else {
            return self.too_large(designation, width, height);
        };
        if !side.contains(&width) || !side.contains(&height) {
            return self.too_large(designation, width, height);
        }
        let (offset, size) = self.append(bytes);
        Image::Stored {
            source: texture_source::EMBEDDED,
            width: width_u16,
            height: height_u16,
            offset,
            size,
        }
    }

    fn too_large(&mut self, designation: &str, width: u32, height: u32) -> Image {
        self.refusals.push(TextureRefusal::TooLarge {
            designation: designation.to_owned(),
            width,
            height,
        });
        Image::Refused
    }

    /// Consigne le refus d'un type déclaré autre que PNG ; rend `true` s'il y a
    /// refus.
    fn refuse_declared(&mut self, designation: &str, declared: Option<&str>) -> bool {
        match declared {
            Some(mime) if !mime.eq_ignore_ascii_case(PNG_MIME) => {
                self.refusals.push(TextureRefusal::NotPng {
                    designation: designation.to_owned(),
                    detail: format!("type déclaré {mime}"),
                });
                true
            }
            _ => false,
        }
    }

    /// Consigne le refus d'un chemin dont l'extension n'est pas `png` ; rend
    /// `true` s'il y a refus.
    fn refuse_extension(&mut self, path: &str) -> bool {
        match Path::new(path)
            .extension()
            .and_then(|extension| extension.to_str())
        {
            Some(extension) if !extension.eq_ignore_ascii_case("png") => {
                self.refusals.push(TextureRefusal::NotPng {
                    designation: path.to_owned(),
                    detail: format!("extension « .{extension} »"),
                });
                true
            }
            _ => false,
        }
    }

    /// Ajoute des octets à la zone de données et rend leur plage.
    ///
    /// Au-delà de 4 Gio, la plage ne tient plus dans un `u32` : elle est
    /// saturée, et l'encodage de `TEXR` refusera la table entière — une section
    /// ne peut décrire une telle taille.
    fn append(&mut self, bytes: &[u8]) -> (u32, u32) {
        let offset = u32::try_from(self.table.blob.len()).unwrap_or(u32::MAX);
        let size = u32::try_from(bytes.len()).unwrap_or(u32::MAX);
        self.table.blob.extend_from_slice(bytes);
        (offset, size)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ax_model::dm::material::texture_sampler;

    #[test]
    fn t271_une_image_partagee_n_est_copiee_qu_une_fois() {
        let png = png::header_for_tests(64, 64);
        let mut builder = TextureTableBuilder::default();
        let key = ImageKey::Indexed(0);
        builder.add_embedded(key.clone(), "a", Some("image/png"), &png);
        assert!(builder.has_image(&key));
        // Une seconde rencontre ne range rien : les octets ne sont pas relus.
        builder.add_embedded(key.clone(), "a", Some("image/png"), &[]);

        let a = builder.texture(key.clone(), 0);
        let b = builder.texture(key.clone(), texture_sampler::FILTER_LINEAR);
        let c = builder.texture(key, 0);
        let (table, refusals) = builder.finish();

        assert_eq!((a, b, c), (0, 1, 0), "une entrée par échantillonneur");
        assert_eq!(table.blob.len(), png.len(), "octets copiés une fois");
        assert_eq!(table.data(0), table.data(1));
        assert_eq!((table.entries[0].width, table.entries[0].height), (64, 64));
        assert_eq!(table.entries[0].source, texture_source::EMBEDDED);
        assert!(table.entries.iter().all(|entry| entry.check().is_ok()));
        assert!(refusals.is_empty());
    }

    #[test]
    fn t272_une_image_refusee_l_est_une_fois_et_laisse_le_slot_vide() {
        let mut builder = TextureTableBuilder::default();
        let jpeg = [0xFF, 0xD8, 0xFF, 0xE0];
        let photo = ImageKey::Indexed(3);
        builder.add_embedded(photo.clone(), "photo", None, &jpeg);
        builder.add_embedded(photo.clone(), "photo", None, &jpeg);
        let a = builder.texture(photo.clone(), 0);
        let b = builder.texture(photo, 2);

        let declaree = ImageKey::Indexed(4);
        builder.add_embedded(
            declaree.clone(),
            "declaree",
            Some("image/jpeg"),
            &png::header_for_tests(4, 4),
        );
        let grande = ImageKey::Indexed(5);
        builder.add_embedded(
            grande.clone(),
            "grande",
            None,
            &png::header_for_tests(8192, 16),
        );
        let slots = (
            a,
            b,
            builder.texture(declaree, 0),
            builder.texture(grande, 0),
        );
        let (table, refusals) = builder.finish();

        assert_eq!(slots, (NO_TEXTURE, NO_TEXTURE, NO_TEXTURE, NO_TEXTURE));
        assert!(table.entries.is_empty() && table.blob.is_empty());
        assert_eq!(
            refusals
                .iter()
                .map(TextureRefusal::code)
                .collect::<Vec<_>>(),
            vec![-3004, -3004, -3006],
            "un refus par image, pas par slot"
        );
    }

    #[test]
    fn t271_une_ressource_garde_son_chemin_sans_etre_lue() {
        let mut builder = TextureTableBuilder::default();
        let caisse = ImageKey::Path("tex/caisse.png".to_owned());
        builder.add_resource(caisse.clone(), "tex/caisse.png", None);
        let slot = builder.texture(caisse, texture_sampler::FILTER_NEAREST);
        let (table, refusals) = builder.finish();

        assert_eq!(slot, 0);
        assert_eq!(table.resource_path(0), Some("tex/caisse.png"));
        assert_eq!(table.entries[0].source, texture_source::RESOURCE);
        assert_eq!((table.entries[0].width, table.entries[0].height), (0, 0));
        assert_eq!(table.entries[0].check(), Ok(()));
        assert!(refusals.is_empty());
    }

    #[test]
    fn t272_une_ressource_qui_n_est_pas_un_png_est_refusee() {
        // R-532 : un type déclaré, ou à défaut l'extension, suffit à refuser
        // sans lire ; un chemin sans extension laisse la signature trancher.
        let mut builder = TextureTableBuilder::default();
        let cas = [
            ("tex/photo.jpg", None, NO_TEXTURE),
            ("tex/declaree.png", Some("image/jpeg"), NO_TEXTURE),
            ("tex/MAJUSCULES.PNG", None, 0),
            ("tex/sans_extension", None, 1),
            ("tex/declaree_png.bin", Some("image/png"), 2),
        ];
        for (path, declared, attendu) in cas {
            let key = ImageKey::Path(path.to_owned());
            builder.add_resource(key.clone(), path, declared);
            assert_eq!(builder.texture(key, 0), attendu, "{path}");
        }
        let (_, refusals) = builder.finish();
        assert_eq!(
            refusals,
            [
                TextureRefusal::NotPng {
                    designation: "tex/photo.jpg".to_owned(),
                    detail: "extension « .jpg »".to_owned(),
                },
                TextureRefusal::NotPng {
                    designation: "tex/declaree.png".to_owned(),
                    detail: "type déclaré image/jpeg".to_owned(),
                },
            ]
        );
    }

    fn proche(a: [f32; 2], b: [f32; 2]) -> bool {
        (a[0] - b[0]).abs() < 1e-6 && (a[1] - b[1]).abs() < 1e-6
    }

    #[test]
    fn t271_une_transformation_neutre_n_est_pas_une_transformation() {
        let neutre = UvMapping::from_transform(0, [0.0; 2], 0.0, [1.0; 2]);
        assert_eq!(neutre, UvMapping::IDENTITY);
        assert_eq!(neutre.apply([0.25, 0.75]), [0.25, 0.75]);
    }

    #[test]
    fn t271_la_transformation_suit_la_specification_khronos() {
        // Échelle puis décalage : u' = sx·u + ox, v' = sy·v + oy.
        let tuile = UvMapping::from_transform(0, [0.5, 0.25], 0.0, [2.0, 4.0]);
        assert!(proche(tuile.apply([1.0, 1.0]), [2.5, 4.25]));

        // Un quart de tour : u' = −v, v' = u. Le signe est celui de la
        // spécification, colonnes (cos, sin) et (−sin, cos) ; inversé, la
        // texture tournerait dans l'autre sens, sans rien qui le signale.
        let quart = UvMapping::from_transform(0, [0.0; 2], core::f32::consts::FRAC_PI_2, [1.0; 2]);
        assert!(proche(quart.apply([1.0, 0.0]), [0.0, 1.0]));
        assert!(proche(quart.apply([0.0, 1.0]), [-1.0, 0.0]));

        // L'échelle s'applique avant la rotation.
        let compose =
            UvMapping::from_transform(0, [0.0; 2], core::f32::consts::FRAC_PI_2, [2.0, 3.0]);
        assert!(proche(compose.apply([1.0, 1.0]), [-3.0, 2.0]));
    }

    #[test]
    fn t271_les_materiaux_par_defaut() {
        let gltf = gltf_default_material();
        assert_eq!((gltf.metallic, gltf.roughness), (1.0, 1.0));
        assert_eq!(gltf.albedo_factor, [1.0; 4]);
        assert_eq!(gltf.blend_mode, blend_mode::OPAQUE);
        assert_eq!(gltf.cull_mode, cull_mode::BACK);
        assert_eq!(gltf.texture_slots(), [NO_TEXTURE; 6]);
        assert_eq!(gltf.check(), Ok(()));

        let plain = plain_default_material();
        assert_eq!(
            (plain.metallic, plain.roughness),
            (DEFAULT_METALLIC, DEFAULT_ROUGHNESS)
        );
        assert_eq!(plain.check(), Ok(()));
    }
}
