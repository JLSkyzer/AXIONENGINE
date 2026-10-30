//! ADR-156 D3, D4 exercé par l'ABI comme Java le fera : l'asset chargé avec `NODE | PHYS | ANIM`,
//! le `DEFN` écrit dans `ASSET_IN`, `axion_definition_compile` qui répond dans `ASSET_OUT` — le
//! résumé ou le refus —, et `axion_definition_remove`.
//!
//! Un seul test, son propre binaire : la session native est globale au processus.

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

use ax_asset::a3d::{
    encode_animations, encode_colliders, encode_nodes, A3dWriter, AnimationTable, SectionTag,
};
use ax_asset::compile::COMPILER_VERSION;
use ax_model::buffer::{BufferKind, HEADER_BYTES};
use ax_model::dm::animation::{channel, interp, AnimationDesc, TrackDesc};
use ax_model::dm::commands::AssemblyBodyEntry;
use ax_model::dm::definition::{
    reference_category, summary_flags, Definition, DefinitionHeader, DefinitionRef,
    DefinitionRefusal, DefinitionSummary,
};
use ax_model::dm::geometry::Transform;
use ax_model::dm::handle::Handle;
use ax_model::dm::physics::{BodyDesc, ColliderDesc, ColliderShape};
use ax_model::dm::scene::{name_hash, NodeDesc, NONE_U16, NONE_U32, NO_PARENT};
use axion_native::abi::{
    axion_asset_load, axion_asset_unload, axion_buffer_acquire, axion_buffer_release,
    axion_definition_compile, axion_definition_remove, axion_init, axion_last_error,
    axion_shutdown, AxionBufferInfo, AXION_E_INVALID_BUFFER, AXION_E_INVALID_HANDLE, AXION_OK,
    AXION_SECTION_ANIM, AXION_SECTION_NODE, AXION_SECTION_PHYS, AXION_SECTION_SKEL,
    AXION_SIDE_SERVER,
};

static CONTEXTE: std::sync::Mutex<()> = std::sync::Mutex::new(());

const ASSET_ID: u64 = 0x0A51_0C0B_E000_0156;
const DEMI_RACINE: f32 = core::f32::consts::FRAC_1_SQRT_2;

fn node(name: &str, parent: u32, x: f32, collider: u32) -> NodeDesc {
    NodeDesc {
        name_hash: name_hash(name),
        parent,
        local: Transform {
            translation: [x, 0.0, 0.0],
            ..Transform::identity()
        },
        flags: 0,
        mesh: NONE_U32,
        collider,
        bone: NONE_U32,
        part: NONE_U16,
        region: NONE_U16,
        lod_mask: u8::MAX,
        state: 0,
        mesh_count: 0,
    }
}

fn boite() -> ColliderDesc {
    ColliderDesc {
        shape: ColliderShape::Box {
            half_extents: [0.25; 3],
        },
        local: Transform::identity(),
        material: NONE_U16,
        _pad: 0,
        group: u32::MAX,
        mask: u32::MAX,
        flags: 0,
        density: 1000.0,
        damage_zone: NONE_U16,
        part: NONE_U16,
        region: NONE_U16,
        _pad2: 0,
        hull_points_offset: 0,
        hull_points_count: 0,
    }
}

/// Une base et une porte, chacune avec sa boîte ; un clip, `tourner`, anime la porte.
fn porte() -> Vec<u8> {
    let nodes = [node("base", NO_PARENT, 0.0, 0), node("porte", 0, 2.0, 1)];
    let names = ["base".to_owned(), "porte".to_owned()];
    let anim = encode_animations(&AnimationTable {
        animations: vec![AnimationDesc {
            name_hash: name_hash("tourner"),
            duration: 1.0,
            track_offset: 0,
            track_count: 1,
            flags: 0,
        }],
        names: vec!["tourner".to_owned()],
        tracks: vec![TrackDesc {
            target_node: 1,
            channel: channel::ROTATION,
            interp: interp::LINEAR,
            _pad: [0; 2],
            key_offset: 0,
            key_count: 2,
        }],
        keys: vec![
            0.0,
            0.0,
            0.0,
            0.0,
            1.0,
            1.0,
            0.0,
            0.0,
            DEMI_RACINE,
            DEMI_RACINE,
        ],
    })
    .expect("ANIM");
    let mut writer = A3dWriter::new(ASSET_ID, 0, COMPILER_VERSION);
    writer
        .section(
            SectionTag::NODE,
            &encode_nodes(&nodes, &names).expect("NODE"),
        )
        .expect("NODE");
    writer
        .section(
            SectionTag::PHYS,
            &encode_colliders(&[boite(), boite()], &[], &[]).expect("PHYS"),
        )
        .expect("PHYS");
    writer.section(SectionTag::ANIM, &anim).expect("ANIM");
    writer.finish().expect("conteneur")
}

