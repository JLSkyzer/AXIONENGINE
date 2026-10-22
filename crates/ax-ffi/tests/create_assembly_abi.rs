//! CREATE_ASSEMBLY d'IF-03, exercé par l'ABI comme Java le fera (C-32, Option A,
//! ADR-115) : un flux `SimIn` porte une commande CREATE_ASSEMBLY dont le payload
//! est l'en-tête `CreateAssembly` suivi des octets de la section `PHYS`. Le natif
//! décode les colliders, crée un corps, et le corps apparaît dans le `collect`.
//! Depuis ADR-130, la description DM-08 du corps suit l'en-tête ; invalide, ou d'un
//! autre type de corps que l'en-tête, l'assembly est refusée (T-312). Qu'elle soit
//! appliquée se voit dans `body_desc_abi`.
//!
//! Son propre binaire : la session native est globale au processus, et les tests
//! s'y succèdent sous un verrou.

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

use ax_asset::a3d::{encode_colliders, encode_nodes};
use ax_model::buffer::{BufferHeader, BufferKind, HEADER_BYTES};
use ax_model::dm::commands::{
    create_assembly_flags, create_assembly_payload, opcode, AssemblyBodyEntry, CommandStreamHeader,
    CreateAssembly,
};
use ax_model::dm::geometry::{Transform, WorldTransform};
use ax_model::dm::handle::Handle;
use ax_model::dm::physics::{BodyDesc, ColliderDesc, ColliderShape};
use ax_model::dm::scene::{name_hash, NodeDesc, NONE_U16, NONE_U32, NO_PARENT};
use axion_native::abi::{
    axion_buffer_acquire, axion_buffer_release, axion_init, axion_shutdown, axion_sim_cancel,
    axion_sim_collect, axion_sim_submit, AxionBufferInfo, AxionCollectResult, AXION_OK,
    AXION_SIDE_SERVER,
};

static CONTEXTE: std::sync::Mutex<()> = std::sync::Mutex::new(());

fn release_buffer(ctx: u64, kind: BufferKind) {
    let mut info = AxionBufferInfo {
        ptr: std::ptr::null_mut(),
        capacity: 0,
        kind: 0,
        generation: 0,
    };
    unsafe {
        axion_buffer_acquire(ctx, kind.as_u32(), 0, &raw mut info);
        axion_buffer_release(ctx, kind.as_u32(), info.generation);
    }
}

/// Une boîte de collider, densité de l'eau, sans référence.
fn box_collider() -> ColliderDesc {
    ColliderDesc {
        shape: ColliderShape::Box {
            half_extents: [0.5, 0.5, 0.5],
        },
        local: Transform::identity(),
        material: u16::MAX,
        _pad: 0,
        group: u32::MAX,
        mask: u32::MAX,
        flags: 0,
        density: 1000.0,
        damage_zone: u16::MAX,
        part: u16::MAX,
        region: u16::MAX,
        _pad2: 0,
        hull_points_offset: 0,
        hull_points_count: 0,
    }
}

/// Construit le flux `SimIn` : en-tête + une commande CREATE_ASSEMBLY (en-tête
/// `CreateAssembly`, description DM-08 du corps, octets `PHYS`).
fn command_stream(desc: BodyDesc) -> Vec<u8> {
    let phys = encode_colliders(&[box_collider()], &[], &[]).expect("encodage PHYS");

    // En-tête CreateAssembly (64 octets).
    let mut payload = Vec::new();
    payload.extend_from_slice(&7u32.to_le_bytes()); // handle.index
    payload.extend_from_slice(&1u32.to_le_bytes()); // handle.generation
    payload.extend_from_slice(&0u64.to_le_bytes()); // dimension
    for value in [5.0f64, 10.0, -2.0] {
        payload.extend_from_slice(&value.to_le_bytes()); // spawn.position
    }
    for value in [0.0f32, 0.0, 0.0, 1.0] {
        payload.extend_from_slice(&value.to_le_bytes()); // spawn.rotation
    }
    payload.push(2); // body_kind : dynamique
    payload.resize(CreateAssembly::BYTES, 0); // drapeaux, corps, joints, masse, NODE
    let en_tete = CreateAssembly::read_le(&payload).expect("en-tête complet");

    // ADR-130 : la description DM-08 du corps, puis les octets PHYS.
    // ADR-151 D7 : un seul corps, à la racine de l'asset, sans section NODE.
    let mut payload = create_assembly_payload(
        &en_tete,
        &[AssemblyBodyEntry { desc, root_node: 0 }],
        &[],
        &[],
    );
    assert_eq!(payload.len() % 8, 0, "PHYS suit les corps, aligné sur 8");
    payload.extend_from_slice(&phys);

    let mut stream = CommandStreamHeader::CURRENT_SCHEMA.to_le_bytes().to_vec();
    stream.extend_from_slice(&0u32.to_le_bytes()); // _pad
    stream.extend_from_slice(&opcode::CREATE_ASSEMBLY.to_le_bytes());
    stream.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    stream.extend_from_slice(&payload);
    stream
}

