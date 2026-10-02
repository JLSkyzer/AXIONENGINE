//! DM-05 — matériaux de rendu, et textures qu'ils désignent (C-26, ADR-122).
//!
//! [`MaterialDesc`] est transcrit **tel quel** du cahier des charges (§3.5).
//! [`TextureDesc`] est fixé par ADR-122 (§2) : la PARTIE 7 ne donne de `TEXR`
//! que son contenu — « table de `ResourceLocation` + textures extraites ».
//!
//! Les deux traversent la frontière : les sections `MATL` et `TEXR` les portent,
//! puis la charge de matériaux remise à Java (ADR-122 §6). Leurs dispositions
//! sont donc figées par test, comme celle du sommet.
//!
//! Les **facteurs** sont en espace linéaire, comme en glTF : c'est au backend
//! qui dessine de les ramener à son espace de rendu (ADR-122 §7).

use crate::dm::limits;

/// Slot de texture vide : la texture neutre du slot s'applique (C-26).
pub const NO_TEXTURE: u16 = u16::MAX;

/// Aucun profil d'usure (C-47).
pub const NO_WEAR_PROFILE: u16 = u16::MAX;

/// Mode de mélange d'un matériau (DM-05).
pub mod blend_mode {
    /// Opaque : l'alpha est ignoré.
    pub const OPAQUE: u8 = 0;
    /// Découpe : un fragment sous `alpha_cutoff` est rejeté.
    pub const CUTOUT: u8 = 1;
    /// Translucide : mélangé, trié par distance (R-150).
    pub const TRANSLUCENT: u8 = 2;

    /// Indique si la valeur est un mode connu.
    #[must_use]
    pub const fn is_known(value: u8) -> bool {
        value <= TRANSLUCENT
    }
}

/// Faces rendues d'un matériau (DM-05).
pub mod cull_mode {
    /// Faces arrière éliminées.
    pub const BACK: u8 = 0;
    /// Les deux faces sont rendues.
    pub const NONE: u8 = 1;

    /// Indique si la valeur est un mode connu.
    #[must_use]
    pub const fn is_known(value: u8) -> bool {
        value <= NONE
    }
}

/// Modèle d'éclairage d'un matériau (DM-05).
pub mod shading_model {
    /// PBR metallic-roughness (R-151).
    pub const PBR: u8 = 0;
    /// PBR avec une couche de vernis (carrosserie).
    pub const PBR_CLEARCOAT: u8 = 1;
    /// PBR avec le lustre d'un tissu.
    pub const PBR_SHEEN: u8 = 2;
    /// Sans éclairage.
    pub const UNLIT: u8 = 3;
    /// Éclairage du backend vanilla (R-1513).
    pub const VANILLA_COMPAT: u8 = 4;

    /// Indique si la valeur est un modèle connu.
    #[must_use]
    pub const fn is_known(value: u8) -> bool {
        value <= VANILLA_COMPAT
    }
}

/// Drapeaux d'un matériau (DM-05).
pub mod material_flags {
    /// Les couleurs de sommet multiplient l'albedo.
    pub const VERTEX_COLOR: u16 = 1 << 0;
    /// Le matériau accepte une teinte d'instance.
    pub const TINTABLE: u16 = 1 << 1;
    /// Éclairé au maximum, quelle que soit la lumière du monde.
    pub const FULLBRIGHT: u16 = 1 << 2;
    /// L'usure de surface s'applique (C-47).
    pub const WEAR_ENABLED: u16 = 1 << 3;
    /// Le matériau reçoit les décalques (C-69).
    pub const DECAL_RECEIVER: u16 = 1 << 4;
    /// Tous les drapeaux connus : un bit hors de ce masque est refusé.
    pub const KNOWN: u16 = VERTEX_COLOR | TINTABLE | FULLBRIGHT | WEAR_ENABLED | DECAL_RECEIVER;
}

