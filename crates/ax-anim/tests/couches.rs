//! T-362, T-363, T-364 et R-200 — les couches d'une instance : crossfade sans saut, couches
//! additives, masque de nodes, plafond de pistes actives ; boucle, vitesse et arrêt.

use ax_anim::{AnimError, Animator, ClipLibrary};
use ax_model::dm::animation::{animation_flags, channel, interp, AnimationDesc, TrackDesc};
use ax_model::dm::geometry::Transform;
use ax_model::dm::scene::name_hash;

const FRAC: f32 = core::f32::consts::FRAC_1_SQRT_2;

/// Un clip en construction : ses pistes et leurs clés.
struct Clip {
    nom: &'static str,
    duree: f32,
    boucle: bool,
    pistes: Vec<(u32, u8, u8, Vec<f32>)>,
}

impl Clip {
    fn new(nom: &'static str, duree: f32) -> Self {
        Self {
            nom,
            duree,
            boucle: false,
            pistes: Vec::new(),
        }
    }

    fn piste(mut self, node: u32, canal: u8, interpolation: u8, cles: &[f32]) -> Self {
        self.pistes
            .push((node, canal, interpolation, cles.to_vec()));
        self
    }
}

fn bibliotheque(clips: Vec<Clip>, nodes: usize) -> ClipLibrary {
    let (mut animations, mut noms, mut pistes, mut cles) =
        (Vec::new(), Vec::new(), Vec::new(), Vec::new());
    for clip in clips {
        animations.push(AnimationDesc {
            name_hash: name_hash(clip.nom),
            duration: clip.duree,
            track_offset: pistes.len() as u32,
            track_count: clip.pistes.len() as u32,
            flags: if clip.boucle {
                animation_flags::LOOP
            } else {
                0
            },
        });
        noms.push(clip.nom.to_owned());
        for (node, canal, interpolation, valeurs) in clip.pistes {
            let mut piste = TrackDesc {
                target_node: node,
                channel: canal,
                interp: interpolation,
                _pad: [0; 2],
                key_offset: cles.len() as u32,
                key_count: 0,
            };
            piste.key_count = (valeurs.len() / piste.key_stride().unwrap()) as u32;
            cles.extend(valeurs);
            pistes.push(piste);
        }
    }
    ClipLibrary::new(animations, noms, pistes, cles, nodes).expect("bibliothèque")
}

/// Deux nodes : le premier à l'origine, le second un bloc au-dessus.
fn repos() -> Vec<Transform> {
    vec![
        Transform::identity(),
        Transform {
            translation: [0.0, 1.0, 0.0],
            ..Transform::identity()
        },
    ]
}

/// Les clips de l'essai :
/// - `avancer` : le node 0 de 0 à 1 bloc en `x`, en une seconde ;
/// - `monter` : le node 0 tenu à 5 blocs en `y` ;
/// - `tourner` : le node 1 d'un quart de tour autour de `z`, en une seconde ;
/// - `pencher` : le node 1 tourné d'un quart de tour autour de `x`, et 2 blocs plus loin en `z` ;
/// - `tout` : le node 0 à 3 blocs en `x`, le node 1 à 4 blocs en `y` ;
/// - `boucler` : `avancer`, en boucle.
fn clips() -> ClipLibrary {
    let avancer = [0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0];
    let mut boucler =
        Clip::new("boucler", 1.0).piste(0, channel::TRANSLATION, interp::LINEAR, &avancer);
    boucler.boucle = true;
    bibliotheque(
        vec![
            Clip::new("avancer", 1.0).piste(0, channel::TRANSLATION, interp::LINEAR, &avancer),
            Clip::new("monter", 0.0).piste(
                0,
                channel::TRANSLATION,
                interp::STEP,
                &[0.0, 0.0, 5.0, 0.0],
            ),
            Clip::new("tourner", 1.0).piste(
                1,
                channel::ROTATION,
                interp::LINEAR,
                &[0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0, FRAC, FRAC],
            ),
            Clip::new("pencher", 0.0)
                .piste(
                    1,
                    channel::ROTATION,
                    interp::STEP,
                    &[0.0, FRAC, 0.0, 0.0, FRAC],
                )
                .piste(1, channel::TRANSLATION, interp::STEP, &[0.0, 0.0, 1.0, 2.0]),
            Clip::new("tout", 0.0)
                .piste(0, channel::TRANSLATION, interp::STEP, &[0.0, 3.0, 0.0, 0.0])
                .piste(1, channel::TRANSLATION, interp::STEP, &[0.0, 0.0, 4.0, 0.0]),
            boucler,
        ],
        2,
    )
}

