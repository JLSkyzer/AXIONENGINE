# Rendering

How AXION ENGINE draws its assemblies today (milestone M3), and which parts of its lighting are physically
based or synthesised (R-1514). The normative reference remains the specification (PART 19, sections 5.47 to
5.51); the implementation choices are in the cited ADRs. Options are set in `axion-client.toml`: see
[CONFIGURATION.md](CONFIGURATION.md), sections `[render]`, `[quality]` and `[overlay]`.

## Two backends

| | `NATIVE_GL` (C-60) | `VANILLA_CONSUMER` (C-61) |
|---|---|---|
| Drawing | OpenGL 3.3, AXION's programs, instancing, shared GPU arenas | Minecraft's `RenderType` and `VertexConsumer`, no direct OpenGL call (R-741) |
| Lighting | BRDF from §19.5 (below) | Minecraft's entity lighting: albedo × lightmap, shading by the normal |
| Normal maps and ORM | read | ignored (R-1513) |
| Emission | separate additive pass (pass 5 of §19.10) | entity eyes `RenderType`: full brightness |

**Choosing the backend** (`render.backend`, `auto` by default). The native one, unless the context does not offer
OpenGL 3.3, a shader fails to compile (`E-4001`, never a crash, R-761), or a **shaderpack is in use** (H-09): then
the vanilla one. A shaders mod that is installed, with its pack turned off, lets the native backend draw; the choice
is made again when the pack changes (ADR-129). The pack's state is read through Iris's v0 API, which Oculus also
ships; a shaders mod whose state cannot be read is treated as active. Under a shaderpack, the pack does the
lighting: that is what the player expects.

**Passes.** Opaque, cutout and emission after entities; translucent after translucent blocks; debug overlays after
particles (§19.10). Each pass leaves the OpenGL state as it found it (R-1502, R-1503), including under Oculus and
Embeddium.

## The native backend's lighting model

**Physically based** — the core of the BRDF, in linear space:

- GGX distribution, height-correlated Smith visibility, Schlick Fresnel;
- energy split between diffuse and specular (diffuse receives `1 − F`);
- glTF's metallic-roughness workflow: `F0 = 0.04` for a dielectric, the albedo for a metal;
  roughness clamped to 0.045; ORM maps (occlusion, roughness, metallic) and tangent-space normal maps,
  as glTF defines them;
- integrated ambient specular response, approximated analytically (Karis, 2014), instead of the
  `brdfLUT` table.

**Synthesised** — chosen to match the object to Minecraft's world, with no physical basis:

- **Direct light** is the sun, or the moon once the sun is below the horizon: direction taken from Minecraft's
  sky, intensity from how dark the sky is (rain and thunderstorms included), with no jump at sunset.
  The intensities are chosen by calculation, not measured: a white albedo facing the noon sun comes out
  roughly white (`WorldLight`).
- **The sun only reaches what the sky sees**: it is weighted by the sky part of the vertex's
  lightmap, which stands in for cast shadows.
- **Ambient light** takes its intensity and tint from Minecraft's lightmap — the time of day, the visible
  sky, block light (R-1511): an object in a dark cave stays dark. Its distribution by normal comes
  from a sky-ground hemisphere, until the C-81 probe arrives.
- **Analytic specular only**, as at level Q-1 of R-1512: no prefiltering, no probe, no clearcoat,
  sheen, anisotropy, parallax or SSR.
- **Output**: gamma conversion by a 1/2.2 power, no tone mapping (C-83, R-1550, coming later);
  Minecraft's fog, spherical or cylindrical.

The vanilla backend has no PBR: Minecraft's `RenderType`s do not expose its inputs
(R-1513).

## Culling, levels of detail and instancing

Each frame, the client hands the native library the assemblies Minecraft gives it to draw, with their pose
relative to the camera, and the native library decides (C-64, C-65, [ADR-154](../decisions/ADR-154.md)):

- **Culling**: an assembly beyond `render.max_distance`, or entirely out of view, is not drawn. An assembly
  whose articulated bodies have moved away from their rest pose, or that is marked `noCulling`, is always
  drawn; so is one drawn as a fallback box. Occlusion will come with C-82 (M9).
- **Levels of detail**: the compiled asset carries its LODs (4 by default); the native library picks the level
  of each assembly from its size on screen — below 200, 80, 30 and 10 pixels —, with 10% hysteresis and
  the `render.lod_bias` offset, and tells the client the mesh for each draw.
- **Cap**: beyond `budgets.max_visible_instances`, the frame drops one level and the farthest
  assemblies are left out.
- **Deadline**: preparation never goes past `budgets.render_prep_ns`; an assembly it had no time to
  examine keeps its decision from the previous frame. It never waits for the physics step, even in single
  player, where the integrated server shares the native library.
- **Instancing** (native backend): opaques are sorted by asset, mesh and face winding; a run of at least
  `render.instancing_threshold` identical meshes (4 by default) is drawn in a single call, a shorter one draw
  by draw. Under GL 4.3 and `render.indirect`, runs with the same state go out in a single
  `glMultiDrawElementsIndirect`. The image is the same through all three paths (T-478, T-907).

## Animation and skinning

An animated assembly plays clips that the server commands (C-37, [ADR-155](../decisions/ADR-155.md));
the client pushes the received state to the native library, which evaluates the pose every frame and returns the
matrix of each draw. A skinned mesh follows its bones' palette — `world(bone node) × inverse bind` —, four
bones per vertex, and is placed at the assembly's root:

- **native backend**: on the GPU (`SKINNED` variant), the palette read from a TBO, or in pages of a UBO
  when the TBO is not enough (C-66). `render.max_skinned_instances` (64) reserves it for the nearest
  instances; the others are drawn at rest, counted by `axion.render.skinned_capped`;
