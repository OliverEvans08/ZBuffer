package engine;

import java.util.ArrayList;
import java.util.WeakHashMap;
import objects.GameObject;
import objects.dynamic.Body;
import util.AABB;

public class Camera {

    public enum Mode {
        FIRST_PERSON,
        THIRD_PERSON,
    }

    /**
     * Optional bridge for objects that keep collision geometry separate from their
     * render mesh. Existing objects fall back to their transformed visual mesh.
     */
    public interface CollisionGeometryProvider {

        double[][] getCollisionVertices();

        int[][] getCollisionFaces();

        default AABB getCollisionAABB() {
            return null;
        }

        /**
         * When provided, this simplified box is used for third-person camera
         * obstruction instead of the full triangle mesh.
         */
        default AABB getCameraCollisionAABB() {
            return null;
        }

        /**
         * Increment when collision vertices are modified in place.
         */
        default long getCollisionGeometryRevision() {
            return Long.MIN_VALUE;
        }
    }

    public static final double WIDTH = 0.6;
    public static final double HEIGHT = 1.8;
    public static final double EYE_HEIGHT = 1.6;
    public static final double HEAD_HEIGHT = HEIGHT - 0.08;

    private static final double PLAYER_RADIUS = WIDTH * 0.5;
    private static final double PLAYER_RADIUS2 = PLAYER_RADIUS * PLAYER_RADIUS;
    private static final double FP_FORWARD_OFFSET = 0.06;

    public static final double GRAVITY = -30.0;
    public static final double JUMP_STRENGTH = 10.0;

    private static final double CAM_WALL_PAD = 0.12;
    private static final double CAM_MIN_DIST = 0.20;
    private static final double CAMERA_TINY_MOVE2 = 0.0025 * 0.0025;
    private static final double CAMERA_RECHECK_INTERVAL = 0.050;

    private static final double RAY_EPS = 1e-9;
    private static final double TRI_EPS = 1e-8;
    private static final double TRI_EPS2 = TRI_EPS * TRI_EPS;
    private static final double HORIZONTAL_TRI_PAD = 0.02;
    private static final double MAX_WALL_NORMAL_Y = 0.35;
    private static final double MIN_WALKABLE_NORMAL_Y = MAX_WALL_NORMAL_Y;
    private static final double CAPSULE_DIAGONAL_SAMPLE = 0.70;
    private static final double INV_SQRT_2 = 0.7071067811865476;
    private static final double VERT_SNAP_EPS = 0.06;
    private static final double FLOOR_CACHE_TINY_MOVE2 = 0.012 * 0.012;

    private static final byte TRI_WALL = 1;
    private static final byte TRI_FLOOR = 2;
    private static final byte TRI_CEILING = 4;
    private static final int BVH_LEAF_TRIANGLES = 8;
    private static final long NO_GEOMETRY_REVISION = Long.MIN_VALUE;

    private static final double BOB_FREQ_BASE = 4.5;
    private static final double BOB_FREQ_ADD = 4.0;
    private static final double BOB_Y_MAX = 0.050;
    private static final double BOB_X_MAX = 0.030;

    private static final double TPS_SMOOTH_OUT = 10.0;
    private static final double TPS_SMOOTH_IN = 40.0;

    private static final double SELF_IGNORE_RADIUS = 1.25;
    private static final double SELF_IGNORE_RADIUS2 =
            SELF_IGNORE_RADIUS * SELF_IGNORE_RADIUS;
    private static final double SELF_IGNORE_MAX_W = 1.10;
    private static final double SELF_IGNORE_MAX_H = 1.25;

    public double x;
    public double y;
    public double z;
    public double pitch;
    public double yaw;

    public boolean flightMode = false;
    public boolean onGround = true;

    public double dx = 0.0;
    public double dy = 0.0;
    public double dz = 0.0;
    public double yVelocity = 0.0;

    private Mode mode = Mode.FIRST_PERSON;

    private double thirdPersonDistance = 3.2;
    private double shoulderOffset = 0.6;

    private double eyeX;
    private double eyeY;
    private double eyeZ;
    private double viewYaw;
    private double viewPitch;

    private double bobT;
    private double bobX;
    private double bobY;

    final GameEngine gameEngine;

    private final double[] tmpCam = new double[3];
    private final double[] tmpClosestXZ = new double[2];
    private final ArrayList<GameObject> nearby = new ArrayList<>(256);
    private final WeakHashMap<GameObject, MeshCollider> meshColliders =
            new WeakHashMap<>();

    private int[] bvhStack = new int[64];

    private GameObject cachedFloorObject;
    private MeshCollider cachedFloorMesh;
    private int cachedFloorTriangle = -1;
    private boolean cachedFloorIsAabb;
    private double cachedFloorHeight = Double.NEGATIVE_INFINITY;

    private GameObject previousCameraBlocker;
    private double previousCameraHitDistance = Double.POSITIVE_INFINITY;
    private boolean cameraCollisionCacheValid;
    private double cameraCollisionElapsed = Double.POSITIVE_INFINITY;
    private double cachedCameraPivotX;
    private double cachedCameraPivotY;
    private double cachedCameraPivotZ;
    private double cachedCameraDesiredX;
    private double cachedCameraDesiredY;
    private double cachedCameraDesiredZ;

    private int lastSurfaceTriangle = -1;

    private double cachedYaw = Double.NaN;
    private double cachedPitch = Double.NaN;
    private boolean basisCached;

    private double cy;
    private double sy;
    private double cp;
    private double sp;
    private double fwdX;
    private double fwdY;
    private double fwdZ;
    private double rightX;
    private double rightY;
    private double rightZ;

    public Camera(
            double x,
            double y,
            double z,
            double pitch,
            double yaw,
            GameEngine gameEngine
    ) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.pitch = pitch;
        this.yaw = yaw;
        this.gameEngine = gameEngine;

