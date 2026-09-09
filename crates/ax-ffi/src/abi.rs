//! IF-01 — points d'entrée de l'ABI native.
//!
//! Chaque fonction exportée suit la même forme, imposée par R-490 :
//! `#[no_mangle] extern "C"`, un `catch_unwind`, la validation de **tous** ses
//! arguments, l'appel au crate métier, puis la conversion du résultat en `i32`.
//! Aucune logique de moteur ne vit ici.
//!
//! Conventions de l'ABI :
//!
//! - toute fonction renvoie un `i32` : `0` en succès, sinon un code négatif de
//!   l'ANNEXE A.1 ;
//! - le contexte est un `u64` opaque, jamais un pointeur (R-264) ;
//! - les chaînes sont en UTF-8 avec longueur explicite, jamais terminées par
//!   zéro (R-263) ;
//! - aucune structure `repr(Rust)` ne traverse (R-262) ;
//! - la donnée de masse passe par les tampons de IF-02, pas par les paramètres.

// Ce module est la frontière elle-même : y interdire `unsafe` reviendrait à
// interdire la frontière. Le lint reste `deny` pour tout le reste du crate, et
// chaque bloc ci-dessous porte sa justification `// SAFETY:`.
#![allow(unsafe_code)]

use std::panic::{catch_unwind, AssertUnwindSafe};

use ax_model::buffer::BufferKind;
use ax_model::config::{self, ConfigScope, ParsedValue};

use crate::context;

/// Version de l'ABI.
///
/// Versionnée indépendamment du produit (R-261) : toute rupture de
/// compatibilité binaire l'incrémente. Java la compare à la sienne avant tout
/// autre appel et bascule en `DISABLED` sur écart (R-260, `E-1002`).
pub const AXION_ABI_VERSION: u32 = 1;

/// Succès.
pub const AXION_OK: i32 = 0;

/// Panic capturée à la frontière (`E-2000`).
pub const AXION_E_PANIC: i32 = -2000;

/// Contexte ou handle invalide (`E-2001`).
pub const AXION_E_INVALID_HANDLE: i32 = -2001;

/// Tampon ou pointeur invalide (`E-2002`).
pub const AXION_E_INVALID_BUFFER: i32 = -2002;

/// Configuration refusée (`E-1006`).
pub const AXION_E_CONFIG: i32 = -1006;

/// Exécute `action` en interceptant toute panic (R-310, INV-05).
///
/// Une panic est journalisée, comptée, et empoisonne le contexte quand il y en
/// a un : on ne sait pas où le travail s'est interrompu, et supposer l'état
/// restaurable serait le contraire de l'option conservatrice. `AXION_E_PANIC`
/// est renvoyé à Java, qui bascule en `DISABLED`.
///
/// `panic = "abort"` est interdit dans ce crate (R-312) : il ferait mourir le
/// processus avant toute capture.
fn shielded(token: Option<u64>, action: impl FnOnce() -> i32) -> i32 {
    match catch_unwind(AssertUnwindSafe(action)) {
        Ok(code) => code,
        Err(payload) => {
            let message = describe_panic(payload.as_ref());
            if let Some(token) = token {
                // L'accès est autorisé même si le contexte est déjà empoisonné :
                // il s'agit précisément de l'empoisonner.
                let _ = context::with(token, true, |session| session.record_panic(&message));
            }
            AXION_E_PANIC
        }
    }
}

/// Extrait un message lisible de la charge utile d'une panic.
fn describe_panic(payload: &(dyn std::any::Any + Send)) -> String {
    if let Some(message) = payload.downcast_ref::<&'static str>() {
        format!("panic native : {message}")
    } else if let Some(message) = payload.downcast_ref::<String>() {
        format!("panic native : {message}")
    } else {
        "panic native sans message".to_owned()
    }
}

