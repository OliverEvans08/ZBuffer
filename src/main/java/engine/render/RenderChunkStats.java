package engine.render;

/**
 * Last-frame counters for tuning the chunking cost model with profiling data.
 */
public record RenderChunkStats(
        int renderItems,
        int partitionedItems,
        int chunksTested,
        int chunksVisible,
        int facesRejected
) {
    public static RenderChunkStats empty() {
        return new RenderChunkStats(
                0,
                0,
                0,
                0,
                0
        );
    }

    public int chunksRejected() {
        return Math.max(
                0,
                chunksTested -
                        chunksVisible
        );
    }
}