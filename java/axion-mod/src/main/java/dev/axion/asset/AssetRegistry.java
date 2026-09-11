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

    /** `E-3001` : asset requis indisponible au démarrage. */
    private static final int STARTUP_TIMEOUT_CODE = -3001;

    private final AssetSource source;
    private final AssetCompiler compiler;
    private final int compilerVersion;
    private final int abiVersion;
    private final LongSupplier clock;
    private final AssetCache cache;

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
        this(source, compiler, compilerVersion, 0, clock, null);
    }

    /**
     * Cree un orchestrateur adosse a un cache.
     *
     * @param source d'ou viennent les sources
     * @param compiler qui les compile
     * @param compilerVersion version du compilateur, entrant dans la cle
     * @param abiVersion version de l'ABI, entrant dans la cle (C-25)
     * @param clock horloge en nanosecondes, pour tenir le budget
     * @param cache cache des assets compiles, ou {@code null}
     */
    public AssetRegistry(
            AssetSource source,
            AssetCompiler compiler,
            int compilerVersion,
            int abiVersion,
            LongSupplier clock,
            AssetCache cache) {
        this.source = source;
        this.compiler = compiler;
        this.compilerVersion = compilerVersion;
        this.abiVersion = abiVersion;
        this.clock = clock;
        this.cache = cache;
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

            AssetKey key = AssetKey.of(content, OPTIONS, compilerVersion, abiVersion);
            AssetEntry existing = entries.get(path);
            if (existing != null && key.equals(existing.key())) {
                // R-520 : rien n'a changé, rien n'est refait.
                continue;
            }

            AssetEntry entry = new AssetEntry(path, format, key, content);
            entries.put(path, entry);

            // C-25 : une entree valide evite toute la compilation. C'est le
            // seul cas ou un asset atteint son contenu sans passer par le pool.
            byte[] cached = cache == null ? null : cache.get(key);
            if (cached != null) {
                entry.transitionTo(AssetState.CACHED);
                entry.setCompiled(cached);
                continue;
            }
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

    /**
     * Attend que tout soit compilé, sans dépasser un délai (R-521).
     *
     * <p>C'est la barrière du démarrage d'un serveur dédié : sans elle, le
     * monde se chargerait avant ses assets, et les premières entités
     * apparaîtraient inertes sans que rien n'explique pourquoi.
     *
     * <p>Au-delà du délai, les assets encore en attente sont
     * <strong>désactivés</strong> avec {@code E-3001} plutôt que d'être
     * attendus indéfiniment : un serveur qui ne démarre jamais est pire qu'un
     * serveur auquel il manque une pièce.
     *
     * @param timeoutNanos délai maximal, en nanosecondes
     * @return vrai si tout a abouti dans le délai
     */
    public boolean awaitSettled(long timeoutNanos) {
        long start = clock.getAsLong();
        while (!isSettled()) {
            if (clock.getAsLong() - start >= timeoutNanos) {
                for (AssetEntry entry : entries.values()) {
                    if (entry.state().isPending()) {
                        entry.forceFailed();
                        fail(entry, STARTUP_TIMEOUT_CODE, "non compilé dans le délai de démarrage");
                    }
                }
                return false;
            }
            // Budget levé : la barrière est précisément le moment où le temps
            // passé en compilation est celui qu'on accepte de passer.
            pump(0);
        }
        return true;
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
                entry.setCompiled(status.payload());
                if (cache != null && entry.key() != null) {
                    cache.put(entry.key(), status.payload());
                }
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

    /** {@return les diagnostics accumules, chacun emis une seule fois} */
    public List<String> diagnostics() {
        List<String> all = new ArrayList<>(diagnostics);
        if (cache != null) {
            all.addAll(cache.diagnostics());
        }
        return List.copyOf(all);
    }

    /**
     * Force la recompilation de tous les assets, cache compris.
     *
     * <p>C'est ce que fait {@code /axion assets reload} : les sources n'ont pas
     * change — {@code /reload} s'en charge —, mais leur resultat, si. On
     * l'emploie apres une correction du compilateur, ou pour repartir d'un
     * cache dont on doute.
     *
     * @return le nombre d'assets a recompiler
     */
    public int forceRecompile() {
        int count = 0;
        for (AssetEntry entry : entries.values()) {
            entry.reset();
            count++;
        }
        return count;
    }

    /** {@return le cache adosse a l'orchestrateur, ou {@code null}} */
    public AssetCache cache() {
        return cache;
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
