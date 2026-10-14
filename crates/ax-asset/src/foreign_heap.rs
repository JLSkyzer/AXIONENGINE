//! Les bibliothèques C et C++ de la chaîne d'assets allouent par l'allocateur compteur
//! (INV-08, R-480).
//!
//! meshoptimizer (cache de sommets, LOD, C-23) et zstd (sections A3D, C-24) prennent leurs
//! tampons de travail par `operator new` et `malloc` : sans ce module, ces octets échapperaient
//! au comptage de la bibliothèque native. Ce module est leur **seule porte d'entrée** dans
//! `ax-asset` — un test le vérifie — et leur fait prendre [`ax_mem::foreign_alloc`] :
//!
//! - **meshoptimizer** a un allocateur global au processus, `meshopt_setAllocator`. Il est
//!   installé **une fois, avant le premier appel** : chaque fonction de ce module y passe
//!   d'abord, et [`Once`] fait attendre un appel concurrent que l'installation soit faite.
//!   Le changer pendant qu'un autre thread est dans meshoptimizer lui ferait rendre à un
//!   allocateur un bloc pris à un autre. Aucun autre crate du dépôt n'emploie meshoptimizer.
//!   Le crate `meshopt` 0.6.2 n'expose pas cette fonction, que la bibliothèque qu'il compile
//!   contient (`allocator.cpp`) : elle est déclarée ici ;
//! - **zstd** reçoit son allocateur par contexte (`ZSTD_customMem`, API dite expérimentale de
//!   libzstd 1.5.7, liaisons de `zstd-sys`). La compression et la décompression refont
//!   exactement ce que font `zstd::bulk::compress` et `zstd::bulk::decompress` — mêmes appels,
//!   mêmes paramètres —, si bien que les octets écrits ne changent pas ; un test le vérifie.
//!
//! Les tampons de sortie restent des `Vec` de Rust, donc comptés.

// R-2120 : `unsafe` est admis dans ce seul module d'ax-asset — deux bibliothèques C s'y
// appellent par leurs fonctions étrangères, et leur reçoivent des pointeurs bruts.
#![allow(unsafe_code)]
#![deny(clippy::undocumented_unsafe_blocks)]

use core::ffi::c_void;
use std::sync::Once;
use zstd_sys as zs;

extern "C" {
    /// `meshoptimizer.h` : remplace `operator new` et `operator delete` pour toute la suite du
    /// processus. `MESHOPTIMIZER_ALLOC_CALLCONV` vaut `__cdecl` sous MSVC, la convention C des
    /// plateformes supportées.
    fn meshopt_setAllocator(
        allocate: extern "C" fn(usize) -> *mut c_void,
        deallocate: unsafe extern "C" fn(*mut c_void),
    );
}

static MESHOPT_HEAP: Once = Once::new();

/// Installe l'allocateur compteur dans meshoptimizer, au premier appel du processus.
fn meshopt_heap() {
    MESHOPT_HEAP.call_once(|| {
        // SAFETY: aucun appel de meshoptimizer n'a pu commencer — ils passent tous par ce
        // module, qui appelle d'abord cette fonction, et `Once` retient les autres threads
        // jusqu'à la fin de l'installation. Les deux fonctions tiennent le contrat d'un
        // allocateur C : un bloc d'au moins la taille demandée, aligné sur 16 octets comme par
        // `operator new`, rendu par la seconde.
        unsafe { meshopt_setAllocator(ax_mem::foreign_alloc, ax_mem::foreign_free) };
    });
}

/// `meshopt::optimize_vertex_cache`, après l'installation de l'allocateur.
pub(crate) fn optimize_vertex_cache(indices: &[u32], vertex_count: usize) -> Vec<u32> {
    meshopt_heap();
    meshopt::optimize_vertex_cache(indices, vertex_count)
}

/// `meshopt::simplify_decoder` sans option, après l'installation de l'allocateur.
pub(crate) fn simplify(
    indices: &[u32],
    positions: &[[f32; 3]],
    target_count: usize,
    target_error: f32,
) -> Vec<u32> {
    meshopt_heap();
    meshopt::simplify_decoder(
        indices,
        positions,
        target_count,
        target_error,
        meshopt::SimplifyOptions::None,
        None,
    )
}

/// Taux de miss du cache de sommets (ACMR) d'une liste d'indices, cache de 16.
#[cfg(test)]
pub(crate) fn vertex_cache_acmr(indices: &[u32], vertex_count: usize) -> f32 {
    meshopt_heap();
    meshopt::analyze_vertex_cache(indices, vertex_count, 16, 0, 0).acmr
}

