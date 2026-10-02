package dev.axion.bridge;

/**
 * Constantes des tampons de transfert (IF-02).
 *
 * <p><strong>Fichier généré — ne pas modifier à la main.</strong> La source est
 * {@code crates/ax-model/src/buffer.rs}. Régénérer avec :
 *
 * <pre>cargo run -p axion-codegen --bin gen_java_config</pre>
 *
 * <p>Les valeurs numériques font partie de l'ABI : les changer romprait la
 * compatibilité avec un binaire déjà distribué.
 */
public final class BufferKinds {

    private BufferKinds() {
        throw new AssertionError("classe de constantes, non instanciable");
    }

    /** Taille de l'en-tête de tout tampon, en octets. */
    public static final int HEADER_BYTES = 32;

    /**
     * Magic ouvrant tout tampon, lu comme un entier little-endian.
     *
     * <p>Correspond aux quatre octets {@code 'A' 'X' 'N' 'B'}. Un
     * {@link java.nio.ByteBuffer} doit être en little-endian (R-271) pour que
     * cette comparaison ait un sens.
     */
    public static final int MAGIC = 0x424E5841;

    /** Commandes de simulation, Java vers le natif. */
    public static final int SIM_IN = 1;

    /** États de bodies, natif vers Java. */
    public static final int SIM_OUT = 2;

    /** Événements physiques, de dommage, de rupture et de détachement. */
    public static final int EVENTS = 3;

    /** Impacts d'origine Minecraft. */
    public static final int IMPACT_IN = 4;

    /** Pages de champ de déformation modifiées. */
    public static final int DEFORM_OUT = 5;

    /** Paquets de déformation sérialisés. */
    public static final int DEFORM_NET = 6;

    /** Instances visibles, matrices, palettes, décalques, LOD. */
    public static final int RENDER_OUT = 7;

    /** Instances de la passe d'ombre. */
    public static final int SHADOW_OUT = 8;

    /** Source d'un asset à compiler. */
    public static final int ASSET_IN = 9;

    /** Asset compilé. */
    public static final int ASSET_OUT = 10;

    /** Charges utiles réseau sérialisées. */
    public static final int NET_OUT = 11;

    /** Blobs de persistance sérialisés. */
    public static final int PERSIST = 12;

    /** Géométrie de debug. */
    public static final int DEBUG = 13;

    /** Version du schéma de la charge de {@code SIM_OUT}, lue à l'octet 12 de l'en-tête. */
    public static final int SIM_OUT_SCHEMA = 1;
}
