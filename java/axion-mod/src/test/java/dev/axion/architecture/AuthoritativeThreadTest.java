package dev.axion.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * T-011 — INV-03 : aucune API Minecraft mutante hors du thread autoritatif (P-04).
 *
 * <p>Une mutation de l'état de Minecraft hors du thread du serveur ne lève rien d'elle-même :
 * elle croise le tick et corrompt le monde au hasard. Le contrat de
 * {@code PlatformAdapter.isAuthoritativeThread} veut qu'une méthode qui modifie l'état le
 * <b>vérifie</b> plutôt que l'espérer ; {@code AuthoritativeThread.require} est cette
 * vérification, et ce test s'assure, sur le graphe d'appels du moteur ({@link CallGraph}),
 * qu'aucun chemin vers une mutation ne l'évite.
 *
 * <p><b>Mutation</b> : appel d'une méthode d'état du monde, des entités ou de leurs données
 * synchronisées dont le nom dit qu'elle modifie ({@link #MUTATORS}), y compris héritée par une
 * classe du moteur ; ou écriture d'un champ de Minecraft.
 *
 * <p><b>De confiance</b> : les méthodes que Minecraft ou Forge appellent elles-mêmes sur le
 * thread qui possède l'objet — redéfinitions de leurs méthodes ({@code tick}, {@code lerpTo},
 * lecture des données de sauvegarde…) et constructeurs d'une sous-classe de leurs types. Un
 * {@code super.tick()} dans une redéfinition n'est pas une mutation d'AXION, c'est le cycle de
 * vie de Minecraft lui-même. Un gestionnaire d'événement, lui, n'est pas de confiance : Forge
 * en publie sur plusieurs threads.
 */
class AuthoritativeThreadTest {

    private static final String GUARD_OWNER = "dev/axion/forge/AuthoritativeThread";

    /** Préfixes de nom des méthodes qui modifient l'état. */
    static final List<String> MUTATORS = List.of(
            "set", "add", "tryAdd", "remove", "discard", "kill", "hurt", "heal", "push", "move",
            "teleport", "destroy", "explode", "spawn", "startRiding", "stopRiding", "eject",
            "lerp", "absMove", "playSound", "sendParticles", "broadcast", "place", "tick",
            "onAdded", "refresh", "define", "die", "drop", "clear", "fill");

    /** {@return vrai si la classe porte de l'état du jeu} Les valeurs ({@code world/phys}) non. */
    static boolean isGameState(String owner) {
        if (owner.startsWith("net/minecraft/world/phys/")) {
            return false;
        }
        return owner.startsWith("net/minecraft/world/")
                || owner.startsWith("net/minecraft/server/level/")
                || owner.equals("net/minecraft/network/syncher/SynchedEntityData");
    }

    static boolean isMutator(String name) {
        return MUTATORS.stream().anyMatch(name::startsWith);
    }

    /** La règle d'INV-03. */
    static final CallGraph.Rule RULE = new CallGraph.Rule() {
        @Override
        public boolean guards(MethodInsnNode call) {
            return call.owner.equals(GUARD_OWNER) && call.name.equals("require");
        }

        @Override
        public String sensitive(ClassNode owner, MethodNode method, AbstractInsnNode insn, CallGraph graph) {
            if (insn instanceof MethodInsnNode call) {
                // L'état du jeu est porté par des instances : un appel statique (défini de clé de
                // donnée synchronisée, fabrique) n'en modifie aucun.
                if (call.getOpcode() == Opcodes.INVOKESTATIC || !isMutator(call.name)) {
                    return null;
                }
                String declaring = graph.declaringClass(call.owner, call.name, call.desc, false);
                if (!isGameState(declaring)) {
                    return null;
                }
                // super.tick() dans tick() : le cycle de vie de Minecraft, pas une mutation d'AXION.
                if (call.getOpcode() == Opcodes.INVOKESPECIAL
                        && call.name.equals(method.name) && call.desc.equals(method.desc)) {
                    return null;
                }
                return simple(declaring) + "." + call.name;
            }
            if (insn instanceof FieldInsnNode field
                    && (field.getOpcode() == Opcodes.PUTFIELD || field.getOpcode() == Opcodes.PUTSTATIC)) {
                String declaring = graph.declaringClass(field.owner, field.name, field.desc, true);
                if (isGameState(declaring)) {
                    return simple(declaring) + "." + field.name + " =";
                }
            }
            return null;
        }

        @Override
        public boolean trusted(ClassNode owner, MethodNode method, CallGraph graph) {
            if (method.name.equals("<init>")) {
                return graph.supertypes(owner.name).stream().anyMatch(AuthoritativeThreadTest::isGameState);
            }
            return graph.overridesPlatform(owner, method);
        }
    };

    private static String simple(String owner) {
        return owner.substring(owner.lastIndexOf('/') + 1);
    }

    @Test
    @DisplayName("T-011 : toute mutation de Minecraft par le moteur affirme le thread autoritatif")
    void aucuneMutationHorsDuThreadAutoritatif() throws IOException {
        CallGraph graph = CallGraph.ofEngine(RULE);
        assertTrue(graph.classes.size() > 100, () -> "trop peu de classes lues : " + graph.classes.size());
        assertTrue(graph.sensitive.size() >= 5, () -> "trop peu de mutations vues : " + graph.sensitive);
        List<String> violations = graph.violations();
        assertTrue(
                violations.isEmpty(),
                () -> "INV-03 violé — ces méthodes modifient l'état de Minecraft sans que le thread"
                        + " autoritatif soit affirmé sur tous leurs chemins :\n  "
                        + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("T-011 : la règle reconnaît mutations, valeurs et cycle de vie")
    void laRegleEstFiable() {
        assertTrue(isGameState("net/minecraft/world/entity/Entity"));
        assertTrue(isGameState("net/minecraft/server/level/ServerLevel"));
        assertTrue(!isGameState("net/minecraft/world/phys/AABB"), "AABB.move rend une valeur neuve");
        assertTrue(isMutator("setPos") && isMutator("discard") && isMutator("hurt"));
        assertTrue(!isMutator("getX") && !isMutator("isAlive"));

        CallGraph graph = new CallGraph(RULE);
        ClassNode owner = new ClassNode();
        owner.name = "dev/axion/X";
        MethodNode tick = new MethodNode(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
        MethodInsnNode superTick = new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "net/minecraft/world/entity/Entity", "tick", "()V", false);
        assertNull(RULE.sensitive(owner, tick, superTick, graph), "super.tick() est le cycle de vie");
        MethodInsnNode discard = new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "net/minecraft/world/entity/Entity", "discard", "()V", false);
        assertEquals("Entity.discard", RULE.sensitive(owner, tick, discard, graph));
        FieldInsnNode hurtMarked = new FieldInsnNode(
                Opcodes.PUTFIELD, "net/minecraft/world/entity/Entity", "hurtMarked", "Z");
        assertEquals("Entity.hurtMarked =", RULE.sensitive(owner, tick, hurtMarked, graph));
        assertTrue(RULE.guards(new MethodInsnNode(Opcodes.INVOKESTATIC, GUARD_OWNER, "require", "()V", false)));
    }
}
