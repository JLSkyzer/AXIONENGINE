//! DM-02 et DM-04 — transformations, meshes et sommets.

/// Transformation locale d'un node (DM-02).
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Transform {
    /// Translation, en blocs.
    pub translation: [f32; 3],
    /// Rotation, quaternion `(x, y, z, w)` (R-461).
    pub rotation: [f32; 4],
    /// Échelle par axe.
    pub scale: [f32; 3],
}

impl Transform {
    /// Transformation neutre.
    #[must_use]
    pub const fn identity() -> Self {
        Self {
            translation: [0.0; 3],
            rotation: [0.0, 0.0, 0.0, 1.0],
            scale: [1.0; 3],
        }
    }

    /// Indique si l'échelle est uniforme.
    ///
    /// R-120 l'autorise sur un node de rendu et l'**interdit** sur un node
    /// portant un collider : une échelle non uniforme déforme un collider
    /// convexe en quelque chose que le moteur physique ne sait plus décrire.
    #[must_use]
    pub fn has_uniform_scale(&self) -> bool {
        let [x, y, z] = self.scale;
        x == y && y == z
    }

    /// Indique si tous les champs sont finis.
    #[must_use]
    pub fn is_finite(&self) -> bool {
        self.translation.iter().all(|value| value.is_finite())
            && self.rotation.iter().all(|value| value.is_finite())
            && self.scale.iter().all(|value| value.is_finite())
    }

    /// Norme du quaternion de rotation.
    #[must_use]
    pub fn rotation_norm(&self) -> f32 {
        self.rotation
            .iter()
            .map(|value| value * value)
            .sum::<f32>()
            .sqrt()
    }
}

/// Position d'une assembly dans le monde (DM-02).
///
/// La position est en `f64` : R-462 l'impose, un monde Minecraft dépassant
/// largement la précision d'un `f32` à ses bords.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct WorldTransform {
    /// Position monde, en blocs.
    pub position: [f64; 3],
    /// Rotation, quaternion `(x, y, z, w)`.
    pub rotation: [f32; 4],
}

/// Drapeaux d'un mesh (DM-04).
pub mod mesh_flags {
    /// Le mesh est déformé par un squelette.
    pub const SKINNED: u8 = 1 << 0;
    /// Les deux faces sont rendues.
    pub const DOUBLE_SIDED: u8 = 1 << 1;
    /// Le mesh passe par la passe transparente.
    pub const TRANSPARENT: u8 = 1 << 2;
    /// Le mesh est soumis à la déformation continue.
    pub const DEFORMABLE: u8 = 1 << 3;
}

/// Description d'un mesh (DM-04).
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct MeshDesc {
    /// Premier sommet, dans le tableau de sommets de l'asset.
    pub vertex_offset: u32,
    /// Nombre de sommets.
    pub vertex_count: u32,
    /// Premier indice, dans le tableau d'indices de l'asset.
    pub index_offset: u32,
    /// Nombre d'indices.
    pub index_count: u32,
    /// Matériau de rendu.
    pub material: u16,
    /// Niveau de détail.
    pub lod: u8,
    /// Drapeaux, voir [`mesh_flags`].
    pub flags: u8,
    /// Coin inférieur de la boîte englobante, en espace local.
    pub aabb_min: [f32; 3],
    /// Coin supérieur de la boîte englobante.
    pub aabb_max: [f32; 3],
    /// Région de déformation dominante, `u16::MAX` si aucune.
    pub region: u16,
    /// Réservé, à zéro.
    pub _pad: u16,
}

/// Valeur signifiant « aucune région » dans un champ `u16`.
pub const NO_REGION_U16: u16 = u16::MAX;

/// Valeur signifiant « aucune région » dans le champ `u8` d'un [`Vertex`].
pub const NO_REGION_U8: u8 = 255;

