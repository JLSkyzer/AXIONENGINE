package dev.axion.net;

/**
 * La pose du véhicule que le client pilote et prédit (ADR-168 D2, sous-tranche 6c-C) : chaque pas du monde prédit y
 * pose l'état qu'il a produit, au rang de l'entrée qui l'a produit. Le monde prédit avance d'un pas par tick du
 * client ; la piste se lit donc comme Minecraft dessine une entité : entre ses deux derniers ticks, à la fraction de
 * tick de l'image ({@code partialTick}), et l'entité se pose à son dernier tick. Le joueur assis, que Minecraft
 * interpole de la même manière, reste ainsi sur son siège à chaque image.
 *
 * <ul>
 *   <li>Un pas neuf ({@link #advance}) ne change pas ce qui s'affiche : l'image d'avant montrait déjà le tick qui devient
 *       l'avant-dernier.</li>
 *   <li>Un pas rejoué ({@link #replay}) réécrit son tick : l'écart à l'écran se résorbe en 200 ms d'heure locale, ou se
 *       téléporte au-delà de {@code net.snap_threshold} (21.7, D5).</li>
 *   <li>Le passage de l'interpolation à la prédiction, et retour, se lisse de même ({@link #blendFrom}) ; et le tick
 *       où le client règle la cadence de ses entrées, qui fait deux pas ou aucun (ADR-168 D3, révisé le
 *       2026-11-14) : la pose de la dernière image ({@link #lastShown}) s'y résorbe.</li>
 * </ul>
 *
 * <p>Logique pure, sans Forge ni natif. Thread du client seul.
 */
public final class PredictedTrack {

    private final Interpolation poses;
    private long latest;
    private boolean started;
    /** Le temps de rendu de la dernière image dessinée : là où un rejeu mesure ce qu'il fait à l'écran. */
    private double lastFrameMillis = Double.NaN;

    /**
     * @param poses l'interpolation qui garde les états, réglée par la configuration du client
     */
    public PredictedTrack(Interpolation poses) {
        this.poses = poses;
    }

    /**
     * Le pas d'une entrée neuve.
     *
     * @param sequence la séquence de l'entrée, qui sert de tick
     * @param state l'état qu'il a produit
     * @param nowMillis le temps local, en millisecondes
     */
    public void advance(long sequence, AssemblyState state, double nowMillis) {
        // Ce qui s'affichait juste avant ce pas : le dernier tick posé, en fin d'image.
        double shown = (started ? latest : sequence - 1) * ServerClock.TICK_MILLIS;
        poses.put(sequence, state, shown, nowMillis);
        latest = sequence;
        started = true;
    }

    /**
     * Un pas rejoué, qui réécrit l'état de son tick.
     *
     * @param sequence la séquence de l'entrée rejouée
     * @param state l'état que le rejeu a produit
     * @param nowMillis le temps local, en millisecondes
     */
    public void replay(long sequence, AssemblyState state, double nowMillis) {
        if (started) {
            // L'écart se mesure là où l'image se dessine : un rejeu qui réécrit l'avant-dernier tick change l'image
            // dessinée entre deux ticks, pas la pose du dernier.
            double shown = Double.isNaN(lastFrameMillis) ? latest * ServerClock.TICK_MILLIS : lastFrameMillis;
            poses.put(sequence, state, shown, nowMillis);
        }
    }

    /**
     * Part d'une pose affichée jusque-là, celle de l'interpolation : l'écart se résorbe.
     *
     * @param shown la pose affichée ; {@code null} : rien à résorber
     * @param fraction la fraction de tick à laquelle elle s'affichait
     * @param nowMillis le temps local, en millisecondes
     */
    public void blendFrom(Interpolation.Pose shown, double fraction, double nowMillis) {
        if (started) {
            poses.blend(shown, renderMillis(fraction), nowMillis);
        }
    }

    /**
     * {@return la pose à afficher, {@code null} avant le premier pas}
     *
     * @param fraction la fraction de tick de l'image, dans [0, 1]
     * @param nowMillis le temps local, en millisecondes : il fait décroître une correction en cours
     */
    public Interpolation.Pose sample(double fraction, double nowMillis) {
        if (!started) {
            return null;
        }
        lastFrameMillis = renderMillis(fraction);
        return poses.sample(lastFrameMillis, nowMillis);
    }

    /**
     * {@return la pose à une fraction de tick, sans qu'elle compte comme une image dessinée ; {@code null} avant le
     * premier pas} L'entité s'y pose à son tick (fraction 1), pour sa hitbox.
     *
     * @param fraction la fraction de tick, dans [0, 1]
     * @param nowMillis le temps local, en millisecondes
     */
    public Interpolation.Pose pose(double fraction, double nowMillis) {
        return started ? poses.sample(renderMillis(fraction), nowMillis) : null;
    }

    /**
     * {@return la pose de la dernière image dessinée, telle qu'elle s'est affichée ; {@code null} avant}
     *
     * @param nowMillis le temps local, en millisecondes
     */
    public Interpolation.Pose lastShown(double nowMillis) {
        return started && !Double.isNaN(lastFrameMillis) ? poses.sample(lastFrameMillis, nowMillis) : null;
    }

    /** {@return vrai si un pas au moins y est posé} */
    public boolean started() {
        return started;
    }

    /** Le temps de rendu : de l'avant-dernier pas au dernier, à la fraction de tick. */
    private double renderMillis(double fraction) {
        return (latest - 1 + Math.min(1.0, Math.max(0.0, fraction))) * ServerClock.TICK_MILLIS;
    }
}