/// Matériau de rendu (DM-05).
///
/// Un slot de texture vaut l'index d'une entrée de `TEXR`, ou [`NO_TEXTURE`].
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct MaterialDesc {
    /// Empreinte FNV-1a 64 du nom.
    pub name_hash: u64,
    /// Couleur de base.
    pub albedo_tex: u16,
    /// Normales en espace tangent.
    pub normal_tex: u16,
    /// Occlusion (R), rugosité (G), métal (B).
    pub orm_tex: u16,
    /// Émission.
    pub emissive_tex: u16,
    /// Hauteur, pour le parallax.
    pub height_tex: u16,
    /// Masque de dommage : R éraflure, G déformation, B brûlure, A saleté.
    pub damage_tex: u16,
    /// Couleur de base, RGBA linéaire.
    pub albedo_factor: [f32; 4],
    /// Émission, RGB linéaire.
    pub emissive_factor: [f32; 3],
    /// Métal, de 0 à 1.
    pub metallic: f32,
    /// Rugosité, de 0 à 1.
    pub roughness: f32,
    /// Force de l'occlusion.
    pub occlusion_strength: f32,
    /// Échelle des normales.
    pub normal_scale: f32,
    /// Seuil de découpe, de 0 à 1 (mode `CUTOUT`).
    pub alpha_cutoff: f32,
    /// Échelle du parallax.
    pub parallax_scale: f32,
    /// Couche de vernis.
    pub clearcoat: f32,
    /// Rugosité du vernis.
    pub clearcoat_roughness: f32,
    /// Lustre de tissu.
    pub sheen: f32,
    /// Anisotropie (métal brossé).
    pub anisotropy: f32,
    /// Voir [`blend_mode`].
    pub blend_mode: u8,
    /// Voir [`cull_mode`].
    pub cull_mode: u8,
    /// Voir [`shading_model`].
    pub shading_model: u8,
    /// Réservé, à zéro.
    pub _pad: u8,
    /// Voir [`material_flags`].
    pub flags: u16,
    /// Profil d'usure (C-47), [`NO_WEAR_PROFILE`] si aucun.
    pub wear_profile: u16,
}

impl MaterialDesc {
    /// Taille du descripteur, en octets.
    pub const BYTES: usize = 96;

    /// Les six slots de texture, dans l'ordre de la disposition.
    #[must_use]
    pub const fn texture_slots(&self) -> [u16; 6] {
        [
            self.albedo_tex,
            self.normal_tex,
            self.orm_tex,
            self.emissive_tex,
            self.height_tex,
            self.damage_tex,
        ]
    }

    /// Les dix-sept flottants, dans l'ordre de la disposition.
    fn floats(&self) -> [f32; 17] {
        let [r, g, b, a] = self.albedo_factor;
        let [er, eg, eb] = self.emissive_factor;
        [
            r,
            g,
            b,
            a,
            er,
            eg,
            eb,
            self.metallic,
            self.roughness,
            self.occlusion_strength,
            self.normal_scale,
            self.alpha_cutoff,
            self.parallax_scale,
            self.clearcoat,
            self.clearcoat_roughness,
            self.sheen,
            self.anisotropy,
        ]
    }

    /// Écrit le descripteur sur ses 96 octets, en petit-boutiste, à la suite de
    /// `out`.
    ///
    /// Sérialisation unique, partagée par la section `MATL` et la charge de
    /// matériaux (ADR-122). Le champ réservé est écrit à zéro.
    pub fn write_le(&self, out: &mut Vec<u8>) {
        out.extend_from_slice(&self.name_hash.to_le_bytes());
        for slot in self.texture_slots() {
            out.extend_from_slice(&slot.to_le_bytes());
        }
        for value in self.floats() {
            out.extend_from_slice(&value.to_le_bytes());
        }
        out.push(self.blend_mode);
        out.push(self.cull_mode);
        out.push(self.shading_model);
        out.push(0);
        out.extend_from_slice(&self.flags.to_le_bytes());
        out.extend_from_slice(&self.wear_profile.to_le_bytes());
    }

