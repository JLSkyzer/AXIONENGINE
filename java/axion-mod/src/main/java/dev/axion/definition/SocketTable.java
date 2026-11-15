package dev.axion.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Les sockets d'une definition, compilés (ADR-171 D2) : chacun à son node résolu, avec ce qu'il accepte et ses
 * paramètres. Seul le schéma 6 en déclare ; aux schémas 1 à 5, la table est vide — leurs sockets n'étaient que des noms,
 * jamais résolus contre le modèle.
 *
 * @param sockets les sockets, dans l'ordre de la definition
 */
public record SocketTable(List<Spec> sockets) {

    /** Aucun socket. */
    public static final SocketTable NONE = new SocketTable(List.of());

    /** Les types d'attache, dans l'ordre canonique, celui de leur rang sur la frontière (ADR-171 D1). */
    public static final List<String> KINDS = List.copyOf(Sockets.KINDS);

    /** Copie la liste : une table compilée ne change plus. */
    public SocketTable {
        sockets = List.copyOf(sockets);
    }

    /**
     * Un socket compilé. Une longueur, une vitesse ou un cône absents valent {@code NaN} : la valeur se décide à
     * l'attache, avec l'autre socket (Q5).
     *
     * @param name son nom
     * @param node le node du modèle qui le porte
     * @param accepts les types qu'il prend, dans l'ordre canonique ({@link Sockets#KINDS})
     * @param tag sa forme, {@code null} sans
     * @param mates les formes qu'il épouse ; vide : toutes
     * @param maxForce sa force de rupture, en newtons ; l'infini sans
     * @param maxTorque son couple de rupture, en newtons-mètres ; l'infini sans
     * @param misalignment l'écart, en blocs, au-delà duquel l'attache rompt ; {@code NaN} sans
     * @param anchor vrai s'il s'ancre au monde
     * @param cone {@code hitch} : le balancement permis, en degrés ; {@code NaN} sans
     * @param ropeLength {@code rope} : la longueur ; {@code NaN} sans
     * @param winchSpeed {@code winch} : la vitesse, en blocs par seconde ; {@code NaN} sans
     * @param winchMin {@code winch} : la plus courte ; {@code NaN} sans
     * @param winchMax {@code winch} : la plus longue ; {@code NaN} sans
     * @param winchVariable {@code winch} : la variable qui le pilote, {@code null} sans
     * @param magnetVariable {@code magnet} : la variable qui l'allume, {@code null} sans
     * @param power la prise de force : variable de cette definition vers variable de l'autre
     * @param damage la part d'un choc qui traverse l'attache (branchée en M6)
     */
    public record Spec(String name, int node, Set<String> accepts, String tag, Set<String> mates, double maxForce,
            double maxTorque, double misalignment, boolean anchor, double cone, double ropeLength, double winchSpeed,
            double winchMin, double winchMax, String winchVariable, String magnetVariable, Map<String, String> power,
            double damage) {

        /** Copie les collections. */
        public Spec {
            accepts = Set.copyOf(new LinkedHashSet<>(accepts));
            mates = Set.copyOf(mates);
            power = Map.copyOf(power);
        }

        /** {@return vrai si ce socket épouse la forme de {@code other}} Sans {@code mates}, il les épouse toutes. */
        public boolean mates(Spec other) {
            return mates.isEmpty() || (other.tag() != null && mates.contains(other.tag()));
        }
    }

    /** {@return le socket de ce nom, ou vide} */
    public Optional<Spec> named(String name) {
        return sockets.stream().filter(socket -> socket.name().equals(name)).findFirst();
    }

    /** {@return vrai si la table n'a aucun socket} */
    public boolean isEmpty() {
        return sockets.isEmpty();
    }

    /**
     * Compile les sockets d'une definition de schéma 6.
     *
     * @param root la definition, acceptée
     * @param nodes le node résolu de chaque référence à l'asset, par chemin
     * @return la table, vide avant le schéma 6
     * @throws IllegalArgumentException si le node d'un socket n'a pas été résolu : un défaut de la frontière, que le
     *     natif aurait refusé
     */
    static SocketTable of(JsonObject root, Map<String, Integer> nodes) {
        if (!(root.get("sockets") instanceof JsonArray declared) || declared.isEmpty()
                || !(declared.get(0) instanceof JsonObject)) {
            return NONE;
        }
        List<Spec> sockets = new ArrayList<>();
        for (int index = 0; index < declared.size(); index++) {
            JsonObject socket = declared.get(index).getAsJsonObject();
            String path = "$.sockets[" + index + "]";
            Integer node = nodes.get(socket.has("node") ? path + ".node" : path + ".name");
            if (node == null || node < 0) {
                throw new IllegalArgumentException(path + " : node non résolu par le natif");
            }
            Set<String> accepts = new LinkedHashSet<>();
            Set<String> declaredKinds = strings(socket.get("accepts"));
            for (String kind : Sockets.KINDS) {
                if (declaredKinds.contains(kind)) {
                    accepts.add(kind);
                }
            }
            JsonObject winch = object(socket, "winch");
            Map<String, String> power = new LinkedHashMap<>();
            JsonObject transferBlock = object(socket, "transfer");
            if (transferBlock != null && transferBlock.get("power") instanceof JsonObject declaredPower) {
                for (Map.Entry<String, JsonElement> entry : declaredPower.entrySet()) {
                    power.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            sockets.add(new Spec(
                    socket.get("name").getAsString(),
                    node,
                    accepts,
                    socket.has("tag") ? socket.get("tag").getAsString() : null,
                    strings(socket.get("mates")),
                    number(socket, "max_force", Double.POSITIVE_INFINITY),
                    number(socket, "max_torque", Double.POSITIVE_INFINITY),
                    number(socket, "misalignment", Double.NaN),
                    socket.has("anchor") && socket.get("anchor").getAsBoolean(),
                    number(object(socket, "hitch"), "cone", Double.NaN),
                    number(object(socket, "rope"), "length", Double.NaN),
                    number(winch, "speed", Double.NaN),
                    number(winch, "min_length", Double.NaN),
                    number(winch, "max_length", Double.NaN),
                    winch != null && winch.has("variable") ? winch.get("variable").getAsString() : null,
                    object(socket, "magnet") != null && object(socket, "magnet").has("variable")
                            ? object(socket, "magnet").get("variable").getAsString() : null,
                    power,
                    number(transferBlock, "damage", 0.0)));
        }
        return new SocketTable(sockets);
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent != null && parent.get(key) instanceof JsonObject child ? child : null;
    }

    private static double number(JsonObject parent, String key, double fallback) {
        return parent != null && parent.has(key) ? parent.get(key).getAsDouble() : fallback;
    }

    private static Set<String> strings(JsonElement element) {
        Set<String> values = new LinkedHashSet<>();
        if (element instanceof JsonArray array) {
            for (JsonElement value : array) {
                values.add(value.getAsString());
            }
        }
        return values;
    }
}
