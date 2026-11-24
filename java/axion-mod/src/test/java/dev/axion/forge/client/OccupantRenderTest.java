package dev.axion.forge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-177 D1 : la mise à l'échelle d'un occupant se fait autour du point de son siège, et se défait exactement. */
class OccupantRenderTest {

    private static Vector3f image(PoseStack stack, float x, float y, float z) {
        return stack.last().pose().transformPosition(new Vector3f(x, y, z));
    }

    @Test
    @DisplayName("le point du siège ne bouge pas, un point au-dessus s'éloigne de lui de l'échelle")
    void lePointDuSiegeResteEnPlace() {
        PoseStack stack = new PoseStack();
        double siege = 0.35; // le décalage de monture d'un joueur, 0,35 sous le siège
        OccupantRender.about(stack, siege, 0.5f);

        Vector3f point = image(stack, 0f, (float) siege, 0f);
        assertEquals(0f, point.x, 1e-6f);
        assertEquals(siege, point.y, 1e-6f, "le point du siège reste où il est");

        Vector3f tete = image(stack, 0f, (float) siege + 1.27f, 0f);
        assertEquals(siege + 1.27 * 0.5, tete.y, 1e-6f, "1,27 m au-dessus du siège devient 0,635 m");
        Vector3f pieds = image(stack, 0f, 0f, 0f);
        assertEquals(siege * 0.5, pieds.y, 1e-6f, "les pieds se rapprochent du siège avec le reste du corps");
    }

    @Test
    @DisplayName("l'échelle inverse défait la première : la matrice revient à elle-même")
    void l_echelleInverseLaDefait() {
        PoseStack stack = new PoseStack();
        stack.translate(3.0, 64.0, -7.0);
        Vector3f avant = image(stack, 0.2f, 1.1f, -0.4f);
        OccupantRender.about(stack, 0.35, 0.67f);
        OccupantRender.about(stack, 0.35, 1f / 0.67f);
        Vector3f apres = image(stack, 0.2f, 1.1f, -0.4f);
        assertEquals(avant.x, apres.x, 1e-5f);
        assertEquals(avant.y, apres.y, 1e-5f);
        assertEquals(avant.z, apres.z, 1e-5f);
    }

    @Test
    @DisplayName("debout, les pieds sont au siège : l'échelle les laisse en place")
    void deboutLesPiedsRestent() {
        PoseStack stack = new PoseStack();
        OccupantRender.about(stack, 0.0, 2f);
        assertEquals(0f, image(stack, 0f, 0f, 0f).y, 1e-6f);
        assertEquals(3.6f, image(stack, 0f, 1.8f, 0f).y, 1e-6f, "un joueur de 1,8 m en fait 3,6");
    }
}
