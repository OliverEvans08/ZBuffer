package engine.render.lighting;

import engine.lighting.LightData;
import engine.lighting.LightType;
import engine.render.RenderSettings;
import java.util.Arrays;

/**
 * CPU depth shadow map.
 *
 * Aggressive change: the default radius-0 bilinear PCF paths are fully
 * unrolled. This removes all loop overhead and gives the JIT a much cleaner
 * straight-line region to optimise.
 */
public final class ShadowMap {
    private static final int DIRECTIONAL = 0;
    private static final int SPOT = 1;
    private static final int POINT = 2;

    private static final double[][] POINT_FORWARD = {
            {1.0, 0.0, 0.0},
            {-1.0, 0.0, 0.0},
            {0.0, 1.0, 0.0},
            {0.0, -1.0, 0.0},
            {0.0, 0.0, 1.0},
            {0.0, 0.0, -1.0}
    };

    private static final double[][] POINT_RIGHT = {
            {0.0, 0.0, -1.0},
            {0.0, 0.0, 1.0},
            {1.0, 0.0, 0.0},
            {1.0, 0.0, 0.0},
            {1.0, 0.0, 0.0},
            {-1.0, 0.0, 0.0}
    };

    private static final double[][] POINT_UP = {
            {0.0, 1.0, 0.0},
            {0.0, 1.0, 0.0},
            {0.0, 0.0, -1.0},
            {0.0, 0.0, 1.0},
            {0.0, 1.0, 0.0},
            {0.0, 1.0, 0.0}
    };

    private int kind;
    private int size;
    private int faceCount;
    private float[][] depths = new float[0][];

    private double originX;
    private double originY;
    private double originZ;

    private double centerX;
    private double centerY;
    private double centerZ;

    private double forwardX;
    private double forwardY;
    private double forwardZ;

    private double rightX;
    private double rightY;
    private double rightZ;

    private double upX;
    private double upY;
    private double upZ;

    private double halfSpan;
    private double tangentHalfAngle;
    private double maximumDistance;

    private final double[] clipX0 = new double[8];
    private final double[] clipY0 = new double[8];
    private final double[] clipZ0 = new double[8];

    private final double[] clipX1 = new double[8];
    private final double[] clipY1 = new double[8];
    private final double[] clipZ1 = new double[8];

    public void configure(
            LightData light,
            double cameraX,
            double cameraY,
            double cameraZ,
            double farDistance
    ) {
        if (light.type == LightType.DIRECTIONAL) {
            kind = DIRECTIONAL;
            size = RenderSettings.SHADOW_MAP_SIZE;
            faceCount = 1;

            // Cover the full visible range (render distance). No artificial
            // distance cap – shadows are limited only by what is in view.
            halfSpan = Math.max(1.0, farDistance);
            maximumDistance = farDistance;

            setForward(
                    light.dx,
                    light.dy,
                    light.dz
            );

            buildBasis();

            snapDirectionalCenter(
                    cameraX,
                    cameraY,
                    cameraZ
            );

            ensureStorage();
            clearDirectional();
            return;
        }

        originX = light.x;
        originY = light.y;
        originZ = light.z;

        maximumDistance =
                light.range > 0.0
                        ? light.range
                        : farDistance;

        if (light.type == LightType.SPOT) {
            kind = SPOT;
            size = RenderSettings.SHADOW_MAP_SIZE;
            faceCount = 1;

            setForward(
                    light.dx,
                    light.dy,
                    light.dz
            );

            buildBasis();

            final double halfAngle =
                    Math.acos(
                            clamp(
                                    light.outerCos,
                                    -0.9998,
                                    0.9998
                            )
                    );

            tangentHalfAngle =
                    Math.tan(
                            Math.min(
                                    Math.toRadians(89.0),
                                    Math.max(
                                            Math.toRadians(0.5),
                                            halfAngle
                                    )
                            )
                    );
        } else {
            kind = POINT;
            size = RenderSettings.POINT_SHADOW_MAP_SIZE;
            faceCount = 6;
            tangentHalfAngle = 1.0;
        }

        ensureStorage();
        clearPerspective();
    }

    public void addTriangle(
            double x0,
            double y0,
            double z0,
            double x1,
            double y1,
            double z1,
            double x2,
            double y2,
            double z2
    ) {
        if (kind == DIRECTIONAL) {
            addDirectionalTriangle(
                    x0, y0, z0,
                    x1, y1, z1,
                    x2, y2, z2
            );
            return;
        }

        if (kind == SPOT) {
            addPerspectiveTriangle(
                    0,
                    forwardX, forwardY, forwardZ,
                    rightX, rightY, rightZ,
                    upX, upY, upZ,
                    tangentHalfAngle,
                    x0, y0, z0,
                    x1, y1, z1,
                    x2, y2, z2
            );
            return;
        }

        for (int face = 0; face < 6; face++) {
            final double[] forward = POINT_FORWARD[face];
            final double[] right = POINT_RIGHT[face];
            final double[] up = POINT_UP[face];

            addPerspectiveTriangle(
                    face,
                    forward[0], forward[1], forward[2],
                    right[0], right[1], right[2],
                    up[0], up[1], up[2],
                    1.0,
                    x0, y0, z0,
                    x1, y1, z1,
                    x2, y2, z2
            );
        }
    }

