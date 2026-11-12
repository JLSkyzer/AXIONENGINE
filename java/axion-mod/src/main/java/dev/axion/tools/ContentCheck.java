package dev.axion.tools;

import dev.axion.api.content.ContentKind;
import dev.axion.asset.AssetEntry;
import dev.axion.asset.AssetRegistry;
import dev.axion.asset.AssetState;
import dev.axion.content.ContentRegistry;
import dev.axion.content.RegisteredContent;
import dev.axion.definition.DefinitionRegistry;
import dev.axion.lifecycle.AxionRuntime;
import dev.axion.platform.PlatformAdapter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Le validateur hors jeu d'un addon (ADR-166 D4, CAP-49) : {@code ./gradlew checkAxionContent}.
 *
 * <p>Il applique au dossier {@code axion/} d'un addon exactement les règles du chargement en jeu, parce qu'il fait
 * tourner le moteur lui-même, sans Minecraft : le runtime d'AXION et sa bibliothèque native, sur une plateforme
 * minimale. Le contenu d'AXION (ses matériaux, que les definitions de l'addon nomment) et celui de l'addon
 * s'enregistrent comme au chargement des mods ; chaque famille est remise au runtime dans l'ordre du démarrage d'un
 * serveur ; puis la barrière de ce démarrage attend la compilation de chaque modèle et de chaque definition contre son
 * modèle. Le validateur rend ensuite ce qui concerne l'addon : ses fautes, qui font échouer la tâche, et ses
 * avertissements.
 *
 * <p>Ce qu'il ne voit pas : une definition construite en Java par le builder, qui n'existe qu'à l'exécution de
 * l'addon. Le kit de GameTest la couvre ({@code AxionGameTests.assertLoaded}).
 *
 * <pre>{@code
 * java -cp <AXION et ses dépendances> dev.axion.tools.ContentCheck <modId> <dossier des ressources> [dossier de travail]
 * }</pre>
 */
public final class ContentCheck {

    /** Le délai de la barrière de compilation : celui du démarrage d'un serveur dédié par défaut. */
    private static final long BARRIERE_S = 120;

    /**
     * Ce que le validateur a trouvé.
     *
     * @param counts le nombre d'entrées de l'addon, par famille
     * @param faults ce qui empêche le chargement d'une entrée de l'addon
     * @param warnings ce que le chargement signale sans rien refuser
     */
    public record Report(Map<ContentKind, Integer> counts, List<String> faults, List<String> warnings) {}

