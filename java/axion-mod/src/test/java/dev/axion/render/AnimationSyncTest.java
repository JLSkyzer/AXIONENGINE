package dev.axion.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.axion.net.AnimationLayers;
import dev.axion.net.AnimationLayers.Layer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-155, tranche 4 : ce que le client pousse de l'animation au contexte de rendu, et quand. */
class AnimationSyncTest {

    private static final int RECORD = AnimationCommands.HEADER_BYTES + AnimationCommands.COMMAND_BYTES;

    private static final AnimationLayers JOUE =
            AnimationLayers.NONE.with(0, new Layer(2, 100L, 0.5f, 2f, 0.75f, 0.25f));

    /** L'action de la commande de rang {@code rank}, aucune n'ayant de masque. */
    private static int action(AnimationCommands commands, int rank) {
        return ByteBuffer.wrap(commands.bytes()).order(ByteOrder.LITTLE_ENDIAN)
                .getInt(rank * RECORD + AnimationCommands.HEADER_BYTES + 8);
    }

    @Test
    @DisplayName("Une commande attend que le tick de rendu atteigne le sien, puis part une fois")
    void echeance() {
        AnimationSync sync = new AnimationSync();
        sync.see(42, 3, 7, JOUE);
        assertEquals(0, sync.flush(99.5).count(), "en avance de l'image");
        sync.see(42, 3, 7, JOUE);
        AnimationCommands commands = sync.flush(100.0);
        assertEquals(1, commands.count());
        ByteBuffer b = ByteBuffer.wrap(commands.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(42, b.getInt(0));
        assertEquals(3, b.getInt(8));
        assertEquals(7, b.getInt(12));
        assertEquals(100.0, b.getDouble(16));
        assertEquals(AnimationCommands.PLAY, b.getInt(24));
        assertEquals(2, b.getInt(32), "clip");
        assertEquals(2f, b.getFloat(44), "vitesse");
        assertEquals(0.5f, b.getFloat(52), "temps de clip");
        sync.see(42, 3, 7, JOUE);
        assertEquals(0, sync.flush(101.0).count(), "inchangée, rien ne repart");
    }

    @Test
    @DisplayName("Une assembly sans animation n'occupe rien ; arrêtée, elle donne STOP")
    void sansAnimation() {
        AnimationSync sync = new AnimationSync();
        sync.see(1, 0, 1, AnimationLayers.NONE);
        assertEquals(0, sync.flush(0.0).count());
        assertEquals(0, sync.tracked());

        sync.see(1, 0, 1, JOUE);
        sync.flush(200.0);
        sync.see(1, 0, 1, AnimationLayers.NONE.with(0, Layer.stopped(150L, 0.5f)));
        AnimationCommands commands = sync.flush(200.0);
        assertEquals(1, commands.count());
        assertEquals(AnimationCommands.STOP, action(commands, 0));
    }

    @Test
    @DisplayName("Non vue pendant 64 frames, l'assembly est oubliée du natif ; revue, elle rejoue son état")
    void oubli() {
        AnimationSync sync = new AnimationSync();
        sync.see(5, 0, 1, JOUE);
        assertEquals(1, sync.flush(500.0).count());
        for (int frame = 1; frame < AnimationSync.FORGET_AFTER_FRAMES; frame++) {
            assertEquals(0, sync.flush(500.0).count(), "frame " + frame);
        }
        AnimationCommands oubli = sync.flush(500.0);
        assertEquals(1, oubli.count());
        assertEquals(AnimationCommands.FORGET, action(oubli, 0));
        assertEquals(0, sync.tracked());

        sync.see(5, 0, 1, JOUE);
        AnimationCommands rejeu = sync.flush(500.0);
        assertEquals(1, rejeu.count());
        assertEquals(AnimationCommands.PLAY, action(rejeu, 0));
    }

    @Test
    @DisplayName("Un autre asset fait rejouer l'état entier, comme le natif repart de zéro")
    void autreAsset() {
        AnimationSync sync = new AnimationSync();
        sync.see(9, 0, 1, JOUE);
        assertEquals(1, sync.flush(500.0).count());
        sync.see(9, 4, 1, JOUE);
        AnimationCommands rejeu = sync.flush(500.0);
        assertEquals(1, rejeu.count());
        assertEquals(4, ByteBuffer.wrap(rejeu.bytes()).order(ByteOrder.LITTLE_ENDIAN).getInt(8));
    }
}
