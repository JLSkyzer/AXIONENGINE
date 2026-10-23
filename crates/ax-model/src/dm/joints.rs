//! Les joints d'une assembly (C-34, ADR-151 D3, D7).
//!
//! Un [`JointDesc`] par joint suit les corps dans le payload de `CREATE_ASSEMBLY` : Java le
//! transcrit de la definition (schéma 2), le natif en fait un joint de rapier. Les points des
//! courbes de grippage suivent les joints, en annexe, `[f32; 2]` par point.
//!
//! Unités de R-102 : blocs = mètres, radians, newtons, newtons-mètres, joules. `anchor` et `axis`
//! sont dans le repère du node `b_node`, à la pose de repos de l'asset.

/// Types de joint de la fiche 5.26, champ `kind` de [`JointDesc`].
pub mod joint_kind {
    /// Aucun degré de liberté.
    pub const FIXED: u8 = 0;
    /// Une rotation autour de `axis`.
    pub const REVOLUTE: u8 = 1;
    /// Une translation le long de `axis`.
    pub const PRISMATIC: u8 = 2;
    /// Trois rotations autour de l'ancrage.
    pub const SPHERICAL: u8 = 3;
    /// Six axes réglés un à un (`axes`, `axis_limits`).
    pub const GENERIC: u8 = 4;
    /// Une distance maximale entre les ancrages (`max_length`).
    pub const ROPE: u8 = 5;
    /// Un ressort amorti vers une longueur de repos (`rest_length`, `stiffness`, `damping`).
    pub const SPRING: u8 = 6;
}

/// Drapeaux de [`JointDesc`], champ `flags` : ce que la definition déclare.
pub mod joint_flags {
    /// `limits` porte la plage de l'axe libre (`revolute`, `prismatic`).
    pub const LIMITS: u8 = 1 << 0;
    /// Un moteur en vitesse : `motor_speed`, plafonné par `motor_max_force`.
    pub const MOTOR_VELOCITY: u8 = 1 << 1;
    /// Un servo en position : `motor_target`, `motor_stiffness`, `motor_damping`, plafonné par
    /// `motor_max_force`.
    pub const MOTOR_POSITION: u8 = 1 << 2;
    /// Une friction interne, `friction`.
    pub const FRICTION: u8 = 1 << 3;
    /// Un grippage : sa courbe (`jam_first`, `jam_count`) et `jam_friction`.
    pub const JAM: u8 = 1 << 4;
    /// Les deux corps du joint se touchent (`collide`).
    pub const COLLIDE: u8 = 1 << 5;
    /// Tous les drapeaux connus.
    pub const ALL: u8 = LIMITS | MOTOR_VELOCITY | MOTOR_POSITION | FRICTION | JAM | COLLIDE;
}

/// Modes d'un axe de `generic`, champ `axes` de [`JointDesc`].
pub mod joint_axis_mode {
    /// L'axe est bloqué.
    pub const LOCKED: u8 = 0;
    /// L'axe est libre.
    pub const FREE: u8 = 1;
    /// L'axe est libre dans la plage de `axis_limits`.
    pub const LIMITED: u8 = 2;
}

