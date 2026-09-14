package dev.axion.api;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Position, orientation et échelle d'une assembly ou d'un node dans le monde.
 *
 * <p>La position est en {@link Vec3} (double), car un monde Minecraft dépasse la
 * précision d'un flottant simple à ses bords (R-462) ; l'orientation est un
 * quaternion JOML, l'échelle un vecteur par axe. Aucun type natif n'apparaît
 * (R-1753).
 *
 * @param position translation monde, en blocs
 * @param rotation orientation, quaternion {@code (x, y, z, w)}
 * @param scale échelle par axe
 */
@Stable
public record Transform(Vec3 position, Quaternionf rotation, Vec3 scale) {}
