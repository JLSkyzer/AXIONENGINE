package dev.axion.forge;

import dev.axion.AxionMod;
import dev.axion.api.assembly.Modifier;
import dev.axion.api.assembly.Parameters;
import dev.axion.api.content.AxionContent;
import dev.axion.api.content.ContentProvider;
import dev.axion.api.vehicle.VehicleController;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;

/**
 * Les contrôleurs de véhicule du contenu de test (ADR-167 D9) : enregistrés par l'API publique, comme un addon le fait,
 * pendant la phase d'enregistrement, avant les definitions qui les nomment. Ils ne vivent qu'en développement.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ControleursDeTest {

    /** Le couple que {@link #PROPULSION} pose sur chaque roue, en N·m. */
    static final double COUPLE_PAR_ROUE = 120.0;

    /** Le paramètre propre au mod du test, qui existe sur un véhicule dès qu'un de ses contrôleurs y est. */
    static final String GAIN = "axion:test/gain";

    /** Pousse chaque roue vers l'avant : sans {@code powertrain}, c'est tout son couple (R-1082). */
    static final VehicleController PROPULSION = (vue, sortie) -> {
        for (int roue = 0; roue < vue.wheels().size(); roue++) {
            sortie.wheelTorque(roue, COUPLE_PAR_ROUE);
        }
    };

    /** Compte ses ticks dans la variable {@code compteur} — son état —, et ajoute 1 au gain pour ce tick. */
    static final VehicleController COMPTEUR = (vue, sortie) -> {
        try {
            sortie.variable("compteur", vue.variable("compteur") + 1);
        } catch (IllegalArgumentException sansCompteur) {
            // Attaché à un véhicule qui ne déclare pas « compteur » : il ne règle que le gain.
        }
        sortie.modify(GAIN, Modifier.Operation.ADD, 1);
    };

    /** Lève à chaque appel : désactivé au nombre de fautes consécutives toléré (E-1011). */
    static final VehicleController CASSE = (vue, sortie) -> {
        throw new IllegalStateException("contrôleur d'essai cassé exprès");
    };

    /** Fait sortir le couple du moteur de ses bornes : sa sortie est écartée à chaque tick. */
    static final VehicleController EXCESSIF = (vue, sortie) ->
            sortie.modify(Parameters.ENGINE_TORQUE, Modifier.Operation.MULTIPLY_TOTAL, -2);

    private ControleursDeTest() {
        throw new AssertionError("non instanciable");
    }

    /**
     * Envoie le fournisseur des contrôleurs de test à AXION.
     *
     * @param event l'envoi des messages entre mods
     */
    @SubscribeEvent
    public static void enregistrer(InterModEnqueueEvent event) {
        InterModComms.sendTo(AxionContent.MOD_ID, AxionContent.REGISTER, () -> (ContentProvider) registrar -> {
            registrar.registerController("test/propulsion", PROPULSION);
            registrar.registerController("test/compteur", COMPTEUR);
            registrar.registerController("test/casse", CASSE);
            registrar.registerController("test/excessif", EXCESSIF);
            registrar.registerParameter("test/gain", "", 1.0, new Parameters.Range(0, true, 10, true));
        });
    }
}
