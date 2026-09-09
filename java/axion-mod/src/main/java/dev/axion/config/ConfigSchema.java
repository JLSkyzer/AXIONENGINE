package dev.axion.config;

import java.util.List;

/**
 * Schéma de la configuration d'AXION ENGINE.
 *
 * <p><strong>Fichier généré — ne pas modifier à la main.</strong> Les options sont
 * déclarées une seule fois, dans {@code crates/ax-model/src/config/defaults.rs}.
 * Régénérer avec :
 *
 * <pre>cargo run -p axion-codegen --bin gen_java_config</pre>
 *
 * <p>Le test de parité T-005 échoue si ce fichier diverge du registre.
 *
 * <p>Exigences : R-430, R-431, R-1830.
 */
public final class ConfigSchema {

    private ConfigSchema() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /** Fichier de configuration auquel une option appartient. */
    public enum Scope {
        /** {@code axion-common.toml}. */
        COMMON("axion-common.toml"),
        /** {@code axion-client.toml}. */
        CLIENT("axion-client.toml"),
        /** {@code axion-server.toml}. */
        SERVER("axion-server.toml");

        private final String fileName;

        Scope(String fileName) {
            this.fileName = fileName;
        }

        /** {@return le nom du fichier correspondant} */
        public String fileName() {
            return fileName;
        }

        /** {@return les options déclarées pour cette portée} */
        public List<Option> options() {
            return switch (this) {
                case COMMON -> COMMON_OPTIONS;
                case CLIENT -> CLIENT_OPTIONS;
                case SERVER -> SERVER_OPTIONS;
            };
        }
    }

    /** Type d'une valeur de configuration. */
    public enum Kind {
        /** Booléen. */
        BOOLEAN,
        /** Entier signé. */
        INTEGER,
        /** Flottant double précision. */
        FLOAT,
        /** Chaîne. */
        STRING
    }

    /** Nature du domaine de validité d'une option. */
    public enum DomainKind {
        /** Les deux valeurs booléennes. */
        BOOLEAN,
        /** Intervalle entier fermé. */
        INT_RANGE,
        /** Intervalle flottant fermé. */
        FLOAT_RANGE,
        /** Ensemble fermé de flottants. */
        FLOAT_SET,
        /** Ensemble fermé de chaînes. */
        ENUMERATION,
        /** Un mot-clé de la liste, ou un entier positif. */
        KEYWORD_OR_COUNT,
        /** Chaîne libre. */
        FREE_TEXT
    }

    /**
     * Domaine de validité d'une option.
     *
     * @param kind nature du domaine
     * @param min borne inférieure, pour les intervalles
     * @param max borne supérieure, pour les intervalles
     * @param allowed valeurs admises, pour les ensembles fermés
     */
    public record Domain(DomainKind kind, double min, double max, List<String> allowed) {}

    /**
     * Une option de configuration.
     *
     * @param path chemin pointé, tel qu'il apparaît en TOML
     * @param kind type de la valeur
     * @param defaultValue valeur par défaut
     * @param domain domaine de validité
     * @param hot rechargeable par {@code /axion config reload} (R-431)
     * @param serverAuthoritative imposée par le serveur au handshake (R-1830)
     * @param description description reprise dans la documentation générée
     */
    public record Option(
            String path,
            Kind kind,
            Object defaultValue,
            Domain domain,
            boolean hot,
            boolean serverAuthoritative,
            String description) {

        /** {@return la propriété système qui surcharge cette option} */
        public String systemProperty() {
            return "axion." + path;
        }

        /** {@return la section TOML de l'option} */
        public String section() {
            return path.substring(0, path.lastIndexOf('.'));
        }

        /** {@return le nom de l'option dans sa section} */
        public String key() {
            return path.substring(path.lastIndexOf('.') + 1);
        }
    }