        recomputeViewOrigin(0.0);
    }

    public Mode getMode() {
        return mode;
    }

    public boolean isFirstPerson() {
        return mode == Mode.FIRST_PERSON;
    }

    public boolean isThirdPerson() {
        return mode == Mode.THIRD_PERSON;
    }

    public void toggleMode() {
        mode = mode == Mode.FIRST_PERSON
                ? Mode.THIRD_PERSON
                : Mode.FIRST_PERSON;

        invalidateCameraCollisionCache();
        recomputeViewOrigin(0.0);
    }

    public void setThirdPersonDistance(double distance) {
        thirdPersonDistance = Math.max(0.2, distance);
        invalidateCameraCollisionCache();
    }

    public void setShoulderOffset(double offset) {
        shoulderOffset = Math.max(-1.5, Math.min(1.5, offset));
        invalidateCameraCollisionCache();
    }

    public double getViewX() {
        return eyeX;
    }

    public double getViewY() {
        return eyeY;
    }

    public double getViewZ() {
        return eyeZ;
    }

    public double getViewYaw() {
        return viewYaw;
    }

    public double getViewPitch() {
        return viewPitch;
    }

    public double getAimX() {
        return mode == Mode.FIRST_PERSON ? eyeX : x + bobX;
    }

    public double getAimY() {
        return mode == Mode.FIRST_PERSON
                ? eyeY
                : y + EYE_HEIGHT + bobY;
    }

    public double getAimZ() {
        return mode == Mode.FIRST_PERSON ? eyeZ : z;
    }

    public double getForwardX() {
        updateBasisIfNeeded();
        return fwdX;
    }

    public double getForwardY() {
        updateBasisIfNeeded();
        return fwdY;
    }

    public double getForwardZ() {
        updateBasisIfNeeded();
        return fwdZ;
    }

    public double getRightX() {
        updateBasisIfNeeded();
        return rightX;
    }

    public double getRightY() {
        updateBasisIfNeeded();
        return rightY;
    }

    public double getRightZ() {
        updateBasisIfNeeded();
        return rightZ;
    }

    public void setFlightMode(boolean enabled) {
        flightMode = enabled;
        clearFloorCache();

        if (enabled) {
            onGround = false;
            yVelocity = 0.0;
        }
    }

    public void jump() {
        if (!flightMode && onGround) {
            yVelocity = JUMP_STRENGTH;
            onGround = false;
            clearFloorCache();

            if (gameEngine != null && gameEngine.soundEngine != null) {
                gameEngine.soundEngine.fireSound("jump.wav", 0.55);
            }
        }
    }

    public void update(double delta) {
        normalizeAngles();
        updateBasisIfNeeded();

        double speedIntent = Math.sqrt(dx * dx + dz * dz);
        double move01 = clamp01(speedIntent / 7.8);

        if (!flightMode && onGround && move01 > 1e-6) {
            bobT += delta * (BOB_FREQ_BASE + BOB_FREQ_ADD * move01);
            bobY = Math.sin(bobT) * (BOB_Y_MAX * move01);
            bobX = Math.cos(bobT * 0.5) * (BOB_X_MAX * move01);
        } else {
            double amount = 1.0 - Math.exp(-delta * 10.0);
            bobY += -bobY * amount;
            bobX += -bobX * amount;
        }

        double previousX = x;
        double previousZ = z;

        double moveX = (dx * cy - dz * sy) * delta;
        double moveZ = (dz * cy + dx * sy) * delta;

        x += moveX;
        z += moveZ;

        if (!flightMode) {
            resolveHorizontalCollisions(previousX, previousZ);
        }

        double previousY = y;

        if (flightMode) {
            y += dy * delta;
            onGround = false;
            yVelocity = 0.0;
        } else {
            yVelocity += GRAVITY * delta;
            y += yVelocity * delta;
        }

        if (!flightMode) {
            resolveVerticalCollisions(previousX, previousY, previousZ);
        }

        if (!flightMode && y < 0.0) {
            y = 0.0;
            yVelocity = 0.0;
            onGround = true;
            clearFloorCache();
        }

        recomputeViewOrigin(delta);
        resetMovementDeltas();
    }

    private void updateBasisIfNeeded() {
        if (!basisCached || yaw != cachedYaw || pitch != cachedPitch) {
            cachedYaw = yaw;
            cachedPitch = pitch;

            cy = Math.cos(cachedYaw);
            sy = Math.sin(cachedYaw);
            cp = Math.cos(cachedPitch);
            sp = Math.sin(cachedPitch);

            fwdX = -cp * sy;
            fwdY = -sp;
            fwdZ = cp * cy;

            rightX = cy;
            rightY = 0.0;
            rightZ = sy;

            basisCached = true;
        }
    }

    private void normalizeAngles() {
        double limit = Math.toRadians(89.9);

        if (pitch > limit) {
            pitch = limit;
        } else if (pitch < -limit) {
            pitch = -limit;
        }

        double twoPi = Math.PI * 2.0;
        yaw %= twoPi;

        if (yaw > Math.PI) {
            yaw -= twoPi;
        } else if (yaw < -Math.PI) {
            yaw += twoPi;
        }
    }

    private boolean isPlayerOrChild(GameObject object) {
        if (gameEngine == null || object == null) {
            return false;
        }

        Body playerBody = gameEngine.getPlayerBody();

        return playerBody != null
                && isDescendantOrSelf(object, playerBody);
    }

    private static boolean isDescendantOrSelf(
            GameObject node,
            GameObject root
    ) {
        if (node == null || root == null) {
            return false;
        }

        for (
                GameObject current = node;
                current != null;
                current = current.getParent()
        ) {
            if (current == root) {
                return true;
            }
        }

        return false;
    }

    private boolean shouldIgnoreColliderForPlayer(
            GameObject object,
            AABB bounds
    ) {
        if (object == null || object.isIgnorePlayerCollisions()) {
            return true;
        }

        if (bounds == null) {
            return false;
        }

        double width = bounds.maxX - bounds.minX;
        double height = bounds.maxY - bounds.minY;
        double depth = bounds.maxZ - bounds.minZ;

        if (
                width > SELF_IGNORE_MAX_W
                        || depth > SELF_IGNORE_MAX_W
                        || height > SELF_IGNORE_MAX_H
        ) {
            return false;
        }

        double centerX = (bounds.minX + bounds.maxX) * 0.5;
        double centerY = (bounds.minY + bounds.maxY) * 0.5;
        double centerZ = (bounds.minZ + bounds.maxZ) * 0.5;

        double offsetX = centerX - x;
        double offsetY = centerY - (y + HEIGHT * 0.5);
        double offsetZ = centerZ - z;

        return offsetX * offsetX
                + offsetY * offsetY
                + offsetZ * offsetZ
                <= SELF_IGNORE_RADIUS2;
    }

    private AABB getCollisionBounds(GameObject object) {
        if (object instanceof CollisionGeometryProvider) {
            AABB bounds =
                    ((CollisionGeometryProvider) object).getCollisionAABB();

            if (bounds != null) {
                return bounds;
            }
        }

        return object.getWorldAABB();
    }

    private MeshCollider getMeshCollider(
            GameObject object,
            AABB collisionBounds
    ) {
        double[][] vertices;
        int[][] faces;
        long revision = NO_GEOMETRY_REVISION;

        if (object instanceof CollisionGeometryProvider) {
            CollisionGeometryProvider provider =
                    (CollisionGeometryProvider) object;

            vertices = provider.getCollisionVertices();
            faces = provider.getCollisionFaces();
            revision = provider.getCollisionGeometryRevision();
        } else {
            vertices = object.getTransformedVertices();
            faces = object.getFacesArray();
        }

        if (!hasValidDetailedCollider(vertices, faces)) {
            meshColliders.remove(object);
            return null;
        }

        long probe = revision == NO_GEOMETRY_REVISION
                ? geometryProbe(vertices, faces)
                : 0L;

        MeshCollider collider = meshColliders.get(object);

        if (
                collider != null
                        && collider.isCurrent(
                        vertices,
                        faces,
                        collisionBounds,
                        revision,
                        probe
                )
        ) {
            return collider;
        }

        collider = MeshCollider.build(
                vertices,
                faces,
                collisionBounds,
                revision,
                probe
        );

        if (collider == null) {
            meshColliders.remove(object);
        } else {
            meshColliders.put(object, collider);
        }

        return collider;
    }

    private static long geometryProbe(
            double[][] vertices,
            int[][] faces
    ) {
        long hash = 0xcbf29ce484222325L;

        if (vertices.length > 0) {
            hash = probeVertex(hash, vertices[0]);
            hash = probeVertex(hash, vertices[vertices.length >>> 1]);
            hash = probeVertex(hash, vertices[vertices.length - 1]);
        }

        if (faces.length > 0) {
            hash = probeFace(hash, faces[0]);
            hash = probeFace(hash, faces[faces.length >>> 1]);
            hash = probeFace(hash, faces[faces.length - 1]);
        }

        return hash;
    }

    private static long probeVertex(long hash, double[] vertex) {
        if (vertex == null || vertex.length < 3) {
            return mixProbe(hash, 0x9e3779b97f4a7c15L);
        }

        hash = mixProbe(hash, Double.doubleToLongBits(vertex[0]));
        hash = mixProbe(hash, Double.doubleToLongBits(vertex[1]));

        return mixProbe(hash, Double.doubleToLongBits(vertex[2]));
    }

    private static long probeFace(long hash, int[] face) {
        if (face == null || face.length < 3) {
            return mixProbe(hash, 0x517cc1b727220a95L);
        }

        hash = mixProbe(hash, face[0]);
        hash = mixProbe(hash, face[1]);

        return mixProbe(hash, face[2]);
    }

    private static long mixProbe(long hash, long value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }

    private void resolveHorizontalCollisions(
            double previousX,
            double previousZ
    ) {
        if (gameEngine == null) {
            return;
        }

        double sweptMinimumX =
                Math.min(previousX, x) - PLAYER_RADIUS;
        double sweptMaximumX =
                Math.max(previousX, x) + PLAYER_RADIUS;
        double sweptMinimumZ =
                Math.min(previousZ, z) - PLAYER_RADIUS;
        double sweptMaximumZ =
                Math.max(previousZ, z) + PLAYER_RADIUS;

        gameEngine.queryNearbyCollidersXZ(
                sweptMinimumX,
                sweptMaximumX,
                sweptMinimumZ,
                sweptMaximumZ,
                nearby
        );

        for (int i = 0, count = nearby.size(); i < count; i++) {
            GameObject object = nearby.get(i);

            if (object == null || isPlayerOrChild(object)) {
                continue;
            }

            AABB bounds = getCollisionBounds(object);

            if (
                    bounds == null
                            || bounds.maxX < sweptMinimumX
                            || bounds.minX > sweptMaximumX
                            || bounds.maxZ < sweptMinimumZ
                            || bounds.minZ > sweptMaximumZ
                            || y + HEIGHT <= bounds.minY
                            || y >= bounds.maxY
                            || shouldIgnoreColliderForPlayer(object, bounds)
            ) {
                continue;
            }

            MeshCollider collider = getMeshCollider(object, bounds);

            if (collider != null && collider.wallBvh != null) {
                resolveHorizontalAgainstMesh(collider);
            } else {
                resolveCapsuleAgainstAabb(bounds);
            }
        }
    }

    private void resolveHorizontalAgainstMesh(MeshCollider collider) {
        MeshBvh bvh = collider.wallBvh;

        ensureBvhStackCapacity(bvh.nodeCount);

        int stackSize = 0;
        bvhStack[stackSize++] = 0;

        while (stackSize > 0) {
            int node = bvhStack[--stackSize];

            if (
                    bvh.maxX[node] + HORIZONTAL_TRI_PAD
                            < x - PLAYER_RADIUS
                            || bvh.minX[node] - HORIZONTAL_TRI_PAD
                            > x + PLAYER_RADIUS
                            || bvh.maxY[node] + HORIZONTAL_TRI_PAD <= y
                            || bvh.minY[node] - HORIZONTAL_TRI_PAD
                            >= y + HEIGHT
                            || bvh.maxZ[node] + HORIZONTAL_TRI_PAD
                            < z - PLAYER_RADIUS
                            || bvh.minZ[node] - HORIZONTAL_TRI_PAD
                            > z + PLAYER_RADIUS
            ) {
                continue;
            }

            int triangleCount = bvh.count[node];

            if (triangleCount != 0) {
                int end = bvh.start[node] + triangleCount;

                for (
                        int orderIndex = bvh.start[node];
                        orderIndex < end;
                        orderIndex++
                ) {
                    int triangle = bvh.order[orderIndex];

                    if (
                            collider.maxX[triangle] + HORIZONTAL_TRI_PAD
                                    < x - PLAYER_RADIUS
                                    || collider.minX[triangle]
                                    - HORIZONTAL_TRI_PAD
                                    > x + PLAYER_RADIUS
                                    || collider.maxY[triangle]
                                    + HORIZONTAL_TRI_PAD
                                    <= y
                                    || collider.minY[triangle]
                                    - HORIZONTAL_TRI_PAD
                                    >= y + HEIGHT
                                    || collider.maxZ[triangle]
                                    + HORIZONTAL_TRI_PAD
                                    < z - PLAYER_RADIUS
                                    || collider.minZ[triangle]
                                    - HORIZONTAL_TRI_PAD
                                    > z + PLAYER_RADIUS
                    ) {
                        continue;
                    }

                    resolveCapsuleAgainstTriangle(collider, triangle);
                }
            } else {
                bvhStack[stackSize++] = bvh.left[node];
                bvhStack[stackSize++] = bvh.right[node];
            }
        }
    }

    private void resolveCapsuleAgainstTriangle(
            MeshCollider collider,
            int triangle
    ) {
        closestPointOnTriangleXZ(
                collider,
                triangle,
                x,
                z,
                tmpClosestXZ
        );

        double offsetX = x - tmpClosestXZ[0];
        double offsetZ = z - tmpClosestXZ[1];
        double distanceSquared =
                offsetX * offsetX + offsetZ * offsetZ;

        if (distanceSquared >= PLAYER_RADIUS2) {
            return;
        }

        double directionX;
        double directionZ;
        double distance;

        if (distanceSquared > TRI_EPS2) {
            distance = Math.sqrt(distanceSquared);
            directionX = offsetX / distance;
            directionZ = offsetZ / distance;
        } else {
            double horizontalNormalLength = Math.sqrt(
                    collider.normalX[triangle]
                            * collider.normalX[triangle]
                            + collider.normalZ[triangle]
                            * collider.normalZ[triangle]
            );

            if (horizontalNormalLength <= TRI_EPS) {
                return;
            }

            directionX =
                    collider.normalX[triangle] / horizontalNormalLength;
            directionZ =
                    collider.normalZ[triangle] / horizontalNormalLength;

            double sampleY = clamp(
                    y + HEIGHT * 0.5,
                    collider.minY[triangle],
                    collider.maxY[triangle]
            );

            double signedDistance =
                    collider.normalX[triangle] * x
                            + collider.normalY[triangle] * sampleY
                            + collider.normalZ[triangle] * z
                            + collider.planeD[triangle];

            if (signedDistance < 0.0) {
                directionX = -directionX;
                directionZ = -directionZ;
            }

            distance = Math.abs(signedDistance);
        }

        double penetration =
                PLAYER_RADIUS - distance + TRI_EPS;

        if (penetration > 0.0) {
            x += directionX * penetration;
            z += directionZ * penetration;
        }
    }

    private void resolveCapsuleAgainstAabb(AABB bounds) {
        double closestX = clamp(x, bounds.minX, bounds.maxX);
        double closestZ = clamp(z, bounds.minZ, bounds.maxZ);

        double offsetX = x - closestX;
        double offsetZ = z - closestZ;
        double distanceSquared =
                offsetX * offsetX + offsetZ * offsetZ;

        if (distanceSquared > TRI_EPS2) {
            if (distanceSquared >= PLAYER_RADIUS2) {
                return;
            }

            double distance = Math.sqrt(distanceSquared);
            double penetration =
                    PLAYER_RADIUS - distance + TRI_EPS;

            x += offsetX * (penetration / distance);
            z += offsetZ * (penetration / distance);
            return;
        }

        double toLeft = x - (bounds.minX - PLAYER_RADIUS);
        double toRight = bounds.maxX + PLAYER_RADIUS - x;
        double toFront = z - (bounds.minZ - PLAYER_RADIUS);
        double toBack = bounds.maxZ + PLAYER_RADIUS - z;

        double minimum = Math.min(
                Math.min(toLeft, toRight),
                Math.min(toFront, toBack)
        );

        if (minimum == toLeft) {
            x = bounds.minX - PLAYER_RADIUS - TRI_EPS;
        } else if (minimum == toRight) {
            x = bounds.maxX + PLAYER_RADIUS + TRI_EPS;
        } else if (minimum == toFront) {
            z = bounds.minZ - PLAYER_RADIUS - TRI_EPS;
        } else {
            z = bounds.maxZ + PLAYER_RADIUS + TRI_EPS;
        }
    }

    private void resolveVerticalCollisions(
            double previousX,
            double previousY,
            double previousZ
    ) {
        if (gameEngine == null) {
            return;
        }

        boolean wasOnGround = onGround;
        boolean movingDown = y <= previousY;

        onGround = false;

        double previousFeet = previousY;
        double previousHead = previousY + HEIGHT;
        double newFeet = y;
        double newHead = y + HEIGHT;

        double bestFloor = Double.NEGATIVE_INFINITY;
        double bestCeiling = Double.POSITIVE_INFINITY;

        GameObject bestFloorObject = null;
        MeshCollider bestFloorMesh = null;
        int bestFloorTriangle = -1;
        boolean bestFloorIsAabb = false;

        if (movingDown && cachedFloorObject != null) {
            AABB cachedBounds =
                    getCollisionBounds(cachedFloorObject);

            if (
                    cachedBounds != null
                            && !shouldIgnoreColliderForPlayer(
                            cachedFloorObject,
                            cachedBounds
                    )
            ) {
                if (cachedFloorIsAabb) {
                    double floor = testAabbFloor(
                            cachedBounds,
                            previousFeet,
                            newFeet
                    );

                    if (Double.isFinite(floor)) {
                        bestFloor = floor;
                        bestFloorObject = cachedFloorObject;
                        bestFloorIsAabb = true;
                    }
                } else {
                    MeshCollider current = getMeshCollider(
                            cachedFloorObject,
                            cachedBounds
                    );

                    if (
                            current == cachedFloorMesh
                                    && cachedFloorTriangle >= 0
                    ) {
                        double floor = capsuleSurfaceContact(
                                current,
                                cachedFloorTriangle,
                                true,
                                previousFeet,
                                newFeet
                        );

                        if (Double.isFinite(floor)) {
                            bestFloor = floor;
                            bestFloorObject = cachedFloorObject;
                            bestFloorMesh = current;
                            bestFloorTriangle = cachedFloorTriangle;
                        }
                    }
                }
            }

            double horizontalMoveX = x - previousX;
            double horizontalMoveZ = z - previousZ;

            if (
                    bestFloor != Double.NEGATIVE_INFINITY
                            && wasOnGround
                            && horizontalMoveX * horizontalMoveX
                            + horizontalMoveZ * horizontalMoveZ
                            <= FLOOR_CACHE_TINY_MOVE2
                            && Math.abs(
                            previousFeet - cachedFloorHeight
                    ) <= VERT_SNAP_EPS * 2.0
            ) {
                y = bestFloor;
                yVelocity = 0.0;
                onGround = true;
                cachedFloorHeight = bestFloor;
                return;
            }
        }

        if (!movingDown) {
            clearFloorCache();
        }

        double queryMinimumX =
                Math.min(previousX, x) - PLAYER_RADIUS;
        double queryMaximumX =
                Math.max(previousX, x) + PLAYER_RADIUS;
        double queryMinimumZ =
                Math.min(previousZ, z) - PLAYER_RADIUS;
        double queryMaximumZ =
                Math.max(previousZ, z) + PLAYER_RADIUS;

        gameEngine.queryNearbyCollidersXZ(
                queryMinimumX,
                queryMaximumX,
                queryMinimumZ,
                queryMaximumZ,
                nearby
        );

        for (int i = 0, count = nearby.size(); i < count; i++) {
            GameObject object = nearby.get(i);

            if (object == null || isPlayerOrChild(object)) {
                continue;
            }

            AABB bounds = getCollisionBounds(object);

            if (
                    bounds == null
                            || bounds.maxX < queryMinimumX
                            || bounds.minX > queryMaximumX
                            || bounds.maxZ < queryMinimumZ
                            || bounds.minZ > queryMaximumZ
                            || shouldIgnoreColliderForPlayer(object, bounds)
            ) {
                continue;
            }

            MeshCollider collider = getMeshCollider(object, bounds);

            if (collider != null) {
                if (movingDown && collider.floorBvh != null) {
                    int skipTriangle =
                            object == bestFloorObject
                                    && collider == bestFloorMesh
                                    ? bestFloorTriangle
                                    : -1;

                    double floor = findBestCapsuleSurface(
                            collider,
                            true,
                            previousFeet,
                            newFeet,
                            skipTriangle
                    );

                    if (floor > bestFloor) {
                        bestFloor = floor;
                        bestFloorObject = object;
                        bestFloorMesh = collider;
                        bestFloorTriangle = lastSurfaceTriangle;
                        bestFloorIsAabb = false;
                    }
                } else if (
                        !movingDown
                                && collider.ceilingBvh != null
                ) {
                    double ceiling = findBestCapsuleSurface(
                            collider,
                            false,
                            previousHead,
                            newHead,
                            -1
                    );

                    if (ceiling < bestCeiling) {
                        bestCeiling = ceiling;
                    }
                }

                continue;
            }

            if (movingDown) {
                double floor = testAabbFloor(
                        bounds,
                        previousFeet,
                        newFeet
                );

                if (floor > bestFloor) {
                    bestFloor = floor;
                    bestFloorObject = object;
                    bestFloorMesh = null;
                    bestFloorTriangle = -1;
                    bestFloorIsAabb = true;
                }
            } else {
                double ceiling = testAabbCeiling(
                        bounds,
                        previousHead,
                        newHead
                );

                if (ceiling < bestCeiling) {
                    bestCeiling = ceiling;
                }
            }
        }

        if (movingDown) {
            if (bestFloor != Double.NEGATIVE_INFINITY) {
                y = bestFloor;
                yVelocity = 0.0;
                onGround = true;

                cachedFloorObject = bestFloorObject;
                cachedFloorMesh = bestFloorMesh;
                cachedFloorTriangle = bestFloorTriangle;
                cachedFloorIsAabb = bestFloorIsAabb;
                cachedFloorHeight = bestFloor;
            } else {
                clearFloorCache();
            }
        } else if (bestCeiling != Double.POSITIVE_INFINITY) {
            y = bestCeiling - HEIGHT;
            yVelocity = 0.0;
        }
    }

    private double findBestCapsuleSurface(
            MeshCollider collider,
            boolean floor,
            double previousEnd,
            double newEnd,
            int skipTriangle
    ) {
        MeshBvh bvh =
                floor ? collider.floorBvh : collider.ceilingBvh;

        if (bvh == null) {
            lastSurfaceTriangle = -1;

            return floor
                    ? Double.NEGATIVE_INFINITY
                    : Double.POSITIVE_INFINITY;
        }

        double minimumSweepY =
                Math.min(previousEnd, newEnd)
                        - PLAYER_RADIUS
                        - VERT_SNAP_EPS;

        double maximumSweepY =
                Math.max(previousEnd, newEnd)
                        + PLAYER_RADIUS
                        + VERT_SNAP_EPS;

        double best = floor
                ? Double.NEGATIVE_INFINITY
                : Double.POSITIVE_INFINITY;

        int bestTriangle = -1;

        ensureBvhStackCapacity(bvh.nodeCount);

        int stackSize = 0;
        bvhStack[stackSize++] = 0;

        while (stackSize > 0) {
            int node = bvhStack[--stackSize];

            if (
                    bvh.maxX[node] < x - PLAYER_RADIUS
                            || bvh.minX[node] > x + PLAYER_RADIUS
                            || bvh.maxY[node] < minimumSweepY
                            || bvh.minY[node] > maximumSweepY
                            || bvh.maxZ[node] < z - PLAYER_RADIUS
                            || bvh.minZ[node] > z + PLAYER_RADIUS
            ) {
                continue;
            }

            int triangleCount = bvh.count[node];

            if (triangleCount != 0) {
                int end = bvh.start[node] + triangleCount;

                for (
                        int orderIndex = bvh.start[node];
                        orderIndex < end;
                        orderIndex++
                ) {
                    int triangle = bvh.order[orderIndex];

                    if (
                            triangle == skipTriangle
                                    || collider.maxX[triangle]
                                    < x - PLAYER_RADIUS
                                    || collider.minX[triangle]
                                    > x + PLAYER_RADIUS
                                    || collider.maxY[triangle]
                                    < minimumSweepY
                                    || collider.minY[triangle]
                                    > maximumSweepY
                                    || collider.maxZ[triangle]
                                    < z - PLAYER_RADIUS
                                    || collider.minZ[triangle]
                                    > z + PLAYER_RADIUS
                    ) {
                        continue;
                    }

                    double hit = capsuleSurfaceContact(
                            collider,
                            triangle,
                            floor,
                            previousEnd,
                            newEnd
                    );

                    if (floor ? hit > best : hit < best) {
                        best = hit;
                        bestTriangle = triangle;
                    }
                }
            } else {
                bvhStack[stackSize++] = bvh.left[node];
                bvhStack[stackSize++] = bvh.right[node];
            }
        }

        lastSurfaceTriangle = bestTriangle;

        return best;
    }

    /**
     * Sweeps the spherical end of the player's vertical capsule against a
     * preclassified floor or ceiling surface.
     */
    private double capsuleSurfaceContact(
            MeshCollider collider,
            int triangle,
            boolean floor,
            double previousEnd,
            double newEnd
    ) {
        byte requiredFlag = floor ? TRI_FLOOR : TRI_CEILING;

        if ((collider.flags[triangle] & requiredFlag) == 0) {
            return floor
                    ? Double.NEGATIVE_INFINITY
                    : Double.POSITIVE_INFINITY;
        }

        double central = surfaceContactAt(
                collider,
                triangle,
                x,
                z,
                0.0,
                floor
        );

        if (isSweptSurfaceHit(
                central,
                previousEnd,
                newEnd,
                floor
        )) {
            return central;
        }

        /*
         * The four support samples are only evaluated when the central
         * sample misses. They are support points on the capsule rather
         * than five independent vertical rays.
         */
        double diagonal =
                PLAYER_RADIUS
                        * CAPSULE_DIAGONAL_SAMPLE
                        * INV_SQRT_2;

        double diagonalDistance2 =
                diagonal * diagonal * 2.0;

        double best = floor
                ? Double.NEGATIVE_INFINITY
                : Double.POSITIVE_INFINITY;

        best = selectSurfaceSupport(
                best,
                surfaceContactAt(
                        collider,
                        triangle,
                        x - diagonal,
                        z - diagonal,
                        diagonalDistance2,
                        floor
                ),
                floor
        );

        best = selectSurfaceSupport(
                best,
                surfaceContactAt(
                        collider,
                        triangle,
                        x + diagonal,
                        z - diagonal,
                        diagonalDistance2,
                        floor
                ),
                floor
        );

        best = selectSurfaceSupport(
                best,
                surfaceContactAt(
                        collider,
                        triangle,
                        x - diagonal,
                        z + diagonal,
                        diagonalDistance2,
                        floor
                ),
                floor
        );

        best = selectSurfaceSupport(
                best,
                surfaceContactAt(
                        collider,
                        triangle,
                        x + diagonal,
                        z + diagonal,
                        diagonalDistance2,
                        floor
                ),
                floor
        );

        closestPointOnTriangleXZ(
                collider,
                triangle,
                x,
                z,
                tmpClosestXZ
        );

        double closestOffsetX = tmpClosestXZ[0] - x;
        double closestOffsetZ = tmpClosestXZ[1] - z;

        double closestDistance2 =
                closestOffsetX * closestOffsetX
                        + closestOffsetZ * closestOffsetZ;

        if (closestDistance2 <= PLAYER_RADIUS2 + TRI_EPS) {
            best = selectSurfaceSupport(
                    best,
                    surfaceContactAt(
                            collider,
                            triangle,
                            tmpClosestXZ[0],
                            tmpClosestXZ[1],
                            Math.min(
                                    PLAYER_RADIUS2,
                                    closestDistance2
                            ),
                            floor
                    ),
                    floor
            );
        }

        if (
                isSweptSurfaceHit(
                        best,
                        previousEnd,
                        newEnd,
                        floor
                )
        ) {
            return best;
        }

        return floor
                ? Double.NEGATIVE_INFINITY
                : Double.POSITIVE_INFINITY;
    }

    private static double surfaceContactAt(
            MeshCollider collider,
            int triangle,
            double sampleX,
            double sampleZ,
            double horizontalDistance2,
            boolean floor
    ) {
        if (
                sampleX < collider.minX[triangle] - TRI_EPS
                        || sampleX
                        > collider.maxX[triangle] + TRI_EPS
                        || sampleZ
                        < collider.minZ[triangle] - TRI_EPS
                        || sampleZ
                        > collider.maxZ[triangle] + TRI_EPS
                        || !pointInTriangleXZ(
                        collider,
                        triangle,
                        sampleX,
                        sampleZ
                )
        ) {
            return floor
                    ? Double.NEGATIVE_INFINITY
                    : Double.POSITIVE_INFINITY;
        }

        double normalY = collider.normalY[triangle];

        if (Math.abs(normalY) <= TRI_EPS) {
            return floor
                    ? Double.NEGATIVE_INFINITY
                    : Double.POSITIVE_INFINITY;
        }

        double planeY = -(
                collider.normalX[triangle] * sampleX
                        + collider.normalZ[triangle] * sampleZ
                        + collider.planeD[triangle]
        ) / normalY;

        double sphereRise = Math.sqrt(
                Math.max(
                        0.0,
                        PLAYER_RADIUS2 - horizontalDistance2
                )
        );

        return floor
                ? planeY + sphereRise - PLAYER_RADIUS
                : planeY - sphereRise + PLAYER_RADIUS;
    }

    private static double selectSurfaceSupport(
            double current,
            double candidate,
            boolean floor
    ) {
        return floor
                ? Math.max(current, candidate)
                : Math.min(current, candidate);
    }

    private static boolean isSweptSurfaceHit(
            double candidate,
            double previousEnd,
            double newEnd,
            boolean floor
    ) {
        if (!Double.isFinite(candidate)) {
            return false;
        }

        if (floor) {
            return candidate <= previousEnd + VERT_SNAP_EPS
                    && candidate >= newEnd - VERT_SNAP_EPS;
        }

        return candidate >= previousEnd - VERT_SNAP_EPS
                && candidate <= newEnd + VERT_SNAP_EPS;
    }

    private double testAabbFloor(
            AABB bounds,
            double previousFeet,
            double newFeet
    ) {
        if (!circleOverlapsAabbXZ(
                x,
                z,
                PLAYER_RADIUS,
                bounds
        )) {
            return Double.NEGATIVE_INFINITY;
        }

        double top = bounds.maxY;

        return previousFeet >= top - VERT_SNAP_EPS
                && newFeet <= top + VERT_SNAP_EPS
                ? top
                : Double.NEGATIVE_INFINITY;
    }

    private double testAabbCeiling(
            AABB bounds,
            double previousHead,
            double newHead
    ) {
        if (!circleOverlapsAabbXZ(
                x,
                z,
                PLAYER_RADIUS,
                bounds
        )) {
            return Double.POSITIVE_INFINITY;
        }

        double bottom = bounds.minY;

        return previousHead <= bottom + VERT_SNAP_EPS
                && newHead >= bottom - VERT_SNAP_EPS
                ? bottom
                : Double.POSITIVE_INFINITY;
    }

    private static boolean circleOverlapsAabbXZ(
            double centerX,
            double centerZ,
            double radius,
            AABB bounds
    ) {
        double closestX = clamp(
                centerX,
                bounds.minX,
                bounds.maxX
        );

        double closestZ = clamp(
                centerZ,
                bounds.minZ,
                bounds.maxZ
        );

        double offsetX = centerX - closestX;
        double offsetZ = centerZ - closestZ;

        return offsetX * offsetX + offsetZ * offsetZ
                <= radius * radius;
    }

    private void clearFloorCache() {
        cachedFloorObject = null;
        cachedFloorMesh = null;
        cachedFloorTriangle = -1;
        cachedFloorIsAabb = false;
        cachedFloorHeight = Double.NEGATIVE_INFINITY;
    }

    private boolean intersectsPlayerCapsuleAabb(AABB bounds) {
        return y + HEIGHT > bounds.minY
                && y < bounds.maxY
                && circleOverlapsAabbXZ(
                x,
                z,
                PLAYER_RADIUS,
                bounds
        );
    }

    public boolean collidesWith(GameObject object) {
        if (object == null) {
            return false;
        }

        AABB bounds = getCollisionBounds(object);

        if (
                bounds == null
                        || !intersectsPlayerCapsuleAabb(bounds)
        ) {
            return false;
        }

        MeshCollider collider = getMeshCollider(object, bounds);

        return collider == null || capsuleOverlapsMesh(collider);
    }

    private boolean capsuleOverlapsMesh(MeshCollider collider) {
        MeshBvh bvh = collider.allBvh;

        if (bvh == null) {
            return false;
        }

        ensureBvhStackCapacity(bvh.nodeCount);

        int stackSize = 0;
        bvhStack[stackSize++] = 0;

        while (stackSize > 0) {
            int node = bvhStack[--stackSize];

            if (
                    bvh.maxX[node] < x - PLAYER_RADIUS
                            || bvh.minX[node] > x + PLAYER_RADIUS
                            || bvh.maxY[node] <= y
                            || bvh.minY[node] >= y + HEIGHT
                            || bvh.maxZ[node] < z - PLAYER_RADIUS
                            || bvh.minZ[node] > z + PLAYER_RADIUS
            ) {
                continue;
            }

            int triangleCount = bvh.count[node];

            if (triangleCount != 0) {
                int end = bvh.start[node] + triangleCount;

                for (
                        int orderIndex = bvh.start[node];
                        orderIndex < end;
                        orderIndex++
                ) {
                    int triangle = bvh.order[orderIndex];

                    if (
                            collider.maxY[triangle] <= y
                                    || collider.minY[triangle]
                                    >= y + HEIGHT
                                    || collider.maxX[triangle]
                                    < x - PLAYER_RADIUS
                                    || collider.minX[triangle]
                                    > x + PLAYER_RADIUS
                                    || collider.maxZ[triangle]
                                    < z - PLAYER_RADIUS
                                    || collider.minZ[triangle]
                                    > z + PLAYER_RADIUS
                    ) {
                        continue;
                    }

                    closestPointOnTriangleXZ(
                            collider,
                            triangle,
                            x,
                            z,
                            tmpClosestXZ
                    );

                    double offsetX = x - tmpClosestXZ[0];
                    double offsetZ = z - tmpClosestXZ[1];

                    if (
                            offsetX * offsetX
                                    + offsetZ * offsetZ
                                    <= PLAYER_RADIUS2
                    ) {
                        return true;
                    }
                }
            } else {
                bvhStack[stackSize++] = bvh.left[node];
                bvhStack[stackSize++] = bvh.right[node];
            }
        }

        return false;
    }

    private void resetMovementDeltas() {
        dx = 0.0;
        dy = 0.0;
        dz = 0.0;
    }

    private void recomputeViewOrigin(double delta) {
        double pivotX = x + bobX;
        double pivotZ = z;
        double thirdPersonPivotY =
                y + EYE_HEIGHT + bobY;
        double firstPersonY =
                y + HEAD_HEIGHT + bobY;

        if (mode == Mode.FIRST_PERSON) {
            double planarForwardX = -Math.sin(yaw);
            double planarForwardZ = Math.cos(yaw);

            eyeX =
                    pivotX
                            + planarForwardX
                            * FP_FORWARD_OFFSET;

            eyeY = firstPersonY;

            eyeZ =
                    pivotZ
                            + planarForwardZ
                            * FP_FORWARD_OFFSET;

            viewYaw = yaw;
            viewPitch = pitch;
            return;
        }

        updateBasisIfNeeded();

        double desiredX =
                pivotX
                        - fwdX * thirdPersonDistance
                        + rightX * shoulderOffset;

        double desiredY =
                thirdPersonPivotY
                        - fwdY * thirdPersonDistance
                        + rightY * shoulderOffset;

        double desiredZ =
                pivotZ
                        - fwdZ * thirdPersonDistance
                        + rightZ * shoulderOffset;

        double[] adjusted = resolveThirdPersonCameraCollision(
                pivotX,
                thirdPersonPivotY,
                pivotZ,
                desiredX,
                desiredY,
                desiredZ,
                delta
        );

        double targetX = adjusted[0];
        double targetY = adjusted[1];
        double targetZ = adjusted[2];

        if (delta <= 0.0) {
            eyeX = targetX;
            eyeY = targetY;
            eyeZ = targetZ;
        } else {
            double currentDistanceSquared = dist3Squared(
                    eyeX,
                    eyeY,
                    eyeZ,
                    pivotX,
                    thirdPersonPivotY,
                    pivotZ
            );

            double targetDistanceSquared = dist3Squared(
                    targetX,
                    targetY,
                    targetZ,
                    pivotX,
                    thirdPersonPivotY,
                    pivotZ
            );

            double smoothing =
                    targetDistanceSquared
                            < currentDistanceSquared
                            ? TPS_SMOOTH_IN
                            : TPS_SMOOTH_OUT;

            double amount =
                    1.0 - Math.exp(-delta * smoothing);

            eyeX += (targetX - eyeX) * amount;
            eyeY += (targetY - eyeY) * amount;
            eyeZ += (targetZ - eyeZ) * amount;
        }

        viewYaw = yaw;
        viewPitch = pitch;
    }

    private static double dist3Squared(
            double firstX,
            double firstY,
            double firstZ,
            double secondX,
            double secondY,
            double secondZ
    ) {
        double offsetX = firstX - secondX;
        double offsetY = firstY - secondY;
        double offsetZ = firstZ - secondZ;

        return offsetX * offsetX
                + offsetY * offsetY
                + offsetZ * offsetZ;
    }

    private double[] resolveThirdPersonCameraCollision(
            double pivotX,
            double pivotY,
            double pivotZ,
            double desiredX,
            double desiredY,
            double desiredZ,
            double delta
    ) {
        if (gameEngine == null) {
            setTmpCamera(desiredX, desiredY, desiredZ);
            return tmpCam;
        }

        cameraCollisionElapsed += Math.max(0.0, delta);

        if (
                cameraCollisionCacheValid
                        && cameraCollisionElapsed
                        < CAMERA_RECHECK_INTERVAL
                        && dist3Squared(
                        pivotX,
                        pivotY,
                        pivotZ,
                        cachedCameraPivotX,
                        cachedCameraPivotY,
                        cachedCameraPivotZ
                ) <= CAMERA_TINY_MOVE2
                        && dist3Squared(
                        desiredX,
                        desiredY,
                        desiredZ,
                        cachedCameraDesiredX,
                        cachedCameraDesiredY,
                        cachedCameraDesiredZ
                ) <= CAMERA_TINY_MOVE2
        ) {
            applyCameraHitDistance(
                    pivotX,
                    pivotY,
                    pivotZ,
                    desiredX,
                    desiredY,
                    desiredZ,
                    previousCameraHitDistance
            );

            return tmpCam;
        }

        double directionX = desiredX - pivotX;
        double directionY = desiredY - pivotY;
        double directionZ = desiredZ - pivotZ;

        double distance = Math.sqrt(
                directionX * directionX
                        + directionY * directionY
                        + directionZ * directionZ
        );

        if (distance < 1e-12) {
            storeCameraCollisionQuery(
                    pivotX,
                    pivotY,
                    pivotZ,
                    desiredX,
                    desiredY,
                    desiredZ,
                    Double.POSITIVE_INFINITY,
                    null
            );

            setTmpCamera(desiredX, desiredY, desiredZ);
            return tmpCam;
        }

        double inverseDistance = 1.0 / distance;

        directionX *= inverseDistance;
        directionY *= inverseDistance;
        directionZ *= inverseDistance;

        double segmentMinimumX =
                Math.min(pivotX, desiredX) - CAM_WALL_PAD;
        double segmentMaximumX =
                Math.max(pivotX, desiredX) + CAM_WALL_PAD;
        double segmentMinimumY =
                Math.min(pivotY, desiredY) - CAM_WALL_PAD;
        double segmentMaximumY =
                Math.max(pivotY, desiredY) + CAM_WALL_PAD;
        double segmentMinimumZ =
                Math.min(pivotZ, desiredZ) - CAM_WALL_PAD;
        double segmentMaximumZ =
                Math.max(pivotZ, desiredZ) + CAM_WALL_PAD;

        gameEngine.queryNearbyCollidersXZ(
                segmentMinimumX,
                segmentMaximumX,
                segmentMinimumZ,
                segmentMaximumZ,
                nearby
        );

        int previousBlockerIndex =
                findIdentity(nearby, previousCameraBlocker);

        double bestHit = Double.POSITIVE_INFINITY;
        GameObject bestBlocker = null;

        /*
         * Temporal coherence: test the previous blocker before every
         * other candidate.
         */
        if (previousBlockerIndex >= 0) {
            GameObject object =
                    nearby.get(previousBlockerIndex);

            double hit = cameraObjectHitDistance(
                    object,
                    pivotX,
                    pivotY,
                    pivotZ,
                    directionX,
                    directionY,
                    directionZ,
                    distance,
                    segmentMinimumX,
                    segmentMaximumX,
                    segmentMinimumY,
                    segmentMaximumY,
                    segmentMinimumZ,
                    segmentMaximumZ
            );

            if (hit < bestHit) {
                bestHit = hit;
                bestBlocker = object;
            }
        }

        for (int i = 0, count = nearby.size(); i < count; i++) {
            if (i == previousBlockerIndex) {
                continue;
            }

            GameObject object = nearby.get(i);

            double maximumTestDistance =
                    Math.min(distance, bestHit);

            double hit = cameraObjectHitDistance(
                    object,
                    pivotX,
                    pivotY,
                    pivotZ,
                    directionX,
                    directionY,
                    directionZ,
                    maximumTestDistance,
                    segmentMinimumX,
                    segmentMaximumX,
                    segmentMinimumY,
                    segmentMaximumY,
                    segmentMinimumZ,
                    segmentMaximumZ
            );

            if (hit < bestHit) {
                bestHit = hit;
                bestBlocker = object;
            }
        }

        storeCameraCollisionQuery(
                pivotX,
                pivotY,
                pivotZ,
                desiredX,
                desiredY,
                desiredZ,
                bestHit,
                bestBlocker
        );

        applyCameraHitDistance(
                pivotX,
                pivotY,
                pivotZ,
                desiredX,
                desiredY,
                desiredZ,
                bestHit
        );

        return tmpCam;
    }

    private double cameraObjectHitDistance(
            GameObject object,
            double originX,
            double originY,
            double originZ,
            double directionX,
            double directionY,
            double directionZ,
            double maximumDistance,
            double segmentMinimumX,
            double segmentMaximumX,
            double segmentMinimumY,
            double segmentMaximumY,
            double segmentMinimumZ,
            double segmentMaximumZ
    ) {
        if (object == null || isPlayerOrChild(object)) {
            return Double.POSITIVE_INFINITY;
        }

        AABB collisionBounds = getCollisionBounds(object);

        if (
                collisionBounds == null
                        || shouldIgnoreColliderForPlayer(
                        object,
                        collisionBounds
                )
                        || collisionBounds.maxX + CAM_WALL_PAD
                        < segmentMinimumX
                        || collisionBounds.minX - CAM_WALL_PAD
                        > segmentMaximumX
                        || collisionBounds.maxY + CAM_WALL_PAD
                        < segmentMinimumY
                        || collisionBounds.minY - CAM_WALL_PAD
                        > segmentMaximumY
                        || collisionBounds.maxZ + CAM_WALL_PAD
                        < segmentMinimumZ
                        || collisionBounds.minZ - CAM_WALL_PAD
                        > segmentMaximumZ
        ) {
            return Double.POSITIVE_INFINITY;
        }

        if (object instanceof CollisionGeometryProvider) {
            AABB simplified =
                    ((CollisionGeometryProvider) object)
                            .getCameraCollisionAABB();

            if (simplified != null) {
                return rayAabbHitDistanceExpanded(
                        originX,
                        originY,
                        originZ,
                        directionX,
                        directionY,
                        directionZ,
                        maximumDistance,
                        simplified,
                        CAM_WALL_PAD
                );
            }
        }

        MeshCollider collider =
                getMeshCollider(object, collisionBounds);

        if (collider != null) {
            return rayMeshHitDistance(
                    collider,
                    originX,
                    originY,
                    originZ,
                    directionX,
                    directionY,
                    directionZ,
                    maximumDistance,
                    segmentMinimumX,
                    segmentMaximumX,
                    segmentMinimumY,
                    segmentMaximumY,
                    segmentMinimumZ,
                    segmentMaximumZ
            );
        }

        return rayAabbHitDistanceExpanded(
                originX,
                originY,
                originZ,
                directionX,
                directionY,
                directionZ,
                maximumDistance,
                collisionBounds,
                CAM_WALL_PAD
        );
    }

    private double rayMeshHitDistance(
            MeshCollider collider,
            double originX,
            double originY,
            double originZ,
            double directionX,
            double directionY,
            double directionZ,
            double maximumDistance,
            double segmentMinimumX,
            double segmentMaximumX,
            double segmentMinimumY,
            double segmentMaximumY,
            double segmentMinimumZ,
            double segmentMaximumZ
    ) {
        MeshBvh bvh = collider.allBvh;

        if (bvh == null) {
            return Double.POSITIVE_INFINITY;
        }

        double bestHit = maximumDistance;
        boolean hitAnything = false;

        ensureBvhStackCapacity(bvh.nodeCount);

        int stackSize = 0;
        bvhStack[stackSize++] = 0;

        while (stackSize > 0) {
            int node = bvhStack[--stackSize];

            if (
                    bvh.maxX[node] + CAM_WALL_PAD
                            < segmentMinimumX
                            || bvh.minX[node] - CAM_WALL_PAD
                            > segmentMaximumX
                            || bvh.maxY[node] + CAM_WALL_PAD
                            < segmentMinimumY
                            || bvh.minY[node] - CAM_WALL_PAD
                            > segmentMaximumY
                            || bvh.maxZ[node] + CAM_WALL_PAD
                            < segmentMinimumZ
                            || bvh.minZ[node] - CAM_WALL_PAD
                            > segmentMaximumZ
                            || rayBoundsHitDistance(
                            originX,
                            originY,
                            originZ,
                            directionX,
                            directionY,
                            directionZ,
                            bestHit,
                            bvh.minX[node] - CAM_WALL_PAD,
                            bvh.maxX[node] + CAM_WALL_PAD,
                            bvh.minY[node] - CAM_WALL_PAD,
                            bvh.maxY[node] + CAM_WALL_PAD,
                            bvh.minZ[node] - CAM_WALL_PAD,
                            bvh.maxZ[node] + CAM_WALL_PAD
                    ) == Double.POSITIVE_INFINITY
            ) {
                continue;
            }

            int triangleCount = bvh.count[node];

            if (triangleCount != 0) {
                int end = bvh.start[node] + triangleCount;

                for (
                        int orderIndex = bvh.start[node];
                        orderIndex < end;
                        orderIndex++
                ) {
                    int triangle = bvh.order[orderIndex];

                    if (
                            collider.maxX[triangle]
                                    + CAM_WALL_PAD
                                    < segmentMinimumX
                                    || collider.minX[triangle]
                                    - CAM_WALL_PAD
                                    > segmentMaximumX
                                    || collider.maxY[triangle]
                                    + CAM_WALL_PAD
                                    < segmentMinimumY
                                    || collider.minY[triangle]
                                    - CAM_WALL_PAD
                                    > segmentMaximumY
                                    || collider.maxZ[triangle]
                                    + CAM_WALL_PAD
                                    < segmentMinimumZ
                                    || collider.minZ[triangle]
                                    - CAM_WALL_PAD
                                    > segmentMaximumZ
                    ) {
                        continue;
                    }

                    double hit = rayTriangleHitDistance(
                            collider,
                            triangle,
                            originX,
                            originY,
                            originZ,
                            directionX,
                            directionY,
                            directionZ,
                            bestHit
                    );

                    if (hit <= bestHit) {
                        bestHit = hit;
                        hitAnything = true;
                    }
                }
            } else {
                int left = bvh.left[node];
                int right = bvh.right[node];

                double leftHit = rayBoundsHitDistance(
                        originX,
                        originY,
                        originZ,
                        directionX,
                        directionY,
                        directionZ,
                        bestHit,
                        bvh.minX[left] - CAM_WALL_PAD,
                        bvh.maxX[left] + CAM_WALL_PAD,
                        bvh.minY[left] - CAM_WALL_PAD,
                        bvh.maxY[left] + CAM_WALL_PAD,
                        bvh.minZ[left] - CAM_WALL_PAD,
                        bvh.maxZ[left] + CAM_WALL_PAD
                );

                double rightHit = rayBoundsHitDistance(
                        originX,
                        originY,
                        originZ,
                        directionX,
                        directionY,
                        directionZ,
                        bestHit,
                        bvh.minX[right] - CAM_WALL_PAD,
                        bvh.maxX[right] + CAM_WALL_PAD,
                        bvh.minY[right] - CAM_WALL_PAD,
                        bvh.maxY[right] + CAM_WALL_PAD,
                        bvh.minZ[right] - CAM_WALL_PAD,
                        bvh.maxZ[right] + CAM_WALL_PAD
                );

                if (leftHit < rightHit) {
                    if (rightHit != Double.POSITIVE_INFINITY) {
                        bvhStack[stackSize++] = right;
                    }

                    if (leftHit != Double.POSITIVE_INFINITY) {
                        bvhStack[stackSize++] = left;
                    }
                } else {
                    if (leftHit != Double.POSITIVE_INFINITY) {
                        bvhStack[stackSize++] = left;
                    }

                    if (rightHit != Double.POSITIVE_INFINITY) {
                        bvhStack[stackSize++] = right;
                    }
                }
            }
        }

        return hitAnything
                ? bestHit
                : Double.POSITIVE_INFINITY;
    }

    private static double rayTriangleHitDistance(
            MeshCollider collider,
            int triangle,
            double originX,
            double originY,
            double originZ,
            double directionX,
            double directionY,
            double directionZ,
            double maximumDistance
    ) {
        int base = triangle * 9;

        double ax = collider.xyz[base];
        double ay = collider.xyz[base + 1];
        double az = collider.xyz[base + 2];

        double bx = collider.xyz[base + 3];
        double by = collider.xyz[base + 4];
        double bz = collider.xyz[base + 5];

        double cx = collider.xyz[base + 6];
        double cy = collider.xyz[base + 7];
        double cz = collider.xyz[base + 8];

        double edge1X = bx - ax;
        double edge1Y = by - ay;
        double edge1Z = bz - az;

        double edge2X = cx - ax;
        double edge2Y = cy - ay;
        double edge2Z = cz - az;

        double perpendicularX =
                directionY * edge2Z
                        - directionZ * edge2Y;

        double perpendicularY =
                directionZ * edge2X
                        - directionX * edge2Z;

        double perpendicularZ =
                directionX * edge2Y
                        - directionY * edge2X;

        double determinant =
                edge1X * perpendicularX
                        + edge1Y * perpendicularY
                        + edge1Z * perpendicularZ;

        if (Math.abs(determinant) < TRI_EPS) {
            return Double.POSITIVE_INFINITY;
        }

        double inverseDeterminant = 1.0 / determinant;

        double translatedX = originX - ax;
        double translatedY = originY - ay;
        double translatedZ = originZ - az;

        double barycentricU = (
                translatedX * perpendicularX
                        + translatedY * perpendicularY
                        + translatedZ * perpendicularZ
        ) * inverseDeterminant;

        if (
                barycentricU < -TRI_EPS
                        || barycentricU > 1.0 + TRI_EPS
        ) {
            return Double.POSITIVE_INFINITY;
        }

        double crossX =
                translatedY * edge1Z
                        - translatedZ * edge1Y;

        double crossY =
                translatedZ * edge1X
                        - translatedX * edge1Z;

        double crossZ =
                translatedX * edge1Y
                        - translatedY * edge1X;

        double barycentricV = (
                directionX * crossX
                        + directionY * crossY
                        + directionZ * crossZ
        ) * inverseDeterminant;

        if (
                barycentricV < -TRI_EPS
                        || barycentricU + barycentricV
                        > 1.0 + TRI_EPS
        ) {
            return Double.POSITIVE_INFINITY;
        }

        double distance = (
                edge2X * crossX
                        + edge2Y * crossY
                        + edge2Z * crossZ
        ) * inverseDeterminant;

        return distance >= 0.0
                && distance <= maximumDistance
                ? distance
                : Double.POSITIVE_INFINITY;
    }

    private void applyCameraHitDistance(
            double pivotX,
            double pivotY,
            double pivotZ,
            double desiredX,
            double desiredY,
            double desiredZ,
            double hitDistance
    ) {
        double directionX = desiredX - pivotX;
        double directionY = desiredY - pivotY;
        double directionZ = desiredZ - pivotZ;

        double distance = Math.sqrt(
                directionX * directionX
                        + directionY * directionY
                        + directionZ * directionZ
        );

        if (
                distance <= RAY_EPS
                        || hitDistance
                        == Double.POSITIVE_INFINITY
        ) {
            setTmpCamera(desiredX, desiredY, desiredZ);
            return;
        }

        double allowedDistance = Math.max(
                CAM_MIN_DIST,
                hitDistance - CAM_WALL_PAD
        );

        if (allowedDistance >= distance) {
            setTmpCamera(desiredX, desiredY, desiredZ);
            return;
        }

        double scale = allowedDistance / distance;

        setTmpCamera(
                pivotX + directionX * scale,
                pivotY + directionY * scale,
                pivotZ + directionZ * scale
        );
    }

    private void storeCameraCollisionQuery(
            double pivotX,
            double pivotY,
            double pivotZ,
            double desiredX,
            double desiredY,
            double desiredZ,
            double hitDistance,
            GameObject blocker
    ) {
        cachedCameraPivotX = pivotX;
        cachedCameraPivotY = pivotY;
        cachedCameraPivotZ = pivotZ;

        cachedCameraDesiredX = desiredX;
        cachedCameraDesiredY = desiredY;
        cachedCameraDesiredZ = desiredZ;

        previousCameraHitDistance = hitDistance;
        previousCameraBlocker = blocker;

        cameraCollisionElapsed = 0.0;
        cameraCollisionCacheValid = true;
    }

    private void invalidateCameraCollisionCache() {
        cameraCollisionCacheValid = false;
        cameraCollisionElapsed = Double.POSITIVE_INFINITY;
        previousCameraBlocker = null;
        previousCameraHitDistance = Double.POSITIVE_INFINITY;
    }

    private void setTmpCamera(
            double valueX,
            double valueY,
            double valueZ
    ) {
        tmpCam[0] = valueX;
        tmpCam[1] = valueY;
        tmpCam[2] = valueZ;
    }

    private static int findIdentity(
            ArrayList<GameObject> objects,
            GameObject target
    ) {
        if (target == null) {
            return -1;
        }

        for (int i = 0, count = objects.size(); i < count; i++) {
            if (objects.get(i) == target) {
                return i;
            }
        }

        return -1;
    }

    private static double rayAabbHitDistanceExpanded(
            double originX,
            double originY,
            double originZ,
            double directionX,
            double directionY,
            double directionZ,
            double maximumDistance,
            AABB bounds,
            double padding
    ) {
        return rayBoundsHitDistance(
                originX,
                originY,
                originZ,
                directionX,
                directionY,
                directionZ,
                maximumDistance,
                bounds.minX - padding,
                bounds.maxX + padding,
                bounds.minY - padding,
                bounds.maxY + padding,
                bounds.minZ - padding,
                bounds.maxZ + padding
        );
    }

    private static double rayBoundsHitDistance(
            double originX,
            double originY,
            double originZ,
            double directionX,
            double directionY,
            double directionZ,
            double maximumDistance,
            double minimumX,
            double maximumX,
            double minimumY,
            double maximumY,
            double minimumZ,
            double maximumZ
    ) {
        double minimumTime = 0.0;
        double maximumTime = maximumDistance;

        if (Math.abs(directionX) < RAY_EPS) {
            if (originX < minimumX || originX > maximumX) {
                return Double.POSITIVE_INFINITY;
            }
        } else {
            double inverseDirection = 1.0 / directionX;

            double time1 =
                    (minimumX - originX) * inverseDirection;

            double time2 =
                    (maximumX - originX) * inverseDirection;

            if (time1 > time2) {
                double temporary = time1;
                time1 = time2;
                time2 = temporary;
            }

            minimumTime = Math.max(minimumTime, time1);
            maximumTime = Math.min(maximumTime, time2);

            if (minimumTime > maximumTime) {
                return Double.POSITIVE_INFINITY;
            }
        }

        if (Math.abs(directionY) < RAY_EPS) {
            if (originY < minimumY || originY > maximumY) {
                return Double.POSITIVE_INFINITY;
            }
        } else {
            double inverseDirection = 1.0 / directionY;

            double time1 =
                    (minimumY - originY) * inverseDirection;

            double time2 =
                    (maximumY - originY) * inverseDirection;

            if (time1 > time2) {
                double temporary = time1;
                time1 = time2;
                time2 = temporary;
            }

            minimumTime = Math.max(minimumTime, time1);
            maximumTime = Math.min(maximumTime, time2);

            if (minimumTime > maximumTime) {
                return Double.POSITIVE_INFINITY;
            }
        }

        if (Math.abs(directionZ) < RAY_EPS) {
            if (originZ < minimumZ || originZ > maximumZ) {
                return Double.POSITIVE_INFINITY;
            }
        } else {
            double inverseDirection = 1.0 / directionZ;

            double time1 =
                    (minimumZ - originZ) * inverseDirection;

            double time2 =
                    (maximumZ - originZ) * inverseDirection;

            if (time1 > time2) {
                double temporary = time1;
                time1 = time2;
                time2 = temporary;
            }

            minimumTime = Math.max(minimumTime, time1);
            maximumTime = Math.min(maximumTime, time2);

            if (minimumTime > maximumTime) {
                return Double.POSITIVE_INFINITY;
            }
        }

        return maximumTime < 0.0
                ? Double.POSITIVE_INFINITY
                : minimumTime >= 0.0
                ? minimumTime
                : maximumTime;
    }

    private void ensureBvhStackCapacity(int required) {
        if (bvhStack.length >= required) {
            return;
        }

        int newLength = bvhStack.length;

        while (newLength < required) {
            newLength <<= 1;
        }

        bvhStack = new int[newLength];
    }

    private static boolean pointInTriangleXZ(
            MeshCollider collider,
            int triangle,
            double pointX,
            double pointZ
    ) {
        double inverseDenominator =
                collider.inverseXzDenominator[triangle];

        if (!Double.isFinite(inverseDenominator)) {
            return false;
        }

        int base = triangle * 9;

        double ax = collider.xyz[base];
        double az = collider.xyz[base + 2];

        double bx = collider.xyz[base + 3];
        double bz = collider.xyz[base + 5];

        double cx = collider.xyz[base + 6];
        double cz = collider.xyz[base + 8];

        double relativeX = pointX - cx;
        double relativeZ = pointZ - cz;

        double weightA = (
                (bz - cz) * relativeX
                        + (cx - bx) * relativeZ
        ) * inverseDenominator;

        if (
                weightA < -TRI_EPS
                        || weightA > 1.0 + TRI_EPS
        ) {
            return false;
        }

        double weightB = (
                (cz - az) * relativeX
                        + (ax - cx) * relativeZ
        ) * inverseDenominator;

        return weightB >= -TRI_EPS
                && weightA + weightB <= 1.0 + TRI_EPS;
    }

    private static void closestPointOnTriangleXZ(
            MeshCollider collider,
            int triangle,
            double pointX,
            double pointZ,
            double[] result
    ) {
        if (
                pointInTriangleXZ(
                        collider,
                        triangle,
                        pointX,
                        pointZ
                )
        ) {
            result[0] = pointX;
            result[1] = pointZ;
            return;
        }

        int base = triangle * 9;

        double ax = collider.xyz[base];
        double az = collider.xyz[base + 2];

        double bx = collider.xyz[base + 3];
        double bz = collider.xyz[base + 5];

        double cx = collider.xyz[base + 6];
        double cz = collider.xyz[base + 8];

        double bestX = ax;
        double bestZ = az;
        double bestDistance2 = Double.POSITIVE_INFINITY;

        double edgeX = bx - ax;
        double edgeZ = bz - az;
        double edgeLength2 =
                edgeX * edgeX + edgeZ * edgeZ;

        double edgeT = edgeLength2 <= TRI_EPS2
                ? 0.0
                : clamp01(
                ((pointX - ax) * edgeX
                        + (pointZ - az) * edgeZ)
                        / edgeLength2
        );

        double candidateX = ax + edgeX * edgeT;
        double candidateZ = az + edgeZ * edgeT;
        double offsetX = pointX - candidateX;
        double offsetZ = pointZ - candidateZ;
        double distance2 =
                offsetX * offsetX + offsetZ * offsetZ;

        if (distance2 < bestDistance2) {
            bestDistance2 = distance2;
            bestX = candidateX;
            bestZ = candidateZ;
        }

        edgeX = cx - bx;
        edgeZ = cz - bz;
        edgeLength2 = edgeX * edgeX + edgeZ * edgeZ;

        edgeT = edgeLength2 <= TRI_EPS2
                ? 0.0
                : clamp01(
                ((pointX - bx) * edgeX
                        + (pointZ - bz) * edgeZ)
                        / edgeLength2
        );

        candidateX = bx + edgeX * edgeT;
        candidateZ = bz + edgeZ * edgeT;
        offsetX = pointX - candidateX;
        offsetZ = pointZ - candidateZ;
        distance2 = offsetX * offsetX + offsetZ * offsetZ;

        if (distance2 < bestDistance2) {
            bestDistance2 = distance2;
            bestX = candidateX;
            bestZ = candidateZ;
        }

        edgeX = ax - cx;
        edgeZ = az - cz;
        edgeLength2 = edgeX * edgeX + edgeZ * edgeZ;

        edgeT = edgeLength2 <= TRI_EPS2
                ? 0.0
                : clamp01(
                ((pointX - cx) * edgeX
                        + (pointZ - cz) * edgeZ)
                        / edgeLength2
        );

        candidateX = cx + edgeX * edgeT;
        candidateZ = cz + edgeZ * edgeT;
        offsetX = pointX - candidateX;
        offsetZ = pointZ - candidateZ;
        distance2 = offsetX * offsetX + offsetZ * offsetZ;

        if (distance2 < bestDistance2) {
            bestX = candidateX;
            bestZ = candidateZ;
        }

        result[0] = bestX;
        result[1] = bestZ;
    }

    private static boolean hasValidDetailedCollider(
            double[][] vertices,
            int[][] faces
    ) {
        return vertices != null
                && vertices.length > 0
                && faces != null
                && faces.length > 0;
    }

    private static boolean validTriIndex(
            double[][] vertices,
            int index
    ) {
        return index >= 0
                && index < vertices.length
                && vertices[index] != null
                && vertices[index].length >= 3;
    }

    private static double clamp(
            double value,
            double minimum,
            double maximum
    ) {
        return value < minimum
                ? minimum
                : value > maximum
                ? maximum
                : value;
    }

    private static double clamp01(double value) {
        return value <= 0.0
                ? 0.0
                : value >= 1.0
                ? 1.0
                : value;
    }

    private static final class MeshCollider {

        final double[][] sourceVertices;
        final int[][] sourceFaces;
        final long sourceRevision;
        final long sourceProbe;

        final boolean hasSourceBounds;
        final double sourceMinX;
        final double sourceMaxX;
        final double sourceMinY;
        final double sourceMaxY;
        final double sourceMinZ;
        final double sourceMaxZ;

        final int triangleCount;
        final double[] xyz;

        final double[] minX;
        final double[] maxX;
        final double[] minY;
        final double[] maxY;
        final double[] minZ;
        final double[] maxZ;

        final double[] normalX;
        final double[] normalY;
        final double[] normalZ;
        final double[] planeD;
        final double[] inverseXzDenominator;
        final byte[] flags;

        final MeshBvh allBvh;
        final MeshBvh wallBvh;
        final MeshBvh floorBvh;
        final MeshBvh ceilingBvh;

        private MeshCollider(
                double[][] sourceVertices,
                int[][] sourceFaces,
                AABB sourceBounds,
                long sourceRevision,
                long sourceProbe,
                int triangleCount,
                double[] xyz,
                double[] minX,
                double[] maxX,
                double[] minY,
                double[] maxY,
                double[] minZ,
                double[] maxZ,
                double[] normalX,
                double[] normalY,
                double[] normalZ,
                double[] planeD,
                double[] inverseXzDenominator,
                byte[] flags
        ) {
            this.sourceVertices = sourceVertices;
            this.sourceFaces = sourceFaces;
            this.sourceRevision = sourceRevision;
            this.sourceProbe = sourceProbe;

            this.hasSourceBounds = sourceBounds != null;

            this.sourceMinX =
                    sourceBounds == null ? 0.0 : sourceBounds.minX;
            this.sourceMaxX =
                    sourceBounds == null ? 0.0 : sourceBounds.maxX;
            this.sourceMinY =
                    sourceBounds == null ? 0.0 : sourceBounds.minY;
            this.sourceMaxY =
                    sourceBounds == null ? 0.0 : sourceBounds.maxY;
            this.sourceMinZ =
                    sourceBounds == null ? 0.0 : sourceBounds.minZ;
            this.sourceMaxZ =
                    sourceBounds == null ? 0.0 : sourceBounds.maxZ;

            this.triangleCount = triangleCount;
            this.xyz = xyz;

            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.minZ = minZ;
            this.maxZ = maxZ;

            this.normalX = normalX;
            this.normalY = normalY;
            this.normalZ = normalZ;
            this.planeD = planeD;
            this.inverseXzDenominator =
                    inverseXzDenominator;
            this.flags = flags;

            this.allBvh = MeshBvh.build(this, (byte) 0);
            this.wallBvh = MeshBvh.build(this, TRI_WALL);
            this.floorBvh = MeshBvh.build(this, TRI_FLOOR);
            this.ceilingBvh =
                    MeshBvh.build(this, TRI_CEILING);
        }

        static MeshCollider build(
                double[][] vertices,
                int[][] faces,
                AABB sourceBounds,
                long revision,
                long probe
        ) {
            int validCount = 0;

            for (int i = 0; i < faces.length; i++) {
                int[] face = faces[i];

                if (
                        face != null
                                && face.length >= 3
                                && validTriIndex(
                                vertices,
                                face[0]
                        )
                                && validTriIndex(
                                vertices,
                                face[1]
                        )
                                && validTriIndex(
                                vertices,
                                face[2]
                        )
                                && !isDegenerate(
                                vertices[face[0]],
                                vertices[face[1]],
                                vertices[face[2]]
                        )
                ) {
                    validCount++;
                }
            }

            if (validCount == 0) {
                return null;
            }

            double[] xyz = new double[validCount * 9];

            double[] minX = new double[validCount];
            double[] maxX = new double[validCount];
            double[] minY = new double[validCount];
            double[] maxY = new double[validCount];
            double[] minZ = new double[validCount];
            double[] maxZ = new double[validCount];

            double[] normalX = new double[validCount];
            double[] normalY = new double[validCount];
            double[] normalZ = new double[validCount];
            double[] planeD = new double[validCount];

            double[] inverseXzDenominator =
                    new double[validCount];

            byte[] flags = new byte[validCount];

            int triangle = 0;

            for (int i = 0; i < faces.length; i++) {
                int[] face = faces[i];

                if (
                        face == null
                                || face.length < 3
                                || !validTriIndex(
                                vertices,
                                face[0]
                        )
                                || !validTriIndex(
                                vertices,
                                face[1]
                        )
                                || !validTriIndex(
                                vertices,
                                face[2]
                        )
                ) {
                    continue;
                }

                double[] a = vertices[face[0]];
                double[] b = vertices[face[1]];
                double[] c = vertices[face[2]];

                double edge1X = b[0] - a[0];
                double edge1Y = b[1] - a[1];
                double edge1Z = b[2] - a[2];

                double edge2X = c[0] - a[0];
                double edge2Y = c[1] - a[1];
                double edge2Z = c[2] - a[2];

                double rawNormalX =
                        edge1Y * edge2Z
                                - edge1Z * edge2Y;

                double rawNormalY =
                        edge1Z * edge2X
                                - edge1X * edge2Z;

                double rawNormalZ =
                        edge1X * edge2Y
                                - edge1Y * edge2X;

                double normalLength2 =
                        rawNormalX * rawNormalX
                                + rawNormalY * rawNormalY
                                + rawNormalZ * rawNormalZ;

                if (normalLength2 <= TRI_EPS2) {
                    continue;
                }

                int base = triangle * 9;

                xyz[base] = a[0];
                xyz[base + 1] = a[1];
                xyz[base + 2] = a[2];

                xyz[base + 3] = b[0];
                xyz[base + 4] = b[1];
                xyz[base + 5] = b[2];

                xyz[base + 6] = c[0];
                xyz[base + 7] = c[1];
                xyz[base + 8] = c[2];

                minX[triangle] = Math.min(
                        a[0],
                        Math.min(b[0], c[0])
                );

                maxX[triangle] = Math.max(
                        a[0],
                        Math.max(b[0], c[0])
                );

                minY[triangle] = Math.min(
                        a[1],
                        Math.min(b[1], c[1])
                );

                maxY[triangle] = Math.max(
                        a[1],
                        Math.max(b[1], c[1])
                );

                minZ[triangle] = Math.min(
                        a[2],
                        Math.min(b[2], c[2])
                );

                maxZ[triangle] = Math.max(
                        a[2],
                        Math.max(b[2], c[2])
                );

                double inverseNormalLength =
                        1.0 / Math.sqrt(normalLength2);

                double nx =
                        rawNormalX * inverseNormalLength;
                double ny =
                        rawNormalY * inverseNormalLength;
                double nz =
                        rawNormalZ * inverseNormalLength;

                normalX[triangle] = nx;
                normalY[triangle] = ny;
                normalZ[triangle] = nz;

                planeD[triangle] = -(
                        nx * a[0]
                                + ny * a[1]
                                + nz * a[2]
                );

                double denominator =
                        (b[2] - c[2]) * (a[0] - c[0])
                                + (c[0] - b[0])
                                * (a[2] - c[2]);

                inverseXzDenominator[triangle] =
                        Math.abs(denominator) <= TRI_EPS
                                ? Double.NaN
                                : 1.0 / denominator;

                byte triangleFlags = 0;

                if (Math.abs(ny) <= MAX_WALL_NORMAL_Y) {
                    triangleFlags |= TRI_WALL;
                }

                if (ny >= MIN_WALKABLE_NORMAL_Y) {
                    triangleFlags |= TRI_FLOOR;
                } else if (
                        ny <= -MIN_WALKABLE_NORMAL_Y
                ) {
                    triangleFlags |= TRI_CEILING;
                }

                flags[triangle] = triangleFlags;
                triangle++;
            }

            return new MeshCollider(
                    vertices,
                    faces,
                    sourceBounds,
                    revision,
                    probe,
                    validCount,
                    xyz,
                    minX,
                    maxX,
                    minY,
                    maxY,
                    minZ,
                    maxZ,
                    normalX,
                    normalY,
                    normalZ,
                    planeD,
                    inverseXzDenominator,
                    flags
            );
        }

        boolean isCurrent(
                double[][] vertices,
                int[][] faces,
                AABB bounds,
                long revision,
                long probe
        ) {
            if (
                    sourceVertices != vertices
                            || sourceFaces != faces
                            || sourceRevision != revision
                            || hasSourceBounds != (bounds != null)
            ) {
                return false;
            }

            if (
                    bounds != null
                            && (
                            sourceMinX != bounds.minX
                                    || sourceMaxX != bounds.maxX
                                    || sourceMinY != bounds.minY
                                    || sourceMaxY != bounds.maxY
                                    || sourceMinZ != bounds.minZ
                                    || sourceMaxZ != bounds.maxZ
                    )
            ) {
                return false;
            }

            return revision != NO_GEOMETRY_REVISION
                    || sourceProbe == probe;
        }

        private static boolean isDegenerate(
                double[] a,
                double[] b,
                double[] c
        ) {
            double edge1X = b[0] - a[0];
            double edge1Y = b[1] - a[1];
            double edge1Z = b[2] - a[2];

            double edge2X = c[0] - a[0];
            double edge2Y = c[1] - a[1];
            double edge2Z = c[2] - a[2];

            double normalX =
                    edge1Y * edge2Z
                            - edge1Z * edge2Y;

            double normalY =
                    edge1Z * edge2X
                            - edge1X * edge2Z;

            double normalZ =
                    edge1X * edge2Y
                            - edge1Y * edge2X;

            return normalX * normalX
                    + normalY * normalY
                    + normalZ * normalZ
                    <= TRI_EPS2;
        }
    }

    private static final class MeshBvh {

        final int[] order;

        final double[] minX;
        final double[] maxX;
        final double[] minY;
        final double[] maxY;
        final double[] minZ;
        final double[] maxZ;

        final int[] left;
        final int[] right;
        final int[] start;
        final int[] count;

        int nodeCount;

        private MeshBvh(int[] order) {
            this.order = order;

            int capacity = Math.max(1, order.length * 2);

            minX = new double[capacity];
            maxX = new double[capacity];
            minY = new double[capacity];
            maxY = new double[capacity];
            minZ = new double[capacity];
            maxZ = new double[capacity];

            left = new int[capacity];
            right = new int[capacity];
            start = new int[capacity];
            count = new int[capacity];
        }

        static MeshBvh build(
                MeshCollider collider,
                byte requiredFlag
        ) {
            int selectedCount = 0;

            for (
                    int triangle = 0;
                    triangle < collider.triangleCount;
                    triangle++
            ) {
                if (
                        requiredFlag == 0
                                || (
                                collider.flags[triangle]
                                        & requiredFlag
                        ) != 0
                ) {
                    selectedCount++;
                }
            }

            if (selectedCount == 0) {
                return null;
            }

            int[] order = new int[selectedCount];
            int position = 0;

            for (
                    int triangle = 0;
                    triangle < collider.triangleCount;
                    triangle++
            ) {
                if (
                        requiredFlag == 0
                                || (
                                collider.flags[triangle]
                                        & requiredFlag
                        ) != 0
                ) {
                    order[position++] = triangle;
                }
            }

            MeshBvh bvh = new MeshBvh(order);
            bvh.buildNode(collider, 0, selectedCount);

            return bvh;
        }

        private int buildNode(
                MeshCollider collider,
                int rangeStart,
                int rangeEnd
        ) {
            int node = nodeCount++;

            double nodeMinX = Double.POSITIVE_INFINITY;
            double nodeMaxX = Double.NEGATIVE_INFINITY;
            double nodeMinY = Double.POSITIVE_INFINITY;
            double nodeMaxY = Double.NEGATIVE_INFINITY;
            double nodeMinZ = Double.POSITIVE_INFINITY;
            double nodeMaxZ = Double.NEGATIVE_INFINITY;

            double centerMinX = Double.POSITIVE_INFINITY;
            double centerMaxX = Double.NEGATIVE_INFINITY;
            double centerMinY = Double.POSITIVE_INFINITY;
            double centerMaxY = Double.NEGATIVE_INFINITY;
            double centerMinZ = Double.POSITIVE_INFINITY;
            double centerMaxZ = Double.NEGATIVE_INFINITY;

            for (int i = rangeStart; i < rangeEnd; i++) {
                int triangle = order[i];

                nodeMinX = Math.min(
                        nodeMinX,
                        collider.minX[triangle]
                );

                nodeMaxX = Math.max(
                        nodeMaxX,
                        collider.maxX[triangle]
                );

                nodeMinY = Math.min(
                        nodeMinY,
                        collider.minY[triangle]
                );

                nodeMaxY = Math.max(
                        nodeMaxY,
                        collider.maxY[triangle]
                );

                nodeMinZ = Math.min(
                        nodeMinZ,
                        collider.minZ[triangle]
                );

                nodeMaxZ = Math.max(
                        nodeMaxZ,
                        collider.maxZ[triangle]
                );

                double centerX =
                        collider.minX[triangle]
                                + collider.maxX[triangle];

                double centerY =
                        collider.minY[triangle]
                                + collider.maxY[triangle];

                double centerZ =
                        collider.minZ[triangle]
                                + collider.maxZ[triangle];

                centerMinX = Math.min(centerMinX, centerX);
                centerMaxX = Math.max(centerMaxX, centerX);
                centerMinY = Math.min(centerMinY, centerY);
                centerMaxY = Math.max(centerMaxY, centerY);
                centerMinZ = Math.min(centerMinZ, centerZ);
                centerMaxZ = Math.max(centerMaxZ, centerZ);
            }

            minX[node] = nodeMinX;
            maxX[node] = nodeMaxX;
            minY[node] = nodeMinY;
            maxY[node] = nodeMaxY;
            minZ[node] = nodeMinZ;
            maxZ[node] = nodeMaxZ;

            int triangleCount = rangeEnd - rangeStart;

            if (triangleCount <= BVH_LEAF_TRIANGLES) {
                start[node] = rangeStart;
                count[node] = triangleCount;
                left[node] = -1;
                right[node] = -1;

                return node;
            }

            double extentX = centerMaxX - centerMinX;
            double extentY = centerMaxY - centerMinY;
            double extentZ = centerMaxZ - centerMinZ;

            int axis =
                    extentX >= extentY && extentX >= extentZ
                            ? 0
                            : extentY >= extentZ
                            ? 1
                            : 2;

            double selectedExtent =
                    axis == 0
                            ? extentX
                            : axis == 1
                            ? extentY
                            : extentZ;

            if (selectedExtent <= TRI_EPS) {
                start[node] = rangeStart;
                count[node] = triangleCount;
                left[node] = -1;
                right[node] = -1;

                return node;
            }

            sortByCenter(
                    collider,
                    order,
                    rangeStart,
                    rangeEnd - 1,
                    axis
            );

            int middle = (rangeStart + rangeEnd) >>> 1;

            left[node] = buildNode(
                    collider,
                    rangeStart,
                    middle
            );

            right[node] = buildNode(
                    collider,
                    middle,
                    rangeEnd
            );

            start[node] = 0;
            count[node] = 0;

            return node;
        }

        private static void sortByCenter(
                MeshCollider collider,
                int[] order,
                int low,
                int high,
                int axis
        ) {
            int left = low;
            int right = high;

            double pivot = center(
                    collider,
                    order[(low + high) >>> 1],
                    axis
            );

            while (left <= right) {
                while (
                        center(
                                collider,
                                order[left],
                                axis
                        ) < pivot
                ) {
                    left++;
                }

                while (
                        center(
                                collider,
                                order[right],
                                axis
                        ) > pivot
                ) {
                    right--;
                }

                if (left <= right) {
                    int temporary = order[left];
                    order[left] = order[right];
                    order[right] = temporary;

                    left++;
                    right--;
                }
            }

            if (low < right) {
                sortByCenter(
                        collider,
                        order,
                        low,
                        right,
                        axis
                );
            }

            if (left < high) {
                sortByCenter(
                        collider,
                        order,
                        left,
                        high,
                        axis
                );
            }
        }

        private static double center(
                MeshCollider collider,
                int triangle,
                int axis
        ) {
            if (axis == 0) {
                return collider.minX[triangle]
                        + collider.maxX[triangle];
            }

            if (axis == 1) {
                return collider.minY[triangle]
                        + collider.maxY[triangle];
            }

            return collider.minZ[triangle]
                    + collider.maxZ[triangle];
        }
    }
}