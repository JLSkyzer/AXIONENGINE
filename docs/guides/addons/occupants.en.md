# Scaling a seat

A player sitting in your vehicle is, by default, a Minecraft player: 1.8 m tall, in the game's seated pose, with the game's eye. If
your model is bigger or smaller than Minecraft, the player floats above or sinks into the seat. A seat sets what it does with its
**occupant** ([ADR-177](../../decisions/ADR-177.md) D1), from schema 7:

```json
"seats": [ { "name": "driver", "node": "seat",
             "occupant": { "pose": "sit", "scale": 0.67, "eye": [0, 0.75, 0.05] } } ]
```

All three keys are optional; a seat without `occupant` keeps Minecraft's pose, scale and eye.

## The three settings

- **`pose`**: `sit` (seated, the game's pose, the default) or `stand` (standing: a firing platform, a standing post). Standing, the
  player's **feet** are at the seat's node, without Minecraft's mount offset; seated, it is the seat of the pants that is there.
- **`scale`**: from 0.25 to 4, **1 by default**. The occupant is **drawn** at this scale, around the seat's point: its size changes,
  not the place where it sits.
- **`eye`**: the occupant's first-person eye, in the seat node's frame, in metres. Without it, it is Minecraft's eye **multiplied by
  `scale`**: an occupant at 0.67 sees from 0.67 times lower. A camera view that declares its own `eye` (see
  [cameras](cameras.md)) keeps it.

## Two ways to match the model and the player

AXION allows both, your choice:

1. **Scale the model up or down** and keep `scale` at 1. The test repository's Charger, scaled ×1.5, is the example.
2. **Keep the model at its real size** and set `scale`: the same Charger at 1, with `scale` at 0.67 (1 ÷ 1.5) on every seat.

The choice depends on what you want elsewhere. A model at its real size behaves like it does in the game wherever it is used
(another body, another mod); a seat scale spares you from redoing a model you do not control.

## What does not change

- **The player's collision box** stays a Minecraft player's. The scale touches the drawing only: the safe exit (R-711), hits, what
  the server reads, all of it stays what a normal player does.
- **Other players** see the occupant at the same scale: `scale` comes from the definition, which every client reads.
- **Third person**: the camera backs away from the eye Minecraft placed; `eye` and `scale` change nothing there.

## Good to know

- The player's name, above them, is drawn with them: it follows the scale.
- Seated or standing, the server places the player at the same node; only the height differs (the mount offset, 0.35 m for a seated
  player).
- The API writes these fields like the others: `seat("driver", s -> s.occupant(o -> o.scale(0.67)))`.

The test definition `axion:test/siege_occupant` (in `content/test`) sets both seats; the GameTest
`lOccupantReglePoseEchelleEtOeilEtSaBoiteResteCelleDuJoueur` checks its pose, scale, eye and box.
