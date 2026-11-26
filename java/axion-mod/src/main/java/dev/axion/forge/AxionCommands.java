package dev.axion.forge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.axion.bridge.NativeBridge;
import dev.axion.api.content.ContentKind;
import dev.axion.content.RegisteredContent;
import dev.axion.diag.AssetsReport;
import dev.axion.diag.DefinitionsReport;
import dev.axion.diag.BenchRunner;
import dev.axion.diag.BuildInfo;
import dev.axion.diag.EntityMetrics;
import dev.axion.diag.MetricsExport;
import dev.axion.diag.MetricsReport;
import dev.axion.diag.SchemaTwoWriter;
import dev.axion.diag.StatusReport;
import dev.axion.entity.AssemblySpawns;
import dev.axion.lifecycle.AxionRuntime;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
     * @param devReload le rechargement en développement (ADR-162 D9)
     */
    static void register(CommandDispatcher<CommandSourceStack> dispatcher, AxionRuntime runtime,
            DevReload devReload) {
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
                        // CAP-42 : voir en jeu les definitions chargées, et pourquoi une autre a été refusée.
                        .then(Commands.literal("defs")
                                .then(Commands.literal("list")
                                        .executes(context -> send(
                                                context.getSource(),
                                                DefinitionsReport.list(runtime.definitions()))))
                                .then(Commands.literal("info")
                                        .then(Commands.argument("definition", ResourceLocationArgument.id())
                                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                        definitionsEtRefus(runtime), builder))
                                                .executes(context -> send(
                                                        context.getSource(),
                                                        DefinitionsReport.info(
                                                                runtime.definitions(),
                                                                ResourceLocationArgument.getId(context, "definition")
                                                                        .toString(),
                                                                cle -> RegisteredContent
                                                                        .entry(ContentKind.DEFINITION, cle)
                                                                        .map(entree -> entree.source())))))))
                        .then(Commands.literal("dev")
                                .then(Commands.literal("reload")
                                        .executes(context -> devReload.run(context.getSource()))))
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
                        // ADR-171 D4 : poser, ancrer, défaire et lister les attaches d'une assembly.
                        .then(Commands.literal("attach")
                                .then(Commands.argument("cible", EntityArgument.entity())
                                        .then(Commands.argument("socket", StringArgumentType.word())
                                                .then(Commands.argument("autre", EntityArgument.entity())
                                                        .then(Commands.argument("socket_autre",
                                                                        StringArgumentType.word())
                                                                .executes(context -> attach(context.getSource(),
                                                                        EntityArgument.getEntity(context, "cible"),
                                                                        StringArgumentType.getString(context, "socket"),
                                                                        EntityArgument.getEntity(context, "autre"),
                                                                        StringArgumentType.getString(context,
                                                                                "socket_autre"))))))))
                        .then(Commands.literal("anchor")
                                .then(Commands.argument("cible", EntityArgument.entity())
                                        .then(Commands.argument("socket", StringArgumentType.word())
                                                .then(Commands.argument("bloc",
                                                                net.minecraft.commands.arguments.coordinates
                                                                        .BlockPosArgument.blockPos())
                                                        .executes(context -> anchor(context.getSource(),
                                                                EntityArgument.getEntity(context, "cible"),
                                                                StringArgumentType.getString(context, "socket"),
                                                                net.minecraft.commands.arguments.coordinates
                                                                        .BlockPosArgument.getLoadedBlockPos(context,
                                                                                "bloc")))))))
                        .then(Commands.literal("detach")
                                .then(Commands.argument("cible", EntityArgument.entity())
                                        .then(Commands.argument("socket", StringArgumentType.word())
                                                .executes(context -> detach(context.getSource(),
                                                        EntityArgument.getEntity(context, "cible"),
                                                        StringArgumentType.getString(context, "socket"))))))
                        // ADR-174, axe Diagnostic : les derniers événements de contenu, de toutes ou d'une assembly.
                        .then(Commands.literal("events")
                                .executes(context -> events(context.getSource(), null))
                                .then(Commands.argument("cible", EntityArgument.entity())
                                        .executes(context -> events(context.getSource(),
                                                EntityArgument.getEntity(context, "cible")))))
                        .then(Commands.literal("attaches")
                                .then(Commands.argument("cible", EntityArgument.entity())
                                        .executes(context -> attaches(context.getSource(),
                                                EntityArgument.getEntity(context, "cible")))))
                        .then(Commands.literal("variables")
                                .then(Commands.argument("cible", EntityArgument.entity())
                                        .executes(context -> variables(
                                                context.getSource(),
                                                EntityArgument.getEntity(context, "cible")))))
                        // ADR-167 D10 : voir, poser et retirer les modificateurs d'une instance.
                        .then(Commands.literal("params")
                                .then(Commands.argument("cible", EntityArgument.entity())
                                        .executes(context -> params(
                                                context.getSource(), EntityArgument.getEntity(context, "cible"), null))
                                        .then(Commands.literal("set")
                                                .then(Commands.argument("cause", ResourceLocationArgument.id())
                                                        .then(Commands.argument("parametre", StringArgumentType.word())
                                                                .suggests((context, builder) ->
                                                                        SharedSuggestionProvider.suggest(
                                                                                parameterNames(context), builder))
                                                                .then(Commands.argument("operation",
                                                                                StringArgumentType.word())
                                                                        .suggests((context, builder) ->
                                                                                SharedSuggestionProvider.suggest(
                                                                                        OPERATIONS, builder))
                                                                        .then(Commands.argument("quantite",
                                                                                        DoubleArgumentType.doubleArg())
                                                                                .executes(context -> paramsSet(
                                                                                        context.getSource(),
                                                                                        EntityArgument.getEntity(
                                                                                                context, "cible"),
                                                                                        ResourceLocationArgument.getId(
                                                                                                context, "cause"),
                                                                                        StringArgumentType.getString(
                                                                                                context, "parametre"),
                                                                                        StringArgumentType.getString(
                                                                                                context, "operation"),
                                                                                        DoubleArgumentType.getDouble(
                                                                                                context,
                                                                                                "quantite"))))))))
                                        .then(Commands.literal("remove")
                                                .then(Commands.argument("cause", ResourceLocationArgument.id())
                                                        .executes(context -> paramsRemove(
                                                                context.getSource(),
                                                                EntityArgument.getEntity(context, "cible"),
                                                                ResourceLocationArgument.getId(context, "cause"),
                                                                null))
                                                        .then(Commands.argument("parametre", StringArgumentType.word())
                                                                .suggests((context, builder) ->
                                                                        SharedSuggestionProvider.suggest(
                                                                                parameterNames(context), builder))
                                                                .executes(context -> paramsRemove(
                                                                        context.getSource(),
                                                                        EntityArgument.getEntity(context, "cible"),
                                                                        ResourceLocationArgument.getId(
                                                                                context, "cause"),
                                                                        StringArgumentType.getString(
                                                                                context, "parametre"))))))
                                        .then(Commands.argument("parametre", StringArgumentType.word())
                                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                        parameterNames(context), builder))
                                                .executes(context -> params(
                                                        context.getSource(),
                                                        EntityArgument.getEntity(context, "cible"),
                                                        StringArgumentType.getString(context, "parametre"))))))
                        // ADR-176 D3 : voir, remplir et décharger l'énergie d'un véhicule.
                        .then(Commands.literal("energy")
                                .then(Commands.argument("cible", EntityArgument.entity())
                                        .executes(context -> energy(context.getSource(),
                                                EntityArgument.getEntity(context, "cible")))
                                        .then(Commands.literal("refuel")
                                                .then(Commands.argument("quantite", DoubleArgumentType.doubleArg(0.0))
                                                        .executes(context -> energyRefuel(context.getSource(),
                                                                EntityArgument.getEntity(context, "cible"),
                                                                DoubleArgumentType.getDouble(context, "quantite"), null))
                                                        // ADR-182 D2 : une quantité d'un type, dans la première réserve
                                                        // qui l'accepte.
                                                        .then(Commands.argument("type", ResourceLocationArgument.id())
                                                                .suggests((context, builder) ->
                                                                        SharedSuggestionProvider.suggest(
                                                                                carriers(context), builder))
                                                                .executes(context -> energyRefuel(context.getSource(),
                                                                        EntityArgument.getEntity(context, "cible"),
                                                                        DoubleArgumentType.getDouble(context,
                                                                                "quantite"),
                                                                        ResourceLocationArgument.getId(context, "type")
                                                                                .toString())))))
                                        .then(Commands.literal("battery")
                                                .then(Commands.argument("charge", DoubleArgumentType.doubleArg(0.0))
                                                        .executes(context -> energyBattery(context.getSource(),
                                                                EntityArgument.getEntity(context, "cible"),
                                                                DoubleArgumentType.getDouble(context, "charge")))))))
                        // ADR-176 D4 : voir et poser l'usure des pneus d'un véhicule.
                        .then(Commands.literal("wear")
                                .then(Commands.argument("cible", EntityArgument.entity())
                                        .executes(context -> wear(context.getSource(),
                                                EntityArgument.getEntity(context, "cible")))
                                        .then(Commands.literal("set")
                                                .then(Commands.argument("roue", StringArgumentType.word())
                                                        .suggests((context, builder) ->
                                                                SharedSuggestionProvider.suggest(wheelNames(context),
                                                                        builder))
                                                        .then(Commands.argument("usure",
                                                                        DoubleArgumentType.doubleArg(0.0, 1.0))
                                                                .executes(context -> wearSet(context.getSource(),
                                                                        EntityArgument.getEntity(context, "cible"),
                                                                        StringArgumentType.getString(context, "roue"),
                                                                        DoubleArgumentType.getDouble(context,
                                                                                "usure"))))))
                                        .then(Commands.literal("reset")
                                                .executes(context -> wearSet(context.getSource(),
                                                        EntityArgument.getEntity(context, "cible"), null, 0.0)))))
                        // ADR-178 : qui porte qui, au serveur.
                        .then(Commands.literal("carry")
                                .executes(context -> carried(context.getSource())))
                        .then(Commands.literal("debug")
                                .then(Commands.literal("trace")
                                        .then(Commands.literal("on")
                                                .executes(context -> traceOn(context.getSource(), runtime)))
                                        .then(Commands.literal("off")
                                                .executes(context -> traceOff(context.getSource(), runtime)))))
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
                                                StringArgumentType.getString(context, "scénario"),
                                                null))
                                        .then(Commands.literal("--json")
                                                .then(Commands.argument("fichier", StringArgumentType.greedyString())
                                                        .executes(context -> bench(
                                                                context.getSource(),
                                                                runtime,
                                                                StringArgumentType.getString(context, "scénario"),
                                                                StringArgumentType.getString(
                                                                        context, "fichier"))))))));
    }

    /**
     * Exécute un scénario de benchmark en jeu (C-72) et affiche ses statistiques.
     *
     * <p>Refuse si le runtime natif n'est pas prêt : le scénario {@code ffi}
     * mesure un aller-retour natif, qui n'aboutirait pas sinon. La mesure est
     * par lot, donc juste même sous la résolution de {@code nanoTime} (voir
     * {@link BenchRunner}) — contrairement à la calibration de démarrage.
     */
    private static int bench(
            CommandSourceStack source, AxionRuntime runtime, String scenario, String jsonPath) {
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

        List<String> lines = new ArrayList<>();
        lines.add("AXION ENGINE — benchmark ffi (aller-retour FFI, mesuré par lot)");
        lines.add(String.format(java.util.Locale.ROOT,
                "  p50=%.1f p95=%.1f p99=%.1f ns/appel (min %.1f, max %.1f)",
                result.p50Ns(), result.p95Ns(), result.p99Ns(), result.minNs(), result.maxNs()));
        lines.add("  " + result.calls() + " appels mesurés");

        if (jsonPath != null) {
            Path gameDir = runtime.gameDir();
            if (gameDir == null) {
                source.sendFailure(Component.literal("AXION : répertoire de jeu inconnu"));
                return send(source, lines);
            }
            try {
                // Sous le répertoire de jeu, jamais au chemin reçu : la commande
                // est au niveau 2, et un chemin libre écraserait n'importe quoi.
                Path out = SchemaTwoWriter.targetIn(gameDir, jsonPath);
                // R-2231 : le commit que le build a compilé, pas un marqueur.
                SchemaTwoWriter.write("B-07", scenario, BuildInfo.commit(), result, out);
                // R-810 : une écriture sur le disque se journalise avec son auteur.
                LOGGER.info("AXION : /axion bench {} — résultat schéma 2 écrit dans {} pour {}",
                        scenario, out, source.getTextName());
                lines.add("  résultat schéma 2 écrit dans " + out);
            } catch (IOException | RuntimeException error) {
                source.sendFailure(Component.literal(
                        "AXION : écriture du JSON refusée ou échouée — " + error.getMessage()));
            }
        }
        return send(source, lines);
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
        AuthoritativeThread.require();
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
            EntityMetrics.GLOBAL.spawnRefused();
            source.sendFailure(Component.literal("AXION : " + decision.refusal()));
            return 0;
        }

        ServerLevel level = source.getLevel();
        CompoundTag tag = nbt == null ? new CompoundTag() : nbt.copy();
        tag.putString("id", AxionEntities.ASSEMBLY.getId().toString());
        Entity entity = EntityType.loadEntityRecursive(tag, level, created -> {
            // Rappel synchrone, mais le bytecode ne le dit pas : il vérifie lui aussi (T-011).
            AuthoritativeThread.require();
            created.moveTo(position.x, position.y, position.z, created.getYRot(), created.getXRot());
            return created;
        });
        if (!(entity instanceof AxionEntity assembly)) {
            EntityMetrics.GLOBAL.spawnRefused();
            source.sendFailure(Component.literal("AXION : l'entité n'a pas pu être construite"));
            return 0;
        }
        assembly.bind(decision.definition());
        if (!level.tryAddFreshEntityWithPassengers(assembly)) {
            // Même refus que `/summon` : un UUID déjà présent dans le monde.
            EntityMetrics.GLOBAL.spawnRefused();
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
        AuthoritativeThread.require();
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

    /**
     * Montre les variables d'une assembly (C-71, ADR-163 D2) : pour chacune, sa valeur, son type et ses bornes, ses
     * règles, et ce qui l'a posée en dernier. Lecture seule.
     */
    private static int attach(CommandSourceStack source, Entity target, String socket, Entity other,
            String otherSocket) {
        AttachmentRuntime runtime = AttachmentRuntime.current();
        if (!(target instanceof AxionEntity a) || !(other instanceof AxionEntity b) || runtime == null) {
            source.sendFailure(Component.literal("AXION : les deux cibles ne sont pas des assemblies"));
            return 0;
        }
        return report(source, runtime.attach(a, socket, b, otherSocket, java.util.Set.of(), "axion"));
    }

    private static int anchor(CommandSourceStack source, Entity target, String socket,
            net.minecraft.core.BlockPos block) {
        AttachmentRuntime runtime = AttachmentRuntime.current();
        if (!(target instanceof AxionEntity a) || runtime == null) {
            source.sendFailure(Component.literal("AXION : la cible n'est pas une assembly"));
            return 0;
        }
        return report(source, runtime.anchor(a, socket, block, java.util.Set.of(), "axion"));
    }

    private static int report(CommandSourceStack source, dev.axion.api.attach.AttachResult result) {
        if (result.attached()) {
            source.sendSuccess(() -> Component.literal("AXION : attache posée"), true);
            return 1;
        }
        source.sendFailure(Component.literal("AXION : attache refusée — " + result.status() + " : " + result.message()));
        return 0;
    }

    private static int detach(CommandSourceStack source, Entity target, String socket) {
        AttachmentRuntime runtime = AttachmentRuntime.current();
        if (!(target instanceof AxionEntity a) || runtime == null) {
            source.sendFailure(Component.literal("AXION : la cible n'est pas une assembly"));
            return 0;
        }
        if (!runtime.detach(a, socket, "detached")) {
            source.sendFailure(Component.literal("AXION : rien n'est attaché au socket " + socket));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("AXION : socket " + socket + " détaché"), true);
        return 1;
    }

    private static int attaches(CommandSourceStack source, Entity target) {
        AttachmentRuntime runtime = AttachmentRuntime.current();
        if (!(target instanceof AxionEntity a) || runtime == null) {
            source.sendFailure(Component.literal("AXION : la cible n'est pas une assembly"));
            return 0;
        }
        List<dev.axion.api.attach.Attachments.Attachment> list = runtime.list(a);
        List<String> lines = new ArrayList<>();
        lines.add("AXION ENGINE — attaches de " + a.definitionId() + " : " + list.size());
        for (dev.axion.api.attach.Attachments.Attachment attachment : list) {
            String other = attachment.anchor().map(p -> "bloc " + p[0] + " " + p[1] + " " + p[2])
                    .orElse(attachment.other().map(id -> id + " « " + attachment.otherSocket() + " »").orElse("?"));
            lines.add(String.format(java.util.Locale.ROOT, "  %s → %s : %s, longueur %.2f, écart %.3f, tension %.0f N"
                    + " (%s)", attachment.socket(), other, attachment.kind(), attachment.length(), attachment.distance(),
                    attachment.tension(), attachment.owner()));
        }
        source.sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return list.size();
    }

    private static int events(CommandSourceStack source, Entity target) {
        if (target != null && !(target instanceof AxionEntity)) {
            source.sendFailure(Component.literal("AXION : la cible n'est pas une assembly"));
            return 0;
        }
        List<ContentEventLog.Entry> last = ContentEventLog.last(target == null ? null : target.getUUID());
        List<String> lines = new ArrayList<>();
        lines.add("AXION ENGINE — derniers événements de contenu" + (target == null ? "" : " de "
                + ((AxionEntity) target).definitionId()) + " : " + last.size() + " (au plus " + ContentEventLog.KEPT
                + ")");
        last.forEach(entry -> lines.add(entry.line()));
        source.sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return last.size();
    }

    /** L'énergie d'une assembly, en lignes lisibles (ADR-176 D3, ADR-182 D2). */
    private static List<String> energyLines(AxionEntity assembly) {
        dev.axion.definition.EnergySpec spec = assembly.energySpec();
        List<String> lines = new java.util.ArrayList<>();
        lines.add("AXION : énergie de " + assembly.definitionId());
        if (!spec.declared()) {
            lines.add("  aucune : la definition ne déclare ni réservoir ni batterie");
            return lines;
        }
        // Un réservoir sans type et une batterie, ceux du schéma 7 : les lignes d'avant.
        boolean plain = spec.stores().stream().allMatch(store -> store.carrier() == null && store.maxFillRate() == 0f
                && (store.name().equals("fuel") || store.name().equals(dev.axion.definition.EnergySpec.BATTERY)));
        if (!plain) {
            for (int slot = 0; slot < spec.stores().size(); slot++) {
                dev.axion.definition.EnergySpec.Store store = spec.stores().get(slot);
                String role = slot == spec.fuelStore() ? ", brûlée par le moteur"
                        : slot == spec.batteryStore() ? ", celle du démarreur" : "";
                String rate = store.maxFillRate() > 0f ? String.format(java.util.Locale.ROOT, ", %.4f par seconde au plus",
                        store.maxFillRate()) : "";
                lines.add(String.format(java.util.Locale.ROOT, "  %s (%s%s%s) : %.3f / %.3f (%.1f %%)", store.name(),
                        store.carrier() == null ? "tout" : store.carrier(), role, rate, assembly.storeAmount(slot),
                        store.capacity(), 100.0 * assembly.storeAmount(slot) / store.capacity()));
            }
            if (spec.massDeclared()) {
                lines.add(String.format(java.util.Locale.ROOT, "  masse des réserves : %.2f kg",
                        spec.mass(storeAmounts(assembly, spec))));
            }
            return lines;
        }
        if (spec.fuelCapacity() > 0f) {
            lines.add(String.format(java.util.Locale.ROOT, "  carburant : %.3f / %.3f l (%.1f %%)", assembly.fuel(),
                    spec.fuelCapacity(), 100.0 * assembly.fuel() / spec.fuelCapacity()));
            lines.add(String.format(java.util.Locale.ROOT, "  masse du carburant : %.2f kg (%.3f kg par litre)",
                    spec.fuelMass(assembly.fuel()), spec.unitMass()));
        }
        if (spec.batteryCapacity() > 0f) {
            lines.add(String.format(java.util.Locale.ROOT, "  batterie : %.3f / %.3f", assembly.battery(),
                    spec.batteryCapacity()));
        }
        return lines;
    }

    private static double[] storeAmounts(AxionEntity assembly, dev.axion.definition.EnergySpec spec) {
        double[] amounts = new double[spec.stores().size()];
        for (int slot = 0; slot < amounts.length; slot++) {
            amounts[slot] = assembly.storeAmount(slot);
        }
        return amounts;
    }

    /** {@return les types des réserves de la cible, pour la complétion} */
    private static List<String> carriers(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        try {
            return EntityArgument.getEntity(context, "cible") instanceof AxionEntity assembly
                    ? assembly.energySpec().stores().stream().map(dev.axion.definition.EnergySpec.Store::carrier)
                            .filter(java.util.Objects::nonNull).distinct().toList()
                    : List.of();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException unresolved) {
            return List.of();
        }
    }

    private static AxionEntity energyTarget(CommandSourceStack source, Entity target) {
        if (!(target instanceof AxionEntity assembly)) {
            source.sendFailure(Component.literal("AXION : la cible n'est pas une assembly"));
            return null;
        }
        if (assembly.held() == null) {
            source.sendFailure(Component.literal("AXION : definition " + assembly.definitionId()
                    + " pas encore compilée"));
            return null;
        }
        return assembly;
    }

    private static int energy(CommandSourceStack source, Entity target) {
        AxionEntity assembly = energyTarget(source, target);
        if (assembly == null) {
            return 0;
        }
        List<String> lines = energyLines(assembly);
        source.sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return 1;
    }

    /** Remplit une réserve : commande d'administration, mutante, journalisée avec son auteur (R-810). */
    private static int energyRefuel(CommandSourceStack source, Entity target, double amount, String carrier) {
        AxionEntity assembly = energyTarget(source, target);
        if (assembly == null) {
            return 0;
        }
        dev.axion.definition.EnergySpec spec = assembly.energySpec();
        if (carrier == null && !(spec.fuelCapacity() > 0f)) {
            source.sendFailure(Component.literal("AXION : " + assembly.definitionId() + " n'a pas de réservoir"
                    + (spec.declared() ? " brûlé par un moteur : indiquer le type" : "")));
            return 0;
        }
        if (carrier == null && spec.stores().get(spec.fuelStore()).carrier() != null) {
            source.sendFailure(Component.literal("AXION : le réservoir de " + assembly.definitionId() + " n'accepte que "
                    + spec.stores().get(spec.fuelStore()).carrier() + " : indiquer le type"));
            return 0;
        }
        if (carrier != null && spec.stores().stream().noneMatch(store -> store.accepts(carrier))) {
            source.sendFailure(Component.literal("AXION : aucune réserve de " + assembly.definitionId() + " n'accepte "
                    + carrier));
            return 0;
        }
        double entered = carrier == null ? assembly.refuel(amount) : assembly.fillAny(null, carrier, amount);
        LOGGER.info("AXION : /axion energy refuel {} {} sur {} ({}) demandé par {} : {} entrés", amount,
                carrier == null ? "" : carrier, target.getUUID(), assembly.definitionId(), source.getTextName(), entered);
        List<String> lines = energyLines(assembly);
        source.sendSuccess(() -> Component.literal(String.join("\n", lines)), true);
        return 1;
    }

    /** Pose la charge de la batterie : commande d'administration, journalisée avec son auteur (R-810). */
    private static int energyBattery(CommandSourceStack source, Entity target, double charge) {
        AxionEntity assembly = energyTarget(source, target);
        if (assembly == null) {
            return 0;
        }
        if (!(assembly.energySpec().batteryCapacity() > 0f)) {
            source.sendFailure(Component.literal("AXION : " + assembly.definitionId() + " n'a pas de batterie"));
            return 0;
        }
        assembly.setBattery(charge);
        LOGGER.info("AXION : /axion energy battery {} sur {} ({}) demandé par {}", charge, target.getUUID(),
                assembly.definitionId(), source.getTextName());
        List<String> lines = energyLines(assembly);
        source.sendSuccess(() -> Component.literal(String.join("\n", lines)), true);
        return 1;
    }

    /** {@return les roues de la cible, pour la complétion ; aucune si elle n'est pas un véhicule compilé} */
    private static List<String> wheelNames(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        try {
            return EntityArgument.getEntity(context, "cible") instanceof AxionEntity assembly ? assembly.wheelNodes()
                    : List.of();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException absente) {
            return List.of();
        }
    }

    /** L'usure des pneus d'un véhicule, une ligne par roue (ADR-176 D4). */
    private static List<String> wearLines(AxionEntity assembly) {
        List<String> lines = new java.util.ArrayList<>();
        lines.add("AXION : pneus de " + assembly.definitionId());
        List<String> wheels = assembly.wheelNodes();
        if (wheels.isEmpty()) {
            lines.add("  aucune roue : la definition n'est pas un véhicule");
            return lines;
        }
        for (int rank = 0; rank < wheels.size(); rank++) {
            // ADR-181 D4 : ce que chaque roue touche, tel que le natif l'a rapporté.
            String surface = assembly.wheelSurface(rank);
            String sol = !assembly.wheelGrounded(rank) ? "en l'air"
                    : surface == null ? "sur un sol sans matériau" : "sur " + surface;
            lines.add(String.format(java.util.Locale.ROOT, "  %s : %.1f %% d'usure, %s", wheels.get(rank),
                    100.0 * assembly.wheelWear(rank), sol));
        }
        lines.add(String.format(java.util.Locale.ROOT, "  route : mouillée à %.0f %%", 100.0 * assembly.wetness()));
        return lines;
    }

    private static int wear(CommandSourceStack source, Entity target) {
        AxionEntity assembly = energyTarget(source, target);
        if (assembly == null) {
            return 0;
        }
        List<String> lines = wearLines(assembly);
        source.sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return assembly.wheelNodes().size();
    }

    /** Les entités que les assemblies portent au serveur (ADR-178) : lecture seule. */
    private static int carried(CommandSourceStack source) {
        AuthoritativeThread.require();
        List<String> lines = new ArrayList<>();
        for (var entry : CarrySupports.serverEntries()) {
            CarrySupports.Support support = entry.getValue();
            lines.add(entry.getKey().getName().getString() + " : sur l'assembly " + support.assembly().getUUID()
                    + ", corps " + support.body() + (support.lag() >= 0 ? ", retard " + support.lag() + " tick(s)" : ""));
        }
        String report = lines.isEmpty() ? "AXION : aucune entité portée" : "AXION : " + lines.size()
                + " entité(s) portée(s)\n" + String.join("\n", lines);
        source.sendSuccess(() -> Component.literal(report), false);
        return lines.size();
    }

    /** Change un pneu, ou tous : commande d'administration, mutante, journalisée avec son auteur (R-810). */
    private static int wearSet(CommandSourceStack source, Entity target, String wheel, double wear) {
        AxionEntity assembly = energyTarget(source, target);
        if (assembly == null) {
            return 0;
        }
        try {
            assembly.setWearByName(wheel, wear);
        } catch (IllegalArgumentException refus) {
            source.sendFailure(Component.literal("AXION : refusé — " + refus.getMessage()));
            return 0;
        }
        LOGGER.info("AXION : /axion wear set {} {} sur {} ({}) demandé par {}", wheel == null ? "(toutes)" : wheel, wear,
                target.getUUID(), assembly.definitionId(), source.getTextName());
        List<String> lines = wearLines(assembly);
        source.sendSuccess(() -> Component.literal(String.join("\n", lines)), true);
        return 1;
    }

    private static int variables(CommandSourceStack source, Entity target) {
        if (!(target instanceof AxionEntity assembly)) {
            source.sendFailure(Component.literal("AXION : la cible n'est pas une assembly"));
            return 0;
        }
        dev.axion.entity.AssemblyVariables held = assembly.variables();
        if (held == null) {
            source.sendFailure(Component.literal("AXION : definition " + assembly.definitionId()
                    + " pas encore compilée"));
            return 0;
        }
        List<String> lines = variableLines(assembly.definitionId(), held);
        source.sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return held.size();
    }

    /** Les opérations d'un modificateur, telles qu'une commande les écrit. */
    private static final List<String> OPERATIONS = java.util.Arrays.stream(
            dev.axion.api.assembly.Modifier.Operation.values()).map(dev.axion.api.assembly.Modifier.Operation::key)
            .toList();

    /** {@return les paramètres de la cible, pour la complétion ; aucun si elle n'en est pas une assembly compilée} */
    private static List<String> parameterNames(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        try {
            if (EntityArgument.getEntity(context, "cible") instanceof AxionEntity assembly
                    && assembly.parameters() != null) {
                return assembly.parameters().tuning().names();
            }
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException introuvable) {
            // La cible n'est pas encore résolue : rien à proposer.
        }
        return List.of();
    }

    /** {@return les paramètres d'une cible, ou {@code null} après avoir dit pourquoi il n'y en a pas} */
    private static dev.axion.entity.AssemblyParameters parametersOf(CommandSourceStack source, Entity target) {
        if (!(target instanceof AxionEntity assembly)) {
            source.sendFailure(Component.literal("AXION : la cible n'est pas une assembly"));
            return null;
        }
        dev.axion.entity.AssemblyParameters table = assembly.parameters();
        if (table == null) {
            source.sendFailure(Component.literal("AXION : definition " + assembly.definitionId()
                    + " pas encore compilée"));
        }
        return table;
    }

    /**
     * Montre les paramètres d'une assembly (C-71, ADR-167 D10) : valeur, base, et chaque modificateur avec son origine
     * et, s'il est inactif, sa raison. Lecture seule.
     */
    private static int params(CommandSourceStack source, Entity target, String only) {
        dev.axion.entity.AssemblyParameters table = parametersOf(source, target);
        if (table == null) {
            return 0;
        }
        if (only != null && table.tuning().indexOf(only) < 0
                && table.applied().stream().noneMatch(entry -> entry.modifier().parameter().equals(only))) {
            source.sendFailure(Component.literal("AXION : paramètre « " + only + " » inconnu de cette assembly ; elle a : "
                    + String.join(", ", table.tuning().names())));
            return 0;
        }
        AxionEntity assembly = (AxionEntity) target;
        List<String> lines = dev.axion.diag.ParametersReport.lines(assembly.definitionId(), table, only,
                assembly.controllers(), AxionEntities.controllerCosts(),
                dev.axion.definition.VehicleExtensions.current());
        source.sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return table.tuning().size();
    }

    /**
     * Pose un modificateur sur une assembly (ADR-167 D10, CAP-34) : commande d'administration, mutante, journalisée
     * avec son auteur et ses paramètres (R-810). Un modificateur hors bornes est refusé et nommé.
     */
    private static int paramsSet(CommandSourceStack source, Entity target, ResourceLocation cause, String parameter,
            String operation, double amount) {
        dev.axion.entity.AssemblyParameters table = parametersOf(source, target);
        if (table == null) {
            return 0;
        }
        dev.axion.api.assembly.Modifier.Operation op;
        try {
            op = dev.axion.api.assembly.Modifier.Operation.valueOf(operation.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException inconnue) {
            source.sendFailure(Component.literal("AXION : opération « " + operation + " » inconnue ; admises : "
                    + String.join(", ", OPERATIONS)));
            return 0;
        }
        try {
            table.modify(new dev.axion.api.assembly.Modifier(cause.toString(), parameter, op, amount),
                    dev.axion.entity.AssemblyParameters.COMMAND);
        } catch (IllegalArgumentException refus) {
            source.sendFailure(Component.literal("AXION : refusé — " + refus.getMessage()));
            return 0;
        }
        LOGGER.info("AXION : /axion params set {} {} {} {} sur {} ({}) demandé par {}", cause, parameter,
                op.key(), amount, target.getUUID(), ((AxionEntity) target).definitionId(), source.getTextName());
        List<String> lines = dev.axion.diag.ParametersReport.lines(((AxionEntity) target).definitionId(), table,
                parameter);
        source.sendSuccess(() -> Component.literal(String.join("\n", lines)), true);
        return 1;
    }

    /**
     * Retire les modificateurs d'une cause, sur un paramètre ou sur tous (ADR-167 D10) : réussit toujours, journalisé
     * avec son auteur (R-810).
     */
    private static int paramsRemove(CommandSourceStack source, Entity target, ResourceLocation cause,
            String parameter) {
        dev.axion.entity.AssemblyParameters table = parametersOf(source, target);
        if (table == null) {
            return 0;
        }
        boolean removed = parameter == null ? table.remove(cause.toString()) : table.remove(cause.toString(), parameter);
        if (!removed) {
            source.sendFailure(Component.literal("AXION : « " + cause + " » ne porte aucun modificateur"
                    + (parameter == null ? "" : " sur « " + parameter + " »")));
            return 0;
        }
        LOGGER.info("AXION : /axion params remove {} {} sur {} ({}) demandé par {}", cause,
                parameter == null ? "(tous)" : parameter, target.getUUID(), ((AxionEntity) target).definitionId(),
                source.getTextName());
        List<String> lines = dev.axion.diag.ParametersReport.lines(((AxionEntity) target).definitionId(), table,
                parameter);
        source.sendSuccess(() -> Component.literal(String.join("\n", lines)), true);
        return 1;
    }

    /** {@return les lignes de {@code /axion variables} : un en-tête, puis une ligne par variable} */
    static List<String> variableLines(String definition, dev.axion.entity.AssemblyVariables held) {
        List<String> lines = new java.util.ArrayList<>();
        lines.add("AXION : " + definition + " — " + held.size() + " variable(s)");
        for (int i = 0; i < held.size(); i++) {
            dev.axion.definition.Variables.Variable variable = held.declared().get(i);
            String bounds = variable.type() == dev.axion.definition.Variables.Type.BOOL
                    || (Double.isInfinite(variable.min()) && Double.isInfinite(variable.max()))
                    ? ""
                    : " dans [" + number(variable.min()) + ", " + number(variable.max()) + "]";
            // ADR-174 D2 : une variable par siège dit la valeur de chacun.
            String shown = number(held.get(i));
            if (variable.perSeat()) {
                List<String> seats = new ArrayList<>();
                double[] values = held.seatValues(i);
                for (int seat = 0; seat < values.length; seat++) {
                    seats.add(held.seats().get(seat) + " " + number(values[seat]));
                }
                shown = "{" + String.join(", ", seats) + "} par siège";
            }
            lines.add("  " + variable.name() + " = " + shown + " (" + variable.type().key() + bounds
                    + ") — posée par " + held.writer(i) + " ; persist " + variable.persist()
                    + ", replicate " + variable.replicate().name().toLowerCase(java.util.Locale.ROOT)
                    + ", write " + variable.write().name().toLowerCase(java.util.Locale.ROOT));
        }
        return lines;
    }

    private static String number(double value) {
        if (Double.isInfinite(value)) {
            return value > 0 ? "+∞" : "-∞";
        }
        return value == Math.rint(value) && Math.abs(value) < 1e15
                ? Long.toString((long) value)
                : Double.toString(value);
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
     * Écrit l'export complet dans un fichier et en donne le chemin (R-502), par
     * {@link MetricsExport}, le chemin que partage l'export du client.
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

        Path target;
        try {
            target = MetricsExport.write(
                    json, gameDir, MetricsExport.SERVER_PREFIX, LocalDateTime.now());
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

    /**
     * Ouvre une trace de la simulation (C-71, {@code /axion debug trace on}) : chaque corps et
     * chaque événement, à chaque tick, dans un CSV horodaté sous {@code <gameDir>/axion/traces/}.
     * La commande est journalisée avec son auteur (R-810).
     */
    private static int traceOn(CommandSourceStack source, AxionRuntime runtime) {
        Path gameDir = runtime.gameDir();
        if (gameDir == null) {
            source.sendFailure(Component.literal("AXION : répertoire de jeu inconnu"));
            return 0;
        }
        String stamp = LocalDateTime.now().format(FILE_STAMP);
        Path target = gameDir.resolve("axion").resolve("traces").resolve("trace-" + stamp + ".csv");
        try {
            runtime.startTrace(target);
        } catch (IOException failure) {
            source.sendFailure(Component.literal(
                    "AXION : trace impossible — " + failure.getMessage()));
            return 0;
        }
        LOGGER.info("AXION : /axion debug trace on demandé par {} — {}", source.getTextName(), target);
        source.sendSuccess(() -> Component.literal("AXION : trace de la simulation dans " + target
                + " — /axion debug trace off pour l'arrêter"), true);
        return 1;
    }

    /** Ferme la trace de la simulation en cours (C-71, {@code /axion debug trace off}). */
    private static int traceOff(CommandSourceStack source, AxionRuntime runtime) {
        long lines = runtime.stopTrace();
        if (lines < 0) {
            source.sendFailure(Component.literal("AXION : aucune trace en cours"));
            return 0;
        }
        LOGGER.info("AXION : /axion debug trace off demandé par {}", source.getTextName());
        source.sendSuccess(
                () -> Component.literal("AXION : trace arrêtée, " + lines + " ligne(s) écrites"), true);
        return 1;
    }

    /** {@return les definitions chargées puis refusées, pour la suggestion de {@code /axion defs info}} */
    private static List<String> definitionsEtRefus(AxionRuntime runtime) {
        List<String> ids = new ArrayList<>(runtime.definitions().ids());
        ids.addAll(runtime.definitions().refusedIds());
        return ids;
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
