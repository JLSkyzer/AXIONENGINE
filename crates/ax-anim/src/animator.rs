//! Les quatre couches d'une instance (§18.2, R-1440) : `BASE`, `ADDITIVE_1`, `ADDITIVE_2`,
//! `OVERRIDE`, appliquées dans cet ordre sur la pose de repos.
//!
//! # Le blending, en TRS
//!
//! Une couche produit une **cible** par node qu'elle anime, puis l'applique avec son poids `w` :
//!
//! - `BASE` et `OVERRIDE` remplacent : la pose devient `mélange(pose, cible, w)` — translation et
//!   échelle linéaires, rotation par slerp ;
//! - `ADDITIVE_1` et `ADDITIVE_2` ajoutent l'écart de la cible à la **pose de repos** du node :
//!   `w · (t - t_repos)` à la translation, la rotation `r_repos⁻¹ · r` portée à la puissance `w` à
//!   droite de la rotation courante, le rapport `s / s_repos` porté de même à l'échelle.
//!
//! Jamais de matrice : une matrice se mélange mal (cisaillement, perte d'unitarité), et §18.2 le
//! demande en TRS.
//!
//! # Le crossfade
//!
//! Lancer un clip sur une couche occupée, avec un fondu, fait passer la cible linéairement du clip
//! sortant, qui continue de jouer, au clip entrant, sur la durée du fondu. Un côté sans clip — un
//! fondu depuis une couche vide, ou vers l'arrêt — vaut la pose neutre : celle de dessous pour une
//! couche qui remplace, la pose de repos pour une couche additive. La pose ne saute donc ni au
//! début ni à la fin du fondu (T-362). Interrompre un fondu garde comme sortant celui des deux
//! clips qui pesait le plus.
//!
//! # Le temps
//!
//! Les instants sont des secondes `f64` du temps de l'appelant — le tick du serveur, ou le temps
//! interpolé du client, que le descripteur d'états synchronise (ADR-155 D4). Le temps de clip est
//! `origine + (maintenant - ancre) × vitesse`, ramené dans la durée : modulo si le clip boucle,
//! borné sinon, si bien qu'un clip sans boucle tient sa dernière pose.

use crate::clip::ClipLibrary;
use crate::quat::{self, Quat};
use crate::sample::{sample, Cursor};
use ax_model::dm::animation::{anim_action, channel, AnimCommand};
use ax_model::dm::geometry::Transform;
use ax_model::dm::limits::MAX_ACTIVE_TRACKS;

/// Le nombre de couches, figé par R-1440.
pub const LAYERS: usize = 4;

/// Ticks de jeu par seconde : l'horloge des commandes est celle du serveur (ADR-155, tranche 3).
pub const TICKS_PER_SECOND: f64 = 20.0;

/// Les couches additives, par leur rang.
const ADDITIVE: [bool; LAYERS] = [false, true, true, false];

/// Ce qu'une commande d'animation refuse. Une commande refusée ne change rien.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AnimError {
    /// Le clip n'existe pas dans la bibliothèque de l'asset.
    UnknownClip,
    /// La couche n'est pas entre 0 et 3.
    LayerOutOfRange,
    /// Un poids hors de `[0, 1]`, un fondu négatif, une vitesse ou un instant non finis.
    InvalidParameter,
    /// Le clip porterait l'instance au-delà de 256 pistes actives, même fondu coupé (R-200).
    TooManyTracks {
        /// Les pistes qui seraient actives.
        active: usize,
    },
}

/// Un clip qui joue.
#[derive(Debug, Clone, PartialEq)]
struct Playing {
    clip: u32,
    /// Le temps de clip, non ramené dans la durée, à l'instant `anchor`.
    origin: f64,
    /// L'instant où le temps de clip valait `origin`.
    anchor: f64,
    speed: f32,
    /// Un curseur par piste du clip.
    cursors: Vec<Cursor>,
}

impl Playing {
    fn new(library: &ClipLibrary, clip: u32, now: f64, speed: f32) -> Self {
        Self {
            clip,
            origin: 0.0,
            anchor: now,
            speed,
            cursors: vec![Cursor::default(); library.tracks(clip).len()],
        }
    }

