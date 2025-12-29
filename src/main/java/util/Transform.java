package util;

public class Transform {
    public Vector3 position;
    public Vector3 rotation;
    public Vector3 scale;

    // Cached local TRS matrix
    private final Matrix4 cached = new Matrix4();
    private boolean cachedValid = false;

    // Last values used to build cached matrix
    private double lpX, lpY, lpZ;
    private double lrX, lrY, lrZ;
    private double lsX, lsY, lsZ;

    // Bumps when TRS changes (local-space)
    private long version = 0L;

    public Transform() {
        this.position = new Vector3(0, 0, 0);
        this.rotation = new Vector3(0, 0, 0);
        this.scale = new Vector3(1, 1, 1);
    }

    private void syncIfNeeded() {
        if (!cachedValid ||
                position.x != lpX || position.y != lpY || position.z != lpZ ||
                rotation.x != lrX || rotation.y != lrY || rotation.z != lrZ ||
                scale.x != lsX || scale.y != lsY || scale.z != lsZ) {

            cached.setTRS(position, rotation, scale);

            lpX = position.x; lpY = position.y; lpZ = position.z;
            lrX = rotation.x; lrY = rotation.y; lrZ = rotation.z;
            lsX = scale.x;    lsY = scale.y;    lsZ = scale.z;

            cachedValid = true;
            version++; // transform changed (or first build)
        }
    }

    public Matrix4 getTransformationMatrix() {
        syncIfNeeded();
        return cached;
    }

    /**
     * Returns a monotonically increasing value that changes whenever
     * position/rotation/scale changes (local-space).
     */
    public long getVersion() {
        syncIfNeeded();
        return version;
    }

    /**
     * Optional helper if you ever want to force a recompute next query.
     */
    public void invalidate() {
        cachedValid = false;
    }
}
