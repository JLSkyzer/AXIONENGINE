package dev.axion.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-006 — INV-01 : aucun littéral de nom de mod, de véhicule ou de contenu dans le moteur
 * (P-01, AC-08, job {@code lint-no-mod-names} de la PARTIE 34.3).
 *
 * <p>Le moteur est générique : un comportement propre à un contenu naît d'une composition de
 * données, jamais d'un {@code if (id.equals("…"))}. Une telle branche commence toujours par un
 * littéral ; ce test lit les littéraux de chaîne du moteur — Java de {@code src/main} et Rust de
 * {@code crates/*}{@code /src}, hors items {@code #[cfg(test)]} — et y cherche trois familles de
 * noms :
 *
 * <ul>
 *   <li>les mods tiers que le cahier des charges nomme (PARTIE 26.5, R-2330), et DynamX, projet
 *       de référence (ADR-101) ;
 *   <li>des types de véhicule — {@code vehicle} n'en est pas un : c'est une catégorie
 *       ({@code kind}) du schéma des definitions ;
 *   <li>les identifiants du contenu du dépôt : definitions livrées, et tout ce que porte
 *       {@code src/devcontent}.
 * </ul>
 *
 * <p>Les commentaires ne comptent pas : seuls les littéraux peuvent piloter une branche.
 */
class EngineGenericityTest {

    /** Mods tiers, en minuscules. */
    private static final List<String> MOD_NAMES = List.of(
            "iris", "oculus", "sodium", "embeddium", "rubidium", "optifine", "rustforgex", "dynamx");

    /** Types de véhicule, en minuscules, anglais et français. */
    private static final List<String> VEHICLE_NAMES = List.of(
            "car", "voiture", "plane", "airplane", "avion", "helicopter", "helicoptere", "boat",
            "bateau", "truck", "camion", "motorcycle", "moto", "submarine", "sous_marin");

    /**
     * Emplacements où un nom de mod est légitime, par nom.
     *
     * <p>R-1940 : la détection d'un mod tiers sert uniquement à choisir un chemin de
     * compatibilité. Celle des mods de shaders est exigée (H-09, PARTIE 26.5) : elle teste leur
     * présence par identifiant et lit l'état du pack par leur API. RUSTFORGE-X n'est nommé que
     * dans C-76 et dans le registre de configuration, qui porte les options
     * {@code integration.rustforgex.*} de l'ANNEXE A.3 (exception listée en 34.3, et T-014).
     */
    private static final Map<String, List<String>> ALLOWED = Map.of(
            "iris", List.of(
                    "dev/axion/forge/client/AxionRenderPass.java",
                    "dev/axion/render/ShaderpackProbe.java"),
            "oculus", List.of("dev/axion/forge/client/AxionRenderPass.java"),
            "rustforgex", List.of(
                    "dev/axion/integration/rustforgex/",
                    "ax-model/src/config/",
                    "dev/axion/config/ConfigSchema.java"));

    /** Un littéral de chaîne du code, avec sa ligne. */
    record Literal(int line, String text) {}

    // ------------------------------------------------------------------------------------------
    // Lecture des littéraux
    // ------------------------------------------------------------------------------------------

    private static final String CFG_TEST = "#[cfg(test)]";

    /**
     * {@return les littéraux de chaîne de {@code source}, commentaires exclus}
     *
     * <p>Rust : chaînes brutes {@code r#"…"#} comprises, et l'item qui suit un
     * {@code #[cfg(test)]} sauté — jusqu'à son {@code ;}, ou jusqu'à l'accolade qui ferme la
     * première qu'il ouvre.
     */
    static List<Literal> literals(String source, boolean rust) {
        List<Literal> out = new ArrayList<>();
        int n = source.length();
        int line = 1;
        int depth = 0;
        boolean pendingSkip = false;
        int skipDepth = -1;
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);
            if (c == '\n') {
                line++;
                i++;
            } else if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? n : end;
            } else if (source.startsWith("/*", i)) {
                int close = source.indexOf("*/", i + 2);
                int end = close < 0 ? n : close + 2;
                line += newlines(source, i, end);
                i = end;
            } else if (rust && source.startsWith(CFG_TEST, i)) {
                if (skipDepth < 0) {
                    pendingSkip = true;
                }
                i += CFG_TEST.length();
            } else if (!rust && source.startsWith("\"\"\"", i)) {
                int close = source.indexOf("\"\"\"", i + 3);
                int end = close < 0 ? n : close;
                String text = source.substring(i + 3, end);
                if (skipDepth < 0) {
                    out.add(new Literal(line, text));
                }
                line += newlines(text, 0, text.length());
                i = Math.min(n, end + 3);
            } else if (rust && c == 'r' && rawStringStart(source, i) > 0) {
                int hashes = rawStringStart(source, i) - 1;
                String closing = "\"" + "#".repeat(hashes);
                int open = i + 2 + hashes;
                int close = source.indexOf(closing, open);
                int end = close < 0 ? n : close;
                String text = source.substring(open, end);
                if (skipDepth < 0) {
                    out.add(new Literal(line, text));
                }
                line += newlines(text, 0, text.length());
                i = Math.min(n, end + closing.length());
            } else if (c == '"') {
                StringBuilder text = new StringBuilder();
                int j = i + 1;
                while (j < n && source.charAt(j) != '"') {
                    if (source.charAt(j) == '\\' && j + 1 < n) {
                        text.append(source, j, j + 2);
                        j += 2;
                    } else {
                        text.append(source.charAt(j));
                        j++;
                    }
                }
                if (skipDepth < 0) {
                    out.add(new Literal(line, text.toString()));
                }
                line += newlines(text, 0, text.length());
                i = Math.min(n, j + 1);
            } else if (c == '\'' && charLiteralLength(source, i) > 0) {
                i += charLiteralLength(source, i);
            } else {
                if (c == '{') {
                    depth++;
                    if (pendingSkip) {
                        pendingSkip = false;
                        skipDepth = depth;
                    }
                } else if (c == '}') {
                    if (depth == skipDepth) {
                        skipDepth = -1;
                    }
                    depth--;
                } else if (c == ';' && pendingSkip) {
                    // `#[cfg(test)] mod tests;` ou `#[cfg(test)] use …;`
                    pendingSkip = false;
                }
                i++;
            }
        }
        return out;
    }

    /** {@return 1 + nombre de {@code #} si une chaîne brute Rust commence en {@code i}, sinon 0} */
    private static int rawStringStart(String source, int i) {
        if (i > 0 && (Character.isLetterOrDigit(source.charAt(i - 1)) || source.charAt(i - 1) == '_')) {
            return 0;
        }
        int j = i + 1;
        while (j < source.length() && source.charAt(j) == '#') {
            j++;
        }
        return j < source.length() && source.charAt(j) == '"' ? j - i : 0;
    }

    /** {@return la longueur du littéral de caractère qui commence en {@code i}, ou 0 (durée de vie Rust)} */
    private static int charLiteralLength(String source, int i) {
        int n = source.length();
        if (i + 2 < n && source.charAt(i + 1) != '\\' && source.charAt(i + 2) == '\'') {
            return 3;
        }
        if (i + 1 < n && source.charAt(i + 1) == '\\') {
            int close = source.indexOf('\'', i + 3);
            if (close > 0 && close - i <= 11) {
                return close - i + 1;
            }
        }
        return 0;
    }

    private static int newlines(CharSequence text, int from, int to) {
        int count = 0;
        for (int k = from; k < to; k++) {
            if (text.charAt(k) == '\n') {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------------------------------
    // Recherche
    // ------------------------------------------------------------------------------------------

    private static final Pattern TOKEN_SEPARATOR = Pattern.compile("[^a-z0-9_]+");

    private static Set<String> tokens(String text) {
        return Set.copyOf(List.of(TOKEN_SEPARATOR.split(text.toLowerCase(Locale.ROOT))));
    }

    /** {@return les violations portées par {@code literal}, du fichier {@code relative}} */
    static List<String> violations(String relative, Literal literal, Set<String> contentIds) {
        List<String> hits = new ArrayList<>();
        String lower = literal.text().toLowerCase(Locale.ROOT);
        Set<String> tokens = tokens(lower);
        String where = relative + ":" + literal.line() + " \"" + literal.text() + "\"";
        for (String mod : MOD_NAMES) {
            if (tokens.contains(mod) && !allowed(mod, relative)) {
                hits.add("mod « " + mod + " » — " + where);
            }
        }
        for (String vehicle : VEHICLE_NAMES) {
            if (tokens.contains(vehicle)) {
                hits.add("véhicule « " + vehicle + " » — " + where);
            }
        }
        for (String id : contentIds) {
            if (Pattern.compile("(^|[^a-z0-9_/])" + Pattern.quote(id) + "($|[^a-z0-9_/])")
                    .matcher(lower)
                    .find()) {
                hits.add("contenu « " + id + " » — " + where);
            }
        }
        return hits;
    }

    private static boolean allowed(String mod, String relative) {
        return ALLOWED.getOrDefault(mod, List.of()).stream().anyMatch(relative::contains);
    }

    // ------------------------------------------------------------------------------------------
    // Sources et contenu
    // ------------------------------------------------------------------------------------------

    private static Path property(String name) {
        String dir = System.getProperty(name);
        assertTrue(dir != null && !dir.isBlank(), name + " non fourni par le build");
        return Path.of(dir);
    }

    private static List<Path> files(Path root, String extension) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(extension))
                    .sorted()
                    .toList();
        }
    }

    /** Un fichier source du moteur, sous sa racine. */
    private record Source(Path root, Path file, boolean rust) {
        String relative() {
            return root.relativize(file).toString().replace('\\', '/');
        }
    }

    private static List<Source> engineSources() throws IOException {
        List<Source> sources = new ArrayList<>();
        Path mod = property("axion.source.dir");
        for (Path file : files(mod, ".java")) {
            sources.add(new Source(mod, file, false));
        }
        // axion-api : module frère, même disposition.
        Path api = mod.resolve("../../../../axion-api/src/main/java").normalize();
        for (Path file : files(api, ".java")) {
            sources.add(new Source(api, file, false));
        }
        Path crates = property("axion.crates.dir");
        try (Stream<Path> dirs = Files.list(crates)) {
            for (Path crate : dirs.filter(Files::isDirectory).sorted().toList()) {
                for (Path file : files(crate.resolve("src"), ".rs")) {
                    sources.add(new Source(crates, file, true));
                }
            }
        }
        return sources;
    }

    /**
     * {@return les identifiants du contenu du dépôt}
     *
     * <p>Sous {@code data/<espace>/}, le premier dossier est le genre ({@code structures},
     * {@code gametest}), ou {@code axion/<genre>} pour le contenu d'AXION ; le reste du chemin,
     * sans extension, est l'identifiant — {@code test/materiaux/vitre}, {@code test_cube}.
     */
    static Set<String> contentIds() throws IOException {
        Set<String> ids = new TreeSet<>();
        Path main = property("axion.source.dir").getParent().resolve("resources/data");
        Path dev = property("axion.devcontent.dir").getParent().resolve("resources/data");
        for (Path data : List.of(main, dev)) {
            for (Path file : files(data, "")) {
                List<String> parts = new ArrayList<>();
                data.relativize(file).forEach(part -> parts.add(part.toString()));
                int skip = parts.size() > 3 && parts.get(1).equals("axion") ? 3 : 2;
                if (parts.size() <= skip) {
                    continue;
                }
                // Hors devcontent, seules les definitions sont du contenu : le reste (modèle de
                // repli, types de dégâts) appartient au moteur.
                if (data == main && !(skip == 3 && parts.get(2).equals("definitions"))) {
                    continue;
                }
                String id = String.join("/", parts.subList(skip, parts.size()))
                        .toLowerCase(Locale.ROOT);
                int dot = id.lastIndexOf('.');
                ids.add(dot > id.lastIndexOf('/') ? id.substring(0, dot) : id);
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("T-006 : aucun nom de mod, de véhicule ou de contenu dans les littéraux du moteur")
    void aucunNomDansLeMoteur() throws IOException {
        Set<String> ids = contentIds();
        List<Source> sources = engineSources();
        List<String> hits = new ArrayList<>();
        for (Source source : sources) {
            String text = Files.readString(source.file(), StandardCharsets.UTF_8);
            for (Literal literal : literals(text, source.rust())) {
                hits.addAll(violations(source.relative(), literal, ids));
            }
        }
        assertTrue(sources.size() > 100, () -> "trop peu de sources lues : " + sources.size());
        assertTrue(
                hits.isEmpty(),
                () -> "INV-01 violé — le moteur est générique, le contenu est data-driven :\n  "
                        + String.join("\n  ", hits));
    }

    @Test
    @DisplayName("T-006 : le contenu du dépôt est bien recensé")
    void leContenuEstRecense() throws IOException {
        Set<String> ids = contentIds();
        assertTrue(ids.contains("test_cube"), ids::toString);
        assertTrue(ids.contains("test/materiaux/vitre"), ids::toString);
        assertTrue(ids.contains("scenarios/physique/empilement_60s"), ids::toString);
        assertTrue(!ids.contains("builtin/missing"), "le modèle de repli est au moteur");
    }

    @Test
    @DisplayName("T-006 : chaque exception nommant un fichier y trouve encore son nom")
    void lesExceptionsServent() throws IOException {
        List<String> unused = new ArrayList<>();
        List<Source> sources = engineSources();
        for (Map.Entry<String, List<String>> entry : ALLOWED.entrySet()) {
            for (String place : entry.getValue()) {
                if (place.endsWith("/")) {
                    continue; // dossier réservé (C-76), qui peut ne pas exister encore
                }
                boolean used = false;
                for (Source source : sources) {
                    if (!source.relative().endsWith(place)) {
                        continue;
                    }
                    String text = Files.readString(source.file(), StandardCharsets.UTF_8);
                    for (Literal literal : literals(text, source.rust())) {
                        used |= tokens(literal.text()).contains(entry.getKey());
                    }
                }
                if (!used) {
                    unused.add(entry.getKey() + " @ " + place);
                }
            }
        }
        assertTrue(unused.isEmpty(), () -> "exceptions sans objet, à retirer : " + unused);
    }

    @Test
    @DisplayName("T-006 : la lecture voit les littéraux, ignore commentaires et tests Rust")
    void laLectureEstFiable() {
        String java = """
                // ModList.get().isLoaded("sodium") en commentaire
                /* "embeddium" */ char q = '"'; char e = '\\'';
                if (ModList.get().isLoaded("sodium")) { kind = \"""
                    car\"""; }
                """;
        List<Literal> found = literals(java, false);
        assertEquals(List.of(new Literal(3, "sodium"), new Literal(3, "\n    car")), found);
        List<String> hits = new ArrayList<>();
        for (Literal literal : found) {
            hits.addAll(violations("X.java", literal, Set.of("test/vitre")));
        }
        assertEquals(2, hits.size(), hits::toString);

        String rust = """
                fn f<'a>(s: &'a str) -> bool { s == r#"test/vitre"# || s == "x" }
                #[cfg(test)]
                mod tests { fn g() { let _ = "dynamx"; let _ = '{'; } }
                #[cfg(test)]
                mod autres;
                const N: &str = "oculus";
                """;
        List<Literal> rustFound = literals(rust, true);
        assertEquals(
                List.of(new Literal(1, "test/vitre"), new Literal(1, "x"), new Literal(6, "oculus")),
                rustFound);
        assertEquals(
                1,
                violations("ax-x/src/lib.rs", rustFound.get(0), Set.of("test/vitre")).size(),
                "un identifiant de contenu en chaîne brute est vu");
        assertEquals(
                0,
                violations("ax-x/src/lib.rs", new Literal(1, "test/vitre_2"), Set.of("test/vitre")).size(),
                "un identifiant voisin ne l'est pas");
        assertEquals(
                1,
                violations("ax-x/src/lib.rs", rustFound.get(2), Set.of()).size(),
                "un nom de mod hors exception est vu");
    }
}
