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

1. **Scale the model up or down** and keep `scale` at 1: a model scaled ×1.5 in Blender, for instance, around a Minecraft player who keeps their size.
2. **Keep the model at its real size** and set `scale`: the same model at 1, with `scale` at 0.67 (1 ÷ 1.5) on every seat.

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

## The player mannequin

To tune `scale` or `eye` in Blender without launching the game, AXION provides the **player mannequin** ([ADR-177](../../decisions/ADR-177.md)
D2, CAP-56): a Minecraft player at his true dimensions, as a GLB.

```powershell
axion-cli mannequin mannequin
```

writes `joueur_large.glb` (4-pixel arms) and `joueur_fin.glb` (3-pixel arms) into the folder, and refuses to overwrite without `--force`.

- **Its dimensions are written nowhere**: they are read from Minecraft's player model, baked the way the client does
  (`PlayerModel.createMesh`). The model is 1.875 m tall — the 0.9375 scale that the renderer applies to players —, the collision box
  1.8 m. Twelve parts, articulated at the game's pivots: `head`, `body`, the arms and the legs, and the second layer of each
  (`hat`, `jacket`, `right_sleeve`, `left_sleeve`, `right_pants`, `left_pants`).
- **Three poses**, one single-frame clip each: `stand`, `sneak` and `sit` (the mount pose). They come from the game's `setupAnim`, not
  from a drawing.
- **Put on a seat**: parent the mannequin's root node (`joueur_large` or `joueur_fin`) to your model's `SEAT` node **without
  touching its position, rotation or scale**: they now read in the seat's frame (in Blender, after `Ctrl+P`, use `Alt+P` ›
  *Clear Parent Inverse*). Play the `sit` clip. The origin of the player's entity is then **0.35 m below the node** — Minecraft's mount
  offset —, where the server puts it. It looks towards `-Z`, AXION's front.
- **To match the model and the player**: put the mannequin on the seat and scale the model (first convention). For the second one,
  insert an empty node between the two: child of the `SEAT`, with no offset or rotation, scaled by your `scale`, and parent of the
  mannequin's root; then read on it where the head and the eye fall. **Do not scale the root itself**: in the `sit` pose its origin is
  1.06 m above the seat, and the mannequin would grow around that point, not around the seat as in the game.
- **Its texture** is neutral, in a skin's layout (64 × 64), tinted by part so you can find your way: no Mojang texture.
- **It never diverges from the game**: `./gradlew genMannequin` writes it from the game's model, and the render bench compares every
  vertex of both files, in the three poses, with what the game draws (1 mm), then the seated player with the origin that the seated
  mannequin puts below the node. If Minecraft changes its model, the bench fails and is fixed with `./gradlew genMannequin`.

## Seeing what the seat sets

`/axion debug seats on` draws, at each seat, the occupant at the set scale and a cross at its eye in orange, and a player's collision box in grey,
which the scale does not touch.

The test definition `axion:test/siege_occupant` (in `content/test`) sets both seats; the GameTest
`lOccupantReglePoseEchelleEtOeilEtSaBoiteResteCelleDuJoueur` checks its pose, scale, eye and box.
