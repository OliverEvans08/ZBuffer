package util;

public class Transform {

    public Vector3 position;
    public Vector3 rotation;
    public Vector3 scale;

    private final Matrix4 cached = new Matrix4();
    private boolean cachedValid;

    private double lastPositionX;
    private double lastPositionY;
    private double lastPositionZ;

    private double lastRotationX;
    private double lastRotationY;
    private double lastRotationZ;

    private double lastScaleX;
    private double lastScaleY;
    private double lastScaleZ;

    private long version;

    public Transform() {
        position = new Vector3(0, 0, 0);
        rotation = new Vector3(0, 0, 0);
        scale = new Vector3(1, 1, 1);
    }

    private void synchronizeIfNeeded() {
        if (
                !cachedValid ||
                        position.x != lastPositionX ||
                        position.y != lastPositionY ||
                        position.z != lastPositionZ ||
                        rotation.x != lastRotationX ||
                        rotation.y != lastRotationY ||
                        rotation.z != lastRotationZ ||
                        scale.x != lastScaleX ||
                        scale.y != lastScaleY ||
                        scale.z != lastScaleZ
        ) {
            cached.setTRS(position, rotation, scale);

            lastPositionX = position.x;
            lastPositionY = position.y;
            lastPositionZ = position.z;

            lastRotationX = rotation.x;
            lastRotationY = rotation.y;
            lastRotationZ = rotation.z;

            lastScaleX = scale.x;
            lastScaleY = scale.y;
            lastScaleZ = scale.z;

            cachedValid = true;
            version++;
        }
    }

    public Matrix4 getTransformationMatrix() {
        synchronizeIfNeeded();

        return cached;
    }

    public long getVersion() {
        synchronizeIfNeeded();

        return version;
    }

    public long getCachedVersion() {
        return version;
    }

    public void invalidate() {
        cachedValid = false;
    }
}