package dev.axion.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * T-019 — INV-12 : aucun appel OpenGL hors du render thread.
 *
 * <p>Un appel GL hors du thread qui porte le contexte ne lève rien : il échoue en silence, ou
 * corrompt l'état d'un autre contexte. La règle du dépôt est donc qu'un code qui touche GL
 * <b>affirme</b> le render thread ({@code RenderSystem.assertOnRenderThread}), et ce test la
 * vérifie sur le bytecode du moteur, lu par ASM (celui de la chaîne de Forge) :
 *
 * <ul>
 *   <li>une méthode qui appelle GL est gardée si elle affirme le render thread, ou si tous ses
 *       appelants du moteur sont gardés ;
 *   <li>une méthode sans appelant dans le moteur — gestionnaire d'événement, initialiseur
 *       statique — n'est gardée que par sa propre affirmation ;
 *   <li>un corps de lambda ou une référence de méthode s'exécute là où l'interface fonctionnelle
 *       est appelée, sur un thread que le bytecode ne dit pas : il compte comme sans appelant ;
 *   <li>l'implémentation d'une méthode d'interface ou d'une méthode redéfinie hérite des
 *       appelants de la méthode abstraite.
 * </ul>
 *
 * <p>Côté natif, l'invariant tient par construction : aucune crate ne lie OpenGL, ce que le
 * second test vérifie sur {@code Cargo.lock}.
 */
class GlRenderThreadTest {

    /** Une méthode, par propriétaire, nom et descripteur. */
    record MethodRef(String owner, String name, String desc) {
        @Override
        public String toString() {
            return owner.replace('/', '.') + "." + name + desc;
        }
    }

    private static final String ENGINE = "dev/axion/";
    private static final String RENDER_SYSTEM = "com/mojang/blaze3d/systems/RenderSystem";

    /** Les affirmations du render thread. */
    private static final Set<String> ASSERTIONS = Set.of(
            "assertOnRenderThread", "assertOnRenderThreadOrInit");

    /** Méthodes de {@code RenderSystem} sans effet GL, sûres sur tout thread. */
    private static final Set<String> RENDER_SYSTEM_THREAD_SAFE = Set.of(
            "assertOnRenderThread", "assertOnRenderThreadOrInit", "assertOnGameThread",
            "assertOnGameThreadOrInit", "isOnRenderThread", "isOnRenderThreadOrInit",
            "isOnGameThread", "isInInitPhase", "recordRenderCall");

    /** Classes de Blaze3D qui appellent GL directement. */
    private static final Set<String> GL_CLASSES = Set.of(
            "com/mojang/blaze3d/platform/GlStateManager",
            "com/mojang/blaze3d/platform/TextureUtil",
            "com/mojang/blaze3d/vertex/VertexBuffer",
            "com/mojang/blaze3d/vertex/BufferUploader");

    static boolean isGl(String owner, String name) {
        if (owner.startsWith("org/lwjgl/opengl/") || GL_CLASSES.contains(owner)) {
            return true;
        }
        return owner.equals(RENDER_SYSTEM) && !RENDER_SYSTEM_THREAD_SAFE.contains(name);
    }

    /** Le graphe d'appels du moteur, réduit à ce que la règle demande. */
    static final class Graph {
        final Map<MethodRef, Set<MethodRef>> callers = new HashMap<>();
        final Set<MethodRef> asserting = new HashSet<>();
        final Map<MethodRef, Set<String>> glCalls = new HashMap<>();
        final Set<MethodRef> escaped = new HashSet<>();
        final Map<String, ClassNode> classes = new HashMap<>();

