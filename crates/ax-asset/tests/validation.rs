//! T-230..T-233 — la liste de contrôle de C-22, groupe par groupe.
//!
//! La fiche ne nomme que la plage ; les groupes sont répartis ainsi, d'après
//! les préfixes des tests : T-230, l'asset de référence sans violation
//! (R-540) ; T-231, STRUCTURE et LIMITES, et les erreurs nommées et situées
//! (R-541) ; T-232, GÉOMÉTRIE, UV, NORMALES et SKIN, et leurs réparations
//! (R-542) ; T-233, PHYSIQUE et NOMS. DÉFORMATION est T-800, le graphe de
//! parts T-850.
//!
//! Chaque test part d'un asset **valide** et n'y introduit qu'un seul défaut.
//! C'est ce qui permet d'affirmer que la violation constatée vient bien de là :
//! un asset fautif de partout ferait passer n'importe quelle règle pour celle
//! qui a mordu.

use ax_asset::validate::{validate, AssetView, Located, NamedEntry, ValidationReport, Violation};
use ax_model::dm::geometry::Transform;
use ax_model::dm::geometry::{MeshDesc, Vertex, NO_REGION_U8};
use ax_model::dm::integrity::{
    link_flags, region_flags, DeformRegionDesc, LinkKind, StructuralLinkDesc,
};
use ax_model::dm::material::{blend_mode, cull_mode, shading_model, MaterialDesc, NO_TEXTURE};
use ax_model::dm::physics::{ColliderDesc, ColliderShape};
use ax_model::dm::scene::{node_flags, NodeDesc, NONE_U16, NONE_U32, NO_PARENT};

/// Sommet neutre à une position donnée.
fn vertex(position: [f32; 3]) -> Vertex {
    Vertex {
        position,
        normal: [0, 127, 0, 0],
        tangent: [127, 0, 0, 127],
        uv0: [0; 2],
        uv1: [0; 2],
        color: [255; 4],
        bones: [0; 4],
        weights: [255, 0, 0, 0],
        region: NO_REGION_U8,
        def_w: 0,
        _pad: [0; 6],
    }
}

/// Node neutre.
fn node(parent: u32) -> NodeDesc {
    NodeDesc {
        name_hash: 1,
        parent,
        local: Transform::identity(),
        flags: node_flags::VISIBLE,
        mesh: NONE_U32,
        collider: NONE_U32,
        bone: NONE_U32,
        part: NONE_U16,
        region: NONE_U16,
        lod_mask: 1,
        state: 0,
        mesh_count: 0,
    }
}

/// Mesh d'un triangle, occupant tout le tableau de sommets.
fn mesh() -> MeshDesc {
    MeshDesc {
        vertex_offset: 0,
        vertex_count: 3,
        index_offset: 0,
        index_count: 3,
        material: 0,
        lod: 0,
        flags: 0,
        aabb_min: [0.0, 0.0, 0.0],
        aabb_max: [1.0, 1.0, 0.0],
        region: NONE_U16,
        uv0_range: 0,
    }
}

/// Matériau valide, opaque, une face, dont l'albedo désigne la texture 0.
fn materiau() -> MaterialDesc {
    MaterialDesc {
        name_hash: 1,
        albedo_tex: 0,
        normal_tex: NO_TEXTURE,
        orm_tex: NO_TEXTURE,
        emissive_tex: NO_TEXTURE,
        height_tex: NO_TEXTURE,
        damage_tex: NO_TEXTURE,
        albedo_factor: [1.0; 4],
        emissive_factor: [0.0; 3],
        metallic: 0.0,
        roughness: 0.6,
        occlusion_strength: 1.0,
        normal_scale: 1.0,
        alpha_cutoff: 0.5,
        parallax_scale: 0.0,
        clearcoat: 0.0,
        clearcoat_roughness: 0.0,
        sheen: 0.0,
        anisotropy: 0.0,
        blend_mode: blend_mode::OPAQUE,
        cull_mode: cull_mode::BACK,
        shading_model: shading_model::PBR,
        _pad: 0,
        flags: 0,
        wear_profile: u16::MAX,
    }
}

fn collider() -> ColliderDesc {
    ColliderDesc {
        shape: ColliderShape::Sphere { radius: 0.5 },
        local: Transform::identity(),
        material: 0,
        _pad: 0,
        group: 1,
        mask: u32::MAX,
        flags: 0,
        density: 1000.0,
        damage_zone: NONE_U16,
        part: 0,
        region: NONE_U16,
        _pad2: 0,
        hull_points_offset: 0,
        hull_points_count: 0,
    }
}

