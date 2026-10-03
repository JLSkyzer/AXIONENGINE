package dev.axion.asset;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Matériaux et textures d'un asset chargé, tels que le natif les remet (ADR-122 §6).
 *
 * <pre>
 * u32 material_count, u32 texture_count, u32 path_bytes, u32 0
 * MaterialDesc[material_count]   96 octets (DM-05)
 * TextureDesc[texture_count]     16 octets : RESOURCE, son chemin dans paths ;
 *                                EMBEDDED, décalage nul et taille du PNG
 * u8 paths[path_bytes]           chemins relatifs, UTF-8
 * </pre>
 *
 * <p>Le natif a tout contrôlé avant de déposer : comptes sous les plafonds de C-22,
 * énumérations connues, facteurs finis, seuil de découpe dans {@code [0, 1]}. Ce lecteur
 * vérifie ce dont dépend sa propre lecture — longueur annoncée, mot réservé, provenance des
 * textures, plages de chemins, slots dans la table — : un écart y serait un défaut de la
 * frontière, qui doit se voir plutôt que d'indexer à côté.
 *
 * <p>Les octets d'une texture embarquée ne voyagent pas ici : {@code axion_asset_texture} les
 * remet un par un, par rang. Un chemin de ressource est rendu tel qu'écrit, sans décodage ni
 * normalisation : le résoudre contre le répertoire du modèle, et le contrôler de nouveau
 * (R-531), revient au chargement des textures.
 *
 * <p>Les tableaux des matériaux sont ceux de l'instance, sans copie ; ils ne doivent pas être
 * modifiés.
 */
public final class MaterialTransfer {

    /** Taille de l'en-tête : trois dénombrements et une réserve. */
    public static final int HEADER_BYTES = 16;

    /** Taille d'un {@code MaterialDesc} (DM-05). */
    public static final int MATERIAL_BYTES = 96;

    /** Taille d'un {@code TextureDesc} (ADR-122 §2). */
    public static final int TEXTURE_BYTES = 16;

    /** Slot de texture vide : la texture neutre s'applique. */
    public static final int NO_TEXTURE = 0xFFFF;

    /** Provenance d'une texture : PNG embarqué, remis par {@code axion_asset_texture}. */
    public static final int SOURCE_EMBEDDED = 1;

    /** Provenance d'une texture : chemin relatif au modèle, chargé des resource packs. */
    public static final int SOURCE_RESOURCE = 2;

    /**
     * Un matériau (DM-05), facteurs en espace linéaire. Un slot de texture vaut le rang d'une
     * entrée de {@link #textures()}, ou {@link #NO_TEXTURE}.
     *
     * @param nameHash empreinte FNV-1a 64 du nom
     * @param albedoTexture slot de l'albedo
     * @param normalTexture slot de la carte de normales
     * @param ormTexture slot occlusion, rugosité, métal
     * @param emissiveTexture slot de l'émissive
     * @param heightTexture slot de la hauteur
     * @param damageTexture slot du dommage
     * @param albedoFactor facteur d'albedo, RGBA
     * @param emissiveFactor facteur d'émissive, RGB
     * @param metallic métal
     * @param roughness rugosité
     * @param occlusionStrength force de l'occlusion
     * @param normalScale échelle des normales
     * @param alphaCutoff seuil de découpe, dans {@code [0, 1]}
     * @param parallaxScale échelle de parallaxe
     * @param clearcoat vernis
     * @param clearcoatRoughness rugosité du vernis
     * @param sheen lustre
     * @param anisotropy anisotropie
     * @param blendMode mode de mélange : opaque 0, découpe 1, translucide 2
     * @param cullMode faces : arrière cachées 0, aucune 1
     * @param shadingModel modèle d'éclairage : PBR 0, vernis 1, lustre 2, sans éclairage 3,
     *     vanilla 4
     * @param flags drapeaux : couleur de sommet 1, teintable 2, pleine lumière 4, usure 8,
     *     décalques 16
     * @param wearProfile profil d'usure, {@code 0xFFFF} pour aucun
     */
    public record Material(
            long nameHash,
            int albedoTexture,
            int normalTexture,
            int ormTexture,
            int emissiveTexture,
            int heightTexture,
            int damageTexture,
            float[] albedoFactor,
            float[] emissiveFactor,
            float metallic,
            float roughness,
            float occlusionStrength,
            float normalScale,
            float alphaCutoff,
            float parallaxScale,
            float clearcoat,
            float clearcoatRoughness,
            float sheen,
            float anisotropy,
            int blendMode,
            int cullMode,
            int shadingModel,
            int flags,
            int wearProfile) {

        /** {@return les six slots, dans l'ordre de la disposition} */
        public int[] textureSlots() {
            return new int[] {
                albedoTexture, normalTexture, ormTexture, emissiveTexture, heightTexture, damageTexture
            };
        }
    }

