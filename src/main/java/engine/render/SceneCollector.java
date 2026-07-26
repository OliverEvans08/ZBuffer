package engine.render;

import engine.GameEngine;
import engine.lighting.LightData;
import engine.lighting.LightType;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.List;
import objects.GameObject;
import util.AABB;
import util.Vector3;

public final class SceneCollector {
    private static final LightData FALLBACK_LIGHT =
            createFallbackLight();

    private final GameEngine gameEngine;
    private final RenderFrame frame;
    private final ArrayDeque<GameObject> lightTraversalStack =
            new ArrayDeque<>(256);
    private final IdentityHashMap<GameObject, LightData>
            emissiveLightCache = new IdentityHashMap<>(128);

    public SceneCollector(GameEngine gameEngine, RenderFrame frame) {
        this.gameEngine = gameEngine;
        this.frame = frame;
    }

    public void buildNearbyRootLists(
            double cameraX,
            double cameraZ,
            double farDistance
    ) {
        frame.nearbyRenderables.clear();
        frame.renderRoots.clear();

        final double padding = 8.0;
        final double minimumX = cameraX - farDistance - padding;
        final double maximumX = cameraX + farDistance + padding;
        final double minimumZ = cameraZ - farDistance - padding;
        final double maximumZ = cameraZ + farDistance + padding;

        gameEngine.queryNearbyRenderablesXZ(
                minimumX,
                maximumX,
                minimumZ,
                maximumZ,
                frame.nearbyRenderables
        );

        final boolean firstPerson = gameEngine.isFirstPerson();
        final GameObject playerBody = gameEngine.getPlayerBody();

        if (playerBody != null && !firstPerson) {
            frame.renderRoots.add(playerBody);
        }

        for (GameObject object : frame.nearbyRenderables) {
            if (
                    object == null ||
                            !object.isActive() ||
                            !object.isVisible() ||
                            object == playerBody
            ) {
                continue;
            }
            frame.renderRoots.add(object);
        }
    }

    public void gatherLights(
            List<GameObject> roots,
            double cameraX,
            double cameraY,
            double cameraZ,
            double farDistance
    ) {
        frame.lights.clear();

        if (roots == null || roots.isEmpty()) {
            frame.lights.add(FALLBACK_LIGHT);
            return;
        }

        final ArrayDeque<GameObject> stack = lightTraversalStack;
        stack.clear();

        for (int i = roots.size() - 1; i >= 0; i--) {
            final GameObject root = roots.get(i);
            if (root != null) {
                stack.push(root);
            }
        }

        while (!stack.isEmpty()) {
            final GameObject object = stack.pop();

            if (object == null || !object.isActive()) {
                continue;
            }

            if (
                    object instanceof
                            objects.lighting.LightObject lightObject
            ) {
                final LightData light = lightObject.getLight();

                if (light != null && light.strength > 0.0) {
                    if (light.type == LightType.DIRECTIONAL) {
                        frame.lights.add(light);
                    } else {
                        final double deltaX = light.x - cameraX;
                        final double deltaY = light.y - cameraY;
                        final double deltaZ = light.z - cameraZ;
                        final double maximum =
                                farDistance +
                                        Math.max(0.0, light.range);

                        if (
                                deltaX * deltaX +
                                        deltaY * deltaY +
                                        deltaZ * deltaZ <=
                                        maximum * maximum
                        ) {
                            frame.lights.add(light);
                        }
                    }
                }
            }

            final Material material = object.getMaterial();

            if (
                    material != null &&
                            material.getEmissiveStrength() > 0.0 &&
                            material.getEmissiveRange() > 0.0
            ) {
                final double worldX = object.getWorldX();
                final double worldY = object.getWorldY();
                final double worldZ = object.getWorldZ();
                final double deltaX = worldX - cameraX;
                final double deltaY = worldY - cameraY;
                final double deltaZ = worldZ - cameraZ;
                final double maximum =
                        farDistance + material.getEmissiveRange();

                if (
                        deltaX * deltaX +
                                deltaY * deltaY +
                                deltaZ * deltaZ <=
                                maximum * maximum
                ) {
                    LightData light =
                            emissiveLightCache.get(object);

                    if (light == null) {
                        light = new LightData();
                        emissiveLightCache.put(object, light);
                    }

                    light.type = LightType.POINT;
                    light.x = worldX;
                    light.y = worldY;
                    light.z = worldZ;
                    light.setColor(material.getEmissiveColor());
                    light.strength =
                            material.getEmissiveStrength();
                    light.range = material.getEmissiveRange();
                    light.attLinear = 0.0;
                    light.attQuadratic = 1.0;
                    /*
                     * Aggressive: emissive materials do not cast shadows.
                     * Point-light shadow maps (6 faces) are one of the
                     * largest remaining CPU costs; disabling them here
                     * yields large frame-time savings with only a modest
                     * visual change for most scenes.
                     */
                    light.shadows = false;
                    light.owner = object;
                    frame.lights.add(light);
                }
            }

            final List<GameObject> children = object.getChildren();

            if (children != null && !children.isEmpty()) {
                for (int i = children.size() - 1; i >= 0; i--) {
                    final GameObject child = children.get(i);
                    if (child != null) {
                        stack.push(child);
                    }
                }
            }
        }

        if (frame.lights.isEmpty()) {
            frame.lights.add(FALLBACK_LIGHT);
        }
    }