/// Une definition d'un seul corps, qui nomme `noms` — des nodes — par ses références.
fn definition(bodies: Vec<AssemblyBodyEntry>, noms: &[&str]) -> Vec<u8> {
    let mut bytes = Vec::new();
    Definition {
        header: DefinitionHeader {
            flags: 0,
            group: 0b10,
            mask: u32::MAX,
            assembly_mass: 0.0,
            ref_count: 0,
            body_count: 0,
            joint_count: 0,
            jam_points: 0,
            part_count: 0,
            physics: BodyDesc::undeclared(1),
            implicit: BodyDesc::undeclared(2),
        },
        refs: noms
            .iter()
            .map(|nom| DefinitionRef {
                name_hash: name_hash(nom),
                category: reference_category::NODE,
            })
            .collect(),
        bodies,
        joints: Vec::new(),
        jam_points: Vec::new(),
        parts: Vec::new(),
    }
    .write_le(&mut bytes);
    bytes
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

fn release(ctx: u64, buffer: AxionBufferInfo) {
    assert_eq!(
        unsafe { axion_buffer_release(ctx, buffer.kind, buffer.generation) },
        AXION_OK
    );
}

/// Écrit `bytes` dans la charge utile d'`ASSET_IN`, comme Java le fait ; le tampon reste acquis
/// jusqu'après l'appel qui le lit — relâché, il n'existe plus.
fn write_asset_in(ctx: u64, bytes: &[u8]) -> AxionBufferInfo {
    let buffer = acquire(ctx, BufferKind::AssetIn, bytes.len() as u64);
    let view = unsafe {
        std::slice::from_raw_parts_mut(buffer.ptr, usize::try_from(buffer.capacity).unwrap())
    };
    view[HEADER_BYTES..HEADER_BYTES + bytes.len()].copy_from_slice(bytes);
    buffer
}

fn read_asset_out(ctx: u64, size: u64) -> Vec<u8> {
    let buffer = acquire(ctx, BufferKind::AssetOut, 0);
    let view = unsafe {
        std::slice::from_raw_parts(buffer.ptr, usize::try_from(buffer.capacity).unwrap())
    };
    let payload = view[HEADER_BYTES..HEADER_BYTES + usize::try_from(size).unwrap()].to_vec();
    release(ctx, buffer);
    payload
}

fn load(ctx: u64, mask: u32) -> Result<Handle, i32> {
    let asset_in = write_asset_in(ctx, &porte());
    let mut handle = Handle::ABSENT;
    let code = unsafe { axion_asset_load(ctx, ASSET_ID, mask, &raw mut handle) };
    release(ctx, asset_in);
    match code {
        AXION_OK => Ok(handle),
        code => Err(code),
    }
}

/// Compile `defn` sous l'empreinte `hash` : le code, et la réponse d'`ASSET_OUT` s'il y en a une.
fn compile(ctx: u64, hash: u64, asset: Handle, defn: &[u8]) -> (i32, Option<Vec<u8>>) {
    let asset_in = write_asset_in(ctx, defn);
    let mut size = u64::MAX;
    let code =
        unsafe { axion_definition_compile(ctx, hash, asset, defn.len() as u64, &raw mut size) };
    release(ctx, asset_in);
    (code, (size != u64::MAX).then(|| read_asset_out(ctx, size)))
}

/// Le dernier message d'erreur de la session.
fn last_error(ctx: u64) -> String {
    let mut bytes = vec![0u8; 1024];
    let mut len = 0usize;
    unsafe { axion_last_error(ctx, bytes.as_mut_ptr(), bytes.len(), &raw mut len) };
    bytes.truncate(len.min(1024));
    String::from_utf8_lossy(&bytes).into_owned()
}

fn corps(root_node: u32) -> AssemblyBodyEntry {
    AssemblyBodyEntry {
        desc: BodyDesc::undeclared(1),
        root_node,
    }
}

#[test]
fn adr156_une_definition_se_compile_s_inscrit_se_refuse_et_se_retire_par_l_abi() {
    let _contexte = CONTEXTE
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let mut ctx = 0u64;
    assert_eq!(
        unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut ctx) },
        AXION_OK
    );

    // Le masque d'IF-06 : PHYS avec NODE, ANIM et SKEL avec PHYS.
    assert_eq!(load(ctx, AXION_SECTION_PHYS), Err(AXION_E_INVALID_BUFFER));
    assert_eq!(
        load(ctx, AXION_SECTION_NODE | AXION_SECTION_ANIM),
        Err(AXION_E_INVALID_BUFFER)
    );
    assert_eq!(
        load(ctx, AXION_SECTION_NODE | AXION_SECTION_SKEL),
        Err(AXION_E_INVALID_BUFFER)
    );
    let asset = load(
        ctx,
        AXION_SECTION_NODE | AXION_SECTION_PHYS | AXION_SECTION_ANIM | AXION_SECTION_SKEL,
    )
    .unwrap_or_else(|code| {
        panic!(
            "chargement pour une definition : {code}, {}",
            last_error(ctx)
        )
    });

    // Un seul corps à la racine : la porte s'anime, le serveur joue l'animation (R-1430).
    let (code, reponse) = compile(ctx, 1, asset, &definition(Vec::new(), &[]));
    assert_eq!(code, AXION_OK);
    let resume = DefinitionSummary::read_le(&reponse.expect("résumé")).expect("lisible");
    assert_eq!(resume.roots, [0]);
    assert_eq!(resume.joint_count, 0);
    assert_eq!(resume.flags, summary_flags::SERVER_ANIMATED);
    assert!(resume.warnings.is_empty());

    // R-580 : un nom introuvable refuse la definition, la référence fautive désignée.
    let (code, reponse) = compile(ctx, 2, asset, &definition(vec![corps(0)], &["capot"]));
    assert_eq!(code, -7001);
    let refus = DefinitionRefusal::read_le(&reponse.expect("refus")).expect("lisible");
    assert_eq!((refus.code, refus.reference), (-7001, 0));
    assert!(refus.message.contains("node"), "{}", refus.message);

    // Une compilation remplace la précédente ; un refus la retire.
    let (code, _) = compile(ctx, 3, asset, &definition(vec![corps(0)], &["porte"]));
    assert_eq!(code, AXION_OK);
    let (code, _) = compile(ctx, 3, asset, &definition(vec![corps(0)], &["capot"]));
    assert_eq!(code, -7001);
    assert_eq!(
        unsafe { axion_definition_remove(ctx, 3) },
        AXION_E_INVALID_HANDLE,
        "le refus a retiré la definition"
    );
    assert_eq!(
        unsafe { axion_definition_remove(ctx, 2) },
        AXION_E_INVALID_HANDLE,
        "une definition refusée n'est jamais inscrite"
    );
    assert_eq!(unsafe { axion_definition_remove(ctx, 1) }, AXION_OK);
    assert_eq!(
        unsafe { axion_definition_remove(ctx, 1) },
        AXION_E_INVALID_HANDLE,
        "déjà retirée"
    );

    // Les défauts de Java : un DEFN tronqué, un asset sans PHYS, un handle périmé. Aucune réponse,
    // le registre inchangé.
    let defn = definition(Vec::new(), &[]);
    let (code, reponse) = compile(ctx, 4, asset, &defn[..defn.len() - 1]);
    assert_eq!((code, reponse), (AXION_E_INVALID_BUFFER, None));
    let sans_phys = load(ctx, AXION_SECTION_NODE).expect("chargement sans PHYS");
    let (code, reponse) = compile(ctx, 4, sans_phys, &defn);
    assert_eq!((code, reponse), (AXION_E_INVALID_BUFFER, None));
    assert_eq!(unsafe { axion_asset_unload(ctx, sans_phys) }, AXION_OK);
    let (code, reponse) = compile(ctx, 4, sans_phys, &defn);
    assert_eq!((code, reponse), (AXION_E_INVALID_HANDLE, None));
    assert_eq!(
        unsafe { axion_definition_remove(ctx, 4) },
        AXION_E_INVALID_HANDLE
    );

    // Une definition compilée ne tient pas l'asset : rendu, l'arrêt est équilibré (R-322).
    let (code, _) = compile(ctx, 5, asset, &defn);
    assert_eq!(code, AXION_OK);
    assert_eq!(unsafe { axion_asset_unload(ctx, asset) }, AXION_OK);
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK, "arrêt équilibré");
}
