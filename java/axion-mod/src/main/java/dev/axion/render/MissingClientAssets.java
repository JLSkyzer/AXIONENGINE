package dev.axion.render;

import dev.axion.asset.AssetRegistry;
import java.util.HashSet;
import java.util.Set;

/**
 * Le modèle qu'une definition demande et que ce client n'a pas (R-1640, ADR-150 D3, T-421).
 *
 * <p>Les assets ne passent jamais par le réseau : chaque client compile ses modèles depuis ses
 * resource packs, puis depuis les mods installés chez lui. Un modèle absent de son registre donne la
 * boîte de repli et un avertissement — jamais une déconnexion. L'avertissement se dit une fois par
 * modèle et par registre : une assembly se dessine à chaque frame, et un journal qui répète la même
 * ligne soixante fois par seconde ne dit plus rien.
 *
 * <p>Un modèle en cours de compilation n'est pas absent : il a son entrée, et l'avertissement attend.
 * Avant le premier rechargement des ressources, il n'y a pas de registre, et rien n'est encore absent.
 */
public final class MissingClientAssets {

    private final Set<String> warned = new HashSet<>();
    private AssetRegistry seen;

    /**
     * @param registry registre d'assets de ce client, ou {@code null} avant le premier rechargement
     * @param path chemin du modèle que demande la definition
     * @return l'avertissement à journaliser, la première fois que ce modèle manque à ce registre ;
     *     {@code null} sinon
     */
    public String check(AssetRegistry registry, String path) {
        if (registry == null) {
            return null;
        }
        if (registry != seen) {
            // Un rechargement de ressources remplace le registre : un modèle qui manque encore le
            // redira, une fois.
            warned.clear();
            seen = registry;
        }
        if (registry.entry(path) != null || !warned.add(path)) {
            return null;
        }
        return "modèle « " + path + " » absent des resource packs et des mods de ce client : "
                + "boîte de repli (R-1640)";
    }
}
