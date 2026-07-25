package engine.render.lighting;

import engine.GameEngine;
import engine.lighting.LightData;
import engine.lighting.LightType;
import engine.render.RenderFrame;
import engine.render.RenderSettings;
import engine.render.SceneCollector;
import engine.render.util.RenderWorkerContext;
import java.util.ArrayList;
import objects.GameObject;
import util.AABB;

public final class ShadowCalculator {
    private final GameEngine gameEngine;
    private final RenderFrame frame;

    public ShadowCalculator(
            GameEngine gameEngine,
            RenderFrame frame
    ) {
        this.gameEngine = gameEngine;
        this.frame = frame;
    }

    public void calculateObjectShadowFlags(
            RenderWorkerContext context,
            GameObject receiver,
            AABB receiverBounds,
            double cameraX,
            double cameraY,
            double cameraZ,
            double farDistance
    ) {
        context.ensureShadowCapacity(frame.lightCount);

        if (
                frame.lightCount == 0 ||
                        receiverBounds == null
        ) {
            return;
        }

        final double receiverX =
                0.5 *
                        (receiverBounds.minX +
                                receiverBounds.maxX);
        final double receiverY =
                0.5 *
                        (receiverBounds.minY +
                                receiverBounds.maxY);
        final double receiverZ =
                0.5 *
                        (receiverBounds.minZ +
                                receiverBounds.maxZ);
        final double cameraDeltaX = receiverX - cameraX;
        final double cameraDeltaY = receiverY - cameraY;
        final double cameraDeltaZ = receiverZ - cameraZ;

        if (
                cameraDeltaX * cameraDeltaX +
                        cameraDeltaY * cameraDeltaY +
                        cameraDeltaZ * cameraDeltaZ >
                        RenderSettings
                                .SHADOW_RECEIVER_MAXIMUM_DISTANCE *
                                RenderSettings
                                        .SHADOW_RECEIVER_MAXIMUM_DISTANCE
        ) {
            return;
        }

        for (
                int lightIndex = 0;
                lightIndex < frame.lightCount;
                lightIndex++
        ) {
            final LightData light =
                    frame.lightArray[lightIndex];

            if (
                    light == null ||
                            !light.shadows ||
                            light.strength <= 0.0
            ) {
                continue;
            }

            double directionX;
            double directionY;
            double directionZ;
            double maximumDistance;

            if (light.type == LightType.DIRECTIONAL) {
                directionX = -light.dx;
                directionY = -light.dy;
                directionZ = -light.dz;
                maximumDistance = Math.min(
                        farDistance,
                        RenderSettings
                                .SHADOW_DIRECTIONAL_MAXIMUM_DISTANCE
                );
            } else {
                final double toLightX =
                        light.x - receiverX;
                final double toLightY =
                        light.y - receiverY;
                final double toLightZ =
                        light.z - receiverZ;
                final double distanceSquared =
                        toLightX * toLightX +
                                toLightY * toLightY +
                                toLightZ * toLightZ;

                if (
                        distanceSquared < 1.0e-18 ||
                                (light.range > 0.0 &&
                                        distanceSquared >
                                                light.range *
                                                        light.range)
                ) {
                    continue;
                }

                final double distance =
                        Math.sqrt(distanceSquared);
                final double inverseDistance =
                        1.0 / distance;
                directionX =
                        toLightX * inverseDistance;
                directionY =
                        toLightY * inverseDistance;
                directionZ =
                        toLightZ * inverseDistance;

                if (light.type == LightType.SPOT) {
                    final double cosineTheta =
                            light.dx * -directionX +
                                    light.dy * -directionY +
                                    light.dz * -directionZ;

                    if (cosineTheta <= light.outerCos) {
                        continue;
                    }
                }

                maximumDistance =
                        distance -
                                RenderSettings.SHADOW_BIAS;
            }

            if (maximumDistance <= 1.0e-6) {
                continue;
            }

            final double originX =
                    receiverX +
                            directionX *
                                    RenderSettings.SHADOW_BIAS;
            final double originY =
                    receiverY +
                            directionY *
                                    RenderSettings.SHADOW_BIAS;
            final double originZ =
                    receiverZ +
                            directionZ *
                                    RenderSettings.SHADOW_BIAS;

            context.shadowedPerLight[lightIndex] =
                    isOccluded(
                            context,
                            originX,
                            originY,
                            originZ,
                            directionX,
                            directionY,
                            directionZ,
                            maximumDistance,
                            receiver,
                            light.owner
                    );
        }
    }

