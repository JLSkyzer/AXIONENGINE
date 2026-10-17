package dev.axion.render;

/**
 * Le temps GPU des passes du backend natif (§31.1, « GPU par passe »), lu sans jamais attendre le
 * GPU.
 *
 * <p>Chaque passe est encadrée par deux horodatages GPU — {@code glQueryCounter} sur
 * {@code GL_TIMESTAMP} — dont l'écart est le temps que le GPU a passé sur ses commandes. Une requête
 * {@code GL_TIME_ELAPSED} ne convient pas : Minecraft en tient une ouverte sur toute la frame quand
 * l'écran F3 montre l'usage du GPU ({@code TimerQuery}), il n'en peut être active qu'une à la fois, et
 * fermer la nôtre terminerait la sienne (ADR-146 §2).
 *
 * <p>Le résultat d'un horodatage n'est connu que quelques frames plus tard. Chaque passe garde donc un
 * anneau de {@link #DEPTH} paires : un relevé n'est lu qu'une fois prêt, et le plus récent prêt fait
 * foi. Une paire encore en vol quand l'anneau revient sur elle est reprise, et son relevé compté perdu
 * — le render thread n'attend jamais le GPU.
 *
 * <p>Render thread seul (R-751).
 */
public final class GpuPassTimings {

    /** Les requêtes d'horodatage du contexte GL. */
    public interface Queries {

        /** {@return une requête neuve, de nom non nul} */
        int create();

        /**
         * Pose un horodatage GPU dans le flux de commandes.
         *
         * @param query la requête
         */
        void stamp(int query);

        /**
         * Ne bloque jamais.
         *
         * @param query une requête déjà horodatée
         * @return vrai si son résultat est connu
         */
        boolean ready(int query);

        /**
         * @param query une requête prête
         * @return son horodatage, en nanosecondes
         */
        long nanos(int query);

        /**
         * Détruit la requête.
         *
         * @param query la requête
         */
        void delete(int query);
    }

    /** Les passes mesurées : celles du backend natif qui dessinent elles-mêmes, en GL. */
    public enum Pass {
        /** Passes 1, 2 et 5 du §19.10 : surfaces opaques, découpées, émission. */
        OPAQUE,
        /** Passe 4 : surfaces translucides. */
        TRANSLUCENT
    }

    /** Paires par passe : le nombre de frames qu'un relevé peut attendre son résultat. */
    public static final int DEPTH = 4;

    private final Queries queries;
    private final Ring[] rings = new Ring[Pass.values().length];
    private long dropped;

    /**
     * @param queries requêtes d'horodatage du contexte
     */
    public GpuPassTimings(Queries queries) {
        this.queries = queries;
        for (int i = 0; i < rings.length; i++) {
            rings[i] = new Ring();
        }
    }

    /**
     * Ouvre la mesure d'une passe ; lit d'abord, sans attendre, ce que ses passes précédentes ont
     * rendu.
     *
     * @param pass la passe
     */
    public void begin(Pass pass) {
        Ring ring = rings[pass.ordinal()];
        ring.collect();
        ring.begin();
    }

    /**
     * Ferme la mesure d'une passe.
     *
     * @param pass la passe
     * @throws IllegalStateException si la passe n'a pas été ouverte
     */
    public void end(Pass pass) {
        rings[pass.ordinal()].end();
    }

    /** {@return le dernier temps GPU connu de la passe, en nanosecondes ; zéro avant le premier} */
    public long lastNanos(Pass pass) {
        return rings[pass.ordinal()].last;
    }

    /** {@return les relevés perdus : leur paire reprise avant que le GPU les ait rendus} */
    public long dropped() {
        return dropped;
    }

    /** Détruit toutes les requêtes. */
    public void close() {
        for (Ring ring : rings) {
            ring.close();
        }
    }

    /** L'anneau de paires d'une passe ; {@code next} désigne la plus ancienne. */
    private final class Ring {

        private final int[] starts = new int[DEPTH];
        private final int[] ends = new int[DEPTH];
        private final boolean[] inFlight = new boolean[DEPTH];
        private int next;
        private int open = -1;
        private long last;

        /** Lit les paires prêtes, de la plus ancienne à la plus récente : la dernière lue fait foi. */
        void collect() {
            for (int age = 0; age < DEPTH; age++) {
                int slot = (next + age) % DEPTH;
                if (inFlight[slot] && queries.ready(starts[slot]) && queries.ready(ends[slot])) {
                    last = Math.max(0, queries.nanos(ends[slot]) - queries.nanos(starts[slot]));
                    inFlight[slot] = false;
                }
            }
        }

        void begin() {
            int slot = next;
            if (inFlight[slot]) {
                dropped++;
                inFlight[slot] = false;
            }
            if (starts[slot] == 0) {
                starts[slot] = queries.create();
                ends[slot] = queries.create();
            }
            queries.stamp(starts[slot]);
            open = slot;
        }

        void end() {
            if (open < 0) {
                throw new IllegalStateException("mesure GPU fermée sans avoir été ouverte");
            }
            queries.stamp(ends[open]);
            inFlight[open] = true;
            next = (open + 1) % DEPTH;
            open = -1;
        }

        void close() {
            for (int slot = 0; slot < DEPTH; slot++) {
                if (starts[slot] != 0) {
                    queries.delete(starts[slot]);
                    queries.delete(ends[slot]);
                    starts[slot] = 0;
                    ends[slot] = 0;
                }
                inFlight[slot] = false;
            }
        }
    }
}
