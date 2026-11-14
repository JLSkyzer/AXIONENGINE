package dev.axion.net;

/**
 * La cadence des entrées d'un client qui prédit (ADR-168 D3, révisé le 2026-11-14) : il tient lui-même la profondeur de
 * la file de son siège au serveur.
 *
 * <p>Le serveur applique une entrée par tick. Quand sa file est vide, il garde l'entrée d'avant ; quand elle déborde, il
 * en saute. Dans les deux cas il fait un pas de plus ou de moins que le monde prédit, qui se décale d'un tick. Une file
 * trop pleine, elle, ajoute un tick de latence par entrée en trop. Le client tient la file au plus juste : chaque
 * correction lui dit combien d'entrées elle tenait à ce tick, avant d'en prendre une ({@link CorrectionMessage#queued()}),
 * et il vise que la plus basse de ces profondeurs, sur une fenêtre, soit d'une entrée — celle du tick.
 *
 * <ul>
 *   <li><b>Une file trouvée vide</b> : au tick suivant, le client envoie deux entrées, et fait deux pas prédits. Il
 *       a décidé le pas de plus ; il le prédit, au lieu de le découvrir.</li>
 *   <li><b>Une fenêtre pleine dont la plus basse profondeur dépasse un</b> : l'excédent n'a servi à rien. Le client
 *       retient autant d'envois, un par tick, et ne fait pas de pas prédit à ces ticks.</li>
 *   <li><b>Un gros excédent</b> — {@link #FAST_DEPTH} entrées au moins sur {@link #FAST_WINDOW} corrections de suite,
 *       la rafale d'un client qui rattrape ses ticks après un à-coup, au chargement d'un modèle lourd par exemple — se
 *       retient sans attendre la fenêtre, jusqu'à deux entrées : c'est autant de latence prise à chaque entrée. Le
 *       seuil est haut, pour qu'une gigue n'y ressemble jamais ; la fenêtre ajuste ce qui reste.</li>
 *   <li>Une retenue n'a lieu que sur un tick tranquille — une entrée égale à la précédente — : une commande tapée en
 *       un tick ne se perd pas.</li>
 *   <li>Après un ajustement, les corrections des entrées d'avant ne comptent plus : elles disent l'état d'avant.</li>
 *   <li>Une file vide parce que le client lui-même a tardé — un à-coup, puis ses ticks rattrapés d'un coup, comme
 *       Minecraft les rattrape ({@link #sentLate}) — ne compte pas : le rattrapage la remplit de lui-même, et un pas
 *       de plus la creuserait d'autant.</li>
 *   <li>Une file trouvée vide après une retenue double la fenêtre suivante, jusqu'à {@link #MAX_WINDOW} : sur un lien
 *       qui tremble, le client garde sa marge au lieu d'osciller.</li>
 * </ul>
 *
 * <p>Logique pure, sans Forge ni natif. Thread du client seul.
 */
public final class InputPacing {

    /**
     * La première fenêtre, en corrections : deux secondes de conduite. Assez pour que la plus basse profondeur dise la
     * gigue du lien plutôt que le hasard d'un tick ; assez courte pour rendre vite une latence prise pour rien — une
     * file remplie pendant que le véhicule dormait, sans correction pour la mesurer.
     */
    public static final int BASE_WINDOW = 40;

    /**
     * Profondeur à partir de laquelle l'excédent se retient sans attendre la fenêtre : quatre entrées de trop, 200 ms
     * de latence — bien au-delà de ce qu'une gigue demande de marge.
     */
    public static final int FAST_DEPTH = 5;

    /** Corrections de suite qui la montrent avant de retenir : un quart de seconde. */
    public static final int FAST_WINDOW = 5;

    /** La plus longue fenêtre, en corrections : une minute. */
    public static final int MAX_WINDOW = 1200;

