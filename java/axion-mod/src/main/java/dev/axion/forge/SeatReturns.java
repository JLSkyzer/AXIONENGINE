package dev.axion.forge;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Rend son siège à un joueur qui revient (ADR-158, décision de Killian du 2026-11-09) : à la déconnexion
 * l'assembly reste dans le monde et le joueur en descend, mais ses données gardent l'assembly et le nom de
 * son siège. À son retour, l'assembly n'est pas forcément déjà chargée : on la cherche pendant quelques
 * secondes, tant que le joueur n'a pas commencé à marcher. Sur le thread du serveur.
 */
final class SeatReturns {

    /** Ticks pendant lesquels l'assembly est attendue. */
    private static final int WAIT_TICKS = 200;

    /** Un joueur qui a bougé d'autant, en blocs, a repris sa route : il n'est plus rassis. */
    private static final double MOVED_BLOCKS = 2.0;

    private record Pending(UUID player, UUID assembly, String seat, Vec3 from, int deadline) {}

    private final List<Pending> pending = new ArrayList<>();

    /**
     * Un joueur entre en jeu : s'il était assis à son départ, l'assembly est à retrouver.
     *
     * @param player le joueur
     */
    void onLogin(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        if (data.hasUUID(AxionEntity.REMEMBERED_ASSEMBLY) && data.contains(AxionEntity.REMEMBERED_SEAT)) {
            pending.add(new Pending(player.getUUID(), data.getUUID(AxionEntity.REMEMBERED_ASSEMBLY),
                    data.getString(AxionEntity.REMEMBERED_SEAT), player.position(),
                    player.getServer().getTickCount() + WAIT_TICKS));
        }
    }

    /**
     * Cherche les assemblies attendues.
     *
     * @param server le serveur
     */
    void tick(MinecraftServer server) {
        AuthoritativeThread.require();
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending wait = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(wait.player());
            if (player == null) {
                it.remove();
                continue;
            }
            if (player.position().distanceTo(wait.from()) > MOVED_BLOCKS) {
                it.remove();
                AxionEntity.forgetSeat(player);
                continue;
            }
            Entity found = player.serverLevel().getEntity(wait.assembly());
            if (found instanceof AxionEntity assembly && assembly.seatingKnown()) {
                it.remove();
                if (!assembly.reseat(player, wait.seat())) {
                    AxionEntity.forgetSeat(player);
                }
            } else if (server.getTickCount() >= wait.deadline()) {
                it.remove();
                AxionEntity.forgetSeat(player);
            }
        }
    }
}
