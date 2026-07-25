package engine.physics.collision;

import engine.camera.Camera;
import util.AABB;

import static engine.physics.collision.TriangleQueries.TRI_EPS;
import static engine.physics.collision.TriangleQueries.TRI_EPS2;
import static engine.physics.collision.TriangleQueries.clamp;
import static engine.physics.player.PlayerCapsule.HEIGHT;
import static engine.physics.player.PlayerCapsule.RADIUS;
import static engine.physics.player.PlayerCapsule.RADIUS2;

public final class AabbCollisionQueries {

    public static final double RAY_EPS = 1e-9;
    public static final double VERT_SNAP_EPS = 0.06;

    private AabbCollisionQueries() {
    }

    public static void resolveCapsuleAgainstAabb(
            Camera camera,
            AABB bounds
    ) {
        double closestX =
                clamp(camera.x, bounds.minX, bounds.maxX);

        double closestZ =
                clamp(camera.z, bounds.minZ, bounds.maxZ);

        double offsetX = camera.x - closestX;
        double offsetZ = camera.z - closestZ;
        double distanceSquared =
                offsetX * offsetX + offsetZ * offsetZ;

        if (distanceSquared > TRI_EPS2) {
            if (distanceSquared >= RADIUS2) {
                return;
            }

            double distance = Math.sqrt(distanceSquared);
            double penetration =
                    RADIUS - distance + TRI_EPS;

            camera.x += offsetX * (penetration / distance);
            camera.z += offsetZ * (penetration / distance);
            return;
        }

        double toLeft =
                camera.x - (bounds.minX - RADIUS);

        double toRight =
                bounds.maxX + RADIUS - camera.x;

        double toFront =
                camera.z - (bounds.minZ - RADIUS);

        double toBack =
                bounds.maxZ + RADIUS - camera.z;

        double minimum = Math.min(
                Math.min(toLeft, toRight),
                Math.min(toFront, toBack)
        );

        if (minimum == toLeft) {
            camera.x = bounds.minX - RADIUS - TRI_EPS;
        } else if (minimum == toRight) {
            camera.x = bounds.maxX + RADIUS + TRI_EPS;
        } else if (minimum == toFront) {
            camera.z = bounds.minZ - RADIUS - TRI_EPS;
        } else {
            camera.z = bounds.maxZ + RADIUS + TRI_EPS;
        }
    }

    public static double testAabbFloor(
            double x,
            double z,
            AABB bounds,
            double previousFeet,
            double newFeet
    ) {
        if (!circleOverlapsAabbXZ(x, z, RADIUS, bounds)) {
            return Double.NEGATIVE_INFINITY;
        }

        double top = bounds.maxY;

        return previousFeet >= top - VERT_SNAP_EPS
                && newFeet <= top + VERT_SNAP_EPS
                ? top
                : Double.NEGATIVE_INFINITY;
    }

    public static double testAabbCeiling(
            double x,
            double z,
            AABB bounds,
            double previousHead,
            double newHead
    ) {
        if (!circleOverlapsAabbXZ(x, z, RADIUS, bounds)) {
            return Double.POSITIVE_INFINITY;
        }

        double bottom = bounds.minY;

        return previousHead <= bottom + VERT_SNAP_EPS
                && newHead >= bottom - VERT_SNAP_EPS
                ? bottom
                : Double.POSITIVE_INFINITY;
    }

    public static boolean circleOverlapsAabbXZ(
            double centerX,
            double centerZ,
            double radius,
            AABB bounds
    ) {
        double closestX = clamp(
                centerX,
                bounds.minX,
                bounds.maxX
        );

        double closestZ = clamp(
                centerZ,
                bounds.minZ,
                bounds.maxZ
        );

        double offsetX = centerX - closestX;
        double offsetZ = centerZ - closestZ;

        return offsetX * offsetX + offsetZ * offsetZ
                <= radius * radius;
    }

    public static boolean intersectsPlayerCapsuleAabb(
            Camera camera,
            AABB bounds
    ) {
        return camera.y + HEIGHT > bounds.minY
                && camera.y < bounds.maxY
                && circleOverlapsAabbXZ(
                camera.x,
                camera.z,
                RADIUS,
                bounds
        );
    }

    public static double rayAabbHitDistanceExpanded(
            double originX,
            double originY,
            double originZ,
            double directionX,
            double directionY,
            double directionZ,
            double maximumDistance,
            AABB bounds,
            double padding
    ) {
        return rayBoundsHitDistance(
                originX,
                originY,
                originZ,
                directionX,
                directionY,
                directionZ,
                maximumDistance,
                bounds.minX - padding,
                bounds.maxX + padding,
                bounds.minY - padding,
                bounds.maxY + padding,
                bounds.minZ - padding,
                bounds.maxZ + padding
        );
    }

    public static double rayBoundsHitDistance(
            double originX,
            double originY,
            double originZ,
            double directionX,
            double directionY,
            double directionZ,
            double maximumDistance,
            double minimumX,
            double maximumX,
            double minimumY,
            double maximumY,
            double minimumZ,
            double maximumZ
    ) {
        double minimumTime = 0.0;
        double maximumTime = maximumDistance;

        if (Math.abs(directionX) < RAY_EPS) {
            if (originX < minimumX || originX > maximumX) {
                return Double.POSITIVE_INFINITY;
            }
        } else {
            double inverseDirection = 1.0 / directionX;

            double time1 =
                    (minimumX - originX) * inverseDirection;

            double time2 =
                    (maximumX - originX) * inverseDirection;

            if (time1 > time2) {
                double temporary = time1;
                time1 = time2;
                time2 = temporary;
            }

            minimumTime = Math.max(minimumTime, time1);
            maximumTime = Math.min(maximumTime, time2);

            if (minimumTime > maximumTime) {
                return Double.POSITIVE_INFINITY;
            }
        }

        if (Math.abs(directionY) < RAY_EPS) {
            if (originY < minimumY || originY > maximumY) {
                return Double.POSITIVE_INFINITY;
            }
        } else {
            double inverseDirection = 1.0 / directionY;

            double time1 =
                    (minimumY - originY) * inverseDirection;

            double time2 =
                    (maximumY - originY) * inverseDirection;

            if (time1 > time2) {
                double temporary = time1;
                time1 = time2;
                time2 = temporary;
            }

            minimumTime = Math.max(minimumTime, time1);
            maximumTime = Math.min(maximumTime, time2);

            if (minimumTime > maximumTime) {
                return Double.POSITIVE_INFINITY;
            }
        }

        if (Math.abs(directionZ) < RAY_EPS) {
            if (originZ < minimumZ || originZ > maximumZ) {
                return Double.POSITIVE_INFINITY;
            }
        } else {
            double inverseDirection = 1.0 / directionZ;

            double time1 =
                    (minimumZ - originZ) * inverseDirection;

            double time2 =
                    (maximumZ - originZ) * inverseDirection;

            if (time1 > time2) {
                double temporary = time1;
                time1 = time2;
                time2 = temporary;
            }

            minimumTime = Math.max(minimumTime, time1);
            maximumTime = Math.min(maximumTime, time2);

            if (minimumTime > maximumTime) {
                return Double.POSITIVE_INFINITY;
            }
        }

        return maximumTime < 0.0
                ? Double.POSITIVE_INFINITY
                : minimumTime >= 0.0
                ? minimumTime
                : maximumTime;
    }
}