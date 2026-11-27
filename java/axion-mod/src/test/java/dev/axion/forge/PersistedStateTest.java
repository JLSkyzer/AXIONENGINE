package dev.axion.forge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.axion.entity.AssemblyBinding;
import java.util.Arrays;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import org.joml.Quaternionf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-433, T-434, et le schéma 3 de §22.2 (ADR-153 D2, D5). */
class PersistedStateTest {

    private static PersistedState etat() {
        return new PersistedState(
                new double[] {1.5, 64.0, -3.25},
                new float[] {0f, 0.38268343f, 0f, 0.9238795f},
                new float[] {0.5f, -2.0f, 0.125f},
                new float[] {0f, 1.25f, -0.75f},
                true,
                List.of(
                        new PersistedState.Body(7, 2.5, 63.0, -3.0, new float[] {0.5f, 0.5f, 0.5f, 0.5f},
                                new float[] {1f, 2f, 3f}, new float[] {-1f, -2f, -3f}),
                        new PersistedState.Body(9, 30_000_000.25, -60.5, 12.0, new float[] {0f, 0f, 0f, 1f},
                                new float[] {0f, 0f, 0f}, new float[] {0f, 0f, 0.5f})),
                List.of(new PersistedState.Joint(true, 0f), new PersistedState.Joint(false, 0.5f)));
    }

    private static void memesEtats(PersistedState attendu, PersistedState lu) {
        assertArrayEquals(attendu.position(), lu.position());
        assertArrayEquals(attendu.rotation(), lu.rotation(), "au bit près, sans renormalisation");
        assertArrayEquals(attendu.linear(), lu.linear());
        assertArrayEquals(attendu.angular(), lu.angular());
        assertEquals(attendu.sleeping(), lu.sleeping());
        assertEquals(attendu.bodies().size(), lu.bodies().size());
        for (int i = 0; i < attendu.bodies().size(); i++) {
            PersistedState.Body a = attendu.bodies().get(i);
            PersistedState.Body b = lu.bodies().get(i);
            assertEquals(a.node(), b.node());
            assertEquals(a.x(), b.x());
            assertEquals(a.y(), b.y());
            assertEquals(a.z(), b.z());
            assertArrayEquals(a.rotation(), b.rotation());
            assertArrayEquals(a.linear(), b.linear());
            assertArrayEquals(a.angular(), b.angular());
        }
        assertEquals(attendu.joints(), lu.joints());
    }

    @Test
    @DisplayName("ADR-153 D2 : l'état écrit se relit au bit près, corps secondaires et joints compris")
    void allerRetour() {
        CompoundTag gardees = new CompoundTag();
        etat().write(gardees);
        assertEquals(2 * PersistedState.Body.BYTES, gardees.getByteArray(PersistedState.BODIES).length);
        assertEquals(2 * PersistedState.Joint.BYTES, gardees.getByteArray(PersistedState.JOINTS).length);
        memesEtats(etat(), PersistedState.read(gardees).orElseThrow());
    }

    @Test
    @DisplayName("ADR-153 D2 : la position monde d'un corps loin de l'origine garde ses décimales (f64)")
    void positionsEnDouble() {
        CompoundTag gardees = new CompoundTag();
        etat().write(gardees);
        // 30 000 000,25 n'est pas représentable en f32, dont le pas y vaut 2.
        assertEquals(30_000_000.25, PersistedState.read(gardees).orElseThrow().bodies().get(1).x());
    }

