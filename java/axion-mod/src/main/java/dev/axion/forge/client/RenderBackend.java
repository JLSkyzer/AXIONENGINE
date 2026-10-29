package dev.axion.forge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.axion.asset.GeometryTransfer;
import dev.axion.forge.AxionEntity;
import dev.axion.render.BackendSelection;
import dev.axion.render.BodyRig;
import dev.axion.render.CpuSkinning;
import dev.axion.render.LeveledDraws;
import dev.axion.render.RenderAsset;
import dev.axion.render.RenderPrepCodec;
import java.util.List;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;

/**
 * Backend de rendu des assemblies (ADR-118, §19.2).
 *
 * <p>Deux implémentations à terme : le backend vanilla (C-61), qui passe par les
 * {@code RenderType} de Minecraft sans appel GL direct (R-741), et le backend natif (C-60),
 * pipeline OpenGL dédié. Tous deux respectent les mêmes invariants géométriques (R-1492) :
 * même position, orientation, échelle, LOD et géométrie, quelle que soit la façon de dessiner.
 *
 * <p>Appelé uniquement sur le render thread, par la passe centrale ({@link AxionRenderPass}).
 */
interface RenderBackend {

    /** {@return le type de ce backend} */
    BackendSelection.Kind kind();

    /**
     * Dessine les surfaces opaques puis découpées des assemblies, puis leur émission (passes 1, 2
     * et 5, stage {@code AFTER_ENTITIES}, R-1570).
     *
     * @param frame contexte de la frame
     */
    void renderOpaque(Frame frame);

    /**
     * Dessine les surfaces translucides des assemblies, de la plus lointaine à la plus proche
     * (passe 4, stage {@code AFTER_TRANSLUCENT_BLOCKS}, R-1570, R-1580). Tout ce qui est dessiné
     * y est vidé avant de rendre la main : sous « Fabulous », la cible translucide n'est liée que
     * pendant ce stage.
     *
     * @param frame contexte de la frame
     */
    void renderTranslucent(Frame frame);

    /**
     * Le backend est remplacé — chargement d'un monde, rechargement des ressources (R-752) — ou le
     * monde est quitté : il rend tout ce qu'il tient sur le GPU. Render thread.
     */
    default void close() {}

    /** {@return les octets GPU des maillages que le backend tient ; zéro s'il n'en tient pas (R-750)} */
    default long gpuBytes() {
        return 0;
    }

    /**
     * Décharge le maillage d'un asset, que le budget GPU évince ; rien s'il n'en tient pas.
     *
     * @param asset l'asset évincé
     */
    default void evict(RenderAsset asset) {}

    /**
     * {@return pourquoi le backend ne peut plus dessiner — un shader compilé à la demande refusé par
     * le pilote —, ou {@code null} : il le peut} La passe centrale le remplace alors par le backend
     * vanilla (R-761). Render thread.
     */
    default String failure() {
        return null;
    }

    /**
     * Contexte d'une frame de rendu.
     *
     * @param pose pile de transformations de la vue (rotation caméra appliquée, translation non)
     * @param camera position monde de la caméra
     * @param partialTick fraction du tick écoulée, pour interpoler les positions
     * @param buffers tampons de sommets de Minecraft
     * @param assemblies assemblies à dessiner
     */
    record Frame(
            PoseStack pose,
            Vec3 camera,
            float partialTick,
            MultiBufferSource.BufferSource buffers,
            List<Assembly> assemblies) {}

    /**
     * Une assembly et ce qu'il faut pour la dessiner.
     *
     * @param entity entité de l'assembly
     * @param asset géométrie, pose de repos, apparence des meshes et textures (ADR-119, ADR-122),
     *     ou {@code null} tant que rien n'est prêt — chargement en cours, échec, ou multijoueur :
     *     le backend dessine alors sa boîte de repli
     * @param rig les corps de son asset sous sa definition (ADR-151 D9), ou {@code null} : tout se dessine
     *     alors à la pose de l'assembly
     * @param draws ses dessins à cette frame : ceux de la liste au repos, le mesh de chacun remplacé par
     *     celui du niveau de détail que le natif a choisi (ADR-154, tranche 2b) ; vide sans asset
     * @param palette la première matrice de sa palette d'os dans la table que le natif a remise au GPU, ou
     *     {@link RenderPrepCodec#NO_PALETTE} : ses meshes skinnés se dessinent alors au repos (C-66)
     * @param skin sa palette d'os pour le skinning sur le CPU du backend vanilla (R-742), ou {@code null} : ses
     *     meshes skinnés s'y dessinent alors au repos
     */
    record Assembly(
            AxionEntity entity,
            RenderAsset asset,
            BodyRig rig,
            List<GeometryTransfer.Draw> draws,
            int palette,
            CpuSkinning skin) {

        /**
         * Une assembly dessinée au niveau 0 : les dessins de la liste au repos.
         *
         * @param entity entité de l'assembly
         * @param asset géométrie et apparence, ou {@code null}
         * @param rig les corps de son asset, ou {@code null}
         */
        Assembly(AxionEntity entity, RenderAsset asset, BodyRig rig) {
            this(entity, asset, rig, asset == null ? List.of() : asset.mesh().draws(), RenderPrepCodec.NO_PALETTE,
                    null);
        }

        /**
         * {@return la même assembly, chaque dessin au mesh donné} Inchangée si les meshes sont ceux de la
         * liste au repos, ou s'ils ne sont pas un par dessin — le natif et Java ne s'accorderaient pas,
         * et le niveau 0 reste juste.
         *
         * @param meshes le mesh de chaque dessin, dans l'ordre de la liste au repos
         * @param matrices la matrice de chaque dessin d'une assembly animée (ADR-155), ou {@code null}
         * @param first la première matrice de sa palette d'os, ou {@link RenderPrepCodec#NO_PALETTE}
         */
        Assembly withMeshes(int[] meshes, float[][] matrices, int first) {
            if (asset == null) {
                return this;
            }
            List<GeometryTransfer.Draw> leveled =
                    LeveledDraws.of(asset.mesh().draws(), asset.mesh().meshes().size(), meshes, matrices);
            return leveled == draws && first == palette ? this : new Assembly(entity, asset, rig, leveled, first, skin);
        }

        /**
         * {@return la même assembly, skinnée sur le CPU par cette palette (R-742)}
         *
         * @param cpu sa palette d'os pour la frame
         */
        Assembly withSkin(CpuSkinning cpu) {
            return new Assembly(entity, asset, rig, draws, palette, cpu);
        }
    }
}
