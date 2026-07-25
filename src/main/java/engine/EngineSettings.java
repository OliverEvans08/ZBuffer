package engine;

public final class EngineSettings {

    public static final int WIDTH = 1920;
    public static final int HEIGHT = 1080;

    public static final double COLLIDER_CELL_SIZE = 6.0;
    public static final double EDGE_EPSILON = 1.0e-9;

    public static final int VERTEX_PARALLEL_THRESHOLD = 4096;
    public static final int VERTEX_PARALLEL_CHUNK = 2048;
    public static final int DERIVED_PARALLEL_THRESHOLD = 64;

    public static final long MINIMUM_REPAINT_NANOSECONDS = 1_000_000L;
    public static final long IDLE_PARK_NANOSECONDS = 250_000L;

    private double fieldOfViewDegrees = 70.0;
    private double fieldOfViewRadians = Math.toRadians(fieldOfViewDegrees);
    private double renderDistance = 200.0;

    public double getFieldOfViewDegrees() {
        return fieldOfViewDegrees;
    }

    public double getFieldOfViewRadians() {
        return fieldOfViewRadians;
    }

    public void setFieldOfViewDegrees(double degrees) {
        if (Double.isFinite(degrees)) {
            fieldOfViewDegrees = Math.max(30.0, Math.min(120.0, degrees));
            fieldOfViewRadians = Math.toRadians(fieldOfViewDegrees);
        }
    }

    public double getRenderDistance() {
        return Math.max(5.0, renderDistance);
    }

    public void setRenderDistance(double distance) {
        if (Double.isFinite(distance)) {
            renderDistance = Math.max(5.0, distance);
        }
    }
}