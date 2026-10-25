//! `CREATE_ASSEMBLY` d'une assembly à un ou plusieurs corps (ADR-151 D2, D7).
//!
//! Décode le payload — en-tête, corps, joints, sections `NODE` et `PHYS` de l'asset — et
//! range chaque collider dans le corps de son node, posé dans le repère de ce corps : la
//! chaîne des transformations des nodes est composée depuis la racine de l'asset, échelle
//! uniforme comprise (R-120). Chaque joint reçoit ses repères dans ses deux corps, pris à la
//! pose de repos de l'asset. Rien n'est créé ici : le pilote reçoit des corps et des joints
//! prêts, et un payload fautif n'en donne aucun.

use ax_asset::a3d::{decode_colliders, decode_nodes};
use ax_math::{Quat, Vec3};
use ax_model::dm::commands::{
    create_assembly_flags, AssemblyBodyEntry, CreateAssembly, RestoredBody, RestoredJoint,
    MAX_ASSEMBLY_BODIES,
};
use ax_model::dm::geometry::Transform;
use ax_model::dm::joints::{joint_axis_mode, joint_flags, joint_kind, JointDesc};
use ax_model::dm::physics::ColliderDesc;
use ax_model::dm::scene::{NodeDesc, NONE_U32};
use ax_physics::{
    AssemblyBody, AssemblyJoint, AxisMode, BodyCollider, BodyKind, BreakThresholds,
    CollisionGroups, ContactMaterial, Jam, JointKind, JointMotor, JointSpec, Shape,
};

/// Une assembly décodée, prête pour `SimDriver::create_assembly_bodies`.
#[derive(Debug)]
pub(crate) struct AssemblyRequest {
    /// L'en-tête lu.
    pub header: CreateAssembly,
    /// Les corps, le corps racine d'abord, chacun avec ses colliders dans son repère.
    pub bodies: Vec<AssemblyBody>,
    /// Les joints, dans l'ordre de la definition, leurs repères dans ceux de leurs corps.
    pub joints: Vec<AssemblyJoint>,
    /// Les groupes de collision communs à tous les colliders (R-980).
    pub groups: CollisionGroups,
    /// L'état restauré, si l'en-tête porte `RESTORED` (ADR-153 D3).
    pub restored: Option<Restored>,
}

/// Décode un payload de `CREATE_ASSEMBLY`. `None` au premier écart — en-tête tronqué, aucun
/// corps ou plus de 64, description invalide, corps racine d'un autre type que l'en-tête ou
/// autre corps non dynamique, masse non finie ou négative, joint invalide ou incohérent,
/// section illisible, node ou collider hors de leur table, échelle non uniforme sur la
/// chaîne d'un collider ou d'un `b_node`, corps sans collider, groupes discordants :
/// l'assembly est abandonnée entière, jamais formée à moitié.
pub(crate) fn decode(payload: &[u8]) -> Option<AssemblyRequest> {
    let header = CreateAssembly::read_le(payload)?;
    let count = usize::from(header.body_count);
    if count == 0 || count > MAX_ASSEMBLY_BODIES {
        return None;
    }
    if !(header.assembly_mass.is_finite() && header.assembly_mass >= 0.0) {
        return None;
    }
    let mut entries = Vec::with_capacity(count);
    for index in 0..count {
        let at = CreateAssembly::BODIES_OFFSET + index * AssemblyBodyEntry::WIRE_BYTES;
        let entry = AssemblyBodyEntry::read_le(payload.get(at..)?)?;
        entry.desc.validate().ok()?;
        entries.push(entry);
    }
    // Le corps racine a le type de l'assembly ; les autres suivent leurs joints, dynamiques.
    if entries[0].desc.body_type != header.body_kind
        || entries[1..].iter().any(|entry| entry.desc.body_type != 2)
    {
        return None;
    }

    // Les joints suivent les corps, puis les points de leurs courbes de grippage (D7).
    let joints_at = CreateAssembly::BODIES_OFFSET + count * AssemblyBodyEntry::WIRE_BYTES;
    let mut descs = Vec::with_capacity(usize::from(header.joint_count));
    for index in 0..usize::from(header.joint_count) {
        let desc = JointDesc::read_le(payload.get(joints_at + index * JointDesc::BYTES..)?)?;
        desc.validate().ok()?;
        descs.push(desc);
    }
    let jam_at = joints_at + descs.len() * JointDesc::BYTES;
    let mut jam_points = Vec::with_capacity(usize::from(header.jam_points));
    for index in 0..usize::from(header.jam_points) {
        let at = jam_at + index * JointDesc::JAM_POINT_BYTES;
        let point = payload.get(at..at + JointDesc::JAM_POINT_BYTES)?;
        let f32_at = |at: usize| f32::from_le_bytes(point[at..at + 4].try_into().expect("borne"));
        jam_points.push([f32_at(0), f32_at(4)]);
    }

    let nodes_at = jam_at + jam_points.len() * JointDesc::JAM_POINT_BYTES;
    let node_bytes = usize::try_from(header.node_bytes).ok()?;
    let nodes_end = nodes_at.checked_add(node_bytes)?;
    // Sans section NODE, les colliders sont pris tels quels ; un second corps n'y aurait pas
    // de node racine, et `Skeleton::new` le refuse.
    let nodes = if node_bytes == 0 {
        Vec::new()
    } else {
        decode_nodes(payload.get(nodes_at..nodes_end)?).ok()?.nodes
    };
    // ADR-153 D3 : l'état restauré suit la section NODE, un état par corps puis par joint.
    let (restored, restored_end) = if header.flags & create_assembly_flags::RESTORED == 0 {
        (None, nodes_end)
    } else {
        let joints_state_at = nodes_end + count * RestoredBody::BYTES;
        let restored_bodies = (0..count)
            .map(|index| {
                RestoredBody::read_le(payload.get(nodes_end + index * RestoredBody::BYTES..)?)
            })
            .collect::<Option<Vec<_>>>()?;
        let restored_joints = (0..descs.len())
            .map(|index| {
                RestoredJoint::read_le(
                    payload.get(joints_state_at + index * RestoredJoint::BYTES..)?,
                )
            })
            .collect::<Option<Vec<_>>>()?;
        let end = joints_state_at + descs.len() * RestoredJoint::BYTES;
        (
            Some(Restored {
                bodies: restored_bodies,
                joints: restored_joints,
            }),
            end,
        )
    };
    let phys_at = restored_end.next_multiple_of(8);
    let (colliders, points, children) = decode_colliders(payload.get(phys_at..)?).ok()?;
    let groups = body_groups(&colliders)?;
    let skeleton = Skeleton::new(&entries, &nodes)?;
    let bodies = layout(&skeleton, &entries, &nodes, &colliders, &points, &children)?;
    let joints = descs
        .iter()
        .enumerate()
        .map(|(index, desc)| assembly_joint(&skeleton, &entries, &jam_points, index, desc))
        .collect::<Option<Vec<_>>>()?;
    Some(AssemblyRequest {
        header,
        bodies,
        joints,
        groups,
        restored,
    })
}

