# Configuration

This file is **generated** by `ax-model`: do not edit it by hand.
Every AXION option is declared once, in
`crates/ax-model/src/config/defaults.rs`, with its default, its domain
and its description (R-430).

An option can be overridden with the system property `-Daxion.<path>`.
The "hot" column says it can be reloaded with
`/axion config reload` (R-431); the "server" column, that the server
imposes it at handshake, the client's local value then being
ignored (R-1830).

## `axion-common.toml`

### `[general]`

| Option | Type | Default | Domain | Hot | Server | Description |
|---|---|---|---|---|---|---|
| `enabled` | boolean | `true` | `true \| false` | no | no | Enables AXION. When false, the mod loads in DISABLED mode and assemblies stay inert. |
| `maturity_allow_experimental` | boolean | `false` | `true \| false` | no | no | Allows enabling features marked EXPERIMENTAL. |

### `[sim]`

| Option | Type | Default | Domain | Hot | Server | Description |
|---|---|---|---|---|---|---|
| `fixed_dt` | float | `0.0166667` | `0.0333333 \| 0.0166667 \| 0.0083333` | no | yes | Fixed simulation time step, in seconds. An intermediate step would desynchronise the simulation from server ticks. |
| `max_substeps` | integer | `4` | `1..8` | no | yes | Maximum number of substeps caught up in one tick when real time runs ahead of the fixed step. |
| `simulation_radius` | float | `128.0` | `16.0..512.0` | no | no | Simulation radius around a player, in blocks. Beyond it, assemblies are put to sleep. |

### `[physics]`

| Option | Type | Default | Domain | Hot | Server | Description |
|---|---|---|---|---|---|---|
| `gravity` | float | `-9.81` | `-50.0..0.0` | no | yes | Gravitational acceleration, in m/s². |
| `velocity_iterations` | integer | `4` | `2..16` | no | yes | Velocity solver iterations per step. Higher gives more stable contacts and costs more. |
| `position_iterations` | integer | `1` | `0..8` | no | yes | Position correction iterations per step, against residual interpenetration. |
| `broadphase_cell_size` | float | `2.0` | `0.5..16.0` | no | no | Broad phase cell size, in blocks. |
| `contact_event_threshold` | float | `0.5` | `0.0..100.0` | no | no | Minimum impulse, in N·s, for a contact to produce an event. |
| `max_events_per_tick` | integer | `4096` | `256..65536` | no | no | Cap on physics events processed per tick. |
| `entity_push` | boolean | `true` | `true \| false` | no | yes | Assemblies push the vanilla entities they hit, players excepted. |
| `entity_damage` | boolean | `true` | `true \| false` | no | yes | A hit from an assembly hurts living entities, like a fall at the same speed. |

### `[damage]`

| Option | Type | Default | Domain | Hot | Server | Description |
|---|---|---|---|---|---|---|
| `enabled` | boolean | `true` | `true \| false` | no | no | Enables the damage chain: impacts, deformation, breakage, wear. |
| `max_impacts_per_tick` | integer | `512` | `32..8192` | no | no | Cap on impacts resolved per tick. The surplus is queued, never lost. |
| `min_impact_energy` | float | `5.0` | `0.0..1000.0` | no | no | Minimum energy of an impact, in joules, below which it is ignored. |
| `max_impact_energy` | float | `5000000.0` | `1000.0..1000000000.0` | no | no | Maximum energy kept for an impact, in joules. Clips aberrant values. |
| `max_propagation_depth` | integer | `4` | `0..8` | no | no | Maximum depth to which energy propagates through the structural graph. |
| `max_assemblies_per_tick` | integer | `64` | `4..1024` | no | no | Maximum number of assemblies processed by the damage chain in one tick. |
| `pending_queue_max` | integer | `4096` | `0..` | no | no | Maximum size of the pending impact queue. |
| `max_detach_per_tick` | integer | `4` | `0..64` | no | no | Maximum number of part detachments per tick. |
| `max_debris` | integer | `96` | `0..512` | no | no | Maximum number of simultaneous debris. |
| `max_debris_generation` | integer | `2` | `0..4` | no | no | Maximum number of successive debris generations, to bound cascading fragmentation. |
| `debris_lifetime_s` | integer | `90` | `5..1200` | no | no | Lifetime of a debris, in seconds. |
| `debris_sleep_s` | integer | `5` | `0..` | no | no | Time at rest, in seconds, after which a debris is put to sleep. |
| `persist_debris` | boolean | `false` | `true \| false` | no | no | Saves debris with the world. |
| `explosion_sample_area` | float | `0.5` | `0.0..` | no | no | Area represented by one explosion sample point, in m². |
| `default_sharp_factor` | float | `8.0` | `1.0..64.0` | no | no | Concentration factor of a sharp impact: it reduces the effective radius, so increases penetration (13.3bis). |
| `default_blunt_factor` | float | `2.0` | `1.0..16.0` | no | no | Spreading factor of a blunt impact: it increases the effective radius. |
| `default_shear_ratio` | float | `0.6` | `0.0..1.0` | no | no | Ratio of shear strength to tensile strength, when the material does not declare it. |