/// Renvoie la version de l'ABI de cette bibliothèque.
///
/// À appeler **avant toute autre fonction** (R-260). Ne dépend d'aucun
/// contexte : c'est ce qui permet de la comparer avant d'initialiser quoi que
/// ce soit.
#[no_mangle]
pub extern "C" fn axion_abi_version() -> i32 {
    // Le transtypage est sûr : la version reste très en deçà de i32::MAX, et
    // une valeur négative serait indiscernable d'un code d'erreur.
    AXION_ABI_VERSION as i32
}

/// Initialise le contexte natif.
///
/// # Arguments
///
/// - `config_cbor` : configuration encodée en CBOR, une map de chemins d'option
///   vers leurs valeurs. Peut être nul si `len` vaut 0.
/// - `len` : longueur de `config_cbor`, en octets.
/// - `out_ctx` : reçoit le jeton de contexte en cas de succès.
///
/// # Safety
///
/// `config_cbor` doit pointer sur `len` octets lisibles, et `out_ctx` sur un
/// `u64` accessible en écriture. Les deux sont validés dans la mesure du
/// possible — un pointeur nul est refusé —, mais aucune API ne permet de
/// vérifier qu'un pointeur non nul est valide.
#[no_mangle]
pub unsafe extern "C" fn axion_init(config_cbor: *const u8, len: usize, out_ctx: *mut u64) -> i32 {
    shielded(None, || {
        if out_ctx.is_null() {
            return AXION_E_INVALID_BUFFER;
        }
        if config_cbor.is_null() && len != 0 {
            return AXION_E_INVALID_BUFFER;
        }

        let config = if len == 0 {
            &[][..]
        } else {
            // SAFETY: le contrat de la fonction impose `len` octets lisibles à
            // partir de `config_cbor`, et la nullité vient d'être écartée.
            unsafe { std::slice::from_raw_parts(config_cbor, len) }
        };

        let token = match context::open() {
            Ok(token) => token,
            Err(code) => return code,
        };

        if let Err(message) = apply_config(config) {
            // La configuration est refusée : la session est refermée pour ne
            // pas laisser un contexte à moitié initialisé derrière soi.
            let _ = context::with(token, true, |session| session.set_last_error(&message));
            let _ = context::close(token);
            return AXION_E_CONFIG;
        }

        // SAFETY: `out_ctx` est non nul, et le contrat impose qu'il soit
        // accessible en écriture.
        unsafe { out_ctx.write(token) };
        AXION_OK
    })
}

/// Ferme le contexte natif.
///
/// Reste appelable sur un contexte empoisonné : c'est l'une des deux seules
/// fonctions que R-311 y autorise, sans quoi rien ne pourrait plus être libéré.
///
/// # Safety
///
/// Aucun paramètre n'est déréférencé ; la fonction est `unsafe` par symétrie
/// avec le reste de l'ABI et parce qu'elle invalide un jeton encore détenu par
/// l'appelant.
#[no_mangle]
pub unsafe extern "C" fn axion_shutdown(ctx: u64) -> i32 {
    shielded(Some(ctx), || match context::close(ctx) {
        Ok(()) => AXION_OK,
        Err(code) => code,
    })
}

/// Copie le dernier message d'erreur du contexte.
///
/// Le message est en UTF-8 **sans zéro terminal** (R-263) ; `out_len` reçoit le
/// nombre d'octets écrits. Si la capacité ne suffit pas, le message est tronqué
/// sur une frontière de caractère : Java reçoit toujours de l'UTF-8 valide,
/// quitte à ce qu'il soit incomplet. Un message de diagnostic tronqué reste
/// exploitable ; un message invalide ne l'est pas.
///
/// Reste appelable sur un contexte empoisonné (R-311) — c'est même là qu'elle
/// sert le plus.
///
/// # Safety
///
/// `out_utf8` doit pointer sur `cap` octets accessibles en écriture, et
/// `out_len` sur un `usize` accessible en écriture.
#[no_mangle]
pub unsafe extern "C" fn axion_last_error(
    ctx: u64,
    out_utf8: *mut u8,
    cap: usize,
    out_len: *mut usize,
) -> i32 {
    shielded(Some(ctx), || {
        if out_len.is_null() || (out_utf8.is_null() && cap != 0) {
            return AXION_E_INVALID_BUFFER;
        }

        let copied = context::with(ctx, true, |session| {
            let message = session.last_error().unwrap_or("");
            let end = utf8_boundary(message, cap);
            let bytes = &message.as_bytes()[..end];
            if !bytes.is_empty() {
                // SAFETY: `out_utf8` est non nul dès que `cap` l'est, et
                // `bytes` ne dépasse pas `cap` par construction.
                unsafe { std::ptr::copy_nonoverlapping(bytes.as_ptr(), out_utf8, bytes.len()) };
            }
            bytes.len()
        });

        match copied {
            Ok(written) => {
                // SAFETY: nullité écartée ci-dessus.
                unsafe { out_len.write(written) };
                AXION_OK
            }
            Err(code) => code,
        }
    })
}

