//! C-14 — bibliothèque native d'AXION ENGINE.
//!
//! Ce crate est le seul du workspace à produire une bibliothèque dynamique. Le
//! nom `axion_native` est imposé par R-420 : le chargeur Java (C-03) extrait la
//! bibliothèque, vérifie son SHA-256, puis appelle `System.load` sur un chemin
//! absolu. Un nom unique au projet évite toute collision avec la bibliothèque
//! native d'un autre mod.
//!
//! # Ce que ce crate contiendra
//!
//! Les points d'entrée JNI, et eux seuls : R-490 impose que chaque fonction
//! exportée se limite à `#[no_mangle] extern "C"`, un `catch_unwind`, la
//! validation de tous ses arguments, l'appel au crate métier et la conversion
//! du `Result` en `i32` — moins de cinquante lignes. Aucune logique de moteur
//! ne vit ici.
//!
//! Trois contraintes gouvernent cette frontière :
//!
//! - aucune panic Rust ne la traverse (INV-05) ;
//! - toute donnée venant de Java est validée avant usage, `magic`,
//!   `generation`, `schema_version` et `payload_len` compris (R-491) ;
//! - les échanges se font par lots, jamais élément par élément (interdiction
//!   3.8), sous la barre de trente-deux traversées par tick (INV-04).
//!
//! # Organisation
//!
//! - [`abi`] : les points d'entrée exportés, leur validation d'arguments et le
//!   bouclier `catch_unwind` qui empêche toute panic de traverser ;
//! - [`context`] : la session native, son jeton opaque et son état ;
//! - `cpu_clock` : le temps CPU du thread qui fait le pas de simulation, un appel système
//!   par plateforme — ici parce que R-2120 confine `unsafe` à la frontière native.

pub mod abi;
pub mod asset_store;
pub mod context;
mod cpu_clock;
pub mod jni_bridge;

pub use abi::{
    AXION_ABI_VERSION, AXION_E_CONFIG, AXION_E_INVALID_BUFFER, AXION_E_INVALID_HANDLE,
    AXION_E_PANIC, AXION_OK,
};
