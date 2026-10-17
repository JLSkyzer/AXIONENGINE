//! T-015 — INV-07 : aucun thread worker Rust n'attache de `JNIEnv` (R-032).
//!
//! Un thread natif n'atteint la JVM que de deux façons. Il reçoit un `JNIEnv` d'un thread
//! Java : le type l'interdit, `JNIEnv` n'est pas `Send` — ce que la compilation de ce test
//! vérifie —, une tâche du pool ne peut donc pas l'emporter. Ou il s'attache lui-même par un
//! `JavaVM` : il faudrait l'avoir gardé, ou le retrouver par `JNI_GetCreatedJavaVMs`, ce que les
//! sources livrées ne font nulle part — ce que ce test balaie.
//!
//! La JVM n'est connue que du pont JNI de C-14, qui en reçoit la machine dans `JNI_OnLoad`, le
//! temps d'enregistrer ses méthodes, sans la garder.

use std::fs;
use std::path::{Path, PathBuf};

/// Appels par lesquels un thread natif atteint la JVM, ou en retrouve la machine.
const ATTACHES: [&str; 5] = [
    "attach_current_thread",
    "AttachCurrentThread",
    "get_java_vm",
    "GetJavaVM",
    "GetCreatedJavaVMs",
];

/// Le seul fichier qui nomme la JVM.
const PONT: &str = "ax-ffi/src/jni_bridge.rs";

/// Le répertoire `crates/`, parent de cette crate.
fn crates() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("ax-ffi vit dans crates/")
        .to_path_buf()
}

fn rust_sous(dir: &Path, out: &mut Vec<PathBuf>) {
    for entry in fs::read_dir(dir).expect("répertoire lisible") {
        let path = entry.expect("entrée lisible").path();
        if path.is_dir() {
            rust_sous(&path, out);
        } else if path.extension().is_some_and(|ext| ext == "rs") {
            out.push(path);
        }
    }
}

/// Les sources livrées de chaque crate : `src/`, hors tests, benchmarks et exemples. Chaque
/// chemin est rendu relatif à `crates/`, en `/`.
fn sources_livrees() -> Vec<(String, String)> {
    let root = crates();
    let mut files = Vec::new();
    for entry in fs::read_dir(&root).expect("crates/ lisible") {
        let src = entry.expect("entrée lisible").path().join("src");
        if src.is_dir() {
            rust_sous(&src, &mut files);
        }
    }
    files.sort();
    let sources: Vec<(String, String)> = files
        .into_iter()
        .map(|path| {
            let relative = path
                .strip_prefix(&root)
                .expect("sous crates/")
                .to_string_lossy()
                .replace('\\', "/");
            (relative, fs::read_to_string(&path).expect("source UTF-8"))
        })
        .collect();
    // Un balayage vide passerait sans rien prouver : le pool et le pont doivent en être.
    for attendu in ["ax-jobs/src/lib.rs", PONT] {
        assert!(
            sources.iter().any(|(path, _)| path == attendu),
            "{attendu} non balayé"
        );
    }
    sources
}

#[test]
fn t015_un_jnienv_ne_passe_pas_a_un_autre_thread() {
    // Si `JNIEnv` était `Send`, les deux implémentations s'appliqueraient et l'appel serait
    // ambigu : ce test ne compilerait plus.
    trait AmbiguSiSend<A> {
        fn verifie() {}
    }
    impl<T: ?Sized> AmbiguSiSend<()> for T {}
    impl<T: ?Sized + Send> AmbiguSiSend<u8> for T {}
    <jni::JNIEnv<'static> as AmbiguSiSend<_>>::verifie();
}

#[test]
fn t015_aucun_thread_natif_ne_s_attache_a_la_jvm() {
    let mut hits = Vec::new();
    for (path, text) in sources_livrees() {
        for (index, line) in text.lines().enumerate() {
            if let Some(appel) = ATTACHES.iter().find(|appel| line.contains(*appel)) {
                hits.push(format!("{path}:{} ({appel})", index + 1));
            }
        }
    }
    assert!(
        hits.is_empty(),
        "INV-07 violé — un thread natif atteint la JVM :\n  {}",
        hits.join("\n  ")
    );
}

#[test]
fn t015_la_machine_virtuelle_ne_se_garde_nulle_part() {
    // Hors du pont, la JVM n'a pas de nom. Dans le pont, elle n'est qu'un paramètre : une ligne
    // qui la nomme sans être une signature de fonction l'importerait, la rangerait dans une
    // statique ou un champ, ou la reconstruirait d'un pointeur.
    let mut hits = Vec::new();
    for (path, text) in sources_livrees() {
        for (index, line) in text.lines().enumerate() {
            if !line.contains("JavaVM") {
                continue;
            }
            let admise = path == PONT
                && (line.trim_start().starts_with("use jni::") || line.contains("fn "))
                && !line.contains("from_raw");
            if !admise {
                hits.push(format!("{path}:{}: {}", index + 1, line.trim()));
            }
        }
    }
    assert!(
        hits.is_empty(),
        "INV-07 — la JVM gardée ou nommée hors du pont :\n  {}",
        hits.join("\n  ")
    );
}

#[test]
fn t015_seul_le_pont_depend_de_jni() {
    // Le pool de workers (ax-jobs), la physique, les assets n'ont pas même accès à l'API JNI ;
    // et le pont ne prend pas la fonction `invocation`, qui sait créer ou retrouver une JVM.
    let root = crates();
    let mut manifests = vec![(
        "workspace".to_owned(),
        root.parent().expect("racine").join("Cargo.toml"),
    )];
    for entry in fs::read_dir(&root).expect("crates/ lisible") {
        let dir = entry.expect("entrée lisible").path();
        if dir.join("Cargo.toml").is_file() {
            let name = dir
                .file_name()
                .expect("nom de crate")
                .to_string_lossy()
                .into_owned();
            manifests.push((name, dir.join("Cargo.toml")));
        }
    }
    let mut hits = Vec::new();
    for (name, manifest) in manifests {
        let text = fs::read_to_string(&manifest).expect("manifeste lisible");
        for (index, line) in text.lines().enumerate() {
            let declaration = line.trim_start();
            let jni = declaration.starts_with("jni") && !declaration.starts_with("jni_");
            let admis = name == "ax-ffi" || name == "workspace";
            if (jni && !admis) || declaration.contains("invocation") {
                hits.push(format!("{name}/Cargo.toml:{}: {}", index + 1, line.trim()));
            }
        }
    }
    for (path, text) in sources_livrees() {
        if path != PONT && text.contains("jni::") {
            hits.push(format!("{path} : l'API JNI hors du pont"));
        }
    }
    assert!(
        hits.is_empty(),
        "INV-07 — l'API JNI hors du pont de C-14 :\n  {}",
        hits.join("\n  ")
    );
}