fn part(parent: u16) -> ax_model::dm::scene::PartDesc {
    ax_model::dm::scene::PartDesc {
        name_hash: 1,
        root_node: 0,
        parent_part: parent,
        _pad: 0,
        flags: 0,
        max_health: 100.0,
        structural_capacity: 5000.0,
        detach_threshold: 0.2,
        propagation: 0.3,
        mass: 250.0,
        material: 0,
        region_first: 0,
        region_count: 0,
        _pad2: 0,
        mesh_intact: NONE_U32,
        mesh_damaged: NONE_U32,
        mesh_destroyed: NONE_U32,
        debris_definition: 0,
    }
}

fn region() -> DeformRegionDesc {
    DeformRegionDesc {
        name_hash: 1,
        root_node: 0,
        part: 0,
        res: [4, 4, 4],
        flags: region_flags::SHELL,
        obb_center: [0.0; 3],
        obb_half: [1.0, 0.5, 2.0],
        obb_rot: [0.0, 0.0, 0.0, 1.0],
        thickness: 0.002,
        max_disp: 0.1,
        anchor_mask_offset: 0,
        node_count: 64,
        hull_binding_offset: 0,
        hull_binding_count: 0,
        material: 0,
        _pad: 0,
    }
}

fn link() -> StructuralLinkDesc {
    StructuralLinkDesc {
        name_hash: 1,
        part_a: 0,
        part_b: 1,
        kind: LinkKind::Weld as u8,
        flags: link_flags::LOAD_BEARING,
        _pad: 0,
        capacity: 1000.0,
        tensile: 5000.0,
        shear: 3000.0,
        torque: 200.0,
        anchor_local: [0.0; 3],
        joint: u32::MAX,
        propagation: 0.5,
    }
}

/// Le décor d'un asset valide, dont chaque test ne modifie qu'une pièce.
struct Scene {
    nodes: Vec<NodeDesc>,
    meshes: Vec<MeshDesc>,
    vertices: Vec<Vertex>,
    indices: Vec<u32>,
    colliders: Vec<ColliderDesc>,
    parts: Vec<ax_model::dm::scene::PartDesc>,
    regions: Vec<DeformRegionDesc>,
    links: Vec<StructuralLinkDesc>,
    anchors: Vec<u8>,
    names: Vec<NamedEntry<'static>>,
    bone_count: usize,
    materials: Option<Vec<MaterialDesc>>,
    texture_count: usize,
    animation_count: usize,
    dynamic_body: bool,
    missing_normals: Vec<bool>,
}

impl Scene {
    fn valide() -> Self {
        Self {
            nodes: vec![node(NO_PARENT), node(0)],
            meshes: vec![mesh()],
            vertices: vec![
                vertex([0.0, 0.0, 0.0]),
                vertex([1.0, 0.0, 0.0]),
                vertex([0.0, 1.0, 0.0]),
            ],
            indices: vec![0, 1, 2],
            colliders: vec![collider()],
            parts: vec![part(NONE_U16), part(0)],
            regions: vec![region()],
            links: vec![link()],
            // Huit nœuds ancrés sur les soixante-quatre du lattice.
            anchors: vec![0xFF, 0, 0, 0, 0, 0, 0, 0],
            names: vec![
                NamedEntry::new("node", "chassis"),
                NamedEntry::new("part", "capot"),
            ],
            bone_count: 0,
            materials: Some(vec![materiau()]),
            texture_count: 1,
            animation_count: 0,
            dynamic_body: true,
            missing_normals: Vec::new(),
        }
    }

    fn view(&self) -> AssetView<'_> {
        AssetView {
            nodes: &self.nodes,
            meshes: &self.meshes,
            vertices: &self.vertices,
            indices: &self.indices,
            colliders: &self.colliders,
            parts: &self.parts,
            regions: &self.regions,
            links: &self.links,
            anchor_masks: &self.anchors,
            bone_count: self.bone_count,
            materials: self.materials.as_deref(),
            texture_count: self.texture_count,
            animation_count: self.animation_count,
            names: &self.names,
            dynamic_body: self.dynamic_body,
            missing_normals: &self.missing_normals,
        }
    }

    fn valider(&self) -> ValidationReport {
        validate(&self.view(), &[])
    }
}

/// Vérifie qu'un rapport contient exactement une violation, celle attendue.
fn seule(report: &ValidationReport, attendue: &Violation) {
    assert_eq!(
        report.len(),
        1,
        "attendu une seule violation, obtenu : {:?}",
        report.errors
    );
    assert_eq!(&report.errors[0].violation, attendue);
}

