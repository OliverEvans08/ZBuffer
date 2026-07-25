package engine.ui;

import engine.EngineContext;
import java.awt.Color;
import java.awt.Graphics;

public final class DebugOverlay {

    private final EngineContext context;

    public DebugOverlay(EngineContext context) {
        this.context = context;
    }

    public void draw(Graphics graphics, int fps) {
        graphics.setColor(Color.WHITE);
        graphics.drawString("FPS: " + fps, 10, 20);
        graphics.drawString(
                "FeetY: " + String.format("%.3f", context.camera.y),
                10,
                40
        );
        graphics.drawString(
                "EyeY: " + String.format("%.3f", context.camera.getViewY()),
                10,
                60
        );
        graphics.drawString(
                "X/Z: " + String.format(
                        "%.3f/%.3f",
                        context.camera.x,
                        context.camera.z
                ),
                10,
                80
        );
        graphics.drawString(
                "FlightMode: " + context.camera.flightMode,
                10,
                100
        );
        graphics.drawString(
                "Yaw: " + String.format(
                        "%.1f°",
                        Math.toDegrees(context.camera.yaw)
                ),
                10,
                120
        );
        graphics.drawString(
                "Pitch: " + String.format(
                        "%.1f°",
                        Math.toDegrees(context.camera.pitch)
                ),
                10,
                140
        );
        graphics.drawString(
                "LoadedObjects: " +
                        context.scene.getRootObjects().size(),
                10,
                160
        );
        graphics.drawString(
                "SolidColliders: " +
                        context.scene.getColliders().size(),
                10,
                180
        );
        graphics.drawString(
                "GUIOpen: " + context.clickGUI.isOpen(),
                10,
                200
        );
        graphics.drawString(
                "InvOpen: " + context.inventoryUI.isOpen(),
                10,
                220
        );
        graphics.drawString(
                "FOV: " + String.format(
                        "%.1f°",
                        context.settings.getFieldOfViewDegrees()
                ),
                10,
                240
        );
        graphics.drawString(
                "RenderDist: " + String.format(
                        "%.0f",
                        context.settings.getRenderDistance()
                ),
                10,
                260
        );
        graphics.drawString(
                "CamMode: " +
                        (context.camera.isFirstPerson()
                                ? "First"
                                : "Third"),
                10,
                280
        );
        graphics.drawString(
                "RenderScale: " + String.format(
                        "%.2f",
                        context.renderer.getRenderScale()
                ),
                10,
                300
        );
        graphics.drawString(
                "FXAA: " + context.renderer.isFxaaEnabled(),
                10,
                320
        );
        graphics.drawString(
                "MasterVol: " + String.format(
                        "%.2f",
                        context.soundEngine.getMasterVolume()
                ),
                10,
                340
        );
    }
}