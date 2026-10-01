//! IF-06 / ADR-119 exercé par l'ABI comme Java le fera : le conteneur A3D est
//! écrit dans `ASSET_IN`, chargé par `axion_asset_load`, sa géométrie relue dans
//! `ASSET_OUT`, puis le handle rendu. Le bilan d'arrêt (R-322) doit voir un
//! handle oublié.
//!
//! Un seul test, son propre binaire : la session native est globale au processus.

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

use ax_asset::collider::ColliderMode;
use ax_asset::compile::{compile, CompileOptions};
use ax_asset::import::{ImportLimits, SourceFormat};
use ax_asset::optimize::LodOptions;
use ax_model::buffer::{BufferKind, HEADER_BYTES};
use ax_model::dm::handle::Handle;
use ax_model::dm::render::{geometry_transfer_len, RestDraw};
use axion_native::abi::{
    axion_asset_geometry, axion_asset_load, axion_asset_unload, axion_buffer_acquire,
    axion_buffer_release, axion_init, axion_shutdown, AxionBufferInfo, AXION_E_INVALID_BUFFER,
    AXION_E_INVALID_HANDLE, AXION_E_LEAK, AXION_OK, AXION_SECTION_GEOM, AXION_SECTION_NODE,
    AXION_SIDE_CLIENT,
};

static CONTEXTE: std::sync::Mutex<()> = std::sync::Mutex::new(());

const ASSET_ID: u64 = 0x0A51_0C0B_E000_0042;
const NODE_GEOM: u32 = AXION_SECTION_NODE | AXION_SECTION_GEOM;
/// Bit de `PHYS` (rang 4 de la table de la PARTIE 7) : non pris en charge par
/// `axion_asset_load` dans ADR-119.
const PHYS: u32 = 1 << 4;

/// Un cube unité compilé de bout en bout, comme le registre d'assets le reçoit.
fn cube() -> Vec<u8> {
    const CUBE_OBJ: &str = "\
v 0.0 0.0 0.0
v 1.0 0.0 0.0
v 1.0 1.0 0.0
v 0.0 1.0 0.0
v 0.0 0.0 1.0
v 1.0 0.0 1.0
v 1.0 1.0 1.0
v 0.0 1.0 1.0
f 1 2 3
f 1 3 4
f 5 7 6
f 5 8 7
f 1 5 6
f 1 6 2
f 4 3 7
f 4 7 8
f 1 4 8
f 1 8 5
f 2 6 7
f 2 7 3
";
    let options = CompileOptions {
        asset_id: ASSET_ID,
        source_hash: 0,
        limits: ImportLimits::new(1 << 20),
        dynamic_body: true,
        lod: LodOptions::DEFAULT,
        collider_mode: ColliderMode::None,
    };
    compile(CUBE_OBJ.as_bytes(), SourceFormat::Obj, &options, |_| None)
        .expect("compilation refusée")
        .bytes
}

/// Acquiert un tampon et rend sa description.
fn acquire(ctx: u64, kind: BufferKind, min: u64) -> AxionBufferInfo {
    let mut out = AxionBufferInfo {
        ptr: std::ptr::null_mut(),
        capacity: 0,
        kind: 0,
        generation: 0,
    };
    let code = unsafe { axion_buffer_acquire(ctx, kind.as_u32(), min, &raw mut out) };
    assert_eq!(code, AXION_OK, "acquisition de {kind:?}");
    assert!(!out.ptr.is_null());
    out
}

/// Écrit le conteneur dans la charge utile d'`ASSET_IN`, comme Java le fait.
fn write_asset_in(ctx: u64, container: &[u8]) -> AxionBufferInfo {
    let buffer = acquire(ctx, BufferKind::AssetIn, container.len() as u64);
    let view = unsafe {
        std::slice::from_raw_parts_mut(buffer.ptr, usize::try_from(buffer.capacity).unwrap())
    };
    view[HEADER_BYTES..HEADER_BYTES + container.len()].copy_from_slice(container);
    buffer
}

fn release(ctx: u64, buffer: AxionBufferInfo) {
    assert_eq!(
        unsafe { axion_buffer_release(ctx, buffer.kind, buffer.generation) },
        AXION_OK,
        "relâchement du tampon {}",
        buffer.kind
    );
}

