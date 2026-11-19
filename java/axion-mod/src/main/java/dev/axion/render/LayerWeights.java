package dev.axion.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Le poids des couches qu'une source pilote (ADR-174 D4, schéma 7) : le {@code weight_source} d'une animation,
 * évalué au client à chaque image comme les pistes procédurales, que le serveur ne réplique pas. Chaque client le
 * lit de l'état répliqué. Logique pure, sans natif ni Minecraft.
 *
 * <p>Une couche a au plus un poids piloté (la definition qui en déclare deux est refusée) ; il vaut la source ramenée
 * dans [0, 1], et remplace le poids que la couche a reçu du serveur.
 */
public final class LayerWeights {

    /** Aucun poids piloté. */
    public static final LayerWeights NONE = new LayerWeights(new ProceduralTracks.Probe[4]);

    /** La sonde de chaque couche, ou {@code null}. */
    private final ProceduralTracks.Probe[] probes;

    private LayerWeights(ProceduralTracks.Probe[] probes) {
        this.probes = probes;
    }

    /**
     * {@return le poids piloté des couches d'une definition, {@link #NONE} sans ou avant le schéma 7}
     *
     * @param root document de la definition
     * @param journal où dire qu'une source n'est pas prise en charge : la couche garde alors le poids du serveur
     */
    public static LayerWeights of(JsonObject root, Consumer<String> journal) {
        if (!(root.get("schema") instanceof JsonPrimitive schema) || schema.getAsInt() < 7
                || !(root.get("animations") instanceof JsonObject animations)) {
            return NONE;
        }
        ProceduralTracks.Probe[] probes = new ProceduralTracks.Probe[4];
        boolean any = false;
        for (Map.Entry<String, JsonElement> entry : animations.entrySet()) {
            if (!(entry.getValue() instanceof JsonObject animation)
                    || !(animation.get("weight_source") instanceof JsonPrimitive source)) {
                continue;
            }
            int layer = animation.get("layer") instanceof JsonPrimitive rank ? rank.getAsInt() : 0;
            if (layer < 0 || layer >= probes.length || probes[layer] != null) {
                continue;
            }
            ProceduralTracks.Probe probe = ProceduralTracks.probe(root, source.getAsString());
            if (probe == null) {
                journal.accept("animation « " + entry.getKey() + " » : source « " + source.getAsString()
                        + " » non prise en charge, la couche " + layer + " garde le poids du serveur");
                continue;
            }
            probes[layer] = probe;
            any = true;
        }
        return any ? new LayerWeights(probes) : NONE;
    }

    /** {@return vrai si aucune couche n'a son poids piloté} */
    public boolean isEmpty() {
        return this == NONE;
    }

    /**
     * {@return le poids de chaque couche à cette image, ramené dans [0, 1] ; NaN pour une couche dont le poids n'est
     * pas piloté, ou dont la source manque (sa roue, son siège)}
     *
     * @param frame la trame de l'image
     */
    public float[] evaluate(ProceduralTracks.Frame frame) {
        float[] weights = new float[probes.length];
        Arrays.fill(weights, Float.NaN);
        for (int layer = 0; layer < probes.length; layer++) {
            if (probes[layer] != null) {
                double value = probes[layer].read(frame);
                weights[layer] = Double.isNaN(value) ? Float.NaN : (float) Math.max(0.0, Math.min(1.0, value));
            }
        }
        return weights;
    }

    /**
     * {@return vrai si une couche a, à cette image, un poids piloté en dessous du plein} La pose est alors un mélange
     * qu'aucun clip seul ne balaie (ADR-157 D1).
     *
     * @param weights les poids de {@link #evaluate}, ou {@code null}
     */
    public static boolean partial(float[] weights) {
        if (weights != null) {
            for (float weight : weights) {
                if (weight < 1f) {
                    return true;
                }
            }
        }
        return false;
    }
}
