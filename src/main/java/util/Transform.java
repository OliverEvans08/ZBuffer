package util;

public class Transform {
    public Vector3 position;
    public Vector3 rotation;
    public Vector3 scale;

    // Cached matrix to avoid allocations every frame
    private final Matrix4 cached = new Matrix4();
    private boolean cachedValid = false;

    private double lpX, lpY, lpZ;
    private double lrX, lrY, lrZ;
    private double lsX, lsY, lsZ;

    public Transform() {
        this.position = new Vector3(0, 0, 0);
        this.rotation = new Vector3(0, 0, 0);
        this.scale = new Vector3(1, 1, 1);
    }

    public Matrix4 getTransformationMatrix() {
        if (!cachedValid ||
                position.x != lpX || position.y != lpY || position.z != lpZ ||
                rotation.x != lrX || rotation.y != lrY || rotation.z != lrZ ||
                scale.x != lsX || scale.y != lsY || scale.z != lsZ) {

            cached.setTRS(position, rotation, scale);

            lpX = position.x; lpY = position.y; lpZ = position.z;
            lrX = rotation.x; lrY = rotation.y; lrZ = rotation.z;
            lsX = scale.x;    lsY = scale.y;    lsZ = scale.z;

            cachedValid = true;
        }
        return cached;
    }
}
