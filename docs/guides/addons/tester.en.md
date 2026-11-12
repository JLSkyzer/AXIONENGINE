# Test your addon

Two safety nets keep you from discovering a mistake while playing: the **validator**, which checks your content
without launching the game, and **GameTests**, which bring it to life in a real server. The addon template already has
both. And in game, a command tells you what is loaded, and why the rest is not.

## 1. The validator, without launching the game

```bash
./gradlew checkAxionContent
```

It reads your `src/main/resources/axion/` folder and applies **exactly** the game's rules: it runs AXION's engine,
without Minecraft, and compiles each of your models and each of your sheets against its model. When everything is
fine:

```text
AXION — vérification du contenu de example_addon (…/src/main/resources/axion)
  1 models
  2 definitions
Aucune faute : le contenu se charge comme en jeu.
```

Otherwise, each fault is named, in the words of the game log (AXION's logs are in French):

```text
  FAUTE : example_addon:plume — E-7001 example_addon:axion/definitions/plume.json — $.physics.mass : -5 hors de [0.001, 1000000]
  FAUTE : example_addon:fantome — E-7001 example_addon:axion/definitions/fantome.json — $.asset : modèle introuvable : example_addon:axion/models/absent.glb
  FAUTE : example_addon:axion/models/casse.glb — compilation échouée (import : source gltf mal formée : …), code -3050 ; remplacé par axion:builtin/missing
```

!!! question "Why does my build fail?"
    `./gradlew build` runs the validator: content that would not load in game fails the build, and says why. That is
    on purpose — better to learn it there than in front of a missing object.

    ```groovy
    --8<-- "java/addon-temoin/modele/build.gradle:verification"
    ```

!!! info "What it does not see"
    A sheet written in Java with the *builder* only exists when your mod runs. The GameTests below cover it.

## 2. GameTests, in a real server

A GameTest sets up a scene in a world, waits, and checks. AXION gives you the helpers, in
`dev.axion.api.testing.AxionGameTests`:

```java
--8<-- "java/addon-temoin/src/main/java/com/example/addon/ExampleAddonGameTests.java:test"
```

| Helper | What it does |
|---|---|
| `assertLoaded(helper, id)` | fails the test, with the reason, if the sheet is not loaded |
| `spawn(helper, id, position)` | spawns the object, at a position relative to the slab |
| `succeedWhenAtRest(helper, object)` | succeeds once the object stops moving |
| `succeedWhenMoved(helper, object, distance)` | succeeds once the object has moved at least that far |

Run them:

```bash
./gradlew runGameTestServer
```

The server starts, plays the tests, says `All 2 required tests passed :)` and stops. A failing test writes its reason
to the log.

!!! question "Where does the slab come from?"
    `AxionGameTests.DALLE` names a 9 × 9 slab that the addon template stores in
    `src/main/resources/data/example_addon/structures/dalle.nbt`. Forge looks for a test's template in the namespace of
    the mod that holds it: if you rename your mod, rename this folder too.

## 3. In game: what is loaded, and why the rest is not

An object that does not show up? Ask the game, without leaving your world (you need to be an operator):

```text
/axion defs list
```

Here, with a `plume.json` sheet whose mass is negative:

```text
AXION ENGINE — definitions : 3 chargée(s), 1 refusée(s)
  refusée : E-7001 example_addon:axion/definitions/plume.json — $.physics.mass : -5 hors de [0.001, 1000000]
  example_addon (3) : crate, heavy_crate, upgradable_crate
```

Refusals come first: they are what you are looking for. For one sheet:

```text
/axion defs info example_addon:crate
```

```text
AXION ENGINE — example_addon:crate
  genre : RIGID_OBJECT
  modèle : example_addon:axion/models/crate.gltf
  schéma : 3
  enregistrée par : example_addon — axion/definitions/crate.json
  empreinte : …
```

For a refused sheet, the answer is its reason, the same as in the log and the validator. The Tab key completes names,
refused sheets included.
