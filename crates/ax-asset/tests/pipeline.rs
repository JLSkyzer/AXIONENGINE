//! T-226 — la chaîne d'assets tient bout à bout.
//!
//! Import, validation, écriture, relecture. Chaque composant a ses propres
//! tests ; celui-ci vérifie qu'ils s'emboîtent, ce qu'aucun d'eux ne peut dire
//! seul.

use ax_asset::a3d::{A3dFile, A3dLimits, A3dWriter, SectionTag};
use ax_asset::import::{heal_geometry, import_obj, import_stl, ImportLimits, ImportedAsset};
use ax_asset::validate::{validate, AssetView, NamedEntry};
use ax_model::dm::material::MaterialDesc;

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

/// Les matériaux de l'asset, tels que la section `MATL` les portera.
fn table(asset: &ImportedAsset) -> Vec<MaterialDesc> {
    asset
        .materials
        .iter()
        .map(|material| material.desc)
        .collect()
}

/// Compose la vue que le validateur examine.
fn view<'a>(
    asset: &'a ImportedAsset,
    names: &'a [NamedEntry<'a>],
    materials: &'a [MaterialDesc],
) -> AssetView<'a> {
    AssetView {
        nodes: &asset.nodes,
        meshes: &asset.meshes,
        vertices: &asset.vertices,
        indices: &asset.indices,
        names,
        materials: Some(materials),
        texture_count: asset.textures.entries.len(),
        dynamic_body: true,
        missing_normals: &asset.missing_normals,
        ..AssetView::default()
    }
}

#[test]
fn t226_un_obj_importe_est_accepte_par_le_validateur() {
    let asset = import_obj(CUBE_OBJ, &SOURCE_LIMITS, |_| None).expect("import refusé");

    let names = [NamedEntry::new("node", "cube")];
    let materials = table(&asset);
    let report = validate(&view(&asset, &names, &materials), &asset.raw_uvs);

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
    let materials = table(&asset);
    let report = validate(&view(&asset, &names, &materials), &asset.raw_uvs);

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
    let materials = table(&asset);
    let report = validate(&view(&asset, &names, &materials), &asset.raw_uvs);

    assert!(!report.is_valid(), "coordonnée hors bornes acceptée");
    assert_eq!(report.code(), -3030);
}

/// Deux triangles qui partagent une arête, aux coordonnées `a`, `b`, `c`, `d`.
fn deux_triangles(a: [f32; 2], b: [f32; 2], c: [f32; 2], d: [f32; 2]) -> String {
    format!(
        "v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nvt {} {}\nvt {} {}\nvt {} {}\nvt {} {}\nvn 0 0 1\n\
         f 1/1/1 2/2/1 3/3/1\nf 1/1/1 3/3/1 4/4/1\n",
        a[0], a[1], b[0], b[1], c[0], c[1], d[0], d[1]
    )
}

fn valide_apres_reprise(asset: &ax_asset::import::ImportedAsset) {
    let names = [NamedEntry::new("node", "cube")];
    let materials = table(asset);
    let report = validate(&view(asset, &names, &materials), &asset.raw_uvs);
    assert!(report.is_valid(), "{:?}", report.errors.first());
}

#[test]
fn adr159_des_coordonnees_de_texture_lointaines_sont_ramenees_sans_changer_la_repetition() {
    let source = deux_triangles([818.25, 0.5], [818.75, 0.5], [818.75, 0.75], [818.25, 0.75]);
    let mut asset = import_obj(&source, &SOURCE_LIMITS, |_| None).expect("import");
    let healed = heal_geometry(&mut asset);
    assert_eq!(healed.shifted, 2);
    assert_eq!(healed.squeezed, 0);
    assert_eq!(healed.removed, 0);
    valide_apres_reprise(&asset);
    for uv in &asset.raw_uvs {
        assert_eq!((uv[0] * 4.0).rem_euclid(8.0) % 1.0, 0.0, "{uv:?}");
        // 818 est pair : le décalage laisse la même position dans la texture, en répétition comme en miroir.
        assert!((0.0..2.0).contains(&uv[0]), "{uv:?}");
    }
    assert_eq!(
        asset.vertices.len(),
        4,
        "des triangles qui s'accordent partagent leurs sommets"
    );
}

#[test]
fn adr159_deux_triangles_decales_differemment_ne_partagent_plus_leurs_sommets() {
    // Le second triangle a son centre bien plus loin que le premier.
    let source = deux_triangles([0.5, 0.5], [20.5, 0.5], [20.5, 0.75], [60.5, 0.75]);
    let mut asset = import_obj(&source, &SOURCE_LIMITS, |_| None).expect("import");
    let healed = heal_geometry(&mut asset);
    assert_eq!(healed.shifted, 2);
    valide_apres_reprise(&asset);
    assert_eq!(asset.vertices.len(), 6);
    assert_eq!(asset.indices.len(), 6);
}

#[test]
fn adr159_un_triangle_trop_etendu_est_resserre_et_reste_valide() {
    let source = deux_triangles([-100.0, 0.5], [100.0, 0.5], [100.0, 0.75], [-100.0, 0.75]);
    let mut asset = import_obj(&source, &SOURCE_LIMITS, |_| None).expect("import");
    let healed = heal_geometry(&mut asset);
    assert_eq!(healed.squeezed, 2);
    valide_apres_reprise(&asset);
}

#[test]
fn r542_un_triangle_d_aire_nulle_est_retire() {
    let source = "v 0 0 0\nv 1 0 0\nv 1 1 0\nv 2 0 0\nvt 0 0\nvn 0 0 1\n\
                  f 1/1/1 2/1/1 3/1/1\nf 1/1/1 2/1/1 4/1/1\n";
    let mut asset = import_obj(source, &SOURCE_LIMITS, |_| None).expect("import");
    let healed = heal_geometry(&mut asset);
    assert_eq!(healed.removed, 1);
    assert_eq!(asset.indices.len(), 3);
    valide_apres_reprise(&asset);
}

#[test]
fn r870_un_modele_sain_n_est_pas_touche() {
    let mut asset = import_obj(CUBE_OBJ, &SOURCE_LIMITS, |_| None).expect("import");
    let avant = (
        asset.vertices.clone(),
        asset.indices.clone(),
        asset.raw_uvs.clone(),
    );
    assert_eq!(
        heal_geometry(&mut asset),
        ax_asset::import::Healed::default()
    );
    assert_eq!(
        avant,
        (
            asset.vertices.clone(),
            asset.indices.clone(),
            asset.raw_uvs.clone()
        )
    );
}

#[test]
fn adr159_une_coordonnee_non_finie_reste_au_validateur() {
    let source = deux_triangles([f32::NAN, 0.5], [50.0, 0.5], [0.0, 0.0], [0.0, 1.0]);
    if let Ok(mut asset) = import_obj(&source, &SOURCE_LIMITS, |_| None) {
        assert_eq!(
            heal_geometry(&mut asset),
            ax_asset::import::Healed::default()
        );
    }
}
