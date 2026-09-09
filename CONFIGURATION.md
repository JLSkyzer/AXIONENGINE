# Configuration

Ce fichier est **généré** par `ax-model` : ne pas le modifier à la main.
Toute option d'AXION est déclarée une seule fois, dans
`crates/ax-model/src/config/defaults.rs`, avec son défaut, son domaine
et sa description (R-430).

Une option se surcharge par la propriété système `-Daxion.<chemin>`.
La colonne « chaud » indique qu'elle est rechargeable par
`/axion config reload` (R-431) ; la colonne « serveur » qu'elle est
imposée par le serveur au handshake, la valeur locale du client étant
alors ignorée (R-1830).

## `axion-common.toml`

### `[general]`

| Option | Type | Défaut | Domaine | Chaud | Serveur | Description |
|---|---|---|---|---|---|---|
| `enabled` | booléen | `true` | `true \| false` | non | non | Active AXION. À false, le mod se charge en mode DISABLED et les assemblies restent inertes. |
| `maturity_allow_experimental` | booléen | `false` | `true \| false` | non | non | Autorise l'activation des fonctionnalités marquées EXPERIMENTAL. |

### `[sim]`

| Option | Type | Défaut | Domaine | Chaud | Serveur | Description |
|---|---|---|---|---|---|---|
| `fixed_dt` | flottant | `0.0166667` | `0.0333333 \| 0.0166667 \| 0.0083333` | non | oui | Pas de temps fixe de la simulation, en secondes. Un pas intermédiaire désynchroniserait la simulation des ticks du serveur. |
| `max_substeps` | entier | `4` | `1..8` | non | oui | Nombre maximal de sous-pas rattrapés en un tick lorsque le temps réel dépasse le pas fixe. |
| `simulation_radius` | flottant | `128.0` | `16.0..512.0` | non | non | Rayon de simulation autour d'un joueur, en blocs. Au-delà, les assemblies sont endormies. |

### `[physics]`

| Option | Type | Défaut | Domaine | Chaud | Serveur | Description |
|---|---|---|---|---|---|---|
| `gravity` | flottant | `-9.81` | `-50.0..0.0` | non | oui | Accélération de la pesanteur, en m/s². |
| `velocity_iterations` | entier | `4` | `2..16` | non | oui | Itérations de résolution des vitesses par pas. Plus élevé donne des contacts plus stables et coûte plus cher. |
| `position_iterations` | entier | `1` | `0..8` | non | oui | Itérations de correction des positions par pas, contre l'interpénétration résiduelle. |
| `broadphase_cell_size` | flottant | `2.0` | `0.5..16.0` | non | non | Taille de cellule de la broad phase, en blocs. |
| `contact_event_threshold` | flottant | `0.5` | `0.0..100.0` | non | non | Impulsion minimale, en N·s, pour qu'un contact produise un événement. |
| `max_events_per_tick` | entier | `4096` | `256..65536` | non | non | Plafond d'événements physiques traités par tick. |

### `[damage]`