    /// Lit un descripteur depuis ses 96 octets petit-boutistes ; le champ
    /// réservé est rendu à zéro.
    #[must_use]
    pub fn read_le(bytes: &[u8; Self::BYTES]) -> Self {
        let u16_at = |at: usize| u16::from_le_bytes([bytes[at], bytes[at + 1]]);
        let f32_at = |at: usize| {
            f32::from_le_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]])
        };
        let mut name_hash = [0u8; 8];
        name_hash.copy_from_slice(&bytes[0..8]);
        Self {
            name_hash: u64::from_le_bytes(name_hash),
            albedo_tex: u16_at(8),
            normal_tex: u16_at(10),
            orm_tex: u16_at(12),
            emissive_tex: u16_at(14),
            height_tex: u16_at(16),
            damage_tex: u16_at(18),
            albedo_factor: [f32_at(20), f32_at(24), f32_at(28), f32_at(32)],
            emissive_factor: [f32_at(36), f32_at(40), f32_at(44)],
            metallic: f32_at(48),
            roughness: f32_at(52),
            occlusion_strength: f32_at(56),
            normal_scale: f32_at(60),
            alpha_cutoff: f32_at(64),
            parallax_scale: f32_at(68),
            clearcoat: f32_at(72),
            clearcoat_roughness: f32_at(76),
            sheen: f32_at(80),
            anisotropy: f32_at(84),
            blend_mode: bytes[88],
            cull_mode: bytes[89],
            shading_model: bytes[90],
            _pad: 0,
            flags: u16_at(92),
            wear_profile: u16_at(94),
        }
    }

    /// Vérifie ce que la disposition ne garantit pas : énumérations et drapeaux
    /// connus, facteurs finis, seuil de découpe dans `[0, 1]`.
    ///
    /// Les slots de texture ne sont pas examinés ici : leur borne est le nombre
    /// d'entrées de `TEXR`, que seul le lecteur des deux sections connaît.
    ///
    /// # Errors
    ///
    /// Ce qui ne va pas, en clair.
    pub fn check(&self) -> Result<(), &'static str> {
        if !blend_mode::is_known(self.blend_mode) {
            return Err("mode de mélange inconnu");
        }
        if !cull_mode::is_known(self.cull_mode) {
            return Err("mode de faces inconnu");
        }
        if !shading_model::is_known(self.shading_model) {
            return Err("modèle d'éclairage inconnu");
        }
        if self.flags & !material_flags::KNOWN != 0 {
            return Err("drapeau de matériau inconnu");
        }
        if !self.floats().iter().all(|value| value.is_finite()) {
            return Err("facteur de matériau non fini");
        }
        if !(0.0..=1.0).contains(&self.alpha_cutoff) {
            return Err("seuil de découpe hors de [0, 1]");
        }
        Ok(())
    }
}

/// Provenance d'une texture (ADR-122 §2).
pub mod texture_source {
    /// Octets PNG embarqués dans la section `TEXR`, non décodés (R-532).
    pub const EMBEDDED: u8 = 1;
    /// Chemin relatif au modèle, résolu en `ResourceLocation` par Java.
    pub const RESOURCE: u8 = 2;
}

/// Format d'une texture (ADR-122 §2).
pub mod texture_format {
    /// PNG, seul format admis (R-532).
    pub const PNG: u8 = 1;
}

/// Échantillonneur d'une texture (ADR-122 §2) : filtrage sur deux bits,
/// écrêtage par axe sur deux autres, répétition par défaut.
pub mod texture_sampler {
    /// Bits du filtrage.
    pub const FILTER_MASK: u8 = 0b11;
    /// Filtrage non déclaré par la source : le défaut ratifié s'applique.
    pub const FILTER_UNDECLARED: u8 = 0;
    /// Filtrage au plus proche.
    pub const FILTER_NEAREST: u8 = 1;
    /// Filtrage linéaire.
    pub const FILTER_LINEAR: u8 = 2;
    /// Coordonnée U écrêtée au bord, plutôt que répétée.
    pub const CLAMP_U: u8 = 1 << 2;
    /// Coordonnée V écrêtée au bord, plutôt que répétée.
    pub const CLAMP_V: u8 = 1 << 3;
    /// Tous les bits connus : un bit hors de ce masque est refusé.
    pub const KNOWN: u8 = FILTER_MASK | CLAMP_U | CLAMP_V;

    /// Filtrage déclaré par l'échantillonneur.
    #[must_use]
    pub const fn filter(sampler: u8) -> u8 {
        sampler & FILTER_MASK
    }
}

