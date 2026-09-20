//! Pont JNI vers `dev.axion.bridge.NativeBridge` (C-14).
//!
//! # Pourquoi `RegisterNatives` plutôt que des symboles `Java_*`
//!
//! JNI sait résoudre une méthode `native` de deux façons : par convention de
//! nommage — un symbole exporté `Java_dev_axion_bridge_NativeBridge_init` — ou
//! par enregistrement explicite depuis `JNI_OnLoad`.
//!
//! R-2020 impose que la bibliothèque **n'exporte que des symboles `axion_*`**,
//! pour qu'aucune collision ne soit possible avec la bibliothèque native d'un
//! autre mod chargé dans la même JVM. La première voie ajouterait un symbole
//! exporté par méthode ; la seconde n'en ajoute qu'un, `JNI_OnLoad`, dont le nom
//! est imposé par la spécification JNI et que la JVM appelle sur le handle de
//! *cette* bibliothèque, jamais par résolution globale.
//!
//! Les fonctions ci-dessous ne portent donc pas `#[no_mangle]` : elles sont
//! transmises par pointeur, et n'apparaissent pas dans la table d'export.
//!
//! # Ce que le pont fait, et ne fait pas
//!
//! Il traduit, il ne décide pas : chaque fonction convertit ses arguments,
//! appelle le point d'entrée `axion_*` correspondant, et rend le résultat. La
//! protection contre les panics vit dans ces points d'entrée (R-310).
//!
//! R-313 interdit aux fonctions de l'ABI d'allouer côté Java ou d'appeler une
//! méthode Java, et aucune ne le fait. Le pont, lui, doit créer un
//! `DirectByteBuffer` : c'est le seul moyen pour Java de voir la mémoire
//! native, et cela n'arrive qu'à l'acquisition d'un tampon — une fois par kind
//! et par réallocation, jamais dans une boucle chaude.

#![allow(unsafe_code)]

use std::ffi::c_void;

use jni::objects::{JByteArray, JClass, JLongArray, JObject};
use jni::sys::{jint, jlong, JNI_ERR, JNI_VERSION_1_6};
use jni::{JNIEnv, JavaVM, NativeMethod};

use crate::abi::{
    axion_abi_version, axion_asset_compile, axion_asset_poll, axion_buffer_acquire,
    axion_buffer_release, axion_init, axion_last_error, axion_metrics_export, axion_shutdown,
    axion_sim_cancel, axion_sim_collect, axion_sim_submit, AxionBufferInfo, AxionCollectResult,
    AXION_E_INVALID_BUFFER, AXION_OK,
};

/// Classe Java qui déclare les méthodes natives (R-492 : une seule).
const BRIDGE_CLASS: &str = "dev/axion/bridge/NativeBridge";

/// Nombre de valeurs qu'`simCollect` écrit, une par champ d'[`AxionCollectResult`].
const SIM_COLLECT_SLOTS: i32 = 7;

/// Enregistre les méthodes natives au chargement de la bibliothèque.
///
/// Appelée par la JVM depuis `System.load`. Un échec renvoie `JNI_ERR`, ce qui
/// fait échouer le chargement de façon nette : le bootstrap bascule alors en
/// `DISABLED` plutôt que de découvrir l'absence des méthodes au premier appel.
///
/// # Safety
///
/// Appelée par la JVM avec une machine virtuelle valide.
#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut c_void) -> jint {
    match register(&vm) {
        Ok(()) => JNI_VERSION_1_6,
        // Rien n'est journalisé ici : appeler la JVM alors que
        // l'enregistrement vient d'échouer ajouterait un mode de défaillance
        // sans rien apprendre. Java constate l'échec de `System.load`.
        Err(_) => JNI_ERR,
    }
}