/// Format de sommet canonique, entrelacé, 48 octets (DM-04).
///
/// **Figé en V1.0** (R-140). Un format unique évite la combinatoire de variantes
/// que trainent les moteurs qui en acceptent plusieurs, et permet de lire les
/// sommets en place dans une section A3D.
///
/// `region` et `def_w` occupent deux octets pris sur la zone réservée : la
/// taille et l'alignement sont inchangés, et R-883 s'appuie dessus pour ne
/// stocker **aucune** donnée de liaison au lattice — les coordonnées y sont
/// implicites.
#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Vertex {
    /// Position locale, en blocs.
    pub position: [f32; 3],
    /// Normale, `i8` normalisée.
    pub normal: [i8; 4],
    /// Tangente ; `w` porte le signe de la bitangente.
    pub tangent: [i8; 4],
    /// Coordonnées de texture principales, `UNORM16`.
    pub uv0: [u16; 2],
    /// Second jeu de coordonnées : lightmap, occlusion ambiante.
    pub uv1: [u16; 2],
    /// Couleur de sommet.
    pub color: [u8; 4],
    /// Indices d'os.
    pub bones: [u8; 4],
    /// Poids d'os, `UNORM8`, de somme 255.
    pub weights: [u8; 4],
    /// Index de région de déformation ; [`NO_REGION_U8`] si aucune.
    pub region: u8,
    /// Poids de déformation, `UNORM8` : 0 totalement rigide, 255 pleinement
    /// déformable (R-141).
    pub def_w: u8,
    /// Réservé, à zéro.
    pub _pad: [u8; 6],
}

impl Vertex {
    /// Taille du format, en octets. Figée par R-140.
    pub const BYTES: usize = 48;

    /// Somme attendue des poids d'os, en `UNORM8`.
    pub const WEIGHT_SUM: u16 = 255;

    /// Somme des poids d'os.
    #[must_use]
    pub fn weight_sum(&self) -> u16 {
        self.weights.iter().map(|weight| u16::from(*weight)).sum()
    }

    /// Écrit le sommet sur ses 48 octets, en petit-boutiste, à la suite de `out`.
    ///
    /// C'est **la** sérialisation du sommet canonique : la section `GEOM` du
    /// compilateur et le transfert de géométrie vers Java (ADR-119) l'emploient
    /// tous deux. Deux écritures du même format divergeraient à la première
    /// correction — c'est ce qui était arrivé à la quantification des normales.
    /// La zone réservée est écrite à zéro, quelle que soit sa valeur en mémoire :
    /// un octet non initialisé rendrait un asset non reproductible.
    pub fn write_le(&self, out: &mut Vec<u8>) {
        for value in self.position {
            out.extend_from_slice(&value.to_le_bytes());
        }
        out.extend_from_slice(&self.normal.map(|value| value as u8));
        out.extend_from_slice(&self.tangent.map(|value| value as u8));
        for value in self.uv0 {
            out.extend_from_slice(&value.to_le_bytes());
        }
        for value in self.uv1 {
            out.extend_from_slice(&value.to_le_bytes());
        }
        out.extend_from_slice(&self.color);
        out.extend_from_slice(&self.bones);
        out.extend_from_slice(&self.weights);
        out.push(self.region);
        out.push(self.def_w);
        out.extend_from_slice(&[0; 6]);
    }

    /// Lit un sommet depuis ses 48 octets petit-boutistes.
    ///
    /// Le tableau de taille fixe porte la vérification de bornes : l'appelant
    /// découpe la tranche, et une tranche trop courte est refusée par la
    /// conversion, jamais lue au-delà. La zone réservée est rendue à zéro.
    #[must_use]
    pub fn read_le(bytes: &[u8; Self::BYTES]) -> Self {
        let f32_at = |at: usize| {
            f32::from_le_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]])
        };
        let u16_at = |at: usize| u16::from_le_bytes([bytes[at], bytes[at + 1]]);
        let i8x4_at = |at: usize| {
            [
                bytes[at] as i8,
                bytes[at + 1] as i8,
                bytes[at + 2] as i8,
                bytes[at + 3] as i8,
            ]
        };
        let u8x4_at = |at: usize| [bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]];
        Self {
            position: [f32_at(0), f32_at(4), f32_at(8)],
            normal: i8x4_at(12),
            tangent: i8x4_at(16),
            uv0: [u16_at(20), u16_at(22)],
            uv1: [u16_at(24), u16_at(26)],
            color: u8x4_at(28),
            bones: u8x4_at(32),
            weights: u8x4_at(36),
            region: bytes[40],
            def_w: bytes[41],
            _pad: [0; 6],
        }
    }
}

impl MeshDesc {
    /// Taille du descripteur, en octets (DM-04).
    pub const BYTES: usize = 48;

