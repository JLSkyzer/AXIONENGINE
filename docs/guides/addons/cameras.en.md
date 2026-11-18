# Vehicle cameras and dashboard

When a player sits in your vehicle, AXION gives them **views** — the driver's eye, a camera that follows behind — and a
**dashboard** on screen: speed, engine speed, gear. Without writing anything, your vehicle already has both. This page
shows how to choose your own views, place a camera on your model in Blender, add gauges, and draw a whole dial in
Java.

The Java excerpts come from the witness addon, compiled and tested on every change to AXION.

## 1. What your vehicle already has

Each seat has two views, which the player cycles with **F5** (Minecraft's perspective key, as they assigned it):

| View | What you see |
|---|---|
| `cockpit` | the player's own eye, seated; they look around freely |
| `poursuite` (follow) | behind the vehicle, at a distance taken from its size; the mouse orbits around it, and the view swings back behind after 1.5 s without mouse movement |

On foot, F5 stays Minecraft's. Getting out, the player gets back the perspective they had before getting in.

The driver also sees the default dashboard, bottom right: speed, engine speed, gear. A passenger does not, unless your
definition says so (section 4).

## 2. Declare your views

Your definition (schema 5) can name its own views in `cameras`. As soon as the list is there, it **replaces** the two
default views: F5 cycles yours, in the order you write them.

```json
"cameras": [
  { "name": "pilote", "kind": "cockpit", "seats": ["conducteur"], "eye": [0, 0.62, 0.05] },
  { "name": "loin", "kind": "follow", "distance": 9, "height": 2.5, "smoothing_ms": 250 },
  { "name": "capot", "kind": "fixed", "node": "cam_capot", "fov": 80, "look": { "yaw": 60, "pitch": 30 } }
]
```

Three kinds of view:

- **`cockpit`** — in the occupant's place. Without `eye`, it is the player's eye, as in Minecraft. With `eye`, the
  camera sits at the seat's node, offset by `[x, y, z]` metres in its frame.
- **`follow`** — the chase view. It looks the way the vehicle is moving (its heading when stopped and when reversing),
  smoothed over time; a block between the vehicle and the camera pulls it closer, like Minecraft's third person.
  Without `distance` or `height`, it frames itself on the vehicle's size — and what is attached to it: a car towing
  a trailer is framed together with it, the camera aiming at the middle of both.
- **`fixed`** — attached to a node of your model, which it follows as it moves: a bonnet, a bumper, a mirror, a crane's
  basket. Section 3 shows how to place it.

| Field | For | What it sets | Bounds |
|---|---|---|---|
| `name` | all | the view's name, unique in the definition | — |
| `kind` | all | `cockpit`, `follow` or `fixed` | — |
| `seats` | all | the seats that have this view; all without it | seats of the definition |
| `fov` | all | the field of view, in degrees; the player's own without it | 30 to 110 |
| `eye` | `cockpit` | the eye offset, in metres, in the seat's frame | ±4 m |
| `distance` | `follow` | how far behind, in metres | 0.5 to 64 |
| `height` | `follow` | the height above the vehicle, in metres | -8 to 32 |
| `smoothing_ms` | `follow` | how long the direction takes to follow the vehicle (200 without it) | 0 to 5,000 |
| `recenter_ms` | `follow` | time without mouse movement before swinging back behind (1,500 without it) | 0 to 5,000 |
| `node` | `fixed` | the model node the camera sits on | a node of the model |
| `look` | `cockpit`, `fixed` | how far the gaze turns: `yaw` to each side, `pitch` up and down, in degrees | 0 to 180 |

Without `look`, the gaze is free in the cockpit and fixed on a fixed view. Sixteen views at most. In game,
`/axion defs info <your definition>` lists the views and the dashboard AXION kept.

!!! warning "Refused, never guessed"
    A field that does not belong to the view's kind — a `distance` on a cockpit, a `node` on a follow view — refuses
    the definition and names the field, like a value out of bounds. You see it at load time, in the log, rather than
    in game.

The Java builder has the same fields, generated from the same schema:

```java
.camera("loin", "follow", c -> c.distance(9.0).height(2.5))
```

## 3. Place a camera on your model, in Blender

A `fixed` view looks from a node of your model. The simplest is an **empty**:

1. Add an empty (`Add` → `Empty` → `Arrows`), place it where the lens should be, and give it a clear name:
   `cam_capot`.
2. Orient it: in Blender, the view looks along its **Y** arrow and its up is its **Z** arrow. An empty without rotation
   therefore looks towards the scene's +Y. (On export, Blender converts its axes to the glTF frame, where that gaze
   becomes the node's −Z, a glTF camera's: that is what AXION reads.)
3. Parent it to the part it should follow (a door, a basket): the view follows that node, not only the body.
4. Give it the `camera` role. In Blender's Python console, with the empty selected:

    ```python
    bpy.context.object["axion"] = {"role": "camera"}
    ```

    A node with the `camera` role is never drawn nor touched by collisions. The role is not mandatory — a `fixed` view
    can name any node —, but without it, a node carrying geometry would be drawn.
5. On glTF export, tick `Include` → `Custom Properties`: that is how the role reaches AXION.

In your definition, the view names the node:

```json
{ "name": "capot", "kind": "fixed", "node": "cam_capot", "look": { "yaw": 45, "pitch": 20 } }
```

## 4. The dashboard

The dashboard is set in `dashboard`:

