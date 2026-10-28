//! IF-05 / ADR-154 exercé par l'ABI comme Java le fera : un cube chargé, des candidats écrits
//! dans `RENDER_IN`, `axion_render_prepare`, les retenus relus dans `RENDER_OUT`, la frame rendue
//! par `axion_render_release`. T-520 et T-521 de bout en bout, et la part « solo » de T-524 : la
//! préparation n'attend pas un verrou de session tenu par un autre thread.
//!
//! Un seul test, son propre binaire : la session native est globale au processus.

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

use ax_asset::compile::{compile, CompileOptions};
use ax_asset::import::{ImportLimits, SourceFormat};
use ax_asset::options::AssetOptions;
use ax_math::{Mat4, Quat, Vec3};
use ax_model::buffer::{BufferKind, HEADER_BYTES};
use ax_model::dm::handle::Handle;
use ax_render_prep::{cull_scalar, Camera, Candidate, LocalBounds};
use axion_native::abi::{
    axion_asset_load, axion_asset_unload, axion_buffer_acquire, axion_buffer_release, axion_init,
    axion_render_prepare, axion_render_release, axion_shutdown, AxionBufferInfo, AxionRenderResult,
    AXION_E_INVALID_BUFFER, AXION_E_INVALID_HANDLE, AXION_E_LEAK, AXION_OK, AXION_RENDER_DEGRADED,
    AXION_SECTION_GEOM, AXION_SECTION_NODE, AXION_SIDE_CLIENT,
};
use std::time::{Duration, Instant};

const ASSET_ID: u64 = 0x0A51_0C0B_E000_0154;

/// Un cube unité, de (0, 0, 0) à (1, 1, 1), douze triangles.
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
        authored: AssetOptions::DEFAULT,
    };
    compile(CUBE_OBJ.as_bytes(), SourceFormat::Obj, &options, |_| None)
        .expect("compilation refusée")
        .bytes
}

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
fn perspective(fov_y: f32, aspect: f32, near: f32, far: f32) -> Mat4 {
    let f = 1.0 / (fov_y / 2.0).tan();
    let mut m = [0f32; 16];
    m[0] = f / aspect;
    m[5] = f;
    m[10] = (far + near) / (near - far);
    m[11] = -1.0;
    m[14] = 2.0 * far * near / (near - far);
    Mat4::from_cols_array(&m)
}

fn camera(candidate_count: u32) -> Camera {
    Camera {
        projection: perspective(70f32.to_radians(), 16.0 / 9.0, 0.05, 1024.0),
        view_rotation: Quat::IDENTITY,
        viewport_width: 1920,
        viewport_height: 1080,
        max_distance: 128.0,
        lod_bias: 0,
        quality: 2,
        candidate_count,
    }
}

fn candidate(id: u32, asset: Handle, position: Vec3) -> Candidate {
    Candidate {
        id,
        asset: asset.index,
        generation: asset.generation,
        flags: 0,
        position,
        scale: 1.0,
        rotation: Quat::IDENTITY,
    }
}

/// Écrit les candidats dans `RENDER_IN`, comme Java le fait.
fn write_candidates(ctx: u64, candidates: &[Candidate]) -> AxionBufferInfo {
    let buffer = acquire(
        ctx,
        BufferKind::RenderIn,
        (candidates.len() * Candidate::BYTES) as u64,
    );
    let bytes = view(&buffer);
    for (i, c) in candidates.iter().enumerate() {
        let at = HEADER_BYTES + i * Candidate::BYTES;
        bytes[at..at + Candidate::BYTES].copy_from_slice(&c.to_bytes());
    }
    buffer
}

fn prepare(ctx: u64, camera: &Camera, budget_ns: u64) -> Result<AxionRenderResult, i32> {
    let mut out = AxionRenderResult::default();
    let desc = camera.to_bytes();
    match unsafe { axion_render_prepare(ctx, desc.as_ptr(), 0.5, budget_ns, &raw mut out) } {
        AXION_OK => Ok(out),
        code => Err(code),
    }
}

fn u32_at(bytes: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap())
}