/// `customAlloc` de zstd : l'allocateur étranger d'ax-mem, sans état.
unsafe extern "C" fn zstd_alloc(_opaque: *mut c_void, size: usize) -> *mut c_void {
    ax_mem::foreign_alloc(size)
}

/// `customFree` de zstd.
///
/// # Safety
///
/// `address` est nul, ou vient de [`zstd_alloc`] — zstd n'en rend pas d'autre.
unsafe extern "C" fn zstd_free(_opaque: *mut c_void, address: *mut c_void) {
    // SAFETY: zstd ne rend à `customFree` que ce que `customAlloc` lui a donné, ou nul.
    unsafe { ax_mem::foreign_free(address) };
}

fn custom_mem() -> zs::ZSTD_customMem {
    zs::ZSTD_customMem {
        customAlloc: Some(zstd_alloc),
        customFree: Some(zstd_free),
        opaque: core::ptr::null_mut(),
    }
}

/// Rend le code s'il signale une erreur zstd.
fn checked(code: usize) -> Option<usize> {
    // SAFETY: fonction pure de libzstd, sans pointeur.
    (unsafe { zs::ZSTD_isError(code) } == 0).then_some(code)
}

/// Contexte de compression, libéré à la sortie de la portée.
struct Cctx(*mut zs::ZSTD_CCtx);

impl Drop for Cctx {
    fn drop(&mut self) {
        // SAFETY: le contexte vient de `ZSTD_createCCtx_advanced` et n'est libéré qu'ici.
        unsafe { zs::ZSTD_freeCCtx(self.0) };
    }
}

/// Contexte de décompression, libéré à la sortie de la portée.
struct Dctx(*mut zs::ZSTD_DCtx);

impl Drop for Dctx {
    fn drop(&mut self) {
        // SAFETY: le contexte vient de `ZSTD_createDCtx_advanced` et n'est libéré qu'ici.
        unsafe { zs::ZSTD_freeDCtx(self.0) };
    }
}

/// Compresse en une trame zstd au niveau donné — les octets de `zstd::bulk::compress`.
///
/// `None` si libzstd refuse : niveau hors de sa plage, mémoire refusée.
pub(crate) fn compress(payload: &[u8], level: i32) -> Option<Vec<u8>> {
    // SAFETY: `custom_mem` donne deux fonctions valides pour toute la vie du contexte.
    let context = Cctx(unsafe { zs::ZSTD_createCCtx_advanced(custom_mem()) });
    if context.0.is_null() {
        return None;
    }
    // SAFETY: contexte valide, paramètre et valeur de l'API publique de libzstd.
    checked(unsafe {
        zs::ZSTD_CCtx_setParameter(
            context.0,
            zs::ZSTD_cParameter::ZSTD_c_compressionLevel,
            level,
        )
    })?;
    // SAFETY: fonction pure de libzstd.
    let bound = unsafe { zs::ZSTD_compressBound(payload.len()) };
    let mut out = Vec::<u8>::with_capacity(bound);
    // SAFETY: `out` offre `bound` octets écrivables, `payload` en offre `payload.len()`
    // lisibles ; le contexte est valide. ZSTD_compressBound borne la sortie d'une trame.
    let written = checked(unsafe {
        zs::ZSTD_compress2(
            context.0,
            out.as_mut_ptr().cast(),
            bound,
            payload.as_ptr().cast(),
            payload.len(),
        )
    })?;
    // SAFETY: libzstd a écrit `written` ≤ `bound` octets au début de `out`.
    unsafe { out.set_len(written) };
    Some(out)
}

