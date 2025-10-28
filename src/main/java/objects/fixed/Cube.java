package objects.fixed;

import objects.GameObject;
import util.Vector3;

import java.awt.*;

public class Cube extends GameObject {
    // Unit cube geometry (centered at origin); scale via Transform.scale
    private static final double[][] UNIT_VERTS = new double[][]{
            { -0.5, -0.5,  0.5 },
            {  0.5, -0.5,  0.5 },
            {  0.5,  0.5,  0.5 },
            { -0.5,  0.5,  0.5 },
            { -0.5, -0.5, -0.5 },
            {  0.5, -0.5, -0.5 },
            {  0.5,  0.5, -0.5 },
            { -0.5,  0.5, -0.5 }
    };

    private static final int[][] EDGES = new int[][]{
            {0,1},{1,2},{2,3},{3,0},
            {4,5},{5,6},{6,7},{7,4},
            {0,4},{1,5},{2,6},{3,7}
    };

    // Tri faces (two per cube side), CCW winding when looking from outside
    private static final int[][] FACES = new int[][]{
            {0,1,2},{0,2,3}, // +Z
            {5,4,7},{5,7,6}, // -Z
            {1,5,6},{1,6,2}, // +X
            {4,0,3},{4,3,7}, // -X
            {3,2,6},{3,6,7}, // +Y
            {4,5,1},{4,1,0}  // -Y
    };

    public Cube(double size, double x, double y, double z) {
        setColor(Color.YELLOW);
        this.transform.position = new Vector3(x, y, z);
        this.transform.scale    = new Vector3(size, size, size);
    }

    @Override public double[][] getVertices() { return UNIT_VERTS; }
    @Override public int[][] getEdges()       { return EDGES; }
    @Override public int[][] getFacesArray()  { return FACES; } // internal for default impl
    @Override public void update(double delta) { /* static */ }
}
