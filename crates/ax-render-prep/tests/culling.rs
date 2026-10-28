//! T-520 (distance) et T-521 (frustum, lots identiques au chemin scalaire) — ADR-154 D8.

use ax_math::{Mat4, Quat, Vec3};
use ax_render_prep::{
    cull_batched, cull_scalar, Camera, CameraError, Candidate, CandidateError, LocalBounds, Visible,
};

/// Projection en perspective d'OpenGL (repère droit, profondeur de -1 à 1), comme celle de Minecraft.
fn perspective(fov_y: f32, aspect: f32, near: f32, far: f32) -> Mat4 {
    let f = 1.0 / (fov_y / 2.0).tan();
    Mat4::from_cols_array(&[
        f / aspect,
        0.0,
        0.0,
        0.0,
        0.0,
        f,
        0.0,
        0.0,
        0.0,
        0.0,
        (far + near) / (near - far),
        -1.0,
        0.0,
        0.0,
        2.0 * far * near / (near - far),
        0.0,
    ])
}

/// Une caméra à l'origine, regardant vers -Z (convention d'OpenGL), 70° de champ vertical.
fn camera(max_distance: f32, view_rotation: Quat) -> Camera {
    Camera {
        projection: perspective(70f32.to_radians(), 16.0 / 9.0, 0.05, 1024.0),
        view_rotation,
        viewport_width: 1920,
        viewport_height: 1080,
        max_distance,
        lod_bias: 0,
        quality: 2,
        candidate_count: 0,
    }
}

fn candidate(id: u32, asset: u32, position: Vec3) -> Candidate {
    Candidate {
        id,
        asset,
        flags: 0,
        position,
        scale: 1.0,
        rotation: Quat::IDENTITY,
    }
}

/// Une sphère unité centrée sur l'origine de l'asset ; l'asset 99 est inconnu.
fn unit(asset: u32) -> Option<LocalBounds> {
    (asset != 99).then_some(LocalBounds {
        center: Vec3::ZERO,
        radius: 1.0,
    })
}

fn visible_ids(camera: &Camera, candidates: &[Candidate]) -> Vec<u32> {
    let mut out = Vec::new();
    cull_scalar(camera, &camera.frustum(), candidates, unit, &mut out);
    out.iter().map(|v| v.id).collect()
}

#[test]
fn t520_au_dela_de_la_distance_maximale_rien_n_est_emis() {
    let cam = camera(128.0, Quat::IDENTITY);
    let candidates = [
        candidate(1, 0, Vec3::new(0.0, 0.0, -50.0)),
        candidate(2, 0, Vec3::new(0.0, 0.0, -200.0)),
        // Sphère qui touche la limite : distance 129, rayon 1 — gardée.
        candidate(3, 0, Vec3::new(0.0, 0.0, -129.0)),
        // Juste au-delà : distance 129,5, rayon 1.
        candidate(4, 0, Vec3::new(0.0, 0.0, -129.5)),
    ];
    let mut out = Vec::new();
    let stats = cull_scalar(&cam, &cam.frustum(), &candidates, unit, &mut out);
    assert_eq!(out.iter().map(|v| v.id).collect::<Vec<_>>(), [1, 3]);
    assert_eq!(
        (stats.candidates, stats.visible, stats.culled_distance),
        (4, 2, 2)
    );
    assert_eq!(out[0].distance, 50.0);
}

#[test]
fn t520_la_decision_a_la_limite_est_stable() {
    let cam = camera(128.0, Quat::IDENTITY);
    let at_limit = [candidate(7, 0, Vec3::new(0.0, 0.0, -129.0))];
    let first = visible_ids(&cam, &at_limit);
    for _ in 0..100 {
        assert_eq!(visible_ids(&cam, &at_limit), first);
    }
}

#[test]
fn t521_hors_du_frustum_rejete_a_cheval_garde() {
    let cam = camera(512.0, Quat::IDENTITY);
    let candidates = [
        candidate(1, 0, Vec3::new(0.0, 0.0, -10.0)),   // devant
        candidate(2, 0, Vec3::new(0.0, 0.0, 10.0)),    // derrière
        candidate(3, 0, Vec3::new(100.0, 0.0, -10.0)), // loin sur le côté
        candidate(4, 0, Vec3::new(0.0, 100.0, -10.0)), // loin au-dessus
        // Centre juste hors du plan gauche (demi-largeur à 10 blocs ≈ 12,45), sphère à cheval.
        candidate(5, 0, Vec3::new(-12.9, 0.0, -10.0)),
    ];
    let mut out = Vec::new();
    let stats = cull_scalar(&cam, &cam.frustum(), &candidates, unit, &mut out);
    assert_eq!(out.iter().map(|v| v.id).collect::<Vec<_>>(), [1, 5]);
    assert_eq!(stats.culled_frustum, 3);
}

