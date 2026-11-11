# Install your tools

Three tools are enough, all free. Install them in this order.

## 1. Java 17

Minecraft 1.20.1 and Forge 47 run on **Java 17**, neither older nor newer to build a mod. Install a JDK 17 (the
development kit, not just what runs Java), for example **Eclipse Temurin 17** from [adoptium.net](https://adoptium.net/).

Check in a terminal:

```bash
java -version
```

The answer must start with `openjdk version "17`.

!!! question "Why exactly 17?"
    A mod is compiled for a precise Java version. Minecraft 1.20.1 expects 17: a mod compiled for a newer version
    would refuse to load.

## 2. A code editor: IntelliJ IDEA

**IntelliJ IDEA** ([jetbrains.com/idea](https://www.jetbrains.com/idea/)), in its free edition, opens a mod project
as is: it understands Gradle, the tool that builds the mod and launches the game, completes your code and flags
errors as you type.

!!! question "And Gradle, do I install it?"
    No. Each mod project contains its own Gradle launcher (`gradlew`), which downloads the right version by itself on
    first launch, then Minecraft and Forge.

## 3. Blender, for 3D models

**Blender** ([blender.org](https://www.blender.org/)) is used to create the shape and colours of your objects. AXION
reads the **glTF 2.0** format: in Blender, *File › Export › glTF 2.0*, then pick `.glb` (a single file, the simplest)
or `.gltf`.

!!! tip "Not comfortable with Blender yet?"
    Start with a cube: the first addon only needs a simple shape. You will refine it later.

## Ready

With Java 17, IntelliJ IDEA and Blender, you have everything you need for [your first addon](premier-addon.md).