/// L'état restauré d'une assembly (ADR-153 D3), lu de sa section : un état par corps, dans
/// l'ordre des corps, puis un par joint, dans l'ordre de la definition.
#[derive(Debug, Clone, PartialEq)]
pub(crate) struct Restored {
    /// Pose et vitesses de chaque corps.
    pub bodies: Vec<RestoredBody>,
    /// État de chaque joint.
    pub joints: Vec<RestoredJoint>,
}

/// Pose d'un node dans le repère de l'asset : translation, rotation et échelle uniforme.
#[derive(Debug, Clone, Copy)]
struct NodePose {
    translation: Vec3,
    rotation: Quat,
    scale: f32,
}

impl NodePose {
    const ROOT: Self = Self {
        translation: Vec3::ZERO,
        rotation: Quat::IDENTITY,
        scale: 1.0,
    };

    /// Cette pose suivie d'une transformation locale ; `None` si l'échelle locale n'est pas
    /// uniforme et positive (R-120), ou si le résultat n'est pas fini.
    fn then(self, local: &Transform) -> Option<Self> {
        let scale = uniform_scale(local)?;
        let pose = Self {
            translation: self.translation
                + self.rotation * (Vec3::from_array(local.translation) * self.scale),
            rotation: (self.rotation * Quat::from_array(local.rotation)).normalize(),
            scale: self.scale * scale,
        };
        (pose.translation.is_finite() && pose.rotation.is_finite() && pose.scale.is_finite())
            .then_some(pose)
    }

    /// Un point et une rotation de l'asset, exprimés dans ce repère, sans son échelle.
    fn local(&self, point: Vec3, rotation: Quat) -> (Vec3, Quat) {
        let inverse = self.rotation.inverse();
        (
            inverse * (point - self.translation),
            (inverse * rotation).normalize(),
        )
    }
}

/// L'échelle d'une transformation si elle est uniforme et positive.
fn uniform_scale(local: &Transform) -> Option<f32> {
    let scale = local.scale[0];
    (local.has_uniform_scale() && scale > 0.0).then_some(scale)
}

/// Les nodes d'une assembly à la pose de repos, et ses corps (ADR-151 D2).
///
/// Chaque node appartient au corps dont la racine est son plus proche ancêtre, ou lui-même ;
/// un node qu'aucune racine ne réclame appartient au corps racine. Le repère du corps racine
/// est celui de l'asset ; celui d'un autre corps est la pose de son node racine, sans son
/// échelle.
struct Skeleton {
    /// Poses dans l'asset, parent avant enfant (C-22) ; `None` pour une chaîne qu'une échelle
    /// non uniforme rompt — elle ne peut porter ni collider, ni racine de corps, ni joint.
    poses: Vec<Option<NodePose>>,
    /// Le rang du corps de chaque node.
    owner: Vec<usize>,
    /// Le repère de chaque corps dans l'asset.
    frames: Vec<NodePose>,
}

impl Skeleton {
    /// `None` si un parent suit son enfant, ou si une racine de corps est hors de la table ou
    /// sur une chaîne rompue.
    fn new(entries: &[AssemblyBodyEntry], nodes: &[NodeDesc]) -> Option<Self> {
        let mut poses: Vec<Option<NodePose>> = Vec::with_capacity(nodes.len());
        for (index, node) in nodes.iter().enumerate() {
            let parent = if node.is_root() {
                Some(NodePose::ROOT)
            } else {
                let parent = node.parent as usize;
                if parent >= index {
                    return None;
                }
                poses[parent]
            };
            poses.push(parent.and_then(|pose| pose.then(&node.local)));
        }

        // Deux corps au même node racine : le second n'aurait aucun collider, et `layout` le
        // refuse.
        let roots: Vec<u32> = entries.iter().map(|entry| entry.root_node).collect();
        if roots[1..].iter().any(|root| *root as usize >= nodes.len()) {
            return None;
        }
        let mut owner = vec![0usize; nodes.len()];
        for (index, node) in nodes.iter().enumerate() {
            owner[index] = match roots.iter().position(|root| *root as usize == index) {
                Some(rank) => rank,
                None if node.is_root() => 0,
                None => owner[node.parent as usize],
            };
        }
        let mut frames = vec![NodePose::ROOT; roots.len()];
        for (rank, root) in roots.iter().enumerate().skip(1) {
            frames[rank] = poses[*root as usize]?;
        }
        Some(Self {
            poses,
            owner,
            frames,
        })
    }
}

