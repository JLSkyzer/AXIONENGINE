package dev.axion.forge;

import dev.axion.AxionMod;
import dev.axion.bridge.NativeBridge;
import dev.axion.net.AssemblyState;
import dev.axion.physics.BodyFrame;
import dev.axion.physics.CarriedPlace;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Le transport au serveur (ADR-178 D4, CAP-14) : à la fin de chaque tick, chaque entité posée sur une assembly est déplacée de ce
 * que son support a bougé.
 *
 * <p><b>Les joueurs.</b> Un joueur envoie ses positions : le serveur ne les calcule pas, il les <b>juge</b>. Son client le porte à la
 * pose qu'il <b>dessine</b>, en retard de son aller-retour et du délai d'interpolation ; le serveur cherche donc le support dans les
 * poses qu'il a gardées (la dernière seconde), à partir du retard estimé — l'aller-retour mesuré sur les snapshots acquittés, la
 * latence de vanilla avant le premier —, par une sonde sous ses pieds de
 * {@code net.carry_tolerance}. Trouvé, le déplacement du support à ce retard entre dans la position du joueur <b>et</b> dans les
 * références de vanilla ({@code firstGood}, {@code lastGood}), si bien que le mouvement que le joueur annonce ensuite se lit comme le
 * sien ; sa collision se juge contre l'assembly à la même pose ; et il n'est pas compté comme volant. Sans support, rien ne change :
 * la détection de vol de vanilla reste entière.
 *
 * <p><b>Les autres entités</b>, que le serveur déplace lui-même : leur support vient de leur collision (D2), et leur déplacement
 * suit la pose du tick, au plus {@code physics.carry_max_entities} par assembly.
 *
 * <p>Dans les deux cas, la place de l'entité dans le corps se retient : la physique voit une entité portée <b>là où elle est sur
 * son support aujourd'hui</b> ({@link EntityPresenceBridge#candidateOf}), d'où l'ordre : avant le pas de la simulation.
 *
 * <p>Chaque assembly apprend enfin qui elle a porté ce tick, et où (ADR-184 D1), au plus
 * {@code physics.carry_max_entities} entités, les joueurs d'abord : ses snapshots le disent aux clients, qui les dessinent
 * sur le corps qu'ils dessinent. Pour un joueur, c'est la place qu'il a envoyée (ADR-185 D1), quand la position jugée la
 * confirme ({@link CarriedPlace}) ; sinon, celle que le serveur calcule, et le refus se compte.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerCarry {

    /** Combien de retards autour de l'estimation on essaie, de part et d'autre. */
    private static final int LAG_SEARCH = 4;

    /** La portée de la sonde, au-delà de la tolérance : le tremblement d'un corps posé. */
    private static final double PROBE_SLACK = 0.1;

    /** Millisecondes d'un tick. */
    private static final double TICK_MILLIS = 50.0;

    /** Une place envoyée sert moins de tant de ticks après sa réception (ADR-185 D2). */
    private static final int PLACE_FRESH_TICKS = 5;

    private static final AtomicLong REFUSED_PLACES = new AtomicLong();

    private ServerCarry() {}

    /** @param event la fin d'un tick du serveur ; avant le pas de la simulation, qui est au dernier rang */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || AssemblyIndex.total() == 0) {
            return;
        }
        AuthoritativeThread.require();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            List<AxionEntity> assemblies = AssemblyIndex.all(level);
            if (assemblies.isEmpty()) {
                continue;
            }
            for (AxionEntity assembly : assemblies) {
                assembly.rememberCarryPoses();
            }
            if (AxionEntities.entityPush()) {
                pushByDriven(level, assemblies);
            }
            Map<AxionEntity, List<AssemblyState.CarriedEntity>> riders = new HashMap<>();
            if (AxionEntities.carry()) {
                for (ServerPlayer player : level.players()) {
                    carryPlayer(player, assemblies);
                    note(riders, player);
                }
                // ADR-178 D7 : au deuxième palier, le gouverneur coupe le transport des entités autres que les joueurs ;
                // jamais la collision ni celui d'un joueur.
                if (AxionEntities.degradationLevel() < 2) {
                    carryOthers(level, riders);
                }
            }
            for (AxionEntity assembly : assemblies) {
                assembly.carriedOnNet(AssemblyState.Carried.of(riders.getOrDefault(assembly, List.of())));
            }
        }
    }

    /** Note une entité portée ce tick pour les snapshots de son assembly (ADR-184 D1), dans la limite par assembly. */
    private static void note(Map<AxionEntity, List<AssemblyState.CarriedEntity>> riders, Entity entity) {
        CarrySupports.Support support = CarrySupports.of(entity);
        double[] local = support == null ? null : support.local();
        if (local == null) {
            return;
        }
        List<AssemblyState.CarriedEntity> carried = riders.computeIfAbsent(support.assembly(), a -> new ArrayList<>());
        long cap = Math.min(AxionEntities.carryMaxEntities(), AssemblyState.Carried.MAX);
        if (carried.size() < cap) {
            double[] said = entity instanceof ServerPlayer player ? said(player, support) : null;
            carried.add(AssemblyState.CarriedEntity.of(entity.getId(), support.body(), said == null ? local : said));
        }
    }

    /**
     * {@return la place que ce joueur a envoyée sur ce support, que sa position confirme, ou {@code null}} (ADR-185 D2) Une
     * place envoyée pour ce support que la position ne confirme à aucun retard se compte ; l'absence de place, ou une place
     * d'un autre support — le temps d'un changement —, non.
     */
    private static double[] said(ServerPlayer player, CarrySupports.Support support) {
        AssemblyState.CarriedEntity place =
                dev.axion.forge.net.AxionNetwork.reportedPlace(player.getUUID(), PLACE_FRESH_TICKS);
        if (place == null || place.entity() != support.assembly().getId() || place.body() != support.body()) {
            return null;
        }
        double[] local = place.local();
        if (CarriedPlace.agrees(support.assembly()::carryPoses, support.body(), local,
                new double[] {player.getX(), player.getY(), player.getZ()}, AxionEntities.carryTolerance())) {
            return local;
        }
        REFUSED_PLACES.incrementAndGet();
        return null;
    }

    /** {@return les places envoyées par des joueurs que leur position n'a pas confirmées, depuis le lancement} */
    static long refusedPlaces() {
        return REFUSED_PLACES.get();
    }

    /** Un joueur : son support se cherche dans les poses gardées, puis il est porté et ses références suivent. */
    private static void carryPlayer(ServerPlayer player, List<AxionEntity> assemblies) {
        // « Au sol » n'est pas demandé : à chaque tick, vanilla fait bouger le joueur dans sa propre simulation puis le remet
        // (doTick), contre la pose du moment tant qu'il n'a pas de support — l'exiger le perdait pour de bon à grande vitesse.
        // La sonde fait foi.
        if (player.isSpectator() || player.isPassenger() || player.noPhysics || !player.isAlive()
                || player.getAbilities().flying || player.isFallFlying() || player.connection == null) {
            CarrySupports.clear(player);
            return;
        }
        double tolerance = AxionEntities.carryTolerance();
        // La latence de vanilla se mesure toutes les quinze secondes, et vaut zéro à la connexion : l'aller-retour des
        // snapshots d'AXION, lui, se mesure à chaque acquittement. Un retard trouvé n'est jamais gardé contre la mesure.
        double measured = dev.axion.forge.net.AxionNetwork.roundTripTicks(player.getUUID());
        double roundTrip = measured >= 0.0 ? measured : player.latency / TICK_MILLIS;
        int estimate = (int) Math.round(roundTrip + AxionEntities.interpolationDelayMillis() / TICK_MILLIS);
        AABB box = player.getBoundingBox();
        List<AxionEntity> near = new ArrayList<>(assemblies.size());
        for (AxionEntity assembly : assemblies) {
            // L'emprise, élargie de ce qu'une assembly parcourt en une seconde à 40 m/s : sa pose d'il y a une seconde compte.
            if (assembly.getBoundingBox().inflate(40.0).intersects(box)) {
                near.add(assembly);
            }
        }
        for (int step = 0; step <= 2 * LAG_SEARCH; step++) {
            // L'estimation d'abord, puis de part et d'autre : 0, +1, -1, +2, -2…
            int lag = estimate + (step % 2 == 1 ? (step + 1) / 2 : -(step / 2));
            if (lag < 0 || lag >= AxionEntity.CARRY_HISTORY - 1) {
                continue;
            }
            for (AxionEntity assembly : near) {
                int body = probe(assembly, assembly.carryPoses(lag), box, tolerance + PROBE_SLACK);
                if (body >= 0) {
                    CarrySupports.landAt(player, assembly, body, lag);
                    shift(player, assembly, body, lag);
                    return;
                }
            }
        }
        CarrySupports.clear(player);
    }

    /** Déplace le joueur, et les références de position de vanilla, de ce que le corps a bougé à ce retard. */
    private static void shift(ServerPlayer player, AxionEntity assembly, int body, int lag) {
        int after = Math.max(lag - 1, 0);
        double[] before = assembly.carryPoses(after + 1);
        double[] now = assembly.carryPoses(after);
        if (before == null || now == null) {
            return;
        }
        double[] local = BodyFrame.toLocal(before, body, player.getX(), player.getY(), player.getZ());
        double[] world = BodyFrame.toWorld(now, body, local);
        double dx = world[0] - player.getX();
        double dy = world[1] - player.getY();
        double dz = world[2] - player.getZ();
        player.setPos(world[0], world[1], world[2]);
        keep(player, BodyFrame.toLocal(now, body, world[0], world[1], world[2]));
        player.connection.firstGoodX += dx;
        player.connection.firstGoodY += dy;
        player.connection.firstGoodZ += dz;
        player.connection.lastGoodX += dx;
        player.connection.lastGoodY += dy;
        player.connection.lastGoodZ += dz;
        player.connection.clientIsFloating = false;
        player.connection.aboveGroundTickCount = 0;
    }

    /** Les entités que le serveur déplace lui-même : portées à la pose du tick, dans la limite par assembly. */
    private static void carryOthers(ServerLevel level, Map<AxionEntity, List<AssemblyState.CarriedEntity>> riders) {
        long cap = AxionEntities.carryMaxEntities();
        Map<AxionEntity, Integer> carried = new HashMap<>();
        for (Map.Entry<Entity, CarrySupports.Support> entry : CarrySupports.serverEntries()) {
            Entity entity = entry.getKey();
            CarrySupports.Support support = entry.getValue();
            if (entity instanceof Player || entity.level() != level) {
                continue;
            }
            if (entity.isRemoved() || entity.isPassenger() || !support.current()) {
                CarrySupports.clear(entity);
                continue;
            }
            if (carried.merge(support.assembly(), 1, Integer::sum) > cap) {
                continue;
            }
            double[] before = support.assembly().carryPoses(1);
            double[] now = support.assembly().carryPoses(0);
            if (before == null || now == null) {
                continue;
            }
            double[] local = BodyFrame.toLocal(before, support.body(), entity.getX(), entity.getY(), entity.getZ());
            double[] world = BodyFrame.toWorld(now, support.body(), local);
            entity.setPos(world[0], world[1], world[2]);
            support.place(BodyFrame.toLocal(now, support.body(), world[0], world[1], world[2]));
            note(riders, entity);
        }
    }

    /**
     * Sort les entités des assemblies menées qui avancent contre elles (ADR-183 D4). Le contact de R-614 pousse celles
     * que la physique voit ({@link EntityPresenceBridge#eligible}) contre une assembly simulée ; une assembly figée,
     * cinématique, n'en a aucun contre elles. Les mêmes sont sorties comme un joueur chez son client (ADR-178 D5) : la
     * plus courte sortie, bornée, arrêtée par les blocs. Ce qu'elle porte est transporté, pas poussé.
     */
    private static void pushByDriven(ServerLevel level, List<AxionEntity> assemblies) {
        for (AxionEntity assembly : assemblies) {
            // Immobile, elle ne pousse plus rien : ses collisions suffisent.
            if (!assembly.isFrozen() || assembly.netSleeping()) {
                continue;
            }
            for (Entity entity : level.getEntities(assembly, assembly.getBoundingBox().inflate(0.1),
                    entity -> !(entity instanceof Player) && EntityPresenceBridge.eligible(entity))) {
                CarrySupports.Support support = CarrySupports.of(entity);
                if (support != null && support.assembly() == assembly) {
                    continue;
                }
                net.minecraft.world.phys.Vec3 push = AssemblyCollision.pushOut(entity);
                if (push != null) {
                    entity.setPos(entity.position().add(push));
                }
            }
        }
    }

    /** Retient la place d'une entité portée dans le corps qui la porte. */
    private static void keep(Entity entity, double[] local) {
        CarrySupports.Support support = CarrySupports.of(entity);
        if (support != null) {
            support.place(local);
        }
    }

    /** {@return le corps que la sonde rencontre sous la boîte, sur une surface qui se marche, ou {@code -1}} */
    private static int probe(AxionEntity assembly, double[] poses, AABB box, double reach) {
        long key = assembly.collisionKey();
        if (poses == null || key == 0L || !assembly.carries()) {
            return -1;
        }
        double[] input = {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, 0.0, -reach, 0.0};
        double[] out = new double[NativeBridge.COLLIDE_OUTPUT];
        int code = AxionEntities.collider().collide(key, NativeBridge.COLLIDE_SWEEP, poses,
                poses.length / NativeBridge.COLLIDE_POSE, input, out);
        return code == NativeBridge.OK && out[0] > 0.5 && out[4] >= AssemblyCollision.WALKABLE ? (int) out[1] : -1;
    }
}
