//! Rejeu du corpus de fuzzing sur la chaîne épinglée (R-903).
//!
//! `cargo-fuzz` exige une chaîne `nightly` et un désinfecteur : il ne tourne ni
//! sur la chaîne que ce dépôt épingle, ni sur toutes les plateformes de la
//! matrice. Le corpus, lui, est du contenu versionné, et rien n'empêche de le
//! rejouer ici.
//!
//! Ce que ce fichier apporte, et que le fuzzer n'apporte pas :
//!
//! - **Le corpus est exercé à chaque exécution de la CI**, sur les quatre
//!   plateformes, sans nightly. Une entrée qui ferait paniquer un importeur
//!   n'attend pas la prochaine campagne de fuzzing pour se voir.
//! - **Les graines restent des graines.** Une graine qu'un changement de code
//!   ferait refuser d'emblée cesse d'être un point de départ : le fuzzer
//!   repartirait de rien sans que personne ne s'en aperçoive. C'est le défaut
//!   le plus discret d'un corpus versionné, et le seul test qui le voie est
//!   celui qui vérifie qu'au moins une graine par cible est **acceptée**.
//!
//! Chaque cas rejoue exactement ce que fait la cible de fuzzing correspondante,
//! résolveur compris : rejouer autre chose ne dirait rien de ce que le fuzzer
//! rencontre.

use std::path::{Path, PathBuf};

use ax_asset::a3d::{A3dFile, A3dLimits, SectionMask, SectionTag};
use ax_asset::import::{import_gltf, import_obj, import_stl, ImportError, ImportLimits};

/// Les mêmes plafonds que les cibles de fuzzing.
const IMPORT_LIMITS: ImportLimits = ImportLimits::new(1 << 20);
const A3D_LIMITS: A3dLimits = A3dLimits::new(1 << 20);

/// Racine du corpus, relative au crate.
fn corpus(cible: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../fuzz/corpus")
        .join(cible)
}

/// Les fichiers d'une cible, triés — l'ordre d'un répertoire ne l'est pas.
fn graines(cible: &str) -> Vec<PathBuf> {
    let racine = corpus(cible);
    let mut fichiers: Vec<PathBuf> = std::fs::read_dir(&racine)
        .unwrap_or_else(|erreur| panic!("corpus introuvable : {} ({erreur})", racine.display()))
        .filter_map(Result::ok)
        .map(|entree| entree.path())
        .filter(|chemin| chemin.is_file())
        .collect();
    fichiers.sort();

    assert!(
        !fichiers.is_empty(),
        "aucune graine pour la cible « {cible} » : R-903 veut un corpus versionné, \
         et un fuzzer parti de rien passe son temps à réinventer un en-tête"
    );
    fichiers
}

/// Refuse une panique retenue par le filet du point de delegation.
///
/// `import_*` attrape les paniques des analyseurs tiers et les rend sous forme
/// d'erreur, pour qu'un asset soit refuse proprement au lieu de laisser remonter
/// une panique opaque. Ici comme dans les cibles de fuzzing, on veut l'inverse :
/// sans cette verification, le filet rendrait une panique **invisible**, et
/// R-903 exige la tolerance zero.
fn refuse_une_panique<T>(chemin: &Path, resultat: &Result<T, ImportError>) {
    if let Err(ImportError::ParserPanicked { format, detail }) = resultat {
        panic!(
            "{} fait paniquer l'analyseur {format:?} : {detail}",
            chemin.display()
        );
    }
}

fn octets(chemin: &Path) -> Vec<u8> {
    std::fs::read(chemin).unwrap_or_else(|erreur| panic!("{} : {erreur}", chemin.display()))
}

#[test]
fn t681_le_corpus_a3d_se_rejoue_sans_paniquer() {
    let mut acceptees = 0;
    for chemin in graines("a3d_reader") {
        let data = octets(&chemin);

        // Exactement le corps de fuzz_targets/a3d_reader.rs.
        if let Ok(fichier) = A3dFile::open(&data, A3D_LIMITS) {
            acceptees += 1;
            let _ = fichier.load(SectionMask::all());
            for tag in SectionTag::NORMATIVE {
                let _ = fichier.has(tag);
                let _ = fichier.section(tag);
                let _ = fichier.stored_bytes(tag);
            }
        }
    }

    assert!(
        acceptees > 0,
        "aucune graine A3D n'est lisible : le corpus ne fait plus partir le \
         fuzzer d'un conteneur valide"
    );
}