#[test]
fn t521_la_rotation_de_la_vue_compte() {
    // Une vue tournée d'un demi-tour autour de Y regarde vers +Z.
    let cam = camera(512.0, Quat::from_rotation_y(core::f32::consts::PI));
    let candidates = [
        candidate(1, 0, Vec3::new(0.0, 0.0, -10.0)),
        candidate(2, 0, Vec3::new(0.0, 0.0, 10.0)),
    ];
    assert_eq!(visible_ids(&cam, &candidates), [2]);
}

#[test]
fn t521_un_asset_inconnu_n_est_pas_rendu() {
    let cam = camera(512.0, Quat::IDENTITY);
    let mut out = Vec::new();
    let stats = cull_scalar(
        &cam,
        &cam.frustum(),
        &[candidate(1, 99, Vec3::new(0.0, 0.0, -5.0))],
        unit,
        &mut out,
    );
    assert!(out.is_empty());
    assert_eq!(stats.unknown_asset, 1);
}

/// Générateur congruentiel : un jeu de candidats reproductible, sans dépendance.
struct Lcg(u64);

impl Lcg {
    fn next(&mut self) -> f32 {
        self.0 = self
            .0
            .wrapping_mul(6_364_136_223_846_793_005)
            .wrapping_add(1_442_695_040_888_963_407);
        ((self.0 >> 40) as f32 / (1u64 << 24) as f32) * 2.0 - 1.0
    }
}

#[test]
fn t521_les_lots_rendent_exactement_la_decision_scalaire() {
    let mut rng = Lcg(0x5EED);
    let views = [
        Quat::IDENTITY,
        Quat::from_rotation_y(1.1),
        Quat::from_euler(ax_math::EulerRot::YXZ, -2.3, 0.4, 0.1),
    ];
    let bounds = |asset: u32| match asset {
        0 => Some(LocalBounds {
            center: Vec3::new(0.5, 1.0, -0.25),
            radius: 1.5,
        }),
        1 => Some(LocalBounds {
            center: Vec3::ZERO,
            radius: 0.1,
        }),
        2 => Some(LocalBounds {
            center: Vec3::new(-3.0, 0.0, 2.0),
            radius: 6.0,
        }),
        _ => None,
    };
    for view in views {
        let cam = camera(160.0, view);
        // 1 003 candidats : le dernier lot est incomplet.
        let candidates: Vec<Candidate> = (0..1003)
            .map(|i| Candidate {
                id: i,
                asset: i % 4,
                flags: 0,
                position: Vec3::new(rng.next() * 200.0, rng.next() * 60.0, rng.next() * 200.0),
                scale: 0.5 + (rng.next() + 1.0) * 2.0,
                rotation: Quat::from_euler(
                    ax_math::EulerRot::YXZ,
                    rng.next() * 3.0,
                    rng.next(),
                    rng.next(),
                )
                .normalize(),
            })
            .collect();
        let frustum = cam.frustum();
        let (mut scalar, mut batched): (Vec<Visible>, Vec<Visible>) = (Vec::new(), Vec::new());
        let a = cull_scalar(&cam, &frustum, &candidates, bounds, &mut scalar);
        let b = cull_batched(&cam, &frustum, &candidates, bounds, &mut batched);
        assert_eq!(a, b);
        assert_eq!(scalar.len(), batched.len());
        for (s, t) in scalar.iter().zip(&batched) {
            assert_eq!(
                (s.index, s.id, s.distance.to_bits()),
                (t.index, t.id, t.distance.to_bits())
            );
        }
        // Le jeu exerce chaque issue.
        assert!(
            a.visible > 0 && a.culled_distance > 0 && a.culled_frustum > 0 && a.unknown_asset > 0,
            "{a:?}"
        );
    }
}

