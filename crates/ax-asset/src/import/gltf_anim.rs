//! Skins et animations glTF : le squelette et les clips de DM-10 (C-37, ADR-155 D2).
//!
//! **Un squelette par asset.** Les skins du document se fusionnent par node : un node qui sert
//! d'os à deux skins est un seul os, à condition que les deux lui donnent la même matrice de
//! liaison inverse. Les indices d'os des sommets, locaux au skin de leur node dans glTF,
//! deviennent des indices du squelette.
//!
//! **Les clips** gardent les temps et les valeurs de la source, sans rééchantillonnage : une clé
//! est un temps puis sa valeur, trois valeurs en `CUBIC_SPLINE` (tangente d'entrée, valeur,
//! tangente de sortie). Les poids de morphing ne sont pas animés par le moteur : leurs pistes
//! sont ignorées avec un avertissement (R-912), le reste du clip est importé.

use super::gltf::GltfReport;
use super::{ImportError, ImportedAsset, SourceFormat};
use ax_model::dm::animation::{channel, interp, AnimationDesc, BoneDesc, TrackDesc};
use ax_model::dm::geometry::mesh_flags;
use ax_model::dm::limits;
use ax_model::dm::scene::{name_hash, node_flags, NONE_U16, NO_PARENT};
use gltf::animation::util::ReadOutputs;
use gltf::animation::{Interpolation, Property};

/// La matrice identité, colonne par colonne : celle d'un skin sans `inverseBindMatrices`.
const IDENTITY: [f32; 16] = [
    1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0,
];

