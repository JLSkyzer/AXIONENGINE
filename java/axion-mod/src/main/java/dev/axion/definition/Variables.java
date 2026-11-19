package dev.axion.definition;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Les variables qu'une definition déclare (schéma 3, ADR-163 D2) : leur type, leur valeur par défaut, leurs bornes,
 * et qui les garde, les voit et les écrit. Chacune vaut par instance et porte sur l'assembly entière ; elle se lit
 * comme source {@code var.<nom>}. Logique pure, sans Forge.
 *
 * <p>Les règles de forme — type connu, défaut et bornes du bon type, défaut dans les bornes — sont tranchées avant,
 * par {@link DefinitionChecker} : une definition qui arrive ici les respecte.
 *
 * @param declared les variables, dans l'ordre de la definition
 */
public record Variables(List<Variable> declared) {

    /** Aucune variable. */
    public static final Variables NONE = new Variables(List.of());

    /** Variables d'une definition, au plus. */
    public static final int MAX = 64;

    /** Copie la liste : une table compilée ne change plus. */
    public Variables {
        declared = List.copyOf(declared);
    }

    /** Le type d'une variable. Une valeur est toujours un {@code double} ; le type borne ce qu'elle peut valoir. */
    public enum Type {
        /** 0 ou 1. */
        BOOL,
        /** Un entier. */
        INT,
        /** Un réel fini. */
        FLOAT;

        /** {@return le nom du type dans une definition} */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Qui reçoit une variable par les snapshots. */
    public enum Replicate {
        /** Tous les joueurs qui suivent l'assembly. */
        ALL,
        /** Le seul joueur au siège {@code driver}. */
        DRIVER,
        /** Personne : elle reste au serveur. */
        NONE
    }

    /** Ce que couvre une variable (ADR-174 D2). */
    public enum Scope {
        /** Une valeur pour l'assembly entière. */
        ASSEMBLY,
        /** Une valeur par siège (schéma 7) : son occupant l'écrit, {@code var.<nom>} la lit depuis ce siège. */
        SEAT
    }

    /** Qui peut écrire une variable par une entrée : une commande liée à un siège. */
    public enum Write {
        /** Le seul siège {@code driver}. */
        DRIVER,
        /** Tout siège qui la lie. */
        SEAT,
        /** Aucune entrée : seuls l'API et les interactions la posent. */
        NONE
    }

    /**
     * Une variable.
     *
     * @param name son nom, clé de {@code variables}
     * @param type son type
     * @param initial sa valeur par défaut
     * @param min sa borne basse, {@link Double#NEGATIVE_INFINITY} sans
     * @param max sa borne haute, {@link Double#POSITIVE_INFINITY} sans
     * @param persist sauvegardée avec l'assembly
     * @param replicate qui la reçoit
     * @param write qui peut l'écrire par une entrée
     * @param scope ce qu'elle couvre : l'assembly, ou chaque siège
     */
    public record Variable(String name, Type type, double initial, double min, double max, boolean persist,
            Replicate replicate, Write write, Scope scope) {

        /** Une variable de l'assembly entière. */
        public Variable(String name, Type type, double initial, double min, double max, boolean persist,
                Replicate replicate, Write write) {
            this(name, type, initial, min, max, persist, replicate, write, Scope.ASSEMBLY);
        }

        /** {@return vrai si elle a une valeur par siège (ADR-174 D2)} */
        public boolean perSeat() {
            return scope == Scope.SEAT;
        }

        /**
         * {@return pourquoi cette valeur ne lui convient pas, {@code null} si elle convient} Une valeur hors de ses
         * bornes ou de son type est refusée et nommée, jamais tronquée (ADR-163 D2).
         *
         * @param value la valeur
         */
        public String refusal(double value) {
            if (!Double.isFinite(value)) {
                return "variable « " + name + " » : valeur non finie";
            }
            if (type == Type.BOOL && value != 0.0 && value != 1.0) {
                return "variable « " + name + " » : booléen, 0 ou 1 attendu, " + value + " reçu";
            }
            if (type == Type.INT && value != Math.rint(value)) {
                return "variable « " + name + " » : entier attendu, " + value + " reçu";
            }
            if (value < min || value > max) {
                return "variable « " + name + " » : " + value + " hors de [" + min + ", " + max + "]";
            }
            return null;
        }
    }

    /** {@return le nombre de variables} */
    public int size() {
        return declared.size();
    }