/// Description d'un tampon telle qu'elle traverse la frontière (IF-02).
///
/// `repr(C)` est obligatoire : R-262 interdit qu'une structure `repr(Rust)`
/// traverse, sa disposition n'étant garantie par rien.
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct AxionBufferInfo {
    /// Adresse du premier octet du tampon.
    pub ptr: *mut u8,
    /// Capacité utilisable, en octets.
    pub capacity: u64,
    /// Nature du tampon.
    pub kind: u32,
    /// Génération : Java ré-acquiert dès qu'elle change (R-270).
    pub generation: u32,
}

/// Acquiert un tampon de transfert.
///
/// `min_capacity` s'entend en octets de charge utile : la place de l'en-tête
/// est réservée en plus. Le tampon existant est réutilisé s'il suffit ; sinon
/// il est remplacé et la génération avance, ce qui invalide la vue que Java
/// détenait (R-270).
///
/// # Safety
///
/// `out` doit pointer sur un [`AxionBufferInfo`] accessible en écriture.
#[no_mangle]
pub unsafe extern "C" fn axion_buffer_acquire(
    ctx: u64,
    kind: u32,
    min_capacity: u64,
    out: *mut AxionBufferInfo,
) -> i32 {
    shielded(Some(ctx), || {
        if out.is_null() {
            return AXION_E_INVALID_BUFFER;
        }
        // Un kind venu de Java est une donnée externe : il est traduit, jamais
        // utilisé comme indice tel quel (interdiction 3.13).
        let Some(kind) = BufferKind::from_u32(kind) else {
            return AXION_E_INVALID_BUFFER;
        };

        let info = context::with(ctx, false, |session| {
            session.buffers().acquire(kind, min_capacity)
        });
        match info {
            Ok(info) => {
                // SAFETY: `out` est non nul, et le contrat impose qu'il soit
                // accessible en écriture.
                unsafe {
                    out.write(AxionBufferInfo {
                        ptr: info.ptr,
                        capacity: info.capacity,
                        kind: info.kind,
                        generation: info.generation,
                    });
                }
                AXION_OK
            }
            Err(code) => code,
        }
    })
}

/// Libère un tampon de transfert.
///
/// La génération doit être celle du tampon courant : une génération périmée
/// signale que l'appelant raisonne sur un tampon qui n'existe plus, et l'appel
/// est refusé avec `E-2002` plutôt que de libérer le mauvais (R-270).
///
/// # Safety
///
/// Aucun paramètre n'est déréférencé ; la fonction est `unsafe` par symétrie
/// avec le reste de l'ABI.
#[no_mangle]
pub unsafe extern "C" fn axion_buffer_release(ctx: u64, kind: u32, generation: u32) -> i32 {
    shielded(Some(ctx), || {
        let Some(kind) = BufferKind::from_u32(kind) else {
            return AXION_E_INVALID_BUFFER;
        };
        match context::with(ctx, false, |session| {
            session.buffers().release(kind, generation)
        }) {
            Ok(true) => AXION_OK,
            Ok(false) => AXION_E_INVALID_BUFFER,
            Err(code) => code,
        }
    })
}

