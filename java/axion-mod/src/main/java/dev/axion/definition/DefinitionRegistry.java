package dev.axion.definition;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.axion.asset.AssetSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.ToLongFunction;
import java.util.regex.Pattern;

/**
 * Registry des definitions (C-27).
 *
 * <p>Construite d'un bloc à chaque rechargement de données, puis figée : une
 * registry qui changerait sous les pieds d'une entité en cours d'apparition
 * donnerait une entité à moitié décrite.
 *
 * <p>Une definition invalide est refusée <strong>individuellement</strong>
 * (R-580) : les autres se chargent, et chaque refus est nommé, localisé et
 * rendu dans {@link #refusals()}.
 */
public final class DefinitionRegistry {

    /** Préfixe des definitions, relatif à la racine du gestionnaire de données. */
    public static final String PREFIX = "axion/definitions/";

    /** Extension des definitions. */
    public static final String EXTENSION = ".json";

    /** Seule version de schéma lue (R-1780). */
    public static final int SCHEMA = 1;

    /** Espace de noms d'une {@code ResourceLocation}. */
    static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");

    /** Chemin d'une {@code ResourceLocation}. */
    static final Pattern RESOURCE_PATH = Pattern.compile("[a-z0-9/._-]+");

    private final SortedMap<String, Definition> definitions;
    private final Map<Long, Definition> byHash;
    private final List<String> refusals;
    private final String fingerprint;

    private DefinitionRegistry(
            SortedMap<String, Definition> definitions,
            Map<Long, Definition> byHash,
            List<String> refusals) {
        this.definitions = definitions;
        this.byHash = byHash;
        this.refusals = List.copyOf(refusals);
        this.fingerprint = fingerprintOf(definitions);
    }

    /** {@return une registry vide, avant tout rechargement} */
    public static DefinitionRegistry empty() {
        return new DefinitionRegistry(new TreeMap<>(), new HashMap<>(), List.of());
    }

    /**
     * Charge les definitions d'une source.
     *
     * @param source ressources sous {@link #PREFIX}
     * @param rules ce que la validation consulte hors du document
     * @return la registry, definitions refusées exclues
     */
    public static DefinitionRegistry load(AssetSource source, DefinitionRules rules) {
        return load(source, rules, DefinitionIds::hash);
    }

    /**
     * Charge les definitions avec une empreinte donnée.
     *
     * <p>Deux identifiants distincts de même empreinte FNV-1a 64 bits sont
     * rarissimes, et introuvables pour un test ; celui-ci fournit la sienne.
     *
     * @param source ressources sous {@link #PREFIX}
     * @param rules ce que la validation consulte hors du document
     * @param hasher empreinte d'un identifiant
     * @return la registry, definitions refusées exclues
     */
    static DefinitionRegistry load(
            AssetSource source, DefinitionRules rules, ToLongFunction<String> hasher) {
        SortedMap<String, Definition> accepted = new TreeMap<>();
        List<String> refusals = new ArrayList<>();

        for (String location : source.list()) {
            try {
                Definition definition = read(location, source.read(location), rules);
                accepted.put(definition.id(), definition);
            } catch (DefinitionException refusal) {
                refusals.add(refusal.codeName() + " " + location + " — " + refusal.path()
                        + " : " + refusal.getMessage());
            } catch (IOException failure) {
                refusals.add("E-7001 " + location + " — illisible : " + failure.getMessage());
            }
        }

        // E-3010 : deux identifiants de même empreinte. Le NBT d'une assembly ne
        // porte que l'empreinte (axion:def) ; garder l'une des deux ferait
        // recharger une entité avec la definition de l'autre. Les deux sont
        // refusées, et l'auteur renomme.
        Map<Long, String> first = new HashMap<>();
        Set<String> colliding = new TreeSet<>();
        for (Definition definition : accepted.values()) {
            String other = first.putIfAbsent(hasher.applyAsLong(definition.id()), definition.id());
            if (other != null) {
                colliding.add(other);
                colliding.add(definition.id());
            }
        }
        for (String id : colliding) {
            Definition definition = accepted.remove(id);
            refusals.add("E-3010 " + definition.location() + " — $ : empreinte d'identifiant "
                    + "partagée avec une autre definition ; renommer l'une des deux");
        }

        Map<Long, Definition> byHash = new HashMap<>();
        for (Definition definition : accepted.values()) {
            byHash.put(hasher.applyAsLong(definition.id()), definition);
        }
        return new DefinitionRegistry(accepted, byHash, refusals);
    }