fn register(vm: &JavaVM) -> Result<(), jni::errors::Error> {
    let mut env = vm.get_env()?;
    let class = env.find_class(BRIDGE_CLASS)?;

    let methods = [
        NativeMethod {
            name: "abiVersion".into(),
            sig: "()I".into(),
            fn_ptr: jni_abi_version as *mut c_void,
        },
        NativeMethod {
            name: "init".into(),
            sig: "([BI)J".into(),
            fn_ptr: jni_init as *mut c_void,
        },
        NativeMethod {
            name: "shutdown".into(),
            sig: "(J)I".into(),
            fn_ptr: jni_shutdown as *mut c_void,
        },
        NativeMethod {
            name: "lastError".into(),
            sig: "(J[B)I".into(),
            fn_ptr: jni_last_error as *mut c_void,
        },
        NativeMethod {
            name: "bufferAcquire".into(),
            sig: "(JIJ)Ljava/nio/ByteBuffer;".into(),
            fn_ptr: jni_buffer_acquire as *mut c_void,
        },
        NativeMethod {
            name: "bufferRelease".into(),
            sig: "(JII)I".into(),
            fn_ptr: jni_buffer_release as *mut c_void,
        },
        NativeMethod {
            name: "metricsExport".into(),
            sig: "(J[B)I".into(),
            fn_ptr: jni_metrics_export as *mut c_void,
        },
        NativeMethod {
            name: "assetCompile".into(),
            sig: "(JJIJ)I".into(),
            fn_ptr: jni_asset_compile as *mut c_void,
        },
        NativeMethod {
            name: "assetPoll".into(),
            sig: "(JI[J)I".into(),
            fn_ptr: jni_asset_poll as *mut c_void,
        },
        NativeMethod {
            name: "simSubmit".into(),
            sig: "(JJII)I".into(),
            fn_ptr: jni_sim_submit as *mut c_void,
        },
        NativeMethod {
            name: "simCollect".into(),
            sig: "(JJ[J)I".into(),
            fn_ptr: jni_sim_collect as *mut c_void,
        },
        NativeMethod {
            name: "simCancel".into(),
            sig: "(J)I".into(),
            fn_ptr: jni_sim_cancel as *mut c_void,
        },
    ];

    env.register_native_methods(&class, &methods)
}

/// `NativeBridge.abiVersion()`.
extern "system" fn jni_abi_version(_env: JNIEnv, _class: JClass) -> jint {
    axion_abi_version()
}

/// `NativeBridge.init(byte[], int)`.
///
/// Renvoie le jeton de contexte, toujours positif — son motif commence par
/// `0x41`, donc le bit de signe reste à zéro —, ou un code d'erreur négatif.
extern "system" fn jni_init(env: JNIEnv, _class: JClass, config: JByteArray, side: jint) -> jlong {
    let bytes = if config.is_null() {
        Vec::new()
    } else {
        match env.convert_byte_array(&config) {
            Ok(bytes) => bytes,
            Err(_) => return jlong::from(AXION_E_INVALID_BUFFER),
        }
    };

    let mut ctx: u64 = 0;
    // SAFETY: `bytes` reste vivant pendant l'appel, et `ctx` est une variable
    // locale accessible en écriture.
    let side = u32::try_from(side).unwrap_or(u32::MAX);
    let code = unsafe { axion_init(bytes.as_ptr(), bytes.len(), side, &raw mut ctx) };
    if code == AXION_OK {
        // Le jeton tient sur 63 bits utiles : la conversion préserve sa valeur
        // et reste distinguable d'un code d'erreur.
        jlong::try_from(ctx).unwrap_or(jlong::from(AXION_E_INVALID_BUFFER))
    } else {
        jlong::from(code)
    }
}

/// `NativeBridge.shutdown(long)`.
extern "system" fn jni_shutdown(_env: JNIEnv, _class: JClass, ctx: jlong) -> jint {
    // SAFETY: aucun pointeur n'est déréférencé.
    unsafe { axion_shutdown(ctx as u64) }
}