fn load(ctx: u64, asset_id: u64, mask: u32) -> Result<Handle, i32> {
    let mut handle = Handle::ABSENT;
    match unsafe { axion_asset_load(ctx, asset_id, mask, &raw mut handle) } {
        AXION_OK => Ok(handle),
        code => Err(code),
    }
}

fn u32_at(bytes: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap())
}

#[test]
fn un_asset_se_charge_se_transfere_et_se_rend_sans_fuite() {
    let _contexte = CONTEXTE
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);

    let mut ctx = 0u64;
    assert_eq!(
        unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_CLIENT, &raw mut ctx) },
        AXION_OK,
        "init"
    );

    let container = cube();
    let asset_in = write_asset_in(ctx, &container);

    // Refus nets, sans rien ranger.
    assert_eq!(
        load(ctx, ASSET_ID, 0),
        Err(AXION_E_INVALID_BUFFER),
        "masque vide"
    );
    assert_eq!(
        load(ctx, ASSET_ID, NODE_GEOM | PHYS),
        Err(AXION_E_INVALID_BUFFER),
        "section non prise en charge"
    );
    assert_eq!(
        load(ctx, ASSET_ID ^ 1, NODE_GEOM),
        Err(AXION_E_INVALID_BUFFER),
        "octets d'un autre asset"
    );
    assert_eq!(
        unsafe { axion_asset_load(ctx, ASSET_ID, NODE_GEOM, std::ptr::null_mut()) },
        AXION_E_INVALID_BUFFER,
        "sortie nulle"
    );

    // Chargement réel. La génération 0 est réservée (R-111).
    let handle = load(ctx, ASSET_ID, NODE_GEOM).expect("chargement");
    assert_ne!(handle.generation, 0);
    release(ctx, asset_in);

    // Géométrie déposée dans ASSET_OUT.
    let mut size = 0u64;
    assert_eq!(
        unsafe { axion_asset_geometry(ctx, handle, &raw mut size) },
        AXION_OK,
        "géométrie"
    );
    let asset_out = acquire(ctx, BufferKind::AssetOut, 0);
    let view = unsafe {
        std::slice::from_raw_parts(asset_out.ptr, usize::try_from(asset_out.capacity).unwrap())
    };
    let transfer = &view[HEADER_BYTES..HEADER_BYTES + usize::try_from(size).unwrap()];
    let meshes = u32_at(transfer, 0) as usize;
    let vertices = u32_at(transfer, 4) as usize;
    let indices = u32_at(transfer, 8) as usize;
    let draws = u32_at(transfer, 12) as usize;
    assert!(
        meshes >= 1 && vertices >= 8 && indices >= 36,
        "cube complet"
    );
    assert!(draws >= 1, "un node affiche le cube");
    assert_eq!(
        Some(transfer.len()),
        geometry_transfer_len(meshes, vertices, indices, draws),
        "disposition d'ADR-119"
    );
    let first_draw = transfer.len() - draws * RestDraw::BYTES;
    let draw = RestDraw::read_le(
        transfer[first_draw..first_draw + RestDraw::BYTES]
            .try_into()
            .unwrap(),
    );
    assert!((draw.mesh as usize) < meshes);
    release(ctx, asset_out);

    // Rendu, puis périmé : ni rendu ni lu une seconde fois (R-110).
    assert_eq!(
        unsafe { axion_asset_unload(ctx, handle) },
        AXION_OK,
        "unload"
    );
    assert_eq!(
        unsafe { axion_asset_unload(ctx, handle) },
        AXION_E_INVALID_HANDLE,
        "double unload"
    );
    assert_eq!(
        unsafe { axion_asset_geometry(ctx, handle, &raw mut size) },
        AXION_E_INVALID_HANDLE,
        "géométrie d'un handle périmé"
    );

    // Tout est rendu : l'arrêt est équilibré (R-322, T-016).
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK, "arrêt équilibré");

    // Une seconde session, un handle oublié : l'arrêt doit le dire (R-321).
    let mut ctx = 0u64;
    assert_eq!(
        unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_CLIENT, &raw mut ctx) },
        AXION_OK,
        "seconde init"
    );
    let asset_in = write_asset_in(ctx, &container);
    let _oublie = load(ctx, ASSET_ID, NODE_GEOM).expect("chargement");
    release(ctx, asset_in);
    assert_eq!(
        unsafe { axion_shutdown(ctx) },
        AXION_E_LEAK,
        "un handle non rendu est signalé"
    );
}
