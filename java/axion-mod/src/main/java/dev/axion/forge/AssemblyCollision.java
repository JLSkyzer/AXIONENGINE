package dev.axion.forge;

import dev.axion.bridge.NativeBridge;
import dev.axion.physics.BoxSlide;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ce que les colliders d'une assembly font du déplacement d'une entité (ADR-178 D1, CAP-14) : l'injection de
 * {@code Entity.collide} (dev.axion.mixin) l'appelle à la sortie de la collision de vanilla, avec le déplacement voulu et celui que
 * les blocs ont laissé.
 *
 * <p>Le contrat d'une injection d'AXION (R-1921) : elle ne remplace rien. Elle rend {@code null} — vanilla garde son
 * déplacement — ou un déplacement <b>plus court</b> ; toute exception rend {@code null}, se dit une fois au journal, et compte :
 * à trois, la fonction s'éteint pour la session (R-1912). Sans assembly au monde, le coût est la lecture d'un compteur.
 *
 * <p>Le balayage est celui du natif, contre la definition posée : le serveur la pose à son dernier tick, le client à la pose que
 * son tick a prise de l'interpolation — celle que le joueur voit.
 */
public final class AssemblyCollision {

    private static final Logger LOGGER = LoggerFactory.getLogger("AXION collision");

    /** Après combien d'échecs la fonction s'éteint. */
    static final int MAX_FAILURES = 3;

    private static final AtomicInteger FAILURES = new AtomicInteger();

    /** Vrai une fois qu'on a dit qu'une poussée s'est arrêtée à un bloc : une fois par session. */
    private static final AtomicBoolean PINNED = new AtomicBoolean();

    private static final ThreadLocal<double[][]> BUFFERS = ThreadLocal.withInitial(
            () -> new double[][] {new double[NativeBridge.COLLIDE_INPUT], new double[NativeBridge.COLLIDE_OUTPUT]});

    private AssemblyCollision() {}

    /** La normale à partir de laquelle une surface se marche : plus raide que 45°, l'entité n'y tient pas. */
    static final double WALKABLE = 0.7;

    /** Une assembly posée, prête à être balayée. */
    private record Placed(AxionEntity assembly, long key, double[] poses, int count) {}

    /** Ce qui a arrêté l'entité vers le bas, le temps d'un déplacement : l'assembly et le corps. */
    private static final class Landing {
        private AxionEntity assembly;
        private int body;
    }

    /**
     * Le déplacement qu'une entité peut faire, une fois les assemblies prises en compte.
     *
     * @param entity l'entité qui bouge
     * @param requested le déplacement voulu
     * @param vanilla le déplacement que les blocs et les entités de vanilla ont laissé
     * @return un déplacement plus court, ou {@code null} : celui de vanilla reste
     */
    public static Vec3 adjust(Entity entity, Vec3 requested, Vec3 vanilla) {
        if (AssemblyIndex.total() == 0 || FAILURES.get() >= MAX_FAILURES) {
            return null;
        }
        try {
            return compute(entity, vanilla);
        } catch (RuntimeException | LinkageError failure) {
            if (FAILURES.incrementAndGet() == 1) {
                LOGGER.warn("AXION : la collision des entités avec les assemblies a échoué ; vanilla garde son"
                        + " déplacement (R-1921)", failure);
            }
            if (FAILURES.get() >= MAX_FAILURES) {
                LOGGER.warn("AXION : la collision des entités avec les assemblies s'éteint pour la session (R-1912)");
            }
            return null;
        }
    }

    private static Vec3 compute(Entity entity, Vec3 motion) {
        if (AssemblyIndex.isAssembly(entity) || motion.lengthSqr() < 1.0e-12) {
            return null;
        }
        if (entity.noPhysics || entity.isSpectator() || entity.isPassenger() || !AxionEntities.carry()) {
            CarrySupports.clear(entity);
            return null;
        }
        AABB box = entity.getBoundingBox();
        List<AxionEntity> near = AssemblyIndex.near(entity.level(), box.expandTowards(motion).inflate(0.25));
        if (near.isEmpty()) {
            CarrySupports.clear(entity);
            return null;
        }
        // ADR-178 D4 : un joueur du serveur se juge, son support compris, à la pose que son client dessine ; ses supports
        // sont l'affaire de ServerCarry.
        boolean serverPlayer = entity instanceof net.minecraft.server.level.ServerPlayer;
        CarrySupports.Support support = serverPlayer ? CarrySupports.of(entity) : null;
        List<Placed> placed = new ArrayList<>(near.size());
        for (AxionEntity assembly : near) {
            long key = assembly.collisionKey();
            double[] poses = key == 0L ? null
                    : support != null && support.assembly() == assembly && support.lag() >= 0
                            ? assembly.carryPoses(support.lag()) : assembly.collisionPoses();
            if (poses != null) {
                placed.add(new Placed(assembly, key, poses, poses.length / NativeBridge.COLLIDE_POSE));
            }
        }
        if (placed.isEmpty()) {
            if (!serverPlayer) {
                CarrySupports.clear(entity);
            }
            return null;
        }
        Landing landing = new Landing();
        BoxSlide.Sweeper sweeper = (at, mx, my, mz) -> sweep(placed, at, mx, my, mz, landing);
        double[] slid = BoxSlide.slide(new double[] {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ},
                new double[] {motion.x, motion.y, motion.z}, sweeper, entity.maxUpStep(), entity.onGround());
        // ADR-178 D2 : le support naît d'une chute arrêtée par une surface qu'on marche ; sinon, il meurt. Celui d'un
        // joueur au serveur se cherche à la fin du tick (D4) : ses déplacements annoncés n'y disent rien.
        if (!serverPlayer) {
            if (motion.y < 0.0 && slid[1] > motion.y + 1.0e-9 && landing.assembly != null && landing.assembly.carries()) {
                CarrySupports.land(entity, landing.assembly, landing.body);
            } else {
                CarrySupports.clear(entity);
            }
        }
        if (Math.abs(slid[0] - motion.x) < 1.0e-9 && Math.abs(slid[1] - motion.y) < 1.0e-9
                && Math.abs(slid[2] - motion.z) < 1.0e-9) {
            return null;
        }
        return new Vec3(slid[0], slid[1], slid[2]);
    }