    @Test
    @DisplayName("ADR-153 D2 : les octets d'un corps et d'un joint sont ceux du format, en little-endian")
    void disposition() {
        CompoundTag gardees = new CompoundTag();
        new PersistedState(new double[3], new float[] {0f, 0f, 0f, 1f}, new float[3], new float[3], false,
                List.of(new PersistedState.Body(0x01020304, 1.0, 0.0, 0.0, new float[] {0f, 0f, 0f, 1f},
                        new float[3], new float[3])),
                List.of(new PersistedState.Joint(true, 1.0f)))
                .write(gardees);
        byte[] corps = gardees.getByteArray(PersistedState.BODIES);
        assertEquals(68, corps.length);
        assertArrayEquals(new byte[] {4, 3, 2, 1}, Arrays.copyOfRange(corps, 0, 4), "node u32 LE");
        // 1.0 en f64 LE : 00 00 00 00 00 00 F0 3F.
        assertEquals((byte) 0xF0, corps[4 + 6]);
        assertEquals((byte) 0x3F, corps[4 + 7]);
        byte[] joint = gardees.getByteArray(PersistedState.JOINTS);
        // Drapeaux, trois réservés, puis 1.0 en f32 LE : 00 00 80 3F.
        assertArrayEquals(new byte[] {1, 0, 0, 0, 0, 0, (byte) 0x80, 0x3F}, joint);
    }

    @Test
    @DisplayName("ADR-153 D3 : l'entité posée ailleurs emporte ses corps ; à sa place sauvée, rien ne change")
    void deplaceAvecLEntite() {
        PersistedState etat = etat();
        assertSame(etat, etat.movedTo(1.5, 64.0, -3.25), "à sa place : le même état, au bit près");
        PersistedState ailleurs = etat.movedTo(11.5, 70.0, -3.25);
        assertArrayEquals(new double[] {11.5, 70.0, -3.25}, ailleurs.position());
        assertEquals(12.5, ailleurs.bodies().get(0).x());
        assertEquals(69.0, ailleurs.bodies().get(0).y());
        assertEquals(-3.0, ailleurs.bodies().get(0).z());
        assertEquals(30_000_010.25, ailleurs.bodies().get(1).x(), "chaque corps du même décalage");
        assertSame(etat.rotation(), ailleurs.rotation());
        assertEquals(etat.joints(), ailleurs.joints());
        assertEquals(7, ailleurs.bodies().get(0).node());
    }

    @Test
    @DisplayName("ADR-153 D2 : le sommeil est le bit 0 de axion:flags, les bits réservés restent")
    void drapeaux() {
        CompoundTag gardees = new CompoundTag();
        gardees.putInt(PersistedState.FLAGS, 0b100);
        etat().write(gardees);
        assertEquals(0b101, gardees.getInt(PersistedState.FLAGS));
        PersistedState eveillee = new PersistedState(etat().position(), etat().rotation(), etat().linear(),
                etat().angular(), false, List.of(), List.of());
        eveillee.write(gardees);
        assertEquals(0b100, gardees.getInt(PersistedState.FLAGS));
        assertFalse(PersistedState.read(gardees).orElseThrow().sleeping());
    }

    @Test
    @DisplayName("ADR-153 D2 : un état illisible n'est pas lu — l'assembly repart de sa pose de repos")
    void illisible() {
        CompoundTag base = new CompoundTag();
        etat().write(base);

        CompoundTag courte = base.copy();
        courte.putByteArray(PersistedState.BODIES, new byte[PersistedState.Body.BYTES - 1]);
        assertTrue(PersistedState.read(courte).isEmpty(), "corps tronqué");

        CompoundTag joint = base.copy();
        joint.putByteArray(PersistedState.JOINTS, new byte[PersistedState.Joint.BYTES + 1]);
        assertTrue(PersistedState.read(joint).isEmpty(), "joint tronqué");

        CompoundTag nul = base.copy();
        nul.putByteArray(PersistedState.BODIES, new byte[PersistedState.Body.BYTES]);
        assertTrue(PersistedState.read(nul).isEmpty(), "quaternion nul");

        CompoundTag infinie = base.copy();
        ListTag position = new ListTag();
        position.add(DoubleTag.valueOf(0.0));
        position.add(DoubleTag.valueOf(Double.POSITIVE_INFINITY));
        position.add(DoubleTag.valueOf(0.0));
        infinie.put(PersistedState.POSITION, position);
        assertTrue(PersistedState.read(infinie).isEmpty(), "position non finie");

        CompoundTag sansDrapeaux = base.copy();
        sansDrapeaux.remove(PersistedState.FLAGS);
        assertTrue(PersistedState.read(sansDrapeaux).isEmpty(), "sans axion:flags");

        CompoundTag sansVitesse = base.copy();
        sansVitesse.remove(PersistedState.LINEAR);
        assertTrue(PersistedState.read(sansVitesse).isEmpty(), "sans axion:lvel");
    }

