package dev.axion.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Le graphe d'appels du moteur, lu dans son bytecode par ASM (celui de la chaîne de Forge), et la
 * règle de thread qu'il vérifie : la base de T-011 (INV-03) et de T-019 (INV-12).
 *
 * <p>Une règle désigne des accès <b>sensibles</b> — qui n'ont le droit d'avoir lieu que sur un
 * thread — et l'appel qui <b>affirme</b> ce thread. Une méthode qui fait un accès sensible est
 * gardée si elle affirme le thread, si la plateforme l'appelle elle-même sur ce thread (méthode
 * <b>de confiance</b>, selon la règle), ou si tous ses appelants du moteur sont gardés :
 *
 * <ul>
 *   <li>une méthode sans appelant dans le moteur — gestionnaire d'événement, initialiseur
 *       statique — n'est gardée que par sa propre affirmation, ou sa confiance ;
 *   <li>un corps de lambda ou une référence de méthode s'exécute là où l'interface fonctionnelle
 *       est appelée, sur un thread que le bytecode ne dit pas : il compte comme sans appelant ;
 *   <li>l'implémentation d'une méthode d'interface ou d'une méthode redéfinie hérite des
 *       appelants de la méthode abstraite.
 * </ul>
 */
final class CallGraph {

    /** Préfixe des classes du moteur. */
    static final String ENGINE = "dev/axion/";

    /** Une méthode, par propriétaire, nom et descripteur. */
    record MethodRef(String owner, String name, String desc) {
        @Override
        public String toString() {
            return owner.replace('/', '.') + "." + name + desc;
        }
    }

    /** Ce qu'une règle de thread désigne. */
    interface Rule {
        /** {@return vrai si cet appel affirme le thread exigé} */
        boolean guards(MethodInsnNode call);

        /**
         * {@return l'accès sensible que fait {@code insn}, nommé pour le message, ou {@code null}}
         *
         * @param owner classe qui contient l'instruction
         * @param method méthode qui la contient
         * @param graph le graphe, pour la hiérarchie des classes
         */
        String sensitive(ClassNode owner, MethodNode method, AbstractInsnNode insn, CallGraph graph);

        /** {@return vrai si la plateforme appelle elle-même cette méthode sur le bon thread} */
        default boolean trusted(ClassNode owner, MethodNode method, CallGraph graph) {
            return false;
        }
    }

    private final Rule rule;
    final Map<MethodRef, Set<MethodRef>> callers = new HashMap<>();
    final Set<MethodRef> asserting = new HashSet<>();
    final Map<MethodRef, Set<String>> sensitive = new HashMap<>();
    final Set<MethodRef> escaped = new HashSet<>();
    final Set<MethodRef> trusted = new HashSet<>();
    final Map<String, ClassNode> classes = new HashMap<>();
    private final Map<String, Optional<ClassNode>> external = new HashMap<>();

    CallGraph(Rule rule) {
        this.rule = rule;
    }

