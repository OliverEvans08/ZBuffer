package engine.assets.mesh;

/**
 * Immutable, binary-ready mesh storage.
 *
 * <p>All data uses flat primitive arrays:</p>
 *
 * <ul>
 *     <li>{@code vertices}: x, y, z per vertex.</li>
 *     <li>{@code uvs}: u, v per vertex, or an empty array.</li>
 *     <li>{@code vertexNormals}: x, y, z per vertex.</li>
 *     <li>{@code indices}: three vertex indices per triangle.</li>
 *     <li>{@code triangleNormals}: x, y, z per triangle.</li>
 *     <li>{@code triangleBounds}: minX, minY, minZ, maxX, maxY, maxZ.</li>
 *     <li>{@code bvhBounds}: the same six-value layout per BVH node.</li>
 * </ul>
 */
public final class MeshAsset {

    public final String meshId;

    public final float[] vertices;
    public final float[] uvs;
    public final float[] vertexNormals;

    public final int[] indices;

    public final float[] triangleNormals;
    public final float[] triangleBounds;

    public final int[] bvhTriangleOrder;
    public final float[] bvhBounds;
    public final int[] bvhLeft;
    public final int[] bvhRight;
    public final int[] bvhFirstTriangle;
    public final int[] bvhTriangleCount;

    public MeshAsset(
            String meshId,
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
    ) {
        this.meshId =
                meshId;
        this.vertices =
                vertices;
        this.uvs =
                uvs;
        this.vertexNormals =
                vertexNormals;
        this.indices =
                indices;
        this.triangleNormals =
                triangleNormals;
        this.triangleBounds =
                triangleBounds;
        this.bvhTriangleOrder =
                bvhTriangleOrder;
        this.bvhBounds =
                bvhBounds;
        this.bvhLeft =
                bvhLeft;
        this.bvhRight =
                bvhRight;
        this.bvhFirstTriangle =
                bvhFirstTriangle;
        this.bvhTriangleCount =
                bvhTriangleCount;
    }

    public int vertexCount() {
        return vertices.length / 3;
    }

    public int triangleCount() {
        return indices.length / 3;
    }

    public int bvhNodeCount() {
        return bvhLeft.length;
    }

    public boolean hasUvs() {
        return uvs.length != 0;
    }

    public boolean isBvhLeaf(
            int nodeIndex
    ) {
        checkBvhNodeIndex(nodeIndex);

        return bvhLeft[nodeIndex] < 0
                && bvhRight[nodeIndex] < 0;
    }

    private void checkBvhNodeIndex(
            int nodeIndex
    ) {
        if (
                nodeIndex < 0
                        || nodeIndex >= bvhNodeCount()
        ) {
            throw new IndexOutOfBoundsException(
                    "BVH node index: "
                            + nodeIndex
            );
        }
    }
}