    /**
     * Une texture : une image et son échantillonneur (ADR-122 §2).
     *
     * @param source {@link #SOURCE_EMBEDDED} ou {@link #SOURCE_RESOURCE}
     * @param format format de l'image : PNG 1, seul admis (R-532)
     * @param sampler bits 0-1 le filtrage — non déclaré 0, plus proche 1, linéaire 2 —, bit 2
     *     l'écrêtage de U, bit 3 celui de V ; répétition sinon
     * @param width largeur lue dans l'{@code IHDR} d'une texture embarquée ; 0 sinon
     * @param height hauteur lue dans l'{@code IHDR} d'une texture embarquée ; 0 sinon
     * @param size taille du PNG d'une texture embarquée, ou du chemin d'une ressource, en
     *     octets
     * @param path chemin relatif d'une ressource, tel qu'écrit ; {@code null} pour une
     *     texture embarquée
     */
    public record Texture(int source, int format, int sampler, int width, int height, long size, String path) {

        /** {@return vrai si les octets de la texture sont embarqués dans l'asset} */
        public boolean embedded() {
            return source == SOURCE_EMBEDDED;
        }
    }

    private static final MaterialTransfer EMPTY = new MaterialTransfer(List.of(), List.of());

    private final List<Material> materials;
    private final List<Texture> textures;

    private MaterialTransfer(List<Material> materials, List<Texture> textures) {
        this.materials = materials;
        this.textures = textures;
    }

    /** {@return une table sans matériau ni texture} */
    public static MaterialTransfer empty() {
        return EMPTY;
    }

    /**
     * Lit un transfert.
     *
     * @param bytes charge utile déposée par {@code axion_asset_materials}
     * @return les matériaux et les textures
     * @throws IllegalArgumentException si la longueur ne correspond pas aux dénombrements, si
     *     la réserve n'est pas nulle, si une texture est de provenance inconnue ou désigne hors
     *     de ses chemins, si un chemin n'est pas de l'UTF-8, ou si un slot désigne hors de la
     *     table
     */
    public static MaterialTransfer parse(byte[] bytes) {
        if (bytes.length < HEADER_BYTES) {
            throw new IllegalArgumentException("transfert de " + bytes.length + " octets : en-tête tronqué");
        }
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long materialCount = Integer.toUnsignedLong(in.getInt(0));
        long textureCount = Integer.toUnsignedLong(in.getInt(4));
        long pathBytes = Integer.toUnsignedLong(in.getInt(8));
        if (in.getInt(12) != 0) {
            throw new IllegalArgumentException("réserve d'en-tête non nulle");
        }
        long expected = HEADER_BYTES + materialCount * MATERIAL_BYTES + textureCount * TEXTURE_BYTES + pathBytes;
        if (expected != bytes.length) {
            throw new IllegalArgumentException("transfert de " + bytes.length
                    + " octets, " + expected + " annoncés par ses dénombrements");
        }

        int texturesAt = HEADER_BYTES + (int) materialCount * MATERIAL_BYTES;
        int pathsAt = texturesAt + (int) textureCount * TEXTURE_BYTES;

        List<Texture> textures = new ArrayList<>((int) textureCount);
        for (int rank = 0; rank < textureCount; rank++) {
            textures.add(texture(in, texturesAt + rank * TEXTURE_BYTES, bytes, pathsAt, pathBytes, rank));
        }

        List<Material> materials = new ArrayList<>((int) materialCount);
        for (int rank = 0; rank < materialCount; rank++) {
            Material material = material(in, HEADER_BYTES + rank * MATERIAL_BYTES);
            for (int slot : material.textureSlots()) {
                if (slot != NO_TEXTURE && slot >= textureCount) {
                    throw new IllegalArgumentException("matériau " + rank + " : slot " + slot
                            + " hors d'une table de " + textureCount + " textures");
                }
            }
            materials.add(material);
        }

        return new MaterialTransfer(List.copyOf(materials), List.copyOf(textures));
    }

