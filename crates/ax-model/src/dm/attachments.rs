//! Les attaches entre assemblies, sur la frontière (DM-17, C-48, ADR-171 D6).
//!
//! Java tient le graphe des attaches, leurs sockets et leurs identifiants ; le natif n'en reçoit
//! que ce qu'il faut pour créer le joint : le corps et le repère de chaque extrémité, ou un point
//! du monde pour un ancrage, le type, les seuils et les longueurs. Trois commandes ([`Attach`],
//! [`Detach`], [`SetAttachmentLength`]), une section de `SIM_OUT` ([`AttachmentReport`]), et deux
//! genres d'événement : `ATTACH` à la création, `DETACH_ATTACHMENT` à la fin, sa cause dans
//! `node_a` ([`detach_cause`]).

use super::handle::Handle;

/// Les types d'attache (ADR-171 D1), champ `kind` d'[`Attach`]. `SEATED` est retiré (Q1).
pub mod attachment_kind {
    /// Soude les deux sockets : un joint fixe.
    pub const RIGID: u8 = 0;
    /// Une rotule dont chaque axe de balancement est borné par `cone`.
    pub const HITCH: u8 = 1;
    /// Une distance maximale, `length`.
    pub const ROPE: u8 = 2;
    /// Une corde dont la longueur suit [`super::SetAttachmentLength`], à `speed`, dans
    /// `[min_length, max_length]`.
    pub const WINCH: u8 = 3;
    /// Un joint fixe que Java pose et retire selon la variable de commande.
    pub const MAGNET: u8 = 4;
    /// Le dernier type connu.
    pub const LAST: u8 = MAGNET;
}

/// La cause d'un `DETACH_ATTACHMENT`, portée par `node_a` de l'événement ; `data` porte
/// l'identifiant de l'attache.
pub mod detach_cause {
    /// Le joint a rompu au-delà de sa force ou de son couple (T-891).
    pub const BROKEN: u32 = super::super::physics::event_data::DETACH_BROKEN;
    /// Le natif a refusé de la créer : une extrémité inconnue, deux mondes, un réglage non fini.
    pub const REFUSED: u32 = super::super::physics::event_data::DETACH_REFUSED;
}

/// Payload d'`ATTACH` (ADR-171 D6) : 152 octets. L'identifiant vient de Java, unique dans la
/// session ; un identifiant déjà vivant est refusé.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Attach {
    /// L'identifiant de l'attache.
    pub id: u32,
    /// Voir [`attachment_kind`].
    pub kind: u8,
    /// Réservé, à zéro.
    pub _pad0: [u8; 3],
    /// L'assembly de l'extrémité `a`.
    pub a: Handle,
    /// Node racine, dans l'asset, du corps de `a` qui porte le socket.
    pub a_node: u32,
    /// Node racine du corps de `b` ; ignoré pour un ancrage au monde.
    pub b_node: u32,
    /// L'assembly de l'extrémité `b`, ou [`Handle::ABSENT`] pour un ancrage au monde.
    pub b: Handle,
    /// Le repère du socket `a` dans son corps : position `(x, y, z)` puis rotation `(x, y, z, w)`.
    pub a_frame: [f32; 7],
    /// Réservé, à zéro.
    pub _pad1: u32,
    /// Le repère du socket `b` dans son corps ; ignoré pour un ancrage au monde.
    pub b_frame: [f32; 7],
    /// Réservé, à zéro.
    pub _pad2: u32,
    /// Le point d'ancrage au monde, en coordonnées absolues ; ignoré entre deux assemblies.
    pub world: [f64; 3],
    /// Force au-delà de laquelle l'attache rompt, en newtons ; l'infini pour jamais.
    pub max_force: f32,
    /// Couple au-delà duquel elle rompt, en newtons-mètres ; l'infini pour jamais.
    pub max_torque: f32,
    /// `HITCH` : le balancement permis sur chaque axe, en radians, de part et d'autre.
    pub cone: f32,
    /// `ROPE`, `WINCH` : la longueur au départ, en blocs.
    pub length: f32,
    /// `WINCH` : la longueur la plus courte.
    pub min_length: f32,
    /// `WINCH` : la longueur la plus longue.
    pub max_length: f32,
    /// `WINCH` : la vitesse d'enroulement, en blocs par seconde.
    pub speed: f32,
    /// Réservé, à zéro.
    pub _pad3: u32,
}

impl Attach {
    /// Taille sur la frontière, en octets.
    pub const BYTES: usize = 152;
}

/// Payload de `DETACH` : 8 octets. Un identifiant inconnu est ignoré.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Detach {
    /// L'identifiant de l'attache.
    pub id: u32,
    /// Réservé, à zéro.
    pub _pad: u32,
}

impl Detach {
    /// Taille sur la frontière, en octets.
    pub const BYTES: usize = 8;
}

/// Payload de `SET_ATTACHMENT_LENGTH` (ADR-171 D5) : 8 octets. La longueur cible d'un treuil, que
/// le natif rejoint à sa vitesse, sous-pas par sous-pas, dans ses bornes.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct SetAttachmentLength {
    /// L'identifiant de l'attache.
    pub id: u32,
    /// La longueur cible, en blocs.
    pub target: f32,
}

impl SetAttachmentLength {
    /// Taille sur la frontière, en octets.
    pub const BYTES: usize = 8;
}

/// Drapeaux d'[`AttachmentReport`], champ `flags`.
pub mod attachment_report_flags {
    /// L'attache a rompu à ce tick ; c'est son dernier rapport.
    pub const BROKEN: u32 = 1 << 0;
}

