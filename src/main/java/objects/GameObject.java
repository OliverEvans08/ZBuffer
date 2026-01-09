package objects;

import engine.MeshData;
import engine.animation.Animator;
import engine.render.Material;
import util.AABB;
import util.Matrix4;
import util.Transform;
import util.Vector3;

import java.awt.Color;
import java.util.*;
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

    // Optional mesh reference (data-driven meshes)
    private MeshData mesh = null;

    private final Matrix4 cachedWorld = new Matrix4();
    private long cachedWorldStamp = Long.MIN_VALUE;

    private static final double[][] EMPTY_VERTS = new double[0][0];
    private static final int[][] EMPTY_EDGES = new int[0][];

    private double[][] cachedTransformed = null;
    private int cachedVertCount = -1;
    private long cachedTransformedStamp = Long.MIN_VALUE;

    private AABB cachedWorldAABB = new AABB(0, 0, 0, 0, 0, 0);
    private boolean worldAabbDirty = true;
    private long cachedWorldAabbStamp = Long.MIN_VALUE;
    private int cachedAabbVertCount = -1;

    private final double[] tmpAabbPoint = new double[3];

    public GameObject() {
        this.transform = new Transform();
        this.children  = new ArrayList<>();
        this.childrenView = Collections.unmodifiableList(children);
    }

    // ---------------------------
    // MeshData attachment (optional)
    // ---------------------------

    public MeshData getMesh() { return mesh; }

    /**
     * Attach/detach a mesh reference.
     * This does NOT copy arrays; MeshData is shared & cached by AssetManager.
     */
    public GameObject setMesh(MeshData mesh) {
        this.mesh = mesh;

        // Mesh swap must invalidate geometry caches even if transform didn't change.
        cachedWorldStamp = Long.MIN_VALUE;
        cachedTransformedStamp = Long.MIN_VALUE;
        cachedWorldAabbStamp = Long.MIN_VALUE;

        cachedTransformed = null;
        cachedVertCount = -1;
        cachedAabbVertCount = -1;
        worldAabbDirty = true;

        return this;
    }

    // ---------------------------
    // Scene graph
    // ---------------------------

    public void addChild(GameObject child) {
        if (child == null || child == this) return;
        if (child.parent == this) return;
        if (child.parent != null) child.parent.removeChild(child);
        child.parent = this;
        children.add(child);

        child.markWorldAABBDirty();
    }

    public void removeChild(GameObject child) {
        if (child == null) return;
        if (children.remove(child)) {
            child.parent = null;
            child.markWorldAABBDirty();
        }
    }

    public GameObject getParent() { return parent; }
    public List<GameObject> getChildren() { return childrenView; }

    public Transform getTransform() { return transform; }

    public Matrix4 getWorldTransform() {
        Matrix4 local = transform.getTransformationMatrix();
        if (parent == null) return local;

        long stamp = computeWorldStamp();
        if (stamp == cachedWorldStamp) return cachedWorld;

        Matrix4 pw = parent.getWorldTransform();
        pw.multiply(local, cachedWorld);
        cachedWorldStamp = stamp;
        return cachedWorld;
    }

    public Vector3 getWorldPosition() {
        return getWorldTransform().transform(new Vector3(0,0,0));
    }

    // ---------------------------
    // Geometry (default: from MeshData if attached; else empty)
    // Existing procedural objects can still override these.
    // ---------------------------

    public double[][] getVertices() {
        MeshData m = this.mesh;
        return (m != null ? m.getVertices() : EMPTY_VERTS);
    }

    public int[][] getEdges() { return EMPTY_EDGES; }

    public int[][] getFacesArray() {
        MeshData m = this.mesh;
        return (m != null ? m.getFaces() : null);
    }

    public double[][] getUVs() {
        MeshData m = this.mesh;
        return (m != null ? m.getUVs() : null);
    }

    public double[][] getTransformedVertices() {
        double[][] local = getVertices();
        int n = (local != null ? local.length : 0);
        if (n <= 0) return EMPTY_VERTS;

        if (cachedTransformed == null || cachedVertCount != n) {
            cachedTransformed = new double[n][3];
            cachedVertCount = n;
            cachedTransformedStamp = Long.MIN_VALUE;
        }

        long stamp = computeWorldStamp();
        if (stamp == cachedTransformedStamp) {
            return cachedTransformed;
        }

        Matrix4 M = getWorldTransform();
        for (int i = 0; i < n; i++) {
            double[] v = local[i];
            if (v == null || v.length < 3) continue;
            M.transformPoint(v[0], v[1], v[2], cachedTransformed[i]);
        }

        cachedTransformedStamp = stamp;
        return cachedTransformed;
    }

    public AABB getWorldAABB() {
        double[][] local = getVertices();
        int n = (local != null ? local.length : 0);
        if (n != cachedAabbVertCount) {
            cachedAabbVertCount = n;
            worldAabbDirty = true;
        }

        long stamp = computeWorldStamp();
        if (stamp != cachedWorldAabbStamp) worldAabbDirty = true;

        if (!worldAabbDirty) return cachedWorldAABB;

        cachedWorldAABB = computeWorldAABBNow(local);
        cachedWorldAabbStamp = stamp;
        worldAabbDirty = false;
        return cachedWorldAABB;
    }

    protected void markWorldAABBDirty() {
        worldAabbDirty = true;
        cachedWorldStamp = Long.MIN_VALUE;
        cachedTransformedStamp = Long.MIN_VALUE;
        cachedWorldAabbStamp = Long.MIN_VALUE;
    }

    protected long computeWorldStamp() {
        long localVer = transform.getVersion();
        if (parent == null) return mix64(localVer);
        long parentStamp = parent.computeWorldStamp();
        return mix64(localVer * 31L + parentStamp);
    }

    private static long mix64(long x) {
        x ^= (x >>> 33);
        x *= 0xff51afd7ed558ccdL;
        x ^= (x >>> 33);
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= (x >>> 33);
        return x;
    }

    private AABB computeWorldAABBNow(double[][] localVerts) {
        if (localVerts == null || localVerts.length == 0) {
            return new AABB(0, 0, 0, 0, 0, 0);
        }

        Matrix4 M = getWorldTransform();
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;

        for (double[] v : localVerts) {
            if (v == null || v.length < 3) continue;

            M.transformPoint(v[0], v[1], v[2], tmpAabbPoint);
            double x = tmpAabbPoint[0], y = tmpAabbPoint[1], z = tmpAabbPoint[2];

            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }

        if (minX == Double.POSITIVE_INFINITY) {
            return new AABB(0, 0, 0, 0, 0, 0);
        }
        return new AABB(minX, maxX, minY, maxY, minZ, maxZ);
    }

    // Behavior
    public abstract void update(double delta);

    // ---------------------------
    // Flags / render properties
    // ---------------------------

    public boolean isActive()  { return active; }
    public void setActive(boolean active) { this.active = active; }

    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }

    public boolean isFull() { return full; }
    public void setFull(boolean full) { this.full = full; }

    public Color getColor() { return color; }

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
