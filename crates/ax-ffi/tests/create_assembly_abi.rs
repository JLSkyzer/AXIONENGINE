//! CREATE_ASSEMBLY d'IF-03, exercé par l'ABI comme Java le fera : la definition compilée dans la
//! session (ADR-156), puis un flux `SimIn` dont la commande CREATE_ASSEMBLY ne porte que son
//! empreinte. Le natif reprend les corps de la definition compilée, les crée, et ils apparaissent
//! dans le `collect`. Une description invalide est refusée à la compilation (T-312), et aucune
//! assembly n'en naît ; une empreinte inconnue du registre ne crée rien (R-704).
//!
//! Son propre binaire : la session native est globale au processus, et les tests
//! s'y succèdent sous un verrou.

// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet du test.
#![allow(unsafe_code)]

mod common;

use ax_model::buffer::{BufferHeader, BufferKind, HEADER_BYTES};
use ax_model::dm::commands::{opcode, AssemblyBodyEntry, CommandStreamHeader};
use ax_model::dm::geometry::{Transform, WorldTransform};
use ax_model::dm::handle::Handle;
use ax_model::dm::joints::{joint_kind, JointDesc, JointState};
use ax_model::dm::physics::{BodyDesc, ColliderDesc, ColliderShape};
use ax_model::dm::scene::{name_hash, NodeDesc, NONE_U16, NONE_U32, NO_PARENT};
use axion_native::abi::{
    axion_assembly_raycast, axion_box_touches_assembly, axion_buffer_acquire, axion_buffer_release,
    axion_init, axion_metrics_export, axion_shutdown, axion_sim_cancel, axion_sim_collect,
    axion_sim_submit, AxionBufferInfo, AxionCollectResult, AXION_E_INVALID_BUFFER,
    AXION_E_INVALID_HANDLE, AXION_OK, AXION_RAY_INPUT, AXION_RAY_OUTPUT, AXION_SIDE_SERVER,
};
use common::{creation, Modele};

static CONTEXTE: std::sync::Mutex<()> = std::sync::Mutex::new(());

/// L'empreinte sous laquelle chaque cas inscrit sa definition.
const DEFINITION: u64 = 0x0DEF_0000_0000_0156;

/// La pose de spawn de chaque cas.
const SPAWN: WorldTransform = WorldTransform {
    position: [5.0, 10.0, -2.0],
    rotation: [0.0, 0.0, 0.0, 1.0],
};

/// Un cas : la definition à compiler, et le flux `SimIn` qui fait naître son assembly.
type Cas = (Modele, Vec<u8>);

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

/// Le flux `SimIn` d'une commande CREATE_ASSEMBLY de l'empreinte `definition`, et du joueur qui
/// tient l'assembly éveillée (R-612).
fn flux(definition: u64) -> Vec<u8> {
    flux_de(creation(Handle::new(7, 1), 0, SPAWN, definition, 0, None))
}

/// Le flux `SimIn` du payload de `CREATE_ASSEMBLY` `payload`, et du joueur qui tient l'assembly
/// éveillée.
fn flux_de(payload: Vec<u8>) -> Vec<u8> {
    let mut stream = CommandStreamHeader::CURRENT_SCHEMA.to_le_bytes().to_vec();
    stream.extend_from_slice(&0u32.to_le_bytes()); // _pad
    stream.extend_from_slice(&opcode::CREATE_ASSEMBLY.to_le_bytes());
    stream.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    stream.extend_from_slice(&payload);
    stream.resize(stream.len().next_multiple_of(8), 0);
    // R-612 : un joueur au point d'apparition, sans quoi la gestion d'activité endort l'assembly,
    // racine fixe comprise (ADR-153 D4).
    let mut joueur = 0u64.to_le_bytes().to_vec();
    joueur.extend_from_slice(&1u32.to_le_bytes());
    joueur.extend_from_slice(&0u32.to_le_bytes());
    for value in SPAWN.position {
        joueur.extend_from_slice(&value.to_le_bytes());
    }
    stream.extend_from_slice(&opcode::SET_OBSERVERS.to_le_bytes());
    stream.extend_from_slice(&(joueur.len() as u32).to_le_bytes());
    stream.extend_from_slice(&joueur);
    stream
}

