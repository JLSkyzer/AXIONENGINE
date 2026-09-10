package dev.axion.asset;

import dev.axion.asset.AssetCompiler.CompileStatus;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Orchestrateur d'assets (C-20).
 *
 * <p>Il décide <strong>quoi</strong> compiler et <strong>quand</strong>. La
 * compilation elle-même est ailleurs — côté natif, sur le pool de jobs — et
 * c'est ce partage qui permet à R-521 de tenir : rien ici ne bloque le thread
 * autoritatif, jamais.
 *
 * <p>Trois règles gouvernent son comportement :
 *
 * <ul>
 *   <li><strong>R-520</strong> : un rechargement de ressources ne recompile que
 *       les assets dont la clé a changé. Tout recompiler à chaque rechargement
 *       rendrait la commande inutilisable sur un pack un peu fourni ;
 *   <li><strong>R-521</strong> : le sondage se fait à chaque tick, sous le
 *       budget {@code budgets.asset_ns_per_tick}. Dépasser le budget est
 *       préférable à le respecter en abandonnant un travail — mais le
 *       dépassement est marqué, jamais ignoré ;
 *   <li><strong>R-522</strong> : un asset qui échoue est journalisé
 *       <strong>une fois</strong>, remplacé par l'asset de secours, et
 *       n'empêche jamais le chargement du monde.
 * </ul>
 */
public final class AssetRegistry {

    /** Identifiant de l'asset de secours, toujours disponible (R-522). */
    public static final String FALLBACK_ID = "axion:builtin/missing";

    /** Options de compilation, telles qu'elles entrent dans la clé. */
    private static final String OPTIONS = "";

    private final AssetSource source;
    private final AssetCompiler compiler;
    private final int compilerVersion;
    private final LongSupplier clock;

    private final Map<String, AssetEntry> entries = new LinkedHashMap<>();
    private final List<String> diagnostics = new ArrayList<>();

    /**
     * Crée un orchestrateur.
     *
     * @param source d'où viennent les sources
     * @param compiler qui les compile
     * @param compilerVersion version du compilateur, entrant dans la clé
     * @param clock horloge en nanosecondes, pour tenir le budget
     */
    public AssetRegistry(
            AssetSource source, AssetCompiler compiler, int compilerVersion, LongSupplier clock) {
        this.source = source;
        this.compiler = compiler;
        this.compilerVersion = compilerVersion;
        this.clock = clock;
    }

    /**
     * Énumère les sources et met à jour le registre (C-20 étape 1, R-520).
     *
     * <p>Un asset dont la clé n'a pas changé <strong>reste où il en est</strong>
     * : c'est tout l'objet de R-520. Un asset dont la clé a changé repart de la
     * découverte, y compris s'il avait échoué — une source corrigée doit
     * reprendre sa chance.
     *
     * @return le nombre d'assets à recompiler
     */
    public int discover() {
        List<String> paths = source.list();
        int changed = 0;

        for (String path : paths) {
            int format = SourceFormats.fromPath(path);
            if (format == SourceFormats.UNKNOWN) {
                // Un `.a3d` est déjà le résultat d'une compilation, et le reste
                // n'est pas une source de modèle. Les compter comme des échecs
                // remplirait le journal de faux problèmes.
                continue;
            }

            byte[] content;
            try {
                content = source.read(path);
            } catch (IOException failure) {
                fail(path, format, 0, "source illisible : " + failure);
                continue;
            }

            AssetKey key = AssetKey.of(content, OPTIONS, compilerVersion);
            AssetEntry existing = entries.get(path);
            if (existing != null && key.equals(existing.key())) {
                // R-520 : rien n'a changé, rien n'est refait.
                continue;
            }

            entries.put(path, new AssetEntry(path, format, key, content));
            changed++;
        }

        // Une source disparue laisse son entrée derrière elle : la retirer est
        // ce qui distingue un pack rechargé d'un pack qui grossit sans fin.
        entries.keySet().removeIf(path -> !paths.contains(path));
        return changed;
    }