- **vanilla backend**: on the CPU, when vertices are emitted, each mesh once per frame (R-742).
  `render.vanilla_max_skinned_vertices` (50,000 per frame) caps the skinned vertices, nearest instances
  first; the others stay at rest, counted by `axion.render.vanilla_skinned_capped`.

Continuous deformation, which adds to this, comes with M6.

## GPU resources

- **Meshes** sub-allocated in shared 16 MiB arenas (VBO, EBO), a common instance buffer, release deferred
  until 3 frames without use (C-62).
- **Budget**: `budgets.gpu_mem_bytes` caps the native backend's meshes **and** the textures AXION
  uploads, mipmaps included, in both backends; beyond it, the least recently seen asset among those not
  visible is unloaded entirely — mesh, textures, native handle —, and reloads when it comes back
  (R-750, ADR-133). Minecraft textures that AXION only references do not count.
- **Degradation**: keeping to the budget goes through steps, each logged with its cause
  (§19.12) — `NOMINAL`, `UNLOADING` (non-visible assets are unloaded), `FORCED_LOD_1` and
  `FORCED_LOD_2` (the excess assets are visible: one, then two levels of detail coarser, and as many of the
  finest mips removed from AXION's textures, which reload four times smaller per mip — since a LOD only
  costs indices, it is the textures that give memory back), `UNRESOLVED` (forced LOD is not enough;
  deformation quality, the last lever of R-750, comes with M6). Between two forced-LOD steps, 10 s: the
  time for textures to reload. Stepping down is immediate; stepping back up happens one step at a time,
  after 30 s under the threshold and at least 10 s after the previous one (SM-02). The current step is the
  gauge `axion.render.gpu_degradation_level`.
- **Textures**: decoded and mipmapped off the render thread, uploaded by Minecraft
  (C-26, ADR-122).
- **Shaders**: JAR resources (`assets/axion/shaders/`), reloaded with resource packs. Variants —
  permutations of the eleven defines of R-762 — compile **on demand**, one GL step per frame, with the
  base variant as an immediate fallback (T-904); their number is capped by `render.max_shader_variants`.
  The sources only implement `CUTOUT` today. A binary cache (`GL_ARB_get_program_binary`) keeps the
  programs in `<gameDir>/axion/cache/shaders/`, invalidated by the hash of the sources and the driver
  string (R-760, T-905).
- **Thread**: every OpenGL call asserts the render thread (INV-12), which T-019 checks on the
  bytecode.

## Debug overlays

`/axion debug <overlay> on|off`, in developer mode (`-Daxion.dev=true`) (C-67, ADR-121). The overlays
drawn are `colliders`, `aabb`, `bodies`, `contacts`, `normals`, `joints`, `limits`, `world_tiles`,
`center_of_mass`, `network`, `lod` (the bounds of each drawn assembly, coloured by its level) and
`culling` (kept in green, culled in red); joints and their ranges are described in
[PHYSICS.md](PHYSICS.md#errors-network-and-debugging). For animated assemblies, `bones` (each bone, from
its parent bone to it), `nodes` (each node's axes) and `pivots` (the origin of each animated node): the
frame's pose, seen through the skin, drawn by the render context without waiting for physics. Turned off,
an overlay costs only a flag check per frame (R-2280).

## Measurements

`/axion metrics export` carries, along with the native metrics, those the renderer measures (ADR-133):
the CPU time of AXION's passes per frame (`budgets.render_prep_ns`), the GPU memory in use and its
overruns (`budgets.gpu_mem_bytes`), the instances and triangles drawn
(`budgets.max_visible_instances`, `budgets.max_triangles_frame`), and the `axion.render.*` gauges:
active backend, texture and mesh bytes, evictions, degradation step, live GL objects, state of the
native backend's shaders. On a client connected to a remote server, where the command runs on the server,
`/axion metrics export client` writes the client's export (ADR-144 §2). Under the native backend, the
GPU time of its opaque and translucent passes is recorded with GPU timestamps, read a few frames later
without ever waiting for the GPU (`axion.render.gpu_opaque_ns`, `axion.render.gpu_translucent_ns`, lost
readings `axion.render.gpu_timings_dropped`, ADR-146 §2); the vanilla backend has none, since R-741
forbids it any OpenGL call.

## Verification

The **render test bench** (ADR-126) launches the client automatically under vanilla, under the native
backend and under a shaderpack (Oculus and Embeddium): no OpenGL error (T-471), state handed back intact
(T-472), switching to a shaderpack and back (T-480, T-490), on-demand compilation without stutter
(T-904, judged on hardware GPUs only, ADR-127), ordered transparency (T-476), no AXION texture left on
the GPU by a backend switch (T-481), AXION's resources rebuilt by a real resource pack change (T-483),
GPU time of the native backend's passes known (ADR-146 §2), culling and LOD of assemblies placed out of
view, 200 copies of a cube without an extra draw call (T-477), non-instanced and indirect paths giving the
same image (T-478, T-907), 100 assemblies measured under their caps (T-485), forced LOD giving back three
quarters of texture memory (T-484), a skinned arm bent where its CPU reference puts it, on the GPU as on
the CPU (T-908, R-742), the same image through a TBO and through a UBO (T-541), skinning caps held and
counted (T-542, T-493), bones seen through the skin. CI runs it on every push.

## What does not exist yet

Quality levels Q-0 to Q-4, the environment probe (C-81) and tone mapping (C-83), the effect of defines
other than `CUTOUT` and `SKINNED` (deformation, parallax, clearcoat, sheen, anisotropy, decals, received
shadows, SSR), occlusion culling (C-82, M9), GPU time per material, for shadows and for decals (M9, with
those passes).