#[test]
fn t230_un_asset_valide_ne_produit_aucune_violation() {
    let report = Scene::valide().valider();
    assert!(
        report.is_valid(),
        "asset de référence refusé : {:?}",
        report.errors
    );
    assert_eq!(report.code(), 0);
}

#[test]
fn t231_un_parent_declare_apres_son_enfant_est_refuse() {
    let mut scene = Scene::valide();
    // R-130 : la hiérarchie est en ordre topologique, parent avant enfant.
    scene.nodes[0] = node(1);

    let report = scene.valider();
    seule(&report, &Violation::ParentAfterChild { parent: 1 });
    assert_eq!(report.code(), -3021);
    assert_eq!(report.errors[0].at, Located::Node(0));
}

#[test]
fn t231_un_parent_inexistant_est_refuse() {
    let mut scene = Scene::valide();
    scene.nodes[1] = node(99);

    let report = scene.valider();
    seule(&report, &Violation::ParentOutOfRange { parent: 99 });
}

#[test]
fn t231_une_profondeur_excessive_est_refusee() {
    let mut scene = Scene::valide();
    // Trente-quatre nodes en chaîne : la profondeur dépasse 32 aux deux
    // derniers.
    scene.nodes = vec![node(NO_PARENT)];
    for index in 1..34u32 {
        scene.nodes.push(node(index - 1));
    }

    let report = scene.valider();
    assert!(!report.is_valid());
    assert!(report
        .errors
        .iter()
        .all(|error| matches!(error.violation, Violation::DepthExceeded { .. })));
    assert_eq!(report.code(), -3022);
}

#[test]
fn t231_une_echelle_non_uniforme_sur_un_node_a_collider_est_refusee() {
    let mut scene = Scene::valide();
    scene.nodes[1].collider = 0;
    scene.nodes[1].local.scale = [1.0, 2.0, 1.0];

    let report = scene.valider();
    seule(&report, &Violation::NonUniformScaleOnCollider);
    assert_eq!(report.code(), -3020);
}

#[test]
fn t231_une_echelle_non_uniforme_sans_collider_est_admise() {
    let mut scene = Scene::valide();
    // R-120 l'autorise sur un node de rendu : c'est un usage courant.
    scene.nodes[1].local.scale = [1.0, 2.0, 1.0];
    assert!(scene.valider().is_valid());
}

#[test]
fn t231_un_quaternion_non_normalise_est_refuse() {
    let mut scene = Scene::valide();
    scene.nodes[1].local.rotation = [0.0, 0.0, 0.0, 2.0];

    seule(&scene.valider(), &Violation::RotationNotNormalized);
}

#[test]
fn t232_un_indice_hors_bornes_est_refuse() {
    let mut scene = Scene::valide();
    scene.indices[2] = 7;

    let report = scene.valider();
    seule(
        &report,
        &Violation::IndexOutOfRange {
            index: 7,
            vertex_count: 3,
        },
    );
    // R-541 : localisée jusqu'à l'indice fautif.
    assert_eq!(report.errors[0].at, Located::Index(2));
}

#[test]
fn t232_un_nombre_d_indices_non_multiple_de_trois_est_refuse() {
    let mut scene = Scene::valide();
    scene.indices = vec![0, 1, 2, 0];
    scene.meshes[0].index_count = 4;

    seule(
        &scene.valider(),
        &Violation::IndexCountNotTriangles { count: 4 },
    );
}

#[test]
fn t232_une_plage_hors_du_tableau_est_refusee() {
    let mut scene = Scene::valide();
    scene.meshes[0].vertex_count = 99;

    seule(
        &scene.valider(),
        &Violation::RangeOutOfAsset { what: "sommets" },
    );
}

#[test]
fn t232_un_triangle_degenere_est_refuse() {
    let mut scene = Scene::valide();
    // Trois sommets alignés : aire nulle, normale indéterminée.
    scene.vertices[2] = vertex([2.0, 0.0, 0.0]);

    let report = scene.valider();
    assert_eq!(report.len(), 1, "{:?}", report.errors);
    assert!(matches!(
        report.errors[0].violation,
        Violation::DegenerateTriangle { .. }
    ));
    assert_eq!(
        report.errors[0].at,
        Located::Triangle {
            mesh: 0,
            triangle: 0
        }
    );
}

#[test]
fn t232_une_position_non_finie_est_refusee() {
    let mut scene = Scene::valide();
    scene.vertices[1].position[0] = f32::NAN;

    let report = scene.valider();
    // La position non finie est signalée, et le triangle qu'elle rend
    // incalculable aussi : les deux sont vrais.
    assert!(report
        .errors
        .iter()
        .any(|error| error.violation == Violation::NotFinite("la position")));
}