/// Une texture : une image et son échantillonneur (ADR-122 §2).
///
/// `data_offset` et `data_size` désignent les octets PNG d'une texture
/// `EMBEDDED`, ou le chemin UTF-8 d'une texture `RESOURCE`, dans la zone de
/// données de la section qui la porte.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TextureDesc {
    /// Voir [`texture_source`].
    pub source: u8,
    /// Voir [`texture_format`].
    pub format: u8,
    /// Voir [`texture_sampler`].
    pub sampler: u8,
    /// Réservé, à zéro.
    pub _pad: u8,
    /// Largeur lue dans l'IHDR (`EMBEDDED`) ; 0 si inconnue (`RESOURCE`).
    pub width: u16,
    /// Hauteur lue dans l'IHDR (`EMBEDDED`) ; 0 si inconnue (`RESOURCE`).
    pub height: u16,
    /// Début des octets, dans la zone de données.
    pub data_offset: u32,
    /// Nombre d'octets.
    pub data_size: u32,
}

impl TextureDesc {
    /// Taille du descripteur, en octets.
    pub const BYTES: usize = 16;

    /// Écrit le descripteur sur ses 16 octets, en petit-boutiste, à la suite de
    /// `out`. Le champ réservé est écrit à zéro.
    pub fn write_le(&self, out: &mut Vec<u8>) {
        out.push(self.source);
        out.push(self.format);
        out.push(self.sampler);
        out.push(0);
        out.extend_from_slice(&self.width.to_le_bytes());
        out.extend_from_slice(&self.height.to_le_bytes());
        out.extend_from_slice(&self.data_offset.to_le_bytes());
        out.extend_from_slice(&self.data_size.to_le_bytes());
    }