    /// Le temps de clip non ramené : celui que la vitesse fait avancer.
    fn unwrapped(&self, now: f64) -> f64 {
        self.origin + (now - self.anchor) * f64::from(self.speed)
    }

    /// Le temps de clip à l'instant `now`, ramené dans la durée.
    fn time(&self, library: &ClipLibrary, now: f64) -> f32 {
        let duration = f64::from(library.clip(self.clip).duration);
        let time = self.unwrapped(now);
        let time = if library.loops(self.clip) && duration > 0.0 {
            time.rem_euclid(duration)
        } else {
            time.clamp(0.0, duration)
        };
        time as f32
    }
}

/// Une couche : le clip entrant, le sortant pendant un fondu, le poids, le masque.
#[derive(Debug, Clone, PartialEq)]
struct Layer {
    incoming: Option<Playing>,
    outgoing: Option<Playing>,
    /// Un fondu est en cours ; ses deux côtés peuvent être vides.
    fading: bool,
    fade_start: f64,
    fade: f32,
    weight: f32,
    /// La vitesse des clips que la couche lance.
    speed: f32,
    /// Les nodes que la couche anime, un bit par node ; `None` : tous.
    mask: Option<Vec<u64>>,
}

impl Default for Layer {
    fn default() -> Self {
        Self {
            incoming: None,
            outgoing: None,
            fading: false,
            fade_start: 0.0,
            fade: 0.0,
            weight: 1.0,
            speed: 1.0,
            mask: None,
        }
    }
}

impl Layer {
    /// L'avancement du fondu, de 0 à 1.
    fn alpha(&self, now: f64) -> f32 {
        if !self.fading || self.fade <= 0.0 {
            return 1.0;
        }
        (((now - self.fade_start) / f64::from(self.fade)) as f32).clamp(0.0, 1.0)
    }

    /// Clôt un fondu achevé.
    fn settle(&mut self, now: f64) {
        if self.fading && self.alpha(now) >= 1.0 {
            self.fading = false;
            self.outgoing = None;
        }
    }

    /// Ce que la couche montre le plus à l'instant `now` : l'entrant, ou le sortant d'un fondu
    /// encore à moins de sa moitié. C'est lui qui sort quand un nouveau fondu commence.
    fn dominant_is_outgoing(&self, now: f64) -> bool {
        self.fading && self.alpha(now) < 0.5
    }

    fn tracks(&self, library: &ClipLibrary) -> usize {
        count(library, self.incoming.as_ref()) + count(library, self.outgoing.as_ref())
    }

    /// Commence un fondu de `fade` secondes à l'instant `now`, depuis ce que la couche montre ; un
    /// fondu nul n'en garde rien. L'entrant est vidé : l'appelant le remplace.
    fn start_fade(&mut self, fade: f32, now: f64) {
        let shown = if self.dominant_is_outgoing(now) {
            self.outgoing.take()
        } else {
            self.incoming.take()
        };
        self.incoming = None;
        if fade <= 0.0 {
            self.fading = false;
            self.outgoing = None;
        } else {
            self.outgoing = shown;
            self.fading = true;
            self.fade_start = now;
            self.fade = fade;
        }
    }

    fn masks(&self, node: usize) -> bool {
        self.mask.as_ref().is_none_or(|bits| {
            bits.get(node / 64)
                .is_some_and(|word| word >> (node % 64) & 1 != 0)
        })
    }
}

/// Une durée de fondu admise : finie et positive ou nulle — un `NaN` ne l'est pas.
fn valid_fade(fade: f32) -> bool {
    fade.is_finite() && fade >= 0.0
}

/// Les pistes d'un clip qui joue, zéro s'il n'y en a pas.
fn count(library: &ClipLibrary, playing: Option<&Playing>) -> usize {
    playing.map_or(0, |playing| library.tracks(playing.clip).len())
}

/// L'animation d'une instance : ses quatre couches et les tampons de l'évaluation.
#[derive(Debug, Clone, PartialEq)]
pub struct Animator {
    layers: [Layer; LAYERS],
    /// La cible du clip sortant, puis de l'entrant, par node.
    outgoing_pose: Vec<Transform>,
    incoming_pose: Vec<Transform>,
    /// Les nodes que chaque côté anime, en bits, et leur liste.
    outgoing_touched: Vec<u64>,
    incoming_touched: Vec<u64>,
    touched: Vec<u32>,
}

