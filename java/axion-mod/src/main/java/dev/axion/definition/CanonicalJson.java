package dev.axion.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * La forme canonique d'une definition (ADR-162 D4, CAP-47) : un même contenu s'y écrit d'une seule façon, qu'il
 * vienne d'un fichier JSON ou du builder Java, et son empreinte se calcule sur elle.
 *
 * <p>Les règles suivent la canonicalisation JSON du RFC 8785 :
 * <ul>
 *   <li>aucun espace ;</li>
 *   <li>les clés d'un objet triées par unités UTF-16 ({@link String#compareTo}) ; les tableaux gardent leur ordre ;</li>
 *   <li>une chaîne n'échappe que {@code "}, {@code \}, et les caractères de contrôle — {@code \b \f \n \r \t},
 *       les autres en échappement Unicode à quatre chiffres hexadécimaux, en minuscules ; tout le reste s'écrit
 *       tel quel, en UTF-8 ;</li>
 *   <li>un nombre est la valeur {@code double} qu'il désigne, écrite par le plus court décimal qui la redonne, à la
 *       façon de JavaScript : {@code 1}, {@code 1.0} et {@code 1e0} s'écrivent {@code 1} ; {@code -0} s'écrit
 *       {@code 0} ; l'exposant n'apparaît qu'en dessous de {@code 1e-6} ou à partir de {@code 1e21}. Aucun champ du
 *       schéma n'admet d'entier au-delà de 2<sup>53</sup>, où un {@code double} cesserait d'être exact.</li>
 * </ul>
 *
 * <p>Le plus court décimal se calcule par {@link BigDecimal}, à partir de la valeur exacte du {@code double} :
 * le résultat ne dépend pas de {@link Double#toString}, dont la sortie a changé avec le JDK 19. Un serveur et un
 * client sur deux JDK différents calculent donc la même empreinte.
 */
public final class CanonicalJson {

    private CanonicalJson() {}

    /** {@return la forme canonique d'un document JSON} */
    public static String of(JsonElement element) {
        StringBuilder out = new StringBuilder();
        write(element, out);
        return out.toString();
    }

    /** {@return l'empreinte SHA-256 de la forme canonique, en hexadécimal} */
    public static String sha256(JsonElement element) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(of(element).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 fait partie des algorithmes que toute JVM doit fournir.
            throw new IllegalStateException(impossible);
        }
    }

    private static void write(JsonElement element, StringBuilder out) {
        if (element == null || element.isJsonNull()) {
            out.append("null");
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            List<String> keys = new ArrayList<>(object.keySet());
            keys.sort(null);
            out.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                string(keys.get(i), out);
                out.append(':');
                write(object.get(keys.get(i)), out);
            }
            out.append('}');
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            out.append('[');
            for (int i = 0; i < array.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(array.get(i), out);
            }
            out.append(']');
        } else {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                out.append(primitive.getAsBoolean());
            } else if (primitive.isNumber()) {
                out.append(number(primitive.getAsDouble()));
            } else {
                string(primitive.getAsString(), out);
            }
        }
    }

    private static final Map<Character, String> SHORT_ESCAPES = Map.of(
            '"', "\\\"", '\\', "\\\\", '\b', "\\b", '\f', "\\f", '\n', "\\n", '\r', "\\r", '\t', "\\t");

    private static void string(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            String escape = SHORT_ESCAPES.get(c);
            if (escape != null) {
                out.append(escape);
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        out.append('"');
    }

    /**
     * {@return un nombre fini, écrit par le plus court décimal qui redonne le même {@code double}, à la façon de
     * JavaScript}
     *
     * @throws IllegalArgumentException si le nombre n'est pas fini : JSON n'en a pas
     */
    static String number(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("nombre non fini : " + value);
        }
        if (value == 0.0) {
            return "0";
        }
        BigDecimal exact = new BigDecimal(value);
        BigDecimal shortest = exact;
        for (int precision = 1; precision <= 17; precision++) {
            BigDecimal rounded = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
            if (rounded.doubleValue() == value) {
                shortest = rounded;
                break;
            }
        }
        shortest = shortest.stripTrailingZeros();
        String digits = shortest.unscaledValue().abs().toString();
        int k = digits.length();
        // value = 0.d1…dk × 10^n
        int n = k - shortest.scale();
        StringBuilder out = new StringBuilder(value < 0 ? "-" : "");
        if (k <= n && n <= 21) {
            out.append(digits).append("0".repeat(n - k));
        } else if (0 < n && n <= 21) {
            out.append(digits, 0, n).append('.').append(digits, n, k);
        } else if (-6 < n && n <= 0) {
            out.append("0.").append("0".repeat(-n)).append(digits);
        } else {
            out.append(digits.charAt(0));
            if (k > 1) {
                out.append('.').append(digits, 1, k);
            }
            int exponent = n - 1;
            out.append('e').append(exponent < 0 ? '-' : '+').append(Math.abs(exponent));
        }
        return out.toString();
    }
}
