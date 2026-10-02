package dev.axion.render;

import dev.axion.asset.AssetLoader;
import dev.axion.asset.AssetRegistry.Published;
import dev.axion.asset.GeometryTransfer;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Maillages des assets, chargés dans le natif pour le rendu client (ADR-119, C-61 T2).
 *
 * <p>Chaque asset publié ({@link Published}) est chargé une fois par le natif, qui en décode la
 * géométrie et la pose de repos ; le cache garde le résultat et le <b>handle</b> qui va avec.
 * Trois threads s'y croisent, et la classe est construite autour d'eux :
 *
 * <ul>
 *   <li><b>le thread de rendu</b> appelle {@link #get} à chaque frame. Il ne bloque jamais :
 *       une table concurrente lui répond, et un maillage absent est demandé puis remplacé, le
 *       temps du chargement, par la boîte de repli (décision 2 d'ADR-119) ;
 *   <li><b>le thread de fond</b> exécute les chargements. Un gros modèle s'y décode sans
 *       à-coup à l'écran ;
 *   <li><b>le thread qui arrête</b> — rendu à la sortie d'un monde, serveur intégré à l'arrêt
 *       du jeu — rend tous les handles par {@link #releaseAll} ou {@link #close}, avant la
 *       fermeture du natif (R-321, R-322).
 * </ul>
 *
 * <p>Tout ce qui touche un handle natif passe sous un même verrou : le chargement, le retrait
 * d'un maillage remplacé, la libération. Une libération attend donc le chargement en vol, puis
 * rend aussi son handle. Une <b>époque</b>, incrémentée à chaque libération, fait abandonner
 * les chargements demandés avant elle ; un chargement supplanté pendant qu'il tournait rend
 * son handle aussitôt. Aucun chemin ne laisse un handle vivant hors de {@link #live}, et rien
 * de ce qui y est n'échappe à la libération.
 *
 * <p>Deux publications de même clé désignent le même conteneur compilé (C-25) : le maillage
 * déjà chargé est gardé, sans rechargement. Un échec est rapporté une fois, et n'est pas
 * retenté pour le même contenu — le décodage est déterministe.
 */
public final class MeshCache {

    private final AssetLoader loader;
    private final Executor executor;
    private final Consumer<String> diagnostics;

    /** État de chaque chemin d'asset. Lu sans verrou par le thread de rendu. */
    private final Map<String, State> states = new ConcurrentHashMap<>();

    /** Garde les appels natifs, {@link #live} et les écritures de l'époque. */
    private final Object nativeLock = new Object();

    /** Handles natifs vivants, rendus par la libération. Sous {@link #nativeLock}. */
    private final Set<Handle> live = new HashSet<>();

    /** Incrémentée à chaque libération ; écrite sous {@link #nativeLock}. */
    private volatile long epoch;

    /** Vrai après {@link #close} : plus aucun chargement. */
    private volatile boolean closed;

    /**
     * Crée un cache vide.
     *
     * @param loader chargeur natif
     * @param executor exécute les chargements, hors du thread de rendu
     * @param diagnostics reçoit les échecs, chacun une fois
     */
    public MeshCache(AssetLoader loader, Executor executor, Consumer<String> diagnostics) {
        this.loader = loader;
        this.executor = executor;
        this.diagnostics = diagnostics;
    }

    /**
     * {@return le maillage d'un asset publié, ou {@code null} s'il n'est pas prêt}
     *
     * <p>Appelé par le thread de rendu ; ne bloque jamais. Un maillage absent, ou chargé
     * depuis un autre contenu, est demandé au thread de fond.
     *
     * @param path chemin de l'asset
     * @param source contenu publié de l'asset
     */
    public GeometryTransfer get(String path, Published source) {
        if (closed) {
            return null;
        }
        State current = states.get(path);
        if (current != null && sameContent(current.source, source)) {
            return current instanceof Ready ready ? ready.mesh : null;
        }
        schedule(path, source, current);
        return null;
    }

    /**
     * Rend tous les handles (sortie d'un monde). Le cache reste utilisable : le prochain
     * {@link #get} recharge ce qu'il demande.
     */
    public void releaseAll() {
        synchronized (nativeLock) {
            epoch++;
            releaseLocked();
        }
    }

    /**
     * Rend tous les handles et refuse tout chargement ultérieur (arrêt du natif).
     */
    public void close() {
        synchronized (nativeLock) {
            closed = true;
            epoch++;
            releaseLocked();
        }
    }

    /** {@return le nombre de handles natifs détenus} */
    public int liveHandles() {
        synchronized (nativeLock) {
            return live.size();
        }
    }

    /** Même conteneur compilé : même publication, ou même clé (C-25). */
    private static boolean sameContent(Published held, Published wanted) {
        return held == wanted
                || (held.key() != null
                        && held.key().equals(wanted.key())
                        && held.assetId() == wanted.assetId());
    }

    /** Pose un chargement à la place de {@code previous} et le confie au thread de fond. */
    private void schedule(String path, Published source, State previous) {
        Loading loading = new Loading(source, epoch);
        boolean installed = previous == null
                ? states.putIfAbsent(path, loading) == null
                : states.replace(path, previous, loading);
        if (!installed) {
            // Une libération est passée entre-temps ; la frame suivante redemandera.
            return;
        }
        if (previous instanceof Ready ready) {
            // Refusé, le retrait n'est pas perdu : le handle reste dans `live`, rendu à la
            // prochaine libération.
            submit(() -> retire(ready.handle));
        }
        if (!submit(() -> load(path, loading))) {
            settleFailed(path, loading, "chargement impossible, exécuteur arrêté");
        }
    }

    private boolean submit(Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException refused) {
            return false;
        }
    }

    /** Thread de fond : charge, décode, et installe le maillage s'il est encore attendu. */
    private void load(String path, Loading loading) {
        synchronized (nativeLock) {
            if (closed || loading.epoch != epoch) {
                // Demandé avant une libération : rien n'est chargé, la demande s'efface.
                states.remove(path, loading);
                return;
            }
            AssetLoader.Loaded loaded;
            try {
                loaded = loader.load(loading.source.assetId(), loading.source.a3d());
            } catch (RuntimeException | UnsatisfiedLinkError failure) {
                settleFailed(path, loading, "chargement natif impossible : " + failure);
                return;
            }
            if (!loaded.ok()) {
                settleFailed(path, loading, "chargement natif refusé, code " + loaded.code());
                return;
            }
            Handle handle = new Handle(loaded.index(), loaded.generation());
            live.add(handle);
            GeometryTransfer mesh;
            try {
                mesh = GeometryTransfer.parse(loaded.transfer());
            } catch (IllegalArgumentException malformed) {
                unloadLocked(handle);
                settleFailed(path, loading, "géométrie illisible : " + malformed.getMessage());
                return;
            }
            if (!states.replace(path, loading, new Ready(loading.source, mesh, handle))) {
                // Supplanté pendant le chargement : un autre contenu est attendu.
                unloadLocked(handle);
            }
        }
    }

    /** Remplace un chargement par un échec, et le rapporte s'il était encore attendu. */
    private void settleFailed(String path, Loading loading, String reason) {
        if (states.replace(path, loading, new Failed(loading.source))) {
            diagnostics.accept(path + " — " + reason + " ; boîte de repli affichée");
        }
    }

    /** Thread de fond : rend le handle d'un maillage remplacé. */
    private void retire(Handle handle) {
        synchronized (nativeLock) {
            unloadLocked(handle);
        }
    }

    /** Rend un handle, une seule fois. Sous {@link #nativeLock}. */
    private void unloadLocked(Handle handle) {
        if (!live.remove(handle)) {
            return;
        }
        int code = loader.unload(handle.index(), handle.generation());
        if (code != 0) {
            diagnostics.accept("handle de maillage " + handle + " non rendu, code " + code);
        }
    }

    /** Rend tout et oublie tout. Sous {@link #nativeLock}. */
    private void releaseLocked() {
        for (Handle handle : List.copyOf(live)) {
            unloadLocked(handle);
        }
        states.clear();
    }

    /** Un handle d'asset natif (DM-01). */
    private record Handle(int index, int generation) {}

    /**
     * État d'un chemin. Comparé par <b>identité</b> : les remplacements conditionnels de la
     * table ne doivent réussir que sur l'état exact qu'on a lu.
     */
    private abstract static class State {
        final Published source;

        State(Published source) {
            this.source = source;
        }
    }

    /** Chargement demandé, à l'époque où il l'a été. */
    private static final class Loading extends State {
        final long epoch;

        Loading(Published source, long epoch) {
            super(source);
            this.epoch = epoch;
        }
    }

    /** Maillage prêt, et le handle qui le retient dans le natif. */
    private static final class Ready extends State {
        final GeometryTransfer mesh;
        final Handle handle;

        Ready(Published source, GeometryTransfer mesh, Handle handle) {
            super(source);
            this.mesh = mesh;
            this.handle = handle;
        }
    }

    /** Échec pour ce contenu : la boîte de repli le remplace. */
    private static final class Failed extends State {
        Failed(Published source) {
            super(source);
        }
    }
}
