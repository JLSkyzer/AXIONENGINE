package dev.axion.asset;

import java.io.IOException;
import java.util.List;

/**
 * D'ou viennent les sources d'assets (C-20 etape 1).
 *
 * <p>Cette interface existe pour que l'orchestrateur soit verifiable sans
 * gestionnaire de ressources Minecraft. Sans elle, il faudrait un monde charge
 * pour tester une machine a etats, et ce qui demande un monde charge n'est
 * teste par personne.
 *
 * <p>Les implementations reelles rendent le contenu que les mods enregistrent
 * (ADR-162), sous les cles {@code <modId>:axion/<famille>/<chemin>}, et, chez le
 * client, les modeles {@code assets/<ns>/axion/models/**} des resource packs.
 */
public interface AssetSource {

    /**
     * {@return les chemins de toutes les ressources disponibles : les sources,
     * et les fichiers d'options qui les accompagnent (ADR-135)}
     *
     * <p>L'ordre importe : il decide de l'ordre de compilation, donc de ce qui
     * est pret en premier. Une enumeration stable rend le demarrage
     * reproductible, ce qu'une exploration de systeme de fichiers ne garantit
     * pas d'elle-meme.
     */
    List<String> list();

    /**
     * Lit une source.
     *
     * @param path chemin rendu par {@link #list()}
     * @return le contenu du fichier
     * @throws IOException si la ressource a disparu entre l'enumeration et la
     *     lecture, ce qu'un rechargement concurrent peut produire
     */
    byte[] read(String path) throws IOException;
}
