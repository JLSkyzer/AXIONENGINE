# AXION ENGINE

Physics, deformation and 3D rendering for **Minecraft 1.20.1** and **Forge 47**. AXION is a generic engine: it knows
no content by name. Vehicles, objects and machines come from mods that declare them through the public `axion-api`,
in Java or with JSON files the mod loads.

!!! warning "In development"
    AXION is at milestone **M5** (vehicles). The API is marked `@Experimental` until that milestone ends: it may still
    change. Nothing has been released for players yet.

!!! info "Translation in progress"
    Pages that are not translated yet are shown in French.

## Where to start

<div class="grid cards" markdown>

-   **Addon development**

    ---

    Declare vehicles and objects from your mod, in JSON or in Java, and reload them without restarting.

    [:octicons-arrow-right-24: Getting started with AXION](addons/index.md)

-   **Play and host**

    ---

    Configuration options, what a server requires from its clients, and what an administrator can tune.

    [:octicons-arrow-right-24: Configuration](CONFIGURATION.md) · [Networking](NETWORKING.md)

-   **Engine reference**

    ---

    How AXION simulates, renders and picks its physical materials.

    [:octicons-arrow-right-24: Physics](PHYSICS.md) · [Rendering](RENDERING.md) · [Materials](MATERIALS.md)

</div>

## Code and decisions

The code, the specification and the architecture decisions are on
[GitHub](https://github.com/JLSkyzer/AXIONENGINE), under the Apache-2.0 license. This documentation is built from the
same files as the code: it always describes the version it ships with.
