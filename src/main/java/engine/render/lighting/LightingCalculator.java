package engine.render.lighting;

import engine.lighting.LightData;
import engine.lighting.LightType;
import engine.render.RenderFrame;
import engine.render.util.RenderWorkerContext;

public final class LightingCalculator {
    private static final int MAX_PIXEL_LIGHTS = 8;

    private static final byte TYPE_DISABLED = 0;
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
     * Hot light data is flattened once per frame.
     *
     * This avoids:
     * - LightData object dereferences for every pixel.
     * - Enum loads and enum comparisons for every light and pixel.
     * - Recalculating range squared.
     * - Recalculating spotlight cone reciprocal.
     * - Repeatedly negating directional vectors.
     */
    private final byte[] preparedTypes =
            new byte[MAX_PIXEL_LIGHTS];

    private final int[] sourceLightIndices =
            new int[MAX_PIXEL_LIGHTS];

    private final double[] preparedX =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] preparedY =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] preparedZ =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] preparedDirectionX =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] preparedDirectionY =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] preparedDirectionZ =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] preparedStrength =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] preparedRange =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] preparedRangeSquared =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] preparedLinear =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] preparedQuadratic =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] preparedInnerCos =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] preparedOuterCos =
            new double[MAX_PIXEL_LIGHTS];

    private final double[] preparedInverseConeWidth =
            new double[MAX_PIXEL_LIGHTS];

    private final float[] preparedRed =
            new float[MAX_PIXEL_LIGHTS];

    private final float[] preparedGreen =
            new float[MAX_PIXEL_LIGHTS];

    private final float[] preparedBlue =
            new float[MAX_PIXEL_LIGHTS];

    private int preparedLightCount;

    private double cameraX;
    private double cameraY;
    private double cameraZ;

    private double halfWidth;
    private double halfHeight;

    /*
     * Precomputed camera-ray transform.
     *
     * A pixel position produces a world-space ray:
     *
     * world = camera + cameraSpaceZ *
     *         (constant + x * xCoefficient + y * yCoefficient)
     *
     * This replaces the full inverse-view matrix expression in every shaded
     * pixel with three short affine expressions.
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

        /*
         * Expanded form of the existing inverse camera rotation.
         *
         * pixelOffsetX and pixelOffsetY are multiplied by cameraSpaceZ later,
         * so all camera-constant coefficients are prepared once here.
         */
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
        preparedLightCount =
                Math.min(
                        frame.lightCount,
                        MAX_PIXEL_LIGHTS
                );

        perPixelLightingRequired =
                false;

        final LightData[] lights =
                frame.lightArray;

        for (
                int index = 0;
                index < preparedLightCount;
                index++
        ) {
            final LightData light =
                    lights[index];

            sourceLightIndices[index] =
                    index;

            if (
                    light == null ||
                            light.strength <= 0.0
            ) {
                preparedTypes[index] =
                        TYPE_DISABLED;

                continue;
            }

            preparedX[index] =
                    light.x;

            preparedY[index] =
                    light.y;

            preparedZ[index] =
                    light.z;

            preparedStrength[index] =
                    light.strength;

            preparedRed[index] =
                    (float) light.r;

            preparedGreen[index] =
                    (float) light.g;

            preparedBlue[index] =
                    (float) light.b;

            if (light.type == LightType.DIRECTIONAL) {
                preparedTypes[index] =
                        TYPE_DIRECTIONAL;

                /*
                 * Direction from the shaded point towards the light.
                 */
                preparedDirectionX[index] =
                        -light.dx;

                preparedDirectionY[index] =
                        -light.dy;

                preparedDirectionZ[index] =
                        -light.dz;

                if (light.shadows) {
                    perPixelLightingRequired =
                            true;
                }

                continue;
            }

            perPixelLightingRequired =
                    true;

            final double range =
                    Math.max(
                            0.0,
                            light.range
                    );

            preparedRange[index] =
                    range;

            preparedRangeSquared[index] =
                    range * range;

            preparedLinear[index] =
                    light.attLinear;

            preparedQuadratic[index] =
                    light.attQuadratic;

            if (light.type == LightType.SPOT) {
                preparedTypes[index] =
                        TYPE_SPOT;

                preparedDirectionX[index] =
                        light.dx;

                preparedDirectionY[index] =
                        light.dy;

                preparedDirectionZ[index] =
                        light.dz;

                preparedInnerCos[index] =
                        light.innerCos;

                preparedOuterCos[index] =
                        light.outerCos;

                final double coneWidth =
                        light.innerCos -
                                light.outerCos;

                preparedInverseConeWidth[index] =
                        coneWidth > MINIMUM_DENOMINATOR
                                ? 1.0 / coneWidth
                                : 0.0;
            } else {
                preparedTypes[index] =
                        TYPE_POINT;
            }
        }

        /*
         * Clear stale type entries if the number of lights shrank. Only type
         * must be cleared because disabled entries never read the other arrays.
         */
        for (
                int index = preparedLightCount;
                index < MAX_PIXEL_LIGHTS;
                index++
        ) {
            preparedTypes[index] =
                    TYPE_DISABLED;
        }
    }

    /**
     * Per-pixel lighting entry.
     *
     * The supplied normal is already transformed into world space once per
     * triangle by TriangleRasterizer.
     */
    public int calculatePackedAtPixel(
            double worldNormalX,
            double worldNormalY,
            double worldNormalZ,
            double pixelX,
            double pixelY,
            double inverseZ,
            boolean doubleSided,
            double ambient,
            double diffuse
    ) {
        if (
                inverseZ <= 0.0 ||
                        !Double.isFinite(inverseZ)
        ) {
            return packGray(
                    ambient
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

        /*
         * The original inverse view transform has been algebraically factored
         * into these affine ray expressions.
         */
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

        return calculatePackedAtWorld(
                worldNormalX,
                worldNormalY,
                worldNormalZ,
                worldX,
                worldY,
                worldZ,
                doubleSided,
                ambient,
                diffuse
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

        final int lightCount =
                preparedLightCount;

        for (
                int preparedIndex = 0;
                preparedIndex < lightCount;
                preparedIndex++
        ) {
            final byte type =
                    preparedTypes[preparedIndex];

            if (type == TYPE_DISABLED) {
                continue;
            }

            double lightX;
            double lightY;
            double lightZ;
            double attenuation;
            double spotFactor =
                    1.0;

            if (type == TYPE_DIRECTIONAL) {
                lightX =
                        preparedDirectionX[preparedIndex];

                lightY =
                        preparedDirectionY[preparedIndex];

                lightZ =
                        preparedDirectionZ[preparedIndex];

                attenuation =
                        preparedStrength[preparedIndex];
            } else {
                final double toLightX =
                        preparedX[preparedIndex] -
                                worldX;

                final double toLightY =
                        preparedY[preparedIndex] -
                                worldY;

                final double toLightZ =
                        preparedZ[preparedIndex] -
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
                        preparedRange[preparedIndex];

                if (
                        range > 0.0 &&
                                distanceSquared >
                                        preparedRangeSquared[preparedIndex]
                ) {
                    continue;
                }

                /*
                 * Math.sqrt is intrinsified by HotSpot on supported targets.
                 * Exactly one square root is retained and its reciprocal is
                 * reused for normalisation, attenuation and range fade.
                 */
                final double inverseDistance =
                        1.0 /
                                Math.sqrt(
                                        distanceSquared
                                );

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
                                    preparedDirectionX[preparedIndex] *
                                            lightX +
                                            preparedDirectionY[preparedIndex] *
                                                    lightY +
                                            preparedDirectionZ[preparedIndex] *
                                                    lightZ
                            );

                    final double outerCos =
                            preparedOuterCos[preparedIndex];

                    if (cosineTheta <= outerCos) {
                        continue;
                    }

                    final double innerCos =
                            preparedInnerCos[preparedIndex];

                    if (cosineTheta < innerCos) {
                        final double inverseConeWidth =
                                preparedInverseConeWidth[preparedIndex];

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

                /*
                 * Original:
                 *
                 * strength /
                 * (1 + linear * distance + quadratic * distance²)
                 *
                 * Multiplying numerator and denominator by inverseDistance²
                 * avoids computing distance separately.
                 */
                final double inverseDistanceSquared =
                        inverseDistance *
                                inverseDistance;

                final double denominator =
                        inverseDistanceSquared +
                                preparedLinear[preparedIndex] *
                                        inverseDistance +
                                preparedQuadratic[preparedIndex];

                if (
                        denominator <=
                                MINIMUM_DENOMINATOR
                ) {
                    continue;
                }

                attenuation =
                        preparedStrength[preparedIndex] *
                                inverseDistanceSquared /
                                denominator;

                if (range > 0.0) {
                    final double normalisedDistance =
                            1.0 /
                                    (
                                            inverseDistance *
                                                    range
                                    );

                    final double remaining =
                            1.0 -
                                    normalisedDistance;

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

            if (doubleSided) {
                normalDotLight =
                        normalDotLight < 0.0
                                ? -normalDotLight
                                : normalDotLight;
            }

            if (normalDotLight <= 0.0) {
                continue;
            }

            final double visibility =
                    shadowCalculator.visibility(
                            sourceLightIndices[preparedIndex],
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
                                    preparedRed[preparedIndex]
                    );

            green +=
                    (float) (
                            contribution *
                                    preparedGreen[preparedIndex]
                    );

            blue +=
                    (float) (
                            contribution *
                                    preparedBlue[preparedIndex]
                    );
        }

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

    private static int packGray(
            double value
    ) {
        final int channel =
                to255(
                        value
                );

        return (
                channel << 16
        ) |
                (
                        channel << 8
                ) |
                channel;
    }

    /*
     * Performs clamp and conversion in one method. This avoids first creating
     * a clamped double and then invoking another helper for every colour
     * channel and every shaded pixel.
     */
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