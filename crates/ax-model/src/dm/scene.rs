//! DM-03 et DM-11 — hiérarchie de nodes et découpage en parts.

use super::geometry::Transform;

/// Drapeaux d'un node (DM-03).
///
/// Constantes plutôt qu'un type à drapeaux tiers : quatre jeux de drapeaux ne
/// justifient pas une dépendance, et R-2300 demande une justification à
/// chacune.
pub mod node_flags {
    /// Le node est rendu.
    pub const VISIBLE: u32 = 1 << 0;
    /// Le node est un point d'attache nommé.
    pub const SOCKET: u32 = 1 << 1;
    /// Le node porte une roue.
    pub const WHEEL: u32 = 1 << 2;
    /// Le node porte un siège.
    pub const SEAT: u32 = 1 << 3;
    /// Le node porte une source de lumière.
    pub const LIGHT: u32 = 1 << 4;
    /// Le node peut se détacher.
    pub const DETACHABLE: u32 = 1 << 5;
    /// Le node est animé.
    pub const ANIMATED: u32 = 1 << 6;
    /// La transformation du node vient de la physique.
    pub const PHYSICS_DRIVEN: u32 = 1 << 7;
    /// Le node disparaît à la première personne.
    pub const HIDDEN_IN_FIRST_PERSON: u32 = 1 << 8;
    /// Le node échappe au culling.
    pub const NO_CULL: u32 = 1 << 9;
    /// Le node émet de la lumière.
    pub const EMISSIVE: u32 = 1 << 10;
    /// Le node est soumis à la déformation continue.
    pub const DEFORMABLE: u32 = 1 << 11;
    /// Le node est un élément interne, caché tant que rien ne l'expose.
    pub const INTERNAL: u32 = 1 << 12;
    /// Le node apparaît lorsque la pièce qui le couvre est endommagée.
    pub const REVEALED_ON_DAMAGE: u32 = 1 << 13;
    /// Le node ancre un tissu ou un câble.
    pub const CLOTH_ANCHOR: u32 = 1 << 14;
    /// Le node accepte les décalques.
    pub const DECAL_TARGET: u32 = 1 << 15;
    /// Le node projette une ombre.
    pub const SHADOW_CASTER: u32 = 1 << 16;
    /// Les normales du node ne suivent pas la déformation.
    pub const NO_DEFORM_NORMALS: u32 = 1 << 17;
}

/// Valeur signifiant « racine » dans le champ `parent` d'un [`NodeDesc`].
pub const NO_PARENT: u32 = u32::MAX;

/// Valeur signifiant « aucun » dans un champ d'index `u32`.
pub const NONE_U32: u32 = u32::MAX;

/// Valeur signifiant « aucune » dans un champ d'index `u16`.
pub const NONE_U16: u16 = u16::MAX;

/// `lod_mask` d'un node visible à tous les niveaux de détail.
///
/// C'est la valeur d'un node sans annotation : R-913 veut qu'une annotation
/// contrôle et n'active pas, et un node qui disparaîtrait dès le premier LOD
/// généré faute d'annotation contredirait la règle.
pub const ALL_LODS: u8 = u8::MAX;

/// États de node (SM-03, PARTIE 9.2), valeurs du champ [`NodeDesc::state`].
///
/// Le cahier des charges nomme les états sans leur donner de valeur. Ils sont
/// numérotés dans l'**ordre de priorité de R-930** : entre deux sources, la plus
/// grande valeur l'emporte, et une comparaison suffit à l'arbitrer. `STATIC`
/// vaut zéro, ce qu'écrit tout importeur pour un node sans annotation.
pub mod node_state {
    /// Transform locale constante depuis l'asset.
    pub const STATIC: u8 = 0;
    /// Transform locale issue d'une piste d'animation.
    pub const ANIMATION_DRIVEN: u8 = 1;
    /// Transform locale calculée par un système : roue, direction, suspension.
    pub const PROCEDURAL: u8 = 2;
    /// Transform locale issue de l'état d'un joint physique.
    pub const JOINT_DRIVEN: u8 = 3;
    /// Transform monde issue d'un body ; la locale en est recalculée.
    pub const PHYSICS_DRIVEN: u8 = 4;
    /// Le node appartient désormais à une autre assembly.
    pub const DETACHED: u8 = 5;

