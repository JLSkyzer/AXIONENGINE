package dev.axion.platform;

import java.nio.file.Path;

/**
 * Vue d'AXION sur la plateforme qui l'héberge (IF-10, C-01).
 *
 * <p>Tout ce dont le moteur a besoin de la plateforme passe par cette interface,
 * et rien d'autre. C'est ce qui permet à R-401 de tenir : seul
 * {@code dev.axion.forge} connaît Forge, et il n'en laisse filtrer que ces
 * quelques méthodes.
 *
 * <p>L'intérêt n'est pas théorique. Sans cette frontière, une centaine de
 * classes dépendraient d'API Forge, et il deviendrait impossible de tester quoi
 * que ce soit sans démarrer le jeu — ni de savoir, en lisant le code, ce qu'AXION
 * demande réellement à son hôte.
 */
public interface PlatformAdapter {

    /**
     * {@return le nom de la plateforme et sa version majeure, par exemple
     * {@code forge-47}}
     */
    String platformName();

    /** {@return la version de Minecraft, par exemple {@code 1.20.1}} */
    String minecraftVersion();

    /**
     * Indique si un mod est chargé.
     *
     * @param modid identifiant du mod
     * @return vrai si le mod est présent
     */
    boolean isModLoaded(String modid);

    /**
     * Indique si le thread courant est celui qui fait autorité sur l'état du
     * jeu.
     *
     * <p>INV-03 : aucune API Minecraft mutante n'est appelée hors de ce thread.
     * Une méthode qui modifie l'état doit le vérifier plutôt que l'espérer.
     *
     * @return vrai si l'appel a lieu sur le thread autoritatif
     */
    boolean isAuthoritativeThread();

    /** {@return vrai si l'on est du côté client} */
    boolean isClient();

    /** {@return le numéro du tick courant} */
    long currentTick();

    /**
     * Programme un travail sur le thread autoritatif.
     *
     * <p>Exécute immédiatement si l'appel a déjà lieu sur ce thread.
     *
     * @param work travail à exécuter
     */
    void runOnAuthoritativeThread(Runnable work);

    /** {@return le répertoire de jeu} */
    Path gameDir();

    /** {@return le répertoire de configuration} */
    Path configDir();
}