    private int window = BASE_WINDOW;
    /** Les corrections des entrées d'avant celle-ci disent l'état d'avant le dernier ajustement. */
    private long settled = -1;
    private int observed;
    private int smallest = Integer.MAX_VALUE;
    /** Les dernières corrections de suite à {@link #FAST_DEPTH} au moins, et leur plus basse profondeur. */
    private int deep;
    private int deepest = Integer.MAX_VALUE;
    /** Positif : des pas de plus à faire ; négatif : des envois à retenir ; 0 : rien. */
    private int wanted;
    private boolean lastWasWithhold;
    private long added;
    private long withheld;
    /** Les séquences parties en retard, par leur rang modulo la taille : un à-coup ne dure pas si longtemps. */
    private final long[] late = new long[LATE_KEPT];

    /** Séquences parties en retard dont le client se souvient. */
    private static final int LATE_KEPT = 128;

    /** Aucune séquence partie en retard au départ. */
    public InputPacing() {
        java.util.Arrays.fill(late, -1L);
    }

    /**
     * Une entrée part en retard : le client a tardé avant elle, ou elle rattrape un tick tardé.
     *
     * @param sequence sa séquence
     */
    public void sentLate(long sequence) {
        if (sequence >= 0) {
            late[(int) (sequence % LATE_KEPT)] = sequence;
        }
    }

    /**
     * Une correction arrive.
     *
     * @param appliedSequence la séquence qu'elle acquitte, -1 sans
     * @param queued les entrées que la file tenait à ce tick, avant d'en prendre une ; -1 sans
     */
    public void onCorrection(long appliedSequence, int queued) {
        if (appliedSequence < 0 || queued < 0 || appliedSequence < settled || wanted != 0) {
            return;
        }
        if (queued == 0) {
            if (late[(int) ((appliedSequence + 1) % LATE_KEPT)] == appliedSequence + 1) {
                // L'entrée qui manquait au serveur est partie en retard : le client a tardé, pas le lien.
                return;
            }
            if (lastWasWithhold) {
                window = Math.min(MAX_WINDOW, window * 2);
            }
            wanted = 1;
            return;
        }
        observed++;
        smallest = Math.min(smallest, queued);
        if (queued >= FAST_DEPTH) {
            deep++;
            deepest = Math.min(deepest, queued);
            if (deep >= FAST_WINDOW) {
                wanted = -(deepest - 2);
                return;
            }
        } else {
            deep = 0;
            deepest = Integer.MAX_VALUE;
        }
        if (observed >= window) {
            if (smallest >= 2) {
                wanted = -(smallest - 1);
            } else {
                restart();
            }
        }
    }

    /**
     * {@return combien d'entrées partent à ce tick : 1 d'ordinaire, 2 pour un pas de plus, 0 pour une retenue}
     *
     * @param nextSequence la séquence que portera la prochaine entrée
     * @param quiet vrai si l'entrée de ce tick est égale à la précédente : seule une entrée tranquille se retient
     */
    public int inputsThisTick(long nextSequence, boolean quiet) {
        if (wanted == 0 || (wanted < 0 && !quiet)) {
            return 1;
        }
        int count;
        if (wanted > 0) {
            count = 2;
            wanted--;
            added++;
            lastWasWithhold = false;
        } else {
            count = 0;
            wanted++;
            withheld++;
            lastWasWithhold = true;
        }
        restart();
        // La première entrée partie après l'ajustement est la première dont la correction le voit.
        settled = nextSequence + Math.max(count, 1);
        return count;
    }

    private void restart() {
        observed = 0;
        smallest = Integer.MAX_VALUE;
        deep = 0;
        deepest = Integer.MAX_VALUE;
    }

    /** {@return les pas ajoutés depuis la création} */
    public long added() {
        return added;
    }

    /** {@return les envois retenus depuis la création} */
    public long withheld() {
        return withheld;
    }

    /** {@return la fenêtre en cours, en corrections} */
    public int window() {
        return window;
    }
}
