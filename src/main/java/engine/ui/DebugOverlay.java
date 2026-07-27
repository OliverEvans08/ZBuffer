package engine.ui;

import engine.EngineContext;
import engine.render.RenderStats;
import java.awt.Color;
import java.awt.Graphics;
import java.util.Locale;

public final class DebugOverlay {

    private static final int LEFT = 10;
    private static final int FIRST_LINE_Y = 20;
    private static final int LINE_HEIGHT = 20;

    private final EngineContext context;

    public DebugOverlay(
            EngineContext context
    ) {
        this.context = context;
    }

    public void draw(
            Graphics graphics,
            int fps
    ) {
        final RenderStats stats =
                context.renderer.getLatestStats();

        graphics.setColor(
                Color.WHITE
        );

        int line = 0;

        drawLine(
                graphics,
                line++,
                "FPS: " + fps
        );

        drawLine(
                graphics,
                line++,
                "Visible objects: " +
                        stats.visibleObjects()
        );

        drawLine(
                graphics,
                line++,
                "Chunks nearby/visible: " +
                        stats.nearbyChunks() +
                        "/" +
                        stats.visibleChunks()
        );

        drawLine(
                graphics,
                line++,
                "Camera-pass triangles: " +
                        stats.cameraPassTriangles()
        );

        drawLine(
                graphics,
                line++,
                "Shadow-pass triangles: " +
                        stats.shadowPassTriangles()
        );

        drawLine(
                graphics,
                line++,
                "Tile references: " +
                        stats.tileReferences()
        );

        drawLine(
                graphics,
                line++,
                "Pixels shaded: " +
                        stats.pixelsShaded()
        );

        line++;

        drawLine(
                graphics,
                line++,
                "Frame total: " +
                        milliseconds(
                                stats.totalMilliseconds()
                        )
        );

        drawLine(
                graphics,
                line++,
                "  Setup: " +
                        milliseconds(
                                stats.setupMilliseconds()
                        )
        );

        drawLine(
                graphics,
                line++,
                "  Scene/lights: " +
                        milliseconds(
                                stats.sceneMilliseconds()
                        )
        );

        drawLine(
                graphics,
                line++,
                "  Shadow pass: " +
                        milliseconds(
                                stats.shadowMilliseconds()
                        )
        );

        drawLine(
                graphics,
                line++,
                "  Camera pass: " +
                        milliseconds(
                                stats.cameraPassMilliseconds()
                        )
        );

        drawLine(
                graphics,
                line++,
                "    Camera transform: " +
                        milliseconds(
                                stats.cameraTransformationMilliseconds()
                        )
        );

        drawLine(
                graphics,
                line++,
                "    Clipping: " +
                        milliseconds(
                                stats.clippingMilliseconds()
                        )
        );

        drawLine(
                graphics,
                line++,
                "    Triangle emission: " +
                        milliseconds(
                                stats.triangleEmissionMilliseconds()
                        )
        );

        drawLine(
                graphics,
                line++,
                "  Tile binning: " +
                        milliseconds(
                                stats.tileBinningMilliseconds()
                        )
        );

        drawLine(
                graphics,
                line++,
                "  Raster/shade: " +
                        milliseconds(
                                stats.rasterMilliseconds()
                        )
        );

        drawLine(
                graphics,
                line++,
                "  FXAA: " +
                        milliseconds(
                                stats.fxaaMilliseconds()
                        )
        );

        drawLine(
                graphics,
                line++,
                "  Blit: " +
                        milliseconds(
                                stats.blitMilliseconds()
                        )
        );

        line++;

        drawLine(
                graphics,
                line++,
                "FeetY: " +
                        format(
                                "%.3f",
                                context.camera.y
                        )
        );

        drawLine(
                graphics,
                line++,
                "EyeY: " +
                        format(
                                "%.3f",
                                context.camera.getViewY()
                        )
        );

        drawLine(
                graphics,
                line++,
                "X/Z: " +
                        format(
                                "%.3f/%.3f",
                                context.camera.x,
                                context.camera.z
                        )
        );

        drawLine(
                graphics,
                line++,
                "FlightMode: " +
                        context.camera.flightMode
        );

        drawLine(
                graphics,
                line++,
                "Yaw: " +
                        format(
                                "%.1f°",
                                Math.toDegrees(
                                        context.camera.yaw
                                )
                        )
        );

        drawLine(
                graphics,
                line++,
                "Pitch: " +
                        format(
                                "%.1f°",
                                Math.toDegrees(
                                        context.camera.pitch
                                )
                        )
        );

        drawLine(
                graphics,
                line++,
                "LoadedObjects: " +
                        context.scene
                                .getRootObjects()
                                .size()
        );

        drawLine(
                graphics,
                line++,
                "SolidColliders: " +
                        context.scene
                                .getColliders()
                                .size()
        );

        drawLine(
                graphics,
                line++,
                "GUIOpen: " +
                        context.clickGUI.isOpen()
        );

        drawLine(
                graphics,
                line++,
                "InvOpen: " +
                        context.inventoryUI.isOpen()
        );

        drawLine(
                graphics,
                line++,
                "FOV: " +
                        format(
                                "%.1f°",
                                context.settings
                                        .getFieldOfViewDegrees()
                        )
        );

        drawLine(
                graphics,
                line++,
                "RenderDist: " +
                        format(
                                "%.0f",
                                context.settings
                                        .getRenderDistance()
                        )
        );

        drawLine(
                graphics,
                line++,
                "CamMode: " +
                        (
                                context.camera.isFirstPerson()
                                        ? "First"
                                        : "Third"
                        )
        );

        drawLine(
                graphics,
                line++,
                "RenderScale: " +
                        format(
                                "%.2f",
                                context.renderer.getRenderScale()
                        )
        );

        drawLine(
                graphics,
                line++,
                "FXAA: " +
                        context.renderer.isFxaaEnabled()
        );

        drawLine(
                graphics,
                line,
                "MasterVol: " +
                        format(
                                "%.2f",
                                context.soundEngine.getMasterVolume()
                        )
        );
    }

    private static void drawLine(
            Graphics graphics,
            int line,
            String text
    ) {
        graphics.drawString(
                text,
                LEFT,
                FIRST_LINE_Y +
                        line * LINE_HEIGHT
        );
    }

    private static String milliseconds(
            double value
    ) {
        return format(
                "%.3f ms",
                value
        );
    }

    private static String format(
            String pattern,
            Object... arguments
    ) {
        return String.format(
                Locale.ROOT,
                pattern,
                arguments
        );
    }
}