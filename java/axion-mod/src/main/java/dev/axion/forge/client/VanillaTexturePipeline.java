package dev.axion.forge.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.axion.render.TextureBinding;
import dev.axion.render.TexturePipeline;
import dev.axion.render.TextureRefusal;
import dev.axion.render.TextureRules;
import dev.axion.render.TextureSampling;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

/**
 * Textures des assets par les moyens du rendu vanilla (C-61, C-26, ADR-122 §7).
 *
 * <p>Décodage par {@link NativeImage}, après contrôle de la signature PNG ; mipmaps par
 * {@link MipmapGenerator}, la recette de l'atlas vanilla ; téléversement dans une
 * {@link AxionTexture} enregistrée auprès du {@code TextureManager}. Aucun appel OpenGL direct
 * (R-741).
 */
final class VanillaTexturePipeline implements TexturePipeline {

    @Override
    public Prepared prepare(Request request) throws TextureRefusal {
        Source source = request.embedded() != null
                ? new Source(request.embedded(), null, null)
                : readResource(request.resource());
        if (!TextureRules.isPng(source.bytes)) {
            throw new TextureRefusal("E-3004 : pas un PNG (R-532)");
        }
        NativeImage image;
        try {
            image = NativeImage.read(source.bytes);
        } catch (IOException | RuntimeException failure) {
            throw new TextureRefusal("PNG illisible : " + failure.getMessage(), failure);
        }
        try {
            String refusal = TextureRules.sizeRefusal(image.getWidth(), image.getHeight());
            if (refusal != null) {
                throw new TextureRefusal(refusal);
            }
            if (request.key().cutout()) {
                binarize(image, request.key().threshold());
            }
            TextureSampling sampling =
                    TextureSampling.of(request.texture().sampler(), source.blur, source.clamp);
            int levels = TextureRules.mipLevels(
                    Minecraft.getInstance().options.mipmapLevels().get(),
                    image.getWidth(),
                    image.getHeight());
            NativeImage[] mips = MipmapGenerator.generateMipLevels(new NativeImage[] {image}, levels);
            String note = sampling.mixedClamp()
                    ? "un seul axe écrêté : rendue en répétition, le rendu vanilla n'écrêtant que"
                            + " les deux à la fois"
                    : null;
            return new PreparedTexture(
                    new TextureBinding(request.location(), sampling.blur(), levels > 0),
                    new AxionTexture(mips, sampling.blur(), sampling.clamp()),
                    note);
        } catch (TextureRefusal | RuntimeException failure) {
            image.close();
            throw failure;
        }
    }

    @Override
    public void upload(Prepared prepared) {
        PreparedTexture texture = (PreparedTexture) prepared;
        ResourceLocation id = location(texture.binding().location());
        TextureManager manager = Minecraft.getInstance().getTextureManager();
        // Sa méthode load ne fait rien : l'enregistrement ne relit aucun fichier.
        manager.register(id, texture.texture);
        try {
            texture.texture.upload();
        } catch (RuntimeException failure) {
            manager.release(id);
            throw failure;
        }
    }

    @Override
    public void release(String location) {
        // Ses types de rendu partent avec elle : aucun ne la lierait plus.
        AxionRenderTypes.forget(location);
        Minecraft.getInstance().getTextureManager().release(location(location));
    }

    @Override
    public void discard(Prepared prepared) {
        ((PreparedTexture) prepared).texture.discard();
    }

    /**
     * Lit une texture de ressource dans les ressources du client, et son {@code .mcmeta}.
     */
    private static Source readResource(String location) throws TextureRefusal {
        ResourceLocation id = location(location);
        Optional<Resource> found = Minecraft.getInstance().getResourceManager().getResource(id);
        if (found.isEmpty()) {
            throw new TextureRefusal("introuvable dans les ressources du client : assets/"
                    + id.getNamespace() + "/" + id.getPath());
        }
        Resource resource = found.get();
        try (InputStream stream = resource.open()) {
            byte[] bytes = stream.readAllBytes();
            Optional<TextureMetadataSection> metadata =
                    resource.metadata().getSection(TextureMetadataSection.SERIALIZER);
            return metadata
                    .map(section -> new Source(bytes, section.isBlur(), section.isClamp()))
                    .orElseGet(() -> new Source(bytes, null, null));
        } catch (IOException failure) {
            throw new TextureRefusal("illisible : " + failure.getMessage(), failure);
        }
    }

    /**
     * Binarise l'alpha d'une image au seuil d'un matériau CUTOUT : la coupe à 0,1 des shaders
     * d'entité vanilla tombe alors exactement à ce seuil (ADR-122 §7).
     */
    private static void binarize(NativeImage image, float threshold) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getPixelRGBA(x, y);
                int alpha = TextureRules.cutoutAlpha(pixel >>> 24, threshold);
                image.setPixelRGBA(x, y, (pixel & 0x00FFFFFF) | (alpha << 24));
            }
        }
    }

    /** Une {@code ResourceLocation} que le cache a déjà validée. */
    static ResourceLocation location(String location) {
        ResourceLocation id = ResourceLocation.tryParse(location);
        if (id == null) {
            throw new IllegalArgumentException("ResourceLocation invalide : " + location);
        }
        return id;
    }

    /**
     * Octets d'une texture et réglages de son {@code .mcmeta}.
     *
     * @param bytes octets du fichier
     * @param blur {@code blur} déclaré, ou {@code null}
     * @param clamp {@code clamp} déclaré, ou {@code null}
     */
    private record Source(byte[] bytes, Boolean blur, Boolean clamp) {}

    /**
     * Une texture prête à téléverser.
     *
     * @param binding nom sous lequel l'enregistrer, et filtrage du téléversement
     * @param texture la texture et ses niveaux de mipmaps
     * @param note ce qu'il faut signaler une fois, ou {@code null}
     */
    private record PreparedTexture(TextureBinding binding, AxionTexture texture, String note) implements Prepared {}
}
