package dev.axion.diag;

import dev.axion.bootstrap.BootstrapOutcome;
import dev.axion.lifecycle.AxionRuntime;
import dev.axion.lifecycle.HookGuard;
import dev.axion.render.RenderCapabilities;
import java.util.ArrayList;
import java.util.List;

/**
 * Rapport d'état d'AXION, tel que {@code /axion status} l'affiche (C-05, C-71).
 *
 * <p>Le rapport se construit hors de toute API de plateforme : il n'est qu'une
 * liste de lignes, ce qui le rend vérifiable sans démarrer le jeu et réutilisable
 * par l'overlay de debug comme par {@code /axion diag dump}.
 *
 * <p>Il ne contient <strong>aucune donnée de monde, de chat ou de joueur</strong>
 * (R-442). Ce qu'il montre décrit l'installation et l'état du moteur, rien
 * d'autre.
 *
 * <p>Quand AXION est inactif, le rapport dit <em>pourquoi</em>. C'est tout son
 * intérêt : un moteur silencieux sans explication est indiscernable d'un moteur
 * en panne.
 */
public final class StatusReport {

    private StatusReport() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /**
     * Compose le rapport d'état.
     *
     * @param runtime cycle de vie à décrire
     * @return les lignes du rapport, dans l'ordre d'affichage
     */
    public static List<String> of(AxionRuntime runtime) {
        List<String> lines = new ArrayList<>();
        BootstrapOutcome outcome = runtime.outcome();

        lines.add("AXION ENGINE — " + (runtime.isOperational() ? "actif" : "inactif"));
        lines.add("  phase : " + runtime.phase());

        if (outcome == null) {
            // Avant la phase de préparation, ou après un arrêt : il n'y a pas
            // d'issue à décrire, et le taire vaut mieux que l'inventer.
            lines.add("  démarrage : pas encore effectué");
        } else if (outcome.isReady()) {
            lines.add("  runtime natif : prêt");
            lines.add("  aller-retour FFI : " + outcome.ffiRoundtripNanos() + " ns (médiane mesurée)");
        } else {
            lines.add("  runtime natif : inactif — " + outcome.reason());
            lines.add("  cause : " + outcome.detail());
        }

        if (outcome != null && !outcome.diagnostics().isEmpty()) {
            lines.add("  diagnostics du démarrage :");
            outcome.diagnostics().forEach(line -> lines.add("    " + line));
        }

        if (runtime.assets() != null) {
            long prets = runtime.assets().entries().stream()
                    .filter(entry -> entry.state().isUsable())
                    .count();
            long refuses = runtime.assets().entries().stream()
                    .filter(entry -> entry.state() == dev.axion.asset.AssetState.FAILED)
                    .count();
            lines.add("  assets : " + prets + " prêt(s), " + refuses + " refusé(s), sur "
                    + runtime.assets().entries().size());
            // Un asset refusé se voit ici et nulle part ailleurs après son
            // unique passage au journal (R-522).
            runtime.assets().diagnostics().forEach(line -> lines.add("    " + line));
        }

        RenderCapabilities render = runtime.renderCapabilities();
        if (render == null) {
            lines.add("  rendu : aucun backend client (serveur dédié, ou aucun monde chargé)");
        } else {
            // R-1493 : ce que le backend actif ne sait pas faire se lit ici, avec son repli.
            List<String> description = render.describe();
            lines.add("  rendu : " + description.get(0));
            description.subList(1, description.size()).forEach(line -> lines.add("    " + line));
        }

        List<HookGuard> disabled = runtime.guards().values().stream()
                .filter(HookGuard::isDisabled)
                .toList();
        if (disabled.isEmpty()) {
            lines.add("  hooks : tous actifs");
        } else {
            // Un hook devenu silencieux doit rester visible : sans cela, une
            // fonctionnalité disparaîtrait sans explication.
            lines.add("  hooks désactivés (E-1010) :");
            disabled.forEach(guard -> lines.add("    " + guard.name()
                    + " après " + guard.consecutiveFailures() + " échecs consécutifs"));
        }

        return List.copyOf(lines);
    }
}
