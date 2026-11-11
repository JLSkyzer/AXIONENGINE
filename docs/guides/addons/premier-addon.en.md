# Your first addon

On this page, you declare a **40 kg crate** to AXION, first with a JSON file, then in Java, and you reload it without
restarting the game. If a word escapes you, hover over it or open the [glossary](glossaire.md).

All excerpts come from AXION's **witness addon**
([`java/addon-temoin`](../../../java/addon-temoin/src/main/java/com/example/addon/ExampleAddon.java) on GitHub): a
real mod, compiled with every AXION build. What you read here works.

!!! info "The downloadable mod template is coming soon"
    For now, `axion-api` (the library your mod uses) is not published yet: the witness addon only runs from AXION's
    repository. With the release, this same witness will become a ready-to-use template that you copy and run on its
    own. Read this page now: nothing in it will change.

## 1. Tell Forge your mod needs AXION

Each mod has a `src/main/resources/META-INF/mods.toml` file that introduces it to Forge. AXION is added there as a
dependency:

```toml
--8<-- "java/addon-temoin/src/main/resources/META-INF/mods.toml:axion"
```

!!! question "Why `side = \"BOTH\"`?"
    AXION simulates objects on the server and draws them for each player: it must be installed on both sides, and so
    must your addon. Without AXION, Forge refuses to start the game and says which one is missing.

## 2. Lay out your content

Your content lives under `axion/`, at the root of the mod's resources. Each kind of content has its folder:

```text
src/main/resources/
└── axion/
    ├── definitions/         crate.json    ← your crate's sheet
    └── models/              crate.gltf    ← its 3D shape, exported from Blender
```

Other folders exist to go further: `physics_materials/`, `tires/`, `collision_groups/`, `block_materials/`.

!!! question "How does AXION name your crate?"
    From the file path, prefixed with the modId: `axion/definitions/crate.json` becomes `example_addon:crate`, and
    `axion/models/crate.gltf` becomes `example_addon:models/crate.gltf`. You never write these names by hand anywhere
    but in your own files.

## 3. Your crate's sheet

`src/main/resources/axion/definitions/crate.json`:

```json
--8<-- "java/addon-temoin/src/main/resources/axion/definitions/crate.json"
```

| Field | What it says |
|---|---|
| `schema` | the version of the sheet format; keep `3` |
| `asset` | the object's 3D model |
| `kind` | the kind: `rigid_object` for an object that falls and rolls, `vehicle` for a vehicle… |
| `physics.mass` | the mass, in kilograms (from 0.001 to 1,000,000) |

## 4. Send your content to AXION

This is the only Java code your first addon needs. When Forge loads your mod, it calls its constructor, which
registers for the moment mods send each other messages:

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleAddon.java:constructor"
```

At that moment, your mod sends AXION a **provider**: the piece of code AXION will call to learn your content.

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleAddon.java:provider"
```

`registerFolder("axion/")` sends your whole `axion/` folder. The next line shows the other way: the same kind of
sheet, written in Java with the *builder*, for a 400 kg crate.

!!! question "JSON or Java?"
    Both produce exactly the same sheet, checked the same way. JSON is enough to start with; Java is useful when you
    want to compute a sheet, for example ten variants of the same crate.

## 5. See your crate in game

Launch the game, open a world in creative mode, then type:

```text
/axion spawn example_addon:crate ^ ^1 ^3
```

Your crate appears three blocks in front of you and falls to the ground. `example_addon:heavy_crate` spawns the
400 kg one.

## 6. Change things without restarting

Restarting Minecraft for every try takes time. AXION can reload your content mid-game:

1. enable the `content.dev_reload` option in `config/axion-common.toml` (or launch the game with
   `-Daxion.content.dev_reload=true`);
2. after each change, copy your resources: `./gradlew processResources`, or your IDE's build;
3. type `/axion dev reload` in game.

AXION re-reads your folder and recompiles your models. Crates already placed keep their old sheet: spawn a new one to
see the change.

!!! warning "Your provider must be callable again"
    `/axion dev reload` calls your provider again. It must only register your content, nothing else.

## If it does not work

Open the game log (`logs/latest.log`) and look for your mod's name (`example_addon`) on the lines starting with
`AXION`. Each error there names the sheet or the file, and what is wrong: a duplicate, an out-of-bounds field, a
missing model. The faulty sheet is dropped, the rest loads.
