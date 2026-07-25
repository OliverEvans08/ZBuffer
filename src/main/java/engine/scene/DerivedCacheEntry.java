package engine.scene;

import util.AABB;
import util.Matrix4;

public final class DerivedCacheEntry {

    public static final double[][] EMPTY_TRANSFORMED_VERTICES =
            new double[0][0];

    public final Matrix4 worldMatrix = new Matrix4();

    public double[][][] transformedVertices = new double[2][][];
    public AABB[] bounds = {
            new AABB(0, 0, 0, 0, 0, 0),
            new AABB(0, 0, 0, 0, 0, 0),
    };

    public final double[] worldX = new double[2];
    public final double[] worldY = new double[2];
    public final double[] worldZ = new double[2];

    public int vertexCount = -1;
    public double[][] sourceVertices;

    public long lastWorldVersion = Long.MIN_VALUE;

    public boolean initialized;
    public boolean worldMatrixInitialized;

    public int currentFrame;

    public boolean colliderIndexed;
    public boolean renderableIndexed;
    public boolean indexCellsInitialized;

    public int minimumCellX;
    public int maximumCellX;
    public int minimumCellZ;
    public int maximumCellZ;

    public void ensureVertexBuffers(int required, double[][] source) {
        if (required <= 0) {
            if (vertexCount != 0 || sourceVertices != null) {
                initialized = false;
                lastWorldVersion = Long.MIN_VALUE;
            }

            transformedVertices[0] = EMPTY_TRANSFORMED_VERTICES;
            transformedVertices[1] = EMPTY_TRANSFORMED_VERTICES;
            vertexCount = 0;
            sourceVertices = null;

            return;
        }

        if (vertexCount == required &&
                sourceVertices == source &&
                transformedVertices[0] != null &&
                transformedVertices[1] != null) {
            return;
        }

        transformedVertices[0] = new double[required][3];
        transformedVertices[1] = new double[required][3];
        vertexCount = required;
        sourceVertices = source;
        initialized = false;
        lastWorldVersion = Long.MIN_VALUE;
        currentFrame = 0;
    }

    public void alignToWriteFrame(int writeFrame) {
        if (currentFrame == writeFrame) {
            return;
        }

        final double[][] temporaryVertices = transformedVertices[0];

        transformedVertices[0] = transformedVertices[1];
        transformedVertices[1] = temporaryVertices;

        final AABB temporaryBounds = bounds[0];

        bounds[0] = bounds[1];
        bounds[1] = temporaryBounds;

        swap(worldX);
        swap(worldY);
        swap(worldZ);

        currentFrame = writeFrame;
    }

    private static void swap(double[] values) {
        final double temporary = values[0];

        values[0] = values[1];
        values[1] = temporary;
    }
}