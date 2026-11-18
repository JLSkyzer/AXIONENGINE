package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-174 D4 : la grammaire fermée de {@code trigger}. */
class AnimationTriggersTest {

    @Test
    @DisplayName("ADR-174 D4 : chaque forme de la grammaire se lit ; le reste ne se lit pas")
    void laGrammaire() {
        assertEquals(new AnimationTriggers.Parsed(AnimationTriggers.Kind.INTERACT, "porte"),
                AnimationTriggers.parse("interact:porte"));
        assertEquals(new AnimationTriggers.Parsed(AnimationTriggers.Kind.EVENT, "klaxon"),
                AnimationTriggers.parse("event:klaxon"));
        assertEquals(new AnimationTriggers.Parsed(AnimationTriggers.Kind.VAR, "phares"),
                AnimationTriggers.parse("var:phares"));
        assertEquals(new AnimationTriggers.Parsed(AnimationTriggers.Kind.SEAT_ENTER, "conducteur"),
                AnimationTriggers.parse("seat:conducteur:enter"));
        assertEquals(new AnimationTriggers.Parsed(AnimationTriggers.Kind.SEAT_EXIT, "conducteur"),
                AnimationTriggers.parse("seat:conducteur:exit"));
        assertEquals(new AnimationTriggers.Parsed(AnimationTriggers.Kind.STAGE, "capot:DESTROYED"),
                AnimationTriggers.parse("stage:capot:DESTROYED"));
        for (String faux : List.of("interact", "interact:", "clic:porte", "seat:conducteur", "seat:conducteur:sit",
                "stage:capot", "stage:capot:")) {
            assertNull(AnimationTriggers.parse(faux), faux);
        }
    }

    @Test
    @DisplayName("ADR-174 D4 : les déclencheurs d'une definition au schéma 7, et ceux qu'un fait déclenche")
    void lesDeclencheursDUneDefinition() {
        JsonObject root = JsonParser.parseString("{\"schema\": 7, \"animations\": {"
                + "\"a\": {\"clip\": \"glisse\", \"layer\": 1, \"trigger\": \"var:ouverte\"},"
                + " \"b\": {\"trigger\": \"event:sonne\"}, \"c\": {\"clip\": \"x\"}}}").getAsJsonObject();
        AnimationTriggers triggers = AnimationTriggers.of(root);
        assertEquals(2, triggers.triggers().size(), "l'animation sans trigger n'en a pas");
        assertEquals(List.of(new AnimationTriggers.Trigger(AnimationTriggers.Kind.EVENT, "sonne", "b", "b", 0)),
                triggers.matching(AnimationTriggers.Kind.EVENT, "sonne"), "sans clip, le nom de l'animation");
        assertEquals(List.of("ouverte"), triggers.watchedVariables());
        assertTrue(triggers.matching(AnimationTriggers.Kind.EVENT, "autre").isEmpty());
        root.addProperty("schema", 6);
        assertEquals(AnimationTriggers.NONE, AnimationTriggers.of(root), "avant le schéma 7, trigger est un texte libre");
    }
}