/// Soumet le flux dans une session neuve, collecte un tick, et rend le nombre de
/// corps collectés et la position monde du premier, s'il y en a un.
fn soumettre(stream: &[u8]) -> (u32, Option<[f64; 3]>) {
    let etats = etats(stream);
    (
        u32::try_from(etats.len()).unwrap(),
        etats.first().map(|(_, position)| *position),
    )
}

/// Soumet le flux dans une session neuve, collecte un tick, et rend le node et la position
/// monde de chaque corps collecté (`BodyState` de 80 octets : position `f64` à l'offset 8,
/// node à l'offset 76).
fn etats(stream: &[u8]) -> Vec<(u32, [f64; 3])> {
    let _contexte = CONTEXTE
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);

    let mut ctx = 0u64;
    let code = unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut ctx) };
    assert_eq!(code, AXION_OK, "init");

    // Écrit le flux dans SimIn (en-tête de tampon + payload).
    let mut info = AxionBufferInfo {
        ptr: std::ptr::null_mut(),
        capacity: 0,
        kind: 0,
        generation: 0,
    };
    let code = unsafe {
        axion_buffer_acquire(
            ctx,
            BufferKind::SimIn.as_u32(),
            stream.len() as u64,
            &raw mut info,
        )
    };
    assert_eq!(code, AXION_OK, "acquire SimIn");
    assert!(!info.ptr.is_null());
    let view = unsafe {
        std::slice::from_raw_parts_mut(info.ptr, usize::try_from(info.capacity).unwrap())
    };
    let header = BufferHeader {
        kind: BufferKind::SimIn,
        generation: info.generation,
        schema_version: 0,
        payload_len: stream.len() as u64,
        element_count: 1,
        crc32c: 0,
    };
    header.write(view);
    view[HEADER_BYTES..HEADER_BYTES + stream.len()].copy_from_slice(stream);

    // Soumet la commande, puis collecte.
    assert_eq!(
        unsafe { axion_sim_submit(ctx, 1, 1, 0) },
        AXION_OK,
        "submit"
    );
    let mut result = AxionCollectResult::default();
    assert_eq!(
        unsafe { axion_sim_collect(ctx, u64::MAX, &raw mut result) },
        AXION_OK,
        "collect"
    );

    let mut etats = Vec::new();
    if result.state_count > 0 {
        let mut out = AxionBufferInfo {
            ptr: std::ptr::null_mut(),
            capacity: 0,
            kind: 0,
            generation: 0,
        };
        let code =
            unsafe { axion_buffer_acquire(ctx, BufferKind::SimOut.as_u32(), 0, &raw mut out) };
        assert_eq!(code, AXION_OK, "acquire SimOut");
        let out_view =
            unsafe { std::slice::from_raw_parts(out.ptr, usize::try_from(out.capacity).unwrap()) };
        for rang in 0..result.state_count as usize {
            let base = HEADER_BYTES + rang * 80;
            let f = |offset: usize| {
                f64::from_le_bytes(
                    out_view[base + offset..base + offset + 8]
                        .try_into()
                        .unwrap(),
                )
            };
            let node = u32::from_le_bytes(out_view[base + 76..base + 80].try_into().unwrap());
            etats.push((node, [f(8), f(16), f(24)]));
        }
    }

    assert_eq!(unsafe { axion_sim_cancel(ctx) }, AXION_OK, "cancel");
    release_buffer(ctx, BufferKind::SimIn);
    release_buffer(ctx, BufferKind::SimOut);
    release_buffer(ctx, BufferKind::Events);
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK, "shutdown");
    etats
}

