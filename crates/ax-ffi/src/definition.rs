//! Compilation d'une definition contre son asset (étapes 3 et 4 de C-27, ADR-156 D2).
//!
//! Java remet un [`Definition`] (`DEFN`) : ce que la definition déclare, les noms d'asset en
//! empreintes. La compilation les résout contre l'asset, forme la liste des corps — les déclarés,
//! puis ceux que créent les `b_node` des joints (ADR-151 D2) —, marque les colliders de l'asset —
//! groupes de collision (R-980), matériau de leur part (ADR-149 D2), `REFITTABLE` (R-623) —, puis
//! les range en corps et pose les joints par le pipeline de `CREATE_ASSEMBLY`
//! ([`crate::assembly::prepare`]). Le résultat se calcule une fois, pas à chaque apparition.
//!
//! Une definition qui ne se compile pas est refusée seule (R-580) : `E-7001` pour un nom
//! introuvable ou une assembly qui ne se forme pas, `E-3050` au-delà de 64 corps (R-190), `E-7002`
//! pour une racine de corps sous un node animé (R-931). Une racine elle-même animée n'est qu'un
//! avertissement : la physique l'emporte (R-930).

use ax_asset::a3d::decode_animations;
use ax_model::dm::animation::BoneDesc;
use ax_model::dm::commands::{AssemblyBodyEntry, MAX_ASSEMBLY_BODIES};
use ax_model::dm::definition::{
    definition_flags, part_flags, reference_category, Definition, DefinitionRef,
};
use ax_model::dm::joints::JointDesc;
use ax_model::dm::physics::{collider_flags, ColliderDesc, ColliderShape};
use ax_model::dm::scene::{NodeDesc, NONE_U16, NONE_U32};
use ax_physics::{AssemblyBody, AssemblyJoint, CollisionGroups};

use crate::assembly::{prepare, AnimationVerdict, ServerAnimation};

/// `E-7001` : definition invalide (R-580).
pub(crate) const E_DEFINITION_INVALID: i32 = -7001;

/// `E-3050` : plafond d'assembly dépassé (R-190).
pub(crate) const E_ASSEMBLY_LIMIT: i32 = -3050;

/// `E-7002` : node physique sous un parent animé (R-931).
pub(crate) const E_PHYSICS_UNDER_ANIMATED: i32 = -7002;

/// Index de l'acier générique dans la table des matériaux de la session : celui d'un collider
/// dont la part ne nomme pas de matériau, ou qui n'est d'aucune part (R-1032, ADR-149 D2).
pub(crate) const GENERIC_STEEL: u16 = 0;

/// Type de corps dynamique (DM-08).
const BODY_DYNAMIC: u8 = 2;

/// Ce que la compilation lit de l'asset : ses nodes, ses colliders et leurs annexes, sa section
/// `ANIM` s'il en a une, et ses os.
#[derive(Debug, Clone, Copy)]
pub(crate) struct AssetView<'a> {
    /// Les nodes (DM-03), parent avant enfant.
    pub nodes: &'a [NodeDesc],
    /// Les colliders de la section `PHYS` (DM-06).
    pub colliders: &'a [ColliderDesc],
    /// L'annexe des points d'enveloppe.
    pub points: &'a [[f32; 3]],
    /// L'annexe des enfants de composés.
    pub children: &'a [ColliderDesc],
    /// La section `ANIM`, telle que l'asset la porte.
    pub anim: Option<&'a [u8]>,
    /// Les os de la section `SKEL` (DM-10).
    pub bones: &'a [BoneDesc],
}

/// Une definition compilée : ce que la création d'une assembly reprend tel quel.
///
/// Le résumé de l'ABI n'en lit que les racines, les joints, l'animation et les avertissements :
/// `CREATE_ASSEMBLY` lira le reste en tranche 4 d'ADR-156, et le refit de la déformation (C-42, M6)
/// les colliders marqués `REFITTABLE`.
#[derive(Debug)]
#[allow(dead_code)]
pub(crate) struct CompiledDefinition {
    /// Type du corps racine : `0` statique, `1` cinématique, `2` dynamique.
    pub body_kind: u8,
    /// Les corps de l'assembly se touchent entre eux (§10.4).
    pub self_collision: bool,
    /// Masse à répartir entre les corps sans masse déclarée, en kg ; `0` pour un seul corps.
    pub assembly_mass: f32,
    /// Node racine de chaque corps, le corps racine d'abord.
    pub roots: Vec<u32>,
    /// Les corps, chacun avec ses colliders dans son repère.
    pub bodies: Vec<AssemblyBody>,
    /// Les joints, dans l'ordre de la definition.
    pub joints: Vec<AssemblyJoint>,
    /// Les groupes de collision de l'assembly (R-980).
    pub groups: CollisionGroups,
    /// L'animation que le serveur joue, si un collider dépend d'un node animé (R-1430).
    pub animation: Option<ServerAnimation>,
    /// Les colliders de l'asset, marqués : groupes, matériau, `REFITTABLE`.
    pub colliders: Vec<ColliderDesc>,
    /// Les enfants de composés, marqués comme leur composé.
    pub children: Vec<ColliderDesc>,
    /// Les avertissements de la compilation (R-930).
    pub warnings: Vec<String>,
}

