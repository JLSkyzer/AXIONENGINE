# Tune a vehicle

An upgrade kit changes **one** vehicle, not its model: more torque, sharper brakes, a few kilos less. AXION calls these
quantities **parameters**. Each vehicle tunes its own, even while it is moving; the tuning is saved with it and sent to
the players who see it. The other vehicles of the same model are not affected.

This page shows how an upgrades mod does it. The excerpts come from the witness addon, compiled and tested on every
change to AXION.

## 1. The parameters

| Parameter | Unit | What it tunes |
|---|---|---|
| `mass` | kg | the object's mass, vehicle or not; its centre of mass does not move |
| `engine.torque` | N·m | the engine's peak torque; the whole curve follows, keeping its shape |
| `engine.max_rpm` | rpm | the rev limiter; with an automatic gearbox, the shift points follow |
| `transmission.final_drive` | — | the final drive ratio |
| `transmission.shift_time` | s | how long a gear change takes |
| `transmission.gear.<rank>` | — | one gear ratio: `transmission.gear.1` is first gear, `transmission.gear.-1` reverse |
| `brakes.front`, `brakes.rear`, `brakes.handbrake` | N·m | the torque of the front brakes, the rear brakes and the handbrake |
| `tires.grip` | factor | the grip of each tyre; 1: the grip it declares |
| `suspension.stiffness`, `suspension.damping` | factor | the stiffness and damping of each suspension; 1: what it declares |
| `steering.max_angle` | rad | the maximum steering angle |
| `aero.drag`, `aero.downforce_front`, `aero.downforce_rear` | — | aerodynamic drag and downforce |
| `motor.<name>.torque` | N·m | an [electric motor](moteur.md)'s peak torque; at 0 it gives nothing |
| `motor.<name>.regen` | × | the regenerative braking of an electric motor that declares `regen`: 1 as in the sheet, 0 none, 2 twice (0 to 2) |

**An object only has the parameters its sheet declares.** `mass` only exists if the sheet declares `physics.mass`. The
engine's parameters only exist for a vehicle with an engine, and there is one gear per entry of `gears`.
`parameters().names()` gives you an object's list, and `has(name)` tells you whether a parameter is there.

!!! info "Tuned stiffness changes the ride height"
    As on a real car, a stiffer suspension sinks less under the weight: the vehicle rises a little. A tuned mass, on
    the other hand, keeps the ride height, because stiffness is counted per kilogram.

## 2. Add a modifier

Tuning does not replace the value: it **adds a modifier**, on behalf of a **cause**. The cause is an identifier in
your mod's namespace — one per kit:

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleUpgrades.java:cause"
```

Then each parameter the kit changes gets its modifier:

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleUpgrades.java:fit"
```

A modifier has an **operation**, the same as for Minecraft's attributes:

| Operation | What it does | Example on 200 |
|---|---|---|
| `ADD` | adds the amount | `-50` gives 150 |
| `MULTIPLY_BASE` | adds a percentage of the base; those of every mod add up | `0.25` gives 250 |
| `MULTIPLY_TOTAL` | multiplies the result; each mod multiplies in turn | `-0.25` gives 150 |

So the effective value is:

```text
(base + sum of add) × (1 + sum of multiply_base) × product of (1 + multiply_total)
```

The **base** is the sheet's value, as the server's administrator may have adjusted it. Adding a modifier from the same
cause on the same parameter replaces the previous one.

