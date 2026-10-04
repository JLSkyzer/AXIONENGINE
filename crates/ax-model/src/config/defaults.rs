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
    b("general.enabled", true, "Active AXION. À false, le mod se charge en mode DISABLED et les assemblies restent inertes."),
    b("general.maturity_allow_experimental", false, "Autorise l'activation des fonctionnalités marquées EXPERIMENTAL."),

    // [sim]
    srv(fs("sim.fixed_dt", 0.0166667, &[0.0333333, 0.0166667, 0.0083333], "Pas de temps fixe de la simulation, en secondes. Un pas intermédiaire désynchroniserait la simulation des ticks du serveur.")),
    srv(i("sim.max_substeps", 4, 1, 8, "Nombre maximal de sous-pas rattrapés en un tick lorsque le temps réel dépasse le pas fixe.")),
    f("sim.simulation_radius", 128.0, 16.0, 512.0, "Rayon de simulation autour d'un joueur, en blocs. Au-delà, les assemblies sont endormies."),

    // [physics]
    srv(f("physics.gravity", -9.81, -50.0, 0.0, "Accélération de la pesanteur, en m/s².")),
    srv(i("physics.velocity_iterations", 4, 2, 16, "Itérations de résolution des vitesses par pas. Plus élevé donne des contacts plus stables et coûte plus cher.")),
    srv(i("physics.position_iterations", 1, 0, 8, "Itérations de correction des positions par pas, contre l'interpénétration résiduelle.")),
    f("physics.broadphase_cell_size", 2.0, 0.5, 16.0, "Taille de cellule de la broad phase, en blocs."),
    f("physics.contact_event_threshold", 0.5, 0.0, 100.0, "Impulsion minimale, en N·s, pour qu'un contact produise un événement."),
    i("physics.max_events_per_tick", 4096, 256, 65536, "Plafond d'événements physiques traités par tick."),
    // R-614 : l'effet d'une collision sur une entité vanilla est configurable — amendement A1
    // de l'ANNEXE A.3 (38.9), ADR-123.
    srv(b("physics.entity_push", true, "Les assemblies poussent les entités vanilla qu'elles heurtent, joueurs exceptés.")),
    srv(b("physics.entity_damage", true, "Un choc contre une assembly blesse les entités vivantes, comme une chute de même vitesse.")),

    // [damage]
    b("damage.enabled", true, "Active la chaîne de dommage : impacts, déformation, rupture, usure."),
    i("damage.max_impacts_per_tick", 512, 32, 8192, "Plafond d'impacts résolus par tick. Le surplus est mis en file, jamais perdu."),
    f("damage.min_impact_energy", 5.0, 0.0, 1000.0, "Énergie minimale d'un impact, en joules, en deçà de laquelle il est ignoré."),
    f("damage.max_impact_energy", 5000000.0, 1000.0, 1000000000.0, "Énergie maximale retenue pour un impact, en joules. Écrête les valeurs aberrantes."),
    i("damage.max_propagation_depth", 4, 0, 8, "Profondeur maximale de propagation de l'énergie dans le graphe structurel."),
    i("damage.max_assemblies_per_tick", 64, 4, 1024, "Nombre maximal d'assemblies traitées par la chaîne de dommage en un tick."),
    ip("damage.pending_queue_max", 4096, "Taille maximale de la file d'impacts en attente."),
    i("damage.max_detach_per_tick", 4, 0, 64, "Nombre maximal de détachements de pièces par tick."),
    i("damage.max_debris", 96, 0, 512, "Nombre maximal de débris simultanés."),
    i("damage.max_debris_generation", 2, 0, 4, "Nombre maximal de générations successives de débris, pour borner la fragmentation en cascade."),
    i("damage.debris_lifetime_s", 90, 5, 1200, "Durée de vie d'un débris, en secondes."),
    ip("damage.debris_sleep_s", 5, "Délai d'immobilité, en secondes, au bout duquel un débris est endormi."),
    b("damage.persist_debris", false, "Sauvegarde les débris avec le monde."),
    fp("damage.explosion_sample_area", 0.5, "Aire représentée par un point d'échantillonnage d'explosion, en m²."),
    f("damage.default_sharp_factor", 8.0, 1.0, 64.0, "Facteur de concentration d'un impact tranchant : il réduit le rayon efficace, donc augmente la pénétration (13.3bis)."),
    f("damage.default_blunt_factor", 2.0, 1.0, 16.0, "Facteur d'étalement d'un impact contondant : il augmente le rayon efficace."),
    f("damage.default_shear_ratio", 0.6, 0.0, 1.0, "Rapport entre résistance au cisaillement et résistance en traction, quand le matériau ne la déclare pas."),

    // [struct]
    f("struct.max_gap", 0.05, 0.0, 0.5, "Écart maximal, en mètres, entre deux pièces pour qu'une liaison structurelle soit générée automatiquement."),
    f("struct.max_link_area", 4.0, 0.01, 64.0, "Aire maximale d'une liaison structurelle, en m²."),
    b("struct.auto_links", true, "Génère automatiquement les liaisons structurelles entre pièces voisines."),

    // [deformation]
    b("deformation.enabled", true, "Active la déformation continue de géométrie."),
    e("deformation.quality", "auto", QUALITY, "Niveau de qualité de la déformation. auto laisse le gouverneur décider."),
    ip("deformation.max_field_bytes_per_assembly", 262144, "Taille maximale du champ de déformation d'une assembly, en octets."),
    ip("deformation.max_residual_vertices", 4096, "Nombre maximal de sommets portant un déplacement résiduel hors lattice."),
    f("deformation.collider_refit_threshold", 0.04, 0.005, 0.25, "Écart, en mètres, entre géométrie visuelle et géométrie de collision au-delà duquel le collider est réajusté."),
    fp("deformation.mass_update_threshold", 0.05, "Variation relative de masse à partir de laquelle les propriétés inertielles sont recalculées."),
    fp("deformation.elastic_release_s", 5.0, "Temps de retour à zéro d'une déformation élastique, en secondes."),
    b("deformation.tear_enabled", false, "Active la déchirure du maillage. EXPERIMENTAL."),
    fp("deformation.tear_max_cells_per_region", 0.08, "Fraction maximale des cellules d'une région pouvant être déchirées."),

    // [particles]
    b("particles.enabled", true, "Active le solveur de particules : tissu, cordes, câbles, filets, corps souples."),
    e("particles.quality", "auto", QUALITY, "Niveau de qualité du solveur de particules."),
    ip("particles.max_sets", 48, "Nombre maximal d'ensembles de particules simulés côté client."),
    ip("particles.max_sets_server", 16, "Nombre maximal d'ensembles de particules simulés côté serveur."),
    b("particles.self_collide", false, "Active l'auto-collision des particules. EXPERIMENTAL."),
    b("particles.tear", false, "Active la déchirure des ensembles de particules. EXPERIMENTAL."),
    f("particles.visual_drift_max", 0.25, 0.02, 2.0, "Dérive visuelle maximale tolérée, en mètres, entre la simulation client et l'état serveur (17.2bis)."),
    i("particles.visual_freeze_ms", 500, 100, 5000, "Durée pendant laquelle l'affichage est gelé faute d'état serveur, en millisecondes."),

    // [budgets]
    ip("budgets.sim_ns_per_tick", 3000000, "Budget de la simulation physique, en nanosecondes par tick."),
    ip("budgets.damage_ns_per_tick", 1000000, "Budget de la chaîne de dommage, en nanosecondes par tick."),
    ip("budgets.deformation_ns_per_tick", 1500000, "Budget de la déformation, en nanosecondes par tick."),
    ip("budgets.particles_ns_per_tick", 1000000, "Budget du solveur de particules, en nanosecondes par tick."),
    ip("budgets.render_prep_ns", 2000000, "Budget de préparation du rendu, en nanosecondes par frame."),
    ip("budgets.occlusion_ns", 800000, "Budget de l'occlusion culling logiciel, en nanosecondes par frame."),
    ip("budgets.asset_ns_per_tick", 1000000, "Budget du chargement et de la compilation d'assets, en nanosecondes par tick."),
    ip("budgets.idle_hook_ns", 50000, "Surcoût maximal des hooks Forge par tick lorsqu'aucune assembly n'est présente, en nanosecondes."),
    ip("budgets.submit_ns", 200000, "Budget de soumission des commandes de rendu, en nanosecondes par frame."),
    ip("budgets.native_mem_bytes", 536870912, "Plafond de mémoire native, en octets."),
    ip("budgets.deform_mem_bytes", 134217728, "Plafond de mémoire des champs de déformation, en octets."),
    ip("budgets.gpu_mem_bytes", 536870912, "Plafond de mémoire GPU gérée par AXION, en octets."),
    ip("budgets.max_active_bodies", 2048, "Nombre maximal de corps physiques actifs simultanément."),
    ip("budgets.max_deformed_assemblies", 256, "Nombre maximal d'assemblies portant un champ de déformation."),
    ip("budgets.max_collider_refits_per_tick", 8, "Nombre maximal de réajustements de collider par tick."),
    ip("budgets.max_visible_instances", 512, "Nombre maximal d'instances rendues par frame."),
    ip("budgets.max_triangles_frame", 3000000, "Nombre maximal de triangles rendus par frame."),
    ip("budgets.max_decals_frame", 512, "Nombre maximal de décalques rendus par frame."),
    ip("budgets.max_shadow_instances", 128, "Nombre maximal d'instances projetant une ombre AXION."),

    // [jobs]
    ip("jobs.max_workers", 0, "Nombre de threads du pool de jobs. 0 laisse AXION le déduire du nombre de cœurs disponibles."),

    // [assets]
    ip("assets.max_source_bytes", 134217728, "Taille maximale d'un fichier source d'asset accepté, en octets."),
    ip("assets.max_compiled_bytes", 268435456, "Taille maximale d'un asset compilé, en octets."),
    ip("assets.max_deform_bytes", 4194304, "Taille maximale des données de déformation compilées d'un asset, en octets."),
    ip("assets.max_compile_ms", 30000, "Durée maximale de compilation d'un asset, en millisecondes."),
    ip("assets.cache_max_bytes", 2147483648, "Taille maximale du cache d'assets compilés, en octets."),
    ip("assets.startup_timeout_s", 120, "Délai maximal de préparation des assets au démarrage, en secondes."),
    ip("assets.unload_delay_s", 60, "Délai avant déchargement d'un asset inutilisé, en secondes."),

    // [world]
    i("world.tiles_per_tick", 8, 1, 64, "Nombre de tuiles de collision monde extraites par tick."),
    i("world.tile_radius", 3, 1, 8, "Rayon, en tuiles, de la géométrie de collision monde maintenue autour d'une assembly."),

    // [vehicle]
    fp("vehicle.debris_ignore_s", 1.0, "Durée pendant laquelle un véhicule ignore les collisions avec ses propres débris, en secondes."),
    fp("vehicle.max_suspension_misalignment", 0.15, "Désalignement maximal toléré d'une suspension déformée, en mètres, avant signalement."),

    // [attachment]
    ip("attachment.max_chain", 8, "Longueur maximale d'une chaîne d'assemblies attachées."),

    // [net]
    i("net.snapshot_rate_hz", 10, 2, 20, "Fréquence d'émission des snapshots d'état, en hertz."),
    fp("net.sync_radius", 96.0, "Rayon de synchronisation autour d'un joueur, en blocs."),
    ip("net.interpolation_delay_ms", 100, "Retard d'interpolation appliqué côté client, en millisecondes."),
    ip("net.extrapolation_max_ms", 250, "Durée maximale d'extrapolation en l'absence de snapshot, en millisecondes."),
    fp("net.snap_threshold", 2.0, "Écart, en mètres, au-delà duquel le client repositionne brutalement plutôt qu'interpoler."),
    b("net.prediction", true, "Active la prédiction côté client pour l'assembly pilotée par le joueur."),
    ip("net.prediction_max_ping", 400, "Latence maximale, en millisecondes, au-delà de laquelle la prédiction est désactivée."),
    ip("net.max_bytes_per_second_per_player", 32768, "Plafond de bande passante AXION par joueur, en octets par seconde."),
    ip("net.max_packets_per_second", 40, "Plafond de paquets AXION par seconde et par joueur."),
    ip("net.max_impacts_per_packet", 64, "Nombre maximal d'impacts groupés dans un paquet."),
    ip("net.digest_interval_ticks", 40, "Intervalle, en ticks, entre deux empreintes de vérification d'état."),
    ip("net.max_deform_snapshots_per_second", 4, "Plafond de snapshots de déformation par seconde en réplication par événements."),
    ip("net.max_deform_snapshots_per_second_fallback", 16, "Plafond de snapshots de déformation par seconde en mode SNAPSHOT, quand la reconstruction déterministe n'est pas garantie (5.12bis)."),
    i("net.divergence_tolerance", 3, 1, 32, "Nombre de divergences d'empreinte tolérées avant bascule de RECONSTRUCT vers SNAPSHOT."),

    // [persistence]
    ip("persistence.max_bytes_per_assembly", 65536, "Taille maximale de l'état persisté d'une assembly, en octets."),
    ip("persistence.max_custom_bytes", 16384, "Taille maximale des données personnalisées persistées par une assembly, en octets."),
    ip("persistence.max_persisted_decals", 16, "Nombre maximal de décalques sauvegardés par assembly."),
    ip("persistence.max_load_ns_per_tick", 500000, "Budget de chargement d'état persisté, en nanosecondes par tick."),
    b("persistence.journal_enabled", true, "Active le journal latéral de récupération (22.5.2)."),
    i("persistence.journal_ns_per_tick", 200000, 0, 2000000, "Budget d'écriture du journal, en nanosecondes par tick."),
    ip("persistence.journal_max_bytes", 268435456, "Taille maximale du journal, en octets."),

    // [limits]
    ip("limits.max_assemblies_per_dimension", 4096, "Nombre maximal d'assemblies par dimension."),
    ip("limits.max_spawn_per_command", 64, "Nombre maximal d'assemblies créées par une seule commande."),

    // [watchdog]
    ip("watchdog.job_timeout_ms", 5000, "Durée au-delà de laquelle un job est signalé en dépassement. Le watchdog ne tue jamais de thread (R-1901)."),

    // [modules]
    b("modules.vehicles", true, "Active le module véhicules."),
    b("modules.joints", true, "Active le module joints et contraintes."),
    b("modules.damage", true, "Active le module dommage."),
    b("modules.deformation", true, "Active le module déformation."),
    b("modules.structure", true, "Active le module intégrité structurelle."),
    b("modules.repair", true, "Active le module réparation."),
    b("modules.wear", true, "Active le module usure de surface."),
    b("modules.particles", true, "Active le module particules."),
    b("modules.animation", true, "Active le module animation."),
    b("modules.attachments", true, "Active le module attaches entre assemblies."),
    b("modules.blocks", true, "Active le module blocs et items 3D."),

    // [integration.rustforgex]
    e("integration.rustforgex.mode", "auto", &["auto", "off"], "Détection d'un mod d'optimisation tiers. AXION n'en dépend jamais et fonctionne identiquement en son absence."),
    opt("integration.rustforgex.cpu_share", ConfigValue::Str("auto"), ConfigDomain::KeywordOrCount(&["auto", "half", "full"]), "Part de parallélisme qu'AXION s'autorise en présence d'un autre consommateur de CPU. Un entier fixe le nombre de threads."),
    b("integration.rustforgex.bridge", true, "Active le pont d'interopérabilité optionnel. Réflexif, isolé, désactivable ; aucune fonctionnalité d'AXION n'en dépend."),
    b("integration.rustforgex.hint_no_transform", true, "Signale aux outils tiers qu'AXION préfère ne pas voir son bytecode transformé. Une indication, jamais une exigence."),

    // [debug]
    hot(b("debug.checksum_buffers", false, "Vérifie l'intégrité des tampons échangés avec le natif. Coûteux, réservé au diagnostic.")),
    hot(b("debug.record_incidents", false, "Enregistre de quoi rejouer un incident.")),
];

