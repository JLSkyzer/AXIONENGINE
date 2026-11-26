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
- Energy feeds a powertrain: a definition without `powertrain` — a trailer — cannot declare it.

- **Mass follows fuel**: `physics.mass` is the **dry** mass, and the engine adds `unit_mass` × the remaining fuel — in steps
  of 1/255 of the capacity, so the vehicle is not woken for every drop. The cause is called `axion:energy`, it shows in
  `/axion params`, and it is not saved: it is recomputed.
- **Saving**: fuel and battery are kept (`axion:energy`); a reloaded vehicle resumes with the fuel it had, not a full tank.

## Filling and reading the energy

- **API**: `assembly.energy()` gives `fuel()`, `fuelCapacity()`, `fuelRatio()`, `battery()`; `refuel(litres)` pours, and returns what
  went in (never more than what is missing); the fill reaches the native side without waking the vehicle.
- **Command**: `/axion energy <target>` shows the energy; `/axion energy <target> refuel <litres>` fills, `battery <charge>` sets the
  battery (operators, logged).
- **Interaction**: a `refuel { "litres": 5 }` action in an interaction's `actions` list pours into the definition's tank. AXION ships
  no pump: your content makes it.
- **Sources**: `energy.fuel` (litres), `energy.fuel_ratio` and `energy.battery` (0 to 1) for a procedural track or a gauge.

The example buggy has a starter (`contact` variable, command 3), a 20-litre tank and a battery: it is the model for all of the above.

## Several typed stores (schema 8)

At schema 8 you name your stores and what they hold ([ADR-182](../../decisions/ADR-182.md)): a diesel engine only takes
diesel, a tank trailer carries water, a battery charges slowly.

```json
"schema": 8,
"energy": {
  "stores": [
    { "name": "diesel", "carrier": "axion:diesel", "capacity": 60.0, "unit_mass": 0.84 },
    { "name": "tank", "carrier": "mymod:water", "capacity": 800.0, "unit_mass": 1.0, "max_fill_rate": 5.0 }
  ],
  "battery": { "capacity": 1.0, "starter_draw": 0.02, "charge_rate": 0.002 }
},
"powertrain": {
  "engine": { "torque_curve": [[0, 0], [1500, 260], [3500, 240]], "idle_rpm": 800, "max_rpm": 4500,
              "inertia": 0.3, "braking_torque": 40.0,
              "starter": { "time": 1.0, "variable": "contact" },
              "fuel": { "store": "diesel", "consumption": [[0.0, 0.8], [1.0, 16.0]] } }
}
```

(The rest of `powertrain` — gearbox, driven wheels — is that of an ordinary vehicle.)

- **Up to 4 stores**, the `energy.battery` battery included.
- **`carrier`**: what it holds, an identifier you choose freely (`axion:diesel`, `axion:petrol`, `mymod:hydrogen`…). The
  engine treats none of them specially. Without a `carrier`, the store takes anything.
- **The unit is your content's**: litres, kilograms… Capacity, consumption, `unit_mass` and fills all use the same one.
- **`unit_mass`**: the mass of one unit, added to `physics.mass` (the dry mass). The vehicle weighs the sum of its stores.
- **`max_fill_rate`**: the units that can be poured in per second, at most. Absent: no limit.
- **`engine.fuel`**: the store the engine burns, and its consumption in units per hour (same rules as schema 7).
  `energy.fuel` does not exist next to `stores`.
- **The starter** draws the `energy.battery` battery, or a store: `"starter": { "time": 1.0, "variable": "contact",
  "store": "auxiliary", "draw": 0.02, "charge_rate": 0.002 }`. Not both, and not the store the engine burns.
- **Without an engine**: a definition may declare stores without a `powertrain` — a tank trailer weighs what it carries. It
  must have wheels (`wheels`).
- At schema 7, the tank and the battery read as the `fuel` and `battery` stores: nothing to change.

### Filling a typed store

A fill of another kind does not go in. Your pump or charger calls the API every tick; `max_fill_rate` bounds what goes in,
whatever it asks for.

- **API**: `assembly.energy().store("tank")` gives `carrier()`, `capacity()`, `amount()`, `ratio()`;
  `fill(carrier, amount)` returns what went in; `set(amount)` sets the content. `stores()` gives them all. `refuel(litres)`
  only pours into an untyped engine tank.
- **Interaction**: `refuel { "carrier": "axion:diesel", "amount": 5 }` pours into the first store that takes it and has
  room; add `"store": "diesel"` to aim at one. The `{ "litres": 5 }` form stays reserved for the untyped tank.
- **Command**: `/axion energy <target>` lists the stores; `/axion energy <target> refuel <amount> <carrier>` pours
  (operators, logged).
- **Sources**: `energy.<store>.amount` (in its unit) and `energy.<store>.ratio` (0 to 1), for a track or a gauge.
  `energy.fuel`, `energy.fuel_ratio` and `energy.battery` still mean the engine's store and the starter's.
- **Saving**: each content is kept under its store's name. Rename a store with a `rename.stores` migration
  ([ADR-175](../../decisions/ADR-175.md)); a removed store stays in the save and the log says so (`E-7012`); a new store
  starts full.
