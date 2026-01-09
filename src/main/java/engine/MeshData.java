package engine;

import java.util.Objects;

/**
 * Engine-native mesh format.
 * - vertices: double[n][3] (x,y,z)
 * - faces:    int[m][3]    (triangle indices into vertices)
 * - uvs:      double[n][2] OR null (u,v per vertex)
 *
 * MeshData is immutable by convention (do not mutate its arrays after creation).
 */
public final class MeshData {

    private final String id;
    private final double[][] vertices;
    private final int[][] faces;
    private final double[][] uvs;

    // Local-space AABB (useful for debugging / validation)
    public final double minX, maxX, minY, maxY, minZ, maxZ;

    public MeshData(String id, double[][] vertices, int[][] faces, double[][] uvs) {
        this.id = (id == null ? "" : id);
        this.vertices = (vertices == null ? new double[0][0] : vertices);
        this.faces = (faces == null ? new int[0][0] : faces);
        this.uvs = uvs;

        double[] aabb = computeAabb(this.vertices);
        this.minX = aabb[0]; this.maxX = aabb[1];
        this.minY = aabb[2]; this.maxY = aabb[3];
        this.minZ = aabb[4]; this.maxZ = aabb[5];
    }

    public String getId() { return id; }

    public double[][] getVertices() { return vertices; }

    public int[][] getFaces() { return faces; }

    public double[][] getUVs() { return uvs; }

    public int vertexCount() { return vertices.length; }

    public int faceCount() { return faces.length; }

    @Override
    public String toString() {
        return "MeshData{id='" + id + "', verts=" + vertexCount() + ", tris=" + faceCount() + "}";
    }

    private static double[] computeAabb(double[][] v) {
        if (v == null || v.length == 0) return new double[]{0,0,0,0,0,0};

        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;

        for (double[] p : v) {
            if (p == null || p.length < 3) continue;
            double x = p[0], y = p[1], z = p[2];
            if (x < minX) minX = x; if (x > maxX) maxX = x;
            if (y < minY) minY = y; if (y > maxY) maxY = y;
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
        }

        if (minX == Double.POSITIVE_INFINITY) return new double[]{0,0,0,0,0,0};
        return new double[]{minX, maxX, minY, maxY, minZ, maxZ};
    }
}