/// Un seul corps, décrit par `desc`, à la racine de l'asset.
fn command_stream(desc: BodyDesc) -> Cas {
    (Modele::seul(vec![box_collider()], desc), flux(DEFINITION))
}

/// Soumet le cas dans une session neuve, collecte un tick, et rend le nombre de
/// corps collectés et la position monde du premier, s'il y en a un.
fn soumettre(cas: &Cas) -> (u32, Option<[f64; 3]>) {
    let (etats, _, _, _) = etats_et_joints(cas);
    (
        u32::try_from(etats.len()).unwrap(),
        etats.first().map(|(_, position)| *position),
    )
}

/// Le node d'un corps collecté et sa position monde.
type EtatCorps = (u32, [f64; 3]);

/// Soumet le cas dans une session neuve, collecte un tick, et rend le node et la position
/// monde de chaque corps collecté (`BodyState` de 80 octets : position `f64` à l'offset 8,
/// node à l'offset 76).
fn etats(cas: &Cas) -> Vec<EtatCorps> {
    etats_et_joints(cas).0
}

/// Le nombre de commandes du flux : après son en-tête de 8 octets, chacune a un en-tête de 8 octets
/// et un payload complété à 8.
fn commandes(stream: &[u8]) -> u32 {
    let mut at = 8;
    let mut count = 0;
    while at + 8 <= stream.len() {
        let len = u32::from_le_bytes(stream[at + 4..at + 8].try_into().unwrap()) as usize;
        at += 8 + len.next_multiple_of(8);
        count += 1;
    }
    count
}

/// [`etats`], la durée de l'étape 5 du tick (`axion.sim.last_step_joints_ns`), les `JointState`
/// qui suivent les emprises dans `SIM_OUT` (ADR-151 D7), et le code de la compilation.
fn etats_et_joints(cas: &Cas) -> (Vec<EtatCorps>, u64, Vec<JointState>, i32) {
    etats_et_joints_sonde(cas, |_| {})
}

/// [`etats_et_joints`], et `sonde` appelée sur la session après le collect, avant sa fermeture.
fn etats_et_joints_sonde(
    cas: &Cas,
    sonde: impl FnOnce(u64),
) -> (Vec<EtatCorps>, u64, Vec<JointState>, i32) {
    let (modele, stream) = cas;
    let _contexte = CONTEXTE
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);

    let mut ctx = 0u64;
    let code = unsafe { axion_init(std::ptr::null(), 0, AXION_SIDE_SERVER, &raw mut ctx) };
    assert_eq!(code, AXION_OK, "init");
    // ADR-156 : la definition se compile avant que l'assembly ne naisse.
    let compilation = modele.inscrire(ctx, DEFINITION);

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
        unsafe { axion_sim_submit(ctx, 1, commandes(stream), 0) },
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
    let mut joints = Vec::new();
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
        // La section des joints suit les états (80 o) et les emprises (24 o) des corps.
        let section = HEADER_BYTES + result.state_count as usize * (80 + 24);
        joints = JointState::read_section(&out_view[section..]).expect("section des joints");
    }

    let joints_ns = metrique(ctx, "axion.sim.last_step_joints_ns");
    sonde(ctx);
    assert_eq!(unsafe { axion_sim_cancel(ctx) }, AXION_OK, "cancel");
    release_buffer(ctx, BufferKind::SimIn);
    release_buffer(ctx, BufferKind::SimOut);
    release_buffer(ctx, BufferKind::Events);
    assert_eq!(unsafe { axion_shutdown(ctx) }, AXION_OK, "shutdown");
    (etats, joints_ns, joints, compilation)
}

