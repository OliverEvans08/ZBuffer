package objects.lighting;

import engine.lighting.LightData;
import engine.lighting.LightType;
import objects.GameObject;
import util.Matrix4;
import util.Vector3;

import java.awt.Color;

public class LightObject extends GameObject {

    private final LightData light;
    private final double[] tmp = new double[3];

    private LightObject(LightData light) {
        this.light = (light == null ? new LightData() : light);
        this.light.owner = this;

        // Not a solid occluder
        setFull(false);

        // Lights are invisible geometry
        setVisible(false);
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

    public LightData getLight() {
        syncWorldPositionIfNeeded();
        return light;
    }

    private void syncWorldPositionIfNeeded() {
        if (light.type == LightType.POINT || light.type == LightType.SPOT) {
            Matrix4 w = getWorldTransform();
            w.transformPoint(0, 0, 0, tmp);
            light.x = tmp[0];
            light.y = tmp[1];
            light.z = tmp[2];
        }
    }

    public LightObject setDirection(Vector3 raysDirection) {
        light.setDirection(raysDirection);
        return this;
    }

    // ✅ Now valid because GameObject#setColor returns GameObject (covariant override allowed)
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
    @Override public void update(double delta) { }
}