/// Décompresse une trame zstd dans au plus `capacity` octets — `zstd::bulk::decompress`.
///
/// La sortie est bornée à l'avance (R-902). `None` si la trame est invalide ou plus grande que
/// `capacity`.
pub(crate) fn decompress(stored: &[u8], capacity: usize) -> Option<Vec<u8>> {
    // SAFETY: comme pour la compression.
    let context = Dctx(unsafe { zs::ZSTD_createDCtx_advanced(custom_mem()) });
    if context.0.is_null() {
        return None;
    }
    let mut out = Vec::<u8>::with_capacity(capacity);
    // SAFETY: `out` offre `capacity` octets écrivables, `stored` en offre `stored.len()`
    // lisibles ; libzstd n'écrit jamais au-delà de `capacity`.
    let written = checked(unsafe {
        zs::ZSTD_decompressDCtx(
            context.0,
            out.as_mut_ptr().cast(),
            capacity,
            stored.as_ptr().cast(),
            stored.len(),
        )
    })?;
    // SAFETY: libzstd a écrit `written` ≤ `capacity` octets au début de `out`.
    unsafe { out.set_len(written) };
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Des contenus qui exercent les chemins de zstd : vide, court, répétitif, sans redondance,
    /// et assez long pour plusieurs blocs.
    fn payloads() -> Vec<Vec<u8>> {
        vec![
            Vec::new(),
            b"axion".to_vec(),
            (0..8192).map(|i| (i % 7) as u8).collect(),
            (0..4096u32)
                .flat_map(|n| n.wrapping_mul(2_654_435_761).to_le_bytes())
                .collect(),
            (0..300_000u32)
                .flat_map(|i| format!("v {} {} {}\n", i % 97, i % 89, i * 7 % 83).into_bytes())
                .collect(),
        ]
    }

    #[test]
    fn inv08_la_compression_rend_les_octets_de_zstd_bulk() {
        for payload in payloads() {
            for level in [1, 3, 9, 19] {
                let reference = zstd::bulk::compress(&payload, level).expect("zstd::bulk");
                assert_eq!(
                    compress(&payload, level).as_deref(),
                    Some(reference.as_slice()),
                    "{} octets, niveau {level}",
                    payload.len()
                );
            }
        }
    }

    #[test]
    fn inv08_la_decompression_rend_le_contenu_et_reste_bornee() {
        for payload in payloads() {
            let stored = compress(&payload, 9).expect("compression");
            assert_eq!(
                decompress(&stored, payload.len()).as_deref(),
                Some(payload.as_slice())
            );
            assert_eq!(
                decompress(&stored, payload.len()),
                zstd::bulk::decompress(&stored, payload.len()).ok()
            );
            if !payload.is_empty() {
                // R-902 : une sortie plus grande que la capacité annoncée est refusée.
                assert_eq!(decompress(&stored, payload.len() - 1), None);
                // Une trame tronquée aussi.
                assert_eq!(decompress(&stored[..stored.len() - 1], payload.len()), None);
            }
        }
    }

    #[test]
    fn inv08_meshoptimizer_et_zstd_allouent_par_l_allocateur_etranger() {
        let before = ax_mem::foreign_allocations();
        let grid: Vec<u32> = (0..64u32)
            .flat_map(|q| [q, q + 1, q + 65, q + 1, q + 66, q + 65])
            .collect();
        let _ = optimize_vertex_cache(&grid, 130);
        let after_meshopt = ax_mem::foreign_allocations();
        assert!(
            after_meshopt > before,
            "meshoptimizer n'a pas employé l'allocateur"
        );
        let _ = compress(&payloads()[4], 9);
        assert!(
            ax_mem::foreign_allocations() > after_meshopt,
            "zstd n'a pas employé l'allocateur"
        );
    }

    #[test]
    fn inv08_seul_ce_module_appelle_meshoptimizer_et_zstd() {
        // Un appel direct, ailleurs, allouerait hors du comptage — et pour meshoptimizer,
        // pourrait précéder l'installation de l'allocateur.
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
        let mut stack = vec![root];
        let mut scanned = 0;
        let mut offenders = Vec::new();
        while let Some(dir) = stack.pop() {
            for entry in std::fs::read_dir(&dir).expect("répertoire lisible") {
                let path = entry.expect("entrée").path();
                if path.is_dir() {
                    stack.push(path);
                    continue;
                }
                if path.extension().and_then(|e| e.to_str()) != Some("rs") {
                    continue;
                }
                let text = std::fs::read_to_string(&path).expect("source lisible");
                if path.file_name().and_then(|n| n.to_str()) == Some("foreign_heap.rs") {
                    // Témoin : le motif cherché est bien celui que ce module emploie.
                    assert!(text.contains("meshopt::") && text.contains("zs::ZSTD_"));
                    continue;
                }
                scanned += 1;
                for motif in ["meshopt::", "zstd::", "zstd_sys"] {
                    if text.contains(motif) {
                        offenders.push(format!("{} : {motif}", path.display()));
                    }
                }
            }
        }
        assert!(
            scanned > 20,
            "{scanned} fichiers parcourus : arborescence mal trouvée"
        );
        assert!(offenders.is_empty(), "appels directs : {offenders:?}");
    }
}
