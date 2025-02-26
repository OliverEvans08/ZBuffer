package objects.fixed;

import objects.GameObject;

import java.awt.*;

public class GameCube extends GameObject {

    public boolean hitGround = false;

    double u;
    double v;
    double n;


    public GameCube() {
        setColor(Color.YELLOW);

        this.u = Math.round(Math.random() * 1) == 0 ? -0.03 : 0.03;
        this.v = Math.round(Math.random() * 1) == 0 ? -0.03 : 0.03;
        this.n = Math.round(Math.random() * 1) == 0 ? -0.03 : 0.03;

        this.transform.position.y = 500 + Math.round(Math.random() * 10);

        double rx = Math.round(Math.random() * 400 - Math.random() * 400);
        double rz = Math.round(Math.random() * 400 - Math.random() * 400);
        this.transform.position.x = rx;
        this.transform.position.z = rz;
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
        if (transform.position.y <= 0) {
            hitGround = true;
        }

        if (hitGround) {
            respawn();
            hitGround = false;
        } else {
            transform.rotation.x += v;
            transform.rotation.y += u;
            transform.rotation.z += n;
        }

        transform.position.y -= 0.1;
    }


    public void respawn() {
        this.transform.position.y = 500 + Math.round(Math.random() * 10);

        double rx = Math.round(Math.random() * 400 - Math.random() * 400);
        double rz = Math.round(Math.random() * 400 - Math.random() * 400);
        this.transform.position.x = rx;
        this.transform.position.z = rz;
    }
}