/// Les `id` retenus, lus dans `RENDER_OUT` ; chaque enregistrement désigne, par son index, le
/// candidat qui porte cet `id`.
fn retained(ctx: u64, result: &AxionRenderResult, candidates: &[Candidate]) -> Vec<u32> {
    let out = acquire(ctx, BufferKind::RenderOut, 0);
    let bytes = view(&out);
    let base = HEADER_BYTES;
    assert_eq!(
        u32_at(bytes, base),
        result.frame_id,
        "frame_id de l'en-tête"
    );
    assert_eq!(u32_at(bytes, base + 4), result.instance_count);
    assert_eq!(u32_at(bytes, base + 12), result.flags);
    (0..result.instance_count as usize)
        .map(|i| {
            let (index, id) = (
                u32_at(bytes, base + 16 + i * 16),
                u32_at(bytes, base + 16 + i * 16 + 4),
            );
            assert_eq!(candidates[index as usize].id, id, "index du retenu {i}");
            id
        })
        .collect()
}

#[test]
fn if05_prepare_culle_depose_et_n_attend_pas_la_session() {
    let mut ctx = 0u64;
    assert_eq!(
        unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_CLIENT, &raw mut ctx) },
        AXION_OK
    );

    // Un cube chargé : son emprise est publiée au contexte de rendu.
    let container = cube();
    let asset_in = acquire(ctx, BufferKind::AssetIn, container.len() as u64);
    view(&asset_in)[HEADER_BYTES..HEADER_BYTES + container.len()].copy_from_slice(&container);
    let mut handle = Handle::ABSENT;
    assert_eq!(
        unsafe {
            axion_asset_load(
                ctx,
                ASSET_ID,
                AXION_SECTION_NODE | AXION_SECTION_GEOM,
                &raw mut handle,
            )
        },
        AXION_OK
    );
    release(ctx, &asset_in);
    let asset = handle;
    let stale = Handle {
        index: handle.index,
        generation: handle.generation + 1,
    };
    let unknown = Handle {
        index: 77,
        generation: 1,
    };

    // T-520, T-521 : devant, derrière, trop loin, asset inconnu, handle périmé.
    let candidates = [
        candidate(10, asset, Vec3::new(0.0, 0.0, -10.0)),
        candidate(11, asset, Vec3::new(0.0, 0.0, 10.0)),
        candidate(12, asset, Vec3::new(0.0, 0.0, -300.0)),
        candidate(13, unknown, Vec3::new(0.0, 0.0, -5.0)),
        candidate(14, stale, Vec3::new(0.0, 0.0, -6.0)),
    ];
    write_candidates(ctx, &candidates);
    let first = prepare(ctx, &camera(5), 1_000_000_000).expect("préparation");
    assert_eq!(first.instance_count, 1);
    assert_eq!(first.triangle_estimate, 12, "les douze triangles du cube");
    assert_eq!(first.flags, 0);
    assert_eq!(retained(ctx, &first, &candidates), [10]);

    // R-301 : la frame se libère une fois, la sienne seulement.
    assert_eq!(
        unsafe { axion_render_release(ctx, first.frame_id + 1) },
        AXION_E_INVALID_HANDLE
    );
    assert_eq!(
        unsafe { axion_render_release(ctx, first.frame_id) },
        AXION_OK
    );
    assert_eq!(
        unsafe { axion_render_release(ctx, first.frame_id) },
        AXION_E_INVALID_HANDLE
    );

    // Une préparation sans libération de la précédente la clôt et la compte.
    let a = prepare(ctx, &camera(5), 1_000_000_000).unwrap();
    let b = prepare(ctx, &camera(5), 1_000_000_000).unwrap();
    assert_ne!(a.frame_id, b.frame_id);
    let unreleased = axion_native::render::with(ctx, |render| render.stats().unreleased).unwrap();
    assert_eq!(unreleased, 1);
    assert_eq!(unsafe { axion_render_release(ctx, b.frame_id) }, AXION_OK);

    // Refus nets : plus de candidats annoncés que n'en tient le tampon, caméra nulle.
    assert_eq!(
        prepare(ctx, &camera(1_000_000), 1_000_000_000),
        Err(AXION_E_INVALID_BUFFER)
    );
    let mut out = AxionRenderResult::default();
    assert_eq!(
        unsafe { axion_render_prepare(ctx, std::ptr::null(), 0.0, 0, &raw mut out) },
        AXION_E_INVALID_BUFFER
    );

    // 3 000 candidats : les lots au-delà du premier partent sur les workers. Le résultat est
    // celui du culling de référence, dans l'ordre des candidats.
    let many: Vec<Candidate> = (0..3000u32)
        .map(|i| {
            let a = i as f32 * 0.37;
            let r = (i % 150) as f32;
            candidate(
                i,
                asset,
                Vec3::new(a.cos() * r, (i % 7) as f32 - 3.0, a.sin() * r),
            )
        })
        .collect();
    let cube_bounds = LocalBounds::from_aabb(Vec3::ZERO, Vec3::ONE);
    let mut reference = Vec::new();
    let cam = camera(many.len() as u32);
    cull_scalar(
        &cam,
        &cam.frustum(),
        &many,
        |_| Some(cube_bounds),
        &mut reference,
    );
    let expected: Vec<u32> = reference.iter().map(|v| v.id).collect();
    assert!(!expected.is_empty() && expected.len() < many.len());
    write_candidates(ctx, &many);
    let full = prepare(ctx, &cam, 1_000_000_000).unwrap();
    assert_eq!(full.flags, 0, "un budget d'une seconde suffit");
    assert_eq!(retained(ctx, &full, &many), expected);
    assert_eq!(
        unsafe { axion_render_release(ctx, full.frame_id) },
        AXION_OK
    );

    // Budget nul : partiel ou non, rien de ce que la référence garde ne disparaît.
    let rushed = prepare(ctx, &cam, 0).unwrap();
    let got = retained(ctx, &rushed, &many);
    if rushed.flags & AXION_RENDER_DEGRADED == 0 {
        assert_eq!(got, expected);
    } else {
        assert!(
            expected.iter().all(|id| got.contains(id)),
            "un retenu a disparu"
        );
    }
    assert_eq!(
        unsafe { axion_render_release(ctx, rushed.frame_id) },
        AXION_OK
    );

    // T-524, le cas du solo : un autre thread tient le verrou de session (comme le pas physique
    // pendant `axion_sim_collect`) ; la préparation et ses tampons n'attendent pas.
    let (locked, wait) = std::sync::mpsc::channel();
    let holder = std::thread::spawn(move || {
        axion_native::context::with(ctx, false, |_| {
            locked.send(()).unwrap();
            std::thread::sleep(Duration::from_millis(600));
        })
        .unwrap();
    });
    wait.recv().unwrap();
    let started = Instant::now();
    let render_in = acquire(
        ctx,
        BufferKind::RenderIn,
        (many.len() * Candidate::BYTES) as u64,
    );
    let during = prepare(ctx, &cam, 1_000_000_000).unwrap();
    let elapsed = started.elapsed();
    assert!(
        elapsed < Duration::from_millis(300),
        "la préparation a attendu le verrou de session : {elapsed:?}"
    );
    assert_eq!(retained(ctx, &during, &many), expected);
    assert_eq!(
        unsafe { axion_render_release(ctx, during.frame_id) },
        AXION_OK
    );
    holder.join().unwrap();

    // Déchargé, l'asset n'est plus connu du rendu : plus rien n'est retenu.
    assert_eq!(unsafe { axion_asset_unload(ctx, handle) }, AXION_OK);
    let after = prepare(ctx, &cam, 1_000_000_000).unwrap();
    assert_eq!(after.instance_count, 0);
    assert_eq!(
        unsafe { axion_render_release(ctx, after.frame_id) },
        AXION_OK
    );
    release(ctx, &render_in);
    release(ctx, &acquire(ctx, BufferKind::RenderOut, 0));

    // Tout est rendu : l'arrêt est équilibré, tampons du rendu compris (R-322).
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK);

    // Une session suivante qui garde un tampon du rendu à l'arrêt : le bilan le voit.
    let mut ctx = 0u64;
    assert_eq!(
        unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_CLIENT, &raw mut ctx) },
        AXION_OK
    );
    acquire(ctx, BufferKind::RenderOut, 64);
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_E_LEAK);
}