    /// Indique si la valeur désigne un état connu.
    #[must_use]
    pub const fn is_known(state: u8) -> bool {
        state <= DETACHED
    }
}

/// Empreinte d'un nom, champ `name_hash` des descripteurs : FNV-1a 64 bits.
///
/// C'est l'empreinte des identifiants du cahier des charges (DM-01). Elle vit
/// ici, avec les descripteurs qui la portent : l'importeur glTF la calculait
/// seul, et les nodes OBJ et STL restaient à zéro — une empreinte que personne
/// ne pouvait retrouver depuis un nom.
#[must_use]
pub const fn name_hash(name: &str) -> u64 {
    let bytes = name.as_bytes();
    let mut hash = 0xcbf2_9ce4_8422_2325_u64;
    let mut index = 0;
    while index < bytes.len() {
        hash ^= bytes[index] as u64;
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
        index += 1;
    }
    hash
}

/// Node de la hiérarchie d'un asset (DM-03).
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct NodeDesc {
    /// Empreinte du nom.
    pub name_hash: u64,
    /// Index du parent, [`NO_PARENT`] pour une racine.
    pub parent: u32,
    /// Transformation locale.
    pub local: Transform,
    /// Drapeaux, voir [`node_flags`].
    pub flags: u32,
    /// Mesh porté, [`NONE_U32`] si aucun.
    pub mesh: u32,
    /// Collider porté, [`NONE_U32`] si aucun.
    pub collider: u32,
    /// Os associé, [`NONE_U32`] si aucun.
    pub bone: u32,
    /// Part d'appartenance, [`NONE_U16`] si aucune.
    pub part: u16,
    /// Région de déformation, [`NONE_U16`] si aucune.
    pub region: u16,
    /// Masque des niveaux de détail où le node apparaît.
    pub lod_mask: u8,
    /// Source de l'état visuel du node.
    pub state: u8,
    /// Nombre de meshes portés, consécutifs à partir de `mesh` (ADR-122 §5) :
    /// une primitive glTF donne un mesh, et un mesh glTF en porte plusieurs.
    /// `0` vaut un quand `mesh` en désigne un — la lecture de tout asset
    /// compilé avant lui. Voir [`NodeDesc::meshes`].
    pub mesh_count: u16,
}

impl NodeDesc {
    /// Indique si le node porte ce drapeau.
    #[must_use]
    pub const fn has(&self, flag: u32) -> bool {
        self.flags & flag != 0
    }

    /// Les meshes portés : `mesh .. mesh + mesh_count`, vide si le node n'en
    /// porte aucun.
    ///
    /// `mesh_count` nul vaut un (ADR-122 §5). La plage n'est pas bornée ici par
    /// le nombre de meshes de l'asset, que seul son lecteur connaît : C-22 le
    /// vérifie à la compilation, la liste de dessin au chargement.
    #[must_use]
    pub const fn meshes(&self) -> core::ops::Range<u32> {
        if self.mesh == NONE_U32 {
            return 0..0;
        }
        let count = if self.mesh_count == 0 {
            1
        } else {
            self.mesh_count as u32
        };
        self.mesh..self.mesh.saturating_add(count)
    }

    /// Indique si le node est une racine.
    #[must_use]
    pub const fn is_root(&self) -> bool {
        self.parent == NO_PARENT
    }
}

/// Drapeaux d'une part (DM-11).
pub mod part_flags {
    /// La part peut se détacher.
    pub const DETACHABLE: u32 = 1 << 0;
    /// La perte de la part met l'assembly hors service.
    pub const CRITICAL: u32 = 1 << 1;
    /// La part disparaît une fois détruite.
    pub const HIDE_WHEN_DESTROYED: u32 = 1 << 2;
    /// La part participe au graphe structurel.
    pub const STRUCTURAL: u32 = 1 << 3;
    /// La part est interne.
    pub const INTERNAL: u32 = 1 << 4;
    /// La part ne se déforme pas.
    pub const NO_DEFORM: u32 = 1 << 5;
    /// La part est réparable.
    pub const REPAIRABLE: u32 = 1 << 6;
    /// La part engendre des débris en se détachant.
    pub const SPAWNS_DEBRIS: u32 = 1 << 7;
}

