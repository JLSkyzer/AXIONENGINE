package dev.axion.forge.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.axion.render.TextureBinding;
import dev.axion.render.TextureKey;
import dev.axion.render.TexturePipeline;
import dev.axion.render.TextureRefusal;
import dev.axion.render.TextureRules;
import dev.axion.render.TextureSampling;
import java.io.ByteArrayInputStream;
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
 * <p>Décodage par {@link NativeImage}, après contrôle de la signature PNG, toujours en RGBA ;
 * variantes — alpha binarisé d'une découpe, émissive masquée par l'albedo — texel par texel ;
 * mipmaps par {@link MipmapGenerator}, la recette de l'atlas vanilla ; téléversement dans une
 * {@link AxionTexture} enregistrée auprès du {@code TextureManager}. Aucun appel OpenGL direct
 * (R-741).
 */
final class VanillaTexturePipeline implements TexturePipeline {

    @Override
    public Prepared prepare(Request request) throws TextureRefusal {
        TextureKey key = request.key();
        Decoded image = null;
        Decoded mask = null;
        NativeImage texture = null;
        try {
            image = request.image() == null ? null : decode(request.image());
            mask = request.mask() == null ? null : decode(request.mask());
            // L'image d'origine donne ses dimensions et son échantillonnage à la texture :
            // l'émissive, ou l'albedo qui masque du blanc.
            TextureSampling sampling = (image != null ? image : mask).sampling();
            if (key.masked()) {
                texture = masked(image, mask.image(), key.threshold());
            } else {
                texture = image.image();
                image = null;
                if (key.cutout()) {
                    binarize(texture, key.threshold());
                }
            }
            int levels = TextureRules.mipLevels(
                    Minecraft.getInstance().options.mipmapLevels().get(),
                    texture.getWidth(),
                    texture.getHeight());
            NativeImage[] mips = MipmapGenerator.generateMipLevels(new NativeImage[] {texture}, levels);
            String note = sampling.mixedClamp()
                    ? "un seul axe écrêté : rendue en répétition, le rendu vanilla n'écrêtant que"
                            + " les deux à la fois"
                    : null;
            Prepared prepared = new PreparedTexture(
                    new TextureBinding(request.location(), sampling.blur(), levels > 0),
                    new AxionTexture(mips, sampling.blur(), sampling.clamp()),
                    note);
            // Ses niveaux appartiennent désormais à la texture préparée.
            texture = null;
            return prepared;
        } finally {
            close(image);
            close(mask);
            if (texture != null) {
                texture.close();
            }
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
     * Lit une image et la décode, après en avoir contrôlé la signature (R-532) et les dimensions
     * (R-570).
     *
     * <p>Par un flux, que Minecraft copie hors de la pile : {@code read(byte[])} copierait le PNG
     * sur la pile de LWJGL, 64 Kio par défaut, qu'une texture plus grosse fait déborder — une
     * {@code OutOfMemoryError}, qui n'est pas une {@code RuntimeException} (relevé dans le
     * bytecode). En RGBA, que {@code getPixelRGBA} — découpe, masque, mipmaps — exige.
     */
    private static Decoded decode(Source source) throws TextureRefusal {
        Loaded file = source.embedded() != null
                ? new Loaded(source.embedded(), null, null)
                : readResource(source.resource());
        if (!TextureRules.isPng(file.bytes())) {
            throw new TextureRefusal("E-3004 : pas un PNG (R-532)");
        }
        NativeImage image;
        try {
            image = NativeImage.read(NativeImage.Format.RGBA, new ByteArrayInputStream(file.bytes()));
        } catch (IOException | RuntimeException failure) {
            throw new TextureRefusal("PNG illisible : " + failure.getMessage(), failure);
        }
        String refusal = TextureRules.sizeRefusal(image.getWidth(), image.getHeight());
        if (refusal != null) {
            image.close();
            throw new TextureRefusal(refusal);
        }
        return new Decoded(image, TextureSampling.of(source.texture().sampler(), file.blur(), file.clamp()));
    }

    /**
     * Lit une texture de ressource dans les ressources du client, et son {@code .mcmeta}.
     */
    private static Loaded readResource(String location) throws TextureRefusal {
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
                    .map(section -> new Loaded(bytes, section.isBlur(), section.isClamp()))
                    .orElseGet(() -> new Loaded(bytes, null, null));
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

    /**
     * Une émissive — ou du blanc — masquée par l'albedo binarisé au seuil : sa couleur là où
     * l'albedo est gardé, du noir ailleurs. Aux dimensions de l'émissive, de l'albedo pour du
     * blanc ; le masque est lu au plus proche.
     *
     * @param emission l'émissive, ou {@code null} pour du blanc
     * @param mask l'albedo
     * @param threshold seuil de la découpe du matériau
     */
    private static NativeImage masked(Decoded emission, NativeImage mask, float threshold) {
        int width = emission == null ? mask.getWidth() : emission.image().getWidth();
        int height = emission == null ? mask.getHeight() : emission.image().getHeight();
        NativeImage out = new NativeImage(width, height, false);
        try {
            for (int y = 0; y < height; y++) {
                int maskY = TextureRules.maskCoordinate(y, height, mask.getHeight());
                for (int x = 0; x < width; x++) {
                    int maskX = TextureRules.maskCoordinate(x, width, mask.getWidth());
                    int color = emission == null ? 0xFFFFFFFF : emission.image().getPixelRGBA(x, y);
                    out.setPixelRGBA(x, y,
                            TextureRules.maskedEmission(color, mask.getPixelRGBA(maskX, maskY) >>> 24, threshold));
                }
            }
            return out;
        } catch (RuntimeException failure) {
            out.close();
            throw failure;
        }
    }

    private static void close(Decoded decoded) {
        if (decoded != null) {
            decoded.image().close();
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
     * Une image décodée en RGBA, et l'échantillonnage que sa source déclare.
     *
     * @param image l'image
     * @param sampling filtrage et répétition, de l'échantillonneur ou du {@code .mcmeta}
     */
    private record Decoded(NativeImage image, TextureSampling sampling) {}

    /**
     * Octets d'un fichier et réglages de son {@code .mcmeta}.
     *
     * @param bytes octets du fichier
     * @param blur {@code blur} déclaré, ou {@code null}
     * @param clamp {@code clamp} déclaré, ou {@code null}
     */
    private record Loaded(byte[] bytes, Boolean blur, Boolean clamp) {}

    /**
     * Une texture prête à téléverser.
     *
     * @param binding nom sous lequel l'enregistrer, et filtrage du téléversement
     * @param texture la texture et ses niveaux de mipmaps
     * @param note ce qu'il faut signaler une fois, ou {@code null}
     */
    private record PreparedTexture(TextureBinding binding, AxionTexture texture, String note) implements Prepared {}
}
