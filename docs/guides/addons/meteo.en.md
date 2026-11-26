# Rain, snow and grip

In the rain, a road is slippery. AXION simulates it **per vehicle** and **per material** ([ADR-176](../../decisions/ADR-176.md) D5): a
vehicle under a roof does not slide, a rain tyre grips better than the others, and ice is no more slippery wet than it already is.

## What the engine does

- Every 10 ticks, the server checks whether **the rain falls on the vehicle**: open sky above it, in a biome where it does not snow.
  A thunderstorm counts as rain. Under a roof, in a tunnel, in a cold biome, it does not rain on it.
- Its **wetness**, from 0 (dry) to 1, rises to 1 in `physics.wet_rise_seconds` (2 s by default) in the rain, and falls back to 0 in
  `physics.wet_dry_seconds` (30 s) otherwise. Wetness is not saved: a reloaded vehicle starts dry.
- Each wheel's grip coefficient is multiplied by `1 − wetness × (1 − min(1, material wet × tyre wet_grip))`. At 0 it does not change;
  at 1, on a 0.75 material with an ordinary tyre, it is 0.75.

## Declaring what slips

In a **physics material** (`axion/physics_materials/<name>.json`), the optional `wet` key: the friction factor when it is wet, from 0.05
to 1. **0.75 by default.** Ice, which already slips, declares `1`.

```json
{ "density": 916.7, "friction": 0.05, "wet": 1.0 }
```

In a **tyre profile** (`axion/tires/<path>.json`), the optional `wet_grip` key, from 0.1 to 4 (**1 by default**): a rain tyre declares
more than 1 and gives back what the wet road takes away. With a 0.5 material, a tyre at 2 grips like the dry.

```json
{ "schema": 1, "mu_long": 1.0, "mu_lat": 1.0,
  "long": { "peak_slip": 0.12, "peak_value": 1.0, "tail_slip": 0.5, "tail_value": 0.75 },
  "lat":  { "peak_slip": 0.15, "peak_value": 1.0, "tail_slip": 0.6, "tail_value": 0.8 },
  "wet_grip": 1.35 }
```

## Snow is content

There is no weather rule for snow: the examples map `minecraft:snow`, `minecraft:snow_block` and `minecraft:powder_snow` to the
`axion:snow` material (friction 0.35, `wet` 1), like any block has its material. These values are design choices, not measurements; your
content declares its own (see [materials](../MATERIALS.en.md)).

**The ground, block by block.** Each wheel takes the material of the block it touches ([ADR-180](../../decisions/ADR-180.md)):
a road on grass grips like the road. The first snow layer, which has no collision, covers the block below with its
material because the example mapping declares it `cover` ([materials](../MATERIALS.en.md)); yours declares its own.

## Knowing what a wheel rolls on

`WheelView.surface()` gives the identifier of the physical material the wheel touches — `axion:ice`, `axion:snow`, or an
addon's own ([ADR-181](../../decisions/ADR-181.md)). It is empty in the air, or on ground that declares none. It is what the
physics uses, snow cover included: for sounds or particles that depend on the ground, a controller or your server code
reads it.

```java
for (WheelView wheel : assembly.vehicle().orElseThrow().state().wheels()) {
    if (wheel.surface().map(id -> id.getPath().equals("sand")).orElse(false)) {
        // the wheel kicks up sand: wheel.node(), wheel.slipLong()
    }
}
```

A controller reads the same value on the predicting client; right after a server correction, it gets for one tick the last
material the prediction saw under the wheel.

`/axion wear <target>` says what each wheel touches, and how wet the road is.

## Reading it

- The procedural source `vehicle.wet` (0 to 1, schema 7): a gauge, a material track, wipers.
- The client receives it from the server, in one byte; it does not read the rain itself, so it predicts the same thing as the server.

## What it does not do

- No puddles, no aquaplaning: wetness is a factor on the coefficient, uniform under the vehicle.
- No wet-road rendering or tracks: that is rendering, outside this work.
