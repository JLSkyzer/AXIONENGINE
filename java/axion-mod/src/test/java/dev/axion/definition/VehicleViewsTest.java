package dev.axion.definition;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-170 D1, D3 : les vues d'une definition compilée, déclarées ou par défaut. */
class VehicleViewsTest {

    private static final String SIEGES = "\"seats\": [{\"name\": \"conducteur\", \"node\": \"siege_g\", \"role\":"
            + " \"driver\"}, {\"name\": \"passager\", \"node\": \"siege_d\"}]";

    private static Seating compiler(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(DefinitionChecker.check(root).isEmpty(), () -> DefinitionChecker.check(root).toString());
        List<DefinitionChecker.AssetReference> references = DefinitionChecker.assetReferences(root);
        // Le natif simulé : le node 10 + rang pour chaque référence à un node.
        int[] nodes = new int[references.size()];
        for (int rank = 0; rank < nodes.length; rank++) {
            nodes[rank] = references.get(rank).category() == NameCategory.NODE ? 10 + rank : -1;
        }
        return Seating.of(root, references, nodes);
    }

    @Test
    @DisplayName("D1 : sans vue déclarée, chaque siège a le cockpit à l'œil du joueur et la poursuite à la taille")
    void defauts() {
        VehicleViews views = compiler("{\"schema\": 4, \"asset\": \"m:a.glb\", \"kind\": \"rigid_object\", "
                + SIEGES + "}").views();
        assertEquals(2, views.views().size());
        for (int seat = 0; seat < 2; seat++) {
            List<VehicleViews.View> vues = views.forSeat(seat);
            assertEquals(List.of(VehicleViews.Kind.COCKPIT, VehicleViews.Kind.FOLLOW),
                    vues.stream().map(VehicleViews.View::kind).toList());
            assertNull(vues.get(0).eye(), "l'œil du joueur, celui de Minecraft");
            assertTrue(Double.isNaN(vues.get(1).distance()) && Double.isNaN(vues.get(1).height()),
                    "la poursuite se fait à la taille du véhicule");
            assertEquals(VehicleViews.DEFAULT_RECENTER_MS, vues.get(1).recenterMs());
        }
    }

    @Test
    @DisplayName("D3 : les vues déclarées remplacent les défauts, chacune servant ses sièges, la fixe à son node")
    void declarees() {
        Seating seating = compiler("{\"schema\": 5, \"asset\": \"m:a.glb\", \"kind\": \"rigid_object\", " + SIEGES
                + ", \"cameras\": ["
                + "{\"name\": \"capot\", \"kind\": \"fixed\", \"node\": \"cam_capot\", \"fov\": 80,"
                + " \"look\": {\"yaw\": 60, \"pitch\": 30}},"
                + "{\"name\": \"loin\", \"kind\": \"follow\", \"distance\": 9, \"height\": 2.5, \"recenter_ms\": 900},"
                + "{\"name\": \"pilote\", \"kind\": \"cockpit\", \"seats\": [\"conducteur\"], \"eye\": [0, 0.6, 0.1]}]}");
        VehicleViews views = seating.views();
        assertEquals(List.of("capot", "loin", "pilote"),
                views.forSeat(0).stream().map(VehicleViews.View::name).toList());
        assertEquals(List.of("capot", "loin"), views.forSeat(1).stream().map(VehicleViews.View::name).toList(),
                "le cockpit ne sert que le conducteur");
        VehicleViews.View capot = views.views().get(0);
        assertTrue(capot.node() >= 10, "la vue fixe porte le node que le natif a résolu");
        assertEquals(80, capot.fov());
        assertEquals(60, capot.lookYaw());
        VehicleViews.View loin = views.views().get(1);
        assertEquals(9, loin.distance());
        assertEquals(900, loin.recenterMs());
        assertEquals(VehicleViews.DEFAULT_SMOOTHING_MS, loin.smoothingMs());
        assertTrue(Double.isNaN(loin.fov()), "le champ de vision du joueur");
        VehicleViews.View pilote = views.views().get(2);
        assertArrayEquals(new float[] {0f, 0.6f, 0.1f}, pilote.eye());
        assertEquals(180, pilote.lookYaw(), "un cockpit regarde librement par défaut");
    }

    @Test
    @DisplayName("D1 : une vue fixe sans bornes de regard est figée")
    void fixeFigee() {
        VehicleViews views = compiler("{\"schema\": 5, \"asset\": \"m:a.glb\", \"kind\": \"rigid_object\", " + SIEGES
                + ", \"cameras\": [{\"name\": \"capot\", \"kind\": \"fixed\", \"node\": \"cam\"}]}").views();
        assertEquals(0, views.views().get(0).lookYaw());
        assertEquals(0, views.views().get(0).lookPitch());
    }
}
