package engine.camera;

public final class CameraBasis {

    private double cachedYaw = Double.NaN;
    private double cachedPitch = Double.NaN;
    private boolean cached;

    private double cy;
    private double sy;
    private double cp;
    private double sp;
    private double forwardX;
    private double forwardY;
    private double forwardZ;
    private double rightX;
    private double rightY;
    private double rightZ;

    public void updateIfNeeded(double yaw, double pitch) {
        if (!cached || yaw != cachedYaw || pitch != cachedPitch) {
            cachedYaw = yaw;
            cachedPitch = pitch;

            cy = Math.cos(cachedYaw);
            sy = Math.sin(cachedYaw);
            cp = Math.cos(cachedPitch);
            sp = Math.sin(cachedPitch);

            forwardX = -cp * sy;
            forwardY = -sp;
            forwardZ = cp * cy;

            rightX = cy;
            rightY = 0.0;
            rightZ = sy;

            cached = true;
        }
    }

    public double getCosYaw() {
        return cy;
    }

    public double getSinYaw() {
        return sy;
    }

    public double getForwardX() {
        return forwardX;
    }

    public double getForwardY() {
        return forwardY;
    }

    public double getForwardZ() {
        return forwardZ;
    }

    public double getRightX() {
        return rightX;
    }

    public double getRightY() {
        return rightY;
    }

    public double getRightZ() {
        return rightZ;
    }
}