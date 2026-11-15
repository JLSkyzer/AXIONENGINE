package dev.axion.forge.client;

import dev.axion.AxionMod;
import dev.axion.config.AxionConfig;
import dev.axion.config.ConfigLoader;
import dev.axion.config.ConfigSchema.Scope;
import dev.axion.definition.Definition;
import dev.axion.definition.Seating;
import dev.axion.definition.VehicleDashboard;
import dev.axion.forge.AxionEntity;
import dev.axion.forge.RuntimeAccess;
import dev.axion.hud.Dashboard;
import dev.axion.lifecycle.AxionRuntime;
import dev.axion.net.Interpolation;
import dev.axion.net.ServerClock;
import dev.axion.render.ProceduralTracks;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Le tableau de bord à l'écran (ADR-170 D4) : un overlay de Forge au-dessus de la barre d'objets, pour l'occupant d'un
 * siège qui le voit — le conducteur par défaut. Le défaut écrit la vitesse, le rapport et le régime ; chaque jauge
 * déclarée lit sa source à l'image. Les valeurs sont celles que le client dessine : prédites pour le conducteur,
 * interpolées pour un passager. F1 le masque avec le reste de l'interface ; {@code hud.dashboard} aussi.
 */
@Mod.EventBusSubscriber(modid = AxionMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class DashboardOverlay {

    private static final int BACKGROUND = 0x90000000;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int DIM = 0xFFB0B0B0;
    private static final int ACCENT = 0xFFFFB000;
    private static final int TRACK = 0xFF404040;

    /** Les sondes des jauges de chaque definition, compilées une fois. */
    private static final Map<String, ProceduralTracks.Probe[]> probes = new HashMap<>();
    private static AxionRuntime probedFor;

    /** {@code axion-client.toml}, gardé ; relu au plus une fois par seconde s'il a changé ([hud] est rechargeable). */
    private static AxionConfig client;
    private static long checkedAt;
    private static long stamp = Long.MIN_VALUE;

    private DashboardOverlay() {}

    /** L'overlay s'inscrit au-dessus de la barre d'objets. */
    @SubscribeEvent
    public static void register(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "dashboard", DashboardOverlay::render);
    }

    private static void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int width, int height) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        AxionRuntime runtime = RuntimeAccess.get();
        if (player == null || runtime == null || minecraft.getCameraEntity() != player
                || !(player.getVehicle() instanceof AxionEntity assembly)) {
            return;
        }
        AxionConfig config = client();
        Seating seating = assembly.declaredSeating();
        if (!config.getBoolean("hud.dashboard") || seating == null) {
            return;
        }
        VehicleDashboard dashboard = seating.dashboard();
        if (dashboard.isEmpty() || !dashboard.serves(assembly.seatOf(player))) {
            return;
        }
        float scale = (float) config.getFloat("hud.dashboard_scale");
        Dashboard.Layout layout = Dashboard.layout(dashboard, Math.round(width / scale), Math.round(height / scale));
        AxionEntity.RenderPose shown = assembly.renderPose(partialTick);
        Font font = minecraft.font;
        graphics.pose().pushPose();
        graphics.pose().scale(scale, scale, 1f);
        if (layout.standard() != null) {
            standard(graphics, font, layout.standard(), shown.vehicle(), config.getString("hud.speed_unit"));
        }
        if (!dashboard.gauges().isEmpty()) {
            ProceduralTracks.Probe[] reading = probes(runtime, assembly, dashboard);
            ProceduralTracks.Frame frame = frame(assembly, shown, partialTick);
            for (int i = 0; i < dashboard.gauges().size(); i++) {
                double value = reading[i] == null ? Double.NaN : reading[i].read(frame);
                gauge(graphics, font, layout.gauges().get(i), dashboard.gauges().get(i), value);
            }
        }
        graphics.pose().popPose();
    }

    /** Le défaut : la vitesse en grand, le rapport à côté, le régime dessous. */
    private static void standard(GuiGraphics graphics, Font font, Dashboard.Box box, Interpolation.VehiclePose vehicle,
            String unit) {
        graphics.fill(box.x(), box.y(), box.x() + box.width(), box.y() + box.height(), BACKGROUND);
        String speed = Dashboard.speed(vehicle.speed(), unit);
        graphics.pose().pushPose();
        graphics.pose().translate(box.x() + 6, box.y() + 6, 0);
        graphics.pose().scale(2f, 2f, 1f);
        graphics.drawString(font, speed.substring(0, speed.indexOf(' ')), 0, 0, TEXT, true);
        graphics.pose().popPose();
        graphics.drawString(font, speed.substring(speed.indexOf(' ') + 1), box.x() + 6, box.y() + 26, DIM, true);
        String gear = Dashboard.gear(vehicle.gear());
        graphics.pose().pushPose();
        graphics.pose().translate(box.x() + box.width() - 6 - font.width(gear) * 2, box.y() + 6, 0);
        graphics.pose().scale(2f, 2f, 1f);
        graphics.drawString(font, gear, 0, 0, ACCENT, true);
        graphics.pose().popPose();
        String rpm = Dashboard.rpm(vehicle.rpm()) + " " + Component.translatable("axion.hud.rpm").getString();
        graphics.drawString(font, rpm, box.x() + box.width() - 6 - font.width(rpm), box.y() + 26, DIM, true);
    }

    private static void gauge(GuiGraphics graphics, Font font, Dashboard.Box box, VehicleDashboard.Gauge gauge,
            double value) {
        String label = gauge.label() == null ? null : Component.translatable(gauge.label()).getString();
        int bottom = box.y() + box.height();
        switch (gauge.kind()) {
            case DIAL -> {
                int radius = gauge.size() / 2;
                int cx = box.x() + radius;
                int cy = box.y() + radius;
                graphics.fill(box.x(), box.y(), box.x() + gauge.size(), box.y() + gauge.size(), BACKGROUND);
                // Les graduations, puis l'aiguille : des points le long d'un rayon.
                for (int tick = 0; tick <= 8; tick++) {
                    double angle = Math.toRadians(-Dashboard.DIAL_SWEEP + 2 * Dashboard.DIAL_SWEEP * tick / 8.0);
                    dot(graphics, cx + Math.sin(angle) * (radius - 4), cy - Math.cos(angle) * (radius - 4), 1, DIM);
                }
                double needle = Math.toRadians(Dashboard.needle(value, gauge.min(), gauge.max()));
                for (int step = 0; step < radius - 6; step++) {
                    dot(graphics, cx + Math.sin(needle) * step, cy - Math.cos(needle) * step, 1, ACCENT);
                }
                String text = Dashboard.value(value, gauge.unit());
                graphics.drawString(font, text, cx - font.width(text) / 2, cy + radius / 3, TEXT, true);
            }
            case BAR -> {
                int y = box.y() + (label == null ? 0 : Dashboard.LINE);
                graphics.fill(box.x(), y, box.x() + box.width(), y + Dashboard.BAR, TRACK);
                int filled = (int) Math.round(box.width() * Dashboard.fraction(value, gauge.min(), gauge.max()));
                graphics.fill(box.x(), y, box.x() + filled, y + Dashboard.BAR, ACCENT);
                graphics.drawString(font, Dashboard.value(value, gauge.unit()), box.x(), y + Dashboard.BAR + 1, TEXT,
                        true);
            }
            case TEXT -> graphics.drawString(font, Dashboard.value(value, gauge.unit()), box.x(),
                    box.y() + (label == null ? 0 : Dashboard.LINE), TEXT, true);
            case LIGHT -> {
                boolean on = Dashboard.lit(value, gauge.min());
                int side = Math.min(box.height(), 8);
                graphics.fill(box.x(), box.y(), box.x() + side, box.y() + side, on ? ACCENT : TRACK);
                if (label != null) {
                    graphics.drawString(font, label, box.x() + side + 4, box.y(), on ? TEXT : DIM, true);
                }
                return;
            }
        }
        if (label != null) {
            int y = gauge.kind() == VehicleDashboard.Kind.DIAL ? bottom - Dashboard.LINE + 1 : box.y();
            graphics.drawString(font, label, box.x(), y, DIM, true);
        }
    }

    private static AxionConfig client() {
        long now = System.nanoTime();
        if (client != null && now - checkedAt < 1_000_000_000L) {
            return client;
        }
        checkedAt = now;
        java.nio.file.Path file = FMLPaths.CONFIGDIR.get().resolve(Scope.CLIENT.fileName());
        long modified;
        try {
            modified = java.nio.file.Files.getLastModifiedTime(file).toMillis();
        } catch (java.io.IOException absent) {
            modified = Long.MIN_VALUE + 1;
        }
        if (client == null || modified != stamp) {
            client = ConfigLoader.load(Scope.CLIENT, FMLPaths.CONFIGDIR.get(), System.getProperties());
            stamp = modified;
        }
        return client;
    }

    private static void dot(GuiGraphics graphics, double x, double y, int half, int color) {
        int ix = (int) Math.round(x);
        int iy = (int) Math.round(y);
        graphics.fill(ix - half, iy - half, ix + half, iy + half, color);
    }

    /** La trame de l'image, comme le rendu la construit ; les angles des roues sont lus, pas avancés. */
    private static ProceduralTracks.Frame frame(AxionEntity assembly, AxionEntity.RenderPose shown, float partialTick) {
        Level level = assembly.level();
        BlockPos at = BlockPos.containing(shown.x(), shown.y(), shown.z());
        double renderTick = level.getGameTime() + partialTick;
        return new ProceduralTracks.Frame(shown.wheels(), assembly.wheelSpin().angles(), shown.vehicle(),
                shown.variables(), assembly.seatsOccupied(), renderTick * ServerClock.TICK_MILLIS / 1000.0,
                ProceduralTracks.worldTime(level.getDayTime()), level.getBrightness(LightLayer.SKY, at),
                level.getRainLevel(partialTick));
    }

    private static ProceduralTracks.Probe[] probes(AxionRuntime runtime, AxionEntity assembly,
            VehicleDashboard dashboard) {
        if (probedFor != runtime) {
            probes.clear();
            probedFor = runtime;
        }
        Definition definition = runtime.definitions().get(assembly.definitionId()).orElse(null);
        List<VehicleDashboard.Gauge> gauges = dashboard.gauges();
        if (definition == null) {
            return new ProceduralTracks.Probe[gauges.size()];
        }
        // L'empreinte dans la clé : une definition rechargée (CAP-50) refait ses sondes.
        return probes.computeIfAbsent(definition.id() + "#" + definition.hash(), key -> {
            ProceduralTracks.Probe[] made = new ProceduralTracks.Probe[gauges.size()];
            for (int i = 0; i < made.length; i++) {
                made[i] = ProceduralTracks.probe(definition.root(), gauges.get(i).source());
            }
            return made;
        });
    }
}