/// La valeur d'une métrique de l'export (R-502).
fn metrique(ctx: u64, nom: &str) -> u64 {
    let mut needed = 0usize;
    let code = unsafe { axion_metrics_export(ctx, std::ptr::null_mut(), 0, &raw mut needed) };
    assert_eq!(code, AXION_OK, "taille de l'export");
    let mut buffer = vec![0u8; needed];
    let mut written = 0usize;
    let code =
        unsafe { axion_metrics_export(ctx, buffer.as_mut_ptr(), buffer.len(), &raw mut written) };
    assert_eq!(code, AXION_OK, "export");
    let json = String::from_utf8(buffer).expect("export UTF-8");
    let marque = format!("\"name\": \"{nom}\"");
    let ligne = json
        .lines()
        .find(|ligne| ligne.contains(&marque))
        .unwrap_or_else(|| panic!("{nom} absente de l'export"));
    ligne
        .split("\"value\": ")
        .nth(1)
        .expect("valeur")
        .chars()
        .take_while(char::is_ascii_digit)
        .collect::<String>()
        .parse()
        .expect("valeur entière")
}

/// Une assembly de deux corps : la racine de l'asset et un node `porte` décalé d'un
/// demi-bloc en x, chacun portant une boîte d'un bloc — elles se chevauchent.
fn deux_corps(self_collision: bool) -> Cas {
    deux_corps_relies(self_collision, 2, &[])
}

/// [`deux_corps`], la racine du type `body_kind`, reliés par `joints`.
fn deux_corps_relies(self_collision: bool, body_kind: u8, joints: &[JointDesc]) -> Cas {
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
    let corps = |kind, root_node| AssemblyBodyEntry {
        desc: BodyDesc::undeclared(kind),
        root_node,
    };
    let modele = Modele {
        nodes: vec![node("racine", NO_PARENT, 0.0, 0), node("porte", 0, 0.5, 1)],
        names: vec!["racine".to_owned(), "porte".to_owned()],
        bodies: vec![corps(body_kind, 0), corps(2, 1)],
        joints: joints.to_vec(),
        self_collision,
        ..Modele::seul(
            vec![box_collider(), box_collider()],
            BodyDesc::undeclared(body_kind),
        )
    };
    (modele, flux(DEFINITION))
}

