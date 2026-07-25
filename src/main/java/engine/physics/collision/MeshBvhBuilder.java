package engine.physics.collision;

import static engine.physics.collision.TriangleQueries.TRI_EPS;

public final class MeshBvhBuilder {

    private static final int BVH_LEAF_TRIANGLES = 8;

    private MeshBvhBuilder() {
    }

    public static MeshBvh build(
            MeshCollider collider,
            byte requiredFlag
    ) {
        int selectedCount = 0;

        for (
                int triangle = 0;
                triangle < collider.triangleCount;
                triangle++
        ) {
            if (
                    requiredFlag == 0
                            || (
                            collider.flags[triangle]
                                    & requiredFlag
                    ) != 0
            ) {
                selectedCount++;
            }
        }

        if (selectedCount == 0) {
            return null;
        }

        int[] order = new int[selectedCount];
        int position = 0;

        for (
                int triangle = 0;
                triangle < collider.triangleCount;
                triangle++
        ) {
            if (
                    requiredFlag == 0
                            || (
                            collider.flags[triangle]
                                    & requiredFlag
                    ) != 0
            ) {
                order[position++] = triangle;
            }
        }

        MeshBvh bvh = new MeshBvh(order);
        buildNode(bvh, collider, 0, selectedCount);

        return bvh;
    }

    private static int buildNode(
            MeshBvh bvh,
            MeshCollider collider,
            int rangeStart,
            int rangeEnd
    ) {
        int node = bvh.nodeCount++;

        double nodeMinX = Double.POSITIVE_INFINITY;
        double nodeMaxX = Double.NEGATIVE_INFINITY;
        double nodeMinY = Double.POSITIVE_INFINITY;
        double nodeMaxY = Double.NEGATIVE_INFINITY;
        double nodeMinZ = Double.POSITIVE_INFINITY;
        double nodeMaxZ = Double.NEGATIVE_INFINITY;

        double centerMinX = Double.POSITIVE_INFINITY;
        double centerMaxX = Double.NEGATIVE_INFINITY;
        double centerMinY = Double.POSITIVE_INFINITY;
        double centerMaxY = Double.NEGATIVE_INFINITY;
        double centerMinZ = Double.POSITIVE_INFINITY;
        double centerMaxZ = Double.NEGATIVE_INFINITY;

        for (int i = rangeStart; i < rangeEnd; i++) {
            int triangle = bvh.order[i];

            nodeMinX = Math.min(
                    nodeMinX,
                    collider.minX[triangle]
            );

            nodeMaxX = Math.max(
                    nodeMaxX,
                    collider.maxX[triangle]
            );

            nodeMinY = Math.min(
                    nodeMinY,
                    collider.minY[triangle]
            );

            nodeMaxY = Math.max(
                    nodeMaxY,
                    collider.maxY[triangle]
            );

            nodeMinZ = Math.min(
                    nodeMinZ,
                    collider.minZ[triangle]
            );

            nodeMaxZ = Math.max(
                    nodeMaxZ,
                    collider.maxZ[triangle]
            );

            double centerX =
                    collider.minX[triangle]
                            + collider.maxX[triangle];

            double centerY =
                    collider.minY[triangle]
                            + collider.maxY[triangle];

            double centerZ =
                    collider.minZ[triangle]
                            + collider.maxZ[triangle];

            centerMinX = Math.min(centerMinX, centerX);
            centerMaxX = Math.max(centerMaxX, centerX);
            centerMinY = Math.min(centerMinY, centerY);
            centerMaxY = Math.max(centerMaxY, centerY);
            centerMinZ = Math.min(centerMinZ, centerZ);
            centerMaxZ = Math.max(centerMaxZ, centerZ);
        }

        bvh.minX[node] = nodeMinX;
        bvh.maxX[node] = nodeMaxX;
        bvh.minY[node] = nodeMinY;
        bvh.maxY[node] = nodeMaxY;
        bvh.minZ[node] = nodeMinZ;
        bvh.maxZ[node] = nodeMaxZ;

        int triangleCount = rangeEnd - rangeStart;

        if (triangleCount <= BVH_LEAF_TRIANGLES) {
            bvh.start[node] = rangeStart;
            bvh.count[node] = triangleCount;
            bvh.left[node] = -1;
            bvh.right[node] = -1;

            return node;
        }

        double extentX = centerMaxX - centerMinX;
        double extentY = centerMaxY - centerMinY;
        double extentZ = centerMaxZ - centerMinZ;

        int axis =
                extentX >= extentY && extentX >= extentZ
                        ? 0
                        : extentY >= extentZ
                        ? 1
                        : 2;

        double selectedExtent =
                axis == 0
                        ? extentX
                        : axis == 1
                        ? extentY
                        : extentZ;

        if (selectedExtent <= TRI_EPS) {
            bvh.start[node] = rangeStart;
            bvh.count[node] = triangleCount;
            bvh.left[node] = -1;
            bvh.right[node] = -1;

            return node;
        }

        sortByCenter(
                collider,
                bvh.order,
                rangeStart,
                rangeEnd - 1,
                axis
        );

        int middle = (rangeStart + rangeEnd) >>> 1;

        bvh.left[node] = buildNode(
                bvh,
                collider,
                rangeStart,
                middle
        );

        bvh.right[node] = buildNode(
                bvh,
                collider,
                middle,
                rangeEnd
        );

        bvh.start[node] = 0;
        bvh.count[node] = 0;

        return node;
    }

    private static void sortByCenter(
            MeshCollider collider,
            int[] order,
            int low,
            int high,
            int axis
    ) {
        int left = low;
        int right = high;

        double pivot = center(
                collider,
                order[(low + high) >>> 1],
                axis
        );

        while (left <= right) {
            while (
                    center(
                            collider,
                            order[left],
                            axis
                    ) < pivot
            ) {
                left++;
            }

            while (
                    center(
                            collider,
                            order[right],
                            axis
                    ) > pivot
            ) {
                right--;
            }

            if (left <= right) {
                int temporary = order[left];
                order[left] = order[right];
                order[right] = temporary;

                left++;
                right--;
            }
        }

        if (low < right) {
            sortByCenter(
                    collider,
                    order,
                    low,
                    right,
                    axis
            );
        }

        if (left < high) {
            sortByCenter(
                    collider,
                    order,
                    left,
                    high,
                    axis
            );
        }
    }

    private static double center(
            MeshCollider collider,
            int triangle,
            int axis
    ) {
        if (axis == 0) {
            return collider.minX[triangle]
                    + collider.maxX[triangle];
        }

        if (axis == 1) {
            return collider.minY[triangle]
                    + collider.maxY[triangle];
        }

        return collider.minZ[triangle]
                + collider.maxZ[triangle];
    }
}