| Option | Type | Défaut | Domaine | Chaud | Serveur | Description |
|---|---|---|---|---|---|---|
| `enabled` | booléen | `true` | `true \| false` | non | non | Active la chaîne de dommage : impacts, déformation, rupture, usure. |
| `max_impacts_per_tick` | entier | `512` | `32..8192` | non | non | Plafond d'impacts résolus par tick. Le surplus est mis en file, jamais perdu. |
| `min_impact_energy` | flottant | `5.0` | `0.0..1000.0` | non | non | Énergie minimale d'un impact, en joules, en deçà de laquelle il est ignoré. |
| `max_impact_energy` | flottant | `5000000.0` | `1000.0..1000000000.0` | non | non | Énergie maximale retenue pour un impact, en joules. Écrête les valeurs aberrantes. |
| `max_propagation_depth` | entier | `4` | `0..8` | non | non | Profondeur maximale de propagation de l'énergie dans le graphe structurel. |
| `max_assemblies_per_tick` | entier | `64` | `4..1024` | non | non | Nombre maximal d'assemblies traitées par la chaîne de dommage en un tick. |
| `pending_queue_max` | entier | `4096` | `0..` | non | non | Taille maximale de la file d'impacts en attente. |
| `max_detach_per_tick` | entier | `4` | `0..64` | non | non | Nombre maximal de détachements de pièces par tick. |
| `max_debris` | entier | `96` | `0..512` | non | non | Nombre maximal de débris simultanés. |
| `max_debris_generation` | entier | `2` | `0..4` | non | non | Nombre maximal de générations successives de débris, pour borner la fragmentation en cascade. |
| `debris_lifetime_s` | entier | `90` | `5..1200` | non | non | Durée de vie d'un débris, en secondes. |
| `debris_sleep_s` | entier | `5` | `0..` | non | non | Délai d'immobilité, en secondes, au bout duquel un débris est endormi. |
| `persist_debris` | booléen | `false` | `true \| false` | non | non | Sauvegarde les débris avec le monde. |
| `explosion_sample_area` | flottant | `0.5` | `0.0..` | non | non | Aire représentée par un point d'échantillonnage d'explosion, en m². |
| `default_sharp_factor` | flottant | `8.0` | `1.0..64.0` | non | non | Facteur de concentration d'un impact tranchant : il réduit le rayon efficace, donc augmente la pénétration (13.3bis). |
| `default_blunt_factor` | flottant | `2.0` | `1.0..16.0` | non | non | Facteur d'étalement d'un impact contondant : il augmente le rayon efficace. |
| `default_shear_ratio` | flottant | `0.6` | `0.0..1.0` | non | non | Rapport entre résistance au cisaillement et résistance en traction, quand le matériau ne la déclare pas. |

### `[struct]`

| Option | Type | Défaut | Domaine | Chaud | Serveur | Description |
|---|---|---|---|---|---|---|
| `max_gap` | flottant | `0.05` | `0.0..0.5` | non | non | Écart maximal, en mètres, entre deux pièces pour qu'une liaison structurelle soit générée automatiquement. |
| `max_link_area` | flottant | `4.0` | `0.01..64.0` | non | non | Aire maximale d'une liaison structurelle, en m². |
| `auto_links` | booléen | `true` | `true \| false` | non | non | Génère automatiquement les liaisons structurelles entre pièces voisines. |

### `[deformation]`

| Option | Type | Défaut | Domaine | Chaud | Serveur | Description |
|---|---|---|---|---|---|---|
| `enabled` | booléen | `true` | `true \| false` | non | non | Active la déformation continue de géométrie. |
| `quality` | chaîne | `"auto"` | `auto \| off \| low \| medium \| high \| ultra` | non | non | Niveau de qualité de la déformation. auto laisse le gouverneur décider. |
| `max_field_bytes_per_assembly` | entier | `262144` | `0..` | non | non | Taille maximale du champ de déformation d'une assembly, en octets. |
| `max_residual_vertices` | entier | `4096` | `0..` | non | non | Nombre maximal de sommets portant un déplacement résiduel hors lattice. |
| `collider_refit_threshold` | flottant | `0.04` | `0.005..0.25` | non | non | Écart, en mètres, entre géométrie visuelle et géométrie de collision au-delà duquel le collider est réajusté. |
| `mass_update_threshold` | flottant | `0.05` | `0.0..` | non | non | Variation relative de masse à partir de laquelle les propriétés inertielles sont recalculées. |
| `elastic_release_s` | flottant | `5.0` | `0.0..` | non | non | Temps de retour à zéro d'une déformation élastique, en secondes. |
| `tear_enabled` | booléen | `false` | `true \| false` | non | non | Active la déchirure du maillage. EXPERIMENTAL. |
| `tear_max_cells_per_region` | flottant | `0.08` | `0.0..` | non | non | Fraction maximale des cellules d'une région pouvant être déchirées. |

### `[particles]`