/// Range les colliders en corps (ADR-151 D2). Un collider de node est posé dans le repère de
/// son corps par la pose de son node composée avec la sienne, sa forme mise à l'échelle de
/// cette chaîne ; un collider qu'aucun node ne porte (le collider de l'asset entier) va au
/// corps racine tel quel.
fn layout(
    skeleton: &Skeleton,
    entries: &[AssemblyBodyEntry],
    nodes: &[NodeDesc],
    colliders: &[ColliderDesc],
    points: &[[f32; 3]],
    children: &[ColliderDesc],
) -> Option<Vec<AssemblyBody>> {
    // Le node porteur de chaque collider, au plus un.
    let mut carrier: Vec<Option<usize>> = vec![None; colliders.len()];
    for (index, node) in nodes.iter().enumerate() {
        if node.collider == NONE_U32 {
            continue;
        }
        let slot = carrier.get_mut(node.collider as usize)?;
        if slot.replace(index).is_some() {
            return None;
        }
    }

    let mut placed: Vec<Vec<BodyCollider>> = vec![Vec::new(); entries.len()];
    for (index, collider) in colliders.iter().enumerate() {
        let shape = Shape::from_collider_shape(&collider.shape, points, children).ok()?;
        let local_scale = uniform_scale(&collider.local)?;
        let local_translation = Vec3::from_array(collider.local.translation);
        let local_rotation = Quat::from_array(collider.local.rotation);
        let (rank, translation, rotation, scale) = match carrier[index] {
            None => (0, local_translation, local_rotation, local_scale),
            Some(node) => {
                let pose = skeleton.poses[node]?;
                let in_asset = pose.translation + pose.rotation * (local_translation * pose.scale);
                let rank = skeleton.owner[node];
                let (translation, rotation) =
                    skeleton.frames[rank].local(in_asset, pose.rotation * local_rotation);
                (rank, translation, rotation, pose.scale * local_scale)
            }
        };
        #[allow(clippy::float_cmp)] // une échelle exactement unitaire laisse la forme telle quelle
        let shape = if scale == 1.0 {
            shape
        } else {
            shape.scaled(scale)
        };
        placed[rank].push(BodyCollider {
            shape,
            density: collider.density,
            // L'index `collider.material` (DM-06), que le pilote résout par la table DM-07 de
            // la session (ADR-149) — à la création, puis à chaque table reçue.
            material: ContactMaterial::unresolved(collider.material),
            translation,
            rotation,
        });
    }
    if placed.iter().any(Vec::is_empty) {
        return None;
    }

    Some(
        entries
            .iter()
            .zip(&skeleton.frames)
            .zip(placed)
            .map(|((entry, frame), colliders)| AssemblyBody {
                node: entry.root_node,
                translation: frame.translation,
                rotation: frame.rotation,
                kind: match entry.desc.body_type {
                    0 => BodyKind::Static,
                    1 => BodyKind::Kinematic,
                    _ => BodyKind::Dynamic,
                },
                colliders,
                desc: entry.desc,
            })
            .collect(),
    )
}

/// Le joint de rang `index` (ADR-151 D3, D4) : cohérent avec son type et avec les corps de
/// l'assembly, ses repères dans ses deux corps pris à la pose de repos. `anchor` et `axis` sont
/// dans le repère de `b_node` ; l'axe X des deux repères est `axis`, ou celui de `b_node` sans
/// axe. `None` pour un joint incohérent.
fn assembly_joint(
    skeleton: &Skeleton,
    entries: &[AssemblyBodyEntry],
    jam_points: &[[f32; 2]],
    index: usize,
    desc: &JointDesc,
) -> Option<AssemblyJoint> {
    if !coherent(desc, entries, jam_points) {
        return None;
    }
    let (body_a, body_b) = (usize::from(desc.body_a), usize::from(desc.body_b));
    let node = (*skeleton.poses.get(desc.b_node as usize)?)?;
    let anchor = node.translation + node.rotation * (Vec3::from_array(desc.anchor) * node.scale);
    let axis = Vec3::from_array(desc.axis);
    let turn = if axis.length_squared() > f32::EPSILON {
        Quat::from_rotation_arc(Vec3::X, axis.normalize())
    } else {
        Quat::IDENTITY
    };
    let rotation = (node.rotation * turn).normalize();
    let frame_a = skeleton.frames[body_a].local(anchor, rotation);
    let frame_b = skeleton.frames[body_b].local(anchor, rotation);
    Some(AssemblyJoint {
        index: u16::try_from(index).ok()?,
        body_a,
        body_b,
        spec: spec(desc, frame_a, frame_b, jam_points),
    })
}