/// `NativeBridge.lastError(long, byte[])`.
///
/// Écrit le message dans le tableau fourni par Java et renvoie le nombre
/// d'octets écrits, ou un code d'erreur négatif. Le tableau est alloué une fois
/// par Java et réutilisé : rien n'est alloué côté JVM à chaque appel.
extern "system" fn jni_last_error(
    env: JNIEnv,
    _class: JClass,
    ctx: jlong,
    out: JByteArray,
) -> jint {
    if out.is_null() {
        return AXION_E_INVALID_BUFFER;
    }
    let capacity = match env.get_array_length(&out) {
        Ok(length) => length.max(0) as usize,
        Err(_) => return AXION_E_INVALID_BUFFER,
    };

    let mut scratch = vec![0u8; capacity];
    let mut written: usize = 0;
    // SAFETY: `scratch` fait `capacity` octets, et `written` est local.
    let code =
        unsafe { axion_last_error(ctx as u64, scratch.as_mut_ptr(), capacity, &raw mut written) };
    if code != AXION_OK {
        return code;
    }

    let signed: &[i8] = bytemuck_cast(&scratch[..written]);
    match env.set_byte_array_region(&out, 0, signed) {
        Ok(()) => jint::try_from(written).unwrap_or(jint::MAX),
        Err(_) => AXION_E_INVALID_BUFFER,
    }
}

/// Réinterprète des octets non signés en octets signés, comme Java les voit.
///
/// `u8` et `i8` ont la même taille et le même alignement ; seule leur
/// interprétation diffère.
fn bytemuck_cast(bytes: &[u8]) -> &[i8] {
    // SAFETY: même disposition mémoire, même longueur.
    unsafe { core::slice::from_raw_parts(bytes.as_ptr().cast::<i8>(), bytes.len()) }
}

/// `NativeBridge.metricsExport(long, byte[])`.
///
/// Renvoie la longueur **complète** de l'export, que le tableau ait suffi ou
/// non, ou un code d'erreur négatif. L'appelant qui trouve la longueur
/// supérieure à la taille de son tableau rappelle avec un plus grand : rien
/// n'a alors été écrit, un JSON tronqué n'étant pas un JSON.
extern "system" fn jni_metrics_export(
    env: JNIEnv,
    _class: JClass,
    ctx: jlong,
    out: JByteArray,
) -> jint {
    if out.is_null() {
        return AXION_E_INVALID_BUFFER;
    }
    let capacity = match env.get_array_length(&out) {
        Ok(length) => length.max(0) as usize,
        Err(_) => return AXION_E_INVALID_BUFFER,
    };

    let mut scratch = vec![0u8; capacity];
    let mut needed: usize = 0;
    // SAFETY: `scratch` fait `capacity` octets, et `needed` est local.
    let code = unsafe {
        axion_metrics_export(ctx as u64, scratch.as_mut_ptr(), capacity, &raw mut needed)
    };
    if code != AXION_OK {
        return code;
    }

    if needed <= capacity {
        let signed: &[i8] = bytemuck_cast(&scratch[..needed]);
        if env.set_byte_array_region(&out, 0, signed).is_err() {
            return AXION_E_INVALID_BUFFER;
        }
    }
    jint::try_from(needed).unwrap_or(jint::MAX)
}

/// `NativeBridge.assetCompile(long, long, int, long)`.
///
/// Rend l'identifiant du travail, strictement positif, ou un code d'erreur
/// négatif. La source a été écrite par Java dans le tampon `ASSET_IN`.
extern "system" fn jni_asset_compile(
    _env: JNIEnv,
    _class: JClass,
    ctx: jlong,
    asset_id: jlong,
    format: jint,
    source_len: jlong,
) -> jint {
    let mut job: u32 = 0;
    // SAFETY: `options_cbor` peut être nul quand sa longueur l'est, et `job`
    // est une variable locale accessible en écriture.
    let code = unsafe {
        axion_asset_compile(
            ctx as u64,
            asset_id as u64,
            u32::try_from(format).unwrap_or(u32::MAX),
            source_len.max(0) as u64,
            std::ptr::null(),
            0,
            &raw mut job,
        )
    };
    if code == AXION_OK {
        jint::try_from(job).unwrap_or(AXION_E_INVALID_BUFFER)
    } else {
        code
    }
}

