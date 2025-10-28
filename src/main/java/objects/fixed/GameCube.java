package objects.fixed;

import objects.GameObject;
import util.Vector3;

import java.awt.*;
import java.util.concurrent.ThreadLocalRandom;

public class GameCube extends GameObject {

    private boolean hitGround = false;

    private double rx, ry, rz; // rotation speeds

    public GameCube() {
        setColor(new Color(255, 220, 90));

        ThreadLocalRandom r = ThreadLocalRandom.current();
        this.rx = r.nextBoolean() ? 0.6 : -0.6;
        this.ry = r.nextBoolean() ? 0.8 : -0.8;
        this.rz = r.nextBoolean() ? 0.4 : -0.4;

        // spawn above ground
        this.transform.position = new Vector3(
                r.nextDouble(-20.0, 20.0),
                r.nextDouble(8.0, 12.0),
                r.nextDouble(6.0, 30.0)
        );
        this.transform.scale = new Vector3(1.0, 1.0, 1.0);
    }

    @Override
    public double[][] getVertices() { return CubeVertices.UNIT_VERTS; }

    @Override
    public int[][] getEdges() { return CubeVertices.EDGES; }

    @Override
    public int[][] getFacesArray() { return CubeVertices.FACES; }

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
            transform.position.y -= 2.0 * delta; // fall
        }
    }

    private void respawn() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        this.transform.position = new Vector3(
                r.nextDouble(-20.0, 20.0),
                r.nextDouble(8.0, 12.0),
                r.nextDouble(6.0, 30.0)
        );
    }

    /**
     * Share unit cube geometry with Cube to avoid duplication.
     */
    private static final class CubeVertices {
        static final double[][] UNIT_VERTS = new double[][]{
                { -0.5, -0.5,  0.5 },
                {  0.5, -0.5,  0.5 },
                {  0.5,  0.5,  0.5 },
                { -0.5,  0.5,  0.5 },
                { -0.5, -0.5, -0.5 },
                {  0.5, -0.5, -0.5 },
                {  0.5,  0.5, -0.5 },
                { -0.5,  0.5, -0.5 }
        };
        static final int[][] EDGES = new int[][]{
                {0,1},{1,2},{2,3},{3,0},
                {4,5},{5,6},{6,7},{7,4},
                {0,4},{1,5},{2,6},{3,7}
        };
        static final int[][] FACES = new int[][]{
                {0,1,2},{0,2,3},
                {5,4,7},{5,7,6},
                {1,5,6},{1,6,2},
                {4,0,3},{4,3,7},
                {3,2,6},{3,6,7},
                {4,5,1},{4,1,0}
        };
    }
}
