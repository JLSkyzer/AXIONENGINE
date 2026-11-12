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
- **the sheet's**, if it gives one, in the `tuning` section:

```json
--8<-- "java/addon-temoin/src/main/resources/axion/definitions/upgradable_crate.json"
```

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
| `modifiers(name)` | each modifier, in the order they were added: its cause, its origin (`api`, `commande` for the command, `sauvegarde` for the save), whether it is active, and if not, why |

## 5. Test it

A GameTest places the object, tunes it, and checks:

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleAddonGameTests.java:upgrade"
```

You run it like your other GameTests ([Test your addon](tester.md)).

## 6. See and tune in game

The `/axion params` command shows the parameters of the object you target, and each modifier with its origin. It needs
operator rights (level 2):

```text
/axion params @e[type=axion:assembly,limit=1,sort=nearest]
```

```text
AXION ENGINE — example_addon:upgradable_crate : 1 paramètre(s), 1 modificateur(s)
  mass = 150 kg (base 200)
    example_addon:stage_one multiply_total -0.25 (api)
```

Add a parameter name to see its bounds too. To try a tuning without writing code:

```text
/axion params @e[type=axion:assembly,limit=1,sort=nearest] set test:lightening mass add -20
/axion params @e[type=axion:assembly,limit=1,sort=nearest] remove test:lightening
```

Every `set` and every `remove` is written to the server log, with who typed it.

## 7. Saves, players, updates

- **The save** keeps the modifiers in place, in their order. When the world loads, each one is added again by the same
  rules as `modify`.
- **Players** receive the modifiers, not the values: each of them redoes the calculation with the same sheet and gets
  exactly the same numbers. On the client, `parameters()` can be read; `modify` and `remove` throw
  `IllegalStateException` there.
- **When your sheet changes** — a narrower range, a gear removed —, a saved modifier that the new bounds refuse is not
  lost: it stays in place, inactive, and the log says so (`E-7011`). `/axion params` shows it, and it can be removed.