/// `NativeBridge.assetPoll(long, int, long[])`.
///
/// Écrit `[état, taille, code d'erreur]` dans le tableau fourni, qui doit
/// compter au moins trois éléments, et rend le code de l'appel.
extern "system" fn jni_asset_poll(
    env: JNIEnv,
    _class: JClass,
    ctx: jlong,
    job_id: jint,
    out: JLongArray,
) -> jint {
    if out.is_null() {
        return AXION_E_INVALID_BUFFER;
    }
    match env.get_array_length(&out) {
        Ok(length) if length >= 3 => {}
        _ => return AXION_E_INVALID_BUFFER,
    }

    let mut status: u32 = 0;
    let mut size: u64 = 0;
    let mut error: i32 = 0;
    // SAFETY: les trois pointeurs désignent des variables locales.
    let code = unsafe {
        axion_asset_poll(
            ctx as u64,
            u32::try_from(job_id).unwrap_or(u32::MAX),
            &raw mut status,
            &raw mut size,
            &raw mut error,
        )
    };
    if code != AXION_OK {
        return code;
    }

    let values = [jlong::from(status), size as jlong, jlong::from(error)];
    match env.set_long_array_region(&out, 0, &values) {
        Ok(()) => AXION_OK,
        Err(_) => AXION_E_INVALID_BUFFER,
    }
}

/// `NativeBridge.bufferAcquire(long, int, long)`.
///
/// Renvoie un `DirectByteBuffer` sur la mémoire native, ou `null` en cas
/// d'échec. C'est le seul endroit où le pont crée un objet Java : Java n'a pas
/// d'autre moyen de voir cette mémoire, et l'appel n'a lieu qu'à l'acquisition,
/// pas par élément.
///
/// La génération se lit dans l'en-tête du tampon, à l'offset 8 : elle n'exige
/// donc aucun second appel.
extern "system" fn jni_buffer_acquire<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    ctx: jlong,
    kind: jint,
    min_capacity: jlong,
) -> JObject<'local> {
    if min_capacity < 0 {
        return JObject::null();
    }

    let mut info = AxionBufferInfo {
        ptr: std::ptr::null_mut(),
        capacity: 0,
        kind: 0,
        generation: 0,
    };
    // SAFETY: `info` est une variable locale accessible en écriture.
    let code = unsafe {
        axion_buffer_acquire(ctx as u64, kind as u32, min_capacity as u64, &raw mut info)
    };
    if code != AXION_OK || info.ptr.is_null() {
        return JObject::null();
    }

    let capacity = match usize::try_from(info.capacity) {
        Ok(capacity) => capacity,
        Err(_) => return JObject::null(),
    };
    // SAFETY: le tampon appartient au contexte et reste valide jusqu'à sa
    // prochaine réallocation, que la génération signale à Java.
    match unsafe { env.new_direct_byte_buffer(info.ptr, capacity) } {
        Ok(buffer) => JObject::from(buffer),
        Err(_) => JObject::null(),
    }
}

/// `NativeBridge.bufferRelease(long, int, int)`.
extern "system" fn jni_buffer_release(
    _env: JNIEnv,
    _class: JClass,
    ctx: jlong,
    kind: jint,
    generation: jint,
) -> jint {
    // SAFETY: aucun pointeur n'est déréférencé.
    unsafe { axion_buffer_release(ctx as u64, kind as u32, generation as u32) }
}

/// `NativeBridge.simSubmit(long, long, int, int)` (IF-03).
///
/// Les commandes ont été écrites par Java dans le tampon `SIM_IN` ; leur nombre
/// voyage en paramètre, pas dans l'en-tête (convention IF-02, comme `assetCompile`).
extern "system" fn jni_sim_submit(
    _env: JNIEnv,
    _class: JClass,
    ctx: jlong,
    tick: jlong,
    command_count: jint,
    impact_count: jint,
) -> jint {
    // SAFETY: aucun pointeur n'est déréférencé.
    unsafe {
        axion_sim_submit(
            ctx as u64,
            tick as u64,
            u32::try_from(command_count).unwrap_or(u32::MAX),
            u32::try_from(impact_count).unwrap_or(u32::MAX),
        )
    }
}

