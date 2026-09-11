//! `obj` — import d'un maillage Wavefront OBJ hostile (T-680).
//!
//! L'OBJ est du texte, ce qui change la nature des défauts qu'on y cherche : pas
//! de décalage menteur, mais des nombres démesurés, des indices négatifs — la
//! spécification les autorise, comptés depuis la fin —, des lignes sans fin, et
//! un `mtllib` qui désigne un chemin sortant.
//!
//! Une erreur n'est pas un défaut. Le défaut serait de paniquer sur un indice,
//! de tourner sans rendre la main, ou de suivre un `mtllib` hors du pack.

#![no_main]

use ax_asset::import::{import_obj, ImportLimits};
use libfuzzer_sys::fuzz_target;

/// Voir la note de `a3d_reader` : un mébioctet, comme les tests.
const LIMITS: ImportLimits = ImportLimits::new(1 << 20);

fuzz_target!(|data: &str| {
    // `&str` plutôt que `&[u8]` : l'entrée est prise en entier comme texte, ce
    // qui fait d'un `.obj` déposé dans `corpus/obj/` une graine telle quelle.
    // Passer par `&[u8]` obligerait à valider l'UTF-8 ici, et le fuzzer perdrait
    // l'essentiel de son temps à produire des suites qu'on rejetterait aussitôt.
    //
    // Le résolveur rend le maillage comme bibliothèque de matériaux : un `.mtl`
    // syntaxiquement absurde est précisément ce qu'on veut lui donner, et un
    // pack qui porte l'un porte l'autre, de la même main.
    let _ = import_obj(data, &LIMITS, |_nom| Some(data.to_string()));
});
