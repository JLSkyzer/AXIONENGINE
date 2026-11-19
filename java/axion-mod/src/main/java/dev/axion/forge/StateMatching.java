package dev.axion.forge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.axion.definition.BodyRests;
import dev.axion.physics.SimCommandStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Le rapprochement d'un état sauvegardé et d'une definition compilée (ADR-175 D1, D2) : chaque corps et chaque joint se
 * retrouve par son nom, jamais par un index qui aurait changé. Ce qui correspond est restauré ; un corps nouveau prend sa
 * pose de repos, relative à la racine restaurée ; ce qui a disparu est dit, et perdu. L'état n'est plus jamais appliqué
 * au mauvais élément, ni abandonné en bloc tant que la racine existe. Logique pure, sans Forge.
 */
final class StateMatching {

    private StateMatching() {}

    /**
     * {@return le nom d'un node racine de corps : celui de l'asset, ou {@code #<index>} s'il ne le donne pas}
     *
     * @param nodeNames le nom de chaque node de l'asset, par index
     * @param node l'index du node
     */
    static String nameOf(List<String> nodeNames, int node) {
        return node >= 0 && node < nodeNames.size() ? nodeNames.get(node) : "#" + node;
    }

    /**
     * Ce que le rapprochement donne.
     *
     * @param restored l'état à rendre au natif, {@code null} si un corps nouveau ne sait pas où se poser
     * @param lost ce que la sauvegarde gardait et que la definition n'a plus, chacun nommé
     * @param fresh ce que la definition a et que la sauvegarde n'avait pas, chacun nommé
     * @param legacy vrai si l'état n'avait pas de noms (schéma 5) : rapproché par les index d'alors
     * @param refusal pourquoi {@code restored} est absent, {@code null} sinon
     */
    record Outcome(SimCommandStream.Restored restored, List<String> lost, List<String> fresh, boolean legacy,
            String refusal) {}

