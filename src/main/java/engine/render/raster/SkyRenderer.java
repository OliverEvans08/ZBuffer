package engine.render.raster;

import engine.render.RenderSettings;
import java.util.Arrays;

public final class SkyRenderer {
    public int[] pixels = new int[0];
    private int width = -1;
    private int height = -1;

    public void ensure(int width, int height) {
        final int pixelCount = width * height;

        if (
                this.width == width &&
                        this.height == height
        ) {
            return;
        }

        pixels = new int[pixelCount];
        this.width = width;
        this.height = height;

        for (int y = 0; y < height; y++) {
            final double interpolation =
                    height <= 1
                            ? 0.0
                            : y / (double) (height - 1);
            final int color = interpolateArgb(
                    RenderSettings.SKY_TOP,
                    RenderSettings.SKY_BOTTOM,
                    interpolation
            );

            Arrays.fill(
                    pixels,
                    y * width,
                    (y + 1) * width,
                    color
            );
        }
    }

    private static int interpolateArgb(
            int first,
            int second,
            double interpolation
    ) {
        interpolation = Math.max(
                0.0,
                Math.min(1.0, interpolation)
        );

        final int firstAlpha =
                (first >>> 24) & 255;
        final int firstRed =
                (first >>> 16) & 255;
        final int firstGreen =
                (first >>> 8) & 255;
        final int firstBlue =
                first & 255;
        final int secondAlpha =
                (second >>> 24) & 255;
        final int secondRed =
                (second >>> 16) & 255;
        final int secondGreen =
                (second >>> 8) & 255;
        final int secondBlue =
                second & 255;

        final int alpha = (int) (
                firstAlpha +
                        (secondAlpha - firstAlpha) *
                                interpolation +
                        0.5
        );
        final int red = (int) (
                firstRed +
                        (secondRed - firstRed) *
                                interpolation +
                        0.5
        );
        final int green = (int) (
                firstGreen +
                        (secondGreen - firstGreen) *
                                interpolation +
                        0.5
        );
        final int blue = (int) (
                firstBlue +
                        (secondBlue - firstBlue) *
                                interpolation +
                        0.5
        );

        return (alpha << 24) |
                (red << 16) |
                (green << 8) |
                blue;
    }
}