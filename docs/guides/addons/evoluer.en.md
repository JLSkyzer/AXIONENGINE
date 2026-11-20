# Evolving your content

A saved assembly keeps its state — the pose of each of its bodies, the state of each of its joints — while your
definition changes: you add a door, remove a joint, reorder your bodies ([ADR-175](../../decisions/ADR-175.md)).
What is found again by its name is restored; the rest is reported, never applied to the wrong element.

## Bodies and joints are found by name

Since save schema 6, each body is stored under the name of its root node, and each joint under the name your definition
gives it (`joints[].name`). Reordering your bodies or joints, or changing a node's index by re-exporting your model, no
longer mixes anything up: "hood" keeps the state of "hood".

| What changes in your definition | What becomes of the saved state |
|---|---|
| a body or joint **renamed** | the old one is lost, the new one starts from its rest pose |
| a body or joint **added** | it takes its rest pose: a body, relative to the restored root body and at its velocity; a joint, intact |
| a body or joint **removed** | its state is lost |
| the **root body** | always restored: it is the assembly's pose |

Renaming is losing: the restoration does not know "door_left" is the old "door". Declared migrations, which rename
without losing, come with a definition's revision.

## What the log says

When something is lost or new, the server log says so **once per assembly**, under code `E-7012`:

```text
AXION: E-7012 the saved state of an assembly of mymod:car is restored in part:
lost — body "trunk", joint "hitch"; new, at its rest pose — body "door"
```

Nothing else changes: the assembly lives, at rest wherever it has no state.

## Revision, migrations and replacement

From schema 7, a definition declares its **revision** and what leads a save from one revision to the next
([ADR-175](../../decisions/ADR-175.md) D3):

```json
"revision": 3,
"migrations": [
  { "from": 1, "rename": { "nodes": { "door_l": "left_door" } } },
  { "from": 2, "rename": { "variables": { "lamp": "lights" }, "joints": { "pin": "hinge" } } }
],
"replaces": [ "mymod:old_car" ]
```

- **`revision`**: an integer, 1 by default. Raise it when you rename something a save names.
- **`migrations`**: one per revision you leave (`from`), which **rename** — `nodes` (bodies and node state), `joints`,
  `variables`, `seats` (the seats of a per-seat variable), `sockets` (of attachments), `parameters`. An assembly saved
  at revision 1, loaded under revision 3, goes through both. A migration creates and computes nothing. Its `from`
  precedes the definition's revision, and two migrations do not start from the same one.
- **`replaces`**: the identifiers of definitions this one replaces. An assembly saved under the old identifier links
  to it instead of staying inert; if a definition still carries that identifier, it keeps it. Without an alias, a
  removed definition leaves its assemblies inert and intact.

Content that changes **without** a new revision is restored by name, and the log says so once.

## Reloading without losing assemblies

When you reload your definitions (`/reload`, or on file save in development), assemblies already placed are **rebound**
to the new definition ([ADR-175](../../decisions/ADR-175.md) D5): they keep their position, velocity, bodies,
attachments and occupants. The log reports each rebind.

- **What is rebound**: an assembly whose definition changed content, or was replaced (`replaces`). An unchanged
  definition rebinds nothing.
- **How**: the state is taken as a save keeps it, carried from the assembly's revision to the definition's by your
  `migrations`, then read against the new definition, **by name**. What no longer exists is reported in the log and
  lost; what is new starts from its rest pose.
- **Occupants**: each keeps the seat carrying the same name, even if its rank changed. A removed or already taken seat
  gives them the first free one; with no room, they are ejected (`NO_SEAT`).
- **Pace**: `content.rebind_per_tick` (4 by default) assemblies per tick. The others live, until their turn, entirely on
  the previous definition — never on a mix of both.
- **One tick without a body**: the native body is removed then recreated on the next tick; the assembly has not moved.

## Saves from before names

An assembly saved before schema 6 has no names. AXION names its bodies from the loaded asset — the node each one
carried — and matches its joints by rank, only if their number has not changed; the log says the state is "from before
names". At its next save it is at schema 6: no more matching by index.