/// Importe les skins : le squelette, l'os de chaque node, les indices d'os des sommets.
///
/// `placement` donne la position de chaque node glTF dans l'asset ; les meshes sont déjà liés à
/// leurs nodes. Un mesh `SKINNED` qu'aucun node à skin ne porte perd le drapeau, avec un
/// avertissement : ses os ne désigneraient rien.
///
/// # Errors
///
/// [`ImportError::Malformed`] si un os est hors de la scène, que deux skins donnent deux
/// matrices au même os, que le squelette dépasse 128 os (R-200), qu'un sommet désigne un joint
/// hors de son skin, ou qu'un mesh soit porté par deux skins d'os différents.
pub(super) fn import_skins(
    document: &gltf::Gltf,
    buffers: &[Vec<u8>],
    placement: &[Option<u32>],
    asset: &mut ImportedAsset,
    report: &mut GltfReport,
    format: SourceFormat,
) -> Result<(), ImportError> {
    let malformed = |detail: String| ImportError::Malformed { format, detail };
    let mut bone_of_node: Vec<Option<u8>> = vec![None; asset.nodes.len()];
    // Pour chaque skin, l'os du squelette de chacun de ses joints.
    let mut maps: Vec<Vec<u8>> = Vec::with_capacity(document.skins().len());

    for skin in document.skins() {
        let reader = skin.reader(|buffer| buffers.get(buffer.index()).map(Vec::as_slice));
        let matrices: Vec<[[f32; 4]; 4]> = match reader.read_inverse_bind_matrices() {
            Some(matrices) => matrices.collect(),
            None if skin.inverse_bind_matrices().is_some() => {
                return Err(malformed(format!(
                    "skins[{}] : matrices de liaison inverse illisibles",
                    skin.index()
                )))
            }
            None => Vec::new(),
        };
        let joints: Vec<gltf::Node> = skin.joints().collect();
        if !matrices.is_empty() && matrices.len() < joints.len() {
            return Err(malformed(format!(
                "skins[{}] : {} matrices de liaison inverse pour {} joints",
                skin.index(),
                matrices.len(),
                joints.len()
            )));
        }
        let mut map = Vec::with_capacity(joints.len());
        for (local, joint) in joints.iter().enumerate() {
            let Some(Some(placed)) = placement.get(joint.index()).copied() else {
                return Err(malformed(format!(
                    "skins[{}] : l'os « {} » n'est dans aucune scène",
                    skin.index(),
                    joint.name().unwrap_or("<sans nom>")
                )));
            };
            let inverse_bind = matrices.get(local).map_or(IDENTITY, |columns| {
                let mut flat = [0.0; 16];
                for (column, values) in columns.iter().enumerate() {
                    flat[column * 4..column * 4 + 4].copy_from_slice(values);
                }
                flat
            });
            let placed = placed as usize;
            match bone_of_node[placed] {
                Some(bone) => {
                    // Le même os dans un second skin : une seule matrice peut le lier.
                    if asset.bones[usize::from(bone)].inverse_bind != inverse_bind {
                        return Err(malformed(format!(
                            "skins[{}] : l'os « {} » est lié par deux matrices différentes",
                            skin.index(),
                            joint.name().unwrap_or("<sans nom>")
                        )));
                    }
                    map.push(bone);
                }
                None => {
                    if asset.bones.len() == limits::MAX_BONES {
                        return Err(malformed(format!(
                            "squelette de plus de {} os (R-200, E-3060)",
                            limits::MAX_BONES
                        )));
                    }
                    // Au plus 128 os : l'index tient dans un `u8`.
                    let bone = asset.bones.len() as u8;
                    asset.bones.push(BoneDesc {
                        name_hash: asset.nodes[placed].name_hash,
                        parent: NONE_U16,
                        _pad: 0,
                        node: placed as u32,
                        inverse_bind,
                    });
                    bone_of_node[placed] = Some(bone);
                    map.push(bone);
                }
            }
        }
        maps.push(map);
    }

    // Le parent d'un os est le plus proche ancêtre de son node qui soit un os.
    for index in 0..asset.bones.len() {
        let mut node = asset.nodes[asset.bones[index].node as usize].parent;
        while node != NO_PARENT {
            if let Some(parent) = bone_of_node[node as usize] {
                asset.bones[index].parent = u16::from(parent);
                break;
            }
            node = asset.nodes[node as usize].parent;
        }
        let node = asset.bones[index].node as usize;
        asset.nodes[node].bone = index as u32;
    }

    // Les indices d'os des sommets, du skin de leur node au squelette.
    let mut remapped: Vec<Option<usize>> = vec![None; asset.meshes.len()];
    for node in document.nodes() {
        let (Some(skin), Some(_), Some(Some(placed))) = (
            node.skin(),
            node.mesh(),
            placement.get(node.index()).copied(),
        ) else {
            continue;
        };
        for mesh in asset.nodes[placed as usize].meshes() {
            let mesh = mesh as usize;
            match remapped.get(mesh).copied().flatten() {
                Some(other) if maps[other] != maps[skin.index()] => {
                    return Err(malformed(format!(
                        "meshes[{}] : porté par deux skins aux os différents",
                        node.mesh().map_or(0, |mesh| mesh.index())
                    )));
                }
                Some(_) => {}
                None => {
                    remap(asset, mesh, &maps[skin.index()]).map_err(|local| {
                        malformed(format!(
                            "node « {} » : un sommet désigne le joint {local}, hors des {} \
                             joints de son skin",
                            node.name().unwrap_or("<sans nom>"),
                            maps[skin.index()].len()
                        ))
                    })?;
                    remapped[mesh] = Some(skin.index());
                }
            }
        }
    }
    for (index, mesh) in asset.meshes.iter_mut().enumerate() {
        if mesh.flags & mesh_flags::SKINNED != 0 && remapped[index].is_none() {
            mesh.flags &= !mesh_flags::SKINNED;
            report.warnings.push(format!(
                "mesh {index} : JOINTS_0 sans node à skin qui le porte — dessiné rigide"
            ));
        }
    }
    Ok(())
}

/// Traduit les indices d'os des sommets d'un mesh `SKINNED` par la table de son skin.
///
/// Un joint de poids nul ne compte pas : glTF ne lui demande rien, et des exporteurs y laissent
/// un index quelconque. Il prend l'os du premier joint. Rend l'index local fautif d'un joint de
/// poids non nul hors du skin.
fn remap(asset: &mut ImportedAsset, mesh: usize, map: &[u8]) -> Result<(), u8> {
    let desc = asset.meshes[mesh];
    if desc.flags & mesh_flags::SKINNED == 0 {
        return Ok(());
    }
    let start = desc.vertex_offset as usize;
    let end = start + desc.vertex_count as usize;
    for vertex in &mut asset.vertices[start..end] {
        for slot in 0..4 {
            let local = vertex.bones[slot];
            vertex.bones[slot] = match map.get(usize::from(local)) {
                Some(bone) => *bone,
                None if vertex.weights[slot] == 0 => map.first().copied().unwrap_or(0),
                None => return Err(local),
            };
        }
    }
    Ok(())
}

