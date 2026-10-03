package dev.axion.render;

import dev.axion.asset.AssetLoader;
import dev.axion.asset.AssetRegistry.Published;
import dev.axion.asset.GeometryTransfer;
import dev.axion.asset.MaterialTransfer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Maillages, matériaux et textures des assets, chargés pour le rendu client (ADR-119, ADR-122,
 * C-61 T2, C-26).
 *
 * <p>Chaque asset publié ({@link Published}) est chargé une fois par le natif, qui en décode la
 * géométrie, la pose de repos et la table des matériaux ; ses textures sont préparées à la suite,
 * puis téléversées. Le cache garde le résultat, le <b>handle</b> natif qui va avec et les
 * textures enregistrées. Trois threads s'y croisent, et la classe est construite autour d'eux :
 *
 * <ul>
 *   <li><b>le thread de rendu</b> appelle {@link #get} à chaque frame. Il ne bloque jamais : une
 *       table concurrente lui répond, et un asset absent est demandé puis remplacé, le temps du
 *       chargement, par la boîte de repli (décision 2 d'ADR-119). C'est lui aussi qui téléverse
 *       et libère les textures : il est seul à toucher au contexte graphique (R-751) ;
 *   <li><b>le thread de fond</b> exécute les chargements, établit l'apparence des meshes —
 *       matériau, couleurs de sommets — et prépare les textures — lecture, contrôles, découpe,
 *       mipmaps. Un gros modèle s'y décode sans à-coup à l'écran ;
 *   <li><b>le thread qui arrête</b> — rendu à la sortie d'un monde, serveur intégré à l'arrêt
 *       du jeu — rend tous les handles par {@link #releaseAll} ou {@link #close}, avant la
 *       fermeture du natif (R-321, R-322).
 * </ul>
 *
 * <p>Tout ce qui touche un handle natif passe sous un même verrou : le chargement, la lecture des
 * matériaux et des textures embarquées, le retrait d'un asset remplacé, la libération. Une
 * libération attend donc le chargement en vol, puis rend aussi son handle. Une <b>époque</b>,
 * incrémentée à chaque libération, fait abandonner les chargements demandés avant elle ; un
 * chargement supplanté rend son handle et abandonne ses textures. Aucun chemin ne laisse un
 * handle vivant hors de {@link #live}, ni une texture enregistrée hors d'un asset prêt.
 *
 * <p>Deux publications de même clé désignent le même conteneur compilé (C-25) : l'asset déjà
 * chargé est gardé, sans rechargement. Un échec est rapporté une fois, et n'est pas retenté pour
 * le même contenu — le décodage est déterministe. Une texture refusée ne bloque pas l'asset : la
 * texture neutre la remplace, et le refus est rapporté une fois.
 */
public final class MeshCache {

    private final AssetLoader loader;
    private final TexturePipeline textures;
    private final Executor executor;
    private final Executor renderThread;
    private final Consumer<String> diagnostics;

    /** État de chaque chemin d'asset. Lu sans verrou par le thread de rendu. */
    private final Map<String, State> states = new ConcurrentHashMap<>();

    /** Garde les appels natifs, {@link #live} et les écritures de l'époque. */
    private final Object nativeLock = new Object();

    /** Handles natifs vivants, rendus par la libération. Sous {@link #nativeLock}. */
    private final Set<Handle> live = new HashSet<>();

    /** Numéro du dernier chargement demandé : il rend uniques les noms de ses textures. */
    private final AtomicLong loads = new AtomicLong();

    /** Incrémentée à chaque libération ; écrite sous {@link #nativeLock}. */
    private volatile long epoch;

    /** Vrai après {@link #close} : plus aucun chargement. */
    private volatile boolean closed;

    /**
     * Crée un cache vide.
     *
     * @param loader chargeur natif
     * @param textures préparation, téléversement et libération des textures
     * @param executor exécute les chargements, hors du thread de rendu
     * @param renderThread exécute les téléversements et les libérations de textures sur le fil de
     *     rendu
     * @param diagnostics reçoit les échecs, chacun une fois
     */
    public MeshCache(
            AssetLoader loader,
            TexturePipeline textures,
            Executor executor,
            Executor renderThread,
            Consumer<String> diagnostics) {
        this.loader = loader;
        this.textures = textures;
        this.executor = executor;
        this.renderThread = renderThread;
        this.diagnostics = diagnostics;
    }

    /**
     * {@return l'asset prêt, ou {@code null} s'il ne l'est pas}
     *
     * <p>Appelé par le thread de rendu ; ne bloque jamais. Un asset absent, ou chargé depuis un
     * autre contenu, est demandé au thread de fond.
     *
     * @param path chemin de l'asset
     * @param source contenu publié de l'asset
     */
    public RenderAsset get(String path, Published source) {
        if (closed) {
            return null;
        }
        State current = states.get(path);
        if (current != null && sameContent(current.source, source)) {
            return current instanceof Ready ready ? ready.asset : null;
        }
        schedule(path, source, current);
        return null;
    }

    /**
     * Rend tous les handles et libère toutes les textures — sortie d'un monde, rechargement des
     * ressources (R-752). Appelé sur le fil de rendu. Le cache reste utilisable : le prochain
     * {@link #get} recharge ce qu'il demande.
     */
    public void releaseAll() {
        List<String> registered;
        synchronized (nativeLock) {
            epoch++;
            registered = releaseLocked();
        }
        registered.forEach(textures::release);
    }

    /**
     * Rend tous les handles et refuse tout chargement ultérieur (arrêt du natif). Appelé depuis
     * n'importe quel thread : les textures sont libérées sur le fil de rendu, s'il tourne encore.
     */
    public void close() {
        List<String> registered;
        synchronized (nativeLock) {
            closed = true;
            epoch++;
            registered = releaseLocked();
        }
        if (!registered.isEmpty()) {
            submit(renderThread, () -> registered.forEach(textures::release));
        }
    }

    /** {@return le nombre de handles natifs détenus} */
    public int liveHandles() {
        synchronized (nativeLock) {
            return live.size();
        }
    }

    /** {@return le nombre de textures enregistrées par les assets prêts} */
    public int liveTextures() {
        int count = 0;
        for (State state : states.values()) {
            if (state instanceof Ready ready) {
                count += ready.registered.size();
            }
        }
        return count;
    }

    /** Même conteneur compilé : même publication, ou même clé (C-25). */
    private static boolean sameContent(Published held, Published wanted) {
        return held == wanted
                || (held.key() != null
                        && held.key().equals(wanted.key())
                        && held.assetId() == wanted.assetId());
    }

    /**
     * Fil de rendu, pendant la collecte d'une frame : pose un chargement à la place de
     * {@code previous} et le confie au thread de fond. Un asset remplacé rend son handle là-bas,
     * et ses textures au passage suivant du fil de rendu.
     */
    private void schedule(String path, Published source, State previous) {
        Loading loading = new Loading(source, epoch, loads.incrementAndGet());
        boolean installed = previous == null
                ? states.putIfAbsent(path, loading) == null
                : states.replace(path, previous, loading);
        if (!installed) {
            // Une libération est passée entre-temps ; la frame suivante redemandera.
            return;
        }
        if (previous instanceof Ready ready) {
            // Une autre assembly de cette frame a pu recevoir l'ancien asset juste avant que son
            // contenu change : ses textures se libèrent la frame finie. Refusé, ce passage n'aura
            // pas lieu — on est déjà sur le fil de rendu, la libération se fait ici.
            Runnable release = () -> ready.registered.forEach(textures::release);
            if (!submit(renderThread, release)) {
                release.run();
            }
            // Refusé, le retrait n'est pas perdu : le handle reste dans `live`, rendu à la
            // prochaine libération.
            submit(executor, () -> retire(ready.handle));
        }
        if (!submit(executor, () -> load(path, loading))) {
            settleFailed(path, loading, "chargement impossible, exécuteur arrêté");
        }
    }

    private static boolean submit(Executor target, Runnable task) {
        try {
            target.execute(task);
            return true;
        } catch (RejectedExecutionException refused) {
            return false;
        }
    }

    /**
     * Thread de fond : charge dans le natif, prépare les textures hors du verrou, puis confie le
     * téléversement au fil de rendu.
     */
    private void load(String path, Loading loading) {
        Decoded decoded = loadNative(path, loading);
        if (decoded == null) {
            return;
        }
        List<MeshLook> looks = looks(path, loading, decoded);
        if (looks == null) {
            return;
        }
        Set<Integer> reported = new HashSet<>(decoded.refused);
        List<Staged> staged = new ArrayList<>();
        for (TexturePipeline.Request request : decoded.requests) {
            int rank = request.key().rank();
            try {
                TexturePipeline.Prepared prepared = textures.prepare(request);
                staged.add(new Staged(request.key(), prepared));
                if (prepared.note() != null && reported.add(rank)) {
                    diagnostics.accept(path + " — texture " + rank + " : " + prepared.note());
                }
            } catch (TextureRefusal refusal) {
                refuse(path, rank, refusal.getMessage(), reported);
            } catch (RuntimeException failure) {
                refuse(path, rank, "préparation impossible : " + failure, reported);
            }
        }
        if (!submit(renderThread, () -> install(path, loading, decoded, looks, staged))) {
            staged.forEach(stage -> textures.discard(stage.prepared));
            retire(decoded.handle);
            settleFailed(path, loading, "téléversement impossible, fil de rendu arrêté");
        }
    }

    /**
     * Thread de fond, hors du verrou : l'apparence de chaque mesh. Un mesh sans matériau dans la
     * table est rapporté une fois — sauf si la table elle-même l'a été, illisible.
     *
     * @return les apparences, ou {@code null} si le chargement s'arrête là
     */
    private List<MeshLook> looks(String path, Loading loading, Decoded decoded) {
        MeshLooks.Result result;
        try {
            result = MeshLooks.of(decoded.mesh, decoded.materials);
        } catch (RuntimeException malformed) {
            // Un mesh qui désigne hors de ses sommets : un défaut de la frontière, qui doit se voir.
            retire(decoded.handle);
            settleFailed(path, loading, "apparence impossible : " + malformed);
            return null;
        }
        if (result.note() != null && decoded.materialsRead) {
            diagnostics.accept(path + " — " + result.note());
        }
        return result.looks();
    }

    /**
     * Thread de fond, sous {@link #nativeLock} : chargement natif, géométrie, table des
     * matériaux, octets des textures embarquées — tout ce qui exige un handle vivant.
     *
     * @return ce qui a été lu, ou {@code null} si le chargement s'arrête là
     */
    private Decoded loadNative(String path, Loading loading) {
        synchronized (nativeLock) {
            if (closed || loading.epoch != epoch) {
                // Demandé avant une libération : rien n'est chargé, la demande s'efface.
                states.remove(path, loading);
                return null;
            }
            AssetLoader.Loaded loaded;
            try {
                loaded = loader.load(loading.source.assetId(), loading.source.a3d());
            } catch (RuntimeException | UnsatisfiedLinkError failure) {
                settleFailed(path, loading, "chargement natif impossible : " + failure);
                return null;
            }
            if (!loaded.ok()) {
                settleFailed(path, loading, "chargement natif refusé, code " + loaded.code());
                return null;
            }
            Handle handle = new Handle(loaded.index(), loaded.generation());
            live.add(handle);
            GeometryTransfer mesh;
            try {
                mesh = GeometryTransfer.parse(loaded.transfer());
            } catch (IllegalArgumentException malformed) {
                unloadLocked(handle);
                settleFailed(path, loading, "géométrie illisible : " + malformed.getMessage());
                return null;
            }
            MaterialTransfer read = readMaterials(path, handle);
            MaterialTransfer materials = read == null ? MaterialTransfer.empty() : read;
            Set<Integer> refused = new HashSet<>();
            List<TexturePipeline.Request> requests = requests(path, loading, handle, materials, refused);
            return new Decoded(mesh, materials, read != null, handle, requests, refused);
        }
    }

    /**
     * Sous {@link #nativeLock} : la table des matériaux. Illisible, elle n'empêche pas l'asset de
     * s'afficher — avec le matériau par défaut, et un diagnostic.
     *
     * @return la table, ou {@code null} si elle est illisible, ce qui a été rapporté
     */
    private MaterialTransfer readMaterials(String path, Handle handle) {
        AssetLoader.FetchedMaterials fetched;
        try {
            fetched = loader.materials(handle.index(), handle.generation());
        } catch (RuntimeException | UnsatisfiedLinkError failure) {
            diagnostics.accept(path + " — matériaux illisibles : " + failure + " ; matériau par défaut");
            return null;
        }
        if (!fetched.ok()) {
            diagnostics.accept(path + " — matériaux refusés, code " + fetched.code() + " ; matériau par défaut");
            return null;
        }
        return fetched.transfer();
    }

    /**
     * Sous {@link #nativeLock} : une demande par texture à préparer. Les octets des textures
     * embarquées se lisent ici, tant que le handle vit ; le chemin d'une ressource se résout et se
     * contrôle (R-531).
     */
    private List<TexturePipeline.Request> requests(
            String path, Loading loading, Handle handle, MaterialTransfer materials, Set<Integer> refused) {
        List<TexturePipeline.Request> requests = new ArrayList<>();
        Map<Integer, byte[]> embedded = new HashMap<>();
        for (TextureKey key : TexturePlan.keys(materials)) {
            int rank = key.rank();
            MaterialTransfer.Texture texture = materials.textures().get(rank);
            String location = TextureLocations.registered(path, loading.serial, key);
            if (texture.embedded()) {
                if (!embedded.containsKey(rank)) {
                    embedded.put(rank, readEmbedded(path, handle, rank, refused));
                }
                byte[] png = embedded.get(rank);
                if (png != null) {
                    requests.add(new TexturePipeline.Request(location, key, texture, png, null));
                }
            } else {
                try {
                    String resource = TextureLocations.resolveResource(path, texture.path());
                    requests.add(new TexturePipeline.Request(location, key, texture, null, resource));
                } catch (IllegalArgumentException invalid) {
                    refuse(path, rank, invalid.getMessage(), refused);
                }
            }
        }
        return requests;
    }

    /** Sous {@link #nativeLock} : les octets d'une texture embarquée, ou {@code null}. */
    private byte[] readEmbedded(String path, Handle handle, int rank, Set<Integer> refused) {
        try {
            AssetLoader.FetchedTexture fetched = loader.texture(handle.index(), handle.generation(), rank);
            if (fetched.ok()) {
                return fetched.png();
            }
            refuse(path, rank, "octets refusés par le natif, code " + fetched.code(), refused);
        } catch (RuntimeException | UnsatisfiedLinkError failure) {
            refuse(path, rank, "octets illisibles : " + failure, refused);
        }
        return null;
    }

    /** Rapporte une texture refusée, une fois par rang. */
    private void refuse(String path, int rank, String reason, Set<Integer> reported) {
        if (reported.add(rank)) {
            diagnostics.accept(path + " — texture " + rank + " : " + reason + " ; texture neutre");
        }
    }

    /**
     * Fil de rendu : téléverse les textures et installe l'asset, s'il est encore attendu. Supplanté
     * ou libéré entre-temps, il abandonne ses textures et rend son handle.
     */
    private void install(String path, Loading loading, Decoded decoded, List<MeshLook> looks, List<Staged> staged) {
        if (closed || loading.epoch != epoch || states.get(path) != loading) {
            staged.forEach(stage -> textures.discard(stage.prepared));
            submit(executor, () -> retire(decoded.handle));
            return;
        }
        Map<TextureKey, TextureBinding> uploaded = new HashMap<>();
        List<String> registered = new ArrayList<>();
        for (Staged stage : staged) {
            try {
                textures.upload(stage.prepared);
                uploaded.put(stage.key, stage.prepared.binding());
                registered.add(stage.prepared.binding().location());
            } catch (RuntimeException failure) {
                textures.discard(stage.prepared);
                diagnostics.accept(path + " — texture " + stage.key.rank()
                        + " : téléversement impossible : " + failure + " ; texture neutre");
            }
        }
        RenderAsset asset = new RenderAsset(decoded.mesh, decoded.materials, looks, uploaded);
        Ready ready = new Ready(loading.source, asset, decoded.handle, List.copyOf(registered));
        if (!states.replace(path, loading, ready)) {
            // Fermé depuis un autre thread entre le contrôle et l'installation.
            ready.registered.forEach(textures::release);
            submit(executor, () -> retire(decoded.handle));
        }
    }

    /** Remplace un chargement par un échec, et le rapporte s'il était encore attendu. */
    private void settleFailed(String path, Loading loading, String reason) {
        if (states.replace(path, loading, new Failed(loading.source))) {
            diagnostics.accept(path + " — " + reason + " ; boîte de repli affichée");
        }
    }

    /** Rend le handle d'un asset remplacé ou abandonné. */
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

    /**
     * Rend tous les handles et oublie tout. Sous {@link #nativeLock}.
     *
     * @return les textures enregistrées des assets prêts, à libérer sur le fil de rendu
     */
    private List<String> releaseLocked() {
        for (Handle handle : List.copyOf(live)) {
            unloadLocked(handle);
        }
        List<String> registered = new ArrayList<>();
        for (State state : states.values()) {
            if (state instanceof Ready ready) {
                registered.addAll(ready.registered);
            }
        }
        states.clear();
        return registered;
    }

    /** Un handle d'asset natif (DM-01). */
    private record Handle(int index, int generation) {}

    /**
     * Ce que le thread de fond a lu sous le verrou.
     *
     * @param materials table des matériaux ; vide si elle est illisible
     * @param materialsRead faux si la table était illisible, ce qui a déjà été rapporté
     * @param refused rangs déjà rapportés comme refusés, à ne pas rapporter une seconde fois
     */
    private record Decoded(
            GeometryTransfer mesh,
            MaterialTransfer materials,
            boolean materialsRead,
            Handle handle,
            List<TexturePipeline.Request> requests,
            Set<Integer> refused) {}

    /** Une texture préparée, et la clé sous laquelle l'asset la retrouvera. */
    private record Staged(TextureKey key, TexturePipeline.Prepared prepared) {}

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

    /** Chargement demandé, à l'époque où il l'a été, sous son numéro. */
    private static final class Loading extends State {
        final long epoch;
        final long serial;

        Loading(Published source, long epoch, long serial) {
            super(source);
            this.epoch = epoch;
            this.serial = serial;
        }
    }

    /** Asset prêt, le handle qui le retient dans le natif, et ses textures enregistrées. */
    private static final class Ready extends State {
        final RenderAsset asset;
        final Handle handle;
        final List<String> registered;

        Ready(Published source, RenderAsset asset, Handle handle, List<String> registered) {
            super(source);
            this.asset = asset;
            this.handle = handle;
            this.registered = registered;
        }
    }

    /** Échec pour ce contenu : la boîte de repli le remplace. */
    private static final class Failed extends State {
        Failed(Published source) {
            super(source);
        }
    }
}
