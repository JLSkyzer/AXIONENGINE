//! La liste de contrôle de la fiche 5.15, groupe par groupe.

use super::error::{Located, Violation};
use super::model::AssetView;
use super::ValidationReport;
use ax_model::dm::geometry::MeshDesc;
use ax_model::dm::limits;
use ax_model::dm::physics::{ColliderShape, CONVEX_MAX_POINTS, CONVEX_MIN_POINTS};
use ax_model::dm::scene::{node_flags, NONE_U16, NONE_U32};

/// Valide un asset et rend toutes ses violations (C-22).
///
/// `raw_uvs`, quand il est fourni, porte les coordonnées de texture **avant**
/// normalisation, celles que R-142 borne à `[-8, 9]`. Une fois l'asset compilé,
/// les UV sont des `UNORM16` : la question ne se pose plus, et la tranche est
/// alors vide.
#[must_use]
pub fn validate(asset: &AssetView<'_>, raw_uvs: &[[f32; 2]]) -> ValidationReport {
    let mut report = ValidationReport::default();

    check_limits(asset, &mut report);
    check_hierarchy(asset, &mut report);
    check_geometry(asset, &mut report);
    check_raw_uvs(raw_uvs, &mut report);
    check_vertices(asset, &mut report);
    check_physics(asset, &mut report);
    check_deformation(asset, &mut report);
    check_structure(asset, &mut report);
    check_names(asset, &mut report);

    report
}

