//! T-368 (§18.7) : 1000 assemblies animées — budget respecté ou dégradation propre.
//!
//! La préparation évalue ses retenus animés sous l'échéance de la frame (R-300) : dans l'ordre de leur
//! `id`, à partir de celui qui suit le dernier évalué, au moins un par frame. Passée l'échéance, les autres
//! gardent la pose de leur dernière évaluation, et la frame se dit dégradée ; la suivante reprend où
//! celle-ci s'est arrêtée.
//!
//! - 1000 bras animés, au budget par défaut : chaque frame évalue tout son monde dans l'échéance, ou se dit
//!   dégradée et compte ceux qu'elle a reportés ; tous restent dessinés, à une pose finie.
//! - Cinq pendules sous un budget d'une nanoseconde : un seul s'évalue par frame, chacun à son tour, les
//!   autres gardent leur pose — celle du repos tant qu'ils n'ont jamais été évalués.
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
use ax_physics::Stage;
use ax_render_prep::{Camera, Candidate};
use axion_native::abi::{
    axion_asset_load, axion_asset_unload, axion_buffer_acquire, axion_buffer_release, axion_init,
    axion_render_animate, axion_render_prepare, axion_render_release, axion_shutdown,
    AxionBufferInfo, AxionRenderResult, AXION_OK, AXION_SECTION_GEOM, AXION_SECTION_NODE,
    AXION_SIDE_CLIENT,
};
use axion_native::render::{
    self, AXION_RENDER_DEGRADED, IN_FRAME_HEADER, MATRIX_FLOATS, NO_MATRICES, OUT_HEADER,
    OUT_RECORD,
};

/// `budgets.render_prep_ns` par défaut.
const BUDGET_NS: u64 = 2_000_000;

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

/// Compile et charge un modèle animé.
fn charger(ctx: u64, gltf: &str, asset_id: u64) -> Handle {
    let options = CompileOptions {
        asset_id,
        source_hash: 0,
        limits: ImportLimits::new(1 << 20),
        dynamic_body: true,
        authored: AssetOptions::DEFAULT,
    };
    let container = compile(gltf.as_bytes(), SourceFormat::Gltf, &options, |_| None)
        .expect("compilation")
        .bytes;
    let asset_in = acquire(ctx, BufferKind::AssetIn, container.len() as u64);
    view(&asset_in)[HEADER_BYTES..HEADER_BYTES + container.len()].copy_from_slice(&container);
    let mut asset = Handle::ABSENT;
    assert_eq!(
        unsafe {
            axion_asset_load(
                ctx,
                asset_id,
                AXION_SECTION_NODE | AXION_SECTION_GEOM,
                &raw mut asset,
            )
        },
        AXION_OK
    );
    release(ctx, &asset_in);
    asset
}

/// Joue le clip 0 de l'asset sur chaque assembly de `ids`, au tick 0.
fn jouer(ctx: u64, asset: Handle, ids: impl Iterator<Item = u32>) {
    let mut out = Vec::new();
    let mut nombre = 0;
    for id in ids {
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
        nombre += 1;
    }
    let mut applied = 0;
    assert_eq!(
        unsafe { axion_render_animate(ctx, out.as_ptr(), out.len(), &raw mut applied) },
        AXION_OK
    );
    assert_eq!(applied, nombre);
}

/// Ce qu'une frame a donné : ses drapeaux, sa durée, et la première matrice de chaque retenu par `id`.
struct Frame {
    flags: u32,
    prepare_ns: u64,
    /// Par retenu : son `id`, et sa première matrice de dessin, `None` s'il se dessine au repos.
    matrices: Vec<(u32, Option<[f32; MATRIX_FLOATS]>)>,
}

/// Prépare puis rend une frame au tick `tick` sous `budget_ns`, chaque `id` à sa place, tous retenus.
fn frame(ctx: u64, asset: Handle, ids: &[u32], tick: f64, budget_ns: u64) -> Frame {
    let input = acquire(
        ctx,
        BufferKind::RenderIn,
        (IN_FRAME_HEADER + ids.len() * Candidate::BYTES) as u64,
    );
    let bytes = view(&input);
    bytes[HEADER_BYTES..HEADER_BYTES + 8].copy_from_slice(&tick.to_le_bytes());
    for (rang, id) in ids.iter().enumerate() {
        let candidate = Candidate {
            id: *id,
            asset: asset.index,
            generation: asset.generation,
            flags: Candidate::NO_CULL,
            position: Vec3::new(
                (rang % 40) as f32 - 20.0,
                0.0,
                -5.0 - (rang / 40) as f32 * 2.0,
            ),
            scale: 1.0,
            rotation: Quat::IDENTITY,
        };
        let at = HEADER_BYTES + IN_FRAME_HEADER + rang * Candidate::BYTES;
        bytes[at..at + Candidate::BYTES].copy_from_slice(&candidate.to_bytes());
    }
    let f = 1.0 / 35f32.to_radians().tan();
    let mut m = [0f32; 16];
    m[0] = f / (16.0 / 9.0);
    m[5] = f;
    m[10] = -1.0001;
    m[11] = -1.0;
    m[14] = -0.1;
    let camera = Camera {
        projection: Mat4::from_cols_array(&m),
        view_rotation: Quat::IDENTITY,
        viewport_width: 1920,
        viewport_height: 1080,
        max_distance: 128.0,
        lod_bias: 0,
        quality: 2,
        candidate_count: ids.len() as u32,
        max_instances: 0,
    };
    let desc = camera.to_bytes();
    let mut result = AxionRenderResult::default();
    assert_eq!(
        unsafe { axion_render_prepare(ctx, desc.as_ptr(), 0.0, budget_ns, &raw mut result) },
        AXION_OK
    );
    assert_eq!(result.instance_count as usize, ids.len(), "tous retenus");
    release(ctx, &input);

    let out = acquire(ctx, BufferKind::RenderOut, 0);
    let bytes = view(&out);
    let word = |at: usize| u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap());
    let base = HEADER_BYTES;
    let retenus = ids.len();
    let table = base + OUT_HEADER + retenus * OUT_RECORD + word(base + 8) as usize * 4;
    let matrices = (0..retenus)
        .map(|rang| {
            let at = base + OUT_HEADER + rang * OUT_RECORD;
            let premiere = word(at + 24);
            let matrice = (premiere != NO_MATRICES).then(|| {
                let debut = table + premiere as usize * MATRIX_FLOATS * 4;
                let mut m = [0f32; MATRIX_FLOATS];
                for (i, value) in m.iter_mut().enumerate() {
                    let at = debut + i * 4;
                    *value = f32::from_le_bytes(bytes[at..at + 4].try_into().unwrap());
                }
                m
            });
            (word(at + 4), matrice)
        })
        .collect();
    release(ctx, &out);
    assert_eq!(
        unsafe { axion_render_release(ctx, result.frame_id) },
        AXION_OK
    );
    let stats = render::with(ctx, |render| render.stats()).unwrap();
    Frame {
        flags: result.flags,
        prepare_ns: stats.last_prepare_ns,
        matrices,
    }
}