    /**
     * Lit et valide une definition.
     *
     * @param location ressource, {@code <ns>:axion/definitions/<chemin>.json}
     * @param content contenu du fichier
     * @param rules ce que la validation consulte hors du document
     * @return la definition acceptée
     * @throws DefinitionException au premier refus
     */
    static Definition read(String location, byte[] content, DefinitionRules rules)
            throws DefinitionException {
        String id = idOf(location);
        JsonElement parsed = StrictJson.parse(content);
        if (!parsed.isJsonObject()) {
            throw DefinitionException.invalid("$", "une definition est un objet JSON");
        }
        JsonObject root = parsed.getAsJsonObject();

        int schema = schemaOf(root);
        String asset = assetOf(root, rules);
        AssemblyKind kind = kindOf(root, rules);

        // Étapes 2 et 3 : structure du schéma 1 et références internes.
        List<DefinitionChecker.Issue> issues = DefinitionChecker.check(root);
        if (!issues.isEmpty()) {
            throw DefinitionException.fromIssues(issues);
        }

        return new Definition(id, location, schema, asset, kind, root, sha256(content));
    }

    /**
     * {@return l'identifiant d'une definition}
     *
     * <p>{@code mymod:axion/definitions/vehicles/pickup.json} donne
     * {@code mymod:vehicles/pickup}.
     *
     * @param location ressource d'origine
     * @throws DefinitionException si la ressource n'est pas une definition
     */
    static String idOf(String location) throws DefinitionException {
        int colon = location.indexOf(':');
        String namespace = colon < 0 ? "" : location.substring(0, colon);
        String path = colon < 0 ? "" : location.substring(colon + 1);
        if (!path.startsWith(PREFIX)
                || !path.endsWith(EXTENSION)
                || path.length() <= PREFIX.length() + EXTENSION.length()) {
            throw DefinitionException.invalid(
                    "$", "ressource hors de <ns>:" + PREFIX + "<nom>" + EXTENSION);
        }
        return namespace + ":" + path.substring(PREFIX.length(), path.length() - EXTENSION.length());
    }

    /** R-1780 : {@code schema} obligatoire, entier, et connu. */
    private static int schemaOf(JsonObject root) throws DefinitionException {
        JsonElement value = root.get("schema");
        if (value == null) {
            throw DefinitionException.invalid("$.schema", "champ obligatoire");
        }
        BigDecimal number = integer(value);
        if (number == null) {
            throw DefinitionException.invalid("$.schema", "attendu un entier");
        }
        if (number.compareTo(BigDecimal.valueOf(SCHEMA)) != 0) {
            throw DefinitionException.unknownSchema(
                    "$.schema", "version " + number.toPlainString() + " inconnue, seule "
                            + SCHEMA + " est lue");
        }
        return SCHEMA;
    }