/// Plus grand indice `<= cap` qui tombe sur une frontière de caractère UTF-8.
fn utf8_boundary(text: &str, cap: usize) -> usize {
    if cap >= text.len() {
        return text.len();
    }
    let mut end = cap;
    while end > 0 && !text.is_char_boundary(end) {
        end -= 1;
    }
    end
}

/// Décode et valide la configuration reçue de Java.
///
/// Le CBOR vient de l'extérieur : rien n'y est cru sur parole (interdiction
/// 3.13). La map doit ne contenir que des chemins déclarés, chacun avec une
/// valeur du bon type et dans son domaine — les mêmes règles que celles
/// appliquées côté Java, vérifiées une seconde fois ici parce que la frontière
/// ne fait pas confiance à son appelant.
fn apply_config(cbor: &[u8]) -> Result<usize, String> {
    if cbor.is_empty() {
        return Ok(0);
    }

    let value: ciborium::Value = ciborium::from_reader(cbor)
        .map_err(|error| format!("configuration CBOR illisible : {error}"))?;

    let entries = value
        .as_map()
        .ok_or_else(|| "configuration CBOR : une map est attendue".to_owned())?;

    let mut accepted = 0usize;
    for (key, raw) in entries {
        let path = key
            .as_text()
            .ok_or_else(|| "configuration CBOR : clé non textuelle".to_owned())?;

        let parsed = to_parsed(raw)
            .ok_or_else(|| format!("configuration CBOR : valeur non supportée pour {path}"))?;

        let option = ConfigScope::ALL
            .into_iter()
            .find_map(|scope| config::find(scope, path))
            .ok_or_else(|| format!("option de configuration inconnue : {path}"))?;

        option
            .validate_parsed(&parsed)
            .map_err(|error| error.to_string())?;
        accepted += 1;
    }
    Ok(accepted)
}

