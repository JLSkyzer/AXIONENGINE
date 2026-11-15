package dev.axion.attach;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Le graphe des attaches d'un serveur (ADR-171 D3) : qui est attaché à qui, par quels sockets. Logique pure, sans Forge.
 *
 * <ul>
 *   <li><strong>Acyclique</strong> (R-690) : deux assemblies déjà reliées, directement ou par une chaîne, ne s'attachent
 *       pas une seconde fois — refus {@code E-8030}. Le monde n'est pas un nœud : un ensemble peut s'ancrer plusieurs
 *       fois.</li>
 *   <li><strong>Chaîne bornée</strong> (R-691) : le plus long chemin d'un ensemble compte au plus {@code maxChain}
 *       attaches.</li>
 *   <li><strong>Un socket, une attache</strong> à la fois.</li>
 * </ul>
 *
 * <p>Un ensemble est une composante connexe : ce que la prédiction, le sommeil et le déchargement traitent comme un
 * tout (D7). Les attaches se rangent par identifiant ; les parcours suivent cet ordre (R-1020).
 */
public final class AttachmentGraph {

    /**
     * Une attache du graphe.
     *
     * @param id son identifiant, unique dans la session
     * @param a l'assembly de l'extrémité {@code a}
     * @param aSocket son socket
     * @param b l'assembly de l'extrémité {@code b}, ou {@code null} pour un ancrage au monde
     * @param bSocket son socket, ou {@code null} pour un ancrage
     */
    public record Link(int id, UUID a, String aSocket, UUID b, String bSocket) {

        /** {@return vrai si l'attache ancre {@code a} au monde} */
        public boolean anchored() {
            return b == null;
        }

        /** {@return vrai si {@code assembly} en est une extrémité} */
        public boolean touches(UUID assembly) {
            return a.equals(assembly) || assembly.equals(b);
        }

        /** {@return le socket de {@code assembly} dans cette attache, ou {@code null}} */
        public String socketOf(UUID assembly) {
            return a.equals(assembly) ? aSocket : assembly.equals(b) ? bSocket : null;
        }

        /** {@return l'autre extrémité vue de {@code assembly}, ou {@code null} pour un ancrage} */
        public UUID other(UUID assembly) {
            return a.equals(assembly) ? b : a;
        }
    }

    /** Pourquoi une attache est refusée. */
    public enum Refusal {
        /** Les deux extrémités sont la même assembly. */
        SELF,
        /** Un des deux sockets porte déjà une attache. */
        SOCKET_BUSY,
        /** Les deux assemblies sont déjà reliées : un cycle ({@code E-8030}, R-690). */
        CYCLE,
        /** L'ensemble dépasserait {@code attachment.max_chain} attaches de bout en bout (R-691). */
        CHAIN,
        /** L'identifiant est déjà pris. */
        DUPLICATE
    }

    private final Map<Integer, Link> links = new TreeMap<>();
    private final Map<UUID, Set<Integer>> byAssembly = new HashMap<>();

    /**
     * Juge une attache sans l'ajouter.
     *
     * @param link l'attache
     * @param maxChain le plus long chemin permis, en attaches
     * @return la raison du refus, ou vide si elle est permise
     */
    public Optional<Refusal> check(Link link, int maxChain) {
        if (links.containsKey(link.id())) {
            return Optional.of(Refusal.DUPLICATE);
        }
        if (link.a().equals(link.b())) {
            return Optional.of(Refusal.SELF);
        }
        if (socketLink(link.a(), link.aSocket()).isPresent()
                || (!link.anchored() && socketLink(link.b(), link.bSocket()).isPresent())) {
            return Optional.of(Refusal.SOCKET_BUSY);
        }
        if (link.anchored()) {
            return Optional.empty();
        }
        Set<UUID> ensemble = ensemble(link.a());
        if (ensemble.contains(link.b())) {
            return Optional.of(Refusal.CYCLE);
        }
        // Les deux composantes sont des arbres disjoints : le plus long chemin passant par la nouvelle attache est
        // l'excentricité de a dans la sienne, plus celle de b dans la sienne, plus un.
        int longest = Math.max(Math.max(diameter(link.a()), diameter(link.b())),
                eccentricity(link.a()) + eccentricity(link.b()) + 1);
        return longest > maxChain ? Optional.of(Refusal.CHAIN) : Optional.empty();
    }

