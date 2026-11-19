package dev.axion.forge;

import dev.axion.physics.CollectResult;
import dev.axion.physics.PhysicsEvent;
import dev.axion.physics.PhysicsEventCodes;
import java.util.function.Consumer;

/**
 * Les événements des capteurs déclarés (ADR-174 D5), au serveur : chaque {@code SENSOR_ENTER} et {@code SENSOR_EXIT}
 * qu'un capteur d'une definition émet va à son assembly, qui compte ce qui y est et émet {@code sensor.<nom>.enter}
 * ou {@code sensor.<nom>.exit}. L'autre est une assembly, ou une entité par son identifiant réseau.
 */
final class SensorEvents implements Consumer<CollectResult> {

    private final AssemblyRuntime assemblies;

    /**
     * @param assemblies les assemblies dotées d'un corps, par handle
     */
    SensorEvents(AssemblyRuntime assemblies) {
        this.assemblies = assemblies;
    }

    @Override
    public void accept(CollectResult result) {
        for (PhysicsEvent event : result.events()) {
            boolean enter = event.kind() == PhysicsEventCodes.Kind.SENSOR_ENTER;
            if ((!enter && event.kind() != PhysicsEventCodes.Kind.SENSOR_EXIT)
                    || (event.data() & PhysicsEventCodes.Data.SENSOR_DECLARED) == 0) {
                continue;
            }
            AxionEntity assembly = assemblies.assembly(event.assemblyAIndex(), event.assemblyAGeneration());
            if (assembly == null) {
                continue;
            }
            int sensor = event.data() >>> PhysicsEventCodes.Data.SENSOR_INDEX_SHIFT;
            boolean entity = (event.data() & PhysicsEventCodes.Data.CONTACT_OTHER_ENTITY) != 0;
            long other = entity ? -1L - event.assemblyBIndex()
                    : ((long) event.assemblyBIndex() << 32) | (event.assemblyBGeneration() & 0xFFFF_FFFFL);
            assembly.sensorChanged(sensor, enter, other);
        }
    }
}
