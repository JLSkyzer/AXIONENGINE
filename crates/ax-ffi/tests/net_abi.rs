//! L'empreinte déterministe et la compression des charges utiles réseau traversent la frontière
//! (C-51, ADR-150 D4 et D5, ABI 6).
//!
//! Un seul test par binaire : le contexte natif est unique.

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

use ax_model::buffer::{BufferKind, HEADER_BYTES};
use axion_native::abi::{
    axion_buffer_acquire, axion_buffer_release, axion_det_profile, axion_init, axion_net_compress,
    axion_net_decompress, axion_shutdown, AxionBufferInfo, AxionDetProfile, AXION_ABI_VERSION,
    AXION_E_INVALID_BUFFER, AXION_NET_MAX_PAYLOAD, AXION_OK, AXION_SIDE_SERVER,
};

fn empty_info() -> AxionBufferInfo {
    AxionBufferInfo {
        ptr: std::ptr::null_mut(),
        capacity: 0,
        kind: 0,
        generation: 0,
    }
}

/// La vue courante de `NET_OUT`, d'au moins `capacity` octets de charge utile.
fn net_out(ctx: u64, capacity: u64) -> AxionBufferInfo {
    let mut info = empty_info();
    // SAFETY: `info` est une variable locale accessible en écriture.
    let code =
        unsafe { axion_buffer_acquire(ctx, BufferKind::NetOut.as_u32(), capacity, &raw mut info) };
    assert_eq!(code, AXION_OK, "NET_OUT refusé");
    info
}

/// Écrit `bytes` dans `NET_OUT`, après l'en-tête.
fn write(ctx: u64, bytes: &[u8]) {
    let info = net_out(ctx, bytes.len() as u64);
    // SAFETY: le tampon appartient au contexte vivant et fait au moins `capacity` octets.
    let view = unsafe {
        std::slice::from_raw_parts_mut(info.ptr, usize::try_from(info.capacity).unwrap())
    };
    view[HEADER_BYTES..HEADER_BYTES + bytes.len()].copy_from_slice(bytes);
}

/// Lit `size` octets de `NET_OUT`, par une vue réacquise : la sortie a pu agrandir le tampon.
fn read(ctx: u64, size: u64) -> Vec<u8> {
    let info = net_out(ctx, 0);
    // SAFETY: le tampon appartient au contexte vivant et fait au moins `capacity` octets.
    let view =
        unsafe { std::slice::from_raw_parts(info.ptr, usize::try_from(info.capacity).unwrap()) };
    view[HEADER_BYTES..HEADER_BYTES + usize::try_from(size).unwrap()].to_vec()
}

fn compress(ctx: u64, input_len: u64) -> (i32, u64) {
    let mut size = u64::MAX;
    // SAFETY: `size` est une variable locale.
    let code = unsafe { axion_net_compress(ctx, input_len, &raw mut size) };
    (code, size)
}

fn decompress(ctx: u64, input_len: u64, max_output: u64) -> (i32, u64) {
    let mut size = u64::MAX;
    // SAFETY: `size` est une variable locale.
    let code = unsafe { axion_net_decompress(ctx, input_len, max_output, &raw mut size) };
    (code, size)
}