| Option | Type | Défaut | Domaine | Chaud | Serveur | Description |
|---|---|---|---|---|---|---|
| `enabled` | booléen | `true` | `true \| false` | non | non | Active le solveur de particules : tissu, cordes, câbles, filets, corps souples. |
| `quality` | chaîne | `"auto"` | `auto \| off \| low \| medium \| high \| ultra` | non | non | Niveau de qualité du solveur de particules. |
| `max_sets` | entier | `48` | `0..` | non | non | Nombre maximal d'ensembles de particules simulés côté client. |
| `max_sets_server` | entier | `16` | `0..` | non | non | Nombre maximal d'ensembles de particules simulés côté serveur. |
| `self_collide` | booléen | `false` | `true \| false` | non | non | Active l'auto-collision des particules. EXPERIMENTAL. |
| `tear` | booléen | `false` | `true \| false` | non | non | Active la déchirure des ensembles de particules. EXPERIMENTAL. |
| `visual_drift_max` | flottant | `0.25` | `0.02..2.0` | non | non | Dérive visuelle maximale tolérée, en mètres, entre la simulation client et l'état serveur (17.2bis). |
| `visual_freeze_ms` | entier | `500` | `100..5000` | non | non | Durée pendant laquelle l'affichage est gelé faute d'état serveur, en millisecondes. |

### `[budgets]`

| Option | Type | Défaut | Domaine | Chaud | Serveur | Description |
|---|---|---|---|---|---|---|
| `sim_ns_per_tick` | entier | `3000000` | `0..` | non | non | Budget de la simulation physique, en nanosecondes par tick. |
| `damage_ns_per_tick` | entier | `1000000` | `0..` | non | non | Budget de la chaîne de dommage, en nanosecondes par tick. |
| `deformation_ns_per_tick` | entier | `1500000` | `0..` | non | non | Budget de la déformation, en nanosecondes par tick. |
| `particles_ns_per_tick` | entier | `1000000` | `0..` | non | non | Budget du solveur de particules, en nanosecondes par tick. |
| `render_prep_ns` | entier | `2000000` | `0..` | non | non | Budget de préparation du rendu, en nanosecondes par frame. |
| `occlusion_ns` | entier | `800000` | `0..` | non | non | Budget de l'occlusion culling logiciel, en nanosecondes par frame. |
| `asset_ns_per_tick` | entier | `1000000` | `0..` | non | non | Budget du chargement et de la compilation d'assets, en nanosecondes par tick. |
| `idle_hook_ns` | entier | `50000` | `0..` | non | non | Surcoût maximal des hooks Forge par tick lorsqu'aucune assembly n'est présente, en nanosecondes. |
| `submit_ns` | entier | `200000` | `0..` | non | non | Budget de soumission des commandes de rendu, en nanosecondes par frame. |
| `native_mem_bytes` | entier | `536870912` | `0..` | non | non | Plafond de mémoire native, en octets. |
| `deform_mem_bytes` | entier | `134217728` | `0..` | non | non | Plafond de mémoire des champs de déformation, en octets. |
| `gpu_mem_bytes` | entier | `536870912` | `0..` | non | non | Plafond de mémoire GPU gérée par AXION, en octets. |
| `max_active_bodies` | entier | `2048` | `0..` | non | non | Nombre maximal de corps physiques actifs simultanément. |
| `max_deformed_assemblies` | entier | `256` | `0..` | non | non | Nombre maximal d'assemblies portant un champ de déformation. |
| `max_collider_refits_per_tick` | entier | `8` | `0..` | non | non | Nombre maximal de réajustements de collider par tick. |
| `max_visible_instances` | entier | `512` | `0..` | non | non | Nombre maximal d'instances rendues par frame. |
| `max_triangles_frame` | entier | `3000000` | `0..` | non | non | Nombre maximal de triangles rendus par frame. |
| `max_decals_frame` | entier | `512` | `0..` | non | non | Nombre maximal de décalques rendus par frame. |
| `max_shadow_instances` | entier | `128` | `0..` | non | non | Nombre maximal d'instances projetant une ombre AXION. |