    @Test
    @DisplayName("T-434 : un NBT au schéma 2 se migre au 3, sans état inventé ni clé perdue")
    void migration2Vers3() {
        CompoundTag v2 = new CompoundTag();
        v2.putInt("axion:v", 2);
        v2.putLong("axion:def", 0x1234L);
        AssemblyTags.putRotation(v2, new Quaternionf(0f, 0.38268343f, 0f, 0.9238795f));
        v2.putByteArray("axion:parts", new byte[] {1, 2, 3});

        CompoundTag v3 = AssemblyTags.migrate(v2);
        assertEquals(AssemblyBinding.NBT_SCHEMA, AssemblyTags.version(v3).orElseThrow());
        assertEquals(0x1234L, AssemblyTags.definition(v3).orElseThrow());
        assertEquals(v2.get("axion:rot"), v3.get("axion:rot"));
        assertArrayEquals(new byte[] {1, 2, 3}, v3.getByteArray("axion:parts"), "une clé inconnue reste");
        assertTrue(PersistedState.read(v3).isEmpty(), "le schéma 2 n'avait ni vitesses ni corps : rien d'inventé");
        assertEquals(2, v2.getInt("axion:v"), "l'original n'est pas touché");
    }

    @Test
    @DisplayName("ADR-163 D2, ADR-167 D7 : un NBT au schéma 3 se migre au 5, sans variable ni modificateur inventés")
    void migration3Vers4() {
        CompoundTag v3 = new CompoundTag();
        v3.putInt("axion:v", 3);
        v3.putLong("axion:def", 0x1234L);
        v3.putByteArray("axion:parts", new byte[] {4, 5});
        CompoundTag v5 = AssemblyTags.migrate(v3);
        assertEquals(6, AssemblyTags.version(v5).orElseThrow());
        assertEquals(AssemblyBinding.NBT_SCHEMA, AssemblyTags.version(v5).orElseThrow());
        assertArrayEquals(new byte[] {4, 5}, v5.getByteArray("axion:parts"), "une clé inconnue reste");
        assertTrue(AssemblyTags.variables(v5).isEmpty(), "le schéma 3 n'avait pas de variables : rien d'inventé");
        assertTrue(AssemblyTags.parameters(v5).isEmpty(), "ni de modificateurs");
        assertEquals(3, v3.getInt("axion:v"), "l'original n'est pas touché");
    }

    @Test
    @DisplayName("ADR-167 D7 : un NBT au schéma 4 se migre au 5, ses variables et toute clé gardées")
    void migration4Vers5() {
        CompoundTag v4 = new CompoundTag();
        v4.putInt("axion:v", 4);
        v4.putLong("axion:def", 0x1234L);
        AssemblyTags.putVariables(v4, java.util.Map.of("gyrophare", 1.0));
        CompoundTag v5 = AssemblyTags.migrate(v4);
        assertEquals(6, AssemblyTags.version(v5).orElseThrow());
        assertEquals(java.util.Map.of("gyrophare", 1.0), AssemblyTags.variables(v5));
        assertTrue(AssemblyTags.parameters(v5).isEmpty());
        assertEquals(4, v4.getInt("axion:v"), "l'original n'est pas touché");
    }

