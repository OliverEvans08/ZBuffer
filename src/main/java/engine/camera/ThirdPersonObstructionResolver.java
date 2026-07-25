package engine.camera;

import engine.GameEngine;
import engine.physics.collision.CollisionGeometryProvider;
import engine.physics.collision.MeshCollider;
import engine.physics.collision.MeshColliderCache;
import engine.physics.collision.Raycast;
import engine.physics.player.CollisionFilter;
import java.util.ArrayList;
import objects.GameObject;
import util.AABB;

import static engine.camera.CameraSettings.CAMERA_RECHECK_INTERVAL;
import static engine.camera.CameraSettings.CAMERA_TINY_MOVE2;
import static engine.camera.CameraSettings.CAM_MIN_DIST;
import static engine.camera.CameraSettings.CAM_WALL_PAD;
import static engine.physics.collision.AabbCollisionQueries.RAY_EPS;
import static engine.physics.collision.AabbCollisionQueries.rayAabbHitDistanceExpanded;

public final class ThirdPersonObstructionResolver {

    private final GameEngine gameEngine;
    private final CollisionFilter collisionFilter;
    private final MeshColliderCache meshColliderCache;
    private final Raycast raycast = new Raycast();

    private final double[] tmpCamera = new double[3];
    private final ArrayList<GameObject> nearby =
            new ArrayList<>(256);

    private GameObject previousCameraBlocker;
    private double previousCameraHitDistance =
            Double.POSITIVE_INFINITY;

    private boolean cameraCollisionCacheValid;
    private double cameraCollisionElapsed =
            Double.POSITIVE_INFINITY;

    private double cachedCameraPivotX;
    private double cachedCameraPivotY;
    private double cachedCameraPivotZ;
    private double cachedCameraDesiredX;
    private double cachedCameraDesiredY;
    private double cachedCameraDesiredZ;

    public ThirdPersonObstructionResolver(
            GameEngine gameEngine,
            CollisionFilter collisionFilter,
            MeshColliderCache meshColliderCache
    ) {
        this.gameEngine = gameEngine;
        this.collisionFilter = collisionFilter;
        this.meshColliderCache = meshColliderCache;
    }

