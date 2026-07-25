package engine.assets.mesh;

import engine.assets.collections.FloatArrayBuilder;
import engine.assets.collections.IntArrayBuilder;

/**
 * Builds a median-split binary BVH over precomputed triangle AABBs.
 *
 * <p>Leaf triangle ranges refer to {@code triangleOrder}, not directly to
 * the mesh index array.</p>
 */
public final class MeshBvhBuilder {

    private static final int BVH_LEAF_TRIANGLES = 8;

    private final float[] triangleBounds;
    private final int[] triangleOrder;

    private final FloatArrayBuilder nodeBounds =
            new FloatArrayBuilder(1_024);
    private final IntArrayBuilder left =
            new IntArrayBuilder(256);
    private final IntArrayBuilder right =
            new IntArrayBuilder(256);
    private final IntArrayBuilder firstTriangle =
            new IntArrayBuilder(256);
    private final IntArrayBuilder triangleCount =
            new IntArrayBuilder(256);

    private MeshBvhBuilder(
            float[] triangleBounds,
            int[] triangleOrder
    ) {
        this.triangleBounds =
                triangleBounds;
        this.triangleOrder =
                triangleOrder;
    }

    public static MeshBvhData build(
            int triangleCount,
            float[] triangleBounds
    ) {
        if (triangleCount == 0) {
            return new MeshBvhData(
                    new int[0],
                    new float[0],
                    new int[0],
                    new int[0],
                    new int[0],
                    new int[0]
            );
        }

        final int[] triangleOrder =
                new int[triangleCount];

        for (
                int triangle = 0;
                triangle < triangleCount;
                triangle++
        ) {
            triangleOrder[triangle] =
                    triangle;
        }

        final MeshBvhBuilder builder =
                new MeshBvhBuilder(
                        triangleBounds,
                        triangleOrder
                );

        builder.buildNode(
                0,
                triangleCount
        );

        return builder.toData();
    }

    private int buildNode(
            int start,
            int count
    ) {
        final int node =
                left.size();

        left.add(-1);
        right.add(-1);
        firstTriangle.add(start);
        triangleCount.add(count);

        final float[] bounds =
                rangeBounds(
                        start,
                        count
                );

        for (float value : bounds) {
            nodeBounds.add(value);
        }

        if (count <= BVH_LEAF_TRIANGLES) {
            return node;
        }

        final int axis =
                longestCentroidAxis(
                        start,
                        count
                );

        sortByCentroid(
                start,
                start + count - 1,
                axis
        );

        final int leftCount =
                count >>> 1;
        final int rightCount =
                count - leftCount;

        final int leftNode =
                buildNode(
                        start,
                        leftCount
                );

        final int rightNode =
                buildNode(
                        start + leftCount,
                        rightCount
                );

        left.set(
                node,
                leftNode
        );
        right.set(
                node,
                rightNode
        );

        /*
         * Internal nodes do not directly own triangles.
         */
        triangleCount.set(
                node,
                0
        );

        return node;
    }

    private float[] rangeBounds(
            int start,
            int count
    ) {
        float minX =
                Float.POSITIVE_INFINITY;
        float minY =
                Float.POSITIVE_INFINITY;
        float minZ =
                Float.POSITIVE_INFINITY;

        float maxX =
                Float.NEGATIVE_INFINITY;
        float maxY =
                Float.NEGATIVE_INFINITY;
        float maxZ =
                Float.NEGATIVE_INFINITY;

        final int end =
                start + count;

        for (
                int index = start;
                index < end;
                index++
        ) {
            final int triangle =
                    triangleOrder[index];
            final int offset =
                    triangle * 6;

            minX =
                    Math.min(
                            minX,
                            triangleBounds[offset]
                    );
            minY =
                    Math.min(
                            minY,
                            triangleBounds[offset + 1]
                    );
            minZ =
                    Math.min(
                            minZ,
                            triangleBounds[offset + 2]
                    );

            maxX =
                    Math.max(
                            maxX,
                            triangleBounds[offset + 3]
                    );
            maxY =
                    Math.max(
                            maxY,
                            triangleBounds[offset + 4]
                    );
            maxZ =
                    Math.max(
                            maxZ,
                            triangleBounds[offset + 5]
                    );
        }

        return new float[]{
                minX,
                minY,
                minZ,
                maxX,
                maxY,
                maxZ
        };
    }

    private int longestCentroidAxis(
            int start,
            int count
    ) {
        float minX =
                Float.POSITIVE_INFINITY;
        float minY =
                Float.POSITIVE_INFINITY;
        float minZ =
                Float.POSITIVE_INFINITY;

        float maxX =
                Float.NEGATIVE_INFINITY;
        float maxY =
                Float.NEGATIVE_INFINITY;
        float maxZ =
                Float.NEGATIVE_INFINITY;

        final int end =
                start + count;

        for (
                int index = start;
                index < end;
                index++
        ) {
            final int offset =
                    triangleOrder[index] * 6;

            final float centerX =
                    (
                            triangleBounds[offset]
                                    + triangleBounds[offset + 3]
                    ) * 0.5f;

            final float centerY =
                    (
                            triangleBounds[offset + 1]
                                    + triangleBounds[offset + 4]
                    ) * 0.5f;

            final float centerZ =
                    (
                            triangleBounds[offset + 2]
                                    + triangleBounds[offset + 5]
                    ) * 0.5f;

            minX = Math.min(minX, centerX);
            minY = Math.min(minY, centerY);
            minZ = Math.min(minZ, centerZ);

            maxX = Math.max(maxX, centerX);
            maxY = Math.max(maxY, centerY);
            maxZ = Math.max(maxZ, centerZ);
        }

        final float xExtent =
                maxX - minX;
        final float yExtent =
                maxY - minY;
        final float zExtent =
                maxZ - minZ;

        if (
                xExtent >= yExtent
                        && xExtent >= zExtent
        ) {
            return 0;
        }

        return yExtent >= zExtent
                ? 1
                : 2;
    }

    private void sortByCentroid(
            int low,
            int high,
            int axis
    ) {
        int leftIndex = low;
        int rightIndex = high;

        final float pivot =
                centroid(
                        triangleOrder[
                                (
                                        low + high
                                ) >>> 1
                                ],
                        axis
                );

        while (leftIndex <= rightIndex) {
            while (
                    centroid(
                            triangleOrder[leftIndex],
                            axis
                    ) < pivot
            ) {
                leftIndex++;
            }

            while (
                    centroid(
                            triangleOrder[rightIndex],
                            axis
                    ) > pivot
            ) {
                rightIndex--;
            }

            if (leftIndex <= rightIndex) {
                final int swap =
                        triangleOrder[leftIndex];

                triangleOrder[leftIndex] =
                        triangleOrder[rightIndex];
                triangleOrder[rightIndex] =
                        swap;

                leftIndex++;
                rightIndex--;
            }
        }

        if (low < rightIndex) {
            sortByCentroid(
                    low,
                    rightIndex,
                    axis
            );
        }

        if (leftIndex < high) {
            sortByCentroid(
                    leftIndex,
                    high,
                    axis
            );
        }
    }

    private float centroid(
            int triangle,
            int axis
    ) {
        final int offset =
                triangle * 6 + axis;

        return (
                triangleBounds[offset]
                        + triangleBounds[offset + 3]
        ) * 0.5f;
    }

    private MeshBvhData toData() {
        return new MeshBvhData(
                triangleOrder,
                nodeBounds.toArray(),
                left.toArray(),
                right.toArray(),
                firstTriangle.toArray(),
                triangleCount.toArray()
        );
    }
}