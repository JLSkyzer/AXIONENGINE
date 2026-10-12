package dev.axion.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-312 (ADR-130) : la description DM-08 d'un corps, lue d'une definition et écrite sur la frontière. */
class BodyDescriptionTest {

    private static JsonObject definition(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    @DisplayName("Sans bloc physics, rien n'est déclaré : tout vient des densités et des défauts")
    void sansBlocRienNEstDeclare() {
        BodyDescription body = BodyDescription.of(definition("{}"), SimCommandStream.BODY_DYNAMIC);
        assertEquals(BodyDescription.undeclared(SimCommandStream.BODY_DYNAMIC), body);
        assertEquals(0.0f, body.mass(), "masse calculée");
        assertTrue(Float.isNaN(body.centerOfMassX()), "centre calculé");
        assertTrue(body.canSleep());
        assertEquals(1.0f, body.gravityScale());
        assertEquals(300.0f, body.maxLinearVel(), "R-180");
        assertEquals(100.0f, body.maxAngularVel(), "R-180");
    }

    @Test
    @DisplayName("Le bloc physics déclare masse, centre de masse, amortissements et CCD")
    void leBlocPhysicsDeclare() {
        BodyDescription body = BodyDescription.of(
                definition("""
                        {"physics": {"mass": 1650, "center_of_mass": [0, -0.25, 0.5],
                                     "linear_damping": 0.1, "angular_damping": 0.2, "ccd": true}}
                        """),
                SimCommandStream.BODY_DYNAMIC);
        assertEquals(1650.0f, body.mass());
        assertEquals(0.0f, body.centerOfMassX());
        assertEquals(-0.25f, body.centerOfMassY());
        assertEquals(0.5f, body.centerOfMassZ());
        assertEquals(0.1f, body.linearDamping());
        assertEquals(0.2f, body.angularDamping());
        assertTrue(body.ccd());
        // Ce que la definition ne déclare pas garde son défaut.
        assertEquals(0.0f, body.inertiaX(), "inertie calculée");
        assertEquals(1.0f, body.gravityScale());
    }

    @Test
    @DisplayName("Sur la frontière : 56 octets, dans l'ordre de BodyDesc::read_le côté natif")
    void ecritureSurLaFrontiere() {
        BodyDescription body = new BodyDescription(SimCommandStream.BODY_DYNAMIC, true, false, 1650.0f, 0.0f, -0.25f,
                0.5f, 10.0f, 20.0f, 30.0f, 0.1f, 0.2f, 0.5f, 120.0f, 40.0f);
        ByteBuffer out = ByteBuffer.allocate(BodyDescription.WIRE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        body.writeTo(out);
        assertEquals(BodyDescription.WIRE_BYTES, out.position());
        assertEquals(2, out.get(0), "body_type");
        assertEquals(1, out.get(1), "ccd");
        assertEquals(0, out.get(2), "can_sleep");
        assertEquals(0, out.get(3), "_pad");
        assertEquals(1650.0f, out.getFloat(4), "mass");
        assertEquals(-0.25f, out.getFloat(12), "center_of_mass.y");
        assertEquals(30.0f, out.getFloat(28), "inertia.z");
        assertEquals(0.1f, out.getFloat(32), "linear_damping");
        assertEquals(0.2f, out.getFloat(36), "angular_damping");
        assertEquals(0.5f, out.getFloat(40), "gravity_scale");
        assertEquals(120.0f, out.getFloat(44), "max_linear_vel");
        assertEquals(40.0f, out.getFloat(48), "max_angular_vel");
        assertEquals(0, out.getInt(52), "réservé");
    }

    @Test
    @DisplayName("Un type de corps hors de {0, 1, 2} est refusé")
    void typeInconnuRefuse() {
        assertThrows(IllegalArgumentException.class, () -> BodyDescription.undeclared(3));
        assertThrows(IllegalArgumentException.class, () -> BodyDescription.undeclared(-1));
        assertFalse(BodyDescription.undeclared(SimCommandStream.BODY_STATIC).ccd());
    }
}
