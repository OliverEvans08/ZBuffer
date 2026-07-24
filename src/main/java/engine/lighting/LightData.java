package engine.lighting;

import objects.GameObject;
import util.Vector3;

import java.awt.Color;

public final class LightData {

    public volatile LightType type = LightType.POINT;

    // position
    public volatile double x, y, z;

    // direction
    public volatile double dx, dy, dz;

    // color (linear 0..1)
    public volatile double r = 1.0, g = 1.0, b = 1.0;

    public volatile double strength = 1.0;

    public volatile double range = 20.0;

    public volatile double attLinear = 0.0;
    public volatile double attQuadratic = 1.0;

    public volatile double innerCos = 0.95;
    public volatile double outerCos = 0.85;

    public volatile boolean shadows = true;

    public volatile GameObject owner;

    public LightData() {}

    public static LightData directional(Vector3 raysDirection, Color color, double strength, boolean shadows, GameObject owner) {
        LightData l = new LightData();
        l.type = LightType.DIRECTIONAL;
        l.setDirection(raysDirection);
        l.setColor(color);
        l.strength = Math.max(0.0, strength);
        l.shadows = shadows;
        l.owner = owner;
        return l;
    }

    public static LightData point(Vector3 pos, Color color, double strength,
                                  double range, double attLinear, double attQuadratic,
                                  boolean shadows, GameObject owner) {
        LightData l = new LightData();
        l.type = LightType.POINT;
        if (pos != null) { l.x = pos.x; l.y = pos.y; l.z = pos.z; }
        l.setColor(color);
        l.strength = Math.max(0.0, strength);
        l.range = Math.max(0.0, range);
        l.attLinear = Math.max(0.0, attLinear);
        l.attQuadratic = Math.max(0.0, attQuadratic);
        l.shadows = shadows;
        l.owner = owner;
        return l;
    }

    public static LightData spot(Vector3 pos, Vector3 raysDirection,
                                 double innerAngleRad, double outerAngleRad,
                                 Color color, double strength,
                                 double range, double attLinear, double attQuadratic,
                                 boolean shadows, GameObject owner) {
        LightData l = new LightData();
        l.type = LightType.SPOT;
        if (pos != null) { l.x = pos.x; l.y = pos.y; l.z = pos.z; }
        l.setDirection(raysDirection);
        l.setColor(color);
        l.strength = Math.max(0.0, strength);
        l.range = Math.max(0.0, range);
        l.attLinear = Math.max(0.0, attLinear);
        l.attQuadratic = Math.max(0.0, attQuadratic);
        l.setSpotAngles(innerAngleRad, outerAngleRad);
        l.shadows = shadows;
        l.owner = owner;
        return l;
    }

    public void setColor(Color c) {
        if (c == null) c = Color.WHITE;
        r = clamp01(c.getRed() / 255.0);
        g = clamp01(c.getGreen() / 255.0);
        b = clamp01(c.getBlue() / 255.0);
    }

    public void setDirection(Vector3 raysDirection) {
        if (raysDirection == null) {
            dx = 0; dy = -1; dz = 0;
            return;
        }
        double lx = raysDirection.x, ly = raysDirection.y, lz = raysDirection.z;
        double L = Math.sqrt(lx*lx + ly*ly + lz*lz);
        if (L < 1e-12) {
            dx = 0; dy = -1; dz = 0;
            return;
        }
        dx = lx / L;
        dy = ly / L;
        dz = lz / L;
    }

    public void setSpotAngles(double innerAngleRad, double outerAngleRad) {
        innerAngleRad = Math.max(0.0, innerAngleRad);
        outerAngleRad = Math.max(innerAngleRad, outerAngleRad);

        double ic = Math.cos(innerAngleRad);
        double oc = Math.cos(outerAngleRad);

        innerCos = Math.max(ic, oc);
        outerCos = Math.min(ic, oc);
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }
}
