package dev.axion.forge.client;

import dev.axion.api.hud.DashboardCanvas;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * La toile d'un widget du tableau de bord (ADR-170 D6), sur le {@link GuiGraphics} de l'overlay : un rectangle de
 * l'écran, origine en haut à gauche, coupé à ses bords. L'échelle du joueur est déjà dans la pose de {@code graphics} ;
 * la découpe, que Minecraft prend en pixels d'interface sans pose, la multiplie elle-même.
 */
final class GuiDashboardCanvas implements DashboardCanvas {

    private final GuiGraphics graphics;
    private final Font font;
    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final float scale;

    GuiDashboardCanvas(GuiGraphics graphics, Font font, int x, int y, int width, int height, float scale) {
        this.graphics = graphics;
        this.font = font;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.scale = scale;
    }

    /** {@return le {@link GuiGraphics} de l'overlay} */
    GuiGraphics graphics() {
        return graphics;
    }

    /** Ouvre la découpe et place l'origine au coin du rectangle. */
    void begin() {
        graphics.enableScissor(Math.round(x * scale), Math.round(y * scale), Math.round((x + width) * scale),
                Math.round((y + height) * scale));
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
    }

    /** Referme ce que {@link #begin} a ouvert, même après une exception du widget. */
    void end() {
        graphics.pose().popPose();
        graphics.disableScissor();
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public void fill(int left, int top, int w, int h, int argb) {
        graphics.fill(left, top, left + w, top + h, argb);
    }

    @Override
    public void line(double x1, double y1, double x2, double y2, int argb) {
        int steps = (int) Math.ceil(Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1)));
        for (int step = 0; step <= steps; step++) {
            double t = steps == 0 ? 0 : step / (double) steps;
            int px = (int) Math.floor(x1 + (x2 - x1) * t);
            int py = (int) Math.floor(y1 + (y2 - y1) * t);
            graphics.fill(px, py, px + 1, py + 1, argb);
        }
    }

    @Override
    public void text(String text, int left, int top, int argb) {
        graphics.drawString(font, text, left, top, argb, true);
    }

    @Override
    public void translated(String key, int left, int top, int argb, Object... arguments) {
        graphics.drawString(font, Component.translatable(key, arguments).getString(), left, top, argb, true);
    }

    @Override
    public int textWidth(String text) {
        return font.width(text);
    }

    @Override
    public int lineHeight() {
        return font.lineHeight;
    }

    @Override
    public void texture(String location, int left, int top, int w, int h) {
        ResourceLocation texture = ResourceLocation.tryParse(location);
        if (texture != null) {
            graphics.blit(texture, left, top, w, h, 0f, 0f, 1, 1, 1, 1);
        }
    }
}
