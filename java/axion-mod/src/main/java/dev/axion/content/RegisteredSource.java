package dev.axion.content;

import dev.axion.asset.AssetSource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Le contenu enregistré d'une famille, présenté aux registres comme une {@link AssetSource} (ADR-162 D2).
 *
 * <p>Les registres ne savent pas d'où vient le contenu : les clés sont celles qu'ils lisaient dans les datapacks,
 * {@code <modId>:axion/<dossier>/<chemin>}, dans l'ordre des entrées, et le contenu est déjà en mémoire.
 */
public final class RegisteredSource implements AssetSource {

    private final Map<String, byte[]> files = new LinkedHashMap<>();
    private final Map<String, String> owners = new LinkedHashMap<>();

    /**
     * @param entries les entrées à présenter, dans l'ordre où les énumérer
     */
    public RegisteredSource(List<ContentRegistry.Entry> entries) {
        for (ContentRegistry.Entry entry : entries) {
            files.put(entry.key(), entry.bytes());
            owners.put(entry.key(), entry.owner() + " (" + entry.source() + ")");
        }
    }

    @Override
    public List<String> list() {
        return new ArrayList<>(files.keySet());
    }

    @Override
    public byte[] read(String path) throws IOException {
        byte[] content = files.get(path);
        if (content == null) {
            throw new IOException("contenu non enregistré : " + path);
        }
        return content.clone();
    }

    /** {@return le mod propriétaire d'une clé et sa source, pour les messages ; {@code null} si inconnue} */
    public String ownerOf(String path) {
        return owners.get(path);
    }
}
