package engine.physics.collision;

import engine.camera.Camera;

import static engine.physics.collision.AabbCollisionQueries.VERT_SNAP_EPS;
import static engine.physics.collision.MeshCollider.TRI_CEILING;
import static engine.physics.collision.MeshCollider.TRI_FLOOR;
import static engine.physics.collision.TriangleQueries.TRI_EPS;
import static engine.physics.collision.TriangleQueries.TRI_EPS2;
import static engine.physics.collision.TriangleQueries.clamp;
import static engine.physics.collision.TriangleQueries.closestPointOnTriangleXZ;
import static engine.physics.collision.TriangleQueries.pointInTriangleXZ;
import static engine.physics.player.PlayerCapsule.HEIGHT;
import static engine.physics.player.PlayerCapsule.RADIUS;
import static engine.physics.player.PlayerCapsule.RADIUS2;

public final class CapsuleTriangleQueries {

    private static final double CAPSULE_DIAGONAL_SAMPLE = 0.70;
    private static final double INV_SQRT_2 = 0.7071067811865476;

    private CapsuleTriangleQueries() {
    }

    public static void resolveCapsuleAgainstTriangle(
            Camera camera,
            MeshCollider collider,
            int triangle,
            double[] closestXZ
    ) {
        closestPointOnTriangleXZ(
                collider,
                triangle,
                camera.x,
                camera.z,
                closestXZ
        );

        double offsetX = camera.x - closestXZ[0];
        double offsetZ = camera.z - closestXZ[1];
        double distanceSquared =
                offsetX * offsetX + offsetZ * offsetZ;

        if (distanceSquared >= RADIUS2) {
            return;
        }

        double directionX;
        double directionZ;
        double distance;

        if (distanceSquared > TRI_EPS2) {
            distance = Math.sqrt(distanceSquared);
            directionX = offsetX / distance;
            directionZ = offsetZ / distance;
        } else {
            double horizontalNormalLength = Math.sqrt(
                    collider.normalX[triangle]
                            * collider.normalX[triangle]
                            + collider.normalZ[triangle]
                            * collider.normalZ[triangle]
            );

            if (horizontalNormalLength <= TRI_EPS) {
                return;
            }

            directionX =
                    collider.normalX[triangle]
                            / horizontalNormalLength;

            directionZ =
                    collider.normalZ[triangle]
                            / horizontalNormalLength;

            double sampleY = clamp(
                    camera.y + HEIGHT * 0.5,
                    collider.minY[triangle],
                    collider.maxY[triangle]
            );

            double signedDistance =
                    collider.normalX[triangle] * camera.x
                            + collider.normalY[triangle] * sampleY
                            + collider.normalZ[triangle] * camera.z
                            + collider.planeD[triangle];

            if (signedDistance < 0.0) {
                directionX = -directionX;
                directionZ = -directionZ;
            }

            distance = Math.abs(signedDistance);
        }

        double penetration = RADIUS - distance + TRI_EPS;

        if (penetration > 0.0) {
            camera.x += directionX * penetration;
            camera.z += directionZ * penetration;
        }
    }

