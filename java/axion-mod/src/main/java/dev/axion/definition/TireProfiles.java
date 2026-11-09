package dev.axion.definition;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.axion.asset.AssetSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Registre des profils de pneus, {@code axion:tire} (12.4, ADR-159 Q1).
 *
 * <p>Un fichier {@code data/<ns>/axion/tires/<chemin>.json} déclare le pneu {@code <ns>:tire/<chemin>},
 * l'identifiant que la definition nomme ({@code "tire": "mymod:tire/road"}). Il porte {@code schema} 1,
 * {@code mu_long}, {@code mu_lat} et les deux courbes {@code long} et {@code lat}, chacune de quatre
 * points : aucun champ n'a de valeur implicite. Une faute refuse le pneu, nommé, sans toucher aux autres.
 *
 * <p>Comme un matériau physique (R-1032), un pneu non déclaré n'est pas un refus de la definition : elle
 * est avertie et prend {@link #GENERIC}. Le moteur ne connaît aucun nom de pneu.
 */
public final class TireProfiles {

    /** Préfixe des pneus, relatif à la racine du gestionnaire de données. */
    public static final String PREFIX = "axion/tires/";

    /** Extension des pneus. */
    public static final String EXTENSION = ".json";

    /** Seule version de format lue. */
    public static final int SCHEMA = 1;

    private static final Set<String> KEYS = Set.of("schema", "mu_long", "mu_lat", "long", "lat");
    private static final Set<String> CURVE_KEYS = Set.of("peak_slip", "peak_value", "tail_slip", "tail_value");

    /**
     * Une courbe de glissement (12.4).
     *
     * @param peakSlip glissement du pic
     * @param peakValue adhérence au pic
     * @param tailSlip glissement du début de la queue
     * @param tailValue adhérence de la queue
     */
    public record Curve(float peakSlip, float peakValue, float tailSlip, float tailValue) {}

    /**
     * Un pneu.
     *
     * @param id son identifiant, {@code null} pour le pneu générique
     * @param muLong coefficient longitudinal
     * @param muLat coefficient latéral
     * @param longitudinal courbe longitudinale
     * @param lateral courbe latérale
     */
    public record Tire(String id, float muLong, float muLat, Curve longitudinal, Curve lateral) {}

    /** Le pneu d'une roue qui n'en nomme pas, ou n'en nomme aucun de déclaré : choix d'ADR-159, un pneu routier. */
    public static final Tire GENERIC = new Tire(null, 1.0f, 1.0f,
            new Curve(0.12f, 1.0f, 0.5f, 0.75f),
            new Curve(0.15f, 1.0f, 0.6f, 0.8f));

    private static final TireProfiles EMPTY = new TireProfiles(new TreeMap<>(), List.of());

    private final TreeMap<String, Tire> tires;
    private final List<String> refusals;

    private TireProfiles(TreeMap<String, Tire> tires, List<String> refusals) {
        this.tires = tires;
        this.refusals = List.copyOf(refusals);
    }

    /** {@return un registre vide, avant tout rechargement} */
    public static TireProfiles empty() {
        return EMPTY;
    }

    /**
     * Charge les pneus d'une source.
     *
     * @param source ressources sous {@link #PREFIX}
     * @return le registre
     */
    public static TireProfiles load(AssetSource source) {
        TreeMap<String, Tire> tires = new TreeMap<>();
        List<String> refusals = new ArrayList<>();
        for (String location : source.list()) {
            try {
                String id = idOf(location);
                tires.put(id, tire(id, source.read(location)));
            } catch (DefinitionException refusal) {
                refusals.add(refusal.codeName() + " " + location + " — " + refusal.path()
                        + " : " + refusal.getMessage());
            } catch (IOException failure) {
                refusals.add("E-7001 " + location + " — illisible : " + failure.getMessage());
            }
        }
        return new TireProfiles(tires, refusals);
    }

    /**
     * {@return vrai si ce pneu est déclaré}
     *
     * @param id identifiant
     */
    public boolean knows(String id) {
        return tires.containsKey(id);
    }

    /**
     * {@return le pneu d'un identifiant, {@link #GENERIC} s'il n'est pas déclaré ou vaut {@code null}}
     *
     * @param id identifiant, ou {@code null}
     */
    public Tire resolve(String id) {
        return id == null ? GENERIC : tires.getOrDefault(id, GENERIC);
    }

    /** {@return les identifiants, triés} */
    public Set<String> ids() {
        return java.util.Collections.unmodifiableSet(tires.keySet());
    }

    /** {@return les fichiers refusés, nommés} */
    public List<String> refusals() {
        return refusals;
    }

    private static Tire tire(String id, byte[] content) throws DefinitionException {
        JsonElement parsed = StrictJson.parse(content);
        if (!parsed.isJsonObject()) {
            throw DefinitionException.invalid("$", "un pneu est un objet JSON");
        }
        JsonObject root = parsed.getAsJsonObject();
        known(root, "$", KEYS);
        JsonElement schema = root.get("schema");
        if (schema == null) {
            throw DefinitionException.invalid("$.schema", "champ obligatoire");
        }
        if (!(schema instanceof JsonPrimitive primitive) || !primitive.isNumber()
                || primitive.getAsBigDecimal().compareTo(BigDecimal.valueOf(SCHEMA)) != 0) {
            throw DefinitionException.unknownSchema("$.schema", "version " + schema
                    + " inconnue, seule " + SCHEMA + " est lue");
        }
        return new Tire(id,
                positive(root, "$", "mu_long"),
                positive(root, "$", "mu_lat"),
                curve(root, "long"),
                curve(root, "lat"));
    }

    private static Curve curve(JsonObject root, String name) throws DefinitionException {
        String at = "$." + name;
        if (!(root.get(name) instanceof JsonObject curve)) {
            throw DefinitionException.invalid(at, "courbe obligatoire : peak_slip, peak_value, tail_slip, tail_value");
        }
        known(curve, at, CURVE_KEYS);
        float peakSlip = positive(curve, at, "peak_slip");
        float peakValue = positive(curve, at, "peak_value");
        float tailSlip = positive(curve, at, "tail_slip");
        float tailValue = value(curve, at, "tail_value");
        if (tailValue < 0f) {
            throw DefinitionException.invalid(at + ".tail_value", "négatif");
        }
        if (tailSlip <= peakSlip) {
            throw DefinitionException.invalid(at + ".tail_slip", "doit dépasser peak_slip");
        }
        return new Curve(peakSlip, peakValue, tailSlip, tailValue);
    }

    private static void known(JsonObject object, String at, Set<String> keys) throws DefinitionException {
        for (Map.Entry<String, JsonElement> member : object.entrySet()) {
            if (!keys.contains(member.getKey())) {
                throw DefinitionException.invalid(StrictJson.member(at, member.getKey()),
                        "champ inconnu ; admis : " + String.join(", ", new java.util.TreeSet<>(keys)));
            }
        }
    }

    private static float positive(JsonObject object, String at, String key) throws DefinitionException {
        float value = value(object, at, key);
        if (value <= 0f) {
            throw DefinitionException.invalid(StrictJson.member(at, key), "doit être strictement positif");
        }
        return value;
    }

    private static float value(JsonObject object, String at, String key) throws DefinitionException {
        String path = StrictJson.member(at, key);
        if (!(object.get(key) instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw DefinitionException.invalid(path, "nombre obligatoire");
        }
        float value = primitive.getAsFloat();
        if (!Float.isFinite(value)) {
            throw DefinitionException.invalid(path, "hors des valeurs représentables");
        }
        return value;
    }

    private static String idOf(String location) throws DefinitionException {
        int colon = location.indexOf(':');
        String path = colon < 0 ? "" : location.substring(colon + 1);
        if (!path.startsWith(PREFIX)
                || !path.endsWith(EXTENSION)
                || path.length() <= PREFIX.length() + EXTENSION.length()) {
            throw DefinitionException.invalid("$", "ressource hors de <ns>:" + PREFIX + "<nom>" + EXTENSION);
        }
        return location.substring(0, colon) + ":tire/"
                + path.substring(PREFIX.length(), path.length() - EXTENSION.length());
    }
}
