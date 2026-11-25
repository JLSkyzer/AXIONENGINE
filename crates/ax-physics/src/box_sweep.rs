//! Le balayage d'une boîte contre les colliders posés d'une definition (ADR-178 D1, CAP-14).
//!
//! La collision d'un joueur, ou de toute entité de Minecraft, avec une assembly : sa boîte — alignée sur
//! le monde, comme celle de vanilla — se déplace d'un vecteur, et la première chose qu'elle heurte
//! l'arrête. Sans état : les colliders sont ceux de la definition compilée, **posés** par l'appelant —
//! le serveur à la pose de son dernier tick, le client à celle qu'il dessine —, si bien qu'aucun monde
//! physique n'est consulté et que les deux côtés posent la même question.
//!
//! La requête ne mute rien et ne dépend d'aucun monde (R-650). Les coordonnées du monde sont en
//! double ; le calcul se fait en simple précision **autour du centre de la boîte**, où tout est petit.

use crate::body::{BodyCollider, BodyError, Shape};
use ax_math::{DVec3, Quat, Vec3};
use rapier3d::parry::query::{self, ShapeCastOptions};
use rapier3d::prelude::{Pose as RapierPose, SharedShape};

/// Un collider prêt à servir : sa forme construite, et sa pose dans le repère de son corps.
#[derive(Debug, Clone)]
struct PreparedCollider {
    shape: SharedShape,
    translation: Vec3,
    rotation: Quat,
}

/// Les colliders d'un corps, prêts à servir.
#[derive(Debug, Clone, Default)]
pub struct PreparedBody {
    colliders: Vec<PreparedCollider>,
}

impl PreparedBody {
    /// Construit les formes des colliders d'un corps, dans son repère. Un collider dont la forme est
    /// invalide (une enveloppe dégénérée) est écarté : la compilation de la definition l'a déjà refusé.
    ///
    /// # Errors
    ///
    /// Aucune : les formes invalides sont écartées, la liste peut en être vide.
    #[must_use]
    pub fn new(colliders: &[BodyCollider]) -> Self {
        let colliders = colliders
            .iter()
            .filter_map(|collider| {
                let shape = prepared_shape(&collider.shape).ok()?;
                Some(PreparedCollider {
                    shape,
                    translation: collider.translation,
                    rotation: collider.rotation,
                })
            })
            .collect();
        Self { colliders }
    }

    /// Le nombre de colliders utilisables.
    #[must_use]
    pub fn len(&self) -> usize {
        self.colliders.len()
    }

    /// Vrai si le corps n'a aucun collider utilisable.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.colliders.is_empty()
    }
}

fn prepared_shape(shape: &Shape) -> Result<SharedShape, BodyError> {
    crate::world::shared_shape_of(shape)
}

/// La pose monde d'un corps : sa position, en double, et sa rotation.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct BodyPose {
    /// Position monde du repère du corps, en blocs.
    pub position: DVec3,
    /// Rotation du repère du corps.
    pub rotation: Quat,
}

/// Ce que heurte un balayage : le corps, la part du déplacement parcourue, la normale.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct BoxHit {
    /// Le rang du corps heurté, parmi ceux qu'on a posés.
    pub body: usize,
    /// La part du déplacement que la boîte parcourt avant le contact : `0` si elle touche déjà, `1`
    /// au plus.
    pub fraction: f32,
    /// La normale de la surface heurtée, unitaire, qui part du collider vers la boîte.
    pub normal: Vec3,
}

/// Ce qui empêche la boîte d'être là où elle est : le corps, la direction et la profondeur de la sortie.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct BoxPenetration {
    /// Le rang du corps où la boîte s'enfonce le plus.
    pub body: usize,
    /// La direction de la plus courte sortie, unitaire, du collider vers la boîte.
    pub normal: Vec3,
    /// La profondeur, en blocs, strictement positive.
    pub depth: f32,
}

/// La pose d'un collider autour de `origin` : la position relative en simple précision, la rotation.
fn placed(pose: &BodyPose, collider: &PreparedCollider, origin: DVec3) -> RapierPose {
    let world = pose.position + DVec3::from(pose.rotation * collider.translation);
    let relative = world - origin;
    #[allow(clippy::cast_possible_truncation)]
    let relative = Vec3::new(relative.x as f32, relative.y as f32, relative.z as f32);
    RapierPose::from_parts(relative, pose.rotation * collider.rotation)
}

