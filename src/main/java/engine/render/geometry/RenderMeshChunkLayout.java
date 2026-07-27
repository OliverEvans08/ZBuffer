package engine.render.geometry;

/**
 * Immutable face-to-chunk mapping produced with a RenderMesh.
 *
 * <p>A byte is used per face because the configured chunk limit is capped at
 * 256. The original index buffer is not duplicated or reordered.</p>
 */
public final class RenderMeshChunkLayout {

    private final RenderMeshChunk[] chunks;
    private final byte[] faceChunks;
    private final int faceCount;
    private final double estimatedSpeedup;
    private final double estimatedSpatialReduction;

    RenderMeshChunkLayout(
            RenderMeshChunk[] chunks,
            byte[] faceChunks,
            int faceCount,
            double estimatedSpeedup,
            double estimatedSpatialReduction
    ) {
        this.chunks = chunks;
        this.faceChunks = faceChunks;
        this.faceCount = faceCount;
        this.estimatedSpeedup = estimatedSpeedup;
        this.estimatedSpatialReduction =
                estimatedSpatialReduction;
    }

    public boolean isPartitioned() {
        return (
                chunks.length > 1 &&
                        faceChunks != null
        );
    }

    public int getChunkCount() {
        return chunks.length;
    }

    public int getFaceCount() {
        return faceCount;
    }

    public RenderMeshChunk getChunk(
            int index
    ) {
        return chunks[index];
    }

    public int getChunkIndexForFace(
            int face
    ) {
        if (
                face < 0 ||
                        face >= faceCount
        ) {
            throw new IndexOutOfBoundsException(
                    "face: " + face
            );
        }

        return faceChunks == null
                ? 0
                : faceChunks[face] & 0xff;
    }

    public double getEstimatedSpeedup() {
        return estimatedSpeedup;
    }

    public double getEstimatedSpatialReduction() {
        return estimatedSpatialReduction;
    }

    byte[] faceChunks() {
        return faceChunks;
    }

    RenderMeshChunk[] chunks() {
        return chunks;
    }

    @Override
    public String toString() {
        return "RenderMeshChunkLayout{" +
                "chunks=" + chunks.length +
                ", faceCount=" + faceCount +
                ", estimatedSpeedup=" +
                estimatedSpeedup +
                ", estimatedSpatialReduction=" +
                estimatedSpatialReduction +
                '}';
    }
}