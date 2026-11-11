# Your first addon

An AXION addon is an ordinary Forge mod that declares content to AXION: definitions (what an object or a vehicle
is), their 3D models, physical materials, tires. AXION reads nothing on its own: it only knows what mods send it
([ADR-162](https://github.com/JLSkyzer/AXIONENGINE/blob/master/docs/decisions/ADR-162.md), in French).

This page declares a 40 kg crate, first in JSON, then in Java.

## 1. Depend on AXION

In `META-INF/mods.toml`, AXION is required on both sides, client and server:

```toml
[[dependencies.mymod]]
    modId = "axion"
    mandatory = true
    versionRange = "[1.0,)"
    ordering = "AFTER"
    side = "BOTH"
```

In `build.gradle`, the API only, at compile time:

```groovy
dependencies {
    compileOnly "dev.axion:axion-api:<version>"
}
```

!!! warning "Never embed `axion-api`"
    AXION provides the API in game. An addon that embeds it in its JAR puts the `dev.axion.api` package in two mods at
    once.

!!! info "Not published yet"
    The `dev.axion:axion-api` artifact is not published yet: it will be with slice 5 of ADR-162, and this page will
    then say where to get it.

## 2. Lay out the content

Content lives under `axion/`, at the root of the mod's resources:

```text
src/main/resources/
└── axion/
    ├── definitions/         crate.json
    ├── models/              crate.glb
    ├── physics_materials/
    ├── collision_groups/
    ├── block_materials/
    └── tires/
```

The namespace is the modId: `axion/definitions/crate.json` becomes the definition `mymod:crate`, and
`axion/models/crate.glb` the model `mymod:models/crate.glb`. A mod only declares in its own namespace.

## 3. Send the content to AXION

The mod sends AXION a `ContentProvider` through Forge's inter-mod messages, during `InterModEnqueueEvent`:

```java
@Mod("mymod")
public final class MyMod {

    public MyMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::send);
    }

    private void send(InterModEnqueueEvent event) {
        InterModComms.sendTo(AxionContent.MOD_ID, AxionContent.REGISTER,
                () -> (ContentProvider) registrar -> registrar.registerFolder("axion/"));
    }
}
```

AXION calls each provider once, on the main thread, in modId order. The `registrar` is only valid during the call:
do not keep it.

## 4. A definition, in JSON or in Java

Both forms produce the same document: same canonical form, same schema, same fingerprint.

=== "JSON"

    `src/main/resources/axion/definitions/crate.json`:

    ```json
    {
      "schema": 3,
      "asset": "mymod:models/crate.glb",
      "kind": "rigid_object",
      "physics": { "mass": 40 }
    }
    ```

    The folder is read by `registrar.registerFolder("axion/")`.

=== "Java"

    In the provider, with the builder generated from the schema:

    ```java
    registrar -> registrar.register("crate",
            DefinitionBuilder.rigidObject("mymod:models/crate.glb")
                    .physics(p -> p.mass(40))
                    .build())
    ```

    The builder checks types; bounds (a mass from 0.001 to 1,000,000 kg) are checked at load time, as for a file.

An error — duplicate, out-of-bounds field, missing model — is written to the game log with the mod, file or call at
fault; the faulty definition is dropped, the rest of the content loads.

## 5. Iterate without restarting

Enable `content.dev_reload` in `config/axion-common.toml` (or launch the game with
`-Daxion.content.dev_reload=true`), then, after each change:

1. copy the resources through the build: `./gradlew processResources`, or your IDE's build;
2. type `/axion dev reload` in game (operator).

AXION calls your provider again, re-reads your folder from disk and sends the registry to players. In singleplayer,
the client's models are recompiled too. Objects already placed keep their old definition: spawn a new one.

!!! tip "The provider must be callable again"
    `/axion dev reload` calls the same `ContentProvider` again. It must only register, with no other effect.
