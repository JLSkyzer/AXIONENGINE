package dev.axion.definition;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Les événements de contenu qu'une definition déclare (ADR-174 D2, schéma 7) : leur nom, dans l'ordre de la
 * definition — le rang sur le fil —, et à qui ils partent. Un événement est un instant : il ne se sauvegarde pas.
 * Logique pure, sans Forge.
 *
 * @param declared chaque événement, dans l'ordre
 */
public record ContentEvents(List<Event> declared) {

    /** Événements d'une definition, au plus. */
    public static final int MAX = 64;

    /** Aucun événement. */
    public static final ContentEvents NONE = new ContentEvents(List.of());

    /** À qui un événement part (ADR-174 D2). */
    public enum Replicate {
        /** Aux joueurs qui suivent l'assembly. */
        ALL,
        /** À ses seuls occupants. */
        OCCUPANTS,
        /** À personne : il ne vit qu'au serveur. */
        NONE;

        static Replicate of(String key) {
            return key == null ? ALL : valueOf(key.toUpperCase(java.util.Locale.ROOT));
        }
    }

    /**
     * Un événement déclaré.
     *
     * @param name son nom
     * @param replicate à qui il part
     */
    public record Event(String name, Replicate replicate) {}

    /** Copie la liste. */
    public ContentEvents {
        declared = List.copyOf(declared);
    }

    /**
     * {@return les événements d'une definition acceptée, {@link #NONE} sans}
     *
     * @param root document de la definition
     */
    public static ContentEvents of(JsonObject root) {
        if (!(root.get("events") instanceof JsonObject events) || events.size() == 0) {
            return NONE;
        }
        List<Event> found = new ArrayList<>(events.size());
        for (Map.Entry<String, JsonElement> entry : events.entrySet()) {
            String replicate = entry.getValue() instanceof JsonObject declared && declared.get("replicate") != null
                    ? declared.get("replicate").getAsString() : null;
            found.add(new Event(entry.getKey(), Replicate.of(replicate)));
        }
        return new ContentEvents(found);
    }

    /**
     * {@return le rang d'un événement, {@code -1} s'il n'est pas déclaré}
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

    /** {@return les noms des événements, dans l'ordre} */
    public List<String> names() {
        return declared.stream().map(Event::name).toList();
    }
}
