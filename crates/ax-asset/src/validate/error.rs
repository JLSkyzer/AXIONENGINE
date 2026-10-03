//! Violations de validation et leur localisation (R-541).

use core::fmt;

/// Où se trouve ce qui cloche.
///
/// R-541 exige une erreur **nommée et localisée**. « Poids non normalisés »
/// n'aide personne sur un modèle de deux millions de sommets ; « sommet 148 372
/// du mesh 3 » se corrige.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Located {
    /// Rien de plus précis que l'asset lui-même.
    Asset,
    /// Un node, par son index.
    Node(usize),
    /// Un mesh, par son index.
    Mesh(usize),
    /// Un sommet, par son index absolu.
    Vertex(usize),
    /// Un indice de triangle, par sa position dans le tableau d'indices.
    Index(usize),
    /// Un triangle, par son rang dans le mesh.
    Triangle {
        /// Mesh auquel il appartient.
        mesh: usize,
        /// Rang du triangle dans le mesh.
        triangle: usize,
    },
    /// Un collider, par son index.
    Collider(usize),
    /// Une part, par son index.
    Part(usize),
    /// Une région de déformation, par son index.
    Region(usize),
    /// Une liaison structurelle, par son index.
    Link(usize),
    /// Un matériau, par son index.
    Material(usize),
}

impl fmt::Display for Located {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Located::Asset => formatter.write_str("asset"),
            Located::Node(index) => write!(formatter, "node {index}"),
            Located::Mesh(index) => write!(formatter, "mesh {index}"),
            Located::Vertex(index) => write!(formatter, "sommet {index}"),
            Located::Index(index) => write!(formatter, "indice {index}"),
            Located::Triangle { mesh, triangle } => {
                write!(formatter, "triangle {triangle} du mesh {mesh}")
            }
            Located::Collider(index) => write!(formatter, "collider {index}"),
            Located::Part(index) => write!(formatter, "part {index}"),
            Located::Region(index) => write!(formatter, "région {index}"),
            Located::Link(index) => write!(formatter, "liaison {index}"),
            Located::Material(index) => write!(formatter, "matériau {index}"),
        }
    }
}

