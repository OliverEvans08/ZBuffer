package engine.render.lighting;

import engine.GameEngine;
import engine.lighting.LightData;
import engine.lighting.LightType;
import engine.render.RenderFrame;
import engine.render.util.RenderWorkerContext;
import java.util.Arrays;
import java.util.List;
import objects.GameObject;
import util.AABB;

/**
 * Builds one independent depth shadow map per shadow-casting light.
 *
 * Only directional lights generate shadow maps. Point/spot maps (especially
 * 6-face cubemaps) are the dominant remaining CPU cost and are deliberately
 * disabled for performance. Visibility queries for those lights always return 1.0.
 *
 * Shadow maps are immutable while the camera frame is rasterized, so tile
 * workers may sample them concurrently without locks.
 */
public final class ShadowCalculator {
    private final RenderFrame frame;
    private ShadowMap[] maps = new ShadowMap[0];

    public ShadowCalculator(
            GameEngine gameEngine,
            RenderFrame frame
    ) {
        this.frame = frame;
    }

    /**
     * Kept for the mesh compiler's existing API. Visibility is now evaluated
     * per pixel from shadow maps, so object-wide flags are deliberately clear.
     */
    public void calculateObjectShadowFlags(
            RenderWorkerContext context,
            GameObject receiver,
            AABB receiverBounds,
            double cameraX,
            double cameraY,
            double cameraZ,
            double farDistance
    ) {
        // Per-object flags are obsolete; shadows are sampled per pixel.
    }

    public void buildShadowMaps(
            List<GameObject> candidates,
            double cameraX,
            double cameraY,
            double cameraZ,
            double farDistance
    ) {
        ensureMapCapacity(frame.lightCount);

        for (
                int lightIndex = 0;
                lightIndex < frame.lightCount;
                lightIndex++
        ) {
            final LightData light = frame.lightArray[lightIndex];

            // Aggressive: only directional lights cast shadows.
            // Point/spot cubemaps are extremely expensive on CPU.
            if (
                    light == null ||
                            !light.shadows ||
                            light.strength <= 0.0 ||
                            light.type != LightType.DIRECTIONAL
            ) {
                maps[lightIndex] = null;
                continue;
            }

            ShadowMap map = maps[lightIndex];

            if (map == null) {
                map = new ShadowMap();
                maps[lightIndex] = map;
            }

            map.configure(
                    light,
                    cameraX,
                    cameraY,
                    cameraZ,
                    farDistance
            );

            if (candidates == null || candidates.isEmpty()) {
                continue;
            }

            final double lightX = light.x;
            final double lightY = light.y;
            final double lightZ = light.z;
            final double range =
                    light.range > 0.0
                            ? light.range
                            : farDistance;
            final double rangeSquared =
                    range * range;

            for (
                    int objectIndex = 0;
                    objectIndex < candidates.size();
                    objectIndex++
            ) {
                final GameObject object =
                        candidates.get(objectIndex);

                if (
                        object == null ||
                                object == light.owner ||
                                !object.isActive() ||
                                !object.isVisible() ||
                                object.isWireframe()
                ) {
                    continue;
                }

                addObjectTriangles(map, object);
            }
        }
    }

    public double visibility(
            int lightIndex,
            double worldX,
            double worldY,
            double worldZ,
            double normalX,
            double normalY,
            double normalZ,
            double normalDotLight
    ) {
        if (
                lightIndex < 0 ||
                        lightIndex >= frame.lightCount ||
                        lightIndex >= maps.length
        ) {
            return 1.0;
        }

        final LightData light = frame.lightArray[lightIndex];
        final ShadowMap map = maps[lightIndex];

        if (
                light == null ||
                        !light.shadows ||
                        map == null ||
                        light.type != LightType.DIRECTIONAL
        ) {
            return 1.0;
        }

        return map.visibility(
                worldX,
                worldY,
                worldZ,
                normalX,
                normalY,
                normalZ,
                normalDotLight
        );
    }

    private static void addObjectTriangles(
            ShadowMap map,
            GameObject object
    ) {
        final double[][] vertices =
                object.getTransformedVertices();
        final int[][] faces = object.getFacesArray();

        if (
                vertices == null ||
                        vertices.length == 0 ||
                        faces == null ||
                        faces.length == 0
        ) {
            return;
        }

        for (int faceIndex = 0; faceIndex < faces.length; faceIndex++) {
            final int[] face = faces[faceIndex];

            if (
                    face == null ||
                            face.length != 3 ||
                            !validIndex(face[0], vertices.length) ||
                            !validIndex(face[1], vertices.length) ||
                            !validIndex(face[2], vertices.length)
            ) {
                continue;
            }

            final double[] point0 = vertices[face[0]];
            final double[] point1 = vertices[face[1]];
            final double[] point2 = vertices[face[2]];

            if (
                    !validPoint(point0) ||
                            !validPoint(point1) ||
                            !validPoint(point2)
            ) {
                continue;
            }

            map.addTriangle(
                    point0[0],
                    point0[1],
                    point0[2],
                    point1[0],
                    point1[1],
                    point1[2],
                    point2[0],
                    point2[1],
                    point2[2]
            );
        }
    }

    private void ensureMapCapacity(int required) {
        if (maps.length >= required) {
            return;
        }

        int capacity = Math.max(16, maps.length);

        while (capacity < required) {
            if (capacity > Integer.MAX_VALUE / 2) {
                capacity = required;
                break;
            }
            capacity <<= 1;
        }

        maps = Arrays.copyOf(maps, capacity);
    }

    private static boolean validIndex(
            int index,
            int length
    ) {
        return index >= 0 && index < length;
    }

    private static boolean validPoint(double[] point) {
        return point != null &&
                point.length >= 3 &&
                Double.isFinite(point[0]) &&
                Double.isFinite(point[1]) &&
                Double.isFinite(point[2]);
    }

    private static double clamp(
            double value,
            double minimum,
            double maximum
    ) {
        return Math.max(
                minimum,
                Math.min(maximum, value)
        );
    }
}