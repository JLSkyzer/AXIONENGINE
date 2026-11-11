package dev.axion.api.content;

import dev.axion.api.Experimental;

/**
 * Ce que reçoit un {@link ContentProvider} : le moyen de déclarer à AXION le contenu de son mod (ADR-162 D3).
 *
 * <p>Tout ce qu'un mod enregistre vit dans son propre espace de noms, son modId : AXION le tient de Forge,
 * qui certifie l'expéditeur du message, et un mod ne peut pas enregistrer au nom d'un autre. Remplacer
 * l'entrée d'un autre mod se fait explicitement, par {@link #replace}.
 *
 * <p>Thread : celui qui appelle le fournisseur, le thread principal, pendant la phase d'enregistrement.
 * Hors de cette phase, toute méthode lève {@link IllegalStateException}. Les erreurs de contenu (fichier
 * illisible, doublon, cible de remplacement inconnue) ne lèvent pas : elles sont consignées, avec le mod
 * propriétaire, la source et l'identifiant, et l'entrée fautive est écartée.
 */
@Experimental
public interface ContentRegistrar {

    /** {@return le modId du mod qui enregistre, espace de noms de toutes ses entrées} */
    String modId();

    /**
     * Enregistre tout le contenu d'un dossier du JAR de ce mod, et rien d'autre.
     *
     * <p>Sous ce dossier, chaque famille a le sien ({@link ContentKind#folder()}) ; un fichier
     * {@code definitions/vehicules/patrouille.json} devient la definition {@code <modId>:vehicules/patrouille}.
     * Effet : les fichiers sont lus pendant l'appel. Coût : la lecture du dossier.
     *
     * @param folder chemin du dossier, relatif à la racine du JAR, par exemple {@code "axion/"}
     * @throws IllegalArgumentException si le dossier est vide, absolu ou remonte au-dessus de la racine
     * @throws IllegalStateException hors de la phase d'enregistrement
     */
    void registerFolder(String folder);

    /**
     * Remplace l'entrée d'un autre mod par un fichier du JAR de ce mod.
     *
     * <p>La trace nomme les deux mods. Deux mods qui remplacent la même entrée sont refusés tous les deux, en
     * les nommant ; une cible qui n'existe pas est refusée de même.
     *
     * @param kind famille de l'entrée remplacée
     * @param target identifiant de l'entrée remplacée, {@code <ns>:<chemin>}, d'un autre mod
     * @param file fichier du JAR de ce mod qui la remplace, relatif à la racine du JAR
     * @throws IllegalArgumentException si l'identifiant est mal formé, ou appartient à ce mod
     * @throws IllegalStateException hors de la phase d'enregistrement
     */
    void replace(ContentKind kind, String target, String file);
}