#[test]
fn t232_une_boite_englobante_invalide_est_refusee() {
    let mut scene = Scene::valide();
    scene.meshes[0].aabb_max = [600.0, 1.0, 0.0];

    seule(&scene.valider(), &Violation::InvalidAabb);

    let mut inversee = Scene::valide();
    inversee.meshes[0].aabb_min = [2.0, 0.0, 0.0];
    seule(&inversee.valider(), &Violation::InvalidAabb);
}

#[test]
fn t232_une_coordonnee_de_texture_hors_bornes_est_refusee() {
    let scene = Scene::valide();
    // R-142 borne les UV avant normalisation ; une fois compilés ce sont des
    // UNORM16, et la question ne se pose plus.
    let report = validate(&scene.view(), &[[0.5, 0.5], [12.0, 0.0]]);

    seule(&report, &Violation::UvOutOfRange { value: 12.0 });
    assert_eq!(report.code(), -3030);
    assert_eq!(report.errors[0].at, Located::Vertex(1));
}

#[test]
fn t232_une_normale_nulle_est_refusee() {
    let mut scene = Scene::valide();
    scene.vertices[0].normal = [0, 0, 0, 0];

    seule(&scene.valider(), &Violation::NormalNotNormalizable);
}

#[test]
fn t232_une_normale_absente_de_la_source_n_est_pas_reprochee() {
    // Entre C-21 et C-23, une normale absente est attendue : l'optimizer la
    // génère. Seul le sommet marqué en profite.
    let mut scene = Scene::valide();
    scene.vertices[0].normal = [0; 4];
    scene.vertices[1].normal = [0; 4];
    scene.missing_normals = vec![true];

    let report = scene.valider();
    seule(&report, &Violation::NormalNotNormalizable);
    assert_eq!(report.errors[0].at, Located::Vertex(1));
}

#[test]
fn t232_des_poids_de_skin_nuls_sont_refuses_sur_un_asset_squelette() {
    let mut scene = Scene::valide();
    scene.bone_count = 4;
    scene.vertices[0].weights = [0; 4];

    seule(&scene.valider(), &Violation::WeightsNotNormalizable);
}

#[test]
fn t232_un_index_d_os_hors_du_squelette_est_refuse() {
    let mut scene = Scene::valide();
    scene.bone_count = 2;
    scene.vertices[1].bones = [0, 0, 0, 9];

    seule(
        &scene.valider(),
        &Violation::BoneOutOfRange {
            bone: 9,
            bone_count: 2,
        },
    );
}

#[test]
fn t232_un_asset_sans_squelette_ne_verifie_pas_les_poids() {
    let mut scene = Scene::valide();
    // Sans os, les champs de skin ne veulent rien dire : les contrôler
    // produirait des violations sur des données qui ne servent pas.
    scene.vertices[0].weights = [0; 4];
    scene.vertices[0].bones = [200; 4];
    assert!(scene.valider().is_valid());
}

#[test]
fn t233_une_densite_nulle_est_refusee() {
    let mut scene = Scene::valide();
    scene.colliders[0].density = 0.0;

    seule(
        &scene.valider(),
        &Violation::InvalidDensity { density: 0.0 },
    );
}

#[test]
fn t233_une_forme_sans_volume_est_refusee_sur_un_body_dynamique() {
    let mut scene = Scene::valide();
    scene.colliders[0].shape = ColliderShape::TriMesh {
        vertices_offset: 0,
        vertices_count: 3,
        indices_offset: 0,
        indices_count: 3,
    };

    seule(
        &scene.valider(),
        &Violation::ShapeForbiddenOnDynamicBody { shape: "TriMesh" },
    );

    // R-160 ne l'interdit que là : sur un body statique, c'est la forme
    // normale de la géométrie du monde.
    scene.dynamic_body = false;
    assert!(scene.valider().is_valid());
}

#[test]
fn t233_une_enveloppe_convexe_hors_bornes_est_refusee() {
    let mut scene = Scene::valide();
    scene.colliders[0].shape = ColliderShape::ConvexHull {
        points_offset: 0,
        points_count: 3,
    };
    let report = scene.valider();
    seule(&report, &Violation::ConvexPointCount { count: 3 });
    assert_eq!(report.code(), -3040);

    scene.colliders[0].shape = ColliderShape::ConvexHull {
        points_offset: 0,
        points_count: 300,
    };
    seule(
        &scene.valider(),
        &Violation::ConvexPointCount { count: 300 },
    );

    scene.colliders[0].shape = ColliderShape::ConvexHull {
        points_offset: 0,
        points_count: 4,
    };
    assert!(scene.valider().is_valid());
}

