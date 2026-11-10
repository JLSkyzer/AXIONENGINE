//! ADR-157 D1 : la sphère que balaient les clips d'un asset contient chacune de ses poses, et la sphère
//! de repos, elle, ne les contient pas toutes — c'est ce qui faisait retenir sans test une assembly
//! animée.
//!
//! Les modèles animés écrits par code sont compilés par le vrai compilateur, leurs sections relues ; la
//! sphère balayée, échantillonnée huit fois par tick, est confrontée à un échantillonnage dix fois plus
//! fin.

#[path = "../../ax-asset/tests/modeles_animes/mod.rs"]
mod modeles_animes;

use ax_anim::TICKS_PER_SECOND;
use ax_asset::a3d::{
    decode_animations, decode_geometry, decode_nodes, A3dFile, A3dLimits, SectionTag,
};
use ax_asset::compile::{compile, CompileOptions};
use ax_asset::import::{ImportLimits, SourceFormat};
use ax_asset::load::render_sections;
use ax_asset::options::AssetOptions;
use ax_math::{Affine3A, Vec3};
use ax_model::dm::animation::{anim_action, AnimCommand};
use ax_model::dm::geometry::MeshDesc;
use ax_model::dm::render::RestDraw;
use ax_render_prep::{bounds_of, LocalBounds};
use ax_scene::rest_draws;
use axion_native::animation::{AnimatedAsset, Instance};
use axion_native::asset_store::decode_render_asset;

/// L'identifiant sous lequel les modèles sont compilés.
const ASSET_ID: u64 = 0x0A57_0157_0000_0001;

/// Un modèle compilé : ses meshes, ses dessins au repos et son animation.
struct Modele {
    conteneur: Vec<u8>,
    meshes: Vec<MeshDesc>,
    draws: Vec<RestDraw>,
    animation: AnimatedAsset,
}

fn charger(gltf: &str) -> Modele {
    let options = CompileOptions {
        asset_id: ASSET_ID,
        source_hash: 0,
        limits: ImportLimits::new(1 << 20),
        dynamic_body: true,
        authored: AssetOptions::DEFAULT,
    };
    let bytes = compile(gltf.as_bytes(), SourceFormat::Gltf, &options, |_| None)
        .expect("compilation")
        .bytes;
    let file = A3dFile::open(&bytes, A3dLimits::new(1 << 20)).expect("A3D");
    let section = |tag| file.section(tag).expect("lisible").expect("présente");
    let nodes = decode_nodes(&section(SectionTag::NODE))
        .expect("NODE")
        .nodes;
    let meshes = decode_geometry(&section(SectionTag::GEOM))
        .expect("GEOM")
        .meshes;
    let draws = rest_draws(&nodes, meshes.len()).expect("dessins");
    let table = decode_animations(&section(SectionTag::ANIM)).expect("ANIM");
    let animation = AnimatedAsset::new(&nodes, table, draws.iter().map(|d| d.node).collect(), &[])
        .expect("table acceptée");
    Modele {
        conteneur: bytes,
        meshes,
        draws,
        animation,
    }
}

/// Les coins posés de chaque dessin, au tick `tick` du clip 0 joué seul depuis le tick 0.
fn coins(modele: &Modele, tick: f64) -> Vec<Vec3> {
    let mut instance = Instance::new(&modele.animation);
    let jouer = AnimCommand {
        tick: 0.0,
        action: anim_action::PLAY,
        layer: 0,
        clip: 0,
        weight: 1.0,
        fade: 0.0,
        speed: 1.0,
        mask_count: 0,
        origin: 0.0,
    };
    instance
        .apply(&modele.animation, &jouer, &[])
        .expect("PLAY");
    instance.evaluate(&modele.animation, tick);
    let mut matrices = Vec::new();
    instance.draw_matrices(&modele.animation, &mut matrices);
    let mut out = Vec::new();
    for (draw, m) in modele.draws.iter().zip(matrices.chunks_exact(12)) {
        let model = Affine3A::from_cols_array(&m.try_into().expect("douze flottants"));
        let mesh = &modele.meshes[draw.mesh as usize];
        let (lo, hi) = (Vec3::from(mesh.aabb_min), Vec3::from(mesh.aabb_max));
        for coin in 0..8u8 {
            out.push(model.transform_point3(Vec3::new(
                if coin & 1 == 0 { lo.x } else { hi.x },
                if coin & 2 == 0 { lo.y } else { hi.y },
                if coin & 4 == 0 { lo.z } else { hi.z },
            )));
        }
    }
    out
}