/// L'écart en x entre les deux corps de [`deux_corps`] après un tick.
fn ecart(self_collision: bool) -> f64 {
    let etats = etats(&deux_corps(self_collision));
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
    let sans = ecart(false);
    assert!((sans - 0.5).abs() < 1e-6, "aucun contact interne : {sans}");
    let avec = ecart(true);
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
fn t312_une_description_invalide_est_refusee_a_la_compilation() {
    // Masse négative : le DEFN est refusé, et aucune assembly ne naît de son empreinte.
    let negative = BodyDesc {
        mass: -1.0,
        ..BodyDesc::undeclared(2)
    };
    let (etats, _, _, compilation) = etats_et_joints(&command_stream(negative));
    assert_eq!(
        compilation, AXION_E_INVALID_BUFFER,
        "description invalide : refusée"
    );
    assert!(etats.is_empty(), "aucun corps à moitié formé");
}

#[test]
fn adr156_une_empreinte_que_le_registre_ne_tient_pas_ne_cree_rien() {
    // R-704 : une creation qui nomme une definition inconnue laisse l'assembly inerte.
    let (modele, _) = command_stream(BodyDesc::undeclared(2));
    let (etats, _, _, compilation) = etats_et_joints(&(modele, flux(DEFINITION ^ 1)));
    assert_eq!(
        compilation, AXION_OK,
        "la definition se compile sous sa propre empreinte"
    );
    assert!(etats.is_empty(), "une autre empreinte ne crée rien");
}

#[test]
fn adr156_des_octets_apres_l_en_tete_sans_restored_ne_creent_rien() {
    // Sans `RESTORED`, l'en-tête est tout le payload : un état qu'il n'annonce pas laisse l'assembly
    // inerte (R-704), plutôt que d'être lu ou ignoré.
    let mut payload = creation(Handle::new(7, 1), 0, SPAWN, DEFINITION, 0, None);
    payload.extend_from_slice(&[0; 8]);
    let (modele, _) = command_stream(BodyDesc::undeclared(2));
    let (etats, _, _, compilation) = etats_et_joints(&(modele, flux_de(payload)));
    assert_eq!(compilation, AXION_OK);
    assert!(etats.is_empty(), "un payload prolongé ne crée rien");
}

/// Un joint `Fixed` de la racine à la porte, ancré à l'origine de la porte.
fn soudure() -> JointDesc {
    JointDesc {
        kind: joint_kind::FIXED,
        flags: 0,
        body_a: 0,
        body_b: 1,
        axes: [0; 6],
        b_node: 1,
        anchor: [0.0; 3],
        axis: [0.0; 3],
        limits: [0.0; 2],
        motor_speed: 0.0,
        motor_target: 0.0,
        motor_stiffness: 0.0,
        motor_damping: 0.0,
        motor_max_force: f32::INFINITY,
        friction: 0.0,
        break_force: f32::INFINITY,
        break_torque: f32::INFINITY,
        break_energy: f32::INFINITY,
        jam_friction: 0.0,
        jam_first: 0,
        jam_count: 0,
        axis_limits: [[0.0; 2]; 6],
        max_length: 0.0,
        rest_length: 0.0,
        stiffness: 0.0,
        damping: 0.0,
    }
}

#[test]
fn adr151_un_joint_venu_par_l_abi_relie_les_corps_de_l_assembly() {
    // ADR-151 D7 : la racine est statique ; soudée à elle, la porte reste à sa hauteur de spawn,
    // et l'étape 5 est mesurée ; sans joint, elle tombe.
    let hauteur = |joints: &[JointDesc]| {
        // Un corps statique n'a pas d'état : seule la porte, au node 1, en rapporte un.
        let (etats, joints_ns, etats_joints, _) =
            etats_et_joints(&deux_corps_relies(false, 0, joints));
        let porte = etats.iter().find(|(node, _)| *node == 1).expect("la porte");
        (porte.1[1], joints_ns, etats_joints)
    };
    let (tenue, mesuree, etats_joints) = hauteur(&[soudure()]);
    assert!((tenue - 10.0).abs() < 1e-4, "soudée : {tenue}");
    assert!(mesuree > 0, "étape 5 mesurée avec un joint");
    // ADR-151 D7 : son état suit les emprises dans SIM_OUT, la porte pendue à lui.
    assert_eq!(etats_joints.len(), 1);
    assert_eq!(
        (etats_joints[0].handle, etats_joints[0].index),
        (Handle::new(7, 1), 0)
    );
    assert!(etats_joints[0].force > 0.0, "il porte la porte");
    let (libre, _, aucun) = hauteur(&[]);
    assert!(aucun.is_empty(), "sans joint, aucun JointState");
    assert!(libre < 10.0 - 1e-3, "libre, elle tombe : {libre}");
}

/// Lance un rayon par l'ABI : le code, et ce que `out` a reçu.
fn rayon(
    ctx: u64,
    assembly: Handle,
    ray: [f64; AXION_RAY_INPUT],
) -> (i32, [f64; AXION_RAY_OUTPUT]) {
    let mut out = [f64::NAN; AXION_RAY_OUTPUT];
    let code = unsafe { axion_assembly_raycast(ctx, assembly, ray.as_ptr(), out.as_mut_ptr()) };
    (code, out)
}

#[test]
fn adr158_un_rayon_vise_les_colliders_d_une_assembly_par_l_abi() {
    let cas = command_stream(BodyDesc::undeclared(2));
    let mut vu = false;
    etats_et_joints_sonde(&cas, |ctx| {
        vu = true;
        let assembly = Handle::new(7, 1);
        // La boîte de 1 bloc au spawn (5, 10, −2) : un rayon venu de z = −10 la touche à z = −2,5,
        // en coordonnées monde absolues.
        let (code, out) = rayon(ctx, assembly, [5.0, 10.0, -10.0, 0.0, 0.0, 1.0, 20.0]);
        assert_eq!(code, AXION_OK);
        assert_eq!(
            (out[0], out[1]),
            (0.0, 0.0),
            "corps racine, premier collider"
        );
        assert!((out[2] - 7.5).abs() < 1.0e-3, "distance {}", out[2]);
        assert!((out[3] - 5.0).abs() < 1.0e-3 && (out[4] - 10.0).abs() < 1.0e-3);
        assert!(
            (out[5] + 2.5).abs() < 1.0e-3,
            "point monde {:?}",
            &out[3..6]
        );
        assert!(out[8] < -0.9, "normale vers le rayon {:?}", &out[6..9]);

        // Dos à la boîte, ou trop court : rien, et l'appel réussit.
        let (code, out) = rayon(ctx, assembly, [5.0, 10.0, -10.0, 0.0, 0.0, -1.0, 20.0]);
        assert_eq!((code, out[0]), (AXION_OK, -1.0));
        let (code, out) = rayon(ctx, assembly, [5.0, 10.0, -10.0, 0.0, 0.0, 1.0, 5.0]);
        assert_eq!((code, out[0]), (AXION_OK, -1.0));
        let (code, out) = rayon(ctx, assembly, [f64::NAN, 10.0, -10.0, 0.0, 0.0, 1.0, 20.0]);
        assert_eq!((code, out[0]), (AXION_OK, -1.0), "origine non finie");

        // Une assembly inconnue, un pointeur nul.
        let (code, _) = rayon(
            ctx,
            Handle::new(8, 1),
            [5.0, 10.0, -10.0, 0.0, 0.0, 1.0, 20.0],
        );
        assert_eq!(code, AXION_E_INVALID_HANDLE);
        let mut out = [0.0; AXION_RAY_OUTPUT];
        let code =
            unsafe { axion_assembly_raycast(ctx, assembly, std::ptr::null(), out.as_mut_ptr()) };
        assert_eq!(code, AXION_E_INVALID_BUFFER);
    });
    assert!(vu, "la sonde a tourné");
}

/// Demande par l'ABI si la boîte touche une assembly : le code, et la réponse.
fn boite(ctx: u64, dimension: u64, aabb: [f64; 6]) -> (i32, u32) {
    let mut out = u32::MAX;
    let code = unsafe { axion_box_touches_assembly(ctx, dimension, aabb.as_ptr(), &raw mut out) };
    (code, out)
}

#[test]
fn adr158_une_boite_touche_les_colliders_d_une_assembly_et_rien_d_autre() {
    let cas = command_stream(BodyDesc::undeclared(2));
    let mut vu = false;
    etats_et_joints_sonde(&cas, |ctx| {
        vu = true;
        // La boîte de 1 bloc au spawn (5, 10, −2), qu'un tick de chute n'a presque pas bougée.
        assert_eq!(
            boite(ctx, 0, [4.8, 9.8, -2.2, 5.2, 10.2, -1.8]),
            (AXION_OK, 1),
            "dedans"
        );
        assert_eq!(
            boite(ctx, 0, [5.4, 9.8, -2.2, 6.0, 10.2, -1.8]),
            (AXION_OK, 1),
            "à cheval sur son flanc"
        );
        assert_eq!(
            boite(ctx, 0, [5.6, 9.8, -2.2, 6.0, 10.2, -1.8]),
            (AXION_OK, 0),
            "à côté"
        );
        assert_eq!(
            boite(ctx, 99, [4.8, 9.8, -2.2, 5.2, 10.2, -1.8]),
            (AXION_OK, 0),
            "une dimension sans simulation"
        );
        for fautive in [
            [f64::NAN, 9.8, -2.2, 5.2, 10.2, -1.8],
            [5.2, 9.8, -2.2, 4.8, 10.2, -1.8],
            [4.8, 9.8, -2.2, 4.8, 10.2, -1.8],
        ] {
            assert_eq!(
                boite(ctx, 0, fautive).0,
                AXION_E_INVALID_BUFFER,
                "{fautive:?}"
            );
        }
        let code =
            unsafe { axion_box_touches_assembly(ctx, 0, std::ptr::null(), std::ptr::null_mut()) };
        assert_eq!(code, AXION_E_INVALID_BUFFER);
    });
    assert!(vu, "la sonde a tourné");
}
