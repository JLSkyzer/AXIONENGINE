package dev.axion.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Les sièges et les interactions d'une definition compilée (ADR-158 D1, D6) : ce que la montée, la
 * descente et le clic sur un node liront, chacun à l'index du node que le natif a résolu.
 *
 * <p>Les règles de forme — un siège a un node, un seul conducteur, des contrôles connus, une
 * interaction complète — sont tranchées avant, par {@link DefinitionChecker} : une definition qui
 * arrive ici les respecte. Logique pure, sans Forge.
 *
 * @param seats les sièges, dans l'ordre de la definition
 * @param interactions les interactions, dans l'ordre de la definition
 * @param warnings les actions déclarées qui restent sans effet jusqu'à leur chantier (ADR-158 D6)
 */
public record Seating(List<Seat> seats, List<Interaction> interactions, List<String> warnings) {

    /** Aucun siège, aucune interaction. */
    public static final Seating NONE = new Seating(List.of(), List.of(), List.of());

    /** Copie les listes : une table compilée ne change plus. */
    public Seating {
        seats = List.copyOf(seats);
        interactions = List.copyOf(interactions);
        warnings = List.copyOf(warnings);
    }

    /** Les contrôles qu'un siège peut autoriser, ceux de {@code VehicleInput} (12.8), en bits. */
    public enum Control {
        /** Accélérateur. */
        THROTTLE("throttle"),
        /** Frein. */
        BRAKE("brake"),
        /** Direction. */
        STEER("steer"),
        /** Embrayage. */
        CLUTCH("clutch"),
        /** Frein à main. */
        HANDBRAKE("handbrake"),
        /** Rapport engagé. */
        GEAR("gear");

        private final String key;

        Control(String key) {
            this.key = key;
        }

        /** {@return le nom du contrôle dans une definition} */
        public String key() {
            return key;
        }

        /** {@return le bit du contrôle} */
        public int bit() {
            return 1 << ordinal();
        }

        /**
         * {@return le contrôle de ce nom, ou {@code null}}
         *
         * @param key nom dans une definition
         */
        public static Control of(String key) {
            for (Control control : values()) {
                if (control.key.equals(key)) {
                    return control;
                }
            }
            return null;
        }
    }

    /**
     * Un siège.
     *
     * @param name son nom
     * @param node l'index du node {@code SEAT} qui le porte
     * @param driver vrai pour le siège conducteur
     * @param exits les positions de sortie, dans le repère du siège, dans l'ordre déclaré
     * @param controls les contrôles qu'il autorise, en bits de {@link Control}
     */
    public record Seat(String name, int node, boolean driver, List<float[]> exits, int controls) {

        /** Copie les positions de sortie. */
        public Seat {
            exits = exits.stream().map(float[]::clone).toList();
        }

        /** {@return une copie des positions de sortie} */
        @Override
        public List<float[]> exits() {
            return exits.stream().map(float[]::clone).toList();
        }

        /**
         * {@return vrai si le siège autorise ce contrôle}
         *
         * @param control le contrôle
         */
        public boolean allows(Control control) {
            return (controls & control.bit()) != 0;
        }
    }

    /** Ce qu'une interaction fait, et le chantier qui donnera leur effet aux autres (ADR-158 D6). */
    public enum Action {
        /** S'asseoir dans un siège. */
        SIT("sit", null),
        /** Jouer un clip, puis le rejouer à l'envers. */
        TOGGLE_ANIMATION("toggle_animation", null),
        /** Jouer un clip depuis son début. */
        PLAY_ANIMATION("play_animation", null),
        /** Ouvrir un conteneur. */
        OPEN_CONTAINER("open_container", "un système de conteneurs, qu'aucun chantier ne porte encore"),
        /** Émettre un événement de contenu. */
        EMIT_EVENT("emit_event", "un bus d'événements de contenu, qu'aucun chantier ne porte encore"),
        /** Poser une variable de contenu. */
        SET_VARIABLE("set_variable", "des variables de contenu, qu'aucun chantier ne porte encore"),
        /** Réparer. */
        REPAIR("repair", "le dommage (M6)"),
        /** Attacher. */
        ATTACH("attach", "les attaches (C-48)"),
        /** Détacher. */
        DETACH("detach", "les attaches (C-48)");

        private final String key;
        private final String awaits;

        Action(String key, String awaits) {
            this.key = key;
            this.awaits = awaits;
        }

        /** {@return le nom de l'action dans une definition} */
        public String key() {
            return key;
        }

        /** {@return vrai si l'action a son effet aujourd'hui} */
        public boolean effective() {
            return awaits == null;
        }

        /** {@return ce qui lui donnera son effet, {@code null} si elle l'a} */
        public String awaits() {
            return awaits;
        }

        /**
         * {@return l'action de ce nom, ou {@code null}}
         *
         * @param key nom dans une definition
         */
        public static Action of(String key) {
            for (Action action : values()) {
                if (action.key.equals(key)) {
                    return action;
                }
            }
            return null;
        }
    }

    /**
     * Une interaction.
     *
     * @param node l'index du node qui la porte
     * @param action ce qu'elle fait
     * @param animation l'animation de la definition qu'elle joue, {@code null} sans
     * @param clip le clip de cette animation, son nom s'il n'en déclare pas ; {@code null} sans
     * @param layer la couche de cette animation, 0 si elle n'en déclare pas
     * @param seat l'index du siège où elle assoit, {@code -1} sans
     */
    public record Interaction(int node, Action action, String animation, String clip, int layer, int seat) {}