fn contient(sphere: LocalBounds, point: Vec3) -> bool {
    point.distance(sphere.center) <= sphere.radius + 1e-4
}

/// Une toupie : la pale, à deux blocs de l'axe, fait `quarts` quarts de tour autour de `z` en 0,125 s
/// — une durée exacte en `f32` —, une clé par quart. Huit échantillons par tick en font vingt : à
/// quatre-vingts quarts, chacun tombe un tour plus loin, au même endroit, et sans échantillons par clé
/// la pale paraîtrait immobile.
fn toupie(quarts: usize) -> String {
    let mut modele = modeles_animes::Modele::default();
    let (positions, indices) = modele.triangle();
    let temps: Vec<f32> = (0..=quarts)
        .map(|k| k as f32 * 0.125 / quarts as f32)
        .collect();
    let temps = modele.flottants(&temps, "SCALAR");
    let mut rotations = Vec::new();
    for k in 0..=quarts {
        let demi = k as f32 * core::f32::consts::FRAC_PI_4;
        rotations.extend_from_slice(&[0.0, 0.0, demi.sin(), demi.cos()]);
    }
    let rotations = modele.flottants(&rotations, "VEC4");
    modele.document(&format!(
        "\"scenes\": [{{ \"nodes\": [0] }}],
  \"nodes\": [{{ \"name\": \"axe\", \"children\": [1] }},
    {{ \"name\": \"pale\", \"mesh\": 0, \"translation\": [2.0, 0.0, 0.0] }}],
  \"meshes\": [{{ \"primitives\": [{{ \"attributes\": {{ \"POSITION\": {positions} }},
    \"indices\": {indices} }}] }}],
  \"animations\": [{{ \"name\": \"tourner\",
    \"samplers\": [{{ \"input\": {temps}, \"output\": {rotations} }}],
    \"channels\": [{{ \"sampler\": 0, \"target\": {{ \"node\": 0, \"path\": \"rotation\" }} }}] }}]"
    ))
}

#[test]
fn adr157_d1_la_sphere_balayee_contient_chaque_pose() {
    for (nom, gltf) in [
        ("pendule", modeles_animes::pendule()),
        ("rebond", modeles_animes::rebond()),
        ("toupie", toupie(8)),
        ("toupie rapide", toupie(80)),
    ] {
        let modele = charger(&gltf);
        let balayee = modele
            .animation
            .swept_bounds(&modele.meshes, &modele.draws)
            .expect("sphère balayée");
        let repos = bounds_of(&modele.meshes, &modele.draws).expect("sphère de repos");
        let duree = f64::from(modele.animation.clips().clip(0).duration);
        // Dix fois plus fin que les huit échantillons par tick de la sphère.
        let pas = (duree * 80.0 * TICKS_PER_SECOND).ceil() as usize;
        let mut hors_du_repos = false;
        for k in 0..=pas {
            let tick = duree * TICKS_PER_SECOND * k as f64 / pas as f64;
            for coin in coins(&modele, tick) {
                assert!(
                    contient(balayee, coin),
                    "{nom} : le coin {coin:?} au tick {tick:.3} sort de la sphère balayée {balayee:?}"
                );
                hors_du_repos |= !contient(repos, coin);
            }
        }
        assert!(
            hors_du_repos,
            "{nom} : la sphère de repos devrait manquer une pose"
        );
    }
}

#[test]
fn adr157_d1_des_dessins_qui_ne_sont_pas_ceux_de_l_animation_ne_balaient_rien() {
    let modele = charger(&modeles_animes::pendule());
    assert!(modele.animation.swept_bounds(&modele.meshes, &[]).is_none());
}

#[test]
fn adr157_d1_un_asset_anime_charge_publie_sa_sphere_balayee_au_culling() {
    // Le chemin du chargement réel : la sphère de culling de l'asset est la sphère balayée, et le LOD
    // garde la sphère de repos.
    let modele = charger(&modeles_animes::pendule());
    let charge = decode_render_asset(
        &modele.conteneur,
        ASSET_ID,
        render_sections(),
        A3dLimits::new(1 << 20),
    )
    .expect("chargement");
    assert_eq!(
        charge.cull_bounds(),
        modele.animation.swept_bounds(&modele.meshes, &modele.draws)
    );
    assert_eq!(
        charge.render_bounds(),
        bounds_of(&modele.meshes, &modele.draws)
    );
    assert_ne!(charge.cull_bounds(), charge.render_bounds());
}