impl Animator {
    /// Une instance de `node_count` nodes, ses couches vides. Les tampons sont alloués ici, une
    /// fois : l'évaluation n'alloue plus.
    #[must_use]
    pub fn new(node_count: usize) -> Self {
        let words = node_count.div_ceil(64);
        Self {
            layers: Default::default(),
            outgoing_pose: vec![Transform::identity(); node_count],
            incoming_pose: vec![Transform::identity(); node_count],
            outgoing_touched: vec![0; words],
            incoming_touched: vec![0; words],
            touched: Vec::with_capacity(node_count),
        }
    }

    /// Joue `clip` sur `layer`, de poids `weight`, avec un fondu de `fade` secondes depuis ce que
    /// la couche montrait. Le clip part de son début.
    ///
    /// Si le fondu porterait l'instance au-delà de 256 pistes actives (R-200), il est coupé : le
    /// clip remplace aussitôt le sortant.
    ///
    /// # Errors
    ///
    /// [`AnimError`] si le clip ou la couche n'existent pas, qu'un paramètre est hors de ses
    /// bornes, ou que l'instance dépasserait 256 pistes actives même fondu coupé.
    pub fn play(
        &mut self,
        library: &ClipLibrary,
        clip: u32,
        layer: usize,
        weight: f32,
        fade: f32,
        now: f64,
    ) -> Result<(), AnimError> {
        if layer >= LAYERS {
            return Err(AnimError::LayerOutOfRange);
        }
        if clip as usize >= library.len() {
            return Err(AnimError::UnknownClip);
        }
        if !(0.0..=1.0).contains(&weight) || !valid_fade(fade) || !now.is_finite() {
            return Err(AnimError::InvalidParameter);
        }
        let others: usize = (0..LAYERS)
            .filter(|index| *index != layer)
            .map(|index| self.layers[index].tracks(library))
            .sum();
        let entering = library.tracks(clip).len();
        if others + entering > MAX_ACTIVE_TRACKS {
            return Err(AnimError::TooManyTracks {
                active: others + entering,
            });
        }

        let target = &mut self.layers[layer];
        target.settle(now);
        let leaving = if target.dominant_is_outgoing(now) {
            count(library, target.outgoing.as_ref())
        } else {
            count(library, target.incoming.as_ref())
        };
        let fade = if others + entering + leaving > MAX_ACTIVE_TRACKS {
            0.0
        } else {
            fade
        };
        target.start_fade(fade, now);
        target.incoming = Some(Playing::new(library, clip, now, target.speed));
        target.weight = weight;
        Ok(())
    }

    /// Arrête `layer` par un fondu de `fade` secondes vers la pose neutre.
    ///
    /// # Errors
    ///
    /// [`AnimError::LayerOutOfRange`] ou [`AnimError::InvalidParameter`].
    pub fn stop(&mut self, layer: usize, fade: f32, now: f64) -> Result<(), AnimError> {
        if layer >= LAYERS {
            return Err(AnimError::LayerOutOfRange);
        }
        if !valid_fade(fade) || !now.is_finite() {
            return Err(AnimError::InvalidParameter);
        }
        let target = &mut self.layers[layer];
        target.settle(now);
        target.start_fade(fade, now);
        Ok(())
    }

    /// Règle la vitesse de `layer` : celle du clip qui y joue, sans saut de son temps, et celle
    /// des clips qu'elle lancera.
    ///
    /// # Errors
    ///
    /// [`AnimError::LayerOutOfRange`] ou [`AnimError::InvalidParameter`] sur une vitesse non finie.
    pub fn set_speed(&mut self, layer: usize, speed: f32, now: f64) -> Result<(), AnimError> {
        if layer >= LAYERS {
            return Err(AnimError::LayerOutOfRange);
        }
        if !speed.is_finite() || !now.is_finite() {
            return Err(AnimError::InvalidParameter);
        }
        let target = &mut self.layers[layer];
        target.speed = speed;
        if let Some(playing) = target.incoming.as_mut() {
            playing.origin = playing.unwrapped(now);
            playing.anchor = now;
            playing.speed = speed;
        }
        Ok(())
    }