### `[struct]`

| Option | Type | Default | Domain | Hot | Server | Description |
|---|---|---|---|---|---|---|
| `max_gap` | float | `0.05` | `0.0..0.5` | no | no | Maximum gap, in metres, between two parts for a structural link to be generated automatically. |
| `max_link_area` | float | `4.0` | `0.01..64.0` | no | no | Maximum area of a structural link, in m². |
| `auto_links` | boolean | `true` | `true \| false` | no | no | Automatically generates structural links between neighbouring parts. |

### `[deformation]`

| Option | Type | Default | Domain | Hot | Server | Description |
|---|---|---|---|---|---|---|
| `enabled` | boolean | `true` | `true \| false` | no | no | Enables continuous geometry deformation. |
| `quality` | string | `"auto"` | `auto \| off \| low \| medium \| high \| ultra` | no | no | Deformation quality level. auto lets the governor decide. |
| `max_field_bytes_per_assembly` | integer | `262144` | `0..` | no | no | Maximum size of an assembly's deformation field, in bytes. |
| `max_residual_vertices` | integer | `4096` | `0..` | no | no | Maximum number of vertices carrying a residual displacement outside the lattice. |
| `collider_refit_threshold` | float | `0.04` | `0.005..0.25` | no | no | Gap, in metres, between visual and collision geometry beyond which the collider is refitted. |
| `mass_update_threshold` | float | `0.05` | `0.0..` | no | no | Relative mass change from which inertial properties are recomputed. |
| `elastic_release_s` | float | `5.0` | `0.0..` | no | no | Time for an elastic deformation to return to zero, in seconds. |
| `tear_enabled` | boolean | `false` | `true \| false` | no | no | Enables mesh tearing. EXPERIMENTAL. |
| `tear_max_cells_per_region` | float | `0.08` | `0.0..` | no | no | Maximum fraction of a region's cells that can be torn. |

### `[particles]`

| Option | Type | Default | Domain | Hot | Server | Description |
|---|---|---|---|---|---|---|
| `enabled` | boolean | `true` | `true \| false` | no | no | Enables the particle solver: cloth, ropes, cables, nets, soft bodies. |
| `quality` | string | `"auto"` | `auto \| off \| low \| medium \| high \| ultra` | no | no | Particle solver quality level. |
| `max_sets` | integer | `48` | `0..` | no | no | Maximum number of particle sets simulated on the client. |
| `max_sets_server` | integer | `16` | `0..` | no | no | Maximum number of particle sets simulated on the server. |
| `self_collide` | boolean | `false` | `true \| false` | no | no | Enables particle self-collision. EXPERIMENTAL. |
| `tear` | boolean | `false` | `true \| false` | no | no | Enables tearing of particle sets. EXPERIMENTAL. |
| `visual_drift_max` | float | `0.25` | `0.02..2.0` | no | no | Maximum visual drift tolerated, in metres, between the client simulation and the server state (17.2bis). |
| `visual_freeze_ms` | integer | `500` | `100..5000` | no | no | Time during which the display is frozen for lack of server state, in milliseconds. |

### `[budgets]`

