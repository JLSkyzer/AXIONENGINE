package dev.axion.forge.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Les capteurs d'une connexion Minecraft, pour l'essai de la prédiction (contenu de développement) : le défaut ouvert de
 * la connexion au serveur dédié (ADR-168, précision 6c-B) — une première connexion sur deux environ expire, le serveur
 * disant avoir envoyé sa poignée de main, le client n'en recevant rien.
 *
 * <p>Branchés sur le tuyau Netty d'une connexion :
 *
 * <ul>
 *   <li>en tête ({@code axion_octets}) : chaque lecture et chaque écriture d'octets bruts, leur taille, et le résultat de
 *       chaque écriture — réussie, ou sa cause d'échec ;</li>
 *   <li>après le décodeur et l'encodeur ({@code axion_paquets}) : chaque paquet décodé et encodé, avec l'état de
 *       protocole de la connexion à cet instant ;</li>
 *   <li>l'ouverture, la fermeture, les exceptions, les événements (délai de lecture dépassé).</li>
 * </ul>
 *
 * <p>Tout se détaille jusqu'à l'entrée en jeu ; ensuite, une ligne par seconde de comptes, pour ne pas noyer le journal.
 */
public final class CapteurReseau {

    private static final Logger LOGGER = LoggerFactory.getLogger("AXION capteur");
    private static final String OCTETS = "axion_octets";
    private static final String PAQUETS = "axion_paquets";
    private static final AtomicInteger NUMEROS = new AtomicInteger();

    /**
     * Le remède de la course de connexion de Forge 47 : une connexion restée sans lecture inscrite la redemande à la main
     * (D:/BDC, note « Sous Forge 47 une course entre le fil de connexion et channelActive peut couper la lecture du
     * client à la connexion »). L'expérience qui a confirmé la cause, sous {@code -Daxion.essai.relance_lecture} ; le mod
     * la rattrape désormais lui-même (ADR-169, {@code ConnectionReadRescue}).
     */
    private static final boolean RELANCE = Boolean.getBoolean("axion.essai.relance_lecture");

    private CapteurReseau() {
        throw new AssertionError("classe utilitaire, non instanciable");
    }

