//! Ce que les tests d'intégration partagent depuis ADR-156 : une assembly naît d'une definition
//! compilée. Un [`Modele`] décrit l'asset — nodes, colliders, clips — et la definition — corps,
//! joints, groupes — comme les payloads de `CREATE_ASSEMBLY` les portaient avant ; [`Modele::inscrire`]
//! écrit l'un en A3D et l'autre en `DEFN`, les charge et les compile dans la session, comme Java le
//! fait ; [`creation`] écrit la commande qui fait naître l'assembly par l'empreinte de sa definition.

// Chaque binaire de test n'emploie qu'une part de ce module.
#![allow(dead_code)]
// On exerce des points d'entrée `unsafe extern "C"` : c'est l'objet des tests.
#![allow(unsafe_code)]

use ax_asset::a3d::{encode_colliders, encode_nodes, A3dWriter, SectionTag};
use ax_asset::compile::COMPILER_VERSION;
use ax_model::buffer::{BufferKind, HEADER_BYTES};
use ax_model::dm::commands::{
    create_assembly_payload, AssemblyBodyEntry, CreateAssembly, RestoredBody, RestoredJoint,
};
use ax_model::dm::definition::{
    definition_flags, reference_category, Definition, DefinitionHeader, DefinitionPart,
    DefinitionRef,
};
use ax_model::dm::geometry::{Transform, WorldTransform};
use ax_model::dm::handle::Handle;
use ax_model::dm::joints::JointDesc;
use ax_model::dm::physics::{BodyDesc, ColliderDesc};
use ax_model::dm::scene::{name_hash, NodeDesc, NONE_U16, NONE_U32};
use axion_native::abi::{
    axion_asset_load, axion_asset_unload, axion_buffer_acquire, axion_buffer_release,
    axion_definition_compile, AxionBufferInfo, AXION_OK, AXION_SECTION_ANIM, AXION_SECTION_NODE,
    AXION_SECTION_PHYS,
};

/// Un asset et la definition qu'une assembly de test prend.
#[derive(Debug, Clone)]
pub struct Modele {
    /// Les nodes de l'asset ; vide : aucune hiérarchie, un seul corps.
    pub nodes: Vec<NodeDesc>,
    /// Le nom de chaque node, par lequel la definition le désigne.
    pub names: Vec<String>,
    /// Les colliders de la section `PHYS`.
    pub colliders: Vec<ColliderDesc>,
    /// L'annexe des points d'enveloppe.
    pub points: Vec<[f32; 3]>,
    /// L'annexe des enfants de composés.
    pub children: Vec<ColliderDesc>,
    /// La section `ANIM`, s'il en a une.
    pub anim: Option<Vec<u8>>,
    /// La description de l'assembly entière, celle de son corps quand elle n'en a qu'un.
    pub physics: BodyDesc,
    /// Les corps déclarés, `root_node` au rang de leur node dans [`Self::nodes`], `NONE_U32` pour la
    /// racine de l'asset ; vide : un seul corps, à la racine.
    pub bodies: Vec<AssemblyBodyEntry>,
    /// Les joints, `b_node` au rang de leur node dans [`Self::nodes`].
    pub joints: Vec<JointDesc>,
    /// Les points des courbes de grippage.
    pub jam_points: Vec<[f32; 2]>,
    /// Les parts, `root` au rang de leur node dans [`Self::nodes`] : leur matériau, leur
    /// déformabilité (ADR-149 D2, R-623).
    pub parts: Vec<DefinitionPart>,
    /// Groupes dont l'assembly est membre (R-980).
    pub group: u32,
    /// Groupes qu'elle heurte.
    pub mask: u32,
    /// Ses corps se touchent entre eux.
    pub self_collision: bool,
    /// Masse à répartir entre ses corps, en kg.
    pub assembly_mass: f32,
}

impl Modele {
    /// Un seul corps, sans hiérarchie : les colliders tels quels, sous la description `physics`.
    #[must_use]
    pub fn seul(colliders: Vec<ColliderDesc>, physics: BodyDesc) -> Self {
        Self {
            nodes: Vec::new(),
            names: Vec::new(),
            colliders,
            points: Vec::new(),
            children: Vec::new(),
            anim: None,
            physics,
            bodies: Vec::new(),
            joints: Vec::new(),
            jam_points: Vec::new(),
            parts: Vec::new(),
            group: u32::MAX,
            mask: u32::MAX,
            self_collision: false,
            assembly_mass: 0.0,
        }
    }