| Option | Type | Default | Domain | Hot | Server | Description |
|---|---|---|---|---|---|---|
| `sim_ns_per_tick` | integer | `3000000` | `0..` | no | no | Physics simulation budget, in nanoseconds per tick. |
| `damage_ns_per_tick` | integer | `1000000` | `0..` | no | no | Damage chain budget, in nanoseconds per tick. |
| `deformation_ns_per_tick` | integer | `1500000` | `0..` | no | no | Deformation budget, in nanoseconds per tick. |
| `particles_ns_per_tick` | integer | `1000000` | `0..` | no | no | Particle solver budget, in nanoseconds per tick. |
| `render_prep_ns` | integer | `2000000` | `0..` | no | no | Render preparation budget, in nanoseconds per frame. |
| `occlusion_ns` | integer | `800000` | `0..` | no | no | Software occlusion culling budget, in nanoseconds per frame. |
| `asset_ns_per_tick` | integer | `1000000` | `0..` | no | no | Asset loading and compilation budget, in nanoseconds per tick. |
| `idle_hook_ns` | integer | `50000` | `0..` | no | no | Maximum overhead of the Forge hooks per tick when no assembly is present, in nanoseconds. |
| `submit_ns` | integer | `200000` | `0..` | no | no | Render command submission budget, in nanoseconds per frame. |
| `native_mem_bytes` | integer | `536870912` | `0..` | no | no | Native memory cap, in bytes. |
| `deform_mem_bytes` | integer | `134217728` | `0..` | no | no | Memory cap of deformation fields, in bytes. |
| `gpu_mem_bytes` | integer | `536870912` | `0..` | no | no | Cap on GPU memory managed by AXION, in bytes. |
| `max_active_bodies` | integer | `2048` | `0..` | no | no | Maximum number of physics bodies active at once. |
| `max_deformed_assemblies` | integer | `256` | `0..` | no | no | Maximum number of assemblies carrying a deformation field. |
| `max_collider_refits_per_tick` | integer | `8` | `0..` | no | no | Maximum number of collider refits per tick. |
| `max_visible_instances` | integer | `512` | `0..` | no | no | Maximum number of instances rendered per frame. |
| `max_triangles_frame` | integer | `3000000` | `0..` | no | no | Maximum number of triangles rendered per frame. |
| `max_decals_frame` | integer | `512` | `0..` | no | no | Maximum number of decals rendered per frame. |
| `max_shadow_instances` | integer | `128` | `0..` | no | no | Maximum number of instances casting an AXION shadow. |

### `[jobs]`

| Option | Type | Default | Domain | Hot | Server | Description |
|---|---|---|---|---|---|---|
| `max_workers` | integer | `0` | `0..` | no | no | Number of threads in the job pool. 0 lets AXION derive it from the number of available cores. |

### `[assets]`

| Option | Type | Default | Domain | Hot | Server | Description |
|---|---|---|---|---|---|---|
| `max_source_bytes` | integer | `134217728` | `0..` | no | no | Maximum size of an accepted asset source file, in bytes. |
| `max_compiled_bytes` | integer | `268435456` | `0..` | no | no | Maximum size of a compiled asset, in bytes. |
| `max_deform_bytes` | integer | `4194304` | `0..` | no | no | Maximum size of an asset's compiled deformation data, in bytes. |
| `max_compile_ms` | integer | `30000` | `0..` | no | no | Maximum compilation time of an asset, in milliseconds. |
| `cache_max_bytes` | integer | `2147483648` | `0..` | no | no | Maximum size of the compiled asset cache, in bytes. |
| `startup_timeout_s` | integer | `120` | `0..` | no | no | Maximum time to prepare assets at startup, in seconds. |
| `unload_delay_s` | integer | `60` | `0..` | no | no | Delay before an unused asset is unloaded, in seconds. |

### `[world]`

| Option | Type | Default | Domain | Hot | Server | Description |
|---|---|---|---|---|---|---|
| `tiles_per_tick` | integer | `8` | `1..64` | no | no | Number of world collision tiles extracted per tick. |
| `tile_radius` | integer | `3` | `1..8` | no | no | Radius, in tiles, of the world collision geometry kept around an assembly. |

