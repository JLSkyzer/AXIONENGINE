package dev.axion.forge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;

/**
 * Scénarios de gameplay déclaratifs (§29.5, ADR-124) : chaque fichier
 * {@code data/axion/gametest/scenarios/<domaine>/<nom>.json} devient le GameTest
 * {@code scenario_<domaine>_<nom>}. Un scénario invalide échoue sous son nom, il ne disparaît pas.
 *
 * <p>Contenu de développement, jamais empaqueté (R-1790).
 */
@GameTestHolder("axion")
public final class ScenarioGameTests {

    private static final String LOT = "axion_scenarios";

    /** Gabarit d'un scénario illisible, qui échoue au premier tick. */
    private static final String GABARIT_PAR_DEFAUT = "axion:test/plancher";

    private static final int MARGE_TICKS = 20;

    /** Décalage des GameTests : la première couche du gabarit est en y = 1 (ADR-124 §2). */
    private static final Vec3 DECALAGE = new Vec3(0, 1, 0);

    private static final Set<String> CLES_SCENARIO =
            Set.of("structure", "setup", "inputs", "asserts", "tolerance", "description");

    /** Vérifications de l'exemple du §29.5 qui attendent leurs composants (C-33, M6, M7). */
    private static final Set<String> CLES_A_VENIR = Set.of("part", "deform_max", "stage", "structure", "debris_count");

    private ScenarioGameTests() {}

    /** {@return un GameTest par fichier de scénario, dans l'ordre de leurs chemins} */
    @GameTestGenerator
    public static Collection<TestFunction> scenarios() {
        Path racine = ModList.get().getModFileById("axion").getFile()
                .findResource("data", "axion", "gametest", "scenarios");
        List<TestFunction> tests = new ArrayList<>();
        if (!Files.isDirectory(racine)) {
            return tests;
        }
        List<Path> fichiers;
        try (Stream<Path> parcours = Files.walk(racine)) {
            fichiers = parcours.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        } catch (IOException echec) {
            throw new IllegalStateException("scénarios illisibles : " + echec, echec);
        }
        for (Path fichier : fichiers) {
            String relatif = racine.relativize(fichier).toString().replace('\\', '/');
            String nom = "scenario_" + relatif.substring(0, relatif.length() - ".json".length())
                    .replace('/', '_').toLowerCase(Locale.ROOT);
            Scenario scenario;
            try (Reader lecteur = Files.newBufferedReader(fichier, StandardCharsets.UTF_8)) {
                scenario = Scenario.lire(JsonParser.parseReader(lecteur));
            } catch (IOException | RuntimeException echec) {
                String raison = relatif + " : " + echec.getMessage();
                tests.add(new TestFunction(LOT, nom, GABARIT_PAR_DEFAUT, MARGE_TICKS, 0L, true, helper -> {
                    throw new GameTestAssertException(raison);
                }));
                continue;
            }
            tests.add(new TestFunction(
                    LOT, nom, scenario.structure(), scenario.dernierTick() + MARGE_TICKS, 0L, true, scenario::jouer));
        }
        return tests;
    }

    /** Une action de mise en place. */
    private sealed interface Action permits Apparition, Remplissage {}

    /** {@code spawn} : une assembly, en coordonnées du gabarit. */
    private record Apparition(String definition, Vec3 position, float[] rotation) implements Action {}

    /** {@code fill} : un pavé de blocs, bornes comprises, en coordonnées du gabarit. */
    private record Remplissage(BlockPos debut, BlockPos fin, BlockState bloc) implements Action {}

    /** Une vérification, jouée à chaque tick de {@code [debut, fin]}. */
    private sealed interface Verification permits Position, VitesseMax, HauteurMin {

        int debut();

        int fin();

        int assembly();

        /**
         * @param tick le tick du test
         * @param position position de l'assembly, en coordonnées du gabarit
         * @param precedente sa position au tick précédent
         * @param tolerance {@code tolerance.position}, en blocs
         * @throws GameTestAssertException si elle échoue
         */
        void verifier(int tick, Vec3 position, Vec3 precedente, double tolerance);
    }

    private record Position(int debut, int fin, int assembly, Vec3 attendue) implements Verification {
        @Override
        public void verifier(int tick, Vec3 position, Vec3 precedente, double tolerance) {
            double ecart = position.distanceTo(attendue);
            if (ecart > tolerance) {
                throw new GameTestAssertException(String.format(Locale.ROOT,
                        "tick %d, assembly %d : en %s, à %.4f m de %s (tolérance %.4f m)",
                        tick, assembly, texte(position), ecart, texte(attendue), tolerance));
            }
        }
    }

