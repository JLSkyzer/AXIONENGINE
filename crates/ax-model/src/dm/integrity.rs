//! DM-12 et DM-14 — régions de déformation et graphe structurel.

/// Drapeaux d'une région de déformation (DM-12).
pub mod region_flags {
    /// La région est une coque : la déformation suit la surface.
    pub const SHELL: u8 = 1 << 0;
    /// La région est volumique.
    pub const VOLUME: u8 = 1 << 1;
    /// Les nœuds de bord sont ancrés, donc non déformables.
    pub const ANCHORED_BORDER: u8 = 1 << 2;
    /// La région peut se déchirer.
    pub const ALLOW_TEAR: u8 = 1 << 3;
}

/// Résolution minimale d'un axe de lattice (DM-12).
pub const LATTICE_MIN_RES: u8 = 2;

/// Résolution maximale d'un axe de lattice (DM-12).
pub const LATTICE_MAX_RES: u8 = 16;

/// Nombre maximal de nœuds d'un lattice (R-212).
pub const LATTICE_MAX_NODES: u32 = 16 * 16 * 16;

/// Région de déformation (DM-12).
///
/// Un lattice de contrôle attaché à un sous-arbre de nodes. Les liaisons
/// sommet vers lattice **ne sont pas stockées** : R-883 les rend implicites,
/// les coordonnées d'un sommet dans l'OBB de sa région suffisant à les
/// retrouver.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct DeformRegionDesc {
    /// Empreinte du nom.
    pub name_hash: u64,
    /// Node racine du sous-arbre couvert.
    pub root_node: u32,
    /// Part d'appartenance.
    pub part: u16,
    /// Résolution du lattice par axe, chacune dans
    /// `[LATTICE_MIN_RES, LATTICE_MAX_RES]`.
    pub res: [u8; 3],
    /// Drapeaux, voir [`region_flags`].
    pub flags: u8,
    /// Centre de l'OBB, en espace de part.
    pub obb_center: [f32; 3],
    /// Demi-dimensions de l'OBB.
    pub obb_half: [f32; 3],
    /// Rotation de l'OBB, quaternion `(x, y, z, w)`.
    pub obb_rot: [f32; 4],
    /// Épaisseur de tôle apparente, en mètres ; strictement positive.
    pub thickness: f32,
    /// Déplacement maximal admissible par nœud, en mètres.
    pub max_disp: f32,
    /// Position du bitset des nœuds ancrés.
    pub anchor_mask_offset: u32,
    /// Nombre de nœuds, égal au produit des trois résolutions.
    pub node_count: u32,
    /// Première liaison vers les points d'enveloppe de collider.
    pub hull_binding_offset: u32,
    /// Nombre de liaisons.
    pub hull_binding_count: u32,
    /// Matériau physique de la région.
    pub material: u16,
    /// Réservé, à zéro.
    pub _pad: u16,
}

impl DeformRegionDesc {
    /// Indique si la région porte ce drapeau.
    #[must_use]
    pub const fn has(&self, flag: u8) -> bool {
        self.flags & flag != 0
    }

    /// Nombre de nœuds qu'implique la résolution.
    #[must_use]
    pub const fn expected_node_count(&self) -> u32 {
        (self.res[0] as u32) * (self.res[1] as u32) * (self.res[2] as u32)
    }

    /// Plus petite demi-dimension de l'OBB.
    ///
    /// C'est elle qui plafonne `max_disp` : un déplacement supérieur à la
    /// moitié de la plus petite dimension retournerait le volume sur lui-même.
    #[must_use]
    pub fn smallest_half_extent(&self) -> f32 {
        self.obb_half.iter().copied().fold(f32::INFINITY, f32::min)
    }

    /// Indique si l'OBB a un volume.
    #[must_use]
    pub fn has_volume(&self) -> bool {
        self.obb_half
            .iter()
            .all(|half| half.is_finite() && *half > 0.0)
    }
}

/// Nature d'une liaison structurelle (DM-14).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum LinkKind {
    /// Soudure.
    Weld = 0,
    /// Boulonnage.
    Bolt = 1,
    /// Charnière.
    Hinge = 2,
    /// Collage.
    Glue = 3,
    /// Liaison organique.
    Organic = 4,
    /// Encliquetage.
    Snap = 5,
}

impl LinkKind {
    /// Toutes les natures de liaison.
    pub const ALL: [LinkKind; 6] = [
        LinkKind::Weld,
        LinkKind::Bolt,
        LinkKind::Hinge,
        LinkKind::Glue,
        LinkKind::Organic,
        LinkKind::Snap,
    ];

    /// Traduit la forme stockée.
    ///
    /// Rend `None` sur une valeur inconnue : la deviner reviendrait à traiter
    /// une soudure comme une charnière.
    #[must_use]
    pub const fn from_u8(raw: u8) -> Option<Self> {
        match raw {
            0 => Some(LinkKind::Weld),
            1 => Some(LinkKind::Bolt),
            2 => Some(LinkKind::Hinge),
            3 => Some(LinkKind::Glue),
            4 => Some(LinkKind::Organic),
            5 => Some(LinkKind::Snap),
            _ => None,
        }
    }

    /// Nom de la nature, pour les messages d'erreur.
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            LinkKind::Weld => "WELD",
            LinkKind::Bolt => "BOLT",
            LinkKind::Hinge => "HINGE",
            LinkKind::Glue => "GLUE",
            LinkKind::Organic => "ORGANIC",
            LinkKind::Snap => "SNAP",
        }
    }
}

