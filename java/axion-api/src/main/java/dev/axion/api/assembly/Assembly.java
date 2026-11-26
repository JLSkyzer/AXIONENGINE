package dev.axion.api.assembly;

import dev.axion.api.Experimental;
import dev.axion.api.content.DefinitionRef;

import java.util.UUID;
import net.minecraft.world.entity.Entity;

/**
 * Une assembly runtime : l'objet AXION derrière une {@code AxionEntity} (§23.2).
 *
 * <p>Les lectures renvoient un instantané : celui du serveur sur le thread autoritatif, celui que le client a reçu
 * et interpolé sur le thread de rendu. Les méthodes <strong>mutantes</strong> ne s'appellent que sur le thread
 * autoritatif et lèvent {@link IllegalStateException} ailleurs (R-1755). Une assembly dont {@link #isValid()} est faux
 * ne doit plus être utilisée.
 *
 * <p><strong>Une surface réelle</strong> (ADR-162 D1) : forces et impulsions, vitesses, sockets, pièces, structure,
 * déformation, surface, véhicule, particules et attaches y entrent avec le composant qui les rend, en méthodes
 * {@code default}.
 */
@Experimental
public interface Assembly {

    /** {@return l'identifiant stable de l'assembly, celui de son entité}. Thread : quelconque. */
    UUID id();

    /** {@return la definition dont l'assembly est issue}. Thread : quelconque. */
    DefinitionRef definition();

    /**
     * {@return la révision de la definition que l'assembly lit : celle de son document, 1 sans {@code revision} (ADR-175
     * D3)} Elle change quand un rechargement relie l'assembly à une definition révisée. Thread : quelconque.
     */
    default int revision() {
        return 1;
    }

    /** {@return l'entité Minecraft qui porte l'assembly}. Thread : quelconque. */
    Entity entity();

    /**
     * {@return la transformation monde courante du corps racine}
     *
     * <p>Thread : autoritatif ou rendu. L'échelle vaut 1 : une assembly n'est pas mise à l'échelle au monde.
     */
    Transform transform();

    /** {@return le contrôle des animations}. Thread : autoritatif ou rendu. */
    AnimationControl animation();

    /** {@return les données personnalisées des mods tiers}. Thread : autoritatif. */
    CustomData customData();

    /** {@return les variables de contenu de l'assembly (ADR-163 D2)}. Thread : autoritatif. */
    Variables variables();

    /**
     * {@return les paramètres réglables de l'assembly (ADR-167)} : sa masse, et pour un véhicule son moteur, sa boîte,
     * ses freins, ses pneus… réglés pour elle seule. Thread : autoritatif ; au client, en lecture seulement.
     */
    Parameters parameters();

    /**
     * {@return son véhicule (ADR-159 D11, ADR-167 D9) : son état, ses contrôleurs, son pilotage par un mod ; vide si sa
     * definition n'a pas de roues} Thread : quelconque ; voir {@link dev.axion.api.vehicle.VehicleControl} pour ses
     * méthodes.
     */
    java.util.Optional<dev.axion.api.vehicle.VehicleControl> vehicle();

    /**
     * {@return ses sièges, dans l'ordre de sa definition, chacun avec la pose et l'échelle de son occupant (ADR-177) ;
     * vide sans siège, ou avant que sa definition ne soit prête} Thread : autoritatif uniquement (R-1755).
     */
    default java.util.List<Seat> seats() {
        return java.util.List.of();
    }

    /**
     * {@return son énergie (ADR-176 D3) : le carburant de son réservoir et la charge de sa batterie ; zéro partout si sa
     * definition n'en déclare pas} Thread : autoritatif uniquement (R-1755).
     */
    default Energy energy() {
        throw new UnsupportedOperationException("energy : cette assembly n'expose pas son énergie");
    }

    /**
     * {@return ses attaches (ADR-171 D4) : poser, ancrer et défaire celles de ses sockets, les lire, piloter un treuil}
     * Thread : voir {@link dev.axion.api.attach.Attachments}.
     */
    dev.axion.api.attach.Attachments attachments();

    /** {@return vrai tant que l'assembly existe côté runtime}. Thread : quelconque. */
    boolean isValid();

    /**
     * Retire l'assembly et son entité.
     *
     * <p>Thread : autoritatif uniquement (R-1755). Coût : négligeable. Échec :
     * sans effet si déjà invalide.
     */
    void remove();

