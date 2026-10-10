package dev.axion.render;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * La clé d'un programme dans le cache binaire des shaders (R-760, ADR-127 §4) — logique pure.
 *
 * <p>Elle couvre tout ce dont dépend le binaire : le nom du programme, ses définitions de R-762, ses
 * sources, et la chaîne du pilote — {@code GL_VENDOR}, {@code GL_RENDERER}, {@code GL_VERSION}. Que
 * l'un d'eux change, la clé change : un binaire d'autres sources ou d'un autre pilote n'est jamais
 * relu, il est recompilé (T-905). Chaque champ entre dans l'empreinte précédé de sa longueur : deux
 * découpages différents des mêmes caractères ne donnent pas la même clé.
 */
public final class ShaderCacheKey {

    private ShaderCacheKey() {}

    /**
     * {@return la clé, 64 chiffres hexadécimaux : l'empreinte SHA-256 des champs}
     *
     * @param program nom du programme
     * @param variant ses définitions
     * @param vertexSource source des sommets, telle que lue
     * @param fragmentSource source des fragments, telle que lue, sans les définitions
     * @param vendor {@code GL_VENDOR}
     * @param renderer {@code GL_RENDERER}
     * @param version {@code GL_VERSION}
     */
    public static String of(
            String program,
            ShaderVariant variant,
            String vertexSource,
            String fragmentSource,
            String vendor,
            String renderer,
            String version) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException absent) {
            // Toute JVM fournit SHA-256 (exigé par la spécification de la plateforme Java).
            throw new IllegalStateException("SHA-256 indisponible", absent);
        }
        for (String field : new String[] {
            program, variant.key(), vertexSource, fragmentSource, vendor, renderer, version
        }) {
            byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
            digest.update(new byte[] {
                (byte) (bytes.length >>> 24), (byte) (bytes.length >>> 16), (byte) (bytes.length >>> 8), (byte) bytes.length
            });
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