### `[jobs]`

| Option | Type | Défaut | Domaine | Chaud | Serveur | Description |
|---|---|---|---|---|---|---|
| `max_workers` | entier | `0` | `0..` | non | non | Nombre de threads du pool de jobs. 0 laisse AXION le déduire du nombre de cœurs disponibles. |

### `[assets]`

| Option | Type | Défaut | Domaine | Chaud | Serveur | Description |
|---|---|---|---|---|---|---|
| `max_source_bytes` | entier | `134217728` | `0..` | non | non | Taille maximale d'un fichier source d'asset accepté, en octets. |
| `max_compiled_bytes` | entier | `268435456` | `0..` | non | non | Taille maximale d'un asset compilé, en octets. |
| `max_deform_bytes` | entier | `4194304` | `0..` | non | non | Taille maximale des données de déformation compilées d'un asset, en octets. |
| `max_compile_ms` | entier | `30000` | `0..` | non | non | Durée maximale de compilation d'un asset, en millisecondes. |
| `cache_max_bytes` | entier | `2147483648` | `0..` | non | non | Taille maximale du cache d'assets compilés, en octets. |
| `startup_timeout_s` | entier | `120` | `0..` | non | non | Délai maximal de préparation des assets au démarrage, en secondes. |
| `unload_delay_s` | entier | `60` | `0..` | non | non | Délai avant déchargement d'un asset inutilisé, en secondes. |

### `[world]`

| Option | Type | Défaut | Domaine | Chaud | Serveur | Description |
|---|---|---|---|---|---|---|
| `tiles_per_tick` | entier | `8` | `1..64` | non | non | Nombre de tuiles de collision monde extraites par tick. |
| `tile_radius` | entier | `3` | `1..8` | non | non | Rayon, en tuiles, de la géométrie de collision monde maintenue autour d'une assembly. |

### `[vehicle]`

| Option | Type | Défaut | Domaine | Chaud | Serveur | Description |
|---|---|---|---|---|---|---|
| `debris_ignore_s` | flottant | `1.0` | `0.0..` | non | non | Durée pendant laquelle un véhicule ignore les collisions avec ses propres débris, en secondes. |
| `max_suspension_misalignment` | flottant | `0.15` | `0.0..` | non | non | Désalignement maximal toléré d'une suspension déformée, en mètres, avant signalement. |

### `[attachment]`

| Option | Type | Défaut | Domaine | Chaud | Serveur | Description |
|---|---|---|---|---|---|---|
| `max_chain` | entier | `8` | `0..` | non | non | Longueur maximale d'une chaîne d'assemblies attachées. |

### `[net]`

| Option | Type | Défaut | Domaine | Chaud | Serveur | Description |
|---|---|---|---|---|---|---|
| `snapshot_rate_hz` | entier | `10` | `2..20` | non | non | Fréquence d'émission des snapshots d'état, en hertz. |
| `sync_radius` | flottant | `96.0` | `0.0..` | non | non | Rayon de synchronisation autour d'un joueur, en blocs. |
| `interpolation_delay_ms` | entier | `100` | `0..` | non | non | Retard d'interpolation appliqué côté client, en millisecondes. |
| `extrapolation_max_ms` | entier | `250` | `0..` | non | non | Durée maximale d'extrapolation en l'absence de snapshot, en millisecondes. |
| `snap_threshold` | flottant | `2.0` | `0.0..` | non | non | Écart, en mètres, au-delà duquel le client repositionne brutalement plutôt qu'interpoler. |
| `prediction` | booléen | `true` | `true \| false` | non | non | Active la prédiction côté client pour l'assembly pilotée par le joueur. |
| `prediction_max_ping` | entier | `400` | `0..` | non | non | Latence maximale, en millisecondes, au-delà de laquelle la prédiction est désactivée. |
| `max_bytes_per_second_per_player` | entier | `32768` | `0..` | non | non | Plafond de bande passante AXION par joueur, en octets par seconde. |
| `max_packets_per_second` | entier | `40` | `0..` | non | non | Plafond de paquets AXION par seconde et par joueur. |
| `max_impacts_per_packet` | entier | `64` | `0..` | non | non | Nombre maximal d'impacts groupés dans un paquet. |
| `digest_interval_ticks` | entier | `40` | `0..` | non | non | Intervalle, en ticks, entre deux empreintes de vérification d'état. |
| `max_deform_snapshots_per_second` | entier | `4` | `0..` | non | non | Plafond de snapshots de déformation par seconde en réplication par événements. |
| `max_deform_snapshots_per_second_fallback` | entier | `16` | `0..` | non | non | Plafond de snapshots de déformation par seconde en mode SNAPSHOT, quand la reconstruction déterministe n'est pas garantie (5.12bis). |
| `divergence_tolerance` | entier | `3` | `1..32` | non | non | Nombre de divergences d'empreinte tolérées avant bascule de RECONSTRUCT vers SNAPSHOT. |