    @Test
    @DisplayName("ADR-175 D1 : un NBT au schéma 5 se migre au 6 sans nom inventé ; ses corps et ses joints restent sans nom")
    void migration5Vers6() {
        CompoundTag v5 = new CompoundTag();
        v5.putInt("axion:v", 5);
        v5.putLong("axion:def", 0x1234L);
        v5.putByteArray("axion:parts", new byte[] {9});
        new PersistedState(new double[] {1, 2, 3}, new float[] {0f, 0f, 0f, 1f}, new float[3], new float[3], false,
                List.of(new PersistedState.Body(7, 2.5, 63.0, -3.0, new float[] {0f, 0f, 0f, 1f}, new float[3],
                        new float[3])), List.of(new PersistedState.Joint(true, 0.5f))).write(v5);
        CompoundTag v6 = AssemblyTags.migrate(v5);
        assertEquals(6, AssemblyTags.version(v6).orElseThrow());
        assertArrayEquals(new byte[] {9}, v6.getByteArray("axion:parts"), "une clé inconnue reste");
        assertTrue(!v6.contains("axion:body_names") && !v6.contains("axion:joint_names"), "aucun nom inventé");
        PersistedState lu = PersistedState.read(v6).orElseThrow();
        assertEquals(7, lu.bodies().get(0).node());
        assertNull(lu.bodies().get(0).name());
        assertNull(lu.joints().get(0).name());
        assertEquals(5, v5.getInt("axion:v"), "l'original n'est pas touché");
    }

    @Test
    @DisplayName("ADR-175 D1 : les noms des corps et des joints voyagent avec leur état, et se relisent")
    void nomsGardes() {
        CompoundTag stored = new CompoundTag();
        PersistedState attendu = new PersistedState(new double[] {1, 2, 3}, new float[] {0f, 0f, 0f, 1f},
                new float[3], new float[3], false,
                List.of(new PersistedState.Body(7, "capot", 2.5, 63.0, -3.0, new float[] {0f, 0f, 0f, 1f},
                        new float[3], new float[3])),
                List.of(new PersistedState.Joint(true, 0.5f, "charniere_capot"),
                        new PersistedState.Joint(false, 0f, "attelage")));
        attendu.write(stored);
        assertEquals("capot", stored.getList("axion:body_names", 8).getString(0));
        PersistedState lu = PersistedState.read(stored).orElseThrow();
        assertEquals("capot", lu.bodies().get(0).name());
        assertEquals(List.of("charniere_capot", "attelage"), lu.joints().stream().map(PersistedState.Joint::name).toList());
        // Un état dont un élément n'a pas de nom n'écrit aucun nom, et retire ceux d'avant.
        new PersistedState(new double[] {1, 2, 3}, new float[] {0f, 0f, 0f, 1f}, new float[3], new float[3], false,
                List.of(new PersistedState.Body(7, 2.5, 63.0, -3.0, new float[] {0f, 0f, 0f, 1f}, new float[3],
                        new float[3])), List.of()).write(stored);
        assertTrue(!stored.contains("axion:body_names") && !stored.contains("axion:joint_names"));
        // Des noms en nombre différent de leurs éléments ne se lisent pas : l'état reste lu, sans noms.
        CompoundTag incoherent = new CompoundTag();
        attendu.write(incoherent);
        incoherent.getList("axion:body_names", 8).add(net.minecraft.nbt.StringTag.valueOf("de trop"));
        assertNull(PersistedState.read(incoherent).orElseThrow().bodies().get(0).name());
    }

    @Test
    @DisplayName("ADR-167 D7 : axion:params garde les modificateurs dans leur ordre ; une entrée illisible est ignorée")
    void modificateursGardes() {
        List<dev.axion.api.assembly.Modifier> poses = List.of(
                new dev.axion.api.assembly.Modifier("upgrades:stage1", "engine.torque",
                        dev.axion.api.assembly.Modifier.Operation.MULTIPLY_BASE, 0.25),
                new dev.axion.api.assembly.Modifier("garage:allegement", "mass",
                        dev.axion.api.assembly.Modifier.Operation.ADD, -80));
        CompoundTag stored = new CompoundTag();
        AssemblyTags.putParameters(stored, poses);
        assertEquals(poses, AssemblyTags.parameters(stored));
        CompoundTag illisible = new CompoundTag();
        illisible.putString("cause", "upgrades:x");
        illisible.putString("parameter", "mass");
        illisible.putString("operation", "divide");
        illisible.putDouble("amount", 2.0);
        stored.getList("axion:params", 10).add(illisible);
        assertEquals(poses, AssemblyTags.parameters(stored), "une opération inconnue ne décrit aucun modificateur");
        AssemblyTags.putParameters(stored, List.of());
        assertTrue(!stored.contains("axion:params"), "sans modificateur, pas de clé");
    }

