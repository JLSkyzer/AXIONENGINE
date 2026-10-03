package dev.axion.forge.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.axion.render.EntityShader;
import dev.axion.render.TextureBinding;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.RenderType;

/**
 * Types de rendu des surfaces d'assets, passes 1 et 2 (ADR-122 §7, R-570, R-741).
 *
 * <p>Les types d'entité vanilla imposent à chaque dessin le filtrage au plus proche, sans mipmap :
 * leur texture est liée par {@code TextureStateShard(rl, false, false)}, qui l'applique. Ceux-ci
 * reprennent leur composition — shader d'entité vanilla, sans mélange, lightmap, overlay, état des
 * faces —, mais lient la texture avec le filtrage de son téléversement : linéaire si la source le
 * déclare, mipmaps quand il y en a. Aucun appel OpenGL direct, aucun shader propre : un shaderpack
 * reconnaît les shaders vanilla et les remplace.
 *
 * <p><b>Sans contour.</b> Un type à contour crée, pour sa texture, un type de contour que
 * Minecraft mémorise dans une table qui ne se vide jamais ({@code RenderType.OUTLINE}, relevé
 * dans le bytecode) ; les textures d'AXION changent de nom à chaque chargement, la table
 * grossirait d'autant. Les assemblies ne dessinent pas de contour.
 *
 * <p>Un type se crée au premier dessin de sa combinaison, puis sert jusqu'à la libération de sa
 * texture ({@link #forget}). Sous-classe de {@code RenderType} pour atteindre ses états
 * {@code protected} ; jamais instanciée. Fil de rendu seul.
 */
abstract class AxionRenderTypes extends RenderType {

    /** Types créés, par texture, shader et faces. */
    private static final Map<Key, RenderType> TYPES = new HashMap<>();

    /** Jamais appelé : la classe ne sert qu'à atteindre les états de {@code RenderType}. */
    private AxionRenderTypes() {
        super("axion", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 0, false, false, () -> {}, () -> {});
    }

    /**
     * {@return le type de rendu d'une surface des passes 1 et 2}
     *
     * @param texture texture liée, nom enregistré et filtrage
     * @param shader shader d'entité vanilla
     * @param doubleSided vrai si les deux faces se dessinent
     */
    static RenderType entity(TextureBinding texture, EntityShader shader, boolean doubleSided) {
        return TYPES.computeIfAbsent(new Key(texture, shader, doubleSided), AxionRenderTypes::build);
    }

    /**
     * Oublie les types d'une texture libérée.
     *
     * @param location nom sous lequel la texture était enregistrée
     */
    static void forget(String location) {
        TYPES.keySet().removeIf(key -> key.texture().location().equals(location));
    }

    /** Compose le type, comme le type d'entité vanilla du même shader, filtrage mis à part. */
    private static RenderType build(Key key) {
        ShaderStateShard shader = switch (key.shader()) {
            case SOLID -> RENDERTYPE_ENTITY_SOLID_SHADER;
            case CUTOUT -> RENDERTYPE_ENTITY_CUTOUT_SHADER;
            case CUTOUT_NO_CULL -> RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER;
        };
        TextureBinding texture = key.texture();
        CompositeState state = CompositeState.builder()
                .setShaderState(shader)
                .setTextureState(new TextureStateShard(
                        VanillaTexturePipeline.location(texture.location()), texture.blur(), texture.mipmap()))
                .setTransparencyState(NO_TRANSPARENCY)
                .setCullState(key.doubleSided() ? NO_CULL : CULL)
                .setLightmapState(LIGHTMAP)
                .setOverlayState(OVERLAY)
                .createCompositeState(false);
        // Mêmes format, mode et tampon initial que les types d'entité vanilla ; pas de tri, ces
        // passes écrivent la profondeur.
        return create("axion_" + key.shader().vanillaName(), DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS, 256, true, false, state);
    }

    /** Une combinaison : texture liée, shader, faces. */
    private record Key(TextureBinding texture, EntityShader shader, boolean doubleSided) {}
}
