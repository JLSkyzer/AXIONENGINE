package dev.axion.definition;

import com.google.gson.JsonArray;
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
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Registre des matériaux physiques, {@code axion:physics_material} (DM-07, PARTIE 11, R-171,
 * ADR-145).
 *
 * <p>Un fichier {@code data/<ns>/axion/physics_materials/<chemin>.json} déclare le matériau
 * {@code <ns>:<chemin>}. Ses clés sont les noms des champs de DM-07, tous facultatifs, plus
 * {@code parent} : un champ absent se prend à l'ancêtre le plus proche qui le déclare (R-1031), et
 * ce qu'aucun ancêtre ne déclare prend la valeur de l'acier générique du moteur
 * ({@link GenericSteel}) — celle que R-1032 donne aussi à un matériau non déclaré. Le moteur ne
 * connaît aucun nom de matériau (R-1030) : la bibliothèque de base est un datapack comme un autre.
 *
 * <p>Chaque champ est jugé contre son unité et sa plage (§11.5, T-840b) : une valeur hors plage
 * refuse le matériau, nommé, jamais bornée en silence (R-580). Un parent inconnu ou un cycle
 * refusent le matériau et ceux qui en héritent.
 *
 * <p>Vérification dimensionnelle (R-1035) : la seule formule de ce module est la relation de Tabor
 * de R-1038, {@code hardness_pressure = 3 × yield_strength} — une pression multipliée par un nombre
 * sans dimension reste une pression (Pa).
 *
 * <p>Construit d'un bloc à chaque rechargement de données, avant ce qui le consulte, puis figé.
 */
public final class PhysicsMaterials {

    /** Préfixe des matériaux, relatif à la racine du gestionnaire de données. */
    public static final String PREFIX = "axion/physics_materials/";

    /** Extension des matériaux. */
    public static final String EXTENSION = ".json";

    private static final String PARENT = "parent";

    /** Facteur de la relation de Tabor (R-1038), sans dimension. */
    static final double TABOR = 3.0;

    /** Mode de combinaison d'un coefficient de contact entre deux matériaux. */
    public enum Combine {
        /** Moyenne des deux coefficients. */
        AVERAGE,
        /** Le plus petit des deux. */
        MIN,
        /** Le plus grand des deux. */
        MAX,
        /** Leur produit. */
        MULTIPLY;

        /** {@return le nom tel qu'un fichier l'écrit} */
        public String jsonName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Drapeau de comportement d'un matériau (DM-07, R-170). */
    public enum Flag {
        /** Fragile : pas de plasticité, tout dépassement rompt. */
        BRITTLE,
        /** Ductile : la plasticité prime. */
        DUCTILE,
        /** Souple : grandes déformations élastiques. */
        SOFT,
        /** Rigide. */
        RIGID,
        /** Inflammable. */
        FLAMMABLE,
        /** Exclu du système de déformation. */
        NON_DEFORMABLE;

        /** {@return le nom tel qu'un fichier l'écrit} */
        public String jsonName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Nature de la valeur d'un champ. */
    enum Kind {
        NUMBER,
        COMBINE,
        GROUP,
        FLAGS
    }

