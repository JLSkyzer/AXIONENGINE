package dev.axion.forge;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Le rapport du banc de rendu (ADR-126), relu par la tâche Gradle qui l'a lancé : vert seulement si
 * chaque vérification attendue a été rendue, et aucune rouge — une vérification peut être mesurée
 * sans être jugée ({@link Verdict#NON_JUGE}), sa raison écrite.
 *
 * <p>Contenu de développement, jamais empaqueté (R-1790).
 */
final class RapportDuBanc {

    /** Le journal du banc lui-même : il n'est pas compté parmi les erreurs d'AXION. */
    private static final Logger LOGGER = LoggerFactory.getLogger("axion.banc");

    /** Le verdict d'une vérification, tel que le rapport l'écrit. */
    enum Verdict {
        /** Passée. */
        VERT("vert"),
        /** Échouée. */
        ROUGE("rouge"),
        /**
         * Mesurée et rapportée, mais pas jugée, pour une raison écrite dans son détail : un critère
         * de performance sous un rendu logiciel (T-904, ADR-127 §4). Elle ne fait le banc ni vert ni
         * rouge.
         */
        NON_JUGE("non jugé");

        private final String json;

        Verdict(String json) {
            this.json = json;
        }
    }

    /**
     * Une vérification rendue.
     *
     * @param id identifiant du test (T-470…) ou de la vérification
     * @param verdict son verdict
     * @param detail ce qui a été mesuré
     */
    record Resultat(String id, Verdict verdict, String detail) {}

    private final List<String> attendus;
    private final List<Resultat> resultats = new ArrayList<>();

    /** @param attendus les vérifications sans lesquelles le rapport ne peut pas être vert */
    RapportDuBanc(List<String> attendus) {
        this.attendus = List.copyOf(attendus);
    }

    /** Juge une vérification, et le journalise aussitôt. */
    void noter(String id, boolean vert, String detail) {
        resultats.add(new Resultat(id, vert ? Verdict.VERT : Verdict.ROUGE, detail));
        if (vert) {
            LOGGER.info("AXION banc de rendu : {} vert — {}", id, detail);
        } else {
            LOGGER.error("AXION banc de rendu : {} ROUGE — {}", id, detail);
        }
    }

    /**
     * Rend une vérification mesurée sans la juger ; son détail dit pourquoi. Elle compte comme
     * rendue, sans faire le banc ni vert ni rouge.
     */
    void nonJuge(String id, String detail) {
        resultats.add(new Resultat(id, Verdict.NON_JUGE, detail));
        LOGGER.warn("AXION banc de rendu : {} non jugé — {}", id, detail);
    }

    /** {@return vrai si chaque vérification attendue est rendue, et aucune n'est rouge} */
    boolean vert() {
        for (String id : attendus) {
            if (resultats.stream().noneMatch(resultat -> resultat.id().equals(id))) {
                return false;
            }
        }
        return resultats.stream().noneMatch(resultat -> resultat.verdict() == Verdict.ROUGE);
    }

    /**
     * Écrit le rapport.
     *
     * @param fichier {@code rapport.json}
     * @param dureeSecondes durée du passage
     * @param journal erreurs d'AXION relevées au journal
     */
    void ecrire(Path fichier, double dureeSecondes, List<String> journal) {
        Map<String, Object> racine = new LinkedHashMap<>();
        racine.put("version", 1);
        racine.put("verdict", vert() ? "vert" : "rouge");
        racine.put("duree_s", Math.round(dureeSecondes * 10) / 10.0);
        List<Map<String, Object>> tests = new ArrayList<>();
        for (String id : attendus) {
            if (resultats.stream().noneMatch(resultat -> resultat.id().equals(id))) {
                tests.add(Map.of("id", id, "verdict", "rouge", "detail", "non jugé : le banc s'est arrêté avant"));
            }
        }
        for (Resultat resultat : resultats) {
            Map<String, Object> test = new LinkedHashMap<>();
            test.put("id", resultat.id());
            test.put("verdict", resultat.verdict().json);
            test.put("detail", resultat.detail());
            tests.add(test);
        }
        racine.put("tests", tests);
        racine.put("journal", journal);
        try {
            Files.createDirectories(fichier.getParent());
            Files.writeString(
                    fichier,
                    new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(racine) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException echec) {
            throw new UncheckedIOException("rapport du banc impossible à écrire : " + fichier, echec);
        }
    }
}