    /** Le premier contact de la boîte avec n'importe laquelle des assemblies posées. */
    private static BoxSlide.Hit sweep(List<Placed> placed, double[] box, double mx, double my, double mz,
            Landing landing) {
        double[][] buffers = BUFFERS.get();
        double[] input = buffers[0];
        double[] out = buffers[1];
        System.arraycopy(box, 0, input, 0, 6);
        input[6] = mx;
        input[7] = my;
        input[8] = mz;
        BoxSlide.Hit best = null;
        Placed bestOf = null;
        int bestBody = 0;
        for (Placed assembly : placed) {
            int code = AxionEntities.collider().collide(assembly.key(), NativeBridge.COLLIDE_SWEEP, assembly.poses(),
                    assembly.count(), input, out);
            if (code != NativeBridge.OK) {
                // La definition n'est pas (encore) compilée au natif de ce côté : cette assembly n'arrête personne.
                continue;
            }
            if (out[0] > 0.5 && (best == null || out[2] < best.fraction())) {
                best = new BoxSlide.Hit(out[2], out[3], out[4], out[5]);
                bestOf = assembly;
                bestBody = (int) out[1];
            }
        }
        if (best != null && my < 0.0 && best.ny() >= WALKABLE) {
            landing.assembly = bestOf.assembly();
            landing.body = bestBody;
        }
        return best;
    }

    /**
     * Ce qui sort une entité d'une assembly qui avance dans elle (ADR-178 D5) : la plus courte sortie, bornée par
     * {@code physics.push_speed_max}, puis arrêtée par les blocs — la poussée ne met personne dans un mur. Chez le client,
     * pour le joueur local, qui est maître de sa position.
     *
     * @param entity l'entité
     * @return le déplacement qui la sort, ou ce que les blocs en laissent ; {@code null} si elle n'est dans aucune assembly
     */
    public static Vec3 pushOut(Entity entity) {
        if (AssemblyIndex.total() == 0 || FAILURES.get() >= MAX_FAILURES || !AxionEntities.carry() || entity.noPhysics
                || entity.isSpectator() || entity.isPassenger()) {
            return null;
        }
        // La boîte rentrée d'un millième : reposer sur un sol n'est pas s'y enfoncer.
        AABB box = entity.getBoundingBox().deflate(1.0e-3);
        List<AxionEntity> near = AssemblyIndex.near(entity.level(), box);
        if (near.isEmpty()) {
            return null;
        }
        double[][] buffers = BUFFERS.get();
        double[] input = buffers[0];
        double[] out = buffers[1];
        double[] bounds = {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ};
        System.arraycopy(bounds, 0, input, 0, 6);
        input[6] = 0.0;
        input[7] = 0.0;
        input[8] = 0.0;
        Vec3 push = null;
        double deepest = 0.0;
        for (AxionEntity assembly : near) {
            long key = assembly.collisionKey();
            double[] poses = key == 0L ? null : assembly.collisionPoses();
            if (poses == null) {
                continue;
            }
            int code = AxionEntities.collider().collide(key, NativeBridge.COLLIDE_PENETRATION, poses,
                    poses.length / NativeBridge.COLLIDE_POSE, input, out);
            if (code == NativeBridge.OK && out[0] > 0.5 && out[2] > deepest) {
                deepest = out[2];
                double reach = Math.min(out[2], AxionEntities.pushSpeedMax() / 20.0);
                push = new Vec3(out[3] * reach, out[4] * reach, out[5] * reach);
            }
        }
        if (push == null) {
            return null;
        }
        // Vanilla glisse ce déplacement contre les blocs, comme un pas ; le joueur que rien ne laisse sortir reste contre eux.
        Vec3 free = Entity.collideBoundingBox(entity, push, entity.getBoundingBox(), entity.level(), List.of());
        if (free.distanceToSqr(push) > 1.0e-6 && PINNED.compareAndSet(false, true)) {
            LOGGER.warn("AXION : une assembly pousse {} contre un bloc ; il reste contre le bloc (ADR-178 D5)",
                    entity.getName().getString());
        }
        return free;
    }

    /** Rend à la fonction sa première heure : pour un test. */
    static void reset() {
        FAILURES.set(0);
    }
}
