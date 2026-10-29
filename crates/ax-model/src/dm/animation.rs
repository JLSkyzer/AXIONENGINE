//! Squelette et animations d'un asset (DM-10, ADR-155 D2).
//!
//! Les trois descripteurs du cahier des charges, et ce qu'il laisse ouvert : les valeurs de
//! `channel` et d'`interp`, la sémantique de `flags`, la disposition des clés. Une clé est
//! `f32` sans quantification (décision de Killian, ADR-155 Q2) : un temps, puis la valeur — 3
//! flottants pour une translation ou une échelle, 4 pour une rotation `(x, y, z, w)` —, et en
//! `CUBIC_SPLINE` trois valeurs par clé, tangente d'entrée, valeur, tangente de sortie, comme
//! glTF les range.

use super::handle::Handle;
use super::scene::NONE_U16;

/// Os d'un squelette (DM-10).
///
/// La pose d'un os est celle de son node : l'os n'en porte que l'index et la matrice qui ramène
/// un sommet de l'espace du mesh à celui de l'os, à la pose de liaison.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct BoneDesc {
    /// Empreinte du nom, celle du node.
    pub name_hash: u64,
    /// Os parent, le plus proche ancêtre du node qui soit un os ; [`NONE_U16`] pour une racine.
    pub parent: u16,
    /// Réservé, à zéro.
    pub _pad: u16,
    /// Node de l'os.
    pub node: u32,
    /// Matrice de liaison inverse, colonne par colonne comme glTF.
    pub inverse_bind: [f32; 16],
}

impl BoneDesc {
    /// Taille sérialisée, celle de la disposition `repr(C)`.
    pub const BYTES: usize = 80;

    /// Indique si l'os est une racine du squelette.
    #[must_use]
    pub const fn is_root(&self) -> bool {
        self.parent == NONE_U16
    }
}

/// Drapeaux d'une animation, champ `flags` d'[`AnimationDesc`].
pub mod animation_flags {
    /// L'animation boucle : passé sa durée, elle reprend à zéro.
    pub const LOOP: u32 = 1 << 0;
}

/// Animation d'un asset (DM-10) : une plage de pistes.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct AnimationDesc {
    /// Empreinte du nom.
    pub name_hash: u64,
    /// Durée, en secondes : le temps de la dernière clé de ses pistes.
    pub duration: f32,
    /// Première piste, dans le tableau des pistes de l'asset.
    pub track_offset: u32,
    /// Nombre de pistes, consécutives à partir de `track_offset`.
    pub track_count: u32,
    /// Drapeaux, voir [`animation_flags`].
    pub flags: u32,
}

impl AnimationDesc {
    /// Taille sérialisée, celle de la disposition `repr(C)`.
    pub const BYTES: usize = 24;
}

/// Ce qu'une piste anime, champ `channel` de [`TrackDesc`].
pub mod channel {
    /// La translation locale du node.
    pub const TRANSLATION: u8 = 0;
    /// La rotation locale du node, quaternion `(x, y, z, w)`.
    pub const ROTATION: u8 = 1;
    /// L'échelle locale du node.
    pub const SCALE: u8 = 2;

    /// Le nombre de flottants d'une valeur du canal, `None` s'il n'existe pas.
    #[must_use]
    pub const fn components(channel: u8) -> Option<usize> {
        match channel {
            TRANSLATION | SCALE => Some(3),
            ROTATION => Some(4),
            _ => None,
        }
    }
}

/// Interpolation d'une piste, champ `interp` de [`TrackDesc`] (§18.2).
pub mod interp {
    /// La valeur de la clé précédente, tenue jusqu'à la suivante.
    pub const STEP: u8 = 0;
    /// Linéaire ; une rotation l'est par `slerp`.
    pub const LINEAR: u8 = 1;
    /// Hermite cubique, tangentes d'entrée et de sortie portées par chaque clé.
    pub const CUBIC_SPLINE: u8 = 2;

    /// Le nombre de valeurs par clé, `None` si l'interpolation n'existe pas.
    #[must_use]
    pub const fn values_per_key(interp: u8) -> Option<usize> {
        match interp {
            STEP | LINEAR => Some(1),
            CUBIC_SPLINE => Some(3),
            _ => None,
        }
    }
}

/// Piste d'une animation (DM-10) : les clés d'un canal d'un node.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct TrackDesc {
    /// Node animé.
    pub target_node: u32,
    /// Canal, voir [`channel`].
    pub channel: u8,
    /// Interpolation, voir [`interp`].
    pub interp: u8,
    /// Réservé, à zéro.
    pub _pad: [u8; 2],
    /// Premier flottant de la piste, dans le tableau des clés de l'asset.
    pub key_offset: u32,
    /// Nombre de clés.
    pub key_count: u32,
}

impl TrackDesc {
    /// Taille sérialisée, celle de la disposition `repr(C)`.
    pub const BYTES: usize = 16;

    /// Le nombre de flottants d'une clé — son temps et ses valeurs —, `None` si le canal ou
    /// l'interpolation n'existe pas.
    #[must_use]
    pub const fn key_stride(&self) -> Option<usize> {
        match (
            channel::components(self.channel),
            interp::values_per_key(self.interp),
        ) {
            (Some(components), Some(values)) => Some(1 + components * values),
            _ => None,
        }
    }
}

/// Ce qu'une [`AnimCommand`] demande, champ `action`.
pub mod anim_action {
    /// Jouer `clip` sur `layer`, de poids `weight`, avec un fondu de `fade` secondes.
    pub const PLAY: u32 = 1;
    /// Arrêter `layer` par un fondu de `fade` secondes.
    pub const STOP: u32 = 2;
    /// Régler la vitesse de `layer` à `speed`.
    pub const SET_SPEED: u32 = 3;
    /// Restreindre `layer` aux `mask_count` nodes qui suivent la commande ; aucun : tous.
    pub const SET_MASK: u32 = 4;
    /// Oublier l'instance : l'assembly a quitté le rendu (client seulement).
    pub const FORGET: u32 = 5;
}

