package objects;

import engine.MeshData;

/**
 * Simple renderable GameObject that draws a MeshData.
 * You can spawn many MeshObjects that all reference the same MeshData.
 */
public final class MeshObject extends GameObject {

    public MeshObject(MeshData mesh) {
        setMesh(mesh);
        // Most imported meshes are typically "not full" (double-sided) unless you want occlusion/colliders.
        setFull(false);
    }

    @Override
    public void update(double delta) {
        // No behavior by default.
    }
}
