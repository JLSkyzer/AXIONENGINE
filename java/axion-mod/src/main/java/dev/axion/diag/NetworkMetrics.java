package dev.axion.diag;

/**
 * Les métriques de la synchronisation réseau (C-51), publiées dans l'export de {@code /axion metrics}.
 *
 * <p>La fiche C-51 renvoie à la PARTIE 21, qui n'en nomme aucune ; R-500 veut, pour chaque composant, ses
 * opérations, ses erreurs, sa mémoire et sa durée, et 31.1 les octets par type de paquet :
 *
 * <ul>
 *   <li>opérations — snapshots envoyés, octets de snapshot et de registre, acquittements reçus ;
 *   <li>erreurs — paquets refusés, de part et d'autre (E-5001), et trames du client ignorées par la
 *       limitation (R-1690) ;
 *   <li>mémoire — joueurs synchronisés, assemblies suivies pour eux ;
 *   <li>durée — l'envoi des snapshots d'un tick, tous joueurs compris.
 * </ul>
 *
 * <p>Le débit réseau par joueur est un budget de la table 25.2 ({@code net.max_bytes_per_second_per_player},
 * R-1850). Sa consommation est celle du joueur le plus servi sur les vingt derniers ticks. Le plafond retient
 * l'envoi, et la consommation ne le franchit que d'une entrée au plus — la première d'un snapshot part dès
 * qu'il reste du débit : le dépassement compté est donc celui de la demande, un tick où des entrées ont dû
 * attendre (R-1664). Le comportement en surcharge est ce retard, servi par ancienneté ; le repli, la fréquence
 * réduite d'une assembly endormie (R-1663).
 */
public final class NetworkMetrics {

    /** Préfixe des métriques réseau. */
    public static final String PREFIX = "axion.net.";

    /** Le budget de débit par joueur (25.2). */
    public static final String BANDWIDTH_BUDGET = "net.max_bytes_per_second_per_player";

    /** Les métriques du jeu, dans le registre global. */
    public static final NetworkMetrics GLOBAL = new NetworkMetrics(JavaMetrics.GLOBAL);

    /**
     * Ce que l'envoi d'un tick a fait, sur tous les joueurs.
     *
     * @param players joueurs synchronisés
     * @param tracked assemblies suivies, sommées sur les joueurs
     * @param snapshots snapshots envoyés
     * @param bytes octets de ces snapshots
     * @param delayed entrées retardées par le plafond
     * @param busiestBytesPerSecond octets envoyés au joueur le plus servi, sur les vingt derniers ticks
     * @param nanos durée de l'envoi, en nanosecondes
     * @param limited trames des clients ignorées par la limitation depuis le tick précédent (R-1690)
     */
    public record Tick(int players, int tracked, int snapshots, long bytes, long delayed, long busiestBytesPerSecond,
            long nanos, long limited) {}

    private final JavaMetrics.Metric snapshots;
    private final JavaMetrics.Metric snapshotBytes;
    private final JavaMetrics.Metric registryBytes;
    private final JavaMetrics.Metric delayed;
    private final JavaMetrics.Metric acks;
    private final JavaMetrics.Metric rejected;
    private final JavaMetrics.Metric limited;
    private final JavaMetrics.Metric players;
    private final JavaMetrics.Metric tracked;
    private final JavaMetrics.Metric sendNanos;
    private final JavaMetrics.Budget bandwidth;

    /**
     * Déclare les métriques réseau.
     *
     * @param metrics registre où les publier
     */
    public NetworkMetrics(JavaMetrics metrics) {
        snapshots = metrics.counter(PREFIX + "snapshots_sent", "count");
        snapshotBytes = metrics.counter(PREFIX + "snapshot_bytes_sent", "bytes");
        registryBytes = metrics.counter(PREFIX + "registry_bytes_sent", "bytes");
        delayed = metrics.counter(PREFIX + "entries_delayed", "count");
        acks = metrics.counter(PREFIX + "acks_received", "count");
        rejected = metrics.counter(PREFIX + "packets_rejected", "count");
        limited = metrics.counter(PREFIX + "packets_limited", "count");
        players = metrics.gauge(PREFIX + "players_synced", "count");
        tracked = metrics.gauge(PREFIX + "assemblies_tracked", "count");
        sendNanos = metrics.gauge(PREFIX + "send_ns", "ns");
        bandwidth = metrics.budget(BANDWIDTH_BUDGET, "bytes_per_second");
    }

    /**
     * Rapporte l'envoi d'un tick.
     *
     * @param tick ce qu'il a fait
     */
    public void tick(Tick tick) {
        snapshots.add(tick.snapshots());
        snapshotBytes.add(tick.bytes());
        delayed.add(tick.delayed());
        limited.add(tick.limited());
        players.set(tick.players());
        tracked.set(tick.tracked());
        sendNanos.set(Math.max(0, tick.nanos()));
        bandwidth.consumed().set(tick.busiestBytesPerSecond());
        if (tick.delayed() > 0) {
            bandwidth.overruns().increment();
        }
    }

    /**
     * Des trames de registre sont parties, à la connexion ou en jeu.
     *
     * @param bytes leurs octets
     */
    public void registrySent(long bytes) {
        registryBytes.add(bytes);
    }

    /** Un acquittement est arrivé d'un client. */
    public void ackReceived() {
        acks.increment();
    }

    /**
     * Des paquets ont été refusés (E-5001), d'un côté ou de l'autre.
     *
     * @param count leur nombre
     */
    public void rejected(int count) {
        rejected.add(count);
    }
}
