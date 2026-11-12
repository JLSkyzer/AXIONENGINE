# Getting started with AXION

Want to add your own objects or vehicles to Minecraft with AXION? This path is for you, even if you have never
written a mod. Each page explains **what you do** and **why you do it**: by the end, you will be able to read the
rest of the documentation without getting lost.

!!! tip "No need to be a developer"
    To add an object, you write almost no Java: you put a 3D model and a small text file (JSON) into a ready-made mod.
    Java comes later, when you want to go further.

## The path

| Step | What you learn | Time |
|---|---|---|
| [1. Understand AXION](comprendre.md) | What a mod, an addon and a definition are — and how they fit together | 10 min |
| [2. Install your tools](outils.md) | Java 17, a code editor, Blender for 3D models | 20 min |
| [3. Your first addon](premier-addon.md) | Declare a crate in JSON then in Java, and reload it without restarting | 30 min |
| [4. Test your addon](tester.md) | Check your content without launching the game, then in a real server | 15 min |
| [5. Versions](versions.md) | Say which AXION version you need, and what a player without it sees | 5 min |
| [6. Tune a vehicle](regler.md) | Change the engine, the brakes or the weight of one vehicle, like an upgrade kit | 15 min |
| [Glossary](glossaire.md) | AXION's words, explained simply | keep it at hand |

Times are indicative: take the time you need.

!!! info "A word with a dotted underline?"
    Hover over it: a bubble explains what it means. All these words are also in the [glossary](glossaire.md).

## If you get stuck

When something does not work, the **game log** almost always says why: AXION writes each error there, with the mod,
the file and the line at fault. The log is `logs/latest.log`, in the game folder.
