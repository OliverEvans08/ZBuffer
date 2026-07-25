package engine.physics.player;

import engine.GameEngine;
import engine.camera.Camera;
import engine.physics.collision.MeshBvh;
import engine.physics.collision.MeshCollider;
import engine.physics.collision.MeshColliderCache;
import java.util.ArrayList;
import objects.GameObject;
import util.AABB;

import static engine.physics.collision.AabbCollisionQueries.VERT_SNAP_EPS;
import static engine.physics.collision.AabbCollisionQueries.testAabbCeiling;
import static engine.physics.collision.AabbCollisionQueries.testAabbFloor;
import static engine.physics.collision.CapsuleTriangleQueries.capsuleSurfaceContact;
import static engine.physics.player.PlayerCapsule.HEIGHT;
import static engine.physics.player.PlayerCapsule.RADIUS;

public final class VerticalCollisionResolver {

    private static final double FLOOR_CACHE_TINY_MOVE2 =
            0.012 * 0.012;

    private final GameEngine gameEngine;
    private final CollisionFilter collisionFilter;
    private final MeshColliderCache meshColliderCache;

    private final ArrayList<GameObject> nearby =
            new ArrayList<>(256);

    private final double[] closestXZ = new double[2];
    private int[] bvhStack = new int[64];
    private int lastSurfaceTriangle = -1;

    private final FloorContactCache floorContactCache =
            new FloorContactCache();

    public VerticalCollisionResolver(
            GameEngine gameEngine,
            CollisionFilter collisionFilter,
            MeshColliderCache meshColliderCache
    ) {
        this.gameEngine = gameEngine;
        this.collisionFilter = collisionFilter;
        this.meshColliderCache = meshColliderCache;
    }

