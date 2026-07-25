package engine.physics.collision;

import static engine.camera.CameraSettings.CAM_WALL_PAD;
import static engine.physics.collision.AabbCollisionQueries.rayBoundsHitDistance;
import static engine.physics.collision.TriangleQueries.TRI_EPS;

public final class Raycast {

    private int[] bvhStack = new int[64];

    public double rayMeshHitDistance(
            MeshCollider collider,
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
        MeshBvh bvh = collider.allBvh;

        if (bvh == null) {
            return Double.POSITIVE_INFINITY;
        }

        double bestHit = maximumDistance;
        boolean hitAnything = false;

        ensureBvhStackCapacity(bvh.nodeCount);

        int stackSize = 0;
        bvhStack[stackSize++] = 0;

        while (stackSize > 0) {
            int node = bvhStack[--stackSize];

            if (
                    bvh.maxX[node] + CAM_WALL_PAD
                            < segmentMinimumX
                            || bvh.minX[node] - CAM_WALL_PAD
                            > segmentMaximumX
                            || bvh.maxY[node] + CAM_WALL_PAD
                            < segmentMinimumY
                            || bvh.minY[node] - CAM_WALL_PAD
                            > segmentMaximumY
                            || bvh.maxZ[node] + CAM_WALL_PAD
                            < segmentMinimumZ
                            || bvh.minZ[node] - CAM_WALL_PAD
                            > segmentMaximumZ
                            || rayBoundsHitDistance(
                            originX,
                            originY,
                            originZ,
                            directionX,
                            directionY,
                            directionZ,
                            bestHit,
                            bvh.minX[node] - CAM_WALL_PAD,
                            bvh.maxX[node] + CAM_WALL_PAD,
                            bvh.minY[node] - CAM_WALL_PAD,
                            bvh.maxY[node] + CAM_WALL_PAD,
                            bvh.minZ[node] - CAM_WALL_PAD,
                            bvh.maxZ[node] + CAM_WALL_PAD
                    ) == Double.POSITIVE_INFINITY
            ) {
                continue;
            }

            int triangleCount = bvh.count[node];

            if (triangleCount != 0) {
                int end = bvh.start[node] + triangleCount;

                for (
                        int orderIndex = bvh.start[node];
                        orderIndex < end;
                        orderIndex++
                ) {
                    int triangle = bvh.order[orderIndex];

                    if (
                            collider.maxX[triangle]
                                    + CAM_WALL_PAD
                                    < segmentMinimumX
                                    || collider.minX[triangle]
                                    - CAM_WALL_PAD
                                    > segmentMaximumX
                                    || collider.maxY[triangle]
                                    + CAM_WALL_PAD
                                    < segmentMinimumY
                                    || collider.minY[triangle]
                                    - CAM_WALL_PAD
                                    > segmentMaximumY
                                    || collider.maxZ[triangle]
                                    + CAM_WALL_PAD
                                    < segmentMinimumZ
                                    || collider.minZ[triangle]
                                    - CAM_WALL_PAD
                                    > segmentMaximumZ
                    ) {
                        continue;
                    }

                    double hit = rayTriangleHitDistance(
                            collider,
                            triangle,
                            originX,
                            originY,
                            originZ,
                            directionX,
                            directionY,
                            directionZ,
                            bestHit
                    );

                    if (hit <= bestHit) {
                        bestHit = hit;
                        hitAnything = true;
                    }
                }
            } else {
                int left = bvh.left[node];
                int right = bvh.right[node];

                double leftHit = rayBoundsHitDistance(
                        originX,
                        originY,
                        originZ,
                        directionX,
                        directionY,
                        directionZ,
                        bestHit,
                        bvh.minX[left] - CAM_WALL_PAD,
                        bvh.maxX[left] + CAM_WALL_PAD,
                        bvh.minY[left] - CAM_WALL_PAD,
                        bvh.maxY[left] + CAM_WALL_PAD,
                        bvh.minZ[left] - CAM_WALL_PAD,
                        bvh.maxZ[left] + CAM_WALL_PAD
                );

                double rightHit = rayBoundsHitDistance(
                        originX,
                        originY,
                        originZ,
                        directionX,
                        directionY,
                        directionZ,
                        bestHit,
                        bvh.minX[right] - CAM_WALL_PAD,
                        bvh.maxX[right] + CAM_WALL_PAD,
                        bvh.minY[right] - CAM_WALL_PAD,
                        bvh.maxY[right] + CAM_WALL_PAD,
                        bvh.minZ[right] - CAM_WALL_PAD,
                        bvh.maxZ[right] + CAM_WALL_PAD
                );

                if (leftHit < rightHit) {
                    if (rightHit != Double.POSITIVE_INFINITY) {
                        bvhStack[stackSize++] = right;
                    }

                    if (leftHit != Double.POSITIVE_INFINITY) {
                        bvhStack[stackSize++] = left;
                    }
                } else {
                    if (leftHit != Double.POSITIVE_INFINITY) {
                        bvhStack[stackSize++] = left;
                    }

                    if (rightHit != Double.POSITIVE_INFINITY) {
                        bvhStack[stackSize++] = right;
                    }
                }
            }
        }

        return hitAnything
                ? bestHit
                : Double.POSITIVE_INFINITY;
    }

