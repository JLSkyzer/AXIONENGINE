//! `CREATE_ASSEMBLY` d'une assembly à un ou plusieurs corps (ADR-151 D2, D7).
//!
//! Décode le payload — en-tête, corps, sections `NODE` et `PHYS` de l'asset — et range
//! chaque collider dans le corps de son node, posé dans le repère de ce corps : la chaîne
//! des transformations des nodes est composée depuis la racine de l'asset, échelle
//! uniforme comprise (R-120). Rien n'est créé ici : le pilote reçoit des corps prêts, et
//! un payload fautif n'en donne aucun.

use ax_asset::a3d::{decode_colliders, decode_nodes};
use ax_math::{Quat, Vec3};
use ax_model::dm::commands::{AssemblyBodyEntry, CreateAssembly, MAX_ASSEMBLY_BODIES};
use ax_model::dm::geometry::Transform;
use ax_model::dm::physics::ColliderDesc;
use ax_model::dm::scene::{NodeDesc, NONE_U32};
use ax_physics::{AssemblyBody, BodyCollider, BodyKind, CollisionGroups, ContactMaterial, Shape};

/// Une assembly décodée, prête pour `SimDriver::create_assembly_bodies`.
#[derive(Debug)]
pub(crate) struct AssemblyRequest {
    /// L'en-tête lu.
    pub header: CreateAssembly,
    /// Les corps, le corps racine d'abord, chacun avec ses colliders dans son repère.
    pub bodies: Vec<AssemblyBody>,
    /// Les groupes de collision communs à tous les colliders (R-980).
    pub groups: CollisionGroups,
}

