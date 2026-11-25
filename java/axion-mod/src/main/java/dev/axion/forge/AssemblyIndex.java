package dev.axion.forge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Les assemblies vivantes de chaque monde, pour la collision des entités (ADR-178 D1, CAP-14).
 *
 * <p>{@code Entity.collide} est appelée pour chaque entité qui bouge, à chaque tick, dans un monde qui n'a presque
 * jamais d'assembly : tant qu'aucune n'est au monde — nulle part —, la question coûte la lecture d'un compteur. Sinon, les
 * assemblies du monde de l'entité se parcourent, et celles que son déplacement ne touche pas s'écartent par leur emprise.
 *
 * <p>Les deux côtés tiennent leur propre index : un monde du serveur et un monde du client sont deux objets.
 */
@Mod.EventBusSubscriber(modid = dev.axion.AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class AssemblyIndex {

    private static final Map<Level, Set<AxionEntity>> BY_LEVEL = new WeakHashMap<>();
    private static final AtomicInteger TOTAL = new AtomicInteger();

    private AssemblyIndex() {}

    /** @param event une entité entre dans un monde : si c'est une assembly, elle s'inscrit */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof AxionEntity assembly) {
            synchronized (BY_LEVEL) {
                if (BY_LEVEL.computeIfAbsent(event.getLevel(), level -> new CopyOnWriteArraySet<>()).add(assembly)) {
                    TOTAL.incrementAndGet();
                }
            }
        }
    }

    /** @param event une entité quitte un monde : si c'est une assembly, elle s'efface */
    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof AxionEntity assembly) {
            synchronized (BY_LEVEL) {
                Set<AxionEntity> here = BY_LEVEL.get(event.getLevel());
                if (here != null && here.remove(assembly)) {
                    TOTAL.decrementAndGet();
                }
            }
        }
    }

    /** {@return le nombre d'assemblies vivantes, tous mondes confondus : 0 dans l'immense majorité des parties} */
    public static int total() {
        return TOTAL.get();
    }

    /**
     * {@return les assemblies de ce monde dont l'emprise touche la boîte} Une copie : la liste se parcourt pendant que le monde
     * en inscrit d'autres.
     *
     * @param level le monde
     * @param box la boîte, en coordonnées monde
     */
    public static List<AxionEntity> near(Level level, AABB box) {
        Set<AxionEntity> here;
        synchronized (BY_LEVEL) {
            here = BY_LEVEL.get(level);
        }
        if (here == null || here.isEmpty()) {
            return List.of();
        }
        List<AxionEntity> found = new ArrayList<>(2);
        for (AxionEntity assembly : here) {
            if (!assembly.isRemoved() && assembly.getBoundingBox().intersects(box)) {
                found.add(assembly);
            }
        }
        return found;
    }

    /** {@return vrai si cette entité est une assembly : elle n'est jamais arrêtée par ses semblables} */
    static boolean isAssembly(Entity entity) {
        return entity instanceof AxionEntity;
    }
}