    /**
     * Rapproche un état sauvegardé de la definition compilée.
     *
     * @param saved l'état lu du NBT
     * @param bodyNames le nom du node racine de chaque corps autre que le corps racine, dans l'ordre de la definition
     * @param jointNames le nom de chaque joint, dans l'ordre de la definition, ou {@code null} s'il ne se sait pas
     * @param jointCount le nombre de joints que la compilation a créés
     * @param nodeNames le nom de chaque node de l'asset, par index : il nomme les corps d'un état de schéma 5
     * @param rests la pose de repos de chaque corps, le corps racine d'abord
     * @return ce qu'il faut rendre, et ce qui est perdu ou nouveau
     */
    static Outcome match(PersistedState saved, List<String> bodyNames, List<String> jointNames, int jointCount,
            List<String> nodeNames, BodyRests rests) {
        List<String> lost = new ArrayList<>();
        List<String> fresh = new ArrayList<>();
        boolean legacy = false;

        // Les corps : par le nom de leur node racine.
        Map<String, PersistedState.Body> savedBodies = new LinkedHashMap<>();
        for (PersistedState.Body body : saved.bodies()) {
            String name = body.name();
            if (name == null) {
                legacy = true;
                name = nameOf(nodeNames, body.node());
            }
            if (savedBodies.putIfAbsent(name, body) != null) {
                lost.add("corps « " + name + " » en double");
            }
        }
        List<SimCommandStream.RestoredBody> bodies = new ArrayList<>(bodyNames.size() + 1);
        double[] root = saved.position();
        bodies.add(new SimCommandStream.RestoredBody(root, saved.rotation(), saved.linear(), saved.angular()));
        for (int rank = 0; rank < bodyNames.size(); rank++) {
            String name = bodyNames.get(rank);
            PersistedState.Body match = savedBodies.remove(name);
            if (match != null) {
                bodies.add(new SimCommandStream.RestoredBody(new double[] {match.x(), match.y(), match.z()},
                        match.rotation(), match.linear(), match.angular()));
                continue;
            }
            // Un corps nouveau : sa pose de repos, relative à la racine restaurée, à la vitesse de celle-ci.
            BodyRests.Rest rest = rank + 1 < rests.rests().size() ? rests.rests().get(rank + 1) : null;
            if (rest == null) {
                return new Outcome(null, lost, fresh, legacy, "le corps « " + name + " » est nouveau et sa pose de"
                        + " repos ne se calcule pas (échelle non uniforme sur sa chaîne de nodes)");
            }
            bodies.add(resting(saved, rest));
            fresh.add("corps « " + name + " »");
        }
        savedBodies.keySet().forEach(name -> lost.add("corps « " + name + " »"));

        // Les joints : par nom ; sans nom (schéma 5), par rang, seulement si leur nombre n'a pas changé.
        List<SimCommandStream.RestoredJoint> joints = new ArrayList<>(jointCount);
        boolean named = !saved.joints().isEmpty() && saved.joints().stream().allMatch(joint -> joint.name() != null);
        if (!named && !saved.joints().isEmpty()) {
            legacy = true;
        }
        Map<String, PersistedState.Joint> savedJoints = new LinkedHashMap<>();
        if (named) {
            for (PersistedState.Joint joint : saved.joints()) {
                if (savedJoints.putIfAbsent(joint.name(), joint) != null) {
                    lost.add("joint « " + joint.name() + " » en double");
                }
            }
        }
        boolean byRank = !named && saved.joints().size() == jointCount;
        for (int rank = 0; rank < jointCount; rank++) {
            String name = jointNames != null && rank < jointNames.size() ? jointNames.get(rank) : "n° " + rank;
            PersistedState.Joint match = named ? savedJoints.remove(name) : byRank ? saved.joints().get(rank) : null;
            if (match != null) {
                joints.add(new SimCommandStream.RestoredJoint(match.broken(), match.misalignment()));
            } else {
                joints.add(new SimCommandStream.RestoredJoint(false, 0f));
                if (!saved.joints().isEmpty()) {
                    fresh.add("joint « " + name + " »");
                }
            }
        }
        if (named) {
            savedJoints.keySet().forEach(name -> lost.add("joint « " + name + " »"));
        } else if (!byRank) {
            for (int rank = 0; rank < saved.joints().size(); rank++) {
                lost.add("joint n° " + rank + " d'un état sans noms, dont le nombre a changé");
            }
        }
        return new Outcome(new SimCommandStream.Restored(List.copyOf(bodies), List.copyOf(joints)), List.copyOf(lost),
                List.copyOf(fresh), legacy, null);
    }

    /** Un corps à sa pose de repos : relative à la racine restaurée, à la vitesse que cette racine lui donne. */
    private static SimCommandStream.RestoredBody resting(PersistedState saved, BodyRests.Rest rest) {
        float[] q = saved.rotation();
        double[] moved = BodyRests.rotate(q, rest.offset());
        double[] position = {saved.position()[0] + moved[0], saved.position()[1] + moved[1],
                saved.position()[2] + moved[2]};
        float[] a = saved.angular();
        // v = v_racine + ω × r : le corps suit la racine comme s'il y était attaché.
        float[] linear = {saved.linear()[0] + (float) (a[1] * moved[2] - a[2] * moved[1]),
                saved.linear()[1] + (float) (a[2] * moved[0] - a[0] * moved[2]),
                saved.linear()[2] + (float) (a[0] * moved[1] - a[1] * moved[0])};
        return new SimCommandStream.RestoredBody(position, BodyRests.multiply(q, rest.rotation()), linear,
                saved.angular());
    }

    /**
     * {@return le nom de chaque joint d'une definition, dans son ordre, ou {@code null} si leur nombre n'est pas celui que
     * la compilation a créé} Avec {@code modules.joints} éteint, la definition les déclare et le natif n'en crée aucun.
     *
     * @param root la definition, ou {@code null}
     * @param expected le nombre de joints que la compilation a créés
     */
    static List<String> jointNames(JsonObject root, int expected) {
        if (root == null || !(root.get("joints") instanceof JsonArray joints) || joints.size() != expected) {
            return null;
        }
        List<String> names = new ArrayList<>(expected);
        for (JsonElement joint : joints) {
            if (!(joint instanceof JsonObject object) || !(object.get("name") instanceof JsonPrimitive name)) {
                return null;
            }
            names.add(name.getAsString());
        }
        return names;
    }
}
