package objects.dynamic;

import objects.GameObject;

import java.util.ArrayList;

public class SpherePart extends GameObject {

    public enum Anchor { CENTER, BOTTOM, TOP }

    private final double[][] verts;
    private final double[][] uvs;
    private final int[][] faces;

    public SpherePart(double radius, int segments, int rings, Anchor anchor) {
        setFull(false);

        radius = Math.max(1e-4, radius);
        segments = Math.max(8, segments);
        rings = Math.max(6, rings);

        double shift;
        if (anchor == Anchor.BOTTOM) shift = radius;
        else if (anchor == Anchor.TOP) shift = -radius;
        else shift = 0.0;

        ArrayList<double[]> v = new ArrayList<>();
        ArrayList<double[]> uv = new ArrayList<>();
        ArrayList<int[]> f = new ArrayList<>();

        // vertices
        for (int r = 0; r <= rings; r++) {
            double vr = r / (double) rings;
            double theta = vr * Math.PI; // 0..pi

            double y = Math.cos(theta) * radius + shift;
            double rr = Math.sin(theta) * radius;

            for (int s = 0; s < segments; s++) {
                double vs = s / (double) segments;
                double phi = vs * (Math.PI * 2.0);

                double x = Math.cos(phi) * rr;
                double z = Math.sin(phi) * rr;

                v.add(new double[]{x, y, z});
                uv.add(new double[]{vs, vr});
            }
        }

        // faces (quad strips)
        for (int r = 0; r < rings; r++) {
            int row0 = r * segments;
            int row1 = (r + 1) * segments;

            for (int s = 0; s < segments; s++) {
                int s1 = (s + 1) % segments;

                int a = row0 + s;
                int b = row1 + s;
                int c = row1 + s1;
                int d = row0 + s1;

                f.add(new int[]{a, b, c});
                f.add(new int[]{a, c, d});
            }
        }

        verts = v.toArray(new double[0][0]);
        uvs   = uv.toArray(new double[0][0]);
        faces = f.toArray(new int[0][0]);
    }

    @Override public double[][] getVertices() { return verts; }
    @Override public double[][] getUVs()      { return uvs; }
    @Override public int[][] getEdges()       { return new int[0][]; }
    @Override public int[][] getFacesArray()  { return faces; }
    @Override public void update(double delta) {}
}
