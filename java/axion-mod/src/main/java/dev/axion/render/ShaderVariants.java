package dev.axion.render;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Les variantes d'un programme, compilées à la demande et bornées (R-762, ADR-127 §4) — logique
 * pure ; le collage GL compile ce qu'elle lui désigne, et lui dit quand c'est fait.
 *
 * <p>La variante de base est compilée au démarrage du backend : c'est la variante de secours, qui
 * dessine à la place d'une variante pas encore prête. Une variante demandée pour la première fois
 * entre en compilation si la borne le permet — {@code render.max_shader_variants}, la base comprise
 * —, sans attendre : la base dessine en attendant. Au-delà de la borne, elle n'est jamais compilée,
 * et la base la remplace pour de bon. Une variante dont la compilation échoue est perdue de même ;
 * R-761 fait alors basculer le backend sur vanilla.
 *
 * <p>Sans synchronisation : render thread seul, comme tout ce qui touche au GPU (INV-12).
 *
 * @param <P> un programme lié
 */
public final class ShaderVariants<P> {

    /** Où en est une variante. */
    public enum State {
        /** Demandée, en attente ou en cours de compilation : la base dessine à sa place. */
        PENDING,
        /** Compilée : elle dessine. */
        READY,
        /** Au-delà de la borne : jamais compilée, la base dessine à sa place. */
        REFUSED,
        /** Sa compilation a échoué : la base dessine à sa place, jusqu'à la bascule sur vanilla. */
        FAILED
    }

    private final int maxVariants;
    private final P base;
    private final Map<ShaderVariant, State> states = new LinkedHashMap<>();
    private final Map<ShaderVariant, P> programs = new LinkedHashMap<>();
    private final Deque<ShaderVariant> waiting = new ArrayDeque<>();

    /**
     * @param base le programme de la variante de base, compilé au démarrage
     * @param maxVariants {@code render.max_shader_variants} ; la base est toujours compilée, même
     *     sous une borne nulle
     */
    public ShaderVariants(P base, int maxVariants) {
        this.base = base;
        this.maxVariants = Math.max(1, maxVariants);
        states.put(ShaderVariant.BASE, State.READY);
        programs.put(ShaderVariant.BASE, base);
    }

    /**
     * {@return le programme qui dessine une variante à cette frame : le sien s'il est prêt, la base
     * sinon} Une variante demandée pour la première fois est mise en attente de compilation, ou
     * refusée au-delà de la borne.
     *
     * @param variant la variante voulue
     */
    public P program(ShaderVariant variant) {
        State state = states.get(variant);
        if (state == null) {
            if (counted() < maxVariants) {
                states.put(variant, State.PENDING);
                waiting.add(variant);
            } else {
                states.put(variant, State.REFUSED);
            }
            return base;
        }
        return state == State.READY ? programs.get(variant) : base;
    }

    /** {@return la prochaine variante à mettre en compilation, ou {@code null} : aucune n'attend} */
    public ShaderVariant nextToCompile() {
        return waiting.poll();
    }

    /**
     * Une variante est compilée : elle dessine désormais.
     *
     * @param variant la variante
     * @param program son programme
     * @throws IllegalStateException si elle n'était pas en compilation
     */
    public void ready(ShaderVariant variant, P program) {
        expectPending(variant);
        states.put(variant, State.READY);
        programs.put(variant, program);
    }

    /**
     * La compilation d'une variante a échoué : la base la remplace (R-761).
     *
     * @param variant la variante
     * @throws IllegalStateException si elle n'était pas en compilation
     */
    public void failed(ShaderVariant variant) {
        expectPending(variant);
        states.put(variant, State.FAILED);
    }

    /** {@return où en est une variante ; {@code null} pour une variante jamais demandée} */
    public State state(ShaderVariant variant) {
        return states.get(variant);
    }

    /** {@return les variantes dans cet état, dans l'ordre de leur première demande} */
    public List<ShaderVariant> inState(State state) {
        List<ShaderVariant> found = new ArrayList<>();
        for (Map.Entry<ShaderVariant, State> entry : states.entrySet()) {
            if (entry.getValue() == state) {
                found.add(entry.getKey());
            }
        }
        return found;
    }

    /** {@return les programmes compilés, la base comprise, à détruire avec le backend} */
    public List<P> programs() {
        return List.copyOf(programs.values());
    }

    /** {@return la borne : variantes compilées ou en compilation, la base comprise} */
    public int maxVariants() {
        return maxVariants;
    }

    /** Ce que compte la borne : les variantes compilées et celles en compilation. */
    private int counted() {
        int counted = 0;
        for (State state : states.values()) {
            if (state == State.READY || state == State.PENDING) {
                counted++;
            }
        }
        return counted;
    }

    private void expectPending(ShaderVariant variant) {
        if (states.get(variant) != State.PENDING) {
            throw new IllegalStateException("variante " + variant.key() + " pas en compilation : " + states.get(variant));
        }
    }
}
