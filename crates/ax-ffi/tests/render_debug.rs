//! ADR-155, tranche 6b : les overlays `bones`, `nodes` et `pivots` traversent la frontière du rendu
//! (ABI 11). `axion_render_debug` dépose dans `RENDER_DEBUG`, au pool du contexte de rendu, la pose
//! que la dernière frame préparée a donnée à chaque instance animée : repère d'origine, relatif à la
//! caméra de cette frame, chaque plage au handle `ABSENT` (décisions de Killian).
//!
//! Deux bras à deux os écrits par code, pliés au tick 20 — le coude d'un quart de tour autour de z,
//! à (0, 1, 0) dans l'espace de l'asset —, le premier à 5 blocs, le second à 10 et tourné d'un quart
//! de tour autour de z.
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
use ax_model::dm::debug::{
    debug_flags, debug_frame, overlay, DebugRange, DebugSegment, DEBUG_HEADER_BYTES,
};
use ax_model::dm::handle::Handle;
use ax_render_prep::{Camera, Candidate};
use axion_native::abi::{
    axion_asset_load, axion_asset_unload, axion_buffer_acquire, axion_buffer_release, axion_init,
    axion_render_animate, axion_render_debug, axion_render_prepare, axion_render_release,
    axion_shutdown, AxionBufferInfo, AxionRenderResult, AXION_E_INVALID_BUFFER, AXION_OK,
    AXION_OVERLAY_BONES, AXION_OVERLAY_COLLIDERS, AXION_OVERLAY_NODES, AXION_OVERLAY_PIVOTS,
    AXION_SECTION_GEOM, AXION_SECTION_NODE, AXION_SIDE_CLIENT,
};
use axion_native::animation::{NODE_AXIS_LENGTH, PIVOT_CROSS};
use axion_native::render::IN_FRAME_HEADER;

const ASSET_ID: u64 = 0x0A51_0C0B_E000_06B0;
const TOUS: u64 = AXION_OVERLAY_BONES | AXION_OVERLAY_NODES | AXION_OVERLAY_PIVOTS;
const EPSILON: f32 = 1e-5;

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

