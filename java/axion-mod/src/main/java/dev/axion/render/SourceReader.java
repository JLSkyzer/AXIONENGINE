package dev.axion.render;

import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

/**
 * Les sources procédurales d'une definition, lues par leur nom hors d'une piste (ADR-173 D1) : par un contrôleur de
 * véhicule ({@code VehicleView.source}) ou par un mod ({@code Assembly.source}), des deux côtés. Chaque nom se résout
 * une fois, par la sonde même des jauges ({@link ProceduralTracks#probe}) : une source vaut la même chose dans un JSON
 * et dans du Java. Logique pure, sans Forge.
 *
 * <p>Un lecteur appartient à une instance et à son thread.
 */
public final class SourceReader {

    /** Les familles de l'ANNEXE A.4, dites quand un nom n'en est pas une. */
    static final String FAMILIES = "vehicle.*, engine.*, transmission.*, wheel.<roue>.*, seat.<siège>.occupied,"
            + " attach.<socket>.*, socket.<socket>.*, time.seconds, var.<variable>, world.*";

    private final JsonObject root;
    private final Map<String, ProceduralTracks.Probe> probes = new HashMap<>();

    /**
     * @param root le document de la definition
     */
    public SourceReader(JsonObject root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /**
     * {@return la valeur d'une source dans une trame}
     *
     * @param name le nom de la source, de l'ANNEXE A.4
     * @param frame la trame
     * @throws IllegalArgumentException si le nom n'est pas une source, ou nomme ce que la definition n'a pas
     */
    public double read(String name, ProceduralTracks.Frame frame) {
        Objects.requireNonNull(name, "name");
        ProceduralTracks.Probe probe = probes.get(name);
        if (probe == null) {
            probe = ProceduralTracks.probe(root, name);
            if (probe == null) {
                throw new IllegalArgumentException("source « " + name + " » inconnue de cette definition ; les"
                        + " familles de l'ANNEXE A.4 : " + FAMILIES);
            }
            probes.put(name, probe);
        }
        return probe.read(frame);
    }

    /**
     * {@return la lecture d'un instant} : la trame n'est construite qu'à la première source lue, puis relue — le monde
     * se lit une fois par tick et par assembly au plus.
     *
     * @param frame construit la trame de l'instant
     */
    public ToDoubleFunction<String> at(Supplier<ProceduralTracks.Frame> frame) {
        Objects.requireNonNull(frame, "frame");
        ProceduralTracks.Frame[] built = new ProceduralTracks.Frame[1];
        return name -> {
            if (built[0] == null) {
                built[0] = frame.get();
            }
            return read(name, built[0]);
        };
    }
}