const AVANCER: u32 = 0;
const MONTER: u32 = 1;
const TOURNER: u32 = 2;
const PENCHER: u32 = 3;
const TOUT: u32 = 4;
const BOUCLER: u32 = 5;

fn pose(animateur: &mut Animator, clips: &ClipLibrary, maintenant: f64) -> Vec<Transform> {
    let mut sortie = repos();
    animateur.evaluate(clips, &repos(), maintenant, &mut sortie);
    sortie
}

fn proche(a: [f32; 3], b: [f32; 3], tolerance: f32) -> bool {
    (0..3).all(|i| (a[i] - b[i]).abs() <= tolerance)
}

fn proche_q(a: [f32; 4], b: [f32; 4]) -> bool {
    let d: f32 = (0..4).map(|i| a[i] * b[i]).sum();
    d.abs() > 1.0 - 1e-6
}

/// `q` autour de l'axe `z`, d'un angle donné.
fn autour_de_z(angle: f32) -> [f32; 4] {
    [0.0, 0.0, (angle / 2.0).sin(), (angle / 2.0).cos()]
}

#[test]
fn t362_le_crossfade_ne_fait_pas_sauter_la_pose() {
    let clips = clips();
    let mut animateur = Animator::new(2);
    animateur.play(&clips, AVANCER, 0, 1.0, 0.0, 0.0).unwrap();
    // À mi-course d'`avancer`, `monter` entre en une demi-seconde.
    animateur.play(&clips, MONTER, 0, 1.0, 0.5, 0.5).unwrap();

    let debut = pose(&mut animateur, &clips, 0.5)[0].translation;
    assert!(proche(debut, [0.5, 0.0, 0.0], 1e-6), "{debut:?}");
    // À mi-fondu, le sortant continue d'avancer : moitié de 0,75 en x, moitié de 5 en y.
    let milieu = pose(&mut animateur, &clips, 0.75)[0].translation;
    assert!(proche(milieu, [0.375, 2.5, 0.0], 1e-5), "{milieu:?}");
    let fin = pose(&mut animateur, &clips, 1.0)[0].translation;
    assert!(proche(fin, [0.0, 5.0, 0.0], 1e-6), "{fin:?}");

    // Aucun saut entre deux frames voisines, avant, pendant et après le fondu : la pose bouge au
    // plus de la vitesse du fondu, 10 blocs par seconde, fois le pas.
    let mut animateur = Animator::new(2);
    animateur.play(&clips, AVANCER, 0, 1.0, 0.0, 0.0).unwrap();
    let mut avant = pose(&mut animateur, &clips, 0.4)[0].translation;
    for pas in 401..=1100 {
        let maintenant = f64::from(pas) * 0.001;
        if pas == 500 {
            animateur
                .play(&clips, MONTER, 0, 1.0, 0.5, maintenant)
                .unwrap();
        }
        let ici = pose(&mut animateur, &clips, maintenant)[0].translation;
        assert!(
            proche(avant, ici, 0.0111),
            "saut à {maintenant} s : {avant:?} -> {ici:?}"
        );
        avant = ici;
    }
}

#[test]
fn t362_un_arret_fondu_rend_la_pose_de_dessous_sans_saut() {
    let clips = clips();
    let mut animateur = Animator::new(2);
    animateur.play(&clips, MONTER, 0, 1.0, 0.0, 0.0).unwrap();
    animateur.stop(0, 0.5, 1.0).unwrap();
    assert_eq!(animateur.playing(0), None);
    let tenu = |animateur: &mut Animator, t| pose(animateur, &clips, t)[0].translation;
    assert!(proche(tenu(&mut animateur, 1.0), [0.0, 5.0, 0.0], 1e-6));
    assert!(proche(tenu(&mut animateur, 1.25), [0.0, 2.5, 0.0], 1e-6));
    assert!(proche(tenu(&mut animateur, 1.5), [0.0, 0.0, 0.0], 1e-6));
    assert_eq!(
        animateur.active_tracks(&clips),
        0,
        "le fondu achevé ne compte plus"
    );
}

