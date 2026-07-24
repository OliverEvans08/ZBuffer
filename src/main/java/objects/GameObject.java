package objects;

import engine.MeshData;
import engine.animation.Animator;
import engine.render.Material;
import java.awt.Color;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import util.AABB;
import util.Matrix4;
import util.Transform;
import util.Vector3;

public abstract class GameObject {

    private static volatile int PUBLISHED_FRAME;
    private static final AtomicLong HIERARCHY_VERSION = new AtomicLong();

    private static final double[][] EMPTY_VERTS = new double[0][0];

    private static final class FrameState {

        private double wx;
        private double wy;
        private double wz;

        private AABB aabb = new AABB(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        private double[][] transformedVertices = EMPTY_VERTS;
    }

    public static int getPublishedFrameIndex() {
        return PUBLISHED_FRAME;
    }

    public static void setPublishedFrameIndex(int index) {
        PUBLISHED_FRAME = index & 1;
    }

    public static long getHierarchyVersion() {
        return HIERARCHY_VERSION.get();
    }

    private final FrameState[] frames = {
            new FrameState(),
            new FrameState(),
    };

    private final String id = UUID.randomUUID().toString();

    private String name = "";
    private String tag = "";
    private int layer;

    public final Transform transform;

    private volatile boolean active = true;
    private volatile boolean visible = true;
    private volatile boolean solid;
    private volatile boolean wireframe;

    private volatile Color color = Color.WHITE;
    private volatile Material material;
    private volatile boolean ignorePlayerCollisions;

    private final CopyOnWriteArrayList<GameObject> children = new CopyOnWriteArrayList<>();

    private volatile GameObject parent;
    private volatile Animator animator;
    private volatile MeshData mesh;

    public GameObject() {
        transform = new Transform();
    }

    public final void publishFrameData(int frameIndex, double worldX, double worldY, double worldZ, AABB worldAabb, double[][] transformedVertices) {
        final FrameState state = frames[frameIndex & 1];

        state.wx = worldX;
        state.wy = worldY;
        state.wz = worldZ;

        if (worldAabb != null) {
            state.aabb = worldAabb;
        } else {
            state.aabb.set(worldX, worldX, worldY, worldY, worldZ, worldZ);
        }

        state.transformedVertices = transformedVertices != null ? transformedVertices : EMPTY_VERTS;
    }

    public MeshData getMesh() {
        return mesh;
    }

    public GameObject setMesh(MeshData mesh) {
        this.mesh = mesh;
        return this;
    }

    public double[][] getVertices() {
        final MeshData currentMesh = mesh;

        return currentMesh != null ? currentMesh.getVertices() : EMPTY_VERTS;
    }

    public int[][] getFacesArray() {
        final MeshData currentMesh = mesh;

        return currentMesh != null ? currentMesh.getFaces() : null;
    }

    public double[][] getUVs() {
        final MeshData currentMesh = mesh;

        return currentMesh != null ? currentMesh.getUVs() : null;
    }

    public void addChild(GameObject child) {
        if (child == null || child == this || child.parent == this) {
            return;
        }

        final GameObject oldParent = child.parent;

        if (oldParent != null) {
            oldParent.removeChild(child);
        }

        child.parent = this;
        children.add(child);

        HIERARCHY_VERSION.incrementAndGet();
    }

    public void removeChild(GameObject child) {
        if (child == null) {
            return;
        }

        if (children.remove(child)) {
            child.parent = null;
            HIERARCHY_VERSION.incrementAndGet();
        }
    }

    public GameObject getParent() {
        return parent;
    }

    public List<GameObject> getChildren() {
        return children;
    }

    public Transform getTransform() {
        return transform;
    }

    public Vector3 getWorldPosition() {
        final FrameState state = frames[PUBLISHED_FRAME];

        return new Vector3(state.wx, state.wy, state.wz);
    }

    public void getWorldPosition(double[] output) {
        if (output == null || output.length < 3) {
            throw new IllegalArgumentException("output must contain at least three elements");
        }

        final FrameState state = frames[PUBLISHED_FRAME];

        output[0] = state.wx;
        output[1] = state.wy;
        output[2] = state.wz;
    }

    public double getWorldX() {
        return frames[PUBLISHED_FRAME].wx;
    }

    public double getWorldY() {
        return frames[PUBLISHED_FRAME].wy;
    }

    public double getWorldZ() {
        return frames[PUBLISHED_FRAME].wz;
    }

    public AABB getWorldAABB() {
        return frames[PUBLISHED_FRAME].aabb;
    }

    public double[][] getTransformedVertices() {
        final double[][] transformed = frames[PUBLISHED_FRAME].transformedVertices;

        return transformed != null ? transformed : EMPTY_VERTS;
    }

    public Matrix4 getWorldTransform() {
        final Matrix4 local = transform.getTransformationMatrix();
        final GameObject currentParent = parent;

        if (currentParent == null) {
            return local;
        }

        final Matrix4 parentWorld = currentParent.getWorldTransform();
        final Matrix4 output = new Matrix4();

        parentWorld.multiply(local, output);

        return output;
    }

    public abstract void update(double delta);

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public boolean isSolid() {
        return solid;
    }

    public void setSolid(boolean solid) {
        this.solid = solid;
    }

    public boolean isWireframe() {
        return wireframe;
    }

    public void setWireframe(boolean wireframe) {
        this.wireframe = wireframe;
    }

    public boolean isRenderFull() {
        return !wireframe;
    }

    public void setRenderFull(boolean full) {
        wireframe = !full;
    }

    public boolean isFull() {
        return solid;
    }

    public void setFull(boolean full) {
        solid = full;
    }

    public Color getColor() {
        return color;
    }

    public GameObject setColor(Color color) {
        this.color = color == null ? Color.WHITE : color;
        return this;
    }

    public Material getMaterial() {
        return material;
    }

    public void setMaterial(Material material) {
        this.material = material;
    }

    public Animator getAnimator() {
        return animator;
    }

    public void setAnimator(Animator animator) {
        this.animator = animator;
    }

    public Animator animate() {
        Animator currentAnimator = animator;

        if (currentAnimator == null) {
            currentAnimator = new Animator(this);
            animator = currentAnimator;
        }

        return currentAnimator;
    }

    public boolean isIgnorePlayerCollisions() {
        return ignorePlayerCollisions;
    }

    public void setIgnorePlayerCollisions(boolean ignorePlayerCollisions) {
        this.ignorePlayerCollisions = ignorePlayerCollisions;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name != null ? name : "";
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag != null ? tag : "";
    }

    public int getLayer() {
        return layer;
    }

    public void setLayer(int layer) {
        this.layer = layer;
    }
}