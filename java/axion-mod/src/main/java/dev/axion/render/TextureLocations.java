package dev.axion.render;

import java.util.regex.Pattern;

/**
 * Noms des textures d'un asset : où lire une ressource, sous quel nom enregistrer une texture
 * (ADR-122 §2 et §7).
 */
public final class TextureLocations {

    /** Espace de noms des textures qu'AXION enregistre. */
    public static final String NAMESPACE = "axion";

    /** Ce qu'admet le chemin d'une {@code ResourceLocation}. */
    private static final Pattern RESOURCE_PATH = Pattern.compile("[a-z0-9/._-]+");

    private TextureLocations() {}

    /**
     * {@return la {@code ResourceLocation} d'une texture de ressource, côté client}
     *
     * <p>Le chemin est résolu contre le répertoire du modèle :
     * {@code ns:axion/models/voiture/voiture.gltf} et {@code tex/caisse.png} donnent
     * {@code ns:axion/models/voiture/tex/caisse.png}, lue dans {@code assets/…} — donc
     * remplaçable par un resource pack. Il est pris tel qu'écrit, sans décodage ni
     * normalisation, et contrôlé de nouveau (R-531) : le natif l'a admis, mais Java ne le croit
     * pas sur parole.
     *
     * @param asset clé de l'asset, {@code <ns>:<chemin>}
     * @param relative chemin de la texture, relatif au modèle
     * @throws IllegalArgumentException si le chemin est refusé, avec la raison
     */
    public static String resolveResource(String asset, String relative) {
        if (relative.isEmpty()
                || relative.startsWith("/")
                || relative.indexOf('\\') >= 0
                || relative.indexOf(':') >= 0) {
            throw new IllegalArgumentException(
                    "E-3002 : chemin « " + relative + " » refusé, absolu ou d'un autre volume (R-531)");
        }
        for (String segment : relative.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException(
                        "E-3002 : chemin « " + relative + " » refusé, segment vide ou remontant (R-531)");
            }
        }
        if (!RESOURCE_PATH.matcher(relative).matches()) {
            throw new IllegalArgumentException("chemin « " + relative + " » hors de [a-z0-9/._-] :"
                    + " une ResourceLocation ne l'admet pas — majuscules et espaces compris");
        }
        int colon = asset.indexOf(':');
        String path = asset.substring(colon + 1);
        String directory = path.substring(0, path.lastIndexOf('/') + 1);
        return asset.substring(0, colon) + ":" + directory + relative;
    }

    /**
     * {@return le nom sous lequel enregistrer une texture préparée}
     *
     * <p>{@code axion:texture/<asset>/<chargement>/<rang>}, suivi de {@code /cutout/<seuil>} pour
     * une variante binarisée, ou de {@code /masque/<rang de l'albedo>/<seuil>} pour une émissive
     * masquée — dont le rang est {@code blanc} sans texture d'émissive. Le numéro de chargement le
     * rend unique : la texture d'un chargement précédent, pas encore libérée, ne peut pas être
     * remplacée par erreur, puis libérée à la place de la nouvelle.
     *
     * @param asset clé de l'asset, {@code <ns>:<chemin>}
     * @param load numéro du chargement
     * @param key texture et variante
     */
    public static String registered(String asset, long load, TextureKey key) {
        String base = NAMESPACE + ":texture/" + asset.replace(':', '/') + "/" + load + "/"
                + (key.white() ? "blanc" : Integer.toString(key.rank()));
        String threshold = Integer.toHexString(Float.floatToIntBits(key.threshold()));
        if (key.masked()) {
            return base + "/masque/" + key.mask() + "/" + threshold;
        }
        return key.cutout() ? base + "/cutout/" + threshold : base;
    }

    /**
     * {@return le nom sous lequel enregistrer une page d'atlas}
     *
     * <p>{@code axion:texture/<asset>/<chargement>/atlas/<page>} : propre au chargement, comme les
     * textures individuelles, pour la même raison.
     *
     * @param asset clé de l'asset, {@code <ns>:<chemin>}
     * @param load numéro du chargement
     * @param page rang de la page parmi celles du chargement
     */
    public static String atlas(String asset, long load, int page) {
        return NAMESPACE + ":texture/" + asset.replace(':', '/') + "/" + load + "/atlas/" + page;
    }
}
