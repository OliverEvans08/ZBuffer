package engine.render.lighting;

import engine.lighting.LightData;
import engine.lighting.LightType;
import engine.render.RenderFrame;
import engine.render.util.RenderWorkerContext;

public final class LightingCalculator {
    private static final int MAX_PIXEL_LIGHTS = 8;

    private static final byte TYPE_DIRECTIONAL = 1;
    private static final byte TYPE_POINT = 2;
    private static final byte TYPE_SPOT = 3;

    private static final double MINIMUM_DISTANCE_SQUARED =
            1.0e-18;

    private static final double MINIMUM_DENOMINATOR =
            1.0e-12;

    private static final double MINIMUM_ATTENUATION =
            1.0e-4;

    private static final double MINIMUM_CONTRIBUTION =
            1.0e-5;

    private static final double INV_255 =
            1.0 / 255.0;

    private final RenderFrame frame;
    private final ShadowCalculator shadowCalculator;

    /*
     * Unshadowed directional lights are invariant across a flat triangle.
     * They are accumulated once during triangle construction rather than once
     * for every covered pixel.
     */
    private final double[] invariantDirectionX =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] invariantDirectionY =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] invariantDirectionZ =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] invariantStrength =
            new double[MAX_PIXEL_LIGHTS];

    private final float[] invariantRed =
            new float[MAX_PIXEL_LIGHTS];

    private final float[] invariantGreen =
            new float[MAX_PIXEL_LIGHTS];

    private final float[] invariantBlue =
            new float[MAX_PIXEL_LIGHTS];

    private int invariantLightCount;

    /*
     * Only position-dependent lights remain in the per-pixel arrays:
     * point lights, spot lights and directional lights with a real shadow map.
     * Entries are compact, so the hot loop never visits disabled slots.
     */
    private final byte[] dynamicTypes =
            new byte[MAX_PIXEL_LIGHTS];

    private final double[] dynamicX =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] dynamicY =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] dynamicZ =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] dynamicDirectionX =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] dynamicDirectionY =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] dynamicDirectionZ =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] dynamicStrength =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] dynamicRange =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] dynamicInverseRange =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] dynamicRangeSquared =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] dynamicLinear =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] dynamicQuadratic =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] dynamicInnerCos =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] dynamicOuterCos =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] dynamicInverseConeWidth =
            new double[MAX_PIXEL_LIGHTS];

    private final float[] dynamicRed =
            new float[MAX_PIXEL_LIGHTS];

    private final float[] dynamicGreen =
            new float[MAX_PIXEL_LIGHTS];

    private final float[] dynamicBlue =
            new float[MAX_PIXEL_LIGHTS];

    private final ShadowMap[] dynamicShadowMaps =
            new ShadowMap[MAX_PIXEL_LIGHTS];

    private int dynamicLightCount;
    private int allDynamicLightsMask;

    private double cameraX;
    private double cameraY;
    private double cameraZ;

    private double halfWidth;
    private double halfHeight;

    /*
     * A pixel position produces a world-space ray:
     *
     * world = camera + cameraSpaceZ *
     *         (constant + x * xCoefficient + y * yCoefficient)
     */
    private double rayConstantX;
    private double rayConstantY;
    private double rayConstantZ;

    private double rayXCoefficientX;
    private double rayXCoefficientZ;

    private double rayYCoefficientX;
    private double rayYCoefficientY;
    private double rayYCoefficientZ;

    public double cosineYaw;
    public double sineYaw;
    public double cosinePitch;
    public double sinePitch;

    private boolean perPixelLightingRequired;

    public LightingCalculator(
            RenderFrame frame,
            ShadowCalculator shadowCalculator
    ) {
        this.frame =
                frame;

        this.shadowCalculator =
                shadowCalculator;
    }

    public void beginFrame(
            double cameraX,
            double cameraY,
            double cameraZ,
            double projectionScale,
            int width,
            int height
    ) {
        this.cameraX =
                cameraX;

        this.cameraY =
                cameraY;

        this.cameraZ =
                cameraZ;

        halfWidth =
                width * 0.5;

        halfHeight =
                height * 0.5;

        cosineYaw =
                frame.cosineYaw;

        sineYaw =
                frame.sineYaw;

        cosinePitch =
                frame.cosinePitch;

        sinePitch =
                frame.sinePitch;

        final double inverseProjectionScale =
                projectionScale == 0.0
                        ? 0.0
                        : 1.0 / projectionScale;

        rayXCoefficientX =
                cosineYaw *
                        inverseProjectionScale;

        rayXCoefficientZ =
                sineYaw *
                        inverseProjectionScale;

        rayYCoefficientX =
                sinePitch *
                        sineYaw *
                        inverseProjectionScale;

        rayYCoefficientY =
                -cosinePitch *
                        inverseProjectionScale;

        rayYCoefficientZ =
                -sinePitch *
                        cosineYaw *
                        inverseProjectionScale;

        rayConstantX =
                -cosinePitch *
                        sineYaw;

        rayConstantY =
                -sinePitch;

        rayConstantZ =
                cosinePitch *
                        cosineYaw;

        prepareLights();
    }

    public boolean isPerPixelLightingRequired() {
        return perPixelLightingRequired;
    }

    private void prepareLights() {
        invariantLightCount =
                0;

        dynamicLightCount =
                0;

        final LightData[] lights =
                frame.lightArray;

        final int sourceCount =
                Math.min(
                        frame.lightCount,
                        MAX_PIXEL_LIGHTS
                );

        for (
                int sourceIndex = 0;
                sourceIndex < sourceCount;
                sourceIndex++
        ) {
            final LightData light =
                    lights[sourceIndex];

            if (
                    light == null ||
                            light.strength <= 0.0
            ) {
                continue;
            }

            final ShadowMap shadowMap =
                    light.shadows
                            ? shadowCalculator
                            .getShadowMap(
                                    sourceIndex
                            )
                            : null;

            if (
                    light.type ==
                            LightType.DIRECTIONAL &&
                            shadowMap == null
            ) {
                final int index =
                        invariantLightCount++;

                invariantDirectionX[index] =
                        -light.dx;

                invariantDirectionY[index] =
                        -light.dy;

                invariantDirectionZ[index] =
                        -light.dz;

                invariantStrength[index] =
                        light.strength;

                invariantRed[index] =
                        (float) light.r;

                invariantGreen[index] =
                        (float) light.g;

                invariantBlue[index] =
                        (float) light.b;

                continue;
            }

            final int index =
                    dynamicLightCount++;

            dynamicX[index] =
                    light.x;

            dynamicY[index] =
                    light.y;

            dynamicZ[index] =
                    light.z;

            dynamicStrength[index] =
                    light.strength;

            dynamicRed[index] =
                    (float) light.r;

            dynamicGreen[index] =
                    (float) light.g;

            dynamicBlue[index] =
                    (float) light.b;

            dynamicShadowMaps[index] =
                    shadowMap;

            if (
                    light.type ==
                            LightType.DIRECTIONAL
            ) {
                dynamicTypes[index] =
                        TYPE_DIRECTIONAL;

                dynamicDirectionX[index] =
                        -light.dx;

                dynamicDirectionY[index] =
                        -light.dy;

                dynamicDirectionZ[index] =
                        -light.dz;

                continue;
            }

            final double range =
                    Double.isFinite(
                            light.range
                    ) &&
                            light.range >
                                    0.0
                            ? light.range
                            : 0.0;

            dynamicRange[index] =
                    range;

            dynamicInverseRange[index] =
                    range > 0.0
                            ? 1.0 / range
                            : 0.0;

            dynamicRangeSquared[index] =
                    range * range;

            dynamicLinear[index] =
                    light.attLinear;

            dynamicQuadratic[index] =
                    light.attQuadratic;

            if (light.type == LightType.SPOT) {
                dynamicTypes[index] =
                        TYPE_SPOT;

                dynamicDirectionX[index] =
                        light.dx;

                dynamicDirectionY[index] =
                        light.dy;

                dynamicDirectionZ[index] =
                        light.dz;

                dynamicInnerCos[index] =
                        light.innerCos;

                dynamicOuterCos[index] =
                        light.outerCos;

                final double coneWidth =
                        light.innerCos -
                                light.outerCos;

                dynamicInverseConeWidth[index] =
                        coneWidth >
                                MINIMUM_DENOMINATOR
                                ? 1.0 / coneWidth
                                : 0.0;
            } else {
                dynamicTypes[index] =
                        TYPE_POINT;
            }
        }

        allDynamicLightsMask =
                dynamicLightCount == 0
                        ? 0
                        : (1 << dynamicLightCount) - 1;

        perPixelLightingRequired =
                dynamicLightCount != 0;
    }

    /**
     * Returns the dynamic lights whose ranges intersect a triangle's
     * world-space AABB. Directional and unlimited-range lights are always
     * retained. The test is conservative, so it cannot cull a contributing
     * light.
     */
    public int buildDynamicLightMask(
            double minimumX,
            double minimumY,
            double minimumZ,
            double maximumX,
            double maximumY,
            double maximumZ
    ) {
        int mask =
                0;

        for (
                int index = 0;
                index < dynamicLightCount;
                index++
        ) {
            if (
                    dynamicTypes[index] ==
                            TYPE_DIRECTIONAL ||
                            dynamicRange[index] <= 0.0
            ) {
                mask |=
                        1 << index;

                continue;
            }

            final double lightX =
                    dynamicX[index];

            final double lightY =
                    dynamicY[index];

            final double lightZ =
                    dynamicZ[index];

            final double deltaX =
                    lightX < minimumX
                            ? minimumX - lightX
                            : lightX > maximumX
                            ? lightX - maximumX
                            : 0.0;

            final double deltaY =
                    lightY < minimumY
                            ? minimumY - lightY
                            : lightY > maximumY
                            ? lightY - maximumY
                            : 0.0;

            final double deltaZ =
                    lightZ < minimumZ
                            ? minimumZ - lightZ
                            : lightZ > maximumZ
                            ? lightZ - maximumZ
                            : 0.0;

            if (
                    deltaX * deltaX +
                            deltaY * deltaY +
                            deltaZ * deltaZ <=
                            dynamicRangeSquared[index]
            ) {
                mask |=
                        1 << index;
            }
        }

        return mask;
    }

    /**
     * Accumulates the unshadowed directional-light contribution once per
     * triangle. The returned array belongs to the worker context.
     */
    public double[] calculateInvariantLighting(
            RenderWorkerContext context,
            double normalX,
            double normalY,
            double normalZ,
            boolean doubleSided
    ) {
        float red =
                0.0f;

        float green =
                0.0f;

        float blue =
                0.0f;

        for (
                int index = 0;
                index < invariantLightCount;
                index++
        ) {
            double normalDotLight =
                    normalX *
                            invariantDirectionX[index] +
                            normalY *
                                    invariantDirectionY[index] +
                            normalZ *
                                    invariantDirectionZ[index];

            if (
                    doubleSided &&
                            normalDotLight < 0.0
            ) {
                normalDotLight =
                        -normalDotLight;
            }

            if (normalDotLight <= 0.0) {
                continue;
            }

            final double contribution =
                    normalDotLight *
                            invariantStrength[index];

            red +=
                    (float) (
                            contribution *
                                    invariantRed[index]
                    );

            green +=
                    (float) (
                            contribution *
                                    invariantGreen[index]
                    );

            blue +=
                    (float) (
                            contribution *
                                    invariantBlue[index]
                    );
        }

        context.lighting[0] =
                red;

        context.lighting[1] =
                green;

        context.lighting[2] =
                blue;

        return context.lighting;
    }

    public int calculatePackedAtPixel(
            double worldNormalX,
            double worldNormalY,
            double worldNormalZ,
            double pixelX,
            double pixelY,
            double inverseZ,
            boolean doubleSided,
            double ambient,
            double diffuse,
            int lightMask,
            float baseRed,
            float baseGreen,
            float baseBlue
    ) {
        final int effectiveMask =
                lightMask &
                        allDynamicLightsMask;

        if (
                effectiveMask == 0 ||
                        inverseZ <= 0.0 ||
                        !Double.isFinite(inverseZ)
        ) {
            return packLighting(
                    baseRed,
                    baseGreen,
                    baseBlue,
                    ambient,
                    diffuse
            );
        }

        final double cameraSpaceZ =
                1.0 / inverseZ;

        final double pixelOffsetX =
                pixelX -
                        halfWidth;

        final double pixelOffsetY =
                pixelY -
                        halfHeight;

        final double rayX =
                rayConstantX +
                        pixelOffsetX *
                                rayXCoefficientX +
                        pixelOffsetY *
                                rayYCoefficientX;

        final double rayY =
                rayConstantY +
                        pixelOffsetY *
                                rayYCoefficientY;

        final double rayZ =
                rayConstantZ +
                        pixelOffsetX *
                                rayXCoefficientZ +
                        pixelOffsetY *
                                rayYCoefficientZ;

        final double worldX =
                cameraX +
                        rayX *
                                cameraSpaceZ;

        final double worldY =
                cameraY +
                        rayY *
                                cameraSpaceZ;

        final double worldZ =
                cameraZ +
                        rayZ *
                                cameraSpaceZ;

        return calculateDynamicPackedAtWorld(
                worldNormalX,
                worldNormalY,
                worldNormalZ,
                worldX,
                worldY,
                worldZ,
                doubleSided,
                ambient,
                diffuse,
                effectiveMask,
                baseRed,
                baseGreen,
                baseBlue
        );
    }

    /**
     * Compatibility entry used by triangle setup and wireframe rendering.
     */
    public double[] calculateLighting(
            RenderWorkerContext context,
            double normalX,
            double normalY,
            double normalZ,
            double worldX,
            double worldY,
            double worldZ,
            boolean doubleSided
    ) {
        final int packed =
                calculatePackedAtWorld(
                        normalX,
                        normalY,
                        normalZ,
                        worldX,
                        worldY,
                        worldZ,
                        doubleSided,
                        0.0,
                        1.0
                );

        context.lighting[0] =
                (
                        (packed >>> 16) &
                                255
                ) *
                        INV_255;

        context.lighting[1] =
                (
                        (packed >>> 8) &
                                255
                ) *
                        INV_255;

        context.lighting[2] =
                (
                        packed &
                                255
                ) *
                        INV_255;

        return context.lighting;
    }

    public int calculatePackedAtWorld(
            double normalX,
            double normalY,
            double normalZ,
            double worldX,
            double worldY,
            double worldZ,
            boolean doubleSided,
            double ambient,
            double diffuse
    ) {
        float red =
                0.0f;

        float green =
                0.0f;

        float blue =
                0.0f;

        for (
                int index = 0;
                index < invariantLightCount;
                index++
        ) {
            double normalDotLight =
                    normalX *
                            invariantDirectionX[index] +
                            normalY *
                                    invariantDirectionY[index] +
                            normalZ *
                                    invariantDirectionZ[index];

            if (
                    doubleSided &&
                            normalDotLight < 0.0
            ) {
                normalDotLight =
                        -normalDotLight;
            }

            if (normalDotLight <= 0.0) {
                continue;
            }

            final double contribution =
                    normalDotLight *
                            invariantStrength[index];

            red +=
                    (float) (
                            contribution *
                                    invariantRed[index]
                    );

            green +=
                    (float) (
                            contribution *
                                    invariantGreen[index]
                    );

            blue +=
                    (float) (
                            contribution *
                                    invariantBlue[index]
                    );
        }

        if (allDynamicLightsMask == 0) {
            return packLighting(
                    red,
                    green,
                    blue,
                    ambient,
                    diffuse
            );
        }

        return calculateDynamicPackedAtWorld(
                normalX,
                normalY,
                normalZ,
                worldX,
                worldY,
                worldZ,
                doubleSided,
                ambient,
                diffuse,
                allDynamicLightsMask,
                red,
                green,
                blue
        );
    }

    private int calculateDynamicPackedAtWorld(
            double normalX,
            double normalY,
            double normalZ,
            double worldX,
            double worldY,
            double worldZ,
            boolean doubleSided,
            double ambient,
            double diffuse,
            int lightMask,
            float baseRed,
            float baseGreen,
            float baseBlue
    ) {
        float red =
                baseRed;

        float green =
                baseGreen;

        float blue =
                baseBlue;

        int remainingLights =
                lightMask;

        while (remainingLights != 0) {
            final int index =
                    Integer.numberOfTrailingZeros(
                            remainingLights
                    );

            remainingLights &=
                    remainingLights -
                            1;

            final byte type =
                    dynamicTypes[index];

            double lightX;
            double lightY;
            double lightZ;
            double attenuation;
            double spotFactor =
                    1.0;

            if (type == TYPE_DIRECTIONAL) {
                lightX =
                        dynamicDirectionX[index];

                lightY =
                        dynamicDirectionY[index];

                lightZ =
                        dynamicDirectionZ[index];

                attenuation =
                        dynamicStrength[index];
            } else {
                final double toLightX =
                        dynamicX[index] -
                                worldX;

                final double toLightY =
                        dynamicY[index] -
                                worldY;

                final double toLightZ =
                        dynamicZ[index] -
                                worldZ;

                final double distanceSquared =
                        toLightX * toLightX +
                                toLightY * toLightY +
                                toLightZ * toLightZ;

                if (
                        distanceSquared <
                                MINIMUM_DISTANCE_SQUARED
                ) {
                    continue;
                }

                final double range =
                        dynamicRange[index];

                if (
                        range > 0.0 &&
                                distanceSquared >
                                        dynamicRangeSquared[index]
                ) {
                    continue;
                }

                final double inverseDistance =
                        1.0 /
                                Math.sqrt(
                                        distanceSquared
                                );

                final double distance =
                        distanceSquared *
                                inverseDistance;

                lightX =
                        toLightX *
                                inverseDistance;

                lightY =
                        toLightY *
                                inverseDistance;

                lightZ =
                        toLightZ *
                                inverseDistance;

                if (type == TYPE_SPOT) {
                    final double cosineTheta =
                            -(
                                    dynamicDirectionX[index] *
                                            lightX +
                                            dynamicDirectionY[index] *
                                                    lightY +
                                            dynamicDirectionZ[index] *
                                                    lightZ
                            );

                    final double outerCos =
                            dynamicOuterCos[index];

                    if (cosineTheta <= outerCos) {
                        continue;
                    }

                    final double innerCos =
                            dynamicInnerCos[index];

                    if (cosineTheta < innerCos) {
                        final double inverseConeWidth =
                                dynamicInverseConeWidth[index];

                        double transition =
                                inverseConeWidth == 0.0
                                        ? 1.0
                                        : (
                                        cosineTheta -
                                                outerCos
                                ) *
                                        inverseConeWidth;

                        if (transition <= 0.0) {
                            continue;
                        }

                        if (transition > 1.0) {
                            transition =
                                    1.0;
                        }

                        spotFactor =
                                transition *
                                        transition *
                                        (
                                                3.0 -
                                                        2.0 *
                                                                transition
                                        );
                    }
                }

                final double denominator =
                        1.0 +
                                dynamicLinear[index] *
                                        distance +
                                dynamicQuadratic[index] *
                                        distanceSquared;

                if (
                        denominator <=
                                MINIMUM_DENOMINATOR
                ) {
                    continue;
                }

                attenuation =
                        dynamicStrength[index] /
                                denominator;

                if (range > 0.0) {
                    final double remaining =
                            1.0 -
                                    distance *
                                            dynamicInverseRange[index];

                    attenuation *=
                            remaining *
                                    remaining;
                }

                if (
                        attenuation *
                                spotFactor <
                                MINIMUM_ATTENUATION
                ) {
                    continue;
                }
            }

            double normalDotLight =
                    normalX * lightX +
                            normalY * lightY +
                            normalZ * lightZ;

            if (
                    doubleSided &&
                            normalDotLight < 0.0
            ) {
                normalDotLight =
                        -normalDotLight;
            }

            if (normalDotLight <= 0.0) {
                continue;
            }

            final ShadowMap shadowMap =
                    dynamicShadowMaps[index];

            final double visibility =
                    shadowMap == null
                            ? 1.0
                            : shadowMap.visibility(
                            worldX,
                            worldY,
                            worldZ,
                            normalX,
                            normalY,
                            normalZ,
                            normalDotLight
                    );

            if (visibility <= 0.0) {
                continue;
            }

            final double contribution =
                    normalDotLight *
                            attenuation *
                            spotFactor *
                            visibility;

            if (
                    contribution <
                            MINIMUM_CONTRIBUTION
            ) {
                continue;
            }

            red +=
                    (float) (
                            contribution *
                                    dynamicRed[index]
                    );

            green +=
                    (float) (
                            contribution *
                                    dynamicGreen[index]
                    );

            blue +=
                    (float) (
                            contribution *
                                    dynamicBlue[index]
                    );
        }

        return packLighting(
                red,
                green,
                blue,
                ambient,
                diffuse
        );
    }

    private static int packLighting(
            float red,
            float green,
            float blue,
            double ambient,
            double diffuse
    ) {
        final int shadeRed =
                to255(
                        ambient +
                                diffuse *
                                        red
                );

        final int shadeGreen =
                to255(
                        ambient +
                                diffuse *
                                        green
                );

        final int shadeBlue =
                to255(
                        ambient +
                                diffuse *
                                        blue
                );

        return (
                shadeRed << 16
        ) |
                (
                        shadeGreen << 8
                ) |
                shadeBlue;
    }

    private static int to255(
            double value
    ) {
        if (value <= 0.0) {
            return 0;
        }

        if (value >= 1.0) {
            return 255;
        }

        return (int) (
                value *
                        255.0 +
                        0.5
        );
    }
}