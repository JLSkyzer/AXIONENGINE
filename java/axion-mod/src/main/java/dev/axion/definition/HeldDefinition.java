package dev.axion.definition;

import dev.axion.asset.DefinitionCompiler;
import java.util.List;

/**
 * Tout ce qu'une assembly vivante lit de sa definition (ADR-175 D4) : le document, ce que la compilation en a tiré — ses
 * corps, ses sièges et ses interactions, son réglage, les noms de ses nodes, la pose de repos de ses corps. Pris d'un seul
 * coup, de la même compilation, et gardé par l'instance tant qu'elle n'est pas reliée à la suivante (D5) : un
 * rechargement n'en change rien d'une assembly vivante, dont le corps natif a été créé de ce même résumé. Sans lui,
 * Java relisait chaque élément par identifiant et mêlait la definition d'avant et celle d'après.
 *
 * @param definition le document de la definition
 * @param summary le résumé de sa compilation native
 * @param seating ses sièges, ses interactions, ses variables, ses sockets
 * @param tuning ses paramètres réglables
 * @param nodeNames le nom de chaque node de son asset, par index
 * @param bodyRests la pose de repos de chacun de ses corps
 * @param ignition le rang de la variable que le démarreur lit comme contact (ADR-176 D1), -1 sans démarreur
 */
public record HeldDefinition(
        Definition definition,
        DefinitionCompiler.Summary summary,
        Seating seating,
        Tuning tuning,
        List<String> nodeNames,
        BodyRests bodyRests,
        int ignition) {

    /** Copie les noms. */
    public HeldDefinition {
        nodeNames = List.copyOf(nodeNames);
    }
}