fn reportes(ctx: u64) -> u64 {
    render::with(ctx, |render| render.stats().animation_deferred).unwrap()
}

#[test]
fn t368_1000_assemblies_animees_budget_respecte_ou_degradation_propre() {
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

    // 1000 bras animés, au budget par défaut, trente frames.
    let bras = charger(ctx, &modeles_animes::bras(), 0x0A51_0C0B_E000_0368);
    let ids: Vec<u32> = (1..=1000).collect();
    jouer(ctx, bras, ids.iter().copied());
    let mut durees = Vec::new();
    for rang in 0..30 {
        let avant = reportes(ctx);
        let frame = frame(ctx, bras, &ids, f64::from(rang) * 0.5, BUDGET_NS);
        let reportes = reportes(ctx) - avant;
        let degradee = frame.flags & AXION_RENDER_DEGRADED != 0;
        assert_eq!(
            degradee,
            reportes > 0,
            "une frame se dit dégradée si, et seulement si, elle a reporté des poses"
        );
        let animation = render::with(ctx, |render| {
            render.stage_duration(Stage::Animation) + render.stage_duration(Stage::SceneGraph)
        })
        .unwrap();
        assert!(
            degradee || animation <= BUDGET_NS,
            "frame {rang} : {animation} ns d'animation sans dégradation, pour un budget de {BUDGET_NS} ns"
        );
        for (id, matrice) in &frame.matrices {
            let matrice = matrice.unwrap_or_else(|| panic!("le bras {id} se dessine animé"));
            assert!(matrice.iter().all(|value| value.is_finite()), "bras {id}");
        }
        durees.push((frame.prepare_ns, animation, reportes));
    }
    durees.sort_unstable();
    eprintln!(
        "T-368 : 1000 bras animés, préparation de {} à {} ns (médiane {} ns), animation jusqu'à {} ns, {} pose(s) \
         reportée(s) sur 30 frames, budget {BUDGET_NS} ns",
        durees[0].0,
        durees[29].0,
        durees[15].0,
        durees.iter().map(|d| d.1).max().unwrap(),
        durees.iter().map(|d| d.2).sum::<u64>()
    );
    assert_eq!(unsafe { axion_asset_unload(ctx, bras) }, AXION_OK);

    // Cinq pendules sous une nanoseconde de budget : un seul s'évalue par frame, chacun à son tour.
    let pendule = charger(ctx, &modeles_animes::pendule(), 0x0A51_0C0B_E000_0369);
    let pendules: Vec<u32> = (2001..=2005).collect();
    jouer(ctx, pendule, pendules.iter().copied());
    // À 0,6 s, le pendule évalué est à 1 bloc en x ; celui qui ne l'a jamais été, au repos.
    for tour in 1..=pendules.len() {
        let avant = reportes(ctx);
        let frame = frame(ctx, pendule, &pendules, 12.0, 1);
        assert_ne!(frame.flags & AXION_RENDER_DEGRADED, 0, "frame dégradée");
        assert_eq!(reportes(ctx) - avant, 4, "un seul évalué, quatre reportés");
        assert_eq!(
            render::with(ctx, |render| render.stages().to_vec()).unwrap(),
            [Stage::Animation, Stage::SceneGraph],
            "au moins un par frame : l'animation avance toujours"
        );
        let bouges: Vec<u32> = frame
            .matrices
            .iter()
            .filter(|(_, matrice)| matrice.expect("animé")[9] > 0.5)
            .map(|(id, _)| *id)
            .collect();
        assert_eq!(
            bouges,
            pendules[..tour],
            "tour {tour} : chacun à son tour, dans l'ordre des id, les autres à leur pose d'avant"
        );
    }
    assert_eq!(unsafe { axion_asset_unload(ctx, pendule) }, AXION_OK);
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK);
}
