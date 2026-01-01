package objects.dynamic;

import objects.GameObject;

import java.util.ArrayList;

public class CapsulePart extends GameObject {

    public enum Anchor { CENTER, BOTTOM, TOP }

    private final double[][] verts;
    private final double[][] uvs;
    private final int[][] faces;

    public CapsulePart(double radius, double cylinderHeight,
                       int segments, int hemisphereRings, int cylinderRings,
                       Anchor anchor) {

        setFull(false);

        radius = Math.max(1e-4, radius);
        cylinderHeight = Math.max(1e-4, cylinderHeight);

        segments = Math.max(8, segments);
        hemisphereRings = Math.max(2, hemisphereRings);
        cylinderRings = Math.max(1, cylinderRings);

        double cylHalf = cylinderHeight * 0.5;

        // Build “rings” profile from top pole to bottom pole.
        ArrayList<Ring> rings = new ArrayList<>();

        // Top hemi: k=0..hemi (0 = pole, hemi = equator)
        for (int k = 0; k <= hemisphereRings; k++) {
            double u = k / (double) hemisphereRings;
            double theta = u * (Math.PI * 0.5);
            double y = cylHalf + radius * Math.cos(theta);
            double r = radius * Math.sin(theta);
            rings.add(new Ring(y, r));
        }

        // Cylinder internal rings (exclude ends, already have equator rings)
        for (int k = 1; k < cylinderRings; k++) {
            double u = k / (double) cylinderRings;
            double y = cylHalf - u * cylinderHeight;
            rings.add(new Ring(y, radius));
        }

        // Bottom hemi: k=hem-1..0 (to avoid duplicating equator ring twice)
        for (int k = hemisphereRings - 1; k >= 0; k--) {
            double u = k / (double) hemisphereRings;
            double theta = u * (Math.PI * 0.5);
            double y = -cylHalf - radius * Math.cos(theta);
            double r = radius * Math.sin(theta);
            rings.add(new Ring(y, r));
        }

        // Anchor shift
        double minY = rings.get(rings.size() - 1).y;
        double maxY = rings.get(0).y;
        double shift;
        if (anchor == Anchor.TOP) shift = -maxY;
        else if (anchor == Anchor.BOTTOM) shift = -minY;
        else shift = -(minY + maxY) * 0.5;

        // Build vertices + UVs
        ArrayList<double[]> v = new ArrayList<>();
        ArrayList<double[]> uv = new ArrayList<>();

        int ringCount = rings.size();
        int[] ringStart = new int[ringCount];
        int[] ringSize  = new int[ringCount];

        for (int ri = 0; ri < ringCount; ri++) {
            Ring R = rings.get(ri);
            double y = R.y + shift;
            double rr = R.r;
            ringStart[ri] = v.size();

            double vv = (ringCount <= 1) ? 0.0 : (ri / (double) (ringCount - 1));

            if (rr <= 1e-10) {
                v.add(new double[]{0.0, y, 0.0});
                uv.add(new double[]{0.0, vv});
                ringSize[ri] = 1;
            } else {
                for (int s = 0; s < segments; s++) {
                    double a = (s / (double) segments) * (Math.PI * 2.0);
                    double x = Math.cos(a) * rr;
                    double z = Math.sin(a) * rr;
                    v.add(new double[]{x, y, z});
                    uv.add(new double[]{s / (double) segments, vv});
                }
                ringSize[ri] = segments;
            }
        }

        // Build faces
        ArrayList<int[]> f = new ArrayList<>();
        for (int ri = 0; ri < ringCount - 1; ri++) {
            int a0 = ringStart[ri];
            int aN = ringSize[ri];
            int b0 = ringStart[ri + 1];
            int bN = ringSize[ri + 1];

            if (aN == 1 && bN > 1) {
                // fan from pole (a) to ring (b)
                for (int s = 0; s < bN; s++) {
                    int b1 = b0 + s;
                    int b2 = b0 + ((s + 1) % bN);
                    f.add(new int[]{a0, b1, b2});
                }
            } else if (aN > 1 && bN == 1) {
                // fan to pole (b)
                for (int s = 0; s < aN; s++) {
                    int a1 = a0 + s;
                    int a2 = a0 + ((s + 1) % aN);
                    f.add(new int[]{a1, b0, a2});
                }
            } else if (aN > 1 && bN > 1) {
                // quad strip
                int n = Math.min(aN, bN);
                for (int s = 0; s < n; s++) {
                    int a1 = a0 + s;
                    int a2 = a0 + ((s + 1) % n);
                    int b1 = b0 + s;
                    int b2 = b0 + ((s + 1) % n);
                    f.add(new int[]{a1, b1, b2});
                    f.add(new int[]{a1, b2, a2});
                }
            }
        }

        // Convert
        verts = v.toArray(new double[0][0]);
        uvs   = uv.toArray(new double[0][0]);
        faces = f.toArray(new int[0][0]);
    }

    @Override public double[][] getVertices() { return verts; }
    @Override public double[][] getUVs()      { return uvs; }
    @Override public int[][] getEdges()       { return new int[0][]; }
    @Override public int[][] getFacesArray()  { return faces; }
    @Override public void update(double delta) {}

    private static final class Ring {
        final double y;
        final double r;
        Ring(double y, double r) { this.y = y; this.r = r; }
    }
}
