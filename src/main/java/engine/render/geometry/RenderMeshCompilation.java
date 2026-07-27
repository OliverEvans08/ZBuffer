package engine.render.geometry;

/**
 * Render mesh plus the spatial partition built from the same immutable source
 * geometry.
 */
public record RenderMeshCompilation(
        RenderMesh mesh,
        RenderMeshChunkLayout chunkLayout
) {
}