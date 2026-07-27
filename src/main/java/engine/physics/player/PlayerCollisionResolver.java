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
import static engine.physics.player.PlayerCapsule.MAX_STEP_DOWN;
import static engine.physics.player.PlayerCapsule.MAX_STEP_UP;
import static engine.physics.player.PlayerCapsule.RADIUS;
import static engine.physics.player.PlayerCapsule.RADIUS2;

public final class PlayerCollisionResolver {

    private static final double STEP_PROGRESS_EPS = 1e-6;
    private static final double STEP_RANGE_EPS = 1e-6;

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

    /**
     * Resolves requested ground movement and retries a blocked move from a
     * bounded raised position. The raised result is accepted only when it
     * makes more progress and lands on a floor inside the configured step
     * range. The vertical pass handles the matching step-down probe.
     */
    public void resolveGroundMovement(
            Camera camera,
            double previousX,
            double previousY,
            double previousZ,
            boolean wasOnGround
    ) {
        double requestedX = camera.x;
        double requestedZ = camera.z;

        horizontalResolver.resolve(
                camera,
                previousX,
                previousZ
        );

        if (
                !wasOnGround
                        || camera.yVelocity > 0.0
        ) {
            return;
        }

        double requestX = requestedX - previousX;
        double requestZ = requestedZ - previousZ;
        double requestedDistance2 =
                requestX * requestX + requestZ * requestZ;

        if (requestedDistance2 <= STEP_PROGRESS_EPS) {
            return;
        }

        double normalX = camera.x;
        double normalZ = camera.z;
        double normalY = camera.y;
        double normalVelocity = camera.yVelocity;
        boolean normalOnGround = camera.onGround;

        double normalProgress = movementProgress(
                previousX,
                previousZ,
                normalX,
                normalZ,
                requestX,
                requestZ,
                requestedDistance2
        );

        boolean steppedUp = false;

        /*
         * Do the more expensive raised retry only if ordinary horizontal
         * collision materially shortened the requested move.
         */
        if (normalProgress < 1.0 - STEP_PROGRESS_EPS) {
            camera.x = requestedX;
            camera.y = previousY + MAX_STEP_UP;
            camera.z = requestedZ;
            camera.yVelocity = 0.0;
            camera.onGround = false;

            horizontalResolver.resolve(
                    camera,
                    previousX,
                    previousZ
            );

            double raisedProgress = movementProgress(
                    previousX,
                    previousZ,
                    camera.x,
                    camera.z,
                    requestX,
                    requestZ,
                    requestedDistance2
            );

            if (
                    raisedProgress
                            > normalProgress + STEP_PROGRESS_EPS
            ) {
                double raisedY = camera.y;

                camera.y = previousY - MAX_STEP_DOWN;

                verticalResolver.resolve(
                        camera,
                        previousX,
                        raisedY,
                        previousZ
                );

                double landedY = camera.y;

                if (
                        camera.onGround
                                && landedY
                                <= previousY
                                + MAX_STEP_UP
                                + STEP_RANGE_EPS
                                && landedY
                                >= previousY
                                - MAX_STEP_DOWN
                                - STEP_RANGE_EPS
                                && hasStepUpHeadroom(
                                camera,
                                previousY,
                                landedY
                        )
                ) {
                    camera.y = landedY;
                    camera.yVelocity = 0.0;
                    camera.onGround = true;
                    steppedUp = true;
                }
            }
        }

        if (steppedUp) {
            return;
        }

        camera.x = normalX;
        camera.y = normalY;
        camera.z = normalZ;
        camera.yVelocity = normalVelocity;
        camera.onGround = normalOnGround;
        verticalResolver.clearFloorCache();
    }

    private boolean hasStepUpHeadroom(
            Camera camera,
            double previousY,
            double landedY
    ) {
        if (landedY <= previousY + STEP_PROGRESS_EPS) {
            return true;
        }

        boolean landedOnGround = camera.onGround;

        /*
         * Reuse the upward vertical sweep to reject a step that would put
         * the capsule through a low ceiling.
         */
        camera.y = landedY;
        verticalResolver.resolve(
                camera,
                camera.x,
                previousY,
                camera.z
        );

        boolean clear =
                camera.y >= landedY - STEP_PROGRESS_EPS;

        camera.y = landedY;
        camera.onGround = landedOnGround;

        return clear;
    }

    private static double movementProgress(
            double previousX,
            double previousZ,
            double resolvedX,
            double resolvedZ,
            double requestX,
            double requestZ,
            double requestedDistance2
    ) {
        return (
                (resolvedX - previousX) * requestX
                        + (resolvedZ - previousZ) * requestZ
        ) / requestedDistance2;
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

    public void resolveVertical(
            Camera camera,
            double previousX,
            double previousY,
            double previousZ,
            double groundedSnapDown
    ) {
        verticalResolver.resolve(
                camera,
                previousX,
                previousY,
                previousZ,
                groundedSnapDown
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