    private record VitesseMax(int debut, int fin, int assembly, double max) implements Verification {
        @Override
        public void verifier(int tick, Vec3 position, Vec3 precedente, double tolerance) {
            double vitesse = position.distanceTo(precedente) * 20.0;
            if (vitesse > max) {
                throw new GameTestAssertException(String.format(Locale.ROOT,
                        "tick %d, assembly %d : %.4f m/s, au plus %.4f attendus", tick, assembly, vitesse, max));
            }
        }
    }

    private record HauteurMin(int debut, int fin, int assembly, double y) implements Verification {
        @Override
        public void verifier(int tick, Vec3 position, Vec3 precedente, double tolerance) {
            if (position.y < y - tolerance) {
                throw new GameTestAssertException(String.format(Locale.ROOT,
                        "tick %d, assembly %d : y = %.4f, sous %.4f (tolérance %.4f m)",
                        tick, assembly, position.y, y, tolerance));
            }
        }
    }

    /** Un scénario lu et validé (ADR-124, version 1). */
    private record Scenario(
            String structure, List<Action> miseEnPlace, List<Verification> verifications, double tolerance, int dernierTick) {

        static Scenario lire(JsonElement element) {
            JsonObject racine = objet(element, "le scénario");
            cles(racine, "le scénario", CLES_SCENARIO);
            String structure = chaine(racine, "structure", "le scénario");
            if (ResourceLocation.tryParse(structure) == null) {
                throw new IllegalArgumentException("structure : identifiant invalide « " + structure + " »");
            }

            List<Action> miseEnPlace = new ArrayList<>();
            int assemblies = 0;
            for (JsonElement brut : tableau(racine, "setup", "le scénario")) {
                JsonObject action = objet(brut, "setup");
                if (action.has("spawn")) {
                    cles(action, "setup.spawn", Set.of("spawn", "at", "rot"));
                    float[] rotation = action.has("rot") ? quaternion(action.get("rot")) : new float[] {0f, 0f, 0f, 1f};
                    miseEnPlace.add(new Apparition(
                            chaine(action, "spawn", "setup"), vecteur(action.get("at"), "setup.at"), rotation));
                    assemblies++;
                } else if (action.has("fill")) {
                    cles(action, "setup.fill", Set.of("fill"));
                    JsonObject fill = objet(action.get("fill"), "setup.fill");
                    cles(fill, "setup.fill", Set.of("from", "to", "block"));
                    String bloc = chaine(fill, "block", "setup.fill");
                    ResourceLocation id = ResourceLocation.tryParse(bloc);
                    if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                        throw new IllegalArgumentException("setup.fill : bloc inconnu « " + bloc + " »");
                    }
                    miseEnPlace.add(new Remplissage(
                            bloc(fill.get("from"), "setup.fill.from"),
                            bloc(fill.get("to"), "setup.fill.to"),
                            BuiltInRegistries.BLOCK.get(id).defaultBlockState()));
                } else {
                    throw new IllegalArgumentException("setup : action inconnue, ni spawn ni fill : " + action);
                }
            }

            if (racine.has("inputs") && !tableau(racine, "inputs", "le scénario").isEmpty()) {
                throw new IllegalArgumentException("inputs : les entrées attendent les véhicules (C-33)");
            }

            boolean positions = false;
            List<Verification> verifications = new ArrayList<>();
            for (JsonElement brut : tableau(racine, "asserts", "le scénario")) {
                JsonObject verification = objet(brut, "asserts");
                for (String cle : verification.keySet()) {
                    if (CLES_A_VENIR.contains(cle)) {
                        throw new IllegalArgumentException("asserts : « " + cle
                                + " » attend le composant qui lui donne un sens (C-33, M6, M7)");
                    }
                }
                cles(verification, "asserts", Set.of("tick", "ticks", "assembly", "position", "speed_max", "y_min"));
                int assembly = entier(verification, "assembly", "asserts");
                if (assembly < 0 || assembly >= assemblies) {
                    throw new IllegalArgumentException("asserts : assembly " + assembly + " hors des " + assemblies
                            + " apparitions");
                }
                if (verification.has("y_min")) {
                    exclusives(verification, "y_min", "position", "speed_max");
                    int[] bornes = intervalle(verification);
                    verifications.add(new HauteurMin(bornes[0], bornes[1], assembly, nombre(verification.get("y_min"), "y_min")));
                    positions = true;
                } else if (verification.has("position")) {
                    exclusives(verification, "position", "speed_max");
                    int tick = instant(verification);
                    verifications.add(new Position(tick, tick, assembly, vecteur(verification.get("position"), "position")));
                    positions = true;
                } else if (verification.has("speed_max")) {
                    int tick = instant(verification);
                    verifications.add(new VitesseMax(tick, tick, assembly, nombre(verification.get("speed_max"), "speed_max")));
                } else {
                    throw new IllegalArgumentException("asserts : ni position, ni speed_max, ni y_min : " + verification);
                }
            }
            if (verifications.isEmpty()) {
                throw new IllegalArgumentException("asserts : aucune vérification, le scénario ne vérifierait rien");
            }

            double tolerance = 0.0;
            if (racine.has("tolerance")) {
                JsonObject tolerances = objet(racine.get("tolerance"), "tolerance");
                cles(tolerances, "tolerance", Set.of("position"));
                if (tolerances.has("position")) {
                    tolerance = nombre(tolerances.get("position"), "tolerance.position");
                    if (!(tolerance > 0)) {
                        throw new IllegalArgumentException("tolerance.position : strictement positive");
                    }
                }
            }
            if (positions && tolerance == 0.0) {
                throw new IllegalArgumentException("tolerance.position : requise par les vérifications de position (R-2190)");
            }
            int dernierTick = verifications.stream().mapToInt(Verification::fin).max().orElseThrow();
            return new Scenario(structure, List.copyOf(miseEnPlace), List.copyOf(verifications), tolerance, dernierTick);
        }

