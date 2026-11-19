# Listening to and refusing a gesture

Your mod can follow what players do with assemblies — sitting, getting off, clicking an interaction — and
**refuse** a gesture before it happens: a locked car, a seat reserved to a rank, a button pressed only once
([ADR-174](../../decisions/ADR-174.md)). Attachments have their own listener, described in [Attachments](attaches.md).

## Registering a listener

Like a controller, a listener is registered with the rest of your content:

```java
registrar.registerSeatListener(new SeatListener() {
    @Override
    public String allowEnter(Assembly assembly, String seat, Entity passenger) {
        boolean locked = assembly.variables().getBoolean("locked");
        return locked && seat.equals("driver") ? "mymod.car.locked" : null;
    }

    @Override
    public void exited(Assembly assembly, String seat, Entity passenger, String cause) {
        // cause: "dismounted" (they got off) or "ejected" (AXION made them get off)
    }
});

registrar.registerInteractionListener(new InteractionListener() {
    @Override
    public String allow(Assembly assembly, String node, Player player, String action) {
        return node.equals("trunk") && !player.isCreative() ? "mymod.trunk.reserved" : null;
    }
});
```

- **Judging**: `allowEnter`, `allowExit` (a wanted dismount) and `allow` (an interaction) return `null` to let it
  happen, or the **translation key** of the reason. The player reads it in the action bar; the server log names your
  mod.
- **Following**: `entered`, `exited` and `interacted` come afterwards.
- Every method has a default: only override what interests you.

## The rules

- Listeners are called **on the server**, on its thread, in mod order then registration order. The **first refusal
  wins**.
- An **ejection** cannot be refused: a seat that disappears, the passenger's death (R-712). The listener is only told,
  with the cause `ejected`.
- An **exception** is logged naming your mod, and refuses nothing. After `content.listener_strikes` consecutive
  exceptions (20), the listener is **disabled for the session** (`E-1012`).

## Content events

From schema 7, a definition declares its events, and who each one goes to:

```json
"events": {
  "horn": { "replicate": "occupants" },
  "door_slam": { "replicate": "all" },
  "counter": { "replicate": "none" }
}
```

They are emitted by the action `{ "emit_event": { "event": "door_slam", "value": 1 } }` of an interaction list, by an
interaction `"action": "emit_event", "event": "horn"`, or by your mod: `assembly.emit("horn", 1.0)`. An event the
definition does not declare is refused, naming itself.

```java
registrar.registerContentEventListener(new ContentEventListener() {
    @Override
    public void onEvent(Assembly assembly, String event, double value) {
        // on the server, in the tick of the emission
    }

    @Override
    public void onClientEvent(Entity assembly, String event, double value) {
        // on a client the event is sent to: a sound, particles
    }
});
```

- `replicate`: `all` (default) to the players who track the assembly, `occupants` to its occupants only, `none` to
  the server alone.
- An event is an **instant**: it is not saved. For a lasting state, use a variable.
- A player receives at most `net.events_per_player_tick` per tick (16).

## Sensors

A sensor is a volume placed at a node, which blocks nothing and sees other assemblies and entities (players,
creatures) enter and leave. From schema 7 ([ADR-174](../../decisions/ADR-174.md) D5):

```json
"sensors": [
  { "name": "door", "node": "threshold", "shape": "box", "size": [1.0, 2.0, 0.2] },
  { "name": "airlock", "node": "threshold", "shape": "sphere", "radius": 1.5, "replicate": "occupants" }
]
```

- A box has its full size (`size`), a sphere its radius (`radius`), in blocks; at most 16 sensors.
- Each sensor declares two events, `sensor.door.enter` and `sensor.door.exit`, whose value is the number of things
  inside after the change; `replicate` says who they are sent to, as for a declared event. A `trigger` names them
  (`event:sensor.door.enter`), your listener receives them like the others.
- The source `sensor.door.count` reads what the sensor counts, on the server as on the client.
- The other bodies of the same assembly do not count, nor does the terrain. An assembly that disappears inside it
  leaves it.
