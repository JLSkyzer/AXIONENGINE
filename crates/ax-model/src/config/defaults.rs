//! Registre des options de configuration d'AXION ENGINE.
//!
//! Ce fichier est la **source unique** dont R-430 et R-2400 parlent : les
//! fichiers TOML livrés, `CONFIGURATION.md` et l'ANNEXE A.3 du cahier des
//! charges en sont tous dérivés. Ajouter une option ici suffit à la faire
//! apparaître partout ; l'ajouter ailleurs sans passer par ici casse le build.
//!
//! L'ordre des options fixe l'ordre des fichiers rendus : les options d'une
//! même section doivent rester contiguës.

use super::{ConfigDomain, ConfigOption, ConfigValue};

/// Niveaux de qualité d'un sous-système (24.2).
///
/// `auto` laisse le gouverneur (C-77) choisir ; les cinq autres forcent
/// respectivement Q-0 à Q-4.
const QUALITY: &[&str] = &["auto", "off", "low", "medium", "high", "ultra"];

// --- Constructeurs -------------------------------------------------------
//
// Ils existent pour que le registre reste lisible : une option par ligne, avec
// son défaut et son domaine sous les yeux.

const fn opt(
    path: &'static str,
    default: ConfigValue,
    domain: ConfigDomain,
    description: &'static str,
) -> ConfigOption {
    ConfigOption {
        path,
        default,
        domain,
        hot: false,
        server_authoritative: false,
        description,
        // Chaque option la reçoit par `.en(...)`, sous la française ; un test
        // vérifie qu'aucune n'en manque.
        description_en: "",
    }
}

/// Booléen.
const fn b(path: &'static str, default: bool, description: &'static str) -> ConfigOption {
    opt(
        path,
        ConfigValue::Bool(default),
        ConfigDomain::Boolean,
        description,
    )
}

/// Entier borné des deux côtés.
const fn i(
    path: &'static str,
    default: i64,
    min: i64,
    max: i64,
    description: &'static str,
) -> ConfigOption {
    opt(
        path,
        ConfigValue::Int(default),
        ConfigDomain::IntRange { min, max },
        description,
    )
}

/// Entier positif dont le cahier des charges ne fixe pas de borne haute.
///
/// La borne au maximum du type n'invente aucune limite métier : elle dit
/// seulement qu'une valeur négative n'a pas de sens.
const fn ip(path: &'static str, default: i64, description: &'static str) -> ConfigOption {
    i(path, default, 0, i64::MAX, description)
}

/// Flottant borné des deux côtés.
const fn f(
    path: &'static str,
    default: f64,
    min: f64,
    max: f64,
    description: &'static str,
) -> ConfigOption {
    opt(
        path,
        ConfigValue::Float(default),
        ConfigDomain::FloatRange { min, max },
        description,
    )
}

/// Flottant positif sans borne haute spécifiée.
const fn fp(path: &'static str, default: f64, description: &'static str) -> ConfigOption {
    f(path, default, 0.0, f64::MAX, description)
}

/// Flottant restreint à un ensemble de valeurs.
const fn fs(
    path: &'static str,
    default: f64,
    allowed: &'static [f64],
    description: &'static str,
) -> ConfigOption {
    opt(
        path,
        ConfigValue::Float(default),
        ConfigDomain::FloatSet(allowed),
        description,
    )
}

/// Chaîne appartenant à un ensemble fermé.
const fn e(
    path: &'static str,
    default: &'static str,
    allowed: &'static [&'static str],
    description: &'static str,
) -> ConfigOption {
    opt(
        path,
        ConfigValue::Str(default),
        ConfigDomain::Enumeration(allowed),
        description,
    )
}

/// Marque une option rechargeable par `/axion config reload` (R-431).
const fn hot(mut option: ConfigOption) -> ConfigOption {
    option.hot = true;
    option
}

/// Marque une option imposée par le serveur au handshake (R-1830).
const fn srv(mut option: ConfigOption) -> ConfigOption {
    option.server_authoritative = true;
    option
}