/// Un joint d'une assembly (C-34, ADR-151 D7).
///
/// **Disposition figée** : 160 octets, alignement 4. Le test de disposition la verrouille. Un
/// réglage que la definition ne déclare pas n'agit pas : son drapeau est absent, ou, pour les
/// seuils de rupture, il vaut `+inf`.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct JointDesc {
    /// Type, voir [`joint_kind`].
    pub kind: u8,
    /// Drapeaux, voir [`joint_flags`].
    pub flags: u8,
    /// Rang du corps `a` parmi les corps de `CREATE_ASSEMBLY`.
    pub body_a: u16,
    /// Rang du corps `b`, celui dont `b_node` est la racine.
    pub body_b: u16,
    /// Modes des six axes de `generic` (x, y, z linéaires, puis x, y, z angulaires), voir
    /// [`joint_axis_mode`] ; nuls pour un autre type.
    pub axes: [u8; 6],
    /// Node `b_node` : son repère porte `anchor` et `axis`.
    pub b_node: u32,
    /// Ancrage, dans le repère de `b_node`, en blocs.
    pub anchor: [f32; 3],
    /// Axe, dans le repère de `b_node`, normalisé (`revolute`, `prismatic`).
    pub axis: [f32; 3],
    /// Plage de l'axe libre, en radians ou en blocs, si [`joint_flags::LIMITS`].
    pub limits: [f32; 2],
    /// Vitesse cible du moteur, en rad/s ou en blocs/s.
    pub motor_speed: f32,
    /// Position cible du servo, en radians ou en blocs.
    pub motor_target: f32,
    /// Raideur du servo.
    pub motor_stiffness: f32,
    /// Amortissement du servo.
    pub motor_damping: f32,
    /// Force ou couple maximal du moteur ; `+inf` s'il n'est pas déclaré.
    pub motor_max_force: f32,
    /// Friction interne, en newtons ou en newtons-mètres, si [`joint_flags::FRICTION`].
    pub friction: f32,
    /// Force de rupture, en newtons ; `+inf` : aucune.
    pub break_force: f32,
    /// Couple de rupture, en newtons-mètres ; `+inf` : aucun.
    pub break_torque: f32,
    /// Énergie de rupture, en joules, absorbée sur un tick (D5) ; `+inf` : aucune.
    pub break_energy: f32,
    /// Friction interne atteinte quand la plage restante du grippage tombe à 0 (D6).
    pub jam_friction: f32,
    /// Premier point de la courbe de grippage dans l'annexe, si [`joint_flags::JAM`].
    pub jam_first: u32,
    /// Nombre de points de la courbe de grippage.
    pub jam_count: u32,
    /// Plages des six axes de `generic`, pour ceux en [`joint_axis_mode::LIMITED`].
    pub axis_limits: [[f32; 2]; 6],
    /// Distance maximale entre les ancrages (`rope`), en blocs.
    pub max_length: f32,
    /// Longueur de repos (`spring`), en blocs.
    pub rest_length: f32,
    /// Raideur du ressort (`spring`), en N/bloc.
    pub stiffness: f32,
    /// Amortissement du ressort (`spring`), en N·s/bloc.
    pub damping: f32,
}

impl JointDesc {
    /// Taille sur la frontière, en octets.
    pub const BYTES: usize = 160;

    /// Taille d'un point de courbe de grippage dans l'annexe, en octets.
    pub const JAM_POINT_BYTES: usize = 8;

    /// Écrit le joint en little-endian, dans sa disposition figée, à la fin de `out`.
    pub fn write_le(&self, out: &mut Vec<u8>) {
        out.push(self.kind);
        out.push(self.flags);
        out.extend_from_slice(&self.body_a.to_le_bytes());
        out.extend_from_slice(&self.body_b.to_le_bytes());
        out.extend_from_slice(&self.axes);
        out.extend_from_slice(&self.b_node.to_le_bytes());
        let settings = [
            self.motor_speed,
            self.motor_target,
            self.motor_stiffness,
            self.motor_damping,
            self.motor_max_force,
            self.friction,
            self.break_force,
            self.break_torque,
            self.break_energy,
            self.jam_friction,
        ];
        let floats = self
            .anchor
            .iter()
            .chain(&self.axis)
            .chain(&self.limits)
            .chain(&settings);
        for value in floats {
            out.extend_from_slice(&value.to_le_bytes());
        }
        out.extend_from_slice(&self.jam_first.to_le_bytes());
        out.extend_from_slice(&self.jam_count.to_le_bytes());
        let lengths = [
            self.max_length,
            self.rest_length,
            self.stiffness,
            self.damping,
        ];
        for value in self.axis_limits.iter().flatten().chain(&lengths) {
            out.extend_from_slice(&value.to_le_bytes());
        }
    }

