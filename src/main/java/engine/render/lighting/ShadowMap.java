package engine.render.lighting;

import engine.lighting.LightData;
import engine.lighting.LightType;
import engine.render.RenderSettings;
import java.util.Arrays;
import util.AABB;

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
    private double directionalMapScale;
    private double directionalWorldUnitsPerTexel;
    private double perspectiveMapScale;
    private double inversePerspectiveMapScale;
    private double maximumMapCoordinate;

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

            directionalMapScale =
                    size /
                            (
                                    2.0 *
                                            halfSpan
                            );

            directionalWorldUnitsPerTexel =
                    (
                            2.0 *
                                    halfSpan
                    ) /
                            size;

            maximumMapCoordinate =
                    Math.nextDown(
                            (double) size
                    );

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

        perspectiveMapScale =
                0.5 *
                        size;

        inversePerspectiveMapScale =
                1.0 /
                        perspectiveMapScale;

        maximumMapCoordinate =
                Math.nextDown(
                        (double) size
                );

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

        for (
                int face = 0;
                face < 6;
                face++
        ) {
            final double[] forward =
                    POINT_FORWARD[face];

            final double[] right =
                    POINT_RIGHT[face];

            final double[] up =
                    POINT_UP[face];

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

    /**
     * Conservative light-space AABB rejection before any face is submitted
     * to the shadow rasterizer.
     */
    public boolean intersects(
            AABB bounds
    ) {
        if (bounds == null) {
            return true;
        }

        if (kind != DIRECTIONAL) {
            return true;
        }

        final double halfX =
                (
                        bounds.maxX -
                                bounds.minX
                ) *
                        0.5;

        final double halfY =
                (
                        bounds.maxY -
                                bounds.minY
                ) *
                        0.5;

        final double halfZ =
                (
                        bounds.maxZ -
                                bounds.minZ
                ) *
                        0.5;

        final double relativeX =
                (
                        bounds.minX +
                                bounds.maxX
                ) *
                        0.5 -
                        centerX;

        final double relativeY =
                (
                        bounds.minY +
                                bounds.maxY
                ) *
                        0.5 -
                        centerY;

        final double relativeZ =
                (
                        bounds.minZ +
                                bounds.maxZ
                ) *
                        0.5 -
                        centerZ;

        final double projectedX =
                relativeX *
                        rightX +
                        relativeY *
                                rightY +
                        relativeZ *
                                rightZ;

        final double extentX =
                Math.abs(
                        rightX
                ) *
                        halfX +
                        Math.abs(
                                rightY
                        ) *
                                halfY +
                        Math.abs(
                                rightZ
                        ) *
                                halfZ;

        if (
                projectedX +
                        extentX <
                        -halfSpan ||
                        projectedX -
                                extentX >
                                halfSpan
        ) {
            return false;
        }

        final double projectedY =
                relativeX *
                        upX +
                        relativeY *
                                upY +
                        relativeZ *
                                upZ;

        final double extentY =
                Math.abs(
                        upX
                ) *
                        halfX +
                        Math.abs(
                                upY
                        ) *
                                halfY +
                        Math.abs(
                                upZ
                        ) *
                                halfZ;

        if (
                projectedY +
                        extentY <
                        -halfSpan ||
                        projectedY -
                                extentY >
                                halfSpan
        ) {
            return false;
        }

        final double projectedDepth =
                relativeX *
                        forwardX +
                        relativeY *
                                forwardY +
                        relativeZ *
                                forwardZ;

        final double depthExtent =
                Math.abs(
                        forwardX
                ) *
                        halfX +
                        Math.abs(
                                forwardY
                        ) *
                                halfY +
                        Math.abs(
                                forwardZ
                        ) *
                                halfZ;

        return projectedDepth +
                depthExtent >=
                -maximumDistance &&
                projectedDepth -
                        depthExtent <=
                        maximumDistance;
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
                normalDotLight < 0.0
                        ? 0.0
                        : normalDotLight > 1.0
                        ? 1.0
                        : normalDotLight;

        final double bias =
                RenderSettings.SHADOW_DEPTH_BIAS +
                        RenderSettings.SHADOW_NORMAL_BIAS *
                                (
                                        1.0 -
                                                cosine
                                );

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

        final double deltaX =
                worldX -
                        originX;

        final double deltaY =
                worldY -
                        originY;

        final double deltaZ =
                worldZ -
                        originZ;

        final double absoluteX =
                Math.abs(
                        deltaX
                );

        final double absoluteY =
                Math.abs(
                        deltaY
                );

        final double absoluteZ =
                Math.abs(
                        deltaZ
                );

        final int face;

        if (
                absoluteX >= absoluteY &&
                        absoluteX >= absoluteZ
        ) {
            face =
                    deltaX >= 0.0
                            ? 0
                            : 1;
        } else if (absoluteY >= absoluteZ) {
            face =
                    deltaY >= 0.0
                            ? 2
                            : 3;
        } else {
            face =
                    deltaZ >= 0.0
                            ? 4
                            : 5;
        }

        final double[] forward =
                POINT_FORWARD[face];

        final double[] right =
                POINT_RIGHT[face];

        final double[] up =
                POINT_UP[face];

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
        final double relativeX0 =
                x0 -
                        centerX;

        final double relativeY0 =
                y0 -
                        centerY;

        final double relativeZ0 =
                z0 -
                        centerZ;

        final double relativeX1 =
                x1 -
                        centerX;

        final double relativeY1 =
                y1 -
                        centerY;

        final double relativeZ1 =
                z1 -
                        centerZ;

        final double relativeX2 =
                x2 -
                        centerX;

        final double relativeY2 =
                y2 -
                        centerY;

        final double relativeZ2 =
                z2 -
                        centerZ;

        final double projectedX0 =
                relativeX0 *
                        rightX +
                        relativeY0 *
                                rightY +
                        relativeZ0 *
                                rightZ;

        final double projectedY0 =
                relativeX0 *
                        upX +
                        relativeY0 *
                                upY +
                        relativeZ0 *
                                upZ;

        final double projectedZ0 =
                relativeX0 *
                        forwardX +
                        relativeY0 *
                                forwardY +
                        relativeZ0 *
                                forwardZ;

        final double projectedX1 =
                relativeX1 *
                        rightX +
                        relativeY1 *
                                rightY +
                        relativeZ1 *
                                rightZ;

        final double projectedY1 =
                relativeX1 *
                        upX +
                        relativeY1 *
                                upY +
                        relativeZ1 *
                                upZ;

        final double projectedZ1 =
                relativeX1 *
                        forwardX +
                        relativeY1 *
                                forwardY +
                        relativeZ1 *
                                forwardZ;

        final double projectedX2 =
                relativeX2 *
                        rightX +
                        relativeY2 *
                                rightY +
                        relativeZ2 *
                                rightZ;

        final double projectedY2 =
                relativeX2 *
                        upX +
                        relativeY2 *
                                upY +
                        relativeZ2 *
                                upZ;

        final double projectedZ2 =
                relativeX2 *
                        forwardX +
                        relativeY2 *
                                forwardY +
                        relativeZ2 *
                                forwardZ;

        if (
                projectedZ0 < -maximumDistance &&
                        projectedZ1 < -maximumDistance &&
                        projectedZ2 < -maximumDistance
        ) {
            return;
        }

        if (
                projectedZ0 > maximumDistance &&
                        projectedZ1 > maximumDistance &&
                        projectedZ2 > maximumDistance
        ) {
            return;
        }

        final double scale =
                directionalMapScale;

        final double screenX0 =
                (
                        projectedX0 +
                                halfSpan
                ) *
                        scale;

        final double screenY0 =
                (
                        halfSpan -
                                projectedY0
                ) *
                        scale;

        final double screenX1 =
                (
                        projectedX1 +
                                halfSpan
                ) *
                        scale;

        final double screenY1 =
                (
                        halfSpan -
                                projectedY1
                ) *
                        scale;

        final double screenX2 =
                (
                        projectedX2 +
                                halfSpan
                ) *
                        scale;

        final double screenY2 =
                (
                        halfSpan -
                                projectedY2
                ) *
                        scale;

        rasterizeDirectionalTriangle(
                screenX0, screenY0, projectedZ0,
                screenX1, screenY1, projectedZ1,
                screenX2, screenY2, projectedZ2
        );
    }

    private void rasterizeDirectionalTriangle(
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
        rasterizeDepthTriangle(
                depths[0],
                false,
                x0,
                y0,
                z0,
                x1,
                y1,
                z1,
                x2,
                y2,
                z2
        );
    }

    private void addPerspectiveTriangle(
            int face,
            double localForwardX,
            double localForwardY,
            double localForwardZ,
            double localRightX,
            double localRightY,
            double localRightZ,
            double localUpX,
            double localUpY,
            double localUpZ,
            double tangent,
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
        final double relativeX0 =
                x0 -
                        originX;

        final double relativeY0 =
                y0 -
                        originY;

        final double relativeZ0 =
                z0 -
                        originZ;

        final double relativeX1 =
                x1 -
                        originX;

        final double relativeY1 =
                y1 -
                        originY;

        final double relativeZ1 =
                z1 -
                        originZ;

        final double relativeX2 =
                x2 -
                        originX;

        final double relativeY2 =
                y2 -
                        originY;

        final double relativeZ2 =
                z2 -
                        originZ;

        final double viewX0 =
                relativeX0 *
                        localRightX +
                        relativeY0 *
                                localRightY +
                        relativeZ0 *
                                localRightZ;

        final double viewY0 =
                relativeX0 *
                        localUpX +
                        relativeY0 *
                                localUpY +
                        relativeZ0 *
                                localUpZ;

        final double viewZ0 =
                relativeX0 *
                        localForwardX +
                        relativeY0 *
                                localForwardY +
                        relativeZ0 *
                                localForwardZ;

        final double viewX1 =
                relativeX1 *
                        localRightX +
                        relativeY1 *
                                localRightY +
                        relativeZ1 *
                                localRightZ;

        final double viewY1 =
                relativeX1 *
                        localUpX +
                        relativeY1 *
                                localUpY +
                        relativeZ1 *
                                localUpZ;

        final double viewZ1 =
                relativeX1 *
                        localForwardX +
                        relativeY1 *
                                localForwardY +
                        relativeZ1 *
                                localForwardZ;

        final double viewX2 =
                relativeX2 *
                        localRightX +
                        relativeY2 *
                                localRightY +
                        relativeZ2 *
                                localRightZ;

        final double viewY2 =
                relativeX2 *
                        localUpX +
                        relativeY2 *
                                localUpY +
                        relativeZ2 *
                                localUpZ;

        final double viewZ2 =
                relativeX2 *
                        localForwardX +
                        relativeY2 *
                                localForwardY +
                        relativeZ2 *
                                localForwardZ;

        clipX0[0] = viewX0;
        clipY0[0] = viewY0;
        clipZ0[0] = viewZ0;

        clipX0[1] = viewX1;
        clipY0[1] = viewY1;
        clipZ0[1] = viewZ1;

        clipX0[2] = viewX2;
        clipY0[2] = viewY2;
        clipZ0[2] = viewZ2;

        final int count =
                clipNearPlane(
                        clipX0,
                        clipY0,
                        clipZ0,
                        3,
                        clipX1,
                        clipY1,
                        clipZ1
                );

        if (count < 3) {
            return;
        }

        final double firstX =
                clipX1[0];

        final double firstY =
                clipY1[0];

        final double firstZ =
                clipZ1[0];

        for (
                int index = 1;
                index < count - 1;
                index++
        ) {
            rasterizePerspectiveTriangle(
                    face,
                    tangent,
                    firstX,
                    firstY,
                    firstZ,
                    clipX1[index],
                    clipY1[index],
                    clipZ1[index],
                    clipX1[index + 1],
                    clipY1[index + 1],
                    clipZ1[index + 1]
            );
        }
    }

    private int clipNearPlane(
            double[] inputX,
            double[] inputY,
            double[] inputZ,
            int inputCount,
            double[] outputX,
            double[] outputY,
            double[] outputZ
    ) {
        int outputCount =
                0;

        double previousX =
                inputX[inputCount - 1];

        double previousY =
                inputY[inputCount - 1];

        double previousZ =
                inputZ[inputCount - 1];

        boolean previousInside =
                previousZ >=
                        RenderSettings.SHADOW_NEAR;

        for (
                int index = 0;
                index < inputCount;
                index++
        ) {
            final double currentX =
                    inputX[index];

            final double currentY =
                    inputY[index];

            final double currentZ =
                    inputZ[index];

            final boolean currentInside =
                    currentZ >=
                            RenderSettings.SHADOW_NEAR;

            if (currentInside != previousInside) {
                final double blend =
                        (
                                RenderSettings.SHADOW_NEAR -
                                        previousZ
                        ) /
                                (
                                        currentZ -
                                                previousZ
                                );

                outputX[outputCount] =
                        previousX +
                                (
                                        currentX -
                                                previousX
                                ) *
                                        blend;

                outputY[outputCount] =
                        previousY +
                                (
                                        currentY -
                                                previousY
                                ) *
                                        blend;

                outputZ[outputCount] =
                        RenderSettings.SHADOW_NEAR;

                outputCount++;
            }

            if (currentInside) {
                outputX[outputCount] =
                        currentX;

                outputY[outputCount] =
                        currentY;

                outputZ[outputCount] =
                        currentZ;

                outputCount++;
            }

            previousX =
                    currentX;

            previousY =
                    currentY;

            previousZ =
                    currentZ;

            previousInside =
                    currentInside;
        }

        return outputCount;
    }

    private void rasterizePerspectiveTriangle(
            int face,
            double tangent,
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
        final double scale =
                perspectiveMapScale;

        final double projectionScale =
                scale /
                        tangent;

        final double inverseDepth0 =
                1.0 /
                        z0;

        final double inverseDepth1 =
                1.0 /
                        z1;

        final double inverseDepth2 =
                1.0 /
                        z2;

        final double screenX0 =
                x0 *
                        inverseDepth0 *
                        projectionScale +
                        scale;

        final double screenY0 =
                scale -
                        y0 *
                                inverseDepth0 *
                                projectionScale;

        final double screenX1 =
                x1 *
                        inverseDepth1 *
                        projectionScale +
                        scale;

        final double screenY1 =
                scale -
                        y1 *
                                inverseDepth1 *
                                projectionScale;

        final double screenX2 =
                x2 *
                        inverseDepth2 *
                        projectionScale +
                        scale;

        final double screenY2 =
                scale -
                        y2 *
                                inverseDepth2 *
                                projectionScale;

        rasterizeDepthTriangle(
                depths[face],
                true,
                screenX0,
                screenY0,
                inverseDepth0,
                screenX1,
                screenY1,
                inverseDepth1,
                screenX2,
                screenY2,
                inverseDepth2
        );
    }

    /**
     * Fixed-point incremental shadow rasterizer.
     *
     * The former implementation evaluated two edge functions, constructed
     * three barycentric weights and multiplied three depths for every texel.
     * This setup is now performed once per triangle; texels advance with only
     * additions and a depth comparison.
     */
    private void rasterizeDepthTriangle(
            float[] depth,
            boolean keepMaximum,
            double x0,
            double y0,
            double depth0,
            double x1,
            double y1,
            double depth1,
            double x2,
            double y2,
            double depth2
    ) {
        if (
                !Double.isFinite(x0) ||
                        !Double.isFinite(y0) ||
                        !Double.isFinite(depth0) ||
                        !Double.isFinite(x1) ||
                        !Double.isFinite(y1) ||
                        !Double.isFinite(depth1) ||
                        !Double.isFinite(x2) ||
                        !Double.isFinite(y2) ||
                        !Double.isFinite(depth2)
        ) {
            return;
        }

        long fixedX0 =
                Math.round(
                        x0 *
                                RenderSettings.SUBPIXEL_SCALE
                );

        long fixedY0 =
                Math.round(
                        y0 *
                                RenderSettings.SUBPIXEL_SCALE
                );

        long fixedX1 =
                Math.round(
                        x1 *
                                RenderSettings.SUBPIXEL_SCALE
                );

        long fixedY1 =
                Math.round(
                        y1 *
                                RenderSettings.SUBPIXEL_SCALE
                );

        long fixedX2 =
                Math.round(
                        x2 *
                                RenderSettings.SUBPIXEL_SCALE
                );

        long fixedY2 =
                Math.round(
                        y2 *
                                RenderSettings.SUBPIXEL_SCALE
                );

        long edge0A =
                fixedY1 -
                        fixedY2;

        long edge0B =
                fixedX2 -
                        fixedX1;

        long edge0C =
                fixedX1 *
                        fixedY2 -
                        fixedY1 *
                                fixedX2;

        long edge1A =
                fixedY2 -
                        fixedY0;

        long edge1B =
                fixedX0 -
                        fixedX2;

        long edge1C =
                fixedX2 *
                        fixedY0 -
                        fixedY2 *
                                fixedX0;

        long edge2A =
                fixedY0 -
                        fixedY1;

        long edge2B =
                fixedX1 -
                        fixedX0;

        long edge2C =
                fixedX0 *
                        fixedY1 -
                        fixedY0 *
                                fixedX1;

        long area =
                edge0A *
                        fixedX0 +
                        edge0B *
                                fixedY0 +
                        edge0C;

        if (area == 0L) {
            return;
        }

        if (area < 0L) {
            long temporaryLong =
                    fixedX1;

            fixedX1 =
                    fixedX2;

            fixedX2 =
                    temporaryLong;

            temporaryLong =
                    fixedY1;

            fixedY1 =
                    fixedY2;

            fixedY2 =
                    temporaryLong;

            final double temporaryDepth =
                    depth1;

            depth1 =
                    depth2;

            depth2 =
                    temporaryDepth;

            edge0A =
                    fixedY1 -
                            fixedY2;

            edge0B =
                    fixedX2 -
                            fixedX1;

            edge0C =
                    fixedX1 *
                            fixedY2 -
                            fixedY1 *
                                    fixedX2;

            edge1A =
                    fixedY2 -
                            fixedY0;

            edge1B =
                    fixedX0 -
                            fixedX2;

            edge1C =
                    fixedX2 *
                            fixedY0 -
                            fixedY2 *
                                    fixedX0;

            edge2A =
                    fixedY0 -
                            fixedY1;

            edge2B =
                    fixedX1 -
                            fixedX0;

            edge2C =
                    fixedX0 *
                            fixedY1 -
                            fixedY0 *
                                    fixedX1;

            area =
                    -area;
        }

        final long minimumFixedX =
                Math.min(
                        fixedX0,
                        Math.min(
                                fixedX1,
                                fixedX2
                        )
                );

        final long maximumFixedX =
                Math.max(
                        fixedX0,
                        Math.max(
                                fixedX1,
                                fixedX2
                        )
                );

        final long minimumFixedY =
                Math.min(
                        fixedY0,
                        Math.min(
                                fixedY1,
                                fixedY2
                        )
                );

        final long maximumFixedY =
                Math.max(
                        fixedY0,
                        Math.max(
                                fixedY1,
                                fixedY2
                        )
                );

        final int minimumX =
                Math.max(
                        0,
                        fastFloor(
                                minimumFixedX /
                                        (double)
                                                RenderSettings
                                                        .SUBPIXEL_SCALE
                        )
                );

        final int maximumX =
                Math.min(
                        size - 1,
                        fastCeil(
                                maximumFixedX /
                                        (double)
                                                RenderSettings
                                                        .SUBPIXEL_SCALE
                        )
                );

        final int minimumY =
                Math.max(
                        0,
                        fastFloor(
                                minimumFixedY /
                                        (double)
                                                RenderSettings
                                                        .SUBPIXEL_SCALE
                        )
                );

        final int maximumY =
                Math.min(
                        size - 1,
                        fastCeil(
                                maximumFixedY /
                                        (double)
                                                RenderSettings
                                                        .SUBPIXEL_SCALE
                        )
                );

        if (
                minimumX > maximumX ||
                        minimumY > maximumY
        ) {
            return;
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

        long edge0Row =
                edge0A *
                        sampleX +
                        edge0B *
                                sampleY +
                        edge0C;

        long edge1Row =
                edge1A *
                        sampleX +
                        edge1B *
                                sampleY +
                        edge1C;

        long edge2Row =
                edge2A *
                        sampleX +
                        edge2B *
                                sampleY +
                        edge2C;

        final double inverseArea =
                1.0 /
                        area;

        double depthRow =
                (
                        edge0Row *
                                depth0 +
                                edge1Row *
                                        depth1 +
                                edge2Row *
                                        depth2
                ) *
                        inverseArea;

        final double depthStepX =
                (
                        edge0StepX *
                                depth0 +
                                edge1StepX *
                                        depth1 +
                                edge2StepX *
                                        depth2
                ) *
                        inverseArea;

        final double depthStepY =
                (
                        edge0StepY *
                                depth0 +
                                edge1StepY *
                                        depth1 +
                                edge2StepY *
                                        depth2
                ) *
                        inverseArea;

        if (keepMaximum) {
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

                double depthValue =
                        depthRow;

                int index =
                        y *
                                size +
                                minimumX;

                for (
                        int x = minimumX;
                        x <= maximumX;
                        x++,
                                index++
                ) {
                    if (
                            (
                                    edge0 |
                                            edge1 |
                                            edge2
                            ) >= 0L
                    ) {
                        final float value =
                                (float) depthValue;

                        if (value > depth[index]) {
                            depth[index] =
                                    value;
                        }
                    }

                    edge0 +=
                            edge0StepX;

                    edge1 +=
                            edge1StepX;

                    edge2 +=
                            edge2StepX;

                    depthValue +=
                            depthStepX;
                }

                edge0Row +=
                        edge0StepY;

                edge1Row +=
                        edge1StepY;

                edge2Row +=
                        edge2StepY;

                depthRow +=
                        depthStepY;
            }

            return;
        }

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

            double depthValue =
                    depthRow;

            int index =
                    y *
                            size +
                            minimumX;

            for (
                    int x = minimumX;
                    x <= maximumX;
                    x++,
                            index++
            ) {
                if (
                        (
                                edge0 |
                                        edge1 |
                                        edge2
                        ) >= 0L
                ) {
                    final float value =
                            (float) depthValue;

                    if (value < depth[index]) {
                        depth[index] =
                                value;
                    }
                }

                edge0 +=
                        edge0StepX;

                edge1 +=
                        edge1StepX;

                edge2 +=
                        edge2StepX;

                depthValue +=
                        depthStepX;
            }

            edge0Row +=
                    edge0StepY;

            edge1Row +=
                    edge1StepY;

            edge2Row +=
                    edge2StepY;

            depthRow +=
                    depthStepY;
        }
    }

    private double sampleDirectional(
            double worldX,
            double worldY,
            double worldZ,
            double normalX,
            double normalY,
            double normalZ,
            double bias
    ) {
        final double relativeX =
                worldX -
                        centerX;

        final double relativeY =
                worldY -
                        centerY;

        final double relativeZ =
                worldZ -
                        centerZ;

        final double receiverX =
                relativeX *
                        rightX +
                        relativeY *
                                rightY +
                        relativeZ *
                                rightZ;

        final double receiverY =
                relativeX *
                        upX +
                        relativeY *
                                upY +
                        relativeZ *
                                upZ;

        final double receiverDepth =
                relativeX *
                        forwardX +
                        relativeY *
                                forwardY +
                        relativeZ *
                                forwardZ;

        if (
                receiverX < -halfSpan ||
                        receiverX > halfSpan ||
                        receiverY < -halfSpan ||
                        receiverY > halfSpan ||
                        receiverDepth < -maximumDistance ||
                        receiverDepth > maximumDistance
        ) {
            return 1.0;
        }

        final double scale =
                directionalMapScale;

        final double mapX =
                clampMapCoordinate(
                        (
                                receiverX +
                                        halfSpan
                        ) *
                                scale
                );

        final double mapY =
                clampMapCoordinate(
                        (
                                halfSpan -
                                        receiverY
                        ) *
                                scale
                );

        final double localNormalRight =
                normalX *
                        rightX +
                        normalY *
                                rightY +
                        normalZ *
                                rightZ;

        final double localNormalUp =
                normalX *
                        upX +
                        normalY *
                                upY +
                        normalZ *
                                upZ;

        final double localNormalForward =
                normalX *
                        forwardX +
                        normalY *
                                forwardY +
                        normalZ *
                                forwardZ;

        // Radius-0 is the production default – fully unrolled bilinear.
        if (RenderSettings.SHADOW_PCF_RADIUS == 0) {
            return percentageCloserDirectionalBilinear(
                    depths[0],
                    mapX,
                    mapY,
                    receiverX,
                    receiverY,
                    receiverDepth,
                    bias,
                    localNormalRight,
                    localNormalUp,
                    localNormalForward
            );
        }

        return percentageCloserDirectional(
                depths[0],
                mapX,
                mapY,
                receiverX,
                receiverY,
                receiverDepth,
                bias,
                localNormalRight,
                localNormalUp,
                localNormalForward
        );
    }

    private double samplePerspective(
            int face,
            double localForwardX,
            double localForwardY,
            double localForwardZ,
            double localRightX,
            double localRightY,
            double localRightZ,
            double localUpX,
            double localUpY,
            double localUpZ,
            double tangent,
            double worldX,
            double worldY,
            double worldZ,
            double normalX,
            double normalY,
            double normalZ,
            double bias
    ) {
        final double relativeX =
                worldX -
                        originX;

        final double relativeY =
                worldY -
                        originY;

        final double relativeZ =
                worldZ -
                        originZ;

        final double receiverX =
                relativeX *
                        localRightX +
                        relativeY *
                                localRightY +
                        relativeZ *
                                localRightZ;

        final double receiverY =
                relativeX *
                        localUpX +
                        relativeY *
                                localUpY +
                        relativeZ *
                                localUpZ;

        final double receiverDepth =
                relativeX *
                        localForwardX +
                        relativeY *
                                localForwardY +
                        relativeZ *
                                localForwardZ;

        if (
                receiverDepth <=
                        RenderSettings.SHADOW_NEAR ||
                        receiverDepth >
                                maximumDistance
        ) {
            return 1.0;
        }

        final double inverseProjectedDepth =
                1.0 /
                        (
                                receiverDepth *
                                        tangent
                        );

        final double normalisedX =
                receiverX *
                        inverseProjectedDepth;

        final double normalisedY =
                receiverY *
                        inverseProjectedDepth;

        if (
                normalisedX < -1.0 ||
                        normalisedX > 1.0 ||
                        normalisedY < -1.0 ||
                        normalisedY > 1.0
        ) {
            return 1.0;
        }

        final double scale =
                perspectiveMapScale;

        final double mapX =
                clampMapCoordinate(
                        (
                                normalisedX +
                                        1.0
                        ) *
                                scale
                );

        final double mapY =
                clampMapCoordinate(
                        (
                                1.0 -
                                        normalisedY
                        ) *
                                scale
                );

        final double localNormalX =
                normalX *
                        localRightX +
                        normalY *
                                localRightY +
                        normalZ *
                                localRightZ;

        final double localNormalY =
                normalX *
                        localUpX +
                        normalY *
                                localUpY +
                        normalZ *
                                localUpZ;

        final double localNormalZ =
                normalX *
                        localForwardX +
                        normalY *
                                localForwardY +
                        normalZ *
                                localForwardZ;

        if (RenderSettings.SHADOW_PCF_RADIUS == 0) {
            return percentageCloserPerspectiveBilinear(
                    depths[face],
                    mapX,
                    mapY,
                    receiverX,
                    receiverY,
                    receiverDepth,
                    bias,
                    localNormalX,
                    localNormalY,
                    localNormalZ,
                    tangent
            );
        }

        return percentageCloserPerspective(
                depths[face],
                mapX,
                mapY,
                receiverX,
                receiverY,
                receiverDepth,
                bias,
                localNormalX,
                localNormalY,
                localNormalZ,
                tangent
        );
    }

    /** Fully unrolled 2×2 bilinear PCF – zero loop overhead. */
    private double percentageCloserDirectionalBilinear(
            float[] depth,
            double mapX,
            double mapY,
            double receiverX,
            double receiverY,
            double receiverDepth,
            double bias,
            double normalRight,
            double normalUp,
            double normalForward
    ) {
        final double textureX =
                mapX -
                        0.5;

        final double textureY =
                mapY -
                        0.5;

        final int baseX =
                fastFloor(
                        textureX
                );

        final int baseY =
                fastFloor(
                        textureY
                );

        final double fractionX =
                textureX -
                        baseX;

        final double fractionY =
                textureY -
                        baseY;

        final double worldUnitsPerTexel =
                directionalWorldUnitsPerTexel;

        final boolean correctPlane =
                Math.abs(
                        normalForward
                ) >
                        1.0e-8;

        final double inverseNormalForward =
                correctPlane
                        ? 1.0 /
                        normalForward
                        : 0.0;

        double visibleWeight =
                0.0;

        double totalWeight =
                0.0;

        // ---- (0,0) ----
        {
            final int y =
                    baseY;

            final int x =
                    baseX;

            if (
                    y >= 0 &&
                            y < size &&
                            x >= 0 &&
                            x < size
            ) {
                final double weightY =
                        1.0 -
                                fractionY;

                final double weightX =
                        1.0 -
                                fractionX;

                final double weight =
                        weightX *
                                weightY;

                if (weight > 0.0) {
                    final double sampleReceiverY =
                            halfSpan -
                                    (
                                            y +
                                                    0.5
                                    ) *
                                            worldUnitsPerTexel;

                    final double deltaUp =
                            sampleReceiverY -
                                    receiverY;

                    final double sampleReceiverX =
                            -halfSpan +
                                    (
                                            x +
                                                    0.5
                                    ) *
                                            worldUnitsPerTexel;

                    final double deltaRight =
                            sampleReceiverX -
                                    receiverX;

                    double expectedDepth =
                            receiverDepth;

                    if (correctPlane) {
                        final double corrected =
                                receiverDepth -
                                        (
                                                normalRight *
                                                        deltaRight +
                                                        normalUp *
                                                                deltaUp
                                        ) *
                                                inverseNormalForward;

                        if (Double.isFinite(corrected)) {
                            expectedDepth =
                                    corrected;
                        }
                    }

                    final float stored =
                            depth[
                                    y *
                                            size +
                                            x
                                    ];

                    if (
                            stored ==
                                    Float.POSITIVE_INFINITY ||
                                    expectedDepth -
                                            bias <=
                                            stored
                    ) {
                        visibleWeight +=
                                weight;
                    }

                    totalWeight +=
                            weight;
                }
            }
        }

        // ---- (1,0) ----
        {
            final int y =
                    baseY;

            final int x =
                    baseX +
                            1;

            if (
                    y >= 0 &&
                            y < size &&
                            x >= 0 &&
                            x < size
            ) {
                final double weightY =
                        1.0 -
                                fractionY;

                final double weightX =
                        fractionX;

                final double weight =
                        weightX *
                                weightY;

                if (weight > 0.0) {
                    final double sampleReceiverY =
                            halfSpan -
                                    (
                                            y +
                                                    0.5
                                    ) *
                                            worldUnitsPerTexel;

                    final double deltaUp =
                            sampleReceiverY -
                                    receiverY;

                    final double sampleReceiverX =
                            -halfSpan +
                                    (
                                            x +
                                                    0.5
                                    ) *
                                            worldUnitsPerTexel;

                    final double deltaRight =
                            sampleReceiverX -
                                    receiverX;

                    double expectedDepth =
                            receiverDepth;

                    if (correctPlane) {
                        final double corrected =
                                receiverDepth -
                                        (
                                                normalRight *
                                                        deltaRight +
                                                        normalUp *
                                                                deltaUp
                                        ) *
                                                inverseNormalForward;

                        if (Double.isFinite(corrected)) {
                            expectedDepth =
                                    corrected;
                        }
                    }

                    final float stored =
                            depth[
                                    y *
                                            size +
                                            x
                                    ];

                    if (
                            stored ==
                                    Float.POSITIVE_INFINITY ||
                                    expectedDepth -
                                            bias <=
                                            stored
                    ) {
                        visibleWeight +=
                                weight;
                    }

                    totalWeight +=
                            weight;
                }
            }
        }

        // ---- (0,1) ----
        {
            final int y =
                    baseY +
                            1;

            final int x =
                    baseX;

            if (
                    y >= 0 &&
                            y < size &&
                            x >= 0 &&
                            x < size
            ) {
                final double weightY =
                        fractionY;

                final double weightX =
                        1.0 -
                                fractionX;

                final double weight =
                        weightX *
                                weightY;

                if (weight > 0.0) {
                    final double sampleReceiverY =
                            halfSpan -
                                    (
                                            y +
                                                    0.5
                                    ) *
                                            worldUnitsPerTexel;

                    final double deltaUp =
                            sampleReceiverY -
                                    receiverY;

                    final double sampleReceiverX =
                            -halfSpan +
                                    (
                                            x +
                                                    0.5
                                    ) *
                                            worldUnitsPerTexel;

                    final double deltaRight =
                            sampleReceiverX -
                                    receiverX;

                    double expectedDepth =
                            receiverDepth;

                    if (correctPlane) {
                        final double corrected =
                                receiverDepth -
                                        (
                                                normalRight *
                                                        deltaRight +
                                                        normalUp *
                                                                deltaUp
                                        ) *
                                                inverseNormalForward;

                        if (Double.isFinite(corrected)) {
                            expectedDepth =
                                    corrected;
                        }
                    }

                    final float stored =
                            depth[
                                    y *
                                            size +
                                            x
                                    ];

                    if (
                            stored ==
                                    Float.POSITIVE_INFINITY ||
                                    expectedDepth -
                                            bias <=
                                            stored
                    ) {
                        visibleWeight +=
                                weight;
                    }

                    totalWeight +=
                            weight;
                }
            }
        }

        // ---- (1,1) ----
        {
            final int y =
                    baseY +
                            1;

            final int x =
                    baseX +
                            1;

            if (
                    y >= 0 &&
                            y < size &&
                            x >= 0 &&
                            x < size
            ) {
                final double weightY =
                        fractionY;

                final double weightX =
                        fractionX;

                final double weight =
                        weightX *
                                weightY;

                if (weight > 0.0) {
                    final double sampleReceiverY =
                            halfSpan -
                                    (
                                            y +
                                                    0.5
                                    ) *
                                            worldUnitsPerTexel;

                    final double deltaUp =
                            sampleReceiverY -
                                    receiverY;

                    final double sampleReceiverX =
                            -halfSpan +
                                    (
                                            x +
                                                    0.5
                                    ) *
                                            worldUnitsPerTexel;

                    final double deltaRight =
                            sampleReceiverX -
                                    receiverX;

                    double expectedDepth =
                            receiverDepth;

                    if (correctPlane) {
                        final double corrected =
                                receiverDepth -
                                        (
                                                normalRight *
                                                        deltaRight +
                                                        normalUp *
                                                                deltaUp
                                        ) *
                                                inverseNormalForward;

                        if (Double.isFinite(corrected)) {
                            expectedDepth =
                                    corrected;
                        }
                    }

                    final float stored =
                            depth[
                                    y *
                                            size +
                                            x
                                    ];

                    if (
                            stored ==
                                    Float.POSITIVE_INFINITY ||
                                    expectedDepth -
                                            bias <=
                                            stored
                    ) {
                        visibleWeight +=
                                weight;
                    }

                    totalWeight +=
                            weight;
                }
            }
        }

        return totalWeight <= 0.0
                ? 1.0
                : visibleWeight /
                totalWeight;
    }

    /** Fully unrolled 2×2 bilinear PCF for perspective maps. */
    private double percentageCloserPerspectiveBilinear(
            float[] depth,
            double mapX,
            double mapY,
            double receiverX,
            double receiverY,
            double receiverDepth,
            double bias,
            double normalX,
            double normalY,
            double normalZ,
            double tangent
    ) {
        final double textureX =
                mapX -
                        0.5;

        final double textureY =
                mapY -
                        0.5;

        final int baseX =
                fastFloor(
                        textureX
                );

        final int baseY =
                fastFloor(
                        textureY
                );

        final double fractionX =
                textureX -
                        baseX;

        final double fractionY =
                textureY -
                        baseY;

        final double rayScale =
                tangent *
                        inversePerspectiveMapScale;

        final double planeNumerator =
                normalX *
                        receiverX +
                        normalY *
                                receiverY +
                        normalZ *
                                receiverDepth;

        double visibleWeight =
                0.0;

        double totalWeight =
                0.0;

        // (0,0)
        {
            final int y =
                    baseY;

            final int x =
                    baseX;

            if (
                    y >= 0 &&
                            y < size &&
                            x >= 0 &&
                            x < size
            ) {
                final double weightY =
                        1.0 -
                                fractionY;

                final double weightX =
                        1.0 -
                                fractionX;

                final double weight =
                        weightX *
                                weightY;

                if (weight > 0.0) {
                    final double rayX =
                            (
                                    x +
                                            0.5
                            ) *
                                    rayScale -
                                    tangent;

                    final double rayY =
                            tangent -
                                    (
                                            y +
                                                    0.5
                                    ) *
                                            rayScale;

                    final double denominator =
                            normalX *
                                    rayX +
                                    normalY *
                                            rayY +
                                    normalZ;

                    double expectedDepth =
                            receiverDepth;

                    if (
                            Math.abs(
                                    denominator
                            ) >
                                    1.0e-8
                    ) {
                        final double corrected =
                                planeNumerator /
                                        denominator;

                        if (
                                corrected >
                                        RenderSettings.SHADOW_NEAR &&
                                        Double.isFinite(corrected)
                        ) {
                            expectedDepth =
                                    corrected;
                        }
                    }

                    final float storedInverseDepth =
                            depth[
                                    y *
                                            size +
                                            x
                                    ];

                    if (
                            storedInverseDepth ==
                                    Float.NEGATIVE_INFINITY ||
                                    (
                                            expectedDepth -
                                                    bias
                                    ) *
                                            storedInverseDepth <=
                                            1.0
                    ) {
                        visibleWeight +=
                                weight;
                    }

                    totalWeight +=
                            weight;
                }
            }
        }

        // (1,0)
        {
            final int y =
                    baseY;

            final int x =
                    baseX +
                            1;

            if (
                    y >= 0 &&
                            y < size &&
                            x >= 0 &&
                            x < size
            ) {
                final double weightY =
                        1.0 -
                                fractionY;

                final double weightX =
                        fractionX;

                final double weight =
                        weightX *
                                weightY;

                if (weight > 0.0) {
                    final double rayX =
                            (
                                    x +
                                            0.5
                            ) *
                                    rayScale -
                                    tangent;

                    final double rayY =
                            tangent -
                                    (
                                            y +
                                                    0.5
                                    ) *
                                            rayScale;

                    final double denominator =
                            normalX *
                                    rayX +
                                    normalY *
                                            rayY +
                                    normalZ;

                    double expectedDepth =
                            receiverDepth;

                    if (
                            Math.abs(
                                    denominator
                            ) >
                                    1.0e-8
                    ) {
                        final double corrected =
                                planeNumerator /
                                        denominator;

                        if (
                                corrected >
                                        RenderSettings.SHADOW_NEAR &&
                                        Double.isFinite(corrected)
                        ) {
                            expectedDepth =
                                    corrected;
                        }
                    }

                    final float storedInverseDepth =
                            depth[
                                    y *
                                            size +
                                            x
                                    ];

                    if (
                            storedInverseDepth ==
                                    Float.NEGATIVE_INFINITY ||
                                    (
                                            expectedDepth -
                                                    bias
                                    ) *
                                            storedInverseDepth <=
                                            1.0
                    ) {
                        visibleWeight +=
                                weight;
                    }

                    totalWeight +=
                            weight;
                }
            }
        }

        // (0,1)
        {
            final int y =
                    baseY +
                            1;

            final int x =
                    baseX;

            if (
                    y >= 0 &&
                            y < size &&
                            x >= 0 &&
                            x < size
            ) {
                final double weightY =
                        fractionY;

                final double weightX =
                        1.0 -
                                fractionX;

                final double weight =
                        weightX *
                                weightY;

                if (weight > 0.0) {
                    final double rayX =
                            (
                                    x +
                                            0.5
                            ) *
                                    rayScale -
                                    tangent;

                    final double rayY =
                            tangent -
                                    (
                                            y +
                                                    0.5
                                    ) *
                                            rayScale;

                    final double denominator =
                            normalX *
                                    rayX +
                                    normalY *
                                            rayY +
                                    normalZ;

                    double expectedDepth =
                            receiverDepth;

                    if (
                            Math.abs(
                                    denominator
                            ) >
                                    1.0e-8
                    ) {
                        final double corrected =
                                planeNumerator /
                                        denominator;

                        if (
                                corrected >
                                        RenderSettings.SHADOW_NEAR &&
                                        Double.isFinite(corrected)
                        ) {
                            expectedDepth =
                                    corrected;
                        }
                    }

                    final float storedInverseDepth =
                            depth[
                                    y *
                                            size +
                                            x
                                    ];

                    if (
                            storedInverseDepth ==
                                    Float.NEGATIVE_INFINITY ||
                                    (
                                            expectedDepth -
                                                    bias
                                    ) *
                                            storedInverseDepth <=
                                            1.0
                    ) {
                        visibleWeight +=
                                weight;
                    }

                    totalWeight +=
                            weight;
                }
            }
        }

        // (1,1)
        {
            final int y =
                    baseY +
                            1;

            final int x =
                    baseX +
                            1;

            if (
                    y >= 0 &&
                            y < size &&
                            x >= 0 &&
                            x < size
            ) {
                final double weightY =
                        fractionY;

                final double weightX =
                        fractionX;

                final double weight =
                        weightX *
                                weightY;

                if (weight > 0.0) {
                    final double rayX =
                            (
                                    x +
                                            0.5
                            ) *
                                    rayScale -
                                    tangent;

                    final double rayY =
                            tangent -
                                    (
                                            y +
                                                    0.5
                                    ) *
                                            rayScale;

                    final double denominator =
                            normalX *
                                    rayX +
                                    normalY *
                                            rayY +
                                    normalZ;

                    double expectedDepth =
                            receiverDepth;

                    if (
                            Math.abs(
                                    denominator
                            ) >
                                    1.0e-8
                    ) {
                        final double corrected =
                                planeNumerator /
                                        denominator;

                        if (
                                corrected >
                                        RenderSettings.SHADOW_NEAR &&
                                        Double.isFinite(corrected)
                        ) {
                            expectedDepth =
                                    corrected;
                        }
                    }

                    final float storedInverseDepth =
                            depth[
                                    y *
                                            size +
                                            x
                                    ];

                    if (
                            storedInverseDepth ==
                                    Float.NEGATIVE_INFINITY ||
                                    (
                                            expectedDepth -
                                                    bias
                                    ) *
                                            storedInverseDepth <=
                                            1.0
                    ) {
                        visibleWeight +=
                                weight;
                    }

                    totalWeight +=
                            weight;
                }
            }
        }

        return totalWeight <= 0.0
                ? 1.0
                : visibleWeight /
                totalWeight;
    }

    private double percentageCloserDirectional(
            float[] depth,
            double mapX,
            double mapY,
            double receiverX,
            double receiverY,
            double receiverDepth,
            double bias,
            double normalRight,
            double normalUp,
            double normalForward
    ) {
        final int radius =
                RenderSettings.SHADOW_PCF_RADIUS;

        final double textureX =
                mapX -
                        0.5;

        final double textureY =
                mapY -
                        0.5;

        final int baseX =
                fastFloor(
                        textureX
                );

        final int baseY =
                fastFloor(
                        textureY
                );

        final double fractionX =
                textureX -
                        baseX;

        final double fractionY =
                textureY -
                        baseY;

        double visibleWeight =
                0.0;

        double totalWeight =
                0.0;

        final double worldUnitsPerTexel =
                directionalWorldUnitsPerTexel;

        final boolean correctPlane =
                Math.abs(
                        normalForward
                ) >
                        1.0e-8;

        final double inverseNormalForward =
                correctPlane
                        ? 1.0 /
                        normalForward
                        : 0.0;

        for (
                int offsetY = -radius;
                offsetY <= radius + 1;
                offsetY++
        ) {
            final int y =
                    baseY +
                            offsetY;

            if (
                    y < 0 ||
                            y >= size
            ) {
                continue;
            }

            final double weightY =
                    filterWeight(
                            offsetY,
                            radius,
                            fractionY
                    );

            if (weightY <= 0.0) {
                continue;
            }

            final int row =
                    y *
                            size;

            final double sampleReceiverY =
                    halfSpan -
                            (
                                    y +
                                            0.5
                            ) *
                                    worldUnitsPerTexel;

            final double deltaUp =
                    sampleReceiverY -
                            receiverY;

            for (
                    int offsetX = -radius;
                    offsetX <= radius + 1;
                    offsetX++
            ) {
                final int x =
                        baseX +
                                offsetX;

                if (
                        x < 0 ||
                                x >= size
                ) {
                    continue;
                }

                final double weightX =
                        filterWeight(
                                offsetX,
                                radius,
                                fractionX
                        );

                final double weight =
                        weightX *
                                weightY;

                if (weight <= 0.0) {
                    continue;
                }

                final double sampleReceiverX =
                        -halfSpan +
                                (
                                        x +
                                                0.5
                                ) *
                                        worldUnitsPerTexel;

                final double deltaRight =
                        sampleReceiverX -
                                receiverX;

                double expectedDepth =
                        receiverDepth;

                if (correctPlane) {
                    final double corrected =
                            receiverDepth -
                                    (
                                            normalRight *
                                                    deltaRight +
                                                    normalUp *
                                                            deltaUp
                                    ) *
                                            inverseNormalForward;

                    if (Double.isFinite(corrected)) {
                        expectedDepth =
                                corrected;
                    }
                }

                final float stored =
                        depth[
                                row +
                                        x
                                ];

                if (
                        stored ==
                                Float.POSITIVE_INFINITY ||
                                expectedDepth -
                                        bias <=
                                        stored
                ) {
                    visibleWeight +=
                            weight;
                }

                totalWeight +=
                        weight;
            }
        }

        return totalWeight <= 0.0
                ? 1.0
                : visibleWeight /
                totalWeight;
    }

    private double percentageCloserPerspective(
            float[] depth,
            double mapX,
            double mapY,
            double receiverX,
            double receiverY,
            double receiverDepth,
            double bias,
            double normalX,
            double normalY,
            double normalZ,
            double tangent
    ) {
        final int radius =
                RenderSettings.SHADOW_PCF_RADIUS;

        final double textureX =
                mapX -
                        0.5;

        final double textureY =
                mapY -
                        0.5;

        final int baseX =
                fastFloor(
                        textureX
                );

        final int baseY =
                fastFloor(
                        textureY
                );

        final double fractionX =
                textureX -
                        baseX;

        final double fractionY =
                textureY -
                        baseY;

        double visibleWeight =
                0.0;

        double totalWeight =
                0.0;

        final double rayScale =
                tangent *
                        inversePerspectiveMapScale;

        final double planeNumerator =
                normalX *
                        receiverX +
                        normalY *
                                receiverY +
                        normalZ *
                                receiverDepth;

        for (
                int offsetY = -radius;
                offsetY <= radius + 1;
                offsetY++
        ) {
            final int y =
                    baseY +
                            offsetY;

            if (
                    y < 0 ||
                            y >= size
            ) {
                continue;
            }

            final double weightY =
                    filterWeight(
                            offsetY,
                            radius,
                            fractionY
                    );

            if (weightY <= 0.0) {
                continue;
            }

            final int row =
                    y *
                            size;

            for (
                    int offsetX = -radius;
                    offsetX <= radius + 1;
                    offsetX++
            ) {
                final int x =
                        baseX +
                                offsetX;

                if (
                        x < 0 ||
                                x >= size
                ) {
                    continue;
                }

                final double weightX =
                        filterWeight(
                                offsetX,
                                radius,
                                fractionX
                        );

                final double weight =
                        weightX *
                                weightY;

                if (weight <= 0.0) {
                    continue;
                }

                final double rayX =
                        (
                                x +
                                        0.5
                        ) *
                                rayScale -
                                tangent;

                final double rayY =
                        tangent -
                                (
                                        y +
                                                0.5
                                ) *
                                        rayScale;

                final double denominator =
                        normalX *
                                rayX +
                                normalY *
                                        rayY +
                                normalZ;

                double expectedDepth =
                        receiverDepth;

                if (
                        Math.abs(
                                denominator
                        ) >
                                1.0e-8
                ) {
                    final double corrected =
                            planeNumerator /
                                    denominator;

                    if (
                            corrected >
                                    RenderSettings.SHADOW_NEAR &&
                                    Double.isFinite(corrected)
                    ) {
                        expectedDepth =
                                corrected;
                    }
                }

                final float storedInverseDepth =
                        depth[
                                row +
                                        x
                                ];

                if (
                        storedInverseDepth ==
                                Float.NEGATIVE_INFINITY ||
                                (
                                        expectedDepth -
                                                bias
                                ) *
                                        storedInverseDepth <=
                                        1.0
                ) {
                    visibleWeight +=
                            weight;
                }

                totalWeight +=
                        weight;
            }
        }

        return totalWeight <= 0.0
                ? 1.0
                : visibleWeight /
                totalWeight;
    }

    private void setForward(
            double x,
            double y,
            double z
    ) {
        final double lengthSquared =
                x *
                        x +
                        y *
                                y +
                        z *
                                z;

        if (
                lengthSquared <= 1.0e-18 ||
                        !Double.isFinite(
                                lengthSquared
                        )
        ) {
            forwardX = 0.0;
            forwardY = -1.0;
            forwardZ = 0.0;
            return;
        }

        final double inverseLength =
                1.0 /
                        Math.sqrt(
                                lengthSquared
                        );

        forwardX =
                x *
                        inverseLength;

        forwardY =
                y *
                        inverseLength;

        forwardZ =
                z *
                        inverseLength;
    }

    private void buildBasis() {
        final boolean nearlyVertical =
                Math.abs(
                        forwardY
                ) >
                        0.95;

        final double helperX =
                nearlyVertical
                        ? 1.0
                        : 0.0;

        final double helperY =
                nearlyVertical
                        ? 0.0
                        : 1.0;

        rightX =
                helperY *
                        forwardZ;

        rightY =
                -helperX *
                        forwardZ;

        rightZ =
                helperX *
                        forwardY -
                        helperY *
                                forwardX;

        final double inverseRightLength =
                1.0 /
                        Math.sqrt(
                                rightX *
                                        rightX +
                                        rightY *
                                                rightY +
                                        rightZ *
                                                rightZ
                        );

        rightX *=
                inverseRightLength;

        rightY *=
                inverseRightLength;

        rightZ *=
                inverseRightLength;

        upX =
                forwardY *
                        rightZ -
                        forwardZ *
                                rightY;

        upY =
                forwardZ *
                        rightX -
                        forwardX *
                                rightZ;

        upZ =
                forwardX *
                        rightY -
                        forwardY *
                                rightX;
    }

    private void snapDirectionalCenter(
            double cameraX,
            double cameraY,
            double cameraZ
    ) {
        final double texelSize =
                directionalWorldUnitsPerTexel;

        final double cameraRight =
                cameraX *
                        rightX +
                        cameraY *
                                rightY +
                        cameraZ *
                                rightZ;

        final double cameraUp =
                cameraX *
                        upX +
                        cameraY *
                                upY +
                        cameraZ *
                                upZ;

        final double snappedRight =
                Math.rint(
                        cameraRight /
                                texelSize
                ) *
                        texelSize;

        final double snappedUp =
                Math.rint(
                        cameraUp /
                                texelSize
                ) *
                        texelSize;

        final double rightOffset =
                snappedRight -
                        cameraRight;

        final double upOffset =
                snappedUp -
                        cameraUp;

        centerX =
                cameraX +
                        rightX *
                                rightOffset +
                        upX *
                                upOffset;

        centerY =
                cameraY +
                        rightY *
                                rightOffset +
                        upY *
                                upOffset;

        centerZ =
                cameraZ +
                        rightZ *
                                rightOffset +
                        upZ *
                                upOffset;
    }

    private void ensureStorage() {
        if (depths.length != faceCount) {
            depths =
                    new float[faceCount][];
        }

        final int required =
                size *
                        size;

        for (
                int face = 0;
                face < faceCount;
                face++
        ) {
            if (
                    depths[face] == null ||
                            depths[face].length != required
            ) {
                depths[face] =
                        new float[required];
            }
        }
    }

    private void clearDirectional() {
        Arrays.fill(
                depths[0],
                Float.POSITIVE_INFINITY
        );
    }

    private void clearPerspective() {
        for (
                int face = 0;
                face < faceCount;
                face++
        ) {
            Arrays.fill(
                    depths[face],
                    Float.NEGATIVE_INFINITY
            );
        }
    }

    private double clampMapCoordinate(
            double coordinate
    ) {
        if (coordinate <= 0.0) {
            return 0.0;
        }

        if (
                coordinate >=
                        maximumMapCoordinate
        ) {
            return maximumMapCoordinate;
        }

        return coordinate;
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

    private static int fastCeil(
            double value
    ) {
        final int integer =
                (int) value;

        return value > integer
                ? integer + 1
                : integer;
    }

    private static double filterWeight(
            int offset,
            int radius,
            double fraction
    ) {
        if (offset == -radius) {
            return 1.0 -
                    fraction;
        }

        if (offset == radius + 1) {
            return fraction;
        }

        return 1.0;
    }

    private static double clamp(
            double value,
            double minimum,
            double maximum
    ) {
        return Math.max(
                minimum,
                Math.min(
                        maximum,
                        value
                )
        );
    }
}