    public double[] resolve(
            Camera camera,
            double pivotX,
            double pivotY,
            double pivotZ,
            double desiredX,
            double desiredY,
            double desiredZ,
            double delta
    ) {
        if (gameEngine == null) {
            setTmpCamera(desiredX, desiredY, desiredZ);
            return tmpCamera;
        }

        cameraCollisionElapsed += Math.max(0.0, delta);

        if (
                cameraCollisionCacheValid
                        && cameraCollisionElapsed
                        < CAMERA_RECHECK_INTERVAL
                        && dist3Squared(
                        pivotX,
                        pivotY,
                        pivotZ,
                        cachedCameraPivotX,
                        cachedCameraPivotY,
                        cachedCameraPivotZ
                ) <= CAMERA_TINY_MOVE2
                        && dist3Squared(
                        desiredX,
                        desiredY,
                        desiredZ,
                        cachedCameraDesiredX,
                        cachedCameraDesiredY,
                        cachedCameraDesiredZ
                ) <= CAMERA_TINY_MOVE2
        ) {
            applyCameraHitDistance(
                    pivotX,
                    pivotY,
                    pivotZ,
                    desiredX,
                    desiredY,
                    desiredZ,
                    previousCameraHitDistance
            );

            return tmpCamera;
        }

        double directionX = desiredX - pivotX;
        double directionY = desiredY - pivotY;
        double directionZ = desiredZ - pivotZ;

        double distance = Math.sqrt(
                directionX * directionX
                        + directionY * directionY
                        + directionZ * directionZ
        );

        if (distance < 1e-12) {
            storeCameraCollisionQuery(
                    pivotX,
                    pivotY,
                    pivotZ,
                    desiredX,
                    desiredY,
                    desiredZ,
                    Double.POSITIVE_INFINITY,
                    null
            );

            setTmpCamera(desiredX, desiredY, desiredZ);
            return tmpCamera;
        }

        double inverseDistance = 1.0 / distance;

        directionX *= inverseDistance;
        directionY *= inverseDistance;
        directionZ *= inverseDistance;

        double segmentMinimumX =
                Math.min(pivotX, desiredX) - CAM_WALL_PAD;

        double segmentMaximumX =
                Math.max(pivotX, desiredX) + CAM_WALL_PAD;

        double segmentMinimumY =
                Math.min(pivotY, desiredY) - CAM_WALL_PAD;

        double segmentMaximumY =
                Math.max(pivotY, desiredY) + CAM_WALL_PAD;

        double segmentMinimumZ =
                Math.min(pivotZ, desiredZ) - CAM_WALL_PAD;

        double segmentMaximumZ =
                Math.max(pivotZ, desiredZ) + CAM_WALL_PAD;

        gameEngine.queryNearbyCollidersXZ(
                segmentMinimumX,
                segmentMaximumX,
                segmentMinimumZ,
                segmentMaximumZ,
                nearby
        );

        int previousBlockerIndex =
                findIdentity(nearby, previousCameraBlocker);

        double bestHit = Double.POSITIVE_INFINITY;
        GameObject bestBlocker = null;

        /*
         * Temporal coherence: test the previous blocker before every
         * other candidate.
         */
        if (previousBlockerIndex >= 0) {
            GameObject object = nearby.get(previousBlockerIndex);

            double hit = cameraObjectHitDistance(
                    camera,
                    object,
                    pivotX,
                    pivotY,
                    pivotZ,
                    directionX,
                    directionY,
                    directionZ,
                    distance,
                    segmentMinimumX,
                    segmentMaximumX,
                    segmentMinimumY,
                    segmentMaximumY,
                    segmentMinimumZ,
                    segmentMaximumZ
            );

            if (hit < bestHit) {
                bestHit = hit;
                bestBlocker = object;
            }
        }

        for (int i = 0, count = nearby.size(); i < count; i++) {
            if (i == previousBlockerIndex) {
                continue;
            }

            GameObject object = nearby.get(i);

            double maximumTestDistance =
                    Math.min(distance, bestHit);

            double hit = cameraObjectHitDistance(
                    camera,
                    object,
                    pivotX,
                    pivotY,
                    pivotZ,
                    directionX,
                    directionY,
                    directionZ,
                    maximumTestDistance,
                    segmentMinimumX,
                    segmentMaximumX,
                    segmentMinimumY,
                    segmentMaximumY,
                    segmentMinimumZ,
                    segmentMaximumZ
            );

            if (hit < bestHit) {
                bestHit = hit;
                bestBlocker = object;
            }
        }

        storeCameraCollisionQuery(
                pivotX,
                pivotY,
                pivotZ,
                desiredX,
                desiredY,
                desiredZ,
                bestHit,
                bestBlocker
        );

        applyCameraHitDistance(
                pivotX,
                pivotY,
                pivotZ,
                desiredX,
                desiredY,
                desiredZ,
                bestHit
        );

        return tmpCamera;
    }

    private double cameraObjectHitDistance(
            Camera camera,
            GameObject object,
            double originX,
            double originY,
            double originZ,
            double directionX,
            double directionY,
            double directionZ,
            double maximumDistance,
            double segmentMinimumX,
            double segmentMaximumX,
            double segmentMinimumY,
            double segmentMaximumY,
            double segmentMinimumZ,
            double segmentMaximumZ
    ) {
        if (
                object == null
                        || collisionFilter.isPlayerOrChild(object)
        ) {
            return Double.POSITIVE_INFINITY;
        }

        AABB collisionBounds =
                collisionFilter.getCollisionBounds(object);

        if (
                collisionBounds == null
                        || collisionFilter
                        .shouldIgnoreColliderForPlayer(
                                camera,
                                object,
                                collisionBounds
                        )
                        || collisionBounds.maxX + CAM_WALL_PAD
                        < segmentMinimumX
                        || collisionBounds.minX - CAM_WALL_PAD
                        > segmentMaximumX
                        || collisionBounds.maxY + CAM_WALL_PAD
                        < segmentMinimumY
                        || collisionBounds.minY - CAM_WALL_PAD
                        > segmentMaximumY
                        || collisionBounds.maxZ + CAM_WALL_PAD
                        < segmentMinimumZ
                        || collisionBounds.minZ - CAM_WALL_PAD
                        > segmentMaximumZ
        ) {
            return Double.POSITIVE_INFINITY;
        }

        if (object instanceof CollisionGeometryProvider) {
            AABB simplified =
                    ((CollisionGeometryProvider) object)
                            .getCameraCollisionAABB();

            if (simplified != null) {
                return rayAabbHitDistanceExpanded(
                        originX,
                        originY,
                        originZ,
                        directionX,
                        directionY,
                        directionZ,
                        maximumDistance,
                        simplified,
                        CAM_WALL_PAD
                );
            }
        }

        MeshCollider collider =
                meshColliderCache.get(object, collisionBounds);

        if (collider != null) {
            return raycast.rayMeshHitDistance(
                    collider,
                    originX,
                    originY,
                    originZ,
                    directionX,
                    directionY,
                    directionZ,
                    maximumDistance,
                    segmentMinimumX,
                    segmentMaximumX,
                    segmentMinimumY,
                    segmentMaximumY,
                    segmentMinimumZ,
                    segmentMaximumZ
            );
        }

        return rayAabbHitDistanceExpanded(
                originX,
                originY,
                originZ,
                directionX,
                directionY,
                directionZ,
                maximumDistance,
                collisionBounds,
                CAM_WALL_PAD
        );
    }