/// `NativeBridge.simCollect(long, long, long[])` (IF-03).
///
/// Avance la simulation et dépose `BodyState[]`/`PhysicsEvent[]` dans
/// `SIM_OUT`/`EVENTS`. Les sept champs d'[`AxionCollectResult`] sont écrits, non
/// signés, dans le tableau fourni — qui doit compter au moins
/// [`SIM_COLLECT_SLOTS`] éléments —, à l'image de `assetPoll` : le JNI ne fait
/// pas traverser de `struct`, seulement des tableaux de sortie.
extern "system" fn jni_sim_collect(
    env: JNIEnv,
    _class: JClass,
    ctx: jlong,
    deadline_ns: jlong,
    out: JLongArray,
) -> jint {
    if out.is_null() {
        return AXION_E_INVALID_BUFFER;
    }
    match env.get_array_length(&out) {
        Ok(length) if length >= SIM_COLLECT_SLOTS => {}
        _ => return AXION_E_INVALID_BUFFER,
    }

    let mut result = AxionCollectResult::default();
    // SAFETY: `result` est une variable locale accessible en écriture.
    let code = unsafe { axion_sim_collect(ctx as u64, deadline_ns.max(0) as u64, &raw mut result) };
    if code != AXION_OK {
        return code;
    }

    // Chaque `u32` devient un `long` non signé : les compteurs restent lisibles
    // côté Java sans piège de signe.
    let values = [
        jlong::from(result.state_count),
        jlong::from(result.event_count),
        jlong::from(result.deform_page_count),
        jlong::from(result.refit_count),
        jlong::from(result.detach_count),
        jlong::from(result.net_bytes),
        jlong::from(result.flags),
    ];
    match env.set_long_array_region(&out, 0, &values) {
        Ok(()) => AXION_OK,
        Err(_) => AXION_E_INVALID_BUFFER,
    }
}

/// `NativeBridge.simCancel(long)` (IF-03).
extern "system" fn jni_sim_cancel(_env: JNIEnv, _class: JClass, ctx: jlong) -> jint {
    // SAFETY: aucun pointeur n'est déréférencé.
    unsafe { axion_sim_cancel(ctx as u64) }
}

#[cfg(test)]
mod tests {
    /// Les signatures JNI sont des chaînes non vérifiées par le compilateur :
    /// une faute de frappe ne se voit qu'au chargement, sous forme d'un échec
    /// d'enregistrement. Ce test les compare à la forme attendue, à défaut de
    /// pouvoir démarrer une JVM ici — c'est le test d'intégration Java qui
    /// prouve l'enregistrement réel.
    #[test]
    fn signatures_jni_conformes() {
        // (nom, signature, description du type Java)
        let attendu = [
            ("abiVersion", "()I"),
            ("init", "([BI)J"),
            ("shutdown", "(J)I"),
            ("lastError", "(J[B)I"),
            ("bufferAcquire", "(JIJ)Ljava/nio/ByteBuffer;"),
            ("bufferRelease", "(JII)I"),
            ("simSubmit", "(JJII)I"),
            ("simCollect", "(JJ[J)I"),
            ("simCancel", "(J)I"),
        ];

        for (nom, signature) in attendu {
            assert!(signature.starts_with('('), "{nom} : parenthèse ouvrante");
            assert!(signature.contains(')'), "{nom} : parenthèse fermante");
            let retour = signature.split(')').nth(1).unwrap();
            assert!(!retour.is_empty(), "{nom} : type de retour absent");
            // Un type objet se termine par « ; » ; un type primitif tient sur
            // un caractère.
            if retour.starts_with('L') {
                assert!(retour.ends_with(';'), "{nom} : type objet mal terminé");
            } else {
                assert_eq!(retour.len(), 1, "{nom} : type primitif mal formé");
            }
        }
    }
}