/// Une commande d'animation horodatée (ADR-155, tranche 3) : la même au client, que Java pousse par
/// `axion_render_animate`, et au serveur, par `SIM_IN`.
///
/// `tick` est un instant en ticks de jeu, fraction comprise (décision de Killian : l'horloge est
/// celle du serveur, 20 ticks par seconde). `fade` est en secondes, comme l'API le présente. Une
/// commande `SET_MASK` est suivie de `mask_count` index de nodes `u32` ; les autres de rien.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct AnimCommand {
    /// Instant de la commande, en ticks.
    pub tick: f64,
    /// Voir [`anim_action`].
    pub action: u32,
    /// Couche, de 0 à 3.
    pub layer: u32,
    /// Clip, rang dans la section `ANIM` de l'asset (`PLAY`).
    pub clip: u32,
    /// Poids, dans `[0, 1]` (`PLAY`).
    pub weight: f32,
    /// Durée du fondu, en secondes (`PLAY`, `STOP`).
    pub fade: f32,
    /// Vitesse (`PLAY`, `SET_SPEED`).
    pub speed: f32,
    /// Nodes du masque qui suivent la commande (`SET_MASK`).
    pub mask_count: u32,
    /// Temps de clip à l'instant de la commande, en secondes (`PLAY`) : 0 joue le clip depuis son
    /// début. Le descripteur réseau v3 rejoue ainsi un clip déjà en cours (ADR-155, tranche 4).
    pub origin: f32,
}

impl AnimCommand {
    /// Taille sur la frontière, en octets.
    pub const BYTES: usize = 40;
}

/// L'en-tête d'une commande d'animation du client : l'assembly, par l'`id` de son candidat de rendu
/// (ADR-154), et son asset. La commande suit.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct RenderAnimate {
    /// `id` du candidat de rendu de l'assembly.
    pub id: u32,
    /// Réservé, à zéro.
    pub _pad: u32,
    /// L'asset de l'assembly.
    pub asset: Handle,
}

impl RenderAnimate {
    /// Taille sur la frontière, en octets, sans la commande qui suit.
    pub const BYTES: usize = 16;
}

#[cfg(test)]
mod tests {
    use super::*;
    use core::mem::{offset_of, size_of};

    #[test]
    fn t230_dispositions_de_dm10() {
        // Les sections `SKEL` et `ANIM` se lisent en place (R-881) : un champ déplacé rendrait
        // illisible tout asset compilé, sans qu'aucune erreur ne le dise.
        assert_eq!(size_of::<BoneDesc>(), BoneDesc::BYTES);
        assert_eq!(offset_of!(BoneDesc, name_hash), 0);
        assert_eq!(offset_of!(BoneDesc, parent), 8);
        assert_eq!(offset_of!(BoneDesc, node), 12);
        assert_eq!(offset_of!(BoneDesc, inverse_bind), 16);

        assert_eq!(size_of::<AnimationDesc>(), AnimationDesc::BYTES);
        assert_eq!(offset_of!(AnimationDesc, duration), 8);
        assert_eq!(offset_of!(AnimationDesc, track_offset), 12);
        assert_eq!(offset_of!(AnimationDesc, track_count), 16);
        assert_eq!(offset_of!(AnimationDesc, flags), 20);

        assert_eq!(size_of::<TrackDesc>(), TrackDesc::BYTES);
        assert_eq!(offset_of!(TrackDesc, channel), 4);
        assert_eq!(offset_of!(TrackDesc, interp), 5);
        assert_eq!(offset_of!(TrackDesc, key_offset), 8);
        assert_eq!(offset_of!(TrackDesc, key_count), 12);

        assert_eq!(size_of::<AnimCommand>(), AnimCommand::BYTES);
        assert_eq!(offset_of!(AnimCommand, action), 8);
        assert_eq!(offset_of!(AnimCommand, layer), 12);
        assert_eq!(offset_of!(AnimCommand, clip), 16);
        assert_eq!(offset_of!(AnimCommand, weight), 20);
        assert_eq!(offset_of!(AnimCommand, fade), 24);
        assert_eq!(offset_of!(AnimCommand, speed), 28);
        assert_eq!(offset_of!(AnimCommand, mask_count), 32);
        assert_eq!(offset_of!(AnimCommand, origin), 36);
        assert_eq!(size_of::<RenderAnimate>(), RenderAnimate::BYTES);
        assert_eq!(offset_of!(RenderAnimate, asset), 8);
    }

    #[test]
    fn le_pas_d_une_cle_suit_le_canal_et_l_interpolation() {
        let piste = |channel, interp| TrackDesc {
            target_node: 0,
            channel,
            interp,
            _pad: [0; 2],
            key_offset: 0,
            key_count: 0,
        };
        assert_eq!(
            piste(channel::TRANSLATION, interp::LINEAR).key_stride(),
            Some(4)
        );
        assert_eq!(piste(channel::ROTATION, interp::STEP).key_stride(), Some(5));
        assert_eq!(
            piste(channel::ROTATION, interp::CUBIC_SPLINE).key_stride(),
            Some(13)
        );
        assert_eq!(
            piste(channel::SCALE, interp::CUBIC_SPLINE).key_stride(),
            Some(10)
        );
        assert_eq!(piste(3, interp::LINEAR).key_stride(), None);
        assert_eq!(piste(channel::SCALE, 3).key_stride(), None);
    }
}