    public double visibility(
            double worldX,
            double worldY,
            double worldZ,
            double normalX,
            double normalY,
            double normalZ,
            double normalDotLight
    ) {
        final double cosine =
                normalDotLight < 0.0 ? 0.0 : (normalDotLight > 1.0 ? 1.0 : normalDotLight);
        final double bias =
                RenderSettings.SHADOW_DEPTH_BIAS +
                        RenderSettings.SHADOW_NORMAL_BIAS * (1.0 - cosine);

        if (kind == DIRECTIONAL) {
            return sampleDirectional(
                    worldX, worldY, worldZ,
                    normalX, normalY, normalZ,
                    bias
            );
        }

        if (kind == SPOT) {
            return samplePerspective(
                    0,
                    forwardX, forwardY, forwardZ,
                    rightX, rightY, rightZ,
                    upX, upY, upZ,
                    tangentHalfAngle,
                    worldX, worldY, worldZ,
                    normalX, normalY, normalZ,
                    bias
            );
        }

        final double deltaX = worldX - originX;
        final double deltaY = worldY - originY;
        final double deltaZ = worldZ - originZ;

        final double absoluteX = Math.abs(deltaX);
        final double absoluteY = Math.abs(deltaY);
        final double absoluteZ = Math.abs(deltaZ);

        final int face;
        if (absoluteX >= absoluteY && absoluteX >= absoluteZ) {
            face = deltaX >= 0.0 ? 0 : 1;
        } else if (absoluteY >= absoluteZ) {
            face = deltaY >= 0.0 ? 2 : 3;
        } else {
            face = deltaZ >= 0.0 ? 4 : 5;
        }

        final double[] forward = POINT_FORWARD[face];
        final double[] right = POINT_RIGHT[face];
        final double[] up = POINT_UP[face];

        return samplePerspective(
                face,
                forward[0], forward[1], forward[2],
                right[0], right[1], right[2],
                up[0], up[1], up[2],
                1.0,
                worldX, worldY, worldZ,
                normalX, normalY, normalZ,
                bias
        );
    }

    private void addDirectionalTriangle(
            double x0, double y0, double z0,
            double x1, double y1, double z1,
            double x2, double y2, double z2
    ) {
        final double relativeX0 = x0 - centerX;
        final double relativeY0 = y0 - centerY;
        final double relativeZ0 = z0 - centerZ;

        final double relativeX1 = x1 - centerX;
        final double relativeY1 = y1 - centerY;
        final double relativeZ1 = z1 - centerZ;

        final double relativeX2 = x2 - centerX;
        final double relativeY2 = y2 - centerY;
        final double relativeZ2 = z2 - centerZ;

        final double projectedX0 =
                relativeX0 * rightX + relativeY0 * rightY + relativeZ0 * rightZ;
        final double projectedY0 =
                relativeX0 * upX + relativeY0 * upY + relativeZ0 * upZ;
        final double projectedZ0 =
                relativeX0 * forwardX + relativeY0 * forwardY + relativeZ0 * forwardZ;

        final double projectedX1 =
                relativeX1 * rightX + relativeY1 * rightY + relativeZ1 * rightZ;
        final double projectedY1 =
                relativeX1 * upX + relativeY1 * upY + relativeZ1 * upZ;
        final double projectedZ1 =
                relativeX1 * forwardX + relativeY1 * forwardY + relativeZ1 * forwardZ;

        final double projectedX2 =
                relativeX2 * rightX + relativeY2 * rightY + relativeZ2 * rightZ;
        final double projectedY2 =
                relativeX2 * upX + relativeY2 * upY + relativeZ2 * upZ;
        final double projectedZ2 =
                relativeX2 * forwardX + relativeY2 * forwardY + relativeZ2 * forwardZ;

        if (projectedZ0 < -maximumDistance &&
                projectedZ1 < -maximumDistance &&
                projectedZ2 < -maximumDistance) {
            return;
        }

        if (projectedZ0 > maximumDistance &&
                projectedZ1 > maximumDistance &&
                projectedZ2 > maximumDistance) {
            return;
        }

        final double scale = size / (2.0 * halfSpan);

        final double screenX0 = (projectedX0 + halfSpan) * scale;
        final double screenY0 = (halfSpan - projectedY0) * scale;
        final double screenX1 = (projectedX1 + halfSpan) * scale;
        final double screenY1 = (halfSpan - projectedY1) * scale;
        final double screenX2 = (projectedX2 + halfSpan) * scale;
        final double screenY2 = (halfSpan - projectedY2) * scale;

        rasterizeDirectionalTriangle(
                screenX0, screenY0, projectedZ0,
                screenX1, screenY1, projectedZ1,
                screenX2, screenY2, projectedZ2
        );
    }

