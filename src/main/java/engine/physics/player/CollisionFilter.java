package engine.physics.player;

import engine.GameEngine;
import engine.camera.Camera;
import engine.physics.collision.CollisionGeometryProvider;
import objects.GameObject;
import objects.dynamic.Body;
import util.AABB;

import static engine.physics.player.PlayerCapsule.HEIGHT;

public final class CollisionFilter {

    private static final double SELF_IGNORE_RADIUS = 1.25;
    private static final double SELF_IGNORE_RADIUS2 =
            SELF_IGNORE_RADIUS * SELF_IGNORE_RADIUS;
    private static final double SELF_IGNORE_MAX_W = 1.10;
    private static final double SELF_IGNORE_MAX_H = 1.25;

    private final GameEngine gameEngine;

    public CollisionFilter(GameEngine gameEngine) {
        this.gameEngine = gameEngine;
    }

    public boolean isPlayerOrChild(GameObject object) {
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

    public boolean shouldIgnoreColliderForPlayer(
            Camera camera,
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

        double offsetX = centerX - camera.x;
        double offsetY =
                centerY - (camera.y + HEIGHT * 0.5);

        double offsetZ = centerZ - camera.z;

        return offsetX * offsetX
                + offsetY * offsetY
                + offsetZ * offsetZ
                <= SELF_IGNORE_RADIUS2;
    }

    public AABB getCollisionBounds(GameObject object) {
        if (object instanceof CollisionGeometryProvider) {
            AABB bounds =
                    ((CollisionGeometryProvider) object)
                            .getCollisionAABB();

            if (bounds != null) {
                return bounds;
            }
        }

        return object.getWorldAABB();
    }
}