//! T-522 — le niveau de détail géométrique (ADR-154 D4) : seuils, hystérésis, biais, mémoire.

use ax_math::{Mat4, Quat};
use ax_render_prep::lod::{
    biased, hysteresis_level, projected_px, target_level, LodState, FORGET_AFTER_FRAMES,
};
use ax_render_prep::Camera;

fn camera() -> Camera {
    let f = 1.0 / (70f32.to_radians() / 2.0).tan();
    let mut m = [0f32; 16];
    m[0] = f / (16.0 / 9.0);
    m[5] = f;
    m[10] = -1.0;
    m[11] = -1.0;
    m[14] = -0.1;
    Camera {
        projection: Mat4::from_cols_array(&m),
        view_rotation: Quat::IDENTITY,
        viewport_width: 1920,
        viewport_height: 1080,
        max_distance: 128.0,
        lod_bias: 0,
        quality: 0,
        candidate_count: 0,
    }
}

#[test]
fn t522_les_niveaux_suivent_les_seuils() {
    assert_eq!(target_level(250.0, 5), 0);
    assert_eq!(
        target_level(200.0, 5),
        0,
        "au seuil, le niveau reste le plus fin"
    );
    assert_eq!(target_level(150.0, 5), 1);
    assert_eq!(target_level(50.0, 5), 2);
    assert_eq!(target_level(20.0, 5), 3);
    assert_eq!(target_level(5.0, 5), 4);
    assert_eq!(target_level(5.0, 3), 2, "borné aux niveaux de l'asset");
    assert_eq!(
        target_level(5.0, 1),
        0,
        "un asset sans LOD reste au niveau 0"
    );
}

#[test]
fn t522_l_hysteresis_retient_le_niveau_pres_d_un_seuil() {
    // Du niveau 0, on ne passe au 1 que sous 90 % de 200 px.
    assert_eq!(hysteresis_level(Some(0), 195.0, 5), 0);
    assert_eq!(hysteresis_level(Some(0), 179.0, 5), 1);
    // Du niveau 1, on ne revient au 0 qu'au-dessus de 110 % de 200 px.
    assert_eq!(hysteresis_level(Some(1), 205.0, 5), 1);
    assert_eq!(hysteresis_level(Some(1), 221.0, 5), 0);
    // Un saut franc traverse plusieurs seuils d'un coup.
    assert_eq!(hysteresis_level(Some(0), 5.0, 5), 4);
    assert_eq!(hysteresis_level(Some(4), 500.0, 5), 0);
    // Un candidat nouveau prend le niveau des seuils.
    assert_eq!(hysteresis_level(None, 195.0, 5), 1);
}

#[test]
fn t522_une_oscillation_de_5_pour_cent_ne_change_pas_le_niveau() {
    let mut state = LodState::new();
    for threshold in [200.0f32, 80.0, 30.0, 10.0] {
        state.begin_frame();
        let start = state.level(7, threshold * 1.2, 5, 0);
        for frame in 0..200 {
            state.begin_frame();
            let px = if frame % 2 == 0 {
                threshold * 1.05
            } else {
                threshold * 0.95
            };
            assert_eq!(
                state.level(7, px, 5, 0),
                start,
                "seuil {threshold}, frame {frame}"
            );
        }
    }
}

#[test]
fn t522_le_biais_decale_et_reste_borne() {
    assert_eq!(biased(1, 1, 5), 2);
    assert_eq!(biased(4, 3, 5), 4);
    assert_eq!(biased(1, -2, 5), 0);
    assert_eq!(biased(0, 2, 1), 0);
    // L'hystérésis porte sur le niveau sans biais.
    let mut state = LodState::new();
    state.begin_frame();
    assert_eq!(state.level(3, 150.0, 5, 2), 3);
    assert_eq!(state.remembered(3), Some(1));
}

#[test]
fn t522_un_candidat_absent_est_oublie() {
    let mut state = LodState::new();
    state.begin_frame();
    state.level(1, 50.0, 5, 0);
    for _ in 0..FORGET_AFTER_FRAMES {
        state.begin_frame();
    }
    assert_eq!(
        state.remembered(1),
        Some(2),
        "encore retenu après 64 frames"
    );
    state.begin_frame();
    assert!(state.is_empty(), "oublié après la 65e");
}

#[test]
fn la_taille_apparente_est_le_diametre_projete() {
    let cam = camera();
    let f = 1.0 / (70f32.to_radians() / 2.0).tan();
    assert_eq!(projected_px(&cam, 1.0, 10.0), 1080.0 * f / 10.0);
    assert!(projected_px(&cam, 1.0, 20.0) < projected_px(&cam, 1.0, 10.0));
    assert_eq!(
        projected_px(&cam, 2.0, 1.0),
        f32::INFINITY,
        "la caméra dans la sphère"
    );
}
