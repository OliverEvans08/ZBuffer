package objects.lighting;

import engine.lighting.LightData;
import engine.lighting.LightType;
import objects.GameObject;
import util.Matrix4;
import util.Vector3;

import java.awt.Color;

public class LightObject extends GameObject {

    private final LightData light;

    // Temp arrays to avoid allocations
    private final double[] tmpPos = new double[3];
    private final double[] tmpDir = new double[3];

    // The light's direction in LOCAL space. World direction is computed from world transform each getLight().
    private Vector3 localDirection = new Vector3(0, -1, 0);

    // Optional: slowly rotate around Y (360° cycle)
    private boolean autoRotateY = false;
    private double autoRotateYSpeedRad = 0.0;

    private LightObject(LightData light) {
        this.light = (light == null ? new LightData() : light);
        this.light.owner = this;

        // Lights are not colliders
        setFull(false);

        // Lights don't render as geometry
        setVisible(false);

        // Initialize local direction from current light data (if applicable)
        if (this.light.type == LightType.DIRECTIONAL || this.light.type == LightType.SPOT) {
            this.localDirection = normalizeSafe(new Vector3(this.light.dx, this.light.dy, this.light.dz));
        }
    }

    public static LightObject directional(Vector3 raysDirection, Color color, double strength, boolean shadows) {
        LightData d = LightData.directional(raysDirection, color, strength, shadows, null);
        return new LightObject(d);
    }

    public static LightObject point(Vector3 position, Color color,
                                    double strength, double range,
                                    double attLinear, double attQuadratic,
                                    boolean shadows) {
        LightData d = LightData.point(position, color, strength, range, attLinear, attQuadratic, shadows, null);
        return new LightObject(d);
    }

    public static LightObject spot(Vector3 position, Vector3 raysDirection,
                                   double innerAngleRad, double outerAngleRad,
                                   Color color, double strength, double range,
                                   double attLinear, double attQuadratic,
                                   boolean shadows) {
        LightData d = LightData.spot(position, raysDirection, innerAngleRad, outerAngleRad,
                color, strength, range, attLinear, attQuadratic, shadows, null);
        return new LightObject(d);
    }

    /**
     * Requirement #1:
     * Update dx/dy/dz for directional (and spot) lights EVERY time getLight() is called.
     * This computes the world direction from the object's world transform and localDirection.
     */
    public LightData getLight() {
        syncWorldStateEveryCall();
        return light;
    }

    private void syncWorldStateEveryCall() {
        Matrix4 w = null;

        // Position sync for POINT/SPOT
        if (light.type == LightType.POINT || light.type == LightType.SPOT) {
            w = getWorldTransform();
            w.transformPoint(0, 0, 0, tmpPos);
            light.x = tmpPos[0];
            light.y = tmpPos[1];
            light.z = tmpPos[2];
        }

        // Direction sync for DIRECTIONAL/SPOT (EVERY getLight() call)
        if (light.type == LightType.DIRECTIONAL || light.type == LightType.SPOT) {
            if (w == null) w = getWorldTransform();

            // Transform local direction by world matrix (ignore translation)
            w.transformDirection(localDirection.x, localDirection.y, localDirection.z, tmpDir);

            double lx = tmpDir[0], ly = tmpDir[1], lz = tmpDir[2];
            double L = Math.sqrt(lx * lx + ly * ly + lz * lz);

            if (L < 1e-12) {
                light.dx = 0.0;
                light.dy = -1.0;
                light.dz = 0.0;
            } else {
                light.dx = lx / L;
                light.dy = ly / L;
                light.dz = lz / L;
            }
        }
    }

    public LightObject setDirection(Vector3 raysDirection) {
        // Store as local direction (normalized) so transform rotation can affect it
        this.localDirection = normalizeSafe(raysDirection);
        light.setDirection(this.localDirection);
        return this;
    }

    /**
     * Requirement #2 helper:
     * Rotate the light around Y forever at the given speed (radians/sec).
     * Example: 2π/60 ≈ 0.1047 for a 60-second full rotation.
     */
    public LightObject setAutoRotateY(double radiansPerSecond) {
        this.autoRotateY = true;
        this.autoRotateYSpeedRad = radiansPerSecond;
        return this;
    }

    public LightObject clearAutoRotateY() {
        this.autoRotateY = false;
        this.autoRotateYSpeedRad = 0.0;
        return this;
    }

    private static Vector3 normalizeSafe(Vector3 v) {
        if (v == null) return new Vector3(0, -1, 0);
        double x = v.x, y = v.y, z = v.z;
        double L = Math.sqrt(x * x + y * y + z * z);
        if (L < 1e-12) return new Vector3(0, -1, 0);
        return new Vector3(x / L, y / L, z / L);
    }

    @Override
    public LightObject setColor(Color c) {
        light.setColor(c);
        return this;
    }

    public LightObject setStrength(double s) {
        light.strength = Math.max(0.0, s);
        return this;
    }

    public LightObject setRange(double r) {
        light.range = Math.max(0.0, r);
        return this;
    }

    public LightObject setAttenuation(double linear, double quadratic) {
        light.attLinear = Math.max(0.0, linear);
        light.attQuadratic = Math.max(0.0, quadratic);
        return this;
    }

    public LightObject setSpotAngles(double innerRad, double outerRad) {
        light.setSpotAngles(innerRad, outerRad);
        return this;
    }

    public LightObject setShadows(boolean enabled) {
        light.shadows = enabled;
        return this;
    }

    @Override public double[][] getVertices() { return new double[0][0]; }
    @Override public int[][] getEdges() { return new int[0][]; }
    @Override public int[][] getFacesArray() { return null; }

    /**
     * Drives the slow 360° cycle when enabled.
     */
    @Override
    public void update(double delta) {
        if (autoRotateY && Math.abs(autoRotateYSpeedRad) > 1e-12) {
            transform.rotation.y += autoRotateYSpeedRad * delta;

            // Keep it bounded (0..2π)
            double twoPi = Math.PI * 2.0;
            transform.rotation.y %= twoPi;
            if (transform.rotation.y < 0) transform.rotation.y += twoPi;
        }
    }
}