    private void applyCameraHitDistance(
            double pivotX,
            double pivotY,
            double pivotZ,
            double desiredX,
            double desiredY,
            double desiredZ,
            double hitDistance
    ) {
        double directionX = desiredX - pivotX;
        double directionY = desiredY - pivotY;
        double directionZ = desiredZ - pivotZ;

        double distance = Math.sqrt(
                directionX * directionX
                        + directionY * directionY
                        + directionZ * directionZ
        );

        if (
                distance <= RAY_EPS
                        || hitDistance
                        == Double.POSITIVE_INFINITY
        ) {
            setTmpCamera(desiredX, desiredY, desiredZ);
            return;
        }

        double allowedDistance = Math.max(
                CAM_MIN_DIST,
                hitDistance - CAM_WALL_PAD
        );

        if (allowedDistance >= distance) {
            setTmpCamera(desiredX, desiredY, desiredZ);
            return;
        }

        double scale = allowedDistance / distance;

        setTmpCamera(
                pivotX + directionX * scale,
                pivotY + directionY * scale,
                pivotZ + directionZ * scale
        );
    }

    private void storeCameraCollisionQuery(
            double pivotX,
            double pivotY,
            double pivotZ,
            double desiredX,
            double desiredY,
            double desiredZ,
            double hitDistance,
            GameObject blocker
    ) {
        cachedCameraPivotX = pivotX;
        cachedCameraPivotY = pivotY;
        cachedCameraPivotZ = pivotZ;

        cachedCameraDesiredX = desiredX;
        cachedCameraDesiredY = desiredY;
        cachedCameraDesiredZ = desiredZ;

        previousCameraHitDistance = hitDistance;
        previousCameraBlocker = blocker;

        cameraCollisionElapsed = 0.0;
        cameraCollisionCacheValid = true;
    }

    public void invalidate() {
        cameraCollisionCacheValid = false;
        cameraCollisionElapsed = Double.POSITIVE_INFINITY;
        previousCameraBlocker = null;
        previousCameraHitDistance = Double.POSITIVE_INFINITY;
    }

    private void setTmpCamera(
            double valueX,
            double valueY,
            double valueZ
    ) {
        tmpCamera[0] = valueX;
        tmpCamera[1] = valueY;
        tmpCamera[2] = valueZ;
    }

    private static int findIdentity(
            ArrayList<GameObject> objects,
            GameObject target
    ) {
        if (target == null) {
            return -1;
        }

        for (int i = 0, count = objects.size(); i < count; i++) {
            if (objects.get(i) == target) {
                return i;
            }
        }

        return -1;
    }

    private static double dist3Squared(
            double firstX,
            double firstY,
            double firstZ,
            double secondX,
            double secondY,
            double secondZ
    ) {
        double offsetX = firstX - secondX;
        double offsetY = firstY - secondY;
        double offsetZ = firstZ - secondZ;

        return offsetX * offsetX
                + offsetY * offsetY
                + offsetZ * offsetZ;
    }
}