    /// Écrit le descripteur sur ses 48 octets, en petit-boutiste, à la suite de
    /// `out`.
    ///
    /// Sérialisation unique, partagée par la section `GEOM` et le transfert de
    /// géométrie (ADR-119). Le champ réservé est écrit à zéro.
    pub fn write_le(&self, out: &mut Vec<u8>) {
        out.extend_from_slice(&self.vertex_offset.to_le_bytes());
        out.extend_from_slice(&self.vertex_count.to_le_bytes());
        out.extend_from_slice(&self.index_offset.to_le_bytes());
        out.extend_from_slice(&self.index_count.to_le_bytes());
        out.extend_from_slice(&self.material.to_le_bytes());
        out.push(self.lod);
        out.push(self.flags);
        for value in self.aabb_min {
            out.extend_from_slice(&value.to_le_bytes());
        }
        for value in self.aabb_max {
            out.extend_from_slice(&value.to_le_bytes());
        }
        out.extend_from_slice(&self.region.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
    }

    /// Lit un descripteur depuis ses 48 octets petit-boutistes ; le champ
    /// réservé est rendu à zéro.
    #[must_use]
    pub fn read_le(bytes: &[u8; Self::BYTES]) -> Self {
        let u32_at = |at: usize| {
            u32::from_le_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]])
        };
        let f32_at = |at: usize| {
            f32::from_le_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]])
        };
        let u16_at = |at: usize| u16::from_le_bytes([bytes[at], bytes[at + 1]]);
        Self {
            vertex_offset: u32_at(0),
            vertex_count: u32_at(4),
            index_offset: u32_at(8),
            index_count: u32_at(12),
            material: u16_at(16),
            lod: bytes[18],
            flags: bytes[19],
            aabb_min: [f32_at(20), f32_at(24), f32_at(28)],
            aabb_max: [f32_at(32), f32_at(36), f32_at(40)],
            region: u16_at(44),
            _pad: 0,
        }
    }
}

/// Encode une normale en `i8` normalisée, telle que [`Vertex`] la porte.
///
/// La quantification fait partie du format canonique de DM-12, pas du travail
/// d'un importeur : elle vit donc ici, avec le type qu'elle sert. Elle en était
/// recopiée à l'identique dans les trois importeurs, et l'optimizer en aurait
/// fait une quatrième copie — trois endroits où une correction n'aurait pas
/// suivi partout.
///
/// **Une direction inexploitable n'est pas remplacée par une direction
/// inventée.** Une normale nulle, infinie ou d'une longueur sous le seuil rend
/// `[0; 4]`, c'est-à-dire « pas de direction » : C-22 la refuse comme non
/// normalisable (R-541), ou C-23 la génère depuis la géométrie quand l'importeur
/// l'a déclarée absente. Rendre un axe par défaut, comme le faisait la première
/// version, effaçait la différence entre une normale `+Y` écrite par l'auteur
/// et une normale qui n'existait pas — et plus rien en aval ne pouvait la
/// retrouver.
#[must_use]
pub fn encode_normal(normal: [f32; 3]) -> [i8; 4] {
    let length = (normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2]).sqrt();
    if !length.is_finite() || length <= f32::EPSILON {
        return [0; 4];
    }

    let mut encoded = [0i8; 4];
    for (index, value) in normal.iter().enumerate() {
        // `127` et non `128` : la plage `i8` est asymétrique, et l'employer
        // entièrement rendrait `-1` et `+1` de magnitudes différentes une fois
        // décodés.
        encoded[index] = ((value / length) * 127.0).round().clamp(-127.0, 127.0) as i8;
    }
    encoded
}

/// Encode une tangente, telle que [`Vertex`] la porte : direction en `i8`
/// normalisée, signe de la bitangente en `w` (`+127` ou `-127`).
///
/// Même règle que [`encode_normal`] : une direction inexploitable rend `[0; 4]`,
/// jamais un axe choisi à la place de la source. Le signe suit la convention
/// glTF et MikkTSpace, `bitangente = w · (normale × tangente)`.
#[must_use]
pub fn encode_tangent(tangent: [f32; 4]) -> [i8; 4] {
    let mut encoded = encode_normal([tangent[0], tangent[1], tangent[2]]);
    if encoded == [0; 4] {
        return encoded;
    }
    encoded[3] = if tangent[3] < 0.0 { -127 } else { 127 };
    encoded
}

#[cfg(test)]
mod tests {
    use super::*;
    use core::mem::{align_of, offset_of, size_of};