/// Le refus d'une definition.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Refusal {
    /// [`E_DEFINITION_INVALID`], [`E_ASSEMBLY_LIMIT`] ou [`E_PHYSICS_UNDER_ANIMATED`].
    pub code: i32,
    /// Rang de la référence fautive dans `DEFN`, si le refus en désigne une : Java en tire le
    /// chemin JSON et le nom.
    pub reference: Option<u32>,
    /// Ce qui ne va pas.
    pub message: String,
}

impl Refusal {
    fn invalid(reference: Option<u32>, message: impl Into<String>) -> Self {
        Self {
            code: E_DEFINITION_INVALID,
            reference,
            message: message.into(),
        }
    }
}

/// Compile une definition contre son asset.
///
/// # Errors
///
/// [`Refusal`] : un nom introuvable, deux corps au même node, un joint dont les deux bouts tombent
/// sur le même corps, plus de 64 corps, une assembly qui ne se forme pas, une racine de corps sous
/// un node animé.
pub(crate) fn compile(
    definition: &Definition,
    asset: &AssetView<'_>,
) -> Result<CompiledDefinition, Refusal> {
    let resolved = resolve(&definition.refs, asset)?;
    let node_of = |reference: u32| resolved[reference as usize].expect("référence NODE résolue");
    let header = &definition.header;

    // ADR-151 D2 : les corps déclarés, chacun à son node racine — la racine de l'asset pour le
    // corps racine qui n'en nomme pas —, puis un corps par `b_node` qui n'est la racine d'aucun.
    // Sans `bodies`, un seul corps à la racine de l'asset ; `DEFN` n'a alors aucun joint.
    if definition.bodies.is_empty() {
        let (colliders, children) = mark(definition, asset, &resolved);
        let entry = AssemblyBodyEntry {
            desc: header.physics,
            root_node: 0,
        };
        return finish(
            definition,
            asset,
            vec![entry],
            &[None],
            &[],
            0.0,
            colliders,
            children,
        );
    }
    let mut roots: Vec<u32> = Vec::with_capacity(definition.bodies.len());
    // La référence qui nomme le node racine de chaque corps, pour désigner un refus.
    let mut origins: Vec<Option<u32>> = Vec::with_capacity(definition.bodies.len());
    for (rank, body) in definition.bodies.iter().enumerate() {
        let node = if body.root_node == NONE_U32 {
            0
        } else {
            node_of(body.root_node)
        };
        if roots.contains(&node) {
            return Err(Refusal::invalid(
                (body.root_node != NONE_U32).then_some(body.root_node),
                format!("le corps {rank} a le node racine d'un corps déjà déclaré"),
            ));
        }
        roots.push(node);
        origins.push((body.root_node != NONE_U32).then_some(body.root_node));
    }
    let mut joints: Vec<JointDesc> = Vec::with_capacity(definition.joints.len());
    for joint in &definition.joints {
        let b_node = node_of(joint.b_node);
        let body_b = match roots.iter().position(|root| *root == b_node) {
            Some(rank) => rank,
            None => {
                roots.push(b_node);
                origins.push(Some(joint.b_node));
                roots.len() - 1
            }
        };
        if body_b == usize::from(joint.body_a) {
            return Err(Refusal::invalid(
                Some(joint.b_node),
                "les deux bouts du joint tombent sur le même corps",
            ));
        }
        joints.push(JointDesc {
            b_node,
            body_b: u16::try_from(body_b).unwrap_or(NONE_U16),
            ..*joint
        });
    }
    if roots.len() > MAX_ASSEMBLY_BODIES {
        return Err(Refusal {
            code: E_ASSEMBLY_LIMIT,
            reference: None,
            message: format!(
                "{} corps avec ceux que créent les joints, au plus {MAX_ASSEMBLY_BODIES} (R-190)",
                roots.len()
            ),
        });
    }

    // Un seul corps porte la description entière de l'assembly ; plusieurs se la partagent.
    let (entries, assembly_mass) = if roots.len() == 1 {
        let entry = AssemblyBodyEntry {
            desc: header.physics,
            root_node: roots[0],
        };
        (vec![entry], 0.0)
    } else {
        let entries = roots
            .iter()
            .enumerate()
            .map(|(rank, root)| AssemblyBodyEntry {
                desc: definition
                    .bodies
                    .get(rank)
                    .map_or(header.implicit, |body| body.desc),
                root_node: *root,
            })
            .collect();
        (entries, header.assembly_mass)
    };

    let (colliders, children) = mark(definition, asset, &resolved);
    finish(
        definition,
        asset,
        entries,
        &origins,
        &joints,
        assembly_mass,
        colliders,
        children,
    )
}

