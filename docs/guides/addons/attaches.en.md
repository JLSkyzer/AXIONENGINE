# Attachments

An **attachment** links two assemblies: a trailer to its car, a car towed on a rope, a module mounted on a machine, a
winch hooked to a tree. It is made and undone in game; your definition only declares **where** your model can attach,
and **how**. These points are called **sockets**.

!!! info "A growing page"
    This page first describes how to declare sockets (schema 6). The in-game gestures, the Java API, the winch and the
    power take-off are added as the attachment work proceeds.

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
| `hitch.cone` | the ball joint's angle, in degrees | 0 to 180 |
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

In game, `attach` will take the nearest free compatible socket of another assembly, or the targeted block for a socket
that anchors to the world: this gesture comes with the rest of the attachment work, and this page will detail it.
