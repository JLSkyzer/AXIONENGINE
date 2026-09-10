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
 * <p>L'implementation reelle enumere les chemins que la fiche C-20 designe :
 * {@code assets/<ns>/axion/models/**} pour les modeles, et les repertoires de
 * {@code data/<ns>/axion/} pour les definitions.
 */
public interface AssetSource {

    /**
     * {@return les chemins de toutes les sources disponibles}
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
