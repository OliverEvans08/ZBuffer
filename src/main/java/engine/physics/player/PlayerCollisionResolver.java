package engine.physics.player;

import engine.GameEngine;
import engine.camera.Camera;
import engine.physics.collision.MeshBvh;
import engine.physics.collision.MeshCollider;
import engine.physics.collision.MeshColliderCache;
import objects.GameObject;
import util.AABB;

import static engine.physics.collision.AabbCollisionQueries.intersectsPlayerCapsuleAabb;
import static engine.physics.collision.TriangleQueries.closestPointOnTriangleXZ;
import static engine.physics.player.PlayerCapsule.HEIGHT;
import static engine.physics.player.PlayerCapsule.RADIUS;
import static engine.physics.player.PlayerCapsule.RADIUS2;

public final class PlayerCollisionResolver {

    private final CollisionFilter collisionFilter;
    private final MeshColliderCache meshColliderCache;

    private final HorizontalCollisionResolver horizontalResolver;
    private final VerticalCollisionResolver verticalResolver;

    private final double[] closestXZ = new double[2];
    private int[] bvhStack = new int[64];

    public PlayerCollisionResolver(
            GameEngine gameEngine,
            CollisionFilter collisionFilter,
            MeshColliderCache meshColliderCache
    ) {
        this.collisionFilter = collisionFilter;
        this.meshColliderCache = meshColliderCache;

        horizontalResolver = new HorizontalCollisionResolver(
                gameEngine,
                collisionFilter,
                meshColliderCache
        );

        verticalResolver = new VerticalCollisionResolver(
                gameEngine,
                collisionFilter,
                meshColliderCache
        );
    }

    public void resolveHorizontal(
            Camera camera,
            double previousX,
            double previousZ
    ) {
        horizontalResolver.resolve(
                camera,
                previousX,
                previousZ
        );
    }

    public void resolveVertical(
            Camera camera,
            double previousX,
            double previousY,
            double previousZ
    ) {
        verticalResolver.resolve(
                camera,
                previousX,
                previousY,
                previousZ
        );
    }

    public void clearFloorCache() {
        verticalResolver.clearFloorCache();
    }

    public boolean collidesWith(
            Camera camera,
            GameObject object
    ) {
        if (object == null) {
            return false;
        }

        AABB bounds =
                collisionFilter.getCollisionBounds(object);

        if (
                bounds == null
                        || !intersectsPlayerCapsuleAabb(
                        camera,
                        bounds
                )
        ) {
            return false;
        }

        MeshCollider collider =
                meshColliderCache.get(object, bounds);

        return collider == null
                || capsuleOverlapsMesh(camera, collider);
    }

    private boolean capsuleOverlapsMesh(
            Camera camera,
            MeshCollider collider
    ) {
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
                    bvh.maxX[node] < camera.x - RADIUS
                            || bvh.minX[node]
                            > camera.x + RADIUS
                            || bvh.maxY[node] <= camera.y
                            || bvh.minY[node]
                            >= camera.y + HEIGHT
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
                            collider.maxY[triangle] <= camera.y
                                    || collider.minY[triangle]
                                    >= camera.y + HEIGHT
                                    || collider.maxX[triangle]
                                    < camera.x - RADIUS
                                    || collider.minX[triangle]
                                    > camera.x + RADIUS
                                    || collider.maxZ[triangle]
                                    < camera.z - RADIUS
                                    || collider.minZ[triangle]
                                    > camera.z + RADIUS
                    ) {
                        continue;
                    }

                    closestPointOnTriangleXZ(
                            collider,
                            triangle,
                            camera.x,
                            camera.z,
                            closestXZ
                    );

                    double offsetX =
                            camera.x - closestXZ[0];

                    double offsetZ =
                            camera.z - closestXZ[1];

                    if (
                            offsetX * offsetX
                                    + offsetZ * offsetZ
                                    <= RADIUS2
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