/// Importe les animations : un clip par animation glTF, une piste par canal.
///
/// Un canal qui vise un node hors de toute scène, ou qui anime des poids de morphing, est
/// ignoré avec un avertissement (R-912). Les nodes animés reçoivent le drapeau `ANIMATED`.
///
/// # Errors
///
/// [`ImportError::Malformed`] si un échantillonneur est illisible, sans clé, ou que ses valeurs
/// ne sont pas en nombre de ses temps.
pub(super) fn import_animations(
    document: &gltf::Gltf,
    buffers: &[Vec<u8>],
    placement: &[Option<u32>],
    asset: &mut ImportedAsset,
    report: &mut GltfReport,
    format: SourceFormat,
) -> Result<(), ImportError> {
    let malformed = |detail: String| ImportError::Malformed { format, detail };
    let too_large = || malformed("animations au-delà de ce qu'un u32 décrit".to_owned());

    for animation in document.animations() {
        let name = animation.name().unwrap_or("").to_owned();
        let label = if name.is_empty() {
            format!("animations[{}]", animation.index())
        } else {
            format!("animation « {name} »")
        };
        let track_offset = asset.animations.tracks.len();
        let mut duration = 0.0f32;

        for gltf_channel in animation.channels() {
            let target = gltf_channel.target();
            let Some(Some(placed)) = placement.get(target.node().index()).copied() else {
                report.warnings.push(format!(
                    "{label} : une piste vise un node hors de toute scène, ignorée"
                ));
                continue;
            };
            let channel = match target.property() {
                Property::Translation => channel::TRANSLATION,
                Property::Rotation => channel::ROTATION,
                Property::Scale => channel::SCALE,
                Property::MorphTargetWeights => {
                    report.warnings.push(format!(
                        "{label} : piste de poids de morphing non prise en charge, ignorée"
                    ));
                    continue;
                }
            };
            let interp = match gltf_channel.sampler().interpolation() {
                Interpolation::Step => interp::STEP,
                Interpolation::Linear => interp::LINEAR,
                Interpolation::CubicSpline => interp::CUBIC_SPLINE,
            };

            let reader =
                gltf_channel.reader(|buffer| buffers.get(buffer.index()).map(Vec::as_slice));
            let unreadable = || malformed(format!("{label} : échantillonneur illisible"));
            let times: Vec<f32> = reader.read_inputs().ok_or_else(unreadable)?.collect();
            let values: Vec<f32> = match reader.read_outputs().ok_or_else(unreadable)? {
                ReadOutputs::Translations(values) | ReadOutputs::Scales(values) => {
                    values.flatten().collect()
                }
                ReadOutputs::Rotations(values) => values.into_f32().flatten().collect(),
                // Écartées plus haut, avec leur avertissement.
                ReadOutputs::MorphTargetWeights(_) => continue,
            };

            let track = TrackDesc {
                target_node: placed,
                channel,
                interp,
                _pad: [0; 2],
                key_offset: u32::try_from(asset.animations.keys.len()).map_err(|_| too_large())?,
                key_count: u32::try_from(times.len()).map_err(|_| too_large())?,
            };
            // Le canal et l'interpolation viennent des deux tables ci-dessus : le pas existe.
            let stride = track.key_stride().unwrap_or(1);
            let per_key = stride - 1;
            if times.is_empty() || values.len() != times.len() * per_key {
                return Err(malformed(format!(
                    "{label} : {} temps pour {} valeurs, {per_key} attendues par clé",
                    times.len(),
                    values.len()
                )));
            }
            asset.animations.keys.reserve(times.len() * stride);
            for (time, value) in times.iter().zip(values.chunks_exact(per_key)) {
                asset.animations.keys.push(*time);
                asset.animations.keys.extend_from_slice(value);
            }
            // Le plus grand temps, même désordonné : C-22 refusera l'ordre, la durée reste
            // celle que l'auteur a écrite.
            duration = times.iter().copied().fold(duration, f32::max);
            asset.animations.tracks.push(track);
            asset.nodes[placed as usize].flags |= node_flags::ANIMATED;
        }

        let track_count = asset.animations.tracks.len() - track_offset;
        asset.animations.animations.push(AnimationDesc {
            name_hash: name_hash(&name),
            duration,
            track_offset: u32::try_from(track_offset).map_err(|_| too_large())?,
            track_count: u32::try_from(track_count).map_err(|_| too_large())?,
            flags: 0,
        });
        if !name.is_empty() {
            asset.names.push(("animation", name.clone()));
        }
        asset.animations.names.push(name);
    }
    Ok(())
}
