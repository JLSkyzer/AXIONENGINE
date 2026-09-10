package dev.axion.asset;

/**
 * Un asset et son état, dans le registre (C-20).
 *
 * <p>L'entrée est mutable, et volontairement : elle traverse la machine à états
 * de SM-01 pendant toute la vie du registre, et en recréer une à chaque
 * transition perdrait ce qui la distingue — le fait d'avoir déjà été
 * journalisée, notamment.
 */
public final class AssetEntry {

    private final String path;
    private final int format;
    private final AssetKey key;
    private final byte[] content;
    private final long assetId;

    private AssetState state = AssetState.DISCOVERED;
    private int jobId;
    private long compiledSize;
    private boolean logged;

    AssetEntry(String path, int format, AssetKey key, byte[] content) {
        this.path = path;
        this.format = format;
        this.key = key;
        this.content = content;
        this.assetId = fnv1a64(path);
    }

    /**
     * Empreinte FNV-1a 64 bits d'un chemin (DM-01).
     *
     * <p>C'est la même fonction que celle qui nomme les nodes côté natif : deux
     * empreintes différentes du même identifiant produiraient deux assets là où
     * l'auteur en voit un.
     */
    private static long fnv1a64(String value) {
        long hash = 0xcbf29ce484222325L;
        for (byte octet : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            hash ^= (octet & 0xFFL);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /** {@return le chemin de la ressource} */
    public String path() {
        return path;
    }

    /** {@return le code de format, voir {@link SourceFormats}} */
    public int format() {
        return format;
    }

    /** {@return la clé de cache, ou {@code null} si la source est illisible} */
    public AssetKey key() {
        return key;
    }

    /** {@return le contenu de la source} */
    public byte[] content() {
        return content;
    }

    /** {@return l'identifiant natif de l'asset} */
    public long assetId() {
        return assetId;
    }

    /** {@return l'état courant} */
    public AssetState state() {
        return state;
    }

    /** {@return l'identifiant du travail de compilation, ou zéro} */
    public int jobId() {
        return jobId;
    }

    /** {@return la taille de l'asset compilé, en octets} */
    public long compiledSize() {
        return compiledSize;
    }

    /**
     * Fait passer l'entrée dans un nouvel état.
     *
     * <p>Une transition hors de SM-01 est refusée plutôt qu'appliquée : sauter
     * un état signifierait sauter ce qu'il garantit — la validation de C-22,
     * pour ne citer qu'elle — et personne ne s'en apercevrait avant que le
     * moteur ne lise des données qu'il n'a jamais contrôlées.
     *
     * @param next état visé
     * @return vrai si la transition a eu lieu
     */
    public boolean transitionTo(AssetState next) {
        if (!state.canTransitionTo(next)) {
            return false;
        }
        state = next;
        return true;
    }

    /**
     * Note qu'une compilation est partie.
     *
     * @param jobId identifiant du travail
     */
    void startCompiling(int jobId) {
        this.jobId = jobId;
        transitionTo(AssetState.COMPILING);
    }

    void setCompiledSize(long size) {
        this.compiledSize = size;
    }

    /**
     * Marque l'entrée comme journalisée et dit si c'était la première fois.
     *
     * <p>R-522 veut qu'un asset refusé soit journalisé une fois. Répéter son
     * message à chaque tick noierait tout le reste, et un journal qu'on
     * n'ouvre plus ne sert à rien.
     *
     * @return vrai si l'entrée n'avait pas encore été journalisée
     */
    boolean markLogged() {
        if (logged) {
            return false;
        }
        logged = true;
        return true;
    }

    @Override
    public String toString() {
        return path + " [" + state + "]";
    }
}