```json
"dashboard": {
  "default": true,
  "seats": ["conducteur", "copilote"],
  "gauges": [
    { "name": "turbo", "kind": "dial", "source": "var.boost", "min": 0, "max": 2,
      "label": "gauge.myaddon.turbo", "unit": "bar", "anchor": "bottom_left" },
    { "name": "sport", "kind": "light", "source": "var.sport", "label": "gauge.myaddon.sport",
      "anchor": "bottom_left" }
  ],
  "widgets": ["example_addon:tachometer"]
}
```

- **`default`**: `false` removes the default dashboard (speed, engine speed, gear); your gauges stay.
- **`seats`**: the seats that see the dashboard. Without it, the driver only.
- **`gauges`**: your gauges, 32 at most.
- **`widgets`**: dials drawn in Java by a mod (section 5), 16 at most.

A gauge has a kind, a source, and the rest is optional:

| Kind | What it draws |
|---|---|
| `dial` | a needle dial, from `min` to `max` |
| `bar` | a bar filling from `min` to `max` |
| `text` | the value, written, followed by its `unit` |
| `light` | a warning light, lit when the value reaches `min` (0.5 without it) |

The **source** is the one procedural animations use: `vehicle.speed`, `engine.rpm`, `transmission.gear`,
`wheel.<wheel>.compression`, `part.<part>.health`, `var.<variable>` for a variable of your definition, and the others
of the same list. A malformed source refuses the definition, and so does a dial or bar without `min` and `max` — or
whose `max` is not above `min`.

| Field | What it sets | Bounds |
|---|---|---|
| `label` | a **translation key**, which your mod translates in its language files | — |
| `unit` | the text written after the value | — |
| `min`, `max` | the range of the dial or bar; a light's threshold | — |
| `anchor` | the screen corner or edge: `top_left`, `top`, `top_right`, `left`, `center`, `right`, `bottom_left`, `bottom`, `bottom_right` | — |
| `offset` | an `[x, y]` offset from the anchor, in GUI pixels | ±4,096 |
| `size` | the size, in GUI pixels (48 without it) | 8 to 512 |

Without `offset`, gauges sharing an anchor stack one above the other, towards the inside of the screen, never over the
hotbar. The default dashboard comes first in the bottom right corner.

## 5. Draw a dial in Java: a widget

A declared gauge covers the common cases. For a dial of your own — a tachometer with its red zone, a GPS, a radio
screen —, your mod registers a **widget**: a function AXION calls every frame, on the client, with a canvas to draw on
and the vehicle as the player sees it.

The witness draws a tachometer. Its identifier:

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleTachometer.java:id"
```

The widget itself: a graduated arc, a needle, the engine speed written below. It also gives its size and anchor:

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleTachometer.java:widget"
```

It registers with the rest of your content:

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleTachometer.java:register"
```

and your definition names it in `dashboard.widgets`: `"widgets": ["example_addon:tachometer"]`.

What the **canvas** (`DashboardCanvas`) can do:

| Method | Effect |
|---|---|
| `width()`, `height()` | the widget's size, in GUI pixels; the origin is top left |
| `fill(x, y, w, h, argb)` | a filled rectangle |
| `line(x1, y1, x2, y2, argb)` | a segment — a needle, a tick |
| `text(text, x, y, argb)` | a text as is |
| `translated(key, x, y, argb, arguments…)` | a text translated into the player's language |
| `textWidth(text)`, `lineHeight()` | what you need to centre a text |
| `texture(resource, x, y, w, h)` | an image of your mod, stretched to the rectangle |

Whatever leaves your rectangle is clipped. The scale the player chose is already applied: you always draw in GUI
pixels.

What it **reads** (`VehicleView`): the same vehicle as a controller ([Tune a vehicle](regler.md), section 6) — speed,
engine speed, gear, driver input, wheels, variables and parameters. For the driver, these are the values their client
predicts; for a passenger, the ones they see on screen.

**Its rules**:

- it draws from what it reads and keeps nothing from one frame to the next;
- no Minecraft class appears in it: the widget loads on a server without a screen, and is tested without the game
  (section 6);
- the mod that registers it is required from players as soon as a definition names it, as for a controller; a
  definition naming a widget no mod registers is refused at load time.

**Its cost is measured**, frame by frame. A frame over budget (`hud.widget_budget_ns`, 200 µs by default) or an
exception counts a strike; twenty in a row **disable the widget for the session**, and the log says so, naming your mod
(`E-1011`). The witness's tachometer takes about 70 µs per frame.

!!! tip "Lots of small strokes?"
    Each canvas call costs next to nothing: AXION groups them and sends them to the graphics card in one go. A
    hundred-pixel segment is still a single `line` call.

## 6. Test it

A canvas that records what it is asked is enough to check a widget, without a screen. The witness's GameTest draws the
tachometer at 3,500 rpm — half its red line — and checks that the needle points straight up and that the engine speed
is written:

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleAddonGameTests.java:tachometer"
```

You run it like the other GameTests ([Test your addon](tester.md)).

## 7. What the player sets

The player keeps control of what they see, in the `[hud]` section of `axion-client.toml`
([Configuration](../CONFIGURATION.md)):

| Option | Effect |
|---|---|
| `hud.dashboard` | `false` hides the whole dashboard, yours included |
| `hud.dashboard_scale` | its scale, from 0.5 to 2 |
| `hud.speed_unit` | `kmh` or `mph` for the default dashboard's speed |

**F1** hides the dashboard with the rest of the GUI. These options reload without a restart.

!!! info "What about other camera mods?"
    If another mod also moves the camera, AXION lets it have its way and says so once in the log. Views do not go
    through the server: they are the player's choice, which the server ignores.