        /** Met en place le scénario au tick 0, puis joue ses vérifications à chaque tick. */
        void jouer(GameTestHelper helper) {
            ObservateursDeTest.declarer(helper, DECALAGE);
            List<AxionEntity> assemblies = new ArrayList<>();
            for (Action action : miseEnPlace) {
                if (action instanceof Apparition apparition) {
                    assemblies.add(AssembliesDeTest.poser(
                            helper, apparition.definition(), apparition.position().add(DECALAGE), apparition.rotation()));
                } else if (action instanceof Remplissage remplissage) {
                    for (BlockPos position : BlockPos.betweenClosed(remplissage.debut(), remplissage.fin())) {
                        helper.setBlock(position.above(), remplissage.bloc());
                    }
                }
            }
            Vec3[] precedentes = positions(helper, assemblies, 0);
            Vec3[] departs = precedentes.clone();
            // La trajectoire, gardée en mémoire et dite seulement en cas d'échec : écrire à chaque
            // tick changerait le rythme du serveur, et avec lui ce qu'on cherche à voir.
            List<List<Vec3>> trajectoires = new ArrayList<>();
            int[] premierEcart = new int[assemblies.size()];
            for (int i = 0; i < assemblies.size(); i++) {
                trajectoires.add(new ArrayList<>());
                premierEcart[i] = -1;
            }
            helper.onEachTick(() -> {
                int tick = (int) helper.getTick();
                Vec3[] actuelles = positions(helper, assemblies, tick);
                for (int i = 0; i < actuelles.length; i++) {
                    trajectoires.get(i).add(actuelles[i]);
                    if (premierEcart[i] < 0 && actuelles[i].distanceTo(departs[i]) > 0.01) {
                        premierEcart[i] = tick;
                    }
                }
                try {
                    for (Verification verification : verifications) {
                        if (tick >= verification.debut() && tick <= verification.fin()) {
                            int i = verification.assembly();
                            verification.verifier(tick, actuelles[i], precedentes[i], tolerance);
                        }
                    }
                } catch (GameTestAssertException echec) {
                    throw new GameTestAssertException(echec.getMessage() + recit(trajectoires, premierEcart));
                }
                System.arraycopy(actuelles, 0, precedentes, 0, actuelles.length);
                if (tick >= dernierTick) {
                    helper.succeed();
                }
            });
        }

        /** {@return pour chaque assembly, le premier tick où elle a quitté sa place, et sa trajectoire} */
        private static String recit(List<List<Vec3>> trajectoires, int[] premierEcart) {
            StringBuilder texte = new StringBuilder();
            for (int i = 0; i < trajectoires.size(); i++) {
                List<Vec3> trajectoire = trajectoires.get(i);
                texte.append(" | assembly ").append(i).append(" : écart de plus d'un centimètre ")
                        .append(premierEcart[i] < 0 ? "jamais" : "au tick " + premierEcart[i]).append(" ;");
                int debut = Math.max(0, (premierEcart[i] < 0 ? 0 : premierEcart[i] - 1) - 1);
                for (int t = debut; t < trajectoire.size() && t < debut + 8; t++) {
                    texte.append(" t").append(t + 1).append(texte(trajectoire.get(t)));
                }
            }
            return texte.toString();
        }

