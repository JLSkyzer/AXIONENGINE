package dev.axion.render;

import dev.axion.net.AnimationLayers;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Ce que le client pousse de l'animation de chaque assembly au contexte de rendu natif (ADR-155, tranche
 * 4). Logique pure, sans natif ni Minecraft.
 *
 * <p>À chaque frame, l'état d'animation de chaque candidate — celui du plus récent snapshot — se compare à
 * celui déjà mis en file : la différence devient des commandes ({@link AnimationLayers#changesFrom}), qui
 * attendent que le tick de rendu atteigne leur tick. Le natif les applique ainsi à l'instant où l'image les
 * montre, et non quand le snapshot arrive, en avance de l'interpolation. Les deltas des pistes procédurales
 * (ADR-159 D9) suivent les commandes, envoyés seulement quand ils changent. Le poids qu'une source pilote (ADR-174
 * D4) remplace celui d'un {@code PLAY}, et part en {@code SET_WEIGHT} quand il change. Une assembly qu'aucune frame n'a vue
 * depuis {@link #FORGET_AFTER_FRAMES} est oubliée du natif ; revue, elle rejoue son état entier.
 */
public final class AnimationSync {

    /** Frames sans voir une assembly avant que le natif ne l'oublie. */
    public static final int FORGET_AFTER_FRAMES = 64;

    /** Ce que le natif a reçu, ou recevra, d'une assembly. */
    private static final class Pushed {
        final int assetIndex;
        final int assetGeneration;
        /** Le dernier état mis en file. */
        AnimationLayers state = AnimationLayers.NONE;
        /** Ses commandes pas encore échues, par tick croissant. */
        final ArrayDeque<AnimationLayers.Change> pending = new ArrayDeque<>();
        /** Les deltas de cette frame, ou {@code null}. */
        ProceduralTracks.Pose pose;
        /** Les derniers deltas envoyés au natif. */
        ProceduralTracks.Pose sent;
        /** Le poids piloté de chaque couche à cette frame, NaN sans ; ou {@code null}. */
        float[] weights;
        /** Le dernier poids piloté envoyé au natif pour chaque couche, NaN sans. */
        final float[] sentWeights = {Float.NaN, Float.NaN, Float.NaN, Float.NaN};
        long seen;

        Pushed(int assetIndex, int assetGeneration) {
            this.assetIndex = assetIndex;
            this.assetGeneration = assetGeneration;
        }
    }

    private final Map<Integer, Pushed> pushed = new HashMap<>();
    private long frame;

    /**
     * Une candidate de la frame. Un autre asset que celui d'avant fait repartir son animation du repos, comme
     * le natif le fait.
     *
     * @param id {@code id} de son candidat de rendu
     * @param assetIndex index du handle natif de son asset
     * @param assetGeneration génération de ce handle
     * @param state son état d'animation
     */
    public void see(int id, int assetIndex, int assetGeneration, AnimationLayers state) {
        see(id, assetIndex, assetGeneration, state, null);
    }

    /**
     * Une candidate de la frame, avec les deltas de ses pistes procédurales.
     *
     * @param id {@code id} de son candidat de rendu
     * @param assetIndex index du handle natif de son asset
     * @param assetGeneration génération de ce handle
     * @param state son état d'animation
     * @param pose ses deltas à cette frame, ou {@code null} : une assembly qui n'a ni animation ni deltas
     *     n'a rien à pousser
     */
    public void see(int id, int assetIndex, int assetGeneration, AnimationLayers state, ProceduralTracks.Pose pose) {
        see(id, assetIndex, assetGeneration, state, pose, null);
    }

    /**
     * Une candidate de la frame, avec les deltas de ses pistes et le poids que des sources pilotent (ADR-174 D4).
     *
     * @param id {@code id} de son candidat de rendu
     * @param assetIndex index du handle natif de son asset
     * @param assetGeneration génération de ce handle
     * @param state son état d'animation
     * @param pose ses deltas à cette frame, ou {@code null}
     * @param weights le poids piloté de chaque couche à cette frame, NaN pour une couche sans ; ou {@code null}
     */
    public void see(int id, int assetIndex, int assetGeneration, AnimationLayers state, ProceduralTracks.Pose pose,
            float[] weights) {
        Pushed entry = pushed.get(id);
        if (entry == null || entry.assetIndex != assetIndex || entry.assetGeneration != assetGeneration) {
            if (entry == null && state.equals(AnimationLayers.NONE) && pose == null) {
                return;
            }
            entry = new Pushed(assetIndex, assetGeneration);
            pushed.put(id, entry);
        }
        entry.seen = frame;
        entry.pose = pose;
        entry.weights = weights;
        if (entry.state != state && !entry.state.equals(state)) {
            entry.pending.addAll(state.changesFrom(entry.state));
            entry.state = state;
        }
    }

    /**
     * Clôt la frame : les commandes échues au tick de rendu, et l'oubli des assemblies qu'on ne voit plus.
     *
     * @param renderTick tick de rendu de l'image, fraction comprise
     * @return les commandes à pousser au natif, peut-être aucune
     */
    public AnimationCommands flush(double renderTick) {
        AnimationCommands commands = new AnimationCommands();
        for (Iterator<Map.Entry<Integer, Pushed>> it = pushed.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Pushed> each = it.next();
            int id = each.getKey();
            Pushed entry = each.getValue();
            if (frame - entry.seen >= FORGET_AFTER_FRAMES) {
                commands.forget(id);
                it.remove();
                continue;
            }
            while (!entry.pending.isEmpty() && entry.pending.peekFirst().tick() <= renderTick) {
                AnimationLayers.Change change = entry.pending.pollFirst();
                switch (change.action()) {
                    case AnimationLayers.Change.PLAY -> {
                        // Le poids piloté remplace celui du serveur dès le lancement du clip.
                        float piloted = piloted(entry, change.layer());
                        float weight = Float.isNaN(piloted) ? change.weight() : piloted;
                        commands.play(id, entry.assetIndex, entry.assetGeneration, change.tick(), change.layer(),
                                change.clip(), weight, change.fade(), change.origin(), change.speed());
                        entry.sentWeights[change.layer()] = piloted;
                    }
                    case AnimationLayers.Change.SET_MASK -> commands.mask(id, entry.assetIndex,
                            entry.assetGeneration, change.tick(), change.layer(),
                            change.mask().stream().mapToInt(Integer::intValue).toArray());
                    case AnimationLayers.Change.STOP -> commands.stop(id, entry.assetIndex, entry.assetGeneration,
                            change.tick(), change.layer(), change.fade());
                    default -> commands.speed(id, entry.assetIndex, entry.assetGeneration, change.tick(),
                            change.layer(), change.speed());
                }
            }
            for (int layer = 0; layer < entry.sentWeights.length; layer++) {
                float piloted = piloted(entry, layer);
                if (!Float.isNaN(piloted) && piloted != entry.sentWeights[layer]) {
                    commands.weight(id, entry.assetIndex, entry.assetGeneration, renderTick, layer, piloted);
                    entry.sentWeights[layer] = piloted;
                }
            }
            entry.weights = null;
            if (entry.pose != null && !same(entry.pose, entry.sent)) {
                commands.pose(id, entry.assetIndex, entry.assetGeneration, renderTick, entry.pose);
                entry.sent = entry.pose;
            }
            entry.pose = null;
        }
        frame++;
        return commands;
    }

    private static float piloted(Pushed entry, int layer) {
        return entry.weights == null || layer >= entry.weights.length ? Float.NaN : entry.weights[layer];
    }

    private static boolean same(ProceduralTracks.Pose a, ProceduralTracks.Pose b) {
        return b != null && Arrays.equals(a.nodes(), b.nodes()) && Arrays.equals(a.deltas(), b.deltas());
    }

    /** {@return le nombre d'assemblies que le natif anime ou animera} */
    public int tracked() {
        return pushed.size();
    }
}
