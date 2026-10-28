//! `AxionCameraDesc` (ADR-154 D3) et le frustum qu'elle découpe.

use ax_math::{Mat4, Quat, Vec4};
use core::fmt;

/// Une caméra de frame, lue depuis `AxionCameraDesc`.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Camera {
    /// Projection, colonne par colonne (convention d'OpenGL et de JOML).
    pub projection: Mat4,
    /// Rotation qui porte un vecteur relatif à la caméra dans le repère de la vue.
    pub view_rotation: Quat,
    /// Largeur de la fenêtre, en pixels.
    pub viewport_width: u32,
    /// Hauteur de la fenêtre, en pixels.
    pub viewport_height: u32,
    /// `render.max_distance`, en blocs.
    pub max_distance: f32,
    /// `render.lod_bias`.
    pub lod_bias: i32,
    /// Niveau de qualité courant.
    pub quality: u32,
    /// Nombre de candidats que Java a écrits dans `RENDER_IN` pour cette frame.
    pub candidate_count: u32,
}

/// Pourquoi un `AxionCameraDesc` est refusé.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CameraError {
    /// Le descripteur n'a pas [`Camera::BYTES`] octets.
    Length(usize),
    /// Une composante de la projection ou de la rotation n'est pas finie.
    NotFinite,
    /// La rotation de la vue n'a pas de direction (quaternion nul).
    NullRotation,
    /// La fenêtre a une dimension nulle.
    EmptyViewport,
    /// La distance maximale est négative ou non finie.
    Distance,
}

impl fmt::Display for CameraError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            CameraError::Length(n) => {
                write!(
                    f,
                    "descripteur de caméra de {n} octets, {} attendus",
                    Camera::BYTES
                )
            }
            CameraError::NotFinite => f.write_str("projection ou rotation de caméra non finie"),
            CameraError::NullRotation => f.write_str("rotation de caméra nulle"),
            CameraError::EmptyViewport => f.write_str("fenêtre de dimension nulle"),
            CameraError::Distance => f.write_str("distance maximale négative ou non finie"),
        }
    }
}

impl std::error::Error for CameraError {}

pub(crate) fn f32_at(bytes: &[u8], at: usize) -> f32 {
    f32::from_le_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]])
}

pub(crate) fn u32_at(bytes: &[u8], at: usize) -> u32 {
    u32::from_le_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]])
}

impl Camera {
    /// Taille de `AxionCameraDesc`, en octets : projection `f32[16]`, rotation `f32[4]`, largeur et
    /// hauteur `u32`, distance `f32`, biais `i32`, qualité `u32`, nombre de candidats `u32`, deux
    /// `u32` réservés.
    pub const BYTES: usize = 112;

    /// Lit un `AxionCameraDesc` little-endian. Une rotation unitaire au flottant près est gardée
    /// telle quelle ; une autre est normalisée.
    ///
    /// # Errors
    ///
    /// [`CameraError`] si la longueur, une valeur ou la fenêtre est invalide.
    pub fn from_bytes(bytes: &[u8]) -> Result<Camera, CameraError> {
        if bytes.len() != Self::BYTES {
            return Err(CameraError::Length(bytes.len()));
        }
        let mut columns = [0f32; 16];
        for (i, value) in columns.iter_mut().enumerate() {
            *value = f32_at(bytes, i * 4);
        }
        let q = [
            f32_at(bytes, 64),
            f32_at(bytes, 68),
            f32_at(bytes, 72),
            f32_at(bytes, 76),
        ];
        if !columns.iter().chain(q.iter()).all(|v| v.is_finite()) {
            return Err(CameraError::NotFinite);
        }
        let norm = q.iter().map(|v| v * v).sum::<f32>();
        if norm < 1e-6 {
            return Err(CameraError::NullRotation);
        }
        let mut view_rotation = Quat::from_xyzw(q[0], q[1], q[2], q[3]);
        if (norm - 1.0).abs() > 1e-6 {
            view_rotation = view_rotation.normalize();
        }
        let viewport_width = u32_at(bytes, 80);
        let viewport_height = u32_at(bytes, 84);
        if viewport_width == 0 || viewport_height == 0 {
            return Err(CameraError::EmptyViewport);
        }
        let max_distance = f32_at(bytes, 88);
        if !max_distance.is_finite() || max_distance < 0.0 {
            return Err(CameraError::Distance);
        }
        Ok(Camera {
            projection: Mat4::from_cols_array(&columns),
            view_rotation,
            viewport_width,
            viewport_height,
            max_distance,
            lod_bias: i32::from_le_bytes([bytes[92], bytes[93], bytes[94], bytes[95]]),
            quality: u32_at(bytes, 96),
            candidate_count: u32_at(bytes, 100),
        })
    }

    /// Le descripteur little-endian de cette caméra ; les octets réservés valent 0.
    #[must_use]
    pub fn to_bytes(&self) -> [u8; Self::BYTES] {
        let mut out = [0u8; Self::BYTES];
        for (i, v) in self.projection.to_cols_array().iter().enumerate() {
            out[i * 4..i * 4 + 4].copy_from_slice(&v.to_le_bytes());
        }
        for (i, v) in self.view_rotation.to_array().iter().enumerate() {
            out[64 + i * 4..68 + i * 4].copy_from_slice(&v.to_le_bytes());
        }
        out[80..84].copy_from_slice(&self.viewport_width.to_le_bytes());
        out[84..88].copy_from_slice(&self.viewport_height.to_le_bytes());
        out[88..92].copy_from_slice(&self.max_distance.to_le_bytes());
        out[92..96].copy_from_slice(&self.lod_bias.to_le_bytes());
        out[96..100].copy_from_slice(&self.quality.to_le_bytes());
        out[100..104].copy_from_slice(&self.candidate_count.to_le_bytes());
        out
    }

    /// Les six plans du volume de vue.
    #[must_use]
    pub fn frustum(&self) -> Frustum {
        Frustum::from_clip(self.projection * Mat4::from_quat(self.view_rotation))
    }
}

/// Les six plans d'un volume de vue, normalisés, la normale vers l'intérieur.
///
/// Un point `p` est du côté intérieur d'un plan `(a, b, c, d)` si `a·x + b·y + c·z + d >= 0`.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Frustum {
    /// Gauche, droite, bas, haut, proche, lointain.
    pub planes: [Vec4; 6],
}

impl Frustum {
    /// Extrait les plans d'une matrice de découpage (projection × vue), à la convention d'OpenGL
    /// (profondeur de -1 à 1), par la méthode de Gribb et Hartmann.
    #[must_use]
    pub fn from_clip(clip: Mat4) -> Frustum {
        let rows = clip.transpose();
        let (r0, r1, r2, r3) = (rows.x_axis, rows.y_axis, rows.z_axis, rows.w_axis);
        let mut planes = [r3 + r0, r3 - r0, r3 + r1, r3 - r1, r3 + r2, r3 - r2];
        for plane in &mut planes {
            let length = plane.truncate().length();
            if length > 0.0 {
                *plane /= length;
            }
        }
        Frustum { planes }
    }
}
