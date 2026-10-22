package dev.axion.physics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
 * <p>Les corps que créent les joints ({@code b_node}, ADR-151 D2) arrivent avec eux (tranche 3).
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
     */
    public record Layout(List<SimCommandStream.AssemblyBody> bodies, float assemblyMass, int rootNode) {}

    /**
     * {@return les corps que la definition déclare, ou {@code null} si un {@code root_node} ne nomme
     * aucun node de l'asset}
     *
     * @param definition racine JSON de la definition
     * @param nodes section {@code NODE} de l'asset, ou {@code null}
     * @param physics description du bloc {@code physics}, son type compris ({@link BodyDescription#of})
     */
    public static Layout of(JsonObject definition, byte[] nodes, BodyDescription physics) {
        JsonElement declared = definition.get("bodies");
        if (!(declared instanceof JsonArray bodies) || bodies.isEmpty()) {
            return new Layout(List.of(new SimCommandStream.AssemblyBody(physics, 0)), 0.0f, 0);
        }
        List<String> names = names(nodes);
        int[] roots = new int[bodies.size()];
        for (int i = 0; i < bodies.size(); i++) {
            JsonElement root = bodies.get(i).getAsJsonObject().get("root_node");
            if (root == null) {
                // Seul le corps racine peut se passer de node : il prend la racine de l'asset.
                if (i > 0) {
                    return null;
                }
                continue;
            }
            roots[i] = names.indexOf(root.getAsString());
            if (roots[i] < 0) {
                return null;
            }
        }
        if (roots.length == 1) {
            return new Layout(List.of(new SimCommandStream.AssemblyBody(physics, roots[0])), 0.0f, roots[0]);
        }
        List<SimCommandStream.AssemblyBody> layout = new ArrayList<>(roots.length);
        layout.add(new SimCommandStream.AssemblyBody(withoutMass(physics, physics.bodyType()), roots[0]));
        BodyDescription secondary = withoutMassProperties(physics);
        for (int i = 1; i < roots.length; i++) {
            layout.add(new SimCommandStream.AssemblyBody(secondary, roots[i]));
        }
        return new Layout(List.copyOf(layout), physics.mass(), roots[0]);
    }

    /** La description sans sa masse, que le natif répartit ; centre et inertie déclarés gardés. */
    private static BodyDescription withoutMass(BodyDescription d, int bodyType) {
        return new BodyDescription(bodyType, d.ccd(), d.canSleep(), 0.0f, d.centerOfMassX(), d.centerOfMassY(),
                d.centerOfMassZ(), d.inertiaX(), d.inertiaY(), d.inertiaZ(), d.linearDamping(), d.angularDamping(),
                d.gravityScale(), d.maxLinearVel(), d.maxAngularVel());
    }

    /**
     * Un corps autre que la racine : dynamique, amorti comme l'assembly ; masse, centre et inertie
     * viennent de ses densités — le centre déclaré l'est dans le repère de l'asset, celui du corps
     * racine.
     */
    private static BodyDescription withoutMassProperties(BodyDescription d) {
        return new BodyDescription(SimCommandStream.BODY_DYNAMIC, d.ccd(), d.canSleep(), 0.0f, Float.NaN, Float.NaN,
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