### `[persistence]`

| Option | Type | Défaut | Domaine | Chaud | Serveur | Description |
|---|---|---|---|---|---|---|
| `max_bytes_per_assembly` | entier | `65536` | `0..` | non | non | Taille maximale de l'état persisté d'une assembly, en octets. |
| `max_custom_bytes` | entier | `16384` | `0..` | non | non | Taille maximale des données personnalisées persistées par une assembly, en octets. |
| `max_persisted_decals` | entier | `16` | `0..` | non | non | Nombre maximal de décalques sauvegardés par assembly. |
| `max_load_ns_per_tick` | entier | `500000` | `0..` | non | non | Budget de chargement d'état persisté, en nanosecondes par tick. |
| `journal_enabled` | booléen | `true` | `true \| false` | non | non | Active le journal latéral de récupération (22.5.2). |
| `journal_ns_per_tick` | entier | `200000` | `0..2000000` | non | non | Budget d'écriture du journal, en nanosecondes par tick. |
| `journal_max_bytes` | entier | `268435456` | `0..` | non | non | Taille maximale du journal, en octets. |

### `[limits]`

| Option | Type | Défaut | Domaine | Chaud | Serveur | Description |
|---|---|---|---|---|---|---|
| `max_assemblies_per_dimension` | entier | `4096` | `0..` | non | non | Nombre maximal d'assemblies par dimension. |
| `max_spawn_per_command` | entier | `64` | `0..` | non | non | Nombre maximal d'assemblies créées par une seule commande. |

### `[watchdog]`

| Option | Type | Défaut | Domaine | Chaud | Serveur | Description |
|---|---|---|---|---|---|---|
| `job_timeout_ms` | entier | `5000` | `0..` | non | non | Durée au-delà de laquelle un job est signalé en dépassement. Le watchdog ne tue jamais de thread (R-1901). |

### `[modules]`

| Option | Type | Défaut | Domaine | Chaud | Serveur | Description |
|---|---|---|---|---|---|---|
| `vehicles` | booléen | `true` | `true \| false` | non | non | Active le module véhicules. |
| `joints` | booléen | `true` | `true \| false` | non | non | Active le module joints et contraintes. |
| `damage` | booléen | `true` | `true \| false` | non | non | Active le module dommage. |
| `deformation` | booléen | `true` | `true \| false` | non | non | Active le module déformation. |
| `structure` | booléen | `true` | `true \| false` | non | non | Active le module intégrité structurelle. |
| `repair` | booléen | `true` | `true \| false` | non | non | Active le module réparation. |
| `wear` | booléen | `true` | `true \| false` | non | non | Active le module usure de surface. |
| `particles` | booléen | `true` | `true \| false` | non | non | Active le module particules. |
| `animation` | booléen | `true` | `true \| false` | non | non | Active le module animation. |
| `attachments` | booléen | `true` | `true \| false` | non | non | Active le module attaches entre assemblies. |
| `blocks` | booléen | `true` | `true \| false` | non | non | Active le module blocs et items 3D. |

### `[integration.rustforgex]`

