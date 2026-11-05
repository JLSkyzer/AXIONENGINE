package dev.axion.asset;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Le natif, vu de la compilation d'une definition (étapes 4 et 5 de C-27, ADR-156 D3, D4) : une
 * definition compilée contre le conteneur de son asset, inscrite au registre de la session sous son
 * empreinte.
 */
public interface DefinitionCompiler {

    /** {@code E-7001} : definition invalide (R-580). */
    int E_DEFINITION_INVALID = -7001;

    /** {@code E-3050} : plafond d'assembly dépassé (R-190). */
    int E_ASSEMBLY_LIMIT = -3050;

    /** {@code E-7002} : node physique sous un parent animé (R-931). */
    int E_PHYSICS_UNDER_ANIMATED = -7002;

    /**
     * Compile une definition contre son asset.
     *
     * @param definition empreinte de la definition, celle que porte le NBT
     * @param assetId identifiant de l'asset, celui que porte son en-tête
     * @param a3d conteneur A3D compilé de l'asset
     * @param defn la definition encodée ({@code DEFN})
     * @return le résumé, le refus, ou l'échec
     */
    Result compile(long definition, long assetId, byte[] a3d, byte[] defn);

    /**
     * Retire une definition du registre de la session.
     *
     * @param definition empreinte de la definition
     * @return vrai si le registre la tenait
     */
    boolean remove(long definition);

    /**
     * Ce qu'une definition compilée donne à Java (ADR-156 D4).
     *
     * @param roots node racine de chaque corps, le corps racine d'abord
     * @param joints nombre de joints
     * @param serverAnimated le serveur joue l'animation de l'asset : un collider dépend d'un node
     *     animé (R-1430)
     * @param warnings avertissements de la compilation (R-930)
     * @param clips les clips de l'asset, leur nom et leur nombre de pistes : ce que l'animation Java
     *     joue et compte (R-200), sans lire la section {@code ANIM} (décision de Killian, tranche 4)
     */
    record Summary(int[] roots, int joints, boolean serverAnimated, List<String> warnings, AnimClips clips) {

        /** Copie les tableaux : un résumé ne change plus. */
        public Summary {
            roots = roots.clone();
            warnings = List.copyOf(warnings);
            if (clips == null) {
                throw new IllegalArgumentException("les clips sont requis, AnimClips.NONE sans");
            }
        }

        /** {@return une copie des nodes racines} */
        @Override
        public int[] roots() {
            return roots.clone();
        }

        /** {@return le node racine du corps racine} */
        public int rootNode() {
            return roots[0];
        }

        /**
         * {@return le résumé que le natif a déposé, ou {@code null} s'il ne se lit pas} Disposition :
         * nombre de corps, de joints, drapeaux, réservé ({@code u32}), le node racine de chaque corps
         * ({@code u32}), les avertissements en lignes, puis les clips : leur nombre ({@code u32}), et par
         * clip son nombre de pistes ({@code u32}) et son nom, en une liste d'une ligne.
         *
         * @param payload la charge d'{@code ASSET_OUT}
         */
        public static Summary read(byte[] payload) {
            ByteBuffer in = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
            try {
                int bodies = in.getInt();
                int joints = in.getInt();
                int flags = in.getInt();
                in.getInt();
                if (bodies < 1 || bodies > in.remaining() / Integer.BYTES) {
                    return null;
                }
                int[] roots = new int[bodies];
                for (int i = 0; i < bodies; i++) {
                    roots[i] = in.getInt();
                }
                List<String> warnings = lines(in);
                if (warnings == null) {
                    return null;
                }
                int clipCount = in.getInt();
                if (clipCount < 0 || clipCount > in.remaining() / (3 * Integer.BYTES)) {
                    return null;
                }
                List<String> names = new ArrayList<>(clipCount);
                int[] tracks = new int[clipCount];
                for (int clip = 0; clip < clipCount; clip++) {
                    tracks[clip] = in.getInt();
                    List<String> name = lines(in);
                    if (tracks[clip] < 0 || name == null || name.size() != 1) {
                        return null;
                    }
                    names.add(name.get(0));
                }
                return in.hasRemaining()
                        ? null
                        : new Summary(roots, joints, (flags & 1) != 0, warnings, new AnimClips(names, tracks));
            } catch (java.nio.BufferUnderflowException truncated) {
                return null;
            }
        }
    }

