//! `axion-cli mannequin` : le mannequin du joueur, hors du jeu (ADR-177 D2, CAP-56).
//!
//! Deux fichiers GLB, `joueur_large.glb` et `joueur_fin.glb`, aux vraies cotes du joueur de Minecraft — pour Blender et les autres
//! outils. Ils sont **écrits par le jeu** (`./gradlew genMannequin`, depuis le modèle du joueur) et **jugés par lui** (le banc de rendu
//! compare chaque sommet, à chaque pose, au modèle du jeu) ; la CLI les porte avec elle, et ne fait que les copier. Aucune texture
//! de Mojang : celle du fichier est neutre, au format d'un skin (64 × 64).

/// Les fichiers du mannequin : leur nom, et leurs octets.
const FICHIERS: [(&str, &[u8]); 2] = [
    (
        "joueur_large.glb",
        include_bytes!("../../../content/examples/axion/models/mannequin/joueur_large.glb"),
    ),
    (
        "joueur_fin.glb",
        include_bytes!("../../../content/examples/axion/models/mannequin/joueur_fin.glb"),
    ),
];

/// Les fichiers du mannequin, tels que la commande les écrit : le nom de chacun et ses octets.
#[must_use]
pub fn mannequin_files() -> &'static [(&'static str, &'static [u8])] {
    &FICHIERS
}

/// Ce que la commande dit après avoir écrit ses fichiers : une ligne par fichier, puis comment s'en servir.
#[must_use]
pub fn mannequin_report(dir: &str, written: &[(&str, usize)]) -> Vec<String> {
    let mut lines: Vec<String> = written
        .iter()
        .map(|(name, bytes)| format!("{dir}/{name} : {bytes} octets"))
        .collect();
    lines.push(
        "Clips : stand (debout), sneak (accroupi), sit (assis, posé au node SEAT : ses pieds au décalage de monture)."
            .to_owned(),
    );
    lines.push(
        "Dans Blender : importer le GLB, puis donner pour parent à son nœud racine le node SEAT du véhicule.".to_owned(),
    );
    lines
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn les_deux_fichiers_sont_des_glb() {
        let files = mannequin_files();
        assert_eq!(files.len(), 2);
        for (name, bytes) in files {
            assert!(name.ends_with(".glb"), "{name}");
            assert_eq!(&bytes[0..4], b"glTF", "{name} : l'en-tête d'un GLB");
            assert_eq!(
                u32::from_le_bytes(bytes[4..8].try_into().unwrap()),
                2,
                "{name} : glTF 2"
            );
            assert_eq!(
                u32::from_le_bytes(bytes[8..12].try_into().unwrap()) as usize,
                bytes.len(),
                "{name} : sa longueur"
            );
        }
    }

    #[test]
    fn le_rapport_dit_les_clips_et_le_node_seat() {
        let lines = mannequin_report("sortie", &[("joueur_large.glb", 10)]);
        assert_eq!(lines[0], "sortie/joueur_large.glb : 10 octets");
        assert!(lines.iter().any(|line| line.contains("sit")));
        assert!(lines.iter().any(|line| line.contains("SEAT")));
    }
}
