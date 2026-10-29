//! ADR-155, tranche 5b : la palette d'os d'un retenu skinné traverse la frontière du rendu
//! (`RENDER_OUT` schéma 4), et `render.max_skinned_instances` la réserve aux plus proches (R-791).
//!
//! Deux bras à deux os écrits par code, pliés par le même clip, à 5 et à 10 blocs de la caméra ; le
//! plafond vaut 1. Le plus proche porte sa palette, après les matrices de ses dessins ; l'autre se
//! dessine au repos, et l'en-tête le compte.
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
    AxionBufferInfo, AxionRenderResult, AXION_OK, AXION_SECTION_GEOM, AXION_SECTION_NODE,
    AXION_SIDE_CLIENT,
};
use axion_native::render::{
    IN_FRAME_HEADER, MATRIX_FLOATS, NO_MATRICES, NO_PALETTE, OUT_HEADER, OUT_RECORD,
};

const ASSET_ID: u64 = 0x0A51_0C0B_E000_0791;

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

/// La configuration du client : un seul retenu skinné par frame.
fn configuration() -> Vec<u8> {
    let mut out = Vec::new();
    ciborium::into_writer(
        &ciborium::Value::Map(vec![(
            ciborium::Value::Text("render.max_skinned_instances".to_owned()),
            ciborium::Value::Integer(1.into()),
        )]),
        &mut out,
    )
    .expect("CBOR");
    out
}

/// Projection en perspective d'OpenGL, comme celle de Minecraft.
fn camera(count: u32) -> Camera {
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
        candidate_count: count,
        max_instances: 0,
    }
}

/// Une commande `PLAY` du clip 0 sur la couche 0 de l'assembly `id`, au tick 0.
fn jouer(id: u32, asset: Handle) -> Vec<u8> {
    let mut out = Vec::new();
    for value in [id, 0, asset.index, asset.generation] {
        out.extend_from_slice(&value.to_le_bytes());
    }
    out.extend_from_slice(&0f64.to_le_bytes());
    for value in [anim_action::PLAY, 0, 0] {
        out.extend_from_slice(&value.to_le_bytes());
    }
    for value in [1.0f32, 0.0, 1.0] {
        out.extend_from_slice(&value.to_le_bytes());
    }
    out.extend_from_slice(&[0; 8]);
    out
}

/// Un retenu lu dans `RENDER_OUT` : son `id`, ses dessins, sa première matrice, sa palette.
struct Retenu {
    id: u32,
    dessins: u32,
    matrice: u32,
    palette: u32,
}