Taking the kit off means removing its cause, in a single call:

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleUpgrades.java:remove"
```

These calls are made **on the server**, on its main thread: where a command runs, where a click on an item or a
server-side Forge event is handled.

## 3. Bounds: refused, never clamped

Tuning stays within two limits:

- **the engine's**: a value the sheet could have declared. A negative mass or an infinite torque are impossible;
- **the sheet's**, if it gives one, in `tuning.ranges`:

```json
--8<-- "java/addon-temoin/src/main/resources/axion/definitions/upgradable_crate.json"
```

(The `ballast` variable and `tuning.modifiers` are explained in [section 5](#5-a-variable-of-the-sheet-tunes-a-parameter).)

With the *builder*, that is `.tuning(tuning -> tuning.range(Parameters.MASS, range -> range.min(100).max(300)))`. A
range is written in the parameter's unit, and must contain the base.

A modifier that would take its value out of these limits is **refused**: `modify` throws an `IllegalArgumentException`
and nothing changes. AXION never silently cuts the value at the limit. The message names the parameter, the value it
would have had and the bounds (AXION's messages are in French):

```text
« mass » vaudrait 75.0 kg, hors de [100, 300] (plage de la definition, tuning.ranges)
```

**Removing always succeeds**, even when another modifier depended on it. Say a mod removes a weight reduction that
another mod's turbo needed to stay under the maximum torque. The turbo is not erased: it stays in place, **inactive**,
with its reason, and becomes active again as soon as the value is back within its bounds.

## 4. Several mods on the same vehicle

Your kit and another mod's combine, without overwriting each other:

- each mod adds its modifiers under its own cause, and only removes its own;
- the order of calculation is that of the cause names, not the order in which they were added: the result is the same
  whichever mod tuned first;
- a vehicle carries at most 64 modifiers; beyond that, `modify` refuses.

To read the state of a parameter:

| Method | What it returns |
|---|---|
| `value(name)` | the effective value |
| `base(name)` | the sheet's value |
| `range(name)` | the effective bounds |
| `unit(name)` | the unit |
| `modifiers(name)` | each modifier: the sheet's first, then those added, in their order; its cause, its origin (`variable`, `api`, `commande` for the command, `sauvegarde` for the save), whether it is active, and if not, why |

## 5. A variable of the sheet tunes a parameter

Tuning can also come from the sheet itself: a **variable** — toggled by a seat key, set by an interaction or by your
code — tunes a parameter. That is the `tuning.modifiers` section. In the tunable crate above, each step of `ballast`
adds 40 kg:

```json
"modifiers": {
  "ballast": { "parameter": "mass", "operation": "add", "variable": "ballast", "values": [0, 40, 80] }
}
```

- **The amount**: `values` gives one per value of an `int` variable (from its `min`) or a `bool` (`false`, then
  `true`). For a `float` variable, `scale` makes it proportional: amount = `scale` × value.
- **The operation** is one of the three of [section 2](#2-add-a-modifier); this modifier combines with the mods'.
- **The cause** is `<sheet id>/<name>` — here `example_addon:upgradable_crate/ballast`. It is neither added nor
  removed through the API: it follows its variable.
- **The variable must be `"replicate": "all"`**: every player redoes the calculation and needs the value. The sheet is
  refused otherwise.
- **Nothing more is saved**: the variable is if it is `persist`, and the modifier follows from it.

With the *builder*: `.tuning(tuning -> tuning.modifier("ballast", Parameters.MASS, "add", "ballast", m -> m.values(0,
40, 80)))`.

The sheet is refused when it loads if one amount, on its own, would take the parameter out of its bounds. In game, a
write of the variable that would take it out of its bounds — together with another mod's modifiers, say — is
**refused**: the key does nothing and the log says so (`E-7010`), `variables().set` throws an
`IllegalArgumentException`. An automatic return, like a released `hold` command, always goes through, like a removal.

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleAddonGameTests.java:ballast"
```

!!! example "The example buggy's sport mode"
    AXION's buggy has a sport mode on command 2 (H by default): a single variable, `sport`, tunes three parameters —
    torque +30 %, rev limiter +500 rpm, grip +20 %. Its sheet is in AXION's repository,
    `content/examples/axion/definitions/example/buggy.json`.

## 6. A vehicle controller

A modifier you add stays until it is removed. A **controller** decides every tick: a speed limiter, traction control,
a hybrid mode. It is a function your mod registers; every tick, AXION hands it the vehicle as it was at the start of
the tick and takes what it writes, for this tick only.

The witness has one, a speed limiter. Its ids:

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleSpeedLimiter.java:ids"
```

The controller reads the vehicle's speed and its top speed, and past it cuts engine torque:

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleSpeedLimiter.java:controller"
```

What it **reads** (`VehicleView`): speed, engine speed, gear, the driver's input, each wheel (contact, compression,
spin, steering, slips), the variables and the parameters. Parameters are composed **without** the controllers'
outputs: two controllers of the same vehicle read the same value, whatever their order.

It also reads **any procedural source** by name, as a track of the sheet reads it: `view.source("world.time")`,
`view.source("world.rain")`, `view.source("var.phares")`… The list of sources is that of the tracks. The world is read
at the vehicle's position, at the start of the tick. An automatic headlight, for instance, switches on at dusk:

```java
double time = view.source("world.time");           // 0 at midnight, 0.5 at noon
boolean night = time < 0.27 || time > 0.77;
out.variable("phares", night ? 1 : 0);
```