#[test]
fn t233_une_masse_hors_bornes_est_refusee() {
    let mut scene = Scene::valide();
    scene.parts[0].mass = 0.0;
    seule(&scene.valider(), &Violation::MassOutOfRange { mass: 0.0 });

    scene.parts[0].mass = 2e6;
    seule(&scene.valider(), &Violation::MassOutOfRange { mass: 2e6 });
}

#[test]
fn t233_une_fraction_hors_de_zero_un_est_refusee() {
    let mut scene = Scene::valide();
    scene.parts[0].detach_threshold = 1.5;

    seule(
        &scene.valider(),
        &Violation::FractionOutOfRange {
            field: "detach_threshold",
            value: 1.5,
        },
    );
}

#[test]
fn t800_une_resolution_de_lattice_hors_bornes_est_refusee() {
    let mut scene = Scene::valide();
    scene.regions[0].res = [1, 4, 4];
    scene.regions[0].node_count = 16;

    seule(
        &scene.valider(),
        &Violation::LatticeResolution { axis: 0, value: 1 },
    );

    scene.regions[0].res = [17, 4, 4];
    scene.regions[0].node_count = 272;
    seule(
        &scene.valider(),
        &Violation::LatticeResolution { axis: 0, value: 17 },
    );
}

#[test]
fn t800_un_nombre_de_noeuds_incoherent_est_refuse() {
    let mut scene = Scene::valide();
    scene.regions[0].node_count = 63;

    seule(
        &scene.valider(),
        &Violation::NodeCountMismatch {
            declared: 63,
            expected: 64,
        },
    );
}

#[test]
fn t800_une_obb_degeneree_est_refusee() {
    let mut scene = Scene::valide();
    scene.regions[0].obb_half = [1.0, 0.0, 2.0];

    let report = scene.valider();
    // Une OBB sans volume rend aussi le plafond de déplacement absurde : les
    // deux violations sont vraies, et les taire serait mentir.
    assert!(report
        .errors
        .iter()
        .any(|error| error.violation == Violation::DegenerateObb));
}

#[test]
fn t800_une_epaisseur_nulle_est_refusee() {
    let mut scene = Scene::valide();
    scene.regions[0].thickness = 0.0;

    seule(
        &scene.valider(),
        &Violation::InvalidThickness { thickness: 0.0 },
    );
}

#[test]
fn t800_un_deplacement_maximal_excessif_est_refuse() {
    let mut scene = Scene::valide();
    // La plus petite demi-dimension vaut 0.5, le plafond 0.25.
    scene.regions[0].max_disp = 0.3;

    let report = scene.valider();
    assert_eq!(report.len(), 1, "{:?}", report.errors);
    assert!(matches!(
        report.errors[0].violation,
        Violation::MaxDisplacementOutOfRange { .. }
    ));

    scene.regions[0].max_disp = 0.25;
    assert!(scene.valider().is_valid(), "le plafond lui-même est admis");

    scene.regions[0].max_disp = 0.0;
    assert!(!scene.valider().is_valid(), "un déplacement nul est admis");
}

#[test]
fn t800_une_region_ancree_sans_aucun_ancrage_est_refusee() {
    let mut scene = Scene::valide();
    scene.regions[0].flags |= region_flags::ANCHORED_BORDER;
    scene.anchors = vec![0; 8];

    seule(&scene.valider(), &Violation::RegionWithoutAnchor);
}

#[test]
fn t800_un_bitset_d_ancrage_hors_bornes_ne_lit_pas_a_cote() {
    let mut scene = Scene::valide();
    scene.regions[0].flags |= region_flags::ANCHORED_BORDER;
    // Le bitset annoncé dépasse ce que porte l'asset.
    scene.anchors = vec![0xFF; 2];

    seule(&scene.valider(), &Violation::RegionWithoutAnchor);
}

#[test]
fn t800_un_node_deformable_sans_region_est_refuse() {
    let mut scene = Scene::valide();
    scene.nodes[1].flags |= node_flags::DEFORMABLE;

    seule(&scene.valider(), &Violation::DeformableWithoutRegion);

    scene.nodes[1].region = 0;
    assert!(scene.valider().is_valid());

    scene.nodes[1].region = 9;
    seule(&scene.valider(), &Violation::DeformableWithoutRegion);
}

#[test]
fn t850_une_part_orpheline_est_refusee() {
    let mut scene = Scene::valide();
    scene.parts[1].parent_part = 9;

    let report = scene.valider();
    assert!(report
        .errors
        .iter()
        .any(|error| error.violation == Violation::OrphanPart { parent: 9 }));
}