#[test]
fn r791_la_palette_du_plus_proche_traverse_le_rendu_l_autre_reste_au_repos() {
    let config = configuration();
    let mut ctx = 0u64;
    assert_eq!(
        unsafe {
            axion_init(
                config.as_ptr(),
                config.len(),
                AXION_SIDE_CLIENT,
                &raw mut ctx,
            )
        },
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
        modeles_animes::bras().as_bytes(),
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

    // Les deux bras jouent `plier` depuis le tick 0.
    let mut commandes = jouer(1, asset);
    commandes.extend(jouer(2, asset));
    let mut applied = 0;
    assert_eq!(
        unsafe { axion_render_animate(ctx, commandes.as_ptr(), commandes.len(), &raw mut applied) },
        AXION_OK
    );
    assert_eq!(applied, 2);

    // Le plus lointain d'abord dans RENDER_IN : la palette suit la distance, pas l'ordre d'envoi.
    let candidats = [(2u32, -10.0f32), (1, -5.0)];
    let input = acquire(
        ctx,
        BufferKind::RenderIn,
        (IN_FRAME_HEADER + candidats.len() * Candidate::BYTES) as u64,
    );
    let bytes = view(&input);
    bytes[HEADER_BYTES..HEADER_BYTES + 8].copy_from_slice(&20f64.to_le_bytes());
    for (rang, (id, z)) in candidats.iter().enumerate() {
        let candidate = Candidate {
            id: *id,
            asset: asset.index,
            generation: asset.generation,
            flags: 0,
            position: Vec3::new(0.0, 0.0, *z),
            scale: 1.0,
            rotation: Quat::IDENTITY,
        };
        let at = HEADER_BYTES + IN_FRAME_HEADER + rang * Candidate::BYTES;
        bytes[at..at + Candidate::BYTES].copy_from_slice(&candidate.to_bytes());
    }
    let mut result = AxionRenderResult::default();
    let desc = camera(candidats.len() as u32).to_bytes();
    assert_eq!(
        unsafe { axion_render_prepare(ctx, desc.as_ptr(), 0.0, 1_000_000_000, &raw mut result) },
        AXION_OK
    );
    assert_eq!(result.instance_count, 2);

    let out = acquire(ctx, BufferKind::RenderOut, 0);
    let bytes = view(&out);
    let word = |at: usize| u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap());
    let base = HEADER_BYTES;
    assert_eq!(
        u32::from_le_bytes(bytes[12..16].try_into().unwrap()),
        BufferKind::RenderOut.schema_version(),
        "le schéma de l'en-tête du tampon"
    );
    assert_eq!(BufferKind::RenderOut.schema_version(), 4);
    let mesh_total = word(base + 8) as usize;
    let matrix_total = word(base + 16) as usize;
    assert_eq!(
        word(base + 20),
        1,
        "un retenu skinné laissé au repos par le plafond"
    );
    let retenus: Vec<Retenu> = (0..2)
        .map(|rang| {
            let at = base + OUT_HEADER + rang * OUT_RECORD;
            Retenu {
                id: word(at + 4),
                dessins: word(at + 20),
                matrice: word(at + 24),
                palette: word(at + 28),
            }
        })
        .collect();
    let proche = retenus.iter().find(|r| r.id == 1).expect("le bras proche");
    let loin = retenus
        .iter()
        .find(|r| r.id == 2)
        .expect("le bras lointain");
    assert_eq!(loin.palette, NO_PALETTE, "au-delà du plafond, au repos");
    assert_ne!(loin.matrice, NO_MATRICES, "ses dessins restent animés");
    assert_ne!(proche.palette, NO_PALETTE);
    assert_eq!(
        proche.palette,
        proche.matrice + proche.dessins,
        "la palette suit les matrices de ses dessins"
    );

    // La palette du proche : l'épaule à l'identité, le coude plié d'un quart de tour autour de son
    // pivot à (0, 1, 0) — T-540.
    let table = base + OUT_HEADER + 2 * OUT_RECORD + mesh_total * 4;
    let matrice = |rang: u32| -> [f32; 12] {
        let at = table + rang as usize * MATRIX_FLOATS * 4;
        let mut m = [0f32; 12];
        for (i, value) in m.iter_mut().enumerate() {
            *value = f32::from_le_bytes(bytes[at + i * 4..at + i * 4 + 4].try_into().unwrap());
        }
        m
    };
    let identite = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0];
    let coude = [0.0, 1.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 0.0];
    let proche_de = |m: [f32; 12], attendue: [f32; 12]| {
        m.iter().zip(attendue).all(|(a, b)| (a - b).abs() < 1e-5)
    };
    let palette = [matrice(proche.palette), matrice(proche.palette + 1)];
    assert!(
        palette.iter().any(|m| proche_de(*m, identite))
            && palette.iter().any(|m| proche_de(*m, coude)),
        "{palette:?}"
    );
    assert_eq!(
        matrix_total as u32,
        proche.palette + 2,
        "les deux os du proche ferment la table"
    );
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
    let stats = axion_native::render::with(ctx, |render| render.stats()).unwrap();
    assert_eq!(stats.skinned, 1);
    assert_eq!(stats.skinned_capped, 1);
    // L'asset déchargé, l'arrêt ne signale aucune fuite (R-321).
    assert_eq!(unsafe { axion_asset_unload(ctx, asset) }, AXION_OK);
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK);
}