/// La boîte demi-dimensions, centrée sur l'origine du calcul ; `None` si elle est vide ou infinie.
fn box_shape(min: DVec3, max: DVec3) -> Option<(SharedShape, DVec3)> {
    if !min.is_finite() || !max.is_finite() || !min.cmplt(max).all() {
        return None;
    }
    let center = (min + max) * 0.5;
    let half = (max - min) * 0.5;
    #[allow(clippy::cast_possible_truncation)]
    let shape = SharedShape::cuboid(half.x as f32, half.y as f32, half.z as f32);
    Some((shape, center))
}

/// Balaie la boîte `[min, max]` du vecteur `motion` contre les colliders des corps posés, et rend le
/// premier contact.
///
/// Un contact **qui s'éloigne** déjà au départ n'en est pas un : une boîte posée sur un sol qui s'y
/// enfonce d'un rien, et qui glisse, n'est pas arrêtée. `None` sans contact, si le déplacement est
/// nul, ou si la boîte est vide.
///
/// # Panics
///
/// Jamais : `bodies` et `poses` de longueurs différentes ne se lisent que jusqu'au plus court.
#[must_use]
pub fn sweep_box(
    bodies: &[PreparedBody],
    poses: &[BodyPose],
    min: DVec3,
    max: DVec3,
    motion: Vec3,
) -> Option<BoxHit> {
    if !motion.is_finite() || motion.length_squared() == 0.0 {
        return None;
    }
    let (cuboid, center) = box_shape(min, max)?;
    let box_pose = RapierPose::IDENTITY;
    let mut best: Option<BoxHit> = None;
    for (rank, (body, pose)) in bodies.iter().zip(poses).enumerate() {
        for collider in &body.colliders {
            let at = placed(pose, collider, center);
            let options = ShapeCastOptions {
                max_time_of_impact: 1.0,
                target_distance: 0.0,
                stop_at_penetration: false,
                compute_impact_geometry_on_penetration: true,
            };
            let Ok(Some(hit)) = query::cast_shapes(
                &at,
                Vec3::ZERO,
                collider.shape.as_ref(),
                &box_pose,
                motion,
                cuboid.as_ref(),
                options,
            ) else {
                continue;
            };
            if best.is_none_or(|current| hit.time_of_impact < current.fraction) {
                // La normale vient dans le repère du collider : le monde la voit tournée comme lui.
                let normal = (at.rotation * hit.normal1).normalize_or_zero();
                best = Some(BoxHit {
                    body: rank,
                    fraction: hit.time_of_impact.clamp(0.0, 1.0),
                    normal,
                });
            }
        }
    }
    best
}

