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

    /**
     * Enregistre une definition construite en Java (ADR-162 D3, D4) : la definition {@code <modId>:<name>}, comme le
     * ferait le fichier {@code definitions/<name>.json} d'un dossier enregistré par {@link #registerFolder}.
     *
     * <p>Elle suit ensuite le chemin d'un fichier : le même schéma la valide au chargement, et sa forme canonique a
     * la même empreinte qu'un fichier de même contenu. Un fichier et une definition Java de même nom sont un doublon,
     * refusé en nommant les deux. Une erreur de contenu ne lève pas : elle est consignée, avec le mod et la source.
     *
     * @param name chemin de la definition dans l'espace de noms du mod, sans extension, par exemple
     *     {@code "vehicules/patrouille"}
     * @param definition le document, construit par le builder ou par {@link DefinitionDocument#of}
     * @throws IllegalArgumentException si le nom est vide, porte une extension, ou sort de {@code [a-z0-9_./-]}
     * @throws IllegalStateException hors de la phase d'enregistrement
     */
    void register(String name, DefinitionDocument definition);

    /**
     * Remplace la definition d'un autre mod par une definition construite en Java, comme {@link #replace(ContentKind,
     * String, String)} le fait par un fichier.
     *
     * @param target identifiant de la definition remplacée, {@code <ns>:<chemin>}, d'un autre mod
     * @param definition le document qui la remplace
     * @throws IllegalArgumentException si l'identifiant est mal formé, ou appartient à ce mod
     * @throws IllegalStateException hors de la phase d'enregistrement
     */
    void replace(String target, DefinitionDocument definition);

    /**
     * Enregistre un contrôleur de véhicule (ADR-167 D9, registre {@code axion:vehicle_controller}) : le contrôleur
     * {@code <modId>:<name>}, qu'une definition nomme ({@code vehicle.controllers}) ou que l'API attache à une
     * instance. Il s'enregistre sur le serveur comme chez le client, avec le mod qui le livre : un joueur sans ce mod ne
     * rejoint pas un serveur dont une definition le nomme (ADR-165). Un contrôleur de même nom déjà enregistré par ce
     * mod est un doublon, refusé et consigné.
     *
     * @param name chemin du contrôleur dans l'espace de noms du mod, par exemple {@code "turbo"}
     * @param controller le contrôleur
     * @throws IllegalArgumentException si le nom est vide ou sort de {@code [a-z0-9_./-]}
     * @throws IllegalStateException hors de la phase d'enregistrement
     */
    void registerController(String name, dev.axion.api.vehicle.VehicleController controller);

    /**
     * Enregistre un paramètre propre à ce mod (ADR-167 D1) : {@code <modId>:<name>}, que ses contrôleurs lisent. Il
     * existe sur un véhicule dès qu'un contrôleur de ce mod y est attaché — par sa definition ou par l'API —, à la base
     * donnée (décision de Killian du 2026-11-12) ; il se règle et se compose comme ceux d'AXION, dans ses bornes, mais
     * n'agit pas sur le moteur physique.
     *
     * @param name chemin du paramètre dans l'espace de noms du mod, par exemple {@code "boost_max"}
     * @param unit son unité, vide pour un facteur ou un rapport
     * @param base sa valeur sans réglage
     * @param limit les valeurs qu'il peut prendre : un réglage qui en sortirait est refusé
     * @throws IllegalArgumentException si le nom est vide ou sort de {@code [a-z0-9_./-]}, si la base n'est pas finie
     *     ou sort de {@code limit}
     * @throws IllegalStateException hors de la phase d'enregistrement
     */
    void registerParameter(String name, String unit, double base, dev.axion.api.assembly.Parameters.Range limit);
}