    private boolean isOccluded(
            RenderWorkerContext context,
            double originX,
            double originY,
            double originZ,
            double directionX,
            double directionY,
            double directionZ,
            double maximumDistance,
            GameObject receiver,
            GameObject lightOwner
    ) {
        final double endX =
                originX + directionX * maximumDistance;
        final double endZ =
                originZ + directionZ * maximumDistance;
        final double padding =
                RenderSettings.SHADOW_BIAS * 2.0;
        final ArrayList<GameObject> candidates =
                context.shadowCandidates;

        gameEngine.queryNearbyCollidersXZ(
                Math.min(originX, endX) - padding,
                Math.max(originX, endX) + padding,
                Math.min(originZ, endZ) - padding,
                Math.max(originZ, endZ) + padding,
                candidates
        );

        for (
                int i = 0, count = candidates.size();
                i < count;
                i++
        ) {
            final GameObject object = candidates.get(i);
            final AABB bounds =
                    object == null
                            ? null
                            : object.getWorldAABB();

            if (
                    object == null ||
                            bounds == null ||
                            !object.isActive() ||
                            !object.isVisible() ||
                            !object.isSolid() ||
                            object == receiver ||
                            object == lightOwner ||
                            (receiver != null &&
                                    SceneCollector
                                            .isDescendantOrSelf(
                                                    object,
                                                    receiver
                                            )) ||
                            (lightOwner != null &&
                                    SceneCollector
                                            .isDescendantOrSelf(
                                                    object,
                                                    lightOwner
                                            )) ||
                            SceneCollector.containsPoint(
                                    bounds,
                                    originX,
                                    originY,
                                    originZ
                            )
            ) {
                continue;
            }

            if (
                    rayAabbHitDistance(
                            originX,
                            originY,
                            originZ,
                            directionX,
                            directionY,
                            directionZ,
                            maximumDistance,
                            bounds
                    ) != Double.POSITIVE_INFINITY
            ) {
                return true;
            }
        }

        return false;
    }

    private static double rayAabbHitDistance(
            double originX,
            double originY,
            double originZ,
            double directionX,
            double directionY,
            double directionZ,
            double maximumDistance,
            AABB bounds
    ) {
        double minimumTime = 0.0;
        double maximumTime = maximumDistance;

        if (
                Math.abs(directionX) <
                        RenderSettings.RAY_EPSILON
        ) {
            if (
                    originX < bounds.minX ||
                            originX > bounds.maxX
            ) {
                return Double.POSITIVE_INFINITY;
            }
        } else {
            final double inverse = 1.0 / directionX;
            double first =
                    (bounds.minX - originX) * inverse;
            double second =
                    (bounds.maxX - originX) * inverse;

            if (first > second) {
                final double temporary = first;
                first = second;
                second = temporary;
            }

            minimumTime = Math.max(minimumTime, first);
            maximumTime = Math.min(maximumTime, second);

            if (minimumTime > maximumTime) {
                return Double.POSITIVE_INFINITY;
            }
        }

        if (
                Math.abs(directionY) <
                        RenderSettings.RAY_EPSILON
        ) {
            if (
                    originY < bounds.minY ||
                            originY > bounds.maxY
            ) {
                return Double.POSITIVE_INFINITY;
            }
        } else {
            final double inverse = 1.0 / directionY;
            double first =
                    (bounds.minY - originY) * inverse;
            double second =
                    (bounds.maxY - originY) * inverse;

            if (first > second) {
                final double temporary = first;
                first = second;
                second = temporary;
            }

            minimumTime = Math.max(minimumTime, first);
            maximumTime = Math.min(maximumTime, second);

            if (minimumTime > maximumTime) {
                return Double.POSITIVE_INFINITY;
            }
        }

        if (
                Math.abs(directionZ) <
                        RenderSettings.RAY_EPSILON
        ) {
            if (
                    originZ < bounds.minZ ||
                            originZ > bounds.maxZ
            ) {
                return Double.POSITIVE_INFINITY;
            }
        } else {
            final double inverse = 1.0 / directionZ;
            double first =
                    (bounds.minZ - originZ) * inverse;
            double second =
                    (bounds.maxZ - originZ) * inverse;

            if (first > second) {
                final double temporary = first;
                first = second;
                second = temporary;
            }

            minimumTime = Math.max(minimumTime, first);
            maximumTime = Math.min(maximumTime, second);

            if (minimumTime > maximumTime) {
                return Double.POSITIVE_INFINITY;
            }
        }

        return minimumTime <= 1.0e-6
                ? Double.POSITIVE_INFINITY
                : minimumTime;
    }
}