/// Part d'une assembly (DM-11).
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct PartDesc {
    /// Empreinte du nom.
    pub name_hash: u64,
    /// Node racine du sous-arbre de la part.
    pub root_node: u32,
    /// Part parente, [`NONE_U16`] pour une racine.
    pub parent_part: u16,
    /// Réservé, à zéro.
    pub _pad: u16,
    /// Drapeaux, voir [`part_flags`].
    pub flags: u32,
    /// Points de vie, pour le dommage visuel et fonctionnel.
    pub max_health: f32,
    /// Énergie structurelle absorbable avant rupture, en joules.
    pub structural_capacity: f32,
    /// Fraction d'intégrité déclenchant le détachement, dans `[0, 1]`.
    pub detach_threshold: f32,
    /// Fraction d'énergie transmise au parent, dans `[0, 1]`.
    pub propagation: f32,
    /// Masse, en kilogrammes.
    pub mass: f32,
    /// Matériau physique dominant.
    pub material: u16,
    /// Première région de déformation de la part.
    pub region_first: u16,
    /// Nombre de régions de déformation.
    pub region_count: u16,
    /// Réservé, à zéro.
    pub _pad2: u16,
    /// Mesh de l'état intact.
    pub mesh_intact: u32,
    /// Mesh de l'état endommagé.
    pub mesh_damaged: u32,
    /// Mesh de l'état détruit.
    pub mesh_destroyed: u32,
    /// Définition à instancier au détachement ; `0` pour un débris automatique.
    pub debris_definition: u64,
}

impl PartDesc {
    /// Indique si la part porte ce drapeau.
    #[must_use]
    pub const fn has(&self, flag: u32) -> bool {
        self.flags & flag != 0
    }