/// Drapeaux d'une liaison structurelle (DM-14).
pub mod link_flags {
    /// La liaison porte une charge.
    pub const LOAD_BEARING: u8 = 1 << 0;
    /// La liaison peut être rompue.
    pub const SEVERABLE: u8 = 1 << 1;
    /// La liaison peut être reformée par réparation.
    pub const REFORMABLE: u8 = 1 << 2;
}

/// Liaison du graphe structurel (DM-14).
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct StructuralLinkDesc {
    /// Empreinte du nom.
    pub name_hash: u64,
    /// Première part liée.
    pub part_a: u16,
    /// Seconde part liée.
    pub part_b: u16,
    /// Nature, voir [`LinkKind`].
    pub kind: u8,
    /// Drapeaux, voir [`link_flags`].
    pub flags: u8,
    /// Réservé, à zéro.
    pub _pad: u16,
    /// Énergie absorbable avant rupture, en joules.
    pub capacity: f32,
    /// Force de traction avant rupture, en newtons.
    pub tensile: f32,
    /// Force de cisaillement avant rupture, en newtons.
    pub shear: f32,
    /// Couple avant rupture, en newton-mètres.
    pub torque: f32,
    /// Point d'application, en espace local.
    pub anchor_local: [f32; 3],
    /// Joint physique associé, `u32::MAX` si aucun.
    pub joint: u32,
    /// Fraction d'énergie transmise à travers la liaison, dans `[0, 1]`.
    pub propagation: f32,
}

impl StructuralLinkDesc {
    /// Nature de la liaison, ou `None` si la valeur stockée est inconnue.
    #[must_use]
    pub const fn kind(&self) -> Option<LinkKind> {
        LinkKind::from_u8(self.kind)
    }

    /// Indique si la liaison porte ce drapeau.
    #[must_use]
    pub const fn has(&self, flag: u8) -> bool {
        self.flags & flag != 0
    }

    /// Indique si toutes les capacités sont finies et strictement positives.
    #[must_use]
    pub fn has_positive_capacities(&self) -> bool {
        [self.capacity, self.tensile, self.shear, self.torque]
            .iter()
            .all(|value| value.is_finite() && *value > 0.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use core::mem::size_of;

    fn region() -> DeformRegionDesc {
        DeformRegionDesc {
            name_hash: 1,
            root_node: 0,
            part: 0,
            res: [4, 4, 4],
            flags: region_flags::SHELL,
            obb_center: [0.0; 3],
            obb_half: [1.0, 0.5, 2.0],
            obb_rot: [0.0, 0.0, 0.0, 1.0],
            thickness: 0.002,
            max_disp: 0.1,
            anchor_mask_offset: 0,
            node_count: 64,
            hull_binding_offset: 0,
            hull_binding_count: 0,
            material: 0,
            _pad: 0,
        }
    }

    #[test]
    fn t230_le_nombre_de_noeuds_decoule_de_la_resolution() {
        let mut region = region();
        assert_eq!(region.expected_node_count(), 64);

        region.res = [16, 16, 16];
        assert_eq!(region.expected_node_count(), LATTICE_MAX_NODES);
    }

    #[test]
    fn t230_la_plus_petite_demi_dimension_borne_le_deplacement() {
        let region = region();
        // Un déplacement supérieur à la moitié de la plus petite dimension
        // retournerait le volume sur lui-même.
        assert!((region.smallest_half_extent() - 0.5).abs() < 1e-6);
        assert!(region.has_volume());
    }

    #[test]
    fn t230_une_obb_degeneree_se_reconnait() {
        let mut region = region();
        region.obb_half = [1.0, 0.0, 1.0];
        assert!(!region.has_volume());

        region.obb_half = [1.0, f32::NAN, 1.0];
        assert!(!region.has_volume());
    }

    #[test]
    fn t230_une_nature_de_liaison_inconnue_n_est_pas_devinee() {
        for (index, kind) in LinkKind::ALL.iter().enumerate() {
            assert_eq!(LinkKind::from_u8(index as u8), Some(*kind));
            assert!(!kind.name().is_empty());
        }
        // La deviner reviendrait à traiter une soudure comme une charnière.
        assert_eq!(LinkKind::from_u8(6), None);
        assert_eq!(LinkKind::from_u8(255), None);
    }

    #[test]
    fn t230_une_capacite_nulle_se_reconnait() {
        let mut link = StructuralLinkDesc {
            name_hash: 1,
            part_a: 0,
            part_b: 1,
            kind: LinkKind::Weld as u8,
            flags: link_flags::LOAD_BEARING,
            _pad: 0,
            capacity: 1000.0,
            tensile: 5000.0,
            shear: 3000.0,
            torque: 200.0,
            anchor_local: [0.0; 3],
            joint: u32::MAX,
            propagation: 0.5,
        };
        assert!(link.has_positive_capacities());
        assert_eq!(link.kind(), Some(LinkKind::Weld));

        link.shear = 0.0;
        assert!(!link.has_positive_capacities());
        link.shear = f32::INFINITY;
        assert!(!link.has_positive_capacities());
    }

    #[test]
    fn les_descripteurs_ne_portent_pas_de_remplissage_implicite() {
        assert_eq!(size_of::<DeformRegionDesc>(), 88);
        assert_eq!(size_of::<StructuralLinkDesc>(), 56);
    }
}
