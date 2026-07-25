package engine.assets.binary;

import java.io.IOException;

public final class BinaryMeshValidator {

    private BinaryMeshValidator() {
    }

    public static void validate(
            float[] vertices,
            float[] uvs,
            float[] vertexNormals,
            int[] indices,
            float[] triangleNormals,
            float[] triangleBounds,
            int[] bvhTriangleOrder,
            float[] bvhBounds,
            int[] bvhLeft,
            int[] bvhRight,
            int[] bvhFirstTriangle,
            int[] bvhTriangleCount
    ) throws IOException {
        if (vertices.length % 3 != 0) {
            throw new IOException(
                    "Invalid vertex array length"
            );
        }

        final int vertexCount =
                vertices.length / 3;

        if (
                uvs.length != 0
                        && uvs.length != vertexCount * 2
        ) {
            throw new IOException(
                    "Invalid UV array length"
            );
        }

        if (
                vertexNormals.length
                        != vertexCount * 3
        ) {
            throw new IOException(
                    "Invalid vertex normal array length"
            );
        }

        if (indices.length % 3 != 0) {
            throw new IOException(
                    "Invalid triangle index array length"
            );
        }

        final int triangleCount =
                indices.length / 3;

        if (
                triangleNormals.length
                        != triangleCount * 3
        ) {
            throw new IOException(
                    "Invalid triangle normal array length"
            );
        }

        if (
                triangleBounds.length
                        != triangleCount * 6
        ) {
            throw new IOException(
                    "Invalid triangle bounds array length"
            );
        }

        if (
                bvhTriangleOrder.length
                        != triangleCount
        ) {
            throw new IOException(
                    "Invalid BVH triangle-order length"
            );
        }

        final int nodeCount =
                bvhLeft.length;

        if (
                bvhBounds.length != nodeCount * 6
                        || bvhRight.length != nodeCount
                        || bvhFirstTriangle.length != nodeCount
                        || bvhTriangleCount.length != nodeCount
        ) {
            throw new IOException(
                    "Invalid BVH node array lengths"
            );
        }

        for (int index : indices) {
            if (
                    index < 0
                            || index >= vertexCount
            ) {
                throw new IOException(
                        "Binary mesh contains an invalid vertex index"
                );
            }
        }

        for (int triangle : bvhTriangleOrder) {
            if (
                    triangle < 0
                            || triangle >= triangleCount
            ) {
                throw new IOException(
                        "Binary mesh contains an invalid BVH triangle index"
                );
            }
        }

        for (
                int node = 0;
                node < nodeCount;
                node++
        ) {
            final int left =
                    bvhLeft[node];
            final int right =
                    bvhRight[node];
            final int first =
                    bvhFirstTriangle[node];
            final int count =
                    bvhTriangleCount[node];

            final boolean leaf =
                    left < 0 && right < 0;

            if (leaf) {
                if (
                        first < 0
                                || count < 0
                                || first > triangleCount
                                || count > triangleCount - first
                ) {
                    throw new IOException(
                            "Binary mesh contains an invalid BVH leaf range"
                    );
                }
            } else {
                if (
                        left < 0
                                || right < 0
                                || left >= nodeCount
                                || right >= nodeCount
                                || count != 0
                ) {
                    throw new IOException(
                            "Binary mesh contains an invalid BVH internal node"
                    );
                }
            }
        }
    }
}