    private static Material material(ByteBuffer in, int at) {
        float[] albedo = new float[4];
        for (int channel = 0; channel < 4; channel++) {
            albedo[channel] = in.getFloat(at + 20 + channel * 4);
        }
        float[] emissive = new float[3];
        for (int channel = 0; channel < 3; channel++) {
            emissive[channel] = in.getFloat(at + 36 + channel * 4);
        }
        return new Material(
                in.getLong(at),
                Short.toUnsignedInt(in.getShort(at + 8)),
                Short.toUnsignedInt(in.getShort(at + 10)),
                Short.toUnsignedInt(in.getShort(at + 12)),
                Short.toUnsignedInt(in.getShort(at + 14)),
                Short.toUnsignedInt(in.getShort(at + 16)),
                Short.toUnsignedInt(in.getShort(at + 18)),
                albedo,
                emissive,
                in.getFloat(at + 48),
                in.getFloat(at + 52),
                in.getFloat(at + 56),
                in.getFloat(at + 60),
                in.getFloat(at + 64),
                in.getFloat(at + 68),
                in.getFloat(at + 72),
                in.getFloat(at + 76),
                in.getFloat(at + 80),
                in.getFloat(at + 84),
                Byte.toUnsignedInt(in.get(at + 88)),
                Byte.toUnsignedInt(in.get(at + 89)),
                Byte.toUnsignedInt(in.get(at + 90)),
                Short.toUnsignedInt(in.getShort(at + 92)),
                Short.toUnsignedInt(in.getShort(at + 94)));
    }

    private static Texture texture(ByteBuffer in, int at, byte[] bytes, int pathsAt, long pathBytes, int rank) {
        int source = Byte.toUnsignedInt(in.get(at));
        long offset = Integer.toUnsignedLong(in.getInt(at + 8));
        long size = Integer.toUnsignedLong(in.getInt(at + 12));
        String path;
        if (source == SOURCE_EMBEDDED) {
            if (offset != 0) {
                throw new IllegalArgumentException("texture " + rank + " embarquée : décalage non nul");
            }
            path = null;
        } else if (source == SOURCE_RESOURCE) {
            if (offset + size > pathBytes) {
                throw new IllegalArgumentException("texture " + rank + " : chemin hors de la zone des chemins");
            }
            path = utf8(bytes, pathsAt + (int) offset, (int) size, rank);
        } else {
            throw new IllegalArgumentException("texture " + rank + " : provenance inconnue " + source);
        }
        return new Texture(
                source,
                Byte.toUnsignedInt(in.get(at + 1)),
                Byte.toUnsignedInt(in.get(at + 2)),
                Short.toUnsignedInt(in.getShort(at + 4)),
                Short.toUnsignedInt(in.getShort(at + 6)),
                size,
                path);
    }

    private static String utf8(byte[] bytes, int start, int length, int rank) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, start, length))
                    .toString();
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("texture " + rank + " : chemin qui n'est pas de l'UTF-8", malformed);
        }
    }

    /** {@return les matériaux, dans l'ordre de leurs index ({@code MeshDesc.material})} */
    public List<Material> materials() {
        return materials;
    }

    /** {@return les textures, dans l'ordre de leurs rangs} */
    public List<Texture> textures() {
        return textures;
    }
}