    /**
     * Branche les capteurs sur la connexion, s'ils n'y sont pas déjà.
     *
     * @param connection la connexion
     * @param cote « serveur » ou « client », pour le journal
     * @return vrai s'ils viennent d'être branchés
     */
    public static boolean brancher(Connection connection, String cote) {
        Channel channel = connection.channel();
        if (channel == null) {
            return false;
        }
        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(OCTETS) != null) {
            return false;
        }
        Etat etat = new Etat(cote + "#" + NUMEROS.incrementAndGet(), channel);
        try {
            pipeline.addFirst(OCTETS, new Octets(etat));
            if (pipeline.get("decoder") != null) {
                pipeline.addAfter("decoder", PAQUETS + "_entrants", new Paquets(etat, true));
            }
            if (pipeline.get("encoder") != null) {
                pipeline.addAfter("encoder", PAQUETS + "_sortants", new Paquets(etat, false));
            }
        } catch (RuntimeException faute) {
            LOGGER.warn("[capteur] {} : branchement incomplet — {}", etat.nom, faute.toString());
        }
        LOGGER.info("[capteur] {} branché : {} -> {}, ouvert {}, actif {}, protocole {}, tuyau {}", etat.nom,
                channel.localAddress(), channel.remoteAddress(), channel.isOpen(), channel.isActive(),
                etat.protocole(), pipeline.names());
        return true;
    }

    /**
     * Sonde l'abonnement de la connexion à la lecture, une fois par seconde tant qu'elle n'est pas en jeu : {@code
     * autoRead}, la clé de sélection — valide, opérations demandées, opérations prêtes —, le fil de sa boucle et son
     * état. De quoi trancher entre une lecture jamais demandée et un sélecteur qui ne réveille pas sa boucle.
     *
     * @param connection la connexion, dont les capteurs sont branchés
     */
    public static void sonder(Connection connection) {
        Channel channel = connection.channel();
        if (channel == null || !(channel.pipeline().get(OCTETS) instanceof Octets octets)) {
            return;
        }
        Etat etat = octets.etat;
        long maintenant = System.nanoTime();
        if (etat.enJeu || maintenant - etat.derniereSonde < 1_000_000_000L) {
            return;
        }
        etat.derniereSonde = maintenant;
        String cle;
        try {
            java.lang.reflect.Method methode =
                    io.netty.channel.nio.AbstractNioChannel.class.getDeclaredMethod("selectionKey");
            methode.setAccessible(true);
            java.nio.channels.SelectionKey key = (java.nio.channels.SelectionKey) methode.invoke(channel);
            cle = key == null ? "aucune" : key.isValid()
                    ? "valide, demandées " + ops(key.interestOps()) + ", prêtes " + ops(key.readyOps())
                            + ", sélecteur " + key.selector().getClass().getSimpleName() + " à "
                            + key.selector().keys().size() + " clé(s)"
                    : "invalide";
        } catch (ReflectiveOperationException | RuntimeException faute) {
            cle = "illisible (" + faute + ")";
        }
        String boucle;
        try {
            java.lang.reflect.Field champ = io.netty.util.concurrent.SingleThreadEventExecutor.class
                    .getDeclaredField("thread");
            champ.setAccessible(true);
            Thread fil = (Thread) champ.get(channel.eventLoop());
            boucle = fil == null ? "sans fil" : fil.getName() + " " + fil.getState();
        } catch (ReflectiveOperationException | RuntimeException faute) {
            boucle = "illisible (" + faute + ")";
        }
        LOGGER.info("[capteur] {} sonde : autoRead {}, actif {}, protocole {}, {} octets lus, clé {}, boucle {}",
                etat.nom, channel.config().isAutoRead(), channel.isActive(), etat.protocole(), etat.octetsLus.get(), cle,
                boucle);
        if (RELANCE && etat.octetsLus.get() == 0 && channel.isActive() && channel.config().isAutoRead()
                && cle.startsWith("valide, demandées aucune") && maintenant - etat.branche > 1_000_000_000L) {
            LOGGER.warn("[capteur] {} : lecture jamais inscrite malgré autoRead — relance channel.read()", etat.nom);
            channel.read();
        }
    }

    private static String ops(int ops) {
        StringBuilder noms = new StringBuilder();
        if ((ops & java.nio.channels.SelectionKey.OP_READ) != 0) {
            noms.append("LECTURE ");
        }
        if ((ops & java.nio.channels.SelectionKey.OP_WRITE) != 0) {
            noms.append("ÉCRITURE ");
        }
        if ((ops & java.nio.channels.SelectionKey.OP_CONNECT) != 0) {
            noms.append("CONNEXION ");
        }
        if ((ops & java.nio.channels.SelectionKey.OP_ACCEPT) != 0) {
            noms.append("ACCEPTATION ");
        }
        return noms.length() == 0 ? "aucune" : noms.toString().trim();
    }

    /** Ce qu'une connexion a vu passer. */
    private static final class Etat {
        final String nom;
        final Channel channel;
        final AtomicLong octetsLus = new AtomicLong();
        final AtomicLong octetsEcrits = new AtomicLong();
        final AtomicLong paquetsLus = new AtomicLong();
        final AtomicLong paquetsEcrits = new AtomicLong();
        volatile boolean enJeu;
        volatile long derniereSynthese;
        volatile long derniereSonde;
        final long branche = System.nanoTime();

        Etat(String nom, Channel channel) {
            this.nom = nom;
            this.channel = channel;
        }

        String protocole() {
            ConnectionProtocol protocole = channel.attr(Connection.ATTRIBUTE_PROTOCOL).get();
            return protocole == null ? "aucun" : protocole.name();
        }

        boolean detaille() {
            if (!enJeu && "PLAY".equals(protocole())) {
                enJeu = true;
                LOGGER.info("[capteur] {} en jeu : {} octets lus, {} écrits, {} paquets lus, {} écrits ; la suite en"
                        + " synthèse", nom, octetsLus.get(), octetsEcrits.get(), paquetsLus.get(), paquetsEcrits.get());
            }
            return !enJeu;
        }

        void synthese() {
            long maintenant = System.nanoTime();
            if (enJeu && maintenant - derniereSynthese > 1_000_000_000L) {
                derniereSynthese = maintenant;
                LOGGER.info("[capteur] {} : {} octets lus, {} écrits, {} paquets lus, {} écrits", nom, octetsLus.get(),
                        octetsEcrits.get(), paquetsLus.get(), paquetsEcrits.get());
            }
        }
    }

    /** En tête du tuyau : les octets bruts. */
    private static final class Octets extends ChannelDuplexHandler {
        final Etat etat;

        Octets(Etat etat) {
            this.etat = etat;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            LOGGER.info("[capteur] {} actif", etat.nom);
            super.channelActive(ctx);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            LOGGER.info("[capteur] {} fermé : {} octets lus, {} écrits, {} paquets lus, {} écrits, protocole {}",
                    etat.nom, etat.octetsLus.get(), etat.octetsEcrits.get(), etat.paquetsLus.get(),
                    etat.paquetsEcrits.get(), etat.protocole());
            super.channelInactive(ctx);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ByteBuf octets) {
                long total = etat.octetsLus.addAndGet(octets.readableBytes());
                if (etat.detaille()) {
                    LOGGER.info("[capteur] {} lit {} octets (total {}), protocole {}", etat.nom, octets.readableBytes(),
                            total, etat.protocole());
                }
            }
            etat.synthese();
            super.channelRead(ctx, msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof ByteBuf octets) {
                int taille = octets.readableBytes();
                long total = etat.octetsEcrits.addAndGet(taille);
                if (etat.detaille()) {
                    LOGGER.info("[capteur] {} écrit {} octets (total {}), protocole {}, inscriptible {}", etat.nom,
                            taille, total, etat.protocole(), ctx.channel().isWritable());
                    promise.addListener(fait -> {
                        if (fait.isSuccess()) {
                            LOGGER.info("[capteur] {} : écriture de {} octets faite", etat.nom, taille);
                        } else {
                            LOGGER.warn("[capteur] {} : écriture de {} octets ÉCHOUÉE — {}", etat.nom, taille,
                                    String.valueOf(fait.cause()));
                        }
                    });
                }
            }
            super.write(ctx, msg, promise);
        }

        @Override
        public void flush(ChannelHandlerContext ctx) throws Exception {
            if (etat.detaille()) {
                LOGGER.info("[capteur] {} vide son tampon (flush)", etat.nom);
            }
            super.flush(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            LOGGER.warn("[capteur] {} exception : {}", etat.nom, String.valueOf(cause));
            super.exceptionCaught(ctx, cause);
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            LOGGER.info("[capteur] {} événement : {}", etat.nom, evt);
            super.userEventTriggered(ctx, evt);
        }
    }

    /** Après le décodeur ou l'encodeur : les paquets. */
    private static final class Paquets extends ChannelDuplexHandler {
        private final Etat etat;
        private final boolean entrants;

        Paquets(Etat etat, boolean entrants) {
            this.etat = etat;
            this.entrants = entrants;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (entrants) {
                etat.paquetsLus.incrementAndGet();
                if (etat.detaille()) {
                    LOGGER.info("[capteur] {} décode {}, protocole {}", etat.nom, msg.getClass().getSimpleName(),
                            etat.protocole());
                }
            }
            super.channelRead(ctx, msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (!entrants) {
                etat.paquetsEcrits.incrementAndGet();
                if (etat.detaille()) {
                    LOGGER.info("[capteur] {} encode {}, protocole {}", etat.nom, msg.getClass().getSimpleName(),
                            etat.protocole());
                }
            }
            super.write(ctx, msg, promise);
        }
    }
}
