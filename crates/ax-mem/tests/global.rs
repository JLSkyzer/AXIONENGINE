//! L'allocateur compteur **installé** comme allocateur global (INV-08, ADR-133 D2) :
//! ce que la bibliothèque standard alloue — croissance d'un `Vec` par `realloc`,
//! `Box`, `String`, table de hachage, threads — est compté, puis décompté.
//!
//! Le harnais de test alloue aussi, sur ses propres threads, et les tests tournent
//! en parallèle : chacun mesure dans une arène à lui — `FRAME`, `DEFORM`, `SCRATCH` —,
//! que rien d'autre n'emploie dans ce binaire.

use ax_mem::{scope, ArenaClass, CountingAllocator};
use std::collections::HashMap;

#[global_allocator]
static ALLOCATOR: CountingAllocator = CountingAllocator::new();

fn frame() -> usize {
    ALLOCATOR.live_bytes_in(ArenaClass::Frame)
}

fn deform() -> usize {
    ALLOCATOR.live_bytes_in(ArenaClass::Deform)
}

fn scratch() -> usize {
    ALLOCATOR.live_bytes_in(ArenaClass::Scratch)
}

/// Libère un `Box` reçu par valeur, avant de rendre la main.
fn consume(boxed: Box<[u8; 256]>) -> u8 {
    let last = boxed[255];
    drop(boxed);
    last
}

#[test]
fn t016_un_box_libere_par_la_fonction_qui_le_recoit_est_decompte() {
    // Un `Box` reçu par valeur reste protégé jusqu'à la fin de la fonction qui le
    // libère : sous Miri (Stacked Borrows), la libération ne doit pas lui retirer
    // ses droits. Deux formes : une fonction qui le consomme, et un
    // `Box<dyn FnOnce>` qui se libère dans son propre appel.
    let before = scratch();
    {
        let _scratch = scope(ArenaClass::Scratch);
        assert_eq!(consume(Box::new([9u8; 256])), 9);
        let captured = "x".repeat(64);
        let task: Box<dyn FnOnce() -> usize> = Box::new(move || captured.len());
        assert_eq!(task(), 64);
    }
    assert_eq!(scratch(), before, "les deux Box sont rendus");
}

#[test]
fn t016_ce_que_la_bibliotheque_standard_alloue_est_compte_puis_rendu() {
    let before = frame();
    {
        let _frame = scope(ArenaClass::Frame);
        let mut grown: Vec<u64> = Vec::new();
        for i in 0..100_000u64 {
            grown.push(i);
        }
        assert!(
            frame() - before >= 100_000 * 8,
            "la croissance par realloc est comptée : {} octets",
            frame() - before
        );
        let boxed = Box::new([7u8; 4096]);
        let text: String = (0..1000)
            .map(|i| char::from(b'a' + (i % 26) as u8))
            .collect();
        let mut table = HashMap::new();
        for i in 0..1000 {
            table.insert(i, format!("valeur {i}"));
        }
        assert!(boxed[4095] == 7 && text.len() == 1000 && table.len() == 1000);
        drop(grown);
        drop(boxed);
        drop(text);
        drop(table);
    }
    assert_eq!(frame(), before, "tout ce qui a été alloué est rendu");
}

#[test]
fn t016_des_threads_qui_allouent_et_liberent_en_croise_laissent_un_bilan_nul() {
    let before = deform();
    // Chaque thread alloue en DEFORM, et la moitié des blocs est libérée par le
    // thread principal : la libération décompte l'arène d'origine, quel qu'en soit
    // le thread.
    let handed: Vec<Vec<Vec<u8>>> = std::thread::scope(|threads| {
        let workers: Vec<_> = (0..8)
            .map(|worker| {
                threads.spawn(move || {
                    let _frame = scope(ArenaClass::Deform);
                    let mut kept = Vec::new();
                    for i in 0..2000 {
                        let block = vec![worker as u8; 16 + (i % 512)];
                        if i % 2 == 0 {
                            kept.push(block);
                        }
                    }
                    kept
                })
            })
            .collect();
        workers
            .into_iter()
            .map(|worker| worker.join().expect("le worker se termine"))
            .collect()
    });
    assert!(deform() > before, "les blocs gardés sont encore comptés");
    drop(handed);
    assert_eq!(
        deform(),
        before,
        "libérés ailleurs, ils sont décomptés de leur arène"
    );
}
