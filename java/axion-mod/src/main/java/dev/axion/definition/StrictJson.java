package dev.axion.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Lecture JSON stricte d'une definition (C-27, étape 1).
 *
 * <p>Gson est déjà dans Minecraft, et la table 32.2 écarte d'en embarquer un
 * autre. Mais son {@code JsonParser} est <strong>permissif</strong> : il
 * accepte les commentaires, les clés sans guillemets, les valeurs après la
 * racine, et garde en silence la dernière de deux clés identiques. Pour un
 * format public, chacune de ces tolérances est une faute d'auteur qui passe :
 * {@code "mass": 1650} écrit deux fois avec deux valeurs différentes ne dit pas
 * laquelle compte.
 *
 * <p>L'arbre est donc construit ici, jeton par jeton, sur un {@link JsonReader}
 * non permissif.
 */
public final class StrictJson {

    /** Profondeur d'imbrication maximale. */
    static final int MAX_DEPTH = 64;

    /** Clé qui s'écrit telle quelle dans un chemin JSON. */
    private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z0-9_]+");

    private StrictJson() {}

    /**
     * Lit un document JSON strict.
     *
     * @param bytes contenu du fichier, en UTF-8
     * @return la valeur racine
     * @throws DefinitionException {@code E-7001} au premier écart, avec son
     *     chemin
     */
    public static JsonElement parse(byte[] bytes) throws DefinitionException {
        String text = decode(bytes);
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            throw DefinitionException.invalid(
                    "$", "marque d'ordre des octets en tête : le JSON strict l'exclut");
        }

        JsonReader reader = new JsonReader(new StringReader(text));
        reader.setLenient(false);
        try {
            JsonElement root = read(reader, "$", 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw DefinitionException.invalid("$", "contenu après la valeur racine");
            }
            return root;
        } catch (IOException | IllegalStateException | NumberFormatException failure) {
            throw DefinitionException.invalid("$", "JSON invalide : " + failure.getMessage());
        }
    }

    /**
     * {@return le chemin JSON d'un membre}
     *
     * @param parent chemin du parent
     * @param key clé du membre
     */
    static String member(String parent, String key) {
        if (PLAIN_KEY.matcher(key).matches()) {
            return parent + "." + key;
        }
        return parent + "[\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
    }

    private static String decode(byte[] bytes) throws DefinitionException {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException failure) {
            // Un décodeur qui remplace les octets invalides donnerait un nom
            // d'asset ou de node qui ne désigne plus rien, sans le dire.
            throw DefinitionException.invalid("$", "UTF-8 invalide");
        }
    }

    private static JsonElement read(JsonReader reader, String path, int depth)
            throws IOException, DefinitionException {
        if (depth > MAX_DEPTH) {
            // Un document hostile imbriqué sur des milliers de niveaux ferait
            // déborder la pile de la lecture récursive.
            throw DefinitionException.invalid(
                    path, "imbrication au-delà de " + MAX_DEPTH + " niveaux");
        }
        JsonToken token = reader.peek();
        switch (token) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    String child = member(path, key);
                    if (object.has(key)) {
                        throw DefinitionException.invalid(child, "clé en double");
                    }
                    object.add(key, read(reader, child, depth + 1));
                }
                reader.endObject();
                return object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray();
                reader.beginArray();
                int index = 0;
                while (reader.hasNext()) {
                    array.add(read(reader, path + "[" + index + "]", depth + 1));
                    index++;
                }
                reader.endArray();
                return array;
            }
            case STRING -> {
                return new JsonPrimitive(reader.nextString());
            }
            case NUMBER -> {
                // Le texte du nombre, pas un double : `schema` doit se lire comme
                // un entier, et un double arrondit sans prévenir.
                String raw = reader.nextString();
                BigDecimal value = new BigDecimal(raw);
                if (!Double.isFinite(value.doubleValue())) {
                    throw DefinitionException.invalid(
                            path, "nombre hors de la plage d'un double : " + raw);
                }
                return new JsonPrimitive(value);
            }
            case BOOLEAN -> {
                return new JsonPrimitive(reader.nextBoolean());
            }
            case NULL -> {
                reader.nextNull();
                return JsonNull.INSTANCE;
            }
            default -> {
                // Les autres jetons ferment une valeur : les recevoir ici
                // signale un document tronqué.
            }
        }
        throw DefinitionException.invalid(path, "jeton inattendu : " + token);
    }
}
