package dev.axion.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * R-581 — une definition ne peut déclencher aucune exécution de code : ni script, ni expression
 * évaluée, ni référence de classe (ADR-009, ADR-141).
 *
 * <p>Une definition n'est que du JSON lu par {@code dev.axion.definition}. Pour qu'elle exécute du
 * code, il faudrait que son lecteur transforme une donnée en code : charger une classe par son
 * nom, invoquer par réflexion, évaluer un script, lancer un processus. Ce test lit le bytecode du
 * lecteur ({@link CallGraph}) et n'y admet aucune de ces portes. Une clé inconnue est déjà
 * refusée (T-282) ; {@code custom}, seul objet ouvert, n'est jamais qu'une donnée.
 */
class DefinitionCodeExecutionTest {

    private static final String DEFINITIONS = "dev/axion/definition/";

    /** Types dont tout usage transforme une donnée en code, ou peut le faire. */
    private static final List<String> FORBIDDEN_TYPES = List.of(
            "java/lang/reflect/",
            "java/lang/ClassLoader",
            "java/net/URLClassLoader",
            "java/lang/invoke/MethodHandles$Lookup",
            "java/util/ServiceLoader",
            "java/lang/ProcessBuilder",
            "javax/script/",
            "jdk/nashorn/",
            "org/mozilla/javascript/",
            "groovy/",
            "org/codehaus/groovy/",
            "org/graalvm/");

    /** Méthodes de {@code Class} qui atteignent une classe ou un membre par son nom. */
    private static final Set<String> FORBIDDEN_CLASS_METHODS = Set.of(
            "forName", "newInstance", "getMethod", "getMethods", "getDeclaredMethod",
            "getDeclaredMethods", "getConstructor", "getConstructors", "getDeclaredConstructor",
            "getDeclaredConstructors", "getField", "getFields", "getDeclaredField",
            "getDeclaredFields");

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

    static String forbidden(String owner, String name) {
        for (String type : FORBIDDEN_TYPES) {
            if (owner.startsWith(type)) {
                return owner + "." + name;
            }
        }
        if (owner.equals("java/lang/Class") && FORBIDDEN_CLASS_METHODS.contains(name)) {
            return "Class." + name;
        }
        if (owner.equals("java/lang/Runtime") && name.equals("exec")) {
            return "Runtime.exec";
        }
        return null;
    }

    private static String forbidden(AbstractInsnNode insn) {
        if (insn instanceof MethodInsnNode call) {
            return forbidden(call.owner, call.name);
        }
        if (insn instanceof FieldInsnNode field) {
            return forbidden(field.owner, field.name);
        }
        if (insn instanceof TypeInsnNode type) {
            return forbidden(type.desc, "<type>");
        }
        return null;
    }

    @Test
    @DisplayName("R-581 : le lecteur de definitions n'a aucune porte vers une exécution de code")
    void aucunePorteVersDuCode() throws IOException {
        CallGraph graph = CallGraph.ofEngine(NO_RULE);
        Set<String> scanned = new TreeSet<>();
        List<String> found = new ArrayList<>();
        for (ClassNode node : graph.classes.values()) {
            if (!node.name.startsWith(DEFINITIONS)) {
                continue;
            }
            scanned.add(node.name);
            for (MethodNode method : node.methods) {
                for (AbstractInsnNode insn : method.instructions) {
                    String hit = forbidden(insn);
                    if (hit != null) {
                        found.add(node.name + "." + method.name + " -> " + hit);
                    }
                }
            }
        }
        // Le lecteur compte une douzaine de classes : en lire moins dirait qu'il a été déplacé.
        assertTrue(scanned.size() >= 10, () -> "classes du lecteur lues : " + scanned);
        assertEquals(List.of(), found, "porte vers une exécution de code");
    }
}
