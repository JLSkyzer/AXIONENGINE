//! `stl` — import d'un maillage STL hostile (T-680).
//!
//! Le STL a deux formes, binaire et ASCII, que le lecteur distingue lui-même à
//! partir des octets. C'est déjà une décision prise sur une entrée non fiable.
//!
//! La forme binaire est la plus exposée : quatre-vingts octets d'en-tête, puis
//! un nombre de triangles sur 32 bits qu'un fichier peut annoncer à quatre
//! milliards alors qu'il n'en porte aucun. Lire ce nombre et allouer en
//! conséquence est la faute classique de tout lecteur de STL.
//!
//! Une erreur n'est pas un défaut : refuser est le travail.

#![no_main]

use ax_asset::import::{import_stl, ImportLimits};
use libfuzzer_sys::fuzz_target;

/// Voir la note de `a3d_reader` : un mébioctet, comme les tests.
const LIMITS: ImportLimits = ImportLimits::new(1 << 20);

fuzz_target!(|data: &[u8]| {
    let _ = import_stl(data, &LIMITS);
});
