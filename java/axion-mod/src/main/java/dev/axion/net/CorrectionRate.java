package dev.axion.net;

/**
 * Le taux de corrections qui coupe la prédiction (ADR-168 D8, R-1683) : la part des corrections qui ont démenti le
 * monde prédit — rejouées, ou acceptées sans rejeu faute de budget — sur une fenêtre glissante de {@link #WINDOW}
 * corrections.
 *
 * <ul>
 *   <li>Le taux ne compte qu'une fois la fenêtre pleine : quelques corrections ne disent rien d'un lien.</li>
 *   <li>Au-delà du seuil ({@code net.prediction_max_corrections}), la prédiction se coupe : elle coûte un rejeu à
 *       presque chaque tick et n'apporte plus qu'un véhicule qui se reprend sans cesse.</li>
 *   <li>Coupée, elle ne peut plus se mesurer : rien n'est prédit, rien n'est démenti. Elle se retente après
 *       {@link #WINDOW} corrections, sur une fenêtre vide — elle se rétablit d'elle-même si la cause a cessé, et se
 *       recoupe une fenêtre plus tard sinon.</li>
 *   <li>Un seuil de 1 ou plus ne coupe jamais.</li>
 * </ul>
 *
 * <p>Logique pure, sans Forge ni natif. Thread du client seul.
 */
public final class CorrectionRate {

    /** La fenêtre, en corrections : cinq secondes de conduite, une correction par tick. */
    public static final int WINDOW = 100;

    private final boolean[] window = new boolean[WINDOW];
    private int count;
    private int next;
    private int diverged;
    private int hold;
    private double cutRate = -1;

    /**
     * Note une correction réconciliée par le monde prédit.
     *
     * @param divergent vrai si elle l'a démenti : rejouée, ou acceptée sans rejeu
     */
    public void record(boolean divergent) {
        if (count == WINDOW) {
            diverged -= window[next] ? 1 : 0;
        } else {
            count++;
        }
        window[next] = divergent;
        diverged += divergent ? 1 : 0;
        next = (next + 1) % WINDOW;
    }

    /**
     * Note une correction reçue pendant que la prédiction est coupée : le délai avant de la retenter s'écoule.
     */
    public void whileCut() {
        if (hold > 0) {
            hold--;
        }
    }

    /**
     * {@return vrai si la prédiction doit être coupée pour son taux de corrections} Le taux qui l'a coupée se garde
     * pendant le délai ({@link #cutRate()}).
     *
     * @param max le seuil, une part des corrections de la fenêtre
     */
    public boolean cut(double max) {
        if (hold > 0) {
            return true;
        }
        if (count == WINDOW && rate() > max) {
            cutRate = rate();
            hold = WINDOW;
            count = 0;
            next = 0;
            diverged = 0;
            return true;
        }
        return false;
    }

    /** {@return la part des corrections de la fenêtre qui ont démenti la prédiction ; 0 tant qu'elle n'est pas pleine} */
    public double rate() {
        return count < WINDOW ? 0.0 : diverged / (double) WINDOW;
    }

    /** {@return le taux qui a coupé la prédiction en dernier, -1 sans coupure} */
    public double cutRate() {
        return cutRate;
    }
}
