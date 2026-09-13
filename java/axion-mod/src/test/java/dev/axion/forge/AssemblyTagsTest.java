package dev.axion.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-403, T-404 — C-50 : NBT de §22.2 et données d'apparition (R-703). */
class AssemblyTagsTest {

    @Test
    @DisplayName("T-403 : axion:v et axion:def s'écrivent, les autres clés d'AXION survivent")
    void nbtSansPerte() {
        CompoundTag charge = new CompoundTag();
        charge.putString("CustomName", "vanilla");
        charge.putInt("axion:v", 2);
        charge.putByteArray("axion:parts", new byte[] {1, 2, 3});

        CompoundTag gardees = AssemblyTags.extract(charge);
        assertFalse(gardees.contains("CustomName"), "une clé vanilla n'est pas à AXION");
        CompoundTag liees = AssemblyTags.bound(gardees, 0x1234L);

        CompoundTag sauvegarde = new CompoundTag();
        AssemblyTags.restore(liees, sauvegarde);
        assertEquals(2, sauvegarde.getInt("axion:v"));
        assertEquals(0x1234L, sauvegarde.getLong("axion:def"));
        // Écrite par un composant à venir, relue par celui-ci : intacte.
        assertEquals(3, sauvegarde.getByteArray("axion:parts").length);
    }

    @Test
    @DisplayName("T-403 : un schéma postérieur est réécrit tel qu'il a été lu (R-1710)")
    void schemaPosterieurConserve() {
        CompoundTag charge = new CompoundTag();
        charge.putInt("axion:v", 9);
        charge.putLong("axion:def", 77L);
        charge.putString("axion:inconnue", "à garder");

        CompoundTag gardees = AssemblyTags.extract(charge);
        assertEquals(9, AssemblyTags.version(gardees).orElseThrow());
        assertEquals(77L, AssemblyTags.definition(gardees).orElseThrow());

        CompoundTag sauvegarde = new CompoundTag();
        AssemblyTags.restore(gardees, sauvegarde);
        assertEquals(charge, sauvegarde);
    }

    @Test
    @DisplayName("T-403 : une clé mal typée ne se lit pas comme une valeur")
    void clesMalTypees() {
        CompoundTag gardees = new CompoundTag();
        gardees.putString("axion:v", "2");
        gardees.putInt("axion:def", 5);
        assertTrue(AssemblyTags.version(gardees).isEmpty());
        assertTrue(AssemblyTags.definition(gardees).isEmpty());
    }

    @Test
    @DisplayName("T-404 : les données d'apparition font l'aller-retour, bornées")
    void donneesDApparition() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        AssemblyTags.writeSpawn(buffer, new AssemblyTags.SpawnState("demo:caisse", true));
        AssemblyTags.SpawnState lu = AssemblyTags.readSpawn(buffer);
        assertEquals("demo:caisse", lu.definitionId());
        assertTrue(lu.inert());

        // Une chaîne annoncée au-delà de la borne est refusée à la lecture.
        FriendlyByteBuf hostile = new FriendlyByteBuf(Unpooled.buffer());
        hostile.writeVarInt(AssemblyTags.MAX_ID_CHARS * 4 + 1);
        assertThrows(RuntimeException.class, () -> AssemblyTags.readSpawn(hostile));
    }
}