    private void rasterizeDirectionalTriangle(
            double x0, double y0, double z0,
            double x1, double y1, double z1,
            double x2, double y2, double z2
    ) {
        final double area = edge(x0, y0, x1, y1, x2, y2);

        if (!Double.isFinite(area) || Math.abs(area) <= 1.0e-12) {
            return;
        }

        final int minimumX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        final int maximumX = Math.min(size - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        final int minimumY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        final int maximumY = Math.min(size - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));

        if (minimumX > maximumX || minimumY > maximumY) {
            return;
        }

        final double inverseArea = 1.0 / area;
        final float[] depth = depths[0];

        for (int y = minimumY; y <= maximumY; y++) {
            final double sampleY = y + 0.5;
            final int row = y * size;

            for (int x = minimumX; x <= maximumX; x++) {
                final double sampleX = x + 0.5;

                final double weight0 = edge(x1, y1, x2, y2, sampleX, sampleY) * inverseArea;
                final double weight1 = edge(x2, y2, x0, y0, sampleX, sampleY) * inverseArea;
                final double weight2 = 1.0 - weight0 - weight1;

                if (weight0 < 0.0 || weight1 < 0.0 || weight2 < 0.0) {
                    continue;
                }

                final float interpolatedDepth =
                        (float) (weight0 * z0 + weight1 * z1 + weight2 * z2);

                final int index = row + x;
                if (interpolatedDepth < depth[index]) {
                    depth[index] = interpolatedDepth;
                }
            }
        }
    }

    private void addPerspectiveTriangle(
            int face,
            double localForwardX, double localForwardY, double localForwardZ,
            double localRightX, double localRightY, double localRightZ,
            double localUpX, double localUpY, double localUpZ,
            double tangent,
            double x0, double y0, double z0,
            double x1, double y1, double z1,
            double x2, double y2, double z2
    ) {
        final double relativeX0 = x0 - originX;
        final double relativeY0 = y0 - originY;
        final double relativeZ0 = z0 - originZ;

        final double relativeX1 = x1 - originX;
        final double relativeY1 = y1 - originY;
        final double relativeZ1 = z1 - originZ;

        final double relativeX2 = x2 - originX;
        final double relativeY2 = y2 - originY;
        final double relativeZ2 = z2 - originZ;

        final double viewX0 =
                relativeX0 * localRightX + relativeY0 * localRightY + relativeZ0 * localRightZ;
        final double viewY0 =
                relativeX0 * localUpX + relativeY0 * localUpY + relativeZ0 * localUpZ;
        final double viewZ0 =
                relativeX0 * localForwardX + relativeY0 * localForwardY + relativeZ0 * localForwardZ;

        final double viewX1 =
                relativeX1 * localRightX + relativeY1 * localRightY + relativeZ1 * localRightZ;
        final double viewY1 =
                relativeX1 * localUpX + relativeY1 * localUpY + relativeZ1 * localUpZ;
        final double viewZ1 =
                relativeX1 * localForwardX + relativeY1 * localForwardY + relativeZ1 * localForwardZ;

        final double viewX2 =
                relativeX2 * localRightX + relativeY2 * localRightY + relativeZ2 * localRightZ;
        final double viewY2 =
                relativeX2 * localUpX + relativeY2 * localUpY + relativeZ2 * localUpZ;
        final double viewZ2 =
                relativeX2 * localForwardX + relativeY2 * localForwardY + relativeZ2 * localForwardZ;

        clipX0[0] = viewX0; clipY0[0] = viewY0; clipZ0[0] = viewZ0;
        clipX0[1] = viewX1; clipY0[1] = viewY1; clipZ0[1] = viewZ1;
        clipX0[2] = viewX2; clipY0[2] = viewY2; clipZ0[2] = viewZ2;

        int count = clipNearPlane(clipX0, clipY0, clipZ0, 3, clipX1, clipY1, clipZ1);

        if (count < 3) {
            return;
        }

        final double firstX = clipX1[0];
        final double firstY = clipY1[0];
        final double firstZ = clipZ1[0];

        for (int index = 1; index < count - 1; index++) {
            rasterizePerspectiveTriangle(
                    face,
                    tangent,
                    firstX, firstY, firstZ,
                    clipX1[index], clipY1[index], clipZ1[index],
                    clipX1[index + 1], clipY1[index + 1], clipZ1[index + 1]
            );
        }
    }

    private int clipNearPlane(
            double[] inputX, double[] inputY, double[] inputZ,
            int inputCount,
            double[] outputX, double[] outputY, double[] outputZ
    ) {
        int outputCount = 0;

        double previousX = inputX[inputCount - 1];
        double previousY = inputY[inputCount - 1];
        double previousZ = inputZ[inputCount - 1];
        boolean previousInside = previousZ >= RenderSettings.SHADOW_NEAR;

        for (int index = 0; index < inputCount; index++) {
            final double currentX = inputX[index];
            final double currentY = inputY[index];
            final double currentZ = inputZ[index];
            final boolean currentInside = currentZ >= RenderSettings.SHADOW_NEAR;

            if (currentInside != previousInside) {
                final double blend =
                        (RenderSettings.SHADOW_NEAR - previousZ) / (currentZ - previousZ);

                outputX[outputCount] = previousX + (currentX - previousX) * blend;
                outputY[outputCount] = previousY + (currentY - previousY) * blend;
                outputZ[outputCount] = RenderSettings.SHADOW_NEAR;
                outputCount++;
            }

            if (currentInside) {
                outputX[outputCount] = currentX;
                outputY[outputCount] = currentY;
                outputZ[outputCount] = currentZ;
                outputCount++;
            }

            previousX = currentX;
            previousY = currentY;
            previousZ = currentZ;
            previousInside = currentInside;
        }

        return outputCount;
    }

