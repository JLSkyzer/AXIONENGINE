package dev.axion.render;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Les commandes d'animation que le client pousse au contexte de rendu natif (ADR-155, ABI 10) : chacune
 * un {@code RenderAnimate} de 16 octets — l'{@code id} du candidat de rendu de l'assembly, un mot réservé,
 * le handle de son asset —, une {@code AnimCommand} de 40 octets, puis les nodes d'un masque. Tout en
 * little-endian (R-271). Logique pure, sans natif : la disposition est celle de
 * {@code ax-model/src/dm/animation.rs}, que les tests des deux côtés confrontent.
 *
 * <p>Les instants sont des ticks du serveur, fraction comprise (décision de Killian) ; les fondus sont en
 * secondes, comme l'API les présente.
 */
public final class AnimationCommands {

    /** Taille d'un {@code RenderAnimate}, en octets. */
    public static final int HEADER_BYTES = 16;

    /** Taille d'une {@code AnimCommand}, en octets. */
    public static final int COMMAND_BYTES = 40;

    /** Jouer un clip sur une couche. */
    public static final int PLAY = 1;

    /** Arrêter une couche. */
    public static final int STOP = 2;

    /** Régler la vitesse d'une couche. */
    public static final int SET_SPEED = 3;

    /** Restreindre une couche à des nodes. */
    public static final int SET_MASK = 4;

    /** Oublier l'animation d'une assembly qui quitte le rendu. */
    public static final int FORGET = 5;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private int count;

    /**
     * Joue un clip.
     *
     * @param id {@code id} du candidat de rendu de l'assembly
     * @param assetIndex index du handle natif de son asset
     * @param assetGeneration génération de ce handle
     * @param tick instant, en ticks du serveur
     * @param layer couche, de 0 à 3
     * @param clip rang du clip dans l'asset
     * @param weight poids, de 0 à 1
     * @param fadeSeconds durée du fondu, en secondes
     * @param origin temps de clip à l'instant {@code tick}, en secondes : 0 joue le clip depuis son début
     * @param speed vitesse
     * @return ce même tampon
     */
    public AnimationCommands play(
            int id, int assetIndex, int assetGeneration, double tick, int layer, int clip, float weight,
            float fadeSeconds, float origin, float speed) {
        return add(id, assetIndex, assetGeneration, tick, PLAY, layer, clip, weight, fadeSeconds, speed, origin,
                new int[0]);
    }

    /**
     * Arrête une couche par un fondu.
     *
     * @param id {@code id} du candidat de rendu de l'assembly
     * @param assetIndex index du handle natif de son asset
     * @param assetGeneration génération de ce handle
     * @param tick instant, en ticks du serveur
     * @param layer couche
     * @param fadeSeconds durée du fondu, en secondes
     * @return ce même tampon
     */
    public AnimationCommands stop(
            int id, int assetIndex, int assetGeneration, double tick, int layer, float fadeSeconds) {
        return add(id, assetIndex, assetGeneration, tick, STOP, layer, 0, 0f, fadeSeconds, 1f, 0f, new int[0]);
    }

    /**
     * Règle la vitesse d'une couche.
     *
     * @param id {@code id} du candidat de rendu de l'assembly
     * @param assetIndex index du handle natif de son asset
     * @param assetGeneration génération de ce handle
     * @param tick instant, en ticks du serveur
     * @param layer couche
     * @param speed vitesse
     * @return ce même tampon
     */
    public AnimationCommands speed(
            int id, int assetIndex, int assetGeneration, double tick, int layer, float speed) {
        return add(id, assetIndex, assetGeneration, tick, SET_SPEED, layer, 0, 0f, 0f, speed, 0f, new int[0]);
    }

    /**
     * Restreint une couche à des nodes ; aucun : tous.
     *
     * @param id {@code id} du candidat de rendu de l'assembly
     * @param assetIndex index du handle natif de son asset
     * @param assetGeneration génération de ce handle
     * @param tick instant, en ticks du serveur
     * @param layer couche
     * @param nodes index des nodes de l'asset
     * @return ce même tampon
     */
    public AnimationCommands mask(
            int id, int assetIndex, int assetGeneration, double tick, int layer, int[] nodes) {
        return add(id, assetIndex, assetGeneration, tick, SET_MASK, layer, 0, 0f, 0f, 1f, 0f, nodes.clone());
    }

    /**
     * Oublie l'animation d'une assembly qui quitte le rendu.
     *
     * @param id {@code id} du candidat de rendu de l'assembly
     * @return ce même tampon
     */
    public AnimationCommands forget(int id) {
        return add(id, 0, 0, 0.0, FORGET, 0, 0, 0f, 0f, 1f, 0f, new int[0]);
    }

    /** {@return le nombre de commandes écrites} */
    public int count() {
        return count;
    }

    /** {@return les octets des commandes écrites, à remettre au natif} */
    public byte[] bytes() {
        return out.toByteArray();
    }

    private AnimationCommands add(
            int id, int assetIndex, int assetGeneration, double tick, int action, int layer, int clip, float weight,
            float fade, float speed, float origin, int[] nodes) {
        ByteBuffer record = ByteBuffer.allocate(HEADER_BYTES + COMMAND_BYTES + nodes.length * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        record.putInt(id).putInt(0).putInt(assetIndex).putInt(assetGeneration);
        record.putDouble(tick).putInt(action).putInt(layer).putInt(clip).putFloat(weight).putFloat(fade)
                .putFloat(speed).putInt(nodes.length).putFloat(origin);
        for (int node : nodes) {
            record.putInt(node);
        }
        out.writeBytes(record.array());
        count++;
        return this;
    }
}
