//! T-820 — R-513 : rejeu des vecteurs d'or du noyau déterministe.
//!
//! Le fichier `tests/golden/kernel-v{N}.txt` fige dix mille cas d'entrée-sortie.
//! Ce test les rejoue et compare bit à bit. Rejoué sur chaque configuration de
//! la matrice de validation (5.12bis), il constate qu'elles calculent toutes la
//! même chose — c'est le seul moyen de voir une divergence **avant** qu'un
//! joueur ne la voie, et une divergence sur une configuration de la matrice est
//! un défaut bloquant.
//!
//! Il ne se contente pas d'échouer : il dit **quels** cas ont bougé, avec leurs
//! entrées, leur valeur figée et leur valeur obtenue. Dix mille échecs sans nom
//! n'apprendraient rien ; cinq cas nommés désignent la fonction fautive.

#[path = "support/cases.rs"]
mod cases;

use std::path::PathBuf;

/// Nombre de divergences détaillées avant de s'en tenir au décompte.
const DIVERGENCES_DETAILLEES: usize = 8;

/// En-tête analysé du fichier.
struct Entete {
    kernel_version: u32,
    cases: usize,
    digest: u64,
}

/// Chemin du fichier de vecteurs d'or de la version courante du noyau.
fn chemin() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests")
        .join("golden")
        .join(format!("kernel-v{}.txt", ax_det::DET_KERNEL_VERSION))
}

/// Lit le fichier, ou explique ce qu'il faut faire s'il manque.
fn lire() -> String {
    let chemin = chemin();
    std::fs::read_to_string(&chemin).unwrap_or_else(|erreur| {
        panic!(
            "vecteurs d'or introuvables pour DET_KERNEL_VERSION = {} ({}) : {erreur}\n\
             \n\
             Si la version du noyau vient d'être incrémentée, les vecteurs de la \
             nouvelle version restent à engendrer :\n\
             \n    cargo run -p ax-det --example generate_golden\n\
             \n\
             Ne le faire qu'après avoir constaté que le changement de sortie est \
             voulu. Régénérer un fichier pour faire taire un échec revient à \
             effacer la seule trace d'une divergence.",
            ax_det::DET_KERNEL_VERSION,
            chemin.display()
        )
    })
}

/// Extrait une valeur d'en-tête, déclarée `# clé: valeur`.
fn champ<'a>(contenu: &'a str, cle: &str) -> &'a str {
    let prefixe = format!("# {cle}:");
    contenu
        .lines()
        .find_map(|ligne| ligne.strip_prefix(&prefixe))
        .unwrap_or_else(|| panic!("en-tête sans champ « {cle} »"))
        .trim()
}

fn entete(contenu: &str) -> Entete {
    let digest = champ(contenu, "digest");
    let digest = digest
        .strip_prefix("0x")
        .unwrap_or_else(|| panic!("empreinte sans préfixe hexadécimal : {digest:?}"));
    Entete {
        kernel_version: champ(contenu, "kernel-version")
            .parse()
            .expect("version de noyau illisible"),
        cases: champ(contenu, "cases").parse().expect("décompte illisible"),
        digest: u64::from_str_radix(digest, 16).expect("empreinte illisible"),
    }
}

/// Les lignes de cas, en-tête et lignes vides écartés.
fn lignes_de_cas(contenu: &str) -> Vec<String> {
    contenu
        .lines()
        .filter(|ligne| !ligne.starts_with('#') && !ligne.trim().is_empty())
        .map(str::to_string)
        .collect()
}

#[test]
fn t820_le_fichier_est_celui_de_la_version_courante_du_noyau() {
    let contenu = lire();
    let entete = entete(&contenu);
    assert_eq!(
        entete.kernel_version,
        ax_det::DET_KERNEL_VERSION,
        "le fichier dit figer la version {} alors que le noyau est en version {} : \
         il a été renommé sans être régénéré, et il ne fige plus rien",
        entete.kernel_version,
        ax_det::DET_KERNEL_VERSION
    );
}

#[test]
fn t820_le_fichier_compte_les_dix_mille_cas_de_r513() {
    let contenu = lire();
    let entete = entete(&contenu);
    let lignes = lignes_de_cas(&contenu);

    assert_eq!(
        lignes.len(),
        10_000,
        "R-513 impose dix mille cas ; le fichier en porte {}",
        lignes.len()
    );
    assert_eq!(
        entete.cases,
        lignes.len(),
        "l'en-tête annonce {} cas et le fichier en porte {}",
        entete.cases,
        lignes.len()
    );
}