    /// Restreint `layer` aux nodes donnés ; `None` lui rend tous les nodes (§18.2, masque de
    /// bones). Un node hors de l'instance est ignoré.
    ///
    /// # Errors
    ///
    /// [`AnimError::LayerOutOfRange`].
    pub fn set_mask(&mut self, layer: usize, nodes: Option<&[u32]>) -> Result<(), AnimError> {
        if layer >= LAYERS {
            return Err(AnimError::LayerOutOfRange);
        }
        let words = self.outgoing_touched.len();
        self.layers[layer].mask = nodes.map(|nodes| {
            let mut bits = vec![0u64; words];
            for node in nodes {
                if let Some(word) = bits.get_mut(*node as usize / 64) {
                    *word |= 1 << (node % 64);
                }
            }
            bits
        });
        Ok(())
    }

    /// Applique une commande horodatée ([`AnimCommand`]) : son instant est en ticks, et devient des
    /// secondes. `mask` sont les nodes qui suivent une commande `SET_MASK`. `FORGET` ne concerne pas
    /// l'instance — c'est son détenteur qui l'oublie — et ne fait rien ici.
    ///
    /// # Errors
    ///
    /// Celles de la commande ; [`AnimError::InvalidParameter`] sur une action inconnue ou un instant
    /// non fini.
    pub fn apply(
        &mut self,
        library: &ClipLibrary,
        command: &AnimCommand,
        mask: &[u32],
    ) -> Result<(), AnimError> {
        let now = command.tick / TICKS_PER_SECOND;
        let layer = command.layer as usize;
        match command.action {
            anim_action::PLAY => self.play(
                library,
                command.clip,
                layer,
                command.weight,
                command.fade,
                now,
            ),
            anim_action::STOP => self.stop(layer, command.fade, now),
            anim_action::SET_SPEED => self.set_speed(layer, command.speed, now),
            anim_action::SET_MASK => self.set_mask(layer, (!mask.is_empty()).then_some(mask)),
            anim_action::FORGET => Ok(()),
            _ => Err(AnimError::InvalidParameter),
        }
    }

    /// Le clip qui joue sur `layer`, `None` si elle est vide ou qu'elle s'arrête.
    #[must_use]
    pub fn playing(&self, layer: usize) -> Option<u32> {
        self.layers
            .get(layer)
            .and_then(|layer| layer.incoming.as_ref())
            .map(|playing| playing.clip)
    }

    /// Indique si aucune couche ne joue ni ne s'efface : l'instance est au repos.
    #[must_use]
    pub fn is_idle(&self) -> bool {
        self.layers
            .iter()
            .all(|layer| layer.incoming.is_none() && layer.outgoing.is_none())
    }

    /// Les pistes actives de l'instance, fondus compris (R-200).
    #[must_use]
    pub fn active_tracks(&self, library: &ClipLibrary) -> usize {
        self.layers.iter().map(|layer| layer.tracks(library)).sum()
    }

    /// Évalue la pose locale de chaque node à l'instant `now` : la pose de repos, puis les quatre
    /// couches dans l'ordre. N'alloue pas.
    ///
    /// `rest` et `pose` ont un élément par node de l'instance.
    pub fn evaluate(
        &mut self,
        library: &ClipLibrary,
        rest: &[Transform],
        now: f64,
        pose: &mut [Transform],
    ) {
        pose.copy_from_slice(rest);
        for (index, additive) in ADDITIVE.into_iter().enumerate() {
            self.layers[index].settle(now);
            let layer = &self.layers[index];
            if layer.weight <= 0.0 || (layer.incoming.is_none() && layer.outgoing.is_none()) {
                continue;
            }
            let alpha = layer.alpha(now);
            let weight = layer.weight;

            self.touched.clear();
            self.outgoing_touched.fill(0);
            self.incoming_touched.fill(0);
            let layer = &mut self.layers[index];
            if let Some(playing) = layer.outgoing.as_mut() {
                let side = Side {
                    buffer: &mut self.outgoing_pose,
                    bits: &mut self.outgoing_touched,
                    other: &self.incoming_touched,
                };
                write_clip(
                    library,
                    playing,
                    now,
                    rest,
                    pose,
                    additive,
                    side,
                    &mut self.touched,
                );
            }
            if let Some(playing) = layer.incoming.as_mut() {
                let side = Side {
                    buffer: &mut self.incoming_pose,
                    bits: &mut self.incoming_touched,
                    other: &self.outgoing_touched,
                };
                write_clip(
                    library,
                    playing,
                    now,
                    rest,
                    pose,
                    additive,
                    side,
                    &mut self.touched,
                );
            }

            let layer = &self.layers[index];
            for &node in &self.touched {
                let node = node as usize;
                if !layer.masks(node) {
                    continue;
                }
                let neutral = if additive { rest[node] } else { pose[node] };
                let side = |bits: &[u64], buffer: &[Transform]| {
                    if bits[node / 64] >> (node % 64) & 1 != 0 {
                        buffer[node]
                    } else {
                        neutral
                    }
                };
                let incoming = side(&self.incoming_touched, &self.incoming_pose);
                let target = if layer.fading {
                    let outgoing = side(&self.outgoing_touched, &self.outgoing_pose);
                    blend(&outgoing, &incoming, alpha)
                } else {
                    incoming
                };
                pose[node] = if additive {
                    add(&pose[node], &rest[node], &target, weight)
                } else {
                    blend(&pose[node], &target, weight)
                };
            }
        }
    }
}

