package dev.axion.asset;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deux sources superposées, fichier par fichier : ce que la première énumère l'emporte, la seconde
 * comble le reste (ADR-150, D3).
 *
 * <p>C'est la règle des resource packs de Minecraft : un pack remplace un fichier, pas un dossier.
 * Le client compile ainsi les modèles de ses resource packs ({@code assets/<ns>/axion/models}) et, à
 * défaut, ceux des mods installés chez lui ({@code data/<ns>/axion/models}). Les deux donnent les
 * mêmes chemins ({@code ns:axion/models/...}) : un modèle de resource pack remplace celui du mod.
 */
public final class LayeredAssetSource implements AssetSource {

    private final AssetSource primary;
    private final AssetSource fallback;
    private final Set<String> primaryPaths;
    private final List<String> paths;

    /**
     * @param primary la source qui l'emporte
     * @param fallback la source qui comble ce que la première n'a pas
     */
    public LayeredAssetSource(AssetSource primary, AssetSource fallback) {
        this.primary = primary;
        this.fallback = fallback;
        this.primaryPaths = Set.copyOf(primary.list());
        // Triés, comme une source seule : l'ordre décide de l'ordre de compilation, et deux
        // démarrages sur les mêmes packs doivent le reproduire.
        TreeSet<String> union = new TreeSet<>(primaryPaths);
        union.addAll(fallback.list());
        this.paths = List.copyOf(new ArrayList<>(union));
    }

    @Override
    public List<String> list() {
        return paths;
    }

    @Override
    public byte[] read(String path) throws IOException {
        return primaryPaths.contains(path) ? primary.read(path) : fallback.read(path);
    }
}
