package dev.axion.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Deux abonnés d'événements Forge ne se partagent jamais un relais.
 *
 * <p>EventBus nomme la classe qu'il engendre pour un abonné {@code <paquet>.__<nom court de la classe>_<méthode>_<nom
 * court de l'événement>} ({@code IEventListenerFactory.getUniqueName}, EventBus 6.2). Deux classes internes de même nom
 * dans le même paquet — deux {@code Client} — dont la méthode et l'événement ont le même nom se partagent donc un
 * relais : seul l'un des deux est appelé, deux fois, et l'autre jamais, sans un mot au journal. Constaté le 2026-11-26 :
 * le banc de la mesure du client ne recevait aucun tick.
 */
class EventSubscriberNamesTest {

    private static final String SUBSCRIBE = "Lnet/minecraftforge/eventbus/api/SubscribeEvent;";

    @Test
    @DisplayName("Aucun relais d'événement n'est partagé, dans le mod livré comme dans le contenu de développement")
    void aucunRelaisPartage() throws IOException {
        Map<String, List<String>> relais = new TreeMap<>();
        for (String propriete : List.of("axion.classes.dir", "axion.devcontent.classes.dir")) {
            Path classes = Path.of(System.getProperty(propriete));
            int avant = relais.size();
            abonnes(classes, relais);
            assertTrue(relais.size() > avant, "aucun abonné lu sous " + classes);
        }
        List<String> partages = relais.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> e.getKey() + " : " + e.getValue())
                .toList();
        assertEquals(List.of(), partages, "renommer l'une des méthodes");
    }

    /** Ajoute chaque abonné des classes d'un dossier sous le nom du relais que Forge lui engendrerait. */
    private static void abonnes(Path dossier, Map<String, List<String>> relais) throws IOException {
        List<Path> fichiers;
        try (Stream<Path> arbre = Files.walk(dossier)) {
            fichiers = arbre.filter(f -> f.toString().endsWith(".class")).sorted().toList();
        }
        for (Path fichier : fichiers) {
            ClassNode classe = lire(fichier);
            for (MethodNode methode : classe.methods) {
                if (!abonne(methode)) {
                    continue;
                }
                int paquet = classe.name.lastIndexOf('/');
                String evenement = Type.getArgumentTypes(methode.desc)[0].getInternalName();
                String nom = classe.name.substring(0, paquet).replace('/', '.') + ".__" + court(classe.name) + "_"
                        + methode.name + "_" + court(evenement);
                relais.computeIfAbsent(nom, cle -> new ArrayList<>()).add(classe.name.replace('/', '.'));
            }
        }
    }

    /** {@return le nom court d'une classe : celui d'une classe interne sans ses englobantes} */
    private static String court(String interne) {
        String sansPaquet = interne.substring(interne.lastIndexOf('/') + 1);
        return sansPaquet.substring(sansPaquet.lastIndexOf('$') + 1);
    }

    private static boolean abonne(MethodNode methode) {
        List<AnnotationNode> annotations = methode.visibleAnnotations;
        return annotations != null && annotations.stream().anyMatch(a -> SUBSCRIBE.equals(a.desc));
    }

    private static ClassNode lire(Path fichier) {
        try (InputStream flux = Files.newInputStream(fichier)) {
            ClassNode classe = new ClassNode();
            new ClassReader(flux).accept(classe, ClassReader.SKIP_CODE);
            return classe;
        } catch (IOException faute) {
            throw new UncheckedIOException(faute);
        }
    }
}
