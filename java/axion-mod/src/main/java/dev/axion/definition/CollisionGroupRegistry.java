package dev.axion.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.axion.asset.AssetSource;
import dev.axion.physics.CollisionFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Registre des groupes de collision tiers, {@code axion:collision_group} (§23.3, R-980, ADR-132
 * amendement A1).
 *
 * <p>Un fichier {@code data/<ns>/axion/collision_groups/<chemin>.json} déclare le groupe
 * {@code <ns>:<chemin>}. Son JSON est un objet qui porte au plus {@code collides_with}, liste de
 * noms de groupes — réservés ou tiers : le masque <strong>par défaut</strong> du groupe, que prend
 * une definition qui déclare ce {@code collision_group} sans {@code collides_with}. Sans lui, le
 * groupe heurte tout.
 *
 * <p>Les groupes tiers, triés par identifiant, reçoivent les bits 8 à 31 dans cet ordre : un même
 * ensemble de packs donne les mêmes bits. Au-delà de {@link #MAX_GROUPS}, chaque groupe en trop
 * est refusé et nommé, comme une definition fautive (R-580) ; un groupe dont le masque nomme un
 * groupe inconnu aussi. Les noms se persistent, les bits jamais : Java les résout à chaque
 * création de corps, dans la charge qu'il envoie au natif.
 *
 * <p>Construit d'un bloc à chaque rechargement de données, avant les definitions qui le
 * consultent, puis figé.
 */
public final class CollisionGroupRegistry {

    /** Préfixe des groupes, relatif à la racine du gestionnaire de données. */
    public static final String PREFIX = "axion/collision_groups/";

    /** Extension des groupes. */
    public static final String EXTENSION = ".json";

    /** Premier bit d'un groupe tiers : les précédents sont aux groupes réservés. */
    static final int FIRST_BIT = CollisionFilter.RESERVED.size();

    /** Groupes tiers au plus : un par bit de 8 à 31. */
    public static final int MAX_GROUPS = Integer.SIZE - FIRST_BIT;

    private static final String COLLIDES_WITH = "collides_with";

    private static final CollisionGroupRegistry EMPTY =
            new CollisionGroupRegistry(new TreeMap<>(), Map.of(), List.of());

    /** Identifiant → index de bit, dans l'ordre des identifiants. */
    private final SortedMap<String, Integer> bits;
    /** Masques par défaut des groupes qui en déclarent un. */
    private final Map<String, Integer> defaultMasks;
    private final List<String> refusals;
    private final String fingerprint;

    private CollisionGroupRegistry(
            SortedMap<String, Integer> bits, Map<String, Integer> defaultMasks, List<String> refusals) {
        this.bits = bits;
        this.defaultMasks = defaultMasks;
        this.refusals = List.copyOf(refusals);
        this.fingerprint = fingerprintOf(bits, defaultMasks);
    }

    /** {@return un registre sans groupe tiers : les réservés seuls} */
    public static CollisionGroupRegistry empty() {
        return EMPTY;
    }

    /** Un fichier lu : son groupe, et les noms de son masque par défaut s'il en déclare un. */
    private record Candidate(String id, String location, List<String> collidesWith) {}

    /**
     * Charge les groupes d'une source.
     *
     * @param source ressources sous {@link #PREFIX}
     * @return le registre, groupes refusés exclus
     */
    public static CollisionGroupRegistry load(AssetSource source) {
        List<String> refusals = new ArrayList<>();
        SortedMap<String, Candidate> candidates = new TreeMap<>();
        for (String location : source.list()) {
            try {
                Candidate candidate = read(location, source.read(location));
                candidates.put(candidate.id(), candidate);
            } catch (DefinitionException refusal) {
                refusals.add(refusal.codeName() + " " + location + " — " + refusal.path()
                        + " : " + refusal.getMessage());
            } catch (IOException failure) {
                refusals.add("E-7001 " + location + " — illisible : " + failure.getMessage());
            }
        }

        // Un masque qui nomme un groupe refusé ou absent se refuse, ce qui peut en priver un autre :
        // jusqu'au point fixe. Le plafond ensuite, sur les seuls groupes valides, puis le point fixe
        // de nouveau pour ceux qui nommaient un groupe en trop.
        prune(candidates, refusals);
        int kept = 0;
        for (Candidate candidate : List.copyOf(candidates.values())) {
            if (++kept > MAX_GROUPS) {
                candidates.remove(candidate.id());
                refusals.add("E-7001 " + candidate.location() + " — $ : plus de " + MAX_GROUPS
                        + " groupes de collision tiers (bits 8 à 31) ; « " + candidate.id()
                        + " » vient après les " + MAX_GROUPS + " premiers dans l'ordre des"
                        + " identifiants");
            }
        }
        prune(candidates, refusals);

        SortedMap<String, Integer> bits = new TreeMap<>();
        for (String id : candidates.keySet()) {
            bits.put(id, FIRST_BIT + bits.size());
        }
        Map<String, Integer> defaultMasks = new LinkedHashMap<>();
        for (Candidate candidate : candidates.values()) {
            if (candidate.collidesWith() != null) {
                int mask = 0;
                for (String name : candidate.collidesWith()) {
                    mask |= bitOf(name, bits);
                }
                defaultMasks.put(candidate.id(), mask);
            }
        }
        return new CollisionGroupRegistry(bits, defaultMasks, refusals);
    }

    /** Retire, jusqu'au point fixe, tout groupe dont le masque nomme un groupe qui n'est pas là. */
    private static void prune(SortedMap<String, Candidate> candidates, List<String> refusals) {
        boolean removed = true;
        while (removed) {
            removed = false;
            for (Candidate candidate : List.copyOf(candidates.values())) {
                if (candidate.collidesWith() == null) {
                    continue;
                }
                List<String> names = candidate.collidesWith();
                for (int index = 0; index < names.size(); index++) {
                    String name = names.get(index);
                    if (!CollisionFilter.RESERVED.contains(name) && !candidates.containsKey(name)) {
                        candidates.remove(candidate.id());
                        refusals.add("E-7001 " + candidate.location() + " — $." + COLLIDES_WITH
                                + "[" + index + "] : groupe de collision « " + name
                                + " » inconnu ; aucun data/<ns>/" + PREFIX + " ne le déclare");
                        removed = true;
                        break;
                    }
                }
            }
        }
    }

    /**
     * Lit un fichier de groupe.
     *
     * @param location ressource, {@code <ns>:axion/collision_groups/<chemin>.json}
     * @param content contenu du fichier
     * @throws DefinitionException au premier refus
     */
    private static Candidate read(String location, byte[] content) throws DefinitionException {
        String id = idOf(location);
        JsonElement parsed = StrictJson.parse(content);
        if (!parsed.isJsonObject()) {
            throw DefinitionException.invalid("$", "un groupe de collision est un objet JSON");
        }
        JsonObject root = parsed.getAsJsonObject();
        for (String key : root.keySet()) {
            if (!key.equals(COLLIDES_WITH)) {
                throw DefinitionException.invalid(StrictJson.member("$", key),
                        "champ inconnu ; seul " + COLLIDES_WITH + " est admis");
            }
        }
        JsonElement declared = root.get(COLLIDES_WITH);
        if (declared == null) {
            return new Candidate(id, location, null);
        }
        String path = StrictJson.member("$", COLLIDES_WITH);
        if (!(declared instanceof JsonArray array)) {
            throw DefinitionException.invalid(path, "attendu une liste de groupes");
        }
        List<String> names = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            String at = path + "[" + index + "]";
            if (!(array.get(index) instanceof JsonPrimitive primitive) || !primitive.isString()) {
                throw DefinitionException.invalid(at, "attendu un nom de groupe");
            }
            String name = primitive.getAsString();
            if (!CollisionFilter.RESERVED.contains(name) && !DefinitionRegistry.isId(name)) {
                throw DefinitionException.invalid(at, notAGroup(name));
            }
            names.add(name);
        }
        return new Candidate(id, location, List.copyOf(names));
    }

    /**
     * {@return l'identifiant d'un groupe}
     *
     * <p>{@code mymod:axion/collision_groups/vehicules/fantome.json} donne
     * {@code mymod:vehicules/fantome}.
     */
    private static String idOf(String location) throws DefinitionException {
        int colon = location.indexOf(':');
        String path = colon < 0 ? "" : location.substring(colon + 1);
        if (!path.startsWith(PREFIX)
                || !path.endsWith(EXTENSION)
                || path.length() <= PREFIX.length() + EXTENSION.length()) {
            throw DefinitionException.invalid(
                    "$", "ressource hors de <ns>:" + PREFIX + "<nom>" + EXTENSION);
        }
        String id = location.substring(0, colon) + ":"
                + path.substring(PREFIX.length(), path.length() - EXTENSION.length());
        if (!DefinitionRegistry.isId(id)) {
            throw DefinitionException.invalid("$", "« " + id + " » n'est pas un identifiant <ns>:<chemin>");
        }
        return id;
    }

    /** {@return pourquoi un nom n'a pas la forme d'un groupe} */
    static String notAGroup(String name) {
        return "« " + name + " » n'est ni un groupe réservé (" + String.join(", ", CollisionFilter.RESERVED)
                + ") ni un identifiant <ns>:<chemin>";
    }

    /** {@return vrai si le nom désigne un groupe réservé ou un groupe de ce registre} */
    public boolean knows(String name) {
        return CollisionFilter.RESERVED.contains(name) || bits.containsKey(name);
    }

    /**
     * {@return le bit d'un groupe, en masque}
     *
     * @throws IllegalArgumentException si le groupe n'est ni réservé ni dans ce registre
     */
    public int bit(String name) {
        return bitOf(name, bits);
    }

    private static int bitOf(String name, Map<String, Integer> bits) {
        if (CollisionFilter.RESERVED.contains(name)) {
            return CollisionFilter.bit(name);
        }
        Integer index = bits.get(name);
        if (index == null) {
            throw new IllegalArgumentException("groupe de collision inconnu : " + name);
        }
        return 1 << index;
    }

    /** {@return le masque par défaut d'un groupe, vide s'il n'en déclare pas} */
    public OptionalInt defaultMask(String group) {
        Integer mask = defaultMasks.get(group);
        return mask == null ? OptionalInt.empty() : OptionalInt.of(mask);
    }

    /**
     * {@return le groupe dont le corps est membre, en masque} {@code assembly} sans déclaration.
     *
     * @param definition racine JSON d'une definition validée contre ce registre
     */
    public int membership(JsonObject definition) {
        JsonElement declared = physics(definition).get("collision_group");
        return declared == null ? bit("assembly") : bit(declared.getAsString());
    }

    /**
     * {@return les groupes que le corps heurte, en masque} Ceux que la definition déclare ; sans
     * déclaration, le masque par défaut de son groupe, sinon tous. Une liste vide ne heurte rien.
     *
     * @param definition racine JSON d'une definition validée contre ce registre
     */
    public int filter(JsonObject definition) {
        JsonObject physics = physics(definition);
        JsonElement declared = physics.get(COLLIDES_WITH);
        if (declared != null && declared.isJsonArray()) {
            int mask = 0;
            for (JsonElement group : declared.getAsJsonArray()) {
                mask |= bit(group.getAsString());
            }
            return mask;
        }
        JsonElement group = physics.get("collision_group");
        return group == null ? CollisionFilter.ALL : defaultMask(group.getAsString()).orElse(CollisionFilter.ALL);
    }

    private static JsonObject physics(JsonObject definition) {
        JsonElement block = definition.get("physics");
        return block != null && block.isJsonObject() ? block.getAsJsonObject() : new JsonObject();
    }

    /** {@return les groupes tiers, dans l'ordre de leurs bits} */
    public List<String> ids() {
        return List.copyOf(bits.keySet());
    }

    /** {@return les refus du chargement, chacun nommé et localisé} */
    public List<String> refusals() {
        return refusals;
    }

    /**
     * {@return l'empreinte du registre : groupes, bits et masques par défaut} Deux chargements
     * d'empreinte égale résolvent tout nom de la même façon.
     */
    public String fingerprint() {
        return fingerprint;
    }

    private static String fingerprintOf(SortedMap<String, Integer> bits, Map<String, Integer> masks) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 fait partie des algorithmes que toute JVM doit fournir.
            throw new IllegalStateException(impossible);
        }
        for (Map.Entry<String, Integer> entry : bits.entrySet()) {
            Integer mask = masks.get(entry.getKey());
            String line = entry.getKey() + "=" + entry.getValue() + ":" + (mask == null ? "-" : mask) + "\n";
            digest.update(line.getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