    private ContentCheck() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /**
     * Le point d'entrée : {@code <modId> <dossier des ressources> [dossier de travail]}. Code de sortie 0 sans faute,
     * 1 avec, 2 sur des arguments faux.
     *
     * @param args les arguments
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage : ContentCheck <modId> <dossier des ressources> [dossier de travail]");
            System.exit(2);
        }
        Path ressources = Path.of(args[1]);
        Path travail = args.length > 2 ? Path.of(args[2]) : Path.of("build", "axion-check");
        Report report = check(args[0], ressources, travail);
        System.out.println("AXION — vérification du contenu de " + args[0] + " (" + ressources.resolve("axion") + ")");
        report.counts().forEach((kind, n) -> System.out.println("  " + n + " " + kind.folder()));
        report.warnings().forEach(line -> System.out.println("  avertissement : " + line));
        report.faults().forEach(line -> System.out.println("  FAUTE : " + line));
        System.out.println(report.faults().isEmpty()
                ? "Aucune faute : le contenu se charge comme en jeu."
                : report.faults().size() + " faute(s) : ce contenu ne se chargerait pas entièrement en jeu.");
        System.exit(report.faults().isEmpty() ? 0 : 1);
    }

    /**
     * {@return ce que le chargement en jeu ferait du contenu de l'addon}
     *
     * @param modId l'addon : l'espace de noms de son contenu
     * @param resources le dossier qui contient son {@code axion/}
     * @param workDir un dossier de travail, où le runtime pose sa configuration et sa bibliothèque native
     */
    public static Report check(String modId, Path resources, Path workDir) {
        try {
            Files.createDirectories(workDir);
        } catch (IOException echec) {
            throw new UncheckedIOException(echec);
        }
        AxionRuntime runtime = new AxionRuntime();
        runtime.onConstructed(new Plateforme(workDir.toAbsolutePath()));
        runtime.onSetup(new Properties());
        List<String> faults = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (runtime.outcome() == null || !runtime.outcome().isReady()) {
            faults.add("le moteur n'a pas démarré : " + String.join(" ; ", runtime.transitions()));
            return new Report(Map.of(), faults, warnings);
        }

        // L'enregistrement, comme au chargement des mods : AXION d'abord, puis l'addon.
        ContentRegistry registry = new ContentRegistry(runtime.maxSiblingBytes());
        try (DossierAxion axion = DossierAxion.ouvrir()) {
            try (ContentRegistry.Session session = registry.open("axion", axion::chemin)) {
                session.registerFolder("axion/");
            }
            Path racine = resources.toAbsolutePath();
            try (ContentRegistry.Session session = registry.open(modId, relative -> existant(racine.resolve(relative)))) {
                session.registerFolder("axion/");
            }
        }
        Map<ContentKind, List<ContentRegistry.Entry>> resolved = registry.resolve();
        RegisteredContent.publish(resolved);
        String prefixe = modId + ":";
        registry.errors().stream().filter(line -> line.contains(prefixe) || line.startsWith(modId + " "))
                .forEach(faults::add);

        // Chaque famille, dans l'ordre du démarrage d'un serveur, puis sa barrière.
        int avant = runtime.transitions().size();
        runtime.onAssetReload(RegisteredContent.source(ContentKind.MODEL));
        runtime.onPhysicsMaterialReload(RegisteredContent.source(ContentKind.PHYSICS_MATERIAL));
        runtime.onCollisionGroupReload(RegisteredContent.source(ContentKind.COLLISION_GROUP));
        runtime.onBlockMaterialReload(RegisteredContent.source(ContentKind.BLOCK_MATERIAL));
        runtime.onTireReload(RegisteredContent.source(ContentKind.TIRE));
        runtime.onDefinitionReload(RegisteredContent.source(ContentKind.DEFINITION));
        if (!runtime.awaitAssets(TimeUnit.SECONDS.toNanos(BARRIERE_S))) {
            faults.add("la compilation des modèles n'a pas fini en " + BARRIERE_S + " s");
        }
        List<String> journal = new ArrayList<>(runtime.transitions().subList(avant, runtime.transitions().size()));
        journal.addAll(runtime.drainAssetJournal());

        // Les definitions de l'addon : chargées, ou refusées avec leur cause (schéma, références, compilation).
        DefinitionRegistry definitions = runtime.definitions();
        for (ContentRegistry.Entry entry : resolved.getOrDefault(ContentKind.DEFINITION, List.of())) {
            if (!entry.owner().equals(modId)) {
                continue;
            }
            String id = definitionId(entry.key());
            if (definitions.get(id).isEmpty()) {
                faults.add(id + " — " + definitions.refusal(id).orElse("non chargée"));
            }
        }
        // Ses modèles : un modèle qui ne compile pas est remplacé en jeu par la boîte de repli.
        AssetRegistry assets = runtime.assets();
        if (assets != null) {
            for (AssetEntry entry : assets.entries()) {
                if (entry.path().startsWith(prefixe) && entry.state() == AssetState.FAILED) {
                    // La cause est au journal des assets, une fois, sous le chemin du modèle.
                    faults.add(journal.stream().filter(line -> line.startsWith(entry.path() + " — ")).findFirst()
                            .orElse(entry.path() + " — modèle refusé à la compilation"));
                }
            }
        }
        // Le reste du journal qui le nomme : refus des autres familles (code E-), avertissements.
        for (String line : journal) {
            if (!line.contains(prefixe) || faults.stream().anyMatch(faute -> faute.contains(line))) {
                continue;
            }
            if (line.startsWith("E-")) {
                faults.add(line);
            } else {
                warnings.add(line);
            }
        }

        Map<ContentKind, Integer> counts = new EnumMap<>(ContentKind.class);
        resolved.forEach((kind, entries) -> {
            int n = (int) entries.stream().filter(entry -> entry.owner().equals(modId)).count();
            if (n > 0) {
                counts.put(kind, n);
            }
        });
        runtime.onGameShuttingDown();
        return new Report(counts, List.copyOf(faults), List.copyOf(warnings));
    }

    /** {@code <ns>:axion/definitions/<chemin>.json} donne {@code <ns>:<chemin>}. */
    private static String definitionId(String key) {
        int colon = key.indexOf(':');
        String path = key.substring(colon + 1).substring("axion/definitions/".length());
        return key.substring(0, colon + 1) + path.substring(0, path.length() - ".json".length());
    }

    private static Path existant(Path path) {
        return Files.exists(path) ? path : null;
    }

    /** Le dossier {@code axion/} d'AXION lui-même : dans son JAR, ou dans ses ressources en développement. */
    private record DossierAxion(Path racine, FileSystem archive) implements AutoCloseable {

        static DossierAxion ouvrir() {
            URL url = ContentCheck.class.getResource("/axion/");
            if (url == null) {
                throw new IllegalStateException("le contenu d'AXION (axion/) est introuvable sur le classpath");
            }
            try {
                URI uri = url.toURI();
                if ("jar".equals(uri.getScheme())) {
                    FileSystem archive = FileSystems.newFileSystem(uri, Map.of());
                    return new DossierAxion(archive.getPath("/"), archive);
                }
                return new DossierAxion(Path.of(uri).getParent(), null);
            } catch (URISyntaxException | IOException echec) {
                throw new IllegalStateException("le contenu d'AXION est illisible : " + echec, echec);
            }
        }

        Path chemin(String relative) {
            return existant(racine.resolve(relative));
        }

        @Override
        public void close() {
            if (archive != null) {
                try {
                    archive.close();
                } catch (IOException ignore) {
                    // Lecture seule : rien n'est perdu.
                }
            }
        }
    }

    /** Ce que le runtime voit de son hôte : un dossier, pas de Minecraft. */
    private record Plateforme(Path dir) implements PlatformAdapter {
        @Override
        public String platformName() {
            return "verification-hors-jeu";
        }

        @Override
        public String minecraftVersion() {
            return "1.20.1";
        }

        @Override
        public boolean isModLoaded(String modid) {
            return false;
        }

        @Override
        public boolean isAuthoritativeThread() {
            return true;
        }

        @Override
        public boolean isClient() {
            return false;
        }

        @Override
        public long currentTick() {
            return 0;
        }

        @Override
        public void runOnAuthoritativeThread(Runnable work) {
            work.run();
        }

        @Override
        public Path gameDir() {
            return dir;
        }

        @Override
        public Path configDir() {
            return dir.resolve("config");
        }
    }
}
