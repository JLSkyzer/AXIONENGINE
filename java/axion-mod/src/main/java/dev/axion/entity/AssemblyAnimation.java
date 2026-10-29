package dev.axion.entity;

import dev.axion.api.AnimationControl;
import dev.axion.asset.AnimClips;
import dev.axion.net.AnimationLayers;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * L'animation d'une assembly côté serveur (C-37, ADR-155 D5, tranche 4) : l'état de ses quatre couches,
 * que le descripteur d'états v3 porte aux clients et que le serveur rejoue à son natif. Logique pure : le
 * tick et la vérification du thread lui sont donnés.
 *
 * <p>Elle reproduit ce que le natif décide d'une commande ({@code ax-anim}, {@code Animator::play_at}) :
 * une lecture qui porterait l'assembly au-delà de 256 pistes actives est refusée, et son fondu coupé s'il
 * suffit à l'éviter (R-200). Il lui faut pour cela le clip que chaque couche efface pendant son fondu, que
 * le descripteur ne porte pas.
 */
public final class AssemblyAnimation implements AnimationControl {

    /** R-200 : pistes actives d'une instance, fondus compris. */
    public static final int MAX_ACTIVE_TRACKS = 256;

    private static final double TICKS_PER_SECOND = 20.0;
    private static final int NONE = -1;

    private final LongSupplier ticks;
    private final Runnable authoritative;
    private AnimClips clips = AnimClips.NONE;
    private AnimationLayers state = AnimationLayers.NONE;
    /** La vitesse de chaque couche, celle des clips qu'elle lancera. */
    private final float[] speeds = {1f, 1f, 1f, 1f};
    /** Le clip que chaque couche efface pendant son fondu, ou {@link #NONE}. */
    private final int[] outgoing = {NONE, NONE, NONE, NONE};

    /**
     * @param ticks le tick courant du serveur
     * @param authoritative vérifie le thread autoritatif, et lève {@link IllegalStateException} ailleurs
     */
    public AssemblyAnimation(LongSupplier ticks, Runnable authoritative) {
        this.ticks = ticks;
        this.authoritative = authoritative;
    }

    /**
     * Pose les clips de l'asset, à la création de son corps. D'autres clips que ceux d'avant — un asset
     * rechargé — remettent l'animation au repos : ses rangs ne désignent plus les mêmes clips.
     *
     * @param assetClips les clips de la section {@code ANIM} de l'asset
     */
    public void bind(AnimClips assetClips) {
        if (assetClips.equals(clips)) {
            return;
        }
        clips = assetClips;
        state = AnimationLayers.NONE;
        Arrays.fill(speeds, 1f);
        Arrays.fill(outgoing, NONE);
    }

    /** {@return l'état des couches, celui que le descripteur v3 porte} */
    public AnimationLayers state() {
        return state;
    }

    @Override
    public List<String> clips() {
        return clips.names();
    }

    @Override
    public void play(String clip, int layer, float weight, float fadeSeconds) {
        authoritative.run();
        checkLayer(layer);
        int index = clips.indexOf(clip);
        if (index < 0) {
            throw new IllegalArgumentException("clip inconnu : « " + clip + " », clips " + clips.names());
        }
        if (!(weight >= 0f && weight <= 1f)) {
            throw new IllegalArgumentException("poids hors de [0, 1] : " + weight);
        }
        checkFade(fadeSeconds);
        long now = ticks.getAsLong();
        int others = 0;
        for (int each = 0; each < AnimationLayers.LAYERS; each++) {
            if (each != layer) {
                others += activeTracks(each, now);
            }
        }
        int entering = clips.tracksOf(index);
        if (others + entering > MAX_ACTIVE_TRACKS) {
            throw new IllegalArgumentException("R-200 : « " + clip + " » porterait l'assembly à "
                    + (others + entering) + " pistes actives, " + MAX_ACTIVE_TRACKS + " au plus");
        }
        int shown = shown(layer, now);
        float fade = others + entering + clips.tracksOf(shown) > MAX_ACTIVE_TRACKS ? 0f : fadeSeconds;
        outgoing[layer] = fade > 0f ? shown : NONE;
        state = state.with(layer, new AnimationLayers.Layer(index, now, 0f, speeds[layer], weight, fade));
    }