    /**
     * {@code asset} : {@code <ns>:<chemin>}, qui désigne
     * {@code <ns>:axion/<chemin>} (ADR-109, point 3).
     */
    private static String assetOf(JsonObject root, DefinitionRules rules)
            throws DefinitionException {
        String value = string(root, "asset");
        int colon = value.indexOf(':');
        if (colon <= 0) {
            // Pas d'espace de noms implicite : `models/x.glb` ne dirait pas si
            // l'auteur pensait au sien ou à celui de Minecraft.
            throw DefinitionException.invalid(
                    "$.asset", "attendu <ns>:<chemin>, espace de noms compris");
        }
        String namespace = value.substring(0, colon);
        String path = value.substring(colon + 1);
        if (!NAMESPACE.matcher(namespace).matches() || !RESOURCE_PATH.matcher(path).matches()) {
            throw DefinitionException.invalid(
                    "$.asset", "caractères admis : [a-z0-9_.-] pour l'espace de noms, "
                            + "[a-z0-9/._-] pour le chemin");
        }
        if (path.startsWith("axion/")) {
            throw DefinitionException.invalid(
                    "$.asset", "le chemin est relatif à axion/ : écrire " + namespace + ":"
                            + path.substring("axion/".length()));
        }
        String resolved = namespace + ":axion/" + path;
        if (!rules.assetExists().test(resolved)) {
            throw DefinitionException.invalid("$.asset", "modèle introuvable : " + resolved);
        }
        return resolved;
    }

    /** {@code kind} : genre connu, et module actif pour un véhicule (§24.1). */
    private static AssemblyKind kindOf(JsonObject root, DefinitionRules rules)
            throws DefinitionException {
        String value = string(root, "kind");
        AssemblyKind kind = AssemblyKind.fromJson(value)
                .orElseThrow(() -> DefinitionException.invalid(
                        "$.kind", "genre « " + value + " » inconnu ; admis : "
                                + AssemblyKind.allowed()));
        if (kind == AssemblyKind.VEHICLE && !rules.vehiclesEnabled()) {
            throw DefinitionException.invalid(
                    "$.kind", "le module vehicles est désactivé (modules.vehicles)");
        }
        return kind;
    }

    /** {@return la chaîne d'un champ obligatoire} */
    private static String string(JsonObject root, String key) throws DefinitionException {
        String path = StrictJson.member("$", key);
        JsonElement value = root.get(key);
        if (value == null) {
            throw DefinitionException.invalid(path, "champ obligatoire");
        }
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isString()) {
            throw DefinitionException.invalid(path, "attendu une chaîne");
        }
        return primitive.getAsString();
    }

    /** {@return la valeur entière d'un élément, ou {@code null}} */
    private static BigDecimal integer(JsonElement value) {
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            return null;
        }
        BigDecimal number = primitive.getAsBigDecimal();
        return number.stripTrailingZeros().scale() <= 0 ? number : null;
    }

    private static String sha256(byte[] content) {
        return HexFormat.of().formatHex(digest().digest(content));
    }

    /**
     * Empreinte de la registry : identifiants et empreintes de fichiers, dans
     * l'ordre des identifiants.
     *
     * <p>C'est elle que le serveur comparera au login (R-1640) : deux registries
     * de même contenu ont la même empreinte, quel que soit l'ordre dans lequel
     * les packs ont été énumérés.
     */
    private static String fingerprintOf(SortedMap<String, Definition> definitions) {
        MessageDigest digest = digest();
        for (Map.Entry<String, Definition> entry : definitions.entrySet()) {
            digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(entry.getValue().sha256().getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 fait partie des algorithmes que toute JVM doit fournir.
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * {@return une definition, ou vide}
     *
     * @param id identifiant
     */
    public Optional<Definition> get(String id) {
        return Optional.ofNullable(definitions.get(id));
    }

    /**
     * {@return la definition d'un identifiant 64 bits, ou vide}
     *
     * @param hash empreinte, telle que {@code axion:def} la porte
     */
    public Optional<Definition> byHash(long hash) {
        return Optional.ofNullable(byHash.get(hash));
    }

    /** {@return les identifiants, triés} */
    public List<String> ids() {
        return List.copyOf(definitions.keySet());
    }

    /** {@return le nombre de definitions acceptées} */
    public int size() {
        return definitions.size();
    }

    /** {@return les refus, un par definition refusée} */
    public List<String> refusals() {
        return refusals;
    }

    /** {@return l'empreinte de la registry, en hexadécimal} */
    public String fingerprint() {
        return fingerprint;
    }
}
