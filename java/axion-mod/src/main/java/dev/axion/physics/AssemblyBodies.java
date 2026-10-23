package dev.axion.physics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Les corps qu'une definition déclare (ADR-151 D2), pour {@code CREATE_ASSEMBLY}.
 *
 * <p>Chaque entrée de {@code bodies[]} donne un corps, à son {@code root_node}, résolu par son
 * nom dans la section {@code NODE} de l'asset ; le premier est le corps racine, que l'entité suit.
 * Sans {@code bodies}, un seul corps à la racine de l'asset. Le bloc {@code physics} décrit
 * l'assembly : son type et ses réglages vont au corps racine ; les autres corps sont dynamiques,
 * amortis comme elle, et sa masse déclarée se répartit entre tous les corps (le natif la
 * répartit au prorata des densités).
 *
 * <p>Le {@code b_node} d'un joint crée un corps à ce node s'il n'est pas déjà la racine d'un corps
 * déclaré (ADR-151 D2) ; ces corps implicites suivent les corps déclarés, dans l'ordre des joints.
 * Un joint nomme son corps {@code a} parmi ceux de {@code bodies[]}, que le chargement a vérifiés :
 * une definition à joints déclare donc ses corps.
 */
public final class AssemblyBodies {

    /** Taille d'un node dans la section {@code NODE}, en octets (A3D). */
    private static final int NODE_BYTES = 80;

    /** Taille de l'en-tête de la section {@code NODE} : nombre de nodes, taille des noms. */
    private static final int HEADER_BYTES = 8;

    private AssemblyBodies() {}

    /**
     * Les corps d'une assembly, prêts pour {@code CREATE_ASSEMBLY}.
     *
     * @param bodies les corps, le corps racine d'abord
     * @param assemblyMass masse de l'assembly à répartir, en kg ; {@code 0} pour un seul corps,
     *     dont la description porte déjà la masse
     * @param rootNode node racine du corps racine : celui que porte son {@link BodyState}
     * @param joints les joints, dans l'ordre de la definition
     * @param jamPoints les points de leurs courbes de grippage, {@code [x, y]}
     * @param selfCollision {@code physics.self_collision} : les corps de l'assembly se touchent
     *     entre eux, sauf ceux qu'un joint relie (§10.4)
     */
    public record Layout(
            List<SimCommandStream.AssemblyBody> bodies,
            float assemblyMass,
            int rootNode,
            List<JointDescription> joints,
            List<float[]> jamPoints,
            boolean selfCollision) {}

    /**
     * {@return les corps et les joints que la definition déclare, ou {@code null} si un
     * {@code root_node} ou un {@code b_node} ne nomme aucun node de l'asset, si les corps dépassent
     * {@link SimCommandStream#MAX_ASSEMBLY_BODIES}, ou si un joint ne se transcrit pas}
     *
     * @param definition racine JSON de la definition
     * @param nodes section {@code NODE} de l'asset, ou {@code null}
     * @param physics description du bloc {@code physics}, son type compris ({@link BodyDescription#of})
     * @param withJoints faux si le module joints est désactivé ({@code modules.joints}) : ni joint ni
     *     corps implicite, les corps déclarés restent séparés
     */
    public static Layout of(JsonObject definition, byte[] nodes, BodyDescription physics, boolean withJoints) {
        boolean selfCollision = definition.get("physics") instanceof JsonObject block
                && block.get("self_collision") != null
                && block.get("self_collision").getAsBoolean();
        JsonElement declared = definition.get("bodies");
        if (!(declared instanceof JsonArray bodies) || bodies.isEmpty()) {
            return single(physics, 0, selfCollision);
        }
        List<String> names = names(nodes);
        List<Integer> roots = new ArrayList<>(bodies.size());
        Map<String, Integer> ranks = new HashMap<>();
        // `bodies[].mass` fixe la masse d'un corps ; celle de l'assembly se répartit entre les autres.
        float[] masses = new float[bodies.size()];
        for (int i = 0; i < bodies.size(); i++) {
            JsonObject body = bodies.get(i).getAsJsonObject();
            if (body.get("mass") != null) {
                masses[i] = body.get("mass").getAsFloat();
            }
            JsonElement root = body.get("root_node");
            int node = 0;
            if (root != null) {
                node = names.indexOf(root.getAsString());
                if (node < 0) {
                    return null;
                }
            } else if (i > 0) {
                // Seul le corps racine peut se passer de node : il prend la racine de l'asset.
                return null;
            }
            roots.add(node);
            if (body.get("name") != null) {
                ranks.put(body.get("name").getAsString(), i);
            }
        }
        JsonArray joints = withJoints && definition.get("joints") instanceof JsonArray declaredJoints
                ? declaredJoints
                : new JsonArray();
        for (JsonElement joint : joints) {
            JsonElement bNode = joint.getAsJsonObject().get("b_node");
            int node = bNode == null ? -1 : names.indexOf(bNode.getAsString());
            if (node < 0) {
                return null;
            }
            if (!roots.contains(node)) {
                roots.add(node);
            }
        }
        if (roots.size() > SimCommandStream.MAX_ASSEMBLY_BODIES) {
            return null;
        }
        if (roots.size() == 1) {
            return single(physics, roots.get(0), selfCollision);
        }
        Map<Integer, Integer> ranksByRoot = new HashMap<>();
        for (int rank = 0; rank < roots.size(); rank++) {
            ranksByRoot.put(roots.get(rank), rank);
        }
        AssemblyJoints.Transcription transcription = withJoints
                ? AssemblyJoints.of(definition, names, ranks, ranksByRoot)
                : new AssemblyJoints.Transcription(List.of(), List.of());
        if (transcription == null) {
            return null;
        }
        List<SimCommandStream.AssemblyBody> layout = new ArrayList<>(roots.size());
        layout.add(new SimCommandStream.AssemblyBody(root(physics, masses[0]), roots.get(0)));
        for (int rank = 1; rank < roots.size(); rank++) {
            // Les corps implicites, après les déclarés, n'ont pas de masse propre.
            float mass = rank < masses.length ? masses[rank] : 0.0f;
            layout.add(new SimCommandStream.AssemblyBody(secondary(physics, mass), roots.get(rank)));
        }
        return new Layout(List.copyOf(layout), physics.mass(), roots.get(0), transcription.joints(),
                transcription.jamPoints(), selfCollision);
    }

