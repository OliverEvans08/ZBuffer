package objects.fixed;

import engine.render.Material;
import engine.render.Textures;
import objects.GameObject;
import util.Vector3;

import java.awt.Color;

public class Cube extends GameObject {

    // 24-vertex cube so each face can have clean UVs
    private static final double[][] UNIT_VERTS = new double[][]{
            // +Z (front)
            {-0.5,-0.5, 0.5}, { 0.5,-0.5, 0.5}, { 0.5, 0.5, 0.5}, {-0.5, 0.5, 0.5},
            // -Z (back)
            { 0.5,-0.5,-0.5}, {-0.5,-0.5,-0.5}, {-0.5, 0.5,-0.5}, { 0.5, 0.5,-0.5},
            // +X (right)
            { 0.5,-0.5, 0.5}, { 0.5,-0.5,-0.5}, { 0.5, 0.5,-0.5}, { 0.5, 0.5, 0.5},
            // -X (left)
            {-0.5,-0.5,-0.5}, {-0.5,-0.5, 0.5}, {-0.5, 0.5, 0.5}, {-0.5, 0.5,-0.5},
            // +Y (top)
            {-0.5, 0.5, 0.5}, { 0.5, 0.5, 0.5}, { 0.5, 0.5,-0.5}, {-0.5, 0.5,-0.5},
            // -Y (bottom)
            {-0.5,-0.5,-0.5}, { 0.5,-0.5,-0.5}, { 0.5,-0.5, 0.5}, {-0.5,-0.5, 0.5}
    };

    private static final double[][] UVS = new double[][]{
            {0,0},{1,0},{1,1},{0,1},
            {0,0},{1,0},{1,1},{0,1},
            {0,0},{1,0},{1,1},{0,1},
            {0,0},{1,0},{1,1},{0,1},
            {0,0},{1,0},{1,1},{0,1},
            {0,0},{1,0},{1,1},{0,1},
    };

    private static final int[][] FACES = new int[][]{
            {0,1,2},{0,2,3},
            {4,5,6},{4,6,7},
            {8,9,10},{8,10,11},
            {12,13,14},{12,14,15},
            {16,17,18},{16,18,19},
            {20,21,22},{20,22,23}
    };

    public Cube(double size, double x, double y, double z) {
        this.transform.position = new Vector3(x, y, z);
        this.transform.scale    = new Vector3(size, size, size);

        // procedural texture so it's instantly "not void"
        var tex = Textures.checker(256, 256, 16, 0xFFBDAE6C, 0xFF6B5B2A);
        var mat = Material.textured(tex)
                .setTint(new Color(255, 255, 255))
                .setAmbient(0.22)
                .setDiffuse(0.85);

        setMaterial(mat);
        setColor(Color.YELLOW); // fallback if material removed
    }

    @Override public double[][] getVertices() { return UNIT_VERTS; }
    @Override public double[][] getUVs() { return UVS; }
    @Override public int[][] getEdges() { return new int[0][]; }
    @Override public int[][] getFacesArray() { return FACES; }
    @Override public void update(double delta) { }
}