A name that is not a source, or that names a wheel, a seat or a variable the sheet does not have, throws an
`IllegalArgumentException` that says so. A mod that only reads goes through the assembly: `assembly.source(name)`, on
the server thread.

What it **writes** (`ControllerOutput`), for this tick only:

| Method | Effect |
|---|---|
| `modify(parameter, operation, amount)` | a modifier in the controller's name, composed with the others; if it would take the parameter out of its bounds, it is left out for this tick |
| `wheelTorque(wheel, torque)` | a wheel's torque, in N·m: added to the engine's, or all the torque of a vehicle without `powertrain` |
| `variable(name, value)` | a vehicle variable, within its bounds: that is where your controller's state lives, saved and sent to players |

It registers with the rest of your content, along with the parameter it reads:

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleSpeedLimiter.java:register"
```

**Where it attaches** — two ways:

- **in the sheet**, for every vehicle of that model:

    ```json
    "vehicle": { "controllers": ["example_addon:speed_limiter"] }
    ```

    A sheet naming a controller no mod registers is refused at load, as is a sheet without wheels that names one. At
    most eight controllers per vehicle.

- **through the API**, on one vehicle only: `assembly.vehicle().orElseThrow().attach("example_addon:speed_limiter")`,
  and `detach` to take it off. It is then saved with the vehicle and sent to players.

**Your mod's parameter** (`max_speed`) exists on a vehicle as soon as one of your controllers is attached to it, with
the registered base: it is tuned, bounded and composed like AXION's own. Take the controller off and the parameter is
gone; a modifier on it is kept, inactive.

**Its cost is measured**, call by call. A call over budget (`vehicle.controller_budget_ns`, 50 µs by default) or one
that throws counts a fault; a sound call clears the count. After `vehicle.controller_strikes` faults in a row (20), the
controller is **disabled for the session**, on every vehicle, and the log says so, naming your mod (`E-1011`): the
vehicles then drive without it. An exception also discards everything the call had written.

**Its rules**:

- a function of what it reads, with no hidden state: its state lives in the vehicle's variables;
- it runs on the server, between two physics steps, and on the client that predicts its vehicle, hence the previous
  rule. On that client, world sources are read from its own level: time and rain are copied there from the server,
  and a rare gap is corrected like any misprediction;
- the mod registering it is required from players as soon as a sheet names it.

The witness's GameTest attaches the limiter to the example buggy, tunes its top speed and drives it at a third throttle.
Flat out, its rear wheels would spin, and once the engine is cut they would keep pushing it until they meet the
ground's speed: a limiter cuts the engine, not the momentum of the wheels:

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleAddonGameTests.java:limiter"
```

`vehicle().drive(input)` drives a vehicle nobody drives — an autopilot, a test. It releases the parking brake; to hold
the vehicle, apply the handbrake. If a player holds the wheel, the call is refused and names them.

## 7. Test it

A GameTest places the object, tunes it, and checks:

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleAddonGameTests.java:upgrade"
```

You run it like your other GameTests ([Test your addon](tester.md)).

## 8. See and tune in game

The `/axion params` command shows the parameters of the object you target, and each modifier with its origin. It needs
operator rights (level 2):

```text
/axion params @e[type=axion:assembly,limit=1,sort=nearest]
```

```text
AXION ENGINE — example_addon:upgradable_crate : 1 paramètre(s), 2 modificateur(s)
  mass = 150 kg (base 200)
    example_addon:upgradable_crate/ballast add 0 (variable)
    example_addon:stage_one multiply_total -0.25 (api)
```

Add a parameter name to see its bounds too. To try a tuning without writing code:

```text
/axion params @e[type=axion:assembly,limit=1,sort=nearest] set test:lightening mass add -20
/axion params @e[type=axion:assembly,limit=1,sort=nearest] remove test:lightening
```

Every `set` and every `remove` is written to the server log, with who typed it.

With no parameter given, `/axion params` also lists the vehicle's controllers, with their mod, their state and their
measured cost (the command speaks French):

```text
  contrôleurs (1) :
    example_addon:speed_limiter (mod example_addon) — actif, <appels> appel(s), moyenne <µs> µs, pire <µs> µs