#[test]
fn camera_aller_retour_et_refus() {
    let cam = Camera {
        candidate_count: 1003,
        ..camera(96.0, Quat::from_rotation_x(0.3))
    };
    let bytes = cam.to_bytes();
    assert_eq!(bytes.len(), Camera::BYTES);
    assert_eq!(Camera::from_bytes(&bytes), Ok(cam));
    assert_eq!(
        Camera::from_bytes(&bytes[..96]),
        Err(CameraError::Length(96))
    );
    let mut nul = bytes;
    nul[64..80].fill(0);
    assert_eq!(Camera::from_bytes(&nul), Err(CameraError::NullRotation));
    let mut vide = bytes;
    vide[84..88].fill(0);
    assert_eq!(Camera::from_bytes(&vide), Err(CameraError::EmptyViewport));
    let mut loin = bytes;
    loin[88..92].copy_from_slice(&(-1f32).to_le_bytes());
    assert_eq!(Camera::from_bytes(&loin), Err(CameraError::Distance));
    let mut nan = bytes;
    nan[0..4].copy_from_slice(&f32::NAN.to_le_bytes());
    assert_eq!(Camera::from_bytes(&nan), Err(CameraError::NotFinite));
}

#[test]
fn candidats_aller_retour_et_refus() {
    let c = Candidate {
        id: 7,
        asset: 3,
        flags: 1,
        position: Vec3::new(1.0, -2.0, 3.5),
        scale: 2.0,
        rotation: Quat::from_rotation_z(0.7),
    };
    let mut bytes = c.to_bytes().to_vec();
    bytes.extend_from_slice(&c.to_bytes());
    assert_eq!(Candidate::read_all(&bytes), Ok(vec![c, c]));
    assert_eq!(
        Candidate::read_all(&bytes[..47]),
        Err(CandidateError::Length(47))
    );
    let mut zero = bytes.clone();
    zero[48 + 28..48 + 32].copy_from_slice(&0f32.to_le_bytes());
    assert_eq!(Candidate::read_all(&zero), Err(CandidateError::Invalid(1)));
    let mut nan = bytes;
    nan[20..24].copy_from_slice(&f32::NAN.to_le_bytes());
    assert_eq!(Candidate::read_all(&nan), Err(CandidateError::Invalid(0)));
}

fn mesh(min: [f32; 3], max: [f32; 3]) -> ax_model::dm::geometry::MeshDesc {
    ax_model::dm::geometry::MeshDesc {
        vertex_offset: 0,
        vertex_count: 0,
        index_offset: 0,
        index_count: 0,
        material: 0,
        lod: 0,
        flags: 0,
        aabb_min: min,
        aabb_max: max,
        region: u16::MAX,
        uv0_range: 0,
    }
}

fn draw(mesh: u32, translation: [f32; 3], scale: f32) -> ax_model::dm::render::RestDraw {
    let [x, y, z] = translation;
    ax_model::dm::render::RestDraw {
        mesh,
        node: 0,
        node_flags: 0,
        _pad: 0,
        model: [scale, 0.0, 0.0, 0.0, scale, 0.0, 0.0, 0.0, scale, x, y, z],
    }
}

#[test]
fn l_emprise_reunit_les_meshes_poses() {
    let meshes = [mesh([-1.0; 3], [1.0; 3])];
    // Le même mesh au repos, puis déplacé de 4 blocs en X et doublé : de (-1, -2, -2) à (6, 2, 2).
    let bounds = ax_render_prep::bounds_of(
        &meshes,
        &[draw(0, [0.0; 3], 1.0), draw(0, [4.0, 0.0, 0.0], 2.0)],
    )
    .expect("emprise");
    assert_eq!(bounds.center, Vec3::new(2.5, 0.0, 0.0));
    assert_eq!(bounds.radius, Vec3::new(3.5, 2.0, 2.0).length());
    assert_eq!(
        ax_render_prep::bounds_of(&meshes, &[]),
        None,
        "rien à dessiner"
    );
    assert_eq!(
        ax_render_prep::bounds_of(&meshes, &[draw(1, [0.0; 3], 1.0)]),
        None,
        "mesh absent"
    );
    let infini = [mesh([-1.0; 3], [f32::INFINITY, 1.0, 1.0])];
    assert_eq!(
        ax_render_prep::bounds_of(&infini, &[draw(0, [0.0; 3], 1.0)]),
        None,
        "valeur non finie"
    );
}