    public static double rayTriangleHitDistance(
            MeshCollider collider,
            int triangle,
            double originX,
            double originY,
            double originZ,
            double directionX,
            double directionY,
            double directionZ,
            double maximumDistance
    ) {
        int base = triangle * 9;

        double ax = collider.xyz[base];
        double ay = collider.xyz[base + 1];
        double az = collider.xyz[base + 2];

        double bx = collider.xyz[base + 3];
        double by = collider.xyz[base + 4];
        double bz = collider.xyz[base + 5];

        double cx = collider.xyz[base + 6];
        double cy = collider.xyz[base + 7];
        double cz = collider.xyz[base + 8];

        double edge1X = bx - ax;
        double edge1Y = by - ay;
        double edge1Z = bz - az;

        double edge2X = cx - ax;
        double edge2Y = cy - ay;
        double edge2Z = cz - az;

        double perpendicularX =
                directionY * edge2Z
                        - directionZ * edge2Y;

        double perpendicularY =
                directionZ * edge2X
                        - directionX * edge2Z;

        double perpendicularZ =
                directionX * edge2Y
                        - directionY * edge2X;

        double determinant =
                edge1X * perpendicularX
                        + edge1Y * perpendicularY
                        + edge1Z * perpendicularZ;

        if (Math.abs(determinant) < TRI_EPS) {
            return Double.POSITIVE_INFINITY;
        }

        double inverseDeterminant = 1.0 / determinant;

        double translatedX = originX - ax;
        double translatedY = originY - ay;
        double translatedZ = originZ - az;

        double barycentricU = (
                translatedX * perpendicularX
                        + translatedY * perpendicularY
                        + translatedZ * perpendicularZ
        ) * inverseDeterminant;

        if (
                barycentricU < -TRI_EPS
                        || barycentricU > 1.0 + TRI_EPS
        ) {
            return Double.POSITIVE_INFINITY;
        }

        double crossX =
                translatedY * edge1Z
                        - translatedZ * edge1Y;

        double crossY =
                translatedZ * edge1X
                        - translatedX * edge1Z;

        double crossZ =
                translatedX * edge1Y
                        - translatedY * edge1X;

        double barycentricV = (
                directionX * crossX
                        + directionY * crossY
                        + directionZ * crossZ
        ) * inverseDeterminant;

        if (
                barycentricV < -TRI_EPS
                        || barycentricU + barycentricV
                        > 1.0 + TRI_EPS
        ) {
            return Double.POSITIVE_INFINITY;
        }

        double distance = (
                edge2X * crossX
                        + edge2Y * crossY
                        + edge2Z * crossZ
        ) * inverseDeterminant;

        return distance >= 0.0
                && distance <= maximumDistance
                ? distance
                : Double.POSITIVE_INFINITY;
    }

    private void ensureBvhStackCapacity(int required) {
        if (bvhStack.length >= required) {
            return;
        }

        int newLength = bvhStack.length;

        while (newLength < required) {
            newLength <<= 1;
        }

        bvhStack = new int[newLength];
    }
}