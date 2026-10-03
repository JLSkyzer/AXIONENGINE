package dev.axion.asset;

/**
 * Ce que le rendu attend du chargeur d'assets natif (IF-06) : la géométrie (ADR-119), les
 * matériaux et les textures (ADR-122).
 *
 * <p>Injectable pour la même raison qu'{@link AssetCompiler} : le cache de maillages gère des
 * handles natifs qu'il doit rendre quoi qu'il arrive (R-321), et ses chemins d'échec ou de
 * concurrence ne seraient jamais vérifiés s'il fallait charger la bibliothèque native pour les
 * exercer.
 */
public interface AssetLoader {

    /**
     * Charge un asset compilé et rend sa géométrie.
     *
     * @param assetId identifiant de l'asset, celui que porte son en-tête
     * @param a3d conteneur A3D compilé
     * @return le handle et la géométrie, ou le code de l'échec
     */
    Loaded load(long assetId, byte[] a3d);

    /**
     * Rend un asset chargé ; son handle devient périmé (R-110).
     *
     * @param index index du handle
     * @param generation génération du handle
     * @return {@code 0}, ou le code d'erreur de l'ANNEXE A.1
     */
    int unload(int index, int generation);

    /**
     * Lit la table des matériaux et des textures d'un asset chargé (ADR-122 §6).
     *
     * @param index index du handle
     * @param generation génération du handle
     * @return la table, ou le code de l'échec
     */
    FetchedMaterials materials(int index, int generation);

    /**
     * Lit les octets PNG d'une texture embarquée d'un asset chargé (ADR-122 §6).
     *
     * @param index index du handle
     * @param generation génération du handle
     * @param texture rang de la texture dans {@link MaterialTransfer#textures()}
     * @return les octets, ou le code de l'échec
     */
    FetchedTexture texture(int index, int generation, int texture);

    /**
     * Ce qu'une lecture de la table des matériaux a produit.
     *
     * @param code {@code 0}, ou le code d'erreur de l'ANNEXE A.1
     * @param transfer matériaux et textures, vides en cas d'échec
     */
    record FetchedMaterials(int code, MaterialTransfer transfer) {

        /** {@return vrai si la table a été lue} */
        public boolean ok() {
            return code == 0;
        }

        /**
         * {@return une lecture refusée}
         *
         * @param code code de l'échec
         */
        public static FetchedMaterials failed(int code) {
            return new FetchedMaterials(code, MaterialTransfer.empty());
        }
    }

    /**
     * Ce qu'une lecture de texture embarquée a produit.
     *
     * @param code {@code 0}, ou le code d'erreur de l'ANNEXE A.1
     * @param png octets du fichier PNG, non décodés (R-532) ; vides en cas d'échec
     */
    record FetchedTexture(int code, byte[] png) {

        /** {@return vrai si les octets ont été lus} */
        public boolean ok() {
            return code == 0;
        }

        /**
         * {@return une lecture refusée}
         *
         * @param code code de l'échec
         */
        public static FetchedTexture failed(int code) {
            return new FetchedTexture(code, new byte[0]);
        }
    }

    /**
     * Ce qu'un chargement a produit.
     *
     * @param code {@code 0}, ou le code d'erreur de l'ANNEXE A.1
     * @param index index du handle, si le chargement a abouti
     * @param generation génération du handle, si le chargement a abouti
     * @param transfer charge utile de géométrie (ADR-119), vide en cas d'échec
     */
    record Loaded(int code, int index, int generation, byte[] transfer) {

        /** {@return vrai si l'asset est chargé et sa géométrie lue} */
        public boolean ok() {
            return code == 0;
        }

        /**
         * {@return un chargement refusé}
         *
         * @param code code de l'échec
         */
        public static Loaded failed(int code) {
            return new Loaded(code, 0, 0, new byte[0]);
        }
    }
}
