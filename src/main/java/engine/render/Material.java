package engine.render;

import java.awt.Color;

public final class Material {

    private Texture albedo;
    private Texture.Wrap wrap = Texture.Wrap.REPEAT;

    private Color tint = Color.WHITE;

    // Base lighting controls (existing)
    private double ambient = 0.20;  // 0..1
    private double diffuse = 0.85;  // 0..1

    // NEW: emissive (glow + optional light source)
    private Color emissiveColor = new Color(0, 0, 0);
    private double emissiveStrength = 0.0; // can be >1 if you want hotter glow
    private double emissiveRange = 0.0;    // if > 0, material also emits point light into the world

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
    public Material setWrap(Texture.Wrap wrap) {
        this.wrap = (wrap == null ? Texture.Wrap.REPEAT : wrap);
        return this;
    }

    public Color getTint() { return tint; }
    public Material setTint(Color tint) {
        this.tint = (tint == null ? Color.WHITE : tint);
        return this;
    }

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

    // ---------------------------
    // NEW: Emissive controls
    // ---------------------------

    /** Color of emitted light / glow. */
    public Color getEmissiveColor() { return emissiveColor; }

    public Material setEmissiveColor(Color emissiveColor) {
        this.emissiveColor = (emissiveColor == null ? new Color(0,0,0) : emissiveColor);
        return this;
    }

    /** How strongly it glows (and how strong its emitted point light is, if range > 0). */
    public double getEmissiveStrength() { return emissiveStrength; }

    public Material setEmissiveStrength(double emissiveStrength) {
        this.emissiveStrength = Math.max(0.0, emissiveStrength);
        return this;
    }

    /**
     * If > 0, the material also becomes a point light source (centered at the object).
     * If 0, it can still glow visually (emissiveStrength + emissiveColor) but won't light other objects.
     */
    public double getEmissiveRange() { return emissiveRange; }

    public Material setEmissiveRange(double emissiveRange) {
        this.emissiveRange = Math.max(0.0, emissiveRange);
        return this;
    }

    /** Convenience: set all emissive properties at once. */
    public Material setEmissive(Color color, double strength, double range) {
        return setEmissiveColor(color).setEmissiveStrength(strength).setEmissiveRange(range);
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }
}