    /// Le `DEFN` de la definition : chaque node que désignent ses corps et ses joints en référence.
    #[must_use]
    pub fn defn(&self) -> Vec<u8> {
        let mut refs = Vec::new();
        let mut reference = |node: u32| -> u32 {
            refs.push(DefinitionRef {
                name_hash: name_hash(&self.names[node as usize]),
                category: reference_category::NODE,
            });
            u32::try_from(refs.len() - 1).expect("rang")
        };
        let bodies: Vec<AssemblyBodyEntry> = self
            .bodies
            .iter()
            .map(|body| AssemblyBodyEntry {
                desc: body.desc,
                root_node: if body.root_node == NONE_U32 {
                    NONE_U32
                } else {
                    reference(body.root_node)
                },
            })
            .collect();
        let joints: Vec<JointDesc> = self
            .joints
            .iter()
            .map(|joint| JointDesc {
                b_node: reference(joint.b_node),
                body_b: NONE_U16,
                ..*joint
            })
            .collect();
        let parts: Vec<DefinitionPart> = self
            .parts
            .iter()
            .map(|part| DefinitionPart {
                root: reference(part.root),
                ..*part
            })
            .collect();
        let mut bytes = Vec::new();
        Definition {
            header: DefinitionHeader {
                flags: if self.self_collision {
                    definition_flags::SELF_COLLISION
                } else {
                    0
                },
                group: self.group,
                mask: self.mask,
                assembly_mass: self.assembly_mass,
                ref_count: 0,
                body_count: 0,
                joint_count: 0,
                jam_points: 0,
                part_count: 0,
                physics: self.physics,
                implicit: BodyDesc::undeclared(2),
            },
            refs,
            bodies,
            joints,
            jam_points: self.jam_points.clone(),
            parts,
        }
        .write_le(&mut bytes);
        bytes
    }

    /// Le conteneur A3D de l'asset : `NODE` s'il a une hiérarchie, `PHYS`, et `ANIM` s'il en a une.
    #[must_use]
    pub fn a3d(&self, asset_id: u64) -> Vec<u8> {
        let mut writer = A3dWriter::new(asset_id, 0, COMPILER_VERSION);
        if !self.nodes.is_empty() {
            writer
                .section(
                    SectionTag::NODE,
                    &encode_nodes(&self.nodes, &self.names).expect("NODE"),
                )
                .expect("NODE");
        }
        writer
            .section(
                SectionTag::PHYS,
                &encode_colliders(&self.colliders, &self.points, &self.children).expect("PHYS"),
            )
            .expect("PHYS");
        if let Some(anim) = &self.anim {
            writer.section(SectionTag::ANIM, anim).expect("ANIM");
        }
        writer.finish().expect("conteneur")
    }

    /// Compile la definition dans la session `ctx`, sous l'empreinte `definition`, comme Java le
    /// fait : l'asset chargé avec `NODE | PHYS | ANIM`, le `DEFN` écrit dans `ASSET_IN`, puis l'asset
    /// rendu et les tampons relâchés. Rend le code d'`axion_definition_compile`.
    ///
    /// # Panics
    ///
    /// Si l'asset ne se charge pas.
    #[must_use]
    pub fn inscrire(&self, ctx: u64, definition: u64) -> i32 {
        let asset_id = definition ^ 0x0A55_E700_0000_0000;
        let entree = ecrire(ctx, &self.a3d(asset_id));
        let mut handle = Handle::ABSENT;
        let charge = unsafe {
            axion_asset_load(
                ctx,
                asset_id,
                AXION_SECTION_NODE | AXION_SECTION_PHYS | AXION_SECTION_ANIM,
                &raw mut handle,
            )
        };
        relacher(ctx, entree);
        assert_eq!(charge, AXION_OK, "l'asset du modèle se charge");
        let defn = self.defn();
        let entree = ecrire(ctx, &defn);
        let mut size = u64::MAX;
        let code = unsafe {
            axion_definition_compile(ctx, definition, handle, defn.len() as u64, &raw mut size)
        };
        relacher(ctx, entree);
        if size != u64::MAX {
            relacher(ctx, acquerir(ctx, BufferKind::AssetOut, 0));
        }
        assert_eq!(unsafe { axion_asset_unload(ctx, handle) }, AXION_OK);
        code
    }
}

/// Un node de l'asset, nommé `name`, posé par `local` sous `parent`, portant `collider`.
#[must_use]
pub fn noeud(name: &str, parent: u32, local: Transform, collider: u32) -> NodeDesc {
    NodeDesc {
        name_hash: name_hash(name),
        parent,
        local,
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

/// Le payload de `CREATE_ASSEMBLY` d'une assembly de la definition `definition`.
#[must_use]
pub fn creation(
    handle: Handle,
    dimension: u64,
    spawn: WorldTransform,
    definition: u64,
    flags: u8,
    restored: Option<(&[RestoredBody], &[RestoredJoint])>,
) -> Vec<u8> {
    create_assembly_payload(
        &CreateAssembly {
            handle,
            dimension,
            spawn,
            definition,
            flags,
            _pad: [0; 7],
        },
        restored,
    )
}

fn acquerir(ctx: u64, kind: BufferKind, min: u64) -> AxionBufferInfo {
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

fn relacher(ctx: u64, buffer: AxionBufferInfo) {
    assert_eq!(
        unsafe { axion_buffer_release(ctx, buffer.kind, buffer.generation) },
        AXION_OK
    );
}

/// Écrit `bytes` dans la charge d'`ASSET_IN`, acquis jusqu'à ce que l'appel qui le lit soit fait.
fn ecrire(ctx: u64, bytes: &[u8]) -> AxionBufferInfo {
    let buffer = acquerir(ctx, BufferKind::AssetIn, bytes.len() as u64);
    let view = unsafe {
        std::slice::from_raw_parts_mut(buffer.ptr, usize::try_from(buffer.capacity).unwrap())
    };
    view[HEADER_BYTES..HEADER_BYTES + bytes.len()].copy_from_slice(bytes);
    buffer
}
