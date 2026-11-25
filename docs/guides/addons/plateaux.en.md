# Platforms, bridges and lifts

An assembly **stops** what hits it and **carries** what rests on it ([ADR-178](../../decisions/ADR-178.md), CAP-14): a player stands on a
rolling platform, a lift takes up those it holds, a pig no longer walks through a container. Nothing is to be written in the definition:
the collision reads its colliders.

## What happens

- **Collision** follows vanilla: the entity's box slides along the surface it hits, steps up a vanilla step (0.6 block) from the ground,
  and rests on it "on ground".
- **The support** is born when a fall is stopped by a walkable surface (less than 45°), and dies when you jump, leave the edge or land on
  a block. You rest on one assembly at a time.
- **Carrying**: the player stays at their place on the body that holds them, and turns with it. Leaving it in the air (jump, edge), they
  keep the horizontal speed the platform gave them. The server carries them at the pose their client draws, so as neither to send them
  back nor to count them as "flying".
- **Push**: an assembly moving into a player pushes them out, at 10 m/s at most (`physics.push_speed_max`), without putting them into a
  block: pinned against a wall, they stay against it and the client log says so. A player weighs 80 kg (`physics.player_proxy_mass`):
  what hits them keeps some momentum, instead of stopping dead as against a wall.
- Other entities (mobs, items) are stopped and carried on the server.

## An assembly that carries nothing

A hangar door stops, but should not take along whoever leans on it: in schema 7,

```json
{ "schema": 7, "asset": "mymod:models/door.glb", "kind": "vehicle", "carry": false }
```

Collision remains; no support is born on it.

## For a mod

```java
// What an assembly carries, on the server.
List<Entity> carried = assembly.carried();

// Every boarding and leaving.
registrar.registerCarryListener(new CarryListener() {
    @Override
    public void carried(Assembly assembly, Entity entity) { /* ... */ }
});
```

## Seeing what happens

- `/axion carry` lists, on the server, who is carried, by which assembly, on which body, and at what delay.
- `/axion debug supports on` draws, in turquoise, a line from the carried player's place to the origin of the body that holds them.

## Settings

| Key | Default | Role |
|-----|---------|------|
| `physics.player_carry` | `true` | everything above; `false` gives back the old game |
| `physics.push_speed_max` | `10` | the speed at which an assembly pushes a player, in m/s |
| `physics.player_proxy_mass` | `80` | the player's mass for physics, in kg; `0`: a wall |
| `physics.carry_max_entities` | `64` | the cap on entities, players excepted, carried by one assembly |
| `net.carry_tolerance` | `0.25` | the gap at which the server recognises a player on an assembly, in blocks |

## Good to know

- Carrying a player has been tried in single player, creative and survival, over a ride of about a second. A dedicated server, two
  players and a long ride have not been tried yet.
- `carry` is declared in the definition: it cannot be changed assembly by assembly in game.
