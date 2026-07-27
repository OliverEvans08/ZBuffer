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

    private final IdentityHashMap<GameObject, LightData> emissiveLightCache =
            new IdentityHashMap<>(128);

    private final SceneChunkGrid sceneChunkGrid =
            new SceneChunkGrid();

    public SceneCollector(
            GameEngine gameEngine,
            RenderFrame frame
    ) {
        this.gameEngine = gameEngine;
        this.frame = frame;
    }

    public void buildNearbyRootLists(
            double cameraX,
            double cameraZ,
            double farDistance
    ) {
        frame.nearbyRenderables.clear();
        frame.sceneChunks.clear();
        frame.nearbyChunkCount = 0;
        frame.visibleChunkCount = 0;

        final double padding = 8.0;

        final double minimumX =
                cameraX -
                        farDistance -
                        padding;

        final double maximumX =
                cameraX +
                        farDistance +
                        padding;

        final double minimumZ =
                cameraZ -
                        farDistance -
                        padding;

        final double maximumZ =
                cameraZ +
                        farDistance +
                        padding;

        gameEngine.queryNearbyRenderablesXZ(
                minimumX,
                maximumX,
                minimumZ,
                maximumZ,
                frame.nearbyRenderables
        );

        final GameObject playerBody =
                gameEngine.getPlayerBody();

        sceneChunkGrid.beginFrame();

        final int nearbyCount =
                frame.nearbyRenderables.size();

        for (
                int index = 0;
                index < nearbyCount;
                index++
        ) {
            final GameObject object =
                    frame.nearbyRenderables.get(
                            index
                    );

            if (
                    object == null ||
                            !object.isActive() ||
                            !object.isVisible() ||
                            object == playerBody
            ) {
                continue;
            }

            sceneChunkGrid.add(
                    object,
                    object.getWorldAABB()
            );
        }

        sceneChunkGrid.copyActiveChunksTo(
                frame.sceneChunks
        );

        frame.nearbyChunkCount =
                frame.sceneChunks.size();
    }

    public void gatherLights(
            List<GameObject> roots,
            double cameraX,
            double cameraY,
            double cameraZ,
            double farDistance
    ) {
        frame.lights.clear();

        if (
                roots == null ||
                        roots.isEmpty()
        ) {
            frame.lights.add(
                    FALLBACK_LIGHT
            );

            return;
        }

        final ArrayDeque<GameObject> stack =
                lightTraversalStack;

        stack.clear();

        for (
                int i = roots.size() - 1;
                i >= 0;
                i--
        ) {
            final GameObject root =
                    roots.get(i);

            if (root != null) {
                stack.push(root);
            }
        }

        while (!stack.isEmpty()) {
            final GameObject object =
                    stack.pop();

            if (
                    object == null ||
                            !object.isActive()
            ) {
                continue;
            }

            if (
                    object instanceof
                            objects.lighting.LightObject lightObject
            ) {
                final LightData light =
                        lightObject.getLight();

                if (
                        light != null &&
                                light.strength > 0.0
                ) {
                    if (
                            light.type ==
                                    LightType.DIRECTIONAL
                    ) {
                        frame.lights.add(
                                light
                        );
                    } else {
                        final double deltaX =
                                light.x -
                                        cameraX;

                        final double deltaY =
                                light.y -
                                        cameraY;

                        final double deltaZ =
                                light.z -
                                        cameraZ;

                        final double maximum =
                                farDistance +
                                        Math.max(
                                                0.0,
                                                light.range
                                        );

                        if (
                                deltaX * deltaX +
                                        deltaY * deltaY +
                                        deltaZ * deltaZ <=
                                        maximum * maximum
                        ) {
                            frame.lights.add(
                                    light
                            );
                        }
                    }
                }
            }

            final Material material =
                    object.getMaterial();

            if (
                    material != null &&
                            material.getEmissiveStrength() > 0.0 &&
                            material.getEmissiveRange() > 0.0
            ) {
                final double worldX =
                        object.getWorldX();

                final double worldY =
                        object.getWorldY();

                final double worldZ =
                        object.getWorldZ();

                final double deltaX =
                        worldX -
                                cameraX;

                final double deltaY =
                        worldY -
                                cameraY;

                final double deltaZ =
                        worldZ -
                                cameraZ;

                final double maximum =
                        farDistance +
                                material.getEmissiveRange();

                if (
                        deltaX * deltaX +
                                deltaY * deltaY +
                                deltaZ * deltaZ <=
                                maximum * maximum
                ) {
                    LightData light =
                            emissiveLightCache.get(
                                    object
                            );

                    if (light == null) {
                        light =
                                new LightData();

                        emissiveLightCache.put(
                                object,
                                light
                        );
                    }

                    light.type =
                            LightType.POINT;

                    light.x = worldX;
                    light.y = worldY;
                    light.z = worldZ;

                    light.setColor(
                            material.getEmissiveColor()
                    );

                    light.strength =
                            material.getEmissiveStrength();

                    light.range =
                            material.getEmissiveRange();

                    light.attLinear = 0.0;
                    light.attQuadratic = 1.0;

                    /*
                     * Emissive materials are real point-light sources.
                     * Keep shadow casting enabled so the lighting pipeline
                     * can process them exactly like explicitly placed lights.
                     */
                    light.shadows = true;
                    light.owner = object;

                    frame.lights.add(
                            light
                    );
                }
            }

            final List<GameObject> children =
                    object.getChildren();

            if (
                    children != null &&
                            !children.isEmpty()
            ) {
                for (
                        int i = children.size() - 1;
                        i >= 0;
                        i--
                ) {
                    final GameObject child =
                            children.get(i);

                    if (child != null) {
                        stack.push(child);
                    }
                }
            }
        }

        if (frame.lights.isEmpty()) {
            frame.lights.add(
                    FALLBACK_LIGHT
            );
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

        final AABB bounds =
                object.getWorldAABB();

        if (bounds == null) {
            return false;
        }

        return passesBoundsCull(
                bounds.minX,
                bounds.minY,
                bounds.minZ,
                bounds.maxX,
                bounds.maxY,
                bounds.maxZ,
                cameraX,
                cameraY,
                cameraZ,
                farDistance,
                tangentHalfFieldOfViewX,
                tangentHalfFieldOfViewY
        );
    }

    public boolean passesChunkCull(
            SceneChunk chunk,
            double cameraX,
            double cameraY,
            double cameraZ,
            double farDistance,
            double tangentHalfFieldOfViewX,
            double tangentHalfFieldOfViewY
    ) {
        return (
                chunk != null &&
                        chunk.hasBounds() &&
                        passesBoundsCull(
                                chunk.minimumX,
                                chunk.minimumY,
                                chunk.minimumZ,
                                chunk.maximumX,
                                chunk.maximumY,
                                chunk.maximumZ,
                                cameraX,
                                cameraY,
                                cameraZ,
                                farDistance,
                                tangentHalfFieldOfViewX,
                                tangentHalfFieldOfViewY
                        )
        );
    }

    private boolean passesBoundsCull(
            double minimumX,
            double minimumY,
            double minimumZ,
            double maximumX,
            double maximumY,
            double maximumZ,
            double cameraX,
            double cameraY,
            double cameraZ,
            double farDistance,
            double tangentHalfFieldOfViewX,
            double tangentHalfFieldOfViewY
    ) {
        final double centerX =
                minimumX +
                        0.5 *
                                (maximumX - minimumX);

        final double centerY =
                minimumY +
                        0.5 *
                                (maximumY - minimumY);

        final double centerZ =
                minimumZ +
                        0.5 *
                                (maximumZ - minimumZ);

        final double halfX =
                0.5 *
                        (maximumX - minimumX);

        final double halfY =
                0.5 *
                        (maximumY - minimumY);

        final double halfZ =
                0.5 *
                        (maximumZ - minimumZ);

        final double worldX =
                centerX -
                        cameraX;

        final double worldY =
                centerY -
                        cameraY;

        final double worldZ =
                centerZ -
                        cameraZ;

        if (
                cameraX >= minimumX &&
                        cameraX <= maximumX &&
                        cameraY >= minimumY &&
                        cameraY <= maximumY &&
                        cameraZ >= minimumZ &&
                        cameraZ <= maximumZ
        ) {
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

        final double depthForBounds =
                Math.max(
                        forwardZ,
                        0.0
                );

        final double allowedX =
                depthForBounds *
                        tangentHalfFieldOfViewX +
                        extentX +
                        extentZ *
                                tangentHalfFieldOfViewX;

        if (
                rotatedX < -allowedX ||
                        rotatedX > allowedX
        ) {
            return false;
        }

        final double allowedY =
                depthForBounds *
                        tangentHalfFieldOfViewY +
                        extentY +
                        extentZ *
                                tangentHalfFieldOfViewY;

        return (
                rotatedY >= -allowedY &&
                        rotatedY <= allowedY
        );
    }

    public static boolean containsPoint(
            AABB bounds,
            double x,
            double y,
            double z
    ) {
        return (
                x >= bounds.minX &&
                        x <= bounds.maxX &&
                        y >= bounds.minY &&
                        y <= bounds.maxY &&
                        z >= bounds.minZ &&
                        z <= bounds.maxZ
        );
    }

    public static boolean isDescendantOrSelf(
            GameObject node,
            GameObject root
    ) {
        if (
                node == null ||
                        root == null
        ) {
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
        final LightData light =
                new LightData();

        light.type =
                LightType.DIRECTIONAL;

        light.setDirection(
                new Vector3(
                        -0.35,
                        -0.85,
                        0.40
                )
        );

        light.setColor(
                java.awt.Color.WHITE
        );

        light.strength = 0.6;
        light.shadows = false;
        light.owner = null;

        return light;
    }
}