package dev.axion.net;

/**
 * Le rattrapage de la course de connexion de Forge 47 (ADR-169) : la décision seule, sans Netty ni Minecraft.
 *
 * <p>À la connexion d'un client, {@code Connection.sendPacket} coupe {@code autoRead} depuis le fil du connecteur, et
 * {@code setProtocol} le rétablit en tâche différée ; selon l'ordre des tâches, le retrait de la lecture que Netty met en
 * file passe après, et le socket du client n'écoute plus : le serveur envoie sa poignée de main dans le vide, la
 * connexion expire au bout de 30 s. Redemander la lecture la relance aussitôt.
 *
 * <p>La décision n'agit que sur la signature exacte de ce blocage, et son geste est sans effet s'il est répété :
 *
 * <ul>
 *   <li>canal actif, {@code autoRead} vrai, lecture absente des opérations demandées, depuis {@link #GRACE_NANOS} :
 *       la lecture se redemande, puis de nouveau chaque {@link #GRACE_NANOS} tant que la signature dure ;</li>
 *   <li>lecture inscrite — le cas normal, ou un autre mod qui l'a déjà rattrapée —, canal inactif, ou {@code autoRead}
 *       coupé à dessein : rien ;</li>
 *   <li>transport dont la clé ne se lit pas (natif, hors NIO) : la lecture se redemande chaque {@link #GRACE_NANOS} à
 *       partir de {@link #BLIND_NANOS} d'écran de connexion — redemander une lecture déjà demandée ne change rien.</li>
 * </ul>
 *
 * <p>Thread du client seul.
 */
public final class ReadRescue {

    /** Ce que la signature doit durer avant le geste : un blocage, pas un instant entre deux tâches de la boucle. */
    public static final long GRACE_NANOS = 1_000_000_000L;

    /** Sans clé lisible, l'écran de connexion qui dure ce temps sans entrer en jeu. */
    public static final long BLIND_NANOS = 3_000_000_000L;

    /** Ce que le canal laisse voir de son abonnement à la lecture. */
    public enum Read {
        /** La lecture est demandée au sélecteur. */
        REGISTERED,
        /** Elle ne l'est pas. */
        MISSING,
        /** Le transport ne le laisse pas lire. */
        UNKNOWN
    }

    private long since = -1;
    private long lastAction = -1;
    private int rescues;

    /**
     * Observe le canal de l'écran de connexion, à un tick.
     *
     * @param now l'heure, en nanosecondes
     * @param active le canal est actif
     * @param autoRead son {@code autoRead}
     * @param read son abonnement à la lecture
     * @return vrai si la lecture doit se redemander maintenant
     */
    public boolean observe(long now, boolean active, boolean autoRead, Read read) {
        if (since < 0) {
            since = now;
        }
        if (!active || !autoRead || read == Read.REGISTERED) {
            lastAction = -1;
            if (read == Read.REGISTERED) {
                since = now;
            }
            return false;
        }
        long wait = read == Read.MISSING ? GRACE_NANOS : BLIND_NANOS;
        boolean due = lastAction < 0 ? now - since >= wait : now - lastAction >= GRACE_NANOS;
        if (due) {
            lastAction = now;
            rescues++;
        }
        return due;
    }

    /** L'écran de connexion est fermé : la connexion suivante repart de zéro. */
    public void reset() {
        since = -1;
        lastAction = -1;
        rescues = 0;
    }

    /** {@return les gestes faits depuis le dernier {@link #reset}} */
    public int rescues() {
        return rescues;
    }
}
