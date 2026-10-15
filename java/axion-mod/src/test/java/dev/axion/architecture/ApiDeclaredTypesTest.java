package dev.axion.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R-001, ADR-143 — les interfaces sans membre de l'API publique sont exactement celles que le §23.2
 * exige par une signature sans leur donner de méthode, et qu'ADR-143 rattache chacune à son
 * composant et à son jalon.
 *
 * <p>Une interface vide n'est pas un bouchon tant qu'une signature du cahier des charges la
 * requiert et qu'aucun code ne prétend l'implémenter (ADR-143). Ce test empêche qu'une nouvelle
 * s'y ajoute sans passer par cette liste, et qu'une liste devenue fausse — une interface qui a
 * reçu ses membres — reste en place.
 */
class ApiDeclaredTypesTest {

    /** Les types d'ADR-143 : déclarés par le §23.2, sans membre jusqu'à leur composant. */
    private static final Set<String> DECLARED = Set.of(
            // Services et vues de la façade, chacun à son jalon (table d'ADR-143).
            "AnimationControl", "AssetService", "AttachmentService", "AttachmentView", "CustomData",
            "DefinitionService", "DeformationService", "Diagnostics", "ImpactSource", "ParticleSetView",
            "PhysicsService", "QualityProfile", "RegistryAccess", "RuntimeState", "VehicleControl",
            // Les dix-huit événements du §23.2 et leur racine : leurs accesseurs viennent avec
            // l'EventBus, lui-même avec le fournisseur d'AxionApi (ADR-137).
            "AxionEvent", "AssemblySpawnEvent", "AssemblyRemoveEvent", "ImpactEvent", "ContactEvent",
            "SensorEvent", "PartDeformedEvent", "PartStageChangeEvent", "PartDetachedEvent",
            "LinkBrokenEvent", "DebrisSpawnedEvent", "SeatEnterEvent", "SeatExitEvent",
            "VehicleInputEvent", "AttachEvent", "DetachEvent", "RepairEvent", "QualityChangeEvent",
            "DegradationEvent");

    @Test
    @DisplayName("R-001, ADR-143 : les interfaces sans membre de l'API sont exactement celles de la liste")
    void lesInterfacesVidesSontCellesDeLaListe() throws IOException, ClassNotFoundException {
        String repo = System.getProperty("axion.repo.dir");
        assertNotNull(repo, "axion.repo.dir non fourni par le build");
        Path sources = Path.of(repo, "java", "axion-api", "src", "main", "java", "dev", "axion", "api");
        Set<String> empty = new TreeSet<>();
        try (Stream<Path> files = Files.list(sources)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString().replace(".java", "");
                if (name.equals("package-info")) {
                    continue;
                }
                Class<?> type = Class.forName("dev.axion.api." + name);
                if (type.isInterface() && !type.isAnnotation()
                        && type.getDeclaredMethods().length == 0
                        && type.getDeclaredClasses().length == 0) {
                    empty.add(name);
                }
            }
        }
        assertEquals(new TreeSet<>(DECLARED), empty);
    }
}
