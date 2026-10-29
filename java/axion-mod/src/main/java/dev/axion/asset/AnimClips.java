package dev.axion.asset;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Les clips d'un asset, tels que la section {@code ANIM} les décrit (DM-10, ADR-155 D2) : le nom et le
 * nombre de pistes de chacun, ce que le serveur Java lit pour {@code AnimationControl} — les noms que
 * l'API joue, les pistes que R-200 compte. Les clés restent au natif, qui seul échantillonne.
 *
 * <p>Disposition, little-endian (R-271) : quatre {@code u32} — clips, pistes, flottants des clés, octets
 * des noms —, les {@code AnimationDesc} de 24 octets, les {@code TrackDesc} de 16, une référence de nom
 * de 8 octets par clip, les clés, puis les noms UTF-8. Elle est fixée côté natif
 * ({@code crates/ax-asset/src/a3d/animation.rs}).
 *
 * @param names le nom de chaque clip, vide s'il n'en a pas
 * @param tracks le nombre de pistes de chaque clip
 */
public record AnimClips(List<String> names, int[] tracks) {

    /** Aucun clip : un asset sans section {@code ANIM}. */
    public static final AnimClips NONE = new AnimClips(List.of(), new int[0]);

    private static final int HEADER_BYTES = 16;
    private static final int ANIMATION_BYTES = 24;
    private static final int TRACK_BYTES = 16;
    private static final int NAME_REF_BYTES = 8;
    /** Décalage de {@code track_count} dans un {@code AnimationDesc}. */
    private static final int TRACK_COUNT_OFFSET = 16;

    /** Copie les noms et les pistes. */
    public AnimClips {
        names = List.copyOf(names);
        tracks = tracks.clone();
        if (names.size() != tracks.length) {
            throw new IllegalArgumentException(names.size() + " noms pour " + tracks.length + " clips");
        }
    }

    /**
     * @param anim les octets d'une section {@code ANIM}, ou {@code null} : aucun clip
     * @return ses clips
     * @throws IllegalArgumentException si la taille de la section n'est pas celle qu'elle annonce, ou qu'un
     *     nom sort de sa table ou n'est pas de l'UTF-8
     */
    public static AnimClips decode(byte[] anim) {
        if (anim == null) {
            return NONE;
        }
        if (anim.length < HEADER_BYTES) {
            throw new IllegalArgumentException("section ANIM tronquée : " + anim.length + " octets");
        }
        ByteBuffer buffer = ByteBuffer.wrap(anim).order(ByteOrder.LITTLE_ENDIAN);
        long animations = Integer.toUnsignedLong(buffer.getInt(0));
        long trackCount = Integer.toUnsignedLong(buffer.getInt(4));
        long keyCount = Integer.toUnsignedLong(buffer.getInt(8));
        long namesSize = Integer.toUnsignedLong(buffer.getInt(12));
        long refs = HEADER_BYTES + animations * ANIMATION_BYTES + trackCount * TRACK_BYTES;
        long names = refs + animations * NAME_REF_BYTES + keyCount * Float.BYTES;
        if (names + namesSize != anim.length) {
            throw new IllegalArgumentException(
                    "section ANIM de " + anim.length + " octets, " + (names + namesSize) + " annoncés");
        }
        List<String> clipNames = new ArrayList<>((int) animations);
        int[] tracks = new int[(int) animations];
        for (int clip = 0; clip < animations; clip++) {
            tracks[clip] = buffer.getInt(HEADER_BYTES + clip * ANIMATION_BYTES + TRACK_COUNT_OFFSET);
            long offset = Integer.toUnsignedLong(buffer.getInt((int) refs + clip * NAME_REF_BYTES));
            long length = Integer.toUnsignedLong(buffer.getInt((int) refs + clip * NAME_REF_BYTES + 4));
            if (offset + length > namesSize) {
                throw new IllegalArgumentException("nom du clip " + clip + " hors de la table des noms");
            }
            clipNames.add(utf8(anim, (int) (names + offset), (int) length, clip));
        }
        return new AnimClips(clipNames, tracks);
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

    private static String utf8(byte[] bytes, int at, int length, int clip) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, at, length))
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            throw new IllegalArgumentException("nom du clip " + clip + " : pas de l'UTF-8", notUtf8);
        }
    }
}