    private void rasterizePerspectiveTriangle(
            int face,
            double tangent,
            double x0, double y0, double z0,
            double x1, double y1, double z1,
            double x2, double y2, double z2
    ) {
        final double scale = 0.5 * size;

        final double inverseDepth0 = 1.0 / z0;
        final double inverseDepth1 = 1.0 / z1;
        final double inverseDepth2 = 1.0 / z2;

        final double screenX0 = (x0 * inverseDepth0 / tangent + 1.0) * scale;
        final double screenY0 = (1.0 - y0 * inverseDepth0 / tangent) * scale;
        final double screenX1 = (x1 * inverseDepth1 / tangent + 1.0) * scale;
        final double screenY1 = (1.0 - y1 * inverseDepth1 / tangent) * scale;
        final double screenX2 = (x2 * inverseDepth2 / tangent + 1.0) * scale;
        final double screenY2 = (1.0 - y2 * inverseDepth2 / tangent) * scale;

        final double area = edge(screenX0, screenY0, screenX1, screenY1, screenX2, screenY2);

        if (!Double.isFinite(area) || Math.abs(area) <= 1.0e-12) {
            return;
        }

        final int minimumX = Math.max(0, (int) Math.floor(Math.min(screenX0, Math.min(screenX1, screenX2))));
        final int maximumX = Math.min(size - 1, (int) Math.ceil(Math.max(screenX0, Math.max(screenX1, screenX2))));
        final int minimumY = Math.max(0, (int) Math.floor(Math.min(screenY0, Math.min(screenY1, screenY2))));
        final int maximumY = Math.min(size - 1, (int) Math.ceil(Math.max(screenY0, Math.max(screenY1, screenY2))));

        if (minimumX > maximumX || minimumY > maximumY) {
            return;
        }

        final double inverseArea = 1.0 / area;
        final float[] depth = depths[face];

        for (int y = minimumY; y <= maximumY; y++) {
            final double sampleY = y + 0.5;
            final int row = y * size;

            for (int x = minimumX; x <= maximumX; x++) {
                final double sampleX = x + 0.5;

                final double weight0 = edge(screenX1, screenY1, screenX2, screenY2, sampleX, sampleY) * inverseArea;
                final double weight1 = edge(screenX2, screenY2, screenX0, screenY0, sampleX, sampleY) * inverseArea;
                final double weight2 = 1.0 - weight0 - weight1;

                if (weight0 < 0.0 || weight1 < 0.0 || weight2 < 0.0) {
                    continue;
                }

                final float inverseDepth =
                        (float) (weight0 * inverseDepth0 + weight1 * inverseDepth1 + weight2 * inverseDepth2);

                final int bufferIndex = row + x;
                if (inverseDepth > depth[bufferIndex]) {
                    depth[bufferIndex] = inverseDepth;
                }
            }
        }
    }

    private double sampleDirectional(
            double worldX, double worldY, double worldZ,
            double normalX, double normalY, double normalZ,
            double bias
    ) {
        final double relativeX = worldX - centerX;
        final double relativeY = worldY - centerY;
        final double relativeZ = worldZ - centerZ;

        final double receiverX =
                relativeX * rightX + relativeY * rightY + relativeZ * rightZ;
        final double receiverY =
                relativeX * upX + relativeY * upY + relativeZ * upZ;
        final double receiverDepth =
                relativeX * forwardX + relativeY * forwardY + relativeZ * forwardZ;

        if (receiverX < -halfSpan || receiverX > halfSpan ||
                receiverY < -halfSpan || receiverY > halfSpan ||
                receiverDepth < -maximumDistance || receiverDepth > maximumDistance) {
            return 1.0;
        }

        final double scale = size / (2.0 * halfSpan);

        final double mapX = clampMapCoordinate((receiverX + halfSpan) * scale);
        final double mapY = clampMapCoordinate((halfSpan - receiverY) * scale);

        final double localNormalRight =
                normalX * rightX + normalY * rightY + normalZ * rightZ;
        final double localNormalUp =
                normalX * upX + normalY * upY + normalZ * upZ;
        final double localNormalForward =
                normalX * forwardX + normalY * forwardY + normalZ * forwardZ;

        // Radius-0 is the production default – fully unrolled bilinear.
        if (RenderSettings.SHADOW_PCF_RADIUS == 0) {
            return percentageCloserDirectionalBilinear(
                    depths[0],
                    mapX, mapY,
                    receiverX, receiverY, receiverDepth,
                    bias,
                    localNormalRight, localNormalUp, localNormalForward
            );
        }

        return percentageCloserDirectional(
                depths[0],
                mapX, mapY,
                receiverX, receiverY, receiverDepth,
                bias,
                localNormalRight, localNormalUp, localNormalForward
        );
    }

