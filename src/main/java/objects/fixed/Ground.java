package objects.fixed;

import engine.render.Material;
import engine.render.Textures;
import objects.GameObject;
import util.Vector3;

import java.awt.Color;

public class Ground extends GameObject {

    private final double halfSize;
    private final double uvTiling;

    private final double[][] verts;
    private final double[][] uvs;
    private static final int[][] FACES = new int[][]{
            {0, 1, 2},
            {0, 2, 3}
    };

    public Ground(double size, double uvTiling) {
        this.halfSize = size * 0.5;
        this.uvTiling = uvTiling;

        // Flat quad at y=0
        verts = new double[][]{
                {-halfSize, 0.0, -halfSize},
                { halfSize, 0.0, -halfSize},
                { halfSize, 0.0,  halfSize},
                {-halfSize, 0.0,  halfSize}
        };

        uvs = new double[][]{
                {0.0, 0.0},
                {uvTiling, 0.0},
                {uvTiling, uvTiling},
                {0.0, uvTiling}
        };

        setFull(true);

        // nice grid texture (procedural)
        var tex = Textures.grid(512, 512, 32, 0xFF2A2A2A, 0xFF111111);
        var mat = Material.textured(tex)
                .setTint(new Color(220, 220, 220))
                .setAmbient(0.35)
                .setDiffuse(0.65);

        setMaterial(mat);

        this.transform.position = new Vector3(0, 0, 0);
        this.transform.scale = new Vector3(1, 1, 1);
    }

    @Override public double[][] getVertices() { return verts; }
    @Override public double[][] getUVs() { return uvs; }
    @Override public int[][] getEdges() { return new int[0][]; }
    @Override public int[][] getFacesArray() { return FACES; }
    @Override public void update(double delta) {}
}