/// Nature d'une violation.
///
/// Les codes de l'ANNEXE A.1 sont plus grossiers que cette liste : l'annexe
/// déclare `E-3020..E-3060` comme « violations de validation », et n'attribue
/// un code propre qu'aux violations que le corps du cahier des charges nomme.
/// Le code classe, le message explique — c'est bien le partage que R-541
/// décrit.
#[derive(Debug, Clone, PartialEq)]
pub enum Violation {
    /// Échelle non uniforme sur un node portant un collider (R-120, `E-3020`).
    NonUniformScaleOnCollider,
    /// Quaternion de norme hors `[0.9, 1.1]` (R-121).
    RotationNotNormalized,
    /// Une valeur non finie là où le moteur attend un nombre.
    NotFinite(&'static str),
    /// Hiérarchie hors ordre topologique (R-130, `E-3021`).
    ParentAfterChild {
        /// Index du parent.
        parent: usize,
    },
    /// Parent inexistant.
    ParentOutOfRange {
        /// Index invoqué.
        parent: u32,
    },
    /// Profondeur au-delà du maximum (R-131, `E-3022`).
    DepthExceeded {
        /// Profondeur atteinte.
        depth: u32,
    },
    /// Un dénombrement au-delà de son plafond.
    LimitExceeded {
        /// Ce qui est compté.
        what: &'static str,
        /// Valeur constatée.
        count: usize,
        /// Plafond.
        limit: usize,
    },
    /// Indice de sommet hors des bornes du mesh.
    IndexOutOfRange {
        /// Indice fautif.
        index: u32,
        /// Nombre de sommets du mesh.
        vertex_count: u32,
    },
    /// Nombre d'indices non multiple de trois.
    IndexCountNotTriangles {
        /// Nombre constaté.
        count: u32,
    },
    /// Plage de sommets ou d'indices hors du tableau de l'asset.
    RangeOutOfAsset {
        /// Ce qui est indexé.
        what: &'static str,
    },
    /// Triangle d'aire nulle ou quasi nulle.
    DegenerateTriangle {
        /// Aire calculée.
        area: f32,
    },
    /// Boîte englobante non finie, inversée ou trop grande.
    InvalidAabb,
    /// Coordonnée de texture hors des bornes admises (R-142, `E-3030`).
    UvOutOfRange {
        /// Valeur fautive.
        value: f32,
    },
    /// Normale non normalisable.
    NormalNotNormalizable,
    /// Poids d'os de somme nulle, donc non normalisables.
    WeightsNotNormalizable,
    /// Index d'os au-delà du squelette.
    BoneOutOfRange {
        /// Index invoqué.
        bone: u8,
        /// Nombre d'os.
        bone_count: usize,
    },
    /// Densité nulle, négative ou non finie.
    InvalidDensity {
        /// Valeur constatée.
        density: f32,
    },
    /// Masse hors de `[0.001, 1e6]` kg.
    MassOutOfRange {
        /// Valeur constatée.
        mass: f32,
    },
    /// Dimension de forme nulle, négative ou non finie.
    InvalidShapeDimension {
        /// Nom de la forme.
        shape: &'static str,
    },
    /// Enveloppe convexe hors de `[4, 256]` points (R-161, `E-3040`).
    ConvexPointCount {
        /// Nombre constaté.
        count: u32,
    },
    /// Forme sans volume sur un body dynamique (R-160).
    ShapeForbiddenOnDynamicBody {
        /// Nom de la forme.
        shape: &'static str,
    },
    /// Résolution de lattice hors de `[2, 16]`.
    LatticeResolution {
        /// Axe fautif, `0`, `1` ou `2`.
        axis: usize,
        /// Valeur constatée.
        value: u8,
    },
    /// Nombre de nœuds incohérent avec la résolution.
    NodeCountMismatch {
        /// Valeur déclarée.
        declared: u32,
        /// Valeur qu'implique la résolution.
        expected: u32,
    },
    /// OBB dégénérée : au moins une demi-dimension nulle ou non finie.
    DegenerateObb,
    /// Épaisseur nulle, négative ou non finie.
    InvalidThickness {
        /// Valeur constatée.
        thickness: f32,
    },
    /// Déplacement maximal nul, ou au-delà de la moitié de la plus petite
    /// dimension de l'OBB.
    MaxDisplacementOutOfRange {
        /// Valeur constatée.
        max_disp: f32,
        /// Plafond admissible.
        limit: f32,
    },
    /// Un node `DEFORMABLE` sans région, ou dans une région inexistante.
    DeformableWithoutRegion,
    /// Une région sans aucun nœud ancré alors que `ANCHORED_BORDER` est posé.
    RegionWithoutAnchor,
    /// Une part orpheline qui n'est pas racine.
    OrphanPart {
        /// Part parente invoquée.
        parent: u16,
    },
    /// Cycle dans le graphe de parts.
    PartCycle,
    /// Liaison référençant une part inexistante.
    LinkPartOutOfRange {
        /// Part invoquée.
        part: u16,
    },
    /// Liaison dont les deux extrémités sont la même part.
    LinkToItself,
    /// Capacité nulle, négative ou non finie.
    InvalidCapacity,
    /// Nature de liaison inconnue.
    UnknownLinkKind {
        /// Valeur stockée.
        raw: u8,
    },
    /// Fraction hors de `[0, 1]`.
    FractionOutOfRange {
        /// Nom du champ.
        field: &'static str,
        /// Valeur constatée.
        value: f32,
    },
    /// Nom vide, trop long, ou non imprimable.
    InvalidName {
        /// Ce qui cloche.
        reason: &'static str,
    },
    /// Deux éléments d'une même catégorie portent le même nom.
    DuplicateName {
        /// Catégorie concernée.
        category: &'static str,
    },
    /// Matériau que DM-05 refuse : énumération ou drapeau inconnu, facteur
    /// non fini, seuil de découpe hors de `[0, 1]`.
    InvalidMaterial {
        /// Ce qui cloche, tel que `MaterialDesc::check` le dit.
        reason: &'static str,
    },
    /// Slot de texture désignant une entrée absente de `TEXR`.
    TextureOutOfRange {
        /// Slot fautif : `albedo`, `normal`, `orm`…
        slot: &'static str,
        /// Index désigné.
        texture: u16,
        /// Nombre d'entrées de `TEXR`.
        texture_count: usize,
    },
    /// Mesh désignant un matériau absent de la table.
    MaterialOutOfRange {
        /// Index désigné.
        material: u16,
        /// Nombre de matériaux.
        material_count: usize,
    },
    /// Drapeaux `TRANSPARENT` et `DOUBLE_SIDED` d'un mesh en désaccord avec
    /// son matériau (ADR-122 §3).
    MeshFlagsDisagreeWithMaterial {
        /// Ce que le mesh porte, réduit à ces deux drapeaux.
        mesh_flags: u8,
        /// Ce que le matériau impose.
        material_flags: u8,
    },
}

impl Violation {
    /// Code de l'ANNEXE A.1 correspondant.
    ///
    /// Les codes nommés par le corps du cahier des charges sont employés tels
    /// quels ; le reste tombe dans `E-3050`, que R-190 attribue aux
    /// dépassements de plafond d'assembly et qui est le code de validation le
    /// plus général de la plage déclarée. Aucun code n'est inventé : l'ANNEXE
    /// A.1 ne s'étend pas depuis le code (voir ADR-102).
    #[must_use]
    pub const fn code(&self) -> i32 {
        match self {
            Violation::NonUniformScaleOnCollider => -3020,
            Violation::ParentAfterChild { .. } => -3021,
            Violation::DepthExceeded { .. } => -3022,
            Violation::UvOutOfRange { .. } => -3030,
            Violation::ConvexPointCount { .. } => -3040,
            _ => -3050,
        }
    }
}

impl fmt::Display for Violation {
    #[allow(clippy::too_many_lines)]
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Violation::NonUniformScaleOnCollider => formatter.write_str(
                "échelle non uniforme sur un node portant un collider : la forme \
                 obtenue n'est plus descriptible par le solveur",
            ),
            Violation::RotationNotNormalized => {
                formatter.write_str("quaternion de norme hors de [0.9, 1.1]")
            }
            Violation::NotFinite(field) => write!(formatter, "{field} n'est pas un nombre fini"),
            Violation::ParentAfterChild { parent } => write!(
                formatter,
                "parent {parent} déclaré après son enfant : la hiérarchie doit \
                 être en ordre topologique"
            ),
            Violation::ParentOutOfRange { parent } => {
                write!(formatter, "parent {parent} inexistant")
            }
            Violation::DepthExceeded { depth } => {
                write!(formatter, "profondeur {depth}, maximum 32")
            }
            Violation::LimitExceeded { what, count, limit } => {
                write!(formatter, "{count} {what}, maximum {limit}")
            }
            Violation::IndexOutOfRange {
                index,
                vertex_count,
            } => write!(
                formatter,
                "indice {index} hors des {vertex_count} sommets du mesh"
            ),
            Violation::IndexCountNotTriangles { count } => write!(
                formatter,
                "{count} indices : le compte n'est pas un multiple de trois"
            ),
            Violation::RangeOutOfAsset { what } => {
                write!(formatter, "plage de {what} hors du tableau de l'asset")
            }
            Violation::DegenerateTriangle { area } => {
                write!(formatter, "triangle d'aire {area:e}, minimum 1e-9")
            }
            Violation::InvalidAabb => {
                formatter.write_str("boîte englobante non finie, inversée ou au-delà de 512 blocs")
            }
            Violation::UvOutOfRange { value } => {
                write!(formatter, "coordonnée de texture {value}, hors de [-8, 9]")
            }
            Violation::NormalNotNormalizable => formatter.write_str("normale non normalisable"),
            Violation::WeightsNotNormalizable => {
                formatter.write_str("poids d'os de somme nulle, donc non normalisables")
            }
            Violation::BoneOutOfRange { bone, bone_count } => {
                write!(formatter, "os {bone} hors des {bone_count} os du squelette")
            }
            Violation::InvalidDensity { density } => {
                write!(
                    formatter,
                    "densité {density}, attendue strictement positive"
                )
            }
            Violation::MassOutOfRange { mass } => {
                write!(formatter, "masse {mass} kg, hors de [0.001, 1e6]")
            }
            Violation::InvalidShapeDimension { shape } => {
                write!(
                    formatter,
                    "dimension nulle ou non finie sur une forme {shape}"
                )
            }
            Violation::ConvexPointCount { count } => {
                write!(
                    formatter,
                    "enveloppe convexe de {count} points, hors de [4, 256]"
                )
            }
            Violation::ShapeForbiddenOnDynamicBody { shape } => write!(
                formatter,
                "forme {shape} sur un body dynamique : elle n'a pas de volume défini"
            ),
            Violation::LatticeResolution { axis, value } => write!(
                formatter,
                "résolution de lattice {value} sur l'axe {axis}, hors de [2, 16]"
            ),
            Violation::NodeCountMismatch { declared, expected } => write!(
                formatter,
                "{declared} nœuds déclarés, {expected} qu'implique la résolution"
            ),
            Violation::DegenerateObb => formatter.write_str("OBB dégénérée"),
            Violation::InvalidThickness { thickness } => write!(
                formatter,
                "épaisseur {thickness}, attendue strictement positive"
            ),
            Violation::MaxDisplacementOutOfRange { max_disp, limit } => write!(
                formatter,
                "déplacement maximal {max_disp}, hors de ]0, {limit}] : au-delà, \
                 le volume se retourne sur lui-même"
            ),
            Violation::DeformableWithoutRegion => formatter.write_str(
                "node DEFORMABLE sans région : chaque node déformable appartient \
                 à exactement une région",
            ),
            Violation::RegionWithoutAnchor => formatter
                .write_str("région ANCHORED_BORDER sans aucun nœud ancré : rien ne la retient"),
            Violation::OrphanPart { parent } => {
                write!(formatter, "part orpheline : parent {parent} inexistant")
            }
            Violation::PartCycle => formatter.write_str("cycle dans le graphe de parts"),
            Violation::LinkPartOutOfRange { part } => {
                write!(formatter, "liaison vers la part {part}, inexistante")
            }
            Violation::LinkToItself => formatter.write_str("liaison d'une part vers elle-même"),
            Violation::InvalidCapacity => {
                formatter.write_str("capacité nulle, négative ou non finie")
            }
            Violation::UnknownLinkKind { raw } => {
                write!(formatter, "nature de liaison {raw} inconnue")
            }
            Violation::FractionOutOfRange { field, value } => {
                write!(formatter, "{field} vaut {value}, hors de [0, 1]")
            }
            Violation::InvalidName { reason } => write!(formatter, "nom invalide : {reason}"),
            Violation::DuplicateName { category } => {
                write!(formatter, "deux {category} portent le même nom")
            }
            Violation::InvalidMaterial { reason } => {
                write!(formatter, "matériau invalide : {reason}")
            }
            Violation::TextureOutOfRange {
                slot,
                texture,
                texture_count,
            } => write!(
                formatter,
                "slot {slot} vers la texture {texture}, hors des {texture_count} de TEXR"
            ),
            Violation::MaterialOutOfRange {
                material,
                material_count,
            } => write!(
                formatter,
                "matériau {material} hors des {material_count} de la table"
            ),
            Violation::MeshFlagsDisagreeWithMaterial {
                mesh_flags,
                material_flags,
            } => write!(
                formatter,
                "le mesh porte {}, son matériau impose {}",
                material_mesh_flags(*mesh_flags),
                material_mesh_flags(*material_flags)
            ),
        }
    }
}