    /// Lit un descripteur depuis ses 16 octets petit-boutistes ; le champ
    /// réservé est rendu à zéro.
    #[must_use]
    pub fn read_le(bytes: &[u8; Self::BYTES]) -> Self {
        let u16_at = |at: usize| u16::from_le_bytes([bytes[at], bytes[at + 1]]);
        let u32_at = |at: usize| {
            u32::from_le_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]])
        };
        Self {
            source: bytes[0],
            format: bytes[1],
            sampler: bytes[2],
            _pad: 0,
            width: u16_at(4),
            height: u16_at(6),
            data_offset: u32_at(8),
            data_size: u32_at(12),
        }
    }

    /// Vérifie ce que la disposition ne garantit pas : provenance, format et
    /// échantillonneur connus ; dimensions d'une texture embarquée dans
    /// `1..=4096` (R-570) ; dimensions d'une texture de ressource à zéro, le
    /// compilateur ne l'ayant pas lue.
    ///
    /// La plage de données n'est pas examinée ici : sa borne est la zone de
    /// données de la section, que seul son lecteur connaît.
    ///
    /// # Errors
    ///
    /// Ce qui ne va pas, en clair.
    pub fn check(&self) -> Result<(), &'static str> {
        if self.format != texture_format::PNG {
            return Err("format de texture inconnu");
        }
        if self.sampler & !texture_sampler::KNOWN != 0
            || texture_sampler::filter(self.sampler) > texture_sampler::FILTER_LINEAR
        {
            return Err("échantillonneur inconnu");
        }
        let side = 1..=limits::MAX_TEXTURE_SIDE;
        match self.source {
            texture_source::EMBEDDED => {
                if !side.contains(&u32::from(self.width)) || !side.contains(&u32::from(self.height))
                {
                    return Err("dimensions de texture hors de 1..=4096 (R-570)");
                }
            }
            texture_source::RESOURCE => {
                if self.width != 0 || self.height != 0 {
                    return Err("dimensions d'une texture de ressource non nulles");
                }
            }
            _ => return Err("provenance de texture inconnue"),
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Un matériau dont chaque champ porte une valeur distincte : un champ écrit
    /// au mauvais endroit ne passerait pas inaperçu.
    fn materiau() -> MaterialDesc {
        MaterialDesc {
            name_hash: 0x0102_0304_0506_0708,
            albedo_tex: 1,
            normal_tex: 2,
            orm_tex: 3,
            emissive_tex: 4,
            height_tex: NO_TEXTURE,
            damage_tex: 5,
            albedo_factor: [0.5, 0.25, 0.125, 0.75],
            emissive_factor: [1.0, 2.0, 3.0],
            metallic: 0.1,
            roughness: 0.2,
            occlusion_strength: 0.3,
            normal_scale: 0.4,
            alpha_cutoff: 0.5,
            parallax_scale: 0.6,
            clearcoat: 0.7,
            clearcoat_roughness: 0.8,
            sheen: 0.9,
            anisotropy: -0.5,
            blend_mode: blend_mode::CUTOUT,
            cull_mode: cull_mode::NONE,
            shading_model: shading_model::PBR_SHEEN,
            _pad: 0,
            flags: material_flags::VERTEX_COLOR | material_flags::DECAL_RECEIVER,
            wear_profile: 9,
        }
    }

    fn embarquee() -> TextureDesc {
        TextureDesc {
            source: texture_source::EMBEDDED,
            format: texture_format::PNG,
            sampler: texture_sampler::FILTER_LINEAR | texture_sampler::CLAMP_V,
            _pad: 0,
            width: 256,
            height: 128,
            data_offset: 32,
            data_size: 1000,
        }
    }

    #[test]
    fn dm05_material_desc_disposition_figee() {
        use core::mem::{align_of, offset_of, size_of};
        assert_eq!(size_of::<MaterialDesc>(), MaterialDesc::BYTES);
        assert_eq!(align_of::<MaterialDesc>(), 8);
        assert_eq!(offset_of!(MaterialDesc, name_hash), 0);
        assert_eq!(offset_of!(MaterialDesc, albedo_tex), 8);
        assert_eq!(offset_of!(MaterialDesc, normal_tex), 10);
        assert_eq!(offset_of!(MaterialDesc, orm_tex), 12);
        assert_eq!(offset_of!(MaterialDesc, emissive_tex), 14);
        assert_eq!(offset_of!(MaterialDesc, height_tex), 16);
        assert_eq!(offset_of!(MaterialDesc, damage_tex), 18);
        assert_eq!(offset_of!(MaterialDesc, albedo_factor), 20);
        assert_eq!(offset_of!(MaterialDesc, emissive_factor), 36);
        assert_eq!(offset_of!(MaterialDesc, metallic), 48);
        assert_eq!(offset_of!(MaterialDesc, roughness), 52);
        assert_eq!(offset_of!(MaterialDesc, occlusion_strength), 56);
        assert_eq!(offset_of!(MaterialDesc, normal_scale), 60);
        assert_eq!(offset_of!(MaterialDesc, alpha_cutoff), 64);
        assert_eq!(offset_of!(MaterialDesc, parallax_scale), 68);
        assert_eq!(offset_of!(MaterialDesc, clearcoat), 72);
        assert_eq!(offset_of!(MaterialDesc, clearcoat_roughness), 76);
        assert_eq!(offset_of!(MaterialDesc, sheen), 80);
        assert_eq!(offset_of!(MaterialDesc, anisotropy), 84);
        assert_eq!(offset_of!(MaterialDesc, blend_mode), 88);
        assert_eq!(offset_of!(MaterialDesc, cull_mode), 89);
        assert_eq!(offset_of!(MaterialDesc, shading_model), 90);
        assert_eq!(offset_of!(MaterialDesc, _pad), 91);
        assert_eq!(offset_of!(MaterialDesc, flags), 92);
        assert_eq!(offset_of!(MaterialDesc, wear_profile), 94);
    }

    #[test]
    fn texture_desc_disposition_figee() {
        use core::mem::{align_of, offset_of, size_of};
        assert_eq!(size_of::<TextureDesc>(), TextureDesc::BYTES);
        assert_eq!(align_of::<TextureDesc>(), 4);
        assert_eq!(offset_of!(TextureDesc, source), 0);
        assert_eq!(offset_of!(TextureDesc, format), 1);
        assert_eq!(offset_of!(TextureDesc, sampler), 2);
        assert_eq!(offset_of!(TextureDesc, _pad), 3);
        assert_eq!(offset_of!(TextureDesc, width), 4);
        assert_eq!(offset_of!(TextureDesc, height), 6);
        assert_eq!(offset_of!(TextureDesc, data_offset), 8);
        assert_eq!(offset_of!(TextureDesc, data_size), 12);
    }

    #[test]
    fn dm05_les_octets_suivent_la_disposition() {
        let mut octets = Vec::new();
        materiau().write_le(&mut octets);
        assert_eq!(octets.len(), MaterialDesc::BYTES);

        let u16_at = |at: usize| u16::from_le_bytes([octets[at], octets[at + 1]]);
        let f32_at = |at: usize| f32::from_le_bytes(octets[at..at + 4].try_into().unwrap());
        assert_eq!(
            u64::from_le_bytes(octets[0..8].try_into().unwrap()),
            0x0102_0304_0506_0708
        );
        assert_eq!(
            [8, 10, 12, 14, 16, 18].map(u16_at),
            [1, 2, 3, 4, NO_TEXTURE, 5]
        );
        assert_eq!([20, 24, 28, 32].map(f32_at), [0.5, 0.25, 0.125, 0.75]);
        assert_eq!([36, 40, 44].map(f32_at), [1.0, 2.0, 3.0]);
        assert_eq!(f32_at(48), 0.1, "metallic");
        assert_eq!(f32_at(64), 0.5, "alpha_cutoff");
        assert_eq!(f32_at(84), -0.5, "anisotropy");
        assert_eq!(
            octets[88..92],
            [
                blend_mode::CUTOUT,
                cull_mode::NONE,
                shading_model::PBR_SHEEN,
                0
            ]
        );
        assert_eq!(
            u16_at(92),
            material_flags::VERTEX_COLOR | material_flags::DECAL_RECEIVER
        );
        assert_eq!(u16_at(94), 9, "wear_profile");
    }

    #[test]
    fn dm05_aller_retour_et_reserve_a_zero() {
        let mut sale = materiau();
        sale._pad = 0xAB;
        let mut octets = Vec::new();
        sale.write_le(&mut octets);
        assert_eq!(octets[91], 0, "la réserve est écrite à zéro");

        let relu = MaterialDesc::read_le(octets.as_slice().try_into().unwrap());
        assert_eq!(relu, materiau());
    }

    #[test]
    fn texture_desc_octets_et_aller_retour() {
        let mut sale = embarquee();
        sale._pad = 0x7F;
        let mut octets = Vec::new();
        sale.write_le(&mut octets);
        assert_eq!(
            octets,
            [
                texture_source::EMBEDDED,
                texture_format::PNG,
                texture_sampler::FILTER_LINEAR | texture_sampler::CLAMP_V,
                0,
                0,
                1, // 256
                128,
                0,
                32,
                0,
                0,
                0,
                0xE8,
                0x03, // 1000
                0,
                0,
            ]
        );
        assert_eq!(
            TextureDesc::read_le(octets.as_slice().try_into().unwrap()),
            embarquee()
        );
    }

    #[test]
    fn dm05_un_materiau_valide_passe_le_controle() {
        assert_eq!(materiau().check(), Ok(()));
    }

    #[test]
    fn dm05_le_controle_refuse_ce_que_la_disposition_laisse_passer() {
        let refus = |modifier: fn(&mut MaterialDesc)| {
            let mut materiau = materiau();
            modifier(&mut materiau);
            materiau.check()
        };
        assert!(refus(|m| m.blend_mode = 3).is_err());
        assert!(refus(|m| m.cull_mode = 2).is_err());
        assert!(refus(|m| m.shading_model = 5).is_err());
        assert!(refus(|m| m.flags = 1 << 5).is_err());
        assert!(refus(|m| m.roughness = f32::NAN).is_err());
        assert!(refus(|m| m.emissive_factor[2] = f32::INFINITY).is_err());
        assert!(refus(|m| m.alpha_cutoff = 1.5).is_err());
        assert!(refus(|m| m.alpha_cutoff = -0.1).is_err());
    }

    #[test]
    fn texture_desc_controle() {
        assert_eq!(embarquee().check(), Ok(()));
        let ressource = TextureDesc {
            source: texture_source::RESOURCE,
            width: 0,
            height: 0,
            ..embarquee()
        };
        assert_eq!(ressource.check(), Ok(()));

        let refus = |modifier: fn(&mut TextureDesc)| {
            let mut texture = embarquee();
            modifier(&mut texture);
            texture.check()
        };
        assert!(refus(|t| t.source = 0).is_err());
        assert!(refus(|t| t.source = 3).is_err());
        assert!(refus(|t| t.format = 2).is_err());
        assert!(refus(|t| t.sampler = 3).is_err(), "filtrage inconnu");
        assert!(refus(|t| t.sampler = 1 << 4).is_err(), "bit inconnu");
        assert!(refus(|t| t.width = 0).is_err());
        assert!(refus(|t| t.height = 4097).is_err());
        assert!(
            TextureDesc {
                width: 16,
                ..ressource
            }
            .check()
            .is_err(),
            "une ressource n'a pas de dimensions connues du compilateur"
        );
    }
}
