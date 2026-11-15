package com.example.addon;

import dev.axion.api.content.ContentRegistrar;
import dev.axion.api.hud.DashboardCanvas;
import dev.axion.api.hud.DashboardWidget;
import dev.axion.api.vehicle.VehicleView;

/**
 * A dashboard widget: a tachometer, an arc with a needle, the engine speed and the gear. A definition names it in its
 * dashboard ({@code "widgets": ["example_addon:tachometer"]}); AXION calls it every frame on the client, on a canvas
 * that holds no Minecraft client class. The addon guide quotes it (page "Vehicle cameras and dashboard").
 */
public final class ExampleTachometer {

    // --8<-- [start:id]
    /** The widget, in this mod's namespace. */
    public static final String WIDGET = ExampleAddon.MOD_ID + ":tachometer";
    // --8<-- [end:id]

    /** The top of the dial, in rpm. */
    public static final double RED_LINE = 7000;

    private ExampleTachometer() {}

    // --8<-- [start:widget]
    /** The tachometer: draws from what it reads, keeps nothing from one frame to the next. */
    public static final DashboardWidget TACHOMETER = new DashboardWidget() {
        @Override
        public void draw(DashboardCanvas canvas, VehicleView vehicle) {
            int centreX = canvas.width() / 2;
            int centreY = canvas.height() - 12;
            int radius = Math.min(centreX, centreY) - 2;
            canvas.fill(0, 0, canvas.width(), canvas.height(), 0x90000000);
            // The scale: a tick every thousand rpm, from -120 to +120 degrees.
            for (int thousand = 0; thousand <= RED_LINE / 1000; thousand++) {
                double angle = Math.toRadians(-120 + 240 * thousand * 1000 / RED_LINE);
                canvas.line(centreX + Math.sin(angle) * (radius - 3), centreY - Math.cos(angle) * (radius - 3),
                        centreX + Math.sin(angle) * radius, centreY - Math.cos(angle) * radius,
                        thousand >= 6 ? 0xFFFF4040 : 0xFFB0B0B0);
            }
            double share = Math.max(0, Math.min(1, vehicle.rpm() / RED_LINE));
            double needle = Math.toRadians(-120 + 240 * share);
            canvas.line(centreX, centreY, centreX + Math.sin(needle) * (radius - 4),
                    centreY - Math.cos(needle) * (radius - 4), 0xFFFFB000);
            String rpm = Long.toString(Math.round(vehicle.rpm()));
            canvas.text(rpm, centreX - canvas.textWidth(rpm) / 2, canvas.height() - canvas.lineHeight(), 0xFFFFFFFF);
        }

        @Override
        public int width() {
            return 72;
        }

        @Override
        public int height() {
            return 56;
        }

        @Override
        public Anchor anchor() {
            return Anchor.BOTTOM_LEFT;
        }
    };
    // --8<-- [end:widget]

    /**
     * Registers the tachometer, during this mod's registration phase.
     *
     * @param registrar what AXION hands this mod
     */
    public static void register(ContentRegistrar registrar) {
        // --8<-- [start:register]
        registrar.registerDashboardWidget("tachometer", TACHOMETER);
        // --8<-- [end:register]
    }
}
