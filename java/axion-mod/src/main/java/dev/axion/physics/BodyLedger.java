package dev.axion.physics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Quelles assemblies ont un corps natif, et lesquelles l'attendent de nouveau (C-40 ↔ C-50).
 *
 * <p>Le corps d'une assembly naît à son entrée au monde et meurt à sa sortie. Sur Forge 47, les
 * deux crochets ne se répondent pas : l'entrée n'est signalée qu'à l'<b>ajout</b> de l'entité, la
 * sortie à chaque <b>fin de suivi</b> — retrait, mais aussi passage de son tronçon à la
 * visibilité cachée. Un tronçon qui redevient visible avant d'être déchargé fait suivre de
 * nouveau la même entité, sans entrée (relevé dans le bytecode de Forge 47.4.23) : sans
 * réconciliation, elle resterait sans corps, figée.
 *
 * <p>Une sortie sans retrait met donc l'entité de côté. À chaque tick, {@link #reconcile} oublie
 * celles qui ont été retirées depuis — déchargées avec leur tronçon — et rend celles qu'on suit
 * de nouveau : l'appelant leur recrée un corps. Ni le retrait ni le suivi ne se lisent ici : une
 * {@link Probe} les dit, si bien que la règle se teste sans Minecraft. Fil autoritaire seul.
 *
 * @param <E> l'entité d'une assembly
 */
public final class BodyLedger<E> {

    /** Ce qu'on sait d'une entité mise de côté, au moment de la réconcilier. */
    public interface Probe<E> {

        /**
         * {@return vrai si l'entité a été retirée du monde — tuée, déchargée, changée de
         * dimension}
         *
         * @param entity entité mise de côté
         */
        boolean removed(E entity);

        /**
         * {@return vrai si le monde la suit de nouveau}
         *
         * @param index index de son handle
         * @param entity entité mise de côté
         */
        boolean tracked(int index, E entity);
    }

    /** Assemblies dotées d'un corps, par index de handle. */
    private final Map<Integer, E> bodies = new HashMap<>();

    /** Assemblies sorties sans être retirées : leur corps est rendu, elles peuvent revenir. */
    private final Map<Integer, E> aside = new HashMap<>();

    /**
     * Note qu'une assembly a reçu un corps.
     *
     * @param index index de son handle
     * @param entity son entité
     */
    public void attach(int index, E entity) {
        aside.remove(index);
        bodies.put(index, entity);
    }

    /**
     * Note la sortie d'une assembly.
     *
     * @param index index de son handle
     * @param entity son entité
     * @param removed vrai si elle a quitté le monde ; faux si elle n'est plus suivie, seulement
     * @return vrai si elle avait un corps, que l'appelant doit rendre
     */
    public boolean detach(int index, E entity, boolean removed) {
        if (bodies.remove(index) == null) {
            return false;
        }
        if (!removed) {
            aside.put(index, entity);
        }
        return true;
    }

    /**
     * {@return l'entité dotée de ce corps, ou {@code null}}
     *
     * @param index index du handle
     */
    public E entity(int index) {
        return bodies.get(index);
    }

    /**
     * {@return vrai si l'assembly a un corps}
     *
     * @param index index du handle
     */
    public boolean hasBody(int index) {
        return bodies.containsKey(index);
    }

    /**
     * Réconcilie les assemblies mises de côté : oublie celles qui ont été retirées, et rend
     * celles que le monde suit de nouveau, à qui l'appelant recrée un corps — puis les note par
     * {@link #attach}.
     *
     * @param probe ce qu'on sait de chaque entité
     * @return les assemblies suivies sans corps, par index de handle croissant
     */
    public List<Map.Entry<Integer, E>> reconcile(Probe<E> probe) {
        List<Map.Entry<Integer, E>> returning = new ArrayList<>();
        Iterator<Map.Entry<Integer, E>> entries = aside.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Integer, E> entry = entries.next();
            if (probe.removed(entry.getValue())) {
                entries.remove();
            } else if (probe.tracked(entry.getKey(), entry.getValue())) {
                entries.remove();
                returning.add(Map.entry(entry.getKey(), entry.getValue()));
            }
        }
        returning.sort(Map.Entry.comparingByKey());
        return returning;
    }

    /**
     * Visite les assemblies dotées d'un corps, par index de handle croissant : un ordre
     * déterministe, qui ne dépend pas de l'histoire du tableau.
     *
     * @param visitor reçoit l'index du handle et l'entité
     */
    public void forEachBody(BiConsumer<Integer, E> visitor) {
        bodies.keySet().stream().sorted().forEach(index -> visitor.accept(index, bodies.get(index)));
    }

    /** {@return le nombre d'assemblies mises de côté, en attente d'un retour ou d'un retrait} */
    public int aside() {
        return aside.size();
    }

    /** Oublie tout (arrêt du serveur). */
    public void clear() {
        bodies.clear();
        aside.clear();
    }
}
