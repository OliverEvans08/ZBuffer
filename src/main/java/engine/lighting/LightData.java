package engine.lighting;

import objects.GameObject;
import util.Vector3;

import java.awt.Color;

public final class LightData {

    public LightType type = LightType.POINT;

    // World position (POINT / SPOT)
    public double x, y, z;

    // World direction of light rays (DIRECTIONAL / SPOT), should be normalized.
    // This is the direction light travels (from light -> scene).
    public double dx, dy, dz;

    // Light color stored as 0..1 doubles
    public double r = 1.0, g = 1.0, b = 1.0;

    public double strength = 1.0;

    // POINT / SPOT range cutoff
    public double range = 20.0;

    // Attenuation: strength / (1 + linear*d + quadratic*d^2)
    public double attLinear = 0.0;
    public double attQuadratic = 1.0;

    // SPOT cone (cosines). innerCos >= outerCos.
    public double innerCos = 0.95;
    public double outerCos = 0.85;

    public boolean shadows = true;

    // Used to ignore self-shadowing for this light, etc.
    public GameObject owner;

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

        // innerCos should be >= outerCos
        innerCos = Math.max(ic, oc);
        outerCos = Math.min(ic, oc);
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }
}