### `[vehicle]`

| Option | Type | Default | Domain | Hot | Server | Description |
|---|---|---|---|---|---|---|
| `debris_ignore_s` | float | `1.0` | `0.0..` | no | no | Time during which a vehicle ignores collisions with its own debris, in seconds. |
| `max_suspension_misalignment` | float | `0.15` | `0.0..` | no | no | Maximum misalignment tolerated for a deformed suspension, in metres, before it is reported. |
| `controller_budget_ns` | integer | `50000` | `1000..50000000` | no | no | Duration of a vehicle controller call beyond which it counts a fault, in nanoseconds. |
| `controller_strikes` | integer | `20` | `1..1200` | no | no | Consecutive faults of a vehicle controller (budget overruns or exceptions) that disable it for the session. |

### `[attachment]`

| Option | Type | Default | Domain | Hot | Server | Description |
|---|---|---|---|---|---|---|
| `max_chain` | integer | `8` | `0..` | no | no | Maximum length of a chain of attached assemblies. |

### `[net]`

| Option | Type | Default | Domain | Hot | Server | Description |
|---|---|---|---|---|---|---|
| `snapshot_rate_hz` | integer | `10` | `2..20` | no | no | Rate at which state snapshots are sent, in hertz. |
| `sync_radius` | float | `96.0` | `0.0..` | no | no | Synchronisation radius around a player, in blocks. |
| `interpolation_delay_ms` | integer | `100` | `0..65535` | no | no | Interpolation delay applied on the client, in milliseconds. |
| `extrapolation_max_ms` | integer | `250` | `0..` | no | no | Maximum extrapolation time when no snapshot arrives, in milliseconds. |
| `snap_threshold` | float | `2.0` | `0.0..` | no | no | Gap, in metres, beyond which the client snaps to position instead of interpolating. |
| `prediction` | boolean | `true` | `true \| false` | no | no | Enables client-side prediction for the assembly the player drives. |
| `prediction_max_ping` | integer | `400` | `0..` | no | no | Maximum latency, in milliseconds, beyond which prediction is disabled. |
| `prediction_replay_budget_ns` | integer | `8200000` | `0..` | no | no | Maximum duration of a prediction replay, in nanoseconds; beyond it, the client accepts the server state without replaying. |
| `prediction_tolerance_position_m` | float | `0.01` | `0.0..` | no | no | Position gap, in metres, below which the predicted state counts as the server's. |
| `prediction_tolerance_rotation_rad` | float | `0.005` | `0.0..` | no | no | Rotation gap, in radians, below which the predicted state counts as the server's. |
| `prediction_tolerance_velocity_ms` | float | `0.2` | `0.0..` | no | no | Velocity gap, in metres per second, below which the predicted state counts as the server's. |
| `prediction_proxy_radius` | float | `32.0` | `0.0..` | no | no | Distance, in blocks, around the predicted vehicle, up to which other assemblies and entities are placed as proxies in the predicted world. |
| `max_bytes_per_second_per_player` | integer | `32768` | `0..` | no | no | Cap on AXION bandwidth per player, in bytes per second. |
| `max_packets_per_second` | integer | `40` | `0..` | no | no | Cap on AXION packets per second per player. |
| `max_impacts_per_packet` | integer | `64` | `0..` | no | no | Maximum number of impacts grouped in one packet. |
| `digest_interval_ticks` | integer | `40` | `0..` | no | no | Interval, in ticks, between two state verification digests. |
| `max_deform_snapshots_per_second` | integer | `4` | `0..` | no | no | Cap on deformation snapshots per second in event-based replication. |
| `max_deform_snapshots_per_second_fallback` | integer | `16` | `0..` | no | no | Cap on deformation snapshots per second in SNAPSHOT mode, when deterministic reconstruction is not guaranteed (5.12bis). |
| `divergence_tolerance` | integer | `3` | `1..32` | no | no | Number of digest divergences tolerated before switching from RECONSTRUCT to SNAPSHOT. |

### `[persistence]`