    /**
     * Ce qu'un clic vise (ADR-158 D2) : une interaction, ou un siège où s'asseoir.
     *
     * @param interaction rang de l'interaction visée, {@code -1} sans
     * @param seat rang du siège visé, {@code -1} sans ; celui d'une interaction {@code sit} si elle en a un
     */
    public record Target(int interaction, int seat) {}

    /**
     * {@return ce que vise un clic sur un node, ou {@code null} : du node touché vers la racine, le
     * premier node qui porte une interaction ou un siège} Sur ce node, l'interaction l'emporte : elle est
     * déclarée pour lui ; un siège seul y assoit. Un clic sur l'assise, le dossier ou la portière s'y
     * rapporte ainsi sans que chaque collider le déclare.
     *
     * @param node le node qui porte le collider touché, {@code -1} sans
     * @param anatomy les nodes de l'asset, pour leurs parents
     */
    public Target target(int node, dev.axion.asset.Anatomy anatomy) {
        int current = node;
        // Une chaîne plus longue que la table serait un cycle, que l'asset refuse : elle s'arrête là.
        for (int steps = 0; current >= 0 && steps <= anatomy.size(); steps++) {
            for (int i = 0; i < interactions.size(); i++) {
                Interaction interaction = interactions.get(i);
                if (interaction.node() == current) {
                    return new Target(i, interaction.action() == Action.SIT ? interaction.seat() : -1);
                }
            }
            int seat = seatAt(current);
            if (seat >= 0) {
                return new Target(-1, seat);
            }
            current = anatomy.parent(current);
        }
        return null;
    }

    /**
     * {@return le premier siège que porte ce node, ou {@code -1}}
     *
     * @param node index d'un node de l'asset
     */
    public int seatAt(int node) {
        for (int i = 0; i < seats.size(); i++) {
            if (seats.get(i).node() == node) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Compile les sièges et les interactions d'une definition acceptée.
     *
     * @param root document de la definition, accepté aux étapes 1 à 3
     * @param references ses références à l'asset, dans l'ordre de leurs rangs ({@link DefinitionEncoder})
     * @param nodes le node que le natif a résolu pour chaque référence, dans le même ordre
     * @return la table
     * @throws IllegalArgumentException si un node déclaré n'a pas été résolu : un défaut de la
     *     frontière, jamais de la definition, que le natif aurait refusée
     */
    static Seating of(JsonObject root, List<DefinitionChecker.AssetReference> references, int[] nodes) {
        Map<String, Integer> byPath = new HashMap<>();
        for (int rank = 0; rank < references.size() && rank < nodes.length; rank++) {
            byPath.put(references.get(rank).path(), nodes[rank]);
        }
        List<Seat> seats = new ArrayList<>();
        Map<String, Integer> seatRanks = new HashMap<>();
        for (JsonObject seat : objects(root, "seats")) {
            String path = "$.seats[" + seats.size() + "]";
            String name = seat.get("name").getAsString();
            int controls = 0;
            if (seat.get("controls") instanceof JsonArray declared) {
                for (JsonElement control : declared) {
                    controls |= Control.of(control.getAsString()).bit();
                }
            }
            List<float[]> exits = new ArrayList<>();
            if (seat.get("exit_offsets") instanceof JsonArray declared) {
                for (JsonElement exit : declared) {
                    JsonArray xyz = exit.getAsJsonArray();
                    exits.add(new float[] {xyz.get(0).getAsFloat(), xyz.get(1).getAsFloat(), xyz.get(2).getAsFloat()});
                }
            }
            seatRanks.putIfAbsent(name, seats.size());
            seats.add(new Seat(name, node(byPath, path + ".node"),
                    "driver".equals(text(seat, "role")), exits, controls));
        }
        List<Interaction> interactions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (JsonObject interaction : objects(root, "interaction")) {
            String path = "$.interaction[" + interactions.size() + "]";
            Action action = Action.of(interaction.get("action").getAsString());
            if (!action.effective()) {
                warnings.add(path + " : l'action « " + action.key() + " » reste sans effet jusqu'à "
                        + action.awaits() + " (ADR-158 D6)");
            }
            String seat = text(interaction, "seat");
            String animation = text(interaction, "animation");
            JsonObject played = animation != null && root.get("animations") instanceof JsonObject animations
                    && animations.get(animation) instanceof JsonObject declared ? declared : null;
            String clip = played != null && text(played, "clip") != null ? text(played, "clip") : animation;
            int layer = played != null && played.has("layer") ? played.get("layer").getAsInt() : 0;
            interactions.add(new Interaction(node(byPath, path + ".node"), action, animation, clip, layer,
                    seat == null ? -1 : seatRanks.getOrDefault(seat, -1)));
        }
        return seats.isEmpty() && interactions.isEmpty() ? NONE : new Seating(seats, interactions, warnings);
    }

    private static int node(Map<String, Integer> byPath, String path) {
        Integer node = byPath.get(path);
        if (node == null || node < 0) {
            throw new IllegalArgumentException(path + " : node non résolu par le natif");
        }
        return node;
    }

    private static List<JsonObject> objects(JsonObject root, String key) {
        List<JsonObject> found = new ArrayList<>();
        if (root.get(key) instanceof JsonArray list) {
            for (JsonElement element : list) {
                if (element instanceof JsonObject object) {
                    found.add(object);
                }
            }
        }
        return found;
    }

    private static String text(JsonObject object, String key) {
        return object.get(key) instanceof JsonPrimitive primitive && primitive.isString()
                ? primitive.getAsString()
                : null;
    }
}
