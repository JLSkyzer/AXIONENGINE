package dev.axion.asset;

import java.util.Arrays;
import java.util.List;

/**
 * Les clips d'un asset (DM-10, ADR-155 D2) : le nom et le nombre de pistes de chacun, ce que le serveur
 * Java lit pour {@code AnimationControl} — les noms que l'API joue, les pistes que R-200 compte. Ils
 * viennent du résumé de la definition compilée (ADR-156 D4, décision de Killian, tranche 4) : Java ne lit
 * plus la section {@code ANIM}, dont les clés restent au natif, qui seul échantillonne.
 *
 * @param names le nom de chaque clip, vide s'il n'en a pas
 * @param tracks le nombre de pistes de chaque clip
 * @param durations la durée de chaque clip, en secondes
 * @param loops pour chaque clip, vrai s'il boucle
 */
public record AnimClips(List<String> names, int[] tracks, float[] durations, boolean[] loops) {

    /** Aucun clip : un asset sans section {@code ANIM}. */
    public static final AnimClips NONE = new AnimClips(List.of(), new int[0]);

    /** Copie les noms et les pistes. */
    public AnimClips {
        names = List.copyOf(names);
        tracks = tracks.clone();
        durations = durations.clone();
        loops = loops.clone();
        if (names.size() != tracks.length || names.size() != durations.length || names.size() != loops.length) {
            throw new IllegalArgumentException(names.size() + " noms pour " + tracks.length + " clips");
        }
    }

    /**
     * Des clips sans durée connue : ceux dont seuls comptent les noms et les pistes.
     *
     * @param names le nom de chaque clip
     * @param tracks le nombre de pistes de chaque clip
     */
    public AnimClips(List<String> names, int[] tracks) {
        this(names, tracks, new float[tracks.length], new boolean[tracks.length]);
    }

    /**
     * {@return ce qu'un temps de clip donne une fois ramené dans le clip, comme le natif l'échantillonne :
     * modulo sa durée s'il boucle, borné à sa durée sinon}
     *
     * @param clip rang d'un clip
     * @param time temps de clip, en secondes
     */
    public double wrap(int clip, double time) {
        double duration = durations[clip];
        if (loops[clip] && duration > 0.0) {
            return ((time % duration) + duration) % duration;
        }
        return Math.max(0.0, Math.min(duration, time));
    }

    /**
     * @param name nom d'un clip
     * @return le rang du premier clip de ce nom, ou -1 : aucun, ou un nom vide
     */
    public int indexOf(String name) {
        return name == null || name.isEmpty() ? -1 : names.indexOf(name);
    }

    /** {@return le nombre de clips} */
    public int size() {
        return names.size();
    }

    /**
     * @param clip rang d'un clip, ou -1
     * @return ses pistes, 0 pour -1
     */
    public int tracksOf(int clip) {
        return clip < 0 ? 0 : tracks[clip];
    }

    @Override
    public int[] tracks() {
        return tracks.clone();
    }

    @Override
    public float[] durations() {
        return durations.clone();
    }

    @Override
    public boolean[] loops() {
        return loops.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AnimClips that && names.equals(that.names) && Arrays.equals(tracks, that.tracks)
                && Arrays.equals(durations, that.durations) && Arrays.equals(loops, that.loops);
    }

    @Override
    public int hashCode() {
        return 31 * names.hashCode() + Arrays.hashCode(tracks);
    }

    @Override
    public String toString() {
        return "AnimClips" + names;
    }
}