#[test]
fn t850_un_cycle_dans_le_graphe_de_parts_est_refuse() {
    let mut scene = Scene::valide();
    // Deux parts qui se réclament l'une de l'autre : aucune n'est racine.
    scene.parts[0].parent_part = 1;
    scene.parts[1].parent_part = 0;

    let report = scene.valider();
    let cycles = report
        .errors
        .iter()
        .filter(|error| error.violation == Violation::PartCycle)
        .count();
    assert_eq!(cycles, 2, "{:?}", report.errors);
}

#[test]
fn t850_une_liaison_vers_une_part_inexistante_est_refusee() {
    let mut scene = Scene::valide();
    scene.links[0].part_b = 9;

    seule(&scene.valider(), &Violation::LinkPartOutOfRange { part: 9 });
}

#[test]
fn t850_une_liaison_d_une_part_vers_elle_meme_est_refusee() {
    let mut scene = Scene::valide();
    scene.links[0].part_b = 0;

    seule(&scene.valider(), &Violation::LinkToItself);
}

#[test]
fn t850_une_capacite_nulle_est_refusee() {
    let mut scene = Scene::valide();
    scene.links[0].shear = 0.0;

    seule(&scene.valider(), &Violation::InvalidCapacity);
}

#[test]
fn t850_une_nature_de_liaison_inconnue_est_refusee() {
    let mut scene = Scene::valide();
    scene.links[0].kind = 42;

    seule(&scene.valider(), &Violation::UnknownLinkKind { raw: 42 });
}

#[test]
fn t233_un_nom_invalide_est_refuse() {
    let mut scene = Scene::valide();
    scene.names = vec![NamedEntry::new("node", "")];
    seule(&scene.valider(), &Violation::InvalidName { reason: "vide" });

    scene.names = vec![NamedEntry::new("node", "a\u{1}b")];
    seule(
        &scene.valider(),
        &Violation::InvalidName {
            reason: "caractère non imprimable",
        },
    );

    let long: &'static str = Box::leak("x".repeat(65).into_boxed_str());
    scene.names = vec![NamedEntry::new("node", long)];
    seule(
        &scene.valider(),
        &Violation::InvalidName {
            reason: "plus de 64 octets",
        },
    );
}

#[test]
fn t233_deux_noms_identiques_dans_une_categorie_sont_refuses() {
    let mut scene = Scene::valide();
    scene.names = vec![
        NamedEntry::new("part", "capot"),
        NamedEntry::new("part", "capot"),
    ];

    seule(
        &scene.valider(),
        &Violation::DuplicateName { category: "part" },
    );
}

#[test]
fn t233_un_meme_nom_dans_deux_categories_est_admis() {
    let mut scene = Scene::valide();
    // Un node et une part peuvent s'appeler pareil : l'unicité est par
    // catégorie.
    scene.names = vec![
        NamedEntry::new("node", "capot"),
        NamedEntry::new("part", "capot"),
    ];
    assert!(scene.valider().is_valid());
}

#[test]
fn t231_la_validation_ne_s_arrete_pas_a_la_premiere_violation() {
    let mut scene = Scene::valide();
    scene.nodes[1].local.rotation = [0.0, 0.0, 0.0, 3.0];
    scene.colliders[0].density = -1.0;
    scene.links[0].part_b = 0;

    let report = scene.valider();
    // Un auteur qui corrige son modèle veut la liste, pas un défaut à la fois.
    assert_eq!(report.len(), 3, "{:?}", report.errors);
}

#[test]
fn t231_un_asset_vide_est_valide() {
    // Rien à valider n'est pas une faute : un asset peut ne porter que des
    // métadonnées.
    let asset = AssetView::default();
    assert!(validate(&asset, &[]).is_valid());
}

#[test]
fn t270_un_materiau_que_dm05_refuse_est_nomme() {
    let mut scene = Scene::valide();
    if let Some(materials) = scene.materials.as_mut() {
        materials[0].roughness = f32::NAN;
    }

    let report = scene.valider();
    seule(
        &report,
        &Violation::InvalidMaterial {
            reason: "facteur de matériau non fini",
        },
    );
    assert_eq!(report.errors[0].at, Located::Material(0));
    assert_eq!(report.code(), -3050);
}

#[test]
fn t270_un_slot_hors_de_texr_est_refuse() {
    let mut scene = Scene::valide();
    scene.texture_count = 0;

    let report = scene.valider();
    seule(
        &report,
        &Violation::TextureOutOfRange {
            slot: "albedo",
            texture: 0,
            texture_count: 0,
        },
    );
    assert_eq!(report.errors[0].at, Located::Material(0));
}