/// Nomme les drapeaux de mesh qu'un matériau impose : un auteur de pack lit
/// « TRANSPARENT », pas `0b100`.
fn material_mesh_flags(flags: u8) -> &'static str {
    use ax_model::dm::geometry::mesh_flags::{DOUBLE_SIDED, TRANSPARENT};
    match (flags & TRANSPARENT != 0, flags & DOUBLE_SIDED != 0) {
        (false, false) => "ni TRANSPARENT ni DOUBLE_SIDED",
        (true, false) => "TRANSPARENT",
        (false, true) => "DOUBLE_SIDED",
        (true, true) => "TRANSPARENT et DOUBLE_SIDED",
    }
}

/// Une violation, avec l'endroit où elle se trouve.
#[derive(Debug, Clone, PartialEq)]
pub struct ValidationError {
    /// Ce qui cloche.
    pub violation: Violation,
    /// Où.
    pub at: Located,
}

impl ValidationError {
    /// Compose une erreur localisée.
    #[must_use]
    pub const fn new(violation: Violation, at: Located) -> Self {
        Self { violation, at }
    }

    /// Code de l'ANNEXE A.1 correspondant.
    #[must_use]
    pub const fn code(&self) -> i32 {
        self.violation.code()
    }
}

impl fmt::Display for ValidationError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{} : {}", self.at, self.violation)
    }
}