| Option | Type | Défaut | Domaine | Chaud | Serveur | Description |
|---|---|---|---|---|---|---|
| `mode` | chaîne | `"auto"` | `auto \| off` | non | non | Détection d'un mod d'optimisation tiers. AXION n'en dépend jamais et fonctionne identiquement en son absence. |
| `cpu_share` | chaîne | `"auto"` | `auto \| half \| full \| <entier>` | non | non | Part de parallélisme qu'AXION s'autorise en présence d'un autre consommateur de CPU. Un entier fixe le nombre de threads. |
| `bridge` | booléen | `true` | `true \| false` | non | non | Active le pont d'interopérabilité optionnel. Réflexif, isolé, désactivable ; aucune fonctionnalité d'AXION n'en dépend. |
| `hint_no_transform` | booléen | `true` | `true \| false` | non | non | Signale aux outils tiers qu'AXION préfère ne pas voir son bytecode transformé. Une indication, jamais une exigence. |

### `[debug]`

| Option | Type | Défaut | Domaine | Chaud | Serveur | Description |
|---|---|---|---|---|---|---|
| `checksum_buffers` | booléen | `false` | `true \| false` | oui | non | Vérifie l'intégrité des tampons échangés avec le natif. Coûteux, réservé au diagnostic. |
| `record_incidents` | booléen | `false` | `true \| false` | oui | non | Enregistre de quoi rejouer un incident. |

## `axion-client.toml`

### `[render]`

| Option | Type | Défaut | Domaine | Chaud | Serveur | Description |
|---|---|---|---|---|---|---|
| `backend` | chaîne | `"auto"` | `auto \| native \| vanilla` | non | non | Backend de rendu. auto bascule sur vanilla en présence d'un shaderpack. |
| `max_distance` | flottant | `128.0` | `0.0..` | non | non | Distance maximale de rendu des objets AXION, en blocs. |
| `lod_bias` | entier | `0` | `-2..3` | non | non | Décalage appliqué au choix du niveau de détail. Négatif privilégie la qualité. |
| `instancing_threshold` | entier | `4` | `0..` | non | non | Nombre d'instances identiques à partir duquel l'instanciation GPU est employée. |
| `indirect` | booléen | `true` | `true \| false` | non | non | Autorise le rendu indirect. Utilisé seulement si GL 4.3 est disponible. |
| `compute` | booléen | `true` | `true \| false` | non | non | Autorise les shaders de calcul. Utilisé seulement si GL 4.3 est disponible. |
| `max_skinned_instances` | entier | `64` | `0..` | non | non | Nombre maximal d'instances animées par squelette et par frame. |
| `max_palette_bones` | entier | `128` | `0..` | non | non | Nombre maximal d'os dans une palette de skinning. |
| `max_deform_pages_per_frame` | entier | `256` | `0..` | non | non | Nombre maximal de pages de déformation téléversées vers le GPU par frame. |
| `max_decals_per_assembly` | entier | `64` | `0..` | non | non | Nombre maximal de décalques portés par une assembly. |
| `max_morph_targets_per_asset` | entier | `8` | `0..` | non | non | Nombre maximal de cibles de morphing par asset. |
| `max_shader_variants` | entier | `64` | `0..` | non | non | Nombre maximal de variantes de shader compilées. |
| `vanilla_max_skinned_vertices` | entier | `50000` | `0..` | non | non | Plafond de sommets animés par frame dans le backend vanilla. |
| `vanilla_max_deformed_vertices` | entier | `100000` | `0..` | non | non | Plafond de sommets déformés par frame dans le backend vanilla. |
| `vanilla_max_decals` | entier | `64` | `0..` | non | non | Plafond de décalques par frame dans le backend vanilla. |
| `per_node_lightmap` | booléen | `false` | `true \| false` | non | non | Échantillonne la lumière par nœud plutôt que par assembly. Plus fidèle, plus coûteux. |
| `item_max_triangles` | entier | `20000` | `0..` | non | non | Nombre maximal de triangles pour le rendu 3D d'un item. |
| `shadow` | chaîne | `"map"` | `map \| contact \| none` | non | non | Technique d'ombre propre aux objets AXION. |
| `shadow_ground_quad` | booléen | `true` | `true \| false` | non | non | Ajoute une ombre de contact au sol sous les objets. |
| `shadow_ns` | entier | `1500000` | `0..` | non | non | Budget du rendu des ombres, en nanosecondes par frame. |
| `probe_interval_frames` | entier | `20` | `0..` | non | non | Intervalle, en frames, entre deux mises à jour de la sonde d'environnement. |
| `ssr_max_roughness` | flottant | `0.25` | `0.0..` | non | non | Rugosité maximale au-delà de laquelle les réflexions en espace écran sont abandonnées. |
| `max_occluders` | entier | `512` | `0..` | non | non | Nombre maximal d'occulteurs retenus pour l'occlusion culling logiciel. |
| `backend_silhouette_tolerance` | flottant | `0.01` | `0.0..0.05` | non | non | Écart de silhouette toléré entre les deux backends de rendu (19.2bis). |