/// Un joint cohérent (ADR-151 D3, D7) : deux corps distincts de l'assembly, `b_node` racine du
/// second ; limites et moteur sur un axe libre seulement (`revolute`, `prismatic`), friction
/// sur un type qui laisse bouger, modes d'axes sur `generic` seulement, une longueur pour
/// `rope` ; un grippage sur des limites déclarées, sa courbe dans l'annexe, d'abscisses
/// positives strictement croissantes et de fractions dans [0, 1].
fn coherent(desc: &JointDesc, entries: &[AssemblyBodyEntry], jam_points: &[[f32; 2]]) -> bool {
    let (a, b) = (usize::from(desc.body_a), usize::from(desc.body_b));
    if a == b || a >= entries.len() || b >= entries.len() || entries[b].root_node != desc.b_node {
        return false;
    }
    let free_axis = matches!(desc.kind, joint_kind::REVOLUTE | joint_kind::PRISMATIC);
    let moving = free_axis || matches!(desc.kind, joint_kind::SPHERICAL | joint_kind::GENERIC);
    let on_free_axis =
        joint_flags::LIMITS | joint_flags::MOTOR_VELOCITY | joint_flags::MOTOR_POSITION;
    if (!free_axis && desc.flags & on_free_axis != 0)
        || (!moving && desc.flags & joint_flags::FRICTION != 0)
        || (desc.kind != joint_kind::GENERIC
            && desc
                .axes
                .iter()
                .any(|mode| *mode != joint_axis_mode::LOCKED))
        || (desc.kind == joint_kind::ROPE && desc.max_length <= 0.0)
    {
        return false;
    }
    if desc.flags & joint_flags::JAM == 0 {
        return desc.jam_count == 0;
    }
    let first = desc.jam_first as usize;
    let Some(curve) = first
        .checked_add(desc.jam_count as usize)
        .and_then(|end| jam_points.get(first..end))
    else {
        return false;
    };
    desc.flags & joint_flags::LIMITS != 0
        && !curve.is_empty()
        && curve
            .iter()
            .all(|[x, f]| x.is_finite() && *x >= 0.0 && (0.0..=1.0).contains(f))
        && curve.windows(2).all(|pair| pair[0][0] < pair[1][0])
}

/// Le joint de rapier d'un `JointDesc` cohérent : ses repères, ses réglages déclarés — son
/// grippage pris dans l'annexe `jam_points` —, et rien de ce qu'il ne déclare pas (ADR-109).
fn spec(
    desc: &JointDesc,
    frame_a: (Vec3, Quat),
    frame_b: (Vec3, Quat),
    jam_points: &[[f32; 2]],
) -> JointSpec {
    let declared = |flag: u8| desc.flags & flag != 0;
    let motor = if declared(joint_flags::MOTOR_VELOCITY) {
        Some(JointMotor::Velocity {
            speed: desc.motor_speed,
            max_force: desc.motor_max_force,
        })
    } else if declared(joint_flags::MOTOR_POSITION) {
        Some(JointMotor::Position {
            target: desc.motor_target,
            speed: desc.motor_speed,
            stiffness: desc.motor_stiffness,
            damping: desc.motor_damping,
            max_force: desc.motor_max_force,
        })
    } else {
        None
    };
    let mut axes = [AxisMode::Locked; 6];
    for ((mode, axis), limits) in axes.iter_mut().zip(desc.axes).zip(desc.axis_limits) {
        *mode = match axis {
            joint_axis_mode::FREE => AxisMode::Free,
            joint_axis_mode::LIMITED => AxisMode::Limited(limits),
            _ => AxisMode::Locked,
        };
    }
    JointSpec {
        kind: match desc.kind {
            joint_kind::FIXED => JointKind::Fixed,
            joint_kind::REVOLUTE => JointKind::Revolute,
            joint_kind::PRISMATIC => JointKind::Prismatic,
            joint_kind::SPHERICAL => JointKind::Spherical,
            joint_kind::GENERIC => JointKind::Generic,
            joint_kind::ROPE => JointKind::Rope,
            _ => JointKind::Spring,
        },
        frame_a,
        frame_b,
        limits: declared(joint_flags::LIMITS).then_some(desc.limits),
        motor,
        friction: declared(joint_flags::FRICTION).then_some(desc.friction),
        breaking: BreakThresholds {
            force: desc.break_force,
            torque: desc.break_torque,
            energy: desc.break_energy,
        },
        axes,
        max_length: desc.max_length,
        rest_length: desc.rest_length,
        stiffness: desc.stiffness,
        damping: desc.damping,
        collide: declared(joint_flags::COLLIDE),
        jam: declared(joint_flags::JAM).then(|| {
            let first = desc.jam_first as usize;
            Jam {
                curve: jam_points[first..first + desc.jam_count as usize].to_vec(),
                friction: desc.jam_friction,
            }
        }),
    }
}

/// Les groupes de collision d'une assembly (R-980) : appartenance et filtre communs à tous
/// ses colliders, `None` s'ils diffèrent d'un collider à l'autre ou s'il n'y en a aucun.
fn body_groups(colliders: &[ColliderDesc]) -> Option<CollisionGroups> {
    let first = colliders.first()?;
    colliders
        .iter()
        .all(|c| c.group == first.group && c.mask == first.mask)
        .then(|| CollisionGroups::from_masks(first.group, first.mask))
}

#[cfg(test)]
mod tests {
    use super::*;
    use ax_asset::a3d::{encode_colliders, encode_nodes};
    use ax_model::dm::commands::{
        create_assembly_payload, create_assembly_payload_restored, restored_joint_flags,
    };
    use ax_model::dm::geometry::WorldTransform;
    use ax_model::dm::handle::Handle;
    use ax_model::dm::physics::{BodyDesc, ColliderShape};
    use ax_model::dm::scene::{name_hash, NONE_U16, NO_PARENT};