/// Options de `axion-client.toml` : rendu, qualité, debug, overlay.
pub const CLIENT: &[ConfigOption] = &[
    // [render]
    e(
        "render.backend",
        "auto",
        &["auto", "native", "vanilla"],
        "Backend de rendu. auto bascule sur vanilla en présence d'un shaderpack.",
    ),
    fp(
        "render.max_distance",
        128.0,
        "Distance maximale de rendu des objets AXION, en blocs.",
    ),
    i(
        "render.lod_bias",
        0,
        -2,
        3,
        "Décalage appliqué au choix du niveau de détail. Négatif privilégie la qualité.",
    ),
    ip(
        "render.instancing_threshold",
        4,
        "Nombre d'instances identiques à partir duquel l'instanciation GPU est employée.",
    ),
    b(
        "render.indirect",
        true,
        "Autorise le rendu indirect. Utilisé seulement si GL 4.3 est disponible.",
    ),
    b(
        "render.compute",
        true,
        "Autorise les shaders de calcul. Utilisé seulement si GL 4.3 est disponible.",
    ),
    ip(
        "render.max_skinned_instances",
        64,
        "Nombre maximal d'instances animées par squelette et par frame.",
    ),
    ip(
        "render.max_palette_bones",
        128,
        "Nombre maximal d'os dans une palette de skinning.",
    ),
    ip(
        "render.max_deform_pages_per_frame",
        256,
        "Nombre maximal de pages de déformation téléversées vers le GPU par frame.",
    ),
    ip(
        "render.max_decals_per_assembly",
        64,
        "Nombre maximal de décalques portés par une assembly.",
    ),
    ip(
        "render.max_morph_targets_per_asset",
        8,
        "Nombre maximal de cibles de morphing par asset.",
    ),
    ip(
        "render.max_shader_variants",
        64,
        "Nombre maximal de variantes de shader compilées.",
    ),
    ip(
        "render.vanilla_max_skinned_vertices",
        50000,
        "Plafond de sommets animés par frame dans le backend vanilla.",
    ),
    ip(
        "render.vanilla_max_deformed_vertices",
        100000,
        "Plafond de sommets déformés par frame dans le backend vanilla.",
    ),
    ip(
        "render.vanilla_max_decals",
        64,
        "Plafond de décalques par frame dans le backend vanilla.",
    ),
    b(
        "render.per_node_lightmap",
        false,
        "Échantillonne la lumière par nœud plutôt que par assembly. Plus fidèle, plus coûteux.",
    ),
    ip(
        "render.item_max_triangles",
        20000,
        "Nombre maximal de triangles pour le rendu 3D d'un item.",
    ),
    e(
        "render.shadow",
        "map",
        &["map", "contact", "none"],
        "Technique d'ombre propre aux objets AXION.",
    ),
    b(
        "render.shadow_ground_quad",
        true,
        "Ajoute une ombre de contact au sol sous les objets.",
    ),
    ip(
        "render.shadow_ns",
        1500000,
        "Budget du rendu des ombres, en nanosecondes par frame.",
    ),
    ip(
        "render.probe_interval_frames",
        20,
        "Intervalle, en frames, entre deux mises à jour de la sonde d'environnement.",
    ),
    fp(
        "render.ssr_max_roughness",
        0.25,
        "Rugosité maximale au-delà de laquelle les réflexions en espace écran sont abandonnées.",
    ),
    ip(
        "render.max_occluders",
        512,
        "Nombre maximal d'occulteurs retenus pour l'occlusion culling logiciel.",
    ),
    f(
        "render.backend_silhouette_tolerance",
        0.01,
        0.0,
        0.05,
        "Écart de silhouette toléré entre les deux backends de rendu (19.2bis).",
    ),
    // [quality]
    e(
        "quality.deformation",
        "auto",
        QUALITY,
        "Niveau de qualité de la déformation côté client.",
    ),
    e(
        "quality.particles",
        "auto",
        QUALITY,
        "Niveau de qualité des particules.",
    ),
    e(
        "quality.shadows",
        "auto",
        QUALITY,
        "Niveau de qualité des ombres.",
    ),
    e(
        "quality.decals",
        "auto",
        QUALITY,
        "Niveau de qualité des décalques.",
    ),
    e(
        "quality.occlusion",
        "auto",
        QUALITY,
        "Niveau de qualité de l'occlusion culling.",
    ),
    e(
        "quality.lighting",
        "auto",
        QUALITY,
        "Niveau de qualité de l'éclairage.",
    ),
    e(
        "quality.skinning",
        "auto",
        QUALITY,
        "Niveau de qualité du skinning.",
    ),
    e(
        "quality.reflections",
        "auto",
        QUALITY,
        "Niveau de qualité des réflexions.",
    ),
    e(
        "quality.parallax",
        "auto",
        QUALITY,
        "Niveau de qualité du parallax mapping.",
    ),
    // [overlay]
    hot(b(
        "overlay.enabled",
        false,
        "Affiche l'overlay de diagnostic.",
    )),
    hot(opt(
        "overlay.key",
        ConfigValue::Str("unbound"),
        ConfigDomain::FreeText,
        "Touche d'activation de l'overlay. unbound laisse l'overlay sans raccourci.",
    )),
    // [debug]
    hot(b(
        "debug.gl",
        false,
        "Active la validation des appels OpenGL. Coûteux, réservé au diagnostic.",
    )),
];

/// Options de `axion-server.toml` : réseau, limites globales, autorisations.
pub const SERVER: &[ConfigOption] = &[
    // [server]
    b(
        "server.allow_client_prediction",
        true,
        "Autorise les clients à prédire le mouvement de l'assembly qu'ils pilotent.",
    ),
    b(
        "server.kick_on_input_abuse",
        true,
        "Expulse un client dont les entrées sortent des bornes admises de façon répétée.",
    ),
    b(
        "server.allow_experimental_authority",
        false,
        "Autorise le mode d'autorité SERVER_FULL pour les particules. EXPERIMENTAL.",
    ),
];
