//! Plafonds du modèle et codes de violation.
//!
//! Chaque plafond vient d'une exigence nommée, et chaque dépassement d'un code
//! de l'ANNEXE A.1. Les valeurs ne sont pas configurables : ce ne sont pas des
//! budgets qu'on ajuste, ce sont les bornes au-delà desquelles le moteur ne
//! sait plus travailler.

/// Profondeur maximale de la hiérarchie de nodes (R-131, `E-3022`).
pub const MAX_NODE_DEPTH: u32 = 32;

/// Nombre maximal de nodes par asset (R-132, `E-3023`).
pub const MAX_NODES: usize = 4096;

/// Nombre maximal de sommets par asset (R-143, `E-3031`).
pub const MAX_VERTICES: usize = 2_000_000;

/// Nombre maximal d'indices par asset (R-143, `E-3031`).
pub const MAX_INDICES: usize = 6_000_000;

/// Nombre maximal d'os par squelette (R-200, `E-3060`).
pub const MAX_BONES: usize = 128;

/// Nombre maximal de matériaux par asset (fiche C-22).
pub const MAX_MATERIALS: usize = 256;

/// Nombre maximal de textures par asset (fiche C-22).
pub const MAX_TEXTURES: usize = 128;

/// Côté maximal d'une texture, en pixels (R-570, `E-3006`).
pub const MAX_TEXTURE_SIDE: u32 = 4096;

/// Nombre maximal d'animations par asset (fiche C-22).
pub const MAX_ANIMATIONS: usize = 128;

/// Nombre maximal de parts par assembly (R-190, `E-3050`).
pub const MAX_PARTS: usize = 64;

/// Nombre maximal de colliders par asset (fiche C-22).
pub const MAX_COLLIDERS: usize = 256;

/// Nombre maximal de régions de déformation par assembly (R-190, `E-3050`).
pub const MAX_REGIONS: usize = 32;

/// Nombre maximal de liaisons structurelles par asset (fiche C-22).
pub const MAX_STRUCTURAL_LINKS: usize = 256;

/// Aire minimale d'un triangle, en unités de surface locale (fiche C-22).
///
/// Un triangle plus petit n'a pas de normale exploitable : la direction qu'on
/// en tirerait dépendrait entièrement de l'erreur d'arrondi.
pub const MIN_TRIANGLE_AREA: f32 = 1e-9;

/// Dimension maximale d'une boîte englobante, en blocs (fiche C-22).
pub const MAX_AABB_EXTENT: f32 = 512.0;

/// Borne inférieure d'une coordonnée UV avant normalisation (R-142, `E-3030`).
pub const MIN_UV: f32 = -8.0;

/// Borne supérieure d'une coordonnée UV avant normalisation (R-142, `E-3030`).
pub const MAX_UV: f32 = 9.0;

/// Masse minimale d'une part, en kilogrammes (fiche C-22).
pub const MIN_PART_MASS: f32 = 0.001;

/// Masse maximale d'une part, en kilogrammes (fiche C-22).
pub const MAX_PART_MASS: f32 = 1e6;

/// Longueur maximale d'un nom, en octets (fiche C-22).
pub const MAX_NAME_BYTES: usize = 64;

/// Norme minimale admise d'un quaternion externe (R-121).
pub const MIN_QUAT_NORM: f32 = 0.9;

/// Norme maximale admise d'un quaternion externe (R-121).
pub const MAX_QUAT_NORM: f32 = 1.1;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn t230_les_plafonds_sont_ceux_du_cahier_des_charges() {
        assert_eq!(MAX_NODE_DEPTH, 32);
        assert_eq!(MAX_NODES, 4096);
        assert_eq!(MAX_VERTICES, 2_000_000);
        assert_eq!(MAX_INDICES, 6_000_000);
        assert_eq!(MAX_BONES, 128);
        assert_eq!(MAX_PARTS, 64);
        assert_eq!(MAX_REGIONS, 32);
        assert_eq!(MAX_NAME_BYTES, 64);
    }

    #[test]
    fn t230_les_bornes_encadrent_bien_leur_domaine() {
        // Des bornes inversées feraient tout refuser, ou tout accepter, sans
        // qu'aucun test de validation ne le montre : ils passeraient tous.
        // Vérifié à la compilation, ces valeurs étant des constantes.
        const _: () = assert!(MIN_UV < MAX_UV);
        const _: () = assert!(MIN_PART_MASS < MAX_PART_MASS);
        const _: () = assert!(MIN_QUAT_NORM < MAX_QUAT_NORM);
        const _: () = assert!(MIN_TRIANGLE_AREA > 0.0);
        const _: () = assert!(MAX_AABB_EXTENT > 0.0);
    }
}