    /** Options de {@code axion-common.toml}. */
    public static final List<Option> COMMON_OPTIONS = List.of(
            new Option(
                    "general.enabled",
                    Kind.BOOLEAN,
                    true,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Active AXION. À false, le mod se charge en mode DISABLED et les assemblies restent inertes."),
            new Option(
                    "general.maturity_allow_experimental",
                    Kind.BOOLEAN,
                    false,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Autorise l'activation des fonctionnalités marquées EXPERIMENTAL."),
            new Option(
                    "sim.fixed_dt",
                    Kind.FLOAT,
                    0.0166667,
                    new Domain(DomainKind.FLOAT_SET, 0.0, 0.0, List.of("0.0333333", "0.0166667", "0.0083333")),
                    false,
                    true,
                    "Pas de temps fixe de la simulation, en secondes. Un pas intermédiaire désynchroniserait la simulation des ticks du serveur."),
            new Option(
                    "sim.max_substeps",
                    Kind.INTEGER,
                    4L,
                    new Domain(DomainKind.INT_RANGE, 1.0, 8.0, List.of()),
                    false,
                    true,
                    "Nombre maximal de sous-pas rattrapés en un tick lorsque le temps réel dépasse le pas fixe."),
            new Option(
                    "sim.simulation_radius",
                    Kind.FLOAT,
                    128.0,
                    new Domain(DomainKind.FLOAT_RANGE, 16.0, 512.0, List.of()),
                    false,
                    false,
                    "Rayon de simulation autour d'un joueur, en blocs. Au-delà, les assemblies sont endormies."),
            new Option(
                    "physics.gravity",
                    Kind.FLOAT,
                    -9.81,
                    new Domain(DomainKind.FLOAT_RANGE, -50.0, 0.0, List.of()),
                    false,
                    true,
                    "Accélération de la pesanteur, en m/s²."),
            new Option(
                    "physics.velocity_iterations",
                    Kind.INTEGER,
                    4L,
                    new Domain(DomainKind.INT_RANGE, 2.0, 16.0, List.of()),
                    false,
                    true,
                    "Itérations de résolution des vitesses par pas. Plus élevé donne des contacts plus stables et coûte plus cher."),
            new Option(
                    "physics.position_iterations",
                    Kind.INTEGER,
                    1L,
                    new Domain(DomainKind.INT_RANGE, 0.0, 8.0, List.of()),
                    false,
                    true,
                    "Itérations de correction des positions par pas, contre l'interpénétration résiduelle."),
            new Option(
                    "physics.broadphase_cell_size",
                    Kind.FLOAT,
                    2.0,
                    new Domain(DomainKind.FLOAT_RANGE, 0.5, 16.0, List.of()),
                    false,
                    false,
                    "Taille de cellule de la broad phase, en blocs."),
            new Option(
                    "physics.contact_event_threshold",
                    Kind.FLOAT,
                    0.5,
                    new Domain(DomainKind.FLOAT_RANGE, 0.0, 100.0, List.of()),
                    false,
                    false,
                    "Impulsion minimale, en N·s, pour qu'un contact produise un événement."),
            new Option(
                    "physics.max_events_per_tick",
                    Kind.INTEGER,
                    4096L,
                    new Domain(DomainKind.INT_RANGE, 256.0, 65536.0, List.of()),
                    false,
                    false,
                    "Plafond d'événements physiques traités par tick."),
            new Option(
                    "damage.enabled",
                    Kind.BOOLEAN,
                    true,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Active la chaîne de dommage : impacts, déformation, rupture, usure."),
            new Option(
                    "damage.max_impacts_per_tick",
                    Kind.INTEGER,
                    512L,
                    new Domain(DomainKind.INT_RANGE, 32.0, 8192.0, List.of()),
                    false,
                    false,
                    "Plafond d'impacts résolus par tick. Le surplus est mis en file, jamais perdu."),
            new Option(
                    "damage.min_impact_energy",
                    Kind.FLOAT,
                    5.0,
                    new Domain(DomainKind.FLOAT_RANGE, 0.0, 1000.0, List.of()),
                    false,
                    false,
                    "Énergie minimale d'un impact, en joules, en deçà de laquelle il est ignoré."),
            new Option(
                    "damage.max_impact_energy",
                    Kind.FLOAT,
                    5000000.0,
                    new Domain(DomainKind.FLOAT_RANGE, 1000.0, 1000000000.0, List.of()),
                    false,
                    false,
                    "Énergie maximale retenue pour un impact, en joules. Écrête les valeurs aberrantes."),
            new Option(
                    "damage.max_propagation_depth",
                    Kind.INTEGER,
                    4L,
                    new Domain(DomainKind.INT_RANGE, 0.0, 8.0, List.of()),
                    false,
                    false,
                    "Profondeur maximale de propagation de l'énergie dans le graphe structurel."),
            new Option(
                    "damage.max_assemblies_per_tick",
                    Kind.INTEGER,
                    64L,
                    new Domain(DomainKind.INT_RANGE, 4.0, 1024.0, List.of()),
                    false,
                    false,
                    "Nombre maximal d'assemblies traitées par la chaîne de dommage en un tick."),
            new Option(
                    "damage.pending_queue_max",
                    Kind.INTEGER,
                    4096L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Taille maximale de la file d'impacts en attente."),
            new Option(
                    "damage.max_detach_per_tick",
                    Kind.INTEGER,
                    4L,
                    new Domain(DomainKind.INT_RANGE, 0.0, 64.0, List.of()),
                    false,
                    false,
                    "Nombre maximal de détachements de pièces par tick."),
            new Option(
                    "damage.max_debris",
                    Kind.INTEGER,
                    96L,
                    new Domain(DomainKind.INT_RANGE, 0.0, 512.0, List.of()),
                    false,
                    false,
                    "Nombre maximal de débris simultanés."),
            new Option(
                    "damage.max_debris_generation",
                    Kind.INTEGER,
                    2L,
                    new Domain(DomainKind.INT_RANGE, 0.0, 4.0, List.of()),
                    false,
                    false,
                    "Nombre maximal de générations successives de débris, pour borner la fragmentation en cascade."),
            new Option(
                    "damage.debris_lifetime_s",
                    Kind.INTEGER,
                    90L,
                    new Domain(DomainKind.INT_RANGE, 5.0, 1200.0, List.of()),
                    false,
                    false,
                    "Durée de vie d'un débris, en secondes."),
            new Option(
                    "damage.debris_sleep_s",
                    Kind.INTEGER,
                    5L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Délai d'immobilité, en secondes, au bout duquel un débris est endormi."),
            new Option(
                    "damage.persist_debris",
                    Kind.BOOLEAN,
                    false,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Sauvegarde les débris avec le monde."),
            new Option(
                    "damage.explosion_sample_area",
                    Kind.FLOAT,
                    0.5,
                    new Domain(DomainKind.FLOAT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Aire représentée par un point d'échantillonnage d'explosion, en m²."),
            new Option(
                    "damage.default_sharp_factor",
                    Kind.FLOAT,
                    8.0,
                    new Domain(DomainKind.FLOAT_RANGE, 1.0, 64.0, List.of()),
                    false,
                    false,
                    "Facteur de concentration d'un impact tranchant : il réduit le rayon efficace, donc augmente la pénétration (13.3bis)."),
            new Option(
                    "damage.default_blunt_factor",
                    Kind.FLOAT,
                    2.0,
                    new Domain(DomainKind.FLOAT_RANGE, 1.0, 16.0, List.of()),
                    false,
                    false,
                    "Facteur d'étalement d'un impact contondant : il augmente le rayon efficace."),
            new Option(
                    "damage.default_shear_ratio",
                    Kind.FLOAT,
                    0.6,
                    new Domain(DomainKind.FLOAT_RANGE, 0.0, 1.0, List.of()),
                    false,
                    false,
                    "Rapport entre résistance au cisaillement et résistance en traction, quand le matériau ne la déclare pas."),
            new Option(
                    "struct.max_gap",
                    Kind.FLOAT,
                    0.05,
                    new Domain(DomainKind.FLOAT_RANGE, 0.0, 0.5, List.of()),
                    false,
                    false,
                    "Écart maximal, en mètres, entre deux pièces pour qu'une liaison structurelle soit générée automatiquement."),
            new Option(
                    "struct.max_link_area",
                    Kind.FLOAT,
                    4.0,
                    new Domain(DomainKind.FLOAT_RANGE, 0.01, 64.0, List.of()),
                    false,
                    false,
                    "Aire maximale d'une liaison structurelle, en m²."),
            new Option(
                    "struct.auto_links",
                    Kind.BOOLEAN,
                    true,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Génère automatiquement les liaisons structurelles entre pièces voisines."),
            new Option(
                    "deformation.enabled",
                    Kind.BOOLEAN,
                    true,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Active la déformation continue de géométrie."),
            new Option(
                    "deformation.quality",
                    Kind.STRING,
                    "auto",
                    new Domain(DomainKind.ENUMERATION, 0.0, 0.0, List.of("auto", "off", "low", "medium", "high", "ultra")),
                    false,
                    false,
                    "Niveau de qualité de la déformation. auto laisse le gouverneur décider."),
            new Option(
                    "deformation.max_field_bytes_per_assembly",
                    Kind.INTEGER,
                    262144L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Taille maximale du champ de déformation d'une assembly, en octets."),
            new Option(
                    "deformation.max_residual_vertices",
                    Kind.INTEGER,
                    4096L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Nombre maximal de sommets portant un déplacement résiduel hors lattice."),
            new Option(
                    "deformation.collider_refit_threshold",
                    Kind.FLOAT,
                    0.04,
                    new Domain(DomainKind.FLOAT_RANGE, 0.005, 0.25, List.of()),
                    false,
                    false,
                    "Écart, en mètres, entre géométrie visuelle et géométrie de collision au-delà duquel le collider est réajusté."),
            new Option(
                    "deformation.mass_update_threshold",
                    Kind.FLOAT,
                    0.05,
                    new Domain(DomainKind.FLOAT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Variation relative de masse à partir de laquelle les propriétés inertielles sont recalculées."),
            new Option(
                    "deformation.elastic_release_s",
                    Kind.FLOAT,
                    5.0,
                    new Domain(DomainKind.FLOAT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Temps de retour à zéro d'une déformation élastique, en secondes."),
            new Option(
                    "deformation.tear_enabled",
                    Kind.BOOLEAN,
                    false,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Active la déchirure du maillage. EXPERIMENTAL."),
            new Option(
                    "deformation.tear_max_cells_per_region",
                    Kind.FLOAT,
                    0.08,
                    new Domain(DomainKind.FLOAT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Fraction maximale des cellules d'une région pouvant être déchirées."),
            new Option(
                    "particles.enabled",
                    Kind.BOOLEAN,
                    true,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Active le solveur de particules : tissu, cordes, câbles, filets, corps souples."),
            new Option(
                    "particles.quality",
                    Kind.STRING,
                    "auto",
                    new Domain(DomainKind.ENUMERATION, 0.0, 0.0, List.of("auto", "off", "low", "medium", "high", "ultra")),
                    false,
                    false,
                    "Niveau de qualité du solveur de particules."),
            new Option(
                    "particles.max_sets",
                    Kind.INTEGER,
                    48L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Nombre maximal d'ensembles de particules simulés côté client."),
            new Option(
                    "particles.max_sets_server",
                    Kind.INTEGER,
                    16L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Nombre maximal d'ensembles de particules simulés côté serveur."),
            new Option(
                    "particles.self_collide",
                    Kind.BOOLEAN,
                    false,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Active l'auto-collision des particules. EXPERIMENTAL."),
            new Option(
                    "particles.tear",
                    Kind.BOOLEAN,
                    false,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Active la déchirure des ensembles de particules. EXPERIMENTAL."),
            new Option(
                    "particles.visual_drift_max",
                    Kind.FLOAT,
                    0.25,
                    new Domain(DomainKind.FLOAT_RANGE, 0.02, 2.0, List.of()),
                    false,
                    false,
                    "Dérive visuelle maximale tolérée, en mètres, entre la simulation client et l'état serveur (17.2bis)."),
            new Option(
                    "particles.visual_freeze_ms",
                    Kind.INTEGER,
                    500L,
                    new Domain(DomainKind.INT_RANGE, 100.0, 5000.0, List.of()),
                    false,
                    false,
                    "Durée pendant laquelle l'affichage est gelé faute d'état serveur, en millisecondes."),
            new Option(
                    "budgets.sim_ns_per_tick",
                    Kind.INTEGER,
                    3000000L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Budget de la simulation physique, en nanosecondes par tick."),
            new Option(
                    "budgets.damage_ns_per_tick",
                    Kind.INTEGER,
                    1000000L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Budget de la chaîne de dommage, en nanosecondes par tick."),
            new Option(
                    "budgets.deformation_ns_per_tick",
                    Kind.INTEGER,
                    1500000L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Budget de la déformation, en nanosecondes par tick."),
            new Option(
                    "budgets.particles_ns_per_tick",
                    Kind.INTEGER,
                    1000000L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Budget du solveur de particules, en nanosecondes par tick."),
            new Option(
                    "budgets.render_prep_ns",
                    Kind.INTEGER,
                    2000000L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Budget de préparation du rendu, en nanosecondes par frame."),
            new Option(
                    "budgets.occlusion_ns",
                    Kind.INTEGER,
                    800000L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Budget de l'occlusion culling logiciel, en nanosecondes par frame."),
            new Option(
                    "budgets.asset_ns_per_tick",
                    Kind.INTEGER,
                    1000000L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Budget du chargement et de la compilation d'assets, en nanosecondes par tick."),
            new Option(
                    "budgets.idle_hook_ns",
                    Kind.INTEGER,
                    50000L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Surcoût maximal des hooks Forge par tick lorsqu'aucune assembly n'est présente, en nanosecondes."),
            new Option(
                    "budgets.submit_ns",
                    Kind.INTEGER,
                    200000L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Budget de soumission des commandes de rendu, en nanosecondes par frame."),
            new Option(
                    "budgets.native_mem_bytes",
                    Kind.INTEGER,
                    536870912L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Plafond de mémoire native, en octets."),
            new Option(
                    "budgets.deform_mem_bytes",
                    Kind.INTEGER,
                    134217728L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Plafond de mémoire des champs de déformation, en octets."),
            new Option(
                    "budgets.gpu_mem_bytes",
                    Kind.INTEGER,
                    536870912L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Plafond de mémoire GPU gérée par AXION, en octets."),
            new Option(
                    "budgets.max_active_bodies",
                    Kind.INTEGER,
                    2048L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Nombre maximal de corps physiques actifs simultanément."),
            new Option(
                    "budgets.max_deformed_assemblies",
                    Kind.INTEGER,
                    256L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Nombre maximal d'assemblies portant un champ de déformation."),
            new Option(
                    "budgets.max_collider_refits_per_tick",
                    Kind.INTEGER,
                    8L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Nombre maximal de réajustements de collider par tick."),
            new Option(
                    "budgets.max_visible_instances",
                    Kind.INTEGER,
                    512L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Nombre maximal d'instances rendues par frame."),
            new Option(
                    "budgets.max_triangles_frame",
                    Kind.INTEGER,
                    3000000L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Nombre maximal de triangles rendus par frame."),
            new Option(
                    "budgets.max_decals_frame",
                    Kind.INTEGER,
                    512L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Nombre maximal de décalques rendus par frame."),
            new Option(
                    "budgets.max_shadow_instances",
                    Kind.INTEGER,
                    128L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Nombre maximal d'instances projetant une ombre AXION."),
            new Option(
                    "jobs.max_workers",
                    Kind.INTEGER,
                    0L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Nombre de threads du pool de jobs. 0 laisse AXION le déduire du nombre de cœurs disponibles."),
            new Option(
                    "assets.max_source_bytes",
                    Kind.INTEGER,
                    134217728L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Taille maximale d'un fichier source d'asset accepté, en octets."),
            new Option(
                    "assets.max_compiled_bytes",
                    Kind.INTEGER,
                    268435456L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Taille maximale d'un asset compilé, en octets."),
            new Option(
                    "assets.max_deform_bytes",
                    Kind.INTEGER,
                    4194304L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Taille maximale des données de déformation compilées d'un asset, en octets."),
            new Option(
                    "assets.max_compile_ms",
                    Kind.INTEGER,
                    30000L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Durée maximale de compilation d'un asset, en millisecondes."),
            new Option(
                    "assets.cache_max_bytes",
                    Kind.INTEGER,
                    2147483648L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Taille maximale du cache d'assets compilés, en octets."),
            new Option(
                    "assets.startup_timeout_s",
                    Kind.INTEGER,
                    120L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Délai maximal de préparation des assets au démarrage, en secondes."),
            new Option(
                    "assets.unload_delay_s",
                    Kind.INTEGER,
                    60L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Délai avant déchargement d'un asset inutilisé, en secondes."),
            new Option(
                    "world.tiles_per_tick",
                    Kind.INTEGER,
                    8L,
                    new Domain(DomainKind.INT_RANGE, 1.0, 64.0, List.of()),
                    false,
                    false,
                    "Nombre de tuiles de collision monde extraites par tick."),
            new Option(
                    "world.tile_radius",
                    Kind.INTEGER,
                    3L,
                    new Domain(DomainKind.INT_RANGE, 1.0, 8.0, List.of()),
                    false,
                    false,
                    "Rayon, en tuiles, de la géométrie de collision monde maintenue autour d'une assembly."),
            new Option(
                    "vehicle.debris_ignore_s",
                    Kind.FLOAT,
                    1.0,
                    new Domain(DomainKind.FLOAT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Durée pendant laquelle un véhicule ignore les collisions avec ses propres débris, en secondes."),
            new Option(
                    "vehicle.max_suspension_misalignment",
                    Kind.FLOAT,
                    0.15,
                    new Domain(DomainKind.FLOAT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Désalignement maximal toléré d'une suspension déformée, en mètres, avant signalement."),
            new Option(
                    "attachment.max_chain",
                    Kind.INTEGER,
                    8L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Longueur maximale d'une chaîne d'assemblies attachées."),
            new Option(
                    "net.snapshot_rate_hz",
                    Kind.INTEGER,
                    10L,
                    new Domain(DomainKind.INT_RANGE, 2.0, 20.0, List.of()),
                    false,
                    false,
                    "Fréquence d'émission des snapshots d'état, en hertz."),
            new Option(
                    "net.sync_radius",
                    Kind.FLOAT,
                    96.0,
                    new Domain(DomainKind.FLOAT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Rayon de synchronisation autour d'un joueur, en blocs."),
            new Option(
                    "net.interpolation_delay_ms",
                    Kind.INTEGER,
                    100L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Retard d'interpolation appliqué côté client, en millisecondes."),
            new Option(
                    "net.extrapolation_max_ms",
                    Kind.INTEGER,
                    250L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Durée maximale d'extrapolation en l'absence de snapshot, en millisecondes."),
            new Option(
                    "net.snap_threshold",
                    Kind.FLOAT,
                    2.0,
                    new Domain(DomainKind.FLOAT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Écart, en mètres, au-delà duquel le client repositionne brutalement plutôt qu'interpoler."),
            new Option(
                    "net.prediction",
                    Kind.BOOLEAN,
                    true,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Active la prédiction côté client pour l'assembly pilotée par le joueur."),
            new Option(
                    "net.prediction_max_ping",
                    Kind.INTEGER,
                    400L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Latence maximale, en millisecondes, au-delà de laquelle la prédiction est désactivée."),
            new Option(
                    "net.max_bytes_per_second_per_player",
                    Kind.INTEGER,
                    32768L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Plafond de bande passante AXION par joueur, en octets par seconde."),
            new Option(
                    "net.max_packets_per_second",
                    Kind.INTEGER,
                    40L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Plafond de paquets AXION par seconde et par joueur."),
            new Option(
                    "net.max_impacts_per_packet",
                    Kind.INTEGER,
                    64L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Nombre maximal d'impacts groupés dans un paquet."),
            new Option(
                    "net.digest_interval_ticks",
                    Kind.INTEGER,
                    40L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Intervalle, en ticks, entre deux empreintes de vérification d'état."),
            new Option(
                    "net.max_deform_snapshots_per_second",
                    Kind.INTEGER,
                    4L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Plafond de snapshots de déformation par seconde en réplication par événements."),
            new Option(
                    "net.max_deform_snapshots_per_second_fallback",
                    Kind.INTEGER,
                    16L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Plafond de snapshots de déformation par seconde en mode SNAPSHOT, quand la reconstruction déterministe n'est pas garantie (5.12bis)."),
            new Option(
                    "net.divergence_tolerance",
                    Kind.INTEGER,
                    3L,
                    new Domain(DomainKind.INT_RANGE, 1.0, 32.0, List.of()),
                    false,
                    false,
                    "Nombre de divergences d'empreinte tolérées avant bascule de RECONSTRUCT vers SNAPSHOT."),
            new Option(
                    "persistence.max_bytes_per_assembly",
                    Kind.INTEGER,
                    65536L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Taille maximale de l'état persisté d'une assembly, en octets."),
            new Option(
                    "persistence.max_custom_bytes",
                    Kind.INTEGER,
                    16384L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Taille maximale des données personnalisées persistées par une assembly, en octets."),
            new Option(
                    "persistence.max_persisted_decals",
                    Kind.INTEGER,
                    16L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Nombre maximal de décalques sauvegardés par assembly."),
            new Option(
                    "persistence.max_load_ns_per_tick",
                    Kind.INTEGER,
                    500000L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Budget de chargement d'état persisté, en nanosecondes par tick."),
            new Option(
                    "persistence.journal_enabled",
                    Kind.BOOLEAN,
                    true,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Active le journal latéral de récupération (22.5.2)."),
            new Option(
                    "persistence.journal_ns_per_tick",
                    Kind.INTEGER,
                    200000L,
                    new Domain(DomainKind.INT_RANGE, 0.0, 2000000.0, List.of()),
                    false,
                    false,
                    "Budget d'écriture du journal, en nanosecondes par tick."),
            new Option(
                    "persistence.journal_max_bytes",
                    Kind.INTEGER,
                    268435456L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Taille maximale du journal, en octets."),
            new Option(
                    "limits.max_assemblies_per_dimension",
                    Kind.INTEGER,
                    4096L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Nombre maximal d'assemblies par dimension."),
            new Option(
                    "limits.max_spawn_per_command",
                    Kind.INTEGER,
                    64L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Nombre maximal d'assemblies créées par une seule commande."),
            new Option(
                    "watchdog.job_timeout_ms",
                    Kind.INTEGER,
                    5000L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Durée au-delà de laquelle un job est signalé en dépassement. Le watchdog ne tue jamais de thread (R-1901)."),
            new Option(
                    "modules.vehicles",
                    Kind.BOOLEAN,
                    true,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Active le module véhicules."),
            new Option(
                    "modules.joints",
                    Kind.BOOLEAN,
                    true,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Active le module joints et contraintes."),
            new Option(
                    "modules.damage",
                    Kind.BOOLEAN,
                    true,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Active le module dommage."),
            new Option(
                    "modules.deformation",
                    Kind.BOOLEAN,
                    true,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Active le module déformation."),
            new Option(
                    "modules.structure",
                    Kind.BOOLEAN,
                    true,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Active le module intégrité structurelle."),
            new Option(
                    "modules.repair",
                    Kind.BOOLEAN,
                    true,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Active le module réparation."),
            new Option(
                    "modules.wear",
                    Kind.BOOLEAN,
                    true,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Active le module usure de surface."),
            new Option(
                    "modules.particles",
                    Kind.BOOLEAN,
                    true,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Active le module particules."),
            new Option(
                    "modules.animation",
                    Kind.BOOLEAN,
                    true,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Active le module animation."),
            new Option(
                    "modules.attachments",
                    Kind.BOOLEAN,
                    true,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Active le module attaches entre assemblies."),
            new Option(
                    "modules.blocks",
                    Kind.BOOLEAN,
                    true,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Active le module blocs et items 3D."),
            new Option(
                    "integration.rustforgex.mode",
                    Kind.STRING,
                    "auto",
                    new Domain(DomainKind.ENUMERATION, 0.0, 0.0, List.of("auto", "off")),
                    false,
                    false,
                    "Détection d'un mod d'optimisation tiers. AXION n'en dépend jamais et fonctionne identiquement en son absence."),
            new Option(
                    "integration.rustforgex.cpu_share",
                    Kind.STRING,
                    "auto",
                    new Domain(DomainKind.KEYWORD_OR_COUNT, 0.0, 0.0, List.of("auto", "half", "full")),
                    false,
                    false,
                    "Part de parallélisme qu'AXION s'autorise en présence d'un autre consommateur de CPU. Un entier fixe le nombre de threads."),
            new Option(
                    "integration.rustforgex.bridge",
                    Kind.BOOLEAN,
                    true,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Active le pont d'interopérabilité optionnel. Réflexif, isolé, désactivable ; aucune fonctionnalité d'AXION n'en dépend."),
            new Option(
                    "integration.rustforgex.hint_no_transform",
                    Kind.BOOLEAN,
                    true,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Signale aux outils tiers qu'AXION préfère ne pas voir son bytecode transformé. Une indication, jamais une exigence."),
            new Option(
                    "debug.checksum_buffers",
                    Kind.BOOLEAN,
                    false,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    true,
                    false,
                    "Vérifie l'intégrité des tampons échangés avec le natif. Coûteux, réservé au diagnostic."),
            new Option(
                    "debug.record_incidents",
                    Kind.BOOLEAN,
                    false,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    true,
                    false,
                    "Enregistre de quoi rejouer un incident."));

    /** Options de {@code axion-client.toml}. */
    public static final List<Option> CLIENT_OPTIONS = List.of(
            new Option(
                    "render.backend",
                    Kind.STRING,
                    "auto",
                    new Domain(DomainKind.ENUMERATION, 0.0, 0.0, List.of("auto", "native", "vanilla")),
                    false,
                    false,
                    "Backend de rendu. auto bascule sur vanilla en présence d'un shaderpack."),
            new Option(
                    "render.max_distance",
                    Kind.FLOAT,
                    128.0,
                    new Domain(DomainKind.FLOAT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Distance maximale de rendu des objets AXION, en blocs."),
            new Option(
                    "render.lod_bias",
                    Kind.INTEGER,
                    0L,
                    new Domain(DomainKind.INT_RANGE, -2.0, 3.0, List.of()),
                    false,
                    false,
                    "Décalage appliqué au choix du niveau de détail. Négatif privilégie la qualité."),
            new Option(
                    "render.instancing_threshold",
                    Kind.INTEGER,
                    4L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Nombre d'instances identiques à partir duquel l'instanciation GPU est employée."),
            new Option(
                    "render.indirect",
                    Kind.BOOLEAN,
                    true,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Autorise le rendu indirect. Utilisé seulement si GL 4.3 est disponible."),
            new Option(
                    "render.compute",
                    Kind.BOOLEAN,
                    true,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Autorise les shaders de calcul. Utilisé seulement si GL 4.3 est disponible."),
            new Option(
                    "render.max_skinned_instances",
                    Kind.INTEGER,
                    64L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Nombre maximal d'instances animées par squelette et par frame."),
            new Option(
                    "render.max_palette_bones",
                    Kind.INTEGER,
                    128L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Nombre maximal d'os dans une palette de skinning."),
            new Option(
                    "render.max_deform_pages_per_frame",
                    Kind.INTEGER,
                    256L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Nombre maximal de pages de déformation téléversées vers le GPU par frame."),
            new Option(
                    "render.max_decals_per_assembly",
                    Kind.INTEGER,
                    64L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Nombre maximal de décalques portés par une assembly."),
            new Option(
                    "render.max_morph_targets_per_asset",
                    Kind.INTEGER,
                    8L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Nombre maximal de cibles de morphing par asset."),
            new Option(
                    "render.max_shader_variants",
                    Kind.INTEGER,
                    64L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Nombre maximal de variantes de shader compilées."),
            new Option(
                    "render.vanilla_max_skinned_vertices",
                    Kind.INTEGER,
                    50000L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Plafond de sommets animés par frame dans le backend vanilla."),
            new Option(
                    "render.vanilla_max_deformed_vertices",
                    Kind.INTEGER,
                    100000L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Plafond de sommets déformés par frame dans le backend vanilla."),
            new Option(
                    "render.vanilla_max_decals",
                    Kind.INTEGER,
                    64L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Plafond de décalques par frame dans le backend vanilla."),
            new Option(
                    "render.per_node_lightmap",
                    Kind.BOOLEAN,
                    false,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Échantillonne la lumière par nœud plutôt que par assembly. Plus fidèle, plus coûteux."),
            new Option(
                    "render.item_max_triangles",
                    Kind.INTEGER,
                    20000L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Nombre maximal de triangles pour le rendu 3D d'un item."),
            new Option(
                    "render.shadow",
                    Kind.STRING,
                    "map",
                    new Domain(DomainKind.ENUMERATION, 0.0, 0.0, List.of("map", "contact", "none")),
                    false,
                    false,
                    "Technique d'ombre propre aux objets AXION."),
            new Option(
                    "render.shadow_ground_quad",
                    Kind.BOOLEAN,
                    true,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Ajoute une ombre de contact au sol sous les objets."),
            new Option(
                    "render.shadow_ns",
                    Kind.INTEGER,
                    1500000L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Budget du rendu des ombres, en nanosecondes par frame."),
            new Option(
                    "render.probe_interval_frames",
                    Kind.INTEGER,
                    20L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Intervalle, en frames, entre deux mises à jour de la sonde d'environnement."),
            new Option(
                    "render.ssr_max_roughness",
                    Kind.FLOAT,
                    0.25,
                    new Domain(DomainKind.FLOAT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Rugosité maximale au-delà de laquelle les réflexions en espace écran sont abandonnées."),
            new Option(
                    "render.max_occluders",
                    Kind.INTEGER,
                    512L,
                    new Domain(DomainKind.INT_RANGE, 0.0, Double.MAX_VALUE, List.of()),
                    false,
                    false,
                    "Nombre maximal d'occulteurs retenus pour l'occlusion culling logiciel."),
            new Option(
                    "render.backend_silhouette_tolerance",
                    Kind.FLOAT,
                    0.01,
                    new Domain(DomainKind.FLOAT_RANGE, 0.0, 0.05, List.of()),
                    false,
                    false,
                    "Écart de silhouette toléré entre les deux backends de rendu (19.2bis)."),
            new Option(
                    "quality.deformation",
                    Kind.STRING,
                    "auto",
                    new Domain(DomainKind.ENUMERATION, 0.0, 0.0, List.of("auto", "off", "low", "medium", "high", "ultra")),
                    false,
                    false,
                    "Niveau de qualité de la déformation côté client."),
            new Option(
                    "quality.particles",
                    Kind.STRING,
                    "auto",
                    new Domain(DomainKind.ENUMERATION, 0.0, 0.0, List.of("auto", "off", "low", "medium", "high", "ultra")),
                    false,
                    false,
                    "Niveau de qualité des particules."),
            new Option(
                    "quality.shadows",
                    Kind.STRING,
                    "auto",
                    new Domain(DomainKind.ENUMERATION, 0.0, 0.0, List.of("auto", "off", "low", "medium", "high", "ultra")),
                    false,
                    false,
                    "Niveau de qualité des ombres."),
            new Option(
                    "quality.decals",
                    Kind.STRING,
                    "auto",
                    new Domain(DomainKind.ENUMERATION, 0.0, 0.0, List.of("auto", "off", "low", "medium", "high", "ultra")),
                    false,
                    false,
                    "Niveau de qualité des décalques."),
            new Option(
                    "quality.occlusion",
                    Kind.STRING,
                    "auto",
                    new Domain(DomainKind.ENUMERATION, 0.0, 0.0, List.of("auto", "off", "low", "medium", "high", "ultra")),
                    false,
                    false,
                    "Niveau de qualité de l'occlusion culling."),
            new Option(
                    "quality.lighting",
                    Kind.STRING,
                    "auto",
                    new Domain(DomainKind.ENUMERATION, 0.0, 0.0, List.of("auto", "off", "low", "medium", "high", "ultra")),
                    false,
                    false,
                    "Niveau de qualité de l'éclairage."),
            new Option(
                    "quality.skinning",
                    Kind.STRING,
                    "auto",
                    new Domain(DomainKind.ENUMERATION, 0.0, 0.0, List.of("auto", "off", "low", "medium", "high", "ultra")),
                    false,
                    false,
                    "Niveau de qualité du skinning."),
            new Option(
                    "quality.reflections",
                    Kind.STRING,
                    "auto",
                    new Domain(DomainKind.ENUMERATION, 0.0, 0.0, List.of("auto", "off", "low", "medium", "high", "ultra")),
                    false,
                    false,
                    "Niveau de qualité des réflexions."),
            new Option(
                    "quality.parallax",
                    Kind.STRING,
                    "auto",
                    new Domain(DomainKind.ENUMERATION, 0.0, 0.0, List.of("auto", "off", "low", "medium", "high", "ultra")),
                    false,
                    false,
                    "Niveau de qualité du parallax mapping."),
            new Option(
                    "overlay.enabled",
                    Kind.BOOLEAN,
                    false,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    true,
                    false,
                    "Affiche l'overlay de diagnostic."),
            new Option(
                    "overlay.key",
                    Kind.STRING,
                    "unbound",
                    new Domain(DomainKind.FREE_TEXT, 0.0, 0.0, List.of()),
                    true,
                    false,
                    "Touche d'activation de l'overlay. unbound laisse l'overlay sans raccourci."),
            new Option(
                    "debug.gl",
                    Kind.BOOLEAN,
                    false,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    true,
                    false,
                    "Active la validation des appels OpenGL. Coûteux, réservé au diagnostic."));

    /** Options de {@code axion-server.toml}. */
    public static final List<Option> SERVER_OPTIONS = List.of(
            new Option(
                    "server.allow_client_prediction",
                    Kind.BOOLEAN,
                    true,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Autorise les clients à prédire le mouvement de l'assembly qu'ils pilotent."),
            new Option(
                    "server.kick_on_input_abuse",
                    Kind.BOOLEAN,
                    true,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Expulse un client dont les entrées sortent des bornes admises de façon répétée."),
            new Option(
                    "server.allow_experimental_authority",
                    Kind.BOOLEAN,
                    false,
                    new Domain(DomainKind.BOOLEAN, 0.0, 0.0, List.of()),
                    false,
                    false,
                    "Autorise le mode d'autorité SERVER_FULL pour les particules. EXPERIMENTAL."));
}