#[test]
fn t270_un_mesh_vers_un_materiau_absent_est_refuse() {
    let mut scene = Scene::valide();
    scene.meshes[0].material = 1;

    let report = scene.valider();
    seule(
        &report,
        &Violation::MaterialOutOfRange {
            material: 1,
            material_count: 1,
        },
    );
    assert_eq!(report.errors[0].at, Located::Mesh(0));
}

#[test]
fn t270_les_drapeaux_d_un_mesh_suivent_son_materiau() {
    use ax_model::dm::geometry::mesh_flags;

    // Une vitre double face : le mesh doit porter les deux drapeaux.
    let mut scene = Scene::valide();
    if let Some(materials) = scene.materials.as_mut() {
        materials[0].blend_mode = blend_mode::TRANSLUCENT;
        materials[0].cull_mode = cull_mode::NONE;
    }
    let report = scene.valider();
    seule(
        &report,
        &Violation::MeshFlagsDisagreeWithMaterial {
            mesh_flags: 0,
            material_flags: mesh_flags::TRANSPARENT | mesh_flags::DOUBLE_SIDED,
        },
    );
    assert_eq!(report.errors[0].at, Located::Mesh(0));

    scene.meshes[0].flags = mesh_flags::TRANSPARENT | mesh_flags::DOUBLE_SIDED;
    assert!(scene.valider().is_valid());

    // Les autres drapeaux ne regardent pas le matériau.
    scene.meshes[0].flags |= mesh_flags::DEFORMABLE;
    assert!(scene.valider().is_valid());
}

#[test]
fn t270_plus_de_256_materiaux_sont_refuses() {
    let mut scene = Scene::valide();
    scene.materials = Some(vec![materiau(); 257]);

    let report = scene.valider();
    seule(
        &report,
        &Violation::LimitExceeded {
            what: "matériaux",
            count: 257,
            limit: 256,
        },
    );
}

#[test]
fn t291_un_node_porte_des_meshes_qui_existent() {
    // ADR-122 §5 : un node porte `mesh .. mesh + mesh_count`.
    let mut scene = Scene::valide();
    let mut second = mesh();
    second.vertex_offset = 0;
    scene.meshes.push(second);
    scene.nodes[1].mesh = 0;
    scene.nodes[1].mesh_count = 2;
    assert!(
        scene.valider().is_valid(),
        "deux meshes portés, deux présents"
    );

    scene.nodes[1].mesh = 1;
    let report = scene.valider();
    seule(
        &report,
        &Violation::MeshRangeOutOfAsset {
            mesh: 1,
            count: 2,
            mesh_total: 2,
        },
    );
    assert_eq!(report.errors[0].at, Located::Node(1));
    assert_eq!(report.code(), -3050);
}

#[test]
fn t291_une_plage_de_meshes_qui_deborde_d_un_u32_est_refusee() {
    // La somme de deux `u32` déborde : comptée de travers, elle reviendrait
    // dans les bornes.
    let mut scene = Scene::valide();
    scene.nodes[1].mesh = u32::MAX - 1;
    scene.nodes[1].mesh_count = 5;
    seule(
        &scene.valider(),
        &Violation::MeshRangeOutOfAsset {
            mesh: u32::MAX - 1,
            count: 5,
            mesh_total: 1,
        },
    );
}

#[test]
fn t291_un_node_sans_mesh_n_en_compte_aucun() {
    let mut scene = Scene::valide();
    scene.nodes[0].mesh_count = 3;
    let report = scene.valider();
    seule(&report, &Violation::MeshCountWithoutMesh { count: 3 });
    assert_eq!(report.errors[0].at, Located::Node(0));
}

#[test]
fn t230_une_plage_d_uv_invalide_est_refusee() {
    let mut scene = Scene::valide();
    scene.meshes[0].uv0_range = 0x0005;
    let report = scene.valider();
    seule(&report, &Violation::InvalidUvRange { bits: 0x0005 });
    assert_eq!(report.errors[0].at, Located::Mesh(0));
}

#[test]
fn t230_des_sommets_partages_sous_deux_plages_sont_refuses() {
    // Le second mesh relit les sommets du premier, comme un LOD — mais sous
    // une autre plage d'UV.
    let mut scene = Scene::valide();
    let mut second = mesh();
    second.uv0_range = ax_model::dm::geometry::UvRange::new(0, 2)
        .expect("[0, 2]")
        .to_bits();
    scene.meshes.push(second);
    let report = scene.valider();
    seule(&report, &Violation::UvRangeConflict { other: 1 });
    assert_eq!(report.errors[0].at, Located::Mesh(0));

    // Même plage : un LOD ordinaire.
    scene.meshes[1].uv0_range = 0;
    assert!(scene.valider().is_valid());
}

