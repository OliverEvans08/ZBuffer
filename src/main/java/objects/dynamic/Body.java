// File: src/main/java/objects/dynamic/Body.java
package objects.dynamic;

import objects.GameObject;
import util.Vector3;

import java.awt.*;

/**
 * Simple humanoid stand-in: a single box roughly WIDTH x HEIGHT.
 * Rendered in 3P; skipped in 1P by Renderer.
 */
public class Body extends GameObject {

    private final double halfW;
    private final double height;

    private static final int[][] FACES = new int[][]{
            {0, 1, 2}, {0, 2, 3},     // front
            {5, 4, 7}, {5, 7, 6},     // back
            {1, 5, 6}, {1, 6, 2},     // right
            {4, 0, 3}, {4, 3, 7},     // left
            {3, 2, 6}, {3, 6, 7},     // top
            {4, 5, 1}, {4, 1, 0}      // bottom
    };

    public Body(double width, double height) {
        this.halfW = width * 0.5;
        this.height = height;
        setColor(new Color(80, 210, 255)); // cyan-ish
        // default transform already initialized in GameObject
        this.getTransform().scale = new Vector3(1, 1, 1);
    }

    @Override
    public double[][] getVertices() {
        // Box with feet at y=0, height upwards, centered on X/Z
        double y0 = 0.0;
        double y1 = height;
        double hw = halfW;

        return new double[][]{
                {-hw, y0,  hw}, { hw, y0,  hw}, { hw, y1,  hw}, { -hw, y1,  hw}, // front quad
                {-hw, y0, -hw}, { hw, y0, -hw}, { hw, y1, -hw}, { -hw, y1, -hw}  // back quad
        };
    }

    @Override
    public int[][] getEdges() {
        // Not used by current renderer
        return new int[0][];
    }

    @Override
    public int[][] getFacesArray() {
        return FACES;
    }

    @Override
    public void update(double delta) {
        // Body is controlled externally by PlayerController; no internal behavior needed.
    }
}
