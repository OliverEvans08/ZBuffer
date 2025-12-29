package objects.dynamic;

import objects.GameObject;

public class BoxPart extends GameObject {

    public enum Anchor { CENTER, BOTTOM, TOP }

    private final double[][] verts;
    private static final int[][] FACES = new int[][]{
            {0, 1, 2}, {0, 2, 3},     // front
            {5, 4, 7}, {5, 7, 6},     // back
            {1, 5, 6}, {1, 6, 2},     // right
            {4, 0, 3}, {4, 3, 7},     // left
            {3, 2, 6}, {3, 6, 7},     // top
            {4, 5, 1}, {4, 1, 0}      // bottom
    };

    public BoxPart(double width, double height, double depth, Anchor anchor) {
        double hw = width * 0.5;
        double hd = depth * 0.5;

        double y0, y1;
        if (anchor == Anchor.TOP) {
            y0 = -height;
            y1 = 0.0;
        } else if (anchor == Anchor.CENTER) {
            y0 = -height * 0.5;
            y1 = height * 0.5;
        } else { // BOTTOM
            y0 = 0.0;
            y1 = height;
        }

        verts = new double[][]{
                {-hw, y0,  hd}, { hw, y0,  hd}, { hw, y1,  hd}, { -hw, y1,  hd},
                {-hw, y0, -hd}, { hw, y0, -hd}, { hw, y1, -hd}, { -hw, y1, -hd}
        };

        // Parts should not be treated as world-colliders
        setFull(false);
    }

    @Override public double[][] getVertices() { return verts; }
    @Override public int[][] getEdges() { return new int[0][]; }
    @Override public int[][] getFacesArray() { return FACES; }

    @Override public void update(double delta) { /* no-op */ }
}
