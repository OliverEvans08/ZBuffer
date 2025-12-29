package objects;

import engine.animation.Animator;
import engine.render.Material;
import util.AABB;
import util.Matrix4;
import util.Transform;
import util.Vector3;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public abstract class GameObject {

    private final String id = UUID.randomUUID().toString();
    private String name = "";
    private String tag  = "";
    private int layer   = 0;

    public final Transform transform;

    private boolean active  = true;
    private boolean visible = true;
    private boolean full = false;
    private Color color = Color.WHITE;

    private Material material = null;

    private final List<GameObject> children;
    private final List<GameObject> childrenView;
    private GameObject parent;

    private Animator animator;

    // Cached world transform
    private final Matrix4 cachedWorld = new Matrix4();

    // Cached transformed vertices
    private double[][] cachedTransformed = null;
    private int cachedVertCount = -1;

    public GameObject() {
        this.transform = new Transform();
        this.children  = new ArrayList<>();
        this.childrenView = Collections.unmodifiableList(children);
    }

    public void addChild(GameObject child) {
        if (child == null || child == this) return;
        if (child.parent == this) return;
        if (child.parent != null) child.parent.removeChild(child);
        child.parent = this;
        children.add(child);
    }

    public void removeChild(GameObject child) {
        if (child == null) return;
        if (children.remove(child)) child.parent = null;
    }

    public GameObject getParent() { return parent; }
    public List<GameObject> getChildren() { return childrenView; }

    public Transform getTransform() { return transform; }

    public Matrix4 getWorldTransform() {
        Matrix4 local = transform.getTransformationMatrix();
        if (parent != null) {
            Matrix4 pw = parent.getWorldTransform();
            pw.multiply(local, cachedWorld);
            return cachedWorld;
        }
        return local;
    }

    public Vector3 getWorldPosition() {
        return getWorldTransform().transform(new Vector3(0,0,0));
    }

    public abstract double[][] getVertices();
    public abstract int[][] getEdges();

    public int[][] getFacesArray() { return null; }
    public double[][] getUVs() { return null; }

    public double[][] getTransformedVertices() {
        double[][] local = getVertices();
        int n = (local != null ? local.length : 0);
        if (n <= 0) return new double[0][0];

        if (cachedTransformed == null || cachedVertCount != n) {
            cachedTransformed = new double[n][3];
            cachedVertCount = n;
        }

        Matrix4 M = getWorldTransform();
        for (int i = 0; i < n; i++) {
            double[] v = local[i];
            if (v == null || v.length < 3) continue;
            M.transformPoint(v[0], v[1], v[2], cachedTransformed[i]);
        }
        return cachedTransformed;
    }

    public AABB getWorldAABB() {
        double[][] w = getTransformedVertices();
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;

        for (double[] v : w) {
            if (v == null || v.length < 3) continue;
            double x = v[0], y = v[1], z = v[2];
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }

        if (minX == Double.POSITIVE_INFINITY) {
            return new AABB(0,0,0,0,0,0);
        }
        return new AABB(minX, maxX, minY, maxY, minZ, maxZ);
    }

    public abstract void update(double delta);

    public boolean isActive()  { return active; }
    public void setActive(boolean active) { this.active = active; }

    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }

    public boolean isFull() { return full; }
    public void setFull(boolean full) { this.full = full; }

    public Color getColor() { return color; }

    // ✅ CHANGED: return GameObject instead of void (enables covariant override in LightObject)
    public GameObject setColor(Color color) {
        this.color = (color == null ? Color.WHITE : color);
        return this;
    }

    public Material getMaterial() { return material; }
    public void setMaterial(Material material) { this.material = material; }

    public Animator getAnimator() { return animator; }
    public void setAnimator(Animator animator) { this.animator = animator; }

    public Animator animate() {
        if (animator == null) animator = new Animator(this);
        return animator;
    }

    public String getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = (name != null ? name : ""); }

    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = (tag != null ? tag : ""); }

    public int getLayer() { return layer; }
    public void setLayer(int layer) { this.layer = layer; }
}
