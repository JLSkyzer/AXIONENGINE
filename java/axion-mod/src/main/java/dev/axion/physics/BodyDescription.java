package dev.axion.physics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;

/**
 * Description d'un corps (DM-08, §3.8), jointe à {@code CREATE_ASSEMBLY} (ADR-130) — miroir de
 * {@code BodyDesc} d'{@code ax-model}.
 *
 * <p>Masse, centre de masse et inertie <b>déclarés</b> priment sur ceux que les densités des
 * colliders donnent (R-622) ; non déclarés, ils en viennent : masse {@code 0}, inertie nulle,
 * centre de masse aux trois composantes NaN. Le bloc {@code physics} d'une definition déclare
 * masse, centre de masse, amortissements et CCD ; le reste prend ses défauts — gravité entière,
 * corps endormissable, plafonds de vitesse de R-180.
 *
 * <p>Sur la frontière : 52 octets en little-endian, dans l'ordre du §3.8, puis 4 octets réservés
 * à zéro ({@link #WIRE_BYTES}).
 *
 * @param bodyType {@link SimCommandStream#BODY_STATIC}, {@link SimCommandStream#BODY_KINEMATIC}
 *     ou {@link SimCommandStream#BODY_DYNAMIC}
 * @param ccd détection continue
 * @param canSleep le corps peut s'endormir de lui-même
 * @param mass masse déclarée, en kg ; {@code 0} : calculée
 * @param centerOfMassX centre de masse déclaré, repère local ; NaN : calculé
 * @param centerOfMassY idem
 * @param centerOfMassZ idem
 * @param inertiaX inertie principale déclarée, en kg·m² ; {@code 0} sur les trois : calculée
 * @param inertiaY idem
 * @param inertiaZ idem
 * @param linearDamping amortissement linéaire, en 1/s
 * @param angularDamping amortissement angulaire, en 1/s
 * @param gravityScale échelle de la gravité de la dimension
 * @param maxLinearVel vitesse linéaire maximale, en m/s
 * @param maxAngularVel vitesse angulaire maximale, en rad/s
 */
public record BodyDescription(
        int bodyType,
        boolean ccd,
        boolean canSleep,
        float mass,
        float centerOfMassX,
        float centerOfMassY,
        float centerOfMassZ,
        float inertiaX,
        float inertiaY,
        float inertiaZ,
        float linearDamping,
        float angularDamping,
        float gravityScale,
        float maxLinearVel,
        float maxAngularVel) {

    /** Taille sur la frontière, 4 octets réservés compris. */
    public static final int WIRE_BYTES = 56;

    /** Vitesse linéaire maximale par défaut (R-180), en m/s. */
    public static final float DEFAULT_MAX_LINEAR_VEL = 300.0f;

    /** Vitesse angulaire maximale par défaut (R-180), en rad/s. */
    public static final float DEFAULT_MAX_ANGULAR_VEL = 100.0f;

    /** @throws IllegalArgumentException pour un type de corps hors de {0, 1, 2} */
    public BodyDescription {
        if (bodyType < SimCommandStream.BODY_STATIC || bodyType > SimCommandStream.BODY_DYNAMIC) {
            throw new IllegalArgumentException("type de corps inconnu : " + bodyType);
        }
    }

    /**
     * {@return rien de déclaré : tout vient des densités et des défauts}
     *
     * @param bodyType type de corps
     */
    public static BodyDescription undeclared(int bodyType) {
        return new BodyDescription(bodyType, false, true, 0.0f, Float.NaN, Float.NaN, Float.NaN, 0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 1.0f, DEFAULT_MAX_LINEAR_VEL, DEFAULT_MAX_ANGULAR_VEL);
    }

    /**
     * {@return la description que le bloc {@code physics} d'une definition déclare} Sans bloc,
     * rien n'est déclaré. Le schéma des definitions a déjà borné ses valeurs : masse dans
     * [0,001 ; 10⁶] kg, amortissements positifs ou nuls, centre de masse à trois composantes.
     *
     * @param definition racine JSON de la definition
     * @param bodyType type de corps
     */
    public static BodyDescription of(JsonObject definition, int bodyType) {
        BodyDescription none = undeclared(bodyType);
        JsonElement block = definition.get("physics");
        if (block == null || !block.isJsonObject()) {
            return none;
        }
        JsonObject physics = block.getAsJsonObject();
        float[] center = {Float.NaN, Float.NaN, Float.NaN};
        if (physics.get("center_of_mass") instanceof JsonArray declared && declared.size() == 3) {
            for (int i = 0; i < 3; i++) {
                center[i] = declared.get(i).getAsFloat();
            }
        }
        return new BodyDescription(
                bodyType,
                physics.has("ccd") && physics.get("ccd").getAsBoolean(),
                none.canSleep(),
                physics.has("mass") ? physics.get("mass").getAsFloat() : none.mass(),
                center[0],
                center[1],
                center[2],
                none.inertiaX(),
                none.inertiaY(),
                none.inertiaZ(),
                physics.has("linear_damping") ? physics.get("linear_damping").getAsFloat() : none.linearDamping(),
                physics.has("angular_damping") ? physics.get("angular_damping").getAsFloat() : none.angularDamping(),
                none.gravityScale(),
                none.maxLinearVel(),
                none.maxAngularVel());
    }

    /**
     * Écrit la description sur la frontière, à la position courante d'un tampon little-endian :
     * {@link #WIRE_BYTES} octets.
     *
     * @param out tampon little-endian
     */
    public void writeTo(ByteBuffer out) {
        out.put((byte) bodyType);
        out.put((byte) (ccd ? 1 : 0));
        out.put((byte) (canSleep ? 1 : 0));
        out.put((byte) 0); // _pad
        for (float value : new float[] {
            mass, centerOfMassX, centerOfMassY, centerOfMassZ, inertiaX, inertiaY, inertiaZ, linearDamping,
            angularDamping, gravityScale, maxLinearVel, maxAngularVel
        }) {
            out.putFloat(value);
        }
        out.putInt(0); // réservé
    }
}
