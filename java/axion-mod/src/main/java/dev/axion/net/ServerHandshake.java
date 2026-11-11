package dev.axion.net;

import dev.axion.config.AxionConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code S2C_Handshake} (21.3), complété de l'empreinte déterministe du serveur (ADR-150 D4).
 *
 * <p>Envoyé en phase de connexion, avant l'entrée en jeu (R-1640). Chaque champ est validé à la
 * lecture : un serveur qui annonce un pas de temps nul ou une fréquence hors bornes ne fait pas
 * régler le client sur n'importe quoi (R-1650). Les bornes sont celles de la configuration : tout
 * serveur correctement configuré passe.
 *
 * @param protocolVersion {@code protocol_version} : {@link NetProtocol#PROTOCOL_VERSION}
 * @param fixedDt {@code sim.fixed_dt}, en secondes
 * @param maxSubsteps {@code sim.max_substeps}
 * @param gravity {@code physics.gravity}, en m/s²
 * @param snapshotRateHz {@code net.snapshot_rate_hz}
 * @param interpolationDelayMs {@code net.interpolation_delay_ms}
 * @param predictionEnabled {@code net.prediction}
 * @param deformationEnabled {@code deformation.enabled}
 * @param deformQuantPolicy politique de quantification de la déformation (R-1641) ; 0 jusqu'en M6
 * @param definitionsHash empreinte des definitions et des groupes de collision (ADR-150 D5)
 * @param damageSourcesHash empreinte des sources de dégâts ; celle d'un registre vide jusqu'en M6
 * @param materialsHash empreinte des matériaux physiques
 * @param det empreinte de configuration déterministe du serveur (5.12bis)
 * @param requiredMods les addons que le serveur exige du client, triés par identifiant (ADR-165 D1)
 */
public record ServerHandshake(
        int protocolVersion,
        float fixedDt,
        int maxSubsteps,
        float gravity,
        int snapshotRateHz,
        int interpolationDelayMs,
        boolean predictionEnabled,
        boolean deformationEnabled,
        int deformQuantPolicy,
        long definitionsHash,
        long damageSourcesHash,
        long materialsHash,
        DetProfile det,
        List<RequiredMod> requiredMods) {

    /** Garde une copie figée de la liste. */
    public ServerHandshake {
        requiredMods = List.copyOf(requiredMods);
    }

    /**
     * Politique de quantification de la déformation (R-1641). Une seule existe tant que la déformation
     * n'est pas répliquée : M6 en définira d'autres.
     */
    public static final int DEFORM_QUANT_POLICY = 0;

    /**
     * Le handshake d'un serveur : sa configuration de simulation, les empreintes du registre qu'il
     * envoie, et son empreinte déterministe.
     *
     * @param config configuration commune du serveur
     * @param registry registre qu'il envoie
     * @param det son empreinte déterministe
     * @param requiredMods les addons qu'il exige du client (ADR-165 D1)
     * @return le handshake
     */
    public static ServerHandshake of(
            AxionConfig config, RegistryContent registry, DetProfile det, List<RequiredMod> requiredMods) {
        return new ServerHandshake(
                NetProtocol.PROTOCOL_VERSION,
                (float) config.getFloat("sim.fixed_dt"),
                (int) config.getInt("sim.max_substeps"),
                (float) config.getFloat("physics.gravity"),
                (int) config.getInt("net.snapshot_rate_hz"),
                (int) config.getInt("net.interpolation_delay_ms"),
                config.getBoolean("net.prediction"),
                config.getBoolean("deformation.enabled"),
                DEFORM_QUANT_POLICY,
                registry.definitionsHash(),
                // Les sources de dégâts viennent avec leur système, en M6 (ADR-150 D5).
                RegistryContent.EMPTY_DIGEST,
                registry.materialsHash(),
                det,
                requiredMods);
    }

    /** {@return le message, genre compris} */
    public byte[] encode() {
        WireWriter writer = new WireWriter(64 + 32 * requiredMods.size())
                .u8(MessageKinds.SERVER_HANDSHAKE)
                .u16(protocolVersion)
                .f32(fixedDt)
                .u8(maxSubsteps)
                .f32(gravity)
                .u8(snapshotRateHz)
                .u16(interpolationDelayMs)
                .bool(predictionEnabled)
                .bool(deformationEnabled)
                .u8(deformQuantPolicy)
                .i64(definitionsHash)
                .i64(damageSourcesHash)
                .i64(materialsHash)
                .i32(det.kernelVersion())
                .bool(det.inMatrix())
                .i64(det.profile())
                .u16(requiredMods.size());
        for (RequiredMod mod : requiredMods) {
            writer.utf8(mod.modId()).utf8(mod.version());
        }
        return writer.toByteArray();
    }

    /**
     * @param message message reçu, genre compris
     * @return le handshake lu
     * @throws MalformedPacketException si le message est mal formé, d'une autre version de
     *     protocole, ou porte une valeur hors de son domaine
     */
    public static ServerHandshake decode(byte[] message) throws MalformedPacketException {
        WireReader reader = new WireReader(message);
        MessageKinds.expect(reader, MessageKinds.SERVER_HANDSHAKE);
        int protocol = reader.u16();
        if (protocol != NetProtocol.PROTOCOL_VERSION) {
            throw new MalformedPacketException(
                    "version de protocole " + protocol + ", " + NetProtocol.PROTOCOL_VERSION + " attendue");
        }
        float fixedDt = reader.f32();
        if (!(fixedDt > 0f && fixedDt <= 1f)) {
            throw new MalformedPacketException("pas de temps hors de ]0, 1] s : " + fixedDt);
        }
        int maxSubsteps = reader.u8();
        if (maxSubsteps < 1 || maxSubsteps > 8) {
            throw new MalformedPacketException("sous-pas hors de [1, 8] : " + maxSubsteps);
        }
        float gravity = reader.f32();
        if (!(gravity >= -50f && gravity <= 0f)) {
            throw new MalformedPacketException("pesanteur hors de [-50, 0] m/s² : " + gravity);
        }
        int rate = reader.u8();
        if (rate < 2 || rate > 20) {
            throw new MalformedPacketException("fréquence des snapshots hors de [2, 20] Hz : " + rate);
        }
        // Tout u16 est un retard admis : l'option a pour domaine celui du champ (21.3).
        int delay = reader.u16();
        boolean prediction = reader.bool();
        boolean deformation = reader.bool();
        int quantPolicy = reader.u8();
        long definitions = reader.i64();
        long damageSources = reader.i64();
        long materials = reader.i64();
        int kernel = reader.i32();
        boolean inMatrix = reader.bool();
        long profile = reader.i64();
        int count = reader.u16();
        if (count > RequiredMod.MAX_MODS) {
            throw new MalformedPacketException(count + " addons exigés, " + RequiredMod.MAX_MODS + " au plus");
        }
        List<RequiredMod> required = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String modId = reader.utf8(64);
            String version = reader.utf8(RequiredMod.MAX_VERSION_BYTES);
            try {
                required.add(new RequiredMod(modId, version));
            } catch (IllegalArgumentException invalid) {
                throw new MalformedPacketException("addon exigé invalide : " + invalid.getMessage());
            }
        }
        reader.expectEnd();
        return new ServerHandshake(protocol, fixedDt, maxSubsteps, gravity, rate, delay, prediction,
                deformation, quantPolicy, definitions, damageSources, materials,
                new DetProfile(profile, kernel, inMatrix), required);
    }
}