#[test]
fn t820_l_empreinte_declaree_est_celle_du_contenu() {
    // Une empreinte unique, citable d'une machine à l'autre : constater que
    // deux configurations de la matrice s'accordent ne demande pas de comparer
    // dix mille lignes.
    let contenu = lire();
    let entete = entete(&contenu);
    let obtenue = cases::digest_lines(&lignes_de_cas(&contenu));

    assert_eq!(
        obtenue, entete.digest,
        "le contenu du fichier ne correspond plus à l'empreinte qu'il déclare \
         (0x{obtenue:016X} contre 0x{:016X}) : il a été modifié à la main, ou \
         tronqué",
        entete.digest
    );
}

#[test]
fn t820_toutes_les_operations_du_noyau_sont_couvertes() {
    // Une opération publique absente du fichier serait libre de diverger sans
    // que rien ne le dise. Le test lit la table des opérations plutôt qu'une
    // liste écrite ici : ajouter une opération sans l'échantillonner devient
    // impossible.
    let contenu = lire();
    let lignes = lignes_de_cas(&contenu);

    for (op, _) in cases::OPERATIONS {
        let compte = lignes
            .iter()
            .filter(|ligne| ligne.split('\t').next() == Some(op))
            .count();
        assert!(compte > 0, "aucun cas pour l'opération « {op} »");
    }

    // Et réciproquement : une opération du fichier qui ne serait plus déclarée
    // signalerait un fichier d'une autre version que le code.
    for ligne in &lignes {
        let op = ligne.split('\t').next().unwrap_or_default();
        assert!(
            cases::arity(op).is_some(),
            "le fichier porte l'opération inconnue « {op} »"
        );
    }
}

#[test]
fn t820_le_noyau_rend_les_valeurs_figees() {
    let contenu = lire();
    let lignes = lignes_de_cas(&contenu);

    let mut divergences: Vec<String> = Vec::new();
    let mut total = 0usize;

    for (index, ligne) in lignes.iter().enumerate() {
        let champs: Vec<&str> = ligne.split('\t').collect();
        assert!(
            champs.len() >= 2,
            "ligne {} mal formée : {ligne:?}",
            index + 1
        );

        let op = champs[0];
        let attendu = champs[champs.len() - 1];
        let args = &champs[1..champs.len() - 1];
        let obtenu = cases::evaluate(op, args);

        if obtenu != attendu {
            total += 1;
            if divergences.len() < DIVERGENCES_DETAILLEES {
                divergences.push(format!(
                    "  ligne {} — {op}({}) : figé {attendu}, obtenu {obtenu}",
                    index + 1,
                    args.join(", ")
                ));
            }
        }
    }

    if total == 0 {
        return;
    }

    // Le message distingue les deux situations, parce qu'elles n'appellent pas
    // la même réaction. Dans la matrice, c'est un défaut bloquant. Hors matrice,
    // c'est le comportement prévu par R-514 — la réplication bascule en
    // SNAPSHOT et rien n'est perdu —, et chercher un défaut serait chercher ce
    // qui n'existe pas.
    let diagnostic = if ax_det::is_in_validation_matrix() {
        "Cette configuration est dans la matrice de validation (5.12bis) : une \
         divergence y est un défaut BLOQUANT. Le noyau ne calcule plus ce qu'il \
         calculait, et deux extrémités de versions différentes ne s'en \
         apercevraient pas."
    } else {
        "Cette configuration est HORS de la matrice de validation (5.12bis). \
         R-514 le prévoit : la réplication y bascule en SNAPSHOT et aucune \
         fonctionnalité n'est retirée. La divergence ci-dessous n'est donc pas \
         nécessairement un défaut — mais les vecteurs d'or ne valent rien ici, \
         et seule une machine de la matrice peut trancher."
    };

    panic!(
        "{total} cas sur {} divergent des vecteurs d'or.\n\n{}\n\n{diagnostic}\n\n\
         Configuration : {}",
        lignes.len(),
        divergences.join("\n"),
        ax_det::det_profile_string()
    );
}
