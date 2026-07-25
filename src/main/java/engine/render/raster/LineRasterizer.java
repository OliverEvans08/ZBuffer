package engine.render.raster;

import engine.render.geometry.TriangleBuffer;
import engine.render.lighting.MaterialState;

public final class LineRasterizer {
    private LineRasterizer() {
    }

    public static void rasterizeWireframeTriangle(
            TriangleBuffer triangles,
            int[] pixels,
            float[] depthBuffer,
            int triangle,
            int tileMinimumX,
            int tileMaximumXExclusive,
            int tileMinimumY,
            int tileMaximumYExclusive,
            int width
    ) {
        final double x0 = triangles.x0[triangle];
        final double y0 = triangles.y0[triangle];
        final float inverseZ0 =
                triangles.inverseZ0[triangle];
        final double x1 = triangles.x1[triangle];
        final double y1 = triangles.y1[triangle];
        final float inverseZ1 =
                triangles.inverseZ1[triangle];
        final double x2 = triangles.x2[triangle];
        final double y2 = triangles.y2[triangle];
        final float inverseZ2 =
                triangles.inverseZ2[triangle];

        final double signedArea =
                TriangleRasterizer.edgeFunction(
                        x0,
                        y0,
                        x1,
                        y1,
                        x2,
                        y2
                );

        if (signedArea <= 0.0) {
            return;
        }

        final MaterialState material =
                triangles.materials[triangle];
        final int color =
                TriangleRasterizer.shadeSolid(
                        material.tint,
                        triangles.shades[triangle],
                        material.emissive
                );

        drawDepthTestedLine(
                pixels,
                depthBuffer,
                x0,
                y0,
                inverseZ0,
                x1,
                y1,
                inverseZ1,
                color,
                tileMinimumX,
                tileMaximumXExclusive,
                tileMinimumY,
                tileMaximumYExclusive,
                width
        );
        drawDepthTestedLine(
                pixels,
                depthBuffer,
                x1,
                y1,
                inverseZ1,
                x2,
                y2,
                inverseZ2,
                color,
                tileMinimumX,
                tileMaximumXExclusive,
                tileMinimumY,
                tileMaximumYExclusive,
                width
        );
        drawDepthTestedLine(
                pixels,
                depthBuffer,
                x2,
                y2,
                inverseZ2,
                x0,
                y0,
                inverseZ0,
                color,
                tileMinimumX,
                tileMaximumXExclusive,
                tileMinimumY,
                tileMaximumYExclusive,
                width
        );
    }

    private static void drawDepthTestedLine(
            int[] pixels,
            float[] depthBuffer,
            double x0,
            double y0,
            float inverseZ0,
            double x1,
            double y1,
            float inverseZ1,
            int color,
            int tileMinimumX,
            int tileMaximumXExclusive,
            int tileMinimumY,
            int tileMaximumYExclusive,
            int width
    ) {
        final double deltaX = x1 - x0;
        final double deltaY = y1 - y0;

        int steps = (int) Math.ceil(
                Math.max(
                        Math.abs(deltaX),
                        Math.abs(deltaY)
                )
        );

        if (steps <= 0) {
            steps = 1;
        }

        final double inverseSteps = 1.0 / steps;
        final double stepX = deltaX * inverseSteps;
        final double stepY = deltaY * inverseSteps;
        final float stepZ =
                (inverseZ1 - inverseZ0) *
                        (float) inverseSteps;

        double x = x0;
        double y = y0;
        float inverseZ = inverseZ0;
        final float[] frameDepth = depthBuffer;
        final int[] framePixels = pixels;

        for (int i = 0; i <= steps; i++) {
            final double roundedX = x + 0.5;
            final double roundedY = y + 0.5;
            int pixelX = (int) roundedX;
            int pixelY = (int) roundedY;

            if (roundedX < pixelX) {
                pixelX--;
            }

            if (roundedY < pixelY) {
                pixelY--;
            }

            if (
                    pixelX >= tileMinimumX &&
                            pixelX <
                                    tileMaximumXExclusive &&
                            pixelY >= tileMinimumY &&
                            pixelY <
                                    tileMaximumYExclusive
            ) {
                final int index =
                        pixelY * width + pixelX;

                if (inverseZ > frameDepth[index]) {
                    frameDepth[index] = inverseZ;
                    framePixels[index] = color;
                }
            }

            x += stepX;
            y += stepY;
            inverseZ += stepZ;
        }
    }
}