    /**
     * {@return le rang d'une variable, {@code -1} si la definition ne la déclare pas}
     *
     * @param name son nom
     */
    public int indexOf(String name) {
        for (int i = 0; i < declared.size(); i++) {
            if (declared.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Ce qu'une source {@code var.<reste>} lit (ADR-174 D2).
     *
     * @param variable rang de la variable
     * @param seat rang du siège nommé ; {@link #READER} : celui du lecteur, pour une variable par siège
     */
    public record Reading(int variable, int seat) {

        /** Le siège du lecteur, ou l'assembly pour une variable de l'assembly. */
        public static final int READER = -1;
    }

    /**
     * {@return ce que lit {@code var.<rest>}, {@code null} si rien ne s'y lit} Le nom exact d'une variable d'abord —
     * pour une variable par siège, depuis le siège du lecteur ; sinon {@code <nom>.<siège>}, une variable par siège lue
     * à un siège nommé.
     *
     * @param rest ce qui suit {@code var.}
     * @param seats le nom de chaque siège, dans l'ordre de la definition
     */
    public Reading resolve(String rest, List<String> seats) {
        int exact = indexOf(rest);
        if (exact >= 0) {
            return new Reading(exact, Reading.READER);
        }
        int dot = rest.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        int variable = indexOf(rest.substring(0, dot));
        int seat = seats.indexOf(rest.substring(dot + 1));
        return variable >= 0 && seat >= 0 && declared.get(variable).perSeat() ? new Reading(variable, seat) : null;
    }

    /**
     * {@return le nom de chaque siège d'une definition, dans son ordre}
     *
     * @param root document de la definition
     */
    public static List<String> seatNames(JsonObject root) {
        List<String> names = new ArrayList<>();
        if (root.get("seats") instanceof com.google.gson.JsonArray seats) {
            for (JsonElement seat : seats) {
                names.add(seat instanceof JsonObject object && object.get("name") instanceof JsonPrimitive name
                        ? name.getAsString() : "");
            }
        }
        return names;
    }

    /**
     * {@return la variable de ce rang}
     *
     * @param index son rang
     */
    public Variable get(int index) {
        return declared.get(index);
    }

    /**
     * Lit les variables d'une definition acceptée.
     *
     * @param root document de la definition
     * @return la table, {@link #NONE} sans variable
     */
    public static Variables of(JsonObject root) {
        if (!(root.get("variables") instanceof JsonObject variables) || variables.size() == 0) {
            return NONE;
        }
        List<Variable> declared = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : variables.entrySet()) {
            JsonObject spec = entry.getValue().getAsJsonObject();
            Type type = Type.valueOf(spec.get("type").getAsString().toUpperCase(Locale.ROOT));
            boolean bool = type == Type.BOOL;
            declared.add(new Variable(
                    entry.getKey(),
                    type,
                    spec.has("default") ? value(spec.get("default")) : 0.0,
                    bool ? 0.0 : spec.has("min") ? spec.get("min").getAsDouble() : Double.NEGATIVE_INFINITY,
                    bool ? 1.0 : spec.has("max") ? spec.get("max").getAsDouble() : Double.POSITIVE_INFINITY,
                    spec.has("persist") && spec.get("persist").getAsBoolean(),
                    spec.has("replicate")
                            ? Replicate.valueOf(spec.get("replicate").getAsString().toUpperCase(Locale.ROOT))
                            : Replicate.ALL,
                    spec.has("write")
                            ? Write.valueOf(spec.get("write").getAsString().toUpperCase(Locale.ROOT))
                            : Write.DRIVER,
                    spec.has("scope")
                            ? Scope.valueOf(spec.get("scope").getAsString().toUpperCase(Locale.ROOT))
                            : Scope.ASSEMBLY));
        }
        return new Variables(declared);
    }

    /**
     * {@return la valeur d'un littéral de definition : un booléen vaut 0 ou 1, un nombre lui-même, tout autre 0}
     *
     * @param element un booléen ou un nombre
     */
    static double value(JsonElement element) {
        if (!(element instanceof JsonPrimitive primitive) || !(primitive.isBoolean() || primitive.isNumber())) {
            // Ni booléen ni nombre : DefinitionChecker le refuse avec son chemin ; la table n'en garde rien.
            return 0.0;
        }
        return primitive.isBoolean() ? (primitive.getAsBoolean() ? 1.0 : 0.0) : primitive.getAsDouble();
    }
}
