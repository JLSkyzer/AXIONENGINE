//! `gltf` — import d'un document glTF 2.0 ou GLB hostile (T-680).
//!
//! L'importeur reçoit un fichier qu'un créateur de pack a produit, souvent avec
//! un exportateur qu'AXION ne connaît pas. Il en lit du JSON, des décalages de
//! `bufferView`, des `uri` — et R-530 lui demande de refuser ce qu'il ne
//! modélise pas plutôt que de l'interpréter de travers.
//!
//! Une erreur n'est pas un défaut : refuser est le travail. Le défaut serait de
//! paniquer, de suivre un `uri` sortant, ou d'allouer d'après une longueur
//! annoncée sans la vérifier.

#![no_main]

use ax_asset::import::{import_gltf, ImportLimits};
use libfuzzer_sys::fuzz_target;

/// Voir la note de `a3d_reader` : un mébioctet, comme les tests.
const LIMITS: ImportLimits = ImportLimits::new(1 << 20);

// Le filet de `catch_parser_panic` n'aveugle pas cette cible, et il n'y a rien a
// faire pour cela : `libfuzzer-sys` installe un hook de panique qui **avorte le
// processus avant le deroulement de pile**, precisement pour que le fuzzer
// puisse lire les cadres. `catch_unwind` n'a donc jamais la main ici, et une
// panique d'analyseur reste vue comme un crash.
//
// Constate le 2026-09-11 : une panique de `gltf-json` a atteint libFuzzer alors
// que l'importeur etait deja enveloppe.
fuzz_target!(|data: &[u8]| {
    // L'entrée est prise **brute**, et non décodée en structure par
    // `arbitrary`. La raison est le corpus : R-903 le veut versionné, et un
    // `.gltf` déposé dans `corpus/gltf/` doit être une graine telle quelle. Une
    // entrée structurée à deux champs lirait ses longueurs depuis la fin du
    // tampon, si bien qu'un vrai fichier n'y désignerait plus un document.
    //
    // Le résolveur rend donc le document lui-même. C'est du bruit pour un
    // `.bin` externe, et c'est exactement ce qu'on veut savoir : l'importeur
    // vérifie-t-il la longueur qu'il a reçue contre celle que le JSON annonce,
    // ou la croit-il sur parole ? Le cas de la ressource **absente** est, lui,
    // couvert par les tests d'intégration (T-228).
    let _ = import_gltf(data, &LIMITS, |_nom| Some(data.to_vec()));
});
