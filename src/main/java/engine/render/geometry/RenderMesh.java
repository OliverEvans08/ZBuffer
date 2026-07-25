package engine.render.geometry;

public final class RenderMesh {
    public final int vertexCount;
    public final int faceCount;
    public final int[][] sourceFaces;
    public final double[] x;
    public final double[] y;
    public final double[] z;
    public final double[] u;
    public final double[] v;
    public final int[] indices;
    public final int anchor0;
    public final int anchor1;
    public final int anchor2;
    public final int anchor3;
    public final int basisMode;
    public final double[] inverseBasis;

    public RenderMesh(
            int vertexCount,
            int[][] sourceFaces,
            double[] x,
            double[] y,
            double[] z,
            double[] u,
            double[] v,
            int[] indices,
            int anchor0,
            int anchor1,
            int anchor2,
            int anchor3,
            int basisMode,
            double[] inverseBasis
    ) {
        this.vertexCount = vertexCount;
        this.faceCount = indices.length / 3;
        this.sourceFaces = sourceFaces;
        this.x = x;
        this.y = y;
        this.z = z;
        this.u = u;
        this.v = v;
        this.indices = indices;
        this.anchor0 = anchor0;
        this.anchor1 = anchor1;
        this.anchor2 = anchor2;
        this.anchor3 = anchor3;
        this.basisMode = basisMode;
        this.inverseBasis = inverseBasis;
    }

    public boolean matches(int vertexCount, int[][] faces) {
        return this.vertexCount == vertexCount &&
                sourceFaces == faces;
    }
}