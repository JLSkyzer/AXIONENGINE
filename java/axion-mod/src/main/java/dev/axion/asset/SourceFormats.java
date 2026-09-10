package dev.axion.asset;

import java.util.Locale;

/**
 * Formats de source reconnus (C-21), et leur code de passage vers le natif.
 *
 * <p>Les codes sont ceux qu'{@code axion_asset_compile} attend. Ils sont
 * declares une fois ici : les ecrire a l'appel les disperserait, et un chiffre
 * disperse finit par diverger.
 */
public final class SourceFormats {

    /** glTF 2.0 binaire. */
    public static final int GLB = 0;

    /** glTF 2.0 JSON. */
    public static final int GLTF = 1;

    /** Wavefront OBJ. */
    public static final int OBJ = 2;

    /** STL. */
    public static final int STL = 3;

    /** Valeur rendue lorsqu'aucun format ne correspond. */
    public static final int UNKNOWN = -1;

    private SourceFormats() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /**
     * Reconnait le format au chemin du fichier.
     *
     * <p>Un {@code .a3d} n'en est pas un : c'est deja le resultat d'une
     * compilation, et le repasser au compilateur serait compiler une sortie.
     *
     * @param path chemin de la ressource
     * @return le code de format, ou {@link #UNKNOWN}
     */
    public static int fromPath(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return UNKNOWN;
        }
        return switch (path.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "glb" -> GLB;
            case "gltf" -> GLTF;
            case "obj" -> OBJ;
            case "stl" -> STL;
            default -> UNKNOWN;
        };
    }

    /**
     * {@return le nom du format, pour les messages}
     *
     * @param format code de format
     */
    public static String name(int format) {
        return switch (format) {
            case GLB -> "glb";
            case GLTF -> "gltf";
            case OBJ -> "obj";
            case STL -> "stl";
            default -> "inconnu";
        };
    }
}