/// L'enfoncement le plus profond de la boîte `[min, max]` dans les colliders des corps posés, ou
/// `None` si elle n'en touche aucun. Ce qui la ramène dehors : `normal × depth`.
#[must_use]
pub fn penetration(
    bodies: &[PreparedBody],
    poses: &[BodyPose],
    min: DVec3,
    max: DVec3,
) -> Option<BoxPenetration> {
    let (cuboid, center) = box_shape(min, max)?;
    let box_pose = RapierPose::IDENTITY;
    let mut deepest: Option<BoxPenetration> = None;
    for (rank, (body, pose)) in bodies.iter().zip(poses).enumerate() {
        for collider in &body.colliders {
            let at = placed(pose, collider, center);
            let Ok(Some(contact)) = query::contact(
                &at,
                collider.shape.as_ref(),
                &box_pose,
                cuboid.as_ref(),
                0.0,
            ) else {
                continue;
            };
            let depth = -contact.dist;
            if depth > 0.0 && deepest.is_none_or(|current| depth > current.depth) {
                deepest = Some(BoxPenetration {
                    body: rank,
                    normal: contact.normal1.normalize_or_zero(),
                    depth,
                });
            }
        }
    }
    deepest
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cuboid(half: [f32; 3]) -> BodyCollider {
        BodyCollider {
            shape: Shape::Cuboid { half_extents: half },
            density: 1000.0,
            material: crate::body::ContactMaterial::default(),
            translation: Vec3::ZERO,
            rotation: Quat::IDENTITY,
        }
    }

    fn body(colliders: &[BodyCollider]) -> PreparedBody {
        PreparedBody::new(colliders)
    }

    fn at(x: f64, y: f64, z: f64) -> BodyPose {
        BodyPose {
            position: DVec3::new(x, y, z),
            rotation: Quat::IDENTITY,
        }
    }

    /// Un joueur : 0,6 de large, 1,8 de haut, les pieds en `(x, y, z)`.
    fn player(x: f64, y: f64, z: f64) -> (DVec3, DVec3) {
        (
            DVec3::new(x - 0.3, y, z - 0.3),
            DVec3::new(x + 0.3, y + 1.8, z + 0.3),
        )
    }

    #[test]
    fn t_adr178_une_boite_qui_tombe_sur_un_plateau_s_arrete_sur_sa_face() {
        // Un plateau de 4 × 0,5 × 4, dessus à y = 10 ; le joueur, pieds à 12, tombe de 3.
        let platform = [body(&[cuboid([2.0, 0.25, 2.0])])];
        let poses = [at(0.0, 9.75, 0.0)];
        let (min, max) = player(0.0, 12.0, 0.0);
        let hit =
            sweep_box(&platform, &poses, min, max, Vec3::new(0.0, -3.0, 0.0)).expect("contact");
        assert_eq!(hit.body, 0);
        assert!(
            (hit.fraction - 2.0 / 3.0).abs() < 1e-4,
            "fraction {}",
            hit.fraction
        );
        assert!(
            (hit.normal - Vec3::Y).length() < 1e-4,
            "normale {:?}",
            hit.normal
        );
    }

    #[test]
    fn t_adr178_un_mur_arrete_la_marche_et_sa_normale_dit_de_quel_cote() {
        let wall = [body(&[cuboid([0.25, 1.0, 2.0])])];
        let poses = [at(5.0, 1.0, 0.0)];
        let (min, max) = player(3.0, 0.0, 0.0);
        let hit = sweep_box(&wall, &poses, min, max, Vec3::new(2.0, 0.0, 0.0)).expect("mur");
        // Le joueur touche le mur quand son bord est à x = 4,75, soit un centre à 4,45 : 1,45 sur 2.
        assert!(
            (hit.fraction - 0.725).abs() < 1e-3,
            "fraction {}",
            hit.fraction
        );
        assert!((hit.normal - Vec3::NEG_X).length() < 1e-4);
    }

    #[test]
    fn t_adr178_rien_n_est_heurte_hors_du_chemin_ou_sans_deplacement() {
        let platform = [body(&[cuboid([2.0, 0.25, 2.0])])];
        let poses = [at(0.0, 0.0, 0.0)];
        let (min, max) = player(10.0, 5.0, 0.0);
        assert!(sweep_box(&platform, &poses, min, max, Vec3::new(0.0, -10.0, 0.0)).is_none());
        let (min, max) = player(0.0, 5.0, 0.0);
        assert!(sweep_box(&platform, &poses, min, max, Vec3::ZERO).is_none());
        assert!(
            sweep_box(&platform, &poses, max, min, Vec3::NEG_Y).is_none(),
            "boîte vide"
        );
    }

    #[test]
    fn t_adr178_une_boite_posee_sur_le_sol_qui_glisse_n_est_pas_arretee() {
        // Dessus du plateau à y = 0 ; les pieds à -0,001 : un enfoncement d'un millième. Elle avance de côté.
        let platform = [body(&[cuboid([5.0, 0.25, 5.0])])];
        let poses = [at(0.0, -0.25, 0.0)];
        let (min, max) = player(0.0, -0.001, 0.0);
        let glisse = sweep_box(&platform, &poses, min, max, Vec3::new(0.2, 0.0, 0.0));
        assert!(glisse.is_none(), "{glisse:?}");
        // Mais une boîte qui s'enfonce davantage est arrêtée tout de suite.
        let enfonce =
            sweep_box(&platform, &poses, min, max, Vec3::new(0.0, -0.5, 0.0)).expect("sol");
        assert!(enfonce.fraction < 0.01, "{}", enfonce.fraction);
        assert!((enfonce.normal - Vec3::Y).length() < 1e-3);
    }

    #[test]
    fn t_adr178_une_rampe_tournee_a_une_normale_inclinee() {
        // Un plateau tourné de 30° autour de z : sa face du dessus penche, la normale avec elle.
        let platform = [body(&[cuboid([3.0, 0.25, 2.0])])];
        let tilt = Quat::from_rotation_z(30f32.to_radians());
        let poses = [BodyPose {
            position: DVec3::new(0.0, 0.0, 0.0),
            rotation: tilt,
        }];
        let (min, max) = player(0.0, 3.0, 0.0);
        let hit = sweep_box(&platform, &poses, min, max, Vec3::new(0.0, -4.0, 0.0)).expect("rampe");
        let attendu = tilt * Vec3::Y;
        assert!(
            (hit.normal - attendu).length() < 1e-3,
            "{:?} / {:?}",
            hit.normal,
            attendu
        );
        assert!(hit.fraction > 0.0 && hit.fraction < 1.0);
    }

    #[test]
    fn t_adr178_loin_de_l_origine_la_precision_tient() {
        // À 2 000 000 blocs de l'origine, un plateau et un joueur posés au millimètre : le contact y est
        // où il serait près de l'origine (le calcul est fait autour de la boîte).
        let platform = [body(&[cuboid([2.0, 0.25, 2.0])])];
        let x = 2_000_000.0;
        let poses = [at(x, 9.75, -x)];
        let (min, max) = player(x, 12.0, -x);
        let hit =
            sweep_box(&platform, &poses, min, max, Vec3::new(0.0, -3.0, 0.0)).expect("contact");
        assert!((hit.fraction - 2.0 / 3.0).abs() < 1e-4, "{}", hit.fraction);
    }

    #[test]
    fn t_adr178_un_collider_decale_dans_son_corps_est_pose_avec_lui() {
        let mut shifted = cuboid([0.5, 0.5, 0.5]);
        shifted.translation = Vec3::new(3.0, 0.0, 0.0);
        let platform = [body(&[shifted])];
        // Le corps est à l'origine ; son collider est à x = 3.
        let poses = [at(0.0, 0.0, 0.0)];
        let (min, max) = player(3.0, 4.0, 0.0);
        let hit = sweep_box(&platform, &poses, min, max, Vec3::new(0.0, -6.0, 0.0))
            .expect("collider décalé");
        assert!(
            (hit.fraction - (4.0 - 0.5) / 6.0).abs() < 1e-3,
            "{}",
            hit.fraction
        );
        // Un corps tourné d'un demi-tour autour de y porte son collider à x = -3.
        let tourne = [BodyPose {
            position: DVec3::ZERO,
            rotation: Quat::from_rotation_y(std::f32::consts::PI),
        }];
        assert!(sweep_box(&platform, &tourne, min, max, Vec3::new(0.0, -6.0, 0.0)).is_none());
        let (min, max) = player(-3.0, 4.0, 0.0);
        assert!(sweep_box(&platform, &tourne, min, max, Vec3::new(0.0, -6.0, 0.0)).is_some());
    }

    #[test]
    fn t_adr178_une_enveloppe_convexe_et_un_compose_se_balaient() {
        let hull = BodyCollider {
            shape: Shape::ConvexHull {
                points: vec![
                    [-1.0, 0.0, -1.0],
                    [1.0, 0.0, -1.0],
                    [1.0, 0.0, 1.0],
                    [-1.0, 0.0, 1.0],
                    [0.0, 1.0, 0.0],
                ],
            },
            density: 1000.0,
            material: crate::body::ContactMaterial::default(),
            translation: Vec3::ZERO,
            rotation: Quat::IDENTITY,
        };
        let prepared = [body(&[hull])];
        assert_eq!(prepared[0].len(), 1);
        let poses = [at(0.0, 0.0, 0.0)];
        let (min, max) = player(0.0, 4.0, 0.0);
        let hit =
            sweep_box(&prepared, &poses, min, max, Vec3::new(0.0, -5.0, 0.0)).expect("pyramide");
        // La pointe est à y = 1.
        assert!(
            hit.fraction > 0.55 && hit.fraction < 0.65,
            "{}",
            hit.fraction
        );
    }

    #[test]
    fn t_adr178_la_penetration_dit_par_ou_sortir_et_de_combien() {
        let platform = [body(&[cuboid([2.0, 0.25, 2.0])])];
        let poses = [at(0.0, 0.0, 0.0)];
        // Dessus du plateau à y = 0,25 ; les pieds à 0,05 : enfoncé de 0,2.
        let (min, max) = player(0.0, 0.05, 0.0);
        let inside = penetration(&platform, &poses, min, max).expect("enfoncée");
        assert!((inside.depth - 0.2).abs() < 1e-3, "{}", inside.depth);
        assert!(
            (inside.normal - Vec3::Y).length() < 1e-3,
            "{:?}",
            inside.normal
        );
        let (min, max) = player(0.0, 1.0, 0.0);
        assert!(penetration(&platform, &poses, min, max).is_none());
    }

    #[test]
    fn t_adr178_un_corps_sans_collider_valide_ne_heurte_rien() {
        let degenerate = BodyCollider {
            shape: Shape::ConvexHull {
                points: vec![[0.0; 3]; 4],
            },
            density: 1000.0,
            material: crate::body::ContactMaterial::default(),
            translation: Vec3::ZERO,
            rotation: Quat::IDENTITY,
        };
        let prepared = [body(&[degenerate])];
        assert!(prepared[0].is_empty());
        let poses = [at(0.0, 0.0, 0.0)];
        let (min, max) = player(0.0, 5.0, 0.0);
        assert!(sweep_box(&prepared, &poses, min, max, Vec3::new(0.0, -9.0, 0.0)).is_none());
    }
}