/// Le tampon d'un côté d'une couche — sortant ou entrant —, ses nodes en bits, et les bits de
/// l'autre côté, pour qu'un node n'entre qu'une fois dans la liste des nodes touchés.
struct Side<'a> {
    buffer: &'a mut [Transform],
    bits: &'a mut [u64],
    other: &'a [u64],
}

/// Échantillonne un clip dans un côté : chaque node visé part de sa pose neutre, puis reçoit les
/// canaux que ses pistes animent.
#[allow(clippy::too_many_arguments)]
fn write_clip(
    library: &ClipLibrary,
    playing: &mut Playing,
    now: f64,
    rest: &[Transform],
    pose: &[Transform],
    additive: bool,
    side: Side<'_>,
    touched: &mut Vec<u32>,
) {
    let time = playing.time(library, now);
    for (track, cursor) in library
        .tracks(playing.clip)
        .iter()
        .zip(playing.cursors.iter_mut())
    {
        let node = track.target_node as usize;
        let (word, bit) = (node / 64, 1u64 << (node % 64));
        if side.bits[word] & bit == 0 {
            side.bits[word] |= bit;
            side.buffer[node] = if additive { rest[node] } else { pose[node] };
            if side.other[word] & bit == 0 {
                touched.push(node as u32);
            }
        }
        let value = sample(track, library.keys(track), time, cursor);
        let target = &mut side.buffer[node];
        match track.channel {
            channel::TRANSLATION => target.translation.copy_from_slice(&value[..3]),
            channel::ROTATION => target.rotation = value,
            _ => target.scale.copy_from_slice(&value[..3]),
        }
    }
}

/// `a` vers `b` de `w` : translation et échelle linéaires, rotation par slerp.
fn blend(a: &Transform, b: &Transform, w: f32) -> Transform {
    let lerp = |x: [f32; 3], y: [f32; 3]| [0, 1, 2].map(|i| x[i] + (y[i] - x[i]) * w);
    Transform {
        translation: lerp(a.translation, b.translation),
        rotation: quat::slerp(&a.rotation, &b.rotation, w),
        scale: lerp(a.scale, b.scale),
    }
}

/// Ajoute à `pose` l'écart de `target` à la pose de repos, pondéré par `w`.
fn add(pose: &Transform, rest: &Transform, target: &Transform, w: f32) -> Transform {
    let delta: Quat = quat::mul(&quat::conjugate(&rest.rotation), &target.rotation);
    let partial = quat::slerp(&quat::IDENTITY, &delta, w);
    Transform {
        translation: [0, 1, 2]
            .map(|i| pose.translation[i] + (target.translation[i] - rest.translation[i]) * w),
        rotation: quat::normalize(&quat::mul(&pose.rotation, &partial)),
        scale: [0, 1, 2].map(|i| {
            let ratio = if rest.scale[i] == 0.0 {
                1.0
            } else {
                target.scale[i] / rest.scale[i]
            };
            pose.scale[i] * (1.0 + (ratio - 1.0) * w)
        }),
    }
}