| Option | Type | Default | Domain | Hot | Server | Description |
|---|---|---|---|---|---|---|
| `max_bytes_per_assembly` | integer | `65536` | `0..` | no | no | Maximum size of an assembly's persisted state, in bytes. |
| `max_custom_bytes` | integer | `16384` | `0..` | no | no | Maximum size of the custom data persisted by an assembly, in bytes. |
| `max_persisted_decals` | integer | `16` | `0..` | no | no | Maximum number of decals saved per assembly. |
| `max_load_ns_per_tick` | integer | `500000` | `0..` | no | no | Budget for loading persisted state, in nanoseconds per tick. |
| `journal_enabled` | boolean | `true` | `true \| false` | no | no | Enables the side recovery journal (22.5.2). |
| `journal_ns_per_tick` | integer | `200000` | `0..2000000` | no | no | Journal write budget, in nanoseconds per tick. |
| `journal_max_bytes` | integer | `268435456` | `16777216..8589934592` | no | no | Maximum size of the journal, in bytes. |

### `[limits]`

| Option | Type | Default | Domain | Hot | Server | Description |
|---|---|---|---|---|---|---|
| `max_assemblies_per_dimension` | integer | `4096` | `0..` | no | no | Maximum number of assemblies per dimension. |
| `max_spawn_per_command` | integer | `64` | `0..` | no | no | Maximum number of assemblies created by a single command. |

### `[watchdog]`

| Option | Type | Default | Domain | Hot | Server | Description |
|---|---|---|---|---|---|---|
| `job_timeout_ms` | integer | `5000` | `0..` | no | no | Time beyond which a job is reported as overrunning. The watchdog never kills a thread (R-1901). |

### `[modules]`

| Option | Type | Default | Domain | Hot | Server | Description |
|---|---|---|---|---|---|---|
| `vehicles` | boolean | `true` | `true \| false` | no | no | Enables the vehicles module. |
| `joints` | boolean | `true` | `true \| false` | no | no | Enables the joints and constraints module. |
| `damage` | boolean | `true` | `true \| false` | no | no | Enables the damage module. |
| `deformation` | boolean | `true` | `true \| false` | no | no | Enables the deformation module. |
| `structure` | boolean | `true` | `true \| false` | no | no | Enables the structural integrity module. |
| `repair` | boolean | `true` | `true \| false` | no | no | Enables the repair module. |
| `wear` | boolean | `true` | `true \| false` | no | no | Enables the surface wear module. |
| `particles` | boolean | `true` | `true \| false` | no | no | Enables the particles module. |
| `animation` | boolean | `true` | `true \| false` | no | no | Enables the animation module. |
| `attachments` | boolean | `true` | `true \| false` | no | no | Enables the module for attachments between assemblies. |
| `blocks` | boolean | `true` | `true \| false` | no | no | Enables the 3D blocks and items module. |

### `[content]`

| Option | Type | Default | Domain | Hot | Server | Description |
|---|---|---|---|---|---|---|
| `examples` | boolean | `false` | `true \| false` | no | yes | Loads the five built-in demonstration definitions (R-1790), stored under `axion/definitions/example/` at the root of the JAR and registered by AXION like an addon (ADR-162). |
| `dev_reload` | boolean | `false` | `true \| false` | yes | no | Allows `/axion dev reload` (operator): calls each mod's registration again, rereads its folder from disk when it runs from its sources, and sends the registry back to clients (ADR-162 D9). For development only. |

### `[integration.rustforgex]`

| Option | Type | Default | Domain | Hot | Server | Description |
|---|---|---|---|---|---|---|
| `mode` | string | `"auto"` | `auto \| off` | no | no | Detection of a third-party optimisation mod. AXION never depends on it and works the same without it. |
| `cpu_share` | string | `"auto"` | `auto \| half \| full \| <integer>` | no | no | Share of parallelism AXION allows itself when another CPU consumer is present. An integer sets the number of threads. |
| `bridge` | boolean | `true` | `true \| false` | no | no | Enables the optional interoperability bridge. Reflective, isolated, can be turned off; no AXION feature depends on it. |
| `hint_no_transform` | boolean | `true` | `true \| false` | no | no | Tells third-party tools that AXION prefers its bytecode not to be transformed. A hint, never a requirement. |

