package dev.axion.forge.client;

import dev.axion.asset.GeometryTransfer;
import dev.axion.render.BodyRig;
import dev.axion.render.BodySkeleton;

/**
 * Où dessiner chaque mesh d'une assembly (ADR-151 D9) : à la pose du corps qui porte son node, avec sa
 * transformation de repos exprimée dans le repère de ce corps. Commun aux deux backends, pour qu'ils posent
 * chaque corps au même endroit.
 *
 * <p>Sans corps secondaire — une assembly à un seul corps, ou aucun snapshot encore —, ou si leurs racines ne
 * se lisent pas contre l'asset, tout se dessine à la pose de l'assembly, à la transformation de repos telle
 * quelle : le dessin d'avant les corps multiples.
 */
final class BodyParts {

    private final BodySkeleton skeleton;

    /** La pose de chaque corps : le corps racine, puis les autres dans l'ordre du squelette. */
    private final AssemblyPlacement[] placements;

    private BodyParts(BodySkeleton skeleton, AssemblyPlacement[] placements) {
        this.skeleton = skeleton;
        this.placements = placements;
    }

    /**
     * @param at pose de l'assembly pour la frame, ses corps secondaires compris
     * @param rig les corps de son asset sous sa definition, ou {@code null}
     * @return les poses de ses corps
     */
    static BodyParts of(AssemblyPlacement at, BodyRig rig) {
        BodySkeleton skeleton = rig == null ? null : rig.skeleton(at.bodyNodes());
        if (skeleton == null) {
            return new BodyParts(null, new AssemblyPlacement[] {at});
        }
        AssemblyPlacement[] placements = new AssemblyPlacement[skeleton.bodies()];
        placements[0] = at;
        for (int body = 1; body < placements.length; body++) {
            placements[body] = at.body(body - 1);
        }
        return new BodyParts(skeleton, placements);
    }

    /** {@return la pose de l'assembly, celle de son corps racine} */
    AssemblyPlacement assembly() {
        return placements[0];
    }

    /** {@return la pose du corps qui porte le node d'un mesh} */
    AssemblyPlacement at(GeometryTransfer.Draw draw) {
        return placements[body(draw)];
    }

    /** {@return la transformation de repos d'un mesh, dans le repère du corps qui le porte} */
    float[] model(GeometryTransfer.Draw draw) {
        int body = body(draw);
        return body == 0 ? draw.model() : skeleton.modelInBody(body, draw.model());
    }

    private int body(GeometryTransfer.Draw draw) {
        return skeleton == null ? 0 : skeleton.bodyOf(draw.node());
    }
}
