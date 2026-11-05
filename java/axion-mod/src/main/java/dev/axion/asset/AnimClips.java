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
 */
public record AnimClips(List<String> names, int[] tracks) {

    /** Aucun clip : un asset sans section {@code ANIM}. */
    public static final AnimClips NONE = new AnimClips(List.of(), new int[0]);

    /** Copie les noms et les pistes. */
    public AnimClips {
        names = List.copyOf(names);
        tracks = tracks.clone();
        if (names.size() != tracks.length) {
            throw new IllegalArgumentException(names.size() + " noms pour " + tracks.length + " clips");
        }
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
    public boolean equals(Object other) {
        return other instanceof AnimClips that && names.equals(that.names) && Arrays.equals(tracks, that.tracks);
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
