//! Étape 4 de C-64 : le niveau de détail géométrique (ADR-154 D4).

use crate::camera::Camera;
use std::collections::HashMap;

/// Seuils de taille apparente, en pixels : le LOD `n` est choisi sous le seuil `n` (ADR-154 D4,
/// repris du §14.7). Au-delà du dernier, le niveau le plus grossier.
pub const THRESHOLDS_PX: [f32; 4] = [200.0, 80.0, 30.0, 10.0];

/// Hystérésis de 10 % (étape 4) : on ne passe à un niveau plus grossier que sous 90 % du seuil, à un
/// plus fin qu'au-dessus de 110 %.
pub const HYSTERESIS: f32 = 0.10;

/// Frames sans être vu au bout desquelles un candidat est oublié.
pub const FORGET_AFTER_FRAMES: u64 = 64;

/// Niveaux de détail au plus, selon ADR-107 (`levels` dans 1..8).
pub const MAX_LEVELS: u8 = 8;

/// La taille apparente d'une sphère, en pixels : son diamètre projeté sur la hauteur de la fenêtre,
/// `hauteur × rayon / (distance × tan(fov_y / 2))`, `1 / tan(fov_y / 2)` lu dans la projection.
/// Une sphère qui contient la caméra est tenue pour infiniment grande.
#[must_use]
pub fn projected_px(camera: &Camera, radius: f32, distance: f32) -> f32 {
    if distance <= radius {
        return f32::INFINITY;
    }
    let focal = camera.projection.y_axis.y.abs();
    camera.viewport_height as f32 * radius * focal / distance
}

/// Le niveau sans hystérésis ni biais : le nombre de seuils sous lesquels tombe `px`, borné aux
/// niveaux de l'asset.
#[must_use]
pub fn target_level(px: f32, levels: u8) -> u8 {
    let coarsest = levels.clamp(1, MAX_LEVELS) - 1;
    let below = THRESHOLDS_PX
        .iter()
        .filter(|&&threshold| px < threshold)
        .count() as u8;
    below.min(coarsest)
}

/// Le niveau d'une frame, depuis celui de la précédente (`None` pour un candidat nouveau) : on ne
/// franchit un seuil qu'au-delà de l'hystérésis. Sans biais : le biais s'applique ensuite, par
/// [`biased`].
#[must_use]
pub fn hysteresis_level(previous: Option<u8>, px: f32, levels: u8) -> u8 {
    let coarsest = levels.clamp(1, MAX_LEVELS) - 1;
    let Some(previous) = previous else {
        return target_level(px, levels);
    };
    let mut level = previous.min(coarsest);
    // Vers le plus grossier : le seuil entre `level` et `level + 1` est THRESHOLDS_PX[level].
    while level < coarsest
        && THRESHOLDS_PX
            .get(level as usize)
            .is_some_and(|&threshold| px < threshold * (1.0 - HYSTERESIS))
    {
        level += 1;
    }
    // Vers le plus fin : le seuil entre `level - 1` et `level` est THRESHOLDS_PX[level - 1].
    while level > 0
        && THRESHOLDS_PX
            .get(level as usize - 1)
            .is_some_and(|&threshold| px > threshold * (1.0 + HYSTERESIS))
    {
        level -= 1;
    }
    level
}

/// Le niveau décalé de `render.lod_bias`, borné aux niveaux de l'asset.
#[must_use]
pub fn biased(level: u8, bias: i32, levels: u8) -> u8 {
    let coarsest = i32::from(levels.clamp(1, MAX_LEVELS) - 1);
    (i32::from(level) + bias).clamp(0, coarsest) as u8
}

/// Le niveau de chaque candidat d'une frame à l'autre, par `id`, pour l'hystérésis (R-770).
#[derive(Debug, Default)]
pub struct LodState {
    frame: u64,
    levels: HashMap<u32, (u8, u64)>,
}

impl LodState {
    /// Un état vide.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Ouvre une frame : les candidats absents depuis [`FORGET_AFTER_FRAMES`] frames sont oubliés.
    pub fn begin_frame(&mut self) {
        self.frame += 1;
        let frame = self.frame;
        self.levels
            .retain(|_, &mut (_, seen)| frame - seen <= FORGET_AFTER_FRAMES);
    }

    /// Le niveau du candidat `id` à cette frame, biais compris ; son niveau sans biais est retenu.
    pub fn level(&mut self, id: u32, px: f32, levels: u8, bias: i32) -> u8 {
        let previous = self.levels.get(&id).map(|&(level, _)| level);
        let level = hysteresis_level(previous, px, levels);
        self.levels.insert(id, (level, self.frame));
        biased(level, bias, levels)
    }

    /// Le niveau sans biais retenu pour `id`, s'il est connu.
    #[must_use]
    pub fn remembered(&self, id: u32) -> Option<u8> {
        self.levels.get(&id).map(|&(level, _)| level)
    }

    /// Nombre de candidats retenus.
    #[must_use]
    pub fn len(&self) -> usize {
        self.levels.len()
    }

    /// Vrai si aucun candidat n'est retenu.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.levels.is_empty()
    }
}