/// Décode un payload de `CREATE_ASSEMBLY`. `None` au premier écart — en-tête tronqué, aucun
/// corps ou plus de 64, joints annoncés, description invalide, corps racine d'un autre type
/// que l'en-tête ou autre corps non dynamique, masse non finie ou négative, section
/// illisible, node ou collider hors de leur table, échelle non uniforme sur la chaîne d'un
/// collider, corps sans collider, groupes discordants : l'assembly est abandonnée entière,
/// jamais formée à moitié.
pub(crate) fn decode(payload: &[u8]) -> Option<AssemblyRequest> {
    let header = CreateAssembly::read_le(payload)?;
    let count = usize::from(header.body_count);
    // Les joints arrivent avec C-34 (ADR-151, tranche 3) : un payload qui en annonce n'est
    // pas encore lisible.
    if count == 0 || count > MAX_ASSEMBLY_BODIES || header.joint_count != 0 {
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

    let nodes_at = CreateAssembly::BODIES_OFFSET + count * AssemblyBodyEntry::WIRE_BYTES;
    let node_bytes = usize::try_from(header.node_bytes).ok()?;
    let nodes_end = nodes_at.checked_add(node_bytes)?;
    // Sans section NODE, les colliders sont pris tels quels ; un second corps n'y aurait pas
    // de node racine, et `layout` le refuse.
    let nodes = if node_bytes == 0 {
        Vec::new()
    } else {
        decode_nodes(payload.get(nodes_at..nodes_end)?).ok()?.nodes
    };
    let phys_at = nodes_end.next_multiple_of(8);
    let (colliders, points, children) = decode_colliders(payload.get(phys_at..)?).ok()?;
    let groups = body_groups(&colliders)?;
    let bodies = layout(&entries, &nodes, &colliders, &points, &children)?;
    Some(AssemblyRequest {
        header,
        bodies,
        groups,
    })
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
}

/// L'échelle d'une transformation si elle est uniforme et positive.
fn uniform_scale(local: &Transform) -> Option<f32> {
    let scale = local.scale[0];
    (local.has_uniform_scale() && scale > 0.0).then_some(scale)
}

/// Range les colliders en corps (ADR-151 D2).
///
/// Chaque node appartient au corps dont la racine est son plus proche ancêtre, ou lui-même ;
/// un node qu'aucune racine ne réclame appartient au corps racine. Le repère du corps racine
/// est celui de l'asset ; celui d'un autre corps est la pose de son node racine, sans son
/// échelle. Un collider de node y est posé par la pose de son node composée avec la sienne,
/// sa forme mise à l'échelle de cette chaîne ; un collider qu'aucun node ne porte (le
/// collider de l'asset entier) va au corps racine tel quel.
fn layout(
    entries: &[AssemblyBodyEntry],
    nodes: &[NodeDesc],
    colliders: &[ColliderDesc],
    points: &[[f32; 3]],
    children: &[ColliderDesc],
) -> Option<Vec<AssemblyBody>> {
    // Poses dans l'asset, parent avant enfant (C-22) ; `None` pour une chaîne qu'une échelle
    // non uniforme rompt — elle ne peut porter ni collider ni racine de corps.
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

    // Deux corps au même node racine : le second n'aurait aucun collider, et il est refusé
    // plus bas.
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

    let mut placed: Vec<Vec<BodyCollider>> = vec![Vec::new(); roots.len()];
    for (index, collider) in colliders.iter().enumerate() {
        let shape = Shape::from_collider_shape(&collider.shape, points, children).ok()?;
        let local_scale = uniform_scale(&collider.local)?;
        let local_translation = Vec3::from_array(collider.local.translation);
        let local_rotation = Quat::from_array(collider.local.rotation);
        let (rank, translation, rotation, scale) = match carrier[index] {
            None => (0, local_translation, local_rotation, local_scale),
            Some(node) => {
                let pose = poses[node]?;
                let in_asset = pose.translation + pose.rotation * (local_translation * pose.scale);
                let frame = frames[owner[node]];
                let inverse = frame.rotation.inverse();
                (
                    owner[node],
                    inverse * (in_asset - frame.translation),
                    (inverse * pose.rotation * local_rotation).normalize(),
                    pose.scale * local_scale,
                )
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
            .zip(frames)
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
    use ax_model::dm::commands::create_assembly_payload;
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

    fn proche(a: Vec3, b: Vec3) -> bool {
        (a - b).length() < 1e-5
    }

    #[test]
    fn un_collider_de_node_est_pose_par_la_chaine_de_son_node() {
        // Un seul corps : la boîte, à (0, 0, 1) dans la porte, est en (2, 0, 0) + quart de
        // tour de (0, 0, 2) = (4, 0, 0) dans l'asset, deux fois plus grande.
        let (nodes, colliders) = racine_et_porte();
        let bodies = layout(&[entree(0)], &nodes, &colliders, &[], &[]).unwrap();
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
        let bodies = layout(&[entree(0), entree(1)], &nodes, &colliders, &[], &[]).unwrap();

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
        let bodies = layout(&[entree(0)], &nodes, &[boite([0.0; 3])], &[], &[]).unwrap();
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
        let bodies = layout(&[entree(0)], &nodes, &[boite([3.0, 0.0, 0.0])], &[], &[]).unwrap();
        assert_eq!(bodies[0].colliders[0].translation, Vec3::new(3.0, 0.0, 0.0));
    }

    #[test]
    fn un_collider_qu_aucun_node_ne_porte_va_au_corps_racine_meme_a_plusieurs_corps() {
        let (mut nodes, mut colliders) = racine_et_porte();
        nodes[0].collider = 1;
        colliders.push(boite([0.0; 3]));
        colliders.push(boite([5.0, 0.0, 0.0]));
        let bodies = layout(&[entree(0), entree(1)], &nodes, &colliders, &[], &[]).unwrap();
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
        assert!(layout(&[entree(0)], &nodes, &colliders, &[], &[]).is_none());
    }

    #[test]
    fn une_echelle_non_uniforme_sur_la_chaine_d_un_collider_est_refusee() {
        // R-120 : le parent est étiré, l'enfant porteur ne l'est pas — la chaîne l'est.
        let (mut nodes, colliders) = racine_et_porte();
        nodes[0].local.scale = [1.0, 2.0, 1.0];
        assert!(layout(&[entree(0)], &nodes, &colliders, &[], &[]).is_none());
    }

    #[test]
    fn un_corps_sans_collider_est_refuse() {
        let (nodes, colliders) = racine_et_porte();
        assert!(layout(&[entree(0), entree(1)], &nodes, &colliders, &[], &[]).is_none());
    }

    #[test]
    fn un_parent_apres_son_enfant_est_refuse() {
        let (mut nodes, colliders) = racine_et_porte();
        nodes.swap(0, 1);
        nodes[0].parent = 1;
        nodes[1].parent = NO_PARENT;
        assert!(layout(&[entree(1)], &nodes, &colliders, &[], &[]).is_none());
    }

    #[test]
    fn deux_corps_au_meme_node_racine_sont_refuses() {
        let (mut nodes, mut colliders) = racine_et_porte();
        nodes[0].collider = 1;
        colliders.push(boite([0.0; 3]));
        let entries = [entree(0), entree(1), entree(1)];
        assert!(layout(&entries, &nodes, &colliders, &[], &[]).is_none());
    }

    fn payload(header: CreateAssembly, entries: &[AssemblyBodyEntry], with_nodes: bool) -> Vec<u8> {
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
        create_assembly_payload(&header, entries, &node_bytes, &phys)
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
            _pad: 0,
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
        // Joints annoncés avant C-34, masse négative, racine d'un autre type, second corps
        // non dynamique, deux corps sans section NODE.
        let mut joints = en_tete();
        joints.joint_count = 1;
        assert!(decode(&payload(joints, &corps, true)).is_none());
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
}
