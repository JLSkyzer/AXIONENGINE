//! Résolution sûre d'un fichier voisin d'une source.
//!
//! Quand un OBJ désigne son `.mtl` ou un glTF un buffer externe, la CLI lit ce
//! fichier **à côté de la source**. L'importeur refuse déjà les chemins absolus
//! et les remontées (R-531) ; ce filet est une défense en profondeur, du côté
//! qui touche réellement le disque.

use ax_asset::import::normalize_reference;
use std::path::{Path, PathBuf};

/// Résout un chemin relatif contre le répertoire de la source.
///
/// Rend `None` si le chemin est absolu ou remonte l'arborescence : l'importeur
/// traite alors la référence comme absente, ce qui est un refus propre plutôt
/// qu'une lecture hors du répertoire de l'asset.
///
/// La règle est celle du jeu, [`normalize_reference`] (R-830, ADR-139 §3) : le refus ne
/// dépend pas de la plateforme — un antislash ou une lettre de lecteur est refusé partout —,
/// et le nom normalisé est celui que le jeu cherche parmi les fichiers que Java lui passe.
#[must_use]
pub fn sibling_path(source_dir: &Path, relative: &str) -> Option<PathBuf> {
    let normalized = normalize_reference(relative)?;
    Some(
        normalized
            .split('/')
            .fold(source_dir.to_path_buf(), |path, segment| path.join(segment)),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn t580_un_voisin_se_resout_sous_le_repertoire_de_la_source() {
        let dir = Path::new("assets/voiture");
        assert_eq!(
            sibling_path(dir, "voiture.mtl"),
            Some(Path::new("assets/voiture/voiture.mtl").to_path_buf())
        );
        assert_eq!(
            sibling_path(dir, "textures/carrosserie.png"),
            Some(Path::new("assets/voiture/textures/carrosserie.png").to_path_buf())
        );
        // La forme normale, celle que le jeu cherche : le même fichier.
        assert_eq!(
            sibling_path(dir, "./voiture.mtl"),
            Some(Path::new("assets/voiture/voiture.mtl").to_path_buf())
        );
    }

    #[test]
    fn t580_une_remontee_ou_un_chemin_absolu_est_refuse() {
        let dir = Path::new("assets/voiture");
        assert_eq!(sibling_path(dir, "../secret.mtl"), None);
        assert_eq!(sibling_path(dir, "a/../../secret"), None);
        assert_eq!(sibling_path(dir, "/etc/passwd"), None);
        // Chemin absolu Windows.
        assert_eq!(sibling_path(dir, "C:\\Windows\\system.ini"), None);
    }
}