    /**
     * L'issue d'une compilation.
     *
     * @param code {@code 0} si la definition se compile ; {@link #E_DEFINITION_INVALID},
     *     {@link #E_ASSEMBLY_LIMIT} ou {@link #E_PHYSICS_UNDER_ANIMATED} pour un refus ; un autre code
     *     négatif pour un échec de la frontière, où la definition n'est pour rien
     * @param summary le résumé, si elle se compile
     * @param reference rang de la référence fautive d'un refus, {@code -1} sans
     * @param message motif d'un refus ou d'un échec
     * @param assetWarnings avertissements de l'asset chargé pour la compilation (ADR-156 D4) : les
     *     tables refusées sans que l'asset le soit
     */
    record Result(int code, Summary summary, int reference, String message, List<String> assetWarnings) {

        /** Copie les avertissements de l'asset. */
        public Result {
            assetWarnings = List.copyOf(assetWarnings);
        }

        /**
         * Une issue sans avertissement d'asset.
         *
         * @param code le code
         * @param summary le résumé, si elle se compile
         * @param reference rang de la référence fautive d'un refus, {@code -1} sans
         * @param message motif d'un refus ou d'un échec
         */
        public Result(int code, Summary summary, int reference, String message) {
            this(code, summary, reference, message, List.of());
        }

        /**
         * {@return la même issue, avec les avertissements de l'asset}
         *
         * @param warnings les avertissements de l'asset
         */
        public Result withAssetWarnings(List<String> warnings) {
            return new Result(code, summary, reference, message, warnings);
        }

        /** {@return vrai si la definition se compile} */
        public boolean compiled() {
            return code == 0 && summary != null;
        }

        /** {@return vrai si le natif refuse la definition elle-même} */
        public boolean refused() {
            return code == E_DEFINITION_INVALID || code == E_ASSEMBLY_LIMIT || code == E_PHYSICS_UNDER_ANIMATED;
        }

        /**
         * {@return le refus que le natif a déposé, ou un échec s'il ne se lit pas} Disposition : code
         * ({@code i32}), rang de la référence fautive ({@code u32}, {@code 0xFFFFFFFF} sans), puis le
         * motif en une ligne.
         *
         * @param code le code que l'appel a rendu
         * @param payload la charge d'{@code ASSET_OUT}
         */
        public static Result readRefusal(int code, byte[] payload) {
            ByteBuffer in = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
            try {
                int written = in.getInt();
                int reference = in.getInt();
                List<String> lines = lines(in);
                if (written != code || lines == null || lines.size() != 1 || in.hasRemaining()) {
                    return new Result(code, null, -1, "refus illisible");
                }
                return new Result(code, null, reference, lines.get(0));
            } catch (java.nio.BufferUnderflowException truncated) {
                return new Result(code, null, -1, "refus tronqué");
            }
        }
    }

    /** Une liste de lignes : {@code u32} du nombre, puis par ligne {@code u32} de sa longueur et ses octets UTF-8. */
    private static List<String> lines(ByteBuffer in) {
        int count = in.getInt();
        if (count < 0 || count > in.remaining() / Integer.BYTES) {
            return null;
        }
        List<String> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int length = in.getInt();
            if (length < 0 || length > in.remaining()) {
                return null;
            }
            byte[] bytes = new byte[length];
            in.get(bytes);
            lines.add(new String(bytes, StandardCharsets.UTF_8));
        }
        return lines;
    }
}