    /// Lit un joint au début de `bytes` ; `None` s'il est tronqué.
    #[must_use]
    pub fn read_le(bytes: &[u8]) -> Option<Self> {
        let bytes = bytes.get(..Self::BYTES)?;
        let u16_at = |at: usize| u16::from_le_bytes([bytes[at], bytes[at + 1]]);
        let u32_at = |at: usize| u32::from_le_bytes(bytes[at..at + 4].try_into().expect("borne"));
        let f32_at = |at: usize| f32::from_le_bytes(bytes[at..at + 4].try_into().expect("borne"));
        let mut axis_limits = [[0.0; 2]; 6];
        for (rank, limits) in axis_limits.iter_mut().enumerate() {
            *limits = [f32_at(96 + rank * 8), f32_at(100 + rank * 8)];
        }
        Some(Self {
            kind: bytes[0],
            flags: bytes[1],
            body_a: u16_at(2),
            body_b: u16_at(4),
            axes: bytes[6..12].try_into().expect("borne"),
            b_node: u32_at(12),
            anchor: [f32_at(16), f32_at(20), f32_at(24)],
            axis: [f32_at(28), f32_at(32), f32_at(36)],
            limits: [f32_at(40), f32_at(44)],
            motor_speed: f32_at(48),
            motor_target: f32_at(52),
            motor_stiffness: f32_at(56),
            motor_damping: f32_at(60),
            motor_max_force: f32_at(64),
            friction: f32_at(68),
            break_force: f32_at(72),
            break_torque: f32_at(76),
            break_energy: f32_at(80),
            jam_friction: f32_at(84),
            jam_first: u32_at(88),
            jam_count: u32_at(92),
            axis_limits,
            max_length: f32_at(144),
            rest_length: f32_at(148),
            stiffness: f32_at(152),
            damping: f32_at(156),
        })
    }

