package objects.fixed;

import engine.render.Material;
import engine.render.Textures;
import objects.GameObject;
import util.Vector3;

import java.awt.Color;
import java.util.concurrent.ThreadLocalRandom;

public class GameCube extends GameObject {

    private boolean hitGround = false;
    private double rx, ry, rz;

    // Same cube mesh as Cube (24 verts + UVs)
    private static final double[][] UNIT_VERTS = new double[][]{
            {-0.5,-0.5, 0.5}, { 0.5,-0.5, 0.5}, { 0.5, 0.5, 0.5}, {-0.5, 0.5, 0.5},
            { 0.5,-0.5,-0.5}, {-0.5,-0.5,-0.5}, {-0.5, 0.5,-0.5}, { 0.5, 0.5,-0.5},
            { 0.5,-0.5, 0.5}, { 0.5,-0.5,-0.5}, { 0.5, 0.5,-0.5}, { 0.5, 0.5, 0.5},
            {-0.5,-0.5,-0.5}, {-0.5,-0.5, 0.5}, {-0.5, 0.5, 0.5}, {-0.5, 0.5,-0.5},
            {-0.5, 0.5, 0.5}, { 0.5, 0.5, 0.5}, { 0.5, 0.5,-0.5}, {-0.5, 0.5,-0.5},
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

    public GameCube() {
        setColor(new Color(255, 220, 90));

        ThreadLocalRandom r = ThreadLocalRandom.current();
        this.rx = r.nextBoolean() ? 0.6 : -0.6;
        this.ry = r.nextBoolean() ? 0.8 : -0.8;
        this.rz = r.nextBoolean() ? 0.4 : -0.4;

        this.transform.position = new Vector3(
                r.nextDouble(-20.0, 20.0),
                r.nextDouble(8.0, 12.0),
                r.nextDouble(6.0, 30.0)
        );
        this.transform.scale = new Vector3(1.0, 1.0, 1.0);

        var tex = Textures.checker(256, 256, 8, 0xFF4A90E2, 0xFF1B2A3A);
        var mat = Material.textured(tex)
                .setTint(Color.WHITE)
                .setAmbient(0.20)
                .setDiffuse(0.90);
        setMaterial(mat);
    }

    @Override public double[][] getVertices() { return UNIT_VERTS; }
    @Override public double[][] getUVs() { return UVS; }
    @Override public int[][] getEdges() { return new int[0][]; }
    @Override public int[][] getFacesArray() { return FACES; }

    @Override
    public void update(double delta) {
        if (transform.position.y <= 0.0) {
            hitGround = true;
        }

        if (hitGround) {
            respawn();
            hitGround = false;
        } else {
            transform.rotation.x += rx * delta;
            transform.rotation.y += ry * delta;
            transform.rotation.z += rz * delta;
            transform.position.y -= 2.0 * delta;
        }
    }

    private void respawn() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        this.transform.position = new Vector3(
                r.nextDouble(-40.0, 40.0),
                r.nextDouble(8.0, 12.0),
                r.nextDouble(12.0, 60.0)
        );
    }
}