/// Range les colliders marqués en corps, pose les joints et juge l'animation serveur. `origins`
/// donne, pour chaque corps, la référence qui nomme son node racine.
#[allow(clippy::too_many_arguments)]
fn finish(
    definition: &Definition,
    asset: &AssetView<'_>,
    entries: Vec<AssemblyBodyEntry>,
    origins: &[Option<u32>],
    joints: &[JointDesc],
    assembly_mass: f32,
    colliders: Vec<ColliderDesc>,
    children: Vec<ColliderDesc>,
) -> Result<CompiledDefinition, Refusal> {
    let header = &definition.header;
    let prepared = prepare(
        &entries,
        joints,
        &definition.jam_points,
        asset.nodes,
        &colliders,
        asset.points,
        &children,
        asset.anim,
    )
    .map_err(|message| Refusal::invalid(None, message))?;

    let mut warnings = Vec::new();
    let animation = match prepared.animation {
        AnimationVerdict::Played(animation) => Some(*animation),
        AnimationVerdict::Still => None,
        AnimationVerdict::RootAnimated(rank) => {
            warnings.push(format!(
                "la racine du corps {rank} est animée : la physique l'emporte, le serveur ne joue \
                 pas l'animation (R-930)"
            ));
            None
        }
        AnimationVerdict::UnderAnimated(rank) => {
            return Err(Refusal {
                code: E_PHYSICS_UNDER_ANIMATED,
                reference: origins.get(rank).copied().flatten(),
                message: format!("la racine du corps {rank} est sous un node animé (R-931)"),
            });
        }
    };

    Ok(CompiledDefinition {
        body_kind: header.physics.body_type,
        self_collision: header.flags & definition_flags::SELF_COLLISION != 0,
        assembly_mass,
        roots: entries.iter().map(|entry| entry.root_node).collect(),
        bodies: prepared.bodies,
        joints: prepared.joints,
        groups: prepared.groups,
        animation,
        colliders,
        children,
        warnings,
    })
}

/// Résout chaque référence contre la table de sa catégorie : le node d'un `NODE`, le premier de ce
/// nom comme Java l'a toujours pris ; rien à garder d'un clip ou d'un os, dont seule l'existence
/// se juge.
fn resolve(refs: &[DefinitionRef], asset: &AssetView<'_>) -> Result<Vec<Option<u32>>, Refusal> {
    let clips: Vec<u64> = asset
        .anim
        .and_then(|bytes| decode_animations(bytes).ok())
        .map(|table| table.animations.iter().map(|clip| clip.name_hash).collect())
        .unwrap_or_default();
    let mut resolved = Vec::with_capacity(refs.len());
    for (rank, reference) in refs.iter().enumerate() {
        let rank = u32::try_from(rank).unwrap_or(u32::MAX);
        let hash = reference.name_hash;
        let found = match reference.category {
            reference_category::NODE => asset
                .nodes
                .iter()
                .position(|node| node.name_hash == hash)
                .map(|index| Some(index as u32)),
            reference_category::CLIP => clips.contains(&hash).then_some(None),
            _ => asset
                .bones
                .iter()
                .any(|bone| bone.name_hash == hash)
                .then_some(None),
        };
        let Some(node) = found else {
            let table = match reference.category {
                reference_category::NODE => "node",
                reference_category::CLIP => "clip d'animation",
                _ => "os",
            };
            return Err(Refusal::invalid(
                Some(rank),
                format!("aucun {table} de ce nom dans l'asset"),
            ));
        };
        resolved.push(node);
    }
    Ok(resolved)
}

