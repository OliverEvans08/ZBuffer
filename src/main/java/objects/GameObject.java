package objects;

import util.Matrix4;
import util.Transform;
import util.Vector3;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public abstract class GameObject {
    public Transform transform;
    private List<GameObject> children;
    private GameObject parent;
    private boolean full = false;
    private Color color = Color.WHITE;

    public GameObject() {
        this.transform = new Transform();
        this.children = new ArrayList<>();
    }

    public void addChild(GameObject child) {
        child.parent = this;
        children.add(child);
    }

    public Matrix4 getWorldTransform() {
        if (parent != null) {
            return parent.getWorldTransform().multiply(transform.getTransformationMatrix());
        }
        return transform.getTransformationMatrix();
    }

    public abstract double[][] getVertices();
    public abstract int[][] getEdges();
    public abstract void update(double delta);

    public double[][] getTransformedVertices() {
        double[][] originalVertices = getVertices();
        double[][] transformedVertices = new double[originalVertices.length][3];

        for (int i = 0; i < originalVertices.length; i++) {
            Vector3 vertex = new Vector3(originalVertices[i][0], originalVertices[i][1], originalVertices[i][2]);
            Vector3 transformed = transform.getTransformationMatrix().transform(vertex);

            transformedVertices[i][0] = transformed.x;
            transformedVertices[i][1] = transformed.y;
            transformedVertices[i][2] = transformed.z;
        }

        return transformedVertices;
    }

    public Color getColor() {
        return color;
    }

    public void setColor(Color color) {
        this.color = color;
    }

    public boolean isFull() {
        return full;
    }

    public void setFull(boolean full) {
        this.full = full;
    }
}
