//! Un corps **au repos** que l'eau gagne (R-642) : de l'eau qui s'écoule, de hauteurs
//! inégales d'une colonne à l'autre, ne doit pas le faire tourner sans fin.
//!
//! Relevé en CI le 2026-10-12 (trace de 6cdc2ec) : un cube posé, de la densité de l'eau,
//! gagné par l'eau d'un aquifère, a vu sa vitesse angulaire croître d'un facteur 2,5 par
//! tick jusqu'à 47 rad/s, puis a traversé le terrain.

use ax_math::{Quat, Vec3};
use ax_physics::{
    BodyCollider, BodyId, BodyKind, ContactMaterial, FluidVolume, PhysicsConfig, PhysicsWorld,
    Shape,
};

const TICK: f32 = 1.0 / 20.0;

fn cuboid(world: &mut PhysicsWorld, kind: BodyKind, center: Vec3, half: [f32; 3]) -> BodyId {
    world
        .add_assembly(
            kind,
            center,
            Quat::IDENTITY,
            &[BodyCollider {
                shape: Shape::Cuboid { half_extents: half },
                density: 1000.0,
                material: ContactMaterial::default(),
                translation: Vec3::ZERO,
                rotation: Quat::IDENTITY,
            }],
        )
        .expect("une boîte est une forme valide")
}

/// Une colonne d'eau sur le bloc `(x, z)`, du sol à la hauteur `h`.
fn colonne(x: f32, z: f32, h: f32) -> FluidVolume {
    FluidVolume {
        min: Vec3::new(x, 0.0, z),
        max: Vec3::new(x + 1.0, h, z + 1.0),
        density: 1000.0,
    }
}

/// Un cube posé sur un sol, légèrement hors de la grille, que gagne une eau de hauteurs
/// inégales sur les neuf colonnes qui l'entourent (en neuvièmes de bloc, 9 : pleine) —
/// une configuration qui l'emballait avant la correction, trouvée par balayage.
fn cube_gagne_par_l_eau() -> (PhysicsWorld, BodyId) {
    let mut world =
        PhysicsWorld::new(PhysicsConfig::new(1.0 / 60.0, 4).expect("configuration valide"));
    cuboid(
        &mut world,
        BodyKind::Static,
        Vec3::new(0.5, -0.5, 0.5),
        [8.0, 0.5, 8.0],
    );
    let cube = cuboid(
        &mut world,
        BodyKind::Dynamic,
        Vec3::new(0.66, 0.5, 0.44),
        [0.5, 0.5, 0.5],
    );
    // Il se pose, au sec.
    for _ in 0..40 {
        world.advance(TICK);
    }
    let hauteurs = [3u8, 5, 7, 1, 2, 6, 9, 9, 9];
    let mut eau = Vec::new();
    let mut colonnes = hauteurs.iter();
    for x in -1..=1 {
        for z in -1..=1 {
            let n = *colonnes.next().expect("neuf colonnes");
            let h = if n == 9 { 1.0 } else { f32::from(n) / 9.0 };
            if n > 0 {
                eau.push(colonne(x as f32, z as f32, h));
            }
        }
    }
    world.set_fluid_volumes([0, 0, 0], eau);
    // En jeu, la tuile rebâtie par l'eau qui arrive réveille le corps (trace de la CI : drapeau
    // SLEEPING levé, IN_FLUID posé) ; une impulsion infime vers le bas fait de même ici
    // (une impulsion nulle ne réveille rien dans rapier).
    world.apply_impulse(cube, Vec3::new(0.0, -1.0e-3, 0.0), Vec3::ZERO, false);
    assert_eq!(world.is_sleeping(cube), Some(false));
    (world, cube)
}

#[test]
fn un_cube_au_repos_que_l_eau_gagne_ne_s_emballe_pas() {
    let (mut world, cube) = cube_gagne_par_l_eau();
    let mut pire = 0.0f32;
    for _ in 0..200 {
        world.advance(TICK);
        pire = pire.max(
            world
                .angular_velocity(cube)
                .expect("le corps existe")
                .length(),
        );
    }
    let pose = world.pose(cube).expect("le corps existe");
    assert!(
        pire < 5.0,
        "vitesse angulaire bornée attendue, pire {pire} rad/s"
    );
    assert!(
        pose.translation.y > 0.0,
        "le cube reste au-dessus du sol, y = {}",
        pose.translation.y
    );
}
