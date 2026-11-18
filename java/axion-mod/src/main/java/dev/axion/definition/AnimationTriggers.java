package dev.axion.definition;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Les déclencheurs des animations d'une definition (ADR-174 D4, schéma 7) : une grammaire fermée, évaluée au
 * serveur, qui joue ou arrête un clip. Logique pure, sans Forge.
 *
 * <pre>
 *   interact:&lt;node&gt;          une interaction de ce node a eu son effet
 *   event:&lt;événement&gt;       cet événement de contenu est émis
 *   var:&lt;variable&gt;          la variable devient non nulle (le clip joue) ; revenue à 0, il s'arrête
 *   seat:&lt;siège&gt;:enter      une entité s'assoit dans ce siège
 *   seat:&lt;siège&gt;:exit       une entité le quitte
 *   stage:&lt;part&gt;:&lt;étape&gt;   la part atteint cette étape de dommage (M6 ; accepté, sans effet avant)
 * </pre>
 *
 * <p>Sans grammaire, pas de machine à états (R-1440) : un déclencheur ne lit qu'un fait, jamais un autre clip.
 *
 * @param triggers chaque déclencheur, dans l'ordre des animations
 */
public record AnimationTriggers(List<Trigger> triggers) {

    /** Aucun déclencheur. */
    public static final AnimationTriggers NONE = new AnimationTriggers(List.of());

    /** Ce qui déclenche. */
    public enum Kind {
        /** Une interaction d'un node. */
        INTERACT,
        /** Un événement de contenu. */
        EVENT,
        /** Une variable qui devient non nulle, ou revient à 0. */
        VAR,
        /** Une montée dans un siège. */
        SEAT_ENTER,
        /** Une descente d'un siège. */
        SEAT_EXIT,
        /** Une étape de dommage (M6). */
        STAGE
    }

    /**
     * Un déclencheur lu.
     *
     * @param kind ce qui déclenche
     * @param key ce qu'il nomme : le node, l'événement, la variable, le siège, ou {@code part:étape}
     */
    public record Parsed(Kind kind, String key) {}

    /**
     * Un déclencheur compilé.
     *
     * @param kind ce qui déclenche
     * @param key ce qu'il nomme
     * @param animation l'animation de la definition
     * @param clip son clip
     * @param layer sa couche
     */
    public record Trigger(Kind kind, String key, String animation, String clip, int layer) {}

    /** Copie la liste. */
    public AnimationTriggers {
        triggers = List.copyOf(triggers);
    }

    /**
     * {@return un déclencheur lu, ou {@code null} s'il ne suit pas la grammaire}
     *
     * @param text le texte de {@code trigger}
     */
    public static Parsed parse(String text) {
        int colon = text.indexOf(':');
        if (colon <= 0 || colon == text.length() - 1) {
            return null;
        }
        String head = text.substring(0, colon);
        String rest = text.substring(colon + 1);
        switch (head) {
            case "interact":
                return new Parsed(Kind.INTERACT, rest);
            case "event":
                return new Parsed(Kind.EVENT, rest);
            case "var":
                return new Parsed(Kind.VAR, rest);
            case "seat": {
                int last = rest.lastIndexOf(':');
                if (last <= 0) {
                    return null;
                }
                String what = rest.substring(last + 1);
                String seat = rest.substring(0, last);
                return "enter".equals(what) ? new Parsed(Kind.SEAT_ENTER, seat)
                        : "exit".equals(what) ? new Parsed(Kind.SEAT_EXIT, seat) : null;
            }
            case "stage": {
                int last = rest.lastIndexOf(':');
                return last <= 0 || last == rest.length() - 1 ? null : new Parsed(Kind.STAGE, rest);
            }
            default:
                return null;
        }
    }

    /**
     * {@return les déclencheurs d'une definition acceptée au schéma 7, {@link #NONE} sans ou avant}
     *
     * @param root document de la definition
     */
    public static AnimationTriggers of(JsonObject root) {
        if (!(root.get("schema") instanceof JsonPrimitive schema) || schema.getAsInt() < 7
                || !(root.get("animations") instanceof JsonObject animations)) {
            return NONE;
        }
        List<Trigger> found = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : animations.entrySet()) {
            if (!(entry.getValue() instanceof JsonObject animation) || animation.get("trigger") == null) {
                continue;
            }
            Parsed parsed = parse(animation.get("trigger").getAsString());
            if (parsed == null) {
                continue;
            }
            String clip = animation.get("clip") != null ? animation.get("clip").getAsString() : entry.getKey();
            int layer = animation.get("layer") != null ? animation.get("layer").getAsInt() : 0;
            found.add(new Trigger(parsed.kind(), parsed.key(), entry.getKey(), clip, layer));
        }
        return found.isEmpty() ? NONE : new AnimationTriggers(found);
    }

    /**
     * {@return les déclencheurs d'un fait}
     *
     * @param kind ce qui a eu lieu
     * @param key ce qu'il nomme
     */
    public List<Trigger> matching(Kind kind, String key) {
        List<Trigger> matched = new ArrayList<>();
        for (Trigger trigger : triggers) {
            if (trigger.kind() == kind && trigger.key().equals(key)) {
                matched.add(trigger);
            }
        }
        return matched;
    }

    /** {@return les variables que des déclencheurs {@code var:} suivent, chacune une fois} */
    public List<String> watchedVariables() {
        return triggers.stream().filter(t -> t.kind() == Kind.VAR).map(Trigger::key).distinct().toList();
    }
}