    private double samplePerspective(
            int face,
            double localForwardX, double localForwardY, double localForwardZ,
            double localRightX, double localRightY, double localRightZ,
            double localUpX, double localUpY, double localUpZ,
            double tangent,
            double worldX, double worldY, double worldZ,
            double normalX, double normalY, double normalZ,
            double bias
    ) {
        final double relativeX = worldX - originX;
        final double relativeY = worldY - originY;
        final double relativeZ = worldZ - originZ;

        final double receiverX =
                relativeX * localRightX + relativeY * localRightY + relativeZ * localRightZ;
        final double receiverY =
                relativeX * localUpX + relativeY * localUpY + relativeZ * localUpZ;
        final double receiverDepth =
                relativeX * localForwardX + relativeY * localForwardY + relativeZ * localForwardZ;

        if (receiverDepth <= RenderSettings.SHADOW_NEAR || receiverDepth > maximumDistance) {
            return 1.0;
        }

        final double normalisedX = receiverX / (receiverDepth * tangent);
        final double normalisedY = receiverY / (receiverDepth * tangent);

        if (normalisedX < -1.0 || normalisedX > 1.0 ||
                normalisedY < -1.0 || normalisedY > 1.0) {
            return 1.0;
        }

        final double scale = 0.5 * size;

        final double mapX = clampMapCoordinate((normalisedX + 1.0) * scale);
        final double mapY = clampMapCoordinate((1.0 - normalisedY) * scale);

        final double localNormalX =
                normalX * localRightX + normalY * localRightY + normalZ * localRightZ;
        final double localNormalY =
                normalX * localUpX + normalY * localUpY + normalZ * localUpZ;
        final double localNormalZ =
                normalX * localForwardX + normalY * localForwardY + normalZ * localForwardZ;

        if (RenderSettings.SHADOW_PCF_RADIUS == 0) {
            return percentageCloserPerspectiveBilinear(
                    depths[face],
                    mapX, mapY,
                    receiverX, receiverY, receiverDepth,
                    bias,
                    localNormalX, localNormalY, localNormalZ,
                    tangent
            );
        }

        return percentageCloserPerspective(
                depths[face],
                mapX, mapY,
                receiverX, receiverY, receiverDepth,
                bias,
                localNormalX, localNormalY, localNormalZ,
                tangent
        );
    }

    /** Fully unrolled 2×2 bilinear PCF – zero loop overhead. */
    private double percentageCloserDirectionalBilinear(
            float[] depth,
            double mapX, double mapY,
            double receiverX, double receiverY, double receiverDepth,
            double bias,
            double normalRight, double normalUp, double normalForward
    ) {
        final double textureX = mapX - 0.5;
        final double textureY = mapY - 0.5;

        final int baseX = (int) Math.floor(textureX);
        final int baseY = (int) Math.floor(textureY);

        final double fractionX = textureX - baseX;
        final double fractionY = textureY - baseY;

        final double worldUnitsPerTexel = (2.0 * halfSpan) / size;

        double visibleWeight = 0.0;
        double totalWeight = 0.0;

        // ---- (0,0) ----
        {
            final int y = baseY;
            final int x = baseX;
            if (y >= 0 && y < size && x >= 0 && x < size) {
                final double weightY = 1.0 - fractionY;
                final double weightX = 1.0 - fractionX;
                final double weight = weightX * weightY;
                if (weight > 0.0) {
                    final double sampleReceiverY = halfSpan - (y + 0.5) * worldUnitsPerTexel;
                    final double deltaUp = sampleReceiverY - receiverY;
                    final double sampleReceiverX = -halfSpan + (x + 0.5) * worldUnitsPerTexel;
                    final double deltaRight = sampleReceiverX - receiverX;

                    double expectedDepth = receiverDepth;
                    if (Math.abs(normalForward) > 1.0e-8) {
                        final double corrected =
                                receiverDepth -
                                        (normalRight * deltaRight + normalUp * deltaUp) / normalForward;
                        if (Double.isFinite(corrected)) {
                            expectedDepth = corrected;
                        }
                    }

                    final float stored = depth[y * size + x];
                    if (stored == Float.POSITIVE_INFINITY || expectedDepth - bias <= stored) {
                        visibleWeight += weight;
                    }
                    totalWeight += weight;
                }
            }
        }

        // ---- (1,0) ----
        {
            final int y = baseY;
            final int x = baseX + 1;
            if (y >= 0 && y < size && x >= 0 && x < size) {
                final double weightY = 1.0 - fractionY;
                final double weightX = fractionX;
                final double weight = weightX * weightY;
                if (weight > 0.0) {
                    final double sampleReceiverY = halfSpan - (y + 0.5) * worldUnitsPerTexel;
                    final double deltaUp = sampleReceiverY - receiverY;
                    final double sampleReceiverX = -halfSpan + (x + 0.5) * worldUnitsPerTexel;
                    final double deltaRight = sampleReceiverX - receiverX;

                    double expectedDepth = receiverDepth;
                    if (Math.abs(normalForward) > 1.0e-8) {
                        final double corrected =
                                receiverDepth -
                                        (normalRight * deltaRight + normalUp * deltaUp) / normalForward;
                        if (Double.isFinite(corrected)) {
                            expectedDepth = corrected;
                        }
                    }

                    final float stored = depth[y * size + x];
                    if (stored == Float.POSITIVE_INFINITY || expectedDepth - bias <= stored) {
                        visibleWeight += weight;
                    }
                    totalWeight += weight;
                }
            }
        }

        // ---- (0,1) ----
        {
            final int y = baseY + 1;
            final int x = baseX;
            if (y >= 0 && y < size && x >= 0 && x < size) {
                final double weightY = fractionY;
                final double weightX = 1.0 - fractionX;
                final double weight = weightX * weightY;
                if (weight > 0.0) {
                    final double sampleReceiverY = halfSpan - (y + 0.5) * worldUnitsPerTexel;
                    final double deltaUp = sampleReceiverY - receiverY;
                    final double sampleReceiverX = -halfSpan + (x + 0.5) * worldUnitsPerTexel;
                    final double deltaRight = sampleReceiverX - receiverX;

                    double expectedDepth = receiverDepth;
                    if (Math.abs(normalForward) > 1.0e-8) {
                        final double corrected =
                                receiverDepth -
                                        (normalRight * deltaRight + normalUp * deltaUp) / normalForward;
                        if (Double.isFinite(corrected)) {
                            expectedDepth = corrected;
                        }
                    }

                    final float stored = depth[y * size + x];
                    if (stored == Float.POSITIVE_INFINITY || expectedDepth - bias <= stored) {
                        visibleWeight += weight;
                    }
                    totalWeight += weight;
                }
            }
        }

        // ---- (1,1) ----
        {
            final int y = baseY + 1;
            final int x = baseX + 1;
            if (y >= 0 && y < size && x >= 0 && x < size) {
                final double weightY = fractionY;
                final double weightX = fractionX;
                final double weight = weightX * weightY;
                if (weight > 0.0) {
                    final double sampleReceiverY = halfSpan - (y + 0.5) * worldUnitsPerTexel;
                    final double deltaUp = sampleReceiverY - receiverY;
                    final double sampleReceiverX = -halfSpan + (x + 0.5) * worldUnitsPerTexel;
                    final double deltaRight = sampleReceiverX - receiverX;

                    double expectedDepth = receiverDepth;
                    if (Math.abs(normalForward) > 1.0e-8) {
                        final double corrected =
                                receiverDepth -
                                        (normalRight * deltaRight + normalUp * deltaUp) / normalForward;
                        if (Double.isFinite(corrected)) {
                            expectedDepth = corrected;
                        }
                    }

                    final float stored = depth[y * size + x];
                    if (stored == Float.POSITIVE_INFINITY || expectedDepth - bias <= stored) {
                        visibleWeight += weight;
                    }
                    totalWeight += weight;
                }
            }
        }

        return totalWeight <= 0.0 ? 1.0 : visibleWeight / totalWeight;
    }

