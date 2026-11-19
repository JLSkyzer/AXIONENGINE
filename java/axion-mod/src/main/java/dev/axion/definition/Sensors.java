package dev.axion.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;

/**
 * Les capteurs qu'une definition déclare (ADR-174 D5, schéma 7) : un volume posé à un node, qui ne résout aucun contact
 * et voit entrer et sortir les autres assemblies et les entités. Chacun donne les événements
 * {@code sensor.<nom>.enter} et {@code sensor.<nom>.exit}, et la source {@code sensor.<nom>.count}. Logique pure, sans
 * Forge.
 *
 * @param declared chaque capteur, dans l'ordre de la definition — son rang au natif
 */
public record Sensors(List<Sensor> declared) {

    /** Capteurs d'une definition, au plus. */
    public static final int MAX = 16;

    /** Aucun capteur. */
    public static final Sensors NONE = new Sensors(List.of());

    /** La forme d'un capteur. */
    public enum Shape {
        /** Une boîte, de la taille entière {@code size}. */
        BOX,
        /** Une sphère, de rayon {@code radius}. */
        SPHERE
    }

    /**
     * Un capteur déclaré.
     *
     * @param name son nom
     * @param node le node de l'asset où il est posé
     * @param shape sa forme
     * @param size la taille entière de la boîte, ou le rayon de la sphère en premier, en blocs
     * @param replicate à qui partent ses événements
     */
    public record Sensor(String name, String node, Shape shape, float[] size, ContentEvents.Replicate replicate) {

        /** Copie la taille. */
        public Sensor {
            size = size.clone();
        }

        @Override
        public float[] size() {
            return size.clone();
        }

        /** {@return le nom de son événement d'entrée} */
        public String enterEvent() {
            return "sensor." + name + ".enter";
        }

        /** {@return le nom de son événement de sortie} */
        public String exitEvent() {
            return "sensor." + name + ".exit";
        }
    }

    /** Copie la liste. */
    public Sensors {
        declared = List.copyOf(declared);
    }

    /**
     * {@return les capteurs d'une definition acceptée au schéma 7, {@link #NONE} sans ou avant}
     *
     * @param root document de la definition
     */
    public static Sensors of(JsonObject root) {
        if (!(root.get("schema") instanceof JsonPrimitive schema) || schema.getAsInt() < 7
                || !(root.get("sensors") instanceof JsonArray sensors) || sensors.isEmpty()) {
            return NONE;
        }
        List<Sensor> found = new ArrayList<>(sensors.size());
        for (JsonElement element : sensors) {
            // Lu aussi pendant la vérification : ce qui est mal formé y est déjà refusé, et ne se lit pas ici.
            if (!(element instanceof JsonObject sensor) || !(sensor.get("name") instanceof JsonPrimitive name)
                    || !(sensor.get("node") instanceof JsonPrimitive node)) {
                continue;
            }
            Shape shape = sensor.get("shape") instanceof JsonPrimitive kind && "sphere".equals(kind.getAsString())
                    ? Shape.SPHERE : Shape.BOX;
            float[] size = new float[3];
            if (shape == Shape.SPHERE) {
                size[0] = number(sensor.get("radius"));
            } else if (sensor.get("size") instanceof JsonArray box && box.size() == 3) {
                for (int axis = 0; axis < 3; axis++) {
                    size[axis] = number(box.get(axis));
                }
            }
            String replicate = sensor.get("replicate") instanceof JsonPrimitive to
                    && java.util.Set.of("all", "occupants", "none").contains(to.getAsString()) ? to.getAsString() : null;
            found.add(new Sensor(name.getAsString(), node.getAsString(), shape, size,
                    ContentEvents.Replicate.of(replicate)));
        }
        return new Sensors(found);
    }

    private static float number(JsonElement element) {
        return element instanceof JsonPrimitive primitive && primitive.isNumber() ? primitive.getAsFloat() : 0f;
    }

    /**
     * {@return le rang d'un capteur, {@code -1} s'il n'est pas déclaré}
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
}