    /**
     * Les champs de DM-07, dans son ordre, avec leur unité (§11.5) et leur plage. Une borne
     * {@code minExclusive} exige une grandeur strictement positive.
     */
    public enum Field {
        FRICTION("friction", "sans dimension", 0, false, 2),
        RESTITUTION("restitution", "sans dimension", 0, false, 1),
        ROLLING_FRICTION("rolling_friction", "sans dimension", 0, false, Double.POSITIVE_INFINITY),
        FRICTION_COMBINE("friction_combine", Kind.COMBINE),
        RESTITUTION_COMBINE("restitution_combine", Kind.COMBINE),
        DENSITY("density", "kg/m³", 0, true, Double.POSITIVE_INFINITY),
        HARDNESS("hardness", "sans dimension", 0, false, 1),
        STIFFNESS("stiffness", "Pa", 0, true, Double.POSITIVE_INFINITY),
        ELASTICITY("elasticity", "sans dimension", 0, false, 1),
        PLASTICITY("plasticity", "sans dimension", 0, false, 1),
        YIELD_STRENGTH("yield_strength", "Pa", 0, true, Double.POSITIVE_INFINITY),
        FRACTURE_STRENGTH("fracture_strength", "Pa", 0, true, Double.POSITIVE_INFINITY),
        DEFORMATION_RESISTANCE("deformation_resistance", "sans dimension", 0.01, false, 100),
        MAX_STRAIN("max_strain", "sans dimension", 0, false, 1),
        DAMPING("damping", "sans dimension", 0, false, Double.POSITIVE_INFINITY),
        FRACTURE_ENERGY("fracture_energy", "J/m²", 0, true, Double.POSITIVE_INFINITY),
        HARDNESS_PRESSURE("hardness_pressure", "Pa", 1e5, false, 1e11),
        INDENTER_RADIUS_REF("indenter_radius_ref", "m", 0, true, Double.POSITIVE_INFINITY),
        SPREAD_FACTOR("spread_factor", "sans dimension", 0.5, false, 8),
        THICKNESS_SPREAD("thickness_spread", "sans dimension", 0, false, 20),
        DAMAGE_MULTIPLIER("damage_multiplier", "sans dimension", 0, false, Double.POSITIVE_INFINITY),
        ENERGY_ABSORPTION("energy_absorption", "sans dimension", 0, false, 1),
        SCRATCH_RESISTANCE("scratch_resistance", "sans dimension", 0, false, 1),
        BURN_THRESHOLD("burn_threshold", "J", 0, false, Double.POSITIVE_INFINITY),
        SOIL_RATE("soil_rate", "sans dimension", 0, false, Double.POSITIVE_INFINITY),
        SOUND_GROUP("sound_group", Kind.GROUP),
        PARTICLE_GROUP("particle_group", Kind.GROUP),
        FLAGS("flags", Kind.FLAGS);

        private final String jsonName;
        private final Kind kind;
        private final String unit;
        private final double min;
        private final boolean minExclusive;
        private final double max;

        Field(String jsonName, String unit, double min, boolean minExclusive, double max) {
            this.jsonName = jsonName;
            this.kind = Kind.NUMBER;
            this.unit = unit;
            this.min = min;
            this.minExclusive = minExclusive;
            this.max = max;
        }

        Field(String jsonName, Kind kind) {
            this.jsonName = jsonName;
            this.kind = kind;
            this.unit = null;
            this.min = 0;
            this.minExclusive = false;
            this.max = 0;
        }

        /** {@return le nom de la clé dans un fichier de matériau} */
        public String jsonName() {
            return jsonName;
        }

        /** {@return l'unité SI du champ (§11.5), ou {@code null} s'il n'est pas un nombre} */
        public String unit() {
            return unit;
        }

        Kind kind() {
            return kind;
        }

        /** {@return vrai si la valeur tient dans la plage du champ} */
        boolean admits(double value) {
            if (!Double.isFinite(value)) {
                return false;
            }
            boolean aboveMin = minExclusive ? value > min : value >= min;
            return aboveMin && value <= max;
        }

        /** {@return la plage du champ, dite pour un message} */
        String range() {
            String low = (minExclusive ? "]" : "[") + plain(min);
            String high = Double.isInfinite(max) ? "+∞[" : plain(max) + "]";
            return low + ", " + high + (unit == null ? "" : " " + unit);
        }

        static String plain(double value) {
            return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        }

