package dev.axion.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Sous-allocation des meshes GPU dans des arènes de taille fixe (fiche 5.49, C-62, ADR-127 §3).
 *
 * <p>Une arène est une paire de tampons — sommets et indices — partagée par plusieurs meshes : un VAO
 * par arène, et un dessin par {@code glDrawElementsInstancedBaseVertex}, dont le décalage de base
 * désigne la place du mesh. Logique pure : une arène n'est ici qu'un numéro et deux capacités ; le
 * collage GL crée ses tampons à la première place qu'il y voit, et les détruit quand elle lui est
 * rendue vide.
 *
 * <p>Première place libre, arène après arène dans l'ordre de leur création ; une place rendue
 * fusionne avec ses voisines libres. Un mesh plus grand qu'une arène reçoit une paire à sa taille,
 * que personne d'autre ne partage. Un numéro d'arène ne resert jamais : un tampon détruit ne se
 * confond pas avec un neuf.
 *
 * <p>Sans synchronisation : render thread seul, comme tout ce qui touche au GPU (R-751).
 */
public final class ArenaAllocator {

    /** Taille de chacun des deux tampons d'une arène (fiche 5.49). */
    public static final long ARENA_BYTES = 16L << 20;

    /** Un sommet GPU (§19.4). */
    public static final int VERTEX_BYTES = 48;

    /** Un indice, {@code u32} (ADR-119). */
    public static final int INDEX_BYTES = 4;

    /**
     * Une arène.
     *
     * @param id numéro, jamais réutilisé
     * @param vertexCapacity taille du tampon de sommets, en octets
     * @param indexCapacity taille du tampon d'indices, en octets
     * @param dedicated vrai pour la paire d'un seul mesh, plus grand qu'une arène
     */
    public record Arena(int id, long vertexCapacity, long indexCapacity, boolean dedicated) {

        /** {@return la mémoire GPU que ses deux tampons occupent} */
        public long gpuBytes() {
            return vertexCapacity + indexCapacity;
        }
    }

    /**
     * La place d'un mesh.
     *
     * @param arena numéro de son arène
     * @param vertexOffset début de ses sommets dans le tampon de sommets, en octets
     * @param vertexBytes taille de ses sommets
     * @param indexOffset début de ses indices dans le tampon d'indices, en octets
     * @param indexBytes taille de ses indices
     */
    public record Allocation(int arena, long vertexOffset, long vertexBytes, long indexOffset, long indexBytes) {

        /** {@return le premier sommet du mesh dans son arène : le décalage de base de ses dessins} */
        public int baseVertex() {
            return Math.toIntExact(vertexOffset / VERTEX_BYTES);
        }
    }

    /** Une arène et ce qui s'y trouve. */
    private static final class Slots {
        final Arena arena;
        /** Places libres, par début, de chaque tampon. */
        final TreeMap<Long, Long> freeVertices = new TreeMap<>();
        final TreeMap<Long, Long> freeIndices = new TreeMap<>();
        /** Places occupées, par début des sommets — jamais vides, donc uniques. */
        final Map<Long, Allocation> live = new LinkedHashMap<>();

        Slots(Arena arena) {
            this.arena = arena;
            freeVertices.put(0L, arena.vertexCapacity());
            if (arena.indexCapacity() > 0) {
                freeIndices.put(0L, arena.indexCapacity());
            }
        }
    }

    private final long arenaBytes;
    private final Map<Integer, Slots> arenas = new LinkedHashMap<>();
    private int nextId;

    /** Des arènes de 16 Mio par tampon. */
    public ArenaAllocator() {
        this(ARENA_BYTES);
    }

    /**
     * @param arenaBytes taille de chaque tampon d'une arène, en octets
     * @throws IllegalArgumentException si elle n'est pas positive
     */
    public ArenaAllocator(long arenaBytes) {
        if (arenaBytes <= 0) {
            throw new IllegalArgumentException("arène de " + arenaBytes + " octets");
        }
        this.arenaBytes = arenaBytes;
    }

    /**
     * Place un mesh : dans la première arène où ses sommets et ses indices tiennent, sinon dans une
     * arène neuve, ou dans une paire à sa taille s'il dépasse une arène.
     *
     * @param vertexBytes taille de ses sommets, un multiple de {@link #VERTEX_BYTES}, non nul
     * @param indexBytes taille de ses indices, un multiple de {@link #INDEX_BYTES}
     * @return sa place
     * @throws IllegalArgumentException pour une taille qui n'est pas un nombre entier de sommets ou
     *     d'indices
     */
    public Allocation allocate(long vertexBytes, long indexBytes) {
        if (vertexBytes <= 0 || vertexBytes % VERTEX_BYTES != 0) {
            throw new IllegalArgumentException(vertexBytes + " octets : pas un nombre entier de sommets");
        }
        if (indexBytes < 0 || indexBytes % INDEX_BYTES != 0) {
            throw new IllegalArgumentException(indexBytes + " octets : pas un nombre entier d'indices");
        }
        if (vertexBytes > arenaBytes || indexBytes > arenaBytes) {
            Slots slots = open(vertexBytes, indexBytes, true);
            return take(slots, 0L, vertexBytes, 0L, indexBytes);
        }
        for (Slots slots : arenas.values()) {
            if (slots.arena.dedicated()) {
                continue;
            }
            Long vertexAt = firstFit(slots.freeVertices, vertexBytes);
            Long indexAt = indexBytes == 0 ? Long.valueOf(0L) : firstFit(slots.freeIndices, indexBytes);
            if (vertexAt != null && indexAt != null) {
                return take(slots, vertexAt, vertexBytes, indexAt, indexBytes);
            }
        }
        Slots slots = open(arenaBytes, arenaBytes, false);
        return take(slots, 0L, vertexBytes, 0L, indexBytes);
    }

