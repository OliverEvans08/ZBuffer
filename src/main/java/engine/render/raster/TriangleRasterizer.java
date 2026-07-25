package engine.render.raster;

import engine.render.RenderSettings;
import engine.render.geometry.TriangleBuffer;
import engine.render.lighting.MaterialState;

public final class TriangleRasterizer {
    private TriangleRasterizer() {
    }

    public static float rasterizeFilledTriangle(
            TriangleBuffer triangles,
            int[] pixels,
            float[] depthBuffer,
            int triangle,
            int tileMinimumX,
            int tileMaximumXExclusive,
            int tileMinimumY,
            int tileMaximumYExclusive,
            int width,
            float tileDepthFloor
    ) {
        final long x0 = Math.round(
                triangles.x0[triangle] *
                        RenderSettings.SUBPIXEL_SCALE
        );
        final long y0 = Math.round(
                triangles.y0[triangle] *
                        RenderSettings.SUBPIXEL_SCALE
        );
        final float inverseZ0 =
                triangles.inverseZ0[triangle];
        final float uOverZ0 =
                triangles.uOverZ0[triangle];
        final float vOverZ0 =
                triangles.vOverZ0[triangle];

        final long x1 = Math.round(
                triangles.x1[triangle] *
                        RenderSettings.SUBPIXEL_SCALE
        );
        final long y1 = Math.round(
                triangles.y1[triangle] *
                        RenderSettings.SUBPIXEL_SCALE
        );
        final float inverseZ1 =
                triangles.inverseZ1[triangle];
        final float uOverZ1 =
                triangles.uOverZ1[triangle];
        final float vOverZ1 =
                triangles.vOverZ1[triangle];

        final long x2 = Math.round(
                triangles.x2[triangle] *
                        RenderSettings.SUBPIXEL_SCALE
        );
        final long y2 = Math.round(
                triangles.y2[triangle] *
                        RenderSettings.SUBPIXEL_SCALE
        );
        final float inverseZ2 =
                triangles.inverseZ2[triangle];
        final float uOverZ2 =
                triangles.uOverZ2[triangle];
        final float vOverZ2 =
                triangles.vOverZ2[triangle];

        final long edge0A = y1 - y2;
        final long edge0B = x2 - x1;
        final long edge0C = x1 * y2 - y1 * x2;
        final long edge1A = y2 - y0;
        final long edge1B = x0 - x2;
        final long edge1C = x2 * y0 - y2 * x0;
        final long edge2A = y0 - y1;
        final long edge2B = x1 - x0;
        final long edge2C = x0 * y1 - y0 * x1;
        final long signedArea =
                edge0A * x0 + edge0B * y0 + edge0C;

        if (signedArea <= 0L) {
            return tileDepthFloor;
        }

        final int minimumX =
                Math.max(
                        triangles.minimumX[triangle],
                        tileMinimumX
                );
        final int maximumX =
                Math.min(
                        triangles.maximumX[triangle],
                        tileMaximumXExclusive - 1
                );
        final int minimumY =
                Math.max(
                        triangles.minimumY[triangle],
                        tileMinimumY
                );
        final int maximumY =
                Math.min(
                        triangles.maximumY[triangle],
                        tileMaximumYExclusive - 1
                );

        if (
                minimumX > maximumX ||
                        minimumY > maximumY
        ) {
            return tileDepthFloor;
        }

        final long edge0StepX =
                edge0A * RenderSettings.SUBPIXEL_SCALE;
        final long edge0StepY =
                edge0B * RenderSettings.SUBPIXEL_SCALE;
        final long edge1StepX =
                edge1A * RenderSettings.SUBPIXEL_SCALE;
        final long edge1StepY =
                edge1B * RenderSettings.SUBPIXEL_SCALE;
        final long edge2StepX =
                edge2A * RenderSettings.SUBPIXEL_SCALE;
        final long edge2StepY =
                edge2B * RenderSettings.SUBPIXEL_SCALE;

        final long edge0Bias =
                edge0A < 0L ||
                        (edge0A == 0L && edge0B < 0L)
                        ? 0L
                        : -1L;
        final long edge1Bias =
                edge1A < 0L ||
                        (edge1A == 0L && edge1B < 0L)
                        ? 0L
                        : -1L;
        final long edge2Bias =
                edge2A < 0L ||
                        (edge2A == 0L && edge2B < 0L)
                        ? 0L
                        : -1L;

        final long sampleX =
                ((long) minimumX <<
                        RenderSettings.SUBPIXEL_BITS) +
                        RenderSettings.SUBPIXEL_HALF;
        final long sampleY =
                ((long) minimumY <<
                        RenderSettings.SUBPIXEL_BITS) +
                        RenderSettings.SUBPIXEL_HALF;

        final long rawEdge0Row =
                edge0A * sampleX +
                        edge0B * sampleY +
                        edge0C;
        final long rawEdge1Row =
                edge1A * sampleX +
                        edge1B * sampleY +
                        edge1C;
        final long rawEdge2Row =
                edge2A * sampleX +
                        edge2B * sampleY +
                        edge2C;

        long edge0Row = rawEdge0Row + edge0Bias;
        long edge1Row = rawEdge1Row + edge1Bias;
        long edge2Row = rawEdge2Row + edge2Bias;

        final int spanX = maximumX - minimumX;
        final int spanY = maximumY - minimumY;
        final long edge0Right =
                edge0Row + edge0StepX * spanX;
        final long edge1Right =
                edge1Row + edge1StepX * spanX;
        final long edge2Right =
                edge2Row + edge2StepX * spanX;
        final long edge0Bottom =
                edge0Row + edge0StepY * spanY;
        final long edge1Bottom =
                edge1Row + edge1StepY * spanY;
        final long edge2Bottom =
                edge2Row + edge2StepY * spanY;

        final boolean fullyCoversTile =
                minimumX == tileMinimumX &&
                        maximumX ==
                                tileMaximumXExclusive - 1 &&
                        minimumY == tileMinimumY &&
                        maximumY ==
                                tileMaximumYExclusive - 1 &&
                        edge0Row >= 0L &&
                        edge1Row >= 0L &&
                        edge2Row >= 0L &&
                        edge0Right >= 0L &&
                        edge1Right >= 0L &&
                        edge2Right >= 0L &&
                        edge0Bottom >= 0L &&
                        edge1Bottom >= 0L &&
                        edge2Bottom >= 0L &&
                        edge0Right +
                                edge0StepY * spanY >= 0L &&
                        edge1Right +
                                edge1StepY * spanY >= 0L &&
                        edge2Right +
                                edge2StepY * spanY >= 0L;

        final double inverseArea =
                1.0 / signedArea;
        final MaterialState material =
                triangles.materials[triangle];
        final int[] texturePixels =
                material.texturePixels;
        final boolean repeatTexture =
                material.repeatTexture;
        final int tint = material.tint;
        final int shade = triangles.shades[triangle];
        final int emissive = material.emissive;

        double inverseZRow =
                (rawEdge0Row * inverseZ0 +
                        rawEdge1Row * inverseZ1 +
                        rawEdge2Row * inverseZ2) *
                        inverseArea;

        final double inverseZStepX =
                (edge0StepX * inverseZ0 +
                        edge1StepX * inverseZ1 +
                        edge2StepX * inverseZ2) *
                        inverseArea;
        final double inverseZStepY =
                (edge0StepY * inverseZ0 +
                        edge1StepY * inverseZ1 +
                        edge2StepY * inverseZ2) *
                        inverseArea;
        final double tileCornerInverseZ = inverseZRow;
        final float[] frameDepth = depthBuffer;
        final int[] framePixels = pixels;

        if (texturePixels == null) {
            final int solidColor =
                    shadeSolid(tint, shade, emissive);

            for (
                    int y = minimumY;
                    y <= maximumY;
                    y++
            ) {
                long edge0 = edge0Row;
                long edge1 = edge1Row;
                long edge2 = edge2Row;
                double inverseZValue = inverseZRow;
                final int rowIndex = y * width;

                for (
                        int x = minimumX;
                        x <= maximumX;
                        x++
                ) {
                    if ((edge0 | edge1 | edge2) >= 0L) {
                        final int index =
                                rowIndex + x;
                        final float inverseZ =
                                (float) inverseZValue;

                        if (
                                inverseZ >
                                        frameDepth[index]
                        ) {
                            frameDepth[index] = inverseZ;
                            framePixels[index] =
                                    solidColor;
                        }
                    }

                    edge0 += edge0StepX;
                    edge1 += edge1StepX;
                    edge2 += edge2StepX;
                    inverseZValue += inverseZStepX;
                }

                edge0Row += edge0StepY;
                edge1Row += edge1StepY;
                edge2Row += edge2StepY;
                inverseZRow += inverseZStepY;
            }

            return updateTileDepthFloor(
                    tileDepthFloor,
                    fullyCoversTile,
                    tileCornerInverseZ,
                    inverseZStepX,
                    inverseZStepY,
                    spanX,
                    spanY
            );
        }

        double uOverZRow =
                (rawEdge0Row * uOverZ0 +
                        rawEdge1Row * uOverZ1 +
                        rawEdge2Row * uOverZ2) *
                        inverseArea;
        double vOverZRow =
                (rawEdge0Row * vOverZ0 +
                        rawEdge1Row * vOverZ1 +
                        rawEdge2Row * vOverZ2) *
                        inverseArea;

        final double uOverZStepX =
                (edge0StepX * uOverZ0 +
                        edge1StepX * uOverZ1 +
                        edge2StepX * uOverZ2) *
                        inverseArea;
        final double vOverZStepX =
                (edge0StepX * vOverZ0 +
                        edge1StepX * vOverZ1 +
                        edge2StepX * vOverZ2) *
                        inverseArea;
        final double uOverZStepY =
                (edge0StepY * uOverZ0 +
                        edge1StepY * uOverZ1 +
                        edge2StepY * uOverZ2) *
                        inverseArea;
        final double vOverZStepY =
                (edge0StepY * vOverZ0 +
                        edge1StepY * vOverZ1 +
                        edge2StepY * vOverZ2) *
                        inverseArea;

        final int modulation =
                multiplyRgb(tint, shade);
        final int modulationRed =
                (modulation >>> 16) & 255;
        final int modulationGreen =
                (modulation >>> 8) & 255;
        final int modulationBlue =
                modulation & 255;
        final int emissiveRed =
                (emissive >>> 16) & 255;
        final int emissiveGreen =
                (emissive >>> 8) & 255;
        final int emissiveBlue =
                emissive & 255;
        final int textureWidth =
                material.textureWidth;
        final int textureHeight =
                material.textureHeight;
        final int maximumTextureX =
                material.maximumTextureX;
        final int maximumTextureY =
                material.maximumTextureY;
        final int textureWidthMask =
                material.textureWidthMask;
        final int textureHeightMask =
                material.textureHeightMask;

        for (
                int y = minimumY;
                y <= maximumY;
                y++
        ) {
            long edge0 = edge0Row;
            long edge1 = edge1Row;
            long edge2 = edge2Row;
            double inverseZValue = inverseZRow;
            double uOverZValue = uOverZRow;
            double vOverZValue = vOverZRow;
            final int rowIndex = y * width;

            for (
                    int x = minimumX;
                    x <= maximumX;
                    x++
            ) {
                if ((edge0 | edge1 | edge2) >= 0L) {
                    final int index = rowIndex + x;
                    final float inverseZ =
                            (float) inverseZValue;

                    if (inverseZ > frameDepth[index]) {
                        final double reciprocalInverseZ =
                                1.0 / inverseZValue;
                        final int textureX;
                        final int textureY;

                        if (repeatTexture) {
                            final double scaledU =
                                    uOverZValue *
                                            reciprocalInverseZ *
                                            textureWidth;
                            final double scaledV =
                                    vOverZValue *
                                            reciprocalInverseZ *
                                            textureHeight;

                            int repeatedX = (int) scaledU;
                            int repeatedY = (int) scaledV;

                            if (scaledU < repeatedX) {
                                repeatedX--;
                            }

                            if (scaledV < repeatedY) {
                                repeatedY--;
                            }

                            if (textureWidthMask >= 0) {
                                repeatedX &=
                                        textureWidthMask;
                            } else {
                                repeatedX %= textureWidth;

                                if (repeatedX < 0) {
                                    repeatedX +=
                                            textureWidth;
                                }
                            }

                            if (textureHeightMask >= 0) {
                                repeatedY &=
                                        textureHeightMask;
                            } else {
                                repeatedY %=
                                        textureHeight;

                                if (repeatedY < 0) {
                                    repeatedY +=
                                            textureHeight;
                                }
                            }

                            textureX = repeatedX;
                            textureY = repeatedY;
                        } else {
                            final double u =
                                    uOverZValue *
                                            reciprocalInverseZ;
                            final double v =
                                    vOverZValue *
                                            reciprocalInverseZ;

                            textureX = u <= 0.0
                                    ? 0
                                    : u >= 1.0
                                    ? maximumTextureX
                                    : (int) (
                                    u * textureWidth
                            );
                            textureY = v <= 0.0
                                    ? 0
                                    : v >= 1.0
                                    ? maximumTextureY
                                    : (int) (
                                    v * textureHeight
                            );
                        }

                        final int sample =
                                texturePixels[
                                        textureY *
                                                textureWidth +
                                                textureX
                                        ];
                        final int redProduct =
                                ((sample >>> 16) & 255) *
                                        modulationRed;
                        final int greenProduct =
                                ((sample >>> 8) & 255) *
                                        modulationGreen;
                        final int blueProduct =
                                (sample & 255) *
                                        modulationBlue;
                        final int adjustedRed =
                                redProduct + 128;
                        final int adjustedGreen =
                                greenProduct + 128;
                        final int adjustedBlue =
                                blueProduct + 128;

                        int red =
                                (adjustedRed +
                                        (adjustedRed >> 8)) >>
                                        8;
                        int green =
                                (adjustedGreen +
                                        (adjustedGreen >> 8)) >>
                                        8;
                        int blue =
                                (adjustedBlue +
                                        (adjustedBlue >> 8)) >>
                                        8;

                        red += emissiveRed;
                        green += emissiveGreen;
                        blue += emissiveBlue;

                        if (red > 255) {
                            red = 255;
                        }

                        if (green > 255) {
                            green = 255;
                        }

                        if (blue > 255) {
                            blue = 255;
                        }

                        frameDepth[index] = inverseZ;
                        framePixels[index] =
                                0xFF000000 |
                                        (red << 16) |
                                        (green << 8) |
                                        blue;
                    }
                }

                edge0 += edge0StepX;
                edge1 += edge1StepX;
                edge2 += edge2StepX;
                inverseZValue += inverseZStepX;
                uOverZValue += uOverZStepX;
                vOverZValue += vOverZStepX;
            }

            edge0Row += edge0StepY;
            edge1Row += edge1StepY;
            edge2Row += edge2StepY;
            inverseZRow += inverseZStepY;
            uOverZRow += uOverZStepY;
            vOverZRow += vOverZStepY;
        }

        return updateTileDepthFloor(
                tileDepthFloor,
                fullyCoversTile,
                tileCornerInverseZ,
                inverseZStepX,
                inverseZStepY,
                spanX,
                spanY
        );
    }

