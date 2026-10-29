package dev.axion.render;

import dev.axion.net.AnimationLayers;
import java.util.ArrayDeque;
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
 * montre, et non quand le snapshot arrive, en avance de l'interpolation. Une assembly qu'aucune frame n'a vue
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
        Pushed entry = pushed.get(id);
        if (entry == null || entry.assetIndex != assetIndex || entry.assetGeneration != assetGeneration) {
            if (entry == null && state.equals(AnimationLayers.NONE)) {
                return;
            }
            entry = new Pushed(assetIndex, assetGeneration);
            pushed.put(id, entry);
        }
        entry.seen = frame;
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
                    case AnimationLayers.Change.PLAY -> commands.play(id, entry.assetIndex, entry.assetGeneration,
                            change.tick(), change.layer(), change.clip(), change.weight(), change.fade(),
                            change.origin(), change.speed());
                    case AnimationLayers.Change.STOP -> commands.stop(id, entry.assetIndex, entry.assetGeneration,
                            change.tick(), change.layer(), change.fade());
                    default -> commands.speed(id, entry.assetIndex, entry.assetGeneration, change.tick(),
                            change.layer(), change.speed());
                }
            }
        }
        frame++;
        return commands;
    }

    /** {@return le nombre d'assemblies que le natif anime ou animera} */
    public int tracked() {
        return pushed.size();
    }
}