### `[debug]`

| Option | Type | Default | Domain | Hot | Server | Description |
|---|---|---|---|---|---|---|
| `checksum_buffers` | boolean | `false` | `true \| false` | yes | no | Checks the integrity of the buffers exchanged with the native side. Costly, for diagnostics only. |
| `record_incidents` | boolean | `false` | `true \| false` | yes | no | Records what is needed to replay an incident. |

## `axion-client.toml`

### `[render]`

| Option | Type | Default | Domain | Hot | Server | Description |
|---|---|---|---|---|---|---|
| `backend` | string | `"auto"` | `auto \| native \| vanilla` | no | no | Rendering backend. auto switches to vanilla when a shaderpack is present. |
| `max_distance` | float | `128.0` | `0.0..` | no | no | Maximum render distance of AXION objects, in blocks. |
| `lod_bias` | integer | `0` | `-2..3` | no | no | Offset applied to the choice of level of detail. Negative favours quality. |
| `anisotropy` | integer | `16` | `1..16` | no | no | Anisotropic filtering of AXION textures, capped by the GPU maximum: keeps surfaces seen at an angle sharp. 1 turns it off (ADR-164). |
| `instancing_threshold` | integer | `4` | `0..` | no | no | Number of identical instances from which GPU instancing is used. |
| `indirect` | boolean | `true` | `true \| false` | no | no | Allows indirect rendering. Used only if GL 4.3 is available. |
| `compute` | boolean | `true` | `true \| false` | no | no | Allows compute shaders. Used only if GL 4.3 is available. |
| `max_skinned_instances` | integer | `64` | `0..` | no | no | Maximum number of skinned instances per frame; beyond it, the farthest are drawn at rest (R-791). |
| `max_palette_bones` | integer | `128` | `0..` | no | no | Maximum number of bones in a skinning palette. |
| `max_deform_pages_per_frame` | integer | `256` | `0..` | no | no | Maximum number of deformation pages uploaded to the GPU per frame. |
| `max_decals_per_assembly` | integer | `64` | `0..` | no | no | Maximum number of decals carried by an assembly. |
| `max_morph_targets_per_asset` | integer | `8` | `0..` | no | no | Maximum number of morph targets per asset. |
| `max_shader_variants` | integer | `64` | `0..` | no | no | Maximum number of compiled shader variants. |
| `vanilla_max_skinned_vertices` | integer | `50000` | `0..` | no | no | Cap on animated vertices per frame in the vanilla backend. |
| `vanilla_max_deformed_vertices` | integer | `100000` | `0..` | no | no | Cap on deformed vertices per frame in the vanilla backend. |
| `vanilla_max_decals` | integer | `64` | `0..` | no | no | Cap on decals per frame in the vanilla backend. |
| `per_node_lightmap` | boolean | `false` | `true \| false` | no | no | Samples light per node rather than per assembly. More faithful, more costly. |
| `item_max_triangles` | integer | `20000` | `0..` | no | no | Maximum number of triangles for the 3D rendering of an item. |
| `shadow` | string | `"map"` | `map \| contact \| none` | no | no | Shadow technique specific to AXION objects. |
| `shadow_ground_quad` | boolean | `true` | `true \| false` | no | no | Adds a contact shadow on the ground under objects. |
| `shadow_ns` | integer | `1500000` | `0..` | no | no | Shadow rendering budget, in nanoseconds per frame. |
| `probe_interval_frames` | integer | `20` | `0..` | no | no | Interval, in frames, between two updates of the environment probe. |
| `ssr_max_roughness` | float | `0.25` | `0.0..` | no | no | Maximum roughness beyond which screen-space reflections are abandoned. |
| `max_occluders` | integer | `512` | `0..` | no | no | Maximum number of occluders kept for software occlusion culling. |
| `backend_silhouette_tolerance` | float | `0.01` | `0.0..0.05` | no | no | Silhouette difference tolerated between the two rendering backends (19.2bis). |

### `[quality]`