    #[test]
    fn t230_le_format_de_sommet_est_fige() {
        // R-140 : le format est unique et figé en V1.0. Ce test est ce qui
        // rend « figé » vérifiable : un champ ajouté, déplacé ou élargi le
        // fait échouer, et c'est bien le but.
        assert_eq!(size_of::<Vertex>(), Vertex::BYTES);
        assert_eq!(align_of::<Vertex>(), 4);

        assert_eq!(offset_of!(Vertex, position), 0);
        assert_eq!(offset_of!(Vertex, normal), 12);
        assert_eq!(offset_of!(Vertex, tangent), 16);
        assert_eq!(offset_of!(Vertex, uv0), 20);
        assert_eq!(offset_of!(Vertex, uv1), 24);
        assert_eq!(offset_of!(Vertex, color), 28);
        assert_eq!(offset_of!(Vertex, bones), 32);
        assert_eq!(offset_of!(Vertex, weights), 36);
        assert_eq!(offset_of!(Vertex, region), 40);
        assert_eq!(offset_of!(Vertex, def_w), 41);
        assert_eq!(offset_of!(Vertex, _pad), 42);
    }

    #[test]
    fn t230_le_sommet_tient_dans_une_section_alignee() {
        // R-881 aligne les sections sur 16 octets ; un sommet aligné sur 4 s'y
        // lit en place quel que soit son rang.
        assert_eq!(Vertex::BYTES % align_of::<Vertex>(), 0);
        assert_eq!(16 % align_of::<Vertex>(), 0);
    }

    #[test]
    fn t230_la_somme_des_poids_se_calcule_sans_deborder() {
        let vertex = Vertex {
            position: [0.0; 3],
            normal: [0; 4],
            tangent: [0; 4],
            uv0: [0; 2],
            uv1: [0; 2],
            color: [0; 4],
            bones: [0; 4],
            weights: [255, 255, 255, 255],
            region: NO_REGION_U8,
            def_w: 0,
            _pad: [0; 6],
        };
        // Quatre poids à 255 dépasseraient un u8 : la somme se fait en u16.
        assert_eq!(vertex.weight_sum(), 1020);
    }

    #[test]
    fn t230_une_echelle_non_uniforme_se_reconnait() {
        assert!(Transform::identity().has_uniform_scale());

        let mut transform = Transform::identity();
        transform.scale = [1.0, 2.0, 1.0];
        assert!(!transform.has_uniform_scale());
    }

    #[test]
    fn t230_une_transformation_non_finie_se_reconnait() {
        let mut transform = Transform::identity();
        assert!(transform.is_finite());

        transform.translation[1] = f32::NAN;
        assert!(!transform.is_finite());

        transform = Transform::identity();
        transform.scale[2] = f32::INFINITY;
        assert!(!transform.is_finite());
    }

    #[test]
    fn t230_la_norme_du_quaternion_se_mesure() {
        assert!((Transform::identity().rotation_norm() - 1.0).abs() < 1e-6);

        let mut transform = Transform::identity();
        transform.rotation = [0.0, 0.0, 0.0, 2.0];
        assert!((transform.rotation_norm() - 2.0).abs() < 1e-6);
    }

    #[test]
    fn t230_une_normale_inexploitable_ne_recoit_aucune_direction() {
        assert_eq!(encode_normal([0.0, 0.0, 2.0]), [0, 0, 127, 0]);
        assert_eq!(encode_normal([-3.0, 0.0, 0.0]), [-127, 0, 0, 0]);
        // Nulle, non finie ou trop courte : « pas de direction », que C-22
        // refuse ou que C-23 génère — jamais un axe choisi à la place de
        // l'auteur.
        assert_eq!(encode_normal([0.0; 3]), [0; 4]);
        assert_eq!(encode_normal([f32::NAN, 1.0, 0.0]), [0; 4]);
        assert_eq!(encode_normal([f32::INFINITY, 0.0, 0.0]), [0; 4]);
        assert_eq!(encode_normal([1e-9, 0.0, 0.0]), [0; 4]);
    }

    #[test]
    fn t230_une_tangente_porte_le_signe_de_sa_bitangente() {
        assert_eq!(encode_tangent([2.0, 0.0, 0.0, 1.0]), [127, 0, 0, 127]);
        assert_eq!(encode_tangent([0.0, -1.0, 0.0, -1.0]), [0, -127, 0, -127]);
        // Direction inexploitable : rien, pas même un signe.
        assert_eq!(encode_tangent([0.0, 0.0, 0.0, -1.0]), [0; 4]);
    }

    #[test]
    fn les_descripteurs_ne_portent_pas_de_remplissage_implicite() {
        // Un octet de remplissage implicite serait de la mémoire non
        // initialisée écrite dans un fichier, donc un build non reproductible
        // et une comparaison d'assets faussée.
        assert_eq!(size_of::<MeshDesc>(), 48);
        assert_eq!(size_of::<Transform>(), 40);
        assert_eq!(size_of::<WorldTransform>(), 40);
    }

