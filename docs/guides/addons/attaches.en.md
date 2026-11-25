# Attachments

An **attachment** links two assemblies: a trailer to its car, a car towed on a rope, a module mounted on a machine, a
winch hooked to a tree. It is made and undone in game; your definition only declares **where** your model can attach,
and **how**. These points are called **sockets**.

## 1. The kinds of attachment

| Kind | What it does | Example |
|---|---|---|
| `rigid` | welds the two sockets together | a mounted module, a car strapped on a flatbed |
| `hitch` | a ball joint pivoting within a cone | a trailer hitch |
| `rope` | a flexible maximum distance | towing on a rope, mooring |
| `winch` | a rope whose length is driven | a winch that reels in and out |
| `magnet` | an attachment that exists while a variable is true | a crane electromagnet |

Putting a player or an entity on board is the **seats'** job, not the attachments'.

## 2. Place a socket in Blender

A socket is a node of your model — an **empty** is the simplest — placed where the attachment happens: the hitch ball,
the towing ring, the cable outlet of a winch.

1. Add an empty, place it at the attachment point and give it a clear name: `attelage`.
2. Parent it to the part that carries the attachment (the chassis, a crane's arm).
3. Give it the `socket` role. In Blender's Python console, with the empty selected:

    ```python
    bpy.context.object["axion"] = {"role": "socket"}
    ```

    A node with the `socket` role is never drawn. The role is **mandatory**: a socket naming a node without that role
    refuses the definition, naming the node.
4. On glTF export, tick `Include` → `Custom Properties`.

## 3. Declare your sockets

At schema 6, each socket is an object. The shortest form is enough:

```json
"sockets": [ { "name": "attelage", "accepts": ["hitch", "rope"] } ]
```

Without `node`, the socket sits on the node that bears its name. A fuller example:

```json
"sockets": [
  { "name": "boule", "node": "socket_towbar", "accepts": ["hitch", "rope"],
    "tag": "ball_50", "mates": ["coupler_50"], "max_force": 60000, "hitch": { "cone": 40 } },
  { "name": "treuil", "node": "sortie_cable", "accepts": ["winch"], "anchor": true,
    "winch": { "speed": 0.8, "min_length": 0.5, "max_length": 30, "variable": "treuil" },
    "transfer": { "power": { "pdf": "pto_in" } } }
]
```

| Field | What it sets | Bounds |
|---|---|---|
| `name` | the socket's name, unique in the definition | — |
| `node` | the model node, with the `socket` role; the socket's name without it | a node of the model |
| `accepts` | the attachment kinds the socket takes | at least one of the five |
| `tag` | the socket's shape (a 50 mm ball, a hook) | simple name `[a-z0-9_.-]` |
| `mates` | the shapes it mates with; all without it | 16 simple names |
| `max_force`, `max_torque` | beyond them the attachment breaks, in N and N·m | positive |
| `misalignment` | the gap between both sockets, in metres, beyond which the attachment breaks | 0.001 to 16 |
| `anchor` | the socket can hook onto a block of the world | — |
| `hitch.cone` | how far the ball joint swings to each side, in degrees, on its two swing axes; it twists freely | 0 to 180 |
| `rope.length` | the rope's length, in metres | 0.1 to 256 |
| `winch.speed` | the reeling speed, in m/s | 0 to 20 |
| `winch.min_length`, `winch.max_length` | the cable's extreme lengths | 0.1 to 256 |
| `winch.variable` | the variable that drives the winch | a variable of the definition |
| `magnet.variable` | the variable that switches the magnet on | a variable of the definition |
| `transfer.power` | the power take-off: each variable of your definition, copied into the named variable of the other assembly | 16 at most |
| `transfer.damage` | the share of a shock that crosses the attachment | 0 to 1 |

Thirty-two sockets at most.

**Two sockets attach** if they share a kind, and if each mates with the other's shape when it declares `mates`. When
both declare the parameters of the same kind, those of the socket the attachment starts from win; for the breaking
thresholds, the weaker of the two applies.

!!! warning "Refused, never guessed"
    A parameter block for a kind the socket does not accept — a `rope` on a socket that only accepts `hitch` — refuses
    the definition and names the field, like a winch whose `max_length` is not above `min_length`.

!!! note "Damage transfer"
    `transfer.damage` is declared and kept from now on; the shock only crosses the attachment from the impacts
    milestone (M6), when AXION measures a shock's energy.

The Java builder has the same fields, generated from the same schema:

```java
.socket("attelage", new String[] {"hitch", "rope"}, s -> s.tag("ball_50").maxForce(40000.0)
        .hitch(h -> h.cone(40.0)))
```

## 4. The `attach` and `detach` interactions

A player attaches and detaches by clicking a node of your model. The interaction names the socket it targets:

```json
"interaction": [
  { "node": "attelage", "action": "attach", "socket": "attelage", "kinds": ["hitch"], "distance": 3 },
  { "node": "attelage", "action": "detach", "socket": "attelage" }
]
```

| Field | For | What it sets |
|---|---|---|
| `socket` | `attach`, `detach` | the targeted socket, mandatory |
| `kinds` | `attach` | the kinds this gesture allows, among those the socket accepts; all without it |
| `distance` | `attach` | how far to look for the other socket, in metres (0.1 to 16) |

When a player clicks, `attach` takes the **nearest free compatible** socket of another assembly, within `distance`
blocks of yours (`attachment.snap_distance`, 2 by default). If it finds none and your socket anchors to the world, it
hooks onto the **block the player is looking at**. The player reads at the bottom of the screen what was done, or why
nothing was. `detach` undoes the socket's attachment.

The kind chosen is the first both sockets accept, in the order `rigid`, `hitch`, `rope`, `winch`, `magnet`.

## 5. What is decided when attaching

| Quantity | Where it comes from |
|---|---|
| a rope's length | `rope.length` of one socket, the one the attachment starts from first; without it, the distance between both sockets when attaching |
| a winch's starting length | the distance between both sockets, brought within its bounds |
| a winch's bounds and speed | `winch.*`; without them: 0.5 to 30 blocks, 1 block/s |
| a hitch's angle | `hitch.cone`; without it: 45° |
| breaking | the weaker of both `max_force`, both `max_torque`, both `misalignment` |

A `rigid`, `hitch` or `magnet` attachment whose sockets drift apart by more than `misalignment` for one second breaks. A
rope drifts apart by nature: only its force breaks it.

A taut `rope` stretches like a tow strap, a few centimetres under load: slack then pulled tight at once, it cushions the
jolt instead of passing it on in an instant. A winch cable stays stiff, to follow its length. A rope therefore takes the
jerks of keyboard driving better than a rigid link; it never pushes: a towed vehicle that does not brake catches up
with the one pulling it.

## 6. The winch, the magnet, the power take-off

- **The winch** follows its variable (`winch.variable`): **negative**, it reels in down to its shortest length;
  **positive**, it reels out up to the longest; **zero**, it keeps the length it has. Bind the variable to a seat
  command (see [Tune a vehicle](regler.md)) and the player drives the winch.
- **The magnet** only exists while its variable (`magnet.variable`) is true: switched off, it lets go; it cannot be
  attached while off.
- **The power take-off** copies, every tick, each variable of `transfer.power` into the named variable of the other
  assembly. A tractor whose `pdf` turns drives the implement that reads `pto_in`, in its animations or controllers.

## 7. Through the Java API

`assembly.attachments()` makes, anchors, undoes and reads an assembly's attachments, on the server:

```java
AttachResult result = tower.attachments().attach("attelage", trailer, "timon");
if (!result.attached()) {
    LOGGER.info("no hitch: {} — {}", result.status(), result.message());
}
tower.attachments().anchor("treuil", 120, 64, -35);   // to a block
tower.attachments().setWinchLength("treuil", 4.0);    // the winch's target length
tower.attachments().detach("attelage");
```

`attach` takes a fourth argument to impose the kind (`"rope"`). The result says why an attachment is refused:

| Status | Why |
|---|---|
| `UNKNOWN_SOCKET` | a named socket does not exist |
| `INCOMPATIBLE` | no common kind, or a shape the other does not mate with |
| `SOCKET_BUSY` | one of the two sockets is already attached |
| `CYCLE` | both assemblies are already linked, directly or through a chain (`E-8030`) |
| `CHAIN` | the chain would exceed `attachment.max_chain` attachments end to end (8) |
| `UNREACHABLE` | two dimensions, the same assembly, or a definition not compiled yet |
| `NOT_ANCHORABLE` | the socket does not declare `anchor` |
| `MAGNET_OFF` | a magnet switched off |
| `REFUSED` | a listener refused it |

`list()` returns each attachment: the other end or the block, the kind, the length, the gap and the tension of the last
tick.

**Following and refusing**: a mod registers a listener with the rest of its content,

```java
registrar.registerAttachmentListener(new AttachmentListener() {
    @Override
    public boolean allow(Assembly assembly, String socket, Optional<Assembly> other, String otherSocket, String kind) {
        return !"magnet".equals(kind) || isAllowed(assembly);
    }

    @Override
    public void detached(Assembly assembly, String socket, Optional<Assembly> other, String otherSocket, String cause) {
        // cause: detached, broken, misaligned, magnet_off or removed
    }
});
```

It sees every attachment of the server, a player's as well as another mod's. An exception is logged naming your mod
and refuses nothing; `content.listener_strikes` consecutive exceptions (20) disable the listener for the session
(`E-1012`).

## 8. Commands

| Command | Effect |
|---|---|
| `/axion attach <target> <socket> <other> <other_socket>` | attaches two assemblies |
| `/axion anchor <target> <socket> <x y z>` | anchors a socket to a block |
| `/axion detach <target> <socket>` | undoes a socket's attachment |
| `/axion attaches <target>` | lists an assembly's attachments, their length, gap and tension |
| `/axion defs info <definition>` | among the rest, tells each socket of a sheet: the kinds it accepts and what it declares |
| `/axion debug sockets on` | draws each socket's frame in game (red x, green y, blue z): where an attachment takes hold |

## 9. Saving and chunks

An attachment is saved with the assembly it starts from. If the other end is unloaded — its chunk, the player gone —
or moves to another dimension, the attachment is **suspended**, not deleted: it comes back when the other end does.
When the server restarts, each attachment is restored as soon as both its ends are loaded. Only destroying an end
undoes it for good.

Attached assemblies form an **ensemble**, which loads and unloads as a whole: while one of its members is within a
player's simulation distance, the chunks of all its members stay loaded. A trailer is not left alone at the edge of a
chunk while its car drives on; once no member is within reach, the whole ensemble unloads, two seconds later.

## 10. What the player sees

A rope or a winch cable is drawn between both sockets, or down to the anchor block: **taut**, straight; **with slack**,
it sags accordingly, and lies on the ground it meets. A rigid attachment, a hitch or a magnet has nothing to draw: both sockets touch.

Your procedural animations read attachments, like wheels or variables:

| Source | What it reads |
|---|---|
| `attach.<socket>.attached` | 1 if the socket is attached, 0 otherwise |
| `attach.<socket>.length` | its rope's length, in blocks |
| `attach.<socket>.tension` | the force it transmits, in newtons |
| `socket.<socket>.misalignment` | the gap between both sockets of a rigid attachment, a hitch or a magnet, in blocks |

A winch drum that turns with the cable's length:

```json
{ "node": "tambour", "channel": "rotation", "axis": "x", "source": "attach.treuil.length", "scale": -12.5 }
```