#[test]
fn t680_le_corpus_gltf_se_rejoue_sans_paniquer() {
    let mut acceptees = 0;
    for chemin in graines("gltf") {
        let data = octets(&chemin);
        let resultat = import_gltf(&data, &IMPORT_LIMITS, |_nom| Some(data.clone()));
        refuse_une_panique(&chemin, &resultat);
        if resultat.is_ok() {
            acceptees += 1;
        }
    }

    assert!(
        acceptees > 0,
        "aucune graine glTF n'est importable : le fuzzer repartirait d'un JSON \
         qu'il devrait réinventer"
    );
}

#[test]
fn t680_le_corpus_obj_se_rejoue_sans_paniquer() {
    let mut acceptees = 0;
    for chemin in graines("obj") {
        let data = octets(&chemin);

        // Le plus long préfixe UTF-8 valide, exactement comme la cible : elle
        // reçoit un `&str` construit par `arbitrary_take_rest`, qui tronque à la
        // première séquence invalide plutôt que de refuser l'entrée.
        //
        // Refuser ici une graine non UTF-8 serait plus strict que le fuzzer, et
        // exclurait du corpus des entrées qu'il produit lui-même — la graine de
        // régression `mtl-triplet-incomplet` en est une.
        let texte = match std::str::from_utf8(&data) {
            Ok(texte) => texte.to_string(),
            Err(erreur) => String::from_utf8_lossy(&data[..erreur.valid_up_to()]).into_owned(),
        };
        let resultat = import_obj(&texte, &IMPORT_LIMITS, |_nom| Some(texte.clone()));
        refuse_une_panique(&chemin, &resultat);
        if resultat.is_ok() {
            acceptees += 1;
        }
    }

    assert!(acceptees > 0, "aucune graine OBJ n'est importable");
}

#[test]
fn t680_le_corpus_stl_se_rejoue_sans_paniquer() {
    let mut acceptees = 0;
    for chemin in graines("stl") {
        let resultat = import_stl(&octets(&chemin), &IMPORT_LIMITS);
        refuse_une_panique(&chemin, &resultat);
        if resultat.is_ok() {
            acceptees += 1;
        }
    }

    // Les deux formes du STL — binaire et ASCII — doivent l'être toutes les
    // deux : le lecteur choisit lui-même laquelle il a en main, et ne garder
    // qu'une graine laisserait l'autre décision sans point de départ.
    assert_eq!(
        acceptees, 2,
        "les deux formes du STL doivent être acceptées, {acceptees} l'est"
    );
}

#[test]
fn t680_chaque_cible_de_fuzzing_a_son_corpus() {
    // La réciproque : une cible ajoutée sans corpus est une cible qui part de
    // rien. Le test lit les cibles déclarées dans `fuzz/Cargo.toml` plutôt
    // qu'une liste écrite ici, pour qu'en ajouter une sans graine échoue.
    let manifeste = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../fuzz/Cargo.toml");
    let texte = std::fs::read_to_string(&manifeste)
        .unwrap_or_else(|erreur| panic!("{} : {erreur}", manifeste.display()));

    let cibles: Vec<String> = texte
        .lines()
        .filter_map(|ligne| ligne.trim().strip_prefix("name = \""))
        .filter_map(|reste| reste.split('"').next())
        .filter(|nom| *nom != "axion-fuzz")
        .map(str::to_string)
        .collect();

    assert!(
        cibles.len() >= 4,
        "seulement {} cible(s) trouvée(s) dans fuzz/Cargo.toml : {cibles:?}",
        cibles.len()
    );

    for cible in &cibles {
        let graines = graines(cible);
        assert!(
            !graines.is_empty(),
            "la cible « {cible} » n'a aucune graine"
        );
    }
}
