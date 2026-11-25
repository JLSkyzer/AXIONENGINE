package dev.axion.mixin;

import dev.axion.forge.AssemblyCollision;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * La collision des entités avec les assemblies (ADR-178 D1, CAP-14 ; cible de §26.2 acceptée par ADR-177 D4, amendement A28).
 *
 * <p>À la sortie de {@code Entity.collide}, après que vanilla a glissé le déplacement contre les blocs, {@link AssemblyCollision}
 * le raccourcit contre les colliders des assemblies. Rien n'est remplacé ni annulé (R-1921) : sans assembly, ou en cas de faute,
 * le déplacement de vanilla est rendu tel quel. Client et serveur.
 */
@Mixin(Entity.class)
public abstract class EntityCollideMixin {

    @Inject(method = "collide", at = @At("RETURN"), cancellable = true)
    private void axion$collide(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        Vec3 adjusted = AssemblyCollision.adjust((Entity) (Object) this, movement, cir.getReturnValue());
        if (adjusted != null) {
            cir.setReturnValue(adjusted);
        }
    }
}
