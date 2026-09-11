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

use ax_asset::import::{ImportError, import_gltf, ImportLimits};
use libfuzzer_sys::fuzz_target;

/// Voir la note de `a3d_reader` : un mébioctet, comme les tests.
const LIMITS: ImportLimits = ImportLimits::new(1 << 20);

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
    let resultat = import_gltf(data, &LIMITS, |_nom| Some(data.to_vec()));
    refuse_une_panique(&resultat);
});

/// Fait echouer la cible sur une panique retenue par le filet.
///
/// `import_*` attrape les paniques des analyseurs tiers et les rend sous la
/// forme d'une erreur : en production, l'asset est refuse proprement au lieu de
/// remonter une panique opaque. Ici, c'est l'inverse qu'on veut — sans cette
/// verification, le filet rendrait ces paniques **invisibles au fuzzer**, et
/// R-903, qui exige la tolerance zero, n'aurait plus aucun moyen de les
/// constater.
fn refuse_une_panique<T>(resultat: &Result<T, ImportError>) {
    if let Err(ImportError::ParserPanicked { format, detail }) = resultat {
        panic!("l'analyseur {format:?} a panique : {detail}");
    }
}
