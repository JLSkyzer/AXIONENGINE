package dev.axion.physics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Le corps racine d'une assembly, résolu par le client contre ses propres assets (ADR-151 D9).
 *
 * <p>Le serveur ne résout plus rien : la compilation native de la definition range corps, joints et
 * colliders (ADR-156 D3). Le client, lui, dessine le modèle qu'il a compilé — qu'un resource pack peut
 * remplacer (ADR-150 D3) —, si bien que les rangs de nodes du serveur ne valent pas pour lui : il
 * retrouve le node racine par son nom, dans la section {@code NODE} de son asset.
 */
public final class AssemblyBodies {

    /** Taille d'un node dans la section {@code NODE}, en octets (A3D). */
    private static final int NODE_BYTES = 80;

    /** Taille de l'en-tête de la section {@code NODE} : nombre de nodes, taille des noms. */
    private static final int HEADER_BYTES = 8;

    private AssemblyBodies() {}

    /**
     * {@return le node racine du corps racine que la definition déclare, résolu comme la compilation native
     * le résout : {@code bodies[0].root_node} par son nom dans la section {@code NODE}, la racine de l'asset
     * s'il n'est pas déclaré ; {@code -1} s'il ne nomme aucun node}
     *
     * <p>Le client, qui dessine les corps sans refaire leur liste (ADR-151 D9), n'a besoin que de lui : les
     * autres corps lui viennent du snapshot.
     *
     * @param definition racine JSON de la definition
     * @param nodes section {@code NODE} de l'asset, ou {@code null}
     */
    public static int rootNodeOf(JsonObject definition, byte[] nodes) {
        if (!(definition.get("bodies") instanceof JsonArray bodies) || bodies.isEmpty()
                || !(bodies.get(0) instanceof JsonObject first) || first.get("root_node") == null) {
            return 0;
        }
        return names(nodes).indexOf(first.get("root_node").getAsString());
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