    /**
     * Ajoute une attache, si {@link #check} la permet.
     *
     * @param link l'attache
     * @param maxChain le plus long chemin permis, en attaches
     * @return la raison du refus, ou vide si elle est ajoutée
     */
    public Optional<Refusal> add(Link link, int maxChain) {
        Optional<Refusal> refusal = check(link, maxChain);
        if (refusal.isEmpty()) {
            links.put(link.id(), link);
            byAssembly.computeIfAbsent(link.a(), key -> new LinkedHashSet<>()).add(link.id());
            if (!link.anchored()) {
                byAssembly.computeIfAbsent(link.b(), key -> new LinkedHashSet<>()).add(link.id());
            }
        }
        return refusal;
    }

    /**
     * Retire une attache.
     *
     * @param id son identifiant
     * @return l'attache retirée, ou vide si elle n'existait pas
     */
    public Optional<Link> remove(int id) {
        Link link = links.remove(id);
        if (link == null) {
            return Optional.empty();
        }
        forget(link.a(), id);
        if (!link.anchored()) {
            forget(link.b(), id);
        }
        return Optional.of(link);
    }

    /**
     * Retire toutes les attaches d'une assembly qui part.
     *
     * @param assembly l'assembly
     * @return les attaches retirées, dans l'ordre de leurs identifiants
     */
    public List<Link> removeAll(UUID assembly) {
        List<Link> removed = new ArrayList<>();
        for (Link link : linksOf(assembly)) {
            remove(link.id()).ifPresent(removed::add);
        }
        return removed;
    }

    /** {@return l'attache {@code id}, ou vide} */
    public Optional<Link> get(int id) {
        return Optional.ofNullable(links.get(id));
    }

    /** {@return les attaches dont {@code assembly} est une extrémité, dans l'ordre de leurs identifiants} */
    public List<Link> linksOf(UUID assembly) {
        Set<Integer> ids = byAssembly.get(assembly);
        if (ids == null) {
            return List.of();
        }
        List<Link> found = new ArrayList<>();
        for (Link link : links.values()) {
            if (ids.contains(link.id())) {
                found.add(link);
            }
        }
        return found;
    }

    /** {@return l'attache que porte le socket {@code socket} de {@code assembly}, ou vide} */
    public Optional<Link> socketLink(UUID assembly, String socket) {
        return linksOf(assembly).stream().filter(link -> socket.equals(link.socketOf(assembly))).findFirst();
    }

    /** {@return toutes les attaches, dans l'ordre de leurs identifiants} */
    public List<Link> all() {
        return List.copyOf(links.values());
    }

    /**
     * {@return l'ensemble de {@code assembly} : elle et toutes celles qui lui sont reliées, de proche en proche} Le
     * monde n'y entre pas. Une assembly sans attache forme un ensemble à elle seule.
     */
    public Set<UUID> ensemble(UUID assembly) {
        Set<UUID> seen = new LinkedHashSet<>();
        Deque<UUID> queue = new ArrayDeque<>();
        seen.add(assembly);
        queue.add(assembly);
        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            for (Link link : linksOf(current)) {
                UUID other = link.other(current);
                if (other != null && seen.add(other)) {
                    queue.add(other);
                }
            }
        }
        return Collections.unmodifiableSet(seen);
    }

    /** La plus grande distance, en attaches, de {@code assembly} à une assembly de son ensemble. */
    private int eccentricity(UUID assembly) {
        return farthest(assembly).distance();
    }

    /** Le plus long chemin de l'ensemble de {@code assembly}, en attaches : deux parcours suffisent sur un arbre. */
    private int diameter(UUID assembly) {
        return farthest(farthest(assembly).node()).distance();
    }

    private record Far(UUID node, int distance) {}

    private Far farthest(UUID start) {
        Map<UUID, Integer> distance = new HashMap<>();
        Deque<UUID> queue = new ArrayDeque<>();
        distance.put(start, 0);
        queue.add(start);
        Far best = new Far(start, 0);
        Set<UUID> visited = new HashSet<>();
        visited.add(start);
        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            int here = distance.get(current);
            if (here > best.distance()) {
                best = new Far(current, here);
            }
            for (Link link : linksOf(current)) {
                UUID other = link.other(current);
                if (other != null && visited.add(other)) {
                    distance.put(other, here + 1);
                    queue.add(other);
                }
            }
        }
        return best;
    }

    private void forget(UUID assembly, int id) {
        Set<Integer> ids = byAssembly.get(assembly);
        if (ids != null) {
            ids.remove(id);
            if (ids.isEmpty()) {
                byAssembly.remove(assembly);
            }
        }
    }
}
