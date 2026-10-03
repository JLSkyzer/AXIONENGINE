package dev.axion.asset;

import dev.axion.bridge.BufferKinds;
import dev.axion.bridge.NativeBridge;
import java.nio.ByteBuffer;

/**
 * Chargement d'un asset compilé dans le natif et récupération de sa géométrie (IF-06,
 * ADR-119), de ses matériaux et de ses textures (ADR-122).
 *
 * <p>Le protocole est celui d'IF-02, entièrement sous le verrou des tampons d'asset
 * ({@link AssetBuffers}) : écrire le conteneur dans {@code ASSET_IN}, charger, relâcher ;
 * demander une charge — géométrie, table des matériaux ou octets d'une texture —, la lire
 * dans {@code ASSET_OUT}, relâcher. Les tampons sont ré-acquis à chaque fois, jamais
 * conservés (R-270), et toujours relâchés : les garder fausserait le bilan de l'arrêt
 * (R-322).
 *
 * <p>Le handle rendu appartient à l'appelant, qui doit le rendre par {@link #unload}
 * (R-321) ; un handle oublié est signalé à l'arrêt du natif.
 */
public final class NativeAssetLoader implements AssetLoader {

    /**
     * Sections chargées pour le rendu : les nodes et la géométrie (ADR-119), les matériaux et
     * les textures (ADR-122).
     */
    public static final int RENDER_SECTIONS = NativeBridge.SECTION_NODE
            | NativeBridge.SECTION_GEOM
            | NativeBridge.SECTION_MATL
            | NativeBridge.SECTION_TEXR;

    /** Premier schéma d'{@code ASSET_OUT} portant la géométrie : inchangée depuis ADR-119. */
    private static final int GEOMETRY_SINCE_SCHEMA = 0;

    /** Premier schéma d'{@code ASSET_OUT} portant matériaux et textures (ADR-122). */
    private static final int MATERIALS_SINCE_SCHEMA = 1;

    /** Position de la génération dans l'en-tête d'un tampon (IF-02). */
    private static final int GENERATION_OFFSET = 8;

    /** Position de la version de schéma dans l'en-tête d'un tampon (IF-02, R-271). */
    private static final int SCHEMA_OFFSET = 12;

    private final long context;
    private final long[] handleOut = new long[NativeBridge.ASSET_LOAD_SLOTS];
    private final long[] sizeOut = new long[1];

    /**
     * Crée un chargeur adossé à un contexte natif.
     *
     * @param context jeton de contexte
     */
    public NativeAssetLoader(long context) {
        this.context = context;
    }

    /**
     * Charge un asset compilé et rend sa géométrie.
     *
     * <p>Peut s'appeler depuis n'importe quel thread : la séquence est sérialisée avec la
     * compilation, qui emploie les mêmes tampons. Si la géométrie ne peut être lue, le
     * handle est rendu aussitôt : un échec ne laisse rien de chargé.
     *
     * @param assetId identifiant de l'asset, celui que porte son en-tête
     * @param a3d conteneur A3D compilé
     * @return le handle et la géométrie, ou le code de l'échec
     */
    @Override
    public Loaded load(long assetId, byte[] a3d) {
        synchronized (AssetBuffers.LOCK) {
            ByteBuffer in = NativeBridge.acquire(context, BufferKinds.ASSET_IN, a3d.length);
            if (in == null) {
                return Loaded.failed(NativeBridge.E_INVALID_BUFFER);
            }
            int inGeneration = in.getInt(GENERATION_OFFSET);
            in.position(BufferKinds.HEADER_BYTES);
            if (in.remaining() < a3d.length) {
                NativeBridge.release(context, BufferKinds.ASSET_IN, inGeneration);
                return Loaded.failed(NativeBridge.E_INVALID_BUFFER);
            }
            in.put(a3d);

            int code = NativeBridge.loadAsset(context, assetId, RENDER_SECTIONS, handleOut);
            // Le natif a copié le conteneur : le tampon d'entrée n'a plus de raison d'être.
            NativeBridge.release(context, BufferKinds.ASSET_IN, inGeneration);
            if (code != NativeBridge.OK) {
                return Loaded.failed(code);
            }
            int index = (int) handleOut[0];
            int generation = (int) handleOut[1];

            byte[] transfer = readGeometry(index, generation);
            if (transfer == null) {
                NativeBridge.unloadAsset(context, index, generation);
                return Loaded.failed(NativeBridge.E_INVALID_BUFFER);
            }
            return new Loaded(NativeBridge.OK, index, generation, transfer);
        }
    }

