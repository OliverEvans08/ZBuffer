package engine.render.geometry;

/**
 * Tight model-space bounds and triangle count for one adaptive mesh chunk.
 */
public final class RenderMeshChunk {

    public final int index;
    public final int faceCount;

    public final double minimumX;
    public final double minimumY;
    public final double minimumZ;
    public final double maximumX;
    public final double maximumY;
    public final double maximumZ;

    final double centerX;
    final double centerY;
    final double centerZ;
    final double halfSizeX;
    final double halfSizeY;
    final double halfSizeZ;

    RenderMeshChunk(
            int index,
            int faceCount,
            double minimumX,
            double minimumY,
            double minimumZ,
            double maximumX,
            double maximumY,
            double maximumZ
    ) {
        this.index = index;
        this.faceCount = faceCount;
        this.minimumX = minimumX;
        this.minimumY = minimumY;
        this.minimumZ = minimumZ;
        this.maximumX = maximumX;
        this.maximumY = maximumY;
        this.maximumZ = maximumZ;

        centerX =
                minimumX +
                        0.5 * (maximumX - minimumX);

        centerY =
                minimumY +
                        0.5 * (maximumY - minimumY);

        centerZ =
                minimumZ +
                        0.5 * (maximumZ - minimumZ);

        halfSizeX =
                0.5 * (maximumX - minimumX);

        halfSizeY =
                0.5 * (maximumY - minimumY);

        halfSizeZ =
                0.5 * (maximumZ - minimumZ);
    }
}