package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.render.GpuPassTimings.Pass;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** §31.1 — le temps GPU par passe du backend natif, lu sans attendre le GPU (ADR-146 §2). */
class GpuPassTimingsTest {

    /**
     * Des requêtes d'horodatage tenues à la main : l'horloge du GPU avance à la demande, et ses
     * résultats ne sont prêts que quand le test l'a rattrapé. Lire une requête pas prête serait
     * attendre le GPU : c'est un échec.
     */
    private static final class Requetes implements GpuPassTimings.Queries {

        private int prochain = 1;
        private long horloge;
        private final Set<Integer> vivantes = new HashSet<>();
        private final Map<Integer, Long> horodatages = new HashMap<>();
        private final Set<Integer> pretes = new HashSet<>();

        @Override
        public int create() {
            vivantes.add(prochain);
            return prochain++;
        }

        @Override
        public void stamp(int query) {
            assertTrue(vivantes.contains(query), "horodatage d'une requête détruite");
            horodatages.put(query, horloge);
            pretes.remove(query);
        }

        @Override
        public boolean ready(int query) {
            return pretes.contains(query);
        }

        @Override
        public long nanos(int query) {
            assertTrue(pretes.contains(query), "lecture d'une requête pas prête : le render thread attendrait le GPU");
            return horodatages.get(query);
        }

        @Override
        public void delete(int query) {
            assertTrue(vivantes.remove(query), "requête détruite deux fois");
        }

        /** Le GPU travaille. */
        void avancer(long nanos) {
            horloge += nanos;
        }

        /** Le GPU a rattrapé tout ce qui a été soumis. */
        void rattraper() {
            pretes.addAll(horodatages.keySet());
        }
    }

    /** Une frame : la passe mesurée dure {@code nanos} au GPU. */
    private static void passe(GpuPassTimings timings, Requetes gpu, Pass pass, long nanos) {
        timings.begin(pass);
        gpu.avancer(nanos);
        timings.end(pass);
    }

    @Test
    @DisplayName("§31.1 : le temps d'une passe est l'écart de ses deux horodatages GPU")
    void laDureeEstLEcartDesHorodatages() {
        Requetes gpu = new Requetes();
        GpuPassTimings timings = new GpuPassTimings(gpu);
        passe(timings, gpu, Pass.OPAQUE, 1_500);
        gpu.rattraper();
        passe(timings, gpu, Pass.OPAQUE, 9_999);
        assertEquals(1_500, timings.lastNanos(Pass.OPAQUE), "la frame précédente, lue à l'ouverture de celle-ci");
    }

    @Test
    @DisplayName("§31.1 : un résultat pas encore rendu n'est pas lu — le render thread n'attend jamais le GPU")
    void aucuneLectureNAttendLeGpu() {
        Requetes gpu = new Requetes();
        GpuPassTimings timings = new GpuPassTimings(gpu);
        for (int frame = 0; frame < GpuPassTimings.DEPTH - 1; frame++) {
            passe(timings, gpu, Pass.OPAQUE, 1_000);
        }
        assertEquals(0, timings.lastNanos(Pass.OPAQUE), "rien n'est prêt, rien n'est connu");
        assertEquals(0, timings.dropped());
    }

    @Test
    @DisplayName("§31.1 : de plusieurs relevés prêts, le plus récent fait foi")
    void leReleveLePlusRecentFaitFoi() {
        Requetes gpu = new Requetes();
        GpuPassTimings timings = new GpuPassTimings(gpu);
        passe(timings, gpu, Pass.OPAQUE, 1_000);
        passe(timings, gpu, Pass.OPAQUE, 3_000);
        passe(timings, gpu, Pass.OPAQUE, 2_000);
        gpu.rattraper();
        passe(timings, gpu, Pass.OPAQUE, 0);
        assertEquals(2_000, timings.lastNanos(Pass.OPAQUE));
    }

    @Test
    @DisplayName("§31.1 : une paire encore en vol au retour de l'anneau est reprise, son relevé compté perdu")
    void unePaireEnVolEstRepriseEtComptee() {
        Requetes gpu = new Requetes();
        GpuPassTimings timings = new GpuPassTimings(gpu);
        for (int frame = 0; frame < GpuPassTimings.DEPTH + 2; frame++) {
            passe(timings, gpu, Pass.OPAQUE, 1_000);
        }
        assertEquals(2, timings.dropped());
        assertEquals(2 * GpuPassTimings.DEPTH, gpu.vivantes.size(), "l'anneau ne grandit pas");
        gpu.rattraper();
        passe(timings, gpu, Pass.OPAQUE, 0);
        assertEquals(1_000, timings.lastNanos(Pass.OPAQUE), "la mesure reprend dès que le GPU rattrape");
    }

    @Test
    @DisplayName("§31.1 : chaque passe a ses propres relevés")
    void lesPassesSontIndependantes() {
        Requetes gpu = new Requetes();
        GpuPassTimings timings = new GpuPassTimings(gpu);
        passe(timings, gpu, Pass.OPAQUE, 4_000);
        passe(timings, gpu, Pass.TRANSLUCENT, 700);
        gpu.rattraper();
        passe(timings, gpu, Pass.OPAQUE, 0);
        passe(timings, gpu, Pass.TRANSLUCENT, 0);
        assertEquals(4_000, timings.lastNanos(Pass.OPAQUE));
        assertEquals(700, timings.lastNanos(Pass.TRANSLUCENT));
    }

    @Test
    @DisplayName("R-744 : la fermeture détruit toutes les requêtes créées")
    void laFermetureDetruitToutesLesRequetes() {
        Requetes gpu = new Requetes();
        GpuPassTimings timings = new GpuPassTimings(gpu);
        for (int frame = 0; frame < 3; frame++) {
            passe(timings, gpu, Pass.OPAQUE, 1_000);
            passe(timings, gpu, Pass.TRANSLUCENT, 1_000);
        }
        timings.close();
        assertEquals(Set.of(), gpu.vivantes);
    }

    @Test
    @DisplayName("§31.1 : fermer une mesure jamais ouverte est une erreur")
    void uneFinSansDebutEstUneErreur() {
        GpuPassTimings timings = new GpuPassTimings(new Requetes());
        assertThrows(IllegalStateException.class, () -> timings.end(Pass.OPAQUE));
    }
}
