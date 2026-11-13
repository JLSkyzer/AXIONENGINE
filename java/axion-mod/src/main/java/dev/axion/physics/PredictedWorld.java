package dev.axion.physics;

import dev.axion.definition.PhysicsMaterialTable;
import dev.axion.seat.VehicleInput;

/**
 * Le monde prédit d'un client distant (ADR-168 D1) : le véhicule que son joueur conduit, simulé par le natif du
 * client, en mode {@code ClientPredicted}. Il avance d'un pas par entrée appliquée, et peut reprendre l'état complet
 * que le serveur a calculé ({@code SET_ASSEMBLY_STATE}) pour rejouer à partir de lui.
 *
 * <p>Il tient le pilote de simulation du contexte natif (R-450 : un contexte par processus) : un client distant n'en a
 * pas d'autre usage. Fermé, il rend au pilote son mode de serveur, celui qu'attend un monde solo ouvert plus tard dans
 * la même session.
 *
 * <p>Logique pure au-dessus de la frontière native, sans Forge. Non synchronisé : le thread du client seul s'en sert.
 */
public final class PredictedWorld implements AutoCloseable {

    /** La génération des handles du monde prédit : une assembly à la fois, recréée sous le même rang. */
    public static final int GENERATION = 1;

    /**
     * Ce qu'un pas a produit pour le véhicule prédit.
     *
     * @param result le résultat du {@code collect} : états des corps, emprises, roues, véhicule
     * @param state l'état complet exporté du véhicule, avec le statut de sa dernière restauration ; {@code null} si le
     *     natif n'en a pas rendu
     */
    public record Step(CollectResult result, AssemblyStateReport state) {}

    private final NativeSimulation simulation;
    private final int handle;
    private final long dimension;
    private SimCommandStream pending = new SimCommandStream();
    private long tick;
    private boolean closed;

    private PredictedWorld(NativeSimulation simulation, int handle, long dimension) {
        this.simulation = simulation;
        this.handle = handle;
        this.dimension = dimension;
    }

    /**
     * Ouvre le monde prédit et y crée le véhicule ; rien ne part au natif avant le premier pas.
     *
     * @param simulation le pilote du contexte natif du client
     * @param materials la table des matériaux physiques du serveur, encodée
     * @param handle le rang du véhicule : son identifiant réseau, comme au serveur
     * @param dimension sa dimension ({@link dev.axion.world.DimensionId})
     * @param position sa position monde, en blocs
     * @param rotation sa rotation, quaternion {@code x, y, z, w}
     * @param definition l'empreinte de sa definition compilée
     * @return le monde prédit
     */
    public static PredictedWorld open(NativeSimulation simulation, PhysicsMaterialTable.Encoded materials, int handle,
            long dimension, double[] position, float[] rotation, long definition) {
        PredictedWorld world = new PredictedWorld(simulation, handle, dimension);
        world.pending.setSimMode(SimCommandStream.SIM_MODE_CLIENT_PREDICTED);
        if (materials != null) {
            world.pending.setPhysicsMaterials(materials.entries(), materials.count());
        }
        world.pending.createAssembly(handle, GENERATION, dimension, position, rotation, definition)
                .setStateExport(handle, GENERATION, true);
        return world;
    }

    /** {@return le rang du véhicule prédit} */
    public int handle() {
        return handle;
    }

    /** {@return sa dimension} */
    public long dimension() {
        return dimension;
    }

    /** {@return les pas déjà faits} */
    public long ticks() {
        return tick;
    }

    /**
     * Rend au véhicule l'état complet calculé par le serveur, avant le pas suivant (ADR-168 D5). Un état qui ne lui
     * convient pas ne change rien : le statut de l'état exporté au pas suivant le dit.
     *
     * @param state le bloc d'état, tel que le serveur l'a exporté
     */
    public void restore(byte[] state) {
        pending.setAssemblyState(handle, GENERATION, state);
    }

    /**
     * Change la table des matériaux physiques, avant le pas suivant.
     *
     * @param materials la table encodée
     */
    public void materials(PhysicsMaterialTable.Encoded materials) {
        pending.setPhysicsMaterials(materials.entries(), materials.count());
    }

    /**
     * Fait un pas, l'entrée du conducteur appliquée : celui du serveur, à ce que le client en sait.
     *
     * @param input l'entrée de ce pas, bornée comme le serveur la borne
     * @param world ce que le monde autour demande à ce pas : observateur, tuiles ; vide sinon
     * @return ce que le pas a produit ; un {@code collect} refusé y porte son code
     * @throws IllegalStateException si le monde est fermé
     */
    public Step step(VehicleInput input, SimCommandStream world) {
        if (closed) {
            throw new IllegalStateException("monde prédit fermé");
        }
        SimCommandStream commands = pending;
        pending = new SimCommandStream();
        commands.merge(world).setVehicleInput(handle, GENERATION, input, true);
        CollectResult result = simulation.tick(++tick, commands, 0L);
        AssemblyStateReport state = null;
        for (AssemblyStateReport report : result.states()) {
            if (report.handleIndex() == handle && report.handleGeneration() == GENERATION) {
                state = report;
            }
        }
        return new Step(result, state);
    }

    /** Retire le véhicule et rend au pilote son mode de serveur. Sans effet la seconde fois. */
    @Override
    public void close() {
        close(new SimCommandStream());
    }

    /**
     * Retire le véhicule, avec ce que le monde autour retire lui aussi — ses tuiles —, et rend au pilote son mode de
     * serveur. Sans effet la seconde fois.
     *
     * @param world les retraits du monde autour
     */
    public void close(SimCommandStream world) {
        if (closed) {
            return;
        }
        closed = true;
        SimCommandStream commands = world
                .removeAssembly(handle, GENERATION)
                .setSimMode(SimCommandStream.SIM_MODE_SERVER);
        simulation.tick(++tick, commands, 0L);
    }
}