        static Optional<Field> fromJson(String name) {
            for (Field field : values()) {
                if (field.jsonName.equals(name)) {
                    return Optional.of(field);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Un matériau résolu : chaque champ de DM-07 a sa valeur, déclarée, héritée ou par défaut.
     *
     * @param id identifiant ; {@code null} pour l'acier générique du moteur
     * @param values valeur de chaque champ ; un groupe absent n'a pas d'entrée
     * @param notes ce que la résolution a supposé (R-1038), à journaliser une fois
     */
    public record Material(String id, Map<Field, Object> values, List<String> notes) {

        /** Copie figée. */
        public Material {
            values = Collections.unmodifiableMap(new EnumMap<>(values));
            notes = List.copyOf(notes);
        }

        /** {@return la valeur d'un champ numérique} */
        public double number(Field field) {
            if (field.kind() != Kind.NUMBER) {
                throw new IllegalArgumentException(field.jsonName() + " n'est pas un nombre");
            }
            return (Double) values.get(field);
        }

        /** {@return le mode de combinaison d'un coefficient} */
        public Combine combine(Field field) {
            if (field.kind() != Kind.COMBINE) {
                throw new IllegalArgumentException(field.jsonName() + " n'est pas un mode de combinaison");
            }
            return (Combine) values.get(field);
        }

        /** {@return le groupe de son ou de particules, absent s'il n'y en a pas} */
        public Optional<String> group(Field field) {
            if (field.kind() != Kind.GROUP) {
                throw new IllegalArgumentException(field.jsonName() + " n'est pas un groupe");
            }
            return Optional.ofNullable((String) values.get(field));
        }

        /** {@return les drapeaux} */
        @SuppressWarnings("unchecked")
        public Set<Flag> flags() {
            return Collections.unmodifiableSet((Set<Flag>) values.get(Field.FLAGS));
        }

        /** {@return le coefficient de frottement} */
        public double friction() {
            return number(Field.FRICTION);
        }

        /** {@return le coefficient de restitution} */
        public double restitution() {
            return number(Field.RESTITUTION);
        }
    }

    /** Un fichier lu, avant résolution de son héritage. */
    private record Candidate(String id, String location, String parent, Map<Field, Object> declared) {}

    private static final PhysicsMaterials EMPTY = new PhysicsMaterials(new TreeMap<>(), List.of());

    private final SortedMap<String, Material> materials;
    private final List<String> refusals;
    private final String fingerprint;

    private PhysicsMaterials(SortedMap<String, Material> materials, List<String> refusals) {
        this.materials = materials;
        this.refusals = List.copyOf(refusals);
        this.fingerprint = fingerprintOf(materials);
    }

    /** {@return un registre sans matériau déclaré : tout se résout en acier générique} */
    public static PhysicsMaterials empty() {
        return EMPTY;
    }

    /**
     * Charge les matériaux d'une source.
     *
     * @param source ressources sous {@link #PREFIX}
     * @return le registre, matériaux refusés exclus
     */
    public static PhysicsMaterials load(AssetSource source) {
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

        // Héritage au point fixe : un parent absent, refusé ou bouclé fait tomber l'enfant, et
        // ainsi de suite jusqu'à ce que plus rien ne tombe.
        boolean removed = true;
        while (removed) {
            removed = false;
            for (Candidate candidate : List.copyOf(candidates.values())) {
                String failure = chainFailure(candidate, candidates);
                if (failure != null) {
                    candidates.remove(candidate.id());
                    refusals.add("E-7001 " + candidate.location() + " — $.parent : " + failure);
                    removed = true;
                }
            }
        }

        SortedMap<String, Material> resolved = new TreeMap<>();
        for (Candidate candidate : candidates.values()) {
            resolved.put(candidate.id(), resolve(candidate, candidates));
        }
        return new PhysicsMaterials(resolved, refusals);
    }

    /** {@return pourquoi la chaîne d'héritage d'un matériau ne tient pas, ou {@code null}} */
    private static String chainFailure(Candidate candidate, Map<String, Candidate> candidates) {
        Set<String> seen = new HashSet<>();
        seen.add(candidate.id());
        for (String parent = candidate.parent(); parent != null; ) {
            Candidate next = candidates.get(parent);
            if (next == null) {
                return "matériau parent « " + parent + " » inconnu ; aucun data/<ns>/" + PREFIX
                        + " ne le déclare";
            }
            if (!seen.add(parent)) {
                return "héritage circulaire par « " + parent + " »";
            }
            parent = next.parent();
        }
        return null;
    }

    /** Résout chaque champ : déclaré, sinon hérité du plus proche ancêtre, sinon générique. */
    private static Material resolve(Candidate candidate, Map<String, Candidate> candidates) {
        Map<Field, Object> values = new EnumMap<>(Field.class);
        List<String> notes = new ArrayList<>();
        for (Field field : Field.values()) {
            // Une chaîne d'héritage compte au plus autant de maillons que de matériaux : la marche
            // s'arrête là, même si un cycle avait échappé au point fixe de load().
            Candidate at = candidate;
            for (int step = 0; at != null && step <= candidates.size(); step++) {
                Object declared = at.declared().get(field);
                if (declared != null) {
                    values.put(field, declared);
                    break;
                }
                at = at.parent() == null ? null : candidates.get(at.parent());
            }
        }
        completeWithGeneric(values, notes, candidate.id());
        return new Material(candidate.id(), values, notes);
    }

    /**
     * Complète par l'acier générique ce que la chaîne ne déclare pas ; {@code hardness_pressure}
     * absent suit la relation de Tabor (R-1038), bornée à sa plage.
     */
    private static void completeWithGeneric(Map<Field, Object> values, List<String> notes, String id) {
        boolean pressureDeclared = values.containsKey(Field.HARDNESS_PRESSURE);
        for (Map.Entry<Field, Object> generic : GenericSteel.VALUES.entrySet()) {
            values.putIfAbsent(generic.getKey(), generic.getValue());
        }
        values.putIfAbsent(Field.FLAGS, EnumSet.noneOf(Flag.class));
        if (!pressureDeclared) {
            double yield = (Double) values.get(Field.YIELD_STRENGTH);
            double tabor = Math.min(Math.max(TABOR * yield, 1e5), 1e11);
            values.put(Field.HARDNESS_PRESSURE, tabor);
            notes.add((id == null ? "acier générique" : id) + " : hardness_pressure non déclaré, "
                    + "3 × yield_strength = " + Field.plain(tabor) + " Pa (R-1038, relation de Tabor)");
        }
    }

    /** L'acier générique du moteur (R-1032), résolu une fois. */
    private static final Material GENERIC = genericMaterial();

    private static Material genericMaterial() {
        Map<Field, Object> values = new EnumMap<>(Field.class);
        List<String> notes = new ArrayList<>();
        completeWithGeneric(values, notes, null);
        return new Material(null, values, notes);
    }

    /** {@return l'acier générique du moteur : ce que vaut un matériau non déclaré (R-1032)} */
    public static Material genericSteel() {
        return GENERIC;
    }

    /**
     * Lit un fichier de matériau.
     *
     * @param location ressource, {@code <ns>:axion/physics_materials/<chemin>.json}
     * @param content contenu du fichier
     * @throws DefinitionException au premier refus
     */
    private static Candidate read(String location, byte[] content) throws DefinitionException {
        String id = idOf(location);
        JsonElement parsed = StrictJson.parse(content);
        if (!parsed.isJsonObject()) {
            throw DefinitionException.invalid("$", "un matériau physique est un objet JSON");
        }
        JsonObject root = parsed.getAsJsonObject();
        String parent = null;
        Map<Field, Object> declared = new EnumMap<>(Field.class);
        for (Map.Entry<String, JsonElement> member : root.entrySet()) {
            String path = StrictJson.member("$", member.getKey());
            if (member.getKey().equals(PARENT)) {
                parent = text(member.getValue(), path);
                if (!DefinitionRegistry.isId(parent)) {
                    throw DefinitionException.invalid(path, "« " + parent
                            + " » n'est pas un identifiant <ns>:<chemin>");
                }
                if (parent.equals(id)) {
                    throw DefinitionException.invalid(path, "un matériau n'hérite pas de lui-même");
                }
                continue;
            }
            Field field = Field.fromJson(member.getKey()).orElseThrow(() -> DefinitionException.invalid(
                    path, "champ inconnu ; admis : parent et les champs de DM-07"));
            declared.put(field, value(field, member.getValue(), path));
        }
        return new Candidate(id, location, parent, declared);
    }

    private static Object value(Field field, JsonElement element, String path) throws DefinitionException {
        return switch (field.kind()) {
            case NUMBER -> {
                if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
                    throw DefinitionException.invalid(path, "attendu un nombre (" + field.range() + ")");
                }
                double number = primitive.getAsDouble();
                if (!field.admits(number)) {
                    throw DefinitionException.invalid(path, primitive.getAsBigDecimal().toPlainString()
                            + " hors de " + field.range());
                }
                yield number;
            }
            case COMBINE -> {
                String name = text(element, path);
                for (Combine combine : Combine.values()) {
                    if (combine.jsonName().equals(name)) {
                        yield combine;
                    }
                }
                throw DefinitionException.invalid(path, "« " + name
                        + " » inconnu ; admis : average, min, max, multiply");
            }
            case GROUP -> {
                String name = text(element, path);
                if (!DefinitionRegistry.isId(name)) {
                    throw DefinitionException.invalid(path, "« " + name
                            + " » n'est pas un identifiant <ns>:<chemin>");
                }
                yield name;
            }
            case FLAGS -> {
                if (!(element instanceof JsonArray array)) {
                    throw DefinitionException.invalid(path, "attendu une liste de drapeaux");
                }
                Set<Flag> flags = EnumSet.noneOf(Flag.class);
                for (int index = 0; index < array.size(); index++) {
                    String at = path + "[" + index + "]";
                    String name = text(array.get(index), at);
                    Flag flag = null;
                    for (Flag candidate : Flag.values()) {
                        if (candidate.jsonName().equals(name)) {
                            flag = candidate;
                        }
                    }
                    if (flag == null) {
                        throw DefinitionException.invalid(at, "« " + name
                                + " » inconnu ; admis : brittle, ductile, soft, rigid, flammable,"
                                + " non_deformable");
                    }
                    flags.add(flag);
                }
                yield flags;
            }
        };
    }

    private static String text(JsonElement element, String path) throws DefinitionException {
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isString()) {
            throw DefinitionException.invalid(path, "attendu une chaîne");
        }
        return primitive.getAsString();
    }

    /**
     * {@return l'identifiant d'un matériau}
     *
     * <p>{@code mymod:axion/physics_materials/metaux/acier.json} donne {@code mymod:metaux/acier}.
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

    /** {@return vrai si le matériau est déclaré} */
    public boolean knows(String id) {
        return materials.containsKey(id);
    }

    /** {@return un matériau déclaré, ou vide} */
    public Optional<Material> get(String id) {
        return Optional.ofNullable(materials.get(id));
    }

    /** {@return un matériau déclaré, ou l'acier générique s'il ne l'est pas (R-1032)} */
    public Material resolve(String id) {
        Material material = materials.get(id);
        return material != null ? material : GENERIC;
    }

    /** {@return les matériaux déclarés, dans l'ordre de leurs identifiants} */
    public List<String> ids() {
        return List.copyOf(materials.keySet());
    }

    /** {@return les refus du chargement, chacun nommé et localisé} */
    public List<String> refusals() {
        return refusals;
    }

    /** {@return ce que la résolution a supposé, une ligne par matériau concerné (R-1038)} */
    public List<String> notes() {
        List<String> notes = new ArrayList<>();
        materials.values().forEach(material -> notes.addAll(material.notes()));
        return notes;
    }

    /** {@return l'empreinte du registre : identifiants et valeurs résolues} */
    public String fingerprint() {
        return fingerprint;
    }

    private static String fingerprintOf(SortedMap<String, Material> materials) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 fait partie des algorithmes que toute JVM doit fournir.
            throw new IllegalStateException(impossible);
        }
        for (Material material : materials.values()) {
            StringBuilder line = new StringBuilder(material.id()).append('=');
            material.values().forEach((field, value) ->
                    line.append(field.jsonName()).append(':').append(value).append(';'));
            digest.update(line.append('\n').toString().getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
