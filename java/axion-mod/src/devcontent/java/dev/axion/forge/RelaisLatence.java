package dev.axion.forge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Un relais TCP qui retarde chaque octet d'un délai fixe, dans chaque sens (ADR-168, essai à deux processus) : le
 * client s'y connecte au lieu du serveur, et vit la latence d'un serveur distant. L'ordre des octets est gardé, rien
 * ne se perd. Contenu de développement, jamais dans le mod livré.
 */
final class RelaisLatence implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("AXION essai");

    /** Un morceau lu, et l'heure à laquelle il part. */
    private record Morceau(long depart, byte[] octets) {}

    private final ServerSocket ecoute;
    private final String hote;
    private final int port;
    private final long delaiNanos;
    private final long gigueNanos;
    /** Graine fixe : deux essais de même réglage subissent la même suite de retards. */
    private final java.util.Random hasard = new java.util.Random(168L);
    private volatile boolean ferme;

    /**
     * Ouvre le relais sur l'adresse locale et commence à accepter.
     *
     * @param portLocal le port où le client se connecte
     * @param hote l'hôte du serveur
     * @param port son port
     * @param delaiMs le délai d'un sens, en millisecondes : l'aller-retour en ajoute le double
     * @param gigueMs la gigue : chaque morceau attend de 0 à tant de millisecondes de plus, au hasard ; un morceau ne
     *     part jamais avant celui d'avant, comme sur TCP, où un retard retient ce qui suit
     * @throws IOException si le port local est pris
     */
    RelaisLatence(int portLocal, String hote, int port, long delaiMs, long gigueMs) throws IOException {
        // L'adresse IPv4 explicite : getLoopbackAddress() rend ::1 dans le jeu, où le client se connecte en IPv4.
        this.ecoute = new ServerSocket(portLocal, 4, InetAddress.getByName("127.0.0.1"));
        this.hote = hote;
        this.port = port;
        this.delaiNanos = delaiMs * 1_000_000L;
        this.gigueNanos = gigueMs * 1_000_000L;
        Thread accepte = new Thread(this::accepter, "axion-relais-latence");
        accepte.setDaemon(true);
        accepte.start();
        LOGGER.info("[essai] relais de latence : 127.0.0.1:{} → {}:{}, {} ms par sens, gigue de 0 à {} ms", portLocal,
                hote, port, delaiMs, gigueMs);
    }

    private void accepter() {
        while (!ferme) {
            try {
                Socket client = ecoute.accept();
                Socket serveur = new Socket(hote, port);
                client.setTcpNoDelay(true);
                serveur.setTcpNoDelay(true);
                relier(client.getInputStream(), serveur.getOutputStream(), "montant", client, serveur);
                relier(serveur.getInputStream(), client.getOutputStream(), "descendant", client, serveur);
            } catch (IOException faute) {
                if (!ferme) {
                    LOGGER.warn("[essai] relais de latence : {}", faute.toString());
                }
            }
        }
    }

    /** Un sens : un fil lit et date, un autre attend l'heure et écrit, dans l'ordre. */
    private void relier(InputStream depuis, OutputStream vers, String sens, Socket a, Socket b) {
        BlockingQueue<Morceau> file = new LinkedBlockingQueue<>();
        // Le relais dit au journal le premier morceau de chaque sens et son bilan à la fermeture : un essai qui échoue
        // à se connecter dit ainsi si les octets ont passé.
        long[] lus = {0};
        long[] ecrits = {0};
        long[] dernierDepart = {0};
        Thread lit = new Thread(() -> {
            byte[] tampon = new byte[16384];
            String fin = "fin du flux";
            try {
                int n;
                while ((n = depuis.read(tampon)) >= 0) {
                    if (lus[0] == 0) {
                        LOGGER.info("[essai] relais {} : premier morceau lu, {} octets", sens, n);
                    }
                    lus[0] += n;
                    long depart = System.nanoTime() + delaiNanos + gigue();
                    depart = Math.max(depart, dernierDepart[0]);
                    dernierDepart[0] = depart;
                    file.put(new Morceau(depart, Arrays.copyOf(tampon, n)));
                }
            } catch (IOException | InterruptedException faute) {
                fin = faute.toString();
            }
            LOGGER.info("[essai] relais {} : lecture close ({}), {} octets lus", sens, fin, lus[0]);
            fermer(a, b);
        }, "axion-relais-" + sens + "-lecture");
        Thread ecrit = new Thread(() -> {
            String fin = "connexion fermée";
            try {
                while (!a.isClosed() || !file.isEmpty()) {
                    Morceau morceau = file.take();
                    long reste = morceau.depart() - System.nanoTime();
                    if (reste > 0) {
                        Thread.sleep(reste / 1_000_000L, (int) (reste % 1_000_000L));
                    }
                    vers.write(morceau.octets());
                    vers.flush();
                    if (ecrits[0] == 0) {
                        LOGGER.info("[essai] relais {} : premier morceau écrit, {} octets", sens, morceau.octets().length);
                    }
                    ecrits[0] += morceau.octets().length;
                }
            } catch (IOException | InterruptedException faute) {
                fin = faute.toString();
            }
            LOGGER.info("[essai] relais {} : écriture close ({}), {} octets écrits", sens, fin, ecrits[0]);
            fermer(a, b);
        }, "axion-relais-" + sens + "-ecriture");
        lit.setDaemon(true);
        ecrit.setDaemon(true);
        lit.start();
        ecrit.start();
    }

    /** {@return un retard de plus, de 0 à la gigue, en nanosecondes} Tiré par les deux sens. */
    private long gigue() {
        if (gigueNanos <= 0) {
            return 0;
        }
        synchronized (hasard) {
            return (long) (hasard.nextDouble() * gigueNanos);
        }
    }

    private static void fermer(Socket a, Socket b) {
        try {
            a.close();
        } catch (IOException ignoree) {
            // Déjà fermée.
        }
        try {
            b.close();
        } catch (IOException ignoree) {
            // Déjà fermée.
        }
    }

    @Override
    public void close() throws IOException {
        ferme = true;
        ecoute.close();
    }
}