impl std::error::Error for ValidationError {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn t231_une_erreur_dit_quoi_et_ou() {
        // R-541 : nommée et localisée. « Poids non normalisés » n'aide personne
        // sur deux millions de sommets.
        let error =
            ValidationError::new(Violation::WeightsNotNormalizable, Located::Vertex(148_372));
        let rendu = error.to_string();
        assert!(rendu.contains("sommet 148372"), "{rendu}");
        assert!(rendu.contains("poids"), "{rendu}");
    }

    #[test]
    fn t231_les_codes_restent_dans_la_plage_declaree() {
        // L'ANNEXE A.1 déclare E-3020..E-3060 pour les violations de
        // validation : aucun code hors de cette plage ne doit sortir d'ici.
        let cas = [
            Violation::NonUniformScaleOnCollider,
            Violation::DepthExceeded { depth: 33 },
            Violation::UvOutOfRange { value: 12.0 },
            Violation::ConvexPointCount { count: 2 },
            Violation::PartCycle,
            Violation::DegenerateObb,
            Violation::InvalidName { reason: "vide" },
            Violation::InvalidMaterial {
                reason: "mode de mélange inconnu",
            },
            Violation::TextureOutOfRange {
                slot: "albedo",
                texture: 3,
                texture_count: 1,
            },
            Violation::MaterialOutOfRange {
                material: 2,
                material_count: 1,
            },
            Violation::MeshFlagsDisagreeWithMaterial {
                mesh_flags: 0,
                material_flags: 4,
            },
        ];
        for violation in cas {
            let code = violation.code();
            assert!(
                (-3060..=-3020).contains(&code),
                "{violation:?} rend {code}, hors de la plage déclarée"
            );
        }
    }