    /// Vérifie ce qu'un joint venu de Java doit tenir ; rend le nom du premier champ fautif.
    ///
    /// # Errors
    ///
    /// Le nom du champ : type ou drapeau inconnu, mode d'axe inconnu, valeur non finie, limites
    /// croisées, axe nul, deux moteurs, longueur ou réglage négatif, seuil nul ou négatif.
    pub fn validate(&self) -> Result<(), &'static str> {
        if self.kind > joint_kind::SPRING {
            return Err("kind");
        }
        if self.flags & !joint_flags::ALL != 0 {
            return Err("flags");
        }
        if self
            .axes
            .iter()
            .any(|mode| *mode > joint_axis_mode::LIMITED)
        {
            return Err("axes");
        }
        let finite = |values: &[f32]| values.iter().all(|value| value.is_finite());
        if !finite(&self.anchor) {
            return Err("anchor");
        }
        if !finite(&self.axis) {
            return Err("axis");
        }
        if matches!(self.kind, joint_kind::REVOLUTE | joint_kind::PRISMATIC)
            && self.axis.iter().map(|c| c * c).sum::<f32>() <= f32::EPSILON
        {
            return Err("axis");
        }
        if self.flags & joint_flags::LIMITS != 0
            && !(finite(&self.limits) && self.limits[0] <= self.limits[1])
        {
            return Err("limits");
        }
        let non_negative = |value: f32| value.is_finite() && value >= 0.0;
        if !(self.motor_speed.is_finite()
            && self.motor_target.is_finite()
            && non_negative(self.motor_stiffness)
            && non_negative(self.motor_damping)
            && !self.motor_max_force.is_nan()
            && self.motor_max_force >= 0.0)
        {
            return Err("motor");
        }
        // Un moteur est en vitesse ou en position, pas les deux (D3).
        let motors = joint_flags::MOTOR_VELOCITY | joint_flags::MOTOR_POSITION;
        if self.flags & motors == motors {
            return Err("motor");
        }
        if !non_negative(self.friction) || !non_negative(self.jam_friction) {
            return Err("friction");
        }
        let threshold = |value: f32| !value.is_nan() && value > 0.0;
        if !(threshold(self.break_force)
            && threshold(self.break_torque)
            && threshold(self.break_energy))
        {
            return Err("break");
        }
        if self
            .axis_limits
            .iter()
            .zip(&self.axes)
            .any(|(limits, mode)| {
                *mode == joint_axis_mode::LIMITED && !(finite(limits) && limits[0] <= limits[1])
            })
        {
            return Err("axis_limits");
        }
        if !(non_negative(self.max_length)
            && non_negative(self.rest_length)
            && non_negative(self.stiffness)
            && non_negative(self.damping))
        {
            return Err("length");
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use core::mem::{align_of, offset_of, size_of};

    /// Une charnière de porte : limites, servo, friction, seuils et grippage.
    fn charniere() -> JointDesc {
        JointDesc {
            kind: joint_kind::REVOLUTE,
            flags: joint_flags::LIMITS
                | joint_flags::MOTOR_POSITION
                | joint_flags::FRICTION
                | joint_flags::JAM,
            body_a: 0,
            body_b: 1,
            axes: [0; 6],
            b_node: 4,
            anchor: [0.0, 0.0, 0.5],
            axis: [0.0, 1.0, 0.0],
            limits: [0.0, 1.6],
            motor_speed: 0.0,
            motor_target: 1.2,
            motor_stiffness: 50.0,
            motor_damping: 5.0,
            motor_max_force: 200.0,
            friction: 4.0,
            break_force: 8000.0,
            break_torque: f32::INFINITY,
            break_energy: 400.0,
            jam_friction: 60.0,
            jam_first: 0,
            jam_count: 3,
            axis_limits: [[0.0; 2]; 6],
            max_length: 0.0,
            rest_length: 0.0,
            stiffness: 0.0,
            damping: 0.0,
        }
    }

    #[test]
    fn joint_desc_disposition_figee() {
        assert_eq!(size_of::<JointDesc>(), JointDesc::BYTES);
        assert_eq!(align_of::<JointDesc>(), 4);
        assert_eq!(offset_of!(JointDesc, body_a), 2);
        assert_eq!(offset_of!(JointDesc, axes), 6);
        assert_eq!(offset_of!(JointDesc, b_node), 12);
        assert_eq!(offset_of!(JointDesc, anchor), 16);
        assert_eq!(offset_of!(JointDesc, axis), 28);
        assert_eq!(offset_of!(JointDesc, limits), 40);
        assert_eq!(offset_of!(JointDesc, motor_speed), 48);
        assert_eq!(offset_of!(JointDesc, friction), 68);
        assert_eq!(offset_of!(JointDesc, break_force), 72);
        assert_eq!(offset_of!(JointDesc, jam_friction), 84);
        assert_eq!(offset_of!(JointDesc, jam_first), 88);
        assert_eq!(offset_of!(JointDesc, axis_limits), 96);
        assert_eq!(offset_of!(JointDesc, max_length), 144);
        assert_eq!(offset_of!(JointDesc, damping), 156);
    }

    #[test]
    fn joint_desc_aller_retour() {
        let mut joint = charniere();
        joint.kind = joint_kind::GENERIC;
        joint.axes = [0, 0, 1, 2, 0, 0];
        joint.axis_limits[3] = [-0.1, 0.1];
        let mut bytes = Vec::new();
        joint.write_le(&mut bytes);
        assert_eq!(bytes.len(), JointDesc::BYTES);
        assert_eq!(JointDesc::read_le(&bytes), Some(joint));
        assert_eq!(JointDesc::read_le(&bytes[..JointDesc::BYTES - 1]), None);
    }

    #[test]
    fn joint_desc_valide_ses_champs() {
        assert_eq!(charniere().validate(), Ok(()));
        let faute = |change: fn(&mut JointDesc)| {
            let mut joint = charniere();
            change(&mut joint);
            joint.validate().unwrap_err()
        };
        assert_eq!(faute(|j| j.kind = 7), "kind");
        assert_eq!(faute(|j| j.flags = 1 << 7), "flags");
        assert_eq!(faute(|j| j.axes[2] = 3), "axes");
        assert_eq!(faute(|j| j.anchor[0] = f32::NAN), "anchor");
        assert_eq!(faute(|j| j.axis = [0.0; 3]), "axis");
        assert_eq!(faute(|j| j.limits = [1.0, 0.0]), "limits");
        assert_eq!(faute(|j| j.motor_stiffness = -1.0), "motor");
        assert_eq!(faute(|j| j.flags |= joint_flags::MOTOR_VELOCITY), "motor");
        assert_eq!(faute(|j| j.friction = -1.0), "friction");
        assert_eq!(faute(|j| j.break_force = 0.0), "break");
        assert_eq!(
            faute(|j| {
                j.axes[3] = joint_axis_mode::LIMITED;
                j.axis_limits[3] = [0.2, -0.2];
            }),
            "axis_limits"
        );
        assert_eq!(faute(|j| j.max_length = -1.0), "length");
        // Un axe nul ne compte que pour un type à axe.
        let mut rotule = charniere();
        rotule.kind = joint_kind::SPHERICAL;
        rotule.axis = [0.0; 3];
        assert_eq!(rotule.validate(), Ok(()));
    }
}