    /** Fully unrolled 2×2 bilinear PCF for perspective maps. */
    private double percentageCloserPerspectiveBilinear(
            float[] depth,
            double mapX, double mapY,
            double receiverX, double receiverY, double receiverDepth,
            double bias,
            double normalX, double normalY, double normalZ,
            double tangent
    ) {
        final double textureX = mapX - 0.5;
        final double textureY = mapY - 0.5;

        final int baseX = (int) Math.floor(textureX);
        final int baseY = (int) Math.floor(textureY);

        final double fractionX = textureX - baseX;
        final double fractionY = textureY - baseY;

        final double scale = 0.5 * size;
        final double planeNumerator =
                normalX * receiverX + normalY * receiverY + normalZ * receiverDepth;

        double visibleWeight = 0.0;
        double totalWeight = 0.0;

        // (0,0)
        {
            final int y = baseY;
            final int x = baseX;
            if (y >= 0 && y < size && x >= 0 && x < size) {
                final double weightY = 1.0 - fractionY;
                final double weightX = 1.0 - fractionX;
                final double weight = weightX * weightY;
                if (weight > 0.0) {
                    final double rayX = ((x + 0.5) / scale - 1.0) * tangent;
                    final double rayY = (1.0 - (y + 0.5) / scale) * tangent;
                    final double denominator = normalX * rayX + normalY * rayY + normalZ;

                    double expectedDepth = receiverDepth;
                    if (Math.abs(denominator) > 1.0e-8) {
                        final double corrected = planeNumerator / denominator;
                        if (corrected > RenderSettings.SHADOW_NEAR && Double.isFinite(corrected)) {
                            expectedDepth = corrected;
                        }
                    }

                    final float storedInverseDepth = depth[y * size + x];
                    if (storedInverseDepth == Float.NEGATIVE_INFINITY ||
                            expectedDepth - bias <= 1.0 / storedInverseDepth) {
                        visibleWeight += weight;
                    }
                    totalWeight += weight;
                }
            }
        }

        // (1,0)
        {
            final int y = baseY;
            final int x = baseX + 1;
            if (y >= 0 && y < size && x >= 0 && x < size) {
                final double weightY = 1.0 - fractionY;
                final double weightX = fractionX;
                final double weight = weightX * weightY;
                if (weight > 0.0) {
                    final double rayX = ((x + 0.5) / scale - 1.0) * tangent;
                    final double rayY = (1.0 - (y + 0.5) / scale) * tangent;
                    final double denominator = normalX * rayX + normalY * rayY + normalZ;

                    double expectedDepth = receiverDepth;
                    if (Math.abs(denominator) > 1.0e-8) {
                        final double corrected = planeNumerator / denominator;
                        if (corrected > RenderSettings.SHADOW_NEAR && Double.isFinite(corrected)) {
                            expectedDepth = corrected;
                        }
                    }

                    final float storedInverseDepth = depth[y * size + x];
                    if (storedInverseDepth == Float.NEGATIVE_INFINITY ||
                            expectedDepth - bias <= 1.0 / storedInverseDepth) {
                        visibleWeight += weight;
                    }
                    totalWeight += weight;
                }
            }
        }

        // (0,1)
        {
            final int y = baseY + 1;
            final int x = baseX;
            if (y >= 0 && y < size && x >= 0 && x < size) {
                final double weightY = fractionY;
                final double weightX = 1.0 - fractionX;
                final double weight = weightX * weightY;
                if (weight > 0.0) {
                    final double rayX = ((x + 0.5) / scale - 1.0) * tangent;
                    final double rayY = (1.0 - (y + 0.5) / scale) * tangent;
                    final double denominator = normalX * rayX + normalY * rayY + normalZ;

                    double expectedDepth = receiverDepth;
                    if (Math.abs(denominator) > 1.0e-8) {
                        final double corrected = planeNumerator / denominator;
                        if (corrected > RenderSettings.SHADOW_NEAR && Double.isFinite(corrected)) {
                            expectedDepth = corrected;
                        }
                    }

                    final float storedInverseDepth = depth[y * size + x];
                    if (storedInverseDepth == Float.NEGATIVE_INFINITY ||
                            expectedDepth - bias <= 1.0 / storedInverseDepth) {
                        visibleWeight += weight;
                    }
                    totalWeight += weight;
                }
            }
        }

        // (1,1)
        {
            final int y = baseY + 1;
            final int x = baseX + 1;
            if (y >= 0 && y < size && x >= 0 && x < size) {
                final double weightY = fractionY;
                final double weightX = fractionX;
                final double weight = weightX * weightY;
                if (weight > 0.0) {
                    final double rayX = ((x + 0.5) / scale - 1.0) * tangent;
                    final double rayY = (1.0 - (y + 0.5) / scale) * tangent;
                    final double denominator = normalX * rayX + normalY * rayY + normalZ;

                    double expectedDepth = receiverDepth;
                    if (Math.abs(denominator) > 1.0e-8) {
                        final double corrected = planeNumerator / denominator;
                        if (corrected > RenderSettings.SHADOW_NEAR && Double.isFinite(corrected)) {
                            expectedDepth = corrected;
                        }
                    }

                    final float storedInverseDepth = depth[y * size + x];
                    if (storedInverseDepth == Float.NEGATIVE_INFINITY ||
                            expectedDepth - bias <= 1.0 / storedInverseDepth) {
                        visibleWeight += weight;
                    }
                    totalWeight += weight;
                }
            }
        }

        return totalWeight <= 0.0 ? 1.0 : visibleWeight / totalWeight;
    }

