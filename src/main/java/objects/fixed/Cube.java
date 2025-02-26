package objects.fixed;

import objects.GameObject;
import util.Vector3d;

import java.awt.*;
import java.util.Random;

public class Cube extends GameObject {
    public Cube(double size, double x, double y, double z) {
        setColor(Color.YELLOW);
    }

    @Override
    public double[][] getVertices() {
        double halfSize = transform.scale.x / 0.1;

        return new double[][]{
                {halfSize, halfSize, halfSize},
                {-halfSize, halfSize, halfSize},
                {-halfSize, -halfSize, halfSize},
                {halfSize, -halfSize, halfSize},
                {halfSize, halfSize, -halfSize},
                {-halfSize, halfSize, -halfSize},
                {-halfSize, -halfSize, -halfSize},
                {halfSize, -halfSize, -halfSize}
        };
    }

    @Override
    public int[][] getEdges() {
        return new int[][]{
                {0, 1}, {1, 2}, {2, 3}, {3, 0},
                {4, 5}, {5, 6}, {6, 7}, {7, 4},
                {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };
    }

    @Override
    public void update(double delta) {

    }
}