    @Test
    @DisplayName("ADR-163 D2 : axion:vars garde chaque variable par nom, et celles que la definition ne déclare plus")
    void variablesGardees() {
        CompoundTag stored = new CompoundTag();
        stored.put("axion:vars", new CompoundTag());
        stored.getCompound("axion:vars").putDouble("ancienne", 7.0);
        stored.getCompound("axion:vars").putString("illisible", "x");
        AssemblyTags.putVariables(stored, java.util.Map.of("gyrophare", 1.0, "phares", 2.0));
        java.util.Map<String, Double> relues = AssemblyTags.variables(stored);
        assertEquals(java.util.Map.of("ancienne", 7.0, "gyrophare", 1.0, "phares", 2.0), relues,
                "une entrée qui n'est pas un double est ignorée ; une variable disparue reste (R-1710)");
        CompoundTag vide = new CompoundTag();
        AssemblyTags.putVariables(vide, java.util.Map.of());
        assertTrue(!vide.contains("axion:vars"), "sans variable gardée, pas de clé");
    }

    @Test
    @DisplayName("T-433 : un schéma postérieur, ou antérieur à 2, traverse la migration intact (R-1710)")
    void autresVersionsIntactes() {
        for (int version : new int[] {1, 6, 7, 9}) {
            CompoundTag gardees = new CompoundTag();
            gardees.putInt("axion:v", version);
            gardees.putLong("axion:def", 77L);
            gardees.putString("axion:inconnue", "à garder");
            assertSame(gardees, AssemblyTags.migrate(gardees), "schéma " + version);
            assertEquals(version, gardees.getInt("axion:v"));
        }
        CompoundTag sansVersion = new CompoundTag();
        assertSame(sansVersion, AssemblyTags.migrate(sansVersion));
    }

