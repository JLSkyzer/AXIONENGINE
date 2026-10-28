//! Les candidats d'une frame (ADR-154 D3) et l'emprise locale de leur asset.

use crate::camera::{f32_at, u32_at};
use ax_math::{Quat, Vec3};
use core::fmt;

/// Une assembly que Java propose de dessiner, sa pose relative à la caméra.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Candidate {
    /// Identifiant stable tant que l'assembly est rendue ; il porte l'état de LOD.
    pub id: u32,
    /// Index du handle de l'asset.
    pub asset: u32,
    /// Génération du handle de l'asset : un index réemployé par un autre asset ne la porte pas.
    pub generation: u32,
    /// Drapeaux, voir [`Candidate::NO_CULL`].
    pub flags: u32,
    /// Position relative à la caméra, en blocs.
    pub position: Vec3,
    /// Échelle uniforme.
    pub scale: f32,
    /// Orientation.
    pub rotation: Quat,
}

/// Pourquoi un candidat est refusé.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CandidateError {
    /// La liste n'est pas un multiple de [`Candidate::BYTES`] octets.
    Length(usize),
    /// Le candidat à cet index porte une valeur non finie, une échelle non positive ou une
    /// rotation nulle.
    Invalid(usize),
}

impl fmt::Display for CandidateError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            CandidateError::Length(n) => {
                write!(
                    f,
                    "liste de candidats de {n} octets, pas un multiple de {}",
                    Candidate::BYTES
                )
            }
            CandidateError::Invalid(i) => {
                write!(
                    f,
                    "candidat {i} invalide : valeur non finie, échelle ou rotation"
                )
            }
        }
    }
}

impl std::error::Error for CandidateError {}

impl Candidate {
    /// Drapeau : toujours retenu, sans test. Pour une assembly dont l'emprise au repos ne borne
    /// pas ce qu'elle dessine — des corps articulés écartés de leur pose de repos.
    pub const NO_CULL: u32 = 1 << 0;

    /// Taille d'un candidat, en octets : `id`, index et génération du handle de l'asset, `flags`
    /// (`u32`), position `f32[3]`, échelle `f32`, rotation `f32[4]`.
    pub const BYTES: usize = 48;

    /// Lit une liste de candidats little-endian.
    ///
    /// # Errors
    ///
    /// [`CandidateError`] si la longueur ou un candidat est invalide ; rien n'est alors rendu.
    pub fn read_all(bytes: &[u8]) -> Result<Vec<Candidate>, CandidateError> {
        if !bytes.len().is_multiple_of(Self::BYTES) {
            return Err(CandidateError::Length(bytes.len()));
        }
        let mut out = Vec::with_capacity(bytes.len() / Self::BYTES);
        for (i, raw) in bytes.chunks_exact(Self::BYTES).enumerate() {
            let position = Vec3::new(f32_at(raw, 16), f32_at(raw, 20), f32_at(raw, 24));
            let scale = f32_at(raw, 28);
            let q = [
                f32_at(raw, 32),
                f32_at(raw, 36),
                f32_at(raw, 40),
                f32_at(raw, 44),
            ];
            let norm = q.iter().map(|v| v * v).sum::<f32>();
            if !position.is_finite()
                || !scale.is_finite()
                || scale <= 0.0
                || !norm.is_finite()
                || norm < 1e-6
            {
                return Err(CandidateError::Invalid(i));
            }
            let mut rotation = Quat::from_xyzw(q[0], q[1], q[2], q[3]);
            if (norm - 1.0).abs() > 1e-6 {
                rotation = rotation.normalize();
            }
            out.push(Candidate {
                id: u32_at(raw, 0),
                asset: u32_at(raw, 4),
                generation: u32_at(raw, 8),
                flags: u32_at(raw, 12),
                position,
                scale,
                rotation,
            });
        }
        Ok(out)
    }

    /// Le candidat encodé.
    #[must_use]
    pub fn to_bytes(&self) -> [u8; Self::BYTES] {
        let mut out = [0u8; Self::BYTES];
        out[0..4].copy_from_slice(&self.id.to_le_bytes());
        out[4..8].copy_from_slice(&self.asset.to_le_bytes());
        out[8..12].copy_from_slice(&self.generation.to_le_bytes());
        out[12..16].copy_from_slice(&self.flags.to_le_bytes());
        for (i, v) in self.position.to_array().iter().enumerate() {
            out[16 + i * 4..20 + i * 4].copy_from_slice(&v.to_le_bytes());
        }
        out[28..32].copy_from_slice(&self.scale.to_le_bytes());
        for (i, v) in self.rotation.to_array().iter().enumerate() {
            out[32 + i * 4..36 + i * 4].copy_from_slice(&v.to_le_bytes());
        }
        out
    }
}

/// La sphère englobante d'un asset, dans son repère local.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct LocalBounds {
    /// Centre, dans le repère de l'asset.
    pub center: Vec3,
    /// Rayon, en blocs.
    pub radius: f32,
}

impl LocalBounds {
    /// La sphère qui contient une boîte alignée sur les axes de l'asset.
    #[must_use]
    pub fn from_aabb(min: Vec3, max: Vec3) -> LocalBounds {
        LocalBounds {
            center: (min + max) * 0.5,
            radius: ((max - min) * 0.5).length(),
        }
    }
}