#[test]
fn t363_une_couche_additive_s_ajoute_a_la_pose_de_dessous() {
    let clips = clips();
    let mut animateur = Animator::new(2);
    animateur.play(&clips, TOURNER, 0, 1.0, 0.0, 0.0).unwrap();
    animateur.play(&clips, PENCHER, 1, 1.0, 0.0, 0.0).unwrap();
    let au_bout = pose(&mut animateur, &clips, 1.0);
    // La base tourne d'un quart de tour autour de z ; la couche additive y ajoute à droite son
    // écart au repos, un quart de tour autour de x.
    let base = autour_de_z(core::f32::consts::FRAC_PI_2);
    let ecart = [FRAC, 0.0, 0.0, FRAC];
    assert!(proche_q(
        au_bout[1].rotation,
        ax_anim::quat::mul(&base, &ecart)
    ));
    // La translation additive vaut 2 en z de plus que le repos.
    assert!(proche(au_bout[1].translation, [0.0, 1.0, 2.0], 1e-6));

    // À demi-poids, sur l'autre couche additive : la moitié de l'écart.
    let mut animateur = Animator::new(2);
    animateur.play(&clips, TOURNER, 0, 1.0, 0.0, 0.0).unwrap();
    animateur.play(&clips, PENCHER, 2, 0.5, 0.0, 0.0).unwrap();
    let moitie = pose(&mut animateur, &clips, 1.0);
    let huitieme = core::f32::consts::FRAC_PI_8;
    let demi_ecart = [huitieme.sin(), 0.0, 0.0, huitieme.cos()];
    assert!(proche_q(
        moitie[1].rotation,
        ax_anim::quat::mul(&base, &demi_ecart)
    ));
    assert!(proche(moitie[1].translation, [0.0, 1.0, 1.0], 1e-6));
    // Le node 0, que rien n'anime, reste au repos.
    assert_eq!(moitie[0], Transform::identity());
}

#[test]
fn t363_override_remplace_par_dessus_la_base() {
    let clips = clips();
    let mut animateur = Animator::new(2);
    animateur.play(&clips, AVANCER, 0, 1.0, 0.0, 0.0).unwrap();
    animateur.play(&clips, TOUT, 3, 0.5, 0.0, 0.0).unwrap();
    let ici = pose(&mut animateur, &clips, 1.0);
    // OVERRIDE à demi-poids : à mi-chemin entre la base (1, 0, 0) et sa cible (3, 0, 0).
    assert!(proche(ici[0].translation, [2.0, 0.0, 0.0], 1e-6));
    assert!(proche(ici[1].translation, [0.0, 2.5, 0.0], 1e-6));
}

#[test]
fn t364_le_masque_de_nodes_est_respecte() {
    let clips = clips();
    let mut animateur = Animator::new(2);
    animateur.set_mask(0, Some(&[1])).unwrap();
    animateur.play(&clips, TOUT, 0, 1.0, 0.0, 0.0).unwrap();
    let masque = pose(&mut animateur, &clips, 0.0);
    assert_eq!(
        masque[0],
        Transform::identity(),
        "le node 0 est hors du masque"
    );
    assert!(proche(masque[1].translation, [0.0, 4.0, 0.0], 1e-6));

    animateur.set_mask(0, None).unwrap();
    let libre = pose(&mut animateur, &clips, 0.0);
    assert!(proche(libre[0].translation, [3.0, 0.0, 0.0], 1e-6));
}