/// LIMITES — les plafonds par asset.
fn check_limits(asset: &AssetView<'_>, report: &mut ValidationReport) {
    let cas: [(&'static str, usize, usize); 10] = [
        ("nodes", asset.nodes.len(), limits::MAX_NODES),
        ("sommets", asset.vertices.len(), limits::MAX_VERTICES),
        ("indices", asset.indices.len(), limits::MAX_INDICES),
        ("os", asset.bone_count, limits::MAX_BONES),
        ("matériaux", asset.material_count, limits::MAX_MATERIALS),
        ("textures", asset.texture_count, limits::MAX_TEXTURES),
        ("animations", asset.animation_count, limits::MAX_ANIMATIONS),
        ("parts", asset.parts.len(), limits::MAX_PARTS),
        ("colliders", asset.colliders.len(), limits::MAX_COLLIDERS),
        ("régions", asset.regions.len(), limits::MAX_REGIONS),
    ];
    for (what, count, limit) in cas {
        if count > limit {
            report.push(
                Violation::LimitExceeded { what, count, limit },
                Located::Asset,
            );
        }
    }
    if asset.links.len() > limits::MAX_STRUCTURAL_LINKS {
        report.push(
            Violation::LimitExceeded {
                what: "liaisons structurelles",
                count: asset.links.len(),
                limit: limits::MAX_STRUCTURAL_LINKS,
            },
            Located::Asset,
        );
    }
}

/// STRUCTURE — ordre topologique, profondeur, échelle des nodes à collider.
///
/// L'ordre topologique est vérifié en exigeant `parent < index`. Cela suffit à
/// garantir l'acyclicité : un cycle demanderait qu'un node soit son propre
/// ancêtre, donc qu'un index soit inférieur à lui-même. Il n'y a donc pas de
/// détection de cycle séparée — elle serait du code inatteignable.
fn check_hierarchy(asset: &AssetView<'_>, report: &mut ValidationReport) {
    let mut depths = vec![0u32; asset.nodes.len()];

    for (index, node) in asset.nodes.iter().enumerate() {
        let at = Located::Node(index);

        if !node.local.is_finite() {
            report.push(Violation::NotFinite("la transformation locale"), at);
        } else {
            let norm = node.local.rotation_norm();
            if !(limits::MIN_QUAT_NORM..=limits::MAX_QUAT_NORM).contains(&norm) {
                report.push(Violation::RotationNotNormalized, at);
            }
        }

        // R-120 : une échelle non uniforme déforme un collider convexe en
        // quelque chose que le solveur ne sait plus décrire.
        if node.collider != NONE_U32 && !node.local.has_uniform_scale() {
            report.push(Violation::NonUniformScaleOnCollider, at);
        }

        if node.is_root() {
            depths[index] = 1;
            continue;
        }

        let parent = node.parent as usize;
        if parent >= asset.nodes.len() {
            report.push(
                Violation::ParentOutOfRange {
                    parent: node.parent,
                },
                at,
            );
            depths[index] = 1;
            continue;
        }
        if parent >= index {
            report.push(Violation::ParentAfterChild { parent }, at);
            depths[index] = 1;
            continue;
        }

        depths[index] = depths[parent] + 1;
        if depths[index] > limits::MAX_NODE_DEPTH {
            report.push(
                Violation::DepthExceeded {
                    depth: depths[index],
                },
                at,
            );
        }
    }
}

/// GÉOMÉTRIE — plages, indices, aires de triangle, boîtes englobantes.
fn check_geometry(asset: &AssetView<'_>, report: &mut ValidationReport) {
    for (index, mesh) in asset.meshes.iter().enumerate() {
        let at = Located::Mesh(index);

        if !within(mesh.vertex_offset, mesh.vertex_count, asset.vertices.len()) {
            report.push(Violation::RangeOutOfAsset { what: "sommets" }, at);
            continue;
        }
        if !within(mesh.index_offset, mesh.index_count, asset.indices.len()) {
            report.push(Violation::RangeOutOfAsset { what: "indices" }, at);
            continue;
        }
        if mesh.index_count % 3 != 0 {
            report.push(
                Violation::IndexCountNotTriangles {
                    count: mesh.index_count,
                },
                at,
            );
            continue;
        }

        check_aabb(mesh, at, report);
        check_triangles(asset, mesh, index, report);
    }
}

fn check_aabb(mesh: &MeshDesc, at: Located, report: &mut ValidationReport) {
    let finite = mesh
        .aabb_min
        .iter()
        .chain(&mesh.aabb_max)
        .all(|value| value.is_finite());
    let ordered = mesh
        .aabb_min
        .iter()
        .zip(&mesh.aabb_max)
        .all(|(low, high)| low <= high);
    let bounded = mesh
        .aabb_min
        .iter()
        .zip(&mesh.aabb_max)
        .all(|(low, high)| high - low <= limits::MAX_AABB_EXTENT);

    if !(finite && ordered && bounded) {
        report.push(Violation::InvalidAabb, at);
    }
}

fn check_triangles(
    asset: &AssetView<'_>,
    mesh: &MeshDesc,
    mesh_index: usize,
    report: &mut ValidationReport,
) {
    let first = mesh.index_offset as usize;
    let count = mesh.index_count as usize;

    for (rank, triangle) in asset.indices[first..first + count]
        .chunks_exact(3)
        .enumerate()
    {
        let at = Located::Triangle {
            mesh: mesh_index,
            triangle: rank,
        };

        let mut positions = [[0.0f32; 3]; 3];
        let mut usable = true;
        for (corner, local) in triangle.iter().enumerate() {
            if *local >= mesh.vertex_count {
                report.push(
                    Violation::IndexOutOfRange {
                        index: *local,
                        vertex_count: mesh.vertex_count,
                    },
                    Located::Index(first + rank * 3 + corner),
                );
                usable = false;
                continue;
            }
            positions[corner] = asset.vertices[(mesh.vertex_offset + local) as usize].position;
        }
        if !usable {
            continue;
        }

        let area = triangle_area(&positions);
        if !area.is_finite() || area <= limits::MIN_TRIANGLE_AREA {
            // Un triangle plus petit n'a pas de normale exploitable : la
            // direction qu'on en tirerait ne dépendrait que de l'arrondi.
            report.push(Violation::DegenerateTriangle { area }, at);
        }
    }
}

/// Aire d'un triangle, moitié de la norme du produit vectoriel.
///
/// Partagée avec C-23 : un LOD généré écarte ses triangles dégénérés avec
/// **cette** fonction, pour que la validation de sortie ne puisse pas en juger
/// autrement à un arrondi près.
pub(crate) fn triangle_area(positions: &[[f32; 3]; 3]) -> f32 {
    let edge = |from: usize, to: usize| {
        [
            positions[to][0] - positions[from][0],
            positions[to][1] - positions[from][1],
            positions[to][2] - positions[from][2],
        ]
    };
    let u = edge(0, 1);
    let v = edge(0, 2);
    let cross = [
        u[1] * v[2] - u[2] * v[1],
        u[2] * v[0] - u[0] * v[2],
        u[0] * v[1] - u[1] * v[0],
    ];
    0.5 * (cross[0] * cross[0] + cross[1] * cross[1] + cross[2] * cross[2]).sqrt()
}

/// UV — bornes avant normalisation (R-142).
fn check_raw_uvs(raw_uvs: &[[f32; 2]], report: &mut ValidationReport) {
    for (index, uv) in raw_uvs.iter().enumerate() {
        for value in uv {
            if !value.is_finite() {
                report.push(
                    Violation::NotFinite("une coordonnée de texture"),
                    Located::Vertex(index),
                );
            } else if !(limits::MIN_UV..=limits::MAX_UV).contains(value) {
                report.push(
                    Violation::UvOutOfRange { value: *value },
                    Located::Vertex(index),
                );
            }
        }
    }
}

/// GÉOMÉTRIE, NORMALES et SKIN — au niveau du sommet.
///
/// Les UV compilés sont des `UNORM16` et le poids de déformation un `UNORM8` :
/// leurs bornes tiennent par construction, et les vérifier reviendrait à
/// vérifier que le type fait ce qu'il dit.
fn check_vertices(asset: &AssetView<'_>, report: &mut ValidationReport) {
    let skinned = asset.bone_count > 0;

    for (index, vertex) in asset.vertices.iter().enumerate() {
        let at = Located::Vertex(index);

        if !vertex.position.iter().all(|value| value.is_finite()) {
            report.push(Violation::NotFinite("la position"), at);
        }
        let missing = asset.missing_normals.get(index).copied().unwrap_or(false);
        if !missing && vertex.normal[0..3].iter().all(|value| *value == 0) {
            report.push(Violation::NormalNotNormalizable, at);
        }
        if skinned {
            if vertex.weight_sum() == 0 {
                report.push(Violation::WeightsNotNormalizable, at);
            }
            for bone in vertex.bones {
                if usize::from(bone) >= asset.bone_count {
                    report.push(
                        Violation::BoneOutOfRange {
                            bone,
                            bone_count: asset.bone_count,
                        },
                        at,
                    );
                }
            }
        }
    }
}

/// PHYSIQUE — densité, formes, enveloppes convexes, masses.
fn check_physics(asset: &AssetView<'_>, report: &mut ValidationReport) {
    for (index, collider) in asset.colliders.iter().enumerate() {
        let at = Located::Collider(index);

        if !collider.density.is_finite() || collider.density <= 0.0 {
            report.push(
                Violation::InvalidDensity {
                    density: collider.density,
                },
                at,
            );
        }
        if !collider.shape.has_valid_dimensions() {
            report.push(
                Violation::InvalidShapeDimension {
                    shape: collider.shape.name(),
                },
                at,
            );
        }
        if asset.dynamic_body && !collider.shape.allowed_on_dynamic_body() {
            report.push(
                Violation::ShapeForbiddenOnDynamicBody {
                    shape: collider.shape.name(),
                },
                at,
            );
        }
        if let ColliderShape::ConvexHull { points_count, .. } = collider.shape {
            if !(CONVEX_MIN_POINTS..=CONVEX_MAX_POINTS).contains(&points_count) {
                report.push(
                    Violation::ConvexPointCount {
                        count: points_count,
                    },
                    at,
                );
            }
        }
    }

    for (index, part) in asset.parts.iter().enumerate() {
        let at = Located::Part(index);
        if !part.mass.is_finite()
            || !(limits::MIN_PART_MASS..=limits::MAX_PART_MASS).contains(&part.mass)
        {
            report.push(Violation::MassOutOfRange { mass: part.mass }, at);
        }
        check_fraction(part.detach_threshold, "detach_threshold", at, report);
        check_fraction(part.propagation, "propagation", at, report);
    }
}

fn check_fraction(value: f32, field: &'static str, at: Located, report: &mut ValidationReport) {
    if !value.is_finite() || !(0.0..=1.0).contains(&value) {
        report.push(Violation::FractionOutOfRange { field, value }, at);
    }
}

/// DÉFORMATION — lattices, OBB, déplacements, ancrages, appartenance.
fn check_deformation(asset: &AssetView<'_>, report: &mut ValidationReport) {
    for (index, region) in asset.regions.iter().enumerate() {
        let at = Located::Region(index);

        for (axis, value) in region.res.iter().enumerate() {
            if !(ax_model::dm::integrity::LATTICE_MIN_RES
                ..=ax_model::dm::integrity::LATTICE_MAX_RES)
                .contains(value)
            {
                report.push(
                    Violation::LatticeResolution {
                        axis,
                        value: *value,
                    },
                    at,
                );
            }
        }

        let expected = region.expected_node_count();
        if region.node_count != expected {
            report.push(
                Violation::NodeCountMismatch {
                    declared: region.node_count,
                    expected,
                },
                at,
            );
        }

        if !region.has_volume() {
            report.push(Violation::DegenerateObb, at);
        }
        if !region.thickness.is_finite() || region.thickness <= 0.0 {
            report.push(
                Violation::InvalidThickness {
                    thickness: region.thickness,
                },
                at,
            );
        }

        // Au-delà de la moitié de la plus petite dimension, le volume se
        // retourne sur lui-même : le champ n'a plus de sens géométrique.
        let limit = 0.5 * region.smallest_half_extent();
        if !region.max_disp.is_finite() || region.max_disp <= 0.0 || region.max_disp > limit {
            report.push(
                Violation::MaxDisplacementOutOfRange {
                    max_disp: region.max_disp,
                    limit,
                },
                at,
            );
        }

        if region.has(ax_model::dm::integrity::region_flags::ANCHORED_BORDER)
            && !has_anchor(asset, region.anchor_mask_offset, region.node_count)
        {
            report.push(Violation::RegionWithoutAnchor, at);
        }
    }

    // Chaque node DEFORMABLE appartient à exactement une région. Le champ
    // `region` étant unique, « au plus une » tient par construction ; c'est
    // « au moins une » qu'il faut vérifier.
    for (index, node) in asset.nodes.iter().enumerate() {
        if !node.has(node_flags::DEFORMABLE) {
            continue;
        }
        if node.region == NONE_U16 || usize::from(node.region) >= asset.regions.len() {
            report.push(Violation::DeformableWithoutRegion, Located::Node(index));
        }
    }
}

/// Indique si au moins un nœud du bitset d'ancrage est posé.
fn has_anchor(asset: &AssetView<'_>, offset: u32, node_count: u32) -> bool {
    let bytes = node_count.div_ceil(8) as usize;
    let start = offset as usize;
    let Some(mask) = asset.anchor_masks.get(start..start + bytes) else {
        // Un bitset hors bornes ne contient aucun ancrage : le signaler comme
        // « sans ancrage » vaut mieux que de lire à côté.
        return false;
    };
    mask.iter().any(|byte| *byte != 0)
}

/// STRUCTURE — graphe de parts et liaisons.
fn check_structure(asset: &AssetView<'_>, report: &mut ValidationReport) {
    for (index, part) in asset.parts.iter().enumerate() {
        let at = Located::Part(index);
        if part.is_root() {
            continue;
        }
        let parent = usize::from(part.parent_part);
        if parent >= asset.parts.len() {
            report.push(
                Violation::OrphanPart {
                    parent: part.parent_part,
                },
                at,
            );
        } else if has_part_cycle(asset, index) {
            report.push(Violation::PartCycle, at);
        }
    }

    for (index, link) in asset.links.iter().enumerate() {
        let at = Located::Link(index);

        for part in [link.part_a, link.part_b] {
            if usize::from(part) >= asset.parts.len() {
                report.push(Violation::LinkPartOutOfRange { part }, at);
            }
        }
        if link.part_a == link.part_b {
            report.push(Violation::LinkToItself, at);
        }
        if !link.has_positive_capacities() {
            report.push(Violation::InvalidCapacity, at);
        }
        if link.kind().is_none() {
            report.push(Violation::UnknownLinkKind { raw: link.kind }, at);
        }
        check_fraction(link.propagation, "propagation", at, report);
    }
}

/// Remonte la chaîne des parents jusqu'à une racine, ou jusqu'à revenir sur ses
/// pas.
///
/// Le graphe de parts n'est pas contraint à l'ordre topologique — le cahier des
/// charges ne l'exige que de la hiérarchie de nodes —, un parcours est donc
/// nécessaire. Il est borné par le nombre de parts : au-delà, on a forcément
/// repassé quelque part.
fn has_part_cycle(asset: &AssetView<'_>, from: usize) -> bool {
    let mut current = from;
    for _ in 0..=asset.parts.len() {
        let part = &asset.parts[current];
        if part.is_root() {
            return false;
        }
        let parent = usize::from(part.parent_part);
        if parent >= asset.parts.len() {
            return false;
        }
        current = parent;
    }
    true
}

/// NOMS — uniques par catégorie, ASCII imprimable, au plus 64 octets.
fn check_names(asset: &AssetView<'_>, report: &mut ValidationReport) {
    let mut seen: Vec<(&str, &str)> = Vec::with_capacity(asset.names.len());

    for entry in asset.names {
        let at = Located::Asset;

        if entry.name.is_empty() {
            report.push(Violation::InvalidName { reason: "vide" }, at);
        } else if entry.name.len() > limits::MAX_NAME_BYTES {
            report.push(
                Violation::InvalidName {
                    reason: "plus de 64 octets",
                },
                at,
            );
        } else if !entry
            .name
            .bytes()
            .all(|byte| byte.is_ascii_graphic() || byte == b' ')
        {
            // Un nom non imprimable ne se retrouve dans aucun message d'erreur
            // ni dans aucun outil : il rendrait le modèle indébogable.
            report.push(
                Violation::InvalidName {
                    reason: "caractère non imprimable",
                },
                at,
            );
        }

        if seen.contains(&(entry.category, entry.name)) {
            report.push(
                Violation::DuplicateName {
                    category: entry.category,
                },
                at,
            );
        } else {
            seen.push((entry.category, entry.name));
        }
    }
}

/// Indique si `offset..offset + count` tient dans `len`.
fn within(offset: u32, count: u32, len: usize) -> bool {
    match offset.checked_add(count) {
        Some(end) => end as usize <= len,
        None => false,
    }
}

/// Sommet neutre, pour les tests.
#[cfg(test)]
pub(crate) fn vertex(position: [f32; 3]) -> ax_model::dm::geometry::Vertex {
    ax_model::dm::geometry::Vertex {
        position,
        normal: [0, 127, 0, 0],
        tangent: [127, 0, 0, 127],
        uv0: [0; 2],
        uv1: [0; 2],
        color: [255; 4],
        bones: [0; 4],
        weights: [255, 0, 0, 0],
        region: ax_model::dm::geometry::NO_REGION_U8,
        def_w: 0,
        _pad: [0; 6],
    }
}