    fn node(name: &str, parent: u32, local: Transform, collider: u32) -> NodeDesc {
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

    fn boite(translation: [f32; 3]) -> ColliderDesc {
        ColliderDesc {
            shape: ColliderShape::Box {
                half_extents: [0.5; 3],
            },
            local: Transform {
                translation,
                ..Transform::identity()
            },
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

    fn entree(root_node: u32) -> AssemblyBodyEntry {
        AssemblyBodyEntry {
            desc: BodyDesc::undeclared(2),
            root_node,
        }
    }

    /// Une racine, et un enfant décalé de (2, 0, 0), tourné d'un quart de tour autour de Y et
    /// d'échelle 2, qui porte une boîte décalée de (0, 0, 1) dans son repère.
    fn racine_et_porte() -> (Vec<NodeDesc>, Vec<ColliderDesc>) {
        let quart = Quat::from_rotation_y(core::f32::consts::FRAC_PI_2);
        let porte = Transform {
            translation: [2.0, 0.0, 0.0],
            rotation: quart.to_array(),
            scale: [2.0; 3],
        };
        (
            vec![
                node("racine", NO_PARENT, Transform::identity(), NONE_U32),
                node("porte", 0, porte, 0),
            ],
            vec![boite([0.0, 0.0, 1.0])],
        )
    }

    /// Les corps de `entries`, rangés sans section `PHYS` annexe.
    fn ranger(
        entries: &[AssemblyBodyEntry],
        nodes: &[NodeDesc],
        colliders: &[ColliderDesc],
    ) -> Option<Vec<AssemblyBody>> {
        let skeleton = Skeleton::new(entries, nodes)?;
        layout(&skeleton, entries, nodes, colliders, &[], &[])
    }

    fn proche(a: Vec3, b: Vec3) -> bool {
        (a - b).length() < 1e-5
    }

    #[test]
    fn un_collider_de_node_est_pose_par_la_chaine_de_son_node() {
        // Un seul corps : la boîte, à (0, 0, 1) dans la porte, est en (2, 0, 0) + quart de
        // tour de (0, 0, 2) = (4, 0, 0) dans l'asset, deux fois plus grande.
        let (nodes, colliders) = racine_et_porte();
        let bodies = ranger(&[entree(0)], &nodes, &colliders).unwrap();
        let collider = &bodies[0].colliders[0];
        assert!(
            proche(collider.translation, Vec3::new(4.0, 0.0, 0.0)),
            "{collider:?}"
        );
        assert_eq!(
            collider.shape,
            Shape::Cuboid {
                half_extents: [1.0; 3]
            }
        );
    }

    #[test]
    fn un_corps_a_son_node_racine_y_pose_ses_colliders_dans_son_repere() {
        // La porte devient un corps : son repère est sa pose sans échelle ; la boîte y est à
        // (0, 0, 2), sans rotation, toujours deux fois plus grande.
        let (mut nodes, mut colliders) = racine_et_porte();
        nodes[0].collider = 1;
        colliders.push(boite([0.0; 3]));
        let bodies = ranger(&[entree(0), entree(1)], &nodes, &colliders).unwrap();

        assert_eq!(bodies[0].colliders.len(), 1, "la racine garde le sien");
        assert!(proche(bodies[1].translation, Vec3::new(2.0, 0.0, 0.0)));
        let collider = &bodies[1].colliders[0];
        assert!(
            proche(collider.translation, Vec3::new(0.0, 0.0, 2.0)),
            "{collider:?}"
        );
        assert!(collider.rotation.angle_between(Quat::IDENTITY) < 1e-5);
        assert_eq!(bodies[1].node, 1);
    }

    #[test]
    fn l_echelle_d_un_parent_s_applique_a_la_position_de_son_enfant() {
        // La racine est d'échelle 3 : l'enfant, décalé de (1, 0, 0), est en (3, 0, 0), et sa
        // boîte trois fois plus grande.
        let mut racine = Transform::identity();
        racine.scale = [3.0; 3];
        let mut enfant = Transform::identity();
        enfant.translation = [1.0, 0.0, 0.0];
        let nodes = vec![
            node("racine", NO_PARENT, racine, NONE_U32),
            node("enfant", 0, enfant, 0),
        ];
        let bodies = ranger(&[entree(0)], &nodes, &[boite([0.0; 3])]).unwrap();
        let collider = &bodies[0].colliders[0];
        assert!(
            proche(collider.translation, Vec3::new(3.0, 0.0, 0.0)),
            "{collider:?}"
        );
        assert_eq!(
            collider.shape,
            Shape::Cuboid {
                half_extents: [1.5; 3]
            }
        );
    }

    #[test]
    fn un_collider_qu_aucun_node_ne_porte_va_au_corps_racine_tel_quel() {
        let nodes = vec![node("racine", NO_PARENT, Transform::identity(), NONE_U32)];
        let bodies = ranger(&[entree(0)], &nodes, &[boite([3.0, 0.0, 0.0])]).unwrap();
        assert_eq!(bodies[0].colliders[0].translation, Vec3::new(3.0, 0.0, 0.0));
    }

    #[test]
    fn un_collider_qu_aucun_node_ne_porte_va_au_corps_racine_meme_a_plusieurs_corps() {
        let (mut nodes, mut colliders) = racine_et_porte();
        nodes[0].collider = 1;
        colliders.push(boite([0.0; 3]));
        colliders.push(boite([5.0, 0.0, 0.0]));
        let bodies = ranger(&[entree(0), entree(1)], &nodes, &colliders).unwrap();
        assert_eq!(
            bodies[0].colliders.len(),
            2,
            "le sien et celui de l'asset entier"
        );
        assert_eq!(bodies[0].colliders[1].translation, Vec3::new(5.0, 0.0, 0.0));
        assert_eq!(bodies[1].colliders.len(), 1);
    }

    #[test]
    fn un_collider_porte_par_deux_nodes_est_refuse() {
        let (mut nodes, colliders) = racine_et_porte();
        nodes[0].collider = 0;
        assert!(ranger(&[entree(0)], &nodes, &colliders).is_none());
    }

    #[test]
    fn une_echelle_non_uniforme_sur_la_chaine_d_un_collider_est_refusee() {
        // R-120 : le parent est étiré, l'enfant porteur ne l'est pas — la chaîne l'est.
        let (mut nodes, colliders) = racine_et_porte();
        nodes[0].local.scale = [1.0, 2.0, 1.0];
        assert!(ranger(&[entree(0)], &nodes, &colliders).is_none());
    }

    #[test]
    fn un_corps_sans_collider_est_refuse() {
        let (nodes, colliders) = racine_et_porte();
        assert!(ranger(&[entree(0), entree(1)], &nodes, &colliders).is_none());
    }

    #[test]
    fn un_parent_apres_son_enfant_est_refuse() {
        let (mut nodes, colliders) = racine_et_porte();
        nodes.swap(0, 1);
        nodes[0].parent = 1;
        nodes[1].parent = NO_PARENT;
        assert!(ranger(&[entree(1)], &nodes, &colliders).is_none());
    }

    #[test]
    fn deux_corps_au_meme_node_racine_sont_refuses() {
        let (mut nodes, mut colliders) = racine_et_porte();
        nodes[0].collider = 1;
        colliders.push(boite([0.0; 3]));
        let entries = [entree(0), entree(1), entree(1)];
        assert!(ranger(&entries, &nodes, &colliders).is_none());
    }

    fn payload(header: CreateAssembly, entries: &[AssemblyBodyEntry], with_nodes: bool) -> Vec<u8> {
        payload_joints(header, entries, with_nodes, &[], &[])
    }

    /// Le payload de la racine et de la porte, avec `joints` et les points de grippage `jam`.
    fn payload_joints(
        header: CreateAssembly,
        entries: &[AssemblyBodyEntry],
        with_nodes: bool,
        joints: &[JointDesc],
        jam: &[[f32; 2]],
    ) -> Vec<u8> {
        let (mut nodes, mut colliders) = racine_et_porte();
        nodes[0].collider = 1;
        colliders.push(boite([0.0; 3]));
        let names = vec!["racine".to_owned(), "porte".to_owned()];
        let node_bytes = if with_nodes {
            encode_nodes(&nodes, &names).unwrap()
        } else {
            Vec::new()
        };
        let phys = encode_colliders(&colliders, &[], &[]).unwrap();
        create_assembly_payload(&header, entries, joints, jam, &node_bytes, &phys)
    }

    /// Une charnière entre la racine et la porte : ancrée en (0, 0, 1) dans la porte, autour de
    /// son axe X, avec limites et friction, sans grippage.
    fn charniere() -> JointDesc {
        JointDesc {
            kind: joint_kind::REVOLUTE,
            flags: joint_flags::LIMITS | joint_flags::FRICTION,
            body_a: 0,
            body_b: 1,
            axes: [joint_axis_mode::LOCKED; 6],
            b_node: 1,
            anchor: [0.0, 0.0, 1.0],
            axis: [1.0, 0.0, 0.0],
            limits: [-0.5, 1.5],
            motor_speed: 0.0,
            motor_target: 0.0,
            motor_stiffness: 0.0,
            motor_damping: 0.0,
            motor_max_force: f32::INFINITY,
            friction: 4.0,
            break_force: 8000.0,
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

    fn proche_q(a: Quat, b: Quat) -> bool {
        a.angle_between(b) < 1e-5
    }

    #[test]
    fn un_joint_recoit_ses_reperes_dans_ses_deux_corps_a_la_pose_de_repos() {
        // La porte est en (2, 0, 0), d'un quart de tour autour de Y, d'échelle 2 : l'ancrage
        // (0, 0, 1) de son repère est en (2, 0, 0) + (2, 0, 0) = (4, 0, 0) dans l'asset, donc
        // dans le corps racine ; dans le corps de la porte, sans son échelle, en (0, 0, 2).
        let quart = Quat::from_rotation_y(core::f32::consts::FRAC_PI_2);
        let corps = [entree(0), entree(1)];
        let request = decode(&payload_joints(
            en_tete(),
            &corps,
            true,
            &[charniere()],
            &[],
        ))
        .unwrap();
        let joint = &request.joints[0];
        assert_eq!((joint.index, joint.body_a, joint.body_b), (0, 0, 1));
        let (anchor_a, rotation_a) = joint.spec.frame_a;
        let (anchor_b, rotation_b) = joint.spec.frame_b;
        assert!(proche(anchor_a, Vec3::new(4.0, 0.0, 0.0)), "{anchor_a}");
        assert!(proche_q(rotation_a, quart), "{rotation_a}");
        assert!(proche(anchor_b, Vec3::new(0.0, 0.0, 2.0)), "{anchor_b}");
        assert!(proche_q(rotation_b, Quat::IDENTITY), "{rotation_b}");
        assert_eq!(joint.spec.kind, JointKind::Revolute);
        assert_eq!(joint.spec.limits, Some([-0.5, 1.5]));
        assert_eq!(joint.spec.friction, Some(4.0));
        assert_eq!(joint.spec.motor, None);
        assert_eq!(joint.spec.breaking.force, 8000.0);
    }

    #[test]
    fn l_axe_d_un_joint_devient_l_axe_x_de_ses_reperes() {
        // Un axe Y dans la porte : le repère du joint tourne X vers Y, puis suit la porte.
        let mut joint = charniere();
        joint.axis = [0.0, 1.0, 0.0];
        let corps = [entree(0), entree(1)];
        let request = decode(&payload_joints(en_tete(), &corps, true, &[joint], &[])).unwrap();
        let (_, rotation_b) = request.joints[0].spec.frame_b;
        assert!(proche(rotation_b * Vec3::X, Vec3::Y), "{rotation_b}");
        let (_, rotation_a) = request.joints[0].spec.frame_a;
        let quart = Quat::from_rotation_y(core::f32::consts::FRAC_PI_2);
        assert!(
            proche(rotation_a * Vec3::X, quart * Vec3::Y),
            "{rotation_a}"
        );
    }

    #[test]
    fn un_moteur_declare_devient_celui_du_joint() {
        let mut vitesse = charniere();
        vitesse.flags |= joint_flags::MOTOR_VELOCITY;
        vitesse.motor_speed = 2.0;
        vitesse.motor_max_force = 300.0;
        let mut servo = charniere();
        servo.flags |= joint_flags::MOTOR_POSITION;
        servo.motor_target = 1.2;
        servo.motor_stiffness = 50.0;
        servo.motor_damping = 5.0;
        let corps = [entree(0), entree(1)];
        let lu = |joint: JointDesc| {
            decode(&payload_joints(en_tete(), &corps, true, &[joint], &[]))
                .unwrap()
                .joints[0]
                .spec
                .motor
        };
        assert_eq!(
            lu(vitesse),
            Some(JointMotor::Velocity {
                speed: 2.0,
                max_force: 300.0
            })
        );
        assert_eq!(
            lu(servo),
            Some(JointMotor::Position {
                target: 1.2,
                speed: 0.0,
                stiffness: 50.0,
                damping: 5.0,
                max_force: f32::INFINITY
            })
        );
    }

    #[test]
    fn un_joint_incoherent_abandonne_l_assembly() {
        let corps = [entree(0), entree(1)];
        let refuse = |change: fn(&mut JointDesc), jam: &[[f32; 2]]| {
            let mut joint = charniere();
            change(&mut joint);
            decode(&payload_joints(en_tete(), &corps, true, &[joint], jam)).is_none()
        };
        assert!(!refuse(|_| {}, &[]), "la charnière est cohérente");
        assert!(
            refuse(|j| j.body_a = 1, &[]),
            "deux bouts sur un même corps"
        );
        assert!(refuse(|j| j.body_b = 2, &[]), "un corps hors de l'assembly");
        assert!(
            refuse(|j| j.b_node = 0, &[]),
            "b_node n'est pas la racine du second corps"
        );
        assert!(
            refuse(|j| j.kind = joint_kind::FIXED, &[]),
            "limites sur un fixed"
        );
        assert!(
            refuse(
                |j| {
                    j.kind = joint_kind::SPHERICAL;
                    j.flags = joint_flags::MOTOR_VELOCITY;
                },
                &[]
            ),
            "moteur sur un spherical"
        );
        assert!(
            refuse(
                |j| {
                    j.kind = joint_kind::FIXED;
                    j.flags = joint_flags::FRICTION;
                },
                &[]
            ),
            "friction sur un fixed"
        );
        assert!(
            refuse(|j| j.axes[0] = joint_axis_mode::FREE, &[]),
            "modes d'axes hors generic"
        );
        assert!(
            refuse(
                |j| {
                    j.kind = joint_kind::ROPE;
                    j.flags = 0;
                },
                &[]
            ),
            "corde sans longueur"
        );
        assert!(
            refuse(|j| j.jam_count = 1, &[[0.0, 1.0]]),
            "courbe sans grippage"
        );
        assert!(refuse(|j| j.b_node = 9, &[]), "b_node hors de la table");
    }

    #[test]
    fn la_courbe_de_grippage_est_lue_et_jugee_dans_l_annexe() {
        let corps = [entree(0), entree(1)];
        let avec = |flags: u8, first: u32, count: u32, jam: &[[f32; 2]]| {
            let mut joint = charniere();
            joint.flags = flags | joint_flags::JAM;
            joint.jam_first = first;
            joint.jam_count = count;
            decode(&payload_joints(en_tete(), &corps, true, &[joint], jam)).is_some()
        };
        let courbe = [[9.0, 9.0], [0.0, 1.0], [0.05, 0.5], [0.12, 0.0]];
        assert!(
            avec(joint_flags::LIMITS, 1, 3, &courbe),
            "une courbe dans l'annexe"
        );
        assert!(!avec(0, 1, 3, &courbe), "un grippage sans limites");
        assert!(
            !avec(joint_flags::LIMITS, 2, 3, &courbe),
            "hors de l'annexe"
        );
        assert!(!avec(joint_flags::LIMITS, 1, 0, &courbe), "une courbe vide");
        let decroissante = [[0.1, 1.0], [0.05, 0.0]];
        assert!(
            !avec(joint_flags::LIMITS, 0, 2, &decroissante),
            "abscisses décroissantes"
        );
        let egales = [[0.1, 1.0], [0.1, 0.0]];
        assert!(
            !avec(joint_flags::LIMITS, 0, 2, &egales),
            "abscisses égales"
        );
        let fraction = [[0.0, 1.5]];
        assert!(
            !avec(joint_flags::LIMITS, 0, 1, &fraction),
            "fraction au-delà de 1"
        );
        let negative = [[-0.1, 1.0]];
        assert!(
            !avec(joint_flags::LIMITS, 0, 1, &negative),
            "abscisse négative"
        );
    }

    #[test]
    fn un_payload_tronque_dans_ses_joints_est_refuse() {
        let corps = [entree(0), entree(1)];
        let complet = payload_joints(en_tete(), &corps, true, &[charniere()], &[[0.0, 1.0]]);
        let joints = CreateAssembly::BODIES_OFFSET + 2 * AssemblyBodyEntry::WIRE_BYTES;
        assert!(decode(&complet[..joints + JointDesc::BYTES - 1]).is_none());
        assert!(decode(&complet[..joints + JointDesc::BYTES + 4]).is_none());
    }

    fn en_tete() -> CreateAssembly {
        CreateAssembly {
            handle: Handle::new(7, 1),
            dimension: 0,
            spawn: WorldTransform {
                position: [0.0, 64.0, 0.0],
                rotation: [0.0, 0.0, 0.0, 1.0],
            },
            body_kind: 2,
            flags: 0,
            body_count: 0,
            joint_count: 0,
            jam_points: 0,
            assembly_mass: 0.0,
            node_bytes: 0,
        }
    }

    #[test]
    fn le_payload_complet_se_decode_en_corps() {
        let request = decode(&payload(en_tete(), &[entree(0), entree(1)], true)).unwrap();
        assert_eq!(request.bodies.len(), 2);
        assert_eq!(request.bodies[1].node, 1);
        assert_eq!(request.header.handle, Handle::new(7, 1));
    }

    #[test]
    fn les_ecarts_d_en_tete_abandonnent_l_assembly() {
        let corps = [entree(0), entree(1)];
        // Masse négative, racine d'un autre type, second corps non dynamique, deux corps sans
        // section NODE.
        let mut masse = en_tete();
        masse.assembly_mass = -1.0;
        assert!(decode(&payload(masse, &corps, true)).is_none());
        let mut statique = en_tete();
        statique.body_kind = 0;
        assert!(decode(&payload(statique, &corps, true)).is_none());
        let mut cinematique = corps;
        cinematique[1].desc = BodyDesc::undeclared(1);
        assert!(decode(&payload(en_tete(), &cinematique, true)).is_none());
        assert!(decode(&payload(en_tete(), &corps, false)).is_none());
        // Un seul corps sans section NODE : ses colliders tels quels.
        assert!(decode(&payload(en_tete(), &corps[..1], false)).is_some());
    }

    #[test]
    fn le_grippage_declare_part_avec_le_joint() {
        let mut joint = charniere();
        joint.flags |= joint_flags::JAM;
        joint.jam_first = 1;
        joint.jam_count = 2;
        joint.jam_friction = 60.0;
        let annexe = [[9.0, 0.0], [0.05, 0.5], [0.12, 0.0]];
        let corps = [entree(0), entree(1)];
        let request = decode(&payload_joints(en_tete(), &corps, true, &[joint], &annexe)).unwrap();
        assert_eq!(
            request.joints[0].spec.jam,
            Some(Jam {
                curve: vec![[0.05, 0.5], [0.12, 0.0]],
                friction: 60.0
            })
        );
        let sans = decode(&payload_joints(
            en_tete(),
            &corps,
            true,
            &[charniere()],
            &[],
        ))
        .unwrap();
        assert_eq!(sans.joints[0].spec.jam, None);
    }

    /// Le payload de la racine et de la porte, sa charnière, et l'état restauré `restored`.
    fn payload_restaure(restored: &[RestoredBody], joints: &[RestoredJoint]) -> Vec<u8> {
        let (mut nodes, mut colliders) = racine_et_porte();
        nodes[0].collider = 1;
        colliders.push(boite([0.0; 3]));
        let names = vec!["racine".to_owned(), "porte".to_owned()];
        let node_bytes = encode_nodes(&nodes, &names).unwrap();
        let phys = encode_colliders(&colliders, &[], &[]).unwrap();
        create_assembly_payload_restored(
            &en_tete(),
            &[entree(0), entree(1)],
            &[charniere()],
            &[],
            &node_bytes,
            Some((restored, joints)),
            &phys,
        )
    }

    fn etats() -> (Vec<RestoredBody>, Vec<RestoredJoint>) {
        (
            vec![
                RestoredBody {
                    position: [10.25, 70.5, -3.125],
                    rotation: [0.0, 0.247_403_96, 0.0, 0.968_912_4],
                    lin_vel: [3.0, 0.0, 0.0],
                    ang_vel: [0.0, 0.25, 0.0],
                },
                RestoredBody {
                    position: [12.0, 70.5, -4.0],
                    rotation: [0.0, 0.0, 0.0, 1.0],
                    lin_vel: [3.0, 0.5, 0.0],
                    ang_vel: [0.0, 0.0, 0.0],
                },
            ],
            vec![RestoredJoint {
                flags: restored_joint_flags::BROKEN,
                misalignment: 0.0,
            }],
        )
    }

    #[test]
    fn adr153_d3_l_etat_restaure_se_lit_entre_node_et_phys() {
        let (corps, joints) = etats();
        let request = decode(&payload_restaure(&corps, &joints)).expect("payload restauré lisible");
        assert_eq!(
            request.restored,
            Some(Restored {
                bodies: corps,
                joints
            })
        );
        // PHYS se lit encore après la section : les deux corps ont leurs colliders.
        assert_eq!(request.bodies.len(), 2);
        assert!(request.bodies.iter().all(|body| !body.colliders.is_empty()));
        // Sans le drapeau, aucune section n'est lue.
        let sans = decode(&payload(en_tete(), &[entree(0), entree(1)], true)).unwrap();
        assert_eq!(sans.restored, None);
    }

    #[test]
    fn adr153_d3_un_etat_restaure_fautif_fait_refuser_l_assembly() {
        let (mut corps, joints) = etats();
        corps[1].lin_vel[0] = f32::NAN;
        assert!(
            decode(&payload_restaure(&corps, &joints)).is_none(),
            "vitesse non finie"
        );
        let (mut corps, joints) = etats();
        corps[0].rotation = [0.0; 4];
        assert!(
            decode(&payload_restaure(&corps, &joints)).is_none(),
            "rotation nulle"
        );
        let (corps, mut joints) = etats();
        joints[0].misalignment = -0.5;
        assert!(
            decode(&payload_restaure(&corps, &joints)).is_none(),
            "désalignement négatif"
        );
        // Le drapeau sans la section : PHYS se cherche plus loin, et ne se lit pas.
        let mut tronque = payload(en_tete(), &[entree(0), entree(1)], true);
        tronque[57] |= create_assembly_flags::RESTORED;
        assert!(decode(&tronque).is_none(), "drapeau sans section");
    }
}