| Option | Type | Default | Domain | Hot | Server | Description |
|---|---|---|---|---|---|---|
| `deformation` | string | `"auto"` | `auto \| off \| low \| medium \| high \| ultra` | no | no | Quality level of client-side deformation. |
| `particles` | string | `"auto"` | `auto \| off \| low \| medium \| high \| ultra` | no | no | Quality level of particles. |
| `shadows` | string | `"auto"` | `auto \| off \| low \| medium \| high \| ultra` | no | no | Quality level of shadows. |
| `decals` | string | `"auto"` | `auto \| off \| low \| medium \| high \| ultra` | no | no | Quality level of decals. |
| `occlusion` | string | `"auto"` | `auto \| off \| low \| medium \| high \| ultra` | no | no | Quality level of occlusion culling. |
| `lighting` | string | `"auto"` | `auto \| off \| low \| medium \| high \| ultra` | no | no | Quality level of lighting. |
| `skinning` | string | `"auto"` | `auto \| off \| low \| medium \| high \| ultra` | no | no | Quality level of skinning. |
| `reflections` | string | `"auto"` | `auto \| off \| low \| medium \| high \| ultra` | no | no | Quality level of reflections. |
| `parallax` | string | `"auto"` | `auto \| off \| low \| medium \| high \| ultra` | no | no | Quality level of parallax mapping. |

### `[overlay]`

| Option | Type | Default | Domain | Hot | Server | Description |
|---|---|---|---|---|---|---|
| `enabled` | boolean | `false` | `true \| false` | yes | no | Shows the diagnostic overlay. |
| `key` | string | `"unbound"` | `free text` | yes | no | Key that toggles the overlay. unbound leaves the overlay without a shortcut. |

### `[debug]`

| Option | Type | Default | Domain | Hot | Server | Description |
|---|---|---|---|---|---|---|
| `gl` | boolean | `false` | `true \| false` | yes | no | Enables validation of OpenGL calls. Costly, for diagnostics only. |

## `axion-server.toml`

### `[server]`

| Option | Type | Default | Domain | Hot | Server | Description |
|---|---|---|---|---|---|---|
| `allow_client_prediction` | boolean | `true` | `true \| false` | no | no | Allows clients to predict the movement of the assembly they drive. |
| `kick_on_input_abuse` | boolean | `true` | `true \| false` | no | no | Kicks a client whose inputs repeatedly fall outside the accepted bounds. |
| `allow_experimental_authority` | boolean | `false` | `true \| false` | no | no | Allows the SERVER_FULL authority mode for particles. EXPERIMENTAL. |

## `axion-server-overrides.toml`

The server administrator's overrides (ADR-162 D8, ADR-165 D4): bounded
multipliers and block mappings, applied to the resolved content before it
is validated; no new content. The server sends the overridden content to
clients: they do not need this file.

The server writes it on first startup, fully commented out — it then
carries no override — and rereads it at startup and on every `/reload`.
Each override, applied or refused with its reason, is written to the
server log; `/axion status` gives the count.

| Section | Key | Effect |
|---|---|---|
| `[physics_materials."<ns>:<material>"]` | a numeric quantity of the material (DM-07) | multiplies it |
| `[definitions."<ns>:<definition>"]` | `mass` | multiplies every written mass: `physics.mass`, bodies, parts, wheels |
| `[definitions."<ns>:<definition>"]` | `engine_torque` | multiplies each torque of `powertrain.engine.torque_curve` |
| `[definitions."<ns>:<definition>"]` | `brake_torque` | multiplies `brakes.max_torque_front`, `max_torque_rear` and `handbrake_torque` |
| `[tires."<ns>:<tire>"]` | `grip` | multiplies `mu_long` and `mu_lat` |
| `[block_materials]` | `"<ns>:<block>"` or `"#<ns>:<tag>"` | the material of a block or of a block tag, read after the mods' mappings |

A multiplier ranges from 0.1 to 10 and only touches written values; the
product is rounded to twelve significant digits. An override is refused,
and logged with its reason, if its target does not exist, if its key is
unknown, if its multiplier is out of bounds, if its target writes no value
to multiply, if a multiplied value leaves its domain, if an overridden
definition no longer passes its schema, or if a block mapping names a
material that no mod declares.
