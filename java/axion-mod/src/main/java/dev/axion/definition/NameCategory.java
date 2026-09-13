package dev.axion.definition;

/**
 * Catégorie d'un nom qu'une definition déclare ou désigne.
 *
 * <p>Les noms sont uniques <strong>par catégorie</strong> (ADR-109, point 7) :
 * une part et une zone peuvent s'appeler {@code hood}, deux parts non.
 *
 * <p>Une catégorie <em>interne</em> est déclarée par la definition elle-même,
 * et toute référence s'y résout dès la lecture. Les autres désignent un
 * élément de l'asset — node, mesh, région — et ne se résolvent que contre
 * l'asset compilé ; d'ici là, seule leur forme est vérifiée.
 */
enum NameCategory {
    /** Body physique, {@code bodies[].name}. */
    BODY("body", true),
    /** Joint, {@code joints[].name}. */
    JOINT("joint", true),
    /** Roue, désignée par son node, {@code wheels[].node}. */
    WHEEL("roue", true),
    /** Part, {@code parts[].name}. */
    PART("part", true),
    /** Liaison structurelle, {@code structural_links[].name}. */
    LINK("liaison structurelle", true),
    /** Zone de dommage, {@code damage_zones[].name}. */
    ZONE("zone de dommage", true),
    /** Siège, {@code seats[].name}. */
    SEAT("siège", true),
    /** Socket, {@code sockets[]}. */
    SOCKET("socket", true),
    /** Animation, clé de {@code animations}. */
    ANIMATION("animation", true),
    /** Ensemble de particules, {@code particles[].name}. */
    PARTICLES("ensemble de particules", true),
    /** Node de l'asset. */
    NODE("node", false),
    /** Mesh de l'asset. */
    MESH("mesh", false),
    /** Région de déformation, déclarée par l'asset ou générée par C-28. */
    REGION("région", false),
    /** Clip d'animation de l'asset. */
    CLIP("clip d'animation", false),
    /** Os du squelette de l'asset. */
    BONE("os", false),
    /** Collider de l'asset. */
    COLLIDER("collider", false);

    private final String label;
    private final boolean internal;

    NameCategory(String label, boolean internal) {
        this.label = label;
        this.internal = internal;
    }

    /** {@return le nom de la catégorie, pour un message} */
    String label() {
        return label;
    }

    /** {@return vrai si la definition déclare elle-même les noms de la catégorie} */
    boolean internal() {
        return internal;
    }
}