/// Options de `axion-common.toml` : simulation, budgets, limites, dommage,
/// déformation.
pub const COMMON: &[ConfigOption] = &[
    // [general]
    b("general.enabled", true, "Active AXION. À false, le mod se charge en mode DISABLED et les assemblies restent inertes.")
        .en("Enables AXION. When false, the mod loads in DISABLED mode and assemblies stay inert."),
    b("general.maturity_allow_experimental", false, "Autorise l'activation des fonctionnalités marquées EXPERIMENTAL.")
        .en("Allows enabling features marked EXPERIMENTAL."),

    // [sim]
    srv(fs("sim.fixed_dt", 0.0166667, &[0.0333333, 0.0166667, 0.0083333], "Pas de temps fixe de la simulation, en secondes. Un pas intermédiaire désynchroniserait la simulation des ticks du serveur."))
        .en("Fixed simulation time step, in seconds. An intermediate step would desynchronise the simulation from server ticks."),
    srv(i("sim.max_substeps", 4, 1, 8, "Nombre maximal de sous-pas rattrapés en un tick lorsque le temps réel dépasse le pas fixe."))
        .en("Maximum number of substeps caught up in one tick when real time runs ahead of the fixed step."),
    f("sim.simulation_radius", 128.0, 16.0, 512.0, "Rayon de simulation autour d'un joueur, en blocs. Au-delà, les assemblies sont endormies.")
        .en("Simulation radius around a player, in blocks. Beyond it, assemblies are put to sleep."),

    // [physics]
    srv(f("physics.gravity", -9.81, -50.0, 0.0, "Accélération de la pesanteur, en m/s²."))
        .en("Gravitational acceleration, in m/s²."),
    srv(i("physics.velocity_iterations", 4, 2, 16, "Itérations de résolution des vitesses par pas. Plus élevé donne des contacts plus stables et coûte plus cher."))
        .en("Velocity solver iterations per step. Higher gives more stable contacts and costs more."),
    srv(i("physics.position_iterations", 1, 0, 8, "Itérations de correction des positions par pas, contre l'interpénétration résiduelle."))
        .en("Position correction iterations per step, against residual interpenetration."),
    f("physics.broadphase_cell_size", 2.0, 0.5, 16.0, "Taille de cellule de la broad phase, en blocs.")
        .en("Broad phase cell size, in blocks."),
    f("physics.contact_event_threshold", 0.5, 0.0, 100.0, "Impulsion minimale, en N·s, pour qu'un contact produise un événement.")
        .en("Minimum impulse, in N·s, for a contact to produce an event."),
    i("physics.max_events_per_tick", 4096, 256, 65536, "Plafond d'événements physiques traités par tick.")
        .en("Cap on physics events processed per tick."),
    // R-614 : l'effet d'une collision sur une entité vanilla est configurable — amendement A1
    // de l'ANNEXE A.3 (38.9), ADR-123.
    srv(b("physics.entity_push", true, "Les assemblies poussent les entités vanilla qu'elles heurtent, joueurs exceptés."))
        .en("Assemblies push the vanilla entities they hit, players excepted."),
    srv(b("physics.entity_damage", true, "Un choc contre une assembly blesse les entités vivantes, comme une chute de même vitesse."))
        .en("A hit from an assembly hurts living entities, like a fall at the same speed."),
    // ADR-176 D5 : le mouillé d'un véhicule suit la pluie qu'il reçoit, lue par le serveur.
    srv(f("physics.wet_rise_seconds", 2.0, 0.1, 60.0, "Secondes qu'un véhicule sous la pluie met à devenir tout à fait mouillé."))
        .en("Seconds a vehicle in the rain takes to become fully wet."),
    srv(f("physics.wet_dry_seconds", 30.0, 0.1, 600.0, "Secondes qu'un véhicule mouillé, plus sous la pluie, met à sécher tout à fait."))
        .en("Seconds a wet vehicle, no longer in the rain, takes to dry out completely."),

    // ADR-178 D7 : un joueur, ou toute entité, ne traverse plus une assembly (CAP-14) ; faux rend le jeu d'avant.
    srv(b("physics.player_carry", true, "Les entités et les joueurs sont arrêtés par les colliders d'une assembly, et un joueur debout dessus est porté."))
        .en("Entities and players are stopped by an assembly's colliders, and a player standing on one is carried."),
    srv(i("physics.carry_max_entities", 64, 0, 1024, "Nombre maximal d'entités, hors joueurs, qu'une assembly porte."))
        .en("Maximum number of entities, players excepted, that one assembly carries."),
    srv(f("physics.player_proxy_mass", 80.0, 0.0, 6000.0, "Masse prêtée au joueur par la physique, en kg : il pèse sur ce qu'il touche et ralentit ce qui le heurte ; 0 : masse infinie, un mur."))
        .en("Mass the physics lends a player, in kg: they weigh on what they touch and slow what hits them; 0: infinite mass, a wall."),
    srv(f("physics.push_speed_max", 10.0, 0.0, 100.0, "Vitesse la plus grande, en m/s, à laquelle une assembly qui avance dans un joueur le repousse."))
        .en("Highest speed, in m/s, at which an assembly moving into a player pushes them out."),

    // [damage]
    b("damage.enabled", true, "Active la chaîne de dommage : impacts, déformation, rupture, usure.")
        .en("Enables the damage chain: impacts, deformation, breakage, wear."),
    i("damage.max_impacts_per_tick", 512, 32, 8192, "Plafond d'impacts résolus par tick. Le surplus est mis en file, jamais perdu.")
        .en("Cap on impacts resolved per tick. The surplus is queued, never lost."),
    f("damage.min_impact_energy", 5.0, 0.0, 1000.0, "Énergie minimale d'un impact, en joules, en deçà de laquelle il est ignoré.")
        .en("Minimum energy of an impact, in joules, below which it is ignored."),
    f("damage.max_impact_energy", 5000000.0, 1000.0, 1000000000.0, "Énergie maximale retenue pour un impact, en joules. Écrête les valeurs aberrantes.")
        .en("Maximum energy kept for an impact, in joules. Clips aberrant values."),
    i("damage.max_propagation_depth", 4, 0, 8, "Profondeur maximale de propagation de l'énergie dans le graphe structurel.")
        .en("Maximum depth to which energy propagates through the structural graph."),
    i("damage.max_assemblies_per_tick", 64, 4, 1024, "Nombre maximal d'assemblies traitées par la chaîne de dommage en un tick.")
        .en("Maximum number of assemblies processed by the damage chain in one tick."),
    ip("damage.pending_queue_max", 4096, "Taille maximale de la file d'impacts en attente.")
        .en("Maximum size of the pending impact queue."),
    i("damage.max_detach_per_tick", 4, 0, 64, "Nombre maximal de détachements de pièces par tick.")
        .en("Maximum number of part detachments per tick."),
    i("damage.max_debris", 96, 0, 512, "Nombre maximal de débris simultanés.")
        .en("Maximum number of simultaneous debris."),
    i("damage.max_debris_generation", 2, 0, 4, "Nombre maximal de générations successives de débris, pour borner la fragmentation en cascade.")
        .en("Maximum number of successive debris generations, to bound cascading fragmentation."),
    i("damage.debris_lifetime_s", 90, 5, 1200, "Durée de vie d'un débris, en secondes.")
        .en("Lifetime of a debris, in seconds."),
    ip("damage.debris_sleep_s", 5, "Délai d'immobilité, en secondes, au bout duquel un débris est endormi.")
        .en("Time at rest, in seconds, after which a debris is put to sleep."),
    b("damage.persist_debris", false, "Sauvegarde les débris avec le monde.")
        .en("Saves debris with the world."),
    fp("damage.explosion_sample_area", 0.5, "Aire représentée par un point d'échantillonnage d'explosion, en m².")
        .en("Area represented by one explosion sample point, in m²."),
    f("damage.default_sharp_factor", 8.0, 1.0, 64.0, "Facteur de concentration d'un impact tranchant : il réduit le rayon efficace, donc augmente la pénétration (13.3bis).")
        .en("Concentration factor of a sharp impact: it reduces the effective radius, so increases penetration (13.3bis)."),
    f("damage.default_blunt_factor", 2.0, 1.0, 16.0, "Facteur d'étalement d'un impact contondant : il augmente le rayon efficace.")
        .en("Spreading factor of a blunt impact: it increases the effective radius."),
    f("damage.default_shear_ratio", 0.6, 0.0, 1.0, "Rapport entre résistance au cisaillement et résistance en traction, quand le matériau ne la déclare pas.")
        .en("Ratio of shear strength to tensile strength, when the material does not declare it."),

    // [struct]
    f("struct.max_gap", 0.05, 0.0, 0.5, "Écart maximal, en mètres, entre deux pièces pour qu'une liaison structurelle soit générée automatiquement.")
        .en("Maximum gap, in metres, between two parts for a structural link to be generated automatically."),
    f("struct.max_link_area", 4.0, 0.01, 64.0, "Aire maximale d'une liaison structurelle, en m².")
        .en("Maximum area of a structural link, in m²."),
    b("struct.auto_links", true, "Génère automatiquement les liaisons structurelles entre pièces voisines.")
        .en("Automatically generates structural links between neighbouring parts."),

    // [deformation]
    b("deformation.enabled", true, "Active la déformation continue de géométrie.")
        .en("Enables continuous geometry deformation."),
    e("deformation.quality", "auto", QUALITY, "Niveau de qualité de la déformation. auto laisse le gouverneur décider.")
        .en("Deformation quality level. auto lets the governor decide."),
    ip("deformation.max_field_bytes_per_assembly", 262144, "Taille maximale du champ de déformation d'une assembly, en octets.")
        .en("Maximum size of an assembly's deformation field, in bytes."),
    ip("deformation.max_residual_vertices", 4096, "Nombre maximal de sommets portant un déplacement résiduel hors lattice.")
        .en("Maximum number of vertices carrying a residual displacement outside the lattice."),
    f("deformation.collider_refit_threshold", 0.04, 0.005, 0.25, "Écart, en mètres, entre géométrie visuelle et géométrie de collision au-delà duquel le collider est réajusté.")
        .en("Gap, in metres, between visual and collision geometry beyond which the collider is refitted."),
    fp("deformation.mass_update_threshold", 0.05, "Variation relative de masse à partir de laquelle les propriétés inertielles sont recalculées.")
        .en("Relative mass change from which inertial properties are recomputed."),
    fp("deformation.elastic_release_s", 5.0, "Temps de retour à zéro d'une déformation élastique, en secondes.")
        .en("Time for an elastic deformation to return to zero, in seconds."),
    b("deformation.tear_enabled", false, "Active la déchirure du maillage. EXPERIMENTAL.")
        .en("Enables mesh tearing. EXPERIMENTAL."),
    fp("deformation.tear_max_cells_per_region", 0.08, "Fraction maximale des cellules d'une région pouvant être déchirées.")
        .en("Maximum fraction of a region's cells that can be torn."),

    // [particles]
    b("particles.enabled", true, "Active le solveur de particules : tissu, cordes, câbles, filets, corps souples.")
        .en("Enables the particle solver: cloth, ropes, cables, nets, soft bodies."),
    e("particles.quality", "auto", QUALITY, "Niveau de qualité du solveur de particules.")
        .en("Particle solver quality level."),
    ip("particles.max_sets", 48, "Nombre maximal d'ensembles de particules simulés côté client.")
        .en("Maximum number of particle sets simulated on the client."),
    ip("particles.max_sets_server", 16, "Nombre maximal d'ensembles de particules simulés côté serveur.")
        .en("Maximum number of particle sets simulated on the server."),
    b("particles.self_collide", false, "Active l'auto-collision des particules. EXPERIMENTAL.")
        .en("Enables particle self-collision. EXPERIMENTAL."),
    b("particles.tear", false, "Active la déchirure des ensembles de particules. EXPERIMENTAL.")
        .en("Enables tearing of particle sets. EXPERIMENTAL."),
    f("particles.visual_drift_max", 0.25, 0.02, 2.0, "Dérive visuelle maximale tolérée, en mètres, entre la simulation client et l'état serveur (17.2bis).")
        .en("Maximum visual drift tolerated, in metres, between the client simulation and the server state (17.2bis)."),
    i("particles.visual_freeze_ms", 500, 100, 5000, "Durée pendant laquelle l'affichage est gelé faute d'état serveur, en millisecondes.")
        .en("Time during which the display is frozen for lack of server state, in milliseconds."),

    // [budgets]
    ip("budgets.sim_ns_per_tick", 3000000, "Budget de la simulation physique, en nanosecondes par tick.")
        .en("Physics simulation budget, in nanoseconds per tick."),
    ip("budgets.damage_ns_per_tick", 1000000, "Budget de la chaîne de dommage, en nanosecondes par tick.")
        .en("Damage chain budget, in nanoseconds per tick."),
    ip("budgets.deformation_ns_per_tick", 1500000, "Budget de la déformation, en nanosecondes par tick.")
        .en("Deformation budget, in nanoseconds per tick."),
    ip("budgets.particles_ns_per_tick", 1000000, "Budget du solveur de particules, en nanosecondes par tick.")
        .en("Particle solver budget, in nanoseconds per tick."),
    ip("budgets.render_prep_ns", 2000000, "Budget de préparation du rendu, en nanosecondes par frame.")
        .en("Render preparation budget, in nanoseconds per frame."),
    ip("budgets.occlusion_ns", 800000, "Budget de l'occlusion culling logiciel, en nanosecondes par frame.")
        .en("Software occlusion culling budget, in nanoseconds per frame."),
    ip("budgets.asset_ns_per_tick", 1000000, "Budget du chargement et de la compilation d'assets, en nanosecondes par tick.")
        .en("Asset loading and compilation budget, in nanoseconds per tick."),
    ip("budgets.idle_hook_ns", 50000, "Surcoût maximal des hooks Forge par tick lorsqu'aucune assembly n'est présente, en nanosecondes.")
        .en("Maximum overhead of the Forge hooks per tick when no assembly is present, in nanoseconds."),
    ip("budgets.submit_ns", 200000, "Budget de soumission des commandes de rendu, en nanosecondes par frame.")
        .en("Render command submission budget, in nanoseconds per frame."),
    ip("budgets.native_mem_bytes", 536870912, "Plafond de mémoire native, en octets.")
        .en("Native memory cap, in bytes."),
    ip("budgets.deform_mem_bytes", 134217728, "Plafond de mémoire des champs de déformation, en octets.")
        .en("Memory cap of deformation fields, in bytes."),
    ip("budgets.gpu_mem_bytes", 536870912, "Plafond de mémoire GPU gérée par AXION, en octets.")
        .en("Cap on GPU memory managed by AXION, in bytes."),
    ip("budgets.max_active_bodies", 2048, "Nombre maximal de corps physiques actifs simultanément.")
        .en("Maximum number of physics bodies active at once."),
    ip("budgets.max_deformed_assemblies", 256, "Nombre maximal d'assemblies portant un champ de déformation.")
        .en("Maximum number of assemblies carrying a deformation field."),
    ip("budgets.max_collider_refits_per_tick", 8, "Nombre maximal de réajustements de collider par tick.")
        .en("Maximum number of collider refits per tick."),
    ip("budgets.max_visible_instances", 512, "Nombre maximal d'instances rendues par frame.")
        .en("Maximum number of instances rendered per frame."),
    ip("budgets.max_triangles_frame", 3000000, "Nombre maximal de triangles rendus par frame.")
        .en("Maximum number of triangles rendered per frame."),
    ip("budgets.max_decals_frame", 512, "Nombre maximal de décalques rendus par frame.")
        .en("Maximum number of decals rendered per frame."),
    ip("budgets.max_shadow_instances", 128, "Nombre maximal d'instances projetant une ombre AXION.")
        .en("Maximum number of instances casting an AXION shadow."),

    // [jobs]
    ip("jobs.max_workers", 0, "Nombre de threads du pool de jobs. 0 laisse AXION le déduire du nombre de cœurs disponibles.")
        .en("Number of threads in the job pool. 0 lets AXION derive it from the number of available cores."),

    // [assets]
    ip("assets.max_source_bytes", 134217728, "Taille maximale d'un fichier source d'asset accepté, en octets.")
        .en("Maximum size of an accepted asset source file, in bytes."),
    ip("assets.max_compiled_bytes", 268435456, "Taille maximale d'un asset compilé, en octets.")
        .en("Maximum size of a compiled asset, in bytes."),
    ip("assets.max_deform_bytes", 4194304, "Taille maximale des données de déformation compilées d'un asset, en octets.")
        .en("Maximum size of an asset's compiled deformation data, in bytes."),
    ip("assets.max_compile_ms", 30000, "Durée maximale de compilation d'un asset, en millisecondes.")
        .en("Maximum compilation time of an asset, in milliseconds."),
    ip("assets.cache_max_bytes", 2147483648, "Taille maximale du cache d'assets compilés, en octets.")
        .en("Maximum size of the compiled asset cache, in bytes."),
    ip("assets.startup_timeout_s", 120, "Délai maximal de préparation des assets au démarrage, en secondes.")
        .en("Maximum time to prepare assets at startup, in seconds."),
    ip("assets.unload_delay_s", 60, "Délai avant déchargement d'un asset inutilisé, en secondes.")
        .en("Delay before an unused asset is unloaded, in seconds."),

    // [world]
    i("world.tiles_per_tick", 8, 1, 64, "Nombre de tuiles de collision monde extraites par tick.")
        .en("Number of world collision tiles extracted per tick."),
    i("world.tile_radius", 3, 1, 8, "Rayon, en tuiles, de la géométrie de collision monde maintenue autour d'une assembly.")
        .en("Radius, in tiles, of the world collision geometry kept around an assembly."),

    // [vehicle]
    fp("vehicle.debris_ignore_s", 1.0, "Durée pendant laquelle un véhicule ignore les collisions avec ses propres débris, en secondes.")
        .en("Time during which a vehicle ignores collisions with its own debris, in seconds."),
    fp("vehicle.max_suspension_misalignment", 0.15, "Désalignement maximal toléré d'une suspension déformée, en mètres, avant signalement.")
        .en("Maximum misalignment tolerated for a deformed suspension, in metres, before it is reported."),
    // ADR-167 D9 : le coût de chaque contrôleur de véhicule est mesuré à chaque appel (R-1770).
    i("vehicle.controller_budget_ns", 50000, 1000, 50000000, "Durée d'un appel de contrôleur de véhicule au-delà de laquelle il compte une faute, en nanosecondes.")
        .en("Duration of a vehicle controller call beyond which it counts a fault, in nanoseconds."),
    i("vehicle.controller_strikes", 20, 1, 1200, "Fautes consécutives d'un contrôleur de véhicule (dépassements du budget ou exceptions) qui le désactivent pour la session.")
        .en("Consecutive faults of a vehicle controller (budget overruns or exceptions) that disable it for the session."),

    // [attachment]
    ip("attachment.max_chain", 8, "Longueur maximale d'une chaîne d'assemblies attachées.")
        .en("Maximum length of a chain of attached assemblies."),
    // ADR-171 D4 : où l'interaction `attach` cherche l'autre socket, quand elle ne le dit pas.
    f("attachment.snap_distance", 2.0, 0.1, 16.0, "Distance à laquelle l'interaction attach cherche le socket d'une autre assembly, en blocs, quand elle ne déclare pas la sienne.")
        .en("Distance within which the attach interaction looks for another assembly's socket, in blocks, when it does not declare its own."),

    // [net]
    i("net.snapshot_rate_hz", 10, 2, 20, "Fréquence d'émission des snapshots d'état, en hertz.")
        .en("Rate at which state snapshots are sent, in hertz."),
    fp("net.sync_radius", 96.0, "Rayon de synchronisation autour d'un joueur, en blocs.")
        .en("Synchronisation radius around a player, in blocks."),
    // Borne du champ `u16` de `S2C_Handshake` (21.3), qui la porte au client (ADR-150).
    i("net.interpolation_delay_ms", 100, 0, 65_535, "Retard d'interpolation appliqué côté client, en millisecondes.")
        .en("Interpolation delay applied on the client, in milliseconds."),
    ip("net.extrapolation_max_ms", 250, "Durée maximale d'extrapolation en l'absence de snapshot, en millisecondes.")
        .en("Maximum extrapolation time when no snapshot arrives, in milliseconds."),
    fp("net.snap_threshold", 2.0, "Écart, en mètres, au-delà duquel le client repositionne brutalement plutôt qu'interpoler.")
        .en("Gap, in metres, beyond which the client snaps to position instead of interpolating."),
    // ADR-169 : le rattrapage de la course de connexion de Forge 47 — une lecture du client restée désinscrite à la
    // connexion se redemande. Un autre mod qui la corrige ne le gêne pas ; coupé, la connexion bloquée expire en 30 s.
    b("net.connection_read_rescue", true, "Rattrape la course de connexion de Forge 47 : une lecture du client restée coupée à la connexion se redemande.")
        .en("Recovers from the Forge 47 connection race: a client read left disabled while connecting is requested again."),
    b("net.prediction", true, "Active la prédiction côté client pour l'assembly pilotée par le joueur.")
        .en("Enables client-side prediction for the assembly the player drives."),
    ip("net.prediction_max_ping", 400, "Latence maximale, en millisecondes, au-delà de laquelle la prédiction est désactivée.")
        .en("Maximum latency, in milliseconds, beyond which prediction is disabled."),
    // ADR-168 D8, précision 6e, décision de Killian du 2026-11-14 : une prédiction coupée par sa latence ne se rouvre
    // que sous cette part de net.prediction_max_ping. Sans marge, elle se coupait et se rouvrait plusieurs fois de
    // suite au seuil (essai à deux processus de la Charger : coupée à 404 ms, rouverte, recoupée à 401 ms).
    f("net.prediction_reenable_ratio", 0.9, 0.0, 1.0, "Part de net.prediction_max_ping sous laquelle une prédiction coupée par sa latence se rétablit.")
        .en("Share of net.prediction_max_ping below which a prediction cut off by its latency comes back."),
    // ADR-168 D8, D10, R-1683 : le pire taux de corrections démenties sur 100 corrections glissantes, mesuré par la
    // campagne à deux processus du 2026-11-15 (tools/campagne/prediction.py), sessions saines : 20 à 27 % pour le
    // véhicule de test (50 et 100 ms par sens, gigue de 20 ms), 15 % pour la Charger (50 ms). Le seuil en garde deux
    // fois la marge : au-delà, la prédiction est démentie une correction sur deux et ne fait plus que se reprendre.
    f("net.prediction_max_corrections", 0.5, 0.0, 1.0, "Part des corrections, sur les 100 dernières, qui démentent la prédiction (rejouées ou acceptées sans rejeu) au-delà de laquelle elle est désactivée ; 1 ne la désactive jamais.")
        .en("Share of corrections, over the last 100, that contradict prediction (replayed or accepted without replay) beyond which it is disabled; 1 never disables it."),
    // ADR-168 D5, D10 : p99 mesuré d'un rejeu de 64 ticks, restauration comprise, au banc du vrai client (essai à deux
    // processus, 2026-11-13 : p50 5,06 ms, p90 6,90 ms, p99 8,17 ms sur 100 mesures).
    ip("net.prediction_replay_budget_ns", 8_200_000, "Durée maximale d'un rejeu de la prédiction, en nanosecondes ; au-delà, le client accepte l'état du serveur sans rejouer.")
        .en("Maximum duration of a prediction replay, in nanoseconds; beyond it, the client accepts the server state without replaying."),
    // ADR-168 D5, précision 6c-B : l'écart entre l'état prédit et celui du serveur en deçà duquel le client ne rejoue
    // pas. Chaque seuil vaut au plus 1 cm de dérive en un tick, un sixième de pixel de bloc ; le bruit mesuré entre
    // deux mondes natifs, essais du 2026-11-13 (151 comparaisons), reste dessous : position 6,3 mm, rotation
    // 2,1 mrad, vitesse 9,2 cm/s au plus.
    fp("net.prediction_tolerance_position_m", 0.01, "Écart de position, en mètres, en deçà duquel l'état prédit vaut celui du serveur.")
        .en("Position gap, in metres, below which the predicted state counts as the server's."),
    fp("net.prediction_tolerance_rotation_rad", 0.005, "Écart de rotation, en radians, en deçà duquel l'état prédit vaut celui du serveur.")
        .en("Rotation gap, in radians, below which the predicted state counts as the server's."),
    fp("net.prediction_tolerance_velocity_ms", 0.2, "Écart de vitesse, en mètres par seconde, en deçà duquel l'état prédit vaut celui du serveur.")
        .en("Velocity gap, in metres per second, below which the predicted state counts as the server's."),
    // ADR-176 D3 : un plein fait par le serveur ne passe pas par le flux d'entrées ; sans cet écart, le client garderait un
    // réservoir périmé tant qu'aucune autre dérive ne l'oblige à se corriger.
    // ADR-176 D4 : une usure posée par le serveur (un pneu changé) ne passe pas non plus par le flux d'entrées. Un cran de
    // l'octet du snapshot vaut 0,0039 : le seuil le dépasse, un pneu qui s'use de lui-même ne force aucune correction.
    // ADR-176 D5 : le mouillé de la route vient de la pluie que le serveur lit ; le client le reçoit. Un cran de l'octet du
    // snapshot vaut 0,0039 : le seuil le dépasse, un véhicule qui se mouille de lui-même ne force aucune correction à chaque cran.
    fp("net.prediction_tolerance_wet", 0.05, "Écart du mouillé de la route, de 0 à 1, en deçà duquel l'état prédit vaut celui du serveur.")
        .en("Road wetness gap, from 0 to 1, below which the predicted state counts as the server's."),
    fp("net.prediction_tolerance_wear", 0.01, "Écart d'usure d'un pneu, de 0 à 1, en deçà duquel l'état prédit vaut celui du serveur.")
        .en("Tyre wear gap, from 0 to 1, below which the predicted state counts as the server's."),
    fp("net.prediction_tolerance_energy", 0.01, "Écart de carburant ou de batterie, en fraction de la plus grande des deux valeurs, en deçà duquel l'état prédit vaut celui du serveur.")
        .en("Fuel or battery gap, as a fraction of the larger of the two values, below which the predicted state counts as the server's."),
    // ADR-168, précision 6c-C2 : autour du véhicule prédit, la distance jusqu'à laquelle les autres assemblies et les
    // entités vanilla y sont posées en proxies. L'horizon la fixe : deux véhicules à 40 m/s qui se rapprochent pendant
    // net.prediction_max_ping (400 ms) se rejoignent de 32 blocs. Le coût ne la borne pas : un proxy coûte de 0,4 à
    // 0,7 µs par pas au banc du vrai client (essai à deux processus du 2026-11-14, 100 rejeux de 64 ticks par
    // compte de proxies) ; 32 proxies ajoutent 1,3 ms à un rejeu de 64 ticks.
    fp("net.prediction_proxy_radius", 32.0, "Distance, en blocs, autour du véhicule prédit, jusqu'à laquelle les autres assemblies et les entités sont posées en proxies dans le monde prédit.")
        .en("Distance, in blocks, around the predicted vehicle, up to which other assemblies and entities are placed as proxies in the predicted world."),
    ip("net.max_bytes_per_second_per_player", 32768, "Plafond de bande passante AXION par joueur, en octets par seconde.")
        .en("Cap on AXION bandwidth per player, in bytes per second."),
    ip("net.max_packets_per_second", 40, "Plafond de paquets AXION par seconde et par joueur.")
        .en("Cap on AXION packets per second per player."),
    // ADR-178 D4 : la sonde du serveur sous les pieds d'un joueur porté, à la pose que son client dessine.
    srv(f("net.carry_tolerance", 0.25, 0.05, 1.0, "Écart, en blocs, jusqu'auquel un joueur porté par une assembly est reconnu sur elle par le serveur."))
        .en("Gap, in blocks, up to which the server recognises a player carried by an assembly as standing on it."),
    ip("net.max_impacts_per_packet", 64, "Nombre maximal d'impacts groupés dans un paquet.")
        .en("Maximum number of impacts grouped in one packet."),
    ip("net.digest_interval_ticks", 40, "Intervalle, en ticks, entre deux empreintes de vérification d'état.")
        .en("Interval, in ticks, between two state verification digests."),
    ip("net.max_deform_snapshots_per_second", 4, "Plafond de snapshots de déformation par seconde en réplication par événements.")
        .en("Cap on deformation snapshots per second in event-based replication."),
    ip("net.max_deform_snapshots_per_second_fallback", 16, "Plafond de snapshots de déformation par seconde en mode SNAPSHOT, quand la reconstruction déterministe n'est pas garantie (5.12bis).")
        .en("Cap on deformation snapshots per second in SNAPSHOT mode, when deterministic reconstruction is not guaranteed (5.12bis)."),
    i("net.divergence_tolerance", 3, 1, 32, "Nombre de divergences d'empreinte tolérées avant bascule de RECONSTRUCT vers SNAPSHOT.")
        .en("Number of digest divergences tolerated before switching from RECONSTRUCT to SNAPSHOT."),
    // ADR-174 D2 : la borne des événements de contenu qu'un tick envoie à un joueur.
    i("net.events_per_player_tick", 16, 1, 256, "Événements de contenu qu'un tick du serveur envoie au plus à un joueur ; au-delà, ils ne partent pas, comptés et journalisés.")
        .en("Content events a server tick sends to a player at most; beyond, they are not sent, counted and logged."),

    // [persistence]
    ip("persistence.max_bytes_per_assembly", 65536, "Taille maximale de l'état persisté d'une assembly, en octets.")
        .en("Maximum size of an assembly's persisted state, in bytes."),
    ip("persistence.max_custom_bytes", 16384, "Taille maximale des données personnalisées persistées par une assembly, en octets.")
        .en("Maximum size of the custom data persisted by an assembly, in bytes."),
    ip("persistence.max_persisted_decals", 16, "Nombre maximal de décalques sauvegardés par assembly.")
        .en("Maximum number of decals saved per assembly."),
    ip("persistence.max_load_ns_per_tick", 500000, "Budget de chargement d'état persisté, en nanosecondes par tick.")
        .en("Budget for loading persisted state, in nanoseconds per tick."),
    b("persistence.journal_enabled", true, "Active le journal latéral de récupération (22.5.2).")
        .en("Enables the side recovery journal (22.5.2)."),
    i("persistence.journal_ns_per_tick", 200000, 0, 2000000, "Budget d'écriture du journal, en nanosecondes par tick.")
        .en("Journal write budget, in nanoseconds per tick."),
    // CDC 30 : de 16 MiB a 8 GiB.
    i("persistence.journal_max_bytes", 268435456, 16777216, 8589934592, "Taille maximale du journal, en octets.")
        .en("Maximum size of the journal, in bytes."),

    // [limits]
    ip("limits.max_assemblies_per_dimension", 4096, "Nombre maximal d'assemblies par dimension.")
        .en("Maximum number of assemblies per dimension."),
    ip("limits.max_spawn_per_command", 64, "Nombre maximal d'assemblies créées par une seule commande.")
        .en("Maximum number of assemblies created by a single command."),

    // [watchdog]
    ip("watchdog.job_timeout_ms", 5000, "Durée au-delà de laquelle un job est signalé en dépassement. Le watchdog ne tue jamais de thread (R-1901).")
        .en("Time beyond which a job is reported as overrunning. The watchdog never kills a thread (R-1901)."),

    // [modules]
    b("modules.vehicles", true, "Active le module véhicules.")
        .en("Enables the vehicles module."),
    b("modules.joints", true, "Active le module joints et contraintes.")
        .en("Enables the joints and constraints module."),
    b("modules.damage", true, "Active le module dommage.")
        .en("Enables the damage module."),
    b("modules.deformation", true, "Active le module déformation.")
        .en("Enables the deformation module."),
    b("modules.structure", true, "Active le module intégrité structurelle.")
        .en("Enables the structural integrity module."),
    b("modules.repair", true, "Active le module réparation.")
        .en("Enables the repair module."),
    b("modules.wear", true, "Active le module usure de surface.")
        .en("Enables the surface wear module."),
    b("modules.particles", true, "Active le module particules.")
        .en("Enables the particles module."),
    b("modules.animation", true, "Active le module animation.")
        .en("Enables the animation module."),
    b("modules.attachments", true, "Active le module attaches entre assemblies.")
        .en("Enables the module for attachments between assemblies."),
    b("modules.blocks", true, "Active le module blocs et items 3D.")
        .en("Enables the 3D blocks and items module."),

    // [content]
    srv(b("content.examples", false, "Charge les cinq definitions de démonstration embarquées (R-1790), rangées sous `axion/definitions/example/` à la racine du JAR et enregistrées par AXION comme un addon (ADR-162)."))
        .en("Loads the five built-in demonstration definitions (R-1790), stored under `axion/definitions/example/` at the root of the JAR and registered by AXION like an addon (ADR-162)."),
    hot(b("content.dev_reload", false, "Autorise `/axion dev reload` (opérateur) : rappelle l'enregistrement de chaque mod, relit son dossier depuis le disque quand il tourne depuis ses sources, et renvoie le registre aux clients (ADR-162 D9). Réservé au développement."))
        .en("Allows `/axion dev reload` (operator): calls each mod's registration again, rereads its folder from disk when it runs from its sources, and sends the registry back to clients (ADR-162 D9). For development only."),
    // ADR-174 D1 (attaches : ADR-171) : un écouteur d'un mod qui lève de suite est désactivé (R-1770).
    i("content.listener_strikes", 20, 1, 1200, "Exceptions de suite d'un écouteur d'un mod (sièges, interactions, attaches) qui le désactivent pour la session.")
        .en("Consecutive exceptions of a mod's listener (seats, interactions, attachments) that disable it for the session."),
    // ADR-175 D5 : une definition rechargée relie les assemblies vivantes, quelques-unes par tick.
    i("content.rebind_per_tick", 4, 1, 256, "Assemblies vivantes reliées par tick à leur definition rechargée (ADR-175 D5) : le reste attend le tick suivant, et vit d'ici là sur la definition d'avant.")
        .en("Live assemblies rebound per tick to their reloaded definition (ADR-175 D5): the rest waits for the next tick, and lives on the previous definition until then."),

    // [integration.rustforgex]
    e("integration.rustforgex.mode", "auto", &["auto", "off"], "Détection d'un mod d'optimisation tiers. AXION n'en dépend jamais et fonctionne identiquement en son absence.")
        .en("Detection of a third-party optimisation mod. AXION never depends on it and works the same without it."),
    opt("integration.rustforgex.cpu_share", ConfigValue::Str("auto"), ConfigDomain::KeywordOrCount(&["auto", "half", "full"]), "Part de parallélisme qu'AXION s'autorise en présence d'un autre consommateur de CPU. Un entier fixe le nombre de threads.")
        .en("Share of parallelism AXION allows itself when another CPU consumer is present. An integer sets the number of threads."),
    b("integration.rustforgex.bridge", true, "Active le pont d'interopérabilité optionnel. Réflexif, isolé, désactivable ; aucune fonctionnalité d'AXION n'en dépend.")
        .en("Enables the optional interoperability bridge. Reflective, isolated, can be turned off; no AXION feature depends on it."),
    b("integration.rustforgex.hint_no_transform", true, "Signale aux outils tiers qu'AXION préfère ne pas voir son bytecode transformé. Une indication, jamais une exigence.")
        .en("Tells third-party tools that AXION prefers its bytecode not to be transformed. A hint, never a requirement."),

    // [debug]
    hot(b("debug.checksum_buffers", false, "Vérifie l'intégrité des tampons échangés avec le natif. Coûteux, réservé au diagnostic."))
        .en("Checks the integrity of the buffers exchanged with the native side. Costly, for diagnostics only."),
    hot(b("debug.record_incidents", false, "Enregistre de quoi rejouer un incident."))
        .en("Records what is needed to replay an incident."),
];