    #[test]
    fn t231_les_codes_nommes_par_le_cdc_sont_employes_tels_quels() {
        assert_eq!(Violation::NonUniformScaleOnCollider.code(), -3020);
        // R-130 exige l'ordre topologique ; l'exiger rend un cycle impossible,
        // et il n'y a donc pas de detection de cycle separee — elle serait du
        // code inatteignable.
        assert_eq!(Violation::ParentAfterChild { parent: 3 }.code(), -3021);
        assert_eq!(Violation::DepthExceeded { depth: 40 }.code(), -3022);
        assert_eq!(Violation::UvOutOfRange { value: 20.0 }.code(), -3030);
        assert_eq!(Violation::ConvexPointCount { count: 300 }.code(), -3040);
    }

    #[test]
    fn t231_chaque_localisation_se_lit() {
        let localisations = [
            Located::Asset,
            Located::Node(1),
            Located::Mesh(2),
            Located::Vertex(3),
            Located::Index(4),
            Located::Triangle {
                mesh: 5,
                triangle: 6,
            },
            Located::Collider(7),
            Located::Part(8),
            Located::Region(9),
            Located::Link(10),
            Located::Material(11),
        ];
        for at in localisations {
            let rendu = at.to_string();
            assert!(!rendu.is_empty(), "{at:?} sans rendu");
        }
    }
}