```

For an upgrades screen, `Parameters.translationKey(name)` gives a parameter's translation key:
`axion.parameter.engine.torque`, translated by AXION; for yours, `example_addon.parameter.max_speed`, which your mod
translates in its language files.

## 9. Saves, players, updates

- **The save** keeps the modifiers in place, in their order. When the world loads, each one is added again by the same
  rules as `modify`.
- **Players** receive the modifiers and the variables, not the values: each of them redoes the calculation with the
  same sheet and gets exactly the same numbers. On the client, `parameters()` can be read; `modify` and `remove` throw
  `IllegalStateException` there.
- **When your sheet changes** — a narrower range, a gear removed —, a saved modifier that the new bounds refuse is not
  lost: it stays in place, inactive, and the log says so (`E-7011`). `/axion params` shows it, and it can be removed.

## 10. Read its velocity, push it

On the server, your mod reads an assembly's velocity and gives it an **impulse** — a trampoline bounce, an explosion's
blast, a catapult:

```java
Vec3 velocity = assembly.linearVelocity();      // m/s, in world space
Vec3 spin = assembly.angularVelocity();         // rad/s around the world axes
assembly.applyImpulse(new Vec3(0, 4000, 0), null);                      // in N·s, at the center of mass
assembly.applyImpulse(new Vec3(2000, 0, 0), assembly.transform().position().add(0, 1, 0));  // at a point: it spins
```

- The impulse applies at the **next physics step**, to the root body, and wakes it. Given before the engine simulates
  the assembly — right after `spawn` —, it waits for its first step. On an assembly suspended far from every player,
  it has no effect.
- The velocity is **zero** for an assembly that is asleep, suspended far from every player, or not simulated yet.
- Server only, on its thread; a non-finite value is refused.

To **push continuously** — a thruster, a wind, a magnet —, give a force or a torque on every tick:

```java
// In a server tick: they last the next physics step, at each of its substeps, then are forgotten.
assembly.applyForce(new Vec3(0, 12000, 0), null);   // in newtons, at the center of mass
assembly.applyForce(new Vec3(500, 0, 0), assembly.transform().position().add(0, 0, 2));  // at a point
assembly.applyTorque(new Vec3(0, 800, 0));          // in N·m around the world axes
```

Neither forces nor impulses are **predicted** on a driver's client: on a driven vehicle in multiplayer, the client
learns of them through the server's corrections and the view snaps back — the stronger the push and the longer the
latency, the more. For a lasting effect on a driven vehicle, a predicted vehicle controller is better when it is enough
(wheel torque, parameters).

Given again on every tick, a force pushes continuously; given once, it amounts to `F × 0.05` N·s. It wakes the body;
given before the engine simulates the assembly, or to a suspended one, it is lost.

An administrator **names** an assembly, like any entity: `/axion rename <target> <name>`. The name shows above it and
is saved.

They **teleport** it within its world, with whatever is attached to it: `/axion tp <target> <x y z> [yaw]` (with a yaw,
it arrives upright, facing it); Minecraft's `/tp` and `Entity#teleportTo` do the same, at rest; your mod, through the
API:

```java
// The root body's pose; other bodies and trailers follow in the same motion. true: arrive stopped.
assembly.teleport(new Transform(new Vec3(120.5, 70, -40.5), new Quaternionf(), new Vec3(1, 1, 1)), true);
```

Anchors to blocks are released (cause `teleported` for attachment listeners), and whatever stood on it without being
seated stays where it is. Seated passengers go with it. The destination chunk is loaded. Other players see it vanish and reappear, without a glide; a
position outside Minecraft's world is refused.

An administrator **freezes** it: `/axion freeze <target> [on|off]`; your mod: `assembly.setFrozen(true)`. While frozen,
nothing pushes it and it does not fall, it carries and blocks like scenery, its engine is paused and nothing is consumed;
passengers stay, and the game saves it frozen. Its driver does not predict it while it is. Handed back to the engine, it
starts again at rest.

A frozen assembly, your mod **drives** it — a lift, a lifting bridge, an animated prop:

```java
assembly.setFrozen(true);
// Every server tick: the pose it reaches at the next physics step. The engine derives its velocity, so it pushes and
// carries whatever it meets.
Transform now = assembly.transform();
assembly.setKinematicTransform(new Transform(now.position().add(0, 0.05, 0), now.rotation(), now.scale()));
```

Called on a dynamic assembly it throws `IllegalStateException`: to move it at once, use `teleport`. While driven, it
carries what stands on it and pushes ahead of it, without hurting them, whatever a rolling vehicle pushes — mobs,
boats, minecarts; not dropped items, item frames or armor stands — as long as `physics.entity_push` is true; a player is
pushed as by any advancing vehicle.