    /**
     * {@return la valeur d'une source procédurale (ANNEXE A.4) à cet instant} — {@code world.time},
     * {@code world.rain}, {@code var.phares}… Le nom et la valeur sont ceux d'une piste procédurale de la definition :
     * le même code les évalue, et le monde se lit au point de l'assembly (ADR-173 D1).
     *
     * <p>Thread : autoritatif uniquement (R-1755). Coût : la première lecture d'un tick construit la trame, les
     * suivantes la relisent.
     *
     * @param name le nom de la source
     * @throws IllegalArgumentException si le nom n'est pas une source de l'ANNEXE A.4, ou nomme une roue, un siège, un
     *     socket ou une variable que la definition n'a pas
     */
    default double source(String name) {
        throw new UnsupportedOperationException("source : cette assembly ne lit pas les sources procédurales");
    }

    /**
     * Émet un événement de contenu que sa definition déclare (ADR-174 D2) : les écouteurs du serveur le reçoivent au
     * tick, et les joueurs que sa déclaration désigne avec le snapshot suivant.
     *
     * <p>Thread : autoritatif uniquement (R-1755).
     *
     * @param event son nom dans {@code events}
     * @param value sa valeur
     * @throws IllegalArgumentException si la definition ne déclare pas cet événement
     */
    default void emit(String event, double value) {
        throw new UnsupportedOperationException("emit : cette assembly n'émet pas d'événements");
    }

    /**
     * {@return ce qui est dans un capteur que sa definition déclare (ADR-174 D5), dans l'ordre d'entrée} Les autres
     * assemblies et les entités ; une qui n'est plus chargée n'y figure pas. L'identité de ce qui entre n'est rendue
     * qu'ici : les événements du capteur ne disent que le compte.
     *
     * <p>Thread : autoritatif uniquement (R-1755).
     *
     * @param sensor le nom du capteur
     * @throws IllegalArgumentException si la definition ne déclare pas ce capteur
     */
    default java.util.List<Entity> sensorOccupants(String sensor) {
        throw new UnsupportedOperationException("sensorOccupants : cette assembly n'a pas de capteurs");
    }

    /**
     * {@return la vitesse de son corps racine, en m/s dans le repère du monde} Nulle pour une assembly endormie,
     * suspendue loin de tout joueur, ou que le moteur physique ne simule pas encore.
     *
     * <p>Thread : autoritatif uniquement (R-1755), au serveur.
     *
     * @throws IllegalStateException chez un client
     */
    default net.minecraft.world.phys.Vec3 linearVelocity() {
        throw new UnsupportedOperationException("linearVelocity : cette assembly ne dit pas sa vitesse");
    }

    /**
     * {@return la vitesse de rotation de son corps racine, en rad/s autour des axes du monde} Mêmes conditions que
     * {@link #linearVelocity}.
     *
     * @throws IllegalStateException chez un client
     */
    default net.minecraft.world.phys.Vec3 angularVelocity() {
        throw new UnsupportedOperationException("angularVelocity : cette assembly ne dit pas sa vitesse");
    }

    /**
     * Donne à son corps racine une impulsion, au prochain pas physique, et le réveille. Elle est gardée tant que le
     * moteur physique ne simule pas encore l'assembly ; un corps fixe ne bouge pas.
     *
     * <p>Thread : autoritatif uniquement (R-1755), au serveur.
     *
     * @param impulse l'impulsion, en N·s dans le repère du monde
     * @param worldPoint le point du monde où elle s'applique, qui fait aussi tourner le corps ; {@code null} : au
     *     centre de masse, sans rotation
     * @throws IllegalArgumentException si une composante n'est pas finie
     * @throws IllegalStateException chez un client
     */
    default void applyImpulse(net.minecraft.world.phys.Vec3 impulse, net.minecraft.world.phys.Vec3 worldPoint) {
        throw new UnsupportedOperationException("applyImpulse : cette assembly ne se pousse pas");
    }

    /**
     * {@return les entités que cette assembly porte au serveur (ADR-178, CAP-14)} Celles posées sur un de ses corps ; une
     * copie, dans un ordre quelconque. Les passagers assis n'y sont pas : ils sont des passagers.
     *
     * <p>Thread : autoritatif uniquement (R-1755).
     */
    default java.util.List<Entity> carried() {
        throw new UnsupportedOperationException("carried : cette assembly ne dit pas ce qu'elle porte");
    }
}
