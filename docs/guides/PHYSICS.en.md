# Physics

What AXION ENGINE's physics does today (milestone M3, and the joints of M4), and how a pack drives
it. The normative reference is still the specification (PART 10, sections 5.23 to 5.32); the
implementation choices are in the cited ADRs. Options are set in `axion-common.toml`: see
[CONFIGURATION.md](CONFIGURATION.md), sections `[sim]`, `[physics]`, `[world]` and `[budgets]`.

## The model

- **Engine**: [`rapier3d`](https://rapier.rs) (ADR-002), in the `ax-physics` crate. Its types do
  not leave the crate (R-1753). The crate's rustdoc gives a map of its modules.
- **Server authority.** The simulation runs on the server; the client receives the states and
  interpolates them, without ever integrating itself (INV-02).
- **One world per dimension**, created when first needed, destroyed when no assembly and no player
  occupies it any more (R-610). World positions are in double precision; each world simulates in
  single precision around a floating origin (R-462).
- **Fixed step** `sim.fixed_dt` (1/60 s by default), at most `sim.max_substeps` substeps per tick.
  Any lag beyond that is dropped, never caught up: no spiral (R-990).
- **Reproducible** on the same machine and the same binary (R-1020), not from one machine to
  another (ADR-005). The bit-exact damage chain is a separate topic (C-16, M6).

## The Minecraft world

The blocks around each assembly become **collision tiles**, one per 16³ section (C-38): merged
boxes, or a heightfield for regular terrain, with at most `world.tiles_per_tick` tiles extracted per
tick within a radius of `world.tile_radius` tiles. Friction and bounce come from the section's
dominant physical material, resolved by the mods' `axion/block_materials/` mappings — by block, then
by tag, then the default (§11.4, ADR-145); materials and their format are described in
[MATERIALS.md](MATERIALS.md). Fluid blocks become fluid volumes, at the density of water whatever
the fluid, for now: a body that enters one is subject to buoyancy and drag.

**Vanilla entities** near an assembly — those Minecraft considers solid or pushable, alive and not
spectators, players included — are represented by kinematic proxies (a capsule for a living
creature, a box otherwise), recomputed every tick (R-614, ADR-123). An impact against an assembly
pushes the entity, except players (`physics.entity_push`), and hurts a living entity like a fall at
the same speed (`physics.entity_damage`).

## The `physics` block of a definition

```json
"physics": {
  "mass": 1650.0,
  "center_of_mass": [0, 0.45, -0.1],
  "linear_damping": 0.05,
  "angular_damping": 0.3,
  "ccd": true,
  "collision_group": "assembly",
  "collides_with": ["world", "assembly", "entity_proxy", "debris"]
}
```

| Field | If not declared | Effect |
|---|---|---|
| `mass` | computed: density × volume of each collider | the body's mass, in kg (0.001 to 10⁶); the inertia follows, with the same distribution |
| `center_of_mass` | computed from the colliders | centre of mass in the model's frame; the inertia is moved to it |
| `linear_damping`, `angular_damping` | `rapier`'s | damping, positive or zero |
| `ccd` | automatic (see below) | continuous detection always on |
| `collision_group` | `assembly` | group the body is a member of |
| `collides_with` | the group's default mask, otherwise all | groups the body collides with; `[]` collides with none |

The body description crosses the boundary along with its creation (DM-08, ADR-130). Two bodies
interact only if each one is a member of a group the other collides with. The eight reserved groups
— `world`, `assembly`, `part`, `debris`, `entity_proxy`, `sensor`, `particle`, `debug`
— have bits 0 to 7; world tiles are members of `world` and filter nothing, entity proxies are
members of `entity_proxy` and only collide with `assembly`.

A pack declares its own groups, one per file (registry `axion:collision_group`, ADR-132
A1): `axion/collision_groups/<path>.json`, in the folder a mod registers, declares `<modId>:<path>`, and its only
field, `collides_with`, optional, is the group's default mask — the one a definition gets when it
names the group without `collides_with`. Third-party groups, sorted by identifier, have
bits 8 to 31, 24 at most; beyond that, or if it names an unknown group, a group is refused and
named in the log. Groups load before definitions: a definition that names an unknown group is
refused, with its path. A body keeps the filter set at its creation; a reload that changes the
registry says so in the log.

The collider shapes of a dynamic body are primitives, convex hulls of 4 to 256 points or compounds
of at most 64 shapes; never a concave mesh (INV-13).

## Bodies and joints

With schema 2 (`"schema": 2`, full format in
[`docs/schema/definition-2.json`](../schema/definition-2.json)), a definition can split its
assembly into several bodies linked by joints (C-34, [ADR-151](../decisions/ADR-151.md)). The
test door, which [`tools/assets/joints_essai.py`](../../tools/assets/joints_essai.py) generates along
with a pendulum, a chain and a grid, gives an example:

```json
"kind": "static",
"bodies": [{ "name": "montant", "root_node": "montant" }],
"joints": [{
  "name": "gond", "type": "revolute", "a": "montant", "b_node": "battant",
  "anchor": [-0.5, 0.0, 0.0], "axis": [0, 1, 0], "limits": [0.0, 1.6],
  "motor": { "target": 1.2, "stiffness": 4000, "damping": 400, "max_force": 5000 },
  "jam": { "curve": [[0.5, 0.0]], "friction": 50 }
}]
```

### Bodies

Each entry of `bodies[]` creates a body at the asset's `root_node` node; a joint's `b_node` creates
one at that node if it is not already the root of a body. An assembly has at most 64 bodies;
without `bodies` or `joints`, it stays a single body.

- The first declared body is the **root body**: its pose is the entity's, and it takes the
  definition's `kind` — the door's post is fixed. The other bodies are dynamic.
- A node belongs to the body it is the root of, otherwise to that of its closest ancestor that is
  one, and failing that to the root body. Its colliders and meshes follow that body. Each body
  carries at least one collider.
- `physics.mass` is still the assembly's mass, split among the bodies according to the mass their
  densities give them. `bodies[].mass` sets one body's mass, the others sharing the rest; declared
  masses that reach the assembly's mass get the definition refused.
- The bodies of an assembly do not touch each other, unless `physics.self_collision: true`; even
  then, two bodies linked by a joint only touch if that joint declares `collide: true`.
- The assembly is still the unit of activity: the simulation radius, the active body cap — which
  counts each of its bodies — and the safeguards act on all its bodies at once.

### Joints

`a` names a declared body, `b_node` the root node of the other. `anchor` is the joint's point in the
frame of `b_node`, its origin by default; `axis` is its axis in that same frame. Both frames are
taken at the asset's rest pose: the joint moves nothing at spawn. Units: blocks (metres), radians,
newtons, newton-metres, joules.

| `type` | What it leaves free | Its settings |
|---|---|---|
| `fixed` | nothing: it welds | — |
| `revolute` | rotation around `axis` | `axis` (required), `limits` in radians, `motor`, `friction`, `jam` |
| `prismatic` | translation along `axis` | `axis` (required), `limits` in blocks, `motor`, `friction`, `jam` |
| `spherical` | the three rotations around the anchor | `friction` |
| `generic` | what its `axes` say | `axes` (required), `friction` |
| `rope` | everything, its two anchors at most `max_length` from each other | `max_length` (required) |
| `spring` | everything, its two anchors pulled back to `rest_length` from each other | `rest_length` and `stiffness` (required), `damping` |

All types accept `break` and `collide`.

- **`axes`** (`generic`): six entries in the frame of `b_node` — linear x, y, z, then angular x, y,
  z — each `locked`, `free` or `limited` with its `limits`.
- **`motor`**: `speed` alone makes a velocity motor; `target` with `stiffness`, and `damping`, a
  position servo. `max_force` caps its effort.
- **`friction`**: internal friction, which slows the free axis without locking it or hindering the
  motor.
- **`break`**: breaking thresholds in `force`, `torque` and `energy`; the first one crossed breaks
  the joint, which emits `JOINT_BROKEN`.
- **`jam`**, jamming, on a joint that declares `limits`: `curve` gives, as a function of the
  anchor's misalignment in blocks (positive, strictly increasing abscissas), the fraction of the
  range that remains, from 1 to 0. The range narrows around the current position, and the friction
  rises towards `jam.friction`. A misalignment set back to 0 restores the original settings. Nothing
  in game produces misalignment yet: C-42 (M6) then the `jam_joint` action (M7) will.
- A missing setting does nothing: no limit, no motor, no friction, no threshold, no jamming.

### Errors, network and debugging

- At load time, a setting that does not apply to its type, a missing required field, a zero axis,
  crossed limits, a malformed jam curve or an `a` that names no declared body get the definition
  refused, with the path of the faulty field (`$.joints[0].limits`). Nodes are resolved against the
  asset as soon as it is compiled (ADR-156): a `root_node` or a `b_node` that names none, a joint
  whose two ends fall on the same body, more than 64 bodies counting those the joints create, or a
  body root under an animated node, gets the definition refused, with the faulty path and name
  (`E-7001 … — $.joints[0].b_node « capot » : aucun node de ce nom dans l'asset`; AXION's logs are
  in French). An assembly that names it stays inert.
- `modules.joints = false` (see [CONFIGURATION.md](CONFIGURATION.md)) ignores joints, with a
  warning: the declared bodies stay separate.
- Breaking and jamming are decided on the server. Clients receive each body's pose relative to the
  root body and each joint's state, and draw each body at its pose. The save keeps each body's pose
  and velocities and each joint's state: a reloaded assembly starts again where it was, asleep
  (ADR-153).
- `/axion debug joints on` draws each joint from one anchor to the other, with its axis: green,
  amber if it is jammed, pink if it is broken. `/axion debug limits on` draws the allowed range of
  each limited axis — that of a `revolute` or a `prismatic`, the `limited` axes of a `generic` —
  and its position: an arc for a rotation, a segment for a translation, in blue as long as the joint
  is free. These overlays show nothing on a client connected to a remote server, which does not
  simulate.

## Activity, caps and CCD

- **Simulation radius.** A body outside `sim.simulation_radius` of every player falls asleep
  before the step, and wakes up when it comes back (R-612). Activity management puts bodies to
  sleep; it never deletes them.
- **Cap.** At most `budgets.max_active_bodies` active bodies on the server, across all
  dimensions: beyond that, the ones furthest from a player fall asleep, the oldest first at equal
  distance (R-613).
- **CCD.** When declared, it stays on. Otherwise, it turns itself on at the substep where the
  body travels more than half its smallest dimension (`|v|·dt > 0,5 × min_dim`, section
  5.23), and turns off below that. The smallest dimension is that of the body's thinnest part.
  Against fixed scenery alone, `rapier` sweeps a fast body anyway.
- **Safeguards.** Velocities are clamped to 300 m/s and 100 rad/s by default; an overshoot is
  clamped, flagged (`CLAMPED`) and logged at most once per minute per body (R-180). A
  non-finite or out-of-world state brings the body back to its last valid state and puts it to
  sleep (R-181, `E-2030`).

## Forces and events

Dimension gravity (`physics.gravity`), per-body gravity scale, wind, drag relative to the
wind, lift from declared surfaces (R-1000) and buoyancy apply at every substep
(§10.6).

The simulation emits events in bounded batches (`physics.max_events_per_tick`, R-1011):
sleep and wake-up, contact start and end, contact impulse above
`physics.contact_event_threshold`, sensor enter and exit, joint break
(`JOINT_BROKEN`), jam enter and exit (`JOINT_JAMMED`). An event lost for lack of
room is counted, never silenced.

## Degradation

The FM-21 governor judges the **p95** of the step duration over 100-tick windows, never a
spike (R-1870). Three windows in a row above `budgets.sim_ns_per_tick` drop one level: one
solver iteration and one substep fewer, then the simulation radius cut by a quarter, then the
active body cap cut by half. It goes back up one level after 30 s under 60% of the budget. Each
transition is logged with its cause (R-1880). Under overload, reproducibility gives way: that is
the very definition of a degradation (R-1890).

## Measurements

`/axion metrics export` writes the session's metrics, including the consumption and overruns of
each budget (`axion.budget.<budget>.consumed` and `.overruns`). On the physics side, the following
are fed: the step duration (`sim_ns_per_tick`), the submit duration (`submit_ns`), the active
bodies (`max_active_bodies`) and native memory (`native_mem_bytes`). Native memory is **all**
counted — `rapier` worlds included — by the native library's global allocator, per arena
(`axion.mem.<arène>.bytes`): `PERSISTENT` by default, `SCRATCH` for what a job allocates
(INV-08, ADR-133).

The step follows the stage order of R-660: commands, then the world stages — integration,
joints and contacts repeated at each substep, the only block where the order goes back — then
collecting and writing the states. Each stage that runs is logged, and in a debug build the order
is checked every tick (T-390). The duration of each existing stage is exposed:
`axion.sim.last_step_commands_ns`, `_integration_ns`, `_joints_ns`, `_contacts_ns`,
`_serialization_ns` (T-391);
per-stage budgets come with the governor (C-77, ADR-144).

## Spatial queries

The physics world answers raycasts, shape sweeps and overlaps, alone or in batches (C-39,
T-380..T-384). A filter picks the collision groups, excludes one body — the one asking — and
says what to do with sensors. A query never modifies the world, and reads the geometry left by
the last step, the state at the start of the tick (R-651). Today they are only called on the
native side: no Java API or boundary function exposes them yet.

## What does not exist yet

Joints between assemblies (C-48, M5), sources of jamming (C-42 in M6, `jam_joint` action in M7),
per-fluid density, the damage chain and deformation (C-41..C-45, M6), vehicle driving
(M5).
