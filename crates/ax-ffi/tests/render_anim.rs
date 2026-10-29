//! ADR-155, tranche 3 : l'animation d'une assembly traverse la frontière du rendu (ABI 10).
//!
//! Le pendule écrit par code est compilé, chargé, joué par `axion_render_animate`, puis préparé à un
//! tick donné : `RENDER_OUT` (schéma 3) porte la matrice de son dessin, que l'on compare à la valeur
//! calculée à la main.
//!
//! Un seul test, son propre binaire : la session native est globale au processus.

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

#[path = "../../ax-asset/tests/modeles_animes/mod.rs"]
mod modeles_animes;

use ax_asset::compile::{compile, CompileOptions};
use ax_asset::import::{ImportLimits, SourceFormat};
use ax_asset::options::AssetOptions;
use ax_math::{Mat4, Quat, Vec3};
use ax_model::buffer::{BufferKind, HEADER_BYTES};
use ax_model::dm::animation::anim_action;
use ax_model::dm::handle::Handle;
use ax_render_prep::{Camera, Candidate};
use axion_native::abi::{
    axion_asset_load, axion_asset_unload, axion_buffer_acquire, axion_buffer_release, axion_init,
    axion_render_animate, axion_render_prepare, axion_render_release, axion_shutdown,
    AxionBufferInfo, AxionRenderResult, AXION_E_INVALID_BUFFER, AXION_OK, AXION_SECTION_GEOM,
    AXION_SECTION_NODE, AXION_SIDE_CLIENT,
};
use axion_native::render::{IN_FRAME_HEADER, MATRIX_FLOATS, NO_MATRICES, OUT_HEADER, OUT_RECORD};

const ASSET_ID: u64 = 0x0A51_0C0B_E000_0155;

fn acquire(ctx: u64, kind: BufferKind, min: u64) -> AxionBufferInfo {
    let mut out = AxionBufferInfo {
        ptr: std::ptr::null_mut(),
        capacity: 0,
        kind: 0,
        generation: 0,
    };
    let code = unsafe { axion_buffer_acquire(ctx, kind.as_u32(), min, &raw mut out) };
    assert_eq!(code, AXION_OK, "acquisition de {kind:?}");
    out
}

fn view(buffer: &AxionBufferInfo) -> &'static mut [u8] {
    unsafe { std::slice::from_raw_parts_mut(buffer.ptr, usize::try_from(buffer.capacity).unwrap()) }
}

fn release(ctx: u64, buffer: &AxionBufferInfo) {
    assert_eq!(
        unsafe { axion_buffer_release(ctx, buffer.kind, buffer.generation) },
        AXION_OK
    );
}

/// Projection en perspective d'OpenGL, comme celle de Minecraft.
fn camera() -> Camera {
    let (near, far) = (0.05f32, 1024.0f32);
    let f = 1.0 / (35f32.to_radians()).tan();
    let mut m = [0f32; 16];
    m[0] = f / (16.0 / 9.0);
    m[5] = f;
    m[10] = (far + near) / (near - far);
    m[11] = -1.0;
    m[14] = 2.0 * far * near / (near - far);
    Camera {
        projection: Mat4::from_cols_array(&m),
        view_rotation: Quat::IDENTITY,
        viewport_width: 1920,
        viewport_height: 1080,
        max_distance: 128.0,
        lod_bias: 0,
        quality: 2,
        candidate_count: 1,
        max_instances: 0,
    }
}

/// Le pendule, devant la caméra, rendu au tick donné : le résultat, et la matrice de son dessin, ou
/// `None` s'il se dessine au repos.
fn frame(ctx: u64, asset: Handle, tick: f64) -> (AxionRenderResult, Option<[f32; 12]>) {
    let candidate = Candidate {
        id: 20,
        asset: asset.index,
        generation: asset.generation,
        flags: 0,
        position: Vec3::new(0.0, 0.0, -10.0),
        scale: 1.0,
        rotation: Quat::IDENTITY,
    };
    let input = acquire(
        ctx,
        BufferKind::RenderIn,
        (IN_FRAME_HEADER + Candidate::BYTES) as u64,
    );
    let bytes = view(&input);
    bytes[HEADER_BYTES..HEADER_BYTES + 8].copy_from_slice(&tick.to_le_bytes());
    let at = HEADER_BYTES + IN_FRAME_HEADER;
    bytes[at..at + Candidate::BYTES].copy_from_slice(&candidate.to_bytes());
    let mut result = AxionRenderResult::default();
    let desc = camera().to_bytes();
    assert_eq!(
        unsafe { axion_render_prepare(ctx, desc.as_ptr(), 0.0, 1_000_000_000, &raw mut result) },
        AXION_OK
    );
    assert_eq!(result.instance_count, 1);
    let out = acquire(ctx, BufferKind::RenderOut, 0);
    let bytes = view(&out);
    let word = |at: usize| u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap());
    let base = HEADER_BYTES;
    let mesh_total = word(base + 8) as usize;
    let matrix_total = word(base + 16) as usize;
    let first = word(base + OUT_HEADER + 24);
    let matrix = (first != NO_MATRICES).then(|| {
        let table = base + OUT_HEADER + OUT_RECORD + mesh_total * 4;
        let at = table + first as usize * MATRIX_FLOATS * 4;
        let mut m = [0f32; 12];
        for (i, value) in m.iter_mut().enumerate() {
            *value = f32::from_le_bytes(bytes[at + i * 4..at + i * 4 + 4].try_into().unwrap());
        }
        m
    });
    assert_eq!(
        result.palette_bytes as usize,
        matrix_total * MATRIX_FLOATS * 4
    );
    release(ctx, &input);
    release(ctx, &out);
    assert_eq!(
        unsafe { axion_render_release(ctx, result.frame_id) },
        AXION_OK
    );
    (result, matrix)
}