/// L'état d'une attache à la fin d'un tick (ADR-171 D6) : ce que lisent les sources `attach.*` et
/// `socket.*`.
///
/// **Disposition figée** : 24 octets. Dans `SIM_OUT` (schéma 7), la section suit les états complets
/// exportés : leur nombre (`u32`), 4 octets réservés, puis un enregistrement par attache vivante,
/// dans l'ordre de leurs identifiants, et une dernière fois pour celle qui vient de rompre.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct AttachmentReport {
    /// L'identifiant de l'attache.
    pub id: u32,
    /// Voir [`attachment_report_flags`].
    pub flags: u32,
    /// `ROPE`, `WINCH` : la longueur de la corde, en blocs ; 0 sinon.
    pub length: f32,
    /// La distance entre les deux ancrages, en blocs : le désalignement d'un `RIGID` ou d'un
    /// `HITCH`, la portée tendue d'une corde.
    pub distance: f32,
    /// La force transmise au dernier sous-pas, en newtons : la tension d'une corde.
    pub force: f32,
    /// Le couple transmis, en newtons-mètres.
    pub torque: f32,
}

impl AttachmentReport {
    /// Taille sur la frontière, en octets.
    pub const BYTES: usize = 24;

    /// Taille de l'en-tête de la section : le nombre d'attaches et 4 octets réservés.
    pub const SECTION_HEADER_BYTES: usize = 8;

    /// Écrit la section des attaches de `SIM_OUT` à la fin de `out`.
    ///
    /// # Panics
    ///
    /// Au-delà de `u32::MAX` attaches.
    pub fn write_section(reports: &[Self], out: &mut Vec<u8>) {
        let count = u32::try_from(reports.len()).expect("au plus u32::MAX attaches");
        out.extend_from_slice(&count.to_le_bytes());
        out.extend_from_slice(&[0u8; 4]);
        for report in reports {
            report.write_le(out);
        }
    }

    /// Lit la section des attaches au début de `bytes` ; `None` si elle est tronquée.
    #[must_use]
    pub fn read_section(bytes: &[u8]) -> Option<Vec<Self>> {
        let count = u32::from_le_bytes(bytes.get(..4)?.try_into().expect("borne")) as usize;
        let records = bytes.get(Self::SECTION_HEADER_BYTES..)?;
        (0..count)
            .map(|rank| Self::read_le(records.get(rank.checked_mul(Self::BYTES)?..)?))
            .collect()
    }

    /// Écrit le rapport en little-endian, dans sa disposition figée, à la fin de `out`.
    pub fn write_le(&self, out: &mut Vec<u8>) {
        out.extend_from_slice(&self.id.to_le_bytes());
        out.extend_from_slice(&self.flags.to_le_bytes());
        out.extend_from_slice(&self.length.to_le_bytes());
        out.extend_from_slice(&self.distance.to_le_bytes());
        out.extend_from_slice(&self.force.to_le_bytes());
        out.extend_from_slice(&self.torque.to_le_bytes());
    }

    /// Lit un rapport au début de `bytes` ; `None` s'il est tronqué.
    #[must_use]
    pub fn read_le(bytes: &[u8]) -> Option<Self> {
        let bytes = bytes.get(..Self::BYTES)?;
        let u32_at = |at: usize| u32::from_le_bytes(bytes[at..at + 4].try_into().expect("borne"));
        let f32_at = |at: usize| f32::from_le_bytes(bytes[at..at + 4].try_into().expect("borne"));
        Some(Self {
            id: u32_at(0),
            flags: u32_at(4),
            length: f32_at(8),
            distance: f32_at(12),
            force: f32_at(16),
            torque: f32_at(20),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use core::mem::{offset_of, size_of};

    #[test]
    fn dispositions_figees() {
        assert_eq!(size_of::<Attach>(), Attach::BYTES);
        assert_eq!(offset_of!(Attach, a), 8);
        assert_eq!(offset_of!(Attach, a_node), 16);
        assert_eq!(offset_of!(Attach, b_node), 20);
        assert_eq!(offset_of!(Attach, b), 24);
        assert_eq!(offset_of!(Attach, a_frame), 32);
        assert_eq!(offset_of!(Attach, b_frame), 64);
        assert_eq!(offset_of!(Attach, world), 96);
        assert_eq!(offset_of!(Attach, max_force), 120);
        assert_eq!(offset_of!(Attach, speed), 144);
        assert_eq!(size_of::<Detach>(), Detach::BYTES);
        assert_eq!(size_of::<SetAttachmentLength>(), SetAttachmentLength::BYTES);
        assert_eq!(size_of::<AttachmentReport>(), AttachmentReport::BYTES);
    }

    #[test]
    fn la_section_des_attaches_fait_l_aller_retour() {
        let reports = [
            AttachmentReport {
                id: 3,
                flags: 0,
                length: 6.0,
                distance: 5.5,
                force: 1200.0,
                torque: 0.0,
            },
            AttachmentReport {
                id: 9,
                flags: attachment_report_flags::BROKEN,
                length: 0.0,
                distance: 0.02,
                force: 90_000.0,
                torque: 15.0,
            },
        ];
        let mut out = Vec::new();
        AttachmentReport::write_section(&reports, &mut out);
        assert_eq!(
            out.len(),
            AttachmentReport::SECTION_HEADER_BYTES + 2 * AttachmentReport::BYTES
        );
        assert_eq!(AttachmentReport::read_section(&out).unwrap(), reports);
        assert!(AttachmentReport::read_section(&out[..20]).is_none());
    }
}
