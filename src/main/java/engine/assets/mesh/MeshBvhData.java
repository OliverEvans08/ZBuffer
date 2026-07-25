package engine.assets.mesh;

public record MeshBvhData(
        int[] triangleOrder,
        float[] bounds,
        int[] left,
        int[] right,
        int[] firstTriangle,
        int[] triangleCount
) {
}