        private static Vec3[] positions(GameTestHelper helper, List<AxionEntity> assemblies, int tick) {
            Vec3[] positions = new Vec3[assemblies.size()];
            for (int i = 0; i < positions.length; i++) {
                AxionEntity assembly = assemblies.get(i);
                if (assembly.isRemoved()) {
                    throw new GameTestAssertException("tick " + tick + " : l'assembly " + i + " a été retirée du monde");
                }
                positions[i] = helper.relativeVec(assembly.position()).subtract(DECALAGE);
            }
            return positions;
        }
    }

    private static int[] intervalle(JsonObject verification) {
        if (verification.has("tick") || !verification.has("ticks")) {
            throw new IllegalArgumentException("asserts : y_min porte sur un intervalle « ticks »: [a, b]");
        }
        JsonArray bornes = verification.getAsJsonArray("ticks");
        if (bornes.size() != 2) {
            throw new IllegalArgumentException("asserts.ticks : deux bornes [a, b]");
        }
        int debut = (int) nombre(bornes.get(0), "ticks[0]");
        int fin = (int) nombre(bornes.get(1), "ticks[1]");
        if (debut < 1 || fin < debut) {
            throw new IllegalArgumentException("asserts.ticks : 1 <= a <= b attendu, pas [" + debut + ", " + fin + "]");
        }
        return new int[] {debut, fin};
    }

    private static int instant(JsonObject verification) {
        if (verification.has("ticks") || !verification.has("tick")) {
            throw new IllegalArgumentException("asserts : position et speed_max portent sur un « tick »");
        }
        int tick = entier(verification, "tick", "asserts");
        if (tick < 1) {
            throw new IllegalArgumentException("asserts.tick : 1 au moins, le tick 0 est celui de la mise en place");
        }
        return tick;
    }

    private static void exclusives(JsonObject objet, String retenue, String... autres) {
        for (String autre : autres) {
            if (objet.has(autre)) {
                throw new IllegalArgumentException("asserts : « " + retenue + " » et « " + autre + " » dans la même vérification");
            }
        }
    }

    private static void cles(JsonObject objet, String ou, Set<String> permises) {
        for (String cle : objet.keySet()) {
            if (!permises.contains(cle)) {
                throw new IllegalArgumentException(ou + " : clé inconnue « " + cle + " » (ADR-124, version 1)");
            }
        }
    }

    private static JsonObject objet(JsonElement element, String ou) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(ou + " : un objet JSON attendu");
        }
        return element.getAsJsonObject();
    }

    private static JsonArray tableau(JsonObject objet, String cle, String ou) {
        JsonElement element = objet.get(cle);
        if (element == null || !element.isJsonArray()) {
            throw new IllegalArgumentException(ou + " : « " + cle + " » doit être un tableau");
        }
        return element.getAsJsonArray();
    }

    private static String chaine(JsonObject objet, String cle, String ou) {
        JsonElement element = objet.get(cle);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(ou + " : « " + cle + " » doit être une chaîne");
        }
        return element.getAsString();
    }

    private static double nombre(JsonElement element, String ou) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(ou + " : un nombre attendu");
        }
        double valeur = element.getAsDouble();
        if (!Double.isFinite(valeur)) {
            throw new IllegalArgumentException(ou + " : un nombre fini attendu");
        }
        return valeur;
    }

    private static int entier(JsonObject objet, String cle, String ou) {
        double valeur = nombre(objet.get(cle), ou + "." + cle);
        if (valeur != Math.rint(valeur)) {
            throw new IllegalArgumentException(ou + "." + cle + " : un entier attendu");
        }
        return (int) valeur;
    }

    private static Vec3 vecteur(JsonElement element, String ou) {
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != 3) {
            throw new IllegalArgumentException(ou + " : trois nombres [x, y, z] attendus");
        }
        JsonArray v = element.getAsJsonArray();
        return new Vec3(nombre(v.get(0), ou), nombre(v.get(1), ou), nombre(v.get(2), ou));
    }

    private static BlockPos bloc(JsonElement element, String ou) {
        Vec3 v = vecteur(element, ou);
        if (v.x != Math.rint(v.x) || v.y != Math.rint(v.y) || v.z != Math.rint(v.z)) {
            throw new IllegalArgumentException(ou + " : des coordonnées de bloc entières attendues");
        }
        return BlockPos.containing(v);
    }

    private static float[] quaternion(JsonElement element) {
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != 4) {
            throw new IllegalArgumentException("setup.rot : quatre nombres [x, y, z, w] attendus");
        }
        JsonArray q = element.getAsJsonArray();
        float[] rotation = new float[4];
        double norme = 0;
        for (int i = 0; i < 4; i++) {
            rotation[i] = (float) nombre(q.get(i), "setup.rot");
            norme += rotation[i] * rotation[i];
        }
        if (Math.abs(Math.sqrt(norme) - 1.0) > 1e-3) {
            throw new IllegalArgumentException("setup.rot : un quaternion unitaire attendu");
        }
        return rotation;
    }

    private static String texte(Vec3 v) {
        return String.format(Locale.ROOT, "(%.4f, %.4f, %.4f)", v.x, v.y, v.z);
    }
}
