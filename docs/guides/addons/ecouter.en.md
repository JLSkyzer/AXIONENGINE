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
