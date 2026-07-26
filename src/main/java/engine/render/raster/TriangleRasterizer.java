package engine.render.raster;

import engine.render.RenderSettings;
import engine.render.geometry.TriangleBuffer;
import engine.render.lighting.LightingCalculator;
import engine.render.lighting.MaterialState;

public final class TriangleRasterizer {
    /*
     * Perspective division is one of the most expensive operations in the
     * textured raster path.
     *
     * Calculate exact perspective-correct texture coordinates at each block
     * boundary, then linearly advance between those boundaries. Depth remains
     * exact per pixel.
     *
     * Eight pixels is a strong CPU/perceptual-quality balance. Four gives
     * higher texture precision; sixteen is more aggressive.
     */
    private static final int PERSPECTIVE_BLOCK_SIZE = 8;

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
            float tileDepthFloor,
            LightingCalculator lightingCalculator
    ) {
        final long edge0A =
                triangles.edge0A[triangle];

        final long edge0B =
                triangles.edge0B[triangle];

        final long edge0C =
                triangles.edge0C[triangle];

        final long edge1A =
                triangles.edge1A[triangle];

        final long edge1B =
                triangles.edge1B[triangle];

        final long edge1C =
                triangles.edge1C[triangle];

        final long edge2A =
                triangles.edge2A[triangle];

        final long edge2B =
                triangles.edge2B[triangle];

        final long edge2C =
                triangles.edge2C[triangle];

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
                edge0A *
                        RenderSettings.SUBPIXEL_SCALE;

        final long edge0StepY =
                edge0B *
                        RenderSettings.SUBPIXEL_SCALE;

        final long edge1StepX =
                edge1A *
                        RenderSettings.SUBPIXEL_SCALE;

        final long edge1StepY =
                edge1B *
                        RenderSettings.SUBPIXEL_SCALE;

        final long edge2StepX =
                edge2A *
                        RenderSettings.SUBPIXEL_SCALE;

        final long edge2StepY =
                edge2B *
                        RenderSettings.SUBPIXEL_SCALE;

        final long edge0Bias =
                edge0A < 0L ||
                        (
                                edge0A == 0L &&
                                        edge0B < 0L
                        )
                        ? 0L
                        : -1L;

        final long edge1Bias =
                edge1A < 0L ||
                        (
                                edge1A == 0L &&
                                        edge1B < 0L
                        )
                        ? 0L
                        : -1L;

        final long edge2Bias =
                edge2A < 0L ||
                        (
                                edge2A == 0L &&
                                        edge2B < 0L
                        )
                        ? 0L
                        : -1L;

        final long sampleX =
                (
                        (long) minimumX <<
                                RenderSettings.SUBPIXEL_BITS
                ) +
                        RenderSettings.SUBPIXEL_HALF;

        final long sampleY =
                (
                        (long) minimumY <<
                                RenderSettings.SUBPIXEL_BITS
                ) +
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

        long edge0Row =
                rawEdge0Row +
                        edge0Bias;

        long edge1Row =
                rawEdge1Row +
                        edge1Bias;

        long edge2Row =
                rawEdge2Row +
                        edge2Bias;

        final int spanX =
                maximumX -
                        minimumX;

        final int spanY =
                maximumY -
                        minimumY;

        final long edge0Right =
                edge0Row +
                        edge0StepX * spanX;

        final long edge1Right =
                edge1Row +
                        edge1StepX * spanX;

        final long edge2Right =
                edge2Row +
                        edge2StepX * spanX;

        final long edge0Bottom =
                edge0Row +
                        edge0StepY * spanY;

        final long edge1Bottom =
                edge1Row +
                        edge1StepY * spanY;

        final long edge2Bottom =
                edge2Row +
                        edge2StepY * spanY;

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
                                edge0StepY * spanY >=
                                0L &&
                        edge1Right +
                                edge1StepY * spanY >=
                                0L &&
                        edge2Right +
                                edge2StepY * spanY >=
                                0L;

        final MaterialState material =
                triangles.materials[triangle];

        final int[] texturePixels =
                material.texturePixels;

        final boolean repeatTexture =
                material.repeatTexture;

        final int tint =
                material.tint;

        final int emissive =
                material.emissive;

        final boolean hasEmissive =
                emissive != 0;

        final double normalX =
                triangles.normalX[triangle];

        final double normalY =
                triangles.normalY[triangle];

        final double normalZ =
                triangles.normalZ[triangle];

        final boolean doubleSided =
                triangles.doubleSided[triangle] != 0;

        final boolean perPixelLighting =
                triangles.lightMasks[triangle] !=
                        0;

        final int lightMask =
                triangles.lightMasks[triangle];

        final float baseLightRed =
                triangles.baseLightRed[triangle];

        final float baseLightGreen =
                triangles.baseLightGreen[triangle];

        final float baseLightBlue =
                triangles.baseLightBlue[triangle];

        final int triangleShade =
                triangles.shades[triangle];

        double worldNormalX =
                normalX;

        double worldNormalY =
                normalY;

        double worldNormalZ =
                normalZ;

        if (perPixelLighting) {
            final double cosineYaw =
                    lightingCalculator.cosineYaw;

            final double sineYaw =
                    lightingCalculator.sineYaw;

            final double cosinePitch =
                    lightingCalculator.cosinePitch;

            final double sinePitch =
                    lightingCalculator.sinePitch;

            worldNormalX =
                    cosineYaw * normalX -
                            sinePitch *
                                    sineYaw *
                                    normalY -
                            cosinePitch *
                                    sineYaw *
                                    normalZ;

            worldNormalY =
                    cosinePitch * normalY -
                            sinePitch * normalZ;

            worldNormalZ =
                    sineYaw * normalX +
                            sinePitch *
                                    cosineYaw *
                                    normalY +
                            cosinePitch *
                                    cosineYaw *
                                    normalZ;
        }

        double inverseZRow =
                triangles.inverseZOrigin[triangle] +
                        triangles.inverseZStepX[triangle] *
                                minimumX +
                        triangles.inverseZStepY[triangle] *
                                minimumY;

        final double inverseZStepX =
                triangles.inverseZStepX[triangle];

        final double inverseZStepY =
                triangles.inverseZStepY[triangle];

        final double tileCornerInverseZ =
                inverseZRow;

        final float[] frameDepth =
                depthBuffer;

        final int[] framePixels =
                pixels;

        /*
         * Solid-colour path.
         *
         * No UV calculations or perspective divisions are performed.
         */
        if (texturePixels == null) {
            final int constantColor =
                    perPixelLighting
                            ? 0
                            : shadeSolid(
                            tint,
                            triangleShade,
                            emissive
                    );

            for (
                    int y = minimumY;
                    y <= maximumY;
                    y++
            ) {
                long edge0 =
                        edge0Row;

                long edge1 =
                        edge1Row;

                long edge2 =
                        edge2Row;

                double inverseZValue =
                        inverseZRow;

                final int rowIndex =
                        y * width;

                int x =
                        minimumX;

                while (x <= maximumX) {
                    final int blockEnd =
                            Math.min(
                                    maximumX,
                                    x +
                                            PERSPECTIVE_BLOCK_SIZE -
                                            1
                            );

                    final int blockSpan =
                            blockEnd -
                                    x;

                    final long endEdge0 =
                            edge0 +
                                    edge0StepX *
                                            blockSpan;

                    final long endEdge1 =
                            edge1 +
                                    edge1StepX *
                                            blockSpan;

                    final long endEdge2 =
                            edge2 +
                                    edge2StepX *
                                            blockSpan;

                    final double endInverseZ =
                            inverseZValue +
                                    inverseZStepX *
                                            blockSpan;

                    final boolean outside =
                            (
                                    edge0 < 0L &&
                                            endEdge0 < 0L
                            ) ||
                                    (
                                            edge1 < 0L &&
                                                    endEdge1 < 0L
                                    ) ||
                                    (
                                            edge2 < 0L &&
                                                    endEdge2 < 0L
                                    );

                    final boolean depthRejected =
                            Math.max(
                                    inverseZValue,
                                    endInverseZ
                            ) <=
                                    tileDepthFloor;

                    if (
                            outside ||
                                    depthRejected
                    ) {
                        final int advance =
                                blockSpan +
                                        1;

                        edge0 +=
                                edge0StepX *
                                        advance;

                        edge1 +=
                                edge1StepX *
                                        advance;

                        edge2 +=
                                edge2StepX *
                                        advance;

                        inverseZValue +=
                                inverseZStepX *
                                        advance;

                        x =
                                blockEnd +
                                        1;

                        continue;
                    }

                    final boolean blockInside =
                            fullyCoversTile ||
                                    (
                                            edge0 |
                                                    edge1 |
                                                    edge2 |
                                                    endEdge0 |
                                                    endEdge1 |
                                                    endEdge2
                                    ) >=
                                            0L;

                    for (
                            ;
                            x <= blockEnd;
                            x++
                    ) {
                        if (
                                blockInside ||
                                        (
                                                edge0 |
                                                        edge1 |
                                                        edge2
                                        ) >= 0L
                        ) {
                            final int index =
                                    rowIndex + x;

                            final float inverseZ =
                                    (float) inverseZValue;

                            if (
                                    inverseZ >
                                            frameDepth[index]
                            ) {
                                final int color;

                                if (perPixelLighting) {
                                    final int shade =
                                            lightingCalculator
                                                    .calculatePackedAtPixel(
                                                            worldNormalX,
                                                            worldNormalY,
                                                            worldNormalZ,
                                                            x + 0.5,
                                                            y + 0.5,
                                                            inverseZValue,
                                                            doubleSided,
                                                            material.ambient,
                                                            material.diffuse,
                                                            lightMask,
                                                            baseLightRed,
                                                            baseLightGreen,
                                                            baseLightBlue
                                                    );

                                    color =
                                            shadeSolid(
                                                    tint,
                                                    shade,
                                                    emissive
                                            );
                                } else {
                                    color =
                                            constantColor;
                                }

                                frameDepth[index] =
                                        inverseZ;

                                framePixels[index] =
                                        color;
                            }
                        }

                        edge0 +=
                                edge0StepX;

                        edge1 +=
                                edge1StepX;

                        edge2 +=
                                edge2StepX;

                        inverseZValue +=
                                inverseZStepX;
                    }
                }

                edge0Row +=
                        edge0StepY;

                edge1Row +=
                        edge1StepY;

                edge2Row +=
                        edge2StepY;

                inverseZRow +=
                        inverseZStepY;
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

        /*
         * Textured path.
         */
        double uOverZRow =
                triangles.uOverZOrigin[triangle] +
                        triangles.uOverZStepX[triangle] *
                                minimumX +
                        triangles.uOverZStepY[triangle] *
                                minimumY;

        double vOverZRow =
                triangles.vOverZOrigin[triangle] +
                        triangles.vOverZStepX[triangle] *
                                minimumX +
                        triangles.vOverZStepY[triangle] *
                                minimumY;

        final double uOverZStepX =
                triangles.uOverZStepX[triangle];

        final double vOverZStepX =
                triangles.vOverZStepX[triangle];

        final double uOverZStepY =
                triangles.uOverZStepY[triangle];

        final double vOverZStepY =
                triangles.vOverZStepY[triangle];

        final int emissiveRed =
                emissive >>> 16 &
                        255;

        final int emissiveGreen =
                emissive >>> 8 &
                        255;

        final int emissiveBlue =
                emissive &
                        255;

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

        final int constantModulation =
                perPixelLighting
                        ? 0
                        : multiplyRgb(
                        tint,
                        triangleShade
                );

        final int constantModulationRed =
                constantModulation >>> 16 &
                        255;

        final int constantModulationGreen =
                constantModulation >>> 8 &
                        255;

        final int constantModulationBlue =
                constantModulation &
                        255;

        for (
                int y = minimumY;
                y <= maximumY;
                y++
        ) {
            long edge0 =
                    edge0Row;

            long edge1 =
                    edge1Row;

            long edge2 =
                    edge2Row;

            double inverseZValue =
                    inverseZRow;

            double uOverZValue =
                    uOverZRow;

            double vOverZValue =
                    vOverZRow;

            final int rowIndex =
                    y * width;

            int x =
                    minimumX;

            while (x <= maximumX) {
                final int blockEnd =
                        Math.min(
                                maximumX,
                                x +
                                        PERSPECTIVE_BLOCK_SIZE -
                                        1
                        );

                final int blockSpan =
                        blockEnd - x;

                final long endEdge0 =
                        edge0 +
                                edge0StepX *
                                        blockSpan;

                final long endEdge1 =
                        edge1 +
                                edge1StepX *
                                        blockSpan;

                final long endEdge2 =
                        edge2 +
                                edge2StepX *
                                        blockSpan;

                final double endInverseZ =
                        inverseZValue +
                                inverseZStepX *
                                        blockSpan;

                final boolean outside =
                        (
                                edge0 < 0L &&
                                        endEdge0 < 0L
                        ) ||
                                (
                                        edge1 < 0L &&
                                                endEdge1 < 0L
                                ) ||
                                (
                                        edge2 < 0L &&
                                                endEdge2 < 0L
                                );

                final boolean depthRejected =
                        Math.max(
                                inverseZValue,
                                endInverseZ
                        ) <=
                                tileDepthFloor;

                if (
                        outside ||
                                depthRejected
                ) {
                    final int advance =
                            blockSpan +
                                    1;

                    edge0 +=
                            edge0StepX *
                                    advance;

                    edge1 +=
                            edge1StepX *
                                    advance;

                    edge2 +=
                            edge2StepX *
                                    advance;

                    inverseZValue +=
                            inverseZStepX *
                                    advance;

                    uOverZValue +=
                            uOverZStepX *
                                    advance;

                    vOverZValue +=
                            vOverZStepX *
                                    advance;

                    x =
                            blockEnd +
                                    1;

                    continue;
                }

                final boolean blockInside =
                        fullyCoversTile ||
                                (
                                        edge0 |
                                                edge1 |
                                                edge2 |
                                                endEdge0 |
                                                endEdge1 |
                                                endEdge2
                                ) >=
                                        0L;

                /*
                 * Exact perspective correction at the start of the block.
                 */
                final double reciprocalStart =
                        1.0 /
                                inverseZValue;

                double textureU =
                        uOverZValue *
                                reciprocalStart;

                double textureV =
                        vOverZValue *
                                reciprocalStart;

                final double textureUStep;
                final double textureVStep;

                if (blockSpan == 0) {
                    textureUStep =
                            0.0;

                    textureVStep =
                            0.0;
                } else {
                    /*
                     * Exact perspective correction at the end of the block.
                     */
                    final double reciprocalEnd =
                            1.0 /
                                    endInverseZ;

                    final double endU =
                            (
                                    uOverZValue +
                                            uOverZStepX *
                                                    blockSpan
                            ) *
                                    reciprocalEnd;

                    final double endV =
                            (
                                    vOverZValue +
                                            vOverZStepX *
                                                    blockSpan
                            ) *
                                    reciprocalEnd;

                    final double inverseBlockSpan =
                            1.0 /
                                    blockSpan;

                    textureUStep =
                            (
                                    endU -
                                            textureU
                            ) *
                                    inverseBlockSpan;

                    textureVStep =
                            (
                                    endV -
                                            textureV
                            ) *
                                    inverseBlockSpan;
                }

                for (
                        ;
                        x <= blockEnd;
                        x++
                ) {
                    if (
                            blockInside ||
                                    (
                                            edge0 |
                                                    edge1 |
                                                    edge2
                                    ) >= 0L
                    ) {
                        final int index =
                                rowIndex + x;

                        final float inverseZ =
                                (float) inverseZValue;

                        if (
                                inverseZ >
                                        frameDepth[index]
                        ) {
                            final int modulationRed;
                            final int modulationGreen;
                            final int modulationBlue;

                            if (perPixelLighting) {
                                final int shade =
                                        lightingCalculator
                                                .calculatePackedAtPixel(
                                                        worldNormalX,
                                                        worldNormalY,
                                                        worldNormalZ,
                                                        x + 0.5,
                                                        y + 0.5,
                                                        inverseZValue,
                                                        doubleSided,
                                                        material.ambient,
                                                        material.diffuse,
                                                        lightMask,
                                                        baseLightRed,
                                                        baseLightGreen,
                                                        baseLightBlue
                                                );

                                final int modulation =
                                        multiplyRgb(
                                                tint,
                                                shade
                                        );

                                modulationRed =
                                        modulation >>> 16 &
                                                255;

                                modulationGreen =
                                        modulation >>> 8 &
                                                255;

                                modulationBlue =
                                        modulation &
                                                255;
                            } else {
                                modulationRed =
                                        constantModulationRed;

                                modulationGreen =
                                        constantModulationGreen;

                                modulationBlue =
                                        constantModulationBlue;
                            }

                            final int textureX;
                            final int textureY;

                            if (repeatTexture) {
                                int repeatedX =
                                        fastFloor(
                                                textureU *
                                                        textureWidth
                                        );

                                int repeatedY =
                                        fastFloor(
                                                textureV *
                                                        textureHeight
                                        );

                                if (textureWidthMask >= 0) {
                                    repeatedX &=
                                            textureWidthMask;
                                } else {
                                    repeatedX %=
                                            textureWidth;

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

                                textureX =
                                        repeatedX;

                                textureY =
                                        repeatedY;
                            } else {
                                textureX =
                                        textureU <= 0.0
                                                ? 0
                                                : textureU >= 1.0
                                                ? maximumTextureX
                                                : (int) (
                                                textureU *
                                                        textureWidth
                                        );

                                textureY =
                                        textureV <= 0.0
                                                ? 0
                                                : textureV >= 1.0
                                                ? maximumTextureY
                                                : (int) (
                                                textureV *
                                                        textureHeight
                                        );
                            }

                            final int sample =
                                    texturePixels[
                                            textureY *
                                                    textureWidth +
                                                    textureX
                                            ];

                            int red =
                                    divideBy255(
                                            (
                                                    sample >>> 16 &
                                                            255
                                            ) *
                                                    modulationRed
                                    );

                            int green =
                                    divideBy255(
                                            (
                                                    sample >>> 8 &
                                                            255
                                            ) *
                                                    modulationGreen
                                    );

                            int blue =
                                    divideBy255(
                                            (
                                                    sample &
                                                            255
                                            ) *
                                                    modulationBlue
                                    );

                            if (hasEmissive) {
                                red +=
                                        emissiveRed;

                                green +=
                                        emissiveGreen;

                                blue +=
                                        emissiveBlue;

                                if (red > 255) {
                                    red =
                                            255;
                                }

                                if (green > 255) {
                                    green =
                                            255;
                                }

                                if (blue > 255) {
                                    blue =
                                            255;
                                }
                            }

                            frameDepth[index] =
                                    inverseZ;

                            framePixels[index] =
                                    0xFF000000 |
                                            red << 16 |
                                            green << 8 |
                                            blue;
                        }
                    }

                    edge0 +=
                            edge0StepX;

                    edge1 +=
                            edge1StepX;

                    edge2 +=
                            edge2StepX;

                    inverseZValue +=
                            inverseZStepX;

                    uOverZValue +=
                            uOverZStepX;

                    vOverZValue +=
                            vOverZStepX;

                    textureU +=
                            textureUStep;

                    textureV +=
                            textureVStep;
                }
            }

            edge0Row +=
                    edge0StepY;

            edge1Row +=
                    edge1StepY;

            edge2Row +=
                    edge2StepY;

            inverseZRow +=
                    inverseZStepY;

            uOverZRow +=
                    uOverZStepY;

            vOverZRow +=
                    vOverZStepY;
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

    private static int fastFloor(
            double value
    ) {
        final int integer =
                (int) value;

        return value < integer
                ? integer - 1
                : integer;
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
                topLeftDepth +
                        stepX * spanX;

        final double bottomLeftDepth =
                topLeftDepth +
                        stepY * spanY;

        final double bottomRightDepth =
                topRightDepth +
                        stepY * spanY;

        final double minimumDepth =
                Math.min(
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
                Math.nextDown(
                        (float) minimumDepth
                );

        return conservativeFloor > currentFloor
                ? conservativeFloor
                : currentFloor;
    }

    public static int shadeSolid(
            int tint,
            int shade,
            int emissive
    ) {
        int red =
                divideBy255(
                        (
                                tint >>> 16 &
                                        255
                        ) *
                                (
                                        shade >>> 16 &
                                                255
                                )
                );

        int green =
                divideBy255(
                        (
                                tint >>> 8 &
                                        255
                        ) *
                                (
                                        shade >>> 8 &
                                                255
                                )
                );

        int blue =
                divideBy255(
                        (
                                tint &
                                        255
                        ) *
                                (
                                        shade &
                                                255
                                )
                );

        red =
                clamp255(
                        red +
                                (
                                        emissive >>> 16 &
                                                255
                                )
                );

        green =
                clamp255(
                        green +
                                (
                                        emissive >>> 8 &
                                                255
                                )
                );

        blue =
                clamp255(
                        blue +
                                (
                                        emissive &
                                                255
                                )
                );

        return 0xFF000000 |
                red << 16 |
                green << 8 |
                blue;
    }

    private static int multiplyRgb(
            int first,
            int second
    ) {
        final int red =
                divideBy255(
                        (
                                first >>> 16 &
                                        255
                        ) *
                                (
                                        second >>> 16 &
                                                255
                                )
                );

        final int green =
                divideBy255(
                        (
                                first >>> 8 &
                                        255
                        ) *
                                (
                                        second >>> 8 &
                                                255
                                )
                );

        final int blue =
                divideBy255(
                        (
                                first &
                                        255
                        ) *
                                (
                                        second &
                                                255
                                )
                );

        return red << 16 |
                green << 8 |
                blue;
    }

    public static double edgeFunction(
            double axisX,
            double axisY,
            double pointBX,
            double pointBY,
            double pointX,
            double pointY
    ) {
        return (
                pointY -
                        axisY
        ) *
                (
                        pointBX -
                                axisX
                ) -
                (
                        pointX -
                                axisX
                ) *
                        (
                                pointBY -
                                        axisY
                        );
    }

    private static int divideBy255(
            int value
    ) {
        final int adjusted =
                value + 128;

        return (
                adjusted +
                        (
                                adjusted >>
                                        8
                        )
        ) >>
                8;
    }

    private static int clamp255(
            int value
    ) {
        return value < 0
                ? 0
                : value > 255
                ? 255
                : value;
    }
}