/// Les colliders de l'asset et les enfants de leurs composés, marqués : chacun sous les groupes
/// de l'assembly (R-980) et au matériau de sa part la plus proche, l'acier générique sans
/// (ADR-149 D2) ; `REFITTABLE` sous une part déformable d'une assembly dynamique, sauf `NO_REFIT`
/// (R-623).
///
/// Une part se définit par son `root_node` : ses colliders sont ceux que porte ce node ou l'un de
/// ses descendants. La part se reconnaît au nom du node, comme Java le faisait : deux nodes du même
/// nom portent la même part. Un collider qu'aucun node ne porte — celui que la definition génère
/// pour l'asset entier — n'appartient à aucune part.
fn mark(
    definition: &Definition,
    asset: &AssetView<'_>,
    resolved: &[Option<u32>],
) -> (Vec<ColliderDesc>, Vec<ColliderDesc>) {
    let header = &definition.header;
    let mut colliders = asset.colliders.to_vec();
    let mut children = asset.children.to_vec();
    for collider in colliders.iter_mut().chain(children.iter_mut()) {
        collider.group = header.group;
        collider.mask = header.mask;
        collider.material = GENERIC_STEEL;
    }

    let root_hash = |part: u32| {
        let node = resolved[part as usize].expect("référence NODE résolue");
        asset.nodes[node as usize].name_hash
    };
    // Une part déclarée plus loin l'emporte sur une précédente de même racine.
    let mut materials: Vec<(u64, u16)> = Vec::new();
    let mut deformable: Vec<u64> = Vec::new();
    for part in &definition.parts {
        let hash = root_hash(part.root);
        if part.material != NONE_U16 {
            materials.retain(|(root, _)| *root != hash);
            materials.push((hash, part.material));
        }
        if part.flags & part_flags::DEFORMABLE != 0 {
            deformable.push(hash);
        }
    }
    let refit = header.physics.body_type == BODY_DYNAMIC && !deformable.is_empty();

    for (index, node) in asset.nodes.iter().enumerate() {
        let collider = node.collider;
        if collider == NONE_U32 || collider as usize >= colliders.len() {
            continue;
        }
        let chain = Chain::new(asset.nodes, index);
        let material = chain
            .clone()
            .find_map(|hash| materials.iter().find(|(root, _)| *root == hash))
            .map(|(_, material)| *material);
        let refittable = refit && chain.clone().any(|hash| deformable.contains(&hash));
        let target = &mut colliders[collider as usize];
        let compound = match target.shape {
            ColliderShape::Compound {
                children_offset,
                children_count,
            } => Some((children_offset as usize, children_count as usize)),
            _ => None,
        };
        let apply = |collider: &mut ColliderDesc| {
            if let Some(material) = material {
                collider.material = material;
            }
            if refittable && collider.flags & collider_flags::NO_REFIT == 0 {
                collider.flags |= collider_flags::REFITTABLE;
            }
        };
        apply(target);
        if let Some((first, count)) = compound {
            let end = first.saturating_add(count).min(children.len());
            for child in children.get_mut(first.min(end)..end).unwrap_or_default() {
                apply(child);
            }
        }
    }
    (colliders, children)
}

/// Les empreintes des noms d'un node et de ses ancêtres, du node vers la racine. Une chaîne plus
/// longue que la table est un cycle, que C-22 refuse : elle s'arrête là.
#[derive(Clone)]
struct Chain<'a> {
    nodes: &'a [NodeDesc],
    current: Option<usize>,
    steps: usize,
}

impl<'a> Chain<'a> {
    fn new(nodes: &'a [NodeDesc], node: usize) -> Self {
        Self {
            nodes,
            current: Some(node),
            steps: 0,
        }
    }
}