    /** Un seul corps, au node {@code root}, qui porte la description entière de l'assembly. */
    private static Layout single(BodyDescription physics, int root, boolean selfCollision) {
        return new Layout(List.of(new SimCommandStream.AssemblyBody(physics, root)), 0.0f, root, List.of(), List.of(),
                selfCollision);
    }

    /**
     * Le corps racine : la description de l'assembly, à la masse que {@code bodies[0].mass} lui
     * fixe, ou sans masse pour que le natif lui en répartisse une ; centre et inertie déclarés gardés.
     */
    private static BodyDescription root(BodyDescription d, float mass) {
        return new BodyDescription(d.bodyType(), d.ccd(), d.canSleep(), mass, d.centerOfMassX(), d.centerOfMassY(),
                d.centerOfMassZ(), d.inertiaX(), d.inertiaY(), d.inertiaZ(), d.linearDamping(), d.angularDamping(),
                d.gravityScale(), d.maxLinearVel(), d.maxAngularVel());
    }

    /**
     * Un corps autre que la racine : dynamique, amorti comme l'assembly, à la masse que sa
     * déclaration lui fixe ou sans masse ; centre et inertie viennent de ses densités — le centre
     * déclaré l'est dans le repère de l'asset, celui du corps racine.
     */
    private static BodyDescription secondary(BodyDescription d, float mass) {
        return new BodyDescription(SimCommandStream.BODY_DYNAMIC, d.ccd(), d.canSleep(), mass, Float.NaN, Float.NaN,
                Float.NaN, 0.0f, 0.0f, 0.0f, d.linearDamping(), d.angularDamping(), d.gravityScale(),
                d.maxLinearVel(), d.maxAngularVel());
    }

    /**
     * Les noms des nodes de la section {@code NODE}, dans leur ordre ; vide si elle manque ou ne se
     * lit pas. Disposition : nombre de nodes et taille des noms ({@code u32}), les nodes, puis pour
     * chacun le décalage et la longueur de son nom ({@code u32}), puis les noms en UTF-8.
     */
    static List<String> names(byte[] nodes) {
        if (nodes == null || nodes.length < HEADER_BYTES) {
            return List.of();
        }
        ByteBuffer n = ByteBuffer.wrap(nodes).order(ByteOrder.LITTLE_ENDIAN);
        long count = Integer.toUnsignedLong(n.getInt(0));
        long refsAt = HEADER_BYTES + count * NODE_BYTES;
        long namesAt = refsAt + count * 8;
        if (namesAt > nodes.length) {
            return List.of();
        }
        List<String> names = new ArrayList<>((int) count);
        for (int node = 0; node < count; node++) {
            long offset = Integer.toUnsignedLong(n.getInt((int) refsAt + node * 8));
            long length = Integer.toUnsignedLong(n.getInt((int) refsAt + node * 8 + 4));
            if (namesAt + offset + length > nodes.length) {
                return List.of();
            }
            names.add(new String(nodes, (int) (namesAt + offset), (int) length, StandardCharsets.UTF_8));
        }
        return names;
    }
}