    @Test
    @DisplayName("ADR-175 D3 : une migration renomme ce que l'état nomme — corps, joints, nodes, variables, sièges, sockets, paramètres")
    void migrationRenomme() {
        CompoundTag stored = new CompoundTag();
        new PersistedState(new double[] {1, 2, 3}, new float[] {0f, 0f, 0f, 1f}, new float[3], new float[3], false,
                List.of(new PersistedState.Body(7, "porte_g", 2.5, 63.0, -3.0, new float[] {0f, 0f, 0f, 1f},
                        new float[3], new float[3])), List.of(new PersistedState.Joint(true, 0.5f, "a"))).write(stored);
        AssemblyTags.putVariables(stored, java.util.Map.of("phare", 1.0, "intacte", 2.0));
        AssemblyTags.putSeatVariables(stored, java.util.Map.of("radio", java.util.Map.of("avant", 3.0)));
        CompoundTag nodes = new CompoundTag();
        CompoundTag etat = new CompoundTag();
        etat.putBoolean("hidden", true);
        etat.putString("swap", "porte_g");
        nodes.put("vitre", etat);
        nodes.put("porte_g", new CompoundTag());
        stored.put("axion:nodes", nodes);
        net.minecraft.nbt.ListTag atts = new net.minecraft.nbt.ListTag();
        CompoundTag attache = new CompoundTag();
        attache.putString("socket", "crochet");
        atts.add(attache);
        stored.put("axion:atts", atts);
        net.minecraft.nbt.ListTag params = new net.minecraft.nbt.ListTag();
        CompoundTag modificateur = new CompoundTag();
        modificateur.putString("parameter", "poids");
        params.add(modificateur);
        stored.put("axion:params", params);
        // ADR-176 D4 : l'usure des pneus se nomme par roue, et une roue se nomme par son node.
        AssemblyTags.putWheels(stored, java.util.Map.of("porte_g", 0.25, "autre", 0.5));

        dev.axion.definition.Revisions revisions = dev.axion.definition.Revisions.of(
                com.google.gson.JsonParser.parseString("{\"schema\": 7, \"revision\": 3, \"migrations\": ["
                        + "{\"from\": 1, \"rename\": {\"nodes\": {\"porte_g\": \"portiere_gauche\"}, \"joints\":"
                        + " {\"a\": \"charniere\"}, \"variables\": {\"phare\": \"feux\"}, \"seats\":"
                        + " {\"avant\": \"conducteur\"}, \"sockets\": {\"crochet\": \"attelage\"}}},"
                        + " {\"from\": 2, \"rename\": {\"parameters\": {\"poids\": \"mass\"},"
                        + " \"nodes\": {\"portiere_gauche\": \"porte_conducteur\"}}}]}").getAsJsonObject());
        java.util.List<String> notes = new java.util.ArrayList<>();
        AssemblyTags.evolve(stored, revisions, "sha-neuf", 99L, notes::add);

        PersistedState lu = PersistedState.read(stored).orElseThrow();
        assertEquals("porte_conducteur", lu.bodies().get(0).name(), "deux migrations s'enchaînent");
        assertEquals("charniere", lu.joints().get(0).name());
        assertEquals(java.util.Map.of("feux", 1.0, "intacte", 2.0), AssemblyTags.variables(stored));
        assertEquals(java.util.Map.of("radio", java.util.Map.of("conducteur", 3.0)), AssemblyTags.seatVariables(stored));
        assertTrue(stored.getCompound("axion:nodes").contains("porte_conducteur")
                && !stored.getCompound("axion:nodes").contains("porte_g"));
        assertEquals("porte_conducteur", stored.getCompound("axion:nodes").getCompound("vitre").getString("swap"));
        assertEquals(java.util.Map.of("porte_conducteur", 0.25, "autre", 0.5), AssemblyTags.wheels(stored),
                "l'usure d'une roue renommée la suit");
        assertEquals("attelage", stored.getList("axion:atts", 10).getCompound(0).getString("socket"));
        assertEquals("mass", stored.getList("axion:params", 10).getCompound(0).getString("parameter"));
        assertEquals(99L, AssemblyTags.definition(stored).orElseThrow(), "liée à l'identifiant que la definition a pris");
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).startsWith("révision 1 → 3 : 2 migration(s)"), notes.get(0));
    }

    @Test
    @DisplayName("ADR-175 D3 : les renommages d'une migration valent tous à la fois — deux noms s'échangent, une chaîne ne perd rien")
    void migrationEchangeEtChaine() {
        CompoundTag stored = new CompoundTag();
        AssemblyTags.putVariables(stored, java.util.Map.of("gauche", 1.0, "droite", 2.0, "c", 3.0, "d", 4.0));
        AssemblyTags.putSeatVariables(stored, java.util.Map.of("radio", java.util.Map.of("avant", 5.0, "arriere", 6.0)));
        CompoundTag nodes = new CompoundTag();
        CompoundTag cache = new CompoundTag();
        cache.putBoolean("hidden", true);
        CompoundTag montre = new CompoundTag();
        montre.putBoolean("hidden", false);
        nodes.put("porte_g", cache);
        nodes.put("porte_d", montre);
        stored.put("axion:nodes", nodes);
        AssemblyTags.putWheels(stored, java.util.Map.of("porte_g", 0.25, "porte_d", 0.5));

        dev.axion.definition.Revisions revisions = dev.axion.definition.Revisions.of(
                com.google.gson.JsonParser.parseString("{\"schema\": 7, \"revision\": 2, \"migrations\": ["
                        + "{\"from\": 1, \"rename\": {\"variables\": {\"gauche\": \"droite\", \"droite\": \"gauche\","
                        + " \"c\": \"d\", \"d\": \"e\"}, \"seats\": {\"avant\": \"arriere\", \"arriere\": \"avant\"},"
                        + " \"nodes\": {\"porte_g\": \"porte_d\", \"porte_d\": \"porte_g\"}}}]}").getAsJsonObject());
        java.util.List<String> notes = new java.util.ArrayList<>();
        AssemblyTags.evolve(stored, revisions, "sha", 1L, notes::add);

        assertEquals(java.util.Map.of("gauche", 2.0, "droite", 1.0, "d", 3.0, "e", 4.0),
                AssemblyTags.variables(stored));
        assertEquals(java.util.Map.of("radio", java.util.Map.of("avant", 6.0, "arriere", 5.0)),
                AssemblyTags.seatVariables(stored));
        assertTrue(stored.getCompound("axion:nodes").getCompound("porte_d").getBoolean("hidden"));
        assertTrue(!stored.getCompound("axion:nodes").getCompound("porte_g").getBoolean("hidden"));
        assertEquals(java.util.Map.of("porte_d", 0.25, "porte_g", 0.5), AssemblyTags.wheels(stored));
        assertTrue(notes.get(0).endsWith("10 nom(s) changé(s)"), notes.get(0));
    }

    @Test
    @DisplayName("ADR-182 D6 : une migration renomme les réserves d'énergie gardées")
    void migrationDesReserves() {
        CompoundTag stored = new CompoundTag();
        AssemblyTags.putEnergy(stored, java.util.Map.of("reservoir", 12.5, "citerne", 30.0));
        dev.axion.definition.Revisions revisions = dev.axion.definition.Revisions.of(
                com.google.gson.JsonParser.parseString("{\"schema\": 8, \"revision\": 2, \"migrations\": ["
                        + "{\"from\": 1, \"rename\": {\"stores\": {\"reservoir\": \"gazole\"}}}]}").getAsJsonObject());
        java.util.List<String> notes = new java.util.ArrayList<>();
        AssemblyTags.evolve(stored, revisions, "sha", 1L, notes::add);

        assertEquals(java.util.Map.of("gazole", 12.5, "citerne", 30.0), AssemblyTags.energy(stored));
        assertTrue(notes.get(0).endsWith("1 nom(s) changé(s)"), notes.get(0));
    }

    @Test
    @DisplayName("ADR-175 D3 : un état déjà à la révision courante n'est pas migré ; un contenu changé sans révision, ou un état plus récent, se dit")
    void evolutionDit() {
        dev.axion.definition.Revisions revisions = dev.axion.definition.Revisions.of(
                com.google.gson.JsonParser.parseString("{\"schema\": 7, \"revision\": 2, \"migrations\": ["
                        + "{\"from\": 1, \"rename\": {\"variables\": {\"a\": \"b\"}}}]}").getAsJsonObject());
        java.util.function.BiFunction<Integer, String, java.util.List<String>> evoluer = (revision, sha) -> {
            CompoundTag stored = new CompoundTag();
            AssemblyTags.putVariables(stored, java.util.Map.of("a", 1.0));
            if (revision != null) {
                AssemblyTags.putRevision(stored, revision, sha);
            }
            java.util.List<String> notes = new java.util.ArrayList<>();
            AssemblyTags.evolve(stored, revisions, "sha-courant", 5L, notes::add);
            return notes;
        };
        assertTrue(evoluer.apply(2, "sha-courant").isEmpty(), "à la révision courante, au même contenu : rien à dire");
        assertTrue(evoluer.apply(2, "sha-ancien").get(0).contains("sans nouvelle révision"));
        assertTrue(evoluer.apply(3, "x").get(0).contains("plus récente"));
        assertTrue(evoluer.apply(null, null).get(0).startsWith("révision 1 → 2"), "sans révision gardée : la première");
    }

    @Test
    @DisplayName("ADR-175 D3 : la révision et l'empreinte se gardent et se relisent")
    void revisionGardee() {
        CompoundTag stored = new CompoundTag();
        assertTrue(AssemblyTags.revision(stored).isEmpty() && AssemblyTags.sha(stored).isEmpty());
        AssemblyTags.putRevision(stored, 4, "abc123");
        assertEquals(4, AssemblyTags.revision(stored).getAsInt());
        assertEquals("abc123", AssemblyTags.sha(stored).orElseThrow());
    }
}
