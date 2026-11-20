# Engine, starter and ignition

A vehicle can have an engine that **starts**: it stays stopped until the driver switches the ignition on
([ADR-176](../../decisions/ADR-176.md) D1). With no starter declared, the engine always runs, as before: nothing changes
for your existing definitions.

## Declaring the starter

At schema 7, in `powertrain.engine`:

```json
"powertrain": {
  "engine": { "torque_curve": [[0, 0], [1500, 90], [3500, 120]], "idle_rpm": 900, "max_rpm": 6500,
              "inertia": 0.15, "braking_torque": 15.0,
              "starter": { "time": 0.8, "variable": "contact" } }
},
"variables": {
  "contact": { "type": "bool", "default": false, "replicate": "all", "write": "driver" }
},
"seats": [
  { "name": "conducteur", "node": "siege", "role": "driver",
    "bindings": [ { "command": 3, "label": "mymod.contact", "variable": "contact", "mode": "toggle" } ] }
]
```

- **`time`**: how long the starter turns, 0.05 to 10 seconds.
- **`variable`**: the ignition. A **boolean of the assembly** that **you do not save** (no `persist`): an ignition saved
  "on" would restart the engine on every load. A per-seat variable is not predicted on the client: it is refused. The
  generic command that flips it is an ordinary binding (`toggle`), as for any variable.

## What the engine does

- **Ignition on** (the rising edge of the variable): the starter turns for `time` seconds — the engine still gives
  nothing — then the engine catches, at idle.
- **Ignition off**: the engine stops at once, starter included.
- **Stopped**: no torque, zero engine speed, no load. An **automatic** gearbox goes to neutral; a **manual** one keeps
  its gear, like a car, and engine braking remains. The parking brake stays on with no driver.
- **A driver getting out does not stop the engine**: the variable says whether the ignition is on.
- **A parked vehicle starts**: the ignition alone wakes it.
- **Saving**: the engine is not saved; on load it is stopped, ignition off.
- **A running engine does not stall** (ADR-159 D6): nothing here opens stalling.

## Reading it

The `engine.running` source is 1 while the engine runs, 0 otherwise: a procedural track can light a lamp or cut a
sound. Your mod reads it through `assembly.source("engine.running")` in the API, and a mod sets the ignition by writing the variable.

## The tank and the battery

A schema 7 definition can declare its energy ([ADR-176](../../decisions/ADR-176.md) D3):

```json
"energy": {
  "fuel": { "capacity": 40.0, "unit_mass": 0.75, "consumption": [[0.0, 0.4], [1.0, 9.0]] },
  "battery": { "capacity": 1.0, "starter_draw": 0.02, "charge_rate": 0.002 }
}
```

- **`fuel.consumption`**: litres per hour according to engine load (0 to 1), multiplied by engine speed over maximum speed.
  Loads strictly increase.
- **`unit_mass`**: the mass of a litre. It requires `physics.mass`, which is then the **dry mass**.
- **`battery`**: the starter draws `starter_draw` per second from it, a running engine gives back `charge_rate`. It requires a
  `starter`.
- **Empty tank**: the engine stops, and does not restart without a new ignition edge. **Empty battery or empty tank**: the
  starter does not turn.
- **A sleeping vehicle burns nothing**: combustion follows being awake, like the rest of the simulation.
- With no `energy`, nothing changes.

- **Mass follows fuel**: `physics.mass` is the **dry** mass, and the engine adds `unit_mass` × the remaining fuel — in steps
  of 1/255 of the capacity, so the vehicle is not woken for every drop. The cause is called `axion:energy`, it shows in
  `/axion params`, and it is not saved: it is recomputed.
- **Saving**: fuel and battery are kept (`axion:energy`); a reloaded vehicle resumes with the fuel it had, not a full tank.

The native side holds the energy (fuel and battery), deterministic, which the client replays; reading it from Java, saving,
the `energy.*` sources and the API come with the next slices of ADR-176.
