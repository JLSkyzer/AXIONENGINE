package dev.axion.net;

import java.util.ArrayList;
import java.util.List;

/**
 * L'état complet d'un ensemble prédit (ADR-171 D7, Q8 ; ADR-168) : le véhicule du conducteur d'abord, puis chaque membre
 * de son ensemble, dans l'ordre de la correction du serveur. La boucle de rejeu ({@link Prediction}) manie un seul bloc
 * d'octets ; celui-ci les met bout à bout, chacun avec l'identifiant réseau de son assembly, et la tolérance d'un
 * ensemble ({@link #tolerance}) les compare un à un.
 *
 * <p>Sur le fil, un ensemble n'existe pas : la correction porte déjà un état par assembly ({@link CorrectionMessage}).
 * Ce format ne vit que dans le client, entre son monde prédit et sa boucle de rejeu. Logique pure.
 */
public final class EnsembleState {

    /** Membres d'un ensemble, au plus : le véhicule et les assemblies que les attaches lui relient. */
    public static final int MAX_MEMBERS = 64;

    /**
     * L'état d'une assembly de l'ensemble.
     *
     * @param entity son identifiant réseau
     * @param state son bloc d'état complet, tel que le natif l'exporte
     */
    public record Member(int entity, byte[] state) {}

    private EnsembleState() {}

    /**
     * {@return les états mis bout à bout : leur nombre, puis pour chacun l'identifiant et le bloc, préfixé de sa longueur}
     *
     * @param members les états, le véhicule d'abord
     * @throws IllegalArgumentException au-delà de {@link #MAX_MEMBERS}
     */
    public static byte[] encode(List<Member> members) {
        if (members.size() > MAX_MEMBERS) {
            throw new IllegalArgumentException(members.size() + " membres, au-delà de " + MAX_MEMBERS);
        }
        WireWriter writer = new WireWriter().varUInt(members.size());
        for (Member member : members) {
            writer.varUInt(member.entity()).bytes(member.state());
        }
        return writer.toByteArray();
    }

    /**
     * {@return les états d'un ensemble}
     *
     * @param bytes ce que {@link #encode} a rendu
     * @throws MalformedPacketException s'il est tronqué, trop long ou a trop de membres
     */
    public static List<Member> decode(byte[] bytes) throws MalformedPacketException {
        WireReader reader = new WireReader(bytes);
        int count = reader.varUInt();
        if (count > MAX_MEMBERS) {
            throw new MalformedPacketException(count + " membres, au-delà de " + MAX_MEMBERS);
        }
        List<Member> members = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            members.add(new Member(reader.varUInt(), reader.bytes(Integer.MAX_VALUE)));
        }
        reader.expectEnd();
        return members;
    }

    /**
     * {@return la tolérance d'un ensemble} Deux ensembles sont proches s'ils ont les mêmes membres, dans le même ordre,
     * et si chaque paire d'états l'est pour {@code member}. Un membre de plus ou de moins — une remorque attelée ou
     * détachée entre la prédiction et la correction — n'est jamais proche : la correction se reprend.
     *
     * @param member la tolérance d'un état d'assembly
     */
    public static Prediction.Tolerance tolerance(Prediction.Tolerance member) {
        return (predicted, server) -> {
            if (predicted == null || server == null) {
                return false;
            }
            List<Member> mine;
            List<Member> theirs;
            try {
                mine = decode(predicted);
                theirs = decode(server);
            } catch (MalformedPacketException malformed) {
                return false;
            }
            if (mine.size() != theirs.size()) {
                return false;
            }
            for (int i = 0; i < mine.size(); i++) {
                if (mine.get(i).entity() != theirs.get(i).entity()
                        || !member.close(mine.get(i).state(), theirs.get(i).state())) {
                    return false;
                }
            }
            return true;
        };
    }
}