    /**
     * Rend la place d'un mesh.
     *
     * @param allocation place rendue par {@link #allocate}
     * @return l'arène, si elle se retrouve vide : elle est oubliée, et ses tampons sont à détruire
     * @throws IllegalStateException pour une place inconnue ou déjà rendue
     */
    public Optional<Arena> free(Allocation allocation) {
        Slots slots = arenas.get(allocation.arena());
        Allocation live = slots == null ? null : slots.live.get(allocation.vertexOffset());
        if (live == null || !live.equals(allocation)) {
            throw new IllegalStateException("place inconnue ou déjà rendue : " + allocation);
        }
        slots.live.remove(allocation.vertexOffset());
        if (slots.live.isEmpty()) {
            arenas.remove(allocation.arena());
            return Optional.of(slots.arena);
        }
        giveBack(slots.freeVertices, allocation.vertexOffset(), allocation.vertexBytes());
        if (allocation.indexBytes() > 0) {
            giveBack(slots.freeIndices, allocation.indexOffset(), allocation.indexBytes());
        }
        return Optional.empty();
    }

    /**
     * Oublie toutes les arènes — rechargement de ressources (R-752), arrêt.
     *
     * @return les arènes oubliées, dont les tampons sont à détruire
     */
    public List<Arena> clear() {
        List<Arena> all = new ArrayList<>();
        for (Slots slots : arenas.values()) {
            all.add(slots.arena);
        }
        arenas.clear();
        return all;
    }

    /**
     * {@return une arène vivante}
     *
     * @param id son numéro
     * @throws IllegalStateException si elle n'existe pas, ou plus
     */
    public Arena arena(int id) {
        Slots slots = arenas.get(id);
        if (slots == null) {
            throw new IllegalStateException("arène " + id + " inconnue");
        }
        return slots.arena;
    }

    /** {@return le nombre d'arènes vivantes} */
    public int arenaCount() {
        return arenas.size();
    }

    /** {@return la mémoire GPU que les arènes vivantes occupent, toutes places comprises} */
    public long gpuBytes() {
        long bytes = 0;
        for (Slots slots : arenas.values()) {
            bytes += slots.arena.gpuBytes();
        }
        return bytes;
    }

    private Slots open(long vertexCapacity, long indexCapacity, boolean dedicated) {
        Slots slots = new Slots(new Arena(nextId++, vertexCapacity, indexCapacity, dedicated));
        arenas.put(slots.arena.id(), slots);
        return slots;
    }

    private static Allocation take(Slots slots, long vertexAt, long vertexBytes, long indexAt, long indexBytes) {
        carve(slots.freeVertices, vertexAt, vertexBytes);
        if (indexBytes > 0) {
            carve(slots.freeIndices, indexAt, indexBytes);
        }
        Allocation allocation = new Allocation(slots.arena.id(), vertexAt, vertexBytes, indexAt, indexBytes);
        slots.live.put(vertexAt, allocation);
        return allocation;
    }

    /** {@return le début de la première place libre d'au moins {@code bytes}, ou {@code null}} */
    private static Long firstFit(TreeMap<Long, Long> free, long bytes) {
        for (Map.Entry<Long, Long> place : free.entrySet()) {
            if (place.getValue() >= bytes) {
                return place.getKey();
            }
        }
        return null;
    }

    /** Prend {@code bytes} au début de la place libre qui commence en {@code at}. */
    private static void carve(TreeMap<Long, Long> free, long at, long bytes) {
        long size = free.remove(at);
        if (size > bytes) {
            free.put(at + bytes, size - bytes);
        }
    }

    /** Rend une place, fusionnée avec ses voisines libres. */
    private static void giveBack(TreeMap<Long, Long> free, long at, long bytes) {
        long start = at;
        long size = bytes;
        Map.Entry<Long, Long> before = free.floorEntry(at);
        if (before != null && before.getKey() + before.getValue() == at) {
            start = before.getKey();
            size += before.getValue();
            free.remove(before.getKey());
        }
        Long after = free.containsKey(at + bytes) ? at + bytes : null;
        if (after != null) {
            size += free.remove(after);
        }
        free.put(start, size);
    }
}