    /**
     * Rend un asset chargé ; son handle devient périmé (R-110).
     *
     * @param index index du handle
     * @param generation génération du handle
     * @return {@link NativeBridge#OK}, ou {@link NativeBridge#E_INVALID_HANDLE} si le handle
     *     est déjà périmé
     */
    @Override
    public int unload(int index, int generation) {
        return NativeBridge.unloadAsset(context, index, generation);
    }

    /**
     * Lit la table des matériaux et des textures d'un asset chargé avec {@code MATL | TEXR}
     * (ADR-122 §6).
     *
     * <p>Peut s'appeler depuis n'importe quel thread, comme {@link #load}. La table est
     * rebâtie depuis l'asset résident à chaque appel : la redemander après un rechargement de
     * ressources ne recharge pas l'asset (R-752).
     *
     * @param index index du handle
     * @param generation génération du handle
     * @return la table, ou le code de l'échec : {@link NativeBridge#E_INVALID_HANDLE} pour un
     *     handle périmé, {@link NativeBridge#E_INVALID_BUFFER} pour un asset chargé sans
     *     {@code MATL | TEXR} ou une charge illisible
     */
    @Override
    public FetchedMaterials materials(int index, int generation) {
        synchronized (AssetBuffers.LOCK) {
            int code = NativeBridge.materialsOf(context, index, generation, sizeOut);
            if (code != NativeBridge.OK) {
                return FetchedMaterials.failed(code);
            }
            byte[] bytes = readAssetOut(MATERIALS_SINCE_SCHEMA);
            if (bytes == null) {
                return FetchedMaterials.failed(NativeBridge.E_INVALID_BUFFER);
            }
            try {
                return new FetchedMaterials(NativeBridge.OK, MaterialTransfer.parse(bytes));
            } catch (IllegalArgumentException malformed) {
                return FetchedMaterials.failed(NativeBridge.E_INVALID_BUFFER);
            }
        }
    }

    /**
     * Lit les octets PNG de la texture embarquée de rang {@code texture} d'un asset chargé
     * (ADR-122 §6) : une texture par appel.
     *
     * @param index index du handle
     * @param generation génération du handle
     * @param texture rang de la texture dans {@link MaterialTransfer#textures()}
     * @return les octets, ou le code de l'échec : {@link NativeBridge#E_INVALID_HANDLE} pour
     *     un handle périmé, {@link NativeBridge#E_INVALID_BUFFER} pour un rang hors de la
     *     table ou une texture de ressource
     */
    @Override
    public FetchedTexture texture(int index, int generation, int texture) {
        synchronized (AssetBuffers.LOCK) {
            int code = NativeBridge.textureOf(context, index, generation, texture, sizeOut);
            if (code != NativeBridge.OK) {
                return FetchedTexture.failed(code);
            }
            byte[] png = readAssetOut(MATERIALS_SINCE_SCHEMA);
            return png == null
                    ? FetchedTexture.failed(NativeBridge.E_INVALID_BUFFER)
                    : new FetchedTexture(NativeBridge.OK, png);
        }
    }

    /** Demande la géométrie et la lit dans {@code ASSET_OUT} ; {@code null} en cas d'échec. */
    private byte[] readGeometry(int index, int generation) {
        if (NativeBridge.geometryOf(context, index, generation, sizeOut) != NativeBridge.OK) {
            return null;
        }
        return readAssetOut(GEOMETRY_SINCE_SCHEMA);
    }

    /**
     * Lit la charge que le dernier dépôt a laissée dans {@code ASSET_OUT}, de la taille reçue
     * dans {@code sizeOut[0]}, puis relâche le tampon.
     *
     * @param sinceSchema premier schéma qui porte la disposition attendue
     * @return la charge, ou {@code null} si le tampon manque, si son schéma est antérieur, ou
     *     si la taille annoncée n'y tient pas
     */
    private byte[] readAssetOut(int sinceSchema) {
        ByteBuffer out = NativeBridge.acquire(context, BufferKinds.ASSET_OUT, 0);
        if (out == null) {
            return null;
        }
        int outGeneration = out.getInt(GENERATION_OFFSET);
        try {
            long size = sizeOut[0];
            if (out.getInt(SCHEMA_OFFSET) < sinceSchema
                    || size < 0
                    || size > out.capacity() - BufferKinds.HEADER_BYTES) {
                return null;
            }
            byte[] payload = new byte[(int) size];
            out.position(BufferKinds.HEADER_BYTES);
            out.get(payload);
            return payload;
        } finally {
            NativeBridge.release(context, BufferKinds.ASSET_OUT, outGeneration);
        }
    }
}