    @Override
    public void stop(int layer, float fadeSeconds) {
        authoritative.run();
        checkLayer(layer);
        checkFade(fadeSeconds);
        long now = ticks.getAsLong();
        int shown = shown(layer, now);
        outgoing[layer] = fadeSeconds > 0f ? shown : NONE;
        state = state.with(layer, AnimationLayers.Layer.stopped(now, fadeSeconds));
    }

    @Override
    public void setSpeed(int layer, float speed) {
        authoritative.run();
        checkLayer(layer);
        if (!Float.isFinite(speed)) {
            throw new IllegalArgumentException("vitesse non finie : " + speed);
        }
        speeds[layer] = speed;
        AnimationLayers.Layer current = state.layer(layer);
        if (!current.playing() || current.speed() == speed) {
            return;
        }
        long now = ticks.getAsLong();
        double clipTime = current.clipTimeAt(now);
        AnimationLayers.Layer changed;
        if (fading(current, now)) {
            // Le tick et le fondu restent, le temps de clip y est ramené : le client retrouve l'instant.
            double origin = clipTime - (now - current.tick()) / TICKS_PER_SECOND * speed;
            changed = new AnimationLayers.Layer(current.clip(), current.tick(), (float) origin, speed,
                    current.weight(), current.fade());
        } else {
            changed = new AnimationLayers.Layer(current.clip(), now, (float) clipTime, speed, current.weight(), 0f);
            outgoing[layer] = NONE;
        }
        state = state.with(layer, changed);
    }

    @Override
    public Optional<String> playing(int layer) {
        checkLayer(layer);
        AnimationLayers.Layer current = state.layer(layer);
        return current.playing() ? Optional.of(clips.names().get(current.clip())) : Optional.empty();
    }

    /** Les pistes actives d'une couche à {@code now} : son clip, et celui qu'elle efface. */
    private int activeTracks(int layer, long now) {
        AnimationLayers.Layer current = state.layer(layer);
        return clips.tracksOf(current.playing() ? current.clip() : NONE)
                + (fading(current, now) ? clips.tracksOf(outgoing[layer]) : 0);
    }

    /**
     * Ce que montre le plus une couche à {@code now} : le clip qu'elle efface tant que son fondu est à moins
     * de sa moitié, son clip sinon ({@code dominant_is_outgoing} du natif).
     */
    private int shown(int layer, long now) {
        AnimationLayers.Layer current = state.layer(layer);
        if (fading(current, now) && alpha(current, now) < 0.5f) {
            return outgoing[layer];
        }
        return current.playing() ? current.clip() : NONE;
    }

    private static boolean fading(AnimationLayers.Layer layer, long now) {
        return alpha(layer, now) < 1f;
    }

    /** L'avancement du fondu d'une couche, de 0 à 1, calculé comme le natif le calcule. */
    private static float alpha(AnimationLayers.Layer layer, long now) {
        if (layer.fade() <= 0f) {
            return 1f;
        }
        float alpha = (float) ((now / TICKS_PER_SECOND - layer.tick() / TICKS_PER_SECOND) / (double) layer.fade());
        return Math.max(0f, Math.min(1f, alpha));
    }

    private static void checkLayer(int layer) {
        if (layer < 0 || layer >= AnimationLayers.LAYERS) {
            throw new IllegalArgumentException("couche hors de 0..3 : " + layer);
        }
    }

    private static void checkFade(float fadeSeconds) {
        if (!(fadeSeconds >= 0f && Float.isFinite(fadeSeconds))) {
            throw new IllegalArgumentException("fondu négatif ou non fini : " + fadeSeconds);
        }
    }
}