impl Iterator for Chain<'_> {
    type Item = u64;

    fn next(&mut self) -> Option<u64> {
        let index = self.current?;
        let node = self.nodes.get(index)?;
        self.steps += 1;
        self.current = if node.is_root() || self.steps >= self.nodes.len() {
            None
        } else {
            Some(node.parent as usize)
        };
        Some(node.name_hash)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ax_asset::a3d::{encode_animations, encode_colliders, encode_nodes, AnimationTable};
    use ax_model::dm::animation::{channel, interp, AnimationDesc, TrackDesc};
    use ax_model::dm::commands::{create_assembly_payload_full, CreateAssembly};
    use ax_model::dm::definition::{DefinitionHeader, DefinitionPart};
    use ax_model::dm::geometry::{Transform, WorldTransform};
    use ax_model::dm::handle::Handle;
    use ax_model::dm::joints::{joint_axis_mode, joint_kind};
    use ax_model::dm::physics::BodyDesc;
    use ax_model::dm::scene::{name_hash, NO_PARENT};

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

    /// Une base à la racine, une caisse à 2 blocs en x, une porte à 2 blocs de plus : chacune
    /// porte une boîte.
    fn chaine() -> Vec<NodeDesc> {
        vec![
            node("base", NO_PARENT, 0.0, 0),
            node("caisse", 0, 2.0, 1),
            node("porte", 1, 2.0, 2),
        ]
    }

    fn asset<'a>(nodes: &'a [NodeDesc], colliders: &'a [ColliderDesc]) -> AssetView<'a> {
        AssetView {
            nodes,
            colliders,
            points: &[],
            children: &[],
            anim: None,
            bones: &[],
        }
    }

    fn nommer(name: &str) -> DefinitionRef {
        DefinitionRef {
            name_hash: name_hash(name),
            category: reference_category::NODE,
        }
    }

    fn definition(refs: Vec<DefinitionRef>) -> Definition {
        let mut physics = BodyDesc::undeclared(2);
        physics.mass = 30.0;
        Definition {
            header: DefinitionHeader {
                flags: 0,
                group: 0b10,
                mask: 0b111,
                assembly_mass: 30.0,
                ref_count: 0,
                body_count: 0,
                joint_count: 0,
                jam_points: 0,
                part_count: 0,
                physics,
                implicit: BodyDesc::undeclared(2),
            },
            refs,
            bodies: Vec::new(),
            joints: Vec::new(),
            jam_points: Vec::new(),
            parts: Vec::new(),
        }
    }

    fn corps(root_node: u32, mass: f32) -> AssemblyBodyEntry {
        let mut desc = BodyDesc::undeclared(2);
        desc.mass = mass;
        AssemblyBodyEntry { desc, root_node }
    }

    fn charniere(b_node: u32) -> JointDesc {
        JointDesc {
            kind: joint_kind::REVOLUTE,
            flags: 0,
            body_a: 0,
            body_b: NONE_U16,
            axes: [joint_axis_mode::LOCKED; 6],
            b_node,
            anchor: [0.0; 3],
            axis: [0.0, 1.0, 0.0],
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

    fn refus(definition: &Definition, asset: &AssetView<'_>) -> Refusal {
        compile(definition, asset).expect_err("refusée")
    }

    #[test]
    fn sans_corps_declare_un_seul_corps_a_la_racine_porte_la_description_entiere() {
        let nodes = chaine();
        let colliders = [boite(), boite(), boite()];
        let compiled = compile(&definition(Vec::new()), &asset(&nodes, &colliders)).unwrap();
        assert_eq!(compiled.roots, [0]);
        assert_eq!(compiled.bodies.len(), 1);
        assert_eq!(
            compiled.bodies[0].colliders.len(),
            3,
            "toute la chaîne au corps racine"
        );
        assert!((compiled.bodies[0].desc.mass - 30.0).abs() < f32::EPSILON);
        assert!(
            compiled.assembly_mass.abs() < f32::EPSILON,
            "un seul corps : rien à répartir"
        );
    }

    #[test]
    fn un_corps_declare_prend_son_root_node_par_son_nom() {
        let nodes = chaine();
        let colliders = [boite(), boite(), boite()];
        let mut definition = definition(vec![nommer("caisse")]);
        definition.bodies = vec![corps(0, 5.0)];
        let compiled = compile(&definition, &asset(&nodes, &colliders)).unwrap();
        assert_eq!(compiled.roots, [1]);
        assert!(
            (compiled.bodies[0].desc.mass - 30.0).abs() < f32::EPSILON,
            "un seul corps porte la description de l'assembly, comme en Java"
        );
    }

    #[test]
    fn plusieurs_corps_se_partagent_la_masse_de_l_assembly() {
        let nodes = chaine();
        let colliders = [boite(), boite(), boite()];
        let mut definition = definition(vec![nommer("porte")]);
        definition.bodies = vec![corps(NONE_U32, 10.0), corps(0, 0.0)];
        let compiled = compile(&definition, &asset(&nodes, &colliders)).unwrap();
        assert_eq!(compiled.roots, [0, 2]);
        assert!((compiled.bodies[0].desc.mass - 10.0).abs() < f32::EPSILON);
        assert!((compiled.assembly_mass - 30.0).abs() < f32::EPSILON);
        assert_eq!(compiled.bodies[0].colliders.len(), 2, "base et caisse");
        assert_eq!(compiled.bodies[1].colliders.len(), 1, "la porte");
    }

    #[test]
    fn r580_un_nom_introuvable_refuse_la_definition_et_designe_sa_reference() {
        let nodes = chaine();
        let colliders = [boite(), boite(), boite()];
        let mut inconnu = definition(vec![nommer("porte"), nommer("capot")]);
        inconnu.bodies = vec![corps(1, 0.0)];
        let refusal = refus(&inconnu, &asset(&nodes, &colliders));
        assert_eq!(refusal.code, E_DEFINITION_INVALID);
        assert_eq!(refusal.reference, Some(1));

        let clip = definition(vec![DefinitionRef {
            name_hash: name_hash("ouvrir"),
            category: reference_category::CLIP,
        }]);
        let refusal = refus(&clip, &asset(&nodes, &colliders));
        assert_eq!(
            (refusal.code, refusal.reference),
            (E_DEFINITION_INVALID, Some(0))
        );
        assert!(refusal.message.contains("clip"), "{}", refusal.message);

        let os = definition(vec![DefinitionRef {
            name_hash: name_hash("porte"),
            category: reference_category::BONE,
        }]);
        let refusal = refus(&os, &asset(&nodes, &colliders));
        assert_eq!(
            (refusal.code, refusal.reference),
            (E_DEFINITION_INVALID, Some(0))
        );
        assert!(refusal.message.contains("os"), "{}", refusal.message);
    }

    #[test]
    fn un_os_et_un_clip_de_l_asset_se_resolvent() {
        let nodes = chaine();
        let colliders = [boite(), boite(), boite()];
        let anim = clips(2);
        let bones = [BoneDesc {
            name_hash: name_hash("porte"),
            parent: NONE_U16,
            _pad: 0,
            node: 2,
            inverse_bind: [0.0; 16],
        }];
        let view = AssetView {
            anim: Some(&anim),
            bones: &bones,
            ..asset(&nodes, &colliders)
        };
        let definition = definition(vec![
            DefinitionRef {
                name_hash: name_hash("tourner"),
                category: reference_category::CLIP,
            },
            DefinitionRef {
                name_hash: name_hash("porte"),
                category: reference_category::BONE,
            },
        ]);
        assert!(compile(&definition, &view).is_ok());
    }

    #[test]
    fn le_b_node_d_un_joint_cree_un_corps_et_le_joint_relie_les_deux() {
        let nodes = chaine();
        let colliders = [boite(), boite(), boite()];
        let mut definition = definition(vec![nommer("porte")]);
        definition.bodies = vec![corps(NONE_U32, 0.0)];
        definition.joints = vec![charniere(0)];
        let compiled = compile(&definition, &asset(&nodes, &colliders)).unwrap();
        assert_eq!(compiled.roots, [0, 2]);
        assert_eq!(compiled.joints.len(), 1);
        assert_eq!(
            (compiled.joints[0].body_a, compiled.joints[0].body_b),
            (0, 1)
        );
        assert!(
            compiled.bodies[1].desc.mass.abs() < f32::EPSILON,
            "le corps implicite prend la description `implicit`, sans masse"
        );
    }

    #[test]
    fn un_b_node_deja_racine_d_un_corps_declare_ne_cree_pas_de_second_corps() {
        let nodes = chaine();
        let colliders = [boite(), boite(), boite()];
        let mut definition = definition(vec![nommer("porte"), nommer("porte")]);
        definition.bodies = vec![corps(NONE_U32, 0.0), corps(0, 0.0)];
        definition.joints = vec![charniere(1)];
        let compiled = compile(&definition, &asset(&nodes, &colliders)).unwrap();
        assert_eq!(compiled.roots, [0, 2]);
        assert_eq!(compiled.joints[0].body_b, 1);
    }

    #[test]
    fn un_joint_dont_les_deux_bouts_tombent_sur_le_meme_corps_est_refuse() {
        let nodes = chaine();
        let colliders = [boite(), boite(), boite()];
        let mut definition = definition(vec![nommer("base")]);
        definition.bodies = vec![corps(NONE_U32, 0.0)];
        definition.joints = vec![charniere(0)];
        let refusal = refus(&definition, &asset(&nodes, &colliders));
        assert_eq!(
            (refusal.code, refusal.reference),
            (E_DEFINITION_INVALID, Some(0))
        );
    }

    #[test]
    fn deux_corps_declares_au_meme_node_sont_refuses() {
        let nodes = chaine();
        let colliders = [boite(), boite(), boite()];
        let mut definition = definition(vec![nommer("porte"), nommer("porte")]);
        definition.bodies = vec![corps(NONE_U32, 0.0), corps(0, 0.0), corps(1, 0.0)];
        let refusal = refus(&definition, &asset(&nodes, &colliders));
        assert_eq!(
            (refusal.code, refusal.reference),
            (E_DEFINITION_INVALID, Some(1))
        );
    }

    #[test]
    fn r190_plus_de_64_corps_avec_ceux_des_joints_refuse_e3050() {
        // Une racine et 64 nodes enfants, chacun avec sa boîte : 64 joints en font 65 corps.
        let mut nodes = vec![node("base", NO_PARENT, 0.0, 0)];
        let mut colliders = vec![boite()];
        let mut definition = definition(Vec::new());
        definition.bodies = vec![corps(NONE_U32, 0.0)];
        for index in 1..=64u32 {
            let name = format!("n{index}");
            nodes.push(node(&name, 0, index as f32, index));
            colliders.push(boite());
            definition.refs.push(nommer(&name));
            definition.joints.push(charniere(index - 1));
        }
        let refusal = refus(&definition, &asset(&nodes, &colliders));
        assert_eq!(refusal.code, E_ASSEMBLY_LIMIT);
        // À 64 corps, la même definition se compile.
        definition.joints.pop();
        assert!(compile(&definition, &asset(&nodes, &colliders)).is_ok());
    }

    #[test]
    fn un_corps_sans_collider_rend_l_assembly_informe_et_la_definition_refusee() {
        let nodes = vec![
            node("base", NO_PARENT, 0.0, 0),
            node("vide", 0, 2.0, NONE_U32),
        ];
        let colliders = [boite()];
        let mut definition = definition(vec![nommer("vide")]);
        definition.bodies = vec![corps(NONE_U32, 0.0)];
        definition.joints = vec![charniere(0)];
        let refusal = refus(&definition, &asset(&nodes, &colliders));
        assert_eq!(
            (refusal.code, refusal.reference),
            (E_DEFINITION_INVALID, None)
        );
        assert!(
            refusal.message.contains("sans collider"),
            "{}",
            refusal.message
        );
    }

    /// Base, caisse et porte ; la caisse porte un composé de deux enfants.
    fn caisse_composee() -> (Vec<NodeDesc>, Vec<ColliderDesc>, Vec<ColliderDesc>) {
        let mut compose = boite();
        compose.shape = ColliderShape::Compound {
            children_offset: 0,
            children_count: 2,
        };
        (
            chaine(),
            vec![boite(), compose, boite()],
            vec![boite(), boite()],
        )
    }

    fn pieces(definition: &mut Definition) {
        definition.refs = vec![nommer("caisse"), nommer("porte")];
        definition.parts = vec![
            DefinitionPart {
                root: 0,
                material: 3,
                flags: 0,
            },
            DefinitionPart {
                root: 1,
                material: 5,
                flags: part_flags::DEFORMABLE,
            },
        ];
    }

    #[test]
    fn r980_chaque_collider_enfants_compris_porte_les_groupes_de_l_assembly() {
        let (nodes, colliders, children) = caisse_composee();
        let view = AssetView {
            children: &children,
            ..asset(&nodes, &colliders)
        };
        let compiled = compile(&definition(Vec::new()), &view).unwrap();
        for collider in compiled.colliders.iter().chain(&compiled.children) {
            assert_eq!((collider.group, collider.mask), (0b10, 0b111));
        }
        assert_eq!(compiled.groups, CollisionGroups::from_masks(0b10, 0b111));
    }

    #[test]
    fn adr149_d2_chaque_collider_prend_le_materiau_de_sa_part_la_plus_proche() {
        let (nodes, colliders, children) = caisse_composee();
        let view = AssetView {
            children: &children,
            ..asset(&nodes, &colliders)
        };
        let mut definition = definition(Vec::new());
        pieces(&mut definition);
        let compiled = compile(&definition, &view).unwrap();
        let materials: Vec<u16> = compiled.colliders.iter().map(|c| c.material).collect();
        assert_eq!(
            materials,
            [GENERIC_STEEL, 3, 5],
            "base sans part, caisse, porte"
        );
        assert!(
            compiled.children.iter().all(|child| child.material == 3),
            "le composé"
        );

        // Une part déclarée plus loin, sur la même racine, l'emporte.
        definition.parts.push(DefinitionPart {
            root: 0,
            material: 7,
            flags: 0,
        });
        let compiled = compile(&definition, &view).unwrap();
        assert_eq!(compiled.colliders[1].material, 7);
    }

    #[test]
    fn r623_refittable_sous_une_part_deformable_d_un_corps_dynamique_no_refit_l_emporte() {
        let (nodes, mut colliders, children) = caisse_composee();
        let mut definition = definition(Vec::new());
        pieces(&mut definition);
        let marked = |colliders: &[ColliderDesc]| {
            let view = AssetView {
                children: &children,
                ..asset(&nodes, colliders)
            };
            compile(&definition, &view)
                .unwrap()
                .colliders
                .iter()
                .map(|c| c.flags & collider_flags::REFITTABLE != 0)
                .collect::<Vec<_>>()
        };
        assert_eq!(
            marked(&colliders),
            [false, false, true],
            "seule la porte est déformable"
        );
        colliders[2].flags = collider_flags::NO_REFIT;
        assert_eq!(
            marked(&colliders),
            [false, false, false],
            "NO_REFIT l'emporte"
        );
    }

    #[test]
    fn r623_rien_n_est_refittable_sur_une_assembly_qui_n_est_pas_dynamique() {
        let (nodes, colliders, children) = caisse_composee();
        let view = AssetView {
            children: &children,
            ..asset(&nodes, &colliders)
        };
        let mut definition = definition(Vec::new());
        pieces(&mut definition);
        definition.header.physics = BodyDesc::undeclared(1);
        let compiled = compile(&definition, &view).unwrap();
        assert!(compiled
            .colliders
            .iter()
            .chain(&compiled.children)
            .all(|c| c.flags & collider_flags::REFITTABLE == 0));
    }

    /// Une section `ANIM` d'un clip, `tourner`, qui tourne le node `target`.
    fn clips(target: u32) -> Vec<u8> {
        encode_animations(&AnimationTable {
            animations: vec![AnimationDesc {
                name_hash: name_hash("tourner"),
                duration: 1.0,
                track_offset: 0,
                track_count: 1,
                flags: 0,
            }],
            names: vec!["tourner".to_owned()],
            tracks: vec![TrackDesc {
                target_node: target,
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
        .expect("ANIM")
    }

    #[test]
    fn r931_une_racine_de_corps_sous_un_node_anime_refuse_e7002() {
        let nodes = chaine();
        let colliders = [boite(), boite(), boite()];
        let anim = clips(1);
        let view = AssetView {
            anim: Some(&anim),
            ..asset(&nodes, &colliders)
        };
        let mut definition = definition(vec![nommer("porte")]);
        definition.bodies = vec![corps(NONE_U32, 0.0), corps(0, 0.0)];
        let refusal = refus(&definition, &view);
        assert_eq!(
            (refusal.code, refusal.reference),
            (E_PHYSICS_UNDER_ANIMATED, Some(0))
        );
    }

    #[test]
    fn r930_une_racine_de_corps_animee_n_est_qu_un_avertissement() {
        let nodes = chaine();
        let colliders = [boite(), boite(), boite()];
        let anim = clips(2);
        let view = AssetView {
            anim: Some(&anim),
            ..asset(&nodes, &colliders)
        };
        let mut definition = definition(vec![nommer("porte")]);
        definition.bodies = vec![corps(NONE_U32, 0.0), corps(0, 0.0)];
        let compiled = compile(&definition, &view).unwrap();
        assert!(compiled.animation.is_none(), "la physique l'emporte");
        assert_eq!(compiled.warnings.len(), 1);
        assert!(compiled.warnings[0].contains("R-930"));
    }

    #[test]
    fn r1430_un_collider_sous_un_node_anime_fait_jouer_le_serveur() {
        let nodes = chaine();
        let colliders = [boite(), boite(), boite()];
        let anim = clips(1);
        let view = AssetView {
            anim: Some(&anim),
            ..asset(&nodes, &colliders)
        };
        let compiled = compile(&definition(Vec::new()), &view).unwrap();
        assert!(compiled.animation.is_some());
        assert!(compiled.warnings.is_empty());
    }

    #[test]
    fn la_compilation_forme_l_assembly_que_create_assembly_formait() {
        // Même definition, deux chemins : DEFN compilé, et le payload que Java envoyait, ses
        // colliders marqués comme la compilation les marque.
        // Un volet, enfant de la base, s'anime hors de la chaîne de la porte, racine d'un corps.
        let (mut nodes, mut colliders, children) = caisse_composee();
        nodes.push(node("volet", 0, -2.0, 3));
        colliders.push(boite());
        let anim = clips(3);
        let view = AssetView {
            children: &children,
            anim: Some(&anim),
            ..asset(&nodes, &colliders)
        };
        let mut definition = definition(Vec::new());
        pieces(&mut definition);
        definition.refs.push(nommer("porte"));
        definition.bodies = vec![corps(NONE_U32, 4.0)];
        definition.joints = vec![charniere(2)];
        definition.header.flags = definition_flags::SELF_COLLISION;
        let compiled = compile(&definition, &view).unwrap();

        let names: Vec<String> = ["base", "caisse", "porte", "volet"]
            .map(str::to_owned)
            .to_vec();
        let header = CreateAssembly {
            handle: Handle {
                index: 1,
                generation: 1,
            },
            dimension: 0,
            spawn: WorldTransform {
                position: [0.0; 3],
                rotation: [0.0, 0.0, 0.0, 1.0],
            },
            body_kind: compiled.body_kind,
            flags: 0,
            body_count: 0,
            joint_count: 0,
            jam_points: 0,
            assembly_mass: compiled.assembly_mass,
            node_bytes: 0,
        };
        let entries = [corps(0, 4.0), {
            let mut implicit = corps(2, 0.0);
            implicit.desc = definition.header.implicit;
            implicit
        }];
        let mut joint = charniere(2);
        joint.body_b = 1;
        let payload = create_assembly_payload_full(
            &header,
            &entries,
            &[joint],
            &[],
            &encode_nodes(&nodes, &names).unwrap(),
            None,
            Some(&anim),
            &encode_colliders(&compiled.colliders, &[], &compiled.children).unwrap(),
        );
        let request = crate::assembly::decode(&payload).expect("payload valide");
        assert_eq!(
            format!("{:?}", request.bodies),
            format!("{:?}", compiled.bodies)
        );
        assert_eq!(request.joints, compiled.joints);
        assert_eq!(request.groups, compiled.groups);
        assert!(compiled.animation.is_some(), "le volet porte un collider");
        assert_eq!(request.animation, compiled.animation);
    }

    #[test]
    fn r931_un_corps_implicite_sous_un_node_anime_designe_le_b_node_de_son_joint() {
        let nodes = chaine();
        let colliders = [boite(), boite(), boite()];
        let anim = clips(1);
        let view = AssetView {
            anim: Some(&anim),
            ..asset(&nodes, &colliders)
        };
        let mut definition = definition(vec![nommer("base"), nommer("porte")]);
        definition.bodies = vec![corps(NONE_U32, 0.0)];
        definition.joints = vec![charniere(1)];
        let refusal = refus(&definition, &view);
        assert_eq!(
            (refusal.code, refusal.reference),
            (E_PHYSICS_UNDER_ANIMATED, Some(1))
        );
    }
}
