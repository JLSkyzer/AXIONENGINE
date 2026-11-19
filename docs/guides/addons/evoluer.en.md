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

## Saves from before names

An assembly saved before schema 6 has no names. AXION names its bodies from the loaded asset — the node each one
carried — and matches its joints by rank, only if their number has not changed; the log says the state is "from before
names". At its next save it is at schema 6: no more matching by index.
