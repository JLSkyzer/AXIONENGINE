// Fichier généré par `cargo run -p axion-codegen --bin gen_definition_builder` depuis
// docs/schema/definition-4.json (ADR-162 D4) : ne pas le modifier à la main. Le test de parité
// parite_builder échoue s'il diverge du schéma.
package dev.axion.api.content;

import dev.axion.api.Experimental;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Le builder d'une definition (ADR-162 D3, D4) : une méthode par champ du schéma 4, dont il est généré.
 *
 * <p>Il produit le même document qu'un fichier JSON : {@link #build()} rend un {@link DefinitionDocument}
 * que {@link ContentRegistrar#register} enregistre, que le même schéma valide au chargement et dont
 * l'empreinte est celle d'un fichier de même contenu. Le builder ne vérifie que les types ; les bornes, les
 * valeurs permises et les noms du modèle se vérifient au chargement, comme pour un fichier.
 *
 * <pre>{@code
 * DefinitionBuilder.vehicle("monmod:models/patrouille.glb")
 *         .physics(p -> p.mass(1900.0))
 *         .wheel("roue_av_g", w -> w.radius(0.53).steering(true))
 *         .build();
 * }</pre>
 *
 * <p>Un objet imbriqué se règle par une lambda ; la rappeler complète le même objet. Un élément de tableau ou de
 * table s'ajoute par une méthode au singulier, dont les premiers arguments sont ses clés et ses champs
 * obligatoires. Un champ dont le nom est un mot réservé de Java prend un {@code _} final.
 */
@Experimental
public final class DefinitionBuilder {

    /** La version du schéma que ce builder écrit dans le champ {@code schema}. */
    public static final int SCHEMA = 4;

    private final Map<String, Object> values = new LinkedHashMap<>();

    private DefinitionBuilder(String kind, String asset) {
        values.put("schema", (long) SCHEMA);
        values.put("asset", Objects.requireNonNull(asset, "asset"));
        values.put("kind", Objects.requireNonNull(kind, "kind"));
    }

    /**
     * {@return une definition de ce genre, sur ce modèle} Le genre est l'une des valeurs de {@code kind}.
     *
     * @param kind genre d'assembly
     * @param asset modèle, {@code <ns>:models/<chemin>.glb}
     */
    public static DefinitionBuilder of(String kind, String asset) {
        return new DefinitionBuilder(kind, asset);
    }

    /**
     * {@return une definition de genre {@code static}, sur ce modèle}
     *
     * @param asset modèle, {@code <ns>:models/<chemin>.glb}
     */
    public static DefinitionBuilder static_(String asset) {
        return new DefinitionBuilder("static", asset);
    }

    /**
     * {@return une definition de genre {@code rigid_object}, sur ce modèle}
     *
     * @param asset modèle, {@code <ns>:models/<chemin>.glb}
     */
    public static DefinitionBuilder rigidObject(String asset) {
        return new DefinitionBuilder("rigid_object", asset);
    }

    /**
     * {@return une definition de genre {@code vehicle}, sur ce modèle}
     *
     * @param asset modèle, {@code <ns>:models/<chemin>.glb}
     */
    public static DefinitionBuilder vehicle(String asset) {
        return new DefinitionBuilder("vehicle", asset);
    }

    /**
     * {@return une definition de genre {@code articulated}, sur ce modèle}
     *
     * @param asset modèle, {@code <ns>:models/<chemin>.glb}
     */
    public static DefinitionBuilder articulated(String asset) {
        return new DefinitionBuilder("articulated", asset);
    }

    /**
     * {@return une definition de genre {@code character}, sur ce modèle}
     *
     * @param asset modèle, {@code <ns>:models/<chemin>.glb}
     */
    public static DefinitionBuilder character(String asset) {
        return new DefinitionBuilder("character", asset);
    }

    /**
     * {@return une definition de genre {@code visual}, sur ce modèle}
     *
     * @param asset modèle, {@code <ns>:models/<chemin>.glb}
     */
    public static DefinitionBuilder visual(String asset) {
        return new DefinitionBuilder("visual", asset);
    }

    /**
     * {@return une definition de genre {@code structure}, sur ce modèle}
     *
     * @param asset modèle, {@code <ns>:models/<chemin>.glb}
     */
    public static DefinitionBuilder structure(String asset) {
        return new DefinitionBuilder("structure", asset);
    }

    /**
     * {@return une definition de genre {@code machine}, sur ce modèle}
     *
     * @param asset modèle, {@code <ns>:models/<chemin>.glb}
     */
    public static DefinitionBuilder machine(String asset) {
        return new DefinitionBuilder("machine", asset);
    }

    /**
     * {@return une definition de genre {@code soft_object}, sur ce modèle}
     *
     * @param asset modèle, {@code <ns>:models/<chemin>.glb}
     */
    public static DefinitionBuilder softObject(String asset) {
        return new DefinitionBuilder("soft_object", asset);
    }

    /** {@return le document, copie figée de ce qui a été réglé} */
    public DefinitionDocument build() {
        return DefinitionDocument.of(values);
    }

    /**
     * {@code aero}.
     *
     * @param config règle l'objet ; rappelée, elle complète le même
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder aero(Consumer<Aero> config) {
        Map<String, Object> nested = object(values, "aero");
        Objects.requireNonNull(config, "config").accept(new Aero(nested));
        return this;
    }

    /**
     * {@code animations}.
     *
     * @param name clé de la table
     * @param config règle l'élément ; rappelée avec les mêmes clés, elle complète le même
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder animation(String name, Consumer<Animation> config) {
        Map<String, Object> item = path(values, "animations", name);
        Objects.requireNonNull(config, "config").accept(new Animation(item));
        return this;
    }

    /**
     * {@code animations}.
     *
     * @param name clé de la table
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder animation(String name) {
        return animation(name, item -> { });
    }

    /**
     * {@code bodies} : au plus 64 éléments.
     *
     * @param name le champ obligatoire {@code name}
     * @param config règle l'élément, ajouté à la suite des précédents
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder body(String name, Consumer<Body> config) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", Objects.requireNonNull(name, "name"));
        Objects.requireNonNull(config, "config").accept(new Body(item));
        list(values, "bodies").add(item);
        return this;
    }

    /**
     * {@code bodies} : au plus 64 éléments.
     *
     * @param name le champ obligatoire {@code name}
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder body(String name) {
        return body(name, item -> { });
    }

    /**
     * {@code brakes}.
     *
     * @param config règle l'objet ; rappelée, elle complète le même
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder brakes(Consumer<Brakes> config) {
        Map<String, Object> nested = object(values, "brakes");
        Objects.requireNonNull(config, "config").accept(new Brakes(nested));
        return this;
    }

    /**
     * {@code custom}.
     *
     * @param key clé de la table
     * @param value la valeur
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder custom(String key, boolean value) {
        object(values, "custom").put(Objects.requireNonNull(key, "key"), value);
        return this;
    }

    /**
     * {@code custom}.
     *
     * @param key clé de la table
     * @param value la valeur
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder custom(String key, double value) {
        object(values, "custom").put(Objects.requireNonNull(key, "key"), value);
        return this;
    }

    /**
     * {@code custom}.
     *
     * @param key clé de la table
     * @param value la valeur
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder custom(String key, String value) {
        object(values, "custom").put(Objects.requireNonNull(key, "key"), value);
        return this;
    }

    /**
     * {@code custom}. Toute valeur de JSON, vérifiée par {@link DefinitionDocument}.
     *
     * @param key clé de la table
     * @param value la valeur
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder custom(String key, Object value) {
        object(values, "custom").put(Objects.requireNonNull(key, "key"), value);
        return this;
    }

    /**
     * {@code damage_zones}.
     *
     * @param name le champ obligatoire {@code name}
     * @param config règle l'élément, ajouté à la suite des précédents
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder damageZone(String name, Consumer<DamageZone> config) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", Objects.requireNonNull(name, "name"));
        Objects.requireNonNull(config, "config").accept(new DamageZone(item));
        list(values, "damage_zones").add(item);
        return this;
    }

    /**
     * {@code damage_zones}.
     *
     * @param name le champ obligatoire {@code name}
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder damageZone(String name) {
        return damageZone(name, item -> { });
    }

    /**
     * {@code deformation}.
     *
     * @param config règle l'objet ; rappelée, elle complète le même
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder deformation(Consumer<Deformation> config) {
        Map<String, Object> nested = object(values, "deformation");
        Objects.requireNonNull(config, "config").accept(new Deformation(nested));
        return this;
    }

    /**
     * {@code interaction}.
     *
     * @param action le champ obligatoire {@code action}
     * @param config règle l'élément, ajouté à la suite des précédents
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder interaction(String action, Consumer<Interaction> config) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("action", Objects.requireNonNull(action, "action"));
        Objects.requireNonNull(config, "config").accept(new Interaction(item));
        list(values, "interaction").add(item);
        return this;
    }

    /**
     * {@code interaction}.
     *
     * @param action le champ obligatoire {@code action}
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder interaction(String action) {
        return interaction(action, item -> { });
    }

    /**
     * {@code joints} : au plus 128 éléments.
     *
     * @param name le champ obligatoire {@code name}
     * @param config règle l'élément, ajouté à la suite des précédents
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder joint(String name, Consumer<Joint> config) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", Objects.requireNonNull(name, "name"));
        Objects.requireNonNull(config, "config").accept(new Joint(item));
        list(values, "joints").add(item);
        return this;
    }

    /**
     * {@code joints} : au plus 128 éléments.
     *
     * @param name le champ obligatoire {@code name}
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder joint(String name) {
        return joint(name, item -> { });
    }

    /**
     * {@code on_detach}.
     *
     * @param part clé de la table
     * @param config règle l'élément ; rappelée avec les mêmes clés, elle complète le même
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder onDetach(String part, Consumer<OnDetach> config) {
        Map<String, Object> item = path(values, "on_detach", part);
        Objects.requireNonNull(config, "config").accept(new OnDetach(item));
        return this;
    }

    /**
     * {@code on_detach}.
     *
     * @param part clé de la table
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder onDetach(String part) {
        return onDetach(part, item -> { });
    }

    /**
     * {@code on_part_stage}.
     *
     * @param part clé de la table
     * @param stage clé de la table
     * @param config règle l'élément ; rappelée avec les mêmes clés, elle complète le même
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder onPartStage(String part, String stage, Consumer<OnPartStage> config) {
        Map<String, Object> item = path(values, "on_part_stage", part, stage);
        Objects.requireNonNull(config, "config").accept(new OnPartStage(item));
        return this;
    }

    /**
     * {@code on_part_stage}.
     *
     * @param part clé de la table
     * @param stage clé de la table
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder onPartStage(String part, String stage) {
        return onPartStage(part, stage, item -> { });
    }

    /**
     * {@code particles} : au plus 8 éléments.
     *
     * @param name le champ obligatoire {@code name}
     * @param config règle l'élément, ajouté à la suite des précédents
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder particle(String name, Consumer<Particle> config) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", Objects.requireNonNull(name, "name"));
        Objects.requireNonNull(config, "config").accept(new Particle(item));
        list(values, "particles").add(item);
        return this;
    }

    /**
     * {@code particles} : au plus 8 éléments.
     *
     * @param name le champ obligatoire {@code name}
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder particle(String name) {
        return particle(name, item -> { });
    }

    /**
     * {@code parts} : au plus 64 éléments.
     *
     * @param name le champ obligatoire {@code name}
     * @param config règle l'élément, ajouté à la suite des précédents
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder part(String name, Consumer<Part> config) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", Objects.requireNonNull(name, "name"));
        Objects.requireNonNull(config, "config").accept(new Part(item));
        list(values, "parts").add(item);
        return this;
    }

    /**
     * {@code parts} : au plus 64 éléments.
     *
     * @param name le champ obligatoire {@code name}
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder part(String name) {
        return part(name, item -> { });
    }

    /**
     * {@code physics}.
     *
     * @param config règle l'objet ; rappelée, elle complète le même
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder physics(Consumer<Physics> config) {
        Map<String, Object> nested = object(values, "physics");
        Objects.requireNonNull(config, "config").accept(new Physics(nested));
        return this;
    }

    /**
     * {@code powertrain}.
     *
     * @param config règle l'objet ; rappelée, elle complète le même
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder powertrain(Consumer<Powertrain> config) {
        Map<String, Object> nested = object(values, "powertrain");
        Objects.requireNonNull(config, "config").accept(new Powertrain(nested));
        return this;
    }

    /**
     * {@code procedural}.
     *
     * @param channel le champ obligatoire {@code channel}
     * @param source le champ obligatoire {@code source}
     * @param config règle l'élément, ajouté à la suite des précédents
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder procedural(String channel, String source, Consumer<Procedural> config) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("channel", Objects.requireNonNull(channel, "channel"));
        item.put("source", Objects.requireNonNull(source, "source"));
        Objects.requireNonNull(config, "config").accept(new Procedural(item));
        list(values, "procedural").add(item);
        return this;
    }

    /**
     * {@code procedural}.
     *
     * @param channel le champ obligatoire {@code channel}
     * @param source le champ obligatoire {@code source}
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder procedural(String channel, String source) {
        return procedural(channel, source, item -> { });
    }

    /**
     * {@code render}.
     *
     * @param config règle l'objet ; rappelée, elle complète le même
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder render(Consumer<Render> config) {
        Map<String, Object> nested = object(values, "render");
        Objects.requireNonNull(config, "config").accept(new Render(nested));
        return this;
    }

    /**
     * {@code repair_rule}.
     *
     * @param value la valeur
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder repairRule(String value) {
        values.put("repair_rule", Objects.requireNonNull(value, "repair_rule"));
        return this;
    }

    /**
     * {@code seats} : au plus 16 éléments.
     *
     * @param name le champ obligatoire {@code name}
     * @param config règle l'élément, ajouté à la suite des précédents
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder seat(String name, Consumer<Seat> config) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", Objects.requireNonNull(name, "name"));
        Objects.requireNonNull(config, "config").accept(new Seat(item));
        list(values, "seats").add(item);
        return this;
    }

    /**
     * {@code seats} : au plus 16 éléments.
     *
     * @param name le champ obligatoire {@code name}
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder seat(String name) {
        return seat(name, item -> { });
    }

    /**
     * {@code sockets} : déclare un nom (socket).
     *
     * @param items les valeurs, dans l'ordre
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder sockets(String... items) {
        values.put("sockets", texts(items));
        return this;
    }

    /**
     * {@code steering}.
     *
     * @param config règle l'objet ; rappelée, elle complète le même
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder steering(Consumer<Steering> config) {
        Map<String, Object> nested = object(values, "steering");
        Objects.requireNonNull(config, "config").accept(new Steering(nested));
        return this;
    }

    /**
     * {@code structural_links} : au plus 256 éléments.
     *
     * @param name le champ obligatoire {@code name}
     * @param config règle l'élément, ajouté à la suite des précédents
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder structuralLink(String name, Consumer<StructuralLink> config) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", Objects.requireNonNull(name, "name"));
        Objects.requireNonNull(config, "config").accept(new StructuralLink(item));
        list(values, "structural_links").add(item);
        return this;
    }

    /**
     * {@code structural_links} : au plus 256 éléments.
     *
     * @param name le champ obligatoire {@code name}
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder structuralLink(String name) {
        return structuralLink(name, item -> { });
    }

    /**
     * {@code tuning}.
     *
     * @param config règle l'objet ; rappelée, elle complète le même
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder tuning(Consumer<Tuning> config) {
        Map<String, Object> nested = object(values, "tuning");
        Objects.requireNonNull(config, "config").accept(new Tuning(nested));
        return this;
    }

    /**
     * {@code variables}.
     *
     * @param name clé de la table
     * @param type le champ obligatoire {@code type}
     * @param config règle l'élément ; rappelée avec les mêmes clés, elle complète le même
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder variable(String name, String type, Consumer<Variable> config) {
        Map<String, Object> item = path(values, "variables", name);
        item.put("type", Objects.requireNonNull(type, "type"));
        Objects.requireNonNull(config, "config").accept(new Variable(item));
        return this;
    }

    /**
     * {@code variables}.
     *
     * @param name clé de la table
     * @param type le champ obligatoire {@code type}
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder variable(String name, String type) {
        return variable(name, type, item -> { });
    }

    /**
     * {@code vehicle}.
     *
     * @param config règle l'objet ; rappelée, elle complète le même
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder vehicle(Consumer<Vehicle> config) {
        Map<String, Object> nested = object(values, "vehicle");
        Objects.requireNonNull(config, "config").accept(new Vehicle(nested));
        return this;
    }

    /**
     * {@code wear}.
     *
     * @param config règle l'objet ; rappelée, elle complète le même
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder wear(Consumer<Wear> config) {
        Map<String, Object> nested = object(values, "wear");
        Objects.requireNonNull(config, "config").accept(new Wear(nested));
        return this;
    }

    /**
     * {@code wheels} : au moins 1 élément ; au plus 32 éléments.
     *
     * @param node le champ obligatoire {@code node}
     * @param config règle l'élément, ajouté à la suite des précédents
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder wheel(String node, Consumer<Wheel> config) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("node", Objects.requireNonNull(node, "node"));
        Objects.requireNonNull(config, "config").accept(new Wheel(item));
        list(values, "wheels").add(item);
        return this;
    }

    /**
     * {@code wheels} : au moins 1 élément ; au plus 32 éléments.
     *
     * @param node le champ obligatoire {@code node}
     * @return ce réglage, pour enchaîner
     */
    public DefinitionBuilder wheel(String node) {
        return wheel(node, item -> { });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> values, String key) {
        return (Map<String, Object>) values.computeIfAbsent(key, k -> new LinkedHashMap<String, Object>());
    }

    private static Map<String, Object> path(Map<String, Object> values, String key, String... keys) {
        Map<String, Object> level = object(values, key);
        for (String sub : keys) {
            level = object(level, Objects.requireNonNull(sub, "clé"));
        }
        return level;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> values, String key) {
        return (List<Object>) values.computeIfAbsent(key, k -> new ArrayList<Object>());
    }

    private static List<Object> doubles(double[] items) {
        List<Object> out = new ArrayList<>(items.length);
        for (double item : items) {
            out.add(item);
        }
        return out;
    }

    private static List<Object> longs(long[] items) {
        List<Object> out = new ArrayList<>(items.length);
        for (long item : items) {
            out.add(item);
        }
        return out;
    }

    private static List<Object> booleans(boolean[] items) {
        List<Object> out = new ArrayList<>(items.length);
        for (boolean item : items) {
            out.add(item);
        }
        return out;
    }

    private static List<Object> texts(String[] items) {
        List<Object> out = new ArrayList<>(items.length);
        for (String item : items) {
            out.add(Objects.requireNonNull(item, "élément"));
        }
        return out;
    }

    private static List<Object> rows(double[][] rows) {
        List<Object> out = new ArrayList<>(rows.length);
        for (double[] row : rows) {
            out.add(doubles(Objects.requireNonNull(row, "ligne")));
        }
        return out;
    }

    /** {@code aero.surfaces[]}. */
    @Experimental
    public static final class AeroSurface {

        private final Map<String, Object> values;

        private AeroSurface(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code aero.surfaces[].area}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public AeroSurface area(double value) {
            values.put("area", value);
            return this;
        }

        /**
         * {@code aero.surfaces[].cd}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public AeroSurface cd(double value) {
            values.put("cd", value);
            return this;
        }

        /**
         * {@code aero.surfaces[].cl}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public AeroSurface cl(double value) {
            values.put("cl", value);
            return this;
        }

        /**
         * {@code aero.surfaces[].node} : nom (node) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public AeroSurface node(String value) {
            values.put("node", Objects.requireNonNull(value, "node"));
            return this;
        }
    }

    /** {@code aero}. */
    @Experimental
    public static final class Aero {

        private final Map<String, Object> values;

        private Aero(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code aero.downforce_front}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Aero downforceFront(double value) {
            values.put("downforce_front", value);
            return this;
        }

        /**
         * {@code aero.downforce_rear}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Aero downforceRear(double value) {
            values.put("downforce_rear", value);
            return this;
        }

        /**
         * {@code aero.drag_coefficient}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Aero dragCoefficient(double value) {
            values.put("drag_coefficient", value);
            return this;
        }

        /**
         * {@code aero.frontal_area}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Aero frontalArea(double value) {
            values.put("frontal_area", value);
            return this;
        }

        /**
         * {@code aero.surfaces}.
         *
         * @param config règle l'élément, ajouté à la suite des précédents
         * @return ce réglage, pour enchaîner
         */
        public Aero surface(Consumer<AeroSurface> config) {
            Map<String, Object> item = new LinkedHashMap<>();
            Objects.requireNonNull(config, "config").accept(new AeroSurface(item));
            list(values, "surfaces").add(item);
            return this;
        }
    }

    /** {@code animations.*}. */
    @Experimental
    public static final class Animation {

        private final Map<String, Object> values;

        private Animation(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code animations.*.clip} : nom (clip) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Animation clip(String value) {
            values.put("clip", Objects.requireNonNull(value, "clip"));
            return this;
        }

        /**
         * {@code animations.*.drives_joint} : nom (joint) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Animation drivesJoint(String value) {
            values.put("drives_joint", Objects.requireNonNull(value, "drives_joint"));
            return this;
        }

        /**
         * {@code animations.*.layer} : au moins 0 ; au plus 3.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Animation layer(long value) {
            values.put("layer", value);
            return this;
        }

        /**
         * {@code animations.*.trigger}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Animation trigger(String value) {
            values.put("trigger", Objects.requireNonNull(value, "trigger"));
            return this;
        }
    }

    /** {@code bodies[]}. */
    @Experimental
    public static final class Body {

        private final Map<String, Object> values;

        private Body(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code bodies[].colliders}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Body colliders(String value) {
            values.put("colliders", Objects.requireNonNull(value, "colliders"));
            return this;
        }

        /**
         * {@code bodies[].mass} : au moins 0.001 ; au plus 1000000.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Body mass(double value) {
            values.put("mass", value);
            return this;
        }

        /**
         * {@code bodies[].name} : déclare un nom (body).
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Body name(String value) {
            values.put("name", Objects.requireNonNull(value, "name"));
            return this;
        }

        /**
         * {@code bodies[].root_node} : nom (node) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Body rootNode(String value) {
            values.put("root_node", Objects.requireNonNull(value, "root_node"));
            return this;
        }
    }

    /** {@code brakes.abs}. */
    @Experimental
    public static final class BrakesAbs {

        private final Map<String, Object> values;

        private BrakesAbs(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code brakes.abs.enabled}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public BrakesAbs enabled(boolean value) {
            values.put("enabled", value);
            return this;
        }

        /**
         * {@code brakes.abs.slip_threshold}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public BrakesAbs slipThreshold(double value) {
            values.put("slip_threshold", value);
            return this;
        }
    }

    /** {@code brakes.stability_control}. */
    @Experimental
    public static final class BrakesStabilityControl {

        private final Map<String, Object> values;

        private BrakesStabilityControl(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code brakes.stability_control.enabled}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public BrakesStabilityControl enabled(boolean value) {
            values.put("enabled", value);
            return this;
        }

        /**
         * {@code brakes.stability_control.yaw_tolerance}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public BrakesStabilityControl yawTolerance(double value) {
            values.put("yaw_tolerance", value);
            return this;
        }
    }

    /** {@code brakes}. */
    @Experimental
    public static final class Brakes {

        private final Map<String, Object> values;

        private Brakes(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code brakes.abs}.
         *
         * @param config règle l'objet ; rappelée, elle complète le même
         * @return ce réglage, pour enchaîner
         */
        public Brakes abs(Consumer<BrakesAbs> config) {
            Map<String, Object> nested = object(values, "abs");
            Objects.requireNonNull(config, "config").accept(new BrakesAbs(nested));
            return this;
        }

        /**
         * {@code brakes.handbrake_torque}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Brakes handbrakeTorque(double value) {
            values.put("handbrake_torque", value);
            return this;
        }

        /**
         * {@code brakes.handbrake_wheels} : nom (wheel) dans le modèle.
         *
         * @param items les valeurs, dans l'ordre
         * @return ce réglage, pour enchaîner
         */
        public Brakes handbrakeWheels(String... items) {
            values.put("handbrake_wheels", texts(items));
            return this;
        }

        /**
         * {@code brakes.max_torque_front}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Brakes maxTorqueFront(double value) {
            values.put("max_torque_front", value);
            return this;
        }

        /**
         * {@code brakes.max_torque_rear}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Brakes maxTorqueRear(double value) {
            values.put("max_torque_rear", value);
            return this;
        }

        /**
         * {@code brakes.stability_control}.
         *
         * @param config règle l'objet ; rappelée, elle complète le même
         * @return ce réglage, pour enchaîner
         */
        public Brakes stabilityControl(Consumer<BrakesStabilityControl> config) {
            Map<String, Object> nested = object(values, "stability_control");
            Objects.requireNonNull(config, "config").accept(new BrakesStabilityControl(nested));
            return this;
        }
    }

    /** {@code damage_zones[]}. */
    @Experimental
    public static final class DamageZone {

        private final Map<String, Object> values;

        private DamageZone(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code damage_zones[].deform_multiplier}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public DamageZone deformMultiplier(double value) {
            values.put("deform_multiplier", value);
            return this;
        }

        /**
         * {@code damage_zones[].multiplier}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public DamageZone multiplier(double value) {
            values.put("multiplier", value);
            return this;
        }

        /**
         * {@code damage_zones[].name} : déclare un nom (zone).
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public DamageZone name(String value) {
            values.put("name", Objects.requireNonNull(value, "name"));
            return this;
        }

        /**
         * {@code damage_zones[].node} : nom (node) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public DamageZone node(String value) {
            values.put("node", Objects.requireNonNull(value, "node"));
            return this;
        }

        /**
         * {@code damage_zones[].parent} : nom (zone) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public DamageZone parent(String value) {
            values.put("parent", Objects.requireNonNull(value, "parent"));
            return this;
        }

        /**
         * {@code damage_zones[].part} : nom (part) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public DamageZone part(String value) {
            values.put("part", Objects.requireNonNull(value, "part"));
            return this;
        }

        /**
         * {@code damage_zones[].region} : nom (region) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public DamageZone region(String value) {
            values.put("region", Objects.requireNonNull(value, "region"));
            return this;
        }
    }

    /** {@code deformation.extras.bones[]}. */
    @Experimental
    public static final class DeformationExtrasBone {

        private final Map<String, Object> values;

        private DeformationExtrasBone(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code deformation.extras.bones[].axis}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public DeformationExtrasBone axis(String value) {
            values.put("axis", Objects.requireNonNull(value, "axis"));
            return this;
        }

        /**
         * {@code deformation.extras.bones[].bone} : nom (bone) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public DeformationExtrasBone bone(String value) {
            values.put("bone", Objects.requireNonNull(value, "bone"));
            return this;
        }

        /**
         * {@code deformation.extras.bones[].driven_by}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public DeformationExtrasBone drivenBy(String value) {
            values.put("driven_by", Objects.requireNonNull(value, "driven_by"));
            return this;
        }

        /**
         * {@code deformation.extras.bones[].max_angle}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public DeformationExtrasBone maxAngle(double value) {
            values.put("max_angle", value);
            return this;
        }

        /**
         * {@code deformation.extras.bones[].scale}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public DeformationExtrasBone scale(double value) {
            values.put("scale", value);
            return this;
        }
    }

    /** {@code deformation.extras.morphs[]}. */
    @Experimental
    public static final class DeformationExtrasMorph {

        private final Map<String, Object> values;

        private DeformationExtrasMorph(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code deformation.extras.morphs[].driven_by}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public DeformationExtrasMorph drivenBy(String value) {
            values.put("driven_by", Objects.requireNonNull(value, "driven_by"));
            return this;
        }

        /**
         * {@code deformation.extras.morphs[].map}.
         *
         * @param rows les lignes, une par élément
         * @return ce réglage, pour enchaîner
         */
        public DeformationExtrasMorph map(double[]... rows) {
            values.put("map", rows(rows));
            return this;
        }

        /**
         * {@code deformation.extras.morphs[].target}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public DeformationExtrasMorph target(String value) {
            values.put("target", Objects.requireNonNull(value, "target"));
            return this;
        }
    }

    /** {@code deformation.extras}. */
    @Experimental
    public static final class DeformationExtras {

        private final Map<String, Object> values;

        private DeformationExtras(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code deformation.extras.bones}.
         *
         * @param config règle l'élément, ajouté à la suite des précédents
         * @return ce réglage, pour enchaîner
         */
        public DeformationExtras bone(Consumer<DeformationExtrasBone> config) {
            Map<String, Object> item = new LinkedHashMap<>();
            Objects.requireNonNull(config, "config").accept(new DeformationExtrasBone(item));
            list(values, "bones").add(item);
            return this;
        }

        /**
         * {@code deformation.extras.morphs}.
         *
         * @param config règle l'élément, ajouté à la suite des précédents
         * @return ce réglage, pour enchaîner
         */
        public DeformationExtras morph(Consumer<DeformationExtrasMorph> config) {
            Map<String, Object> item = new LinkedHashMap<>();
            Objects.requireNonNull(config, "config").accept(new DeformationExtrasMorph(item));
            list(values, "morphs").add(item);
            return this;
        }
    }

    /** {@code deformation}. */
    @Experimental
    public static final class Deformation {

        private final Map<String, Object> values;

        private Deformation(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code deformation.enabled}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Deformation enabled(boolean value) {
            values.put("enabled", value);
            return this;
        }

        /**
         * {@code deformation.extras}.
         *
         * @param config règle l'objet ; rappelée, elle complète le même
         * @return ce réglage, pour enchaîner
         */
        public Deformation extras(Consumer<DeformationExtras> config) {
            Map<String, Object> nested = object(values, "extras");
            Objects.requireNonNull(config, "config").accept(new DeformationExtras(nested));
            return this;
        }

        /**
         * {@code deformation.min_quality} : parmi {@code off}, {@code low}, {@code medium}, {@code high},
         * {@code ultra}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Deformation minQuality(String value) {
            values.put("min_quality", Objects.requireNonNull(value, "min_quality"));
            return this;
        }
    }

    /** {@code interaction[]}. */
    @Experimental
    public static final class Interaction {

        private final Map<String, Object> values;

        private Interaction(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code interaction[].action} : parmi {@code sit}, {@code toggle_animation}, {@code play_animation},
         * {@code open_container}, {@code emit_event}, {@code set_variable}, {@code repair}, {@code attach},
         * {@code detach}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Interaction action(String value) {
            values.put("action", Objects.requireNonNull(value, "action"));
            return this;
        }

        /**
         * {@code interaction[].animation} : nom (animation) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Interaction animation(String value) {
            values.put("animation", Objects.requireNonNull(value, "animation"));
            return this;
        }

        /**
         * {@code interaction[].node} : nom (node) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Interaction node(String value) {
            values.put("node", Objects.requireNonNull(value, "node"));
            return this;
        }

        /**
         * {@code interaction[].seat} : nom (seat) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Interaction seat(String value) {
            values.put("seat", Objects.requireNonNull(value, "seat"));
            return this;
        }

        /**
         * {@code interaction[].value}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Interaction value(boolean value) {
            values.put("value", value);
            return this;
        }

        /**
         * {@code interaction[].value}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Interaction value(double value) {
            values.put("value", value);
            return this;
        }

        /**
         * {@code interaction[].value}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Interaction value(String value) {
            values.put("value", value);
            return this;
        }

        /**
         * {@code interaction[].value}. Toute valeur de JSON, vérifiée par {@link DefinitionDocument}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Interaction value(Object value) {
            values.put("value", value);
            return this;
        }

        /**
         * {@code interaction[].variable} : nom (variable) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Interaction variable(String value) {
            values.put("variable", Objects.requireNonNull(value, "variable"));
            return this;
        }
    }

    /** {@code joints[].axes[]}. */
    @Experimental
    public static final class JointAxis {

        private final Map<String, Object> values;

        private JointAxis(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code joints[].axes[].limits} : 2 éléments.
         *
         * @param items les valeurs, dans l'ordre
         * @return ce réglage, pour enchaîner
         */
        public JointAxis limits(double... items) {
            values.put("limits", doubles(items));
            return this;
        }

        /**
         * {@code joints[].axes[].mode} : parmi {@code locked}, {@code free}, {@code limited}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public JointAxis mode(String value) {
            values.put("mode", Objects.requireNonNull(value, "mode"));
            return this;
        }
    }

    /** {@code joints[].break}. */
    @Experimental
    public static final class JointBreak {

        private final Map<String, Object> values;

        private JointBreak(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code joints[].break.energy} : plus de 0.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public JointBreak energy(double value) {
            values.put("energy", value);
            return this;
        }

        /**
         * {@code joints[].break.force} : plus de 0.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public JointBreak force(double value) {
            values.put("force", value);
            return this;
        }

        /**
         * {@code joints[].break.torque} : plus de 0.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public JointBreak torque(double value) {
            values.put("torque", value);
            return this;
        }
    }

    /** {@code joints[].jam}. */
    @Experimental
    public static final class JointJam {

        private final Map<String, Object> values;

        private JointJam(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code joints[].jam.curve}.
         *
         * @param rows les lignes, une par élément
         * @return ce réglage, pour enchaîner
         */
        public JointJam curve(double[]... rows) {
            values.put("curve", rows(rows));
            return this;
        }

        /**
         * {@code joints[].jam.friction} : au moins 0.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public JointJam friction(double value) {
            values.put("friction", value);
            return this;
        }
    }

    /** {@code joints[].motor}. */
    @Experimental
    public static final class JointMotor {

        private final Map<String, Object> values;

        private JointMotor(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code joints[].motor.damping} : au moins 0.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public JointMotor damping(double value) {
            values.put("damping", value);
            return this;
        }

        /**
         * {@code joints[].motor.max_force} : au moins 0.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public JointMotor maxForce(double value) {
            values.put("max_force", value);
            return this;
        }

        /**
         * {@code joints[].motor.speed}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public JointMotor speed(double value) {
            values.put("speed", value);
            return this;
        }

        /**
         * {@code joints[].motor.stiffness} : au moins 0.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public JointMotor stiffness(double value) {
            values.put("stiffness", value);
            return this;
        }

        /**
         * {@code joints[].motor.target}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public JointMotor target(double value) {
            values.put("target", value);
            return this;
        }
    }

    /** {@code joints[]}. */
    @Experimental
    public static final class Joint {

        private final Map<String, Object> values;

        private Joint(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code joints[].a} : nom (body) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Joint a(String value) {
            values.put("a", Objects.requireNonNull(value, "a"));
            return this;
        }

        /**
         * {@code joints[].anchor} : 3 éléments.
         *
         * @param items les valeurs, dans l'ordre
         * @return ce réglage, pour enchaîner
         */
        public Joint anchor(double... items) {
            values.put("anchor", doubles(items));
            return this;
        }

        /**
         * {@code joints[].axes} : 6 éléments.
         *
         * @param mode le champ obligatoire {@code mode}
         * @param config règle l'élément, ajouté à la suite des précédents
         * @return ce réglage, pour enchaîner
         */
        public Joint axis(String mode, Consumer<JointAxis> config) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("mode", Objects.requireNonNull(mode, "mode"));
            Objects.requireNonNull(config, "config").accept(new JointAxis(item));
            list(values, "axes").add(item);
            return this;
        }

        /**
         * {@code joints[].axes} : 6 éléments.
         *
         * @param mode le champ obligatoire {@code mode}
         * @return ce réglage, pour enchaîner
         */
        public Joint axis(String mode) {
            return axis(mode, item -> { });
        }

        /**
         * {@code joints[].axis} : 3 éléments.
         *
         * @param items les valeurs, dans l'ordre
         * @return ce réglage, pour enchaîner
         */
        public Joint axis(double... items) {
            values.put("axis", doubles(items));
            return this;
        }

        /**
         * {@code joints[].b_node} : nom (node) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Joint bNode(String value) {
            values.put("b_node", Objects.requireNonNull(value, "b_node"));
            return this;
        }

        /**
         * {@code joints[].break}.
         *
         * @param config règle l'objet ; rappelée, elle complète le même
         * @return ce réglage, pour enchaîner
         */
        public Joint break_(Consumer<JointBreak> config) {
            Map<String, Object> nested = object(values, "break");
            Objects.requireNonNull(config, "config").accept(new JointBreak(nested));
            return this;
        }

        /**
         * {@code joints[].collide}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Joint collide(boolean value) {
            values.put("collide", value);
            return this;
        }

        /**
         * {@code joints[].damping} : au moins 0.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Joint damping(double value) {
            values.put("damping", value);
            return this;
        }

        /**
         * {@code joints[].friction} : au moins 0.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Joint friction(double value) {
            values.put("friction", value);
            return this;
        }

        /**
         * {@code joints[].jam}.
         *
         * @param config règle l'objet ; rappelée, elle complète le même
         * @return ce réglage, pour enchaîner
         */
        public Joint jam(Consumer<JointJam> config) {
            Map<String, Object> nested = object(values, "jam");
            Objects.requireNonNull(config, "config").accept(new JointJam(nested));
            return this;
        }

        /**
         * {@code joints[].limits} : 2 éléments.
         *
         * @param items les valeurs, dans l'ordre
         * @return ce réglage, pour enchaîner
         */
        public Joint limits(double... items) {
            values.put("limits", doubles(items));
            return this;
        }

        /**
         * {@code joints[].max_length} : plus de 0.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Joint maxLength(double value) {
            values.put("max_length", value);
            return this;
        }

        /**
         * {@code joints[].motor}.
         *
         * @param config règle l'objet ; rappelée, elle complète le même
         * @return ce réglage, pour enchaîner
         */
        public Joint motor(Consumer<JointMotor> config) {
            Map<String, Object> nested = object(values, "motor");
            Objects.requireNonNull(config, "config").accept(new JointMotor(nested));
            return this;
        }

        /**
         * {@code joints[].name} : déclare un nom (joint).
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Joint name(String value) {
            values.put("name", Objects.requireNonNull(value, "name"));
            return this;
        }

        /**
         * {@code joints[].rest_length} : au moins 0.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Joint restLength(double value) {
            values.put("rest_length", value);
            return this;
        }

        /**
         * {@code joints[].stiffness} : au moins 0.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Joint stiffness(double value) {
            values.put("stiffness", value);
            return this;
        }

        /**
         * {@code joints[].type} : parmi {@code fixed}, {@code revolute}, {@code prismatic}, {@code spherical},
         * {@code generic}, {@code rope}, {@code spring}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Joint type(String value) {
            values.put("type", Objects.requireNonNull(value, "type"));
            return this;
        }
    }

    /** {@code on_detach.*}. */
    @Experimental
    public static final class OnDetach {

        private final Map<String, Object> values;

        private OnDetach(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code on_detach.*.break_joint}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach breakJoint(boolean value) {
            values.put("break_joint", value);
            return this;
        }

        /**
         * {@code on_detach.*.break_joint}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach breakJoint(double value) {
            values.put("break_joint", value);
            return this;
        }

        /**
         * {@code on_detach.*.break_joint}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach breakJoint(String value) {
            values.put("break_joint", value);
            return this;
        }

        /**
         * {@code on_detach.*.break_joint}. Toute valeur de JSON, vérifiée par {@link DefinitionDocument}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach breakJoint(Object value) {
            values.put("break_joint", value);
            return this;
        }

        /**
         * {@code on_detach.*.detach_part}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach detachPart(boolean value) {
            values.put("detach_part", value);
            return this;
        }

        /**
         * {@code on_detach.*.detach_part}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach detachPart(double value) {
            values.put("detach_part", value);
            return this;
        }

        /**
         * {@code on_detach.*.detach_part}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach detachPart(String value) {
            values.put("detach_part", value);
            return this;
        }

        /**
         * {@code on_detach.*.detach_part}. Toute valeur de JSON, vérifiée par {@link DefinitionDocument}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach detachPart(Object value) {
            values.put("detach_part", value);
            return this;
        }

        /**
         * {@code on_detach.*.disable_seat}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach disableSeat(boolean value) {
            values.put("disable_seat", value);
            return this;
        }

        /**
         * {@code on_detach.*.disable_seat}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach disableSeat(double value) {
            values.put("disable_seat", value);
            return this;
        }

        /**
         * {@code on_detach.*.disable_seat}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach disableSeat(String value) {
            values.put("disable_seat", value);
            return this;
        }

        /**
         * {@code on_detach.*.disable_seat}. Toute valeur de JSON, vérifiée par {@link DefinitionDocument}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach disableSeat(Object value) {
            values.put("disable_seat", value);
            return this;
        }

        /**
         * {@code on_detach.*.emit_event}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach emitEvent(String value) {
            values.put("emit_event", Objects.requireNonNull(value, "emit_event"));
            return this;
        }

        /**
         * {@code on_detach.*.hide_node} : nom (node) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach hideNode(String value) {
            values.put("hide_node", Objects.requireNonNull(value, "hide_node"));
            return this;
        }

        /**
         * {@code on_detach.*.jam_joint} : nom (joint) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach jamJoint(String value) {
            values.put("jam_joint", Objects.requireNonNull(value, "jam_joint"));
            return this;
        }

        /**
         * {@code on_detach.*.play_animation}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach playAnimation(boolean value) {
            values.put("play_animation", value);
            return this;
        }

        /**
         * {@code on_detach.*.play_animation}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach playAnimation(double value) {
            values.put("play_animation", value);
            return this;
        }

        /**
         * {@code on_detach.*.play_animation}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach playAnimation(String value) {
            values.put("play_animation", value);
            return this;
        }

        /**
         * {@code on_detach.*.play_animation}. Toute valeur de JSON, vérifiée par {@link DefinitionDocument}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach playAnimation(Object value) {
            values.put("play_animation", value);
            return this;
        }

        /**
         * {@code on_detach.*.set_material_param}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach setMaterialParam(boolean value) {
            values.put("set_material_param", value);
            return this;
        }

        /**
         * {@code on_detach.*.set_material_param}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach setMaterialParam(double value) {
            values.put("set_material_param", value);
            return this;
        }

        /**
         * {@code on_detach.*.set_material_param}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach setMaterialParam(String value) {
            values.put("set_material_param", value);
            return this;
        }

        /**
         * {@code on_detach.*.set_material_param}. Toute valeur de JSON, vérifiée par {@link DefinitionDocument}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach setMaterialParam(Object value) {
            values.put("set_material_param", value);
            return this;
        }

        /**
         * {@code on_detach.*.set_variable}.
         *
         * @param variable clé de la table
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach setVariable(String variable, double value) {
            Map<String, Object> table = object(values, "set_variable");
            table.put(Objects.requireNonNull(variable, "variable"), value);
            return this;
        }

        /**
         * {@code on_detach.*.show_node}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach showNode(boolean value) {
            values.put("show_node", value);
            return this;
        }

        /**
         * {@code on_detach.*.show_node}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach showNode(double value) {
            values.put("show_node", value);
            return this;
        }

        /**
         * {@code on_detach.*.show_node}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach showNode(String value) {
            values.put("show_node", value);
            return this;
        }

        /**
         * {@code on_detach.*.show_node}. Toute valeur de JSON, vérifiée par {@link DefinitionDocument}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach showNode(Object value) {
            values.put("show_node", value);
            return this;
        }

        /**
         * {@code on_detach.*.spawn_debris}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach spawnDebris(String value) {
            values.put("spawn_debris", Objects.requireNonNull(value, "spawn_debris"));
            return this;
        }

        /**
         * {@code on_detach.*.swap_mesh} : nom (mesh) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnDetach swapMesh(String value) {
            values.put("swap_mesh", Objects.requireNonNull(value, "swap_mesh"));
            return this;
        }
    }

    /** {@code on_part_stage.*}. */
    @Experimental
    public static final class OnPartStage {

        private final Map<String, Object> values;

        private OnPartStage(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code on_part_stage.*.break_joint}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage breakJoint(boolean value) {
            values.put("break_joint", value);
            return this;
        }

        /**
         * {@code on_part_stage.*.break_joint}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage breakJoint(double value) {
            values.put("break_joint", value);
            return this;
        }

        /**
         * {@code on_part_stage.*.break_joint}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage breakJoint(String value) {
            values.put("break_joint", value);
            return this;
        }

        /**
         * {@code on_part_stage.*.break_joint}. Toute valeur de JSON, vérifiée par {@link DefinitionDocument}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage breakJoint(Object value) {
            values.put("break_joint", value);
            return this;
        }

        /**
         * {@code on_part_stage.*.detach_part}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage detachPart(boolean value) {
            values.put("detach_part", value);
            return this;
        }

        /**
         * {@code on_part_stage.*.detach_part}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage detachPart(double value) {
            values.put("detach_part", value);
            return this;
        }

        /**
         * {@code on_part_stage.*.detach_part}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage detachPart(String value) {
            values.put("detach_part", value);
            return this;
        }

        /**
         * {@code on_part_stage.*.detach_part}. Toute valeur de JSON, vérifiée par {@link DefinitionDocument}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage detachPart(Object value) {
            values.put("detach_part", value);
            return this;
        }

        /**
         * {@code on_part_stage.*.disable_seat}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage disableSeat(boolean value) {
            values.put("disable_seat", value);
            return this;
        }

        /**
         * {@code on_part_stage.*.disable_seat}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage disableSeat(double value) {
            values.put("disable_seat", value);
            return this;
        }

        /**
         * {@code on_part_stage.*.disable_seat}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage disableSeat(String value) {
            values.put("disable_seat", value);
            return this;
        }

        /**
         * {@code on_part_stage.*.disable_seat}. Toute valeur de JSON, vérifiée par {@link DefinitionDocument}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage disableSeat(Object value) {
            values.put("disable_seat", value);
            return this;
        }

        /**
         * {@code on_part_stage.*.emit_event}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage emitEvent(String value) {
            values.put("emit_event", Objects.requireNonNull(value, "emit_event"));
            return this;
        }

        /**
         * {@code on_part_stage.*.hide_node} : nom (node) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage hideNode(String value) {
            values.put("hide_node", Objects.requireNonNull(value, "hide_node"));
            return this;
        }

        /**
         * {@code on_part_stage.*.jam_joint} : nom (joint) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage jamJoint(String value) {
            values.put("jam_joint", Objects.requireNonNull(value, "jam_joint"));
            return this;
        }

        /**
         * {@code on_part_stage.*.play_animation}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage playAnimation(boolean value) {
            values.put("play_animation", value);
            return this;
        }

        /**
         * {@code on_part_stage.*.play_animation}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage playAnimation(double value) {
            values.put("play_animation", value);
            return this;
        }

        /**
         * {@code on_part_stage.*.play_animation}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage playAnimation(String value) {
            values.put("play_animation", value);
            return this;
        }

        /**
         * {@code on_part_stage.*.play_animation}. Toute valeur de JSON, vérifiée par {@link DefinitionDocument}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage playAnimation(Object value) {
            values.put("play_animation", value);
            return this;
        }

        /**
         * {@code on_part_stage.*.set_material_param}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage setMaterialParam(boolean value) {
            values.put("set_material_param", value);
            return this;
        }

        /**
         * {@code on_part_stage.*.set_material_param}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage setMaterialParam(double value) {
            values.put("set_material_param", value);
            return this;
        }

        /**
         * {@code on_part_stage.*.set_material_param}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage setMaterialParam(String value) {
            values.put("set_material_param", value);
            return this;
        }

        /**
         * {@code on_part_stage.*.set_material_param}. Toute valeur de JSON, vérifiée par {@link DefinitionDocument}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage setMaterialParam(Object value) {
            values.put("set_material_param", value);
            return this;
        }

        /**
         * {@code on_part_stage.*.set_variable}.
         *
         * @param variable clé de la table
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage setVariable(String variable, double value) {
            Map<String, Object> table = object(values, "set_variable");
            table.put(Objects.requireNonNull(variable, "variable"), value);
            return this;
        }

        /**
         * {@code on_part_stage.*.show_node}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage showNode(boolean value) {
            values.put("show_node", value);
            return this;
        }

        /**
         * {@code on_part_stage.*.show_node}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage showNode(double value) {
            values.put("show_node", value);
            return this;
        }

        /**
         * {@code on_part_stage.*.show_node}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage showNode(String value) {
            values.put("show_node", value);
            return this;
        }

        /**
         * {@code on_part_stage.*.show_node}. Toute valeur de JSON, vérifiée par {@link DefinitionDocument}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage showNode(Object value) {
            values.put("show_node", value);
            return this;
        }

        /**
         * {@code on_part_stage.*.spawn_debris}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage spawnDebris(String value) {
            values.put("spawn_debris", Objects.requireNonNull(value, "spawn_debris"));
            return this;
        }

        /**
         * {@code on_part_stage.*.swap_mesh} : nom (mesh) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public OnPartStage swapMesh(String value) {
            values.put("swap_mesh", Objects.requireNonNull(value, "swap_mesh"));
            return this;
        }
    }

    /** {@code particles[].collision_proxies[]}. */
    @Experimental
    public static final class ParticleCollisionProxy {

        private final Map<String, Object> values;

        private ParticleCollisionProxy(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code particles[].collision_proxies[].height}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public ParticleCollisionProxy height(double value) {
            values.put("height", value);
            return this;
        }

        /**
         * {@code particles[].collision_proxies[].node} : nom (node) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public ParticleCollisionProxy node(String value) {
            values.put("node", Objects.requireNonNull(value, "node"));
            return this;
        }

        /**
         * {@code particles[].collision_proxies[].radius}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public ParticleCollisionProxy radius(double value) {
            values.put("radius", value);
            return this;
        }

        /**
         * {@code particles[].collision_proxies[].type}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public ParticleCollisionProxy type(String value) {
            values.put("type", Objects.requireNonNull(value, "type"));
            return this;
        }
    }

    /** {@code particles[].from}. */
    @Experimental
    public static final class ParticleFrom {

        private final Map<String, Object> values;

        private ParticleFrom(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code particles[].from.assembly}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public ParticleFrom assembly(String value) {
            values.put("assembly", Objects.requireNonNull(value, "assembly"));
            return this;
        }

        /**
         * {@code particles[].from.socket} : nom (socket) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public ParticleFrom socket(String value) {
            values.put("socket", Objects.requireNonNull(value, "socket"));
            return this;
        }
    }

    /** {@code particles[].to}. */
    @Experimental
    public static final class ParticleTo {

        private final Map<String, Object> values;

        private ParticleTo(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code particles[].to.attachment}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public ParticleTo attachment(String value) {
            values.put("attachment", Objects.requireNonNull(value, "attachment"));
            return this;
        }
    }

    /** {@code particles[]}. */
    @Experimental
    public static final class Particle {

        private final Map<String, Object> values;

        private Particle(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code particles[].anchors} : nom (node) dans le modèle.
         *
         * @param items les valeurs, dans l'ordre
         * @return ce réglage, pour enchaîner
         */
        public Particle anchors(String... items) {
            values.put("anchors", texts(items));
            return this;
        }

        /**
         * {@code particles[].authority} : parmi {@code visual}, {@code server_simple}, {@code server_full}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Particle authority(String value) {
            values.put("authority", Objects.requireNonNull(value, "authority"));
            return this;
        }

        /**
         * {@code particles[].bending}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Particle bending(double value) {
            values.put("bending", value);
            return this;
        }

        /**
         * {@code particles[].break_force}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Particle breakForce(double value) {
            values.put("break_force", value);
            return this;
        }

        /**
         * {@code particles[].collision_proxies}.
         *
         * @param config règle l'élément, ajouté à la suite des précédents
         * @return ce réglage, pour enchaîner
         */
        public Particle collisionProxy(Consumer<ParticleCollisionProxy> config) {
            Map<String, Object> item = new LinkedHashMap<>();
            Objects.requireNonNull(config, "config").accept(new ParticleCollisionProxy(item));
            list(values, "collision_proxies").add(item);
            return this;
        }

        /**
         * {@code particles[].damping}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Particle damping(double value) {
            values.put("damping", value);
            return this;
        }

        /**
         * {@code particles[].from}.
         *
         * @param config règle l'objet ; rappelée, elle complète le même
         * @return ce réglage, pour enchaîner
         */
        public Particle from(Consumer<ParticleFrom> config) {
            Map<String, Object> nested = object(values, "from");
            Objects.requireNonNull(config, "config").accept(new ParticleFrom(nested));
            return this;
        }

        /**
         * {@code particles[].kind} : parmi {@code cloth}, {@code rope}, {@code cable}, {@code net}, {@code soft}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Particle kind(String value) {
            values.put("kind", Objects.requireNonNull(value, "kind"));
            return this;
        }

        /**
         * {@code particles[].mass_per_particle}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Particle massPerParticle(double value) {
            values.put("mass_per_particle", value);
            return this;
        }

        /**
         * {@code particles[].material}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Particle material(String value) {
            values.put("material", Objects.requireNonNull(value, "material"));
            return this;
        }

        /**
         * {@code particles[].max_distance_from_anchor}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Particle maxDistanceFromAnchor(double value) {
            values.put("max_distance_from_anchor", value);
            return this;
        }

        /**
         * {@code particles[].max_length}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Particle maxLength(double value) {
            values.put("max_length", value);
            return this;
        }

        /**
         * {@code particles[].mesh} : nom (mesh) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Particle mesh(String value) {
            values.put("mesh", Objects.requireNonNull(value, "mesh"));
            return this;
        }

        /**
         * {@code particles[].name} : déclare un nom (particles).
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Particle name(String value) {
            values.put("name", Objects.requireNonNull(value, "name"));
            return this;
        }

        /**
         * {@code particles[].radius}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Particle radius(double value) {
            values.put("radius", value);
            return this;
        }

        /**
         * {@code particles[].segments}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Particle segments(long value) {
            values.put("segments", value);
            return this;
        }

        /**
         * {@code particles[].self_collide}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Particle selfCollide(boolean value) {
            values.put("self_collide", value);
            return this;
        }

        /**
         * {@code particles[].server_segments}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Particle serverSegments(long value) {
            values.put("server_segments", value);
            return this;
        }

        /**
         * {@code particles[].stiffness}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Particle stiffness(double value) {
            values.put("stiffness", value);
            return this;
        }

        /**
         * {@code particles[].tear_threshold}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Particle tearThreshold(double value) {
            values.put("tear_threshold", value);
            return this;
        }

        /**
         * {@code particles[].to}.
         *
         * @param config règle l'objet ; rappelée, elle complète le même
         * @return ce réglage, pour enchaîner
         */
        public Particle to(Consumer<ParticleTo> config) {
            Map<String, Object> nested = object(values, "to");
            Objects.requireNonNull(config, "config").accept(new ParticleTo(nested));
            return this;
        }

        /**
         * {@code particles[].wind_influence}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Particle windInfluence(double value) {
            values.put("wind_influence", value);
            return this;
        }
    }

    /** {@code parts[].meshes}. */
    @Experimental
    public static final class PartMeshes {

        private final Map<String, Object> values;

        private PartMeshes(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code parts[].meshes.damaged} : {@code null} : aucun.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public PartMeshes damaged(String value) {
            values.put("damaged", value);
            return this;
        }

        /**
         * {@code parts[].meshes.destroyed} : {@code null} : aucun.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public PartMeshes destroyed(String value) {
            values.put("destroyed", value);
            return this;
        }

        /**
         * {@code parts[].meshes.intact} : {@code null} : aucun.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public PartMeshes intact(String value) {
            values.put("intact", value);
            return this;
        }
    }

    /** {@code parts[]}. */
    @Experimental
    public static final class Part {

        private final Map<String, Object> values;

        private Part(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code parts[].collider_variants}.
         *
         * @param name clé de la table
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Part colliderVariant(String name, String value) {
            Map<String, Object> table = object(values, "collider_variants");
            table.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "collider_variants"));
            return this;
        }

        /**
         * {@code parts[].deform_regions} : nom (region) dans le modèle.
         *
         * @param items les valeurs, dans l'ordre
         * @return ce réglage, pour enchaîner
         */
        public Part deformRegions(String... items) {
            values.put("deform_regions", texts(items));
            return this;
        }

        /**
         * {@code parts[].detach_threshold} : au moins 0 ; au plus 1.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Part detachThreshold(double value) {
            values.put("detach_threshold", value);
            return this;
        }

        /**
         * {@code parts[].flags}.
         *
         * @param items les valeurs, dans l'ordre
         * @return ce réglage, pour enchaîner
         */
        public Part flags(String... items) {
            values.put("flags", texts(items));
            return this;
        }

        /**
         * {@code parts[].health}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Part health(double value) {
            values.put("health", value);
            return this;
        }

        /**
         * {@code parts[].mass} : au moins 0.001 ; au plus 1000000.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Part mass(double value) {
            values.put("mass", value);
            return this;
        }

        /**
         * {@code parts[].material}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Part material(String value) {
            values.put("material", Objects.requireNonNull(value, "material"));
            return this;
        }

        /**
         * {@code parts[].meshes}.
         *
         * @param config règle l'objet ; rappelée, elle complète le même
         * @return ce réglage, pour enchaîner
         */
        public Part meshes(Consumer<PartMeshes> config) {
            Map<String, Object> nested = object(values, "meshes");
            Objects.requireNonNull(config, "config").accept(new PartMeshes(nested));
            return this;
        }

        /**
         * {@code parts[].name} : déclare un nom (part).
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Part name(String value) {
            values.put("name", Objects.requireNonNull(value, "name"));
            return this;
        }

        /**
         * {@code parts[].propagation} : au moins 0 ; au plus 1.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Part propagation(double value) {
            values.put("propagation", value);
            return this;
        }

        /**
         * {@code parts[].repair_item}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Part repairItem(String value) {
            values.put("repair_item", Objects.requireNonNull(value, "repair_item"));
            return this;
        }

        /**
         * {@code parts[].root_node} : nom (node) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Part rootNode(String value) {
            values.put("root_node", Objects.requireNonNull(value, "root_node"));
            return this;
        }

        /**
         * {@code parts[].structural_capacity}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Part structuralCapacity(double value) {
            values.put("structural_capacity", value);
            return this;
        }
    }

    /** {@code physics}. */
    @Experimental
    public static final class Physics {

        private final Map<String, Object> values;

        private Physics(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code physics.angular_damping} : au moins 0.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Physics angularDamping(double value) {
            values.put("angular_damping", value);
            return this;
        }

        /**
         * {@code physics.ccd}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Physics ccd(boolean value) {
            values.put("ccd", value);
            return this;
        }

        /**
         * {@code physics.center_of_mass} : 3 éléments.
         *
         * @param items les valeurs, dans l'ordre
         * @return ce réglage, pour enchaîner
         */
        public Physics centerOfMass(double... items) {
            values.put("center_of_mass", doubles(items));
            return this;
        }

        /**
         * {@code physics.collides_with}.
         *
         * @param items les valeurs, dans l'ordre
         * @return ce réglage, pour enchaîner
         */
        public Physics collidesWith(String... items) {
            values.put("collides_with", texts(items));
            return this;
        }

        /**
         * {@code physics.collision_group} : parmi {@code world}, {@code assembly}, {@code part}, {@code debris},
         * {@code entity_proxy}, {@code sensor}, {@code particle}, {@code debug} ; ou un identifiant
         * {@code <ns>:<chemin>}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Physics collisionGroup(String value) {
            values.put("collision_group", Objects.requireNonNull(value, "collision_group"));
            return this;
        }

        /**
         * {@code physics.linear_damping} : au moins 0.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Physics linearDamping(double value) {
            values.put("linear_damping", value);
            return this;
        }

        /**
         * {@code physics.mass} : au moins 0.001 ; au plus 1000000.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Physics mass(double value) {
            values.put("mass", value);
            return this;
        }

        /**
         * {@code physics.self_collision}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Physics selfCollision(boolean value) {
            values.put("self_collision", value);
            return this;
        }
    }

    /** {@code powertrain.differential}. */
    @Experimental
    public static final class PowertrainDifferential {

        private final Map<String, Object> values;

        private PowertrainDifferential(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code powertrain.differential.front_rear_split}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public PowertrainDifferential frontRearSplit(double value) {
            values.put("front_rear_split", value);
            return this;
        }

        /**
         * {@code powertrain.differential.lock}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public PowertrainDifferential lock(double value) {
            values.put("lock", value);
            return this;
        }

        /**
         * {@code powertrain.differential.type} : parmi {@code open}, {@code locked}, {@code limited_slip}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public PowertrainDifferential type(String value) {
            values.put("type", Objects.requireNonNull(value, "type"));
            return this;
        }
    }

    /** {@code powertrain.engine}. */
    @Experimental
    public static final class PowertrainEngine {

        private final Map<String, Object> values;

        private PowertrainEngine(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code powertrain.engine.braking_torque}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public PowertrainEngine brakingTorque(double value) {
            values.put("braking_torque", value);
            return this;
        }

        /**
         * {@code powertrain.engine.idle_rpm}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public PowertrainEngine idleRpm(double value) {
            values.put("idle_rpm", value);
            return this;
        }

        /**
         * {@code powertrain.engine.inertia}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public PowertrainEngine inertia(double value) {
            values.put("inertia", value);
            return this;
        }

        /**
         * {@code powertrain.engine.max_rpm}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public PowertrainEngine maxRpm(double value) {
            values.put("max_rpm", value);
            return this;
        }

        /**
         * {@code powertrain.engine.torque_curve}.
         *
         * @param rows les lignes, une par élément
         * @return ce réglage, pour enchaîner
         */
        public PowertrainEngine torqueCurve(double[]... rows) {
            values.put("torque_curve", rows(rows));
            return this;
        }
    }

    /** {@code powertrain.traction_control}. */
    @Experimental
    public static final class PowertrainTractionControl {

        private final Map<String, Object> values;

        private PowertrainTractionControl(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code powertrain.traction_control.enabled}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public PowertrainTractionControl enabled(boolean value) {
            values.put("enabled", value);
            return this;
        }

        /**
         * {@code powertrain.traction_control.slip_threshold}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public PowertrainTractionControl slipThreshold(double value) {
            values.put("slip_threshold", value);
            return this;
        }
    }

    /** {@code powertrain.transmission}. */
    @Experimental
    public static final class PowertrainTransmission {

        private final Map<String, Object> values;

        private PowertrainTransmission(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code powertrain.transmission.auto_shift_down_rpm}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public PowertrainTransmission autoShiftDownRpm(double value) {
            values.put("auto_shift_down_rpm", value);
            return this;
        }

        /**
         * {@code powertrain.transmission.auto_shift_up_rpm}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public PowertrainTransmission autoShiftUpRpm(double value) {
            values.put("auto_shift_up_rpm", value);
            return this;
        }

        /**
         * {@code powertrain.transmission.efficiency}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public PowertrainTransmission efficiency(double value) {
            values.put("efficiency", value);
            return this;
        }

        /**
         * {@code powertrain.transmission.final_drive}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public PowertrainTransmission finalDrive(double value) {
            values.put("final_drive", value);
            return this;
        }

        /**
         * {@code powertrain.transmission.gears}.
         *
         * @param items les valeurs, dans l'ordre
         * @return ce réglage, pour enchaîner
         */
        public PowertrainTransmission gears(double... items) {
            values.put("gears", doubles(items));
            return this;
        }

        /**
         * {@code powertrain.transmission.shift_time}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public PowertrainTransmission shiftTime(double value) {
            values.put("shift_time", value);
            return this;
        }

        /**
         * {@code powertrain.transmission.type}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public PowertrainTransmission type(String value) {
            values.put("type", Objects.requireNonNull(value, "type"));
            return this;
        }
    }

    /** {@code powertrain}. */
    @Experimental
    public static final class Powertrain {

        private final Map<String, Object> values;

        private Powertrain(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code powertrain.differential}.
         *
         * @param config règle l'objet ; rappelée, elle complète le même
         * @return ce réglage, pour enchaîner
         */
        public Powertrain differential(Consumer<PowertrainDifferential> config) {
            Map<String, Object> nested = object(values, "differential");
            Objects.requireNonNull(config, "config").accept(new PowertrainDifferential(nested));
            return this;
        }

        /**
         * {@code powertrain.drive_wheels} : nom (wheel) dans le modèle.
         *
         * @param items les valeurs, dans l'ordre
         * @return ce réglage, pour enchaîner
         */
        public Powertrain driveWheels(String... items) {
            values.put("drive_wheels", texts(items));
            return this;
        }

        /**
         * {@code powertrain.engine}.
         *
         * @param config règle l'objet ; rappelée, elle complète le même
         * @return ce réglage, pour enchaîner
         */
        public Powertrain engine(Consumer<PowertrainEngine> config) {
            Map<String, Object> nested = object(values, "engine");
            Objects.requireNonNull(config, "config").accept(new PowertrainEngine(nested));
            return this;
        }

        /**
         * {@code powertrain.traction_control}.
         *
         * @param config règle l'objet ; rappelée, elle complète le même
         * @return ce réglage, pour enchaîner
         */
        public Powertrain tractionControl(Consumer<PowertrainTractionControl> config) {
            Map<String, Object> nested = object(values, "traction_control");
            Objects.requireNonNull(config, "config").accept(new PowertrainTractionControl(nested));
            return this;
        }

        /**
         * {@code powertrain.transmission}.
         *
         * @param config règle l'objet ; rappelée, elle complète le même
         * @return ce réglage, pour enchaîner
         */
        public Powertrain transmission(Consumer<PowertrainTransmission> config) {
            Map<String, Object> nested = object(values, "transmission");
            Objects.requireNonNull(config, "config").accept(new PowertrainTransmission(nested));
            return this;
        }
    }

    /** {@code procedural[]}. */
    @Experimental
    public static final class Procedural {

        private final Map<String, Object> values;

        private Procedural(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code procedural[].axis} : parmi {@code x}, {@code y}, {@code z}, {@code r}, {@code g}, {@code b}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Procedural axis(String value) {
            values.put("axis", Objects.requireNonNull(value, "axis"));
            return this;
        }

        /**
         * {@code procedural[].bone} : nom (bone) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Procedural bone(String value) {
            values.put("bone", Objects.requireNonNull(value, "bone"));
            return this;
        }

        /**
         * {@code procedural[].channel} : parmi {@code rotation}, {@code translation}, {@code visibility},
         * {@code emissive}, {@code color}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Procedural channel(String value) {
            values.put("channel", Objects.requireNonNull(value, "channel"));
            return this;
        }

        /**
         * {@code procedural[].clamp} : 2 éléments.
         *
         * @param items les valeurs, dans l'ordre
         * @return ce réglage, pour enchaîner
         */
        public Procedural clamp(double... items) {
            values.put("clamp", doubles(items));
            return this;
        }

        /**
         * {@code procedural[].gate}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Procedural gate(String value) {
            values.put("gate", Objects.requireNonNull(value, "gate"));
            return this;
        }

        /**
         * {@code procedural[].map}.
         *
         * @param rows les lignes, une par élément
         * @return ce réglage, pour enchaîner
         */
        public Procedural map(double[]... rows) {
            values.put("map", rows(rows));
            return this;
        }

        /**
         * {@code procedural[].node} : nom (node) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Procedural node(String value) {
            values.put("node", Objects.requireNonNull(value, "node"));
            return this;
        }

        /**
         * {@code procedural[].period} : plus de 0.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Procedural period(double value) {
            values.put("period", value);
            return this;
        }

        /**
         * {@code procedural[].scale}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Procedural scale(double value) {
            values.put("scale", value);
            return this;
        }

        /**
         * {@code procedural[].smoothing} : au moins 0.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Procedural smoothing(double value) {
            values.put("smoothing", value);
            return this;
        }

        /**
         * {@code procedural[].source}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Procedural source(String value) {
            values.put("source", Objects.requireNonNull(value, "source"));
            return this;
        }
    }

    /** {@code render}. */
    @Experimental
    public static final class Render {

        private final Map<String, Object> values;

        private Render(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code render.decals}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Render decals(boolean value) {
            values.put("decals", value);
            return this;
        }

        /**
         * {@code render.lod_bias} : au moins -2 ; au plus 3.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Render lodBias(long value) {
            values.put("lod_bias", value);
            return this;
        }

        /**
         * {@code render.max_distance}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Render maxDistance(double value) {
            values.put("max_distance", value);
            return this;
        }

        /**
         * {@code render.shadow} : parmi {@code map}, {@code contact}, {@code none}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Render shadow(String value) {
            values.put("shadow", Objects.requireNonNull(value, "shadow"));
            return this;
        }
    }

    /** {@code seats[].bindings[]}. */
    @Experimental
    public static final class SeatBinding {

        private final Map<String, Object> values;

        private SeatBinding(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code seats[].bindings[].command} : au moins 1 ; au plus 8.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public SeatBinding command(long value) {
            values.put("command", value);
            return this;
        }

        /**
         * {@code seats[].bindings[].label}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public SeatBinding label(String value) {
            values.put("label", Objects.requireNonNull(value, "label"));
            return this;
        }

        /**
         * {@code seats[].bindings[].mode} : parmi {@code toggle}, {@code hold}, {@code press}, {@code cycle}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public SeatBinding mode(String value) {
            values.put("mode", Objects.requireNonNull(value, "mode"));
            return this;
        }

        /**
         * {@code seats[].bindings[].values}.
         *
         * @param items les valeurs, dans l'ordre ; toute valeur de JSON
         * @return ce réglage, pour enchaîner
         */
        public SeatBinding values(Object... items) {
            values.put("values", new ArrayList<>(Arrays.asList(items)));
            return this;
        }

        /**
         * {@code seats[].bindings[].variable} : nom (variable) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public SeatBinding variable(String value) {
            values.put("variable", Objects.requireNonNull(value, "variable"));
            return this;
        }
    }

    /** {@code seats[]}. */
    @Experimental
    public static final class Seat {

        private final Map<String, Object> values;

        private Seat(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code seats[].bindings} : au plus 8 éléments.
         *
         * @param command le champ obligatoire {@code command}
         * @param variable le champ obligatoire {@code variable}
         * @param mode le champ obligatoire {@code mode}
         * @param config règle l'élément, ajouté à la suite des précédents
         * @return ce réglage, pour enchaîner
         */
        public Seat binding(long command, String variable, String mode, Consumer<SeatBinding> config) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("command", command);
            item.put("variable", Objects.requireNonNull(variable, "variable"));
            item.put("mode", Objects.requireNonNull(mode, "mode"));
            Objects.requireNonNull(config, "config").accept(new SeatBinding(item));
            list(values, "bindings").add(item);
            return this;
        }

        /**
         * {@code seats[].bindings} : au plus 8 éléments.
         *
         * @param command le champ obligatoire {@code command}
         * @param variable le champ obligatoire {@code variable}
         * @param mode le champ obligatoire {@code mode}
         * @return ce réglage, pour enchaîner
         */
        public Seat binding(long command, String variable, String mode) {
            return binding(command, variable, mode, item -> { });
        }

        /**
         * {@code seats[].controls}.
         *
         * @param items les valeurs, dans l'ordre
         * @return ce réglage, pour enchaîner
         */
        public Seat controls(String... items) {
            values.put("controls", texts(items));
            return this;
        }

        /**
         * {@code seats[].exit_offsets}.
         *
         * @param rows les lignes, une par élément
         * @return ce réglage, pour enchaîner
         */
        public Seat exitOffsets(double[]... rows) {
            values.put("exit_offsets", rows(rows));
            return this;
        }

        /**
         * {@code seats[].name} : déclare un nom (seat).
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Seat name(String value) {
            values.put("name", Objects.requireNonNull(value, "name"));
            return this;
        }

        /**
         * {@code seats[].node} : nom (node) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Seat node(String value) {
            values.put("node", Objects.requireNonNull(value, "node"));
            return this;
        }

        /**
         * {@code seats[].role} : parmi {@code driver}, {@code passenger}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Seat role(String value) {
            values.put("role", Objects.requireNonNull(value, "role"));
            return this;
        }
    }

    /** {@code steering}. */
    @Experimental
    public static final class Steering {

        private final Map<String, Object> values;

        private Steering(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code steering.ackermann}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Steering ackermann(double value) {
            values.put("ackermann", value);
            return this;
        }

        /**
         * {@code steering.max_angle}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Steering maxAngle(double value) {
            values.put("max_angle", value);
            return this;
        }

        /**
         * {@code steering.return_speed}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Steering returnSpeed(double value) {
            values.put("return_speed", value);
            return this;
        }

        /**
         * {@code steering.speed}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Steering speed(double value) {
            values.put("speed", value);
            return this;
        }

        /**
         * {@code steering.speed_sensitivity}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Steering speedSensitivity(double value) {
            values.put("speed_sensitivity", value);
            return this;
        }

        /**
         * {@code steering.steered_wheels} : nom (wheel) dans le modèle.
         *
         * @param items les valeurs, dans l'ordre
         * @return ce réglage, pour enchaîner
         */
        public Steering steeredWheels(String... items) {
            values.put("steered_wheels", texts(items));
            return this;
        }
    }

    /** {@code structural_links[]}. */
    @Experimental
    public static final class StructuralLink {

        private final Map<String, Object> values;

        private StructuralLink(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code structural_links[].a} : nom (part) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public StructuralLink a(String value) {
            values.put("a", Objects.requireNonNull(value, "a"));
            return this;
        }

        /**
         * {@code structural_links[].b} : nom (part) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public StructuralLink b(String value) {
            values.put("b", Objects.requireNonNull(value, "b"));
            return this;
        }

        /**
         * {@code structural_links[].capacity} : plus de 0.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public StructuralLink capacity(double value) {
            values.put("capacity", value);
            return this;
        }

        /**
         * {@code structural_links[].flags}.
         *
         * @param items les valeurs, dans l'ordre
         * @return ce réglage, pour enchaîner
         */
        public StructuralLink flags(String... items) {
            values.put("flags", texts(items));
            return this;
        }

        /**
         * {@code structural_links[].joint} : nom (joint) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public StructuralLink joint(String value) {
            values.put("joint", Objects.requireNonNull(value, "joint"));
            return this;
        }

        /**
         * {@code structural_links[].kind} : parmi {@code weld}, {@code bolt}, {@code hinge}, {@code glue},
         * {@code organic}, {@code snap}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public StructuralLink kind(String value) {
            values.put("kind", Objects.requireNonNull(value, "kind"));
            return this;
        }

        /**
         * {@code structural_links[].name} : déclare un nom (link).
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public StructuralLink name(String value) {
            values.put("name", Objects.requireNonNull(value, "name"));
            return this;
        }

        /**
         * {@code structural_links[].propagation} : au moins 0 ; au plus 1.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public StructuralLink propagation(double value) {
            values.put("propagation", value);
            return this;
        }

        /**
         * {@code structural_links[].shear} : plus de 0.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public StructuralLink shear(double value) {
            values.put("shear", value);
            return this;
        }

        /**
         * {@code structural_links[].tensile} : plus de 0.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public StructuralLink tensile(double value) {
            values.put("tensile", value);
            return this;
        }

        /**
         * {@code structural_links[].torque} : plus de 0.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public StructuralLink torque(double value) {
            values.put("torque", value);
            return this;
        }
    }

    /** {@code tuning.modifiers.*}. */
    @Experimental
    public static final class TuningModifier {

        private final Map<String, Object> values;

        private TuningModifier(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code tuning.modifiers.*.operation} : parmi {@code add}, {@code multiply_base}, {@code multiply_total}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public TuningModifier operation(String value) {
            values.put("operation", Objects.requireNonNull(value, "operation"));
            return this;
        }

        /**
         * {@code tuning.modifiers.*.parameter}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public TuningModifier parameter(String value) {
            values.put("parameter", Objects.requireNonNull(value, "parameter"));
            return this;
        }

        /**
         * {@code tuning.modifiers.*.scale}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public TuningModifier scale(double value) {
            values.put("scale", value);
            return this;
        }

        /**
         * {@code tuning.modifiers.*.values}.
         *
         * @param items les valeurs, dans l'ordre
         * @return ce réglage, pour enchaîner
         */
        public TuningModifier values(double... items) {
            values.put("values", doubles(items));
            return this;
        }

        /**
         * {@code tuning.modifiers.*.variable} : nom (variable) dans le modèle.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public TuningModifier variable(String value) {
            values.put("variable", Objects.requireNonNull(value, "variable"));
            return this;
        }
    }

    /** {@code tuning.ranges.*}. */
    @Experimental
    public static final class TuningRange {

        private final Map<String, Object> values;

        private TuningRange(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code tuning.ranges.*.max}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public TuningRange max(double value) {
            values.put("max", value);
            return this;
        }

        /**
         * {@code tuning.ranges.*.min}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public TuningRange min(double value) {
            values.put("min", value);
            return this;
        }
    }

    /** {@code tuning}. */
    @Experimental
    public static final class Tuning {

        private final Map<String, Object> values;

        private Tuning(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code tuning.modifiers}.
         *
         * @param name clé de la table
         * @param parameter le champ obligatoire {@code parameter}
         * @param operation le champ obligatoire {@code operation}
         * @param variable le champ obligatoire {@code variable}
         * @param config règle l'élément ; rappelée avec les mêmes clés, elle complète le même
         * @return ce réglage, pour enchaîner
         */
        public Tuning modifier(String name, String parameter, String operation, String variable, Consumer<TuningModifier> config) {
            Map<String, Object> item = path(values, "modifiers", name);
            item.put("parameter", Objects.requireNonNull(parameter, "parameter"));
            item.put("operation", Objects.requireNonNull(operation, "operation"));
            item.put("variable", Objects.requireNonNull(variable, "variable"));
            Objects.requireNonNull(config, "config").accept(new TuningModifier(item));
            return this;
        }

        /**
         * {@code tuning.modifiers}.
         *
         * @param name clé de la table
         * @param parameter le champ obligatoire {@code parameter}
         * @param operation le champ obligatoire {@code operation}
         * @param variable le champ obligatoire {@code variable}
         * @return ce réglage, pour enchaîner
         */
        public Tuning modifier(String name, String parameter, String operation, String variable) {
            return modifier(name, parameter, operation, variable, item -> { });
        }

        /**
         * {@code tuning.ranges}.
         *
         * @param parameter clé de la table
         * @param config règle l'élément ; rappelée avec les mêmes clés, elle complète le même
         * @return ce réglage, pour enchaîner
         */
        public Tuning range(String parameter, Consumer<TuningRange> config) {
            Map<String, Object> item = path(values, "ranges", parameter);
            Objects.requireNonNull(config, "config").accept(new TuningRange(item));
            return this;
        }

        /**
         * {@code tuning.ranges}.
         *
         * @param parameter clé de la table
         * @return ce réglage, pour enchaîner
         */
        public Tuning range(String parameter) {
            return range(parameter, item -> { });
        }
    }

    /** {@code variables.*}. */
    @Experimental
    public static final class Variable {

        private final Map<String, Object> values;

        private Variable(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code variables.*.default}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Variable default_(boolean value) {
            values.put("default", value);
            return this;
        }

        /**
         * {@code variables.*.default}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Variable default_(double value) {
            values.put("default", value);
            return this;
        }

        /**
         * {@code variables.*.default}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Variable default_(String value) {
            values.put("default", value);
            return this;
        }

        /**
         * {@code variables.*.default}. Toute valeur de JSON, vérifiée par {@link DefinitionDocument}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Variable default_(Object value) {
            values.put("default", value);
            return this;
        }

        /**
         * {@code variables.*.max}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Variable max(double value) {
            values.put("max", value);
            return this;
        }

        /**
         * {@code variables.*.min}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Variable min(double value) {
            values.put("min", value);
            return this;
        }

        /**
         * {@code variables.*.persist}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Variable persist(boolean value) {
            values.put("persist", value);
            return this;
        }

        /**
         * {@code variables.*.replicate} : parmi {@code all}, {@code driver}, {@code none}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Variable replicate(String value) {
            values.put("replicate", Objects.requireNonNull(value, "replicate"));
            return this;
        }

        /**
         * {@code variables.*.scope} : parmi {@code assembly}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Variable scope(String value) {
            values.put("scope", Objects.requireNonNull(value, "scope"));
            return this;
        }

        /**
         * {@code variables.*.type} : parmi {@code bool}, {@code int}, {@code float}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Variable type(String value) {
            values.put("type", Objects.requireNonNull(value, "type"));
            return this;
        }

        /**
         * {@code variables.*.write} : parmi {@code driver}, {@code seat}, {@code none}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Variable write(String value) {
            values.put("write", Objects.requireNonNull(value, "write"));
            return this;
        }
    }

    /** {@code vehicle}. */
    @Experimental
    public static final class Vehicle {

        private final Map<String, Object> values;

        private Vehicle(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code vehicle.controllers} : au plus 8 éléments.
         *
         * @param items les valeurs, dans l'ordre
         * @return ce réglage, pour enchaîner
         */
        public Vehicle controllers(String... items) {
            values.put("controllers", texts(items));
            return this;
        }
    }

    /** {@code wear}. */
    @Experimental
    public static final class Wear {

        private final Map<String, Object> values;

        private Wear(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code wear.profile}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Wear profile(String value) {
            values.put("profile", Objects.requireNonNull(value, "profile"));
            return this;
        }
    }

    /** {@code wheels[].suspension}. */
    @Experimental
    public static final class WheelSuspension {

        private final Map<String, Object> values;

        private WheelSuspension(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code wheels[].suspension.anti_roll}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public WheelSuspension antiRoll(double value) {
            values.put("anti_roll", value);
            return this;
        }

        /**
         * {@code wheels[].suspension.damping_compression}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public WheelSuspension dampingCompression(double value) {
            values.put("damping_compression", value);
            return this;
        }

        /**
         * {@code wheels[].suspension.damping_rebound}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public WheelSuspension dampingRebound(double value) {
            values.put("damping_rebound", value);
            return this;
        }

        /**
         * {@code wheels[].suspension.max_force}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public WheelSuspension maxForce(double value) {
            values.put("max_force", value);
            return this;
        }

        /**
         * {@code wheels[].suspension.max_travel}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public WheelSuspension maxTravel(double value) {
            values.put("max_travel", value);
            return this;
        }

        /**
         * {@code wheels[].suspension.rest_length}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public WheelSuspension restLength(double value) {
            values.put("rest_length", value);
            return this;
        }

        /**
         * {@code wheels[].suspension.stiffness}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public WheelSuspension stiffness(double value) {
            values.put("stiffness", value);
            return this;
        }
    }

    /** {@code wheels[]}. */
    @Experimental
    public static final class Wheel {

        private final Map<String, Object> values;

        private Wheel(Map<String, Object> values) {
            this.values = values;
        }

        /**
         * {@code wheels[].axle}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Wheel axle(String value) {
            values.put("axle", Objects.requireNonNull(value, "axle"));
            return this;
        }

        /**
         * {@code wheels[].mass}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Wheel mass(double value) {
            values.put("mass", value);
            return this;
        }

        /**
         * {@code wheels[].node} : déclare un nom (wheel).
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Wheel node(String value) {
            values.put("node", Objects.requireNonNull(value, "node"));
            return this;
        }

        /**
         * {@code wheels[].powered}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Wheel powered(boolean value) {
            values.put("powered", value);
            return this;
        }

        /**
         * {@code wheels[].radius}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Wheel radius(double value) {
            values.put("radius", value);
            return this;
        }

        /**
         * {@code wheels[].steering}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Wheel steering(boolean value) {
            values.put("steering", value);
            return this;
        }

        /**
         * {@code wheels[].suspension}.
         *
         * @param config règle l'objet ; rappelée, elle complète le même
         * @return ce réglage, pour enchaîner
         */
        public Wheel suspension(Consumer<WheelSuspension> config) {
            Map<String, Object> nested = object(values, "suspension");
            Objects.requireNonNull(config, "config").accept(new WheelSuspension(nested));
            return this;
        }

        /**
         * {@code wheels[].tire}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Wheel tire(String value) {
            values.put("tire", Objects.requireNonNull(value, "tire"));
            return this;
        }

        /**
         * {@code wheels[].width}.
         *
         * @param value la valeur
         * @return ce réglage, pour enchaîner
         */
        public Wheel width(double value) {
            values.put("width", value);
            return this;
        }
    }
}