    private static float updateTileDepthFloor(
            float currentFloor,
            boolean fullyCovered,
            double topLeftDepth,
            double stepX,
            double stepY,
            int spanX,
            int spanY
    ) {
        if (!fullyCovered) {
            return currentFloor;
        }

        final double topRightDepth =
                topLeftDepth + stepX * spanX;
        final double bottomLeftDepth =
                topLeftDepth + stepY * spanY;
        final double bottomRightDepth =
                topRightDepth + stepY * spanY;
        final double minimumDepth = Math.min(
                Math.min(
                        topLeftDepth,
                        topRightDepth
                ),
                Math.min(
                        bottomLeftDepth,
                        bottomRightDepth
                )
        );
        final float conservativeFloor =
                Math.nextDown((float) minimumDepth);

        return conservativeFloor > currentFloor
                ? conservativeFloor
                : currentFloor;
    }

    public static int shadeSolid(
            int tint,
            int shade,
            int emissive
    ) {
        int red = divideBy255(
                ((tint >>> 16) & 255) *
                        ((shade >>> 16) & 255)
        );
        int green = divideBy255(
                ((tint >>> 8) & 255) *
                        ((shade >>> 8) & 255)
        );
        int blue = divideBy255(
                (tint & 255) *
                        (shade & 255)
        );

        red = clamp255(
                red + ((emissive >>> 16) & 255)
        );
        green = clamp255(
                green + ((emissive >>> 8) & 255)
        );
        blue = clamp255(
                blue + (emissive & 255)
        );

        return 0xFF000000 |
                (red << 16) |
                (green << 8) |
                blue;
    }

    private static int multiplyRgb(
            int first,
            int second
    ) {
        final int red = divideBy255(
                ((first >>> 16) & 255) *
                        ((second >>> 16) & 255)
        );
        final int green = divideBy255(
                ((first >>> 8) & 255) *
                        ((second >>> 8) & 255)
        );
        final int blue = divideBy255(
                (first & 255) *
                        (second & 255)
        );

        return (red << 16) |
                (green << 8) |
                blue;
    }

    public static double edgeFunction(
            double ax,
            double ay,
            double bx,
            double by,
            double px,
            double py
    ) {
        return (py - ay) * (bx - ax) -
                (px - ax) * (by - ay);
    }

    private static int divideBy255(int value) {
        final int adjusted = value + 128;
        return (adjusted + (adjusted >> 8)) >> 8;
    }

    private static int clamp255(int value) {
        return Math.max(0, Math.min(255, value));
    }
}