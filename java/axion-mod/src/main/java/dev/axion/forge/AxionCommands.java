package dev.axion.forge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.axion.bridge.NativeBridge;
import dev.axion.diag.AssetsReport;
import dev.axion.diag.BenchRunner;
import dev.axion.diag.MetricsReport;
import dev.axion.diag.StatusReport;
import dev.axion.entity.AssemblySpawns;
import dev.axion.lifecycle.AxionRuntime;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.CompoundTagArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/**
 * Commandes d'AXION (C-71).
 *
 * <p>Les branches existent à mesure que les composants qu'elles pilotent
 * existent : état, métriques, assets, configuration, et depuis C-50 la
 * création et le retrait d'assemblies.
 *
 * <p>La commande exige le niveau de permission des opérateurs. Elle ne divulgue
 * aucune donnée de joueur, mais décrit l'installation — chemins, versions,
 * causes de désactivation — et cela n'a pas à être visible de tous. C'est
 * l'option conservatrice, que rien n'oblige à conserver si l'usage montre le
 * contraire.
 */
final class AxionCommands {

    /** Niveau de permission des opérateurs. */
    private static final int OPERATOR = 2;

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private AxionCommands() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /**
     * Enregistre les commandes d'AXION.
     *
     * @param dispatcher répartiteur de commandes du serveur
     * @param runtime cycle de vie à interroger
     */
    static void register(CommandDispatcher<CommandSourceStack> dispatcher, AxionRuntime runtime) {
        dispatcher.register(
                Commands.literal("axion")
                        .requires(source -> source.hasPermission(OPERATOR))
                        .then(Commands.literal("status")
                                .executes(context -> {
                                    // Le rapport est composé hors de toute API
                                    // de plateforme ; ici on ne fait que
                                    // l'afficher.
                                    return send(context.getSource(), StatusReport.of(runtime));
                                }))
                        .then(Commands.literal("metrics")
                                .executes(context -> metrics(context.getSource(), runtime))
                                .then(Commands.literal("export")
                                        .executes(context ->
                                                export(context.getSource(), runtime))))
                        .then(Commands.literal("assets")
                                .then(Commands.literal("list")
                                        .executes(context -> send(
                                                context.getSource(),
                                                AssetsReport.list(runtime.assets()))))
                                .then(Commands.literal("info")
                                        .then(Commands.argument("chemin", StringArgumentType.greedyString())
                                                .executes(context -> send(
                                                        context.getSource(),
                                                        AssetsReport.info(
                                                                runtime.assets(),
                                                                StringArgumentType.getString(
                                                                        context, "chemin"))))))
                                .then(Commands.literal("reload")
                                        .executes(context -> reloadAssets(context.getSource(), runtime))))
                        .then(Commands.literal("spawn")
                                .then(Commands.argument("definition", ResourceLocationArgument.id())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                runtime.definitions().ids(), builder))
                                        .executes(context -> spawn(
                                                context.getSource(),
                                                runtime,
                                                ResourceLocationArgument.getId(context, "definition"),
                                                context.getSource().getPosition(),
                                                null))
                                        .then(Commands.argument("position", Vec3Argument.vec3())
                                                .executes(context -> spawn(
                                                        context.getSource(),
                                                        runtime,
                                                        ResourceLocationArgument.getId(context, "definition"),
                                                        Vec3Argument.getVec3(context, "position"),
                                                        null))
                                                .then(Commands.argument("nbt", CompoundTagArgument.compoundTag())
                                                        .executes(context -> spawn(
                                                                context.getSource(),
                                                                runtime,
                                                                ResourceLocationArgument.getId(
                                                                        context, "definition"),
                                                                Vec3Argument.getVec3(context, "position"),
                                                                CompoundTagArgument.getCompoundTag(
                                                                        context, "nbt")))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("cibles", EntityArgument.entities())
                                        .executes(context -> remove(
                                                context.getSource(),
                                                EntityArgument.getEntities(context, "cibles")))))
                        .then(Commands.literal("config")
                                .then(Commands.literal("get")
                                        .then(Commands.argument("clé", StringArgumentType.greedyString())
                                                .executes(context -> configGet(
                                                        context.getSource(),
                                                        runtime,
                                                        StringArgumentType.getString(
                                                                context, "clé"))))))
                        .then(Commands.literal("bench")
                                .then(Commands.argument("scénario", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                List.of("ffi"), builder))
                                        .executes(context -> bench(
                                                context.getSource(),
                                                runtime,
                                                StringArgumentType.getString(context, "scénario"))))));
    }

    /**
     * Exécute un scénario de benchmark en jeu (C-72) et affiche ses statistiques.
     *
     * <p>Refuse si le runtime natif n'est pas prêt : le scénario {@code ffi}
     * mesure un aller-retour natif, qui n'aboutirait pas sinon. La mesure est
     * par lot, donc juste même sous la résolution de {@code nanoTime} (voir
     * {@link BenchRunner}) — contrairement à la calibration de démarrage.
     */
    private static int bench(CommandSourceStack source, AxionRuntime runtime, String scenario) {
        if (!"ffi".equals(scenario)) {
            source.sendFailure(Component.literal(
                    "AXION : scénario inconnu — " + scenario + " (connus : ffi)"));
            return 0;
        }
        var outcome = runtime.outcome();
        if (outcome == null || !outcome.isReady()) {
            source.sendFailure(Component.literal("AXION : runtime natif non prêt"));
            return 0;
        }
        BenchRunner.Result result = BenchRunner.ffi(NativeBridge::nativeAbiVersion);
        LOGGER.info("AXION : /axion bench {} demandé par {} — p50 {} ns/appel",
                scenario, source.getTextName(), String.format(java.util.Locale.ROOT, "%.1f", result.p50Ns()));
        return send(source, List.of(
                "AXION ENGINE — benchmark ffi (aller-retour FFI, mesuré par lot)",
                String.format(java.util.Locale.ROOT,
                        "  p50=%.1f p95=%.1f p99=%.1f ns/appel (min %.1f, max %.1f)",
                        result.p50Ns(), result.p95Ns(), result.p99Ns(),
                        result.minNs(), result.maxNs()),
                "  " + result.calls() + " appels mesurés"));
    }

    /**
     * Force la recompilation de tous les assets (C-71).
     *
     * <p>Commande mutante : R-810 veut qu'elle soit journalisée avec son auteur
     * et ses paramètres. Une recompilation complète se remarque sur un serveur,
     * et savoir qui l'a demandée évite d'en chercher la cause ailleurs.
     */
    private static int reloadAssets(CommandSourceStack source, AxionRuntime runtime) {
        if (runtime.assets() == null) {
            source.sendFailure(Component.literal("AXION : aucun asset découvert"));
            return 0;
        }

        int count = runtime.assets().forceRecompile();
        LOGGER.info("AXION : /axion assets reload demandé par {} — {} asset(s) à recompiler",
                source.getTextName(), count);
        source.sendSuccess(
                () -> Component.literal("AXION : " + count + " asset(s) à recompiler"), true);
        return 1;
    }

    /**
     * Crée une assembly (C-50, C-71).
     *
     * <p>L'entité est construite comme {@code /summon} le fait, pour que le NBT
     * donné s'applique de la même façon, puis liée à sa definition. Commande
     * mutante : journalisée avec son auteur et ses paramètres (R-810).
     */
    private static int spawn(
            CommandSourceStack source,
            AxionRuntime runtime,
            ResourceLocation definition,
            Vec3 position,
            CompoundTag nbt) {
        var outcome = runtime.outcome();
        if (outcome == null) {
            source.sendFailure(Component.literal("AXION : configuration non chargée"));
            return 0;
        }
        AssemblySpawns.Decision decision = AssemblySpawns.check(
                runtime.definitions(),
                definition.toString(),
                1,
                outcome.config().getInt("limits.max_spawn_per_command"));
        if (!decision.accepted()) {
            source.sendFailure(Component.literal("AXION : " + decision.refusal()));
            return 0;
        }

        ServerLevel level = source.getLevel();
        CompoundTag tag = nbt == null ? new CompoundTag() : nbt.copy();
        tag.putString("id", AxionEntities.ASSEMBLY.getId().toString());
        Entity entity = EntityType.loadEntityRecursive(tag, level, created -> {
            created.moveTo(position.x, position.y, position.z, created.getYRot(), created.getXRot());
            return created;
        });
        if (!(entity instanceof AxionEntity assembly)) {
            source.sendFailure(Component.literal("AXION : l'entité n'a pas pu être construite"));
            return 0;
        }
        assembly.bind(decision.definition());
        if (!level.tryAddFreshEntityWithPassengers(assembly)) {
            // Même refus que `/summon` : un UUID déjà présent dans le monde.
            source.sendFailure(Component.literal("AXION : UUID déjà présent dans le monde"));
            return 0;
        }

        LOGGER.info("AXION : /axion spawn {} en {} demandé par {}",
                definition, position, source.getTextName());
        source.sendSuccess(() -> Component.literal("AXION : assembly " + definition + " créée"), true);
        return 1;
    }

    /**
     * Retire des assemblies (C-71).
     *
     * <p>Seules les AxionEntity parmi les cibles sont retirées : un sélecteur trop
     * large ne supprime rien d'autre. Journalisée avec son auteur (R-810).
     */
    private static int remove(CommandSourceStack source, Collection<? extends Entity> targets) {
        int removed = 0;
        for (Entity target : targets) {
            if (target instanceof AxionEntity) {
                target.discard();
                removed++;
            }
        }
        LOGGER.info("AXION : /axion remove demandé par {} — {} assembly(s) retirée(s)",
                source.getTextName(), removed);
        if (removed == 0) {
            source.sendFailure(Component.literal("AXION : aucune assembly parmi les cibles"));
            return 0;
        }
        int count = removed;
        source.sendSuccess(() -> Component.literal("AXION : " + count + " assembly(s) retirée(s)"), true);
        return count;
    }

    /** Affiche la valeur effective d'une option de configuration (C-71). */
    private static int configGet(CommandSourceStack source, AxionRuntime runtime, String key) {
        var outcome = runtime.outcome();
        if (outcome == null) {
            source.sendFailure(Component.literal("AXION : configuration non chargée"));
            return 0;
        }

        Object value = outcome.config().values().get(key);
        if (value == null) {
            // Dire que l'option est inconnue, et non rendre une valeur vide :
            // les deux se ressemblent, et seule la première se corrige.
            source.sendFailure(Component.literal("AXION : option inconnue — " + key));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(key + " = " + value), false);
        return 1;
    }

    /** Affiche le résumé des métriques natives (R-502). */
    private static int metrics(CommandSourceStack source, AxionRuntime runtime) {
        String json = runtime.metricsJson();
        if (json == null) {
            source.sendFailure(Component.literal(
                    "AXION : runtime natif inactif, aucune métrique à lire"));
            return 0;
        }
        return send(source, MetricsReport.summary(json));
    }

    /**
     * Écrit l'export complet dans un fichier et en donne le chemin (R-502).
     *
     * <p>Le document part dans un fichier plutôt que dans le chat : il compte
     * plusieurs dizaines de lignes, et R-442 le destine aussi au dump de
     * diagnostic. Le nom porte l'horodatage, si bien que deux exports
     * successifs ne s'écrasent pas — comparer deux instants est précisément ce
     * qu'on veut faire de métriques.
     */
    private static int export(CommandSourceStack source, AxionRuntime runtime) {
        String json = runtime.metricsJson();
        if (json == null) {
            source.sendFailure(Component.literal(
                    "AXION : runtime natif inactif, aucune métrique à exporter"));
            return 0;
        }

        Path gameDir = runtime.gameDir();
        if (gameDir == null) {
            source.sendFailure(Component.literal("AXION : répertoire de jeu inconnu"));
            return 0;
        }

        String stamp = LocalDateTime.now().format(FILE_STAMP);
        Path target = gameDir.resolve("axion").resolve("metrics-" + stamp + ".json");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, json, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            // Un export qui n'aboutit pas se dit : le taire laisserait croire
            // qu'un fichier existe quelque part.
            source.sendFailure(Component.literal(
                    "AXION : export impossible — " + failure.getMessage()));
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal("AXION : métriques exportées dans " + target), true);
        return 1;
    }

    private static int send(CommandSourceStack source, List<String> lines) {
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    /** Horodatage du nom de fichier : trié par ordre alphabétique comme par date. */
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
}
