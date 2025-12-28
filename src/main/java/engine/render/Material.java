package engine.render;

import java.awt.Color;

public final class Material {

    private Texture albedo;     // can be null => solid color
    private Texture.Wrap wrap = Texture.Wrap.REPEAT;

    private Color tint = Color.WHITE;

    // lighting
    private double ambient = 0.20;  // 0..1
    private double diffuse = 0.85;  // 0..1

    public Material() {}

    public static Material solid(Color c) {
        Material m = new Material();
        m.tint = (c == null ? Color.WHITE : c);
        return m;
    }

    public static Material textured(Texture t) {
        Material m = new Material();
        m.albedo = t;
        return m;
    }

    public Texture getAlbedo() { return albedo; }
    public Material setAlbedo(Texture albedo) { this.albedo = albedo; return this; }

    public Texture.Wrap getWrap() { return wrap; }
    public Material setWrap(Texture.Wrap wrap) { this.wrap = (wrap == null ? Texture.Wrap.REPEAT : wrap); return this; }

    public Color getTint() { return tint; }
    public Material setTint(Color tint) { this.tint = (tint == null ? Color.WHITE : tint); return this; }

    public double getAmbient() { return ambient; }
    public Material setAmbient(double ambient) {
        this.ambient = clamp01(ambient);
        return this;
    }

    public double getDiffuse() { return diffuse; }
    public Material setDiffuse(double diffuse) {
        this.diffuse = clamp01(diffuse);
        return this;
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }
}
