package objects;

import engine.assets.mesh.MeshAsset;

public final class MeshObject extends GameObject {

    public MeshObject(MeshAsset mesh) {
        setMeshAsset(mesh);
        setFull(false);
    }

    // Legacy constructor kept for migration
    public MeshObject(engine.MeshData mesh) {
        setMesh(mesh);
        setFull(false);
    }

    @Override
    public void update(double delta) {
        // no-op
    }
}