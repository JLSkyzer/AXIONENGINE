// Added for AXION ENGINE (ADR-152, ADR-153, 2026-10-25): P4. See third_party/rapier3d/AXION.md.
//! A fixed body that has just been inserted did not move: it does not wake the dynamic body it is
//! jointed to. A fixed body that does move still wakes it.

use rapier3d::prelude::*;

struct World {
    bodies: RigidBodySet,
    colliders: ColliderSet,
    impulse_joints: ImpulseJointSet,
    multibody_joints: MultibodyJointSet,
    pipeline: PhysicsPipeline,
    islands: IslandManager,
    broad_phase: DefaultBroadPhase,
    narrow_phase: NarrowPhase,
    ccd: CCDSolver,
    params: IntegrationParameters,
}

impl World {
    fn new() -> Self {
        Self {
            bodies: RigidBodySet::new(),
            colliders: ColliderSet::new(),
            impulse_joints: ImpulseJointSet::new(),
            multibody_joints: MultibodyJointSet::new(),
            pipeline: PhysicsPipeline::new(),
            islands: IslandManager::new(),
            broad_phase: DefaultBroadPhase::new(),
            narrow_phase: NarrowPhase::new(),
            ccd: CCDSolver::new(),
            params: IntegrationParameters::default(),
        }
    }

    fn step(&mut self) {
        self.pipeline.step(
            Vector::ZERO,
            &self.params,
            &mut self.islands,
            &mut self.broad_phase,
            &mut self.narrow_phase,
            &mut self.bodies,
            &mut self.colliders,
            &mut self.impulse_joints,
            &mut self.multibody_joints,
            &mut self.ccd,
            &(),
            &(),
        );
    }

    /// A fixed anchor and a dynamic body two units away, on a revolute joint inserted without
    /// waking anything.
    fn hinge(&mut self) -> (RigidBodyHandle, RigidBodyHandle) {
        let anchor = self.bodies.insert(RigidBodyBuilder::fixed());
        self.colliders
            .insert_with_parent(ColliderBuilder::cuboid(0.5, 0.5, 0.5), anchor, &mut self.bodies);
        let door = self
            .bodies
            .insert(RigidBodyBuilder::dynamic().translation(Vector::new(2.0, 0.0, 0.0)));
        self.colliders
            .insert_with_parent(ColliderBuilder::cuboid(0.5, 0.5, 0.5), door, &mut self.bodies);
        let joint = RevoluteJointBuilder::new(Vector::X)
            .local_anchor1(Vector::new(1.0, 0.0, 0.0))
            .local_anchor2(Vector::new(-1.0, 0.0, 0.0));
        self.impulse_joints.insert(anchor, door, joint, false);
        (anchor, door)
    }
}

#[test]
fn a_freshly_inserted_fixed_body_does_not_wake_its_joint_partner() {
    let mut world = World::new();
    let (_, door) = world.hinge();
    world.bodies.get_mut(door).unwrap().sleep();
    world.step();
    assert!(
        world.bodies.get(door).unwrap().is_sleeping(),
        "the fixed anchor was only inserted"
    );
}

#[test]
fn a_moved_fixed_body_still_wakes_its_joint_partner() {
    let mut world = World::new();
    let (anchor, door) = world.hinge();
    world.step();
    world.bodies.get_mut(door).unwrap().sleep();
    world.step();
    assert!(world.bodies.get(door).unwrap().is_sleeping());
    world
        .bodies
        .get_mut(anchor)
        .unwrap()
        .set_translation(Vector::new(0.0, 1.0, 0.0), true);
    world.step();
    assert!(
        !world.bodies.get(door).unwrap().is_sleeping(),
        "moving the fixed anchor wakes the body jointed to it"
    );
}
