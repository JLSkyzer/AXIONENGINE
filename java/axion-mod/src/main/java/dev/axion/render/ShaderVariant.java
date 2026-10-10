package dev.axion.render;

import java.util.EnumSet;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Une variante de shader (R-762, C-63, ADR-127 §4) : un ensemble de définitions prises dans la liste
 * fermée de R-762, insérées en tête des sources, juste après leur ligne {@code #version}. Logique
 * pure.
 *
 * @param defines les définitions de la variante
 */
public record ShaderVariant(Set<Define> defines) {

    /** Les définitions de R-762, dans son ordre. Aucune autre n'existe. */
    public enum Define {
        SKINNED,
        DEFORMED,
        DEFORMED_RESIDUAL,
        PARALLAX,
        CLEARCOAT,
        SHEEN,
        ANISO,
        DECALS,
        CUTOUT,
        SHADOW_RECV,
        SSR
    }

    /** La variante sans définition : celle de secours, compilée au démarrage du backend. */
    public static final ShaderVariant BASE = new ShaderVariant(EnumSet.noneOf(Define.class));

    /** Garde une copie triée, que l'appelant ne peut plus modifier. */
    public ShaderVariant {
        EnumSet<Define> copy = EnumSet.noneOf(Define.class);
        copy.addAll(defines);
        defines = Set.copyOf(copy);
    }

    /** {@return la variante qui porte ces définitions} */
    public static ShaderVariant of(Define... defines) {
        EnumSet<Define> set = EnumSet.noneOf(Define.class);
        for (Define define : defines) {
            set.add(define);
        }
        return new ShaderVariant(set);
    }

    /** {@return vrai si la variante porte cette définition} */
    public boolean has(Define define) {
        return defines.contains(define);
    }

    /**
     * {@return les sources, les définitions de la variante insérées juste après leur ligne
     * {@code #version}, dans l'ordre de R-762}
     *
     * @param source sources GLSL, qui commencent par une ligne {@code #version} (commentaires et
     *     lignes vides permis avant)
     * @throws IllegalArgumentException si elles n'en ont pas : GLSL exige qu'elle précède tout
     */
    public String apply(String source) {
        int at = versionLineEnd(source);
        StringBuilder out = new StringBuilder(source.length() + 32 * defines.size());
        out.append(source, 0, at);
        for (Define define : Define.values()) {
            if (defines.contains(define)) {
                out.append("#define ").append(define.name()).append('\n');
            }
        }
        out.append(source, at, source.length());
        return out.toString();
    }

    /** {@return un nom lisible : « BASE », ou les définitions jointes par « + »} */
    public String key() {
        if (defines.isEmpty()) {
            return "BASE";
        }
        StringJoiner joined = new StringJoiner("+");
        for (Define define : Define.values()) {
            if (defines.contains(define)) {
                joined.add(define.name());
            }
        }
        return joined.toString();
    }

    /** {@return l'indice qui suit la fin de la ligne {@code #version}, saut de ligne compris} */
    private static int versionLineEnd(String source) {
        int lineStart = 0;
        while (lineStart < source.length()) {
            int lineEnd = source.indexOf('\n', lineStart);
            int next = lineEnd < 0 ? source.length() : lineEnd + 1;
            String line = source.substring(lineStart, lineEnd < 0 ? source.length() : lineEnd).strip();
            if (line.startsWith("#version")) {
                return next;
            }
            if (!line.isEmpty() && !line.startsWith("//")) {
                break;
            }
            lineStart = next;
        }
        throw new IllegalArgumentException("sources GLSL sans ligne #version en tête");
    }
}