/// Une commande de couche 0 sur l'assembly 20 : le clip 0, de poids 1, sans fondu.
fn command(asset: Handle, action: u32, tick: f64) -> Vec<u8> {
    let mut out = Vec::new();
    for value in [20u32, 0, asset.index, asset.generation] {
        out.extend_from_slice(&value.to_le_bytes());
    }
    out.extend_from_slice(&tick.to_le_bytes());
    for value in [action, 0, 0] {
        out.extend_from_slice(&value.to_le_bytes());
    }
    for value in [1.0f32, 0.0, 1.0] {
        out.extend_from_slice(&value.to_le_bytes());
    }
    out.extend_from_slice(&[0; 8]);
    out
}

fn animate(ctx: u64, bytes: &[u8]) -> (i32, u32) {
    let mut applied = 0;
    let code = unsafe { axion_render_animate(ctx, bytes.as_ptr(), bytes.len(), &raw mut applied) };
    (code, applied)
}

#[test]
fn adr155_le_pendule_anime_traverse_le_rendu() {
    let mut ctx = 0u64;
    assert_eq!(
        unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_CLIENT, &raw mut ctx) },
        AXION_OK
    );
    let options = CompileOptions {
        asset_id: ASSET_ID,
        source_hash: 0,
        limits: ImportLimits::new(1 << 20),
        dynamic_body: true,
        authored: AssetOptions::DEFAULT,
    };
    let container = compile(
        modeles_animes::pendule().as_bytes(),
        SourceFormat::Gltf,
        &options,
        |_| None,
    )
    .expect("compilation")
    .bytes;
    let asset_in = acquire(ctx, BufferKind::AssetIn, container.len() as u64);
    view(&asset_in)[HEADER_BYTES..HEADER_BYTES + container.len()].copy_from_slice(&container);
    let mut asset = Handle::ABSENT;
    assert_eq!(
        unsafe {
            axion_asset_load(
                ctx,
                ASSET_ID,
                AXION_SECTION_NODE | AXION_SECTION_GEOM,
                &raw mut asset,
            )
        },
        AXION_OK
    );
    release(ctx, &asset_in);

    // Sans commande, le pendule se dessine au repos.
    let (repos, matrice) = frame(ctx, asset, 12.0);
    assert_eq!(matrice, None);
    assert_eq!(repos.palette_bytes, 0);

    // `balancer` joue depuis le tick 0. Au tick 12 (0,6 s), le palier vaut 1 bloc en x, et la
    // rotation, linéaire sur 0,8 s, a fait les trois quarts de son quart de tour autour de z.
    assert_eq!(
        animate(ctx, &command(asset, anim_action::PLAY, 0.0)),
        (AXION_OK, 1)
    );
    let m = frame(ctx, asset, 12.0)
        .1
        .expect("le pendule animé porte sa matrice");
    let angle = 0.75 * core::f32::consts::FRAC_PI_2;
    let attendu = [
        angle.cos(),
        angle.sin(),
        0.0,
        -angle.sin(),
        angle.cos(),
        0.0,
        0.0,
        0.0,
        1.0,
        1.0,
        0.0,
        0.0,
    ];
    for (obtenu, attendu) in m.iter().zip(attendu) {
        assert!((obtenu - attendu).abs() < 1e-5, "{m:?}");
    }

    // Un tampon tronqué n'applique rien ; une commande sur un asset inconnu est refusée seule.
    let mut tronque = command(asset, anim_action::FORGET, 0.0);
    tronque.pop();
    assert_eq!(animate(ctx, &tronque).0, AXION_E_INVALID_BUFFER);
    assert!(frame(ctx, asset, 12.0).1.is_some(), "rien n'a été oublié");
    let inconnu = Handle::new(asset.index + 5, 1);
    assert_eq!(
        animate(ctx, &command(inconnu, anim_action::PLAY, 0.0)),
        (AXION_OK, 0)
    );
    let refusees = axion_native::render::with(ctx, |render| render.stats().anim_rejected).unwrap();
    assert_eq!(refusees, 1);

    // `FORGET` rend le pendule à son repos.
    assert_eq!(
        animate(ctx, &command(asset, anim_action::FORGET, 20.0)),
        (AXION_OK, 1)
    );
    assert_eq!(frame(ctx, asset, 20.0).1, None);

    // L'asset déchargé emporte ses animations : une commande sur lui est refusée.
    assert_eq!(
        animate(ctx, &command(asset, anim_action::PLAY, 0.0)),
        (AXION_OK, 1)
    );
    let instances =
        |ctx| axion_native::render::with(ctx, |render| render.stats().instances).unwrap();
    assert_eq!(instances(ctx), 1);
    assert_eq!(unsafe { axion_asset_unload(ctx, asset) }, AXION_OK);
    assert_eq!(instances(ctx), 0, "l'animation meurt avec son asset");
    assert_eq!(
        animate(ctx, &command(asset, anim_action::PLAY, 0.0)),
        (AXION_OK, 0)
    );
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK);
}