    /**
     * Sweeps the spherical end of the player's vertical capsule against a
     * preclassified floor or ceiling surface.
     */
    public static double capsuleSurfaceContact(
            MeshCollider collider,
            int triangle,
            boolean floor,
            double previousEnd,
            double newEnd,
            double x,
            double z,
            double[] closestXZ
    ) {
        byte requiredFlag = floor ? TRI_FLOOR : TRI_CEILING;

        if ((collider.flags[triangle] & requiredFlag) == 0) {
            return floor
                    ? Double.NEGATIVE_INFINITY
                    : Double.POSITIVE_INFINITY;
        }

        double central = surfaceContactAt(
                collider,
                triangle,
                x,
                z,
                0.0,
                floor
        );

        if (isSweptSurfaceHit(
                central,
                previousEnd,
                newEnd,
                floor
        )) {
            return central;
        }

        /*
         * The four support samples are only evaluated when the central
         * sample misses. They are support points on the capsule rather
         * than five independent vertical rays.
         */
        double diagonal =
                RADIUS
                        * CAPSULE_DIAGONAL_SAMPLE
                        * INV_SQRT_2;

        double diagonalDistance2 =
                diagonal * diagonal * 2.0;

        double best = floor
                ? Double.NEGATIVE_INFINITY
                : Double.POSITIVE_INFINITY;

        best = selectSurfaceSupport(
                best,
                surfaceContactAt(
                        collider,
                        triangle,
                        x - diagonal,
                        z - diagonal,
                        diagonalDistance2,
                        floor
                ),
                floor
        );

        best = selectSurfaceSupport(
                best,
                surfaceContactAt(
                        collider,
                        triangle,
                        x + diagonal,
                        z - diagonal,
                        diagonalDistance2,
                        floor
                ),
                floor
        );

        best = selectSurfaceSupport(
                best,
                surfaceContactAt(
                        collider,
                        triangle,
                        x - diagonal,
                        z + diagonal,
                        diagonalDistance2,
                        floor
                ),
                floor
        );

        best = selectSurfaceSupport(
                best,
                surfaceContactAt(
                        collider,
                        triangle,
                        x + diagonal,
                        z + diagonal,
                        diagonalDistance2,
                        floor
                ),
                floor
        );

        closestPointOnTriangleXZ(
                collider,
                triangle,
                x,
                z,
                closestXZ
        );

        double closestOffsetX = closestXZ[0] - x;
        double closestOffsetZ = closestXZ[1] - z;

        double closestDistance2 =
                closestOffsetX * closestOffsetX
                        + closestOffsetZ * closestOffsetZ;

        if (closestDistance2 <= RADIUS2 + TRI_EPS) {
            best = selectSurfaceSupport(
                    best,
                    surfaceContactAt(
                            collider,
                            triangle,
                            closestXZ[0],
                            closestXZ[1],
                            Math.min(RADIUS2, closestDistance2),
                            floor
                    ),
                    floor
            );
        }

        if (
                isSweptSurfaceHit(
                        best,
                        previousEnd,
                        newEnd,
                        floor
                )
        ) {
            return best;
        }

        return floor
                ? Double.NEGATIVE_INFINITY
                : Double.POSITIVE_INFINITY;
    }

    private static double surfaceContactAt(
            MeshCollider collider,
            int triangle,
            double sampleX,
            double sampleZ,
            double horizontalDistance2,
            boolean floor
    ) {
        if (
                sampleX < collider.minX[triangle] - TRI_EPS
                        || sampleX
                        > collider.maxX[triangle] + TRI_EPS
                        || sampleZ
                        < collider.minZ[triangle] - TRI_EPS
                        || sampleZ
                        > collider.maxZ[triangle] + TRI_EPS
                        || !pointInTriangleXZ(
                        collider,
                        triangle,
                        sampleX,
                        sampleZ
                )
        ) {
            return floor
                    ? Double.NEGATIVE_INFINITY
                    : Double.POSITIVE_INFINITY;
        }

        double normalY = collider.normalY[triangle];

        if (Math.abs(normalY) <= TRI_EPS) {
            return floor
                    ? Double.NEGATIVE_INFINITY
                    : Double.POSITIVE_INFINITY;
        }

        double planeY = -(
                collider.normalX[triangle] * sampleX
                        + collider.normalZ[triangle] * sampleZ
                        + collider.planeD[triangle]
        ) / normalY;

        double sphereRise = Math.sqrt(
                Math.max(
                        0.0,
                        RADIUS2 - horizontalDistance2
                )
        );

        return floor
                ? planeY + sphereRise - RADIUS
                : planeY - sphereRise + RADIUS;
    }

    private static double selectSurfaceSupport(
            double current,
            double candidate,
            boolean floor
    ) {
        return floor
                ? Math.max(current, candidate)
                : Math.min(current, candidate);
    }

    private static boolean isSweptSurfaceHit(
            double candidate,
            double previousEnd,
            double newEnd,
            boolean floor
    ) {
        if (!Double.isFinite(candidate)) {
            return false;
        }

        if (floor) {
            return candidate <= previousEnd + VERT_SNAP_EPS
                    && candidate >= newEnd - VERT_SNAP_EPS;
        }

        return candidate >= previousEnd - VERT_SNAP_EPS
                && candidate <= newEnd + VERT_SNAP_EPS;
    }
}