    public void resolve(
            Camera camera,
            double previousX,
            double previousY,
            double previousZ
    ) {
        if (gameEngine == null) {
            return;
        }

        boolean wasOnGround = camera.onGround;
        boolean movingDown = camera.y <= previousY;

        camera.onGround = false;

        double previousFeet = previousY;
        double previousHead = previousY + HEIGHT;
        double newFeet = camera.y;
        double newHead = camera.y + HEIGHT;

        double bestFloor = Double.NEGATIVE_INFINITY;
        double bestCeiling = Double.POSITIVE_INFINITY;

        GameObject bestFloorObject = null;
        MeshCollider bestFloorMesh = null;
        int bestFloorTriangle = -1;
        boolean bestFloorIsAabb = false;

        if (movingDown && floorContactCache.object != null) {
            AABB cachedBounds =
                    collisionFilter.getCollisionBounds(
                            floorContactCache.object
                    );

            if (
                    cachedBounds != null
                            && !collisionFilter
                            .shouldIgnoreColliderForPlayer(
                                    camera,
                                    floorContactCache.object,
                                    cachedBounds
                            )
            ) {
                if (floorContactCache.aabb) {
                    double floor = testAabbFloor(
                            camera.x,
                            camera.z,
                            cachedBounds,
                            previousFeet,
                            newFeet
                    );

                    if (Double.isFinite(floor)) {
                        bestFloor = floor;
                        bestFloorObject =
                                floorContactCache.object;

                        bestFloorIsAabb = true;
                    }
                } else {
                    MeshCollider current =
                            meshColliderCache.get(
                                    floorContactCache.object,
                                    cachedBounds
                            );

                    if (
                            current == floorContactCache.mesh
                                    && floorContactCache.triangle
                                    >= 0
                    ) {
                        double floor = capsuleSurfaceContact(
                                current,
                                floorContactCache.triangle,
                                true,
                                previousFeet,
                                newFeet,
                                camera.x,
                                camera.z,
                                closestXZ
                        );

                        if (Double.isFinite(floor)) {
                            bestFloor = floor;
                            bestFloorObject =
                                    floorContactCache.object;

                            bestFloorMesh = current;
                            bestFloorTriangle =
                                    floorContactCache.triangle;
                        }
                    }
                }
            }

            double horizontalMoveX = camera.x - previousX;
            double horizontalMoveZ = camera.z - previousZ;

            if (
                    bestFloor != Double.NEGATIVE_INFINITY
                            && wasOnGround
                            && horizontalMoveX * horizontalMoveX
                            + horizontalMoveZ * horizontalMoveZ
                            <= FLOOR_CACHE_TINY_MOVE2
                            && Math.abs(
                            previousFeet
                                    - floorContactCache.height
                    ) <= VERT_SNAP_EPS * 2.0
            ) {
                camera.y = bestFloor;
                camera.yVelocity = 0.0;
                camera.onGround = true;
                floorContactCache.height = bestFloor;
                return;
            }
        }

        if (!movingDown) {
            clearFloorCache();
        }

        double queryMinimumX =
                Math.min(previousX, camera.x) - RADIUS;

        double queryMaximumX =
                Math.max(previousX, camera.x) + RADIUS;

        double queryMinimumZ =
                Math.min(previousZ, camera.z) - RADIUS;

        double queryMaximumZ =
                Math.max(previousZ, camera.z) + RADIUS;

        gameEngine.queryNearbyCollidersXZ(
                queryMinimumX,
                queryMaximumX,
                queryMinimumZ,
                queryMaximumZ,
                nearby
        );

        for (int i = 0, count = nearby.size(); i < count; i++) {
            GameObject object = nearby.get(i);

            if (
                    object == null
                            || collisionFilter.isPlayerOrChild(object)
            ) {
                continue;
            }

            AABB bounds =
                    collisionFilter.getCollisionBounds(object);

            if (
                    bounds == null
                            || bounds.maxX < queryMinimumX
                            || bounds.minX > queryMaximumX
                            || bounds.maxZ < queryMinimumZ
                            || bounds.minZ > queryMaximumZ
                            || collisionFilter
                            .shouldIgnoreColliderForPlayer(
                                    camera,
                                    object,
                                    bounds
                            )
            ) {
                continue;
            }

            MeshCollider collider =
                    meshColliderCache.get(object, bounds);

            if (collider != null) {
                if (movingDown && collider.floorBvh != null) {
                    int skipTriangle =
                            object == bestFloorObject
                                    && collider == bestFloorMesh
                                    ? bestFloorTriangle
                                    : -1;

                    double floor = findBestCapsuleSurface(
                            camera,
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
                        bestFloorTriangle =
                                lastSurfaceTriangle;

                        bestFloorIsAabb = false;
                    }
                } else if (
                        !movingDown
                                && collider.ceilingBvh != null
                ) {
                    double ceiling = findBestCapsuleSurface(
                            camera,
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
                        camera.x,
                        camera.z,
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
                        camera.x,
                        camera.z,
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
                camera.y = bestFloor;
                camera.yVelocity = 0.0;
                camera.onGround = true;

                floorContactCache.object = bestFloorObject;
                floorContactCache.mesh = bestFloorMesh;
                floorContactCache.triangle = bestFloorTriangle;
                floorContactCache.aabb = bestFloorIsAabb;
                floorContactCache.height = bestFloor;
            } else {
                clearFloorCache();
            }
        } else if (bestCeiling != Double.POSITIVE_INFINITY) {
            camera.y = bestCeiling - HEIGHT;
            camera.yVelocity = 0.0;
        }
    }

    private double findBestCapsuleSurface(
            Camera camera,
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
                        - RADIUS
                        - VERT_SNAP_EPS;

        double maximumSweepY =
                Math.max(previousEnd, newEnd)
                        + RADIUS
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
                    bvh.maxX[node] < camera.x - RADIUS
                            || bvh.minX[node]
                            > camera.x + RADIUS
                            || bvh.maxY[node] < minimumSweepY
                            || bvh.minY[node] > maximumSweepY
                            || bvh.maxZ[node]
                            < camera.z - RADIUS
                            || bvh.minZ[node]
                            > camera.z + RADIUS
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
                                    < camera.x - RADIUS
                                    || collider.minX[triangle]
                                    > camera.x + RADIUS
                                    || collider.maxY[triangle]
                                    < minimumSweepY
                                    || collider.minY[triangle]
                                    > maximumSweepY
                                    || collider.maxZ[triangle]
                                    < camera.z - RADIUS
                                    || collider.minZ[triangle]
                                    > camera.z + RADIUS
                    ) {
                        continue;
                    }

                    double hit = capsuleSurfaceContact(
                            collider,
                            triangle,
                            floor,
                            previousEnd,
                            newEnd,
                            camera.x,
                            camera.z,
                            closestXZ
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

    public void clearFloorCache() {
        floorContactCache.clear();
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
}