    private double percentageCloserDirectional(
            float[] depth,
            double mapX, double mapY,
            double receiverX, double receiverY, double receiverDepth,
            double bias,
            double normalRight, double normalUp, double normalForward
    ) {
        final int radius = RenderSettings.SHADOW_PCF_RADIUS;

        final double textureX = mapX - 0.5;
        final double textureY = mapY - 0.5;

        final int baseX = (int) Math.floor(textureX);
        final int baseY = (int) Math.floor(textureY);

        final double fractionX = textureX - baseX;
        final double fractionY = textureY - baseY;

        double visibleWeight = 0.0;
        double totalWeight = 0.0;

        final double worldUnitsPerTexel = (2.0 * halfSpan) / size;

        for (int offsetY = -radius; offsetY <= radius + 1; offsetY++) {
            final int y = baseY + offsetY;
            if (y < 0 || y >= size) {
                continue;
            }

            final double weightY = filterWeight(offsetY, radius, fractionY);
            if (weightY <= 0.0) {
                continue;
            }

            final int row = y * size;
            final double sampleReceiverY = halfSpan - (y + 0.5) * worldUnitsPerTexel;
            final double deltaUp = sampleReceiverY - receiverY;

            for (int offsetX = -radius; offsetX <= radius + 1; offsetX++) {
                final int x = baseX + offsetX;
                if (x < 0 || x >= size) {
                    continue;
                }

                final double weightX = filterWeight(offsetX, radius, fractionX);
                final double weight = weightX * weightY;
                if (weight <= 0.0) {
                    continue;
                }

                final double sampleReceiverX = -halfSpan + (x + 0.5) * worldUnitsPerTexel;
                final double deltaRight = sampleReceiverX - receiverX;

                double expectedDepth = receiverDepth;
                if (Math.abs(normalForward) > 1.0e-8) {
                    final double corrected =
                            receiverDepth -
                                    (normalRight * deltaRight + normalUp * deltaUp) / normalForward;
                    if (Double.isFinite(corrected)) {
                        expectedDepth = corrected;
                    }
                }

                final float stored = depth[row + x];
                if (stored == Float.POSITIVE_INFINITY || expectedDepth - bias <= stored) {
                    visibleWeight += weight;
                }
                totalWeight += weight;
            }
        }

        return totalWeight <= 0.0 ? 1.0 : visibleWeight / totalWeight;
    }

