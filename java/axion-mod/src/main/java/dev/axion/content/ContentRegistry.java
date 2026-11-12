package dev.axion.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.axion.api.content.ContentKind;
import dev.axion.api.content.ContentRegistrar;
import dev.axion.api.content.DefinitionDocument;
import dev.axion.asset.AssetSiblings;
import dev.axion.definition.CanonicalJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Le contenu que les mods enregistrent auprès d'AXION (ADR-162 D2, D3).
 *
 * <p>Logique pure, sans Forge : la couche Forge ouvre une {@link Session} par mod, avec la racine de son JAR,
 * appelle son fournisseur, puis {@link #resolve()} rend, pour chaque famille, les entrées que lisent les registres
 * existants. Les clés des fichiers sont celles que ces registres attendent : {@code <modId>:axion/<dossier>/<chemin>}.
 *
 * <p>Chaque entrée garde son propriétaire, le mod qui l'a déclarée, et sa source. Un mod n'enregistre que dans son
 * espace de noms, son modId ; une même clé déclarée deux fois est refusée, en nommant les deux sources ; remplacer
 * l'entrée d'un autre mod passe par {@link ContentRegistrar#replace}, et deux mods qui remplacent la même entrée sont
 * refusés tous les deux. Aucune de ces erreurs ne lève : elle est consignée, et l'entrée fautive écartée.
 */
public final class ContentRegistry {

    /** Un espace de noms de Minecraft : un modId. */
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");

    /** Un chemin de Minecraft, relatif à une famille. */
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");

    /**
     * Une entrée enregistrée.
     *
     * @param kind sa famille
     * @param key sa clé de fichier, {@code <modId>:axion/<dossier>/<chemin>}
     * @param owner le mod qui la déclare, ou qui la remplace
     * @param source d'où elle vient, pour les messages
     * @param bytes son contenu
     */
    public record Entry(ContentKind kind, String key, String owner, String source, byte[] bytes) {}

    /** Un remplacement demandé. */
    private record Replacement(ContentKind kind, String key, String owner, String source, byte[] bytes) {}

    private final long maxSiblingBytes;
    private final Map<ContentKind, Map<String, Entry>> entries = new EnumMap<>(ContentKind.class);
    /** Les contrôleurs de véhicule et les paramètres d'addon enregistrés (ADR-167 D1, D9), par identifiant. */
    private final Map<String, dev.axion.definition.VehicleExtensions.Controller> controllers = new TreeMap<>();
    private final Map<String, dev.axion.definition.VehicleExtensions.Parameter> parameters = new TreeMap<>();
    private final List<Replacement> replacements = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    /**
     * @param maxSiblingBytes taille au-delà de laquelle un fichier voisin d'un modèle n'est pas lu
     *     ({@code assets.max_source_bytes}, ADR-139)
     */
    public ContentRegistry(long maxSiblingBytes) {
        this.maxSiblingBytes = maxSiblingBytes;
        for (ContentKind kind : ContentKind.values()) {
            entries.put(kind, new TreeMap<>());
        }
    }

    /**
     * Ouvre l'enregistrement d'un mod.
     *
     * @param modId le mod, certifié par Forge
     * @param jar résout un chemin relatif à la racine du JAR du mod ; rend {@code null} s'il est absent
     * @return le registraire du mod, valable jusqu'à {@link Session#close()}
     */
    public Session open(String modId, Function<String, Path> jar) {
        if (!NAMESPACE.matcher(modId).matches()) {
            throw new IllegalArgumentException("modId invalide comme espace de noms : " + modId);
        }
        return new Session(modId, jar);
    }

    /** {@return les contrôleurs et les paramètres d'addon enregistrés (ADR-167 D1, D9)} */
    public dev.axion.definition.VehicleExtensions extensions() {
        return new dev.axion.definition.VehicleExtensions(controllers, parameters);
    }

    /** {@return les erreurs consignées, dans leur ordre} */
    public List<String> errors() {
        return List.copyOf(errors);
    }

    /**
     * Applique les remplacements, puis rend le contenu de chaque famille.
     *
     * @return les entrées par famille, chacune triée par clé
     */
    public Map<ContentKind, List<Entry>> resolve() {
        Map<String, List<Replacement>> byTarget = new LinkedHashMap<>();
        for (Replacement replacement : replacements) {
            byTarget.computeIfAbsent(replacement.kind() + " " + replacement.key(), k -> new ArrayList<>())
                    .add(replacement);
        }
        for (List<Replacement> claims : byTarget.values()) {
            Replacement first = claims.get(0);
            Map<String, Entry> family = entries.get(first.kind());
            Entry original = family.get(first.key());
            if (claims.size() > 1) {
                errors.add("remplacement refusé : " + first.key() + " est remplacé par plusieurs mods ("
                        + String.join(", ", claims.stream().map(r -> r.owner() + " — " + r.source()).toList())
                        + ")");
                continue;
            }
            if (original == null) {
                errors.add("remplacement refusé : " + first.key() + ", que " + first.owner() + " remplace par "
                        + first.source() + ", n'existe pas");
                continue;
            }
            family.put(first.key(), new Entry(first.kind(), first.key(), first.owner(),
                    first.source() + " (remplace l'entrée de " + original.owner() + ")", first.bytes()));
        }
        replacements.clear();
        Map<ContentKind, List<Entry>> resolved = new EnumMap<>(ContentKind.class);
        entries.forEach((kind, family) -> resolved.put(kind, List.copyOf(family.values())));
        return resolved;
    }

    /**
     * {@return les octets d'une definition construite en Java : sa forme canonique (ADR-162 D4)} Ils entrent au
     * registre comme ceux d'un fichier ; le même contenu y a les mêmes octets, d'où qu'il vienne.
     */
    static byte[] canonicalBytes(DefinitionDocument definition) {
        return CanonicalJson.of(json(definition.root())).getBytes(StandardCharsets.UTF_8);
    }

    /** {@return une valeur de {@link DefinitionDocument} en arbre JSON} */
    private static JsonElement json(Object value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof Map<?, ?> map) {
            JsonObject object = new JsonObject();
            map.forEach((key, child) -> object.add((String) key, json(child)));
            return object;
        }
        if (value instanceof List<?> list) {
            JsonArray array = new JsonArray(list.size());
            list.forEach(child -> array.add(json(child)));
            return array;
        }
        if (value instanceof String text) {
            return new JsonPrimitive(text);
        }
        if (value instanceof Boolean flag) {
            return new JsonPrimitive(flag);
        }
        return new JsonPrimitive((Number) value);
    }

    /** {@return le nom d'une definition construite en Java, vérifié : un chemin de Minecraft, sans extension} */
    private static String checkedName(String name) {
        Objects.requireNonNull(name, "name");
        if (!PATH.matcher(name).matches() || name.startsWith("/") || name.endsWith("/") || name.endsWith(".json")
                || name.contains("//") || List.of(name.split("/")).contains("..")) {
            throw new IllegalArgumentException("nom de definition attendu, chemin [a-z0-9_./-] sans extension : "
                    + name);
        }
        return name;
    }

    /** {@return la clé de fichier d'une entrée de ce mod} */
    static String key(String modId, ContentKind kind, String path) {
        return modId + ":axion/" + kind.folder() + "/" + path;
    }

    /** {@return la clé de l'entrée qu'un identifiant désigne : {@code .json} est implicite hors des modèles} */
    static String keyOf(ContentKind kind, String id) {
        int colon = id.indexOf(':');
        if (colon <= 0 || !NAMESPACE.matcher(id.substring(0, colon)).matches()
                || !PATH.matcher(id.substring(colon + 1)).matches()) {
            throw new IllegalArgumentException("identifiant mal formé, attendu <ns>:<chemin> : " + id);
        }
        String path = id.substring(colon + 1);
        return key(id.substring(0, colon), kind, kind == ContentKind.MODEL ? path : path + ".json");
    }

    /** {@return la famille d'un fichier, d'après son premier dossier ; {@code null} hors des familles} */
    private static ContentKind kindOf(String relative) {
        for (ContentKind kind : ContentKind.values()) {
            if (relative.startsWith(kind.folder() + "/")) {
                return kind;
            }
        }
        return null;
    }

    private void add(Entry entry) {
        Map<String, Entry> family = entries.get(entry.kind());
        Entry previous = family.get(entry.key());
        if (previous != null) {
            errors.add("doublon refusé : " + entry.key() + " est déclaré par " + previous.owner() + " — "
                    + previous.source() + ", et de nouveau par " + entry.owner() + " — " + entry.source());
            return;
        }
        family.put(entry.key(), entry);
    }

    private static byte[] read(Path file, long limit) throws IOException {
        try (InputStream stream = Files.newInputStream(file)) {
            if (limit >= Integer.MAX_VALUE - 8) {
                return stream.readAllBytes();
            }
            byte[] bytes = stream.readNBytes((int) limit + 1);
            if (bytes.length > limit) {
                throw new IOException("non lu : plus de " + limit + " octets (assets.max_source_bytes, ADR-139)");
            }
            return bytes;
        }
    }

    /** {@return un chemin relatif à la racine d'un JAR : ni vide, ni absolu, ni remontant} */
    private static String checkedRelative(String relative) {
        Objects.requireNonNull(relative, "relative");
        String normalized = relative.replace('\\', '/');
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.contains(":")
                || List.of(normalized.split("/")).contains("..")) {
            throw new IllegalArgumentException("chemin relatif à la racine du JAR attendu : " + relative);
        }
        return normalized;
    }

    /** L'enregistrement d'un mod, ouvert le temps d'appeler son fournisseur. */
    public final class Session implements ContentRegistrar, AutoCloseable {

        private final String modId;
        private final Function<String, Path> jar;
        private boolean open = true;

        private Session(String modId, Function<String, Path> jar) {
            this.modId = modId;
            this.jar = jar;
        }

        @Override
        public String modId() {
            return modId;
        }

        @Override
        public void registerFolder(String folder) {
            ensureOpen();
            String root = checkedRelative(folder);
            Path base = jar.apply(root);
            if (base == null || !Files.isDirectory(base)) {
                errors.add(modId + " enregistre le dossier « " + root + " », absent de son JAR");
                return;
            }
            List<Path> files;
            try (Stream<Path> walk = Files.walk(base)) {
                files = walk.filter(Files::isRegularFile).sorted().toList();
            } catch (IOException failure) {
                errors.add(modId + " : dossier « " + root + " » illisible — " + failure.getMessage());
                return;
            }
            for (Path file : files) {
                String relative = base.relativize(file).toString().replace('\\', '/');
                ContentKind kind = kindOf(relative);
                if (kind == null) {
                    continue;
                }
                String path = relative.substring(kind.folder().length() + 1);
                if (kind == ContentKind.MODEL ? AssetSiblings.isImage(path) : !path.endsWith(".json")) {
                    continue;
                }
                String source = modId + " — " + root + "/" + relative;
                if (!PATH.matcher(path).matches()) {
                    errors.add("fichier ignoré : " + source + " (un chemin de Minecraft n'a que [a-z0-9_./-])");
                    continue;
                }
                long limit = kind == ContentKind.MODEL && AssetSiblings.isCandidate(path)
                        ? maxSiblingBytes : Long.MAX_VALUE;
                try {
                    add(new Entry(kind, key(modId, kind, path), modId, source, read(file, limit)));
                } catch (IOException failure) {
                    errors.add("fichier illisible : " + source + " — " + failure.getMessage());
                }
            }
        }

        @Override
        public void replace(ContentKind kind, String target, String file) {
            ensureOpen();
            Objects.requireNonNull(kind, "kind");
            String key = keyOf(kind, Objects.requireNonNull(target, "target"));
            if (key.startsWith(modId + ":")) {
                throw new IllegalArgumentException(modId + " ne remplace pas sa propre entrée " + target
                        + " : il la déclare directement");
            }
            String relative = checkedRelative(Objects.requireNonNull(file, "file"));
            Path path = jar.apply(relative);
            String source = modId + " — " + relative;
            if (path == null || !Files.isRegularFile(path)) {
                errors.add("remplacement refusé : " + source + ", qui doit remplacer " + target
                        + ", est absent du JAR");
                return;
            }
            try {
                replacements.add(new Replacement(kind, key, modId, source, read(path, Long.MAX_VALUE)));
            } catch (IOException failure) {
                errors.add("fichier illisible : " + source + " — " + failure.getMessage());
            }
        }

        @Override
        public void register(String name, DefinitionDocument definition) {
            ensureOpen();
            String path = checkedName(name) + ".json";
            Objects.requireNonNull(definition, "definition");
            String source = modId + " — builder, register(\"" + name + "\")";
            add(new Entry(ContentKind.DEFINITION, key(modId, ContentKind.DEFINITION, path), modId, source,
                    canonicalBytes(definition)));
        }

        @Override
        public void replace(String target, DefinitionDocument definition) {
            ensureOpen();
            Objects.requireNonNull(definition, "definition");
            String key = keyOf(ContentKind.DEFINITION, Objects.requireNonNull(target, "target"));
            if (key.startsWith(modId + ":")) {
                throw new IllegalArgumentException(modId + " ne remplace pas sa propre entrée " + target
                        + " : il la déclare directement");
            }
            replacements.add(new Replacement(ContentKind.DEFINITION, key, modId,
                    modId + " — builder, replace(\"" + target + "\")", canonicalBytes(definition)));
        }

        @Override
        public void registerController(String name, dev.axion.api.vehicle.VehicleController controller) {
            ensureOpen();
            Objects.requireNonNull(controller, "controller");
            String id = modId + ":" + checkedName(name);
            if (controllers.containsKey(id)) {
                errors.add("doublon refusé : le contrôleur " + id + " est déjà enregistré par " + modId);
                return;
            }
            controllers.put(id, new dev.axion.definition.VehicleExtensions.Controller(id, modId, controller));
        }

        @Override
        public void registerParameter(String name, String unit, double base,
                dev.axion.api.assembly.Parameters.Range limit) {
            ensureOpen();
            String id = modId + ":" + checkedName(name);
            dev.axion.definition.VehicleExtensions.Parameter parameter = new dev.axion.definition.VehicleExtensions.Parameter(
                    id, modId, Objects.requireNonNull(unit, "unit"), base, Objects.requireNonNull(limit, "limit"));
            if (parameters.containsKey(id)) {
                errors.add("doublon refusé : le paramètre " + id + " est déjà enregistré par " + modId);
                return;
            }
            parameters.put(id, parameter);
        }

        @Override
        public void close() {
            open = false;
        }

        private void ensureOpen() {
            if (!open) {
                throw new IllegalStateException("enregistrement fermé : un ContentRegistrar n'est valable que "
                        + "pendant l'appel de son fournisseur");
            }
        }
    }
}
