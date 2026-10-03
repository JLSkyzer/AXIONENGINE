package dev.axion.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Capacités visuelles du backend de rendu retenu (R-1493, §19.2bis) — logique pure, sans
 * Forge.
 *
 * <p>Les deux backends partagent les invariants géométriques (R-1492) ; leurs capacités
 * visuelles diffèrent selon la matrice déclarée du §19.2bis. Chaque capacité y est dans l'un
 * de trois états : disponible ; indisponible dans ce backend — désactivée proprement, avec le
 * repli que la matrice prévoit ; ou pas encore livrée — le composant qui l'apportera est
 * nommé, plutôt que de la déclarer disponible avant qu'elle n'existe.
 *
 * <p>La même description sert à l'entrée de journal unique du démarrage du backend et à la
 * section « rendu » de {@code /axion status} (R-1493). L'indicateur de l'overlay de
 * diagnostic viendra avec lui (C-73).
 */
public final class RenderCapabilities {

    /**
     * Capacités de la matrice du §19.2bis, dans son ordre, et les cartes de matériau qu'ADR-122 §7
     * y déclare.
     */
    public enum Capability {
        /** Modèle d'éclairage. */
        LIGHTING("éclairage"),
        /** Ombres AXION (C-80). */
        SHADOWS("ombres AXION"),
        /** Décalques (C-69). */
        DECALS("décalques"),
        /** Parallax, clearcoat, sheen, anisotropie. */
        ADVANCED_MATERIALS("parallax, clearcoat, sheen, anisotropie"),
        /** Cartes normal, ORM, hauteur et dommage d'un matériau (ADR-122 §7). */
        MATERIAL_MAPS("cartes normal, ORM, hauteur et dommage"),
        /** Réflexions en espace écran (R-1560). */
        SSR("SSR"),
        /** Instancing et multi-draw indirect (C-65). */
        INSTANCING("instancing et multi-draw indirect"),
        /** Skinning et déformation. */
        SKINNING_DEFORMATION("skinning et déformation"),
        /** Occlusion culling logiciel (C-82). */
        OCCLUSION_CULLING("occlusion culling logiciel");

        private final String label;

        Capability(String label) {
            this.label = label;
        }

        /** {@return le nom de la capacité, tel que le journal et le rapport l'affichent} */
        public String label() {
            return label;
        }
    }

    /** État d'une capacité dans un backend. */
    public enum Availability {
        /** Disponible. */
        AVAILABLE,
        /** Indisponible dans ce backend : désactivée proprement, avec son repli. */
        UNAVAILABLE,
        /** Pas encore livrée : le composant qui l'apportera est nommé. */
        NOT_DELIVERED
    }

    /**
     * Une ligne de la matrice.
     *
     * @param capability capacité
     * @param availability son état dans le backend
     * @param detail sa forme si elle est disponible, son repli si elle ne l'est pas, ou ce qui
     *     l'apportera si elle n'est pas encore livrée
     */
    public record Entry(Capability capability, Availability availability, String detail) {}

    private final BackendSelection.Selection selection;
    private final List<Entry> entries;

    private RenderCapabilities(BackendSelection.Selection selection, List<Entry> entries) {
        this.selection = selection;
        this.entries = entries;
    }

    /**
     * {@return les capacités du backend retenu}
     *
     * @param selection backend retenu et raison de ce choix
     */
    public static RenderCapabilities of(BackendSelection.Selection selection) {
        List<Entry> entries = switch (selection.kind()) {
            case VANILLA -> vanilla();
            case NATIVE -> nativeGl();
        };
        return new RenderCapabilities(selection, entries);
    }

    /** Colonne VANILLA_CONSUMER du §19.2bis, ramenée à ce qui est livré. */
    private static List<Entry> vanilla() {
        return List.of(
                new Entry(Capability.LIGHTING, Availability.AVAILABLE,
                        "VANILLA_COMPAT (lightmap et ombrage des entités, pleine lumière sans éclairage,"
                                + " émissive additive), ou celui du shaderpack"),
                new Entry(Capability.SHADOWS, Availability.NOT_DELIVERED,
                        "ombre de contact, ou celles du shaderpack : C-80"),
                new Entry(Capability.DECALS, Availability.NOT_DELIVERED,
                        "quads translucides plafonnés (R-743) : C-69"),
                new Entry(Capability.ADVANCED_MATERIALS, Availability.UNAVAILABLE,
                        "ignorés, matériau rendu sans eux"),
                new Entry(Capability.MATERIAL_MAPS, Availability.UNAVAILABLE,
                        "sans effet, ni chargées : albedo et émissive seuls (R-1513)"),
                new Entry(Capability.SSR, Availability.UNAVAILABLE,
                        "jamais dans ce backend (R-1560)"),
                new Entry(Capability.INSTANCING, Availability.UNAVAILABLE,
                        "draw calls individuels"),
                new Entry(Capability.SKINNING_DEFORMATION, Availability.NOT_DELIVERED,
                        "sur CPU, plafonnés (R-742) : C-66, puis M6"),
                new Entry(Capability.OCCLUSION_CULLING, Availability.NOT_DELIVERED,
                        "indépendant du backend : C-82"));
    }

    /** Colonne NATIVE_GL : le backend natif n'est pas livré, rien n'y est déclaré disponible. */
    private static List<Entry> nativeGl() {
        List<Entry> entries = new ArrayList<>();
        for (Capability capability : Capability.values()) {
            entries.add(new Entry(capability, Availability.NOT_DELIVERED, "backend natif : C-60"));
        }
        return List.copyOf(entries);
    }

    /** {@return le backend retenu et la raison de ce choix} */
    public BackendSelection.Selection selection() {
        return selection;
    }

    /** {@return les lignes de la matrice, dans l'ordre du §19.2bis} */
    public List<Entry> entries() {
        return entries;
    }

    /**
     * {@return l'état d'une capacité dans ce backend}
     *
     * @param capability capacité
     */
    public Availability availability(Capability capability) {
        for (Entry entry : entries) {
            if (entry.capability() == capability) {
                return entry.availability();
            }
        }
        throw new IllegalStateException("capacité absente de la matrice : " + capability);
    }

    /**
     * {@return la description : le backend et sa raison, puis une ligne par état, pour les
     * seuls états représentés}
     *
     * <p>La même pour le journal et pour {@code /axion status} (R-1493).
     */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("backend " + selection.kind() + " (" + selection.reason() + ")");
        addGroup(lines, Availability.AVAILABLE, "disponibles");
        addGroup(lines, Availability.UNAVAILABLE, "indisponibles dans ce backend");
        addGroup(lines, Availability.NOT_DELIVERED, "pas encore livrées");
        return List.copyOf(lines);
    }

    private void addGroup(List<String> lines, Availability availability, String title) {
        List<String> items = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.availability() == availability) {
                items.add(entry.capability().label() + " — " + entry.detail());
            }
        }
        if (!items.isEmpty()) {
            lines.add(title + " : " + String.join(" ; ", items));
        }
    }
}
