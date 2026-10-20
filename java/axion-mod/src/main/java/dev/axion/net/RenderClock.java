package dev.axion.net;

import dev.axion.config.AxionConfig;

/**
 * Le temps de rendu du client (21.7) : le temps du serveur estimé, moins le retard d'interpolation que le
 * serveur annonce dans son handshake ; et le temps local, qui mesure les corrections.
 *
 * <p>Un monde solo en pause suspend aussi son serveur intégré : plus aucun snapshot n'arrive. L'estimation
 * continuerait pourtant d'avancer, l'affichage extrapolerait puis se figerait, et la reprise le ferait
 * sauter — l'estimation ne rejoint un serveur resté en arrière qu'une fois sa fenêtre écoulée. Le temps local
 * exclut donc les pauses : pour le client comme pour le serveur, elles n'ont pas duré.
 *
 * <p>Une instance par connexion, sur le thread du client.
 */
public final class RenderClock {

    private final ServerClock server = new ServerClock();
    private final double delayMillis;
    private long pausedNanos;
    private boolean paused;
    private long pausedSince;
    private long frameNanos;

    /**
     * @param delayMillis {@code net.interpolation_delay_ms} du serveur, tel que son handshake l'annonce
     */
    public RenderClock(double delayMillis) {
        this.delayMillis = delayMillis;
    }

    /**
     * L'horloge d'une connexion : au retard que le serveur annonce, ou, faute de handshake, à celui de la
     * configuration du client.
     *
     * @param server handshake du serveur, ou {@code null} s'il n'est pas arrivé
     * @param client configuration du client
     * @return l'horloge
     */
    public static RenderClock of(ServerHandshake server, AxionConfig client) {
        return new RenderClock(server != null
                ? server.interpolationDelayMs()
                : client.getInt("net.interpolation_delay_ms"));
    }

    /**
     * Note une image du client.
     *
     * @param nowNanos heure locale, en nanosecondes
     * @param inPause vrai si le jeu est en pause à cette image
     */
    public void onFrame(long nowNanos, boolean inPause) {
        if (inPause && !paused) {
            pausedSince = nowNanos;
        } else if (!inPause && paused) {
            pausedNanos += nowNanos - pausedSince;
        }
        paused = inPause;
        frameNanos = nowNanos;
    }

    /** {@return l'heure locale de la dernière image notée, en nanosecondes : les passes d'une image s'y datent} */
    public long frameNanos() {
        return frameNanos;
    }

    /**
     * Note l'arrivée d'un snapshot.
     *
     * @param serverTick tick du serveur qu'il porte
     * @param arrivalNanos heure locale de son arrivée, en nanosecondes
     */
    public void onSnapshot(long serverTick, long arrivalNanos) {
        server.onSnapshot(serverTick, localNanos(arrivalNanos));
    }

    /** {@return vrai si au moins un snapshot est arrivé} */
    public boolean known() {
        return server.known();
    }

    /**
     * @param nowNanos heure locale, en nanosecondes, croissante d'un appel à l'autre
     * @return le temps de rendu, en millisecondes du serveur ; {@code NaN} sans snapshot
     */
    public double renderMillis(long nowNanos) {
        return server.serverMillis(localNanos(nowNanos)) - delayMillis;
    }

    /**
     * @param nowNanos heure locale, en nanosecondes
     * @return le temps local, pauses exclues, en millisecondes
     */
    public double localMillis(long nowNanos) {
        return localNanos(nowNanos) / 1e6;
    }

    private long localNanos(long nowNanos) {
        return (paused ? pausedSince : nowNanos) - pausedNanos;
    }
}
