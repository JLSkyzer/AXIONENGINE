package dev.axion.net;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Un addon que le serveur exige du client (ADR-165 D1) : un mod propriétaire d'une entrée de son registre, dans la
 * version que Forge en donne. Le handshake du serveur en porte la liste ; le client répond la version qu'il a de
 * chacun, et le serveur refuse toute différence (D3).
 *
 * @param modId identifiant du mod, selon la règle de Forge
 * @param version version du mod, telle que Forge la donne
 */
public record RequiredMod(String modId, String version) {

    /** L'identifiant d'un mod, selon la règle de Forge. */
    static final Pattern MOD_ID = Pattern.compile("[a-z][a-z0-9_]{1,63}");

    /** Octets d'une version, au plus (ADR-165 D2). */
    public static final int MAX_VERSION_BYTES = 128;

    /** Addons exigés, au plus (ADR-165 D2). */
    public static final int MAX_MODS = 1024;

    /**
     * @throws IllegalArgumentException si l'identifiant n'est pas celui d'un mod, ou si la version est vide ou
     *     dépasse {@link #MAX_VERSION_BYTES} octets
     */
    public RequiredMod {
        Objects.requireNonNull(modId, "modId");
        Objects.requireNonNull(version, "version");
        if (!MOD_ID.matcher(modId).matches()) {
            throw new IllegalArgumentException("identifiant de mod invalide : " + modId);
        }
        if (version.isEmpty() || version.getBytes(StandardCharsets.UTF_8).length > MAX_VERSION_BYTES) {
            throw new IllegalArgumentException("version de " + modId + " vide ou de plus de " + MAX_VERSION_BYTES
                    + " octets : « " + version + " »");
        }
    }

    /**
     * Un addon qui manque au client, ou qu'il a dans une autre version.
     *
     * @param modId identifiant du mod
     * @param expected version que le serveur exige
     * @param actual version que le client a ; vide s'il ne l'a pas
     */
    public record Mismatch(String modId, String expected, String actual) {

        /** {@return vrai si le client n'a pas cet addon} */
        public boolean absent() {
            return actual.isEmpty();
        }
    }

    /**
     * {@return les addons exigés, triés par identifiant} Un par mod propriétaire de contenu.
     *
     * @param versions la version de chaque mod propriétaire de contenu
     * @throws IllegalArgumentException s'il y en a plus de {@link #MAX_MODS}, ou si l'un est invalide
     */
    public static List<RequiredMod> of(Map<String, String> versions) {
        if (versions.size() > MAX_MODS) {
            throw new IllegalArgumentException(versions.size() + " addons de contenu, " + MAX_MODS + " au plus");
        }
        return versions.entrySet().stream()
                .map(entry -> new RequiredMod(entry.getKey(), entry.getValue()))
                .sorted((a, b) -> a.modId().compareTo(b.modId()))
                .toList();
    }

    /**
     * {@return les addons exigés que le client n'a pas, ou pas dans la même version} Une réponse qui ne porte pas
     * une version par addon exigé compte chacun de ceux qu'elle omet comme absent.
     *
     * @param required les addons que le serveur exige, dans l'ordre de son handshake
     * @param versions la version que le client a de chacun, dans le même ordre ; vide s'il ne l'a pas
     */
    public static List<Mismatch> mismatches(List<RequiredMod> required, List<String> versions) {
        List<Mismatch> out = new ArrayList<>();
        for (int i = 0; i < required.size(); i++) {
            RequiredMod mod = required.get(i);
            String actual = i < versions.size() ? versions.get(i) : "";
            if (!mod.version().equals(actual)) {
                out.add(new Mismatch(mod.modId(), mod.version(), actual));
            }
        }
        return List.copyOf(out);
    }
}
