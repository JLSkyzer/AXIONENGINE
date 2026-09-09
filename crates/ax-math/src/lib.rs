//! C-11 — types et conventions mathématiques d'AXION ENGINE.
//!
//! Ce crate ne définit aucun type vecteur ou matrice propre : R-460 l'interdit
//! à tout crate d'AXION. Il réexporte ceux de [`glam`] et n'ajoute que ce que
//! `glam` ne fournit pas — les conventions du moteur et l'origine flottante.
//!
//! # Conventions (R-461)
//!
//! - repère **main droite**, **Y vers le haut** ;
//! - matrices **colonne-major**, comme dans `glam` et OpenGL ;
//! - quaternions ordonnés `(x, y, z, w)`, comme dans `glam` et glTF.
//!
//! # Précision (R-462)
//!
//! Les positions monde sont en [`DVec3`] : à des dizaines de millions de blocs
//! de l'origine, un `f32` n'a plus la résolution nécessaire. La simulation
//! d'une assembly travaille en `f32` **relativement à une origine flottante**,
//! rebasée au-delà de [`FloatingOrigin::REBASE_DISTANCE`]. Voir
//! [`FloatingOrigin`].
//!
//! Exigences : R-460, R-461, R-462. Tests : T-160..T-162.

pub use glam::{
    Affine3A, DAffine3, DMat3, DMat4, DQuat, DVec2, DVec3, DVec4, EulerRot, Mat3, Mat3A, Mat4,
    Quat, Vec2, Vec3, Vec3A, Vec4,
};

/// Direction du haut dans le repère monde (R-461).
pub const WORLD_UP: Vec3 = Vec3::Y;

/// Origine locale d'une simulation, exprimée en coordonnées monde.
///
/// La physique et la déformation d'une assembly travaillent en `f32` autour de
/// cette origine. Tant que les coordonnées locales restent modestes, un `f32`
/// offre une résolution largement suffisante ; c'est la distance à l'origine du
/// monde, et non la taille de l'objet, qui dégrade la précision.
///
/// Au-delà de [`Self::REBASE_DISTANCE`], l'origine est déplacée
/// ([`Self::rebase_to`]) pour ramener les coordonnées locales près de zéro.
///
/// # Exemple
///
/// ```
/// use ax_math::{DVec3, FloatingOrigin};
///
/// let mut origin = FloatingOrigin::new(DVec3::new(30_000_000.0, 64.0, 0.0));
/// let world = DVec3::new(30_000_002.5, 65.0, 0.0);
///
/// // Près de l'origine locale, le `f32` garde toute sa résolution.
/// let local = origin.to_local(world);
/// assert!((origin.to_world(local) - world).length() < 1e-3);
///
/// // Un objet qui s'éloigne finit par demander un rebasage.
/// assert!(origin.needs_rebase(ax_math::Vec3::new(2000.0, 0.0, 0.0)));
/// origin.rebase_to(world);
/// assert_eq!(origin.to_local(world), ax_math::Vec3::ZERO);
/// ```
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct FloatingOrigin {
    origin: DVec3,
}

impl FloatingOrigin {
    /// Distance locale, en blocs, au-delà de laquelle l'origine doit être
    /// rebasée (R-462).
    pub const REBASE_DISTANCE: f32 = 1024.0;

    /// Crée une origine flottante placée en `origin`.
    #[must_use]
    pub const fn new(origin: DVec3) -> Self {
        Self { origin }
    }

    /// Position de l'origine en coordonnées monde.
    #[must_use]
    pub const fn origin(&self) -> DVec3 {
        self.origin
    }

    /// Convertit une position monde en position locale.
    #[must_use]
    pub fn to_local(&self, world: DVec3) -> Vec3 {
        (world - self.origin).as_vec3()
    }

    /// Convertit une position locale en position monde.
    #[must_use]
    pub fn to_world(&self, local: Vec3) -> DVec3 {
        self.origin + local.as_dvec3()
    }

    /// Indique si `local` est assez loin de l'origine pour justifier un
    /// rebasage.
    ///
    /// La comparaison porte sur le carré des distances : elle évite une racine
    /// carrée sans rien changer au résultat.
    #[must_use]
    pub fn needs_rebase(&self, local: Vec3) -> bool {
        local.length_squared() > Self::REBASE_DISTANCE * Self::REBASE_DISTANCE
    }

    /// Déplace l'origine sur `world`.
    ///
    /// Les coordonnées locales calculées avant l'appel deviennent caduques :
    /// l'appelant les recalcule depuis les positions monde.
    pub fn rebase_to(&mut self, world: DVec3) {
        self.origin = world;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// T-160 — le repère est direct, orienté Y vers le haut, et les
    /// quaternions sont ordonnés `(x, y, z, w)` (R-461).
    #[test]
    fn conventions_du_repere() {
        // Base directe : X ^ Y == Z.
        assert_eq!(Vec3::X.cross(Vec3::Y), Vec3::Z);
        assert_eq!(Vec3::Y.cross(Vec3::Z), Vec3::X);
        assert_eq!(Vec3::Z.cross(Vec3::X), Vec3::Y);

        assert_eq!(WORLD_UP, Vec3::Y);

        // Ordre des composantes d'un quaternion.
        let q = Quat::from_xyzw(0.1, 0.2, 0.3, 0.4);
        assert_eq!([q.x, q.y, q.z, q.w], [0.1, 0.2, 0.3, 0.4]);

        // Colonne-major : la quatrième colonne d'une matrice affine porte la
        // translation.
        let m = Mat4::from_translation(Vec3::new(1.0, 2.0, 3.0));
        assert_eq!(m.col(3).truncate(), Vec3::new(1.0, 2.0, 3.0));
    }

    /// T-161 — l'aller-retour monde/local préserve la position, y compris très
    /// loin de l'origine du monde, ce qui est la raison d'être de R-462.
    #[test]
    fn aller_retour_loin_de_l_origine() {
        // Au-delà de la limite d'un monde Minecraft vanilla, là où un `f32`
        // absolu aurait une résolution de plusieurs blocs.
        let origin = FloatingOrigin::new(DVec3::new(29_999_984.0, 64.0, -29_999_984.0));

        for offset in [
            Vec3::ZERO,
            Vec3::new(0.5, -0.25, 0.125),
            Vec3::new(512.0, 100.0, -512.0),
        ] {
            let world = origin.to_world(offset);
            let round_trip = origin.to_local(world);
            assert!(
                (round_trip - offset).length() < 1e-3,
                "aller-retour dégradé pour {offset:?} : {round_trip:?}"
            );
        }
    }

    /// T-162 — le rebasage ramène les coordonnées locales à zéro sans déplacer
    /// l'objet dans le monde.
    #[test]
    fn rebasage_conserve_la_position_monde() {
        let mut origin = FloatingOrigin::new(DVec3::ZERO);

        let far = Vec3::new(2000.0, 0.0, 0.0);
        assert!(origin.needs_rebase(far), "2000 blocs dépasse le seuil");
        assert!(!origin.needs_rebase(Vec3::new(1000.0, 0.0, 0.0)));

        let world_before = origin.to_world(far);
        origin.rebase_to(world_before);

        assert_eq!(origin.to_local(world_before), Vec3::ZERO);
        assert_eq!(origin.to_world(Vec3::ZERO), world_before);
        assert!(!origin.needs_rebase(origin.to_local(world_before)));
    }

    /// Le seuil de rebasage est bien celui qu'exige R-462.
    #[test]
    fn seuil_de_rebasage_conforme() {
        assert_eq!(FloatingOrigin::REBASE_DISTANCE, 1024.0);
    }
}