/// Une assembly de deux corps : la racine de l'asset et un node `porte` décalé d'un
/// demi-bloc en x, chacun portant une boîte d'un bloc — elles se chevauchent.
fn deux_corps(flags: u8) -> Vec<u8> {
    let node = |name: &str, parent: u32, x: f32, collider: u32| NodeDesc {
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
    };
    let nodes = encode_nodes(
        &[node("racine", NO_PARENT, 0.0, 0), node("porte", 0, 0.5, 1)],
        &["racine".to_owned(), "porte".to_owned()],
    )
    .expect("encodage NODE");
    let phys = encode_colliders(&[box_collider(), box_collider()], &[], &[]).expect("PHYS");
    let header = CreateAssembly {
        handle: Handle::new(7, 1),
        dimension: 0,
        spawn: WorldTransform {
            position: [5.0, 10.0, -2.0],
            rotation: [0.0, 0.0, 0.0, 1.0],
        },
        body_kind: 2,
        flags,
        body_count: 0,
        joint_count: 0,
        _pad: 0,
        assembly_mass: 0.0,
        node_bytes: 0,
    };
    let corps = |root_node| AssemblyBodyEntry {
        desc: BodyDesc::undeclared(2),
        root_node,
    };
    let payload = create_assembly_payload(&header, &[corps(0), corps(1)], &nodes, &phys);
    let mut stream = CommandStreamHeader::CURRENT_SCHEMA.to_le_bytes().to_vec();
    stream.extend_from_slice(&0u32.to_le_bytes());
    stream.extend_from_slice(&opcode::CREATE_ASSEMBLY.to_le_bytes());
    stream.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    stream.extend_from_slice(&payload);
    stream
}

/// L'écart en x entre les deux corps de [`deux_corps`] après un tick.
fn ecart(flags: u8) -> f64 {
    let etats = etats(&deux_corps(flags));
    assert_eq!(
        etats.iter().map(|(node, _)| *node).collect::<Vec<_>>(),
        vec![0, 1],
        "deux corps, chacun à son node"
    );
    etats[1].1[0] - etats[0].1[0]
}

#[test]
fn adr151_une_assembly_de_deux_corps_nait_par_l_abi() {
    // ADR-151 D7 : le second corps est posé par son node ; ses colliders ne touchent pas ceux de
    // la racine (§10.4), sauf collisions internes déclarées.
    let sans = ecart(0);
    assert!((sans - 0.5).abs() < 1e-6, "aucun contact interne : {sans}");
    let avec = ecart(create_assembly_flags::SELF_COLLISION);
    assert!(avec > 0.5 + 1e-3, "collisions internes déclarées : {avec}");
}

#[test]
fn create_assembly_cree_un_corps_visible_au_collect() {
    let (count, position) = soumettre(&command_stream(BodyDesc::undeclared(2)));
    // Le corps a été créé depuis les colliders : il apparaît dans l'état collecté,
    // à sa pose de spawn (origine à zéro).
    assert_eq!(count, 1, "un corps créé depuis CREATE_ASSEMBLY");
    let [x, _, _] = position.expect("position du corps");
    assert!((x - 5.0).abs() < 1.0e-3, "corps à sa pose de spawn, x={x}");
}

#[test]
fn t312_une_description_invalide_ou_d_un_autre_type_est_refusee() {
    // Corps statique décrit, en-tête dynamique : l'assembly est abandonnée.
    let (count, _) = soumettre(&command_stream(BodyDesc::undeclared(0)));
    assert_eq!(count, 0, "type de corps différent de l'en-tête : refusé");
    // Masse négative : refusée, sans corps à moitié formé.
    let negative = BodyDesc {
        mass: -1.0,
        ..BodyDesc::undeclared(2)
    };
    let (count, _) = soumettre(&command_stream(negative));
    assert_eq!(count, 0, "description invalide : refusée");
}