/// Convertit une valeur CBOR en valeur de configuration.
///
/// Seuls les quatre types du modèle sont acceptés ; tout le reste — tableau,
/// map imbriquée, octets, `null` — est refusé plutôt que réinterprété.
fn to_parsed(value: &ciborium::Value) -> Option<ParsedValue> {
    Some(match value {
        ciborium::Value::Bool(v) => ParsedValue::Bool(*v),
        ciborium::Value::Integer(v) => ParsedValue::Int(i128::from(*v).try_into().ok()?),
        ciborium::Value::Float(v) => ParsedValue::Float(*v),
        ciborium::Value::Text(v) => ParsedValue::Str(v.clone()),
        _ => return None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// La version d'ABI est positive : une valeur négative serait indiscernable
    /// d'un code d'erreur.
    #[test]
    fn version_d_abi_positive() {
        assert!(axion_abi_version() > 0);
        assert_eq!(axion_abi_version(), AXION_ABI_VERSION as i32);
    }

    /// La troncature tombe toujours sur une frontière de caractère : un message
    /// coupé au milieu d'un « é » ne serait plus de l'UTF-8 valide.
    #[test]
    fn troncature_sur_frontiere_utf8() {
        let text = "déformation";
        // « é » occupe deux octets : couper à 2 doit reculer à 1.
        assert_eq!(utf8_boundary(text, 2), 1);
        assert_eq!(utf8_boundary(text, 3), 3);
        assert_eq!(utf8_boundary(text, 0), 0);
        assert_eq!(utf8_boundary(text, 999), text.len());

        for cap in 0..=text.len() + 4 {
            let end = utf8_boundary(text, cap);
            assert!(end <= cap.min(text.len()));
            assert!(
                std::str::from_utf8(&text.as_bytes()[..end]).is_ok(),
                "troncature invalide à {cap}"
            );
        }
    }

    /// Une configuration vide est acceptée : tous les défauts s'appliquent.
    #[test]
    fn configuration_vide_acceptee() {
        assert_eq!(apply_config(&[]).unwrap(), 0);
    }

    fn encode(pairs: &[(&str, ciborium::Value)]) -> Vec<u8> {
        let map = ciborium::Value::Map(
            pairs
                .iter()
                .map(|(k, v)| (ciborium::Value::Text((*k).to_owned()), v.clone()))
                .collect(),
        );
        let mut out = Vec::new();
        ciborium::into_writer(&map, &mut out).unwrap();
        out
    }

    /// Une configuration valide est acceptée, toutes portées confondues.
    #[test]
    fn configuration_valide_acceptee() {
        let cbor = encode(&[
            ("general.enabled", ciborium::Value::Bool(false)),
            ("sim.max_substeps", ciborium::Value::Integer(6.into())),
            ("physics.gravity", ciborium::Value::Float(-12.0)),
            (
                "deformation.quality",
                ciborium::Value::Text("high".to_owned()),
            ),
            // Portée client, dans la même map.
            (
                "render.backend",
                ciborium::Value::Text("vanilla".to_owned()),
            ),
        ]);

        assert_eq!(apply_config(&cbor).unwrap(), 5);
    }

    /// Rien de ce qui vient de Java n'est cru sur parole : chemin inconnu,
    /// hors-plage, mauvais type et valeur non représentable sont tous refusés.
    #[test]
    fn configuration_invalide_refusee() {
        let cas: Vec<(&str, Vec<u8>)> = vec![
            (
                "chemin inconnu",
                encode(&[("sim.pas_une_option", ciborium::Value::Integer(1.into()))]),
            ),
            (
                "hors plage",
                encode(&[("sim.max_substeps", ciborium::Value::Integer(99.into()))]),
            ),
            (
                "mauvais type",
                encode(&[("sim.max_substeps", ciborium::Value::Bool(true))]),
            ),
            (
                "type CBOR non supporté",
                encode(&[("sim.max_substeps", ciborium::Value::Null)]),
            ),
            (
                "entier hors des bornes d'un i64",
                encode(&[(
                    "sim.max_substeps",
                    ciborium::Value::Integer(
                        i128::from(i64::MAX).saturating_add(1).try_into().unwrap(),
                    ),
                )]),
            ),
        ];

        for (nom, cbor) in cas {
            assert!(apply_config(&cbor).is_err(), "{nom} : accepté à tort");
        }

        // Une map dont les clés ne sont pas textuelles, et un CBOR qui n'est
        // pas une map du tout.
        let mut mauvaise_cle = Vec::new();
        ciborium::into_writer(
            &ciborium::Value::Map(vec![(
                ciborium::Value::Integer(1.into()),
                ciborium::Value::Bool(true),
            )]),
            &mut mauvaise_cle,
        )
        .unwrap();
        assert!(apply_config(&mauvaise_cle).is_err());

        let mut pas_une_map = Vec::new();
        ciborium::into_writer(&ciborium::Value::Bool(true), &mut pas_une_map).unwrap();
        assert!(apply_config(&pas_une_map).is_err());

        // Des octets qui ne sont pas du CBOR valide.
        assert!(apply_config(&[0xFF, 0xFF, 0xFF]).is_err());
    }

    /// Le bouclier transforme une panic en code d'erreur, sans la laisser
    /// traverser (INV-05).
    #[test]
    fn une_panic_ne_traverse_pas_la_frontiere() {
        let code = shielded(None, || panic!("échec simulé"));
        assert_eq!(code, AXION_E_PANIC);

        // Le cas nominal passe sans surcoût observable.
        assert_eq!(shielded(None, || AXION_OK), AXION_OK);
    }

    /// Un pointeur de sortie nul est refusé, jamais déréférencé.
    #[test]
    fn pointeurs_nuls_refuses() {
        // SAFETY: précisément ce que la fonction doit détecter sans lire.
        unsafe {
            assert_eq!(
                axion_init(std::ptr::null(), 0, std::ptr::null_mut()),
                AXION_E_INVALID_BUFFER
            );
            let mut ctx = 0u64;
            assert_eq!(
                axion_init(std::ptr::null(), 4, &raw mut ctx),
                AXION_E_INVALID_BUFFER
            );
            assert_eq!(
                axion_last_error(0, std::ptr::null_mut(), 8, std::ptr::null_mut()),
                AXION_E_INVALID_BUFFER
            );
        }
    }
}
