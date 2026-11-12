# AXION addon template

A ready-to-run Forge 1.20.1 mod that adds content to [AXION ENGINE](https://axionenginedocs.eriniumlab.com/): a 40 kg
crate declared in JSON and a 400 kg crate declared in Java. It is AXION's witness addon, compiled with every AXION
build, so it is known to work.

Step-by-step guide (English and French): <https://axionenginedocs.eriniumlab.com/en/addons/>

## Run it

You need Java 17 and nothing else: Gradle, Minecraft, Forge and AXION are downloaded on first launch.

```bash
./gradlew runClient
```

On Windows, use `gradlew.bat runClient`. In game, open a creative world and type:

```text
/axion spawn example_addon:crate ^ ^1 ^3
```

## Make it yours

| What | Where |
|---|---|
| Your content | `src/main/resources/axion/definitions/*.json` and `src/main/resources/axion/models/*.glb` |
| The Java code | `src/main/java/com/example/addon/ExampleAddon.java` |

To rename the mod from `example_addon` to your own id (lowercase letters, digits and `_`):

1. `gradle.properties`: `mod_id` and `mod_group_id`;
2. `src/main/resources/META-INF/mods.toml`: `modId`, `displayName`, and the three `[[dependencies.example_addon]]`;
3. `ExampleAddon.java`: `MOD_ID`, and the model id `example_addon:models/crate.gltf`;
4. `src/main/resources/axion/definitions/crate.json`: the model id in `asset`;
5. rename the folder `src/main/resources/data/example_addon` to your mod id: it holds the slab your GameTests stand on.

Your content then becomes `yourmod:crate`.

## Test it

`./gradlew runGameTestServer` runs the addon's GameTests (`ExampleAddonGameTests.java`) in a game server, then stops:
the crate must load, fall onto the slab and come to rest.

## Change things without restarting

The development runs enable `/axion dev reload`: after a change, run `./gradlew processResources` (or build from your
IDE), then type `/axion dev reload` in game.

## License

Apache-2.0, like AXION ENGINE.