/// Prépare puis rend une frame au tick 20 : chaque candidat, son `id`, sa position et son orientation.
fn frame(ctx: u64, asset: Handle, candidats: &[(u32, Vec3, Quat)]) {
    let input = acquire(
        ctx,
        BufferKind::RenderIn,
        (IN_FRAME_HEADER + candidats.len() * Candidate::BYTES) as u64,
    );
    let bytes = view(&input);
    bytes[HEADER_BYTES..HEADER_BYTES + 8].copy_from_slice(&20f64.to_le_bytes());
    for (rang, (id, position, rotation)) in candidats.iter().enumerate() {
        let candidate = Candidate {
            id: *id,
            asset: asset.index,
            generation: asset.generation,
            flags: 0,
            position: *position,
            scale: 1.0,
            rotation: *rotation,
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
    assert_eq!(result.instance_count as usize, candidats.len());
    release(ctx, &input);
    // Comme Java : les retenus lus, `RENDER_OUT` relâché.
    let out = acquire(ctx, BufferKind::RenderOut, 0);
    release(ctx, &out);
    // Java demande les overlays après avoir rendu la frame : la pose de chaque instance y survit.
    assert_eq!(
        unsafe { axion_render_release(ctx, result.frame_id) },
        AXION_OK
    );
}

/// Ce que `RENDER_DEBUG` porte après un `axion_render_debug`.
struct Trace {
    flags: u32,
    omitted: u32,
    ranges: Vec<DebugRange>,
    segments: Vec<DebugSegment>,
}

impl Trace {
    /// Les segments de la plage `rang`.
    fn segments_of(&self, rang: usize) -> &[DebugSegment] {
        let range = &self.ranges[rang];
        let first = range.first_segment as usize;
        &self.segments[first..first + range.segment_count as usize]
    }
}

/// Demande les overlays `mask`, puis relit `RENDER_DEBUG`.
fn tracer(ctx: u64, mask: u64, max_segments: u32) -> Trace {
    let mut size = u64::MAX;
    assert_eq!(
        unsafe { axion_render_debug(ctx, mask, max_segments, &raw mut size) },
        AXION_OK
    );
    let out = acquire(ctx, BufferKind::RenderDebug, 0);
    let bytes = view(&out);
    assert_eq!(
        u32::from_le_bytes(bytes[4..8].try_into().unwrap()),
        BufferKind::RenderDebug.as_u32(),
        "le kind de l'en-tête"
    );
    assert_eq!(
        u32::from_le_bytes(bytes[12..16].try_into().unwrap()),
        2,
        "le schéma de DEBUG"
    );
    let payload = &bytes[HEADER_BYTES..HEADER_BYTES + usize::try_from(size).unwrap()];
    let word = |at: usize| u32::from_le_bytes(payload[at..at + 4].try_into().unwrap());
    let (range_count, segment_count) = (word(0) as usize, word(4) as usize);
    assert_eq!(
        payload.len(),
        DEBUG_HEADER_BYTES + range_count * DebugRange::BYTES + segment_count * DebugSegment::BYTES
    );
    let ranges = (0..range_count)
        .map(|rang| {
            let at = DEBUG_HEADER_BYTES + rang * DebugRange::BYTES;
            DebugRange::read_le(payload[at..at + DebugRange::BYTES].try_into().unwrap())
        })
        .collect();
    let first = DEBUG_HEADER_BYTES + range_count * DebugRange::BYTES;
    let segments = (0..segment_count)
        .map(|rang| {
            let at = first + rang * DebugSegment::BYTES;
            DebugSegment::read_le(payload[at..at + DebugSegment::BYTES].try_into().unwrap())
        })
        .collect();
    let trace = Trace {
        flags: word(8),
        omitted: word(12),
        ranges,
        segments,
    };
    release(ctx, &out);
    trace
}

fn proche(a: [f32; 3], b: Vec3) -> bool {
    (Vec3::from(a) - b).abs().max_element() < EPSILON
}

/// Vrai si `segments` porte un segment de `a` à `b`.
fn porte(segments: &[DebugSegment], a: Vec3, b: Vec3) -> bool {
    segments.iter().any(|s| proche(s.a, a) && proche(s.b, b))
}

#[test]
fn adr155_les_overlays_de_l_animation_traversent_le_rendu() {
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

    // Un masque vide, ou qui porte un overlay que le rendu ne trace pas, est refusé ; un budget sans
    // sortie aussi.
    let mut size = 0u64;
    for mask in [
        0,
        AXION_OVERLAY_COLLIDERS,
        AXION_OVERLAY_BONES | AXION_OVERLAY_COLLIDERS,
    ] {
        assert_eq!(
            unsafe { axion_render_debug(ctx, mask, 100, &raw mut size) },
            AXION_E_INVALID_BUFFER,
            "masque {mask:#x}"
        );
    }
    assert_eq!(
        unsafe { axion_render_debug(ctx, TOUS, 100, std::ptr::null_mut()) },
        AXION_E_INVALID_BUFFER
    );
    // Aucune frame préparée : rien à tracer.
    assert!(tracer(ctx, TOUS, 100).ranges.is_empty());

    let mut commandes = jouer(1, asset);
    commandes.extend(jouer(2, asset));
    let mut applied = 0;
    assert_eq!(
        unsafe { axion_render_animate(ctx, commandes.as_ptr(), commandes.len(), &raw mut applied) },
        AXION_OK
    );
    assert_eq!(applied, 2);
    let quart_z = Quat::from_rotation_z(std::f32::consts::FRAC_PI_2);
    let (proche_pos, loin_pos) = (Vec3::new(0.0, 0.0, -5.0), Vec3::new(0.0, 0.0, -10.0));
    // Le plus lointain d'abord dans RENDER_IN : les plages suivent la distance, pas l'ordre d'envoi.
    frame(
        ctx,
        asset,
        &[(2, loin_pos, quart_z), (1, proche_pos, Quat::IDENTITY)],
    );

    let trace = tracer(ctx, TOUS, 1000);
    assert_eq!(trace.flags, 0);
    assert_eq!(trace.omitted, 0);
    let rangs: Vec<u32> = trace.ranges.iter().map(|r| r.overlay).collect();
    let (bones, nodes, pivots) = (
        overlay::rank(overlay::BONES),
        overlay::rank(overlay::NODES),
        overlay::rank(overlay::PIVOTS),
    );
    assert_eq!(
        rangs,
        [bones, nodes, pivots, bones, nodes, pivots],
        "le bras proche, puis le lointain, chacun par rang d'overlay"
    );
    for range in &trace.ranges {
        assert_eq!(range.handle, Handle::ABSENT);
        assert_eq!(range.frame, debug_frame::ORIGIN);
        assert_eq!(range.flags, 0);
    }

    // `bones` : de l'épaule, à l'origine de l'asset, au coude, à (0, 1, 0) ; l'épaule n'a pas d'os parent.
    assert_eq!(
        trace.segments_of(0).len(),
        1,
        "un seul os a un os parent : le coude"
    );
    assert!(porte(
        trace.segments_of(0),
        proche_pos,
        proche_pos + Vec3::Y
    ));
    // Le lointain, tourné d'un quart de tour autour de z : le coude part vers -x.
    assert!(porte(
        trace.segments_of(3),
        loin_pos,
        loin_pos + quart_z * Vec3::Y
    ));

    // `nodes` : trois axes par node ; ceux du coude, plié d'un quart de tour autour de z.
    let reperes = trace.segments_of(1);
    assert!(
        reperes.len() >= 9 && reperes.len().is_multiple_of(3),
        "{}",
        reperes.len()
    );
    let coude = proche_pos + Vec3::Y;
    assert!(porte(reperes, coude, coude + Vec3::Y * NODE_AXIS_LENGTH));
    assert!(porte(reperes, coude, coude - Vec3::X * NODE_AXIS_LENGTH));

    // `pivots` : une croix centrée sur le coude, seul node animé.
    let croix = trace.segments_of(2);
    assert_eq!(croix.len(), 3);
    for segment in croix {
        let (a, b) = (Vec3::from(segment.a), Vec3::from(segment.b));
        assert!(((a + b) * 0.5 - coude).length() < EPSILON);
        assert!((a.distance(b) - PIVOT_CROSS).abs() < EPSILON);
    }

    // Un overlay seul ne trace que lui.
    let seul = tracer(ctx, AXION_OVERLAY_BONES, 1000);
    assert_eq!(seul.ranges.len(), 2);
    assert!(seul.ranges.iter().all(|r| r.overlay == bones));

    // Le budget : la plage la plus proche qui tient, les autres omises et comptées, jamais coupées.
    let serre = tracer(ctx, AXION_OVERLAY_BONES | AXION_OVERLAY_PIVOTS, 1);
    assert_eq!(serre.ranges.len(), 1);
    assert_eq!(serre.ranges[0].overlay, bones);
    assert!(porte(
        serre.segments_of(0),
        proche_pos,
        proche_pos + Vec3::Y
    ));
    assert_eq!(serre.flags, debug_flags::TRUNCATED);
    assert_eq!(serre.omitted, 3);

    // La frame suivante ne retient que le lointain : seules ses plages restent.
    frame(ctx, asset, &[(2, loin_pos, quart_z)]);
    let suivante = tracer(ctx, TOUS, 1000);
    assert_eq!(suivante.ranges.len(), 3);
    assert!(porte(
        suivante.segments_of(0),
        loin_pos,
        loin_pos + quart_z * Vec3::Y
    ));

    // L'asset déchargé, l'arrêt ne signale aucune fuite (R-321).
    assert_eq!(unsafe { axion_asset_unload(ctx, asset) }, AXION_OK);
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK);
}
