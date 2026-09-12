//! T-226 — la chaîne d'assets tient bout à bout.
//!
//! Import, validation, écriture, relecture. Chaque composant a ses propres
//! tests ; celui-ci vérifie qu'ils s'emboîtent, ce qu'aucun d'eux ne peut dire
//! seul.

use ax_asset::a3d::{A3dFile, A3dLimits, A3dWriter, SectionTag};
use ax_asset::import::{import_obj, import_stl, ImportLimits, ImportedAsset};
use ax_asset::validate::{validate, AssetView, NamedEntry};

const SOURCE_LIMITS: ImportLimits = ImportLimits::new(1 << 20);
const A3D_LIMITS: A3dLimits = A3dLimits::new(1 << 20);

const CUBE_OBJ: &str = "\
o cube
v -1.0 -1.0 -1.0
v  1.0 -1.0 -1.0
v  1.0  1.0 -1.0
v -1.0  1.0 -1.0
vt 0.0 0.0
vt 1.0 0.0
vt 1.0 1.0
vt 0.0 1.0
vn 0.0 0.0 -1.0
f 1/1/1 2/2/1 3/3/1
f 1/1/1 3/3/1 4/4/1
";

/// Compose la vue que le validateur examine.
fn view<'a>(asset: &'a ImportedAsset, names: &'a [NamedEntry<'a>]) -> AssetView<'a> {
    AssetView {
        nodes: &asset.nodes,
        meshes: &asset.meshes,
        vertices: &asset.vertices,
        indices: &asset.indices,
        names,
        material_count: asset.materials.len(),
        texture_count: 0,
        animation_count: 0,
        bone_count: 0,
        dynamic_body: true,
        missing_normals: &asset.missing_normals,
        ..AssetView::default()
    }
}

#[test]
fn t226_un_obj_importe_est_accepte_par_le_validateur() {
    let asset = import_obj(CUBE_OBJ, &SOURCE_LIMITS, |_| None).expect("import refusé");

    let names = [NamedEntry::new("node", "cube")];
    let report = validate(&view(&asset, &names), &asset.raw_uvs);

    assert!(
        report.is_valid(),
        "un OBJ sain devrait passer : {:?}",
        report.errors
    );
}

#[test]
fn t226_un_stl_importe_est_accepte_par_le_validateur() {
    // Un STL binaire d'un triangle, écrit à la main.
    let mut bytes = vec![0u8; 80];
    bytes.extend_from_slice(&1u32.to_le_bytes());
    for value in [0.0f32, 0.0, 1.0] {
        bytes.extend_from_slice(&value.to_le_bytes());
    }
    for corner in [[0.0f32, 0.0, 0.0], [1.0, 0.0, 0.0], [0.0, 1.0, 0.0]] {
        for value in corner {
            bytes.extend_from_slice(&value.to_le_bytes());
        }
    }
    bytes.extend_from_slice(&0u16.to_le_bytes());

    let asset = import_stl(&bytes, &SOURCE_LIMITS).expect("import refusé");
    let names = [NamedEntry::new("node", "stl_root")];
    let report = validate(&view(&asset, &names), &asset.raw_uvs);

    assert!(report.is_valid(), "{:?}", report.errors);
}

#[test]
fn t226_un_asset_valide_traverse_le_conteneur_sans_perte() {
    let asset = import_obj(CUBE_OBJ, &SOURCE_LIMITS, |_| None).expect("import refusé");

    // Les sommets partent dans GEOM tels quels : le format est figé, et une
    // section n'est qu'un transport.
    let vertices: Vec<u8> = asset
        .vertices
        .iter()
        .flat_map(|vertex| {
            let mut bytes = Vec::with_capacity(48);
            bytes.extend_from_slice(&vertex.position[0].to_le_bytes());
            bytes.extend_from_slice(&vertex.position[1].to_le_bytes());
            bytes.extend_from_slice(&vertex.position[2].to_le_bytes());
            bytes
        })
        .collect();
    let indices: Vec<u8> = asset
        .indices
        .iter()
        .flat_map(|index| index.to_le_bytes())
        .collect();

    let mut writer = A3dWriter::new(1, 2, 1);
    writer
        .compressed_section(SectionTag::GEOM, &vertices)
        .expect("GEOM");
    writer.section(SectionTag::PHYS, &indices).expect("PHYS");
    let bytes = writer.finish().expect("écriture");

    let file = A3dFile::open(&bytes, A3D_LIMITS).expect("lecture");
    assert_eq!(
        file.section(SectionTag::GEOM).expect("GEOM"),
        Some(vertices)
    );
    assert_eq!(file.section(SectionTag::PHYS).expect("PHYS"), Some(indices));
}

#[test]
fn t226_un_obj_aux_coordonnees_hors_bornes_est_refuse_par_le_validateur() {
    // R-142 : la borne porte sur les valeurs avant normalisation, que l'import
    // conserve précisément pour que le validateur puisse les voir.
    let source = CUBE_OBJ.replace("vt 1.0 0.0", "vt 40.0 0.0");
    let asset = import_obj(&source, &SOURCE_LIMITS, |_| None).expect("import refusé");

    let names = [NamedEntry::new("node", "cube")];
    let report = validate(&view(&asset, &names), &asset.raw_uvs);

    assert!(!report.is_valid(), "coordonnée hors bornes acceptée");
    assert_eq!(report.code(), -3030);
}
