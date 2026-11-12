package dev.axion.entity;

/**
 * Le débit d'un journal : une ligne par intervalle au plus, celles qui tombent entre deux comptées et dites avec la
 * suivante. Logique pure, sans Forge ; un par journal et par assembly, sur le thread autoritatif.
 *
 * <p>Le premier passage est toujours admis : aucun tick « d'avant » ne sert de sentinelle — {@code tick -
 * Integer.MIN_VALUE} déborderait et tairait tout.
 */
public final class JournalThrottle {

    private final int intervalTicks;
    private boolean logged;
    private int lastTick;
    private int hushed;

    /**
     * @param intervalTicks l'écart minimal entre deux lignes, en ticks
     */
    public JournalThrottle(int intervalTicks) {
        if (intervalTicks <= 0) {
            throw new IllegalArgumentException("intervalle positif attendu : " + intervalTicks);
        }
        this.intervalTicks = intervalTicks;
    }

    /**
     * {@return le nombre de lignes tues depuis la précédente si celle-ci peut s'écrire, {@code -1} si elle se tait}
     *
     * @param tick le tick courant
     */
    public int admit(int tick) {
        if (logged && (long) tick - lastTick < intervalTicks) {
            hushed++;
            return -1;
        }
        int silenced = hushed;
        logged = true;
        lastTick = tick;
        hushed = 0;
        return silenced;
    }
}
