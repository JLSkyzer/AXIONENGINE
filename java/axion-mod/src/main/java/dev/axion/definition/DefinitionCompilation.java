package dev.axion.definition;

import dev.axion.asset.AssetEntry;
import dev.axion.asset.AssetState;
import dev.axion.asset.DefinitionCompiler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.ToIntFunction;

/**
 * La compilation des definitions par le natif (étapes 4 et 5 de C-27, ADR-156 D2, D3, D5).
 *
 * <p>Chaque definition acceptée aux étapes 1 à 3 attend que son asset soit compilé, puis se compile
 * contre lui, sous le budget de la pompe d'assets ({@code budgets.asset_ns_per_tick}, R-474) : le
 * natif résout ses noms, forme ses corps et l'inscrit au registre de la session. Un refus — nom
 * introuvable, plafond, racine sous un node animé, asset indisponible — la retire de la registry
 * effective ({@link #effective()}), qui est celle que lisent l'apparition, le rendu et les commandes.
 *
 * <p>Un rechargement des definitions, des matériaux ou des assets repart de zéro ({@link #reset}) :
 * le registre natif est vidé de ce qu'il tenait, et tout attend de nouveau. Rien ne s'y compile contre
 * un asset ou un index de matériau périmé.
 *
 * <p>Les méthodes sont synchronisées : la pompe tourne au tick, un rechargement peut venir d'ailleurs.
 */
public final class DefinitionCompilation {

    private final DefinitionCompiler compiler;
    private final boolean withJoints;
    private final ToIntFunction<String> materialIndex;

    private DefinitionRegistry loaded = DefinitionRegistry.empty();
    private final SortedMap<String, Definition> pending = new TreeMap<>();
    private final Map<String, DefinitionCompiler.Summary> compiled = new HashMap<>();
    private final SortedMap<String, String> refused = new TreeMap<>();
    private final List<String> journal = new ArrayList<>();
    private DefinitionRegistry effective = loaded;

    /**
     * @param compiler le natif
     * @param withJoints faux si {@code modules.joints} est désactivé
     * @param materialIndex index d'un matériau physique dans la table de la session (ADR-149)
     */
    public DefinitionCompilation(
            DefinitionCompiler compiler, boolean withJoints, ToIntFunction<String> materialIndex) {
        this.compiler = compiler;
        this.withJoints = withJoints;
        this.materialIndex = materialIndex;
    }

    /**
     * Repart d'une registry chargée : le registre natif oublie ce qu'il tenait, et chaque definition
     * attend de nouveau sa compilation.
     *
     * @param registry les definitions acceptées aux étapes 1 à 3
     */
    public synchronized void reset(DefinitionRegistry registry) {
        for (String id : compiled.keySet()) {
            compiler.remove(DefinitionIds.hash(id));
        }
        compiled.clear();
        refused.clear();
        pending.clear();
        loaded = registry;
        for (String id : registry.ids()) {
            registry.get(id).ifPresent(definition -> pending.put(id, definition));
        }
        effective = registry;
    }

    /**
     * L'asset d'une definition, vu de sa compilation.
     *
     * @param assetId identifiant de l'asset, celui que porte son en-tête
     * @param compiled son conteneur compilé, ou {@code null} tant qu'il ne l'est pas
     * @param unavailable il n'existe pas, ou sa compilation a échoué
     */
    public record AssetStatus(long assetId, byte[] compiled, boolean unavailable) {

        /**
         * {@return l'état d'une entrée du registre d'assets}
         *
         * @param entry l'entrée, ou {@code null} si le registre n'a pas cet asset
         */
        public static AssetStatus of(AssetEntry entry) {
            return entry == null
                    ? new AssetStatus(0, null, true)
                    : new AssetStatus(entry.assetId(), entry.compiled(), entry.state() == AssetState.FAILED);
        }
    }

    /**
     * Compile les definitions en attente dont l'asset est compilé.
     *
     * @param assets l'état d'un asset par son chemin ({@code <ns>:axion/<chemin>})
     * @param budgetNanos budget de l'appel ; {@code 0} ou moins, sans limite. Une compilation au
     *     moins se fait à chaque appel qui en a une à faire
     * @param clock horloge, en nanosecondes
     * @return vrai si une definition a été compilée ou refusée
     */
    public synchronized boolean pump(Function<String, AssetStatus> assets, long budgetNanos, LongSupplier clock) {
        long start = clock.getAsLong();
        int done = 0;
        for (Definition definition : List.copyOf(pending.values())) {
            AssetStatus asset = assets.apply(definition.asset());
            byte[] a3d = asset.compiled();
            if (a3d == null && !asset.unavailable()) {
                continue;
            }
            if (done > 0 && budgetNanos > 0 && clock.getAsLong() - start >= budgetNanos) {
                break;
            }
            done++;
            pending.remove(definition.id());
            if (a3d == null) {
                // R-1640 côté serveur : sans son asset, la definition est désactivée (E-3001).
                refuse(definition, "E-3001", "$.asset", "asset « " + definition.asset() + " » indisponible");
                continue;
            }
            compile(definition, asset.assetId(), a3d);
        }
        if (done > 0) {
            effective = loaded.without(refused);
        }
        return done > 0;
    }

    private void compile(Definition definition, long assetId, byte[] a3d) {
        DefinitionEncoder.Encoded encoded;
        try {
            encoded = DefinitionEncoder.encode(definition, withJoints, loaded.collisionGroups(), materialIndex);
        } catch (DefinitionException refusal) {
            refuse(definition, refusal.codeName(), refusal.path(), refusal.getMessage());
            return;
        }
        DefinitionCompiler.Result result = compiler.compile(definition.hash(), assetId, a3d, encoded.bytes());
        if (result.compiled()) {
            compiled.put(definition.id(), result.summary());
            for (String warning : result.summary().warnings()) {
                journal.add("AXION : definition " + definition.id() + " : " + warning);
            }
        } else if (result.refused()) {
            refuse(definition, "E-" + (-result.code()), encoded.locate(result.reference()), result.message());
        } else {
            // La frontière a échoué, pas la definition : elle reste inutilisable, et le journal le dit.
            refuse(definition, "E-" + (-result.code()), "$", "compilation impossible : " + result.message());
        }
    }

    private void refuse(Definition definition, String code, String path, String message) {
        String line = code + " " + definition.location() + " — " + path + " : " + message;
        refused.put(definition.id(), line);
        journal.add(line);
    }

    /** {@return la registry que lisent l'apparition, le rendu et les commandes : la chargée, moins les refus de la compilation} */
    public synchronized DefinitionRegistry effective() {
        return effective;
    }

    /**
     * {@return le résumé d'une definition compilée, ou vide si elle attend ou a été refusée}
     *
     * @param id identifiant de la definition
     */
    public synchronized Optional<DefinitionCompiler.Summary> summary(String id) {
        return Optional.ofNullable(compiled.get(id));
    }

    /** {@return vrai si aucune definition n'attend plus son asset} */
    public synchronized boolean isSettled() {
        return pending.isEmpty();
    }

    /** {@return le nombre de definitions compilées} */
    public synchronized int compiledCount() {
        return compiled.size();
    }

    /** {@return le nombre de definitions que leur compilation a refusées} */
    public synchronized int refusedCount() {
        return refused.size();
    }

    /** {@return les lignes consignées depuis l'appel précédent — refus et avertissements —, puis les oublie} */
    public synchronized List<String> drainJournal() {
        List<String> lines = List.copyOf(journal);
        journal.clear();
        return lines;
    }
}