#[test]
fn un_clip_boucle_ou_tient_sa_derniere_pose() {
    let clips = clips();
    let mut animateur = Animator::new(2);
    animateur.play(&clips, BOUCLER, 0, 1.0, 0.0, 0.0).unwrap();
    let boucle = pose(&mut animateur, &clips, 2.25)[0].translation;
    assert!(proche(boucle, [0.25, 0.0, 0.0], 1e-6), "{boucle:?}");
    let mut animateur = Animator::new(2);
    animateur.play(&clips, AVANCER, 0, 1.0, 0.0, 0.0).unwrap();
    let tenu = pose(&mut animateur, &clips, 2.25)[0].translation;
    assert!(proche(tenu, [1.0, 0.0, 0.0], 1e-6), "{tenu:?}");
    assert_eq!(
        animateur.playing(0),
        Some(AVANCER),
        "un clip achevé reste en place"
    );
}

#[test]
fn changer_de_vitesse_ne_fait_pas_sauter_le_temps() {
    let clips = clips();
    let mut animateur = Animator::new(2);
    animateur.play(&clips, AVANCER, 0, 1.0, 0.0, 0.0).unwrap();
    animateur.set_speed(0, 2.0, 0.25).unwrap();
    let x = |animateur: &mut Animator, t| pose(animateur, &clips, t)[0].translation;
    assert!(proche(x(&mut animateur, 0.25), [0.25, 0.0, 0.0], 1e-6));
    assert!(proche(x(&mut animateur, 0.5), [0.75, 0.0, 0.0], 1e-6));
    animateur.set_speed(0, 0.0, 0.5).unwrap();
    assert!(proche(x(&mut animateur, 9.0), [0.75, 0.0, 0.0], 1e-6));
}

#[test]
fn les_commandes_hors_bornes_sont_refusees_sans_rien_changer() {
    let clips = clips();
    let mut animateur = Animator::new(2);
    assert_eq!(
        animateur.play(&clips, 99, 0, 1.0, 0.0, 0.0),
        Err(AnimError::UnknownClip)
    );
    assert_eq!(
        animateur.play(&clips, AVANCER, 4, 1.0, 0.0, 0.0),
        Err(AnimError::LayerOutOfRange)
    );
    assert_eq!(
        animateur.play(&clips, AVANCER, 0, 1.5, 0.0, 0.0),
        Err(AnimError::InvalidParameter)
    );
    assert_eq!(
        animateur.play(&clips, AVANCER, 0, 1.0, -1.0, 0.0),
        Err(AnimError::InvalidParameter)
    );
    assert_eq!(
        animateur.set_speed(0, f32::NAN, 0.0),
        Err(AnimError::InvalidParameter)
    );
    assert_eq!(animateur.stop(7, 0.0, 0.0), Err(AnimError::LayerOutOfRange));
    assert_eq!(animateur.active_tracks(&clips), 0);
}

#[test]
fn r200_au_dela_de_256_pistes_actives_le_fondu_est_coupe_puis_la_lecture_refusee() {
    let piste = [0.0, 1.0, 2.0, 3.0];
    let gros = |nom, pistes: usize| {
        (0..pistes).fold(Clip::new(nom, 0.0), |clip, _| {
            clip.piste(0, channel::TRANSLATION, interp::STEP, &piste)
        })
    };
    let clips = bibliotheque(
        vec![
            gros("deux_cents", 200),
            gros("cinquante", 50),
            gros("cent", 100),
        ],
        1,
    );
    let mut animateur = Animator::new(1);
    animateur.play(&clips, 0, 0, 1.0, 0.0, 0.0).unwrap();

    // 200 + 50 : le fondu tient.
    animateur.play(&clips, 1, 0, 1.0, 0.5, 1.0).unwrap();
    assert_eq!(animateur.active_tracks(&clips), 250);
    // 250 + 100 : trop, même sur une autre couche ; refusé, rien ne change.
    assert_eq!(
        animateur.play(&clips, 2, 1, 1.0, 0.0, 1.0),
        Err(AnimError::TooManyTracks { active: 350 })
    );
    assert_eq!(animateur.playing(1), None);
    assert_eq!(animateur.active_tracks(&clips), 250);

    // Le fondu achevé, 200 + 100 avec fondu dépasserait : le fondu est coupé.
    animateur.play(&clips, 0, 0, 1.0, 0.0, 2.0).unwrap();
    animateur.play(&clips, 2, 0, 1.0, 0.5, 2.0).unwrap();
    assert_eq!(animateur.playing(0), Some(2));
    assert_eq!(animateur.active_tracks(&clips), 100);
}
