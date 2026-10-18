package dev.axion.net;

import dev.axion.bridge.NativeBridge;

/**
 * Empreinte de configuration déterministe d'une extrémité (5.12bis), échangée au handshake
 * (ADR-150 D4).
 *
 * @param profile {@code det_profile} : hash du triplet cible, de la version du noyau déterministe,
 *     du jeu d'instructions compilé et des drapeaux flottants
 * @param kernelVersion {@code DET_KERNEL_VERSION} du noyau déterministe (C-16)
 * @param inMatrix vrai si la configuration figure, validée, dans la matrice de 5.12bis (R-516)
 */
public record DetProfile(long profile, int kernelVersion, boolean inMatrix) {

    /**
     * L'empreinte d'une extrémité dont le runtime natif est inactif : hors de la matrice, ce qui fait
     * négocier {@link ReplicationMode#SNAPSHOT}, le mode qui ne suppose rien du noyau déterministe.
     */
    public static final DetProfile UNKNOWN = new DetProfile(0L, 0, false);

    /**
     * {@return l'empreinte de la bibliothèque native chargée}
     *
     * @throws IllegalStateException si le natif refuse de la rendre
     */
    public static DetProfile ofThisBinary() {
        long[] out = new long[3];
        int code = NativeBridge.readDetProfile(out);
        if (code != NativeBridge.OK) {
            throw new IllegalStateException("empreinte déterministe refusée par le natif, code " + code);
        }
        return new DetProfile(out[0], (int) out[1], out[2] == 1);
    }
}