    public boolean passesObjectCull(
            GameObject object,
            double cameraX,
            double cameraY,
            double cameraZ,
            double farDistance,
            double tangentHalfFieldOfViewX,
            double tangentHalfFieldOfViewY
    ) {
        if (object == null) {
            return false;
        }

        final AABB bounds = object.getWorldAABB();

        if (bounds == null) {
            return false;
        }

        final double centerX =
                0.5 * (bounds.minX + bounds.maxX);
        final double centerY =
                0.5 * (bounds.minY + bounds.maxY);
        final double centerZ =
                0.5 * (bounds.minZ + bounds.maxZ);
        final double halfX =
                0.5 * (bounds.maxX - bounds.minX);
        final double halfY =
                0.5 * (bounds.maxY - bounds.minY);
        final double halfZ =
                0.5 * (bounds.maxZ - bounds.minZ);
        final double worldX = centerX - cameraX;
        final double worldY = centerY - cameraY;
        final double worldZ = centerZ - cameraZ;

        if (containsPoint(bounds, cameraX, cameraY, cameraZ)) {
            return true;
        }

        final double rotatedX =
                worldX * frame.cosineYaw +
                        worldZ * frame.sineYaw;
        final double rotatedZ =
                -worldX * frame.sineYaw +
                        worldZ * frame.cosineYaw;
        final double rotatedY =
                worldY * frame.cosinePitch +
                        rotatedZ * frame.sinePitch;
        final double forwardZ =
                -worldY * frame.sinePitch +
                        rotatedZ * frame.cosinePitch;
        final double extentX =
                frame.absoluteCosineYaw * halfX +
                        frame.absoluteSineYaw * halfZ;
        final double yawExtentZ =
                frame.absoluteSineYaw * halfX +
                        frame.absoluteCosineYaw * halfZ;
        final double extentY =
                frame.absoluteCosinePitch * halfY +
                        frame.absoluteSinePitch * yawExtentZ;
        final double extentZ =
                frame.absoluteSinePitch * halfY +
                        frame.absoluteCosinePitch * yawExtentZ;

        if (
                forwardZ + extentZ <= 0.0 ||
                        forwardZ - extentZ >= farDistance
        ) {
            return false;
        }

        final double depthForBounds = Math.max(forwardZ, 0.0);
        final double maximumX =
                depthForBounds * tangentHalfFieldOfViewX +
                        extentX +
                        extentZ * tangentHalfFieldOfViewX;

        if (rotatedX < -maximumX || rotatedX > maximumX) {
            return false;
        }

        final double maximumY =
                depthForBounds * tangentHalfFieldOfViewY +
                        extentY +
                        extentZ * tangentHalfFieldOfViewY;

        return rotatedY >= -maximumY && rotatedY <= maximumY;
    }

    public static boolean containsPoint(
            AABB bounds,
            double x,
            double y,
            double z
    ) {
        return x >= bounds.minX &&
                x <= bounds.maxX &&
                y >= bounds.minY &&
                y <= bounds.maxY &&
                z >= bounds.minZ &&
                z <= bounds.maxZ;
    }

    public static boolean isDescendantOrSelf(
            GameObject node,
            GameObject root
    ) {
        if (node == null || root == null) {
            return false;
        }

        for (
                GameObject current = node;
                current != null;
                current = current.getParent()
        ) {
            if (current == root) {
                return true;
            }
        }

        return false;
    }

    private static LightData createFallbackLight() {
        final LightData light = new LightData();

        light.type = LightType.DIRECTIONAL;
        light.setDirection(
                new Vector3(-0.35, -0.85, 0.40)
        );
        light.setColor(java.awt.Color.WHITE);
        light.strength = 0.6;
        light.shadows = false;
        light.owner = null;

        return light;
    }
}