    /**
     * Fait avancer les compilations, sous budget (R-521).
     *
     * <p>Le budget est vérifié <strong>entre</strong> deux assets, jamais au
     * milieu d'un : abandonner une soumission à moitié faite laisserait un
     * travail en vol que personne ne sonderait plus. Un tick peut donc dépasser
     * son budget de la durée d'une soumission, et le dépassement est rapporté.
     *
     * @param budgetNanos budget du tick, en nanosecondes ; {@code 0} lève toute
     *     limite, ce qu'emploie la barrière de démarrage de R-521
     * @return ce que le tick a fait
     */
    public PumpResult pump(long budgetNanos) {
        long start = clock.getAsLong();
        int submitted = 0;
        int completed = 0;

        for (AssetEntry entry : entries.values()) {
            if (budgetNanos > 0 && clock.getAsLong() - start >= budgetNanos) {
                break;
            }
            switch (entry.state()) {
                case DISCOVERED -> {
                    if (submit(entry)) {
                        submitted++;
                    }
                }
                case COMPILING -> {
                    if (advance(entry)) {
                        completed++;
                    }
                }
                default -> {
                    // Rien à faire : l'asset est prêt, relâché, ou refusé.
                }
            }
        }

        long elapsed = clock.getAsLong() - start;
        return new PumpResult(submitted, completed, elapsed, budgetNanos > 0 && elapsed > budgetNanos);
    }

    private boolean submit(AssetEntry entry) {
        entry.transitionTo(AssetState.QUEUED);
        int job = compiler.submit(entry.assetId(), entry.format(), entry.content());
        if (job <= 0) {
            fail(entry, job, "compilation refusée");
            return false;
        }
        entry.startCompiling(job);
        return true;
    }

    private boolean advance(AssetEntry entry) {
        CompileStatus status = compiler.poll(entry.jobId());
        switch (status.state()) {
            case COMPILED -> {
                entry.transitionTo(AssetState.COMPILED);
                entry.setCompiledSize(status.size());
                return true;
            }
            case FAILED -> {
                fail(entry, status.error(), "compilation échouée");
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void fail(AssetEntry entry, int code, String reason) {
        entry.transitionTo(AssetState.FAILED);
        // R-522 : journalisé **une fois**. Un asset refusé le reste jusqu'au
        // prochain rechargement, et répéter son message à chaque tick noierait
        // tout le reste.
        if (entry.markLogged()) {
            diagnostics.add(entry.path() + " — " + reason + ", code " + code
                    + " ; remplacé par " + FALLBACK_ID);
        }
    }

    private void fail(String path, int format, int code, String reason) {
        AssetEntry entry = entries.computeIfAbsent(
                path, key -> new AssetEntry(path, format, null, new byte[0]));
        fail(entry, code, reason);
    }

    /** {@return les entrées du registre, dans l'ordre de découverte} */
    public List<AssetEntry> entries() {
        return List.copyOf(entries.values());
    }

    /**
     * {@return l'entrée d'un chemin, ou {@code null}}
     *
     * @param path chemin de la ressource
     */
    public AssetEntry entry(String path) {
        return entries.get(path);
    }

    /**
     * {@return l'identifiant à employer pour un chemin}
     *
     * <p>Un asset refusé rend l'asset de secours (R-522) : le monde se charge,
     * la pièce manquante se voit, et rien ne s'arrête.
     *
     * @param path chemin de la ressource
     */
    public String resolve(String path) {
        AssetEntry entry = entries.get(path);
        return entry != null && entry.state().isUsable() ? path : FALLBACK_ID;
    }

    /** {@return vrai si aucun asset n'attend plus rien} */
    public boolean isSettled() {
        return entries.values().stream().noneMatch(entry -> entry.state().isPending());
    }

    /** {@return les diagnostics accumulés, chacun émis une seule fois} */
    public List<String> diagnostics() {
        return List.copyOf(diagnostics);
    }

    /**
     * Ce qu'un tick de sondage a fait.
     *
     * @param submitted compilations lancées
     * @param completed compilations abouties ou refusées
     * @param elapsedNanos durée mesurée du tick
     * @param overBudget le budget du tick a été dépassé
     */
    public record PumpResult(int submitted, int completed, long elapsedNanos, boolean overBudget) {

        /** {@return vrai si le tick n'a rien eu à faire} */
        public boolean isIdle() {
            return submitted == 0 && completed == 0;
        }
    }
}
