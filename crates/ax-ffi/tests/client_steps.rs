//! T-392 (C-40, ADR-155 D7) : côté client, le pas n'exécute que les étapes que R-662 lui laisse. Le pas du
//! client est la préparation d'une frame : elle échantillonne l'animation des retenus animés (étape 2) et
//! propage leur graphe (étape 14), dans l'ordre de R-660, et rien d'autre — aucune étape de la
//! simulation, de l'intégration à la chaîne de dommage. Chaque graphe propagé se compte dans
//! `axion.scene.propagate_ns` (C-30), sans le verrou de session.
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
use ax_physics::{check_order, Stage};
use ax_render_prep::{Camera, Candidate};
use axion_native::abi::{
    axion_asset_load, axion_asset_unload, axion_buffer_acquire, axion_buffer_release, axion_init,
    axion_render_animate, axion_render_prepare, axion_render_release, axion_shutdown,
    AxionBufferInfo, AxionRenderResult, AXION_OK, AXION_SECTION_GEOM, AXION_SECTION_NODE,
    AXION_SIDE_CLIENT,
};
use axion_native::render::IN_FRAME_HEADER;
use axion_native::{context, render};

const ASSET_ID: u64 = 0x0A51_0C0B_E000_0392;

/// Les étapes que R-662 laisse au client : 2, 9, 13 et 14.
const ETAPES_DU_CLIENT: [Stage; 4] = [
    Stage::Animation,
    Stage::Deformation,
    Stage::Particles,
    Stage::SceneGraph,
];

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

/// Prépare puis rend une frame au tick 10, un candidat par `id`, à 5 blocs de plus chacun.
fn frame(ctx: u64, asset: Handle, ids: &[u32]) {
    let input = acquire(
        ctx,
        BufferKind::RenderIn,
        (IN_FRAME_HEADER + ids.len() * Candidate::BYTES) as u64,
    );
    let bytes = view(&input);
    bytes[HEADER_BYTES..HEADER_BYTES + 8].copy_from_slice(&10f64.to_le_bytes());
    for (rang, id) in ids.iter().enumerate() {
        let candidate = Candidate {
            id: *id,
            asset: asset.index,
            generation: asset.generation,
            flags: 0,
            position: Vec3::new(0.0, 0.0, -5.0 * (rang + 1) as f32),
            scale: 1.0,
            rotation: Quat::IDENTITY,
        };
        let at = HEADER_BYTES + IN_FRAME_HEADER + rang * Candidate::BYTES;
        bytes[at..at + Candidate::BYTES].copy_from_slice(&candidate.to_bytes());
    }
    let mut result = AxionRenderResult::default();
    let desc = camera(ids.len() as u32).to_bytes();
    assert_eq!(
        unsafe { axion_render_prepare(ctx, desc.as_ptr(), 0.0, 1_000_000_000, &raw mut result) },
        AXION_OK
    );
    assert_eq!(result.instance_count as usize, ids.len());
    release(ctx, &input);
    let out = acquire(ctx, BufferKind::RenderOut, 0);
    release(ctx, &out);
    assert_eq!(
        unsafe { axion_render_release(ctx, result.frame_id) },
        AXION_OK
    );
}

/// Le nombre de propagations mesurées dans `axion.scene.propagate_ns`, lu dans l'export (R-502).
fn propagations(ctx: u64) -> u64 {
    let json = context::with(ctx, false, |session| session.metrics_json()).unwrap();
    let line = json
        .lines()
        .find(|line| line.contains("\"axion.scene.propagate_ns\""))
        .expect("la métrique est déclarée");
    let count = line
        .split("\"count\": ")
        .nth(1)
        .expect("une durée porte son nombre de mesures");
    count
        .split(|c: char| !c.is_ascii_digit())
        .next()
        .unwrap()
        .parse()
        .unwrap()
}

#[test]
fn t392_le_pas_du_client_n_execute_que_les_etapes_que_r662_lui_laisse() {
    let config = {
        let mut out = Vec::new();
        ciborium::into_writer(&ciborium::Value::Map(Vec::new()), &mut out).expect("CBOR");
        out
    };
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

    // Sans instance animée, le client n'exécute aucune étape du pas.
    frame(ctx, asset, &[3]);
    assert!(render::with(ctx, |render| render.stages().is_empty()).unwrap());
    assert_eq!(propagations(ctx), 0);

    let mut commandes = jouer(1, asset);
    commandes.extend(jouer(2, asset));
    let mut applied = 0;
    assert_eq!(
        unsafe { axion_render_animate(ctx, commandes.as_ptr(), commandes.len(), &raw mut applied) },
        AXION_OK
    );
    assert_eq!(applied, 2);

    // Deux bras animés et un qui ne l'est pas : l'étape 2 puis l'étape 14, une fois pour la frame.
    frame(ctx, asset, &[1, 2, 3]);
    let etapes = render::with(ctx, |render| render.stages().to_vec()).unwrap();
    assert_eq!(etapes, [Stage::Animation, Stage::SceneGraph]);
    assert_eq!(check_order(&etapes), Ok(()), "l'ordre de R-660");
    for etape in &etapes {
        assert!(
            ETAPES_DU_CLIENT.contains(etape),
            "{etape:?} (étape {}) n'est pas du client (R-662)",
            etape.index()
        );
    }
    assert_eq!(
        propagations(ctx),
        2,
        "un graphe propagé par instance animée, chacun mesuré"
    );

    // La frame suivante ne retient que le bras immobile : le journal est celui de sa seule frame.
    frame(ctx, asset, &[3]);
    assert!(render::with(ctx, |render| render.stages().is_empty()).unwrap());
    assert_eq!(propagations(ctx), 2);

    // La simulation n'a rien exécuté : aucun cycle ne s'est ouvert, aucune étape 1 à 15 inscrite.
    assert!(context::with(ctx, false, |session| session.cycle_stages().is_empty()).unwrap());

    // L'asset déchargé, l'arrêt ne signale aucune fuite (R-321).
    assert_eq!(unsafe { axion_asset_unload(ctx, asset) }, AXION_OK);
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK);
}
