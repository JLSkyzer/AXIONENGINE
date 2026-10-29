//! Les clips d'un asset, tels que la section `ANIM` les porte (DM-10, ADR-155 D2).

use ax_model::dm::animation::{animation_flags, AnimationDesc, TrackDesc};
use ax_model::dm::scene::name_hash;

/// Ce qui empêche de construire une [`ClipLibrary`] : une table que la validation (C-22) aurait
/// refusée. L'échantillonnage ne vérifie plus rien ensuite.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ClipError {
    /// Un nom par animation est attendu.
    NamesMismatch,
    /// Les pistes d'une animation sortent du tableau.
    TracksOutOfRange {
        /// L'animation fautive.
        animation: usize,
    },
    /// Une piste a un canal ou une interpolation inconnus, aucune clé, ou des clés hors du tableau.
    InvalidTrack {
        /// La piste fautive.
        track: usize,
    },
    /// Une piste vise un node absent.
    NodeOutOfRange {
        /// La piste fautive.
        track: usize,
    },
}

/// Les clips d'un asset : animations, pistes et clés, vérifiés une fois.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct ClipLibrary {
    animations: Vec<AnimationDesc>,
    names: Vec<String>,
    tracks: Vec<TrackDesc>,
    keys: Vec<f32>,
}

impl ClipLibrary {
    /// Construit la bibliothèque d'un asset de `node_count` nodes.
    ///
    /// # Errors
    ///
    /// [`ClipError`] si une plage déborde ou qu'une piste est inexploitable : la validation de
    /// l'asset (C-22) l'aurait refusé, et l'échantillonnage ne vérifie plus rien.
    pub fn new(
        animations: Vec<AnimationDesc>,
        names: Vec<String>,
        tracks: Vec<TrackDesc>,
        keys: Vec<f32>,
        node_count: usize,
    ) -> Result<Self, ClipError> {
        if names.len() != animations.len() {
            return Err(ClipError::NamesMismatch);
        }
        for (animation, desc) in animations.iter().enumerate() {
            let end = (desc.track_offset as usize).checked_add(desc.track_count as usize);
            if end.is_none_or(|end| end > tracks.len()) {
                return Err(ClipError::TracksOutOfRange { animation });
            }
        }
        for (track, desc) in tracks.iter().enumerate() {
            if desc.target_node as usize >= node_count {
                return Err(ClipError::NodeOutOfRange { track });
            }
            let range = desc.key_stride().and_then(|stride| {
                let start = desc.key_offset as usize;
                start.checked_add((desc.key_count as usize).checked_mul(stride)?)
            });
            if desc.key_count == 0 || range.is_none_or(|end| end > keys.len()) {
                return Err(ClipError::InvalidTrack { track });
            }
        }
        Ok(Self {
            animations,
            names,
            tracks,
            keys,
        })
    }

    /// Le nombre de clips.
    #[must_use]
    pub fn len(&self) -> usize {
        self.animations.len()
    }

    /// Indique si l'asset n'a aucun clip.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.animations.is_empty()
    }

    /// Les noms des clips, dans leur ordre.
    #[must_use]
    pub fn names(&self) -> &[String] {
        &self.names
    }

    /// L'index du clip de ce nom, `None` s'il n'existe pas.
    #[must_use]
    pub fn find(&self, name: &str) -> Option<u32> {
        let hash = name_hash(name);
        self.animations
            .iter()
            .zip(&self.names)
            .position(|(desc, known)| desc.name_hash == hash && known == name)
            .map(|index| index as u32)
    }

    /// Le descripteur d'un clip ; `clip` doit exister.
    #[must_use]
    pub fn clip(&self, clip: u32) -> &AnimationDesc {
        &self.animations[clip as usize]
    }

    /// Indique si le clip boucle (`axion.loop`, ADR-155).
    #[must_use]
    pub fn loops(&self, clip: u32) -> bool {
        self.clip(clip).flags & animation_flags::LOOP != 0
    }

    /// Les pistes d'un clip.
    #[must_use]
    pub fn tracks(&self, clip: u32) -> &[TrackDesc] {
        let desc = self.clip(clip);
        let start = desc.track_offset as usize;
        &self.tracks[start..start + desc.track_count as usize]
    }

    /// Les clés d'une piste, vérifiées à la construction.
    #[must_use]
    pub fn keys(&self, track: &TrackDesc) -> &[f32] {
        // `new` a vérifié la plage de toute piste de la bibliothèque.
        let stride = track.key_stride().unwrap_or(1);
        let start = track.key_offset as usize;
        &self.keys[start..start + track.key_count as usize * stride]
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ax_model::dm::animation::{channel, interp};

    fn piste(node: u32, offset: u32, count: u32) -> TrackDesc {
        TrackDesc {
            target_node: node,
            channel: channel::TRANSLATION,
            interp: interp::LINEAR,
            _pad: [0; 2],
            key_offset: offset,
            key_count: count,
        }
    }

    fn clip(name: &str, offset: u32, count: u32) -> AnimationDesc {
        AnimationDesc {
            name_hash: name_hash(name),
            duration: 1.0,
            track_offset: offset,
            track_count: count,
            flags: animation_flags::LOOP,
        }
    }

    #[test]
    fn une_bibliotheque_se_construit_et_trouve_ses_clips() {
        let bibliotheque = ClipLibrary::new(
            vec![clip("lever", 0, 1), clip("baisser", 1, 1)],
            vec!["lever".into(), "baisser".into()],
            vec![piste(0, 0, 2), piste(1, 8, 1)],
            vec![0.0; 12],
            2,
        )
        .expect("bibliothèque");
        assert_eq!(bibliotheque.find("baisser"), Some(1));
        assert_eq!(bibliotheque.find("sauter"), None);
        assert!(bibliotheque.loops(0));
        assert_eq!(bibliotheque.keys(&bibliotheque.tracks(0)[0]).len(), 8);
    }

    #[test]
    fn une_table_incoherente_est_refusee() {
        let construit = |pistes: Vec<TrackDesc>, cles: usize, nodes: usize| {
            ClipLibrary::new(
                vec![clip("a", 0, 1)],
                vec!["a".into()],
                pistes,
                vec![0.0; cles],
                nodes,
            )
        };
        assert_eq!(
            construit(vec![piste(0, 0, 2)], 7, 1),
            Err(ClipError::InvalidTrack { track: 0 })
        );
        assert_eq!(
            construit(vec![piste(0, 0, 0)], 8, 1),
            Err(ClipError::InvalidTrack { track: 0 })
        );
        assert_eq!(
            construit(vec![piste(3, 0, 2)], 8, 1),
            Err(ClipError::NodeOutOfRange { track: 0 })
        );
        assert_eq!(
            construit(Vec::new(), 8, 1),
            Err(ClipError::TracksOutOfRange { animation: 0 })
        );
    }
}