    private double percentageCloserPerspective(
            float[] depth,
            double mapX, double mapY,
            double receiverX, double receiverY, double receiverDepth,
            double bias,
            double normalX, double normalY, double normalZ,
            double tangent
    ) {
        final int radius = RenderSettings.SHADOW_PCF_RADIUS;

        final double textureX = mapX - 0.5;
        final double textureY = mapY - 0.5;

        final int baseX = (int) Math.floor(textureX);
        final int baseY = (int) Math.floor(textureY);

        final double fractionX = textureX - baseX;
        final double fractionY = textureY - baseY;

        double visibleWeight = 0.0;
        double totalWeight = 0.0;

        final double scale = 0.5 * size;
        final double planeNumerator =
                normalX * receiverX + normalY * receiverY + normalZ * receiverDepth;

        for (int offsetY = -radius; offsetY <= radius + 1; offsetY++) {
            final int y = baseY + offsetY;
            if (y < 0 || y >= size) {
                continue;
            }

            final double weightY = filterWeight(offsetY, radius, fractionY);
            if (weightY <= 0.0) {
                continue;
            }

            final int row = y * size;

            for (int offsetX = -radius; offsetX <= radius + 1; offsetX++) {
                final int x = baseX + offsetX;
                if (x < 0 || x >= size) {
                    continue;
                }

                final double weightX = filterWeight(offsetX, radius, fractionX);
                final double weight = weightX * weightY;
                if (weight <= 0.0) {
                    continue;
                }

                final double rayX = ((x + 0.5) / scale - 1.0) * tangent;
                final double rayY = (1.0 - (y + 0.5) / scale) * tangent;

                final double denominator = normalX * rayX + normalY * rayY + normalZ;

                double expectedDepth = receiverDepth;
                if (Math.abs(denominator) > 1.0e-8) {
                    final double corrected = planeNumerator / denominator;
                    if (corrected > RenderSettings.SHADOW_NEAR && Double.isFinite(corrected)) {
                        expectedDepth = corrected;
                    }
                }

                final float storedInverseDepth = depth[row + x];
                if (storedInverseDepth == Float.NEGATIVE_INFINITY ||
                        expectedDepth - bias <= 1.0 / storedInverseDepth) {
                    visibleWeight += weight;
                }
                totalWeight += weight;
            }
        }

        return totalWeight <= 0.0 ? 1.0 : visibleWeight / totalWeight;
    }

    private void setForward(double x, double y, double z) {
        final double lengthSquared = x * x + y * y + z * z;

        if (lengthSquared <= 1.0e-18 || !Double.isFinite(lengthSquared)) {
            forwardX = 0.0;
            forwardY = -1.0;
            forwardZ = 0.0;
            return;
        }

        final double inverseLength = 1.0 / Math.sqrt(lengthSquared);
        forwardX = x * inverseLength;
        forwardY = y * inverseLength;
        forwardZ = z * inverseLength;
    }

    private void buildBasis() {
        final double helperX = Math.abs(forwardY) > 0.95 ? 1.0 : 0.0;
        final double helperY = Math.abs(forwardY) > 0.95 ? 0.0 : 1.0;

        rightX = helperY * forwardZ;
        rightY = -helperX * forwardZ;
        rightZ = helperX * forwardY - helperY * forwardX;

        final double inverseRightLength =
                1.0 / Math.sqrt(rightX * rightX + rightY * rightY + rightZ * rightZ);

        rightX *= inverseRightLength;
        rightY *= inverseRightLength;
        rightZ *= inverseRightLength;

        upX = forwardY * rightZ - forwardZ * rightY;
        upY = forwardZ * rightX - forwardX * rightZ;
        upZ = forwardX * rightY - forwardY * rightX;
    }

    private void snapDirectionalCenter(
            double cameraX, double cameraY, double cameraZ
    ) {
        final double texelSize = (2.0 * halfSpan) / size;

        final double cameraRight =
                cameraX * rightX + cameraY * rightY + cameraZ * rightZ;
        final double cameraUp =
                cameraX * upX + cameraY * upY + cameraZ * upZ;

        final double snappedRight = Math.rint(cameraRight / texelSize) * texelSize;
        final double snappedUp = Math.rint(cameraUp / texelSize) * texelSize;

        final double rightOffset = snappedRight - cameraRight;
        final double upOffset = snappedUp - cameraUp;

        centerX = cameraX + rightX * rightOffset + upX * upOffset;
        centerY = cameraY + rightY * rightOffset + upY * upOffset;
        centerZ = cameraZ + rightZ * rightOffset + upZ * upOffset;
    }

    private void ensureStorage() {
        if (depths.length != faceCount) {
            depths = new float[faceCount][];
        }

        final int required = size * size;

        for (int face = 0; face < faceCount; face++) {
            if (depths[face] == null || depths[face].length != required) {
                depths[face] = new float[required];
            }
        }
    }

    private void clearDirectional() {
        Arrays.fill(depths[0], Float.POSITIVE_INFINITY);
    }

    private void clearPerspective() {
        for (int face = 0; face < faceCount; face++) {
            Arrays.fill(depths[face], Float.NEGATIVE_INFINITY);
        }
    }

    private static double edge(
            double ax, double ay,
            double bx, double by,
            double px, double py
    ) {
        return (px - ax) * (by - ay) - (py - ay) * (bx - ax);
    }

    private double clampMapCoordinate(double coordinate) {
        return Math.max(
                0.0,
                Math.min(Math.nextDown((double) size), coordinate)
        );
    }

    private static double filterWeight(int offset, int radius, double fraction) {
        if (offset == -radius) {
            return 1.0 - fraction;
        }
        if (offset == radius + 1) {
            return fraction;
        }
        return 1.0;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}