    /// Indique si la part est une racine du graphe de parts.
    #[must_use]
    pub const fn is_root(&self) -> bool {
        self.parent_part == NONE_U16
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use core::mem::size_of;

    #[test]
    fn t230_les_drapeaux_de_node_sont_distincts() {
        // Deux drapeaux au même bit se confondraient sans que rien ne le dise.
        let tous = [
            node_flags::VISIBLE,
            node_flags::SOCKET,
            node_flags::WHEEL,
            node_flags::SEAT,
            node_flags::LIGHT,
            node_flags::DETACHABLE,
            node_flags::ANIMATED,
            node_flags::PHYSICS_DRIVEN,
            node_flags::HIDDEN_IN_FIRST_PERSON,
            node_flags::NO_CULL,
            node_flags::EMISSIVE,
            node_flags::DEFORMABLE,
            node_flags::INTERNAL,
            node_flags::REVEALED_ON_DAMAGE,
            node_flags::CLOTH_ANCHOR,
            node_flags::DECAL_TARGET,
            node_flags::SHADOW_CASTER,
            node_flags::NO_DEFORM_NORMALS,
        ];
        let mut cumul = 0u32;
        for flag in tous {
            assert_eq!(flag.count_ones(), 1, "{flag:#x} n'est pas un bit unique");
            assert_eq!(cumul & flag, 0, "{flag:#x} en double");
            cumul |= flag;
        }
        assert_eq!(tous.len(), 18);
    }

    #[test]
    fn t230_les_drapeaux_de_part_sont_distincts() {
        let tous = [
            part_flags::DETACHABLE,
            part_flags::CRITICAL,
            part_flags::HIDE_WHEN_DESTROYED,
            part_flags::STRUCTURAL,
            part_flags::INTERNAL,
            part_flags::NO_DEFORM,
            part_flags::REPAIRABLE,
            part_flags::SPAWNS_DEBRIS,
        ];
        let mut cumul = 0u32;
        for flag in tous {
            assert_eq!(cumul & flag, 0, "{flag:#x} en double");
            cumul |= flag;
        }
    }

    #[test]
    fn t230_une_racine_se_reconnait() {
        let mut node = NodeDesc {
            name_hash: 0,
            parent: NO_PARENT,
            local: Transform::identity(),
            flags: node_flags::VISIBLE,
            mesh: NONE_U32,
            collider: NONE_U32,
            bone: NONE_U32,
            part: NONE_U16,
            region: NONE_U16,
            lod_mask: 1,
            state: 0,
            mesh_count: 0,
        };
        assert!(node.is_root());
        assert!(node.has(node_flags::VISIBLE));
        assert!(!node.has(node_flags::DEFORMABLE));

        node.parent = 0;
        assert!(!node.is_root());
    }

    #[test]
    fn t291_un_node_porte_la_plage_de_ses_meshes() {
        let node = |mesh: u32, mesh_count: u16| NodeDesc {
            name_hash: 0,
            parent: NO_PARENT,
            local: Transform::identity(),
            flags: node_flags::VISIBLE,
            mesh,
            collider: NONE_U32,
            bone: NONE_U32,
            part: NONE_U16,
            region: NONE_U16,
            lod_mask: 1,
            state: 0,
            mesh_count,
        };
        // Zéro vaut un : la lecture de tout asset compilé avant ADR-122.
        assert_eq!(node(3, 0).meshes(), 3..4);
        assert_eq!(node(3, 1).meshes(), 3..4);
        assert_eq!(node(3, 4).meshes(), 3..7);
        // Sans mesh, rien, quel que soit le compte.
        assert!(node(NONE_U32, 0).meshes().is_empty());
        assert!(node(NONE_U32, 5).meshes().is_empty());
        // Une plage qui déborderait d'un `u32` s'arrête au bord : C-22 la refuse.
        assert_eq!(node(u32::MAX - 2, 9).meshes().end, u32::MAX);
    }

    #[test]
    fn la_disposition_des_descripteurs_est_figee() {
        // Le cahier des charges ne donne de taille que pour le sommet ; pour
        // les autres, c'est la stabilité qui compte : un champ ajouté ou
        // déplacé rendrait illisibles les assets déjà compilés, et ce test
        // pose la question au moment où le changement est fait.
        assert_eq!(size_of::<NodeDesc>(), 80);
        // La section NODE (ADR-110) écrit chaque node à ces positions, et les
        // quatre derniers octets sont le remplissage de fin qu'impose
        // l'alignement sur le `u64` de tête.
        assert_eq!(core::mem::offset_of!(NodeDesc, name_hash), 0);
        assert_eq!(core::mem::offset_of!(NodeDesc, parent), 8);
        assert_eq!(core::mem::offset_of!(NodeDesc, local), 12);
        assert_eq!(core::mem::offset_of!(NodeDesc, flags), 52);
        assert_eq!(core::mem::offset_of!(NodeDesc, mesh), 56);
        assert_eq!(core::mem::offset_of!(NodeDesc, collider), 60);
        assert_eq!(core::mem::offset_of!(NodeDesc, bone), 64);
        assert_eq!(core::mem::offset_of!(NodeDesc, part), 68);
        assert_eq!(core::mem::offset_of!(NodeDesc, region), 70);
        assert_eq!(core::mem::offset_of!(NodeDesc, lod_mask), 72);
        assert_eq!(core::mem::offset_of!(NodeDesc, state), 73);
        assert_eq!(core::mem::offset_of!(NodeDesc, mesh_count), 74);
    }

    #[test]
    fn l_empreinte_de_nom_est_fnv1a_64() {
        // Vecteurs de référence de FNV-1a 64 bits.
        assert_eq!(name_hash(""), 0xcbf2_9ce4_8422_2325);
        assert_eq!(name_hash("a"), 0xaf63_dc4c_8601_ec8c);
        assert_eq!(name_hash("foobar"), 0x8594_4171_f739_67e8);
        assert_eq!(size_of::<PartDesc>(), 72);
    }
}
