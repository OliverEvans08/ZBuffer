package engine.assets.mesh;

public record DerivedGeometry(
        float[] vertexNormals,
        float[] triangleNormals,
        float[] triangleBounds
) {
}