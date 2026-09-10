//! T-170 — R-470 : aucun crate n'installe le pool global de `rayon`.
//!
//! `ThreadPoolBuilder::build_global` configure un pool unique pour tout le
//! processus. Dans Minecraft, ce processus n'appartient pas à AXION : l'appeler
//! reviendrait à décider du parallélisme des autres mods, et le premier qui
//! l'aurait appelé gagnerait. Un pool dédié n'a pas cet effet de bord.
//!
//! La vérification est statique parce qu'aucun test dynamique ne la rendrait :
//! l'appel réussit une fois par processus, et un test qui l'observerait aurait
//! déjà installé le pool.

use std::fs;
use std::path::{Path, PathBuf};

/// Appel interdit, épelé en deux morceaux : la recherche porte sur le texte des
/// sources, celui-ci compris.
const FORBIDDEN: &str = concat!("build_", "global");

fn workspace_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .ancestors()
        .nth(2)
        .expect("racine du workspace introuvable")
        .to_path_buf()
}

fn rust_sources(root: &Path, into: &mut Vec<PathBuf>) {
    let Ok(entries) = fs::read_dir(root) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            rust_sources(&path, into);
        } else if path.extension().is_some_and(|ext| ext == "rs") {
            into.push(path);
        }
    }
}

#[test]
fn t170_aucun_pool_global_n_est_installe() {
    let root = workspace_root();
    let mut sources = Vec::new();
    rust_sources(&root.join("crates"), &mut sources);
    rust_sources(&root.join("tools"), &mut sources);
    assert!(
        !sources.is_empty(),
        "aucune source Rust trouvée sous {}",
        root.display()
    );

    let mut hits = Vec::new();
    for source in sources {
        let Ok(content) = fs::read_to_string(&source) else {
            continue;
        };
        for (number, line) in content.lines().enumerate() {
            // R-470 interdit l'appel, pas le fait de le nommer : la règle
            // s'explique, et ce fichier comme la documentation du composant
            // l'écrivent en toutes lettres. Une ligne de commentaire ne peut
            // rien exécuter.
            if line.trim_start().starts_with("//") {
                continue;
            }
            if line.contains(FORBIDDEN) {
                hits.push(format!("{}:{}", source.display(), number + 1));
            }
        }
    }

    assert!(
        hits.is_empty(),
        "R-470 violé — le pool global de rayon est installé :\n  {}",
        hits.join("\n  ")
    );
}
