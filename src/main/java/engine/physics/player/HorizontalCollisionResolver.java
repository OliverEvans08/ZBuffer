package engine.physics.player;

import engine.GameEngine;
import engine.camera.Camera;
import engine.physics.collision.MeshBvh;
import engine.physics.collision.MeshCollider;
import engine.physics.collision.MeshColliderCache;
import java.util.ArrayList;
import objects.GameObject;
import util.AABB;

import static engine.physics.collision.AabbCollisionQueries.resolveCapsuleAgainstAabb;
import static engine.physics.collision.CapsuleTriangleQueries.resolveCapsuleAgainstTriangle;
import static engine.physics.player.PlayerCapsule.HEIGHT;
import static engine.physics.player.PlayerCapsule.RADIUS;

public final class HorizontalCollisionResolver {

    private static final double HORIZONTAL_TRI_PAD = 0.02;

    private final GameEngine gameEngine;
    private final CollisionFilter collisionFilter;
    private final MeshColliderCache meshColliderCache;

    private final ArrayList<GameObject> nearby =
            new ArrayList<>(256);

    private final double[] closestXZ = new double[2];
    private int[] bvhStack = new int[64];

    public HorizontalCollisionResolver(
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
            double previousZ
    ) {
        if (gameEngine == null) {
            return;
        }

        double sweptMinimumX =
                Math.min(previousX, camera.x) - RADIUS;

        double sweptMaximumX =
                Math.max(previousX, camera.x) + RADIUS;

        double sweptMinimumZ =
                Math.min(previousZ, camera.z) - RADIUS;

        double sweptMaximumZ =
                Math.max(previousZ, camera.z) + RADIUS;

        gameEngine.queryNearbyCollidersXZ(
                sweptMinimumX,
                sweptMaximumX,
                sweptMinimumZ,
                sweptMaximumZ,
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
                            || bounds.maxX < sweptMinimumX
                            || bounds.minX > sweptMaximumX
                            || bounds.maxZ < sweptMinimumZ
                            || bounds.minZ > sweptMaximumZ
                            || camera.y + HEIGHT <= bounds.minY
                            || camera.y >= bounds.maxY
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

            if (collider != null && collider.wallBvh != null) {
                resolveHorizontalAgainstMesh(camera, collider);
            } else {
                resolveCapsuleAgainstAabb(camera, bounds);
            }
        }
    }

    private void resolveHorizontalAgainstMesh(
            Camera camera,
            MeshCollider collider
    ) {
        MeshBvh bvh = collider.wallBvh;

        ensureBvhStackCapacity(bvh.nodeCount);

        int stackSize = 0;
        bvhStack[stackSize++] = 0;

        while (stackSize > 0) {
            int node = bvhStack[--stackSize];

            if (
                    bvh.maxX[node] + HORIZONTAL_TRI_PAD
                            < camera.x - RADIUS
                            || bvh.minX[node]
                            - HORIZONTAL_TRI_PAD
                            > camera.x + RADIUS
                            || bvh.maxY[node]
                            + HORIZONTAL_TRI_PAD
                            <= camera.y
                            || bvh.minY[node]
                            - HORIZONTAL_TRI_PAD
                            >= camera.y + HEIGHT
                            || bvh.maxZ[node]
                            + HORIZONTAL_TRI_PAD
                            < camera.z - RADIUS
                            || bvh.minZ[node]
                            - HORIZONTAL_TRI_PAD
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
                            collider.maxX[triangle]
                                    + HORIZONTAL_TRI_PAD
                                    < camera.x - RADIUS
                                    || collider.minX[triangle]
                                    - HORIZONTAL_TRI_PAD
                                    > camera.x + RADIUS
                                    || collider.maxY[triangle]
                                    + HORIZONTAL_TRI_PAD
                                    <= camera.y
                                    || collider.minY[triangle]
                                    - HORIZONTAL_TRI_PAD
                                    >= camera.y + HEIGHT
                                    || collider.maxZ[triangle]
                                    + HORIZONTAL_TRI_PAD
                                    < camera.z - RADIUS
                                    || collider.minZ[triangle]
                                    - HORIZONTAL_TRI_PAD
                                    > camera.z + RADIUS
                    ) {
                        continue;
                    }

                    resolveCapsuleAgainstTriangle(
                            camera,
                            collider,
                            triangle,
                            closestXZ
                    );
                }
            } else {
                bvhStack[stackSize++] = bvh.left[node];
                bvhStack[stackSize++] = bvh.right[node];
            }
        }
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