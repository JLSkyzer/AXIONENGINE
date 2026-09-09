//! T-152 — unicité du contexte natif dans le processus (R-450, `E-1004`).
//!
//! Ce fichier ne contient **qu'un seul** test, et c'est délibéré. Le jeton de
//! contexte est un état global au processus : deux tests qui le prendraient en
//! parallèle mesureraient les effets l'un de l'autre, et l'échec ressemblerait
//! à un défaut du code testé. Chaque fichier de `tests/` étant compilé en
//! binaire séparé, un test unique ici garantit l'isolation.

use ax_core::{ContextGuard, CoreError};

#[test]
fn le_contexte_est_unique_par_processus() {
    assert!(!ContextGuard::is_held(), "jeton déjà pris au démarrage");

    let contexte = ContextGuard::acquire().expect("première acquisition refusée");
    assert!(ContextGuard::is_held());

    // Une seconde initialisation est refusée, avec le code de l'ANNEXE A.1.
    let refus = ContextGuard::acquire().unwrap_err();
    assert_eq!(refus, CoreError::AlreadyInitialized);
    assert_eq!(refus.code(), -1004);

    // Un arrêt propre rouvre la possibilité de réinitialiser : c'est le cas
    // d'un serveur qui recharge le mod.
    drop(contexte);
    assert!(!ContextGuard::is_held());

    let reprise = ContextGuard::acquire().expect("réinitialisation après arrêt refusée");
    drop(reprise);
}