### `[quality]`

| Option | Type | Défaut | Domaine | Chaud | Serveur | Description |
|---|---|---|---|---|---|---|
| `deformation` | chaîne | `"auto"` | `auto \| off \| low \| medium \| high \| ultra` | non | non | Niveau de qualité de la déformation côté client. |
| `particles` | chaîne | `"auto"` | `auto \| off \| low \| medium \| high \| ultra` | non | non | Niveau de qualité des particules. |
| `shadows` | chaîne | `"auto"` | `auto \| off \| low \| medium \| high \| ultra` | non | non | Niveau de qualité des ombres. |
| `decals` | chaîne | `"auto"` | `auto \| off \| low \| medium \| high \| ultra` | non | non | Niveau de qualité des décalques. |
| `occlusion` | chaîne | `"auto"` | `auto \| off \| low \| medium \| high \| ultra` | non | non | Niveau de qualité de l'occlusion culling. |
| `lighting` | chaîne | `"auto"` | `auto \| off \| low \| medium \| high \| ultra` | non | non | Niveau de qualité de l'éclairage. |
| `skinning` | chaîne | `"auto"` | `auto \| off \| low \| medium \| high \| ultra` | non | non | Niveau de qualité du skinning. |
| `reflections` | chaîne | `"auto"` | `auto \| off \| low \| medium \| high \| ultra` | non | non | Niveau de qualité des réflexions. |
| `parallax` | chaîne | `"auto"` | `auto \| off \| low \| medium \| high \| ultra` | non | non | Niveau de qualité du parallax mapping. |

### `[overlay]`

| Option | Type | Défaut | Domaine | Chaud | Serveur | Description |
|---|---|---|---|---|---|---|
| `enabled` | booléen | `false` | `true \| false` | oui | non | Affiche l'overlay de diagnostic. |
| `key` | chaîne | `"unbound"` | `texte libre` | oui | non | Touche d'activation de l'overlay. unbound laisse l'overlay sans raccourci. |

### `[debug]`

| Option | Type | Défaut | Domaine | Chaud | Serveur | Description |
|---|---|---|---|---|---|---|
| `gl` | booléen | `false` | `true \| false` | oui | non | Active la validation des appels OpenGL. Coûteux, réservé au diagnostic. |

## `axion-server.toml`

### `[server]`

| Option | Type | Défaut | Domaine | Chaud | Serveur | Description |
|---|---|---|---|---|---|---|
| `allow_client_prediction` | booléen | `true` | `true \| false` | non | non | Autorise les clients à prédire le mouvement de l'assembly qu'ils pilotent. |
| `kick_on_input_abuse` | booléen | `true` | `true \| false` | non | non | Expulse un client dont les entrées sortent des bornes admises de façon répétée. |
| `allow_experimental_authority` | booléen | `false` | `true \| false` | non | non | Autorise le mode d'autorité SERVER_FULL pour les particules. EXPERIMENTAL. |