/// Options de `axion-client.toml` : rendu, qualité, debug, overlay.
pub const CLIENT: &[ConfigOption] = &[
    // [render]
    e(
        "render.backend",
        "auto",
        &["auto", "native", "vanilla"],
        "Backend de rendu. auto bascule sur vanilla en présence d'un shaderpack.",
    )
    .en("Rendering backend. auto switches to vanilla when a shaderpack is present."),
    fp(
        "render.max_distance",
        128.0,
        "Distance maximale de rendu des objets AXION, en blocs.",
    )
    .en("Maximum render distance of AXION objects, in blocks."),
    i(
        "render.lod_bias",
        0,
        -2,
        3,
        "Décalage appliqué au choix du niveau de détail. Négatif privilégie la qualité.",
    )
    .en("Offset applied to the choice of level of detail. Negative favours quality."),
    i(
        "render.anisotropy",
        16,
        1,
        16,
        "Filtrage anisotrope des textures d'AXION, borné par le maximum du GPU : garde nettes les surfaces vues de biais. 1 l'éteint (ADR-164).",
    )
    .en("Anisotropic filtering of AXION textures, capped by the GPU maximum: keeps surfaces seen at an angle sharp. 1 turns it off (ADR-164)."),
    ip(
        "render.instancing_threshold",
        4,
        "Nombre d'instances identiques à partir duquel l'instanciation GPU est employée.",
    )
    .en("Number of identical instances from which GPU instancing is used."),
    b(
        "render.indirect",
        true,
        "Autorise le rendu indirect. Utilisé seulement si GL 4.3 est disponible.",
    )
    .en("Allows indirect rendering. Used only if GL 4.3 is available."),
    b(
        "render.compute",
        true,
        "Autorise les shaders de calcul. Utilisé seulement si GL 4.3 est disponible.",
    )
    .en("Allows compute shaders. Used only if GL 4.3 is available."),
    ip(
        "render.max_skinned_instances",
        64,
        "Nombre maximal d'instances skinnées par frame ; au-delà, les plus lointaines se dessinent au repos (R-791).",
    )
    .en("Maximum number of skinned instances per frame; beyond it, the farthest are drawn at rest (R-791)."),
    ip(
        "render.max_palette_bones",
        128,
        "Nombre maximal d'os dans une palette de skinning.",
    )
    .en("Maximum number of bones in a skinning palette."),
    ip(
        "render.max_deform_pages_per_frame",
        256,
        "Nombre maximal de pages de déformation téléversées vers le GPU par frame.",
    )
    .en("Maximum number of deformation pages uploaded to the GPU per frame."),
    ip(
        "render.max_decals_per_assembly",
        64,
        "Nombre maximal de décalques portés par une assembly.",
    )
    .en("Maximum number of decals carried by an assembly."),
    ip(
        "render.max_morph_targets_per_asset",
        8,
        "Nombre maximal de cibles de morphing par asset.",
    )
    .en("Maximum number of morph targets per asset."),
    ip(
        "render.max_shader_variants",
        64,
        "Nombre maximal de variantes de shader compilées.",
    )
    .en("Maximum number of compiled shader variants."),
    ip(
        "render.vanilla_max_skinned_vertices",
        50000,
        "Plafond de sommets animés par frame dans le backend vanilla.",
    )
    .en("Cap on animated vertices per frame in the vanilla backend."),
    ip(
        "render.vanilla_max_deformed_vertices",
        100000,
        "Plafond de sommets déformés par frame dans le backend vanilla.",
    )
    .en("Cap on deformed vertices per frame in the vanilla backend."),
    ip(
        "render.vanilla_max_decals",
        64,
        "Plafond de décalques par frame dans le backend vanilla.",
    )
    .en("Cap on decals per frame in the vanilla backend."),
    b(
        "render.per_node_lightmap",
        false,
        "Échantillonne la lumière par nœud plutôt que par assembly. Plus fidèle, plus coûteux.",
    )
    .en("Samples light per node rather than per assembly. More faithful, more costly."),
    ip(
        "render.item_max_triangles",
        20000,
        "Nombre maximal de triangles pour le rendu 3D d'un item.",
    )
    .en("Maximum number of triangles for the 3D rendering of an item."),
    e(
        "render.shadow",
        "map",
        &["map", "contact", "none"],
        "Technique d'ombre propre aux objets AXION.",
    )
    .en("Shadow technique specific to AXION objects."),
    b(
        "render.shadow_ground_quad",
        true,
        "Ajoute une ombre de contact au sol sous les objets.",
    )
    .en("Adds a contact shadow on the ground under objects."),
    ip(
        "render.shadow_ns",
        1500000,
        "Budget du rendu des ombres, en nanosecondes par frame.",
    )
    .en("Shadow rendering budget, in nanoseconds per frame."),
    ip(
        "render.probe_interval_frames",
        20,
        "Intervalle, en frames, entre deux mises à jour de la sonde d'environnement.",
    )
    .en("Interval, in frames, between two updates of the environment probe."),
    fp(
        "render.ssr_max_roughness",
        0.25,
        "Rugosité maximale au-delà de laquelle les réflexions en espace écran sont abandonnées.",
    )
    .en("Maximum roughness beyond which screen-space reflections are abandoned."),
    ip(
        "render.max_occluders",
        512,
        "Nombre maximal d'occulteurs retenus pour l'occlusion culling logiciel.",
    )
    .en("Maximum number of occluders kept for software occlusion culling."),
    f(
        "render.backend_silhouette_tolerance",
        0.01,
        0.0,
        0.05,
        "Écart de silhouette toléré entre les deux backends de rendu (19.2bis).",
    )
    .en("Silhouette difference tolerated between the two rendering backends (19.2bis)."),
    // [quality]
    e(
        "quality.deformation",
        "auto",
        QUALITY,
        "Niveau de qualité de la déformation côté client.",
    )
    .en("Quality level of client-side deformation."),
    e(
        "quality.particles",
        "auto",
        QUALITY,
        "Niveau de qualité des particules.",
    )
    .en("Quality level of particles."),
    e(
        "quality.shadows",
        "auto",
        QUALITY,
        "Niveau de qualité des ombres.",
    )
    .en("Quality level of shadows."),
    e(
        "quality.decals",
        "auto",
        QUALITY,
        "Niveau de qualité des décalques.",
    )
    .en("Quality level of decals."),
    e(
        "quality.occlusion",
        "auto",
        QUALITY,
        "Niveau de qualité de l'occlusion culling.",
    )
    .en("Quality level of occlusion culling."),
    e(
        "quality.lighting",
        "auto",
        QUALITY,
        "Niveau de qualité de l'éclairage.",
    )
    .en("Quality level of lighting."),
    e(
        "quality.skinning",
        "auto",
        QUALITY,
        "Niveau de qualité du skinning.",
    )
    .en("Quality level of skinning."),
    e(
        "quality.reflections",
        "auto",
        QUALITY,
        "Niveau de qualité des réflexions.",
    )
    .en("Quality level of reflections."),
    e(
        "quality.parallax",
        "auto",
        QUALITY,
        "Niveau de qualité du parallax mapping.",
    )
    .en("Quality level of parallax mapping."),
    // [overlay]
    hot(b(
        "overlay.enabled",
        false,
        "Affiche l'overlay de diagnostic.",
    ))
    .en("Shows the diagnostic overlay."),
    hot(opt(
        "overlay.key",
        ConfigValue::Str("unbound"),
        ConfigDomain::FreeText,
        "Touche d'activation de l'overlay. unbound laisse l'overlay sans raccourci.",
    ))
    .en("Key that toggles the overlay. unbound leaves the overlay without a shortcut."),
    // [hud] — ADR-170 D4 : le tableau de bord du véhicule, au réglage du joueur.
    hot(b(
        "hud.dashboard",
        true,
        "Affiche le tableau de bord du véhicule conduit : le défaut et les jauges que sa definition déclare.",
    ))
    .en("Shows the dashboard of the driven vehicle: the default one and the gauges its definition declares."),
    hot(f(
        "hud.dashboard_scale",
        1.0,
        0.5,
        2.0,
        "Échelle du tableau de bord, par rapport à l'interface de Minecraft.",
    ))
    .en("Scale of the dashboard, relative to the Minecraft interface."),
    hot(e(
        "hud.speed_unit",
        "kmh",
        &["kmh", "mph"],
        "Unité de la vitesse affichée par le tableau de bord.",
    ))
    .en("Unit of the speed shown by the dashboard."),
    // ADR-170 D6 : comme le budget d'un contrôleur de véhicule (vehicle.controller_budget_ns), un widget d'addon a le
    // sien à chaque image ; vingt fautes de suite le désactivent pour la session. Mesuré le 2026-11-15 sur le
    // compte-tours de l'addon témoin (film des vues, 3 010 images) : p50 72 µs, p99 274 µs, pire 619 µs. 200 µs laisse
    // passer un widget de ce genre ; ses pointes isolées ne font pas vingt fautes de suite.
    hot(i(
        "hud.widget_budget_ns",
        200_000,
        10_000,
        5_000_000,
        "Temps qu'un widget du tableau de bord peut prendre à chaque image, en nanosecondes ; vingt dépassements ou exceptions de suite le désactivent pour la session.",
    ))
    .en("Time a dashboard widget may take each frame, in nanoseconds; twenty overruns or exceptions in a row disable it for the session."),
    // [debug]
    hot(b(
        "debug.gl",
        false,
        "Active la validation des appels OpenGL. Coûteux, réservé au diagnostic.",
    ))
    .en("Enables validation of OpenGL calls. Costly, for diagnostics only."),
];

/// Options de `axion-server.toml` : réseau, limites globales, autorisations.
pub const SERVER: &[ConfigOption] = &[
    // [server]
    b(
        "server.allow_client_prediction",
        true,
        "Autorise les clients à prédire le mouvement de l'assembly qu'ils pilotent.",
    )
    .en("Allows clients to predict the movement of the assembly they drive."),
    b(
        "server.kick_on_input_abuse",
        true,
        "Expulse un client dont les entrées sortent des bornes admises de façon répétée.",
    )
    .en("Kicks a client whose inputs repeatedly fall outside the accepted bounds."),
    b(
        "server.allow_experimental_authority",
        false,
        "Autorise le mode d'autorité SERVER_FULL pour les particules. EXPERIMENTAL.",
    )
    .en("Allows the SERVER_FULL authority mode for particles. EXPERIMENTAL."),
];
