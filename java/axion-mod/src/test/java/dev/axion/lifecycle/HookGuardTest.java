package dev.axion.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FM-02 — un hook qui échoue ne remonte rien, et finit par se taire. */
class HookGuardTest {

    @Test
    @DisplayName("Un hook qui réussit s'exécute et reste actif")
    void hookNominal() {
        HookGuard guard = new HookGuard("tick");
        AtomicInteger appels = new AtomicInteger();

        assertTrue(guard.run(appels::incrementAndGet));
        assertTrue(guard.run(appels::incrementAndGet));

        assertEquals(2, appels.get());
        assertEquals(2, guard.invocations());
        assertFalse(guard.isDisabled());
        assertTrue(guard.failures().isEmpty());
    }

    @Test
    @DisplayName("Aucune exception ne remonte, quelle qu'elle soit")
    void rienNeRemonte() {
        HookGuard guard = new HookGuard("hook");

        // R-400 : une exception qui traverserait interromprait la distribution
        // de l'événement, donc le travail des autres mods.
        assertFalse(guard.run(() -> {
            throw new IllegalStateException("échec simulé");
        }));
        // `Throwable` et pas seulement `Exception` : une erreur de liaison
        // native remonterait sinon jusqu'à l'appelant.
        assertFalse(guard.run(() -> {
            throw new UnsatisfiedLinkError("bibliothèque absente");
        }));
        assertFalse(guard.run(() -> {
            throw new NoClassDefFoundError("classe absente");
        }));

        assertEquals(3, guard.consecutiveFailures());
        assertFalse(guard.isDisabled());
    }

    @Test
    @DisplayName("Cinq échecs consécutifs désactivent le hook (E-1010)")
    void desactivationApresCinqEchecs() {
        HookGuard guard = new HookGuard("damage");
        AtomicInteger appels = new AtomicInteger();
        Runnable echoue = () -> {
            appels.incrementAndGet();
            throw new IllegalStateException("échec répété");
        };

        for (int index = 0; index < HookGuard.MAX_CONSECUTIVE_FAILURES; index++) {
            assertFalse(guard.run(echoue));
        }

        assertTrue(guard.isDisabled());
        assertEquals(HookGuard.MAX_CONSECUTIVE_FAILURES, appels.get());

        // Un hook désactivé n'est plus exécuté du tout : c'est tout l'intérêt,
        // un hook appelé vingt fois par seconde coûterait plus cher que le
        // travail qu'il devait faire.
        assertFalse(guard.run(echoue));
        assertEquals(HookGuard.MAX_CONSECUTIVE_FAILURES, appels.get(), "hook désactivé rappelé");

        // L'incident reste visible : un hook silencieux sans trace serait une
        // fonctionnalité disparue sans explication.
        assertTrue(
                guard.failures().stream().anyMatch(line -> line.contains("E-1010")),
                () -> "E-1010 non signalé : " + guard.failures());
    }

    @Test
    @DisplayName("Un succès remet le compteur à zéro : c'est la répétition qui condamne")
    void unSuccesEffaceLArdoise() {
        HookGuard guard = new HookGuard("net");
        Runnable echoue = () -> {
            throw new IllegalStateException("incident isolé");
        };

        for (int index = 0; index < HookGuard.MAX_CONSECUTIVE_FAILURES - 1; index++) {
            guard.run(echoue);
        }
        assertEquals(HookGuard.MAX_CONSECUTIVE_FAILURES - 1, guard.consecutiveFailures());

        guard.run(() -> {});
        assertEquals(0, guard.consecutiveFailures());
        assertFalse(guard.isDisabled());

        // Et il faut de nouveau cinq échecs d'affilée pour condamner le hook.
        for (int index = 0; index < HookGuard.MAX_CONSECUTIVE_FAILURES - 1; index++) {
            guard.run(echoue);
        }
        assertFalse(guard.isDisabled(), "désactivé trop tôt après un succès");
        guard.run(echoue);
        assertTrue(guard.isDisabled());
    }

    @Test
    @DisplayName("FM-02 : un échec isolé est signalé au journal, avec son exception, une fois")
    void unEchecIsoleEstSignale() {
        HookGuard guard = new HookGuard("tick");
        IllegalStateException cause = new IllegalStateException("section ANIM compressée");
        guard.run(() -> {});
        guard.run(() -> {
            throw cause;
        });
        guard.run(() -> {});

        List<HookGuard.Report> reports = guard.drainReports();
        assertEquals(1, reports.size(), reports::toString);
        HookGuard.Report report = reports.get(0);
        assertEquals("tick", report.hook());
        assertSame(cause, report.failure(), "l'exception part avec sa pile");
        assertFalse(report.disabling());
        assertTrue(guard.drainReports().isEmpty(), "signalé une fois");
    }

    @Test
    @DisplayName("E-1010 : la désactivation est signalée quand elle survient, après le premier échec")
    void laDesactivationEstSignaleeQuandElleSurvient() {
        HookGuard guard = new HookGuard("damage");
        for (int index = 0; index < HookGuard.MAX_CONSECUTIVE_FAILURES; index++) {
            guard.run(() -> {
                throw new IllegalStateException("échec répété");
            });
        }
        List<HookGuard.Report> reports = guard.drainReports();
        assertEquals(2, reports.size(), reports::toString);
        assertFalse(reports.get(0).disabling(), "le premier échec");
        assertTrue(reports.get(1).disabling(), "puis la désactivation");
        assertTrue(reports.get(1).message().contains("E-1010"), reports.get(1)::message);
        assertTrue(reports.get(1).message().contains("3 autre(s)"),
                () -> "les échecs tus entre les deux sont comptés : " + reports.get(1).message());
    }

    @Test
    @DisplayName("FM-02 : un hook qui échoue sans cesse, un appel sur deux, ne noie pas le journal")
    void unHookIntermittentNeNoiePasLeJournal() {
        HookGuard guard = new HookGuard("net");
        Runnable echoue = () -> {
            throw new IllegalStateException("intermittent");
        };
        for (int index = 0; index < 100; index++) {
            guard.run(echoue);
            guard.run(() -> {});
        }
        assertEquals(1, guard.drainReports().size(), "un seul signalé dans la fenêtre");
        assertTrue(guard.failures().size() <= HookGuard.MAX_KEPT_FAILURES,
                () -> "l'historique est borné : " + guard.failures().size());

        // La fenêtre passée, les échecs tus sont dits, en nombre, même sans échec de plus.
        for (long index = 0; index < HookGuard.REPORT_WINDOW; index++) {
            guard.run(() -> {});
        }
        List<HookGuard.Report> reports = guard.drainReports();
        assertEquals(1, reports.size(), reports::toString);
        assertTrue(reports.get(0).message().contains("99"), reports.get(0)::message);
        assertFalse(guard.isDisabled());
    }
}