    /** {@return le graphe des classes compilées du moteur ({@code axion.classes.dir})} */
    static CallGraph ofEngine(Rule rule) throws IOException {
        String dir = System.getProperty("axion.classes.dir");
        assertTrue(dir != null && !dir.isBlank(), "axion.classes.dir non fourni par le build");
        Path root = Path.of(dir);
        assertTrue(Files.isDirectory(root), () -> "classes compilées introuvables : " + root);
        CallGraph graph = new CallGraph(rule);
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
                try (InputStream in = Files.newInputStream(file)) {
                    ClassNode node = new ClassNode();
                    new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES);
                    graph.classes.put(node.name, node);
                }
            }
        }
        // Deux passes : une règle peut demander la hiérarchie de toutes les classes.
        for (ClassNode node : graph.classes.values()) {
            graph.analyze(node);
        }
        return graph;
    }

    // ------------------------------------------------------------------------------------------
    // Hiérarchie
    // ------------------------------------------------------------------------------------------

    /** {@return la classe nommée, du moteur ou de son classpath, ou vide} */
    Optional<ClassNode> classNode(String name) {
        ClassNode engine = classes.get(name);
        if (engine != null) {
            return Optional.of(engine);
        }
        return external.computeIfAbsent(name, CallGraph::load);
    }

    private static Optional<ClassNode> load(String name) {
        try (InputStream in = CallGraph.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (in == null) {
                return Optional.empty();
            }
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_CODE);
            return Optional.of(node);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** {@return les supertypes de {@code name}, classes et interfaces, transitivement} */
    Set<String> supertypes(String name) {
        Set<String> out = new HashSet<>();
        List<String> todo = new ArrayList<>(List.of(name));
        while (!todo.isEmpty()) {
            Optional<ClassNode> node = classNode(todo.remove(todo.size() - 1));
            if (node.isEmpty()) {
                continue;
            }
            List<String> parents = new ArrayList<>(node.get().interfaces);
            if (node.get().superName != null) {
                parents.add(node.get().superName);
            }
            for (String parent : parents) {
                if (out.add(parent)) {
                    todo.add(parent);
                }
            }
        }
        return out;
    }

    /**
     * {@return la classe qui déclare le membre que vise une instruction} Un appel ou un accès
     * nomme le type statique de son receveur ; le membre peut venir d'une superclasse.
     */
    String declaringClass(String owner, String name, String desc, boolean field) {
        String current = owner;
        while (current != null) {
            Optional<ClassNode> node = classNode(current);
            if (node.isEmpty()) {
                return current;
            }
            boolean declares = field
                    ? node.get().fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals(desc))
                    : node.get().methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
            if (declares) {
                return current;
            }
            current = node.get().superName;
        }
        return owner;
    }

    /** {@return vrai si {@code method} redéfinit une méthode d'un supertype hors du moteur} */
    boolean overridesPlatform(ClassNode owner, MethodNode method) {
        if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0 || method.name.startsWith("<")) {
            return false;
        }
        for (String parent : supertypes(owner.name)) {
            if (parent.startsWith(ENGINE) || parent.equals("java/lang/Object")) {
                continue;
            }
            Optional<ClassNode> node = classNode(parent);
            if (node.isPresent() && node.get().methods.stream().anyMatch(m ->
                    m.name.equals(method.name) && m.desc.equals(method.desc)
                            && (m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // Lecture
    // ------------------------------------------------------------------------------------------

    private void analyze(ClassNode node) {
        for (MethodNode method : node.methods) {
            MethodRef self = new MethodRef(node.name, method.name, method.desc);
            if (rule.trusted(node, method, this)) {
                trusted.add(self);
            }
            for (AbstractInsnNode insn : method.instructions) {
                String access = rule.sensitive(node, method, insn, this);
                if (access != null) {
                    sensitive.computeIfAbsent(self, k -> new TreeSet<>()).add(access);
                }
                if (insn instanceof MethodInsnNode call) {
                    if (rule.guards(call)) {
                        asserting.add(self);
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
                            escaped.add(new MethodRef(handle.getOwner(), handle.getName(), handle.getDesc()));
                        }
                    }
                }
            }
        }
    }

    /**
     * Rattache chaque méthode aux appels qui la visent sans la nommer : appel par une sous-classe
     * d'une méthode héritée, appel d'une méthode abstraite ou d'interface qu'elle implémente.
     */
    private void resolveVirtualCalls() {
        Map<MethodRef, Set<MethodRef>> extra = new HashMap<>();
        for (ClassNode node : classes.values()) {
            Set<String> supers = new HashSet<>(supertypes(node.name));
            supers.removeIf(parent -> !parent.startsWith(ENGINE));
            for (MethodNode method : node.methods) {
                MethodRef self = new MethodRef(node.name, method.name, method.desc);
                for (String parent : supers) {
                    Set<MethodRef> viaParent = callers.get(new MethodRef(parent, method.name, method.desc));
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
                    extra.computeIfAbsent(new MethodRef(parent, target.name(), target.desc()), k -> new HashSet<>())
                            .addAll(entry.getValue());
                }
            }
        }
        extra.forEach((k, v) -> callers.computeIfAbsent(k, x -> new HashSet<>()).addAll(v));
    }

    private static boolean declares(ClassNode node, MethodRef ref) {
        return node.methods.stream().anyMatch(m -> m.name.equals(ref.name()) && m.desc.equals(ref.desc()));
    }

    // ------------------------------------------------------------------------------------------
    // Verdict
    // ------------------------------------------------------------------------------------------

    /** {@return les méthodes gardées : point fixe à partir de celles qui affirment, ou de confiance} */
    Set<MethodRef> guarded() {
        Set<MethodRef> guarded = new HashSet<>(asserting);
        guarded.addAll(trusted);
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

    /** {@return les accès sensibles non gardés, triés, chacun avec ses appelants non gardés} */
    List<String> violations() {
        resolveVirtualCalls();
        Set<MethodRef> guarded = guarded();
        List<String> out = new ArrayList<>();
        for (Map.Entry<MethodRef, Set<String>> entry : sensitive.entrySet()) {
            if (!guarded.contains(entry.getKey())) {
                out.add(entry.getKey() + " -> " + entry.getValue() + why(entry.getKey(), guarded));
            }
        }
        out.sort(String::compareTo);
        return out;
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
}