    fn sommet_type() -> Vertex {
        Vertex {
            position: [1.5, -2.0, 0.25],
            normal: [0, 127, -127, 0],
            tangent: [127, 0, 0, -127],
            uv0: [0x1234, 0xFFFF],
            uv1: [7, 8],
            color: [255, 128, 64, 32],
            bones: [1, 2, 3, 4],
            weights: [200, 55, 0, 0],
            region: 9,
            def_w: 250,
            // Une zone réservée non nulle en mémoire ne doit pas fuir à l'écriture.
            _pad: [0xAA; 6],
        }
    }

    fn mesh_type() -> MeshDesc {
        MeshDesc {
            vertex_offset: 10,
            vertex_count: 24,
            index_offset: 30,
            index_count: 36,
            material: 0x0102,
            lod: 3,
            flags: mesh_flags::DOUBLE_SIDED | mesh_flags::DEFORMABLE,
            aabb_min: [-0.5, 0.0, -0.5],
            aabb_max: [0.5, 1.0, 0.5],
            region: NO_REGION_U16,
            _pad: 0xBEEF,
        }
    }

    #[test]
    fn t230_l_ecriture_du_sommet_est_figee_octet_par_octet() {
        // Octets écrits à la main : c'est la disposition de DM-04 qui est
        // vérifiée, pas la cohérence de l'écriture avec elle-même. Ce sont aussi
        // ceux que produisait l'écrivain de `GEOM` avant d'adopter cette
        // fonction : l'adopter ne change aucun octet d'un asset compilé.
        let mut attendu = Vec::new();
        for value in [1.5f32, -2.0, 0.25] {
            attendu.extend_from_slice(&value.to_le_bytes());
        }
        attendu.extend_from_slice(&[0, 127, 0x81, 0]); // normale, i8 -> octet
        attendu.extend_from_slice(&[127, 0, 0, 0x81]); // tangente
        attendu.extend_from_slice(&0x1234u16.to_le_bytes());
        attendu.extend_from_slice(&0xFFFFu16.to_le_bytes());
        attendu.extend_from_slice(&7u16.to_le_bytes());
        attendu.extend_from_slice(&8u16.to_le_bytes());
        attendu.extend_from_slice(&[255, 128, 64, 32]);
        attendu.extend_from_slice(&[1, 2, 3, 4]);
        attendu.extend_from_slice(&[200, 55, 0, 0]);
        attendu.push(9);
        attendu.push(250);
        attendu.extend_from_slice(&[0; 6]);

        let mut ecrit = Vec::new();
        sommet_type().write_le(&mut ecrit);
        assert_eq!(ecrit.len(), Vertex::BYTES);
        assert_eq!(ecrit, attendu);
    }

    #[test]
    fn t230_l_ecriture_du_mesh_est_figee_octet_par_octet() {
        let mut attendu = Vec::new();
        for value in [10u32, 24, 30, 36] {
            attendu.extend_from_slice(&value.to_le_bytes());
        }
        attendu.extend_from_slice(&0x0102u16.to_le_bytes());
        attendu.push(3);
        attendu.push(mesh_flags::DOUBLE_SIDED | mesh_flags::DEFORMABLE);
        for value in [-0.5f32, 0.0, -0.5, 0.5, 1.0, 0.5] {
            attendu.extend_from_slice(&value.to_le_bytes());
        }
        attendu.extend_from_slice(&NO_REGION_U16.to_le_bytes());
        attendu.extend_from_slice(&[0, 0]);

        let mut ecrit = Vec::new();
        mesh_type().write_le(&mut ecrit);
        assert_eq!(ecrit.len(), MeshDesc::BYTES);
        assert_eq!(ecrit, attendu);
    }

    #[test]
    fn t230_sommet_et_mesh_font_l_aller_retour() {
        let mut octets = Vec::new();
        sommet_type().write_le(&mut octets);
        let relu = Vertex::read_le(octets.as_slice().try_into().expect("48 octets"));
        // La zone réservée revient à zéro : seule elle diffère de l'original.
        assert_eq!(
            relu,
            Vertex {
                _pad: [0; 6],
                ..sommet_type()
            }
        );

        let mut octets = Vec::new();
        mesh_type().write_le(&mut octets);
        let relu = MeshDesc::read_le(octets.as_slice().try_into().expect("48 octets"));
        assert_eq!(
            relu,
            MeshDesc {
                _pad: 0,
                ..mesh_type()
            }
        );
    }
}
