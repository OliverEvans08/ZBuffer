package engine.physics.collision;

import util.AABB;

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