#[test]
fn adr150_empreinte_et_compression_reseau_traversent_la_frontiere() {
    assert_eq!(AXION_ABI_VERSION, 9);

    // D4 : l'empreinte est celle d'ax-det, sans contexte.
    let mut profile = AxionDetProfile::default();
    // SAFETY: `profile` est une variable locale.
    assert_eq!(unsafe { axion_det_profile(&raw mut profile) }, AXION_OK);
    assert_eq!(profile.profile, ax_det::det_profile());
    assert_eq!(profile.kernel_version, ax_det::DET_KERNEL_VERSION);
    assert_eq!(
        profile.in_matrix,
        u32::from(ax_det::is_in_validation_matrix())
    );
    // SAFETY: le pointeur nul est l'objet de l'appel.
    assert_eq!(
        unsafe { axion_det_profile(std::ptr::null_mut()) },
        AXION_E_INVALID_BUFFER
    );
    assert_eq!(
        std::mem::size_of::<AxionDetProfile>(),
        16,
        "disposition de l'ABI"
    );

    let mut ctx: u64 = 0;
    // SAFETY: `ctx` est une variable locale accessible en écriture.
    assert_eq!(
        unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut ctx) },
        AXION_OK
    );

    // D5 : un registre compressé par NET_OUT se relit à l'identique, par zstd comme par la FFI.
    let registry: Vec<u8> = (0..50_000u32)
        .flat_map(|i| format!("{{\"schema\":1,\"id\":\"axion:test/{}\"}}\n", i % 37).into_bytes())
        .collect();
    write(ctx, &registry);
    let (code, frame_len) = compress(ctx, registry.len() as u64);
    assert_eq!(code, AXION_OK);
    assert!(
        frame_len < registry.len() as u64,
        "la trame ne compresse pas"
    );
    let frame = read(ctx, frame_len);
    assert_eq!(
        ax_asset::compression::decompress_bounded(&frame, registry.len()),
        Some(registry.clone())
    );

    write(ctx, &frame);
    let (code, content_len) = decompress(ctx, frame_len, registry.len() as u64);
    assert_eq!(code, AXION_OK);
    assert_eq!(read(ctx, content_len), registry);

    // La borne de l'appelant : un octet de moins, et la trame est refusée.
    write(ctx, &frame);
    assert_eq!(
        decompress(ctx, frame_len, registry.len() as u64 - 1).0,
        AXION_E_INVALID_BUFFER
    );

    // Le plafond natif : une trame qui déclare plus d'AXION_NET_MAX_PAYLOAD est refusée même sous
    // une borne d'appelant illimitée.
    let zeros = vec![0u8; usize::try_from(AXION_NET_MAX_PAYLOAD).unwrap() + 1];
    let huge = ax_asset::compression::compress(&zeros, 3).expect("compression");
    drop(zeros);
    write(ctx, &huge);
    assert_eq!(
        decompress(ctx, huge.len() as u64, u64::MAX).0,
        AXION_E_INVALID_BUFFER
    );

    // R-1650 : ce qui n'est pas une trame, ce qui sort du tampon, ce qui dépasse le plafond.
    write(ctx, b"pas une trame zstd");
    assert_eq!(decompress(ctx, 18, 1 << 20).0, AXION_E_INVALID_BUFFER);
    let capacity = net_out(ctx, 0).capacity;
    assert_eq!(
        compress(ctx, capacity).0,
        AXION_E_INVALID_BUFFER,
        "entrée hors du tampon"
    );
    assert_eq!(
        compress(ctx, AXION_NET_MAX_PAYLOAD + 1).0,
        AXION_E_INVALID_BUFFER
    );
    // SAFETY: le pointeur nul est l'objet de l'appel.
    assert_eq!(
        unsafe { axion_net_compress(ctx, 0, std::ptr::null_mut()) },
        AXION_E_INVALID_BUFFER
    );
    // SAFETY: le pointeur nul est l'objet de l'appel.
    assert_eq!(
        unsafe { axion_net_decompress(ctx, 0, 0, std::ptr::null_mut()) },
        AXION_E_INVALID_BUFFER
    );

    // Le plafond d'entrée de la compression tient même quand le tampon est assez grand pour la
    // contenir.
    let trop = vec![0u8; usize::try_from(AXION_NET_MAX_PAYLOAD).unwrap() + 1];
    write(ctx, &trop);
    drop(trop);
    assert_eq!(
        compress(ctx, AXION_NET_MAX_PAYLOAD + 1).0,
        AXION_E_INVALID_BUFFER,
        "compression"
    );

    // Une charge vide se compresse et se relit vide.
    let (code, empty_frame_len) = compress(ctx, 0);
    assert_eq!(code, AXION_OK);
    let empty_frame = read(ctx, empty_frame_len);
    write(ctx, &empty_frame);
    assert_eq!(decompress(ctx, empty_frame_len, 0), (AXION_OK, 0));

    let generation = net_out(ctx, 0).generation;
    // SAFETY: aucun pointeur déréférencé.
    assert_eq!(
        unsafe { axion_buffer_release(ctx, BufferKind::NetOut.as_u32(), generation) },
        AXION_OK
    );
    // SAFETY: le contexte ouvert ci-dessus, fermé une fois.
    assert_eq!(
        unsafe { axion_shutdown(ctx) },
        AXION_OK,
        "fermeture refusée ou fuite (E-2003)"
    );
}
