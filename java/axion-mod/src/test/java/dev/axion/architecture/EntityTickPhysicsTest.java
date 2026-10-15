package dev.axion.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.architecture.CallGraph.MethodRef;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * R-701 — {@code AxionEntity.tick()} n'exécute aucune physique : la simulation est pilotée par
 * C-40 en un lot, jamais entité par entité (ADR-141).
 *
 * <p>Le test part de {@code tick()} et de toutes les méthodes d'{@code AxionEntity} qui
 * redéfinissent une méthode de Minecraft — {@code super.tick()} peut les rappeler — et suit le
 * graphe d'appels du moteur, lu dans son bytecode ({@link CallGraph}) : appels directs,
 * redéfinitions et implémentations dans le moteur, corps de lambda. Aucun chemin ne doit
 * atteindre {@code dev.axion.physics}, ni les points d'entrée {@code sim*} de
 * {@code NativeBridge}.
 *
 * <p>Le même parcours, parti du tick du runtime — le lot de C-40 —, doit les atteindre : sans
 * cela, le premier verdict ne prouverait rien.
 */
class EntityTickPhysicsTest {

    private static final String ENTITY = "dev/axion/forge/AxionEntity";
    private static final MethodRef RUNTIME_TICK =
            new MethodRef("dev/axion/lifecycle/AxionRuntime", "onTick", "()V");

    /** Aucune règle de thread : seuls les appels et la hiérarchie servent ici. */
    private static final CallGraph.Rule NO_RULE = new CallGraph.Rule() {
        @Override
        public boolean guards(MethodInsnNode call) {
            return false;
        }

        @Override
        public String sensitive(ClassNode owner, MethodNode method, AbstractInsnNode insn, CallGraph graph) {
            return null;
        }
    };

    static boolean isPhysics(String owner, String name) {
        return owner.startsWith("dev/axion/physics/")
                || (owner.equals("dev/axion/bridge/NativeBridge") && name.startsWith("sim"));
    }

    @Test
    @DisplayName("R-701 : rien de ce que tick() atteint ne touche la simulation, que le lot de C-40 pilote")
    void leTickDeLEntiteNAtteintAucunePhysique() throws IOException {
        CallGraph graph = CallGraph.ofEngine(NO_RULE);
        ClassNode entity = graph.classes.get(ENTITY);
        assertNotNull(entity, ENTITY + " absente des classes compilées");

        List<MethodRef> roots = new ArrayList<>();
        for (MethodNode method : entity.methods) {
            boolean tick = method.name.equals("tick") && method.desc.equals("()V");
            if (tick || graph.overridesPlatform(entity, method)) {
                roots.add(new MethodRef(ENTITY, method.name, method.desc));
            }
        }
        assertTrue(
                roots.contains(new MethodRef(ENTITY, "tick", "()V")),
                "AxionEntity ne redéfinit plus tick() : le test ne vérifie plus rien");

        assertEquals(List.of(), physicsReachedFrom(graph, roots), "physique atteinte depuis l'entité");

        // Le détecteur voit la simulation là où elle est : le tick du runtime, C-40.
        assertFalse(
                physicsReachedFrom(graph, List.of(RUNTIME_TICK)).isEmpty(),
                "le tick du runtime n'atteint plus la simulation : le détecteur ne prouve rien");
    }

    /** {@return les accès à la simulation atteints depuis {@code roots}, chacun avec son appelant} */
    private static List<String> physicsReachedFrom(CallGraph graph, List<MethodRef> roots) {
        Deque<MethodRef> todo = new ArrayDeque<>(roots);
        Set<MethodRef> seen = new HashSet<>();
        List<String> reached = new ArrayList<>();
        while (!todo.isEmpty()) {
            MethodRef ref = todo.poll();
            if (!seen.add(ref)) {
                continue;
            }
            MethodNode method = method(graph, ref);
            if (method == null) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) {
                    visit(graph, ref, call.owner, call.name, call.desc, todo, reached);
                } else if (insn instanceof FieldInsnNode field && isPhysics(field.owner, "")) {
                    reached.add(ref + " -> " + field.owner + "." + field.name);
                } else if (insn instanceof InvokeDynamicInsnNode indy) {
                    for (Object arg : indy.bsmArgs) {
                        if (arg instanceof Handle handle) {
                            visit(graph, ref, handle.getOwner(), handle.getName(), handle.getDesc(), todo, reached);
                        }
                    }
                }
            }
        }
        return reached;
    }

    private static void visit(
            CallGraph graph,
            MethodRef from,
            String owner,
            String name,
            String desc,
            Deque<MethodRef> todo,
            List<String> reached) {
        if (isPhysics(owner, name)) {
            reached.add(from + " -> " + owner + "." + name);
            return;
        }
        if (owner.startsWith(CallGraph.ENGINE)) {
            todo.addAll(targets(graph, owner, name, desc));
        }
    }

    /** {@return la méthode visée, et toutes ses redéfinitions ou implémentations dans le moteur} */
    private static List<MethodRef> targets(CallGraph graph, String owner, String name, String desc) {
        List<MethodRef> out = new ArrayList<>();
        out.add(new MethodRef(graph.declaringClass(owner, name, desc, false), name, desc));
        for (ClassNode node : graph.classes.values()) {
            if (!node.name.equals(owner)
                    && graph.supertypes(node.name).contains(owner)
                    && node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc))) {
                out.add(new MethodRef(node.name, name, desc));
            }
        }
        return out;
    }

    private static MethodNode method(CallGraph graph, MethodRef ref) {
        ClassNode node = graph.classes.get(ref.owner());
        if (node == null) {
            return null;
        }
        return node.methods.stream()
                .filter(m -> m.name.equals(ref.name()) && m.desc.equals(ref.desc()))
                .findFirst()
                .orElse(null);
    }
}
