package dev.axion.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.axion.physics.AssemblyJoints;
import dev.axion.physics.BodyDescription;
import dev.axion.physics.JointDescription;
import dev.axion.physics.SimCommandStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * {@code DEFN} : une definition acceptée aux étapes 1 à 3, telle que le natif la compile contre son
 * asset (étape 4 de C-27, ADR-156 D2).
 *
 * <p>Java tranche ce qui relève du JSON et des registres de données : la description DM-08 de chaque
 * corps, les réglages des joints, le matériau de chaque part, les groupes de collision en bits. Le
 * natif résout tout ce qui désigne l'asset : chaque nom de node, de clip ou d'os y est une référence,
 * l'empreinte FNV-1a 64 de son nom et sa catégorie. Le texte des noms ne passe pas ; un refus du natif
 * rend le rang de la référence fautive, que {@link Encoded#locate} ramène à son chemin JSON.
 *
 * <p>Disposition, celle d'{@code ax_model::dm::definition}, little-endian et sans remplissage :
 * <pre>
 * en-tête       144 o : "DEFN", version u16, drapeaux u16, groupes u32, filtre u32, masse f32,
 *                       références u32, corps u16, joints u16, points u16, parts u16,
 *                       DM-08 de l'assembly entière, DM-08 d'un corps implicite
 * référence      16 o : empreinte u64, catégorie u8, 7 octets à zéro
 * corps          64 o : DM-08, rang de la référence de son root_node (u32, -1 : racine de l'asset), 0 u32
 * joint         160 o : JointDescription, b_node au rang de sa référence, corps b à 0xFFFF
 * point           8 o : f32, f32
 * part            8 o : rang de la référence de son root_node u32, matériau u16 (0xFFFF sans), drapeaux u16
 * </pre>
 */
public final class DefinitionEncoder {

    /** Version du format. */
    public static final int VERSION = 1;

    /** Taille de l'en-tête, en octets. */
    public static final int HEADER_BYTES = 32 + 2 * BodyDescription.WIRE_BYTES;

    /** Taille d'une référence, en octets. */
    public static final int REFERENCE_BYTES = 16;

    /** Taille d'un corps, en octets. */
    public static final int BODY_BYTES = BodyDescription.WIRE_BYTES + 8;

    /** Taille d'une part, en octets. */
    public static final int PART_BYTES = 8;

    /** Les corps de l'assembly se touchent entre eux ({@code physics.self_collision}). */
    static final int FLAG_SELF_COLLISION = 1;

    /** La part porte au moins une région de déformation (R-623). */
    static final int PART_DEFORMABLE = 1;

    /** Catégorie d'une référence à un node. */
    static final int CATEGORY_NODE = 0;

    /** Catégorie d'une référence à un clip d'animation. */
    static final int CATEGORY_CLIP = 1;

    /** Catégorie d'une référence à un os. */
    static final int CATEGORY_BONE = 2;

    /** Aucun rang, sur 32 bits : la racine de l'asset pour le corps racine. */
    private static final int NONE_U32 = -1;

    /** Aucun rang, ni matériau, sur 16 bits. */
    private static final int NONE_U16 = 0xFFFF;

    private DefinitionEncoder() {}

    /**
     * Un {@code DEFN} et ses références.
     *
     * @param bytes les octets
     * @param references les références à l'asset, dans l'ordre de leurs rangs
     */
    public record Encoded(byte[] bytes, List<DefinitionChecker.AssetReference> references) {

        /** Copie les octets : un tableau partagé se modifierait sous les pieds du natif. */
        public Encoded {
            bytes = bytes.clone();
            references = List.copyOf(references);
        }

        /** {@return une copie des octets} */
        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        /**
         * {@return le chemin JSON et le nom de la référence de rang {@code rank}, pour un message ; le
         * document entier si le rang n'en désigne aucune}
         *
         * @param rank rang rendu par le natif, {@code -1} sans
         */
        public String locate(int rank) {
            if (rank < 0 || rank >= references.size()) {
                return "$";
            }
            DefinitionChecker.AssetReference reference = references.get(rank);
            return reference.path() + " « " + reference.name() + " »";
        }
    }

    /**
     * Écrit le {@code DEFN} d'une definition acceptée.
     *
     * @param definition la definition
     * @param withJoints faux si le module joints est désactivé ({@code modules.joints}) : ni joint ni
     *     corps implicite
     * @param groups le registre contre lequel elle a été validée, qui résout ses groupes en bits
     * @param materialIndex index d'un matériau physique dans la table de la session (ADR-149)
     * @return le {@code DEFN} et ses références
     * @throws DefinitionException {@code E-7001} pour ce que Java rendait inerte à l'apparition et
     *     que le natif ne peut pas trancher : un corps autre que le premier sans {@code root_node}, un
     *     joint incohérent au schéma 1, un joint dont {@code a} ne nomme aucun corps
     */
    public static Encoded encode(
            Definition definition,
            boolean withJoints,
            CollisionGroupRegistry groups,
            ToIntFunction<String> materialIndex)
            throws DefinitionException {
        JsonObject root = definition.root();
        List<DefinitionChecker.AssetReference> references = DefinitionChecker.assetReferences(root);
        Map<String, Integer> byPath = new HashMap<>();
        for (int rank = 0; rank < references.size(); rank++) {
            byPath.put(references.get(rank).path(), rank);
        }

        int bodyType = definition.kind() == AssemblyKind.STATIC
                ? SimCommandStream.BODY_STATIC
                : SimCommandStream.BODY_DYNAMIC;
        BodyDescription physics = BodyDescription.of(root, bodyType);
        boolean selfCollision = root.get("physics") instanceof JsonObject block
                && block.get("self_collision") != null
                && block.get("self_collision").getAsBoolean();

        // ADR-151 D2 : chaque corps déclaré à son root_node ; le premier peut prendre la racine de
        // l'asset. Sa masse déclarée lui est propre, celle de l'assembly se répartit entre les autres.
        JsonArray bodies = root.get("bodies") instanceof JsonArray declared ? declared : new JsonArray();
        List<BodyDescription> descriptions = new ArrayList<>();
        List<Integer> bodyRoots = new ArrayList<>();
        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < bodies.size(); i++) {
            JsonObject body = bodies.get(i).getAsJsonObject();
            float mass = body.has("mass") ? body.get("mass").getAsFloat() : 0.0f;
            Integer reference = byPath.get("$.bodies[" + i + "].root_node");
            if (reference == null && i > 0) {
                throw DefinitionException.invalid("$.bodies[" + i + "]",
                        "seul le premier corps peut se passer de root_node : il prend la racine de l'asset");
            }
            bodyRoots.add(reference == null ? NONE_U32 : reference);
            descriptions.add(i == 0 ? physics.asRootBody(mass) : physics.asSecondaryBody(mass));
            if (body.get("name") instanceof JsonPrimitive name) {
                ranks.put(name.getAsString(), i);
            }
        }

        // ADR-151 D3, D7 : les joints, leur b_node en référence ; le natif tranche le corps b.
        List<JointDescription> joints = new ArrayList<>();
        List<float[]> jamPoints = new ArrayList<>();
        if (withJoints && !bodies.isEmpty() && root.get("joints") instanceof JsonArray declared) {
            for (int k = 0; k < declared.size(); k++) {
                JsonObject joint = declared.get(k).getAsJsonObject();
                String path = "$.joints[" + k + "]";
                AssemblyJoints.Fault fault = AssemblyJoints.check(joint);
                if (fault != null) {
                    throw DefinitionException.invalid(path + "." + fault.field(), fault.message());
                }
                Integer bodyA = ranks.get(joint.get("a").getAsString());
                if (bodyA == null) {
                    throw DefinitionException.invalid(path + ".a", "ne nomme aucun corps déclaré");
                }
                Integer bNode = byPath.get(path + ".b_node");
                if (bNode == null) {
                    throw DefinitionException.invalid(path + ".b_node", "champ obligatoire d'un joint");
                }
                joints.add(AssemblyJoints.transcribe(joint, bodyA, NONE_U16, bNode, jamPoints));
            }
        }

        // ADR-149 D2, R-623 : chaque part par son root_node, son matériau et sa déformabilité.
        List<int[]> parts = new ArrayList<>();
        if (root.get("parts") instanceof JsonArray declared) {
            for (int i = 0; i < declared.size(); i++) {
                if (!(declared.get(i) instanceof JsonObject part)) {
                    continue;
                }
                Integer reference = byPath.get("$.parts[" + i + "].root_node");
                if (reference == null) {
                    // Sans racine, la part ne désigne aucun collider.
                    continue;
                }
                int material = part.get("material") instanceof JsonPrimitive name
                        ? materialIndex.applyAsInt(name.getAsString())
                        : NONE_U16;
                int flags = part.get("deform_regions") instanceof JsonArray regions && !regions.isEmpty()
                        ? PART_DEFORMABLE
                        : 0;
                parts.add(new int[] {reference, material, flags});
            }
        }

        int size = HEADER_BYTES
                + references.size() * REFERENCE_BYTES
                + bodies.size() * BODY_BYTES
                + joints.size() * JointDescription.BYTES
                + jamPoints.size() * JointDescription.JAM_POINT_BYTES
                + parts.size() * PART_BYTES;
        ByteBuffer out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        out.put("DEFN".getBytes(StandardCharsets.US_ASCII));
        out.putShort((short) VERSION);
        out.putShort((short) (selfCollision ? FLAG_SELF_COLLISION : 0));
        out.putInt(groups.membership(root));
        out.putInt(groups.filter(root));
        out.putFloat(physics.mass());
        out.putInt(references.size());
        out.putShort((short) bodies.size());
        out.putShort((short) joints.size());
        out.putShort((short) jamPoints.size());
        out.putShort((short) parts.size());
        physics.writeTo(out);
        physics.asSecondaryBody(0.0f).writeTo(out);
        for (DefinitionChecker.AssetReference reference : references) {
            out.putLong(DefinitionIds.hash(reference.name()));
            out.put((byte) switch (reference.category()) {
                case NODE -> CATEGORY_NODE;
                case CLIP -> CATEGORY_CLIP;
                default -> CATEGORY_BONE;
            });
            out.put(new byte[7]);
        }
        for (int i = 0; i < descriptions.size(); i++) {
            descriptions.get(i).writeTo(out);
            out.putInt(bodyRoots.get(i));
            out.putInt(0);
        }
        for (JointDescription joint : joints) {
            joint.writeTo(out);
        }
        for (float[] point : jamPoints) {
            out.putFloat(point[0]);
            out.putFloat(point[1]);
        }
        for (int[] part : parts) {
            out.putInt(part[0]);
            out.putShort((short) part[1]);
            out.putShort((short) part[2]);
        }
        return new Encoded(out.array(), references);
    }
}
