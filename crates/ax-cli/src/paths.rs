//! Résolution sûre d'un fichier voisin d'une source.
//!
//! Quand un OBJ désigne son `.mtl` ou un glTF un buffer externe, la CLI lit ce
//! fichier **à côté de la source**. L'importeur refuse déjà les chemins absolus
//! et les remontées (R-531) ; ce filet est une défense en profondeur, du côté
//! qui touche réellement le disque.

use std::path::{Component, Path, PathBuf};

/// Résout un chemin relatif contre le répertoire de la source.
///
/// Rend `None` si le chemin est absolu ou remonte l'arborescence : l'importeur
/// traite alors la référence comme absente, ce qui est un refus propre plutôt
/// qu'une lecture hors du répertoire de l'asset.
#[must_use]
pub fn sibling_path(source_dir: &Path, relative: &str) -> Option<PathBuf> {
    let candidate = Path::new(relative);
    for component in candidate.components() {
        match component {
            // Seuls un segment de nom et le « . » courant sont admis.
            Component::Normal(_) | Component::CurDir => {}
            // Racine, préfixe de disque (Windows) ou « .. » : refusés.
            Component::RootDir | Component::Prefix(_) | Component::ParentDir => return None,
        }
    }
    Some(source_dir.join(candidate))
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
        assert_eq!(
            sibling_path(dir, "./voiture.mtl"),
            Some(Path::new("assets/voiture/./voiture.mtl").to_path_buf())
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
