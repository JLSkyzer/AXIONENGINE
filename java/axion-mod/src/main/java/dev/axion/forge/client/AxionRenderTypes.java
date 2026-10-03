package dev.axion.forge.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.axion.render.EntityShader;
import dev.axion.render.TextureBinding;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.RenderType;

/**
 * Types de rendu des surfaces d'assets, passes 1, 2, 4 et 5 (ADR-122 §7, R-570, R-741).
 *
 * <p>Les types d'entité vanilla imposent à chaque dessin le filtrage au plus proche, sans mipmap :
 * leur texture est liée par {@code TextureStateShard(rl, false, false)}, qui l'applique. Ceux-ci
 * reprennent leur composition — shader vanilla, mélange, lightmap, overlay, état des faces,
 * écriture de la profondeur —, mais lient la texture avec le filtrage de son téléversement :
 * linéaire si la source le déclare, mipmaps quand il y en a. Aucun appel OpenGL direct, aucun
 * shader propre : des shaders vanilla seulement, ceux qu'un shaderpack remplace (ADR-122).
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
     * {@return le type de rendu d'une surface, ou de son émission}
     *
     * @param texture texture liée, nom enregistré et filtrage
     * @param shader shader vanilla
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

    /** Compose le type, comme le type vanilla du même shader, filtrage et contour mis à part. */
    private static RenderType build(Key key) {
        TextureBinding binding = key.texture();
        TextureStateShard texture = new TextureStateShard(
                VanillaTexturePipeline.location(binding.location()), binding.blur(), binding.mipmap());
        CullStateShard faces = key.doubleSided() ? NO_CULL : CULL;
        String name = "axion_" + key.shader().vanillaName();
        return switch (key.shader()) {
            // Passes 1 et 2 : pas de tri, elles écrivent la profondeur.
            case SOLID -> create(name, DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, true, false,
                    lit(RENDERTYPE_ENTITY_SOLID_SHADER, texture, faces));
            case CUTOUT -> create(name, DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, true, false,
                    lit(RENDERTYPE_ENTITY_CUTOUT_SHADER, texture, faces));
            case CUTOUT_NO_CULL -> create(name, DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, true,
                    false, lit(RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER, texture, faces));
            // Passe 4, comme entity_translucent(_cull) : mélange alpha, profondeur écrite, quads
            // triés au téléversement, du plus loin au plus près.
            case TRANSLUCENT_CULL -> create(name, DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, true,
                    true, blended(RENDERTYPE_ENTITY_TRANSLUCENT_CULL_SHADER, texture, faces));
            case TRANSLUCENT -> create(name, DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, true,
                    true, blended(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER, texture, faces));
            // Passe 5, comme eyes : additive, profondeur testée mais non écrite, sans lightmap ni
            // overlay ; quads triés au téléversement, comme lui.
            case EYES -> create(name, DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, false, true,
                    CompositeState.builder()
                            .setShaderState(RENDERTYPE_EYES_SHADER)
                            .setTextureState(texture)
                            .setTransparencyState(ADDITIVE_TRANSPARENCY)
                            .setCullState(faces)
                            .setWriteMaskState(COLOR_WRITE)
                            .createCompositeState(false));
        };
    }

    /** L'état des passes 1 et 2 : celui des types d'entité vanilla, sans mélange, éclairé. */
    private static CompositeState lit(ShaderStateShard shader, TextureStateShard texture, CullStateShard faces) {
        return entity(shader, texture, faces, NO_TRANSPARENCY);
    }

    /** L'état de la passe 4 : celui des types d'entité translucides vanilla, mélange alpha, éclairé. */
    private static CompositeState blended(ShaderStateShard shader, TextureStateShard texture, CullStateShard faces) {
        return entity(shader, texture, faces, TRANSLUCENT_TRANSPARENCY);
    }

    private static CompositeState entity(
            ShaderStateShard shader, TextureStateShard texture, CullStateShard faces, TransparencyStateShard blend) {
        return CompositeState.builder()
                .setShaderState(shader)
                .setTextureState(texture)
                .setTransparencyState(blend)
                .setCullState(faces)
                .setLightmapState(LIGHTMAP)
                .setOverlayState(OVERLAY)
                .createCompositeState(false);
    }

    /** Une combinaison : texture liée, shader, faces. */
    private record Key(TextureBinding texture, EntityShader shader, boolean doubleSided) {}
}