        void add(ClassNode node) {
            classes.put(node.name, node);
            for (MethodNode method : node.methods) {
                MethodRef self = new MethodRef(node.name, method.name, method.desc);
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call) {
                        if (call.owner.equals(RENDER_SYSTEM) && ASSERTIONS.contains(call.name)) {
                            asserting.add(self);
                        }
                        if (isGl(call.owner, call.name)) {
                            glCalls.computeIfAbsent(self, k -> new TreeSet<>())
                                    .add(call.owner.substring(call.owner.lastIndexOf('/') + 1)
                                            + "." + call.name);
                        }
                        if (call.owner.startsWith(ENGINE)) {
                            callers.computeIfAbsent(
                                            new MethodRef(call.owner, call.name, call.desc),
                                            k -> new HashSet<>())
                                    .add(self);
                        }
                    } else if (insn instanceof InvokeDynamicInsnNode indy) {
                        for (Object arg : indy.bsmArgs) {
                            if (arg instanceof Handle handle && handle.getOwner().startsWith(ENGINE)) {
                                escaped.add(new MethodRef(
                                        handle.getOwner(), handle.getName(), handle.getDesc()));
                            }
                        }
                    }
                }
            }
        }

        /** {@return les supertypes de {@code name} dans le moteur, transitivement} */
        private Set<String> supertypes(String name) {
            Set<String> out = new HashSet<>();
            List<String> todo = new ArrayList<>(List.of(name));
            while (!todo.isEmpty()) {
                ClassNode node = classes.get(todo.remove(todo.size() - 1));
                if (node == null) {
                    continue;
                }
                List<String> parents = new ArrayList<>(node.interfaces);
                if (node.superName != null) {
                    parents.add(node.superName);
                }
                for (String parent : parents) {
                    if (parent.startsWith(ENGINE) && out.add(parent)) {
                        todo.add(parent);
                    }
                }
            }
            return out;
        }

        /**
         * Rattache chaque méthode aux appels qui la visent sans la nommer : appel par une sous-classe
         * d'une méthode héritée, appel d'une méthode abstraite ou d'interface qu'elle implémente.
         */
        void resolveVirtualCalls() {
            Map<MethodRef, Set<MethodRef>> extra = new HashMap<>();
            for (ClassNode node : classes.values()) {
                Set<String> supers = supertypes(node.name);
                for (MethodNode method : node.methods) {
                    MethodRef self = new MethodRef(node.name, method.name, method.desc);
                    for (String parent : supers) {
                        Set<MethodRef> viaParent =
                                callers.get(new MethodRef(parent, method.name, method.desc));
                        if (viaParent != null) {
                            extra.computeIfAbsent(self, k -> new HashSet<>()).addAll(viaParent);
                        }
                    }
                }
                // Appel nommant une sous-classe d'une méthode qu'elle hérite : il atteint le parent.
                for (Map.Entry<MethodRef, Set<MethodRef>> entry : callers.entrySet()) {
                    MethodRef target = entry.getKey();
                    if (!target.owner().equals(node.name) || declares(node, target)) {
                        continue;
                    }
                    for (String parent : supers) {
                        extra.computeIfAbsent(
                                        new MethodRef(parent, target.name(), target.desc()),
                                        k -> new HashSet<>())
                                .addAll(entry.getValue());
                    }
                }
            }
            extra.forEach((k, v) -> callers.computeIfAbsent(k, x -> new HashSet<>()).addAll(v));
        }

        private static boolean declares(ClassNode node, MethodRef ref) {
            return node.methods.stream()
                    .anyMatch(m -> m.name.equals(ref.name()) && m.desc.equals(ref.desc()));
        }

        /** {@return les méthodes gardées : point fixe à partir de celles qui affirment} */
        Set<MethodRef> guarded() {
            Set<MethodRef> guarded = new HashSet<>(asserting);
            boolean changed = true;
            while (changed) {
                changed = false;
                for (Map.Entry<MethodRef, Set<MethodRef>> entry : callers.entrySet()) {
                    MethodRef method = entry.getKey();
                    if (guarded.contains(method) || escaped.contains(method)) {
                        continue;
                    }
                    Set<MethodRef> by = entry.getValue();
                    if (!by.isEmpty() && guarded.containsAll(by)) {
                        guarded.add(method);
                        changed = true;
                    }
                }
            }
            return guarded;
        }

        /** {@return pourquoi {@code method} n'est pas gardée : ses appelants qui ne le sont pas} */
        private String why(MethodRef method, Set<MethodRef> guarded) {
            String indent = System.lineSeparator() + "      ";
            if (escaped.contains(method)) {
                return indent + "lambda ou référence de méthode : thread inconnu";
            }
            Set<MethodRef> by = callers.getOrDefault(method, Set.of());
            if (by.isEmpty()) {
                return indent + "aucun appelant dans le moteur";
            }
            Set<String> unguarded = new TreeSet<>();
            for (MethodRef caller : by) {
                if (!guarded.contains(caller)) {
                    unguarded.add(caller.toString());
                }
            }
            StringBuilder out = new StringBuilder();
            for (String caller : unguarded) {
                out.append(indent).append("appelée par ").append(caller).append(", non gardée");
            }
            return out.toString();
        }

        /** {@return les appels GL non gardés, triés} */
        List<String> violations() {
            resolveVirtualCalls();
            Set<MethodRef> guarded = guarded();
            List<String> out = new ArrayList<>();
            for (Map.Entry<MethodRef, Set<String>> entry : glCalls.entrySet()) {
                if (!guarded.contains(entry.getKey())) {
                    out.add(entry.getKey() + " -> " + entry.getValue() + why(entry.getKey(), guarded));
                }
            }
            out.sort(String::compareTo);
            return out;
        }
    }

    private static Path property(String name) {
        String dir = System.getProperty(name);
        assertTrue(dir != null && !dir.isBlank(), name + " non fourni par le build");
        return Path.of(dir);
    }

    static Graph engineGraph() throws IOException {
        Path root = property("axion.classes.dir");
        assertTrue(Files.isDirectory(root), () -> "classes compilées introuvables : " + root);
        Graph graph = new Graph();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
                try (InputStream in = Files.newInputStream(file)) {
                    ClassNode node = new ClassNode();
                    new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES);
                    graph.add(node);
                }
            }
        }
        return graph;
    }

    @Test
    @DisplayName("T-019 : tout appel GL du moteur est gardé par une affirmation du render thread")
    void aucunAppelGlHorsDuRenderThread() throws IOException {
        Graph graph = engineGraph();
        assertTrue(graph.classes.size() > 100, () -> "trop peu de classes lues : " + graph.classes.size());
        assertTrue(graph.glCalls.size() > 10, () -> "trop peu d'appels GL vus : " + graph.glCalls.size());
        List<String> violations = graph.violations();
        assertTrue(
                violations.isEmpty(),
                () -> "INV-12 violé — ces méthodes appellent GL sans que le render thread soit affirmé"
                        + " sur tous leurs chemins :\n  " + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("T-019 : aucune crate native ne lie OpenGL")
    void aucuneCrateNeLieOpenGl() throws IOException {
        Path lock = property("axion.repo.dir").resolve("Cargo.lock");
        Set<String> forbidden = Set.of(
                "gl", "glow", "glium", "glutin", "gl_generator", "khronos_api", "wgpu", "wgpu-hal",
                "opengl32-sys", "epoxy", "ash");
        List<String> found = new ArrayList<>();
        for (String line : Files.readAllLines(lock, StandardCharsets.UTF_8)) {
            if (line.startsWith("name = \"")) {
                String name = line.substring(8, line.length() - 1);
                if (forbidden.contains(name)) {
                    found.add(name);
                }
            }
        }
        assertTrue(found.isEmpty(), () -> "liaisons graphiques dans le natif : " + found);
    }

    @Test
    @DisplayName("T-019 : la règle voit un appel non gardé, un appelant non gardé, une lambda")
    void laRegleEstFiable() {
        Graph graph = new Graph();
        MethodRef garde = new MethodRef("dev/axion/A", "garde", "()V");
        MethodRef viaGarde = new MethodRef("dev/axion/A", "viaGarde", "()V");
        MethodRef nu = new MethodRef("dev/axion/A", "nu", "()V");
        MethodRef lambda = new MethodRef("dev/axion/A", "lambda$x$0", "()V");
        graph.asserting.add(garde);
        graph.callers.put(viaGarde, new HashSet<>(Set.of(garde)));
        graph.callers.put(lambda, new HashSet<>(Set.of(garde)));
        graph.escaped.add(lambda);
        for (MethodRef m : List.of(garde, viaGarde, nu, lambda)) {
            graph.glCalls.put(m, Set.of("GL11.glEnable"));
        }
        List<String> violations = graph.violations();
        assertEquals(2, violations.size(), violations::toString);
        assertTrue(violations.get(0).startsWith(lambda + " -> [GL11.glEnable]"), violations::toString);
        assertTrue(violations.get(0).contains("thread inconnu"), violations::toString);
        assertTrue(violations.get(1).startsWith(nu + " -> [GL11.glEnable]"), violations::toString);
        assertTrue(violations.get(1).contains("aucun appelant"), violations::toString);
        assertTrue(isGl("org/lwjgl/opengl/GL20", "glUseProgram"));
        assertTrue(isGl(RENDER_SYSTEM, "enableDepthTest"));
        assertTrue(!isGl(RENDER_SYSTEM, "recordRenderCall"));
    }
}
