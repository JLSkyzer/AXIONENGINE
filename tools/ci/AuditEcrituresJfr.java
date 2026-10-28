import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/**
 * T-437 (R-1711) : lit en flux les {@code jdk.FileWrite} d'un enregistrement JFR, pour
 * {@code tools/ci/audit_ecritures.py}.
 *
 * <p>{@code jfr print} ne rend que cinq cadres par pile si on ne lui en demande pas plus, et en demander 256
 * en JSON pour des centaines de milliers d'écritures produit plus que Python ne relit raisonnablement. Ce
 * lecteur parcourt l'enregistrement sans le charger, et ne rend, pour chaque écriture, que ce que l'audit
 * juge : la classe {@code dev.axion} la plus proche de l'écriture, et ce qui s'intercale entre elles — un
 * cadre de journalisation (une ligne du journal du jeu), ou un cadre de Minecraft ou de Forge (Minecraft
 * écrit ses propres données, appelé par AXION : la déconnexion d'un joueur sauvegarde son inventaire). Il
 * agrège : une ligne par catégorie, et, pour les écritures où AXION est sur la pile, par classe et par
 * chemin.
 *
 * <p>Sortie, une ligne par groupe : {@code nombre<TAB>catégorie<TAB>classe<TAB>chemin}, la catégorie étant
 * {@code axion}, {@code minecraft-pour-axion}, {@code journalisation}, {@code sans-axion} ou
 * {@code sans-axion-tronquee}.
 *
 * <p>Usage : {@code java tools/ci/AuditEcrituresJfr.java <enregistrement.jfr>} (lancement d'un fichier
 * source, JDK 11 et plus).
 */
public final class AuditEcrituresJfr {

    private static final List<String> JOURNALISATION = List.of("org.apache.logging.", "org.slf4j.", "com.mojang.logging.");

    private static final List<String> MINECRAFT = List.of("net.minecraft.", "net.minecraftforge.", "com.mojang.");

    private AuditEcrituresJfr() {}

    public static void main(String[] args) throws Exception {
        Map<String, Long> groupes = new TreeMap<>();
        try (RecordingFile enregistrement = new RecordingFile(Path.of(args[0]))) {
            while (enregistrement.hasMoreEvents()) {
                RecordedEvent evenement = enregistrement.readEvent();
                if (!"jdk.FileWrite".equals(evenement.getEventType().getName())) {
                    continue;
                }
                String chemin = evenement.getString("path");
                if (chemin == null || chemin.isEmpty()) {
                    continue; // sortie standard ou descripteur sans chemin
                }
                groupes.merge(groupe(evenement.getStackTrace(), chemin), 1L, Long::sum);
            }
        }
        for (Map.Entry<String, Long> groupe : groupes.entrySet()) {
            System.out.println(groupe.getValue() + "\t" + groupe.getKey());
        }
    }

    private static String groupe(RecordedStackTrace pile, String chemin) {
        if (pile == null) {
            return "sans-axion\t-\t-";
        }
        boolean journalisation = false;
        boolean minecraft = false;
        for (RecordedFrame cadre : pile.getFrames()) {
            String classe = cadre.getMethod().getType().getName();
            if (classe.startsWith("dev.axion.")) {
                if (journalisation) {
                    return "journalisation\t-\t-";
                }
                return (minecraft ? "minecraft-pour-axion\t" : "axion\t") + classe + "\t" + chemin;
            }
            journalisation |= JOURNALISATION.stream().anyMatch(classe::startsWith);
            minecraft |= MINECRAFT.stream().anyMatch(classe::startsWith);
        }
        return (pile.isTruncated() ? "sans-axion-tronquee" : "sans-axion") + "\t-\t-";
    }
}
