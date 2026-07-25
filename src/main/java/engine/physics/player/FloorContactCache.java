package engine.physics.player;

import engine.physics.collision.MeshCollider;
import objects.GameObject;

public final class FloorContactCache {

    GameObject object;
    MeshCollider mesh;
    int triangle = -1;
    boolean aabb;
    double height = Double.NEGATIVE_INFINITY;

    public void clear() {
        object = null;
        mesh = null;
        triangle = -1;
        aabb = false;
        height = Double.NEGATIVE_INFINITY;
    }
}