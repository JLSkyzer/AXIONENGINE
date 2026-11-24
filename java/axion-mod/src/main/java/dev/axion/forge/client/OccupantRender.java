package dev.axion.forge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.axion.AxionMod;
import dev.axion.definition.Seating;
import dev.axion.forge.AxionEntity;
import dev.axion.forge.OccupantStance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Le rendu d'un occupant réglé par son siège (ADR-177 D1), chez le client, sans Mixin : sa taille et sa pose.
 *
 * <p>Avant que Minecraft ne dessine un passager d'assembly, {@link RenderLivingEvent.Pre} met le dessin à l'échelle
 * du siège, <b>autour du point du siège</b>, et dit à {@link AxionEntity#shouldRiderSit()} si l'occupant est debout ;
 * {@link RenderLivingEvent.Post} défait l'un et l'autre. Seul le dessin change : la boîte de collision, la sortie
 * sûre (R-711) et ce que le serveur lit restent ceux d'un joueur normal.
 *
 * <p>La pile de matrices garde sa hauteur : l'échelle se pose sur la matrice courante et se défait par la matrice
 * inverse, sans {@code pushPose} ni {@code popPose}. Si un autre mod annule le dessin après AXION, {@code Post} ne
 * vient pas, et l'échelle reste sur la matrice de cette entité, que le répartiteur de Minecraft dépile avec elle.
 *
 * <p>Tous les clients lisent la même definition : un autre joueur voit l'occupant à la même échelle.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class OccupantRender {

    private OccupantRender() {}

    /** Avant le dessin d'un être vivant : si c'est l'occupant réglé d'une assembly, son échelle et sa pose. */
    @SubscribeEvent
    public static void onPre(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity entity = event.getEntity();
        Seating.Occupant occupant = occupantOf(entity);
        OccupantStance.standing(occupant != null && occupant.pose() == Seating.Pose.STAND);
        if (occupant != null && occupant.scale() != 1f) {
            about(event.getPoseStack(), anchor(entity), occupant.scale());
        }
    }

    /** Après le dessin : l'échelle et la pose reviennent à celles de Minecraft. */
    @SubscribeEvent
    public static void onPost(RenderLivingEvent.Post<?, ?> event) {
        LivingEntity entity = event.getEntity();
        Seating.Occupant occupant = occupantOf(entity);
        if (occupant != null && occupant.scale() != 1f) {
            about(event.getPoseStack(), anchor(entity), 1f / occupant.scale());
        }
        OccupantStance.standing(false);
    }

    /** {@return ce que son siège règle de cet être, {@code null} s'il n'est pas passager d'une assembly} */
    private static Seating.Occupant occupantOf(LivingEntity entity) {
        return entity.getVehicle() instanceof AxionEntity assembly ? assembly.occupantOf(entity) : null;
    }

    /**
     * {@return la hauteur du point du siège au-dessus des pieds du passager, dans le repère du dessin} Les pieds sont
     * posés au siège, décalage de monture compris : le point du siège est donc l'opposé de ce décalage.
     */
    static double anchor(LivingEntity entity) {
        return entity.getVehicle() instanceof AxionEntity assembly ? -assembly.riderOffset(entity) : 0.0;
    }

    /** Multiplie la matrice courante par une mise à l'échelle uniforme de centre {@code (0, height, 0)}. */
    static void about(PoseStack stack, double height, float factor) {
        stack.translate(0.0, height, 0.0);
        stack.scale(factor, factor, factor);
        stack.translate(0.0, -height, 0.0);
    }
}
