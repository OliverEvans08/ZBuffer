package objects;

import util.Matrix4;
import util.Transform;
import util.Vector3;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public abstract class GameObject {
    public Transform transform;
    private final List<GameObject> children;
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

    /**
     * Optional faces for filled rendering; default none.
     * Provide triangles as {i0,i1,i2}. If you want quads, submit two tri faces.
     */
    public List<int[]> getFaces() {
        int[][] arr = getFacesArray();
        if (arr == null) return null;
        List<int[]> out = new ArrayList<>(arr.length);
        for (int[] f : arr) out.add(f.clone());
        return out;
    }

    /**
     * Internal hook for simple array faces; override if you provide faces.
     */
    public int[][] getFacesArray() { return null; }

    public double[][] getTransformedVertices() {
        double[][] local = getVertices();
        double[][] out = new double[local.length][3];
        Matrix4 M = getWorldTransform(); // IMPORTANT: world transform, not just local

        for (int i = 0; i < local.length; i++) {
            util.Vector3 v = new Vector3(local[i][0], local[i][1], local[i][2]);
            Vector3 t = M.transform(v);
            out[i][0] = t.x;
            out[i][1] = t.y;
            out[i][2] = t.z;
        }
        return out;
    }

    public Color getColor() { return color; }
    public void setColor(Color color) { this.color = color; }

    public boolean isFull() { return full; }
    public void setFull(boolean full) { this.full = full; }
}