#[test]
fn t893_sans_table_de_materiaux_les_index_ne_sont_pas_controles() {
    // Un asset antérieur à ADR-122, ou chargé sans `MATL` : le matériau par
    // défaut s'applique, et ses index ne désignent rien.
    let mut scene = Scene::valide();
    scene.materials = None;
    scene.meshes[0].material = 7;
    scene.meshes[0].flags = ax_model::dm::geometry::mesh_flags::TRANSPARENT;
    assert!(scene.valider().is_valid());

    // Une table vide, elle, fait tout contrôler.
    scene.materials = Some(Vec::new());
    seule(
        &scene.valider(),
        &Violation::MaterialOutOfRange {
            material: 7,
            material_count: 0,
        },
    );
}

/// Vérifie qu'un rapport signale ce dépassement de plafond — parmi d'autres
/// violations, peut-être : un tableau gonflé au-delà de sa limite en entraîne
/// souvent d'autres, que leurs propres tests couvrent.
fn depasse(report: &ValidationReport, what: &'static str, count: usize, limit: usize) {
    let attendue = Violation::LimitExceeded { what, count, limit };
    assert!(
        report
            .errors
            .iter()
            .any(|erreur| erreur.violation == attendue),
        "{what} : dépassement {count} > {limit} non signalé, obtenu : {:?}",
        report.errors
    );
}

#[test]
fn t231_les_compteurs_sont_admis_au_plafond_et_refuses_au_dela() {
    use ax_model::dm::limits::{MAX_ANIMATIONS, MAX_BONES, MAX_TEXTURES};

    type Reglage = fn(&mut Scene, usize);
    let cas: [(&str, usize, Reglage); 3] = [
        ("os", MAX_BONES, |scene, n| scene.bone_count = n),
        ("textures", MAX_TEXTURES, |scene, n| scene.texture_count = n),
        ("animations", MAX_ANIMATIONS, |scene, n| {
            scene.animation_count = n
        }),
    ];
    for (what, limit, regle) in cas {
        let mut scene = Scene::valide();
        regle(&mut scene, limit);
        let report = scene.valider();
        assert!(
            report.is_valid(),
            "{what} au plafond refusés : {:?}",
            report.errors
        );

        regle(&mut scene, limit + 1);
        seule(
            &scene.valider(),
            &Violation::LimitExceeded {
                what,
                count: limit + 1,
                limit,
            },
        );
    }
}

#[test]
fn t231_les_tableaux_au_dela_de_leur_plafond_sont_refuses() {
    use ax_model::dm::limits::{
        MAX_COLLIDERS, MAX_NODES, MAX_PARTS, MAX_REGIONS, MAX_STRUCTURAL_LINKS,
    };

    let mut scene = Scene::valide();
    scene.nodes = vec![node(NO_PARENT); MAX_NODES + 1];
    depasse(&scene.valider(), "nodes", MAX_NODES + 1, MAX_NODES);

    let mut scene = Scene::valide();
    scene.parts = vec![part(NONE_U16); MAX_PARTS + 1];
    depasse(&scene.valider(), "parts", MAX_PARTS + 1, MAX_PARTS);

    let mut scene = Scene::valide();
    scene.colliders = vec![collider(); MAX_COLLIDERS + 1];
    depasse(
        &scene.valider(),
        "colliders",
        MAX_COLLIDERS + 1,
        MAX_COLLIDERS,
    );

    let mut scene = Scene::valide();
    scene.regions = vec![region(); MAX_REGIONS + 1];
    depasse(&scene.valider(), "régions", MAX_REGIONS + 1, MAX_REGIONS);

    let mut scene = Scene::valide();
    scene.links = vec![link(); MAX_STRUCTURAL_LINKS + 1];
    depasse(
        &scene.valider(),
        "liaisons structurelles",
        MAX_STRUCTURAL_LINKS + 1,
        MAX_STRUCTURAL_LINKS,
    );
}

#[test]
fn t231_la_geometrie_au_dela_de_son_plafond_est_refusee() {
    use ax_model::dm::limits::{MAX_INDICES, MAX_VERTICES};

    // Deux millions de sommets, six millions d'indices : la limite se juge sur
    // des tableaux réels, comme dans un asset importé.
    let mut scene = Scene::valide();
    scene.vertices = vec![vertex([0.0, 0.0, 0.0]); MAX_VERTICES + 1];
    depasse(&scene.valider(), "sommets", MAX_VERTICES + 1, MAX_VERTICES);

    let mut scene = Scene::valide();
    scene.indices = vec![0; MAX_INDICES + 1];
    depasse(&scene.valider(), "indices", MAX_INDICES + 1, MAX_INDICES);
}
