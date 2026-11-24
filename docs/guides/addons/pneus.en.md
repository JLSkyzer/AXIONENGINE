# Tyres and wear

A tyre can **wear out**: the more it slides, the less it grips ([ADR-176](../../decisions/ADR-176.md) D4). A tyre that declares
no wear never wears: nothing changes for your existing definitions.

## Declaring a tyre's wear

In the tyre profile (`axion/tires/<path>.json`), the optional `wear` key:

```json
{
  "schema": 1, "mu_long": 1.0, "mu_lat": 1.0,
  "long": { "peak_slip": 0.12, "peak_value": 1.0, "tail_slip": 0.5, "tail_value": 0.75 },
  "lat":  { "peak_slip": 0.15, "peak_value": 1.0, "tail_slip": 0.6, "tail_value": 0.8 },
  "wear": { "rate": 0.002, "grip_loss": 0.4 }
}
```

- **`rate`**: wear per **kilojoule** of friction work. That is the energy the rubber spends sliding on the ground: the wheel's
  load, times the speed it slides at, times the time. A tyre that rolls without sliding hardly wears; a burnout wears it fast.
  `0` (the default): the tyre does not wear.
- **`grip_loss`**: the share of grip a **fully worn** tyre loses, from 0 to 1 (0.4 by default). Wear changes neither the radius
  nor the peak of the curve: those stay with damage.

The definition names this profile like any tyre: `"tire": "mymod:tire/road"` in the wheel.

## What the engine does

- Each wheel's wear goes from 0 (new) to 1 (fully worn). A wheel in the air, or a sleeping vehicle, wears nothing.
- It is **saved by wheel name**: a wheel added, removed or reordered does not shift the others, and your `migrations` can rename a
  wheel. A new tyre is not written.
- It travels to the client in one byte per wheel: a procedural track or a gauge reads it as `wheel.<wheel>.wear`.

## Changing it

- **API**: `assembly.vehicle().orElseThrow().setWear("rear_left", 0.0)` — a garage's tyre change —; `state().wheels().get(i).wear()`
  reads it.
- **Command**: `/axion wear <target>` shows it; `/axion wear <target> set <wheel> <0..1>` and `reset` set it (operators, logged).
- **Interaction**: a `set_wear { "wheel": "rear_left", "value": 0 }` action in an interaction's `actions` list (without `wheel`, all
  the wheels).
- **Diagnostics**: the wheels overlay (`/axion debug`) draws a second, smaller circle as the tyre wears.
