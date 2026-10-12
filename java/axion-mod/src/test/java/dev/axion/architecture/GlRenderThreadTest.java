package dev.axion.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.architecture.CallGraph.MethodRef;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * T-019 — INV-12 : aucun appel OpenGL hors du render thread.
 *
 * <p>Un appel GL hors du thread qui porte le contexte ne lève rien : il échoue en silence, ou
 * corrompt l'état d'un autre contexte. La règle du dépôt est donc qu'un code qui touche GL
 * <b>affirme</b> le render thread ({@code RenderSystem.assertOnRenderThread}) ; ce test la
 * vérifie sur le graphe d'appels du moteur ({@link CallGraph}). Aucune méthode n'y est de
 * confiance : Forge publie des événements de rendu sur le render thread, mais un gestionnaire
 * qui touche GL l'affirme quand même.
 *
 * <p>Côté natif, l'invariant tient par construction : aucune crate ne lie OpenGL, ce que le
 * second test vérifie sur {@code Cargo.lock}.
 */
class GlRenderThreadTest {

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

    /** La règle d'INV-12. */
    static final CallGraph.Rule RULE = new CallGraph.Rule() {
        @Override
        public boolean guards(MethodInsnNode call) {
            return call.owner.equals(RENDER_SYSTEM) && ASSERTIONS.contains(call.name);
        }

        @Override
        public String sensitive(ClassNode owner, MethodNode method, AbstractInsnNode insn, CallGraph graph) {
            if (insn instanceof MethodInsnNode call && isGl(call.owner, call.name)) {
                return call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name;
            }
            return null;
        }
    };

    @Test
    @DisplayName("T-019 : tout appel GL du moteur est gardé par une affirmation du render thread")
    void aucunAppelGlHorsDuRenderThread() throws IOException {
        CallGraph graph = CallGraph.ofEngine(RULE);
        assertTrue(graph.classes.size() > 100, () -> "trop peu de classes lues : " + graph.classes.size());
        assertTrue(graph.sensitive.size() > 10, () -> "trop peu d'appels GL vus : " + graph.sensitive.size());
        List<String> violations = graph.violations();
        assertTrue(
                violations.isEmpty(),
                () -> "INV-12 violé — ces méthodes appellent GL sans que le render thread soit affirmé"
                        + " sur tous leurs chemins :\n  " + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("T-019 : aucune crate native ne lie OpenGL")
    void aucuneCrateNeLieOpenGl() throws IOException {
        String repo = System.getProperty("axion.repo.dir");
        assertTrue(repo != null && !repo.isBlank(), "axion.repo.dir non fourni par le build");
        Path lock = Path.of(repo).resolve("Cargo.lock");
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
        CallGraph graph = new CallGraph(RULE);
        MethodRef garde = new MethodRef("dev/axion/A", "garde", "()V");
        MethodRef viaGarde = new MethodRef("dev/axion/A", "viaGarde", "()V");
        MethodRef nu = new MethodRef("dev/axion/A", "nu", "()V");
        MethodRef lambda = new MethodRef("dev/axion/A", "lambda$x$0", "()V");
        graph.asserting.add(garde);
        graph.callers.put(viaGarde, new HashSet<>(Set.of(garde)));
        graph.callers.put(lambda, new HashSet<>(Set.of(garde)));
        graph.escaped.add(lambda);
        for (MethodRef m : List.of(garde, viaGarde, nu, lambda)) {
            graph.sensitive.put(m, Set.of("GL11.glEnable"));
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
